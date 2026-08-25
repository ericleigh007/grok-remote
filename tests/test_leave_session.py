"""Leaving a remote tab unloads it from the live bridge; disk stays."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import AsyncMock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from acp_client import AcpClient, SessionInfo  # noqa: E402


class LeaveSessionTests(unittest.IsolatedAsyncioTestCase):
    async def test_leave_unknown_is_false(self) -> None:
        c = AcpClient()
        self.assertFalse(await c.leave_session("missing"))

    async def test_leave_pops_live_session(self) -> None:
        c = AcpClient()
        c.cancel = AsyncMock()
        c.sessions["sess-a"] = SessionInfo(session_id="sess-a", cwd=r"C:\proj", title="A")
        self.assertTrue(await c.leave_session("sess-a"))
        self.assertNotIn("sess-a", c.sessions)
        c.cancel.assert_not_awaited()

    async def test_leave_cancels_busy_then_pops(self) -> None:
        c = AcpClient()
        c.cancel = AsyncMock()
        c.sessions["sess-b"] = SessionInfo(
            session_id="sess-b",
            cwd=r"C:\proj",
            title="B",
            busy=True,
        )
        self.assertTrue(await c.leave_session("sess-b"))
        self.assertNotIn("sess-b", c.sessions)
        c.cancel.assert_awaited_once_with("sess-b")


if __name__ == "__main__":
    unittest.main()
