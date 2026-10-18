from __future__ import annotations

import concurrent.futures
import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

import test_pr_review_sqlite_hosted_capture as hosted_fixtures
from pr_review import checkpoint_publication as publication
from pr_review import cli, evidence, github, sqlite_hosted_capture, sqlite_provider_imports, sqlite_store
from pr_review.sqlite_finding_text import _safe_finding_detail
from pr_review.sqlite_review_records import FindingObservation, ReviewRecordsError, SqliteReviewRecords

REPO = "owner/repo"
PR = 42
HEAD = "abcdef0123456789abcdef0123456789abcdef01"
RUN = "run.Checkpoint123"


class FakeGitHub:
    def __init__(self):
        self.comments = []
        self.posts = 0
        self.uncertain = False
        self.hide = False

    def _fetch_api_object(self, endpoint):
        return {"id": 123, "full_name": REPO}

    def fetch_authenticated_user(self):
        return {"id": 456, "login": "owner"}

    def fetch_api_endpoint(self, endpoint):
        return copy.deepcopy(self.comments) if not self.hide else []

    def post_issue_comment(self, repo, pr, body):
        self.posts += 1
        comment = {"id": 789, "body": body, "user": self.fetch_authenticated_user(),
                   "created_at": "2026-09-29T02:00:00Z", "updated_at": "2026-09-29T02:00:00Z",
                   "issue_url": f"https://api.github.com/repos/{repo}/issues/{pr}",
                   "html_url": f"https://github.com/{repo}/pull/{pr}#issuecomment-789"}
        self.comments.append(comment)
        if self.uncertain:
            raise TimeoutError("response lost")
        return copy.deepcopy(comment)


class CheckpointPublicationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "controller.sqlite3"
        sqlite_store.SqliteStateStore(self.path).update(lambda state: state)
        self.store = SqliteReviewRecords(self.path)
        self.store.bootstrap()
        self.github = FakeGitHub()

    def cli_run(self, *, finalize=True, duration=67, repository=REPO):
        metadata = {"repository": repository, "pull_request": PR, "run_id": RUN, "kind": "cli",
                    "candidate_sha": HEAD, "candidate_files": 2, "parent_pr": None,
                    "parent_ref": "develop", "child_head_sha": HEAD, "parent_sha": "a" * 40,
                    "merge_base": "a" * 40, "patch_identity": "b" * 64}
        self.store.start_attempt(attempt_id=RUN, source_pr=PR, channel="cli", candidate_sha=HEAD,
                                 started_at="2026-09-29T01:00:00Z", metadata=metadata)
        events = [{"type": "finding", "codegenInstructions": f"Review comment at @a.py:1\nFinding {i}"}
                  for i in range(1, 4)]
        findings = [FindingObservation(
            source_finding_key=f"cli-run:{RUN}:finding:{i}",
            title=sqlite_provider_imports._cli_finding_title(item["codegenInstructions"], f"CodeRabbit CLI finding {i}"),
            detail=_safe_finding_detail(sqlite_provider_imports._cli_detail(item["codegenInstructions"])),
        ) for i, item in enumerate(events, 1)]
        events.append({"type": "complete", "status": "review_completed", "findings": 3,
                       "reviewedFiles": ["a.py", "b.py"]})
        metadata.update(duration_seconds=duration, exit_status=0, capture_completion_marker="capture-complete")
        self.store.complete_attempt_run(RUN, finish={"state": "completed", "duration_seconds": duration,
            "exit_status": 0, "finished_at": "2026-09-29T01:02:00Z",
            "artifacts": {"metadata": json.dumps(metadata),
                          "cli_events": "\n".join(json.dumps(item) for item in events)}},
            run={"run_id": RUN, "source_pr": PR, "channel": "cli", "source_head": HEAD,
                 "reviewer": "CodeRabbit CLI", "scope": "broad", "findings": findings})
        for i, decision in enumerate(("accepted", "routed", "rejected"), 1):
            self.store.record_source_decision(RUN, source_finding_key=f"cli-run:{RUN}:finding:{i}",
                decision_id=f"decision-{i}", decision=decision, actor="owner", reason="adjudicated",
                target_pr=43 if decision == "routed" else None)
        if finalize:
            self.store.finalize_run(RUN)

    def publish(self, run_id=RUN):
        return publication.publish_checkpoint(self.store, repo=REPO, source_pr=PR, run_id=run_id, adapter=self.github)

    def test_cli_canonical_counts_markers_and_retry_keep_older_head(self):
        self.cli_run()
        result = self.publish()
        self.assertEqual(result["status"], "published")
        self.assertEqual(result["comment_id"], 789)
        self.assertEqual(result["body"], f"CLI: 3 Found / 1 Accepted / 1 Routed · {HEAD} · 2 files · 1m 07s\n"
                         f"<!-- firemud-cli-run: {RUN} -->\n<!-- firemud-review-duration-seconds: 67 -->")
        self.assertEqual(self.publish()["status"], "reused")
        self.assertEqual(self.github.posts, 1)
        self.assertEqual(self.store.history(PR)["runs"][0]["counts"], {"found": 3, "accepted": 1, "routed": 1})

    def test_corrected_native_decision_cannot_replace_original_publication_or_intent(self):
        from test_pr_review_sqlite_review_records import corrected_native_fixture

        run_id, _, comment = corrected_native_fixture(self.store)
        before = copy.deepcopy(self.store.attempt(run_id)["metadata"]["checkpoint_publication_intent"])
        self.github.comments.append({**comment, "user": {"id": 456, "login": "owner"},
            "issue_url": f"https://api.github.com/repos/{REPO}/issues/{PR}"})
        with self.assertRaises((ReviewRecordsError, evidence.CaptureInvalid)):
            self.publish(run_id)
        self.assertEqual(self.github.posts, 0)
        self.assertEqual(self.store.attempt(run_id)["metadata"]["checkpoint_publication_intent"], before)
        self.assertEqual(self.github.comments[0]["body"], comment["body"])

    def test_unfinalized_refuses_before_github(self):
        self.cli_run(finalize=False)
        with self.assertRaisesRegex(ReviewRecordsError, "finalized"):
            self.publish()
        self.assertEqual(self.github.posts, 0)

    def test_wrong_source_pr_and_repository_refuse(self):
        self.cli_run(repository="another/repo")
        with self.assertRaises((ReviewRecordsError, evidence.CaptureInvalid)):
            self.publish()
        with self.assertRaises(ReviewRecordsError):
            publication.checkpoint_body(self.store, repo=REPO, source_pr=43, run_id=RUN)
        self.assertEqual(self.github.posts, 0)

    def test_conflicting_public_count_and_marker_refuse_without_reposting(self):
        self.cli_run()
        self.publish()
        for modification in (lambda text: text.replace("3 Found", "4 Found"),
                             lambda text: text.replace("2 files", "3 files"),
                             lambda text: text.replace(HEAD, "b" * 40),
                             lambda text: text + f"\n<!-- firemud-cli-run: {RUN} -->",
                             lambda text: text.replace(f"{RUN} -->", f"{RUN}>"),
                             lambda text: text.replace("seconds: 67", "seconds: 68")):
            original = self.github.comments[0]["body"]
            self.github.comments[0]["body"] = modification(original)
            with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
                self.publish()
            self.github.comments[0]["body"] = original
        self.assertEqual(self.github.posts, 1)

    def test_existing_linked_sha_and_whitespace_reuses_without_rewriting(self):
        self.cli_run()
        body, _ = publication.checkpoint_body(self.store, repo=REPO, source_pr=PR, run_id=RUN)
        self.github.post_issue_comment(REPO, PR, body)
        variant = body.replace(HEAD, f"[{HEAD[:7]}](https://github.com/{REPO}/commit/{HEAD})")
        variant = "\n  " + variant.replace(" · ", "  ·   ").replace(" / ", "  /   ").replace("\n", "  \n\n  ") + "  \n"
        self.github.comments[0]["body"] = variant
        self.assertEqual(self.publish()["status"], "reused")
        self.assertEqual(self.github.comments[0]["body"], variant)
        self.assertEqual(self.github.posts, 1)

    def test_linked_sha_true_conflicts_refuse(self):
        self.cli_run()
        body, _ = publication.checkpoint_body(self.store, repo=REPO, source_pr=PR, run_id=RUN)
        self.github.post_issue_comment(REPO, PR, body)
        for label, destination in ((HEAD[:7], "b" * 40), ("c" * 7, HEAD)):
            self.github.comments[0]["body"] = body.replace(
                HEAD, f"[{label}](https://github.com/{REPO}/commit/{destination})")
            with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
                self.publish()
        self.github.comments[0]["body"] = body.replace(
            HEAD, f"[{HEAD[:7]}](https://github.com/another/repo/commit/{HEAD})")
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.publish()
        self.assertEqual(self.github.posts, 1)

    def test_unassigned_route_open_refuses_before_controller_or_mutation(self):
        with (patch.object(cli, "default_controller", side_effect=AssertionError("must refuse before controller")),
              self.assertRaises(SystemExit)):
            cli.main(["decide", "route", "open", "--source-pr", str(PR), "--channel", "cli",
                      "--review", RUN, "--finding", "finding-1", "--observation", "unowned", "--unassigned"])

    def test_conflicting_authenticated_author_refuses(self):
        self.cli_run()
        self.publish()
        self.github.comments[0]["user"]["id"] = 999
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.publish()

    def test_uncertain_post_recovers_visible_comment(self):
        self.cli_run()
        self.github.uncertain = True
        self.assertEqual(self.publish()["status"], "published")
        self.assertEqual(self.publish()["status"], "reused")
        self.assertEqual(self.github.posts, 1)

    def test_uncertain_invisible_post_is_durably_held_until_read_recovery(self):
        self.cli_run()
        self.github.uncertain = self.github.hide = True
        with self.assertRaisesRegex(ReviewRecordsError, "uncertain"):
            self.publish()
        with self.assertRaisesRegex(ReviewRecordsError, "prior checkpoint POST"):
            self.publish()
        self.assertEqual(self.github.posts, 1)
        self.github.hide = False
        self.assertEqual(self.publish()["status"], "reused")

    def test_concurrent_publishers_share_lock_and_post_once(self):
        self.cli_run()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: self.publish(), range(2)))
        self.assertEqual(sorted(item["status"] for item in results), ["published", "reused"])
        self.assertEqual(self.github.posts, 1)

    def prepare_recovery(self):
        self.cli_run()
        intent, _, _ = publication._publication_context(
            self.store, repo=REPO, source_pr=PR, run_id=RUN, adapter=self.github)
        self.store.reserve_checkpoint_publication(RUN, json.dumps(intent))
        return {"repo": REPO, "source_pr": PR, "run_id": RUN,
                "expected_state_sha256": self.store.checkpoint_publication_state(RUN)["state_sha256"],
                "decision_id": "operator-recovery-1", "reason": "Accept delayed-delivery risk after inspection",
                "acknowledge_uncertain_delivery": True, "adapter": self.github}

    def test_recovery_posts_once_preserving_all_source_and_provider_evidence(self):
        options = self.prepare_recovery()
        history = self.store.history(PR)
        archives = self.store.attempt_artifacts(RUN)
        metadata = self.store.attempt(RUN)["metadata"]
        controller = sqlite_store.SqliteStateStore(self.path).load()
        result = publication.recover_checkpoint(self.store, **options)
        self.assertEqual((result["status"], result["comment_id"], self.github.posts), ("published", 789, 1))
        state = self.store.checkpoint_publication_state(RUN)
        self.assertEqual([e["type"] for e in state["events"]],
                         ["recovery_decision", "post_started", "post_outcome"])
        self.assertEqual(state["historical_outcome"], "unknown")
        self.assertEqual(state["events"][0]["actor_id"], 456)
        self.assertEqual(state["events"][0]["absence"]["comments"], 0)
        self.assertEqual(state["last_known_outcome"]["comment_id"], 789)
        self.assertEqual(self.store.history(PR), history)
        self.assertEqual(self.store.attempt_artifacts(RUN), archives)
        self.assertEqual(sqlite_store.SqliteStateStore(self.path).load(), controller)
        after = self.store.attempt(RUN)["metadata"]
        after.pop("checkpoint_publication_events")
        self.assertEqual(after, metadata)
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")
        self.assertEqual(self.github.posts, 1)
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            publication.recover_checkpoint(self.store, **{**options, "reason": "Changed justification"})
        self.assertEqual(self.github.posts, 1)

    def test_recovery_requires_acknowledgement_exact_state_and_intent(self):
        options = self.prepare_recovery()
        for changes, message in (({"acknowledge_uncertain_delivery": False}, "acknowledgement"),
                                 ({"expected_state_sha256": "0" * 64}, "stale"),
                                 ({"source_pr": PR + 1}, "exactly one")):
            with self.subTest(changes=changes), self.assertRaisesRegex(ReviewRecordsError, message):
                publication.recover_checkpoint(self.store, **{**options, **changes})
        with patch.object(self.github, "fetch_authenticated_user", return_value={"id": 999, "login": "other"}), self.assertRaisesRegex(ReviewRecordsError, "original publication intent"):
            publication.recover_checkpoint(self.store, **options)
        with patch.object(self.github, "_fetch_api_object", return_value={"id": 999, "full_name": REPO}), self.assertRaisesRegex(ReviewRecordsError, "original publication intent"):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(self.github.posts, 0)
        self.assertEqual(self.store.checkpoint_publication_state(RUN)["events"], [])

    def test_recovery_missing_intent_and_unfinalized_run_refuse(self):
        self.cli_run(finalize=False)
        options = {"repo": REPO, "source_pr": PR, "run_id": RUN, "expected_state_sha256": "0" * 64,
                   "decision_id": "decision", "reason": "Explicit choice", "acknowledge_uncertain_delivery": True,
                   "adapter": self.github}
        with self.assertRaisesRegex(ReviewRecordsError, "finalized"):
            publication.recover_checkpoint(self.store, **options)
        self.store.finalize_run(RUN)
        with self.assertRaisesRegex(ReviewRecordsError, "original publication intent"):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(self.github.posts, 0)

    def test_recovery_reuses_comment_appearing_at_second_read(self):
        options = self.prepare_recovery()
        body = self.store.checkpoint_publication_state(RUN)["intent"]["body"]
        comment = self.github.post_issue_comment(REPO, PR, body)
        self.github.posts = 0
        self.github.comments = []
        with patch.object(self.github, "fetch_api_endpoint", side_effect=[[], [comment]]):
            result = publication.recover_checkpoint(self.store, **options)
        self.assertEqual((result["status"], self.github.posts), ("reused", 0))
        self.assertEqual([e["type"] for e in self.store.checkpoint_publication_state(RUN)["events"]],
                         ["recovery_decision", "reused"])
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")

    def test_recovery_existing_comment_reuses_without_authorizing_post(self):
        options = self.prepare_recovery()
        body = self.store.checkpoint_publication_state(RUN)["intent"]["body"]
        self.github.post_issue_comment(REPO, PR, body)
        self.github.posts = 0
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "reused")
        self.assertEqual(self.github.posts, 0)
        self.assertEqual(self.store.checkpoint_publication_state(RUN)["events"][0]["matched_comment_id"], 789)
        self.github.comments = []
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")
        self.assertEqual(self.github.posts, 0)

    def test_recovery_incomplete_history_refuses_before_or_after_decision(self):
        options = self.prepare_recovery()
        with patch.object(self.github, "fetch_api_endpoint", side_effect=RuntimeError("page two unavailable")), self.assertRaisesRegex(RuntimeError, "page two"):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(self.store.checkpoint_publication_state(RUN)["events"], [])
        with patch.object(self.github, "fetch_api_endpoint", side_effect=[[], RuntimeError("page two unavailable")]), self.assertRaises(RuntimeError):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")
        self.assertEqual(self.github.posts, 0)
        with self.assertRaisesRegex(ReviewRecordsError, "prior checkpoint POST"):
            self.publish()

    def test_recovery_conflicting_duplicate_or_malformed_history_refuses(self):
        options = self.prepare_recovery()
        body = self.store.checkpoint_publication_state(RUN)["intent"]["body"]
        comment = self.github.post_issue_comment(REPO, PR, body)
        self.github.posts = 0
        variants = [[{**comment, "body": body.replace("3 Found", "4 Found")}],
                    [comment, {**comment, "id": 790}],
                    [{**comment, "user": {"id": 999, "login": "other"}}],
                    [{**comment, "body": body + "\n<!-- extra -->"}],
                    [{"id": 789}], [comment, comment]]
        for comments in variants:
            with self.subTest(comments=comments), patch.object(self.github, "fetch_api_endpoint", return_value=comments), self.assertRaises(ReviewRecordsError):
                publication.recover_checkpoint(self.store, **options)
        self.assertEqual(self.store.checkpoint_publication_state(RUN)["events"], [])
        self.assertEqual(self.github.posts, 0)

    def test_recovery_concurrent_same_decision_posts_once(self):
        options = self.prepare_recovery()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: publication.recover_checkpoint(self.store, **options), range(2)))
        self.assertEqual(sorted(r["status"] for r in results), ["decision_replayed", "published"])
        self.assertEqual(self.github.posts, 1)

    def test_recovery_crash_after_consumption_never_replays_post(self):
        options = self.prepare_recovery()
        with patch.object(self.github, "post_issue_comment", side_effect=KeyboardInterrupt), self.assertRaises(KeyboardInterrupt):
            publication.recover_checkpoint(self.store, **options)
        state = self.store.checkpoint_publication_state(RUN)
        self.assertEqual(state["events"][-1]["type"], "post_started")
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")
        with self.assertRaisesRegex(ReviewRecordsError, "prior checkpoint POST"):
            self.publish()
        self.assertEqual(self.github.posts, 0)

    def test_recovery_crash_before_consumption_requires_new_decision(self):
        options = self.prepare_recovery()
        append = self.store.append_checkpoint_publication_event

        def interrupted(run_id, event, **kwargs):
            if event["type"] == "post_started":
                raise KeyboardInterrupt
            return append(run_id, event, **kwargs)

        with patch.object(self.store, "append_checkpoint_publication_event", side_effect=interrupted), self.assertRaises(KeyboardInterrupt):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")
        self.assertEqual(self.github.posts, 0)
        options.update(decision_id="operator-recovery-2",
                       expected_state_sha256=self.store.checkpoint_publication_state(RUN)["state_sha256"])
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "published")
        self.assertEqual(self.github.posts, 1)

    def test_recovery_uncertain_hidden_delivery_stays_fenced(self):
        options = self.prepare_recovery()
        self.github.hide = self.github.uncertain = True
        with self.assertRaisesRegex(ReviewRecordsError, "uncertain"):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")
        with self.assertRaisesRegex(ReviewRecordsError, "prior checkpoint POST"):
            self.publish()
        self.assertEqual(self.github.posts, 1)
        self.github.hide = False
        self.assertEqual(self.publish()["status"], "reused")
        self.assertEqual(self.github.posts, 1)

    def test_recovery_visible_lost_response_is_recovered_and_audited(self):
        options = self.prepare_recovery()
        self.github.uncertain = True
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "published")
        self.assertEqual(self.store.checkpoint_publication_state(RUN)["last_known_outcome"]["type"], "reused")
        self.assertEqual(self.github.posts, 1)

    def test_typed_post_failure_is_retained_without_raw_exception_or_body(self):
        options = self.prepare_recovery()
        with patch.object(self.github, "post_issue_comment", side_effect=github.CommentPostError("http_error", 403)), self.assertRaisesRegex(ReviewRecordsError, "uncertain"):
            publication.recover_checkpoint(self.store, **options)
        state = self.store.checkpoint_publication_state(RUN)
        self.assertEqual(state["last_known_outcome"]["http_status"], 403)
        self.assertEqual(state["last_known_outcome"]["outcome"], "http_error")
        self.assertNotIn("body", state["last_known_outcome"])
        with self.assertRaisesRegex(ReviewRecordsError, "prior checkpoint POST"):
            self.publish()
        self.assertEqual(self.github.posts, 0)

    def test_comment_post_adapter_retains_only_static_category_and_numeric_http(self):
        secret = "ghp_" + "a" * 40
        errors = [(subprocess.CalledProcessError(1, ["gh", secret], stderr=f"{secret} (HTTP 403)"), "http_error", 403),
                  (subprocess.CalledProcessError(1, ["gh", secret], stderr=secret), "client_error", None),
                  (subprocess.TimeoutExpired(["gh", secret], 120, output=secret), "timeout", None),
                  (FileNotFoundError(secret), "client_unavailable", None)]
        for error, category, status in errors:
            with self.subTest(category=category), patch.object(github.subprocess, "run", side_effect=error), self.assertRaises(github.CommentPostError) as caught:
                github.post_issue_comment(REPO, PR, secret)
                self.assertEqual((caught.exception.category, caught.exception.http_status), (category, status))
                self.assertNotIn(secret, str(caught.exception))
                self.assertNotIn(secret, repr(caught.exception.__dict__))
        for output in ("not json " + secret, "[]"):
            with patch.object(github.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output)), self.assertRaises(github.CommentPostError) as caught:
                github.post_issue_comment(REPO, PR, secret)
                self.assertEqual(caught.exception.category, "invalid_response")
                self.assertNotIn(secret, str(caught.exception))

    def test_recovery_reads_all_pages_and_rejects_partial_api_failure(self):
        options = self.prepare_recovery()
        body = self.store.checkpoint_publication_state(RUN)["intent"]["body"]
        comment = self.github.post_issue_comment(REPO, PR, body)
        self.github.posts = 0
        unrelated = {**comment, "id": 700, "body": "Earlier unrelated comment"}
        with (patch.object(self.github, "fetch_api_endpoint", side_effect=github.fetch_api_endpoint),
              patch.object(github.subprocess, "run", return_value=subprocess.CompletedProcess(
                  [], 0, json.dumps([[unrelated], [comment]]))) as api):
            self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "reused")
        self.assertIn("--paginate", api.call_args.args[0])
        self.assertIn("--slurp", api.call_args.args[0])
        state = self.store.checkpoint_publication_state(RUN)
        options.update(decision_id="operator-2", expected_state_sha256=state["state_sha256"])
        partial = subprocess.CalledProcessError(1, ["gh"], output=json.dumps([[unrelated]]), stderr="page failure")
        with (patch.object(self.github, "fetch_api_endpoint", side_effect=github.fetch_api_endpoint),
              patch.object(github.subprocess, "run", side_effect=partial),
              self.assertRaisesRegex(RuntimeError, "page failure")):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(self.github.posts, 0)
        self.assertEqual(self.store.checkpoint_publication_state(RUN), state)

    def test_recovery_conflict_on_final_read_holds_recorded_decision(self):
        options = self.prepare_recovery()
        body = self.store.checkpoint_publication_state(RUN)["intent"]["body"]
        conflict = self.github.post_issue_comment(REPO, PR, body.replace("3 Found", "4 Found"))
        self.github.posts = 0
        with (patch.object(self.github, "fetch_api_endpoint", side_effect=[[], [conflict]]),
              self.assertRaisesRegex(ReviewRecordsError, "conflicts")):
            publication.recover_checkpoint(self.store, **options)
        self.assertEqual(self.github.posts, 0)
        self.assertEqual(self.store.checkpoint_publication_state(RUN)["events"][-1]["type"], "recovery_decision")
        self.assertEqual(publication.recover_checkpoint(self.store, **options)["status"], "decision_replayed")

    def test_recovery_cannot_replace_original_intent_after_source_correction(self):
        from test_pr_review_sqlite_review_records import corrected_native_fixture

        run_id, _, _ = corrected_native_fixture(self.store)
        before = self.store.checkpoint_publication_state(run_id)
        with self.assertRaises((ReviewRecordsError, evidence.CaptureInvalid)):
            publication.recover_checkpoint(self.store, repo=REPO, source_pr=PR, run_id=run_id,
                expected_state_sha256=before["state_sha256"], decision_id="operator-1", reason="Accepted risk",
                acknowledge_uncertain_delivery=True, adapter=self.github)
        self.assertEqual(self.store.checkpoint_publication_state(run_id), before)
        self.assertEqual(self.github.posts, 0)

    def test_duration_boundaries(self):
        self.cli_run(duration=3607)
        self.assertIn("1h 00m 07s", self.publish()["body"])

    def hosted_run(self, *, files=2):
        fixture = hosted_fixtures.SqliteHostedCaptureTest()
        run_id = "hosted-attempt-101"
        metadata = {"repository": REPO}
        if files is not None:
            metadata["reviewed_file_count"] = files
        sqlite_hosted_capture.start_hosted_attempt(self.store, attempt_id=run_id, source_pr=PR,
            candidate_sha=HEAD, started_at=hosted_fixtures.TRIGGER_AT, metadata=metadata)
        sqlite_hosted_capture.record_hosted_terminal_result(self.store, attempt_id=run_id, repo=REPO,
            source_pr=PR, trigger_record=fixture.trigger_record(attempt_id=run_id), payload=fixture.multipart_payload())
        for i, finding in enumerate(self.store.history(PR)["findings"]):
            self.store.record_source_decision(run_id, source_finding_key=finding["source_finding_key"],
                decision_id=f"hosted-decision-{i}", decision="rejected", actor="owner", reason="adjudicated")
        self.store.finalize_run(run_id)
        return run_id

    def test_hosted_reproves_archive_and_formats_response_linkage(self):
        run_id = self.hosted_run()
        result = self.publish(run_id)
        self.assertEqual(result["body"], f"Hosted: 2 Found / 0 Accepted / 0 Routed · {HEAD} · 2 files · 4m 00s\n"
                         "<!-- firemud-hosted-review: 201 -->\n<!-- firemud-review-duration-seconds: 240 -->")
        self.assertEqual(self.publish(run_id)["status"], "reused")

    def test_historical_hosted_missing_file_count_refuses(self):
        run_id = self.hosted_run(files=None)
        with self.assertRaisesRegex(ReviewRecordsError, "request-time reviewed file count"):
            self.publish(run_id)
        self.assertEqual(self.github.posts, 0)

    def test_hosted_mismatched_archived_response_refuses(self):
        run_id = self.hosted_run()
        archive = self.store.attempt_artifacts(run_id)
        metadata = json.loads(archive["metadata"])
        metadata["response_id"] = 999
        archive["metadata"] = json.dumps(metadata)
        with (patch.object(self.store, "attempt_artifacts", return_value=archive),
              self.assertRaisesRegex(ReviewRecordsError, "attribution conflicts")):
            self.publish(run_id)
        self.assertEqual(self.github.posts, 0)

    def test_cli_incomplete_or_conflicting_capture_refuses(self):
        self.cli_run()
        snapshot = self.store.cli_capture_snapshot(RUN, source_pr=PR)
        for key, value in (("capture_completion_marker", "incomplete"), ("duration_seconds", 68),
                           ("candidate_files", 3), ("candidate_sha", "a" * 40)):
            mutated = copy.deepcopy(snapshot)
            metadata = json.loads(mutated["artifacts"]["metadata"])
            metadata[key] = value
            mutated["artifacts"]["metadata"] = json.dumps(metadata)
            with (patch.object(self.store, "cli_capture_snapshot", return_value=mutated),
                  self.assertRaises(evidence.CaptureInvalid)):
                self.publish()
        self.assertEqual(self.github.posts, 0)

    def test_explicit_finalize_dispatch_publishes_native_source(self):
        self.cli_run()
        args = cli._parser().parse_args(["records", "source", "finalize",
                                        "--run-id", RUN, "--repo", REPO, "--database", str(self.path)])
        with patch.object(publication, "publish_checkpoint", return_value={"status": "reused", "comment_id": 789}) as call:
            result, code = cli._dispatch_records(args)
        self.assertEqual(code, 0)
        self.assertEqual(result["result"]["publication"]["comment_id"], 789)
        self.assertEqual(call.call_args.kwargs["source_pr"], PR)

    def test_finalize_failure_leaves_counts_frozen_and_retry_recovers(self):
        self.cli_run(finalize=False)
        args = cli._parser().parse_args(["records", "source", "finalize", "--run-id", RUN,
                                        "--repo", REPO, "--database", str(self.path)])
        with (patch.object(publication, "publish_checkpoint", side_effect=ReviewRecordsError("uncertain POST")),
              self.assertRaisesRegex(ReviewRecordsError, "uncertain POST")):
            cli._dispatch_records(args)
        self.assertTrue(self.store.history(PR)["runs"][0]["finalized"])
        with patch.object(publication, "publish_checkpoint", return_value={"status": "reused", "comment_id": 789}):
            result, code = cli._dispatch_records(args)
        self.assertEqual(code, 0)
        self.assertEqual(result["result"]["publication"]["status"], "reused")

    def test_manual_finalize_remains_local(self):
        self.store.record_run(run_id="manual-pass", source_pr=PR, channel="manual", reviewer="owner",
                              scope="broad", findings=[], outcome="completed", attributable=False)
        args = cli._parser().parse_args(["records", "source", "finalize", "--run-id", "manual-pass",
                                        "--database", str(self.path)])
        with patch.object(publication, "publish_checkpoint", side_effect=AssertionError("must remain local")):
            result, code = cli._dispatch_records(args)
        self.assertEqual(code, 0)
        self.assertNotIn("publication", result["result"])


if __name__ == "__main__":
    unittest.main()
