from __future__ import annotations

import difflib
import hashlib
import importlib
import importlib.util
import json
import re
import subprocess
import sys
import tempfile
import threading
import time
import types
import unittest
from datetime import timedelta
from http.client import HTTPConnection
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / "dev-tools"
SITE = ROOT / "tmp" / "job-site-integration"
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
        self.assertEqual(store.get_calls, [("job-1", 10, False)])

        status, _headers, history = web.private_route("/jobs/job-1/history?offset=0", store)
        self.assertEqual(status, 200)
        self.assertEqual(store.history_calls[-1], ("job-1", None, 50, 0))
        self.assertIn(b"Earlier revision", history)

        status, _headers, revision = web.private_route("/jobs/job-1/history?revision=2", store)
        self.assertEqual(status, 200)
        self.assertEqual(store.history_calls[-1], ("job-1", 2, 50, 0))
        self.assertIn(f"Archived {PRIVATE_SENTINEL}".encode(), revision)

        status, _headers, _body = web.private_route("/jobs/job-1/history?offset=-1", store)
        self.assertEqual(status, 400)
        status, _headers, _body = web.private_route("/jobs/job-1/history?offset=1000001", store)
        self.assertEqual(status, 400)
        self.assertIsNone(web.private_route("/public/jobs/job-1", store))

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
        self.assertEqual(jobs.kwargs["phase"], "shared-foundations")
        status, _headers, body = web.private_route(
            "/workstreams/shared-foundations/history?offset=50", jobs, workstreams=workstreams,
            editorial={"workstreams": {}, "return_points": {}},
        )
        self.assertEqual(status, 200)
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
        self.assertEqual(inbox.list_calls, [("Build & Tools", False, 50, 0)])
        self.assertEqual(inbox.read_calls, [])
        status, _headers, body = web.private_route("/inbox/Build%20%26%20Tools/message-1", None, inbox=inbox)
        self.assertEqual(status, 200)
        self.assertEqual(inbox.read_calls, [("message-1", "Build & Tools")])
        self.assertIn(PRIVATE_SENTINEL.encode(), body)


class IsolatedWebsiteIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not SITE.is_dir():
            raise unittest.SkipTest("private adapter integration requires the documented isolated website copy")
        sys.path.insert(0, str(SITE))
        spec = importlib.util.spec_from_file_location("job_site_render_test", SITE / "test_render.py")
        cls.site_tests = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.site_tests)
        cls.page = cls.site_tests.page
        cls.page._REVIEW_MODEL_LABEL = web.review_model_label
        cls.page._PUBLIC_INLINE = web.render_public_inline

        server_spec = importlib.util.spec_from_file_location("job_site_server", SITE / "server.py")
        cls.server_module = importlib.util.module_from_spec(server_spec)
        server_spec.loader.exec_module(cls.server_module)

        publish_spec = importlib.util.spec_from_file_location("job_site_publish", SITE / "publish-hetzner.py")
        cls.publisher = importlib.util.module_from_spec(publish_spec)
        publish_spec.loader.exec_module(cls.publisher)

        progress_spec = importlib.util.spec_from_file_location("job_site_progress", SITE / "render_progress.py")
        cls.progress_module = importlib.util.module_from_spec(progress_spec)
        progress_spec.loader.exec_module(cls.progress_module)

    def setUp(self):
        self.now = self.site_tests.NOW
        self.data = self.site_tests.StatusPageTest().fixture()
        self.review = self.page.review_snapshot(None, 42, self.site_tests.HEAD, self.now)
        self.github = {"available": False, "states": {}, "lifecycle": {}, "merged_at": {}, "stats": {}}
        self.public_jobs = web.public_jobs([public_row()])
        self.public_lanes = web.public_lanes([{
            "worker": "Gameplay", "status": "blocked", "paused": False, "pause_reason": "",
            "active_count": 0, "blocked_count": 1, "parked_count": 0, "completed_count": 0,
            "jobs_truncated": False, "primary": public_row(), "jobs": [public_row()],
        }])
        self.document = self.page.render(
            self.data, self.review, self.now, self.github, jobs=self.public_jobs,
            lanes_snapshot=self.public_lanes,
        )
        self.editorial = json.loads((SITE / "progress.json").read_text(encoding="utf-8"))
        map_state = {
            "workstreams": [
                {"id": track["id"], "name": track["name"], "state": "SQL STATE",
                 "now": f"SQL current {track['id']}", "milestone": "SQL milestone",
                 "phase_states": {phase["name"]: "SQL PHASE" for phase in track["phases"]}}
                for track in self.editorial["tracks"]
            ],
            "return_points": [
                {"id": point["name"].lower().replace(" ", "-"), "name": point["name"],
                 "trigger": point["trigger"], "state": "SQL RETURN STATE"}
                for point in self.editorial["return_points"]
            ],
        }
        self.map_document = self.progress_module.render(
            self.editorial, self.now, map_state=map_state, jobs=self.public_jobs,
            public_inline=web.render_public_inline,
        )

    def test_mutable_map_paragraphs_use_safe_public_inline_markdown(self):
        text = '**Proof complete** [details](https://example.com) [private](/jobs/private) [unsafe](javascript:alert(1)) <script>bad</script>'
        mapping = {"workstreams": [{"id": track["id"], "name": track["name"], "state": "Plain **state**",
            "now": text, "milestone": text, "phase_states": {phase["name"]: "Active" for phase in track["phases"]}}
            for track in self.editorial["tracks"]], "return_points": [{"name": point["name"], "state": "Pending"}
            for point in self.editorial["return_points"]]}
        page = self.progress_module.render(self.editorial, self.now, map_state=mapping,
                                           public_inline=web.render_public_inline)
        self.assertIn("<strong>Proof complete</strong>", page)
        self.assertIn('href="https://example.com"', page)
        self.assertIn("Plain **state**", page)
        self.assertNotIn('href="/jobs/', page)
        self.assertNotIn('href="javascript:', page)
        self.assertNotIn("<script>", page)
        self.assertIn("&lt;script&gt;", page)
        self.assertNotIn("/jobs/", json.dumps(self.publisher.resources(self.publisher.progress_public_html(page))[1]))

    def test_full_copy_render_and_public_stage_keep_private_data_out(self):
        self.assertIn("Build the bridge", self.document)
        self.assertIn("blocked", self.document)
        self.assertIn("Primary", self.document)
        self.assertIn("Finish the safe handoff.", self.document)
        self.assertNotIn(PRIVATE_SENTINEL, self.document)
        self.assertNotIn("/jobs/private-job", self.document)
        self.assertNotIn('href="/jobs/', self.document)

        public_document = self.publisher.public_html(
            self.document, "http://192.0.2.1:8877/",
        )
        _namespace, objects = self.publisher.resources(
            public_document, progress_document=self.map_document,
        )
        payload = json.dumps(objects, ensure_ascii=False, sort_keys=True)
        self.assertNotIn(PRIVATE_SENTINEL, payload)
        self.assertNotIn("/jobs/", payload)
        self.assertIn("Build the bridge", payload)

        for route in ("/jobs/job-1", "/workstreams/shared-foundations", "/inbox/Gameplay"):
            with self.assertRaisesRegex(ValueError, "private FireController route"):
                self.publisher.resources(f'<a href="{route}">Private</a>')

        self.assertEqual(self.map_document.count('class="track"'), 6)
        self.assertEqual(self.map_document.count('class="domain"'), 10)
        self.assertIn("SQL current decision-foundation", self.map_document)
        self.assertIn("SQL RETURN STATE", self.map_document)
        self.assertIn('data-fire-controller-workstream-id="decision-foundation"', self.map_document)
        self.assertNotIn("/jobs/", self.map_document)
        public_map = self.publisher.progress_public_html(self.map_document)
        _map_namespace, map_objects = self.publisher.resources(public_map)
        self.assertNotIn("/jobs/", json.dumps(map_objects))
        self.assertNotIn("/workstreams/", json.dumps(map_objects))

        detail = self.page.render_record_sections({
            "runs": [{
                "run_id": "subagent-1", "channel": "subagent", "outcome": "completed",
                "counts": {"found": 1, "accepted": 1, "routed": 0},
            }],
            "attempts": [{
                "run_id": "subagent-1", "model": "gpt-5.6-sol", "reasoning_effort": "medium",
            }],
            "findings": [], "routes": [], "decisions": [],
        })
        self.assertIn("Model: gpt-5.6-sol", detail)
        self.assertIn("Medium", detail)
        self.assertIn("Found: 1", detail)
        self.assertIn("Accepted: 1", detail)
        self.assertIn("Routed: 0", detail)

    def test_routed_finding_titles_emphasize_only_current_pr_endpoints(self):
        cases = (
            ({"source_pr": 13, "target_pr": 42}, "PR #13 → <strong>PR #42</strong>"),
            ({"source_pr": 42, "target_pr": 13}, "<strong>PR #42</strong> → PR #13"),
            ({"source_pr": 42, "target_pr": None}, "<strong>PR #42</strong> → Unassigned target"),
            ({"source_pr": 42, "target_pr": 42}, "<strong>PR #42</strong> → <strong>PR #42</strong>"),
        )
        for endpoints, expected in cases:
            with self.subTest(endpoints=endpoints):
                route = {
                    "route_id": "route-1", "status": "open", "title": "Retain this title",
                    **endpoints,
                }
                rendered = self.page.render_record_sections(
                    {"runs": [], "findings": [], "routes": [route], "decisions": []},
                    source_pr=42,
                )
                self.assertIn(expected, rendered)
                self.assertIn("Routed Findings", rendered)
                self.assertIn('<strong>Route 1</strong>', rendered)
                self.assertIn('route-status-open', rendered)
                self.assertIn("Retain this title", rendered)

    def test_config_only_status_uses_sql_lane_snapshot_for_main_and_history(self):
        review = {
            "available": True, "ordered_prs": [42], "queue": {}, "review_targets": {},
        }
        github = {
            "available": True,
            "identity": {42: {"title": "Controller-derived title", "head": "a" * 40, "base": "develop"}},
            "lifecycle": {42: "MERGED"}, "states": {}, "merged_at": {42: self.now.isoformat()}, "stats": {},
        }
        configured_status = {"review_tool": "/private/review-tool"}
        data = self.page.controller_stack(configured_status, review, github, self.now)
        self.assertEqual(set(data), {"review_tool", "stack"})
        main_page = self.page.render(
            data, review, self.now, github, lanes_snapshot=self.public_lanes,
        )
        history_page = self.page.render(
            data, review, self.now, github, history_only=True, lanes_snapshot=self.public_lanes,
        )
        self.assertIn("Controller-derived title", main_page)
        self.assertIn("controller-job-lane", main_page)
        self.assertIn("Gameplay", main_page)
        self.assertIn("Controller-derived title", history_page)
        self.assertNotIn('<h2>Worker lanes</h2>', history_page)

    def test_view_routed_finding_link_keeps_existing_dark_inherited_style(self):
        route = {"route_id": "route-1", "source_pr": 42, "target_pr": 13, "status": "open"}
        rendered = self.page._render_route(
            route, finding={"title": "Linked finding"}, embedded=True,
        )
        self.assertIn('class="route-reference-link" href="#route-route-1"', rendered)
        self.assertIn("View routed finding", rendered)
        history_page = self.page.render_review_detail(
            self.data, self.review, self.now, 42,
            history={"state": "available", "runs": [], "findings": [], "routes": [],
                     "decisions": [], "attempts": []},
        )
        self.assertIn(
            ".route-reference-link { display: inline-block; margin-left: .35rem; color: #222; "
            "font: inherit; font-weight: 400; text-decoration: underline; }",
            history_page,
        )

    def test_review_header_age_and_runtime_keep_readable_size_across_cascade(self):
        finished_at = (self.now - timedelta(minutes=18)).isoformat()
        history_page = self.page.render_review_detail(
            self.data, self.review, self.now, 42,
            history={
                "state": "available",
                "runs": [{
                    "run_id": "run-age", "channel": "hosted", "outcome": "completed",
                    "finished_at": finished_at, "duration_seconds": 73,
                    "counts": {"found": 1, "accepted": 1, "routed": 0},
                }],
                "findings": [], "routes": [], "decisions": [], "attempts": [],
            },
        )
        self.assertIn('class="round-age run-age"', history_page)
        self.assertIn('class="run-runtime" title="Runtime">Runtime 1m 13s</span>', history_page)
        style = history_page.split("<style>", 1)[1].split("</style>", 1)[0]
        age_rule = re.search(r"(?m)^\.run-header \.run-age \{([^}]*)\}", style)
        runtime_rule = re.search(r"(?m)^\.run-runtime \{([^}]*)\}", style)
        generic_rule = re.search(r"(?m)^\.round-age \{([^}]*)\}", style)
        self.assertIsNotNone(age_rule)
        self.assertIsNotNone(runtime_rule)
        self.assertIsNotNone(generic_rule)
        self.assertIn("font-size: .8rem", age_rule.group(1))
        self.assertIn("font-size: .8rem", runtime_rule.group(1))
        self.assertIn("font-size: .67rem", generic_rule.group(1))
        self.assertGreater(
            len(re.findall(r"\.[A-Za-z_-][\w-]*", ".run-header .run-age")),
            len(re.findall(r"\.[A-Za-z_-][\w-]*", ".round-age")),
        )
        mobile_rules = re.findall(
            r"@media\s*\(max-width:\s*760px\)\s*\{(.*?)\}", style, re.DOTALL,
        )
        self.assertTrue(mobile_rules)
        self.assertFalse(any("run-age" in rule or "run-runtime" in rule for rule in mobile_rules))

    def test_review_progress_cards_use_projection_without_status_chatter_or_reconciliation(self):
        def rule(kind, label, *, minimum=0, maximum=None, completed=0, required_remaining=0,
                 maximum_remaining=None, in_flight=0, request_slots_remaining=1, description=None):
            value = {
                "kind": kind, "label": label, "minimum": minimum, "maximum": maximum,
                "completed": completed, "required_remaining": required_remaining,
                "maximum_remaining": maximum_remaining, "in_flight": in_flight,
                "request_slots_remaining": request_slots_remaining,
            }
            if description is not None:
                value["description"] = description
            return value

        def progress(status, label, governing_rule, reason=None):
            value = {"status": status, "label": label, "rule": governing_rule}
            if reason is not None:
                value["reason"] = reason
            return value

        def activity(total=0, recent=None):
            return {"total": total, "recent": recent or []}

        def row(channels, *, hosted=None, cli=None, hosted_activity=None, cli_activity=None,
                preparation=None):
            value = {
                "channels": channels,
                "review_activity": {
                    "hosted": hosted_activity or activity(),
                    "cli": cli_activity or activity(),
                },
            }
            progress_by_channel = {
                channel: selected for channel, selected in (("hosted", hosted), ("cli", cli))
                if selected is not None
            }
            if progress_by_channel:
                value["review_progress"] = progress_by_channel
            if preparation is not None:
                value["preparation"] = preparation
            return value

        description = "Early taper is allowed; running reviews count as unfinished & <current>."
        hosted_round = {
            "raw": 3, "accepted": 2, "routed": 0, "attributable": True,
            "current_head": True, "non_counting": False,
            "completed_at": (self.now - timedelta(hours=1)).isoformat(),
        }
        queue = {
            42: row(
                {"hosted": "READY", "cli": "HELD"},
                preparation={"status": "UNRECONCILED", "label": "Needs reconciliation",
                             "reason": "PRIVATE-RECONCILIATION-SENTINEL"},
                hosted=progress("counting", "1 completed review", rule("normal", "Normal taper"),
                                reason="Parent moved; needs reconciliation."),
                cli=progress("active", "Review in progress", rule(
                    "maximum", "Maximum 2 rounds remaining", maximum=2, maximum_remaining=2,
                    in_flight=1, request_slots_remaining=0, description=description,
                )),
                hosted_activity=activity(2, [hosted_round]),
                cli_activity=activity(1, [{**hosted_round, "raw": 1, "accepted": 0}]),
            ),
            43: row(
                {"hosted": "HUMAN_STOPPED", "cli": "READY"},
                cli=progress("cooldown", "Cooldown", rule(
                    "normal", "Normal taper", description="Provider cooldown does not add a completed round."
                )),
                cli_activity=activity(0),
            ),
            44: row(
                {"hosted": "HELD", "cli": "HELD"},
                hosted=progress("pending_fixes", "Accepted fixes pending", rule(
                    "required", "2 required rounds remaining", minimum=2, maximum=2,
                    required_remaining=2, maximum_remaining=2,
                )),
                hosted_activity=activity(2),
            ),
            45: row(
                {"hosted": "HUMAN_STOPPED", "cli": "COMPLETE"},
                hosted=progress("human_stop", "Human stop", rule("human_stop", "Human stop")),
                cli=progress("taper_met", "Taper met", rule("normal", "Normal taper")),
            ),
            46: row(
                {"hosted": "COMPLETE", "cli": "CAP_AUDITED_STOP"},
                hosted=progress("required_complete", "Required rounds complete", rule(
                    "required", "3 required rounds", minimum=3, maximum=3, completed=3,
                )),
                cli=progress("maximum_reached", "Maximum reached", rule(
                    "maximum", "Maximum 2 rounds", maximum=2, completed=2,
                )),
            ),
            47: row(
                {"hosted": "PARENT_MOVED", "cli": "UNRECONCILED"},
                hosted_activity=activity(4), cli_activity=activity(2),
            ),
            48: row(
                {"hosted": "HELD", "cli": "HELD"},
                hosted=progress("not_checked", "Progress not checked", rule(
                    "maximum", "Maximum 4 rounds remaining", maximum=4, maximum_remaining=4,
                )),
                hosted_activity=activity(9),
            ),
        }
        base = self.data["stack"][0]
        data = {**self.data, "stack": [
            {**base, "number": number, "title": f"Adapter PR {number}", "stage": "Review progress"}
            for number in queue
        ]}
        review = {
            "available": True, "ordered_prs": list(queue), "queue": queue,
            "review_fronts": {
                "hosted": {"pr": 42, "status": "READY"},
                "cli": {"pr": 43, "status": "READY"},
            },
        }
        github = {
            "available": True, "states": {},
            "lifecycle": {number: "OPEN" for number in queue}, "merged_at": {}, "stats": {},
        }
        document = self.page.render(data, review, self.now, github)

        front = document.split('<section class="front-board"', 1)[1].split("</section>", 1)[0]
        self.assertNotIn('class="front-controller-state"', front)
        self.assertNotIn("<strong>Controller</strong>", front)
        self.assertIn("Diff size", front)
        self.assertIn("Progress:</strong> 1 completed review", front)
        self.assertIn("Rule:</strong> Normal taper", front)

        row_42 = document.split('<li id="pr-42"', 1)[1].split("</li>", 1)[0]
        row_43 = document.split('<li id="pr-43"', 1)[1].split("</li>", 1)[0]
        self.assertIn('<span class="queue-status queue-status-front">REVIEW FRONT</span>', row_42)
        self.assertIn('<span class="queue-status queue-status-front">REVIEW FRONT</span>', row_43)
        status_line = row_42.split('<div class="pr-status-line">', 1)[1].split("</div>", 1)[0]
        self.assertEqual(
            '<span class="queue-status queue-status-front">REVIEW FRONT</span>', status_line,
        )
        self.assertNotIn("Hosted ready to request", row_42)
        self.assertNotIn("CLI new request blocked", row_42)
        self.assertNotIn("CLI ready to request", row_43)
        self.assertNotIn("Needs reconciliation", document)
        self.assertNotIn("needs reconciliation", document.casefold())
        self.assertNotIn("parent changed", document.casefold())
        self.assertNotIn("PRIVATE-RECONCILIATION-SENTINEL", document)
        self.assertNotIn('"review_progress"', document)
        self.assertNotIn('"preparation"', document)

        for text in (
            "Maximum 2 rounds remaining", "Cooldown", "2 required rounds remaining",
            "Accepted fixes pending", "Human stop", "Taper met", "Required rounds complete",
            "Maximum reached", "Maximum 4 rounds remaining",
        ):
            self.assertIn(text, document)
        self.assertIn('title="Early taper is allowed; running reviews count as unfinished &amp; &lt;current&gt;."', document)
        self.assertIn('aria-label="Progress: Review in progress; governing rule: Maximum 2 rounds remaining; '
                      'Early taper is allowed; running reviews count as unfinished &amp; &lt;current&gt;."', document)
        self.assertIn("Progress not checked", document)
        fallback = self.page.render_activity_cards(queue[47], self.now)
        self.assertEqual(2, fallback.count("<strong>Progress:</strong> Progress not checked"))
        self.assertEqual(2, fallback.count("<strong>Rule:</strong> Policy not checked"))
        self.assertIn('<span>2 completed</span>', row_42)
        self.assertIn('<span>3/2/0</span><time class="round-age"', row_42)
        self.assertLess(row_42.index("Rule:</strong> Normal taper"), row_42.index('<div class="round-pills">'))
        self.assertIn(".activity-progress { display: flex; flex-wrap: wrap;", document)

        detail = self.page.render_review_detail(
            data, review, self.now, 42,
            history={"state": "empty", "runs": [], "findings": [], "routes": [], "decisions": []},
        )
        self.assertIn("Maximum 2 rounds remaining", detail)
        self.assertIn("Review in progress", detail)
        self.assertIn("Policy not checked", self.page.render_activity_cards(queue[47], self.now))
        self.assertNotIn("Needs reconciliation", detail)

    def front_fixture(self, fronts):
        base = self.data["stack"][0]
        data = {**self.data, "stack": [
            {**base, "number": number, "title": f"PR {number}", "stage": "Review queue"}
            for number in (42, 43)
        ]}
        queue = {number: {
            "state": "OPEN", "channels": {"hosted": "READY", "cli": "READY"},
            "review_activity": {channel: {"total": 0, "recent": []} for channel in ("hosted", "cli")},
            "review_progress": {channel: {"label": "Needs review", "rule": {"label": "Normal taper"}}
                                for channel in ("hosted", "cli")},
        } for number in (42, 43)}
        review = {"available": True, "ordered_prs": [42, 43], "queue": queue,
                  "review_fronts": fronts, "review_targets": {
                      channel: {"pr": None, "status": "UNKNOWN"} for channel in ("hosted", "cli")}}
        github = {"available": True, "states": {}, "lifecycle": {42: "OPEN", 43: "OPEN"},
                  "merged_at": {}, "stats": {}}
        return data, review, github

    @staticmethod
    def channel_card(document, number, channel):
        row = document.split(f'<li id="pr-{number}"', 1)[1].split("</li>", 1)[0]
        name = "Hosted CodeRabbit" if channel == "hosted" else "CLI CodeRabbit"
        return row.split(f"<strong>{name}</strong>", 1)[1].split('class="activity-card"', 1)[0]

    def test_controller_snapshot_retains_fronts_separately_from_request_targets(self):
        _data, review, _github = self.front_fixture({"hosted": {"pr": 42, "status": "READY"}})
        report = {"ordered_prs": review["ordered_prs"],
                  "prs": [{"pr": number, "head": "a" * 40, **row}
                          for number, row in review["queue"].items()],
                  "review_fronts": review["review_fronts"], "review_targets": review["review_targets"]}
        result = subprocess.CompletedProcess([], 0, json.dumps(report), "")
        with patch.object(self.page.subprocess, "run", return_value=result):
            snapshot = self.page.review_snapshot(Path("/fake/tools/pr-review"), None, None, self.now)
        self.assertTrue(snapshot["available"])
        self.assertEqual(snapshot["review_fronts"], report["review_fronts"])
        self.assertEqual(snapshot["review_targets"], report["review_targets"])

    def test_known_split_fronts_have_channel_next_badges_despite_unknown_requests(self):
        data, review, github = self.front_fixture({
            "hosted": {"pr": 42, "status": "UNRECONCILED"},
            "cli": {"pr": 43, "status": "HELD"},
        })
        # A request blocker and running activity never redefine the logical front.
        review["queue"][43]["review_progress"]["cli"]["label"] = "Reviewing"
        document = self.page.render(data, review, self.now, github)
        self.assertEqual(self.page.selected_review_fronts(data, review, github), {42, 43})
        for number, channel in ((42, "hosted"), (43, "cli")):
            self.assertIn('class="activity-next"', self.channel_card(document, number, channel))
        for number, channel in ((42, "cli"), (43, "hosted")):
            self.assertNotIn('class="activity-next"', self.channel_card(document, number, channel))
        self.assertIn("Progress:</strong> Reviewing", self.channel_card(document, 43, "cli"))
        self.assertIn("Progress:</strong> Needs review", self.channel_card(document, 42, "hosted"))
        self.assertEqual(document.count('queue-status-front">REVIEW FRONT'), 2)
        detail = self.page.render_review_detail(data, review, self.now, 43)
        self.assertEqual(detail.count('class="activity-next"'), 1)

    def test_same_pr_front_has_next_badge_on_both_channels(self):
        data, review, github = self.front_fixture({
            channel: {"pr": 42, "status": "READY"} for channel in ("hosted", "cli")})
        document = self.page.render(data, review, self.now, github)
        self.assertEqual(self.page.selected_review_fronts(data, review, github), {42})
        for channel in ("hosted", "cli"):
            self.assertIn('class="activity-next"', self.channel_card(document, 42, channel))
            self.assertNotIn('class="activity-next"', self.channel_card(document, 43, channel))

    def test_missing_or_unknown_fronts_never_fall_back_to_requests_or_unfinished_rows(self):
        for fronts in (None, {}, {"hosted": {"pr": 42, "status": "UNKNOWN"}},
                       {"hosted": {"pr": None, "status": "COMPLETE"}}):
            with self.subTest(fronts=fronts):
                data, review, github = self.front_fixture(fronts)
                if fronts is None:
                    review.pop("review_fronts")
                review["review_targets"] = {channel: {"pr": 42, "status": "READY"}
                                            for channel in ("hosted", "cli")}
                data["review_front"] = 42
                document = self.page.render(data, review, self.now, github)
                self.assertEqual(self.page.selected_review_fronts(data, review, github), set())
                self.assertNotIn('class="activity-next"', document)
                self.assertNotIn('queue-status-front">REVIEW FRONT', document)

    def test_merged_and_closed_fronts_have_no_next_badges(self):
        data, review, github = self.front_fixture({
            "hosted": {"pr": 42, "status": "READY"}, "cli": {"pr": 43, "status": "READY"}})
        github["lifecycle"] = {42: "MERGED", 43: "CLOSED"}
        for number, lifecycle in github["lifecycle"].items():
            review["queue"][number]["state"] = lifecycle
            review["queue"][number]["merged"] = lifecycle == "MERGED"
        document = self.page.render(data, review, self.now, github)
        self.assertEqual(self.page.selected_review_fronts(data, review, github), set())
        self.assertNotIn('class="activity-next"', document)
        for number in (42, 43):
            detail = self.page.render_review_detail(data, review, self.now, number)
            self.assertNotIn('class="activity-next"', detail)

    def test_merged_channel_cards_preserve_history_without_request_progress(self):
        queue_item = {
            "merged": True,
            "review_activity": {"hosted": {"total": 2, "recent": []},
                                "cli": {"total": 1, "recent": []}},
            "review_progress": {"hosted": {"label": "Needs review", "rule": {"label": "Normal taper"}}},
        }
        cards = self.page.render_activity_cards(queue_item, self.now)
        self.assertIn("2 completed", cards)
        self.assertIn("1 completed", cards)
        self.assertNotIn("activity-progress", cards)
        self.assertNotIn("Needs review", cards)
        queue_item["merged"] = False
        self.assertNotIn("activity-progress", self.page.render_activity_cards(
            queue_item, self.now, show_progress=False))

    def test_full_copy_server_serves_private_routes_and_runtime_only_links(self):
        review = {
            "available": True, "ordered_prs": [42], "review_targets": {},
            "queue": {42: {
                "channels": {"hosted": "PARENT_MOVED", "cli": "UNRECONCILED"},
                "review_activity": {"hosted": {"total": 2, "recent": []},
                                    "cli": {"total": 0, "recent": []}},
                "review_progress": {"hosted": {
                    "status": "HELD", "label": "Reviewing",
                    "rule": {"label": "Maximum 2 rounds remaining",
                             "description": "Remaining rounds include any running or reserved round."},
                }},
            }},
        }
        document = self.page.render(
            self.data, review, self.now, self.github, jobs=self.public_jobs,
            lanes_snapshot=self.public_lanes,
        )
        store = FakeStore()
        with tempfile.TemporaryDirectory() as temporary:
            index = Path(temporary) / "index.html"
            index.write_text(document, encoding="utf-8")
            progress = Path(temporary) / "progress.html"
            progress.write_text(self.map_document, encoding="utf-8")
            server = self.server_module.StatusServer(
                ("127.0.0.1", 0), Path(temporary),
                auto_refresh_interval=1_000_000_000,
                jobs_store=store,
                job_web=web,
                inbox_store=object(),
                workstreams_store=object(),
                editorial=self.editorial,
            )
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                deadline = time.monotonic() + 5
                while not thread.is_alive() and time.monotonic() < deadline:
                    time.sleep(0.01)
                connection = HTTPConnection("127.0.0.1", server.server_port, timeout=5)
                connection.request("GET", "/")
                response = connection.getresponse()
                local_page = response.read().decode("utf-8")
                self.assertEqual(response.status, 200)
                self.assertIn("Maximum 2 rounds remaining", local_page)
                self.assertIn("Progress:</strong> Reviewing", local_page)
                self.assertIn("Progress:</strong> Progress not checked", local_page)
                self.assertNotIn("parent changed", local_page.casefold())
                self.assertNotIn("needs reconciliation", local_page.casefold())
                self.assertIn('href="/jobs/job-1"', local_page)
                self.assertIn('href="/inbox/Gameplay"', local_page)
                self.assertNotIn('href="/jobs/job-1"', index.read_text(encoding="utf-8"))
                self.assertNotIn('href="/inbox/Gameplay"', index.read_text(encoding="utf-8"))

                connection.request("GET", "/progress.html")
                progress_response = connection.getresponse()
                local_progress = progress_response.read().decode("utf-8")
                self.assertEqual(progress_response.status, 200)
                self.assertIn('href="/workstreams/decision-foundation"', local_progress)
                self.assertNotIn(
                    'href="/workstreams/decision-foundation"', progress.read_text(encoding="utf-8"),
                )

                connection.request("GET", "/jobs/job-1")
                detail = connection.getresponse()
                detail_body = detail.read().decode("utf-8")
                self.assertEqual(detail.status, 200, detail_body)
                self.assertIn(PRIVATE_SENTINEL, detail_body)
                self.assertEqual(detail.getheader("Cache-Control"), "no-store")

                connection.request("GET", "/jobs/job-1/history?offset=50")
                history = connection.getresponse()
                self.assertEqual(history.status, 200)
                self.assertIn("Build the bridge history", history.read().decode("utf-8"))
                connection.close()
            finally:
                server.shutdown()
                thread.join(timeout=5)
                server.server_close()

    def test_private_inbox_links_support_full_unicode_worker_aliases(self):
        jobs = importlib.import_module("fire_controller.jobs")
        handler = object.__new__(self.server_module.StatusHandler)
        handler.server = types.SimpleNamespace(
            jobs_store=None, workstreams_store=None, inbox_store=object(),
        )
        for worker in ("Gameplay", "界" * 40, "😀" * 100, 'Build "& Tools'):
            with self.subTest(worker=worker):
                self.assertEqual(jobs._worker(worker), worker)
                lane = {**self.public_lanes[0], "worker": worker}
                document = self.page._render_controller_lane(lane)
                target = self.server_module.quote(worker, safe="")
                self.assertIn(f'data-fire-controller-inbox-worker="{target}"', document)
                local = handler._local_private_links(document)
                self.assertIn(f'href="/inbox/{target}">Worker inbox</a>', local)
                self.assertNotIn('data-fire-controller-inbox-worker=', local)
                self.assertNotIn('href="/inbox/', document)

    def test_private_inbox_links_reject_malformed_or_noncanonical_markers(self):
        handler = object.__new__(self.server_module.StatusHandler)
        handler.server = types.SimpleNamespace(
            jobs_store=None, workstreams_store=None, inbox_store=object(),
        )
        tokens = ("", "%", "%ZZ", "%FF", "%41", "%f0%9f%98%80", "x" * 1201,
                  'worker" onclick="alert(1)')
        for token in tokens:
            with self.subTest(token=token):
                document = f'<span data-fire-controller-inbox-worker="{token}"></span>'
                self.assertEqual(handler._local_private_links(document), document)
        document = '<span data-fire-controller-inbox-worker="Gameplay" class="extra"></span>'
        self.assertEqual(handler._local_private_links(document), document)


