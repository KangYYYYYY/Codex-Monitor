from __future__ import annotations

import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server.py"
LOG_PATH = Path(__file__).with_name("codex_mobile_server_autostart.log")
DEFAULT_AGENT_URL = "http://127.0.0.1:8787"


def log(message: str) -> None:
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    with LOG_PATH.open("a", encoding="utf-8") as file:
        file.write(f"[{timestamp}] {message}\n")


def agent_url() -> str:
    return os.getenv("CODEX_MOBILE_AGENT_URL", DEFAULT_AGENT_URL).rstrip("/")


def is_agent_running() -> bool:
    try:
        request = urllib.request.Request(f"{agent_url()}/api/health", method="GET")
        with urllib.request.urlopen(request, timeout=1.0) as response:
            response.read()
            return response.status == 200
    except (OSError, urllib.error.URLError, urllib.error.HTTPError):
        return False


def start_server() -> None:
    if not SERVER.exists():
        log(f"server.py not found: {SERVER}")
        return

    stdout = Path(__file__).with_name("codex_mobile_server_stdout.log").open("ab")
    stderr = Path(__file__).with_name("codex_mobile_server_stderr.log").open("ab")

    env = os.environ.copy()
    env.setdefault("PYTHONUTF8", "1")

    flags = 0
    if os.name == "nt":
        flags |= getattr(subprocess, "CREATE_NO_WINDOW", 0)
        flags |= getattr(subprocess, "DETACHED_PROCESS", 0)

    kwargs = {
        "cwd": str(ROOT),
        "env": env,
        "stdout": stdout,
        "stderr": stderr,
        "stdin": subprocess.DEVNULL,
        "creationflags": flags,
    }
    if os.name != "nt":
        kwargs.pop("creationflags", None)
        kwargs["start_new_session"] = True

    process = subprocess.Popen([sys.executable, str(SERVER)], **kwargs)
    log(f"started server.py pid={process.pid} root={ROOT}")


def main() -> None:
    if is_agent_running():
        log("server already running")
        return

    start_server()
    for _ in range(10):
        time.sleep(0.25)
        if is_agent_running():
            log("server health check passed")
            return
    log("server started but health check did not pass yet")


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"error: {exc}")
        sys.exit(0)
