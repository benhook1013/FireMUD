import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

from tmp.tools import codex_recent_history as history


THREAD = "01a07037-e403-7b91-a500-34fc9f876a12"


class HistoryTest(unittest.TestCase):
    def test_boundaries_deduplication_and_partial_line(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / f"rollout-x-{THREAD}.jsonl"
            records = [
                {"timestamp": "2026-09-05T00:00:00Z", "type": "session_meta", "payload": {"id": THREAD}},
                {"timestamp": "2026-09-05T00:01:00Z", "type": "response_item", "payload": {"type": "message", "id": "a", "role": "user", "content": [{"type": "input_text", "text": "first"}]}},
                {"timestamp": "2026-09-05T00:01:00Z", "type": "response_item", "payload": {"type": "message", "id": "a", "role": "user", "content": [{"type": "input_text", "text": "first"}]}},
                {"timestamp": "2026-09-05T00:02:00Z", "type": "response_item", "payload": {"type": "message", "id": "b", "role": "assistant", "content": [{"type": "output_text", "text": "second"}]}},
            ]
            path.write_text("".join(json.dumps(record) + "\n" for record in records) + "{partial", encoding="utf-8")
            start = datetime(2026, 9, 5, 0, 1, tzinfo=timezone.utc)
            events, newest, eligible, malformed = history.load_events(path, THREAD, start, start, 10, False)
            self.assertEqual([event[3] for event in events], ["first"])
            self.assertEqual(history.iso(newest), "2026-09-05T00:02:00.000Z")
            self.assertEqual(history.iso(eligible), "2026-09-05T00:02:00.000Z")
            self.assertEqual(malformed, 1)

    def test_render_respects_character_bound(self):
        now = datetime(2026, 9, 5, tzinfo=timezone.utc)
        output = history.render(Path("session.jsonl"), now, now, [(now, "assistant", "", "x" * 1000)], now, now, 0, 256)
        self.assertLessEqual(len(output), 256)
        self.assertIn("output truncated", output)

    def test_encrypted_delegation_is_not_printed(self):
        record = {
            "type": "response_item",
            "payload": {
                "type": "function_call",
                "name": "spawn_agent",
                "arguments": json.dumps({"task_name": "worker", "message": "gAAAAsecret"}),
            },
        }
        _, text, detail = history.delegation_event(record)
        self.assertEqual(text, "[assignment prompt is encrypted in this session log]")
        self.assertIn("target=worker", detail)

    def test_analysis_and_malformed_shapes_are_excluded(self):
        analysis = {
            "type": "response_item",
            "payload": {
                "type": "message",
                "role": "assistant",
                "channel": "analysis",
                "content": [{"type": "output_text", "text": "private reasoning"}],
            },
        }
        self.assertIsNone(history.visible_event(analysis))
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / f"rollout-x-{THREAD}.jsonl"
            records = [
                {"timestamp": "2026-09-05T00:00:00Z", "type": "session_meta", "payload": {"id": THREAD}},
                [],
                {"timestamp": 123, "type": "response_item", "payload": {"type": "message", "role": "assistant", "content": []}},
            ]
            path.write_text("".join(json.dumps(record) + "\n" for record in records), encoding="utf-8")
            start = datetime(2026, 9, 5, tzinfo=timezone.utc)
            events, _, _, malformed = history.load_events(path, THREAD, start, start, 10, False)
            self.assertEqual(events, [])
            self.assertEqual(malformed, 2)


if __name__ == "__main__":
    unittest.main()
