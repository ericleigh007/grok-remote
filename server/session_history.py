"""Load recent transcript lines from Grok's on-disk session store for phone UI.

`session/resume` keeps full model context on the agent but does not stream history
to clients. We read ~/.grok/sessions/**/<session_id>/chat_history.jsonl so the
Android/web UI can show the last N user/assistant turns.
"""

from __future__ import annotations

import json
import logging
import os
import re
from pathlib import Path
from typing import Any, Optional
from urllib.parse import unquote

log = logging.getLogger("session_history")

USER_QUERY_RE = re.compile(
    r"<user_query>\s*([\s\S]*?)\s*</user_query>",
    re.IGNORECASE,
)
TAG_RE = re.compile(r"</?[a-zA-Z_][\w:-]*(?:\s[^>]*)?>")


def grok_home() -> Path:
    return Path(os.environ.get("GROK_HOME") or (Path.home() / ".grok"))


def _decode_cwd_folder(name: str) -> str:
    return unquote(name).replace("/", "\\") if os.name == "nt" else unquote(name)


def list_on_disk_sessions(*, min_bytes: int = 200, limit: Optional[int] = None) -> list[dict[str, Any]]:
    """Real Grok sessions on disk (not the config.json project list). Newest first."""
    root = grok_home() / "sessions"
    if not root.is_dir():
        return []
    found: list[dict[str, Any]] = []
    for cwd_dir in root.iterdir():
        if not cwd_dir.is_dir():
            continue
        cwd_fallback = _decode_cwd_folder(cwd_dir.name)
        for sess in cwd_dir.iterdir():
            if not sess.is_dir():
                continue
            hist = sess / "chat_history.jsonl"
            try:
                if not hist.is_file() or hist.stat().st_size < min_bytes:
                    continue
            except OSError:
                continue
            summary: dict[str, Any] = {}
            sp = sess / "summary.json"
            if sp.is_file():
                try:
                    summary = json.loads(sp.read_text(encoding="utf-8"))
                except Exception:
                    summary = {}
            info = summary.get("info") if isinstance(summary.get("info"), dict) else {}
            cwd = str(info.get("cwd") or cwd_fallback)
            title = (
                summary.get("generated_title")
                or summary.get("session_summary")
                or Path(cwd).name
                or sess.name[:8]
            )
            updated = summary.get("updated_at") or summary.get("last_active_at") or ""
            mtime = hist.stat().st_mtime
            nmsg = int(summary.get("num_chat_messages") or 0)
            found.append(
                {
                    "sessionId": sess.name,
                    "title": str(title),
                    "cwd": cwd,
                    "updatedAt": str(updated),
                    "mtime": mtime,
                    "messageCount": nmsg,
                    "preview": str(summary.get("last_turn_summary") or "")[:140],
                }
            )
    found.sort(key=lambda r: (r.get("updatedAt") or "", r.get("mtime") or 0), reverse=True)
    for row in found:
        row.pop("mtime", None)
    if limit is None:
        return found
    return found[: max(1, int(limit))]


def find_session_dir(session_id: str) -> Optional[Path]:
    """Locate session folder by UUID under ~/.grok/sessions."""
    root = grok_home() / "sessions"
    if not root.is_dir():
        return None
    # Session ids are directory names one level under encoded cwd folders
    direct = list(root.glob(f"*/{session_id}"))
    for p in direct:
        if p.is_dir() and (p / "chat_history.jsonl").is_file():
            return p
    # Fallback: deeper search (rare)
    for p in root.rglob(session_id):
        if p.is_dir() and (p / "chat_history.jsonl").is_file():
            return p
    return None


def _extract_text_blocks(content: Any) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, str):
                parts.append(block)
            elif isinstance(block, dict):
                if block.get("type") in ("text", "summary_text", "output_text"):
                    parts.append(str(block.get("text") or ""))
                elif "text" in block:
                    parts.append(str(block.get("text") or ""))
        return "\n".join(p for p in parts if p)
    if isinstance(content, dict):
        return _extract_text_blocks(content.get("text") or content.get("content"))
    return str(content)


def _clean_user_text(text: str) -> str:
    text = text.strip()
    m = USER_QUERY_RE.search(text)
    if m:
        return m.group(1).strip()
    # Drop tooling/system noise often embedded in user turns
    lower = text.lower()
    if (
        text.startswith("<user_info>")
        or text.startswith("<git_status>")
        or text.startswith("<system-reminder>")
        or "mcp servers that failed" in lower
        or "mcp servers connected" in lower
        or lower.startswith("<system>")
    ):
        return ""
    # Strip residual XML-ish dumps for readability
    if "<" in text and ">" in text and len(text) > 1500:
        # Keep short tagged messages; drop large blobs
        return ""
    return text


def _clean_assistant_text(text: str) -> str:
    return text.strip()


def load_recent_messages(
    session_id: str,
    *,
    limit: int = 40,
    include_thoughts: bool = True,
) -> list[dict[str, Any]]:
    """Return newest-last list of {role, text} for UI (max `limit` items)."""
    if limit <= 0:
        return []
    session_dir = find_session_dir(session_id)
    if not session_dir:
        log.info("no on-disk session dir for %s", session_id)
        return []

    history_path = session_dir / "chat_history.jsonl"
    if not history_path.is_file():
        return []

    collected: list[dict[str, Any]] = []
    try:
        # Read all lines — files can be large; stream and keep a ring of candidates
        ring: list[dict[str, Any]] = []
        with history_path.open("r", encoding="utf-8", errors="replace") as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                kind = row.get("type") or row.get("role")
                if kind == "user":
                    text = _clean_user_text(_extract_text_blocks(row.get("content")))
                    if text:
                        ring.append({"role": "user", "text": text})
                elif kind == "assistant":
                    text = _clean_assistant_text(_extract_text_blocks(row.get("content")))
                    if text:
                        ring.append({"role": "assistant", "text": text})
                elif include_thoughts and kind in ("reasoning", "thought"):
                    # Prefer short summary over encrypted blobs
                    summary = row.get("summary")
                    text = _extract_text_blocks(summary) if summary else ""
                    if not text:
                        continue
                    if len(text) > 2000:
                        text = text[:2000] + "…"
                    ring.append({"role": "thought", "text": text, "streaming": False})
                # skip system / tool_result noise for phone UI
        collected = ring[-limit:]
    except Exception:
        log.exception("failed reading history for %s", session_id)
        return []

    log.info(
        "loaded %d history messages for %s from %s",
        len(collected),
        session_id,
        history_path,
    )
    return collected
