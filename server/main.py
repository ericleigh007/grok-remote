"""Grok Remote bridge: mobile web UI + multi-session ACP client."""

from __future__ import annotations

import asyncio
import io
import json
import logging
import os
import secrets
import socket
import subprocess
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional
from urllib.parse import urlencode

import qrcode
from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, HTMLResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from acp_client import AcpClient

ROOT = Path(__file__).resolve().parent.parent
WEB_DIR = ROOT / "web"
CONFIG_PATH = ROOT / "config.json"
RELEASES_DIR = ROOT / "releases"
# Preferred published name + gradle debug output fallback
APK_PUBLISHED = RELEASES_DIR / "grok-remote-debug.apk"
APK_BUILD_FALLBACK = (
    ROOT / "android" / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)
log = logging.getLogger("grok-remote")

load_dotenv(ROOT / ".env")


def load_config() -> dict[str, Any]:
    cfg: dict[str, Any] = {
        "bind_host": "0.0.0.0",
        "bind_port": 8787,
        "remote_token": os.environ.get("GROK_REMOTE_TOKEN") or "",
        "default_cwd": str(ROOT),
        "projects": [],
        "always_approve": True,
        "model": None,
        # Durable backend: websocket to `grok agent serve` (preferred).
        # stdio = bridge spawns/manages a child process (legacy).
        "agent_transport": "websocket",
        "agent_ws_url": "ws://127.0.0.1:2419/ws",
        "agent_secret": os.environ.get("GROK_AGENT_SECRET") or "",
        "agent_bind": "127.0.0.1:2419",
        "auto_reconnect": True,
        # Recent transcript lines to show on phone (agent still has full context via resume)
        "history_limit": 40,
        "history_include_thoughts": False,
    }
    if CONFIG_PATH.exists():
        cfg.update(json.loads(CONFIG_PATH.read_text(encoding="utf-8")))
    if os.environ.get("GROK_REMOTE_TOKEN"):
        cfg["remote_token"] = os.environ["GROK_REMOTE_TOKEN"]
    if os.environ.get("GROK_AGENT_SECRET"):
        cfg["agent_secret"] = os.environ["GROK_AGENT_SECRET"]
    if not cfg.get("remote_token"):
        cfg["remote_token"] = secrets.token_urlsafe(24)
        log.warning(
            "No remote_token configured — generated ephemeral token: %s",
            cfg["remote_token"],
        )
    if not cfg.get("agent_secret"):
        cfg["agent_secret"] = secrets.token_urlsafe(24)
        log.warning(
            "No agent_secret configured — generated: %s (set in config.json / GROK_AGENT_SECRET)",
            cfg["agent_secret"],
        )
    if not cfg.get("projects"):
        cfg["projects"] = [{"name": Path(cfg["default_cwd"]).name, "cwd": cfg["default_cwd"]}]
    return cfg


CONFIG = load_config()
agent = AcpClient(
    always_approve=bool(CONFIG.get("always_approve", True)),
    model=CONFIG.get("model"),
    transport=str(CONFIG.get("agent_transport") or "websocket"),
    ws_url=str(CONFIG.get("agent_ws_url") or "ws://127.0.0.1:2419/ws"),
    ws_secret=str(CONFIG.get("agent_secret") or ""),
    auto_reconnect=bool(CONFIG.get("auto_reconnect", True)),
    history_limit=int(CONFIG.get("history_limit") or 40),
    history_include_thoughts=bool(CONFIG.get("history_include_thoughts", False)),
)
clients: set[WebSocket] = set()
clients_lock = asyncio.Lock()


async def broadcast(event: dict[str, Any]) -> None:
    dead: list[WebSocket] = []
    payload = json.dumps(event, ensure_ascii=False)
    async with clients_lock:
        for ws in clients:
            try:
                await ws.send_text(payload)
            except Exception:
                dead.append(ws)
        for ws in dead:
            clients.discard(ws)


async def on_agent_event(event: dict[str, Any]) -> None:
    await broadcast(event)


