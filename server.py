from __future__ import annotations

import argparse
import json
import mimetypes
import os
from pathlib import Path
from datetime import datetime, timezone
import shutil
import socket
import subprocess
import threading
import queue
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


APP_VERSION = "0.2.1"
ROOT = Path(__file__).resolve().parent
WEB_ROOT = ROOT / "web"
DEFAULT_PORT = 8787
STATIC_VERSION = "20260608j"
CODEX_SESSION_SCAN_FILES = 50
CODEX_SESSION_TAIL_BYTES = 2_000_000
AGENTCORE_TERMINAL_IDLE_SECONDS = 600
AGENTCORE_TRANSIENT_IDLE_GRACE_SECONDS = 90
AGENTCORE_ALERT_TTL_SECONDS = 900
AGENTCORE_COMMANDS = {"IDLE", "THINKING", "WRITING", "RUNNING", "DONE", "ERROR", "NEED_CONFIRM"}
AGENTCORE_STATE_LOCK = threading.Lock()
AGENTCORE_STATE: dict[str, object] = {
    "command": None,
    "tokenPercent": None,
    "updatedAt": None,
    "source": None,
}
AGENTCORE_SESSION_STATES: dict[str, dict[str, object]] = {}
AGENTCORE_OUTPUT_STATE_LOCK = threading.Lock()
AGENTCORE_OUTPUT_STATE: dict[str, object] = {
    "command": None,
    "source": None,
    "updatedAt": None,
}
AGENTCORE_ALERTS_LOCK = threading.Lock()
AGENTCORE_ALERTS: list[dict[str, object]] = []
OPENAI_CACHE_LOCK = threading.Lock()
OPENAI_STATUS_CACHE: dict[str, object] = {
    "updatedAt": 0,
    "data": None,
}
CODEX_RATE_LIMIT_CACHE_LOCK = threading.Lock()
CODEX_RATE_LIMIT_CACHE: dict[str, object] = {
    "updatedAt": 0,
    "data": None,
}


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if key and key not in os.environ:
            os.environ[key] = value


def hidden_subprocess_kwargs() -> dict:
    if os.name != "nt":
        return {}

    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    startupinfo = subprocess.STARTUPINFO()
    startupinfo.dwFlags |= getattr(subprocess, "STARTF_USESHOWWINDOW", 0)
    startupinfo.wShowWindow = getattr(subprocess, "SW_HIDE", 0)
    return {"creationflags": flags, "startupinfo": startupinfo}


def resolve_codex_cli_path() -> str | None:
    configured = os.getenv("CODEX_CLI_PATH", "").strip()
    candidates: list[Path] = []
    if configured:
        candidates.append(Path(configured))

    if os.name == "nt":
        for item in os.getenv("PATH", "").split(os.pathsep):
            if item:
                candidates.append(Path(item) / "codex.exe")

        candidates.extend((Path.home() / ".vscode" / "extensions").glob(
            "openai.chatgpt-*-win32-x64/bin/windows-x86_64/codex.exe"
        ))

        local_app_data = os.getenv("LOCALAPPDATA")
        if local_app_data:
            candidates.extend((Path(local_app_data) / "OpenAI" / "Codex" / "bin").glob("*/codex.exe"))

        candidates.extend((Path.home() / "AppData" / "Roaming" / "npm" / "node_modules").glob(
            "@openai/codex/node_modules/@openai/codex-win32-x64/vendor/*/codex/codex.exe"
        ))
    else:
        found = shutil.which("codex")
        if found:
            candidates.append(Path(found))

    seen: set[str] = set()
    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        key = str(resolved).lower()
        if key in seen:
            continue
        seen.add(key)
        if resolved.is_file() and (os.name != "nt" or resolved.suffix.lower() == ".exe"):
            return str(resolved)

    found = shutil.which("codex")
    if not found:
        return None
    if os.name == "nt" and Path(found).suffix.lower() != ".exe":
        return None
    return found


def now_iso() -> str:
    return datetime.now().astimezone().isoformat(timespec="seconds")


def month_start_ts() -> int:
    now = datetime.now().astimezone()
    start = now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)
    return int(start.timestamp())


def today_start_ts() -> int:
    now = datetime.now().astimezone()
    start = now.replace(hour=0, minute=0, second=0, microsecond=0)
    return int(start.timestamp())


def unix_now() -> int:
    return int(datetime.now(timezone.utc).timestamp())


def from_unix_iso(timestamp: int | float | None) -> str | None:
    if not isinstance(timestamp, (int, float)):
        return None
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).astimezone().isoformat(timespec="seconds")


def parse_event_time(value: object) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None


def event_age_seconds(value: object) -> int | None:
    parsed = parse_event_time(value)
    if not parsed:
        return None
    return max(0, int((datetime.now(timezone.utc) - parsed.astimezone(timezone.utc)).total_seconds()))


def iso_is_newer(left: object, right: object) -> bool:
    left_dt = parse_event_time(left)
    right_dt = parse_event_time(right)
    if left_dt and right_dt:
        return left_dt >= right_dt
    return bool(left_dt and not right_dt)


def get_lan_ips() -> list[str]:
    ips: set[str] = set()
    hostname = socket.gethostname()
    try:
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass

    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("8.8.8.8", 80))
            ip = sock.getsockname()[0]
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass

    return sorted(ips)


def request_json(url: str, headers: dict[str, str], timeout: float = 12.0) -> dict:
    request = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
            return json.loads(body)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"HTTP {exc.code}: {body[:300]}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(str(exc.reason)) from exc


def openai_headers() -> dict[str, str] | None:
    key = os.getenv("OPENAI_ADMIN_KEY") or os.getenv("OPENAI_API_KEY")
    if not key:
        return None

    headers = {
        "Authorization": f"Bearer {key}",
        "Accept": "application/json",
    }
    if os.getenv("OPENAI_ORG_ID"):
        headers["OpenAI-Organization"] = os.getenv("OPENAI_ORG_ID", "")
    if os.getenv("OPENAI_PROJECT_ID"):
        headers["OpenAI-Project"] = os.getenv("OPENAI_PROJECT_ID", "")
    return headers


def build_url(path: str, params: dict[str, object]) -> str:
    clean_params = {key: value for key, value in params.items() if value is not None}
    return f"https://api.openai.com{path}?{urllib.parse.urlencode(clean_params, doseq=True)}"


def collect_amounts(value: object) -> list[tuple[str, float]]:
    amounts: list[tuple[str, float]] = []
    if isinstance(value, dict):
        amount = value.get("amount")
        if isinstance(amount, dict) and isinstance(amount.get("value"), (int, float)):
            currency = str(amount.get("currency") or "usd").upper()
            amounts.append((currency, float(amount["value"])))
        for child in value.values():
            amounts.extend(collect_amounts(child))
    elif isinstance(value, list):
        for child in value:
            amounts.extend(collect_amounts(child))
    return amounts


