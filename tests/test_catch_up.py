"""Catch-up reloads disk history, then best-effort ACP session/resume over the
`grok agent serve` WebSocket — not stdio.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from acp_client import AcpClient, SessionInfo  # noqa: E402


class CatchUpTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self) -> None:
        self.client = AcpClient(
            transport="websocket",
            ws_url="ws://127.0.0.1:2419/ws",
        )
        self.client.ensure_ready = AsyncMock()
        self.emitted: list[dict] = []

        async def _emit(ev: dict) -> None:
            self.emitted.append(ev)

        self.client._emit = _emit  # type: ignore[method-assign]

    async def test_default_transport_is_websocket_to_agent_serve(self) -> None:
        self.assertEqual(self.client.transport, "websocket")
        self.assertIn("2419", self.client.ws_url)
        self.assertTrue(self.client.ws_url.startswith("ws://"))

    async def test_unknown_session_raises(self) -> None:
        with self.assertRaises(KeyError):
            await self.client.catch_up("missing-session")

    async def test_snapshot_is_emitted_before_acp_resume(self) -> None:
        sid = "sess-live"
        self.client.sessions[sid] = SessionInfo(session_id=sid, cwd=r"C:\proj", title="Proj")

        async def resume(*_a, **_k):
            self.assertTrue(self.emitted, "UI snapshot must go out before session/resume")
            return {"ok": True}

        self.client.request = AsyncMock(side_effect=resume)
        disk = [{"role": "user", "text": "from the TUI"}]
        with patch("acp_client.load_recent_messages", return_value=disk):
            info = await self.client.catch_up(sid)

        self.client.ensure_ready.assert_awaited()
        self.assertEqual(info.messages[0]["text"], "from the TUI")
        self.assertTrue(any("Caught up from PC disk" in (m.get("text") or "") for m in info.messages))
        self.assertEqual(self.emitted[0]["type"], "session_loaded")
        self.assertEqual(self.emitted[0]["sessionId"], sid)
        self.client.request.assert_awaited()
        _args, kwargs = self.client.request.call_args
        self.assertEqual(_args[0], "session/resume")
        self.assertEqual(_args[1]["sessionId"], sid)

    async def test_disk_refresh_still_happens_when_resume_fails(self) -> None:
        sid = "sess-live"
        self.client.sessions[sid] = SessionInfo(session_id=sid, cwd=r"C:\proj", title="Proj")
        self.client.request = AsyncMock(side_effect=RuntimeError("agent websocket is not connected"))
        with patch("acp_client.load_recent_messages", return_value=[{"role": "assistant", "text": "hi"}]):
            info = await self.client.catch_up(sid)
        self.assertEqual(info.messages[0]["text"], "hi")
        self.assertEqual(self.emitted[0]["type"], "session_loaded")


if __name__ == "__main__":
    unittest.main()