@asynccontextmanager
async def lifespan(app: FastAPI):
    agent.on_event(on_agent_event)
    log.info("Starting Grok ACP agent…")
    await agent.start()
    # Open configured projects. Prefer resume when session_id is set.
    default_cwd = CONFIG["default_cwd"]
    projects = list(CONFIG.get("projects") or [{"name": "main", "cwd": default_cwd}])
    # No hard cap — open every configured project/session (UI tabs scale with this list).
    for i, proj in enumerate(projects):
        title = proj.get("name") or f"Session {i + 1}"
        cwd = proj.get("cwd") or default_cwd
        session_id = proj.get("session_id") or proj.get("sessionId")
        replay = bool(proj.get("replay_history", False))
        try:
            if session_id:
                await agent.load_session(
                    session_id,
                    cwd,
                    title=title,
                    replay=replay,
                )
                log.info("Resumed session %s (%s) @ %s", title, session_id, cwd)
            else:
                await agent.create_session(cwd, title=title)
                log.info("Created session %s @ %s", title, cwd)
        except Exception:
            log.exception("Failed to open session %s", title)
            # Still give the user a working tab on that project
            try:
                await agent.create_session(cwd, title=f"{title} (new)")
            except Exception:
                log.exception("Fallback create also failed for %s", title)
    if not agent.sessions:
        await agent.create_session(default_cwd, title="Session 1")
    log.info("Holding %d session(s)", len(agent.sessions))
    yield
    await agent.stop()


app = FastAPI(title="Grok Remote", lifespan=lifespan)


def require_token(
    x_remote_token: Optional[str] = Header(default=None, alias="X-Remote-Token"),
    authorization: Optional[str] = Header(default=None),
) -> None:
    expected = CONFIG["remote_token"]
    token = x_remote_token
    if not token and authorization and authorization.lower().startswith("bearer "):
        token = authorization[7:].strip()
    if not token or not secrets.compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="Invalid or missing token")


class CreateSessionBody(BaseModel):
    cwd: Optional[str] = None
    title: Optional[str] = None
    session_id: Optional[str] = None
    replay_history: bool = False


class PromptBody(BaseModel):
    text: str = Field(min_length=1)
    session_id: str


class CancelBody(BaseModel):
    session_id: str


@app.get("/api/health")
async def health():
    return {
        "ok": True,
        "sessions": len(agent.sessions),
        "agentReady": agent._ready.is_set(),
        "agentAlive": agent.agent_alive,
        "agentTransport": agent.transport,
        "reconnectCount": agent.reconnect_count,
        "lastError": agent.last_error,
    }


@app.get("/api/config")
async def get_config(_: None = Depends(require_token)):
    return {
        "projects": CONFIG.get("projects", []),
        "default_cwd": CONFIG.get("default_cwd"),
        "always_approve": CONFIG.get("always_approve", True),
        "model": CONFIG.get("model"),
    }


@app.get("/api/sessions")
async def list_sessions(_: None = Depends(require_token)):
    return {
        "sessions": [
            {
                "sessionId": s.session_id,
                "cwd": s.cwd,
                "title": s.title,
                "busy": s.busy,
                "messageCount": len(s.messages),
                "messages": s.messages,
            }
            for s in agent.sessions.values()
        ]
    }


@app.post("/api/sessions")
async def create_session(body: CreateSessionBody, _: None = Depends(require_token)):
    cwd = body.cwd or CONFIG["default_cwd"]
    if body.session_id:
        info = await agent.load_session(
            body.session_id,
            cwd,
            title=body.title,
            replay=body.replay_history,
        )
    else:
        info = await agent.create_session(cwd, title=body.title)
    return {
        "sessionId": info.session_id,
        "cwd": info.cwd,
        "title": info.title,
    }


@app.post("/api/prompt")
async def prompt(body: PromptBody, _: None = Depends(require_token)):
    try:
        # Fire-and-forget streaming via WS; still await completion for HTTP clients
        result = await agent.prompt(body.session_id, body.text)
        return {"ok": True, "result": result}
    except KeyError as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except Exception as exc:
        log.exception("prompt failed")
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/api/cancel")
async def cancel(body: CancelBody, _: None = Depends(require_token)):
    await agent.cancel(body.session_id)
    return {"ok": True}


