"""Serve the single public test page on an ephemeral loopback port."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

PAGE = Path(__file__).with_name("order.html").read_bytes()


class OrderPage(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/":
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(PAGE)))
        self.end_headers()
        self.wfile.write(PAGE)


if __name__ == "__main__":
    with HTTPServer(("127.0.0.1", 0), OrderPage) as server:
        print(f"http://127.0.0.1:{server.server_port}/", flush=True)
        server.serve_forever()