if __name__ == "__main__":
    unittest.main()


class RetainedHistoryAndAdapterTests(unittest.TestCase):
    def test_adapter_applies_root_relative_patch_inside_parent_git_worktree(self):
        helper_path = TOOLS / "fire_controller" / "integration" / "apply_private_adapter.py"
        spec = importlib.util.spec_from_file_location("nested_adapter_test", helper_path)
        helper = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(helper)
        index_path = Path(subprocess.check_output(
            ["git", "rev-parse", "--path-format=absolute", "--git-path", "index"],
            cwd=ROOT, text=True).strip())
        original_index = index_path.read_bytes()
        fixture_parent = ROOT / "tmp"
        fixture_parent.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=fixture_parent) as temporary:
            directory = Path(temporary)
            outside = directory / "outside.txt"
            outside.write_text("Unrelated source stays intact")
            source, target = directory / "source", directory / "nested" / "site"
            source.mkdir()
            target.mkdir(parents=True)
            patch_parts = []
            helper.BASELINE, helper.POST_PATCH, helper.UNCHANGED = {}, {}, {}
            for name in helper.EXPECTED_FILES:
                old, new = "old source\n", "new source\n"
                (source / name).write_text(old)
                (target / name).write_text(old)
                helper.BASELINE[name] = hashlib.sha256(old.encode()).hexdigest()
                helper.POST_PATCH[name] = hashlib.sha256(new.encode()).hexdigest()
                patch_parts.append(f"diff --git a/{name} b/{name}\n")
                patch_parts.extend(difflib.unified_diff(
                    old.splitlines(keepends=True), new.splitlines(keepends=True),
                    fromfile=f"a/{name}", tofile=f"b/{name}"))
            helper.PATCH = directory / "adapter.patch"
            helper.PATCH.write_text("".join(patch_parts))
            arguments = ["--source-site", str(source), "--site-copy", str(target)]
            self.assertEqual(helper.main(arguments), 0)
            self.assertEqual(helper.main(arguments + ["--verify"]), 0)
            for name in helper.EXPECTED_FILES:
                self.assertEqual((source / name).read_text(), "old source\n")
                self.assertEqual((target / name).read_text(), "new source\n")
            self.assertEqual(outside.read_text(), "Unrelated source stays intact")
            self.assertEqual(index_path.read_bytes(), original_index)

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

    def test_adapter_checks_complete_captured_baseline(self):
        source = TOOLS / "fire_controller" / "integration" / "apply_private_adapter.py"
        spec = importlib.util.spec_from_file_location("adapter_fingerprints", source)
        adapter = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(adapter)
        self.assertEqual(set(adapter.EXPECTED_FILES), set(adapter.BASELINE))
        self.assertEqual(set(adapter.UNCHANGED), {"shared.css", "test_render.py", "test_server.py", "test_publish.py"})
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in (*adapter.BASELINE, *adapter.UNCHANGED):
                (root / name).write_text(name)
            import hashlib
            expected = {name: hashlib.sha256((root / name).read_bytes()).hexdigest()
                        for name in (*adapter.BASELINE, *adapter.UNCHANGED)}
            adapter.check_source(root, expected)
            for name in adapter.UNCHANGED:
                original = (root / name).read_text()
                (root / name).write_text("Changed")
                with self.assertRaisesRegex(ValueError, name):
                    adapter.check_source(root, expected)
                (root / name).write_text(original)
