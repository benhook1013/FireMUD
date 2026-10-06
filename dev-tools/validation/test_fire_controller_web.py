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
        self.assertIn("<title>Keep the lane moving · FireController job</title>", page)
        self.assertIn("<h1>Keep the lane moving</h1>", page)
        self.assertIn('<span class="job-alias">Job alias · Build the bridge</span>', page)
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

    def test_private_job_title_is_escaped_once_in_heading_and_document_title(self):
        detail = {**FakeStore().detail, "title": 'Task <one> & "done"'}
        page = web.render_job(detail)
        self.assertIn("<title>Task &lt;one&gt; &amp; &quot;done&quot; · FireController job</title>", page)
        self.assertIn("<h1>Task &lt;one&gt; &amp; &quot;done&quot;</h1>", page)
        self.assertNotIn("&amp;amp;", page)

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
                self.thread_calls = []

            def messages_page(self, worker, *, limit, offset):
                self.list_calls.append((worker, limit, offset))
                return {"offset": offset, "has_more": False, "messages": [{"id": "message-1", "recipient": worker, "author": "Overseer",
                         "body": PRIVATE_SENTINEL, "created_at": "2026-10-03T00:00:00Z",
                         "reply_to": "message-0"}]}

            def conversations_page(self, worker, *, limit, offset):
                return {"offset": offset, "has_more": False, "conversations": [{"root_id": "message-0",
                        "message_count": 2, "unread_count": 1, "latest_message": {
                            "id": "message-1", "recipient": worker, "author": "Overseer",
                            "body": PRIVATE_SENTINEL, "created_at": "2026-10-03T00:00:00Z"}}]}

            def unread_count(self, worker):
                return 1

            def read(self, message_id, *, recipient):
                self.read_calls.append((message_id, recipient))
                return {"id": message_id, "author": "Overseer", "body": PRIVATE_SENTINEL,
                        "created_at": "2026-10-03T00:00:00Z", "reply_to": "message-0"}

            def thread_page(self, message_id, *, limit, offset, focus_id=None, worker=None):
                self.thread_calls.append((message_id, limit, offset, focus_id, worker))
                return {"offset": offset, "has_more": False, "messages": [
                    {"id": "message-0", "recipient": "Overseer", "author": "Build & Tools",
                     "body": "Original private request", "created_at": "2026-10-02T00:00:00Z",
                     "seen_at": None, "acknowledged_at": None},
                    {"id": "message-1", "recipient": "Build & Tools", "author": "Overseer",
                     "body": PRIVATE_SENTINEL, "created_at": "2026-10-03T00:00:00Z",
                     "reply_to": "message-0", "job": "merge-train", "pr": 2898,
                     "seen_at": None, "acknowledged_at": None},
                ]}

        inbox = Inbox()
        status, _headers, body = web.private_route("/inbox/Build%20%26%20Tools?offset=0", None, inbox=inbox)
        self.assertEqual(status, 200)
        self.assertNotIn(PRIVATE_SENTINEL.encode(), body)
        self.assertIn(b'<time datetime="2026-10-03T00:00:00Z">3 Oct 2026 13:00 NZDT</time>', body)
        self.assertEqual(inbox.list_calls, [])
        self.assertIn(b"Open conversation", body)
        self.assertIn(b"2 message(s)", body)
        self.assertIn(b"1 unread incoming", body)
        status, _headers, body = web.private_route("/inbox/Build%20%26%20Tools?view=messages", None, inbox=inbox)
        self.assertEqual(status, 200)
        self.assertEqual(inbox.list_calls, [("Build & Tools", 50, 0)])
        self.assertIn(b"/thread/message-0?focus=message-0#message-message-0", body)
        self.assertEqual(inbox.read_calls, [])
        status, _headers, body = web.private_route("/inbox/Build%20%26%20Tools/message-1", None, inbox=inbox)
        self.assertEqual(status, 200)
        self.assertEqual(inbox.read_calls, [("message-1", "Build & Tools")])
        self.assertIn(PRIVATE_SENTINEL.encode(), body)
        self.assertIn(b'<time datetime="2026-10-03T00:00:00Z">3 Oct 2026 13:00 NZDT</time>', body)
        self.assertIn(b"/thread/message-0?focus=message-0#message-message-0", body)
        self.assertIn(b"Conversation", body)
        status, _headers, body = web.private_route(
            "/inbox/Build%20%26%20Tools/thread/message-1?offset=50", None, inbox=inbox,
        )
        self.assertEqual(status, 200)
        self.assertEqual(inbox.thread_calls, [("message-1", 50, 50, None, "Build & Tools")])
        self.assertEqual(inbox.read_calls, [("message-1", "Build & Tools")])
        self.assertIn(PRIVATE_SENTINEL.encode(), body)
        self.assertIn(b"Overseer", body)
        self.assertIn(b"Build &amp; Tools", body)
        self.assertIn(b"Unread", body)
        self.assertIn(b"Job: merge-train", body)
        self.assertIn(b"PR: #2898", body)
        self.assertIn(b"Reply to", body)
        self.assertIn(b"Earlier messages", body)

    def test_private_conversation_pagination_labels_follow_chronological_order(self):
        first_page = [
            {"id": f"message-{index}", "recipient": "General", "author": "Overseer",
             "body": f"Message {index}", "created_at": f"2026-10-01T00:{index:02d}:00Z"}
            for index in range(web.HISTORY_PAGE_SIZE)
        ]
        later = web.render_inbox_thread("General", first_page, message_id="message-0", offset=0, has_more=True)
        self.assertIn("Later messages", later)
        self.assertNotIn("Earlier messages", later)

        earlier = web.render_inbox_thread("General", first_page[:1], message_id="message-49", offset=web.HISTORY_PAGE_SIZE)
        self.assertIn("Earlier messages", earlier)
        self.assertNotIn("Later messages", earlier)

    def test_private_job_times_use_durable_activity_nz_timezone_and_relative_age(self):
        from datetime import datetime, timezone

        class FixedDateTime(datetime):
            @classmethod
            def now(cls, tz=None):
                return datetime(2026, 10, 6, 0, 0, tzinfo=timezone.utc).astimezone(tz)

        job = {**public_row(), "created_at": "2026-07-01T00:00:00Z",
               "updated_at": "2026-10-01T00:00:00Z", "last_activity_at": "2026-10-05T22:30:00Z"}
        with patch.object(web, "datetime", FixedDateTime):
            document = web.render_worker_history("Gameplay", [job])
        self.assertIn("<strong>Created</strong>", document)
        self.assertIn("1 Jul 2026 12:00 NZST", document)
        self.assertIn("<strong>Last activity</strong>", document)
        self.assertIn("6 Oct 2026 11:30 NZDT", document)
        self.assertIn("(1h 30m ago)", document)
        self.assertNotIn('datetime="2026-10-01T00:00:00Z"', document)
        self.assertIn('datetime="2026-07-01T00:00:00Z"', document)
        missing = web.render_worker_history("Gameplay", [public_row()])
        self.assertEqual(missing.count("Not recorded"), 2)
        self.assertNotIn("1970", missing)
        invalid = web._time_metadata('<invalid>', relative=True)
        self.assertEqual(invalid, "&lt;invalid&gt;")

    def test_private_pages_share_card_navigation_and_empty_state_styles_only(self):
        pages = [web.render_worker_history("Gameplay", []), web.render_job(FakeStore().detail),
                 web.render_job({"job": FakeStore().detail, "history": []}, history=True),
                 web.render_inbox("Gameplay", []), web.render_inbox_conversations("Gameplay", []),
                 web.render_inbox_thread("Gameplay", [], message_id="message-1"),
                 web.render_workstream({"id": "delivery", "name": "Delivery", "history": []}, history=True)]
        for page in pages:
            with self.subTest(page=page[:120]):
                self.assertIn('<main class="private-pages">', page)
                self.assertIn('href="/shared.css"', page)
                self.assertIn('class="private-actions"', page)
                self.assertIn('var(--paper,#e7e7e7)', page)
                self.assertIn('@media(max-width:760px)', page)
        self.assertIn("private-empty", pages[0])
        self.assertIn("No revisions recorded.", pages[2])
        self.assertIn("No conversations on this page.", pages[4])
        self.assertNotIn("private-pages", json.dumps(web.public_jobs([public_row()])))

    def test_private_page_titles_and_headings_escape_aliases_and_names_once(self):
        for worker in ("Build & Tools", "Build <script>alert(1)</script>"):
            escaped_worker = web.html.escape(worker, quote=True)
            for render in (web.render_inbox_conversations, web.render_inbox):
                document = render(worker, [])
                with self.subTest(worker=worker, render=render):
                    self.assertIn(f"<title>{escaped_worker} inbox · FireController</title>", document)
                    self.assertIn(f"<h1>{escaped_worker} inbox</h1>", document)
                    self.assertNotIn("&amp;amp;", document)
                    self.assertNotIn("&amp;lt;", document)
                    self.assertNotIn("<script>", document)
        record = {"id": "delivery", "name": "Build & <Tools>", "history": []}
        for history in (False, True):
            document = web.render_workstream(record, history=history)
            suffix = " history" if history else " · local workstream"
            self.assertIn(f"<title>Build &amp; &lt;Tools&gt;{suffix}</title>", document)
            self.assertNotIn("&amp;amp;", document)
            self.assertNotIn("&amp;lt;", document)

    def test_worker_history_orders_all_jobs_by_activity_after_the_primary(self):
        jobs = [
            {**public_row(), "id": "state-newer", "name": "state-newer", "title": "State newer",
             "primary": False, "updated_at": "2026-10-05T00:00:00Z", "last_activity_at": "2026-10-05T00:00:00Z"},
            {**public_row(), "id": "primary", "title": "Primary first", "last_activity_at": "2026-10-01T00:00:00Z"},
            {**public_row(), "id": "append-newer", "name": "append-newer", "title": "Appended activity newer",
             "primary": False, "updated_at": "2026-10-01T00:00:00Z", "last_activity_at": "2026-10-06T00:00:00Z"},
        ]
        document = web.render_worker_history("Gameplay", jobs)
        self.assertLess(document.index('href="/jobs/primary"'), document.index('href="/jobs/append-newer"'))
        self.assertLess(document.index('href="/jobs/append-newer"'), document.index('href="/jobs/state-newer"'))
        self.assertEqual(jobs[0]["id"], "state-newer")

    def test_private_inbox_modes_keep_worker_scope_and_message_handling_separate(self):
        import tempfile

        from fire_controller.inbox import InboxStore

        with tempfile.TemporaryDirectory() as directory:
            inbox = InboxStore(Path(directory) / "controller.sqlite3")
            inbox.bootstrap()
            incoming = inbox.send("General", "**Private request**", author="Overseer")
            outgoing = inbox.send("Overseer", "Private response", author="General", reply_to=incoming["id"])
            self_message = inbox.send("General", "Self note", author="General")
            unrelated = inbox.send("Gameplay", "Other lane private text", author="Review")
            other_branch = inbox.send("Gameplay", "Other participant branch", author="Review", reply_to=outgoing["id"])
            for route in ("/inbox/General", "/inbox/General?view=messages", f"/inbox/General/thread/{incoming['id']}"):
                status, headers, body = web.private_route(route, None, inbox=inbox)
                self.assertEqual(status, 200)
                self.assertEqual(headers["Cache-Control"], "no-store")
                self.assertNotIn(b"Other lane private text", body)
                self.assertNotIn(unrelated["id"].encode(), body)
            messages = web.private_route("/inbox/General?view=messages", None, inbox=inbox)[2].decode()
            self.assertIn("Incoming", messages)
            self.assertIn("Outgoing", messages)
            self.assertIn("Self", messages)
            self.assertIn(f'href="/inbox/General/{incoming["id"]}"', messages)
            self.assertIn(f'?focus={outgoing["id"]}#message-{outgoing["id"]}', messages)
            self.assertNotIn(f'href="/inbox/General/{outgoing["id"]}"', messages)
            self.assertNotIn(other_branch["id"], messages)
            thread = web.private_route(f"/inbox/General/thread/{other_branch['id']}", None, inbox=inbox)[2].decode()
            self.assertIn("Other participant branch", thread)
            self.assertIn("<strong>Private request</strong>", thread)
            self.assertEqual(web.private_route(f"/inbox/Gameplay/thread/{self_message['id']}", None, inbox=inbox)[0], 404)
            self.assertTrue(all(row["seen_at"] is None and row["acknowledged_at"] is None
                                for row in inbox.thread(incoming["id"])))
            self.assertEqual(inbox.unread_count("General"), 2)
            self.assertEqual(web.private_route(f"/inbox/General/{incoming['id']}", None, inbox=inbox)[0], 200)
            self.assertEqual(inbox.unread_count("General"), 1)
            self.assertIsNone(inbox.list("General")[-1]["acknowledged_at"])
            self.assertEqual(web.private_route(f"/inbox/General/{outgoing['id']}", None, inbox=inbox)[0], 404)
            for query in ("view=", "view=sent", "view=messages&view=conversations", "view=messages&offset=-1",
                          "view=messages&offset=1000001", "view=messages&focus=message-1"):
                self.assertEqual(web.private_route(f"/inbox/General?{query}", None, inbox=inbox)[0], 400)
            self.assertEqual(web.private_route(f"/inbox/General/thread/{incoming['id']}?view=messages", None, inbox=inbox)[0], 400)

    def test_inbox_full_final_pages_hide_forward_links_and_message_pages_preserve_view(self):
        import tempfile

        from fire_controller.inbox import InboxStore

        with tempfile.TemporaryDirectory() as directory:
            inbox = InboxStore(Path(directory) / "controller.sqlite3")
            inbox.bootstrap()
            roots = [inbox.send("General", f"Request {index}", author="Overseer") for index in range(50)]
            for query, label in (("", "Older conversations"), ("?view=messages", "Older messages")):
                body = web.private_route(f"/inbox/General{query}", None, inbox=inbox)[2].decode()
                self.assertNotIn(label, body)
            inbox.send("Overseer", "Latest response", author="General", reply_to=roots[0]["id"])
            messages = web.private_route("/inbox/General?view=messages", None, inbox=inbox)[2].decode()
            self.assertIn('href="/inbox/General?view=messages&amp;offset=50">Older messages', messages)
            final = web.private_route("/inbox/General?view=messages&offset=50", None, inbox=inbox)[2].decode()
            self.assertIn('href="/inbox/General?view=messages&amp;offset=0">Newer messages', final)
            self.assertNotIn("Older messages", final)
            conversations = web.private_route("/inbox/General", None, inbox=inbox)[2].decode()
            self.assertNotIn("Older conversations", conversations)
            first_link = f'href="/inbox/General/thread/{roots[0]["id"]}"'
            self.assertLess(conversations.index(first_link), conversations.index(roots[-1]["id"]))

    def test_private_parent_focus_loads_only_its_bounded_page_without_marking_messages(self):
        import tempfile
        from datetime import datetime, timedelta, timezone

        from fire_controller.inbox import InboxStore

        with tempfile.TemporaryDirectory() as directory:
            inbox = InboxStore(Path(directory) / "controller.sqlite3")
            inbox.bootstrap()
            start = datetime(2026, 10, 1, tzinfo=timezone.utc)
            timestamps = (start + timedelta(minutes=index) for index in range(100))

            def next_timestamp():
                return next(timestamps).isoformat(timespec="seconds").replace("+00:00", "Z")

            with patch("fire_controller.inbox._now", side_effect=next_timestamp):
                root = inbox.send("General", "THREAD-BODY-0", author="Overseer")
                messages = [root]
                parent = root
                for index in range(1, 54):
                    recipient = "Overseer" if index % 2 else "General"
                    author = "General" if recipient == "Overseer" else "Overseer"
                    parent = inbox.send(
                        recipient, f"THREAD-BODY-{index}", author=author, reply_to=parent["id"],
                    )
                    messages.append(parent)
                unrelated = inbox.send("General", "UNRELATED_THREAD")

            child_id = messages[-1]["id"]
            parent_id = messages[-2]["id"]
            self.assertEqual(inbox.thread_page(child_id, focus_id=parent_id)["offset"], 50)
            inbox_list = web.render_inbox("Overseer", [messages[-1]])
            focused_parent = (
                f"/thread/{parent_id}?focus={parent_id}#message-{parent_id}"
            )
            self.assertIn(focused_parent, inbox_list)

            status, _headers, body = web.private_route(
                f"/inbox/Overseer/thread/{child_id}?focus={parent_id}", None, inbox=inbox,
            )
            page = body.decode("utf-8")
            self.assertEqual(status, 200)
            self.assertIn(f'id="message-{parent_id}"', page)
            self.assertEqual(page.count('class="job-private inbox-thread-message"'), 4)
            self.assertIn("THREAD-BODY-50", page)
            self.assertIn("THREAD-BODY-53", page)
            self.assertNotIn("UNRELATED_THREAD", page)
            self.assertIn("Earlier messages", page)
            self.assertIn(f'href="/inbox/Overseer/thread/{child_id}?offset=0">Earlier messages', page)
            for index in range(54):
                self.assertEqual(f"<p>THREAD-BODY-{index}</p>" in page, index >= 50)
            self.assertTrue(all(message["seen_at"] is None and message["acknowledged_at"] is None
                                for message in inbox.thread(child_id)))

            self.assertEqual(web.private_route(
                f"/inbox/Overseer/thread/{child_id}?focus={unrelated['id']}", None, inbox=inbox,
            )[0], 404)
            self.assertEqual(web.private_route(
                f"/inbox/Overseer/thread/{child_id}?focus=invalid%2Fid", None, inbox=inbox,
            )[0], 400)
            self.assertEqual(web.private_route(
                f"/inbox/Overseer/thread/{child_id}?focus={parent_id}&offset=50", None, inbox=inbox,
            )[0], 400)
            self.assertIsNone(web.private_route(f"/public/inbox/Overseer/thread/{child_id}", None, inbox=inbox))


