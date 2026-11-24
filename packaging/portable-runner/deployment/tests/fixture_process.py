"""Run the public lifecycle fixture with the selected supervision implementation."""
from pathlib import Path
import subprocess
import sys


def run_fixture(supervisor, mode, timeout, attempt):
    script = str(Path(__file__).resolve().parents[1] / 'fixtures/public/child.py')
    if not hasattr(supervisor, '_supervise'):
        return supervisor._run_owned([sys.executable, '-B', script, mode], timeout, attempt)

    def launch(**io):
        return subprocess.Popen(['/usr/bin/env', '--', sys.executable, '-B', script, mode], **io)

    return supervisor._supervise(launch, timeout, attempt)