def sum_costs(report: dict) -> dict[str, float]:
    totals: dict[str, float] = {}
    for currency, value in collect_amounts(report):
        totals[currency] = round(totals.get(currency, 0.0) + value, 6)
    return totals


def sum_completion_usage(report: dict) -> dict[str, int]:
    fields = [
        "input_tokens",
        "input_cached_tokens",
        "output_tokens",
        "input_audio_tokens",
        "output_audio_tokens",
        "num_model_requests",
    ]
    totals = {field: 0 for field in fields}
    for bucket in report.get("data", []):
        for result in bucket.get("results", []):
            if not isinstance(result, dict):
                continue
            for field in fields:
                value = result.get(field, 0)
                if isinstance(value, int):
                    totals[field] += value
    return totals


def fetch_openai_status() -> dict:
    headers = openai_headers()
    if not headers:
        return {
            "status": "not_configured",
            "message": "未配置 OPENAI_ADMIN_KEY 或 OPENAI_API_KEY。",
            "monthCost": {},
            "todayCost": {},
            "usage": {},
            "budgetUsd": read_float_env("OPENAI_MONTHLY_BUDGET_USD"),
            "remainingUsd": None,
            "errors": [],
        }

    errors: list[str] = []
    start_month = month_start_ts()
    start_today = today_start_ts()
    end = unix_now()

    month_cost: dict[str, float] = {}
    today_cost: dict[str, float] = {}
    usage: dict[str, int] = {}

    try:
        month_report = request_json(
            build_url(
                "/v1/organization/costs",
                {
                    "start_time": start_month,
                    "end_time": end,
                    "bucket_width": "1d",
                    "limit": 31,
                },
            ),
            headers,
        )
        month_cost = sum_costs(month_report)
    except RuntimeError as exc:
        errors.append(f"OpenAI costs 月度查询失败：{exc}")

    try:
        today_report = request_json(
            build_url(
                "/v1/organization/costs",
                {
                    "start_time": start_today,
                    "end_time": end,
                    "bucket_width": "1d",
                    "limit": 1,
                },
            ),
            headers,
        )
        today_cost = sum_costs(today_report)
    except RuntimeError as exc:
        errors.append(f"OpenAI costs 今日查询失败：{exc}")

    try:
        usage_report = request_json(
            build_url(
                "/v1/organization/usage/completions",
                {
                    "start_time": start_month,
                    "end_time": end,
                    "bucket_width": "1d",
                    "limit": 31,
                },
            ),
            headers,
        )
        usage = sum_completion_usage(usage_report)
    except RuntimeError as exc:
        errors.append(f"OpenAI usage 查询失败：{exc}")

    budget_usd = read_float_env("OPENAI_MONTHLY_BUDGET_USD")
    month_usd = month_cost.get("USD")
    remaining_usd = None
    if budget_usd is not None and month_usd is not None:
        remaining_usd = round(budget_usd - month_usd, 6)

    return {
        "status": "error" if errors else "ok",
        "message": "已读取 OpenAI API 用量。" if not errors else "部分 OpenAI 数据读取失败。",
        "monthCost": month_cost,
        "todayCost": today_cost,
        "usage": usage,
        "budgetUsd": budget_usd,
        "remainingUsd": remaining_usd,
        "errors": errors,
    }


def read_float_env(name: str) -> float | None:
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return None
    try:
        return float(value)
    except ValueError:
        return None


def read_int_env(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return default
    try:
        return int(value)
    except ValueError:
        return default


def fetch_openai_status_cached() -> dict:
    ttl = max(0, read_int_env("OPENAI_CACHE_SECONDS", 300))
    now = unix_now()
    with OPENAI_CACHE_LOCK:
        cached = OPENAI_STATUS_CACHE.get("data")
        updated_at = OPENAI_STATUS_CACHE.get("updatedAt")
        if isinstance(cached, dict) and isinstance(updated_at, int) and now - updated_at < ttl:
            result = dict(cached)
            result["cached"] = True
            result["cacheAgeSeconds"] = now - updated_at
            return result

    result = fetch_openai_status()
    result["cached"] = False
    result["cacheAgeSeconds"] = 0
    with OPENAI_CACHE_LOCK:
        OPENAI_STATUS_CACHE["data"] = result
        OPENAI_STATUS_CACHE["updatedAt"] = now
    return result


def codex_home() -> Path:
    configured = os.getenv("CODEX_HOME")
    if configured:
        return Path(configured).expanduser()
    return Path.home() / ".codex"


def tail_lines(path: Path, max_bytes: int = CODEX_SESSION_TAIL_BYTES) -> list[str]:
    size = path.stat().st_size
    with path.open("rb") as file:
        if size > max_bytes:
            file.seek(size - max_bytes)
            file.readline()
        data = file.read()
    return data.decode("utf-8", errors="replace").splitlines()


def rate_limit_label(window_minutes: object, fallback: str) -> str:
    if window_minutes == 300:
        return "5h"
    if window_minutes == 10080:
        return "weekly"
    if isinstance(window_minutes, int):
        if window_minutes % 1440 == 0:
            days = window_minutes // 1440
            return f"{days}d"
        if window_minutes % 60 == 0:
            hours = window_minutes // 60
            return f"{hours}h"
        return f"{window_minutes}m"
    return fallback


def normalize_rate_window(name: str, value: object) -> dict | None:
    if not isinstance(value, dict):
        return None

    used = value.get("used_percent")
    if used is None:
        used = value.get("usedPercent")
    if isinstance(used, (int, float)):
        used_percent = round(float(used), 2)
        remaining_percent = round(max(0.0, 100.0 - used_percent), 2)
    else:
        used_percent = None
        remaining_percent = None

    window_minutes = value.get("window_minutes")
    if window_minutes is None:
        window_minutes = value.get("windowDurationMins")
    resets_at = value.get("resets_at")
    if resets_at is None:
        resets_at = value.get("resetsAt")
    reset_in_seconds = None
    if isinstance(resets_at, (int, float)):
        reset_in_seconds = max(0, int(resets_at - unix_now()))

    return {
        "name": name,
        "label": rate_limit_label(window_minutes, name),
        "windowMinutes": window_minutes if isinstance(window_minutes, int) else None,
        "usedPercent": used_percent,
        "remainingPercent": remaining_percent,
        "resetsAt": from_unix_iso(resets_at),
        "resetInSeconds": reset_in_seconds,
    }


def normalize_rate_limit_snapshot(snapshot: object, source: str, updated_at: str | None = None) -> dict | None:
    if not isinstance(snapshot, dict):
        return None

    return {
        "status": "ok",
        "source": source,
        "updatedAt": updated_at or now_iso(),
        "sessionFile": None,
        "limitId": snapshot.get("limit_id", snapshot.get("limitId")),
        "planType": snapshot.get("plan_type", snapshot.get("planType")),
        "rateLimitReachedType": snapshot.get("rate_limit_reached_type", snapshot.get("rateLimitReachedType")),
        "primary": normalize_rate_window("primary", snapshot.get("primary")),
        "secondary": normalize_rate_window("secondary", snapshot.get("secondary")),
        "credits": snapshot.get("credits"),
        "individualLimit": snapshot.get("individual_limit", snapshot.get("individualLimit")),
        "tokenUsage": None,
    }


def read_codex_app_server_rate_limits() -> dict | None:
    codex_path = resolve_codex_cli_path()
    if not codex_path:
        return None

    process = subprocess.Popen(
        [codex_path, "app-server"],
        cwd=str(ROOT),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        **hidden_subprocess_kwargs(),
    )
    lines: queue.Queue[str] = queue.Queue()

    def read_stdout() -> None:
        assert process.stdout is not None
        for stdout_line in process.stdout:
            lines.put(stdout_line)

    reader = threading.Thread(target=read_stdout, daemon=True)
    reader.start()

    def send(message: dict) -> None:
        assert process.stdin is not None
        process.stdin.write(json.dumps(message, separators=(",", ":")) + "\n")
        process.stdin.flush()

    try:
        send(
            {
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "clientInfo": {
                        "name": "codex-lan-monitor",
                        "title": "Codex LAN Monitor",
                        "version": APP_VERSION,
                    },
                    "capabilities": {
                        "experimentalApi": True,
                        "optOutNotificationMethods": [],
                    },
                },
            }
        )
        send({"jsonrpc": "2.0", "id": 2, "method": "account/rateLimits/read"})

        deadline = datetime.now(timezone.utc).timestamp() + 8
        while datetime.now(timezone.utc).timestamp() < deadline:
            try:
                line = lines.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                message = json.loads(line)
            except json.JSONDecodeError:
                continue
            if message.get("id") != 2:
                continue
            result = message.get("result")
            if not isinstance(result, dict):
                return None
            snapshots = result.get("rateLimitsByLimitId")
            snapshot = None
            if isinstance(snapshots, dict):
                snapshot = snapshots.get("codex")
            if not isinstance(snapshot, dict):
                snapshot = result.get("rateLimits")
            return normalize_rate_limit_snapshot(snapshot, "codex_app_server")
        return None
    finally:
        try:
            process.terminate()
            process.wait(timeout=2)
        except Exception:
            try:
                process.kill()
            except Exception:
                pass


