import importlib.util
import base64
import hashlib
import json
import shutil
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
        age_hash = base64.b64encode(hashlib.sha256(page.AGE_SCRIPT.encode()).digest()).decode()
        self.assertIn(f"script-src 'sha256-{refresh_hash}' 'sha256-{age_hash}'", result)
        self.assertIn(f'<script id="local-refresh-progress">{page.REFRESH_SCRIPT}</script>', result)
        self.assertIn(f'<script id="relative-age-updates">{page.AGE_SCRIPT}</script>', result)
        self.assertIn('class="refresh-progress" role="status" aria-live="polite"', result)
        self.assertIn("Review or PR details unavailable", result)
        self.assertNotIn("Takes about a minute", result)
        self.assertNotIn('href="../../task-briefs/', result)
        self.assertNotIn("Review eligibility unavailable", result)
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
        self.assertIn('.overview li.merged { background: #dbcbe2; }', result)
        github["available"] = False
        unavailable = page.render(data, review, NOW, github)
        self.assertNotIn('<li class="merged"><span class="stage-order">01</span>', unavailable)

    def test_stale_and_future_timestamps_are_explicit(self):
        self.assertIn("status stale", page.time_label((NOW - timedelta(hours=25)).isoformat(), NOW))
        self.assertIn("status future-dated", page.time_label((NOW + timedelta(hours=1)).isoformat(), NOW))
        self.assertIn("Manual status checked 1h 0m ago", page.time_label((NOW - timedelta(hours=1)).isoformat(), NOW))
        self.assertEqual("25 Sep 00:00 NZST", page.local_time(NOW))

    @unittest.skipUnless(shutil.which("node"), "Node is unavailable")
    def test_relative_age_script_updates_both_labels_over_time(self):
        javascript = """
const vm = require('node:vm');
const stamp = Date.parse('2026-09-24T12:00:00Z');
let now = stamp;
let tick;
let interval;
const labels = Array.from({length: 2}, () => ({dateTime: '2026-09-24T12:00:00Z', textContent: ''}));
vm.runInNewContext(process.argv[1], {
  document: {querySelectorAll: () => labels}, Date: {now: () => now, parse: Date.parse},
  setInterval: (callback, milliseconds) => { tick = callback; interval = milliseconds; }
});
const states = [labels.map(label => label.textContent)];
for (const minutes of [7, 132, 1440]) {
  now = stamp + minutes * 60000;
  tick();
  states.push(labels.map(label => label.textContent));
}
process.stdout.write(JSON.stringify({states, interval}));
"""
        run = subprocess.run(["node", "-e", javascript, page.AGE_SCRIPT], capture_output=True, text=True, check=True)
        result = json.loads(run.stdout)
        self.assertEqual([[value, value] for value in ("just now", "7m ago", "2h 12m ago", "1d ago")], result["states"])
        self.assertEqual(30000, result["interval"])

    @unittest.skipUnless(shutil.which("node"), "Node is unavailable")
    def test_refresh_elapsed_time_stays_in_button_and_prevents_duplicate_fetch(self):
        javascript = """
const vm = require('node:vm');
let submit;
let tick;
let clock = 0;
let fetchCount = 0;
let resolveFetch;
const pending = new Promise(resolve => { resolveFetch = resolve; });
const button = {disabled: false, textContent: 'Refresh review data'};
const progress = {textContent: ''};
const form = {
  action: '/refresh', classList: {add() {}, remove() {}},
  querySelector: selector => selector === 'button' ? button : progress,
  addEventListener: (event, handler) => { submit = handler; }
};
let reloaded = false;
vm.runInNewContext(process.argv[1], {
  document: {querySelector: () => form}, Date: {now: () => clock},
  setInterval: callback => { tick = callback; return 1; }, clearInterval: () => {},
  fetch: () => { fetchCount++; return pending; },
  window: {location: {reload: () => { reloaded = true; }}}
});
(async () => {
  const first = submit({preventDefault() {}});
  await submit({preventDefault() {}});
  const initial = [button.disabled, button.textContent, progress.textContent, fetchCount];
  clock = 18000;
  tick();
  const elapsed = [button.textContent, progress.textContent];
  resolveFetch({ok: true});
  await first;
  process.stdout.write(JSON.stringify({initial, elapsed, reloaded}));
})().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });
"""
        run = subprocess.run(["node", "-e", javascript, page.REFRESH_SCRIPT], capture_output=True, text=True, check=True)
        result = json.loads(run.stdout)
        self.assertEqual([True, "Refreshing · 0s", "Refresh in progress", 1], result["initial"])
        self.assertEqual(["Refreshing · 18s", "Refresh in progress"], result["elapsed"])
        self.assertTrue(result["reloaded"])

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
        self.assertIn('<span class="sub">Hosted held · CLI ready</span>', result)
        self.assertIn('<li class="merged"><span class="order">02</span>', result)
        self.assertIn('<strong>Merged</strong> <time class="relative-age" '
                      'datetime="2026-09-24T11:48:00+00:00" title="24 Sep 23:48 NZST">12m ago</time>', result)
        self.assertIn('5 files · <span class="additions">+10</span>/<span class="deletions">−3</span> lines', result)
        self.assertIn('<strong>Hosted</strong><span>2 completed</span>', result)
        self.assertIn('<span class="round-pill older" aria-label="4/3 (older head)">4/3</span>', result)
        self.assertIn('<strong>CLI</strong><span>1 completed</span>', result)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest · 1 from older heads</span>', result)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest · 1 from older heads '
                      '· 1 unlinked to a verified review · 1 excluded from taper</span>', result)
        self.assertNotIn('class="activity-note"', result)
        age_markup = ('Refreshed <time class="relative-age" datetime="2026-09-24T12:00:00+00:00" '
                      'title="25 Sep 00:00 NZST">just now</time>')
        self.assertEqual(2, result.count(age_markup))
        self.assertNotIn("Stack record checked", result)
        self.assertNotIn("do not establish taper or merge readiness", result)

    def test_open_review_line_shows_both_states_when_either_channel_is_ready(self):
        data = self.fixture()
        data["stack"].extend({**data["stack"][0], "number": number} for number in range(43, 47))
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {
            42: {"channels": {"hosted": "HELD", "cli": "HELD"}},
            43: {"channels": {"hosted": "PARENT_MOVED", "cli": "PARENT_MOVED"}},
            44: {"channels": {"hosted": "RATE_LIMITED", "cli": "READY"}},
            45: {"channels": {"hosted": "READY", "cli": "READY"}},
        }})
        github = {"available": True, "states": {number: False for number in range(42, 47)},
                  "lifecycle": {number: "OPEN" for number in range(42, 47)},
                  "merged_at": {}, "stats": {}}
        result = page.render(data, review, NOW, github)

        def row(number):
            queue = result.split('<ol class="stack">', 1)[1]
            return queue.split(f'{page.REPO_URL}{number}">', 1)[1].split("</li>", 1)[0]

        self.assertIn('<span class="sub">Hosted held · CLI held</span>', row(42))
        self.assertEqual(1, row(43).count('class="sub"'))
        self.assertIn('<span class="sub">Hosted rate limited · CLI ready</span>', row(44))
        self.assertIn('<span class="sub">Hosted ready · CLI ready</span>', row(45))
        self.assertEqual(1, row(46).count('class="sub"'))
        self.assertNotIn("Ready for review", result)
        self.assertNotIn("parent moved", result)
        self.assertNotIn("Review eligibility unavailable", result)

    def test_pr_size_colors_only_warn_above_ninety_files(self):
        data = self.fixture()
        data["stack"].append({**data["stack"][0], "number": 43})
        github = {"available": True, "states": {}, "lifecycle": {}, "merged_at": {},
                  "stats": {42: {"changedFiles": 90, "additions": 10, "deletions": 3},
                            43: {"changedFiles": 91, "additions": 12, "deletions": 4}}}
        result = page.render(data, page.review_snapshot(None, 42, HEAD, NOW), NOW, github)
        self.assertIn('90 files · <span class="additions">+10</span>/<span class="deletions">−3</span> lines', result)
        self.assertIn('<span class="files-over-warning">91 files</span> · '
                      '<span class="additions">+12</span>/<span class="deletions">−4</span> lines', result)
        self.assertNotIn('class="files-over-warning">90 files', result)

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
        self.assertIn('.round-pill.zero-accepted { background: #ad3b55; color: #fff; }', result)
        self.assertNotIn('.round-pill.unlinked.zero-accepted', result)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest · 1 from older heads '
                      '· 1 unlinked to a verified review · 1 excluded from taper</span>', result)
        self.assertLess(result.index('aria-label="0/0"'), result.index('aria-label="3/1"'))
        self.assertLess(result.index('aria-label="3/1"'), result.index('aria-label="2/0 (older head'))
        review["queue"][42]["review_activity"]["hosted"]["recent"] = []
        without_notes = page.render(self.fixture(), review, NOW)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest</span>', without_notes)
        self.assertNotIn('Recent, oldest to newest ·</span>', without_notes)

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
    def test_queue_wide_review_snapshot_extracts_front_and_rejects_missing_front(self, run):
        report = {
            "ordered_prs": [42, 43],
            "status": "COHERENT",
            "prs": [
                {"pr": 42, "head": HEAD, "channels": {"hosted": "READY", "cli": "HELD"}},
                {"pr": 43, "head": "c" * 40, "channels": {"hosted": "HELD", "cli": "HELD"}},
            ],
        }
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        snapshot = page.review_snapshot(Path("/tmp/pr-review"), 42, HEAD, NOW)
        self.assertEqual([sys.executable, "/tmp/pr-review", "status", "--json"], run.call_args.args[0])
        self.assertTrue(snapshot["available"])
        self.assertEqual(HEAD, snapshot["head"])
        self.assertFalse(snapshot["saved_head_stale"])
        self.assertEqual({42, 43}, set(snapshot["queue"]))
        result = page.render(self.fixture(), snapshot, NOW)
        self.assertIn('<span class="sub">Hosted ready · CLI held</span>', result)
        self.assertNotIn("raw /", result)
        report["prs"][0]["head"] = "b" * 40
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        moved = page.review_snapshot(Path("/tmp/pr-review"), 42, HEAD, NOW)
        self.assertTrue(moved["available"])
        self.assertTrue(moved["saved_head_stale"])
        self.assertNotIn("base codex/", page.render(self.fixture(), moved, NOW))
        report["prs"] = report["prs"][1:]
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        missing = page.review_snapshot(Path("/tmp/pr-review"), 42, HEAD, NOW)
        self.assertFalse(missing["available"])
        self.assertIn("review front missing", missing["reason"])


if __name__ == "__main__":
    unittest.main()
