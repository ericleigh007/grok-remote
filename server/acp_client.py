"""ACP client for Grok Build.

Preferred transport: WebSocket to a long-lived `grok agent serve` process.
Fallback: managed `grok agent stdio` child with auto-restart.

The phone UI talks only to our bridge; this client is the bridge↔agent link.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional
from urllib.parse import urlencode

from session_history import load_recent_messages

log = logging.getLogger("acp")

EventHandler = Callable[[dict[str, Any]], Awaitable[None] | None]


@dataclass
class SessionInfo:
    session_id: str
    cwd: str
    title: str = "Session"
    busy: bool = False
    messages: list[dict[str, Any]] = field(default_factory=list)
    # How this session was opened (for reconnect re-attach)
    resume_id: Optional[str] = None  # original session id if resumed
    replay: bool = False


class AcpClient:
    def __init__(
        self,
        *,
        always_approve: bool = True,
        model: Optional[str] = None,
        grok_bin: str = "grok",
        transport: str = "websocket",  # websocket | stdio
        ws_url: str = "ws://127.0.0.1:2419/ws",
        ws_secret: str = "",
        auto_reconnect: bool = True,
        reconnect_delay_sec: float = 2.0,
        history_limit: int = 40,
        history_include_thoughts: bool = False,
    ) -> None:
        self.always_approve = always_approve
        self.model = model
        self.grok_bin = grok_bin
        self.transport = (transport or "websocket").lower()
        self.ws_url = ws_url
        self.ws_secret = ws_secret
        self.auto_reconnect = auto_reconnect
        self.reconnect_delay_sec = reconnect_delay_sec
        self.history_limit = max(0, int(history_limit))
        self.history_include_thoughts = history_include_thoughts

        self._proc: Optional[asyncio.subprocess.Process] = None
        self._ws: Any = None  # websockets connection
        self._reader_task: Optional[asyncio.Task] = None
        self._stderr_task: Optional[asyncio.Task] = None
        self._watchdog_task: Optional[asyncio.Task] = None
        self._next_id = 0
        self._pending: dict[int, asyncio.Future] = {}
        self._lock = asyncio.Lock()
        self._ready = asyncio.Event()
        self._stopping = False
        self._connect_lock = asyncio.Lock()
        self.sessions: dict[str, SessionInfo] = {}
        self._event_handlers: list[EventHandler] = []
        self.agent_info: dict[str, Any] = {}
        self.reconnect_count = 0
        self.last_error: Optional[str] = None

    # --- public status -------------------------------------------------

    @property
    def agent_alive(self) -> bool:
        if not self._ready.is_set():
            return False
        if self.transport == "websocket":
            if self._ws is None:
                return False
            try:
                # websockets >= 10
                from websockets.protocol import State

                return self._ws.state is State.OPEN
            except Exception:
                return True  # ready set + ws object present
        return self._proc is not None and self._proc.returncode is None

    def on_event(self, handler: EventHandler) -> None:
        self._event_handlers.append(handler)

    # --- lifecycle -----------------------------------------------------

    async def start(self) -> None:
        self._stopping = False
        await self._connect()
        if self.auto_reconnect and (
            not self._watchdog_task or self._watchdog_task.done()
        ):
            self._watchdog_task = asyncio.create_task(self._watchdog_loop())

    async def stop(self) -> None:
        self._stopping = True
        self._ready.clear()
        if self._watchdog_task:
            self._watchdog_task.cancel()
            try:
                await self._watchdog_task
            except asyncio.CancelledError:
                pass
            self._watchdog_task = None
        await self._teardown_transport()

    async def ensure_ready(self) -> None:
        if self.agent_alive:
            return
        # If another coroutine is mid-connect (handshake in progress), wait for it
        # instead of re-entering _connect and deadlocking on _connect_lock.
        if self._connect_lock.locked():
            await asyncio.wait_for(self._ready.wait(), timeout=90)
            return
        await self._connect()
        await asyncio.wait_for(self._ready.wait(), timeout=90)

    async def _connect(self) -> None:
        async with self._connect_lock:
            if self.agent_alive:
                return
            self._ready.clear()
            await self._teardown_transport()
            try:
                if self.transport == "websocket":
                    await self._connect_ws()
                else:
                    await self._connect_stdio()
                await self._handshake()
                self._ready.set()
                self.last_error = None
                log.info(
                    "ACP ready transport=%s protocol=%s",
                    self.transport,
                    (self.agent_info or {}).get("protocolVersion"),
                )
                await self._emit(
                    {
                        "type": "agent_status",
                        "alive": True,
                        "transport": self.transport,
                        "reconnectCount": self.reconnect_count,
                    }
                )
            except Exception as exc:
                self.last_error = str(exc)
                log.exception("ACP connect failed (%s)", self.transport)
                self._ready.clear()
                await self._emit(
                    {
                        "type": "agent_status",
                        "alive": False,
                        "transport": self.transport,
                        "error": str(exc),
                    }
                )
                raise

    async def _connect_ws(self) -> None:
        import websockets

        url = self.ws_url
        if self.ws_secret:
            sep = "&" if "?" in url else "?"
            # server-key query param (printed by grok agent serve)
            if "server-key=" not in url:
                url = f"{url}{sep}{urlencode({'server-key': self.ws_secret})}"
        log.info("connecting ACP websocket: %s", url.split("server-key=")[0] + "server-key=***")
        self._ws = await websockets.connect(
            url,
            open_timeout=20,
            ping_interval=20,
            ping_timeout=20,
            max_size=16 * 1024 * 1024,
        )
        self._reader_task = asyncio.create_task(self._ws_read_loop())

    async def _connect_stdio(self) -> None:
        cmd = [self.grok_bin, "agent"]
        if self.always_approve:
            cmd.append("--always-approve")
        if self.model:
            cmd.extend(["-m", self.model])
        cmd.append("stdio")
        log.info("starting agent stdio: %s", " ".join(cmd))
        self._proc = await asyncio.create_subprocess_exec(
            *cmd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env={**os.environ, "GROK_DISABLE_AUTOUPDATER": "1"},
        )
        self._reader_task = asyncio.create_task(self._stdio_read_loop())
        self._stderr_task = asyncio.create_task(self._stderr_loop())

    async def _handshake(self) -> None:
        init = await self.request(
            "initialize",
            {
                "protocolVersion": 1,
                "clientCapabilities": {
                    "fs": {"readTextFile": True, "writeTextFile": True},
                    "terminal": False,
                },
                "clientInfo": {"name": "grok-remote", "version": "0.2.0"},
            },
            timeout=60,
        )
        self.agent_info = init or {}
        await self.notify("notifications/initialized", {})

    async def _teardown_transport(self) -> None:
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(RuntimeError("agent disconnected"))
        self._pending.clear()

        if self._reader_task:
            self._reader_task.cancel()
            try:
                await self._reader_task
            except asyncio.CancelledError:
                pass
            self._reader_task = None
        if self._stderr_task:
            self._stderr_task.cancel()
            try:
                await self._stderr_task
            except asyncio.CancelledError:
                pass
            self._stderr_task = None

        if self._ws is not None:
            try:
                await self._ws.close()
            except Exception:
                pass
            self._ws = None

        if self._proc and self._proc.returncode is None:
            try:
                self._proc.terminate()
                await asyncio.wait_for(self._proc.wait(), timeout=5)
            except Exception:
                try:
                    self._proc.kill()
                except Exception:
                    pass
        self._proc = None

    async def _watchdog_loop(self) -> None:
        while not self._stopping:
            await asyncio.sleep(self.reconnect_delay_sec)
            if self._stopping:
                break
            if self.agent_alive:
                continue
            # Transport dropped
            self._ready.clear()
            self.reconnect_count += 1
            log.warning(
                "agent transport down — reconnect #%s (transport=%s)",
                self.reconnect_count,
                self.transport,
            )
            await self._emit(
                {
                    "type": "agent_status",
                    "alive": False,
                    "reconnecting": True,
                    "reconnectCount": self.reconnect_count,
                }
            )
            try:
                await self._connect()
                await self._reattach_sessions()
            except Exception as exc:
                self.last_error = str(exc)
                log.warning("reconnect failed: %s", exc)

    async def _reattach_sessions(self) -> None:
        """Re-bind known sessions after reconnect (resume preferred)."""
        snapshot = list(self.sessions.values())
        if not snapshot:
            return
        log.info("re-attaching %d session(s)", len(snapshot))
        for info in snapshot:
            try:
                sid = info.resume_id or info.session_id
                method = "session/load" if info.replay else "session/resume"
                await self.request(
                    method,
                    {
                        "sessionId": sid,
                        "cwd": info.cwd,
                        "mcpServers": [],
                        "_meta": {"yoloMode": True} if self.always_approve else {},
                    },
                    timeout=120,
                )
                info.busy = False
                info.messages.append(
                    {
                        "role": "system",
                        "text": "Agent reconnected — session re-attached. You can continue.",
                    }
                )
                await self._emit(
                    {
                        "type": "session_loaded",
                        "sessionId": info.session_id,
                        "cwd": info.cwd,
                        "title": info.title,
                        "messages": info.messages[-3:],
                        "reconnected": True,
                    }
                )
            except Exception as exc:
                log.warning("re-attach failed for %s: %s", info.session_id, exc)
                info.messages.append(
                    {
                        "role": "system",
                        "text": f"Could not re-attach session after reconnect: {exc}",
                    }
                )

    # --- sessions / prompts --------------------------------------------

    async def create_session(self, cwd: str, title: str | None = None) -> SessionInfo:
        await self.ensure_ready()
        cwd_path = str(Path(cwd).expanduser().resolve())
        result = await self.request(
            "session/new",
            {
                "cwd": cwd_path,
                "mcpServers": [],
                "_meta": {"yoloMode": True} if self.always_approve else {},
            },
            timeout=120,
        )
        session_id = result["sessionId"]
        info = SessionInfo(
            session_id=session_id,
            cwd=cwd_path,
            title=title or Path(cwd_path).name,
            resume_id=session_id,
        )
        self.sessions[session_id] = info
        await self._emit(
            {
                "type": "session_created",
                "sessionId": session_id,
                "cwd": cwd_path,
                "title": info.title,
            }
        )
        return info

    async def load_session(
        self,
        session_id: str,
        cwd: str,
        *,
        title: str | None = None,
        replay: bool = False,
    ) -> SessionInfo:
        await self.ensure_ready()
        cwd_path = str(Path(cwd).expanduser().resolve())
        method = "session/load" if replay else "session/resume"
        params = {
            "sessionId": session_id,
            "cwd": cwd_path,
            "mcpServers": [],
            "_meta": {"yoloMode": True} if self.always_approve else {},
        }
        try:
            await self.request(method, params, timeout=300 if replay else 120)
        except Exception as exc:
            fallback = "session/resume" if replay else "session/load"
            log.warning("%s failed (%s); trying %s", method, exc, fallback)
            await self.request(
                fallback,
                {
                    "sessionId": session_id,
                    "cwd": cwd_path,
                    "mcpServers": [],
                    "_meta": {"yoloMode": True} if self.always_approve else {},
                },
                timeout=300,
            )

        info = SessionInfo(
            session_id=session_id,
            cwd=cwd_path,
            title=title or Path(cwd_path).name,
            resume_id=session_id,
            replay=replay,
        )
        # Agent has full context via resume/load. Also hydrate UI with recent turns
        # from on-disk chat_history.jsonl (not the full 100+ turn dump).
        recent = load_recent_messages(
            session_id,
            limit=self.history_limit,
            include_thoughts=self.history_include_thoughts,
        )
        if recent:
            info.messages.extend(recent)
            info.messages.append(
                {
                    "role": "system",
                    "text": (
                        f"Showing last {len(recent)} messages from this session. "
                        "The agent has the full conversation context on the PC."
                    ),
                }
            )
        else:
            info.messages.append(
                {
                    "role": "system",
                    "text": (
                        f"Resumed session {session_id[:8]}… — could not load local transcript "
                        "for display; the agent still has full context. New messages continue that thread."
                    ),
                }
            )
        self.sessions[session_id] = info
        await self._emit(
            {
                "type": "session_loaded",
                "sessionId": session_id,
                "cwd": cwd_path,
                "title": info.title,
                "messages": info.messages,
            }
        )
        return info

    async def prompt(self, session_id: str, text: str) -> dict[str, Any]:
        await self.ensure_ready()
        if session_id not in self.sessions:
            raise KeyError(f"unknown session: {session_id}")
        info = self.sessions[session_id]
        info.busy = True
        info.messages.append({"role": "user", "text": text})
        await self._emit(
            {"type": "user_message", "sessionId": session_id, "text": text}
        )
        result: dict[str, Any] = {}
        try:
            result = (
                await self.request(
                    "session/prompt",
                    {
                        "sessionId": session_id,
                        "prompt": [{"type": "text", "text": text}],
                    },
                    timeout=3600,
                )
                or {}
            )
            return result
        finally:
            info.busy = False
            msgs = info.messages
            if msgs and msgs[-1].get("role") == "assistant":
                msgs[-1]["streaming"] = False
            await self._emit(
                {
                    "type": "turn_complete",
                    "sessionId": session_id,
                    "stopReason": result.get("stopReason"),
                }
            )

    async def cancel(self, session_id: str) -> None:
        await self.notify("session/cancel", {"sessionId": session_id})

    # --- JSON-RPC transport --------------------------------------------

    async def request(
        self,
        method: str,
        params: Optional[dict[str, Any]] = None,
        *,
        timeout: float = 120,
    ) -> Any:
        await self._ensure_transport()
        self._next_id += 1
        req_id = self._next_id
        msg: dict[str, Any] = {
            "jsonrpc": "2.0",
            "id": req_id,
            "method": method,
        }
        if params is not None:
            msg["params"] = params
        loop = asyncio.get_running_loop()
        fut: asyncio.Future = loop.create_future()
        self._pending[req_id] = fut
        await self._write(msg)
        try:
            return await asyncio.wait_for(fut, timeout=timeout)
        except Exception:
            self._pending.pop(req_id, None)
            raise

    async def notify(self, method: str, params: Optional[dict[str, Any]] = None) -> None:
        await self._ensure_transport()
        msg: dict[str, Any] = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        await self._write(msg)

    async def _ensure_transport(self) -> None:
        # Do not require _ready here — handshake runs before _ready is set.
        if self.transport == "websocket":
            if self._ws is None:
                if self._connect_lock.locked():
                    raise RuntimeError("agent websocket still connecting")
                await self.ensure_ready()
            if self._ws is None:
                raise RuntimeError("agent websocket is not connected")
        else:
            if not self._proc or self._proc.returncode is not None or not self._proc.stdin:
                if self._connect_lock.locked():
                    raise RuntimeError("agent process still starting")
                await self.ensure_ready()
            if not self._proc or self._proc.returncode is not None or not self._proc.stdin:
                raise RuntimeError("agent process is not running")

    async def _write(self, msg: dict[str, Any]) -> None:
        data = json.dumps(msg, ensure_ascii=False)
        async with self._lock:
            if self.transport == "websocket":
                assert self._ws is not None
                await self._ws.send(data)
            else:
                assert self._proc and self._proc.stdin
                self._proc.stdin.write((data + "\n").encode("utf-8"))
                await self._proc.stdin.drain()

    async def _ws_read_loop(self) -> None:
        assert self._ws is not None
        try:
            async for raw in self._ws:
                if isinstance(raw, bytes):
                    raw = raw.decode("utf-8", errors="replace")
                for line in str(raw).splitlines():
                    line = line.strip()
                    if not line:
                        continue
                    try:
                        msg = json.loads(line)
                    except json.JSONDecodeError:
                        log.warning("bad JSON from agent ws: %s", line[:200])
                        continue
                    await self._dispatch(msg)
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.warning("websocket read loop ended: %s", exc)
        finally:
            self._ready.clear()
            self._ws = None

    async def _stdio_read_loop(self) -> None:
        assert self._proc and self._proc.stdout
        try:
            while True:
                line = await self._proc.stdout.readline()
                if not line:
                    log.warning("agent stdout closed")
                    break
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    log.warning("bad JSON from agent: %s", line[:200])
                    continue
                await self._dispatch(msg)
        except asyncio.CancelledError:
            raise
        finally:
            self._ready.clear()

    async def _stderr_loop(self) -> None:
        assert self._proc and self._proc.stderr
        while True:
            line = await self._proc.stderr.readline()
            if not line:
                break
            log.debug("agent stderr: %s", line.decode(errors="replace").rstrip())

    async def _dispatch(self, msg: dict[str, Any]) -> None:
        if "id" in msg and ("result" in msg or "error" in msg) and "method" not in msg:
            fut = self._pending.pop(msg["id"], None)
            if fut and not fut.done():
                if "error" in msg:
                    fut.set_exception(RuntimeError(json.dumps(msg["error"])))
                else:
                    fut.set_result(msg.get("result"))
            return

        method = msg.get("method")
        if not method:
            return
        if "id" in msg and method:
            await self._handle_agent_request(msg)
            return
        await self._handle_notification(msg)

    async def _handle_agent_request(self, msg: dict[str, Any]) -> None:
        method = msg["method"]
        params = msg.get("params") or {}
        req_id = msg["id"]
        try:
            if method == "session/request_permission":
                result = {
                    "outcome": {"outcome": "selected", "optionId": "allow-once"}
                }
                options = (params.get("options") or []) if isinstance(params, dict) else []
                for opt in options:
                    oid = opt.get("optionId") or opt.get("id")
                    if oid in (
                        "allow_always",
                        "allow-always",
                        "allow_once",
                        "allow-once",
                        "allow",
                    ):
                        result = {
                            "outcome": {"outcome": "selected", "optionId": oid}
                        }
                        break
                await self._respond(req_id, result)
                return

            if method == "fs/read_text_file":
                path = Path(params["path"])
                text = path.read_text(encoding="utf-8", errors="replace")
                line = params.get("line")
                limit = params.get("limit")
                if line is not None:
                    lines = text.splitlines(keepends=True)
                    start = max(int(line) - 1, 0)
                    end = start + int(limit) if limit is not None else len(lines)
                    text = "".join(lines[start:end])
                await self._respond(req_id, {"content": text})
                return

            if method == "fs/write_text_file":
                path = Path(params["path"])
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(params.get("content", ""), encoding="utf-8")
                await self._respond(req_id, {})
                return

            log.warning("unsupported agent request: %s", method)
            await self._respond_error(req_id, -32601, f"Method not found: {method}")
        except Exception as exc:
            log.exception("agent request failed: %s", method)
            await self._respond_error(req_id, -32000, str(exc))

    async def _respond(self, req_id: Any, result: Any) -> None:
        await self._write({"jsonrpc": "2.0", "id": req_id, "result": result})

    async def _respond_error(self, req_id: Any, code: int, message: str) -> None:
        await self._write(
            {
                "jsonrpc": "2.0",
                "id": req_id,
                "error": {"code": code, "message": message},
            }
        )

    async def _handle_notification(self, msg: dict[str, Any]) -> None:
        method = msg.get("method")
        params = msg.get("params") or {}

        if method == "session/update":
            session_id = params.get("sessionId")
            update = params.get("update") or {}
            kind = update.get("sessionUpdate")
            event: dict[str, Any] = {
                "type": "session_update",
                "sessionId": session_id,
                "updateType": kind,
                "update": update,
            }
            if session_id in self.sessions:
                info = self.sessions[session_id]
                if kind == "agent_message_chunk":
                    content = update.get("content") or {}
                    text = content.get("text") or ""
                    if text:
                        if (
                            not info.messages
                            or info.messages[-1].get("role") != "assistant"
                            or info.messages[-1].get("streaming") is not True
                        ):
                            info.messages.append(
                                {"role": "assistant", "text": text, "streaming": True}
                            )
                        else:
                            info.messages[-1]["text"] += text
                        event["text"] = text
                elif kind == "agent_thought_chunk":
                    content = update.get("content") or {}
                    text = content.get("text") or ""
                    event["thought"] = text
                    # Persist thought stream for clients (Android/web history)
                    if text and session_id in self.sessions:
                        info = self.sessions[session_id]
                        if (
                            not info.messages
                            or info.messages[-1].get("role") != "thought"
                            or info.messages[-1].get("streaming") is not True
                        ):
                            info.messages.append(
                                {"role": "thought", "text": text, "streaming": True}
                            )
                        else:
                            info.messages[-1]["text"] += text
                elif kind == "tool_call":
                    event["tool"] = {
                        "title": update.get("title"),
                        "kind": update.get("kind"),
                        "status": update.get("status"),
                        "toolCallId": update.get("toolCallId"),
                    }
                elif kind == "tool_call_update":
                    event["tool"] = {
                        "status": update.get("status"),
                        "toolCallId": update.get("toolCallId"),
                    }
            await self._emit(event)
            return

        if method and (
            method.startswith("_x.ai/")
            or method.startswith("x.ai/")
            or method in ("_x.ai/session_notification",)
        ):
            await self._emit(
                {
                    "type": "agent_notification",
                    "method": method,
                    "params": params,
                }
            )
            if method in ("_x.ai/session/prompt_complete", "_x.ai/session_notification"):
                update = params.get("update") or {}
                if method == "_x.ai/session/prompt_complete" or update.get(
                    "sessionUpdate"
                ) in ("turn_completed", "response_completed"):
                    sid = params.get("sessionId")
                    if sid in self.sessions:
                        msgs = self.sessions[sid].messages
                        if msgs and msgs[-1].get("role") == "assistant":
                            msgs[-1]["streaming"] = False
                    await self._emit(
                        {
                            "type": "turn_complete",
                            "sessionId": sid,
                            "stopReason": params.get("stopReason")
                            or update.get("stop_reason"),
                        }
                    )

    async def _emit(self, event: dict[str, Any]) -> None:
        for handler in list(self._event_handlers):
            try:
                res = handler(event)
                if asyncio.iscoroutine(res):
                    await res
            except Exception:
                log.exception("event handler failed")
