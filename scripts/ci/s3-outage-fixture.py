"""Interrupt the runner's GET after MinIO acknowledges PUT. Restore the route for cleanup."""

import json
import os
import re
import socket
import subprocess
import sys
from contextlib import closing
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from threading import Thread
from urllib.parse import parse_qs, urlsplit


class S3Route(BaseHTTPRequestHandler):
    timeout = 5

    def do_GET(self):
        route = self.server
        if not route.owned or urlsplit(self.path).path != route.object_path:
            raise ValueError("Expected a GET for the acknowledged object")
        # Close without an HTTP response, including on SDK retries. This single-threaded
        # route finishes installing the foreign version before accepting any cleanup HEAD.
        self.connection.shutdown(socket.SHUT_RDWR)
        route.drops += 1
        if route.drops == 1:
            target = "fixture" + route.object_path
            owned_bytes = self.mc("cat", "--version-id", route.owned, target, stdout=subprocess.PIPE).stdout
            if owned_bytes != Path("s3/fixtures/greeting.txt").read_bytes():
                raise ValueError("Independent client could not read the acknowledged fixture bytes")
            self.mc("pipe", "--part-size", "8MiB", target, input=b"keep-foreign")
            with Path("s3-before-cleanup.jsonl").open("wb") as snapshot:
                self.mc("ls", "--versions", "--recursive", "--json", "fixture/fixture-bucket", stdout=snapshot)

    def mc(self, *arguments, input=None, stdout=None):
        route = self.server
        return subprocess.run(["docker", "run", "-i", "--rm", "--memory", "160m", "--network",
                               "container:" + route.server_id, "-e", "MC_HOST_fixture", route.client_image, *arguments],
                              input=input, stdout=stdout, check=True, timeout=15)

    def forward(self):
        route = self.server
        path = urlsplit(self.path)
        if not re.fullmatch(r"/fixture-bucket/regress/[a-f0-9]{64}/greeting\.txt", path.path):
            raise ValueError("Unexpected object path")
        size = int(self.headers.get("Content-Length", 0))
        if not 0 <= size <= 1024 * 1024 or self.headers.get("Transfer-Encoding"):
            raise ValueError("Unexpected fixture request framing")
        # Keep the original signed Host, path and headers. Only the TCP destination changes.
        with closing(HTTPConnection("127.0.0.1", route.port, timeout=5)) as backend:
            backend.request(self.command, self.path, self.rfile.read(size), dict(self.headers))
            response = backend.getresponse()
            if self.command == "PUT" and response.status == 200:
                route.object_path = path.path
                route.owned = response.getheader("x-amz-version-id")
            if route.drops:
                version = parse_qs(path.query).get("versionId", [])
                route.cleanup.append((self.command, path.path, version, response.status))
            self.send_response_only(response.status)
            for name, value in response.getheaders():
                if name.lower() not in {"connection", "transfer-encoding"}:
                    self.send_header(name, value)
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(response.read())

    do_PUT = do_HEAD = do_DELETE = forward


def run(jar, server_id, client_image):
    with HTTPServer(("127.0.0.1", 0), S3Route) as route:
        route.port = urlsplit(os.environ["REGRESS_S3_ENDPOINT"]).port
        if not re.fullmatch(r"[0-9a-f]{64}", server_id) or not re.fullmatch(r"[a-zA-Z0-9./:_-]+@sha256:[0-9a-f]{64}", client_image):
            raise ValueError("Expected a running container ID and digest-pinned MinIO client")
        route.server_id = server_id
        route.client_image = client_image
        route.owned = route.object_path = None
        route.drops = 0
        route.cleanup = []
        thread = Thread(target=route.serve_forever, daemon=True)
        thread.start()
        try:
            result = subprocess.run(
                ["java", "-Xmx384m", "-jar", jar, "run", "--suite", "s3/suite_manifest.yaml",
                 "--profile", "external_s3"],
                env={**os.environ, "REGRESS_S3_ENDPOINT": f"http://127.0.0.1:{route.server_port}"},
                timeout=60)
        finally:
            route.shutdown()
            thread.join()

        print(f"S3 dropped GET attempts: {route.drops}; recovered cleanup: {route.cleanup}", file=sys.stderr)
        if result.returncode != 1 or not route.drops or not route.owned or route.owned == "null":
            raise RuntimeError("Expected a versioned PUT followed by a transport failure and CLI exit 1")
        expected = [(op, route.object_path, [route.owned], status)
                    for op, status in [("HEAD", 200), ("DELETE", 204), ("HEAD", 404)]]
        if route.cleanup != expected:
            raise RuntimeError("Expected cleanup to inspect, delete and confirm only the acknowledged version")
        Path("s3-ack.json").write_text(json.dumps(
            {"key": route.object_path.removeprefix("/fixture-bucket/"), "version": route.owned}))


if __name__ == "__main__":
    run(*sys.argv[1:])
