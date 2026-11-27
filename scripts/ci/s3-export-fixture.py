"""One-shot loopback test app. Write a real object using an independent MinIO client."""

import json
import re
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path


class ExportHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/exports" or self.headers.get_content_type() != "application/json":
            self.send_error(400)
            return
        length = int(self.headers.get("Content-Length", 0))
        if not 0 < length <= 1024:
            self.send_error(400)
            return
        key = json.loads(self.rfile.read(length)).get("object_key")
        if not isinstance(key, str) or not re.fullmatch(r"regress/[0-9a-f]{64}/exports/greeting\.txt", key):
            self.send_error(400)
            return
        # The fixture chooses the bucket and payload. The request can only name this run's export.
        subprocess.run(["docker", "run", "-i", "--rm", "--memory", "160m", "--network",
                        "container:" + server_id, "-e", "MC_HOST_fixture", client_image, "pipe",
                        "fixture/fixture-bucket/" + key], input=b"hello", timeout=20, check=True)
        self.send_response(201)
        self.end_headers()


if __name__ == "__main__":
    port_file, server_id, client_image = sys.argv[1:]
    with HTTPServer(("127.0.0.1", 0), ExportHandler) as app:
        app.timeout = 30
        Path(port_file).write_text(str(app.server_port))
        app.handle_request()
