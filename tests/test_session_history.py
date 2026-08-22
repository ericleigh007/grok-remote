"""On-disk session catalog + search. Isolated via GROK_HOME."""

from __future__ import annotations

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "server"))

from session_history import _file_contains_ci, list_on_disk_sessions, load_recent_messages  # noqa: E402


def _write_session(
    grok: Path,
    *,
    folder: str,
    sid: str,
    title: str,
    cwd: str,
    transcript: str,
    updated_at: str = "2026-08-22T12:00:00Z",
    last_turn: str = "",
) -> None:
    d = grok / "sessions" / folder / sid
    d.mkdir(parents=True, exist_ok=True)
    payload = json.dumps({"type": "user", "content": transcript})
    pad = " " * max(0, 220 - len(payload))
    (d / "chat_history.jsonl").write_text(payload + pad + "\n", encoding="utf-8")
    (d / "summary.json").write_text(
        json.dumps(
            {
                "generated_title": title,
                "session_summary": title,
                "last_turn_summary": last_turn,
                "updated_at": updated_at,
                "num_chat_messages": 1,
                "info": {"id": sid, "cwd": cwd},
            }
        ),
        encoding="utf-8",
    )


class SessionHistoryTests(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.grok = Path(self._tmp.name)
        self.env = patch.dict(os.environ, {"GROK_HOME": str(self.grok)})
        self.env.start()
        _write_session(
            self.grok,
            folder="Flow",
            sid="sess-flow",
            title="Flow Debug",
            cwd=r"C:\Users\ericl\source\repos\Flow",
            transcript="work on the Flow portable build",
            updated_at="2026-08-22T18:00:00Z",
            last_turn="portable build",
        )
        _write_session(
            self.grok,
            folder="ISITWeb",
            sid="sess-bell",
            title="Doorbell C# Service",
            cwd=r"C:\Users\ericl\source\repos\ISITWeb",
            transcript="always-running Windows service for the doorbell",
            updated_at="2026-08-21T10:00:00Z",
            last_turn="service install",
        )
        _write_session(
            self.grok,
            folder="other",
            sid="sess-hidden",
            title="Unrelated notes",
            cwd=r"C:\tmp\notes",
            transcript="the unique token zucchini-pancake appears only here",
            updated_at="2026-08-20T09:00:00Z",
        )

    def tearDown(self) -> None:
        self.env.stop()
        self._tmp.cleanup()

    def test_lists_newest_first(self) -> None:
        rows = list_on_disk_sessions()
        self.assertEqual([r["sessionId"] for r in rows], ["sess-flow", "sess-bell", "sess-hidden"])

    def test_query_matches_title(self) -> None:
        rows = list_on_disk_sessions(query="doorbell")
        self.assertEqual([r["sessionId"] for r in rows], ["sess-bell"])
        self.assertEqual(rows[0]["title"], "Doorbell C# Service")

    def test_query_matches_cwd_folder(self) -> None:
        rows = list_on_disk_sessions(query="Flow")
        ids = [r["sessionId"] for r in rows]
        self.assertIn("sess-flow", ids)
        self.assertNotIn("sess-hidden", ids)

    def test_query_matches_config_alias(self) -> None:
        rows = list_on_disk_sessions(
            query="doorbell",
            aliases={"sess-bell": "Doorbell C# Service Always-Running"},
        )
        self.assertEqual(rows[0]["title"], "Doorbell C# Service Always-Running")

    def test_query_matches_transcript_when_title_does_not(self) -> None:
        rows = list_on_disk_sessions(query="zucchini-pancake")
        self.assertEqual([r["sessionId"] for r in rows], ["sess-hidden"])

    def test_short_query_does_not_scan_transcript(self) -> None:
        # "zu" is inside the hidden transcript but shorter than 3 chars.
        rows = list_on_disk_sessions(query="zu")
        self.assertEqual(rows, [])

    def test_metadata_hits_rank_before_transcript_hits(self) -> None:
        _write_session(
            self.grok,
            folder="other",
            sid="sess-title-zu",
            title="zucchini in the title",
            cwd=r"C:\tmp\title",
            transcript="no unique food words",
            updated_at="2026-08-19T00:00:00Z",
        )
        rows = list_on_disk_sessions(query="zucchini")
        self.assertEqual(rows[0]["sessionId"], "sess-title-zu")
        self.assertEqual(rows[1]["sessionId"], "sess-hidden")

    def test_limit(self) -> None:
        rows = list_on_disk_sessions(limit=1)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]["sessionId"], "sess-flow")

    def test_file_contains_ci_across_chunk_boundary(self) -> None:
        p = self.grok / "big.jsonl"
        needle = "boundary-token"
        # 256KiB chunk in _file_contains_ci; put the needle spanning the split.
        left = "a" * (256 * 1024 - 8)
        p.write_text(left + needle + "zzzz", encoding="utf-8")
        self.assertTrue(_file_contains_ci(p, needle))
        self.assertFalse(_file_contains_ci(p, "no-such-token"))

    def test_load_recent_messages(self) -> None:
        msgs = load_recent_messages("sess-flow", include_thoughts=False)
        self.assertTrue(msgs)
        self.assertEqual(msgs[0]["role"], "user")
        self.assertIn("Flow portable", msgs[0]["text"])


if __name__ == "__main__":
    unittest.main()