def find_codex_app_server_rate_limits(force: bool = False) -> dict | None:
    if os.getenv("CODEX_APP_SERVER_RATE_LIMITS", "1").lower() in {"0", "false", "no"}:
        return None

    ttl = max(0, read_int_env("CODEX_APP_SERVER_CACHE_SECONDS", 30))
    current = unix_now()
    if not force:
        with CODEX_RATE_LIMIT_CACHE_LOCK:
            cached = CODEX_RATE_LIMIT_CACHE.get("data")
            updated_at = CODEX_RATE_LIMIT_CACHE.get("updatedAt")
            if isinstance(cached, dict) and isinstance(updated_at, int) and current - updated_at < ttl:
                result = dict(cached)
                result["cached"] = True
                result["cacheAgeSeconds"] = current - updated_at
                return result

    result = read_codex_app_server_rate_limits()
    if not result:
        return None
    result["cached"] = False
    result["cacheAgeSeconds"] = 0
    with CODEX_RATE_LIMIT_CACHE_LOCK:
        CODEX_RATE_LIMIT_CACHE["data"] = result
        CODEX_RATE_LIMIT_CACHE["updatedAt"] = current
    return result


def normalize_token_usage_block(value: object) -> dict | None:
    if not isinstance(value, dict):
        return None

    def int_field(name: str) -> int:
        item = value.get(name)
        return item if isinstance(item, int) else 0

    return {
        "inputTokens": int_field("input_tokens"),
        "cachedInputTokens": int_field("cached_input_tokens"),
        "outputTokens": int_field("output_tokens"),
        "reasoningOutputTokens": int_field("reasoning_output_tokens"),
        "totalTokens": int_field("total_tokens"),
    }


def normalize_token_usage_info(value: object) -> dict | None:
    if not isinstance(value, dict):
        return None

    total = normalize_token_usage_block(value.get("total_token_usage"))
    last = normalize_token_usage_block(value.get("last_token_usage"))
    context_window = value.get("model_context_window")
    if not total and not last:
        return None

    return {
        "total": total,
        "last": last,
        "modelContextWindow": context_window if isinstance(context_window, int) else None,
    }


def find_latest_codex_rate_limits(force_direct: bool = False) -> dict | None:
    direct = find_codex_app_server_rate_limits(force=force_direct)
    if direct:
        log_limits = find_latest_codex_rate_limits_from_session_log()
        token_usage = log_limits.get("tokenUsage") if isinstance(log_limits, dict) else None
        if token_usage:
            direct["tokenUsage"] = token_usage
        return direct
    return find_latest_codex_rate_limits_from_session_log()


