from __future__ import annotations

import importlib
import json
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "dev-tools"
sys.path.insert(0, str(TOOLS))

web = importlib.import_module("fire_controller.web")


PRIVATE_SENTINEL = "PRIVATE-BRIEF-SENTINEL-7f2d"


def public_row():
    return {
        "id": "job-1",
        "name": "Build the bridge",
        "worker": "Gameplay",
        "workstream_id": "shared-foundations",
        "title": "Keep the lane moving",
        "status": "blocked",
        "primary": True,
        "summary": "Finish the safe handoff. [private](/jobs/private-job)",
        "progress": "Public progress.",
        "blocker": "Waiting on the reviewed interface.",
        "checklist": [
            {"id": "c1", "text": "Confirm the contract", "done": True, "private": PRIVATE_SENTINEL},
            {"id": "c2", "text": "Resume after review [private](/jobs/private-job)", "done": False},
        ],
        "brief": PRIVATE_SENTINEL,
        "chat_id": "private-chat",
        "updates": [{"body": PRIVATE_SENTINEL}],
        "notes": [{"body": PRIVATE_SENTINEL}],
        "history": [{"brief": PRIVATE_SENTINEL}],
        "checkpoint": {"pointers": {"private_path": PRIVATE_SENTINEL}},
        "sources": [{"path": PRIVATE_SENTINEL}],
        "path": PRIVATE_SENTINEL,
    }


class FakeStore:
    def __init__(self):
        self.get_calls = []
        self.history_calls = []
        self.detail = {
            **{key: value for key, value in public_row().items() if key in {
                "id", "name", "worker", "title", "status", "primary", "summary", "progress", "blocker",
                "checklist",
            }},
            "brief": f"# Private instructions\n\n{PRIVATE_SENTINEL}\n\n[web](https://example.com) "
                     "[local](/jobs/job-1/history) [unsafe](javascript:alert(1)) "
                     "![remote image](https://example.com/private.png)\n\n<script>private()</script>",
            "latest_checkpoint": {
                "done": "Finished the first milestone.",
                "next_steps": "Complete the handoff.",
                "blocker": "Waiting on a dependency.",
                "pointers": {"branch": "codex/jobs", "path": PRIVATE_SENTINEL},
            },
            "updates": [{"kind": "progress", "created_at": "2026-10-03T10:00:00Z",
                         "body": f"Latest update {PRIVATE_SENTINEL}"}],
            "notes": [{"kind": "reminder", "phase": "after-review", "status": "pending",
                       "body": f"Remember this {PRIVATE_SENTINEL}"}],
        }
        self.revisions = [
            {"revision": 2, "created_at": "2026-10-03T10:00:00Z", "status": "blocked",
             "title": "Latest revision"},
            {"revision": 1, "created_at": "2026-10-02T10:00:00Z", "status": "active",
             "title": "Earlier revision"},
        ]

    def get(self, job_id, *, latest=10, full_history=False):
        self.get_calls.append((job_id, latest, full_history))
        return dict(self.detail)

    def history(self, job_id, *, revision=None, search=None, limit=50, offset=0):
        self.history_calls.append((job_id, revision, limit, offset))
        if revision is not None:
            return {
                "revision": revision,
                "created_at": "2026-10-02T10:00:00Z",
                "status": "active",
                "title": "Earlier revision",
                "summary": "Old summary",
                "progress": "",
                "blocker": "",
                "brief": f"Archived {PRIVATE_SENTINEL}",
            }
        return self.revisions[offset:offset + limit]


