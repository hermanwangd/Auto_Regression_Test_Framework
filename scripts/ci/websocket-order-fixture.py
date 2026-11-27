"""Loopback fixture for the order stream sample. Handles only the frames used by that sample."""
import base64
import hashlib
import json
import struct
import sys
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MAX_BYTES = 8192
PRIVATE_MARKER = "ws-fixture-frame-private-marker"
port_file, receipt_file = map(Path, sys.argv[1:])
lock = threading.Lock()
subscribers = set()
revision = 0


def body(value):
    return json.dumps(value, separators=(",", ":")).encode("utf-8")


def write_frame(stream, opcode, payload, final=True):
    length = len(payload)
    assert length <= MAX_BYTES
    header = bytes([(0x80 if final else 0) | opcode])
    size = bytes([length]) if length < 126 else b"\x7e" + struct.pack("!H", length)
    stream.write(header + size + payload)


def read_frame(stream):
    def exact(size):
        raw = stream.read(size)
        if len(raw) != size:
            raise EOFError()
        return raw

    flags, size = exact(2)
    if not size & 0x80 or flags != (0x80 | (flags & 0x0F)):
        raise ValueError("expected one masked final client frame")
    size &= 0x7F
    if size == 126:
        size = struct.unpack("!H", exact(2))[0]
    elif size == 127:
        raise ValueError("message exceeds fixture limit")
    if size > MAX_BYTES:
        raise ValueError("message exceeds fixture limit")
    key = exact(4)
    return flags & 0x0F, bytes(b ^ key[i % 4] for i, b in enumerate(exact(size)))


class OrderApplication(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *_):
        pass

    def do_GET(self):
        if self.path != "/events" or self.headers.get("Upgrade", "").lower() != "websocket":
            self.send_error(404)
            return
        key = self.headers["Sec-WebSocket-Key"]
        # RFC 6455 section 4.2.2 defines SHA-1 for this handshake response.
        material = (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")
        accept = base64.b64encode(hashlib.sha1(material, usedforsecurity=False).digest()).decode("ascii")
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", accept)
        self.end_headers()
        self.close_connection = True
        self.connection.settimeout(20)
        try:
            opcode, payload = read_frame(self.rfile)
            if opcode != 1 or json.loads(payload) != {"action": "subscribe", "topic": "orders"}:
                raise ValueError("unexpected subscription")
            with lock:
                subscribers.add(self)
                write_frame(self.wfile, 1, body({"event": "subscribed"}))
            while True:
                opcode, payload = read_frame(self.rfile)
                with lock:
                    if opcode == 8:
                        if payload[:2] != struct.pack("!H", 1000):
                            raise ValueError("expected a normal close")
                        subscribers.discard(self)
                        with receipt_file.open("a", encoding="utf-8") as receipts:
                            receipts.write("normal-close\n")
                        write_frame(self.wfile, 8, payload)
                        return
                    if opcode == 9:
                        write_frame(self.wfile, 10, payload)
                    else:
                        raise ValueError("unexpected client frame")
        except (EOFError, OSError):
            return
        finally:
            with lock:
                subscribers.discard(self)

    def do_POST(self):
        global revision
        if self.path != "/orders/change":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= MAX_BYTES:
                raise ValueError()
            identifier = str(uuid.UUID(json.loads(self.rfile.read(length))["correlation_id"]))
        except (KeyError, ValueError):
            self.send_error(400)
            return
        with lock:
            if len(subscribers) != 1:
                self.send_error(409)
                return
            revision += 1
            stream = next(iter(subscribers)).wfile
            for event, correlation in [("order.changed", "earlier-run"), ("order.viewed", identifier),
                                       ("order.changed", identifier)]:
                payload = body({"event": event, "meta": {"correlation_id": correlation},
                                "revision": revision, "detail": PRIVATE_MARKER})
                write_frame(stream, 1, payload[:24], final=False)
                write_frame(stream, 0, payload[24:])
        self.send_response(202)
        self.send_header("Content-Length", "0")
        self.end_headers()


server = ThreadingHTTPServer(("127.0.0.1", 0), OrderApplication)
with server:
    port_file.write_text(str(server.server_port), encoding="utf-8")
    server.serve_forever()
