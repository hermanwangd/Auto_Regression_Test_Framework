"""Interrupt two Redis operation requests after a real write, then restore the same loopback route."""
import os
import socket
import socketserver
import subprocess
import sys
from threading import Thread
from time import monotonic


class RedisRoute(socketserver.StreamRequestHandler):
    timeout = 10

    def client_line(self):
        # Jedis can close with a TCP reset on both a successful request and a timeout.
        try:
            return self.rfile.readline(32)
        except ConnectionResetError:
            return b""

    def handle(self):
        with socket.create_connection(self.server.backend, timeout=5) as backend, backend.makefile("rb") as replies:
            # Jedis sends RESP2 arrays of bulk strings, including its startup commands.
            while header := self.client_line():
                if not header.startswith(b"*"):
                    raise ValueError("Expected Redis command array")
                request = bytearray(header)
                command = None
                count = int(header[1:])
                if not 1 <= count <= 32:
                    raise ValueError("Unexpected Redis argument count")
                for _ in range(count):
                    size = self.rfile.readline(32)
                    if not size.startswith(b"$"):
                        raise ValueError("Expected Redis bulk argument")
                    length = int(size[1:])
                    if not 0 <= length <= 65536:
                        raise ValueError("Unexpected Redis argument length")
                    argument = self.rfile.read(length + 2)
                    if len(argument) != length + 2 or not argument.endswith(b"\r\n"):
                        raise ValueError("Incomplete Redis bulk argument")
                    request.extend(size + argument)
                    if command is None:
                        command = argument[:-2].upper()

                if command == b"EVAL" and self.server.mode == "outage":
                    # Keep this connection open, withhold the command and let the client time out.
                    started = monotonic()
                    if self.client_line():
                        raise ValueError("Unexpected pipelined operation during outage")
                    self.server.stalls.append(monotonic() - started)
                    return

                backend.sendall(request)
                reply = replies.readline(4096)
                # Client setup and the fixture's integer-valued scripts have single-line replies.
                if not reply.startswith((b"+", b"-", b":")):
                    raise ValueError("Unexpected Redis response")
                if self.server.mode == "armed" and command == b"EVAL" and reply == b":1\r\n":
                    self.server.mode = "outage"
                self.wfile.write(reply)


def run(port, jar):
    with socketserver.TCPServer(("127.0.0.1", 0), RedisRoute) as route:
        route.backend = ("127.0.0.1", port)
        route.mode = "armed"
        route.stalls = []
        thread = Thread(target=route.serve_forever, daemon=True)
        thread.start()
        try:
            uri = f"redis://127.0.0.1:{route.server_address[1]}/0"
            result = subprocess.run(
                ["java", "-Xmx384m", "-jar", jar, "run", "--suite", "redis/suite_manifest.yaml",
                 "--profile", "external_redis"],
                env={**os.environ, "FRAMEWORK_REDIS_URI": uri}, timeout=45,
            )
            route.mode = "recovered"
            with socket.create_connection(route.server_address, timeout=5) as probe:
                # Exercise the same operation class that stalled during the outage.
                probe.sendall(b"*3\r\n$4\r\nEVAL\r\n$9\r\nreturn 42\r\n$1\r\n0\r\n")
                with probe.makefile("rb") as response:
                    if response.readline() != b":42\r\n":
                        raise RuntimeError("Redis route did not recover")
            # Processing the probe also synchronizes with the final timed-out connection.
            print(f"Redis read and cleanup socket waits (seconds): {route.stalls}", file=sys.stderr)
            if result.returncode != 1 or len(route.stalls) != 2 or not all(1 <= t <= 6 for t in route.stalls):
                raise RuntimeError("Expected two bounded client socket timeouts and CLI exit 1")
        finally:
            route.shutdown()
            thread.join()


if __name__ == "__main__":
    run(int(sys.argv[1]), sys.argv[2])
