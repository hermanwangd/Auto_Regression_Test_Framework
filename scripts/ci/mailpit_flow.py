"""Loopback application for the synthetic recovery-email regression."""
import json
import smtplib
import sys
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, HTTPServer

LOOPBACK = "127.0.0.1"


def application(smtp_port, variant):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            if self.path != "/forgot-password":
                self.send_error(404)
                return
            length = int(self.headers.get("Content-Length", 0))
            if not 0 < length <= 1024:
                self.send_error(400)
                return
            if json.loads(self.rfile.read(length)) != {"email": "reader@example.test"}:
                self.send_error(400)
                return
            message = EmailMessage()
            message["From"] = "accounts@example.test"
            message["To"] = "other@example.test" if variant == "recipient" else "reader@example.test"
            message["Subject"] = "Receipt" if variant == "subject" else "Account recovery"
            path = "wrong" if variant == "recovery_link" else "demo"
            message.set_content("https://accounts.example.test/reset/" + path)
            with smtplib.SMTP(LOOPBACK, smtp_port, timeout=3) as smtp:
                smtp.send_message(message)
            self.send_response(202)
            self.end_headers()

        def log_message(self, *args):
            pass

    return HTTPServer((LOOPBACK, 0), Handler)


if __name__ == "__main__":
    with application(int(sys.argv[1]), sys.argv[2]) as server:
        print(f"http://{LOOPBACK}:{server.server_port}", flush=True)
        server.serve_forever()
