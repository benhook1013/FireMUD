from __future__ import annotations

import concurrent.futures
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

import test_pr_review_sqlite_hosted_capture as hosted_fixtures
from pr_review import checkpoint_publication as publication
from pr_review import cli, evidence, sqlite_hosted_capture, sqlite_provider_imports, sqlite_store
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
