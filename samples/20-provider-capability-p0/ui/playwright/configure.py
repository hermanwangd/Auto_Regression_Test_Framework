"""Create a concrete cli_process suite for a provisioned Playwright runner."""
import argparse
import hashlib
import json
import re
import shutil
from pathlib import Path
from urllib.parse import urlsplit

SOURCE = Path(__file__).resolve().parent


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", type=Path, required=True)
    parser.add_argument("--node-sha256", required=True)
    parser.add_argument("--tools", type=Path, required=True, help="npm installation prefix")
    parser.add_argument("--chromium", type=Path, required=True, help="provisioned Chromium binary")
    parser.add_argument("--url", required=True)
    parser.add_argument("--expected-after-reload", default="Order UI-1042 confirmed")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    node = args.node.resolve(strict=True)
    digest = hashlib.sha256()
    with node.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    if not re.fullmatch("[0-9a-f]{64}", args.node_sha256) or digest.hexdigest() != args.node_sha256:
        parser.error("Node executable differs from the supplied SHA-256")
    library = (args.tools / "node_modules/playwright-core").resolve(strict=True)
    version = load(SOURCE / "package.json")["dependencies"]["playwright-core"]
    if load(library / "package.json")["version"] != version:
        parser.error("Installed Playwright version differs from the sample pin")
    browser = args.chromium.resolve(strict=True)
    url = urlsplit(args.url)
    if url.scheme not in ("http", "https") or not url.hostname or url.username or url.password:
        parser.error("--url must be an HTTP(S) page without credentials")

    profile = load(SOURCE / "env_profiles/local_browser.yaml")
    profile["targets"]["browser"]["bindings"] = {
        "executable": str(node), "executable_sha256": args.node_sha256,
    }
    test = load(SOURCE / "test_cases/submit_order.yaml")
    test["execute"][0]["with"]["process.argv"][1:] = [
        str(SOURCE / "order-flow.cjs"), str(library), str(browser),
        args.url, args.expected_after_reload, str((args.out / "browser-artifacts").resolve()),
    ]
    args.out.mkdir(parents=True, exist_ok=False)
    (args.out / "env_profiles").mkdir()
    (args.out / "test_cases").mkdir()
    (args.out / "browser-artifacts").mkdir(mode=0o700)
    shutil.copyfile(SOURCE / "suite_manifest.yaml", args.out / "suite_manifest.yaml")
    write(args.out / "env_profiles/local_browser.yaml", profile)
    write(args.out / "test_cases/submit_order.yaml", test)


if __name__ == "__main__":
    main()