class FireControllerWebTest(unittest.TestCase):
    def test_public_projection_drops_all_private_and_unselected_fields(self):
        result = web.public_jobs([public_row()])
        self.assertEqual(set(result[0]), {
            "id", "name", "worker", "workstream_id", "title", "status", "primary", "summary", "progress",
            "blocker", "checklist",
        })
        self.assertEqual(set(result[0]["checklist"][0]), {"id", "text", "done"})
        self.assertTrue(result[0]["primary"])
        self.assertEqual(result[0]["status"], "blocked")
        self.assertNotIn(PRIVATE_SENTINEL, json.dumps(result))
        self.assertNotIn("chat_id", json.dumps(result))

    def test_database_public_loader_uses_one_primary_list(self):
        calls = []

        class Store:
            def __init__(self, database):
                self.database = database

            def list(self, **kwargs):
                calls.append(kwargs)
                return [public_row()]

        fake_jobs = types.ModuleType("fire_controller.jobs")
        fake_jobs.JobStore = Store
        with patch.dict(sys.modules, {"fire_controller.jobs": fake_jobs}):
            result = web.load_public_jobs(Path("/isolated/jobs.sqlite3"))
        self.assertEqual(calls, [{"primary": True}])
        self.assertEqual(result[0]["id"], "job-1")
        self.assertNotIn(PRIVATE_SENTINEL, json.dumps(result))

    def test_lane_projection_batches_and_keeps_blocked_primary_visible(self):
        job = {**public_row(), "worker": "Build & Tools"}
        row = {
            "worker": "Build & Tools", "status": "blocked", "paused": False, "pause_reason": "",
            "active_count": 0, "blocked_count": 1, "parked_count": 2, "completed_count": 4,
            "jobs_truncated": False, "primary": job, "jobs": [job],
            "private_history": PRIVATE_SENTINEL,
        }
        result = web.public_lanes([row])[0]
        self.assertEqual(result["worker"], "Build & Tools")
        self.assertEqual(result["status"], "blocked")
        self.assertTrue(result["primary"]["primary"])
        self.assertNotIn(PRIVATE_SENTINEL, json.dumps(result))

        calls = []

        class Store:
            def __init__(self, database):
                self.database = database

            def lanes(self, *, workers):
                calls.append(workers)
                return [row]

        fake_jobs = types.ModuleType("fire_controller.jobs")
        fake_jobs.JobStore = Store
        with patch.dict(sys.modules, {"fire_controller.jobs": fake_jobs}):
            loaded = web.load_public_lanes(Path("/isolated/jobs.sqlite3"))
        self.assertEqual(calls, [None])
        self.assertEqual(loaded[0]["worker"], "Build & Tools")

    def test_workstream_public_projection_drops_private_notes_and_history(self):
        snapshot = {
            "workstreams": [{
                "id": "shared-foundations", "name": "Shared Foundations", "revision": 4,
                "state": "ACTIVE", "now": "Current", "milestone": "Next",
                "phase_states": {"Phase one": "NOW"}, "notes": PRIVATE_SENTINEL,
                "history": [{"brief": PRIVATE_SENTINEL}],
            }],
            "return_points": [{"id": "adr-0183", "name": "ADR 0183", "trigger": "When human review resumes",
                               "state": "HUMAN DECISION"}],
        }
        result = web.public_workstreams(snapshot)
        self.assertEqual(set(result["workstreams"][0]), {
            "id", "name", "state", "now", "milestone", "phase_states",
        })
        self.assertEqual(result["return_points"], [{
            "id": "adr-0183", "name": "ADR 0183", "trigger": "When human review resumes",
            "state": "HUMAN DECISION",
        }])
        self.assertNotIn(PRIVATE_SENTINEL, json.dumps(result))

    def test_private_markdown_escapes_html_and_rejects_unsafe_links_and_images(self):
        page = web.render_job(FakeStore().detail)
        self.assertIn(PRIVATE_SENTINEL, page)
        self.assertIn('<a href="https://example.com">web</a>', page)
        self.assertIn('<a href="/jobs/job-1/history">local</a>', page)
        self.assertNotIn('href="javascript:', page)
        self.assertNotIn('src="https://example.com/private.png"', page)
        self.assertNotIn('href="//evil.example', page)
        self.assertNotIn('href="/outside/secret', page)
        self.assertIn("remote image", page)
        self.assertIn("&lt;script&gt;private()&lt;/script&gt;", page)
        self.assertNotIn("<script>private()", page)
        self.assertIn("Latest checkpoint", page)
        self.assertIn("Latest update", page)
        self.assertIn("after-review", page)
        self.assertIn("/jobs/job-1/history", page)

    def test_private_time_metadata_uses_nz_fallback_and_preserves_iso_attributes(self):
        rendered = web._time_metadata("2026-10-04T08:47:13Z")
        self.assertEqual(
            '<time datetime="2026-10-04T08:47:13Z">4 Oct 2026 21:47 NZDT</time>',
            rendered,
        )
        self.assertEqual("&lt;script&gt;", web._time_metadata("<script>"))
        self.assertEqual("2026-10-04T08:47:13", web._time_metadata("2026-10-04T08:47:13"))
        self.assertEqual(
            "9999-12-31T23:59:59Z",
            web._time_metadata("9999-12-31T23:59:59Z"),
        )

    def test_public_inline_markdown_keeps_http_links_and_omits_private_targets(self):
        rendered = web.render_public_inline(
            '**bold** [safe](https://example.com/path) [local](/jobs/job-1) '
            '[unsafe](javascript:alert(1)) ![remote](https://example.com/image.png) <script>x</script>'
        )
        self.assertIn("<strong>bold</strong>", rendered)
        self.assertIn('<a href="https://example.com/path">safe</a>', rendered)
        self.assertIn("local", rendered)
        self.assertNotIn("/jobs/", rendered)
        self.assertNotIn("javascript:", rendered)
        self.assertNotIn("src=", rendered)
        self.assertIn("remote", rendered)
        self.assertIn("&lt;script&gt;x&lt;/script&gt;", rendered)

    def test_review_model_label_uses_exact_subagent_attempt_and_escapes_values(self):
        run = {"run_id": "attempt-1", "channel": "subagent"}
        label = web.review_model_label(run, [{
            "run_id": "attempt-1", "model": "gpt-5.6-sol", "reasoning_effort": "medium",
        }])
        self.assertIn("gpt-5.6-sol", label)
        self.assertIn("Medium", label)
        self.assertIn("Model not recorded", web.review_model_label(run, []))
        self.assertEqual("", web.review_model_label({"run_id": "attempt-1", "channel": "hosted"}, [
            {"run_id": "attempt-1", "model": "must-not-appear"},
        ]))
        malicious = web.review_model_label(run, [{
            "run_id": "attempt-1", "model": '<img src=x onerror="alert(1)">',
        }])
        self.assertIn("&lt;img", malicious)
        self.assertNotIn("<img", malicious)

    def test_private_route_reads_detail_and_bounded_history_with_validated_offsets(self):
        store = FakeStore()
        status, headers, body = web.private_route("/jobs/job-1", store)
        self.assertEqual(status, 200, body.decode())
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertIn(PRIVATE_SENTINEL.encode(), body)
        self.assertIn(
            b'<time datetime="2026-10-03T10:00:00Z">3 Oct 2026 23:00 NZDT</time>',
            body,
        )
        self.assertNotIn(b">2026-10-03T10:00:00Z<", body)
        self.assertEqual(store.get_calls, [("job-1", 10, False)])

        status, _headers, history = web.private_route("/jobs/job-1/history?offset=0", store)
        self.assertEqual(status, 200)
        self.assertEqual(store.history_calls[-1], ("job-1", None, 50, 0))
        self.assertIn(b"Earlier revision", history)
        self.assertIn(b'<time datetime="2026-10-03T10:00:00Z">3 Oct 2026 23:00 NZDT</time>', history)

        status, _headers, revision = web.private_route("/jobs/job-1/history?revision=2", store)
        self.assertEqual(status, 200)
        self.assertEqual(store.history_calls[-1], ("job-1", 2, 50, 0))
        self.assertIn(f"Archived {PRIVATE_SENTINEL}".encode(), revision)

        status, _headers, _body = web.private_route("/jobs/job-1/history?offset=-1", store)
        self.assertEqual(status, 400)
        status, _headers, _body = web.private_route("/jobs/job-1/history?offset=1000001", store)
        self.assertEqual(status, 400)
        self.assertIsNone(web.private_route("/public/jobs/job-1", store))

    def test_private_worker_history_lists_all_statuses_without_private_briefs(self):
        class Jobs:
            def __init__(self):
                self.list_calls = []

            def list(self, *, worker):
                self.list_calls.append(worker)
                return [
                    {"id": "current-job", "name": "current", "worker": worker,
                     "title": "Current assignment", "status": "active", "primary": True,
                     "summary": "Current summary", "brief": PRIVATE_SENTINEL},
                    {"id": "parked-job", "name": "parked", "worker": worker,
                     "title": "Parked assignment", "status": "parked", "primary": False,
                     "summary": "Parked summary", "brief": PRIVATE_SENTINEL},
                    {"id": "completed-job", "name": "completed", "worker": worker,
                     "title": "Completed CI propagation", "status": "completed", "primary": False,
                     "summary": "Completed summary", "brief": PRIVATE_SENTINEL},
                ]

        jobs = Jobs()
        status, headers, body = web.private_route(
            "/workers/@Build%20%26%20Tools/jobs", jobs,
        )
        text = body.decode()
        self.assertEqual(status, 200)
        self.assertEqual(headers["Cache-Control"], "no-store")
        self.assertEqual(jobs.list_calls, ["Build & Tools"])
        self.assertLess(text.index("Current assignment"), text.index("Parked assignment"))
        self.assertLess(text.index("Parked assignment"), text.index("Completed CI propagation"))
        self.assertIn('<span class="job-state">completed</span>', text)
        self.assertIn('<h2><a href="/jobs/current-job">Current assignment</a></h2>', text)
        self.assertIn('<h2><a href="/jobs/completed-job">Completed CI propagation</a></h2>', text)
        self.assertIn('href="/jobs/completed-job/history"', text)
        self.assertIn(">Job history</a>", text)
        self.assertNotIn("Private details", text)
        self.assertNotIn(PRIVATE_SENTINEL, text)
        self.assertEqual(
            web.private_route("/workers/@Build%20%26%20Tools/jobs?offset=1", jobs)[0], 400,
        )
        self.assertIsNone(web.private_route("/public/workers/@Build%20%26%20Tools/jobs", jobs))
        for alias in (".", ".."):
            status, _, _ = web.private_route(f"/workers/@{alias}/jobs", jobs)
            self.assertEqual(200, status)
            self.assertEqual(alias, jobs.list_calls[-1])
        self.assertEqual(web.private_route("/workers/General/jobs", jobs)[0], 404)

    def test_workstream_and_inbox_routes_are_private_and_bounded(self):
        class Workstreams:
            def __init__(self):
                self.history_calls = []

            def get(self, identifier, editorial):
                return {"id": identifier, "name": "Shared Foundations", "revision": 1,
                        "state": "ACTIVE", "now": PRIVATE_SENTINEL, "milestone": "Next",
                        "phase_states": {"Phase one": "NOW"}}

            def history(self, record_type, identifier, **kwargs):
                self.history_calls.append((record_type, identifier, kwargs))
                return [{"revision": 1, "created_at": "2026-10-03T00:00:00Z", "state": "ACTIVE",
                         "now": PRIVATE_SENTINEL, "milestone": "Next", "phase_states": {}}]

        class Notes:
            def notes(self, **kwargs):
                self.kwargs = kwargs
                return [{"id": "note-1", "kind": "reminder", "status": "pending",
                         "created_at": "2026-10-03T00:00:00Z", "body": PRIVATE_SENTINEL}]

        workstreams = Workstreams()
        jobs = Notes()
        status, _headers, body = web.private_route(
            "/workstreams/shared-foundations", jobs, workstreams=workstreams,
            editorial={"workstreams": {}, "return_points": {}},
        )
        self.assertEqual(status, 200)
        self.assertIn(PRIVATE_SENTINEL.encode(), body)
        self.assertIn(b'<time datetime="2026-10-03T00:00:00Z">3 Oct 2026 13:00 NZDT</time>', body)
        self.assertEqual(jobs.kwargs["phase"], "shared-foundations")
        status, _headers, body = web.private_route(
            "/workstreams/shared-foundations/history?offset=50", jobs, workstreams=workstreams,
            editorial={"workstreams": {}, "return_points": {}},
        )
        self.assertEqual(status, 200)
        self.assertIn(b'<time datetime="2026-10-03T00:00:00Z">3 Oct 2026 13:00 NZDT</time>', body)
        self.assertEqual(workstreams.history_calls[-1][2], {"limit": 50, "offset": 50})
        self.assertEqual(web.private_route("/workstreams/shared-foundations/history?offset=1000001", jobs,
                                          workstreams=workstreams,
                                          editorial={"workstreams": {}, "return_points": {}})[0], 400)

        class Inbox:
            def __init__(self):
                self.read_calls = []
                self.list_calls = []

            def list(self, recipient, *, unread, limit, offset):
                self.list_calls.append((recipient, unread, limit, offset))
                return [{"id": "message-1", "recipient": recipient, "author": "Overseer",
                         "body": PRIVATE_SENTINEL, "created_at": "2026-10-03T00:00:00Z"}]

            def unread_count(self, worker):
                return 1

            def read(self, message_id, *, recipient):
                self.read_calls.append((message_id, recipient))
                return {"id": message_id, "author": "Overseer", "body": PRIVATE_SENTINEL,
                        "created_at": "2026-10-03T00:00:00Z"}

        inbox = Inbox()
        status, _headers, body = web.private_route("/inbox/Build%20%26%20Tools?offset=0", None, inbox=inbox)
        self.assertEqual(status, 200)
        self.assertNotIn(PRIVATE_SENTINEL.encode(), body)
        self.assertIn(b'<time datetime="2026-10-03T00:00:00Z">3 Oct 2026 13:00 NZDT</time>', body)
        self.assertEqual(inbox.list_calls, [("Build & Tools", False, 50, 0)])
        self.assertEqual(inbox.read_calls, [])
        status, _headers, body = web.private_route("/inbox/Build%20%26%20Tools/message-1", None, inbox=inbox)
        self.assertEqual(status, 200)
        self.assertEqual(inbox.read_calls, [("message-1", "Build & Tools")])
        self.assertIn(PRIVATE_SENTINEL.encode(), body)
        self.assertIn(b'<time datetime="2026-10-03T00:00:00Z">3 Oct 2026 13:00 NZDT</time>', body)