def find_latest_codex_rate_limits_from_session_log() -> dict | None:
    sessions_root = codex_home() / "sessions"
    if not sessions_root.exists():
        return None

    files = sorted(
        sessions_root.rglob("*.jsonl"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )[:CODEX_SESSION_SCAN_FILES]

    latest: dict | None = None
    latest_timestamp = ""
    for file_path in files:
        try:
            lines = tail_lines(file_path)
        except OSError:
            continue

        for line in reversed(lines):
            if '"rate_limits"' not in line or '"token_count"' not in line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue

            payload = record.get("payload")
            if not isinstance(payload, dict) or payload.get("type") != "token_count":
                continue

            rate_limits = payload.get("rate_limits")
            if not isinstance(rate_limits, dict):
                continue

            timestamp = str(record.get("timestamp") or "")
            if latest is not None and timestamp <= latest_timestamp:
                continue

            primary = normalize_rate_window("primary", rate_limits.get("primary"))
            secondary = normalize_rate_window("secondary", rate_limits.get("secondary"))
            token_usage = normalize_token_usage_info(payload.get("info"))
            latest = {
                "status": "ok",
                "source": "codex_session_log",
                "updatedAt": timestamp or None,
                "sessionFile": file_path.name,
                "limitId": rate_limits.get("limit_id"),
                "planType": rate_limits.get("plan_type"),
                "rateLimitReachedType": rate_limits.get("rate_limit_reached_type"),
                "primary": primary,
                "secondary": secondary,
                "credits": rate_limits.get("credits"),
                "individualLimit": rate_limits.get("individual_limit"),
                "tokenUsage": token_usage,
            }
            latest_timestamp = timestamp

    return latest


def safe_payload_type(record: dict) -> str | None:
    payload = record.get("payload")
    if isinstance(payload, dict):
        payload_type = payload.get("type")
        if isinstance(payload_type, str):
            return payload_type
    return None


def payload_for(record: dict) -> dict:
    payload = record.get("payload")
    return payload if isinstance(payload, dict) else {}


def permission_profile_label(value: object) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        profile_type = value.get("type")
        if isinstance(profile_type, str):
            return profile_type
    return None


def approval_policy_label(value: object) -> str:
    labels = {
        "never": "无需请求权限",
        "on-request": "按需请求权限",
        "on-failure": "失败后请求权限",
        "untrusted": "非信任操作需权限",
    }
    if isinstance(value, str):
        return labels.get(value, value)
    return "未知"


def sandbox_policy_label(value: object) -> str:
    labels = {
        "read-only": "只读",
        "workspace-write": "工作区可写",
        "danger-full-access": "完全访问",
    }
    if isinstance(value, dict):
        value = value.get("type")
    if isinstance(value, str):
        return labels.get(value, value)
    return "未知"


def compact_value(value: object) -> str | None:
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        for key in ("mode", "type", "name"):
            item = value.get(key)
            if isinstance(item, str):
                return item
    return None


def flatten_text(value: object, depth: int = 0) -> str:
    if depth > 4:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, dict):
        return " ".join(flatten_text(item, depth + 1) for item in value.values())
    if isinstance(value, list):
        return " ".join(flatten_text(item, depth + 1) for item in value)
    return ""


def looks_like_permission_request(record: dict) -> bool:
    payload = payload_for(record)
    pieces = [
        str(record.get("type") or ""),
        str(payload.get("type") or ""),
        str(payload.get("status") or ""),
        str(payload.get("name") or ""),
    ]
    metadata_text = " ".join(pieces).lower()
    negative_terms = ("output", "end", "complete", "completed", "approved", "denied")
    if any(term in metadata_text for term in negative_terms):
        return False
    if any(term in metadata_text for term in ("approval", "permission", "escalat")):
        return True
    return False

def status_from_event(record: dict, has_newer_tool_output: bool = False) -> tuple[str, str, str]:
    payload = payload_for(record)
    payload_type = payload.get("type")
    record_type = record.get("type")
    phase = payload.get("phase")

    if looks_like_permission_request(record):
        return "needs_permission", "需要权限", "Codex 正在等待权限确认。"

    if payload_type == "task_complete":
        return "completed", "完成", "上一轮任务已经完成。"

    if payload_type == "reasoning":
        return "thinking", "思考中", "Codex 正在推理下一步。"

    if payload_type in {"function_call", "custom_tool_call", "web_search_call"}:
        tool_name = payload.get("name")
        if isinstance(tool_name, str) and tool_name:
            return "running_tool", "执行工具", f"正在调用 {tool_name}。"
        return "running_tool", "执行工具", "正在调用工具。"

    if payload_type in {"function_call_output", "custom_tool_call_output"} or payload_type in {
        "patch_apply_end",
        "mcp_tool_call_end",
        "web_search_end",
    }:
        return "thinking", "处理结果", "工具结果已返回，Codex 正在继续处理。"

    if payload_type in {"agent_message", "message"}:
        if phase == "final_answer":
            return "replying", "输出结果", "Codex 正在输出最终回复。"
        if phase == "commentary":
            return "replying", "回复中", "Codex 正在发送进度或说明。"
        return "replying", "回复中", "Codex 正在输出消息。"

    if payload_type == "user_message":
        return "starting", "已收到任务", "Codex 已收到最新消息。"

    if payload_type == "task_started":
        return "starting", "启动中", "Codex 正在开始新一轮任务。"

    if record_type == "turn_context":
        return "starting", "准备中", "Codex 正在加载上下文和权限模式。"

    if has_newer_tool_output:
        return "thinking", "思考中", "Codex 正在处理上一步结果。"

    return "unknown", "未知", "尚未识别到明确状态。"


def agentcore_command_for_activity(activity: dict) -> str:
    status = activity.get("status")
    last_event = activity.get("lastEvent")
    tool_name = last_event.get("toolName") if isinstance(last_event, dict) else None

    if status == "needs_permission":
        return "NEED_CONFIRM"
    if status == "running_tool":
        return "WRITING" if tool_name == "apply_patch" else "RUNNING"
    if status in {"thinking", "starting", "replying"}:
        return "THINKING"
    if status == "completed":
        return "DONE"
    if status == "error":
        return "ERROR"
    return "IDLE"


def normalize_agentcore_command(value: object) -> str:
    if not isinstance(value, str):
        raise ValueError("command must be a string")
    command = value.strip().upper()
    aliases = {
        "THINK": "THINKING",
        "BUSY": "RUNNING",
        "SUCCESS": "DONE",
        "CONFIRM": "NEED_CONFIRM",
        "PERMISSION": "NEED_CONFIRM",
    }
    command = aliases.get(command, command)
    if command.startswith("TOKEN:"):
        raw = command.split(":", 1)[1]
        token = int(float(raw))
        if token < 0 or token > 100:
            raise ValueError("TOKEN percent must be 0..100")
        return f"TOKEN:{token}"
    if command not in AGENTCORE_COMMANDS:
        raise ValueError(f"unsupported command: {value}")
    return command


def command_for_hook_event(event: dict) -> str | None:
    event_name = (
        event.get("hook_event_name")
        or event.get("event_name")
        or event.get("event")
        or event.get("type")
        or ""
    )
    tool_name = event.get("tool_name", "")

    if event_name == "SessionStart":
        return None
    if event_name == "UserPromptSubmit":
        return "THINKING"
    if event_name == "PreToolUse":
        return "WRITING" if tool_name == "apply_patch" else "RUNNING"
    if event_name == "PostToolUse":
        response_text = json.dumps(event.get("tool_response"), ensure_ascii=False).lower()
        if any(word in response_text for word in ("error", "failed", "exit code: 1", "exit status 1")):
            return "ERROR"
        return "THINKING"
    if event_name == "PermissionRequest":
        return "NEED_CONFIRM"
    if event_name == "Stop":
        return "DONE"
    if event_name in {"PreCompact", "PostCompact"}:
        return "THINKING"
    return None


