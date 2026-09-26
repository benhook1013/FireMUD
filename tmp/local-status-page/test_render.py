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
    def test_channel_labels_distinguish_request_permission_from_cooldown_and_human_stop(self):
        targets = {"hosted": {"pr": 42, "status": "READY"}}
        self.assertEqual("Hosted ready to request", page.channel_label("hosted", "READY", 42, targets))
        self.assertEqual("Hosted waiting its turn", page.channel_label("hosted", "READY", 43, targets))
        self.assertEqual("Hosted cooldown active", page.channel_label("hosted", "RATE_LIMITED", 42, targets))
        self.assertEqual("Hosted new request blocked", page.channel_label("hosted", "HELD", 42, targets))
        self.assertEqual("Hosted human bypass", page.channel_label("hosted", "HUMAN_STOPPED", 42, targets))
        self.assertEqual("Hosted human bypass", page.channel_label("hosted", "OVERRIDE", 42, targets))
        self.assertEqual("CLI request status unknown", page.channel_label("cli", "READY", 42, {}))
        self.assertEqual("CLI request status unknown", page.channel_label("cli", "READY", 42,
                         {"cli": {"pr": None, "status": "UNKNOWN"}}))

    def fixture(self):
        return {
            "review_front": 42,
            "stack": [{"number": 42, "title": "<unsafe> & status", "stage": "<stage> & purpose", "base": "develop", "head": HEAD, "verified_at": "2026-09-24T11:00:00Z"}],
            "lanes": [
                {"name": name, "status": "RUNNING", "task": ["<script>alert(1)</script>"],
                 "next_action": "Legacy next action must not render", "up_next": ["Next & then"],
                 "blocker": ["No <leak>"], "brief": "general-local-status-page.md",
                 "verified_at": "2026-09-24T11:00:00Z"}
                for name in ("Gameplay", "Document", "General")
            ],
        }

    def test_html_escapes_manual_text_and_keeps_links(self):
        review = page.review_snapshot(None, 42, HEAD, NOW)
        result = page.render(self.fixture(), review, NOW)
        self.assertIn("&lt;unsafe&gt; &amp; status", result)
        self.assertIn("&lt;stage&gt; &amp; purpose", result)
        self.assertNotIn("The review train", result)
        self.assertNotIn("PR identities refreshed", result)
        self.assertNotIn("Review overview partial", result)
        self.assertNotIn("Queue position does not establish review eligibility or merge readiness.", result)
        self.assertIn('<section id="train"><div class="section-head"><h2>Configured review queue</h2></div>', result)
        self.assertIn('id="review-front"', result)
        self.assertIn("&lt;script&gt;alert(1)&lt;/script&gt;", result)
        self.assertIn("Next &amp; then", result)
        self.assertIn("No &lt;leak&gt;", result)
        self.assertNotIn("Legacy next action must not render", result)
        self.assertNotIn("<script>", result)
        self.assertIn('href="https://github.com/benhook1013/FireMUD/pull/42"', result)
        self.assertIn('href="https://github.com/benhook1013/FireMUD"', result)
        self.assertIn('<form class="refresh-form" action="/refresh" method="post">', result)
        self.assertIn('<span class="refresh-slot"><button type="submit">Refresh review data</button></span>', result)
        self.assertIn('<link rel="icon" type="image/svg+xml" href="/flame-ember.svg">', result)
        self.assertNotIn('href="/icon-options.html">Icon options</a>', result)
        self.assertIn('class="mast-inner"><div class="refresh-space"><form class="refresh-form"', result)
        self.assertIn('</form></div><div class="mast-content"><div class="mast-top"><h1 class="brand">FireMUD delivery status</h1>', result)
        self.assertIn('class="repo-link" href="https://github.com/benhook1013/FireMUD"', result)
        self.assertIn('</div><span class="refresh-time">', result)
        self.assertIn('.mast-inner { display: grid; grid-template-columns: 8.5rem minmax(0,1fr);', result)
        self.assertIn('width: 100%; height: 100%;', result)
        self.assertIn("const stageLabel = {rendering: 'Refreshing', publishing: 'Publishing'};", result)
        self.assertIn("form-action 'self'", result)
        self.assertIn("connect-src 'self'", result)
        refresh_hash = base64.b64encode(hashlib.sha256(page.REFRESH_SCRIPT.encode()).digest()).decode()
        age_hash = base64.b64encode(hashlib.sha256(page.AGE_SCRIPT.encode()).digest()).decode()
        snapshot_hash = base64.b64encode(hashlib.sha256(page.SNAPSHOT_SCRIPT.encode()).digest()).decode()
        self.assertIn(f"script-src 'sha256-{refresh_hash}' 'sha256-{age_hash}' 'sha256-{snapshot_hash}'", result)
        self.assertIn(f'<script id="local-refresh-progress">{page.REFRESH_SCRIPT}</script>', result)
        self.assertIn(f'<script id="relative-age-updates">{page.AGE_SCRIPT}</script>', result)
        self.assertIn(f'<script id="snapshot-updates">{page.SNAPSHOT_SCRIPT}</script>', result)
        self.assertIn('<meta name="status-snapshot" content="2026-09-24T12:00:00+00:00">', result)
        self.assertIn('class="refresh-progress" role="status" aria-live="polite"', result)
        self.assertIn("Review or PR details unavailable", result)
        self.assertNotIn("Takes about a minute", result)
        self.assertNotIn('href="../../task-briefs/', result)
        self.assertNotIn("Review eligibility unavailable", result)
        self.assertNotIn("Review front", result)
        self.assertLess(result.index('id="review-front"'), result.index('<section id="workers"'))
        self.assertLess(result.index('<section id="workers"'), result.index('<section id="train">'))
        self.assertLess(result.index("<h2>Worker lanes</h2>"), result.index("<h2>Configured review queue</h2>"))
        self.assertIn('<span class="lane-state lane-state-running" aria-label="RUNNING">', result)
        self.assertIn('<span class="lane-state-icon" aria-hidden="true"></span>RUNNING', result)
        self.assertIn('<div class="lane-topline"><h3>Gameplay</h3><span class="lane-state', result)
        self.assertIn('justify-content: space-between; gap: .75rem; width: 100%; min-width: 0;', result)
        self.assertIn('--smoke: #a51f27; --fire: #b71d35;', result)
        self.assertIn('.lane-content { padding: .8rem 1rem 1rem;', result)
        self.assertIn('.queue-stage > h3 { margin: .3rem 1rem 0 0; color: #37414a; font-size: 1.05rem; font-weight: 850;', result)
        self.assertIn('.cards { grid-template-columns: minmax(0,1fr); width: 100%; }', result)
        self.assertIn('.card-top .fresh { max-width: 100%; margin-right: .75rem; white-space: normal; text-align: right; }', result)
        self.assertNotIn('border-bottom: 2px solid var(--fire)', result)
        self.assertIn('<h4>Queued next</h4>', result)
        self.assertIn('<h4>Blocker</h4>', result)

    def test_lane_summaries_are_lists_and_optional_sections_disappear(self):
        data = self.fixture()
        gameplay, document, general = data["lanes"]
        gameplay.update({"task": ["Working through the merge queue."], "next_action": "ignored", "up_next": None, "blocker": None})
        document.update({"status": "PAUSED", "task": ["Working on Unit 1B membership authority (#2873).", "Applying approved tenant mapping."], "up_next": ["Queued work & detail"], "blocker": None})
        general.update({"task": "Working on #2875 migration test and proof repair.", "up_next": None, "blocker": None})
        result = page.render(data, page.review_snapshot(None, 42, HEAD, NOW), NOW)
        lanes = result.split('<section id="workers"', 1)[1].split('<section id="train"', 1)[0]
        gameplay_html = lanes.split('<h3>Gameplay</h3>', 1)[1].split('</article>', 1)[0]
        document_html = lanes.split('<h3>Document</h3>', 1)[1].split('</article>', 1)[0]
        general_html = lanes.split('<h3>General</h3>', 1)[1].split('</article>', 1)[0]
        self.assertIn("Working through the merge queue.", gameplay_html)
        self.assertNotIn("Next", gameplay_html)
        self.assertNotIn("Blocker", gameplay_html)
        self.assertNotIn("#2827", gameplay_html)
        self.assertIn('<span class="lane-state lane-state-paused" aria-label="PAUSED">', lanes)
        self.assertIn("Queued work &amp; detail", document_html)
        self.assertNotIn("Blocker", document_html)
        self.assertIn("Working on #2875 migration test and proof repair.", general_html)
        self.assertNotIn("Queued next", general_html)
        self.assertNotIn("Blocker", general_html)

    def test_lane_status_and_optional_detail_validation(self):
        data = self.fixture()
        data["lanes"][0]["status"] = "IDLE"
        with self.assertRaisesRegex(ValueError, "RUNNING or PAUSED"):
            page.render(data, page.review_snapshot(None, 42, HEAD, NOW), NOW)
        data = self.fixture()
        data["lanes"][0]["up_next"] = {"job": "bad shape"}
        with self.assertRaisesRegex(ValueError, "text or lists of text"):
            page.render(data, page.review_snapshot(None, 42, HEAD, NOW), NOW)

    def test_front_panel_shows_diff_and_controller_states_without_shifting_queue_marker(self):
        data = self.fixture()
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {
            42: {"channels": {"hosted": "READY", "cli": "HELD"}, "review_activity": {
                "hosted": {"total": 12, "recent": []},
                "cli": {"total": 8, "recent": []},
            }},
        }})
        github = {"available": True, "states": {}, "lifecycle": {42: "OPEN"}, "merged_at": {},
                  "stats": {42: {"changedFiles": 87, "additions": 5023, "deletions": 531}}}
        result = page.render(data, review, NOW, github)
        front = result.split('<section class="front-board"', 1)[1].split("</section>", 1)[0]
        front_copy = front.split('<div class="front-copy">', 1)[1].split('<div class="front-evidence">', 1)[0]
        front_evidence = front.split('<div class="front-evidence">', 1)[1]
        self.assertIn('<span class="sub front-fact-value">87 files · <span class="additions">+5,023</span>/'
                      '<span class="deletions">−531</span> lines</span>', front_evidence)
        self.assertIn('<span class="front-controller-state">Hosted request status unknown · CLI new request blocked</span>', front_evidence)
        self.assertNotIn('front-fact', front_copy)
        self.assertLess(front_evidence.index('<div class="front-facts">'), front_evidence.index('<div class="activity-grid">'))
        self.assertNotIn('At the review front', front)
        self.assertNotIn('Jump to the review front', front)
        self.assertIn('<section class="front-board" id="review-front"', result)
        self.assertIn('<li id="pr-42"', result)
        self.assertIn('.front-evidence { background: var(--fire);', result)
        self.assertIn('flex-direction: column; justify-content: center; align-items: stretch;', result)
        self.assertIn('.refresh-form button {', result)
        self.assertIn('height: 2.6rem;', result)
        self.assertIn('line-height: 1.2; font-weight: 700;', result)
        self.assertIn('.mast-top { display: flex; width: 100%;', result)
        self.assertIn('.brand { display: block; min-width: 0; margin: 0;', result)
        self.assertIn('.refresh-form { width: 8.5rem; height: 2.6rem;', result)
        self.assertIn('.refresh-time { display: block; margin-top: .55rem;', result)
        self.assertIn('.queue-stage > h3 { margin: .3rem 1rem 0 0; color: #37414a;', result)
        self.assertIn('.front-facts { grid-template-columns: 1fr; }', result)
        self.assertIn('.queue-stage > .stack li.front { background: var(--blush); border-left: 0; '
                      'box-shadow: inset 5px 0 var(--ember); }', result)
        self.assertIn('.queue-stage { display: grid; grid-template-columns: minmax(150px,.4fr) '
                      'minmax(0,1.6fr); gap: 1rem; margin-top: 0; padding: .55rem 1rem;', result)
        self.assertIn('.queue-stage { padding: .55rem .8rem; }', result)

        unavailable = page.render(
            data,
            page.review_snapshot(None, 42, HEAD, NOW),
            NOW,
            {"available": False, "states": {}, "lifecycle": {}, "merged_at": {}, "stats": {}},
        )
        unavailable_front = unavailable.split('<section class="front-board"', 1)[1].split("</section>", 1)[0]
        self.assertIn("Diff size unavailable", unavailable_front)
        self.assertIn("Hosted/CLI states unavailable", unavailable_front)

    def test_stage_order_must_match_stack_order(self):
        data = self.fixture()
        data["stack"].extend([
            {**data["stack"][0], "number": 43, "stage": "second"},
            {**data["stack"][0], "number": 44},
        ])
        with self.assertRaisesRegex(ValueError, "contiguous"):
            page.render(data, page.review_snapshot(None, 42, HEAD, NOW), NOW)

    def test_stage_grouped_queue_marks_merged_rows_with_badges(self):
        data = self.fixture()
        data["stack"].extend([
            {**data["stack"][0], "number": 43},
            {**data["stack"][0], "number": 44, "stage": "mixed"},
            {**data["stack"][0], "number": 45, "stage": "mixed"},
        ])
        github = {"available": True, "states": {},
                  "lifecycle": {42: "MERGED", 43: "MERGED", 44: "MERGED", 45: "CLOSED"},
                  "merged_at": {}, "stats": {}}
        review = page.review_snapshot(None, 42, HEAD, NOW)
        result = page.render(data, review, NOW, github)
        train = result.split('<div class="review-train">', 1)[1].split('</div></section>', 1)[0]
        self.assertIn('<section class="queue-stage" aria-labelledby="queue-stage-1">', train)
        self.assertIn('<li id="pr-42" class="merged front">', train)
        self.assertIn('<div class="pr-title-line"><a href="https://github.com/benhook1013/FireMUD/pull/42">'
                      '#42 &lt;unsafe&gt; &amp; status</a></div>', train)
        self.assertIn('<div class="pr-status-line"><span class="queue-status queue-status-merged">MERGED</span>', train)
        self.assertIn('<section class="queue-stage" aria-labelledby="queue-stage-2">', train)
        self.assertIn('<li id="pr-44" class="merged">', train)
        self.assertNotIn('<li id="pr-45" class="merged">', train)
        self.assertIn('<span class="queue-status queue-status-closed">CLOSED</span>', train)
        self.assertIn('.queue-status-merged { background: var(--plum); color: #fff; }', result)
        self.assertIn('.queue-stage > .stack li.merged { background: var(--plum-wash); border-left: 0; box-shadow: none; }', result)
        self.assertIn('.queue-stage > .stack li { position: relative; padding: 0; }', result)
        self.assertIn('.queue-stage > .stack li.merged .order { background: var(--plum); }', result)
        self.assertLess(result.index('.queue-stage > .stack li.front { background: var(--blush);'),
                        result.index('.queue-stage > .stack li.merged { background: var(--plum-wash);'))
        self.assertLess(result.index('.queue-stage > .stack li.front .order { background: var(--fire);'),
                        result.index('.queue-stage > .stack li.merged .order { background: var(--plum);'))
        github["available"] = False
        unavailable = page.render(data, review, NOW, github)
        self.assertNotIn('class="queue-status queue-status-merged">MERGED', unavailable)

    def test_queue_status_badges_require_explicit_controller_evidence(self):
        data = self.fixture()
        data["stack"].extend({**data["stack"][0], "number": number} for number in range(43, 47))
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {
            42: {"channels": {"hosted": "HELD", "cli": "HELD"}},
            43: {"channels": {"hosted": "RUNNING", "cli": "HELD"}},
            44: {"channels": {"hosted": "HUMAN_STOPPED", "cli": "READY"}},
            45: {"channels": {"hosted": "HUMAN_STOPPED", "cli": "OVERRIDE"}},
        }})
        github = {"available": True, "states": {}, "lifecycle": {n: "OPEN" for n in range(42, 47)},
                  "merged_at": {}, "stats": {}}
        result = page.render(data, review, NOW, github)

        def row(number):
            return result.split(f'<li id="pr-{number}"', 1)[1].split("</li>", 1)[0]

        self.assertIn('queue-status-front">REVIEW FRONT', row(42))
        self.assertIn('queue-status-reviewing">REVIEWING', row(43))
        self.assertIn('queue-status-queued">QUEUED', row(44))
        self.assertIn('Hosted human bypass · CLI request status unknown', row(44))
        self.assertIn('queue-status-review-closed">REVIEW CLOSED', row(45))
        self.assertIn('Hosted human bypass · CLI human bypass', row(45))
        self.assertIn('queue-status-pending">PENDING', row(46))

        review["queue"][43]["channels"] = {"hosted": "PARENT_MOVED", "cli": "PARENT_MOVED"}
        no_active_evidence = page.render(data, review, NOW, github)
        next_row = no_active_evidence.split('<li id="pr-43"', 1)[1].split("</li>", 1)[0]
        self.assertIn('queue-status-up-next">UP NEXT', next_row)
        self.assertNotIn('queue-status-queued">QUEUED', next_row)

        unavailable = page.render(
            data,
            page.review_snapshot(None, 42, HEAD, NOW),
            NOW,
            github,
        )
        unavailable_train = unavailable.split('<div class="review-train">', 1)[1].split('</div></section>', 1)[0]
        self.assertIn('queue-status-front">REVIEW FRONT', unavailable_train)
        self.assertIn('queue-status-pending">PENDING', unavailable_train)
        self.assertNotIn('queue-status-reviewing">REVIEWING', unavailable_train)

    def test_only_selected_channel_target_says_ready_to_request(self):
        data = self.fixture()
        data["stack"].append({**data["stack"][0], "number": 43})
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {
            42: {"channels": {"hosted": "READY", "cli": "COMPLETE"}},
            43: {"channels": {"hosted": "READY", "cli": "READY"}},
        }, "review_targets": {
            "hosted": {"pr": 42, "status": "READY"},
            "cli": {"pr": 43, "status": "READY"},
        }})
        result = page.render(data, review, NOW)
        front = result.split('<li id="pr-42"', 1)[1].split("</li>", 1)[0]
        later = result.split('<li id="pr-43"', 1)[1].split("</li>", 1)[0]
        self.assertIn("Hosted ready to request · CLI review complete", front)
        self.assertIn("Hosted waiting its turn · CLI ready to request", later)

    def test_stale_and_future_timestamps_are_explicit(self):
        self.assertIn("status stale", page.time_label((NOW - timedelta(hours=25)).isoformat(), NOW))
        self.assertIn("status future-dated", page.time_label((NOW + timedelta(hours=1)).isoformat(), NOW))
        self.assertIn("Manual status checked 1h 0m ago", page.time_label((NOW - timedelta(hours=1)).isoformat(), NOW))
        self.assertEqual("25 Sep 00:00 NZST", page.local_time(NOW))

    def test_round_age_uses_compact_bounded_units(self):
        for minutes, expected in (
            (0, "<1m"), (1, "1m"), (99, "99m"), (100, "1h"),
            (5999, "99h"), (6000, "4d"), (99 * 1440, "99d"), (100 * 1440, "99d+"),
        ):
            with self.subTest(minutes=minutes):
                self.assertEqual(expected, page.round_age(NOW - timedelta(minutes=minutes), NOW))
        self.assertIsNone(page.round_completion(None, NOW))
        self.assertIsNone(page.round_completion("not a timestamp", NOW))
        self.assertIsNone(page.round_completion("2026-09-24T12:00:00", NOW))
        self.assertIsNone(page.round_completion((NOW + timedelta(minutes=1)).isoformat(), NOW))

    @unittest.skipUnless(shutil.which("node"), "Node is unavailable")
    def test_relative_age_script_updates_both_labels_over_time(self):
        javascript = """
const vm = require('node:vm');
const stamp = Date.parse('2026-09-24T12:00:00Z');
let now = stamp;
let tick;
let interval;
const labels = Array.from({length: 2}, () => ({dateTime: '2026-09-24T12:00:00Z', textContent: ''}));
const rounds = Array.from({length: 2}, () => ({dateTime: '2026-09-24T12:00:00Z', textContent: ''}));
vm.runInNewContext(process.argv[1], {
  document: {querySelectorAll: selector => selector === '.relative-age' ? labels : rounds},
  Date: {now: () => now, parse: Date.parse},
  setInterval: (callback, milliseconds) => { tick = callback; interval = milliseconds; }
});
const states = [labels.map(label => label.textContent)];
const roundStates = [rounds.map(label => label.textContent)];
for (const minutes of [7, 99, 100, 5999, 6000, 144000]) {
  now = stamp + minutes * 60000;
  tick();
  states.push(labels.map(label => label.textContent));
  roundStates.push(rounds.map(label => label.textContent));
}
process.stdout.write(JSON.stringify({states, roundStates, interval}));
"""
        run = subprocess.run(["node", "-e", javascript, page.AGE_SCRIPT], capture_output=True, text=True, check=True)
        result = json.loads(run.stdout)
        self.assertEqual([[value, value] for value in (
            "just now", "7m ago", "1h 39m ago", "1h 40m ago", "4d ago", "4d ago", "100d ago"
        )], result["states"])
        self.assertEqual([[value, value] for value in (
            "<1m", "7m", "99m", "1h", "99h", "4d", "99d+"
        )], result["roundStates"])
        self.assertEqual(30000, result["interval"])

    @unittest.skipUnless(shutil.which("node"), "Node is unavailable")
    def test_snapshot_script_reloads_only_for_newer_page_and_restores_scroll(self):
        javascript = """
const vm = require('node:vm');
let tick;
let onVisibility;
let visibilityState = 'visible';
let source = '<meta name="status-snapshot" content="2026-09-24T12:00:00+00:00">';
let fetches = 0;
let reloads = 0;
let restored = null;
let interval = null;
const storage = new Map([['firemud-status-scroll-y', '76']]);
const document = {
  get visibilityState() { return visibilityState; },
  querySelector: selector => selector.startsWith('meta')
    ? {content: '2026-09-24T12:00:00+00:00'} : null,
  addEventListener: (name, listener) => { if (name === 'visibilitychange') onVisibility = listener; }
};
vm.runInNewContext(process.argv[1], {
  document, Date, Number, String,
  window: {scrollY: 310, scrollTo: (x, y) => { restored = [x, y]; },
    location: {pathname: '/', reload: () => { reloads++; }}},
  sessionStorage: {
    getItem: key => storage.has(key) ? storage.get(key) : null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  },
  requestAnimationFrame: callback => callback(),
  fetch: async (path, options) => {
    if (path !== '/' || options.cache !== 'no-store') throw Error('unexpected fetch');
    fetches++;
    return {ok: true, text: async () => source};
  },
  setInterval: (callback, milliseconds) => { tick = callback; interval = milliseconds; }
});
(async () => {
  await tick();
  const unchanged = [fetches, reloads];
  visibilityState = 'hidden';
  await tick();
  const hidden = [fetches, reloads];
  source = '<meta name="status-snapshot" content="2026-09-24T12:30:00+00:00">';
  visibilityState = 'visible';
  onVisibility();
  await new Promise(setImmediate);
  process.stdout.write(JSON.stringify({unchanged, hidden, fetches, reloads, restored,
    saved: storage.get('firemud-status-scroll-y'), interval}));
})().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });
"""
        run = subprocess.run(["node", "-e", javascript, page.SNAPSHOT_SCRIPT],
                             capture_output=True, text=True, check=True)
        result = json.loads(run.stdout)
        self.assertEqual([1, 0], result["unchanged"])
        self.assertEqual([1, 0], result["hidden"])
        self.assertEqual((2, 1), (result["fetches"], result["reloads"]))
        self.assertEqual([0, 76], result["restored"])
        self.assertEqual("310", result["saved"])
        self.assertEqual(120000, result["interval"])

    @unittest.skipUnless(shutil.which("node"), "Node is unavailable")
    def test_refresh_shows_render_and_publish_progress_without_duplicate_post(self):
        javascript = """
const vm = require('node:vm');
let submit;
let tick;
let clock = 0;
let postCount = 0;
let statusPhase = 'rendering';
let resolvePost;
const pending = new Promise(resolve => { resolvePost = resolve; });
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
  fetch: url => url === '/refresh-status'
    ? Promise.resolve({ok: true, json: async () => ({phase: statusPhase})})
    : (postCount++, pending),
  window: {location: {reload: () => { reloaded = true; }}}
});
(async () => {
  const first = submit({preventDefault() {}});
  await submit({preventDefault() {}});
  const initial = [button.disabled, button.textContent, progress.textContent, postCount];
  statusPhase = 'publishing';
  clock = 18000;
  tick();
  await new Promise(setImmediate);
  tick();
  await new Promise(setImmediate);
  tick();
  const publishing = [button.textContent, progress.textContent, postCount];
  resolvePost({ok: true});
  await first;
  process.stdout.write(JSON.stringify({initial, publishing, reloaded}));
})().catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });
"""
        run = subprocess.run(["node", "-e", javascript, page.REFRESH_SCRIPT], capture_output=True, text=True, check=True)
        result = json.loads(run.stdout)
        self.assertEqual([True, "Refreshing · 0s", "Refreshing local review data", 1], result["initial"])
        self.assertEqual(["Publishing · 18s", "Publishing public status page", 1], result["publishing"])
        self.assertTrue(result["reloaded"])

    @unittest.skipUnless(shutil.which("node"), "Node is unavailable")
    def test_refresh_reports_render_and_publish_failures_distinctly(self):
        javascript = """
const vm = require('node:vm');
async function failure(status) {
  let submit;
  const button = {disabled: false, textContent: 'Refresh review data'};
  const progress = {textContent: ''};
  const classes = {add() {}, remove() {}};
  const form = {
    action: '/refresh', classList: classes,
    querySelector: selector => selector === 'button' ? button : progress,
    addEventListener: (event, handler) => { submit = handler; }
  };
  vm.runInNewContext(process.argv[1], {
    document: {querySelector: () => form}, Date,
    setInterval: () => 1, clearInterval: () => {},
    fetch: async url => url === '/refresh-status'
      ? {ok: true, json: async () => ({phase: 'rendering'})}
      : {ok: false, status},
    window: {location: {reload() { throw Error('unexpected reload'); }}}
  });
  await submit({preventDefault() {}});
  return progress.textContent;
}
Promise.all([failure(502), failure(503)]).then(result => process.stdout.write(JSON.stringify(result)))
  .catch(error => { process.stderr.write(String(error)); process.exitCode = 1; });
"""
        run = subprocess.run(["node", "-e", javascript, page.REFRESH_SCRIPT], capture_output=True, text=True, check=True)
        self.assertEqual([
            "Local refresh failed. The previous local and public pages are still available.",
            "Local page updated, but public publishing failed. Try again shortly.",
        ], json.loads(run.stdout))

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
                                              {"raw": 4, "accepted": 3, "attributable": True, "current_head": False, "non_counting": False,
                                               "completed_at": (NOW - timedelta(minutes=14)).isoformat()},
                                              {"raw": 0, "accepted": 0, "attributable": True, "current_head": True, "non_counting": False},
                                          ]},
                                          "cli": {"total": 1, "recent": [
                                              {"raw": 2, "accepted": 0, "attributable": False, "current_head": False, "non_counting": True},
                                          ]},
                                      }},
                                 43: {"channels": {"hosted": "PARENT_MOVED", "cli": "PARENT_MOVED"},
                                      "review_activity": {"hosted": {"total": 1, "recent": [
                                          {"raw": 1, "accepted": 1, "attributable": True, "current_head": False,
                                           "non_counting": False, "completed_at": (NOW - timedelta(hours=2)).isoformat()},
                                      ]}}}},
                       "latest": {"Hosted": None, "CLI": None}, "threads": {"current": 0, "outdated": 0},
                       "ci": "PENDING", "merge": "BLOCKED", "verdict": "NOT READY"})
        github = {"available": True, "states": {42: True, 43: False},
                  "lifecycle": {42: "OPEN", 43: "MERGED"},
                  "merged_at": {43: (NOW - timedelta(minutes=12)).isoformat()},
                  "stats": {42: {"changedFiles": 5, "additions": 10, "deletions": 3}}}
        result = page.render(data, review, NOW, github)
        self.assertIn('<span class="sub">Hosted new request blocked · CLI request status unknown</span>', result)
        self.assertIn('<li id="pr-43" class="merged"><span class="order" aria-label="Queue position 2">02</span>', result)
        merged_row = result.split('<li id="pr-43"', 1)[1].split('</li>', 1)[0]
        front_row = result.split('<li id="pr-42"', 1)[1].split('</li>', 1)[0]
        self.assertIn('<div class="pr-title-line"><a href="https://github.com/benhook1013/FireMUD/pull/43">'
                      '#43 &lt;unsafe&gt; &amp; status</a></div>', merged_row)
        self.assertIn('<div class="pr-title-line"><a href="https://github.com/benhook1013/FireMUD/pull/42">'
                      '#42 &lt;unsafe&gt; &amp; status</a></div>', front_row)
        self.assertIn('<div class="pr-status-line"><span class="queue-status queue-status-merged">MERGED</span>'
                      '<span class="sub"><time class="relative-age" '
                      'datetime="2026-09-24T11:48:00+00:00" title="24 Sep 23:48 NZST">12m ago</time></span>', merged_row)
        self.assertIn('<div class="pr-status-line"><span class="queue-status queue-status-draft">DRAFT</span>'
                      '<span class="sub">Hosted new request blocked · CLI request status unknown</span>', front_row)
        self.assertNotIn('Hosted parent changed', merged_row)
        self.assertNotIn('CLI parent changed', merged_row)
        self.assertIn('<strong>Hosted CodeRabbit</strong><span>1 completed</span>', merged_row)
        self.assertIn('<time class="round-age" datetime="2026-09-24T10:00:00+00:00">2h</time>', merged_row)
        self.assertIn('5 files · <span class="additions">+10</span>/<span class="deletions">−3</span> lines', result)
        self.assertIn('<strong>Hosted CodeRabbit</strong><span>2 completed</span>', result)
        self.assertIn('<span class="round-pill older" aria-label="4/3 (older head), Completed 24 Sep 2026 23:46 NZST" '
                      'title="Completed 24 Sep 2026 23:46 NZST"><span>4/3</span>'
                      '<time class="round-age" datetime="2026-09-24T11:46:00+00:00">14m</time></span>', result)
        self.assertIn('<strong>CLI CodeRabbit</strong><span>1 completed</span>', result)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest · 1 from older heads</span>', result)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest · 1 from older heads '
                      '· 1 unlinked to a verified review · 1 excluded from taper</span>', result)
        self.assertNotIn('class="activity-note"', result)
        age_markup = ('PR data refreshed <time class="relative-age" datetime="2026-09-24T12:00:00+00:00" '
                      'title="25 Sep 00:00 NZST">just now</time>')
        self.assertEqual(1, result.count(age_markup))
        self.assertNotIn('Refreshed <time', result)
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
            return result.split(f'<li id="pr-{number}"', 1)[1].split("</li>", 1)[0]

        self.assertIn('<span class="sub">Hosted new request blocked · CLI new request blocked</span>', row(42))
        self.assertIn('queue-status-up-next">UP NEXT</span><span class="sub">Hosted parent changed · CLI parent changed</span>', row(43))
        self.assertIn('<span class="sub">Hosted parent changed · CLI parent changed</span>', row(43))
        self.assertIn('<span class="sub">Hosted cooldown active · CLI request status unknown</span>', row(44))
        self.assertIn('<span class="sub">Hosted request status unknown · CLI request status unknown</span>', row(45))
        self.assertEqual(2, row(46).count('class="sub"'))
        self.assertNotIn("Ready for review", result)
        self.assertNotIn("Review eligibility unavailable", result)

    def test_next_open_pr_after_front_shows_both_controller_states_without_claiming_readiness(self):
        data = self.fixture()
        data["stack"].extend({**data["stack"][0], "number": number} for number in range(43, 47))
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {
            42: {"channels": {"hosted": "HELD", "cli": "HELD"}},
            45: {"channels": {"hosted": "OVER_CEILING", "cli": "PARENT_MOVED"}},
            46: {"channels": {"hosted": "HELD", "cli": "HELD"}},
        }})
        github = {"available": True, "states": {},
                  "lifecycle": {42: "OPEN", 43: "MERGED", 44: "CLOSED", 45: "OPEN", 46: "OPEN"},
                  "merged_at": {}, "stats": {}}
        result = page.render(data, review, NOW, github)
        def row(number):
            return result.split(f'<li id="pr-{number}"', 1)[1].split("</li>", 1)[0]

        self.assertIn('<span class="sub">Hosted new request blocked · CLI new request blocked</span>', row(42))
        self.assertNotIn("Up next in queue", row(43))
        self.assertNotIn("Up next in queue", row(44))
        self.assertIn('queue-status-up-next">UP NEXT</span><span class="sub">Hosted file limit · CLI parent changed</span>', row(45))
        self.assertIn('<span class="sub">Hosted file limit · CLI parent changed</span>', row(45))
        self.assertNotIn("Up next in queue", row(46))
        self.assertIn('<span class="sub">Hosted new request blocked · CLI new request blocked</span>', row(46))
        self.assertNotIn("Up next in queue", result)

        review["queue"][45] = {"channels": {"hosted": "HELD"}}
        incomplete = page.render(data, review, NOW, github)
        next_row = incomplete.split('<li id="pr-45"', 1)[1].split("</li>", 1)[0]
        self.assertIn("Hosted new request blocked · Review state unavailable (CLI)", next_row)
        self.assertNotIn("CLI new request blocked", next_row)

    def test_near_front_and_later_rows_show_available_controller_states(self):
        data = self.fixture()
        data["stack"].extend({**data["stack"][0], "number": number} for number in range(43, 49))
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {
            42: {"channels": {"hosted": "READY", "cli": "HELD"}},
            43: {"detail_level": "deep", "channels": {"hosted": "HELD", "cli": "HELD"}},
            44: {"detail_level": "deep", "channels": {"hosted": "HELD", "cli": "PARENT_MOVED"}},
            45: {"detail_level": "deep", "channels": {"hosted": "HUMAN_STOPPED", "cli": "HELD"}},
            46: {"detail_level": "identity_only", "evidence_status": "unknown",
                 "channels": {"hosted": "NOT_CHECKED", "cli": "NOT_CHECKED"}},
            47: {"channels": {"hosted": "HELD"}},
        }})
        github = {"available": True, "states": {}, "lifecycle": {number: "OPEN" for number in range(42, 49)},
                  "merged_at": {}, "stats": {}}
        result = page.render(data, review, NOW, github)

        def row(number):
            return result.split(f'<li id="pr-{number}"', 1)[1].split("</li>", 1)[0]

        self.assertIn("Hosted request status unknown · CLI new request blocked", row(42))
        self.assertIn("Hosted new request blocked · CLI new request blocked", row(43))
        self.assertIn("Hosted new request blocked · CLI parent changed", row(44))
        self.assertIn("Hosted human bypass · CLI new request blocked", row(45))
        self.assertIn("Hosted not checked · CLI not checked", row(46))
        self.assertIn("Review evidence not checked in this refresh · identity only", row(46))
        self.assertIn("Hosted new request blocked · Review state unavailable (CLI)", row(47))
        self.assertIn("Review state unavailable", row(48))
        self.assertNotIn("Ready for review", result)

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
        self.assertIn('<span class="round-pill zero-accepted" aria-label="0/0, Completion time unavailable" '
                      'title="Completion time unavailable"><span>0/0</span><span class="round-age">age n/a</span></span>', result)
        self.assertIn('<span class="round-pill" aria-label="3/1, Completion time unavailable" '
                      'title="Completion time unavailable"><span>3/1</span><span class="round-age">age n/a</span></span>', result)
        self.assertIn('<span class="round-pill zero-accepted older unlinked" '
                      'aria-label="2/0 (older head, unlinked, non-counting), Completion time unavailable" '
                      'title="Completion time unavailable"><span>2/0</span><span class="round-age">age n/a</span></span>', result)
        self.assertIn('.round-pill.zero-accepted { background: #ad3b55; color: #fff; }', result)
        self.assertNotIn('.round-pill.unlinked.zero-accepted', result)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest · 1 from older heads '
                      '· 1 unlinked to a verified review · 1 excluded from taper</span>', result)
        self.assertLess(result.index('aria-label="0/0,'), result.index('aria-label="3/1,'))
        self.assertLess(result.index('aria-label="3/1,'), result.index('aria-label="2/0 (older head'))
        review["queue"][42]["review_activity"]["hosted"]["recent"] = []
        without_notes = page.render(self.fixture(), review, NOW)
        self.assertIn('<span class="activity-caption">Recent, oldest to newest</span>', without_notes)
        self.assertNotIn('Recent, oldest to newest ·</span>', without_notes)

    def test_badge_timestamp_is_escaped_and_invalid_values_stay_unknown(self):
        review = page.review_snapshot(None, 42, HEAD, NOW)
        review.update({"available": True, "queue": {42: {
            "review_activity": {"hosted": {"total": 2, "recent": [
                {"raw": 0, "accepted": 0, "current_head": True, "attributable": True,
                 "non_counting": False, "completed_at": (NOW - timedelta(seconds=30)).isoformat()},
                {"raw": 2, "accepted": 1, "current_head": False, "attributable": True,
                 "non_counting": False, "completed_at": '2026-09-24T11:00:00Z" onclick="alert(1)'},
            ]}}}}})
        result = page.render(self.fixture(), review, NOW)
        self.assertIn('class="round-pill zero-accepted"', result)
        self.assertIn('<time class="round-age" datetime="2026-09-24T11:59:30+00:00">&lt;1m</time>', result)
        self.assertIn('class="round-pill older" aria-label="2/1 (older head), Completion time unavailable"', result)
        self.assertNotIn('onclick="alert(1)', result)

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
            "review_targets": {"hosted": {"pr": 42, "status": "READY"}},
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
        self.assertEqual(42, snapshot["review_targets"]["hosted"]["pr"])
        self.assertFalse(snapshot["saved_head_stale"])
        self.assertEqual({42, 43}, set(snapshot["queue"]))
        result = page.render(self.fixture(), snapshot, NOW)
        self.assertIn('<span class="sub">Hosted ready to request · CLI new request blocked</span>', result)
        self.assertNotIn("raw /", result.split('<div class="review-train">', 1)[1])
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

    @patch.object(page.subprocess, "run")
    def test_controller_completion_timestamp_reaches_review_age(self, run):
        round_result = {"raw": 3, "accepted": 2, "attributable": True,
                        "current_head": True, "non_counting": False,
                        "completed_at": (NOW - timedelta(minutes=31)).isoformat()}
        report = {"prs": [{"pr": 42, "head": HEAD,
                           "review_activity": {"hosted": {"total": 1, "recent": [round_result]}}}]}
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        snapshot = page.review_snapshot(Path("/tmp/pr-review"), 42, HEAD, NOW)
        self.assertEqual(round_result["completed_at"],
                         snapshot["queue"][42]["review_activity"]["hosted"]["recent"][0]["completed_at"])
        rendered = page.render(self.fixture(), snapshot, NOW)
        self.assertIn('<time class="round-age" datetime="2026-09-24T11:29:00+00:00">31m</time>', rendered)
        self.assertNotIn('age n/a', rendered)

    @patch.object(page.subprocess, "run")
    def test_windowed_overview_marks_identity_only_tail_as_informational(self, run):
        data = self.fixture()
        data["stack"].extend([{**data["stack"][0], "number": 43}, {**data["stack"][0], "number": 44}])
        report = {
            "status": "PARTIAL", "mode": "windowed", "detail_window": {"deep_prs": [42]},
            "prs": [
                {"pr": 42, "head": HEAD, "detail_level": "deep", "evidence_status": "current",
                 "channels": {"hosted": "READY", "cli": "HELD"}},
                {"pr": 43, "head": "b" * 40, "detail_level": "identity_only", "evidence_status": "unknown",
                 "channels": {"hosted": "NOT_CHECKED", "cli": "NOT_CHECKED"}},
                {"pr": 44, "head": "c" * 40, "detail_level": "identity_only", "evidence_status": "stale",
                 "channels": {"hosted": "NOT_CHECKED", "cli": "NOT_CHECKED"}},
            ],
        }
        run.return_value = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        review = page.review_snapshot(Path("/tmp/pr-review"), 42, HEAD, NOW)
        self.assertEqual("PARTIAL", review["controller_status"])
        github = {"available": True, "states": {}, "lifecycle": {}, "merged_at": {}, "stats": {}}
        result = page.render(data, review, NOW, github)
        header = result.split("</header>", 1)[0]
        queue = result.split("<h2>Configured review queue</h2>", 1)[1]
        self.assertIn("PR data refreshed <time", header)
        self.assertNotIn("Review overview partial", header)
        self.assertNotIn("PR identities refreshed", queue)
        self.assertNotIn("Review overview partial", result)
        self.assertNotIn("Queue position does not establish review eligibility or merge readiness.", result)
        self.assertIn("Review evidence not checked in this refresh · identity only", result)
        self.assertIn("Review evidence stale after identity or parent movement · identity only", result)
        self.assertIn("Hosted not checked · CLI not checked", result)


if __name__ == "__main__":
    unittest.main()
