from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

import start_codex_mobile_server


LOG_PATH = Path(__file__).with_name("codex_mobile_hook.log")
DEFAULT_AGENT_URL = "http://127.0.0.1:8787"
ROOT = Path(__file__).resolve().parents[1]


def log(message: str) -> None:
    with LOG_PATH.open("a", encoding="utf-8") as file:
        file.write(message + "\n")


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        log(f"dotenv read failed: {exc}")
        return

    for line in lines:
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key, value = stripped.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def load_event() -> dict:
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        event = json.loads(raw)
        return event if isinstance(event, dict) else {}
    except json.JSONDecodeError:
        return {"_raw": raw}


def agent_url() -> str:
    return os.getenv("CODEX_MOBILE_AGENT_URL", DEFAULT_AGENT_URL).rstrip("/")


def send_event(event: dict) -> None:
    body = json.dumps(event, ensure_ascii=True).encode("utf-8")
    request = urllib.request.Request(
        f"{agent_url()}/api/agentcore/event",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    token = os.getenv("LOCAL_AGENT_TOKEN")
    if token:
        request.add_header("X-Agent-Token", token)
    with urllib.request.urlopen(request, timeout=2.0) as response:
        response.read()


def main() -> None:
    load_dotenv(ROOT / ".env")
    event = load_event()
    name = (
        event.get("hook_event_name")
        or event.get("event_name")
        or event.get("event")
        or event.get("type")
        or "unknown"
    )
    tool = event.get("tool_name", "")
    session_id = (
        event.get("session_id")
        or event.get("sessionId")
        or event.get("conversation_id")
        or event.get("thread_id")
        or ""
    )
    start_codex_mobile_server.main()
    try:
        send_event(event)
        log(f"sent event={name} tool={tool} session={session_id}")
    except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
        log(f"send failed event={name} tool={tool}: {exc}")

    if name == "Stop":
        print(json.dumps({"continue": True}))


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        log(f"error: {exc}")
        sys.exit(0)