@app.websocket("/ws")
async def ws_endpoint(websocket: WebSocket):
    token = websocket.query_params.get("token") or websocket.query_params.get("server-key")
    if not token or not secrets.compare_digest(token, CONFIG["remote_token"]):
        await websocket.close(code=4401)
        return

    await websocket.accept()
    async with clients_lock:
        clients.add(websocket)

    # Snapshot current state
    await websocket.send_text(
        json.dumps(
            {
                "type": "hello",
                "sessions": [
                    {
                        "sessionId": s.session_id,
                        "cwd": s.cwd,
                        "title": s.title,
                        "busy": s.busy,
                        "messages": s.messages,
                    }
                    for s in agent.sessions.values()
                ],
                "projects": CONFIG.get("projects", []),
                "default_cwd": CONFIG.get("default_cwd"),
            },
            ensure_ascii=False,
        )
    )

    try:
        while True:
            raw = await websocket.receive_text()
            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                await websocket.send_text(
                    json.dumps({"type": "error", "message": "invalid JSON"})
                )
                continue

            mtype = msg.get("type")
            try:
                if mtype == "prompt":
                    session_id = msg["sessionId"]
                    text = (msg.get("text") or "").strip()
                    if not text:
                        continue
                    # Run prompt concurrently so cancel / other sessions stay responsive
                    asyncio.create_task(_run_prompt(session_id, text))
                elif mtype == "cancel":
                    await agent.cancel(msg["sessionId"])
                elif mtype == "new_session":
                    if msg.get("sessionId"):
                        info = await agent.load_session(
                            msg["sessionId"],
                            msg.get("cwd") or CONFIG["default_cwd"],
                            title=msg.get("title"),
                            replay=bool(msg.get("replayHistory")),
                        )
                        await websocket.send_text(
                            json.dumps(
                                {
                                    "type": "session_loaded",
                                    "sessionId": info.session_id,
                                    "cwd": info.cwd,
                                    "title": info.title,
                                    "messages": info.messages,
                                }
                            )
                        )
                    else:
                        info = await agent.create_session(
                            msg.get("cwd") or CONFIG["default_cwd"],
                            title=msg.get("title"),
                        )
                        await websocket.send_text(
                            json.dumps(
                                {
                                    "type": "session_created",
                                    "sessionId": info.session_id,
                                    "cwd": info.cwd,
                                    "title": info.title,
                                }
                            )
                        )
                elif mtype == "ping":
                    await websocket.send_text(json.dumps({"type": "pong"}))
                else:
                    await websocket.send_text(
                        json.dumps({"type": "error", "message": f"unknown type {mtype}"})
                    )
            except Exception as exc:
                log.exception("ws command failed: %s", mtype)
                await websocket.send_text(
                    json.dumps({"type": "error", "message": str(exc), "sessionId": msg.get("sessionId")})
                )
    except WebSocketDisconnect:
        pass
    finally:
        async with clients_lock:
            clients.discard(websocket)


async def _run_prompt(session_id: str, text: str) -> None:
    try:
        await agent.prompt(session_id, text)
    except Exception as exc:
        log.exception("prompt task failed")
        await broadcast(
            {
                "type": "error",
                "sessionId": session_id,
                "message": str(exc),
            }
        )


def _tailscale_exe() -> Optional[Path]:
    for exe in (
        Path(r"C:\Program Files\Tailscale\tailscale.exe"),
        Path(r"C:\Program Files (x86)\Tailscale\tailscale.exe"),
    ):
        if exe.exists():
            return exe
    return None


def _tailscale_ipv4() -> Optional[str]:
    """Best-effort Tailscale IPv4 for this machine."""
    exe = _tailscale_exe()
    if exe:
        try:
            out = subprocess.check_output(
                [str(exe), "ip", "-4"],
                text=True,
                timeout=5,
                stderr=subprocess.DEVNULL,
            ).strip()
            for line in out.splitlines():
                ip = line.strip()
                if ip.startswith("100."):
                    return ip
        except Exception:
            pass
    # Fallback: any 100.x address on a Tailscale-ish interface
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip.startswith("100."):
                return ip
    except Exception:
        pass
    return None