def hook_session_id(event: dict) -> str | None:
    for key in ("session_id", "sessionId", "conversation_id", "conversationId", "thread_id", "threadId"):
        value = event.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def apply_agentcore_command(command: str, source: str, session_id: str | None = None) -> dict:
    normalized = normalize_agentcore_command(command)
    updated_at = now_iso()
    with AGENTCORE_STATE_LOCK:
        if normalized.startswith("TOKEN:"):
            AGENTCORE_STATE["tokenPercent"] = int(normalized.split(":", 1)[1])
        else:
            AGENTCORE_STATE["command"] = normalized
        AGENTCORE_STATE["updatedAt"] = updated_at
        AGENTCORE_STATE["source"] = source
        if session_id and not normalized.startswith("TOKEN:"):
            AGENTCORE_SESSION_STATES[session_id] = {
                "command": normalized,
                "updatedAt": updated_at,
                "source": source,
                "sessionId": session_id,
            }
        state = dict(AGENTCORE_STATE)
    record_agentcore_alert(normalized, updated_at, source, session_id)
    return state


def record_agentcore_alert(
    command: str,
    updated_at: str,
    source: str,
    session_id: str | None,
) -> None:
    if command not in {"NEED_CONFIRM", "DONE", "ERROR"}:
        return

    event_id = f"{session_id or 'global'}:{command}:{updated_at}"
    event = {
        "id": event_id,
        "command": command,
        "createdAt": updated_at,
        "source": source,
        "sessionId": session_id,
    }
    if command == "NEED_CONFIRM":
        event["title"] = "需要权限"
        event["message"] = "请回到这台电脑确认权限请求。"
    elif command == "DONE":
        event["title"] = "任务完成"
        event["message"] = "当前任务已完成。"
    elif command == "ERROR":
        event["title"] = "任务失败"
        event["message"] = "处理当前任务时发生错误。"

    with AGENTCORE_ALERTS_LOCK:
        AGENTCORE_ALERTS.append(event)
        prune_agentcore_alerts_locked()


def prune_agentcore_alerts_locked() -> None:
    kept: list[dict[str, object]] = []
    seen: set[str] = set()
    for event in reversed(AGENTCORE_ALERTS):
        event_id = event.get("id")
        if not isinstance(event_id, str) or event_id in seen:
            continue
        age = event_age_seconds(event.get("createdAt"))
        if isinstance(age, int) and age > AGENTCORE_ALERT_TTL_SECONDS:
            continue
        seen.add(event_id)
        kept.append(event)
    AGENTCORE_ALERTS[:] = list(reversed(kept[-50:]))


def recent_agentcore_alerts() -> list[dict[str, object]]:
    with AGENTCORE_ALERTS_LOCK:
        prune_agentcore_alerts_locked()
        return [dict(event) for event in AGENTCORE_ALERTS]


def current_manual_agentcore_state() -> dict:
    with AGENTCORE_STATE_LOCK:
        state = dict(AGENTCORE_STATE)
        session_states = [dict(item) for item in AGENTCORE_SESSION_STATES.values()]

    active_commands = {"THINKING", "WRITING", "RUNNING", "NEED_CONFIRM"}
    active_sessions = [
        item
        for item in session_states
        if item.get("command") in active_commands
        and (
            event_age_seconds(item.get("updatedAt")) is None
            or event_age_seconds(item.get("updatedAt")) <= 900
        )
    ]
    if active_sessions:
        return max(
            active_sessions,
            key=lambda item: parse_event_time(item.get("updatedAt"))
            or datetime.min.replace(tzinfo=timezone.utc),
        )

    command = state.get("command")
    updated_at = state.get("updatedAt")
    age = event_age_seconds(updated_at)
    if command in {"DONE", "ERROR"} and isinstance(age, int):
        if age > AGENTCORE_TERMINAL_IDLE_SECONDS:
            state["command"] = "IDLE"
            state["source"] = "auto_idle"
            state["autoIdle"] = True
    return state


def token_percent_from_rate_limits(rate_limits: dict | None) -> int | None:
    if not isinstance(rate_limits, dict):
        return None
    primary = rate_limits.get("primary")
    if isinstance(primary, dict) and isinstance(primary.get("remainingPercent"), (int, float)):
        return max(0, min(100, int(round(float(primary["remainingPercent"])))))
    return None


def build_agentcore_status(codex_status: dict) -> dict:
    activity = codex_status.get("activity") if isinstance(codex_status.get("activity"), dict) else {}
    rate_limits = codex_status.get("rateLimits") if isinstance(codex_status.get("rateLimits"), dict) else None
    log_command = agentcore_command_for_activity(activity)
    log_updated_at = activity.get("updatedAt")
    token_percent = token_percent_from_rate_limits(rate_limits)

    manual = current_manual_agentcore_state()
    manual_command = manual.get("command")
    manual_updated_at = manual.get("updatedAt")
    if isinstance(manual.get("tokenPercent"), int):
        token_percent = manual["tokenPercent"]

    log_is_active = log_command in {"THINKING", "WRITING", "RUNNING", "NEED_CONFIRM"}
    manual_is_active = manual_command in {"THINKING", "WRITING", "RUNNING", "NEED_CONFIRM"}
    log_is_terminal = log_command in {"DONE", "ERROR"}
    manual_is_terminal = manual_command in {"DONE", "ERROR"}
    log_terminal_at = activity.get("taskCompletedAt") if log_command == "DONE" else log_updated_at
    if not log_terminal_at:
        log_terminal_at = log_updated_at

    # An active signal from either source wins over a transient idle signal.
    # A newer terminal session-log signal must be allowed to clear stale hook activity.
    if log_is_terminal and (
        not manual_is_active
        or iso_is_newer(log_terminal_at, manual_updated_at)
        or not parse_event_time(manual_updated_at)
    ):
        command = log_command
        source = "codex_session_log"
        updated_at = log_terminal_at
    elif manual_is_terminal and iso_is_newer(manual_updated_at, log_terminal_at):
        command = manual_command
        source = manual.get("source") or "command_endpoint"
        updated_at = manual_updated_at
    elif manual_is_active and not log_is_active:
        command = manual_command
        source = manual.get("source") or "codex_hook"
        updated_at = manual_updated_at
    elif log_is_active and not manual_is_active:
        command = log_command
        source = "codex_session_log"
        updated_at = log_updated_at
    elif log_is_active and manual_is_active and iso_is_newer(manual_updated_at, log_updated_at):
        command = manual_command
        source = manual.get("source") or "codex_hook"
        updated_at = manual_updated_at
    elif log_is_active and manual_is_active:
        command = log_command
        source = "codex_session_log"
        updated_at = log_updated_at
    elif isinstance(manual_command, str) and iso_is_newer(manual_updated_at, log_updated_at):
        command = manual_command
        source = manual.get("source") or "command_endpoint"
        updated_at = manual_updated_at
    else:
        command = log_command
        source = "codex_session_log"
        updated_at = log_updated_at

    command, source, updated_at = stabilize_agentcore_output(
        command,
        source,
        updated_at,
        activity,
    )

    outbound = [command]
    if isinstance(token_percent, int):
        outbound.append(f"TOKEN:{token_percent}")

    return {
        "protocol": "AgentCore-Light",
        "command": command,
        "tokenPercent": token_percent,
        "outboundCommands": outbound,
        "source": source,
        "updatedAt": updated_at,
        "statusLabel": activity.get("label"),
        "statusDetail": activity.get("detail"),
        "events": recent_agentcore_alerts(),
        "supportedCommands": sorted(AGENTCORE_COMMANDS) + ["TOKEN:x"],
    }


