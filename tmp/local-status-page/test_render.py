import importlib.util
import base64
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path
from unittest.mock import patch

MODULE_PATH = Path(__file__).with_name("render.py")
SPEC = importlib.util.spec_from_file_location("local_status_render", MODULE_PATH)
page = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(page)
NOW = datetime(2026, 9, 24, 12, tzinfo=timezone.utc)
HEAD = "a" * 40


class StatusPageTest(unittest.TestCase):
    def fixture(self):
        return {
            "review_front": 42,
            "stack": [{"number": 42, "title": "<unsafe> & status", "stage": "<stage> & purpose", "base": "develop", "head": HEAD, "verified_at": "2026-09-24T11:00:00Z"}],
            "lanes": [
                {"name": name, "task": "<script>alert(1)</script>", "next_action": "Next & then", "blocker": "No <leak>", "brief": "general-local-status-page.md", "verified_at": "2026-09-24T11:00:00Z"}
                for name in ("Gameplay", "Document", "General")
            ],
        }

    def test_html_escapes_manual_text_and_keeps_links(self):
        review = page.review_snapshot(None, 42, HEAD, NOW)
        result = page.render(self.fixture(), review, NOW)
        self.assertIn("&lt;unsafe&gt; &amp; status", result)
        self.assertIn("&lt;stage&gt; &amp; purpose", result)
        self.assertIn("Stack at a glance", result)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", result)
        self.assertNotIn("<script>", result)
        self.assertIn('href="https://github.com/benhook1013/FireMUD/pull/42"', result)
        self.assertIn('href="https://github.com/benhook1013/FireMUD"', result)
        self.assertIn('<form class="refresh-form" action="/refresh" method="post">', result)
        self.assertIn("form-action 'self'", result)
        self.assertIn("connect-src 'self'", result)
        refresh_hash = base64.b64encode(hashlib.sha256(page.REFRESH_SCRIPT.encode()).digest()).decode()
        self.assertIn(f"script-src 'sha256-{refresh_hash}'", result)
        self.assertIn(f'<script id="local-refresh-progress">{page.REFRESH_SCRIPT}</script>', result)
        self.assertIn('class="refresh-progress" role="status" aria-live="polite"', result)
        self.assertIn("stack order and worker notes stay manual", result)
        self.assertNotIn('href="../../task-briefs/', result)
        self.assertIn("Review eligibility unavailable", result)
        self.assertNotIn("Review front", result)
        self.assertLess(result.index("<h2>Worker lanes</h2>"), result.index("<h2>Stack at a glance</h2>"))
        self.assertLess(result.index("<h2>Stack at a glance</h2>"), result.index("<h2>Configured review queue</h2>"))

    def test_stage_order_must_match_stack_order(self):
        data = self.fixture()
        data["stack"].extend([
            {**data["stack"][0], "number": 43, "stage": "second"},
            {**data["stack"][0], "number": 44},
        ])
        with self.assertRaisesRegex(ValueError, "contiguous"):
            page.render(data, page.review_snapshot(None, 42, HEAD, NOW), NOW)

    def test_overview_marks_only_fully_merged_groups(self):
        data = self.fixture()
        data["stack"].extend([
            {**data["stack"][0], "number": 43},
            {**data["stack"][0], "number": 44, "stage": "mixed"},
            {**data["stack"][0], "number": 45, "stage": "mixed"},
        ])
        github = {"available": True, "states": {},
                  "lifecycle": {42: "MERGED", 43: "MERGED", 44: "MERGED", 45: "OPEN"},
                  "merged_at": {}, "stats": {}}
        review = page.review_snapshot(None, 42, HEAD, NOW)
        result = page.render(data, review, NOW, github)
        overview = result.split('<ol class="overview">', 1)[1].split("</ol>", 1)[0]
        self.assertIn('<li class="merged"><span class="stage-order">01</span>', overview)
        self.assertIn('<li><span class="stage-order">02</span>', overview)
        self.assertNotIn('<li class="merged"><span class="stage-order">02</span>', overview)
        self.assertIn('.overview li.merged { background: #e9e1eb; }', result)
        github["available"] = False
        unavailable = page.render(data, review, NOW, github)
        self.assertNotIn('<li class="merged"><span class="stage-order">01</span>', unavailable)

    def test_stale_and_future_timestamps_are_explicit(self):
        self.assertIn("status stale", page.time_label((NOW - timedelta(hours=25)).isoformat(), NOW))
        self.assertIn("status future-dated", page.time_label((NOW + timedelta(hours=1)).isoformat(), NOW))
        self.assertIn("Manual status checked 1h 0m ago", page.time_label((NOW - timedelta(hours=1)).isoformat(), NOW))
        self.assertEqual("25 Sep 00:00 NZST", page.local_time(NOW))

    @patch.object(page, "github_stages")
    @patch.object(page, "review_snapshot")
    def test_failed_live_refresh_preserves_existing_page(self, snapshot, github):
        snapshot.return_value = {"available": False, "reason": "Status fetch unavailable (TimeoutExpired)"}
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "status.json"
            output = Path(directory) / "index.html"
            data = self.fixture()
            data["review_tool"] = "/tmp/pr-review"
            source.write_text(json.dumps(data), encoding="utf-8")
            output.write_text("last good review snapshot", encoding="utf-8")
            with patch.object(sys, "argv", ["render.py", "--input", str(source), "--output", str(output)]):
                with self.assertRaisesRegex(RuntimeError, "existing page preserved"):
                    page.main()
            self.assertEqual("last good review snapshot", output.read_text(encoding="utf-8"))
            github.assert_not_called()
            snapshot.return_value = {"available": True}
            github.return_value = {"available": False}
            with patch.object(sys, "argv", ["render.py", "--input", str(source), "--output", str(output)]):
                with self.assertRaisesRegex(RuntimeError, "GitHub PR details unavailable"):
                    page.main()
            self.assertEqual("last good review snapshot", output.read_text(encoding="utf-8"))

    def test_github_stage_and_review_eligibility_are_distinct(self):
        data = self.fixture()
        data["stack"].append({**data["stack"][0], "number": 43})
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "head": HEAD, "source": "Controller", "as_of": NOW.isoformat(),
                       "queue": {42: {"channels": {"hosted": "HELD", "cli": "READY"},
                                      "review_activity": {
                                          "hosted": {"total": 2, "recent": [
                                              {"raw": 4, "accepted": 3, "attributable": True, "current_head": False, "non_counting": False},
                                              {"raw": 0, "accepted": 0, "attributable": True, "current_head": True, "non_counting": False},
                                          ]},
                                          "cli": {"total": 1, "recent": [
                                              {"raw": 2, "accepted": 0, "attributable": False, "current_head": False, "non_counting": True},
                                          ]},
                                      }},
                                 43: {"channels": {"hosted": "PARENT_MOVED", "cli": "PARENT_MOVED"}}},
                       "latest": {"Hosted": None, "CLI": None}, "threads": {"current": 0, "outdated": 0},
                       "ci": "PENDING", "merge": "BLOCKED", "verdict": "NOT READY"})
        github = {"available": True, "states": {42: True, 43: False},
                  "lifecycle": {42: "OPEN", 43: "MERGED"},
                  "merged_at": {43: (NOW - timedelta(minutes=12)).isoformat()},
                  "stats": {42: {"changedFiles": 5, "additions": 10, "deletions": 3}}}
        result = page.render(data, review, NOW, github)
        self.assertIn("<strong>Draft</strong> · Hosted Held · CLI Ready", result)
        self.assertIn('<li class="merged"><span class="order">02</span>', result)
        self.assertIn("<strong>Merged</strong> · 12m ago · 24 Sep 23:48 NZST", result)
        self.assertIn("5 files · +10/−3 lines", result)
        self.assertIn('<strong>Hosted</strong><span>2 completed</span>', result)
        self.assertIn('<span class="round-pill older" aria-label="4/3 (older head)">4/3</span>', result)
        self.assertIn('<strong>CLI</strong><span>1 completed</span>', result)
        self.assertIn("1 unlinked to a verified review", result)
        self.assertIn("1 excluded from taper", result)
        self.assertIn('Review and PR details refreshed 25 Sep 00:00 NZST', result)
        self.assertNotIn("Stack record checked", result)
        self.assertIn("do not establish taper or merge readiness", result)

    def test_zero_accepted_badges_keep_evidence_distinctions(self):
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {42: {
            "channels": {"hosted": "READY", "cli": "READY"},
            "review_activity": {"hosted": {"total": 4, "recent": [
                {"raw": 0, "accepted": 0, "current_head": True, "attributable": True, "non_counting": False},
                {"raw": 3, "accepted": 1, "current_head": True, "attributable": True, "non_counting": False},
                {"raw": 2, "accepted": 0, "current_head": False, "attributable": False, "non_counting": True},
            ]}}}}})
        result = page.render(self.fixture(), review, NOW)
        self.assertIn('<span class="round-pill zero-accepted" aria-label="0/0">0/0</span>', result)
        self.assertIn('<span class="round-pill" aria-label="3/1">3/1</span>', result)
        self.assertIn('<span class="round-pill zero-accepted older unlinked" '
                      'aria-label="2/0 (older head, unlinked, non-counting)">2/0</span>', result)
        self.assertIn('.round-pill.older.unlinked.zero-accepted { border-style: dashed; }', result)
        self.assertIn('1 unlinked to a verified review', result)
        self.assertIn('1 excluded from taper', result)

    @patch.object(page.subprocess, "run")
    def test_github_stage_fetch_fails_closed(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, '[{"number":42,"state":"MERGED","isDraft":false,"mergedAt":"2026-09-24T12:00:00Z","changedFiles":5,"additions":10,"deletions":3}]', "")
        github = page.github_stages(NOW)
        self.assertEqual({42: False}, github["states"])
        self.assertEqual({42: "MERGED"}, github["lifecycle"])
        self.assertEqual({42: "2026-09-24T12:00:00Z"}, github["merged_at"])
        self.assertEqual({"changedFiles": 5, "additions": 10, "deletions": 3}, github["stats"][42])
        run.side_effect = subprocess.TimeoutExpired([], 20)
        self.assertFalse(page.github_stages(NOW)["available"])

    @patch.object(page.subprocess, "run")
    def test_failed_timeout_and_malformed_fetches_degrade(self, run):
        tool = Path("/tmp/pr-review")
        run.return_value = subprocess.CompletedProcess([], 2, "", "secret private error")
        failed = page.review_snapshot(tool, 42, HEAD, NOW)
        self.assertFalse(failed["available"])
        self.assertIn("exit 2", failed["reason"])
        self.assertNotIn("secret", page.render(self.fixture(), failed, NOW))
        run.return_value = subprocess.CompletedProcess([], 2, "", "state contains fields outside the private configuration schema")
        self.assertIn("schema mismatch", page.review_snapshot(tool, 42, HEAD, NOW)["reason"])
        run.side_effect = subprocess.TimeoutExpired([], 30)
        self.assertIn("TimeoutExpired", page.review_snapshot(tool, 42, HEAD, NOW)["reason"])
        run.side_effect = None
        run.return_value = subprocess.CompletedProcess([], 0, "not json", "")
        self.assertIn("malformed", page.review_snapshot(tool, 42, HEAD, NOW)["reason"])

    @patch.object(page.subprocess, "run")
    def test_exact_head_review_snapshot(self, run):
        report = {
            "pr_number": 42,
            "pull_request": {"headRefOid": HEAD},
            "review_sequence": [
                {"type": "Hosted", "raw_found": 3, "accepted": 1, "reviewed_sha": "aaaaaaa", "created_at": "2026-09-24T09:00:00Z"},
                {"type": "Hosted", "raw_found": 0, "accepted": 0, "reviewed_sha": "aaaaaaaa", "created_at": "2026-09-24T10:00:00Z"},
                {"type": "CLI", "raw_found": 2, "accepted": 0, "reviewed_sha": "bbbbbbbb", "created_at": "2026-09-24T11:00:00Z"},
            ],
            "threads": {"current": 1, "outdated": 2},
            "ci": {"aggregate": {"state": "PENDING"}},
            "mergeability": {"mergeStateStatus": "BLOCKED"},
            "verdict": "NOT READY",
            "review_stack": {"prs": [{"pr": 42, "channels": {"hosted": "READY", "cli": "HELD"}}]},
        }
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        snapshot = page.review_snapshot(Path("/tmp/pr-review"), 42, HEAD, NOW)
        self.assertTrue(snapshot["available"])
        result = page.render(self.fixture(), snapshot, NOW)
        self.assertIn("Hosted Ready · CLI Held", result)
        self.assertNotIn("raw /", result)
        report["pull_request"]["headRefOid"] = "b" * 40
        report["review_stack"]["prs"][0]["head"] = "b" * 40
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        moved = page.review_snapshot(Path("/tmp/pr-review"), 42, HEAD, NOW)
        self.assertTrue(moved["available"])
        self.assertTrue(moved["saved_head_stale"])
        self.assertNotIn("base codex/", page.render(self.fixture(), moved, NOW))


if __name__ == "__main__":
    unittest.main()