def _tailscale_https_origin() -> Optional[str]:
    """https://<magicdns> when Tailscale Serve is configured — needed for phone mic/STT."""
    exe = _tailscale_exe()
    if not exe:
        return None
    try:
        raw = subprocess.check_output(
            [str(exe), "status", "--json"],
            text=True,
            timeout=8,
            stderr=subprocess.DEVNULL,
        )
        data = json.loads(raw)
        dns = ((data.get("Self") or {}).get("DNSName") or "").rstrip(".")
        if not dns:
            return None

        serve_on = False
        try:
            serve_raw = subprocess.check_output(
                [str(exe), "serve", "status", "--json"],
                text=True,
                timeout=5,
                stderr=subprocess.DEVNULL,
            ).strip()
            if serve_raw.startswith("{"):
                serve = json.loads(serve_raw)
                # Active Serve puts handlers under Web and/or TCP.HTTPS
                if serve.get("Web") or any(
                    (v or {}).get("HTTPS") for v in (serve.get("TCP") or {}).values()
                ):
                    serve_on = True
        except Exception as exc:
            log.debug("serve status --json failed: %s", exc)
            try:
                text = subprocess.check_output(
                    [str(exe), "serve", "status"],
                    text=True,
                    timeout=5,
                    stderr=subprocess.DEVNULL,
                ).strip()
                if text and "No serve config" not in text and "https://" in text.lower():
                    serve_on = True
            except Exception:
                pass

        if serve_on or CONFIG.get("prefer_tailscale_https"):
            return f"https://{dns}"
    except Exception as exc:
        log.debug("tailscale https origin failed: %s", exc)
    return None


def _lan_ipv4() -> Optional[str]:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        if not ip.startswith("127."):
            return ip
    except Exception:
        pass
    return None


def pair_base_url(request: Optional[Request] = None) -> str:
    """URL the phone should open.

    Prefer Tailscale Serve HTTPS (mic/STT need a secure context on Android Chrome).
    Fall back to http://100.x:port for typing-only use.
    """
    port = int(CONFIG.get("bind_port", 8787))
    # Explicit override for MagicDNS / custom host (prefer https://…ts.net for mic)
    if CONFIG.get("public_host"):
        host = str(CONFIG["public_host"]).strip().rstrip("/")
        if "://" in host:
            return host
        # bare hostname: prefer https when Serve/MagicDNS style
        scheme = "https" if host.endswith(".ts.net") or CONFIG.get("prefer_tailscale_https") else "http"
        if scheme == "http":
            return f"http://{host}:{port}"
        return f"https://{host}"
    https = _tailscale_https_origin()
    if https:
        return https
    ts = _tailscale_ipv4()
    if ts:
        return f"http://{ts}:{port}"
    lan = _lan_ipv4()
    if lan:
        return f"http://{lan}:{port}"
    if request is not None:
        return str(request.base_url).rstrip("/")
    return f"http://127.0.0.1:{port}"


def pair_connect_url(request: Optional[Request] = None) -> str:
    base = pair_base_url(request)
    token = CONFIG["remote_token"]
    return f"{base}/?{urlencode({'token': token})}"


def download_page_url(request: Optional[Request] = None) -> str:
    """Short phone-facing install URL (prefer /dl over /download)."""
    return f"{pair_base_url(request)}/dl"


def apk_file_url(request: Optional[Request] = None) -> str:
    return f"{pair_base_url(request)}/dl/apk"


def _is_loopback_host(host: Optional[str]) -> bool:
    if not host:
        return False
    h = host.strip().lower()
    if h in ("127.0.0.1", "::1", "localhost"):
        return True
    # IPv4-mapped IPv6 loopback
    if h.startswith("::ffff:127."):
        return True
    return False


def require_local_pair(request: Request) -> None:
    """Pairing exposes the secret token in a QR — only allow from this PC (loopback).

    Open http://127.0.0.1:8787/pair on the machine running the bridge.
    Remote Tailscale / LAN clients get 403 (phone uses the QR payload, not /pair).
    """
    client_host = request.client.host if request.client else None
    if _is_loopback_host(client_host):
        return
    log.warning("Blocked remote pair access from %s", client_host)
    raise HTTPException(
        status_code=403,
        detail=(
            "Pairing is only available on the PC. "
            "On the machine running Grok Remote, open http://127.0.0.1:8787/pair "
            "and scan the QR with your phone."
        ),
    )


@app.get("/")
async def index():
    # Avoid sticky mobile browser cache of the shell HTML (and thus old app.js refs)
    return FileResponse(
        WEB_DIR / "index.html",
        headers={
            "Cache-Control": "no-store, no-cache, must-revalidate",
            "Pragma": "no-cache",
        },
    )


def _qr_png(data: str, scale: int = 8) -> Response:
    qr = qrcode.QRCode(
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=scale,
        border=2,
    )
    qr.add_data(data)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white")
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return Response(
        content=buf.getvalue(),
        media_type="image/png",
        headers={"Cache-Control": "no-store"},
    )