def stabilize_agentcore_output(
    command: str,
    source: str,
    updated_at: str | None,
    activity: dict,
) -> tuple[str, str, str | None]:
    active_commands = {"THINKING", "WRITING", "RUNNING", "NEED_CONFIRM"}
    task_started_at = activity.get("taskStartedAt")
    task_completed_at = activity.get("taskCompletedAt")
    task_in_progress = bool(
        task_started_at
        and (
            not task_completed_at
            or iso_is_newer(task_started_at, task_completed_at)
        )
    )

    with AGENTCORE_OUTPUT_STATE_LOCK:
        previous_command = AGENTCORE_OUTPUT_STATE.get("command")
        previous_updated_at = AGENTCORE_OUTPUT_STATE.get("updatedAt")
        previous_age = event_age_seconds(previous_updated_at)
        transient_idle = (
            command == "IDLE"
            and previous_command in active_commands
            and (
                task_in_progress
                or previous_age is None
                or previous_age < AGENTCORE_TRANSIENT_IDLE_GRACE_SECONDS
            )
        )
        if transient_idle:
            return (
                str(previous_command),
                str(AGENTCORE_OUTPUT_STATE.get("source") or source),
                previous_updated_at if isinstance(previous_updated_at, str) else updated_at,
            )

        AGENTCORE_OUTPUT_STATE["command"] = command
        AGENTCORE_OUTPUT_STATE["source"] = source
        AGENTCORE_OUTPUT_STATE["updatedAt"] = updated_at or now_iso()
        return command, source, updated_at


