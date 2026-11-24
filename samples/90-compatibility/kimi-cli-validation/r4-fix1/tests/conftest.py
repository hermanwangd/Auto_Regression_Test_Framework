from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src"
if str(SRC) not in sys.path:
    sys.path.insert(0, str(SRC))

# Isolated Python omits the working directory; the hardening cases import this sample's runner.
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