class PrivateWebHistoryTests(unittest.TestCase):
    def test_historical_job_structured_state_is_visible_and_escaped(self):
        page = web.render_job({"job": {"id": "job-1", "name": "Current", "title": "Current task title"}, "revision_entry": {
            "revision": 2, "status": "blocked", "worker": "General<script>", "primary": True,
            "title": "Historical title", "brief": "Old brief", "checklist": [
                {"id": "old", "text": "Historical <script>check</script>", "done": True}]}}, history=True)
        for text in ("<title>Current task title history</title>",
                     "<h1>Current task title · Revision 2</h1>",
                     '<p class="job-alias">Job alias · Current</p>',
                     "blocked", "General&lt;script&gt;", "Primary", "Historical title", "Checklist", "Done",
                     "Historical &lt;script&gt;check&lt;/script&gt;"):
            self.assertIn(text, page)
        self.assertNotIn("<script>", page)

    def test_job_revision_list_uses_display_title_and_keeps_alias_and_revision_titles(self):
        page = web.render_job({"job": {"id": "job-1", "name": "Current", "title": "Current task title"},
                               "history": [{"revision": 2, "status": "blocked", "title": "Historical title"}]},
                              history=True)
        self.assertIn("<title>Current task title history</title>", page)
        self.assertIn("<h1>Current task title history</h1>", page)
        self.assertIn('<p class="job-alias">Job alias · Current</p>', page)
        self.assertIn("Historical title", page)

    def test_history_page_titles_escape_current_and_historical_titles_once(self):
        title = 'Task <one> & "done"'
        escaped_title = 'Task &lt;one&gt; &amp; &quot;done&quot;'
        job = {"id": "job-1", "name": "Current", "title": title}
        revision = web.render_job({"job": job, "revision_entry": {
            "revision": 2, "title": 'Past <title> & "kept"'}}, history=True)
        self.assertIn(f"<title>{escaped_title} history</title>", revision)
        self.assertIn(f"<h1>{escaped_title} · Revision 2</h1>", revision)
        self.assertIn("<p>Past &lt;title&gt; &amp; &quot;kept&quot;</p>", revision)
        self.assertNotIn("&amp;amp;", revision)

        history = web.render_job({"job": job, "history": [
            {"revision": 2, "status": "blocked", "title": 'Past <title> & "kept"'}]}, history=True)
        self.assertIn(f"<title>{escaped_title} history</title>", history)
        self.assertIn(f"<h1>{escaped_title} history</h1>", history)
        self.assertIn("Past &lt;title&gt; &amp; &quot;kept&quot;", history)
        self.assertNotIn("&amp;amp;", history)

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