def find_latest_codex_activity() -> dict:
    sessions_root = codex_home() / "sessions"
    if not sessions_root.exists():
        return {
            "status": "unknown",
            "label": "未知",
            "detail": "未找到 Codex 会话目录。",
            "source": "codex_session_log",
        }

    files = sorted(
        sessions_root.rglob("*.jsonl"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not files:
        return {
            "status": "unknown",
            "label": "未知",
            "detail": "未找到 Codex 会话日志。",
            "source": "codex_session_log",
        }

    candidates: list[tuple[dict, float]] = []
    for session_file in files[:6]:
        activity = read_codex_activity_file(session_file)
        try:
            modified_at = session_file.stat().st_mtime
        except OSError:
            modified_at = 0.0
        candidates.append((activity, modified_at))

    active_statuses = {"needs_permission", "running_tool", "thinking", "starting", "replying"}
    active_candidates = [
        candidate
        for candidate in candidates
        if candidate[0].get("status") in active_statuses
        and (
            candidate[0].get("ageSeconds") is None
            or candidate[0].get("ageSeconds") <= 1800
        )
    ]
    if active_candidates:
        activity_priority = {
            "needs_permission": 5,
            "running_tool": 4,
            "thinking": 3,
            "replying": 2,
            "starting": 1,
        }
        return max(
            active_candidates,
            key=lambda candidate: (
                candidate[1],
                activity_priority.get(candidate[0].get("status"), 0),
            ),
        )[0]
    return candidates[0][0]


def read_codex_activity_file(latest_file: Path) -> dict:
    try:
        lines = tail_lines(latest_file, max_bytes=CODEX_SESSION_TAIL_BYTES)
    except OSError as exc:
        return {
            "status": "error",
            "label": "读取失败",
            "detail": str(exc),
            "source": "codex_session_log",
        }

    last_task_started_at: str | None = None
    last_task_complete_at: str | None = None
    last_context: dict = {}
    last_relevant: dict | None = None
    last_permission_request: dict | None = None

    for line in lines:
        try:
            record = json.loads(line)
        except json.JSONDecodeError:
            continue

        payload = payload_for(record)
        payload_type = payload.get("type")
        timestamp = record.get("timestamp")

        if payload_type == "task_started":
            last_task_started_at = timestamp if isinstance(timestamp, str) else None
        elif payload_type == "task_complete":
            last_task_complete_at = timestamp if isinstance(timestamp, str) else None
        elif record.get("type") == "turn_context":
            last_context = payload

        if looks_like_permission_request(record):
            last_permission_request = record

        if record.get("type") in {"event_msg", "response_item", "turn_context"}:
            last_relevant = record

    task_in_progress = bool(
        last_task_started_at
        and (
            not last_task_complete_at
            or last_task_started_at > last_task_complete_at
        )
    )

    if last_task_started_at:
        if last_permission_request and not iso_is_newer(
            last_permission_request.get("timestamp"),
            last_task_started_at,
        ):
            last_permission_request = None
        if last_relevant and not iso_is_newer(
            last_relevant.get("timestamp"),
            last_task_started_at,
        ):
            last_relevant = None

    if last_task_started_at and last_task_complete_at and last_task_complete_at >= last_task_started_at:
        status, label, detail = "completed", "完成", "上一轮任务已经完成。"
        last_event = {"timestamp": last_task_complete_at, "type": "event_msg", "payloadType": "task_complete"}
    elif last_permission_request:
        status, label, detail = status_from_event(last_permission_request)
        payload = payload_for(last_permission_request)
        last_event = {
            "timestamp": last_permission_request.get("timestamp"),
            "type": last_permission_request.get("type"),
            "payloadType": safe_payload_type(last_permission_request),
            "toolName": payload.get("name") if isinstance(payload.get("name"), str) else None,
        }
    elif last_relevant:
        status, label, detail = status_from_event(last_relevant)
        payload = payload_for(last_relevant)
        last_event = {
            "timestamp": last_relevant.get("timestamp"),
            "type": last_relevant.get("type"),
            "payloadType": safe_payload_type(last_relevant),
            "toolName": payload.get("name") if isinstance(payload.get("name"), str) else None,
        }
    else:
        status, label, detail = "unknown", "未知", "还没有可解析的状态事件。"
        last_event = {}

    if task_in_progress and status in {"unknown", "idle", "completed"}:
        status, label, detail = "thinking", "思考中", "Codex 任务仍在进行中。"
        if not last_event:
            last_event = {
                "timestamp": last_task_started_at,
                "type": "event_msg",
                "payloadType": "task_started",
            }

    if not last_task_started_at and not last_relevant and not last_permission_request:
        status, label = "idle", "空闲"
        detail = "未发现当前任务。"

    mode = {
        "approvalPolicy": compact_value(last_context.get("approval_policy")),
        "approvalLabel": approval_policy_label(last_context.get("approval_policy")),
        "sandboxPolicy": compact_value(last_context.get("sandbox_policy")),
        "sandboxLabel": sandbox_policy_label(last_context.get("sandbox_policy")),
        "permissionProfile": permission_profile_label(last_context.get("permission_profile")),
        "collaborationMode": compact_value(last_context.get("collaboration_mode")),
        "model": compact_value(last_context.get("model")),
    }

    return {
        "status": status,
        "label": label,
        "detail": detail,
        "source": "codex_session_log",
        "sessionFile": latest_file.name,
        "updatedAt": last_event.get("timestamp"),
        "ageSeconds": event_age_seconds(last_event.get("timestamp")),
        "taskStartedAt": last_task_started_at,
        "taskCompletedAt": last_task_complete_at,
        "mode": mode,
        "lastEvent": last_event,
    }


def fetch_codex_status(force_direct_limits: bool = False) -> dict:
    codex_path = resolve_codex_cli_path()
    version = None
    errors: list[str] = []
    rate_limits = find_latest_codex_rate_limits(force_direct=force_direct_limits)
    token_usage = rate_limits.get("tokenUsage") if isinstance(rate_limits, dict) else None
    activity = find_latest_codex_activity()

    if codex_path:
        try:
            completed = subprocess.run(
                [codex_path, "--version"],
                capture_output=True,
                text=True,
                timeout=8,
                **hidden_subprocess_kwargs(),
            )
            output = (completed.stdout or completed.stderr).strip()
            if completed.returncode == 0 and output:
                version = output
            elif output:
                errors.append(output)
        except (subprocess.SubprocessError, OSError) as exc:
            errors.append(str(exc))

    return {
        "status": "ok" if codex_path and not errors else ("missing" if not codex_path else "error"),
        "installed": bool(codex_path),
        "path": codex_path,
        "version": version,
        "rateLimits": rate_limits,
        "tokenUsage": token_usage,
        "activity": activity,
        "message": (
            "已直接读取 Codex 当前 5h/weekly 额度。"
            if isinstance(rate_limits, dict) and rate_limits.get("source") == "codex_app_server"
            else "已从最新 Codex 会话日志读取 5h/weekly 额度。"
            if rate_limits
            else "Codex CLI 可用；尚未在本机会话日志里找到 5h/weekly 额度事件。"
            if codex_path
            else "未在 PATH 中找到 Codex CLI。"
        ),
        "errors": errors,
    }


def add_rate_limit_notification(notifications: list[dict], window: dict | None) -> None:
    if not window:
        return

    remaining = window.get("remainingPercent")
    label = window.get("label", "额度")
    if not isinstance(remaining, (int, float)):
        return

    if remaining <= 0:
        notifications.append(
            {
                "level": "critical",
                "title": f"Codex {label} 额度已用完",
                "message": "等待窗口重置后再继续使用。",
            }
        )
    elif remaining <= 10:
        notifications.append(
            {
                "level": "warning",
                "title": f"Codex {label} 额度偏低",
                "message": f"当前剩余约 {remaining:.0f}%。",
            }
        )


def add_activity_notification(notifications: list[dict], activity: dict | None) -> None:
    if not activity:
        return
    status = activity.get("status")
    if status == "needs_permission":
        notifications.append(
            {
                "level": "critical",
                "title": "Codex 等待权限",
                "message": "请回到电脑上的 Codex 终端确认权限请求。",
            }
        )
    elif status in {"thinking", "running_tool"}:
        label = activity.get("label") or "运行中"
        notifications.append(
            {
                "level": "info",
                "title": f"Codex {label}",
                "message": activity.get("detail") or "任务仍在进行。",
            }
        )


def build_notifications(openai_status: dict, codex_status: dict) -> list[dict]:
    notifications: list[dict] = []

    if codex_status["status"] == "missing":
        notifications.append(
            {
                "level": "warning",
                "title": "Codex CLI 未检测到",
                "message": "电脑上没有找到 codex 命令，当前只能查看 API 用量。",
            }
        )
    elif codex_status.get("rateLimits"):
        rate_limits = codex_status["rateLimits"]
        notifications.append(
            {
                "level": "info",
                "title": "Codex 额度已读取",
                "message": "已显示 5h 和 weekly 两个窗口的剩余百分比。",
            }
        )
        add_rate_limit_notification(notifications, rate_limits.get("primary"))
        add_rate_limit_notification(notifications, rate_limits.get("secondary"))
        add_activity_notification(notifications, codex_status.get("activity"))
    else:
        notifications.append(
            {
                "level": "warning",
                "title": "Codex 额度未找到",
                "message": "请先在本机运行一次 Codex，产生 token_count 事件后再刷新。",
            }
        )

    if openai_status["status"] == "error":
        notifications.append(
            {
                "level": "critical",
                "title": "OpenAI 查询失败",
                "message": "请检查密钥权限、组织 ID、网络和服务端日志。",
            }
        )

    budget = openai_status.get("budgetUsd")
    remaining = openai_status.get("remainingUsd")
    if isinstance(budget, (int, float)) and isinstance(remaining, (int, float)):
        if remaining <= 0:
            notifications.append(
                {
                    "level": "critical",
                    "title": "API 预算已用完",
                    "message": f"本月预算 ${budget:.2f} 已达到或超出。",
                }
            )
        elif remaining <= budget * 0.1:
            notifications.append(
                {
                    "level": "warning",
                    "title": "API 预算接近耗尽",
                    "message": f"本月剩余约 ${remaining:.2f}。",
                }
            )

    return notifications


def build_status(force_direct_limits: bool = False) -> dict:
    openai_status = fetch_openai_status_cached()
    codex_status = fetch_codex_status(force_direct_limits=force_direct_limits)
    agentcore_status = build_agentcore_status(codex_status)
    return {
        "app": {
            "name": "Codex LAN Monitor",
            "version": APP_VERSION,
        },
        "server": {
            "time": now_iso(),
            "hostname": socket.gethostname(),
            "pid": os.getpid(),
            "lanIps": get_lan_ips(),
        },
        "codex": codex_status,
        "agentCore": agentcore_status,
        "openaiApi": openai_status,
        "notifications": build_notifications(openai_status, codex_status),
    }


def build_mobile_status(force_direct_limits: bool = False) -> dict:
    codex_status = fetch_codex_status(force_direct_limits=force_direct_limits)
    agentcore_status = build_agentcore_status(codex_status)
    openai_status = fetch_openai_status_cached()
    notifications = []
    if codex_status.get("rateLimits"):
        rate_limits = codex_status["rateLimits"]
        notifications.append(
            {
                "level": "info",
                "title": "Codex 额度已读取",
                "message": "已显示 5h 和 weekly 两个窗口的剩余百分比。",
            }
        )
        add_rate_limit_notification(notifications, rate_limits.get("primary"))
        add_rate_limit_notification(notifications, rate_limits.get("secondary"))
    else:
        notifications.append(
            {
                "level": "warning",
                "title": "Codex 额度未找到",
                "message": "请先在本机运行一次 Codex，产生 token_count 事件后再刷新。",
            }
        )
    add_activity_notification(notifications, codex_status.get("activity"))
    if openai_status["status"] == "error":
        notifications.append(
            {
                "level": "critical",
                "title": "OpenAI 成本查询失败",
                "message": "请检查密钥权限、组织 ID、网络和服务端日志。",
            }
        )

    return {
        "app": {
            "name": "Codex LAN Monitor",
            "version": APP_VERSION,
        },
        "server": {
            "time": now_iso(),
            "hostname": socket.gethostname(),
            "pid": os.getpid(),
            "lanIps": get_lan_ips(),
        },
        "codex": codex_status,
        "agentCore": agentcore_status,
        "openaiApi": openai_status,
        "notifications": notifications,
    }


class LocalAgentHandler(BaseHTTPRequestHandler):
    server_version = f"CodexLanMonitor/{APP_VERSION}"

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.add_common_headers("text/plain")
        self.end_headers()

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if not self.is_authorized(parsed):
            self.send_json(
                {"error": "unauthorized", "message": "需要 LOCAL_AGENT_TOKEN。"},
                status=401,
            )
            return

        if parsed.path == "/api/agentcore/command":
            payload = self.read_json_or_text()
            try:
                command = payload.get("command") if isinstance(payload, dict) else str(payload)
                state = apply_agentcore_command(command, "command_endpoint")
                self.send_json({"ok": True, "state": state})
            except (TypeError, ValueError) as exc:
                self.send_json({"ok": False, "error": str(exc)}, status=400)
            return

        if parsed.path == "/api/agentcore/event":
            payload = self.read_json_or_text()
            if not isinstance(payload, dict):
                self.send_json({"ok": False, "error": "event payload must be JSON"}, status=400)
                return
            command = command_for_hook_event(payload)
            if not command:
                self.send_json({"ok": True, "command": None})
                return
            state = apply_agentcore_command(
                command,
                "codex_hook",
                session_id=hook_session_id(payload),
            )
            self.send_json({"ok": True, "command": command, "state": state})
            return

        self.send_json({"error": "not_found"}, status=404)

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        params = urllib.parse.parse_qs(parsed.query)
        force_direct_limits = params.get("fresh", [""])[0].lower() in {"1", "true", "yes"}

        if path == "/api/health":
            self.send_json({"ok": True, "time": now_iso(), "pid": os.getpid()})
            return

        if path == "/api/status":
            if not self.is_authorized(parsed):
                self.send_json(
                    {"error": "unauthorized", "message": "需要 LOCAL_AGENT_TOKEN。"},
                    status=401,
                )
                return
            self.send_json(build_status(force_direct_limits=force_direct_limits))
            return

        if path == "/api/mobile-status":
            if not self.is_authorized(parsed):
                self.send_json(
                    {"error": "unauthorized", "message": "需要 LOCAL_AGENT_TOKEN。"},
                    status=401,
                )
                return
            self.send_json(build_mobile_status(force_direct_limits=force_direct_limits))
            return

        if path == "/api/agentcore/status":
            if not self.is_authorized(parsed):
                self.send_json(
                    {"error": "unauthorized", "message": "需要 LOCAL_AGENT_TOKEN。"},
                    status=401,
                )
                return
            status = build_status(force_direct_limits=force_direct_limits)
            self.send_json(status["agentCore"])
            return

        self.serve_static(path)

    def read_json_or_text(self) -> object:
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""
        content_type = self.headers.get("Content-Type", "")
        if "application/json" in content_type:
            return json.loads(raw or "{}")
        if raw.strip().startswith("{"):
            try:
                return json.loads(raw)
            except json.JSONDecodeError:
                pass
        return raw.strip()

    def is_authorized(self, parsed: urllib.parse.ParseResult) -> bool:
        token = os.getenv("LOCAL_AGENT_TOKEN")
        if not token:
            return True

        params = urllib.parse.parse_qs(parsed.query)
        query_token = params.get("token", [""])[0]
        header_token = self.headers.get("X-Agent-Token", "")
        bearer = self.headers.get("Authorization", "")
        if bearer.lower().startswith("bearer "):
            bearer = bearer[7:].strip()

        return token in {query_token, header_token, bearer}

    def send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status)
        self.add_common_headers("application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def add_common_headers(self, content_type: str) -> None:
        self.send_header("Content-Type", content_type)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Authorization, X-Agent-Token")
        self.send_header("Cache-Control", "no-store")

    def serve_static(self, path: str) -> None:
        if path in {"", "/"}:
            file_path = WEB_ROOT / "index.html"
        else:
            safe_path = Path(urllib.parse.unquote(path.lstrip("/")))
            file_path = (WEB_ROOT / safe_path).resolve()
            if not str(file_path).startswith(str(WEB_ROOT.resolve())):
                self.send_error(403)
                return

        if not file_path.exists() or not file_path.is_file():
            self.send_error(404)
            return

        body = file_path.read_bytes()
        if file_path.name == "index.html":
            body = body.replace(b"__STATIC_VERSION__", STATIC_VERSION.encode("ascii"))
        content_type = mimetypes.guess_type(str(file_path))[0] or "application/octet-stream"
        if file_path.suffix in {".html", ".css", ".js"}:
            content_type += "; charset=utf-8"

        self.send_response(200)
        self.add_common_headers(content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        print(f"[{now_iso()}] {self.client_address[0]} {format % args}")


class ExclusiveThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = False
    daemon_threads = True

    def server_bind(self) -> None:
        if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
            self.socket.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        super().server_bind()


def main() -> None:
    parser = argparse.ArgumentParser(description="Codex/OpenAI local LAN monitor")
    parser.add_argument("--host", default=os.getenv("HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PORT", DEFAULT_PORT)))
    args = parser.parse_args()

    load_dotenv(ROOT / ".env")

    httpd = ExclusiveThreadingHTTPServer((args.host, args.port), LocalAgentHandler)
    print(f"Codex LAN Monitor {APP_VERSION}")
    print(f"Listening on {args.host}:{args.port}")
    print("Local URL:")
    print(f"  http://127.0.0.1:{args.port}/")
    print("Phone URLs:")
    for ip in get_lan_ips():
        print(f"  http://{ip}:{args.port}/")
    if os.getenv("LOCAL_AGENT_TOKEN"):
        print("LOCAL_AGENT_TOKEN is enabled. Add ?token=... or enter it in the page.")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
