"""The bridge talks to a long-lived `grok agent serve` over WebSocket."""

from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from acp_client import AcpClient  # noqa: E402


class TransportTests(unittest.TestCase):
    def test_acp_client_defaults_to_websocket(self) -> None:
        c = AcpClient()
        self.assertEqual(c.transport, "websocket")
        self.assertEqual(c.ws_url, "ws://127.0.0.1:2419/ws")

    def test_example_config_uses_agent_serve_websocket(self) -> None:
        cfg = json.loads((ROOT / "config.example.json").read_text(encoding="utf-8"))
        self.assertEqual(cfg["agent_transport"], "websocket")
        self.assertTrue(str(cfg["agent_ws_url"]).startswith("ws://"))
        self.assertIn(":2419", cfg["agent_ws_url"])

    def test_empty_transport_falls_back_to_websocket(self) -> None:
        c = AcpClient(transport="")
        self.assertEqual(c.transport, "websocket")


if __name__ == "__main__":
    unittest.main()