@app.get("/pair", response_class=HTMLResponse)
async def pair_page(request: Request):
    """Open this on the PC only; phone scans the QRs — no typing URLs or tokens."""
    require_local_pair(request)
    base = pair_base_url(request)
    dl = download_page_url(request)
    info = _apk_info()
    apk_meta = (
        f"{info['sizeMB']} MB · {info['modifiedIso']}"
        if info.get("available")
        else "No APK published yet — run scripts/publish-apk.ps1"
    )
    # Do not print the full tokenized chat URL as copyable text (QR only).
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Grok Remote — Pair &amp; install</title>
  <style>
    :root {{ color-scheme: dark; font-family: system-ui, sans-serif; }}
    body {{
      margin: 0; min-height: 100vh; display: grid; place-items: center;
      background: #0b1020; color: #e8eefc; padding: 1.25rem;
    }}
    .wrap {{
      display: flex; flex-wrap: wrap; gap: 1rem; justify-content: center;
      max-width: 44rem;
    }}
    .card {{
      background: #121a2f; border: 1px solid rgba(255,255,255,.08);
      border-radius: 16px; padding: 1.35rem 1.5rem; width: min(20rem, 100%);
      text-align: center; box-shadow: 0 12px 40px rgba(0,0,0,.35);
    }}
    h1 {{ margin: 0 0 .35rem; font-size: 1.2rem; }}
    h2 {{ margin: 0 0 .35rem; font-size: 1.05rem; color: #c5d4f5; }}
    p {{ color: #8b9bb8; line-height: 1.45; font-size: 14px; margin: .4rem 0; }}
    img {{
      width: min(220px, 62vw); height: auto; background: #fff;
      border-radius: 12px; padding: 10px; margin: .75rem 0;
    }}
    code {{
      font-size: 11px; background: #1a2440; padding: .15rem .4rem; border-radius: 6px;
      word-break: break-all;
    }}
    .hint {{ font-size: 12px; margin-top: .5rem; }}
    .lock {{ color: #3dd68c; font-size: 12px; margin-bottom: .5rem; }}
    .badge {{
      display: inline-block; font-size: 11px; font-weight: 600;
      letter-spacing: .04em; text-transform: uppercase;
      padding: .2rem .5rem; border-radius: 999px; margin-bottom: .5rem;
    }}
    .badge-pair {{ background: rgba(61,214,140,.15); color: #3dd68c; }}
    .badge-dl {{ background: rgba(110,168,255,.15); color: #6ea8ff; }}
    a {{ color: #6ea8ff; }}
    .foot {{
      width: 100%; text-align: center; color: #8b9bb8; font-size: 12px; margin-top: .25rem;
    }}
  </style>
</head>
<body>
  <div class="wrap">
    <div class="lock" style="width:100%;text-align:center">
      PC only — open <code>http://127.0.0.1:8787/pair</code> on this machine
    </div>

    <div class="card">
      <div class="badge badge-pair">1 · Pair</div>
      <h1>Pair your phone</h1>
      <p>Scan with the phone camera (Tailscale connected). Opens chat with the token — do not type it.</p>
      <img src="/pair/qr.png" alt="Pairing QR code" width="220" height="220" />
      <p class="hint">QR target is the chat base:<br/><code>{base}</code></p>
    </div>

    <div class="card">
      <div class="badge badge-dl">2 · Install APK</div>
      <h2>Install / update app</h2>
      <p>Scan to open the install page — no typing <code>/download</code> on the phone.</p>
      <img src="/pair/dl-qr.png" alt="APK download QR code" width="220" height="220" />
      <p class="hint">{apk_meta}<br/>
        Phone URL: <code>{dl}</code><br/>
        Short path: <code>/dl</code> · APK file: <code>/dl/apk</code>
      </p>
    </div>

    <p class="foot">
      <a href="/">Open chat on this PC</a>
      · <a href="/dl">Preview install page</a>
    </p>
  </div>
</body>
</html>"""
    return HTMLResponse(html, headers={"Cache-Control": "no-store"})


@app.get("/pair/qr.png")
async def pair_qr(request: Request, scale: int = Query(default=8, ge=2, le=16)):
    require_local_pair(request)
    return _qr_png(pair_connect_url(request), scale=scale)


@app.get("/pair/dl-qr.png")
async def pair_dl_qr(request: Request, scale: int = Query(default=8, ge=2, le=16)):
    """QR for the phone install page (public on tailnet — no token in the URL)."""
    require_local_pair(request)
    return _qr_png(download_page_url(request), scale=scale)


@app.get("/api/pair-info")
async def pair_info(request: Request):
    """JSON for debugging — localhost only (includes tokenized connect URL)."""
    require_local_pair(request)
    return {
        "connectUrl": pair_connect_url(request),
        "baseUrl": pair_base_url(request),
        "tailscaleIp": _tailscale_ipv4(),
        "lanIp": _lan_ipv4(),
        "loopbackOnly": True,
        "downloadPage": download_page_url(request),
        "downloadPageLong": f"{pair_base_url(request)}/download",
        "apkUrl": apk_file_url(request),
        "apkUrlLong": f"{pair_base_url(request)}/download/grok-remote.apk",
    }


def _resolve_apk() -> Optional[Path]:
    """Prefer releases/ publish; fall back to latest Gradle debug APK."""
    if APK_PUBLISHED.is_file():
        return APK_PUBLISHED
    if APK_BUILD_FALLBACK.is_file():
        return APK_BUILD_FALLBACK
    return None


def _apk_info() -> dict[str, Any]:
    path = _resolve_apk()
    if not path:
        return {"available": False}
    st = path.stat()
    return {
        "available": True,
        "filename": path.name,
        "sizeBytes": st.st_size,
        "sizeMB": round(st.st_size / (1024 * 1024), 1),
        "modifiedUnix": int(st.st_mtime),
        "modifiedIso": __import__("datetime").datetime.fromtimestamp(st.st_mtime).isoformat(
            timespec="seconds"
        ),
        "source": "releases" if path == APK_PUBLISHED else "gradle-debug",
        "downloadPath": "/download/grok-remote.apk",
    }


@app.get("/api/download/info")
async def download_info(request: Request):
    info = _apk_info()
    if info.get("available"):
        info["url"] = apk_file_url(request)
        info["page"] = download_page_url(request)
        info["urlLong"] = f"{pair_base_url(request)}/download/grok-remote.apk"
        info["pageLong"] = f"{pair_base_url(request)}/download"
    return info


def _download_page_html(request: Request) -> HTMLResponse:
    """Phone-friendly install page (Tailscale HTTPS). Served at /dl and /download."""
    base = pair_base_url(request)
    apk_href = "/dl/apk"
    apk_display = f"{base}/dl/apk"
    info = _apk_info()
    if not info.get("available"):
        body = """
        <p class="warn">No APK published yet.</p>
        <p>On the PC run:</p>
        <pre>powershell -ExecutionPolicy Bypass -File .\\scripts\\publish-apk.ps1</pre>
        """
    else:
        body = f"""
        <p>Install / update the native app without USB.</p>
        <p class="meta">
          <strong>{info['sizeMB']} MB</strong>
          · {info['modifiedIso']}
          · {info['source']}
        </p>
        <a class="btn" href="{apk_href}">Download APK</a>
        <ol class="steps">
          <li>Open this page in <strong>Chrome</strong> on the phone (Tailscale on).</li>
          <li>Tap Download — allow installs from the browser if asked.</li>
          <li>Open the APK and update.</li>
          <li>Pair via QR from the PC <code>/pair</code> page if needed.</li>
        </ol>
        <p class="hint">Direct link:<br/><code>{apk_display}</code></p>
        """
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Grok Remote — Install app</title>
  <style>
    :root {{ color-scheme: dark; font-family: system-ui, sans-serif; }}
    body {{
      margin: 0; min-height: 100vh; display: grid; place-items: center;
      background: #0b1020; color: #e8eefc; padding: 1rem;
    }}
    .card {{
      background: #121a2f; border: 1px solid rgba(255,255,255,.08);
      border-radius: 16px; padding: 1.5rem; max-width: 26rem; width: 100%;
    }}
    h1 {{ margin: 0 0 .5rem; font-size: 1.35rem; }}
    p, li {{ color: #8b9bb8; line-height: 1.45; }}
    .meta {{ color: #c5d4f5 !important; }}
    .warn {{ color: #f0c14b !important; }}
    .btn {{
      display: block; text-align: center; margin: 1rem 0;
      background: linear-gradient(180deg, #6ea8ff, #3d7eff);
      color: #fff !important; text-decoration: none; font-weight: 600;
      padding: .9rem 1rem; border-radius: 12px;
    }}
    code, pre {{
      font-family: ui-monospace, Consolas, monospace; font-size: 12px;
      background: #1a2440; padding: .35rem .5rem; border-radius: 8px;
      display: block; overflow-x: auto; color: #c5d4f5;
    }}
    pre {{ text-align: left; white-space: pre-wrap; }}
    .steps {{ padding-left: 1.2rem; }}
    .hint {{ font-size: 12px; word-break: break-all; }}
    a {{ color: #6ea8ff; }}
  </style>
</head>
<body>
  <div class="card">
    <h1>Install Grok Remote</h1>
    {body}
    <p class="hint"><a href="/">Web chat</a></p>
  </div>
</body>
</html>"""
    return HTMLResponse(
        html,
        headers={"Cache-Control": "no-store, no-cache, must-revalidate"},
    )


def _apk_file_response() -> FileResponse:
    path = _resolve_apk()
    if not path:
        raise HTTPException(
            status_code=404,
            detail="APK not found. Build and run scripts/publish-apk.ps1 on the PC.",
        )
    return FileResponse(
        path,
        media_type="application/vnd.android.package-archive",
        filename="grok-remote-debug.apk",
        headers={
            "Cache-Control": "no-store",
            "Content-Disposition": 'attachment; filename="grok-remote-debug.apk"',
        },
    )


@app.get("/dl", response_class=HTMLResponse)
@app.get("/download", response_class=HTMLResponse)
async def download_page(request: Request):
    """Short /dl and long /download — same install page (no auth; Tailscale only)."""
    return _download_page_html(request)


@app.get("/dl/apk")
@app.get("/download/grok-remote.apk")
async def download_apk():
    """Short /dl/apk and long path — same APK file."""
    return _apk_file_response()


if WEB_DIR.exists():
    app.mount("/static", StaticFiles(directory=str(WEB_DIR)), name="static")


def _setup_lifecycle_logging() -> Path:
    """File log that survives shell redirection and records clean vs killed exits."""
    log_dir = ROOT / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    life = log_dir / "lifecycle.log"

    def _append(msg: str) -> None:
        try:
            with life.open("a", encoding="utf-8") as f:
                f.write(f"{__import__('datetime').datetime.now().isoformat()} pid={os.getpid()} {msg}\n")
        except Exception:
            pass

    def _atexit() -> None:
        _append("atexit (normal interpreter shutdown)")

    import atexit
    import signal

    atexit.register(_atexit)

    def _handle(signum, frame) -> None:  # type: ignore[no-untyped-def]
        _append(f"signal {signum} received — shutting down")
        raise SystemExit(0)

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            signal.signal(sig, _handle)
        except Exception:
            pass
    # Windows console close
    if hasattr(signal, "SIGBREAK"):
        try:
            signal.signal(signal.SIGBREAK, _handle)  # type: ignore[attr-defined]
        except Exception:
            pass

    _append("process start")
    return life


def main() -> None:
    import threading
    import time

    import uvicorn

    life_path = _setup_lifecycle_logging()

    def _heartbeat() -> None:
        while True:
            time.sleep(60)
            try:
                with life_path.open("a", encoding="utf-8") as f:
                    f.write(
                        f"{__import__('datetime').datetime.now().isoformat()} "
                        f"pid={os.getpid()} heartbeat sessions={len(agent.sessions)} "
                        f"agent_alive={agent._proc is not None and agent._proc.returncode is None}\n"
                    )
            except Exception:
                break

    threading.Thread(target=_heartbeat, name="lifecycle-heartbeat", daemon=True).start()

    host = CONFIG.get("bind_host", "0.0.0.0")
    port = int(CONFIG.get("bind_port", 8787))
    connect = pair_connect_url()
    log.info("Grok Remote UI: http://127.0.0.1:%s/", port)
    log.info("Pair + install QRs (PC only): http://127.0.0.1:%s/pair", port)
    log.info("Phone APK install (short): %s/dl", pair_base_url())
    log.info("Phone connect URL: %s", connect)
    log.info("Lifecycle log: %s", life_path)
    try:
        # Pass the app object (not "main:app") so uvicorn does not re-import this
        # module and create a second AcpClient — that made heartbeats look dead
        # while the real server was fine.
        uvicorn.run(
            app,
            host=host,
            port=port,
            reload=False,
            log_level="info",
        )
    finally:
        try:
            with life_path.open("a", encoding="utf-8") as f:
                f.write(
                    f"{__import__('datetime').datetime.now().isoformat()} "
                    f"pid={os.getpid()} uvicorn.run returned (clean exit path)\n"
                )
        except Exception:
            pass


if __name__ == "__main__":
    main()
