"""Two-generation POSIX process tree for the frozen cleanup controls."""
import os
import signal
import sys
import time


def main(mode):
    if mode not in {"timeout", "normal"}:
        raise ValueError("unknown process-tree control")
    if mode == "timeout":
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
    child = os.fork()
    if child == 0:
        print(f"GRANDCHILD {os.getpid()}", flush=True)
        if mode == "timeout":
            time.sleep(60)
        print("GRANDCHILD DONE", flush=True)
        return 0
    print(f"CHILD {os.getpid()} GRANDCHILD {child}", flush=True)
    _, status = os.waitpid(child, 0)
    print("CHILD DONE", flush=True)
    return os.waitstatus_to_exitcode(status)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1]))
