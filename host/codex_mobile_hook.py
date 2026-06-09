from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path


LOG_PATH = Path(__file__).with_name("codex_mobile_hook.log")
DEFAULT_AGENT_URL = "http://127.0.0.1:8787"


def log(message: str) -> None:
    with LOG_PATH.open("a", encoding="utf-8") as file:
        file.write(message + "\n")


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
