"""Independent one-batch SFTP drop-folder consumer for the disposable OpenSSH fixture."""
import csv
import re
import sys
import time
from pathlib import Path


def process(root):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        for batch in root.iterdir():
            if not re.fullmatch(r"batch-[0-9a-f]{64}", batch.name):
                continue
            incoming = batch / "incoming" / "orders.csv"
            if not incoming.is_file():
                continue
            with incoming.open(encoding="utf8", newline="") as stream:
                reader = csv.DictReader(stream)
                if reader.fieldnames != ["order_id", "units"]:
                    raise ValueError("Unexpected order header")
                result = []
                for row in reader:
                    order_id, units = row["order_id"], int(row["units"])
                    if not re.fullmatch(r"ORD-[0-9]+", order_id) or units <= 0:
                        raise ValueError("Invalid order")
                    result.append(f"{order_id},ACCEPTED,{units}\n")
            # Commit only the completed result to the visible filename.
            staged = batch / "returns" / ".reconciliation-tmp"
            staged.write_bytes("".join(result).encode("utf8"))
            staged.rename(batch / "returns" / "reconciliation.csv")
            print(f"processed {batch.name} records={len(result)}", flush=True)
            return
        time.sleep(0.05)
    raise TimeoutError("No order input arrived")


if __name__ == "__main__":
    process(Path(sys.argv[1]))
