from __future__ import annotations

import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT / "server.py"
LOG_PATH = Path(__file__).with_name("codex_mobile_server_autostart.log")
LOCK_PATH = Path(__file__).with_name("codex_mobile_server_autostart.lock")
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


def acquire_start_lock(timeout: float = 5.0, stale_seconds: float = 30.0) -> int | None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            fd = os.open(str(LOCK_PATH), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            os.write(fd, f"{os.getpid()} {time.time()}\n".encode("ascii"))
            return fd
        except FileExistsError:
            try:
                age = time.time() - LOCK_PATH.stat().st_mtime
                if age > stale_seconds:
                    LOCK_PATH.unlink(missing_ok=True)
                    continue
            except OSError:
                pass
            if is_agent_running():
                log("server already running")
                return None
            time.sleep(0.1)
    log("server start lock is held; skipping duplicate start")
    return None


def release_start_lock(fd: int | None) -> None:
    if fd is None:
        return
    try:
        os.close(fd)
    finally:
        try:
            LOCK_PATH.unlink(missing_ok=True)
        except OSError as exc:
            log(f"lock cleanup failed: {exc}")


def background_python() -> str:
    if os.name != "nt":
        return sys.executable

    current = Path(sys.executable)
    sibling_pythonw = current.with_name("pythonw.exe")
    if sibling_pythonw.exists():
        return str(sibling_pythonw)

    for name in ("pythonw", "pyw"):
        resolved = shutil.which(name)
        if resolved:
            return resolved

    return sys.executable


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

    process = subprocess.Popen([background_python(), str(SERVER)], **kwargs)
    log(f"started server.py pid={process.pid} root={ROOT}")


def main() -> None:
    if is_agent_running():
        log("server already running")
        return

    lock_fd = acquire_start_lock()
    if lock_fd is None:
        return

    try:
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
    finally:
        release_start_lock(lock_fd)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"error: {exc}")
        sys.exit(0)