class PrivateWebHistoryTests(unittest.TestCase):
    def test_historical_job_structured_state_is_visible_and_escaped(self):
        page = web.render_job({"job": {"id": "job-1", "name": "Current"}, "revision_entry": {
            "revision": 2, "status": "blocked", "worker": "General<script>", "primary": True,
            "title": "Historical title", "brief": "Old brief", "checklist": [
                {"id": "old", "text": "Historical <script>check</script>", "done": True}]}}, history=True)
        for text in ("blocked", "General&lt;script&gt;", "Primary", "Historical title", "Checklist", "Done",
                     "Historical &lt;script&gt;check&lt;/script&gt;"):
            self.assertIn(text, page)
        self.assertNotIn("<script>", page)

    def test_workstream_pagination_and_historical_phases(self):
        class Store:
            def get(self, identifier, editorial):
                return {"id": identifier, "name": "Track"}

            def history(self, kind, identifier, **kwargs):
                if "revision" in kwargs:
                    return {"revision": kwargs["revision"], "state": "Earlier", "phase_states": {"Earlier phase": "Earlier state"}}
                return [{"revision": 100 - n, "created_at": "then", "state": "Active",
                         "phase_states": {"Phase <one>": "Historical <state>"}}
                        for n in range(50)]
        status, _, body = web.private_route("/workstreams/track/history?offset=50", FakeStore(),
                                           workstreams=Store(), editorial={})
        self.assertEqual(status, 200)
        page = body.decode()
        self.assertIn("history?offset=0", page)
        self.assertIn("history?offset=100", page)
        self.assertIn("Phase &lt;one&gt;", page)
        self.assertIn("Historical &lt;state&gt;", page)
        status, _, body = web.private_route("/workstreams/track/history?revision=2", FakeStore(),
                                           workstreams=Store(), editorial={})
        self.assertEqual(status, 200)
        self.assertIn(b"Earlier phase", body)
        self.assertIn(b"Earlier state", body)
        self.assertNotIn(b"Older revisions", body)


if __name__ == "__main__":
    unittest.main()
