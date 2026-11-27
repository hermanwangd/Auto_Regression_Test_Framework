"""Exercise the packaged recovery suite against a fresh real SMTP inbox for each case."""
import contextlib
import json
import os
from pathlib import Path
import smtplib
import socket
import subprocess
import sys
import tempfile
import threading
import time
from email.message import EmailMessage
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.error import URLError
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[2]
SUITE = ROOT / "samples/20-provider-capability-p0/http/mailpit/suite_manifest.yaml"
LOOPBACK = "127.0.0.1"


def free_port():
    with socket.socket() as listener:
        listener.bind((LOOPBACK, 0))
        return listener.getsockname()[1]


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


@contextlib.contextmanager
def services(binary, work, variant):
    smtp_port, http_port = free_port(), free_port()
    while smtp_port == http_port:
        http_port = free_port()
    inbox = f"http://{LOOPBACK}:{http_port}"
    with application(smtp_port, variant) as app, (work / "mailpit.log").open("w") as log:
        with subprocess.Popen([binary, "--listen", f"{LOOPBACK}:{http_port}", "--smtp",
                               f"{LOOPBACK}:{smtp_port}", "--database", str(work / "mail.db")],
                              stdout=log, stderr=subprocess.STDOUT) as mail:
            try:
                deadline = time.monotonic() + 10
                while True:
                    try:
                        with urlopen(inbox + "/readyz", timeout=1) as response:
                            if response.status == 200:
                                break
                    except URLError:
                        pass
                    if mail.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError("Mailpit failed readiness")
                    time.sleep(0.1)
                thread = threading.Thread(target=app.serve_forever, daemon=True)
                thread.start()
                try:
                    yield f"http://{LOOPBACK}:{app.server_port}", inbox
                finally:
                    app.shutdown()
                    thread.join()
            finally:
                mail.terminate()


def check(binary, jar, variant):
    cli = ["java", "-Xmx256m", "-jar", jar]
    with tempfile.TemporaryDirectory() as scratch:
        work = Path(scratch)
        with services(binary, work, variant) as (app, inbox):
            env = dict(os.environ, REST_BASE_URL=app, MAILPIT_BASE_URL=inbox,
                       REGRESS_REST_ALLOWED_ORIGINS=f"{app},{inbox}")
            run = subprocess.run([*cli, "run", "--suite", str(SUITE), "--profile", "external_mailpit"],
                                 env=env, cwd=work, text=True, stdout=subprocess.PIPE, timeout=60)
            print(run.stdout, flush=True)
            success = variant == "valid"
            if run.returncode != (0 if success else 1):
                raise RuntimeError(f"Unexpected CLI exit for {variant}: {run.returncode}")
            path = next(line.removeprefix("result_json: ") for line in run.stdout.splitlines()
                        if line.startswith("result_json: "))
            result = json.loads((work / path).read_text())
            if result["status"] != ("passed" if success else "failed") or result["cleanup_failures"]:
                raise RuntimeError(f"Unexpected run result for {variant}: {result['failure']}")
            outcomes = {item["id"]: item["status"] for item in result["verify_results"]}
            for assertion in ("inbox_started_empty", "accepted", "one_message", "recipient", "subject", "recovery_link"):
                if outcomes.get(assertion) != ("failed" if assertion == variant else "passed"):
                    raise RuntimeError(f"Unexpected {variant} assertion results: {outcomes}")
                if assertion == variant:
                    break
            if not success and result["failure"]["code"] != "ASSERTION_FAILED":
                raise RuntimeError(f"Expected a mail assertion failure for {variant}")
            subprocess.run([*cli, "validate-evidence", "--result", path], cwd=work, check=True, timeout=30)
            print(f"Verified Mailpit case {variant}", flush=True)


if __name__ == "__main__":
    for case in ("valid", "recipient", "subject", "recovery_link"):
        check(str(Path(sys.argv[1]).resolve()), str(Path(sys.argv[2]).resolve()), case)
