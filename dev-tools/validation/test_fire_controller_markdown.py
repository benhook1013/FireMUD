"""Established relaxed Markdown checks, independent of rendering and formatting."""
from __future__ import annotations

import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
from fire_controller.markdown import diagnostics


class MarkdownTest(unittest.TestCase):
    def test_plain_text_pr_references_and_bracketed_workers_are_quiet(self):
        self.assertEqual(diagnostics({"message": "#2840 is ready. Ask [worker] to check.\nPlain text."}), [])

    def test_code_examples_are_not_markup_warnings(self):
        text = "Example `<b>raw</b>` and `[label][missing]`.\n\n```text\n<b>raw</b>\n[bad]()\n```"
        self.assertEqual(diagnostics({"body": text}), [])

    def test_meaningful_advisories_and_batching_preserve_text(self):
        fields = {"brief": "<b>original HTML</b>\n<a>second HTML</a>",
                  "message": "[empty]() and [explicit][missing]"}
        original = dict(fields)
        with patch("subprocess.run", wraps=subprocess.run) as run:
            warnings = diagnostics(fields)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(fields, original)
        self.assertEqual({warning["rule"] for warning in warnings}, {"MD033", "MD042", "MD052"})
        self.assertEqual(len([warning for warning in warnings if warning["rule"] == "MD033"]), 1)
        self.assertGreaterEqual(next(w for w in warnings if w["rule"] == "MD033")["occurrences"], 2)

    def test_missing_tool_fails_clearly_without_fallback(self):
        with patch("subprocess.run", side_effect=FileNotFoundError("node")), self.assertRaisesRegex(ValueError, "unavailable"):
            diagnostics({"message": "Text to check"})
        with patch("subprocess.run", return_value=subprocess.CompletedProcess([], 2, "", "missing dependency")), \
                self.assertRaisesRegex(ValueError, "npm ci"):
            diagnostics({"message": "Text to check"})

    def test_empty_fields_have_no_process_or_style_rules(self):
        with patch("subprocess.run", side_effect=AssertionError("no text to lint")):
            self.assertEqual(diagnostics({"body": ""}), [])
        self.assertEqual(diagnostics({"body": "No heading\n" + "long line " * 50}), [])


if __name__ == "__main__":
    unittest.main()
