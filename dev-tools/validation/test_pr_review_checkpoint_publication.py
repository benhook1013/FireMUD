from __future__ import annotations

import concurrent.futures
import copy
import dataclasses
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

import test_pr_review_evidence_hosted as evidence_hosted_fixtures
import test_pr_review_sqlite_hosted_capture as hosted_fixtures
import test_pr_review_sqlite_records_repair as repair_fixtures
from pr_review import checkpoint_publication as publication
from pr_review import (
    cli,
    controller,
    evidence,
    github,
    policy,
    runtime,
    sqlite_hosted_capture,
    sqlite_provider_imports,
    sqlite_store,
    state,
)
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
        self.repo = REPO

    def _fetch_api_object(self, endpoint):
        return {"id": 123, "full_name": self.repo}

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

    def recovered_run(self, repo=REPO, *, resolved_threads=False):
        fixture = repair_fixtures.SqliteRecordsRepairTest()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        with (
            patch.object(repair_fixtures, "REPO", repo),
            patch.object(evidence_hosted_fixtures, "REPO", repo),
        ):
            payload, record, record_path = fixture.ambiguous_reply_recovery_fixture()
            if resolved_threads:
                for thread in payload["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"]:
                    thread["isResolved"] = True
                    thread["isOutdated"] = True
            fixture.recovery_record_path = record_path
            record_path.write_text(json.dumps(record), encoding="utf-8")
            recovered = fixture.recover_ambiguous_reply(payload, dry_run=False)
        return fixture, payload, recovered["items"][0]["run_id"]

    def finalize_recovered(self, fixture, run_id, repo=REPO):
        finalize = publication.publish_checkpoint
        args = SimpleNamespace(
            acceptance_fixture=None,
            state_path=None,
            records_command="source",
            source_command="finalize",
            database=str(fixture.database),
            run_id=run_id,
            finalized_at=None,
            repo=repo,
        )
        with (
            patch.object(cli.github, "infer_repo", return_value=repo),
            patch.object(
                publication,
                "publish_checkpoint",
                side_effect=lambda store, **options: finalize(store, adapter=self.github, **options),
            ),
        ):
            return cli._dispatch_records(args)

    def recovered_stop_audit(self, fixture, payload):
        pull = payload["data"]["repository"]["pullRequest"]
        metadata = fixture.records.attempt("current-hosted-attempt")["metadata"]
        base_sha = metadata.get("actual_base_sha", metadata["anchor"]["parent_head"])
        base_ref = metadata.get("actual_base_ref", "develop")
        pull.update({"number": PR, "baseRefName": base_ref, "baseRefOid": base_sha})
        live = SimpleNamespace(
            pull_request=lambda number: SimpleNamespace(
                number=number,
                head_sha=pull["headRefOid"],
                base_sha=base_sha,
                base_ref_name=base_ref,
            ),
            branch_head=lambda _ref: base_sha,
        )
        observer = runtime.LiveEvidence(REPO, live, records=fixture.records)
        anchor = dict(metadata["anchor"])
        anchor.update(
            {
                "pr_base_oid": base_sha,
                "base_ref": base_ref,
                "effective_parent_head": base_sha,
                "enforce_parent_identity_ref": True,
            }
        )
        with (
            patch.object(observer, "_payload", return_value=payload),
            patch.object(observer, "_complete_trigger_paths", return_value=[fixture.recovery_record_path]),
            patch.object(
                runtime.hosted,
                "current_trigger_record_paths",
                return_value=[fixture.recovery_record_path],
            ),
        ):
            return observer.review_stop_audit(PR, anchor)

    def test_recovered_reply_publication_preserves_mixed_case_provider_repository(self):
        repo = "owner/FireMUD"
        fixture, _, run_id = self.recovered_run(repo=repo)
        self.github.repo = repo

        response, status = self.finalize_recovered(fixture, run_id, repo=repo)

        self.assertEqual(status, 0)
        self.assertEqual(response["result"]["publication"]["status"], "published")
        self.assertIn("<!-- firemud-hosted-review: 11 -->", response["result"]["publication"]["body"])
        self.assertEqual(fixture.records.attempt("current-hosted-attempt")["metadata"]["repository"], repo)
        self.assertEqual(fixture.records.history(PR)["provider_origins"][0]["repository"], repo.casefold())

    def test_exact_recovered_zero_run_publishes_through_source_finalize_and_resolves_once(self):
        fixture, payload, run_id = self.recovered_run(resolved_threads=True)
        store = fixture.records
        original_attempt = store.attempt("current-hosted-attempt")
        original_artifacts = store.attempt_artifacts("current-hosted-attempt")
        original_history = store.history(PR)
        recovered_head = original_history["runs"][0]["source_head"]

        response, status = self.finalize_recovered(fixture, run_id)

        publication_result = response["result"]["publication"]
        self.assertEqual(status, 0)
        self.assertEqual(publication_result["status"], "published")
        self.assertEqual(
            publication_result["body"],
            f"Hosted: 0 Found / 0 Accepted / 0 Routed · {recovered_head} · 1 files · 20s\n"
            "<!-- firemud-hosted-review: 11 -->\n<!-- firemud-review-duration-seconds: 20 -->",
        )
        self.assertEqual(store.history(PR)["runs"], original_history["runs"])
        self.assertEqual(len(store.history(PR)["attempts"]), len(original_history["attempts"]))
        current_attempt = store.attempt("current-hosted-attempt")
        for field in (
            "attempt_id",
            "source_pr",
            "channel",
            "candidate_sha",
            "started_at",
            "finished_at",
            "state",
            "run_id",
        ):
            self.assertEqual(current_attempt[field], original_attempt[field], field)
        original_attempt_row = next(
            item for item in original_history["attempts"] if item["attempt_id"] == "current-hosted-attempt"
        )
        current_attempt_row = next(
            item for item in store.history(PR)["attempts"] if item["attempt_id"] == "current-hosted-attempt"
        )
        for field in (
            "started_at",
            "finished_at",
            "duration_seconds",
            "state",
            "run_id",
            "trigger_id",
            "provider_review_id",
            "candidate_sha",
        ):
            self.assertEqual(current_attempt_row[field], original_attempt_row[field], field)
        self.assertEqual(store.attempt_artifacts("current-hosted-attempt"), original_artifacts)
        appended_metadata = copy.deepcopy(current_attempt["metadata"])
        for key in (
            "checkpoint_publication_run_id",
            "checkpoint_publication_intent",
            "checkpoint_publication_events",
        ):
            appended_metadata.pop(key, None)
        self.assertEqual(appended_metadata, original_attempt["metadata"])
        self.assertEqual(store.checkpoint_publication_state(run_id)["intent"]["run_id"], run_id)
        self.assertEqual(
            [event["type"] for event in store.checkpoint_publication_state(run_id)["events"]],
            ["post_started", "post_outcome"],
        )
        repeated, repeated_status = self.finalize_recovered(fixture, run_id)
        self.assertEqual(repeated_status, 0)
        self.assertEqual(repeated["result"]["publication"]["status"], "reused")
        self.assertEqual(self.github.posts, 1)

        public_comment = self.github.comments[0]
        pull = payload["data"]["repository"]["pullRequest"]
        pull["headRefOid"] = recovered_head
        pull["changedFiles"] = 1
        pull["comments"]["nodes"].append(
            {
                "databaseId": public_comment["id"],
                "body": public_comment["body"],
                "createdAt": public_comment["created_at"],
                "updatedAt": public_comment["updated_at"],
                "author": {"login": public_comment["user"]["login"]},
            }
        )
        observer = runtime.LiveEvidence(REPO, None, records=store)
        observer.prefetch_payload(PR, payload)
        parsed, unparsed = evidence.parse_checkpoint_comments(observer._comments(payload))
        self.assertEqual(unparsed, 0)
        target = next(item for item in parsed if item.comment_id == public_comment["id"])
        pubstate = store.checkpoint_publication_state(run_id)
        self.assertEqual(pubstate["intent"]["repository"], REPO)
        self.assertEqual(pubstate["intent"]["source_pr"], PR)
        self.assertEqual(pubstate["intent"]["run_id"], run_id)
        self.assertEqual(pubstate["intent"]["actor_login"], target.author_login)
        self.assertTrue(
            publication._same_checkpoint(
                {
                    "id": target.comment_id,
                    "body": public_comment["body"],
                    "created_at": target.created_at,
                    "issue_url": f"https://api.github.com/repos/{REPO}/issues/{PR}",
                    "user": {"id": pubstate["intent"]["actor_id"], "login": target.author_login},
                },
                pubstate["intent"]["body"],
                repo=REPO,
                source_pr=PR,
            )
        )
        recovered_binding = observer._recovered_hosted_publication(PR, target, public_comment["body"])
        self.assertIsInstance(recovered_binding, dict)
        current_record = runtime.hosted.load_trigger_reservation(fixture.recovery_record_path, REPO, PR)
        current_state = runtime.hosted.trigger_state(
            REPO, PR, payload, current_record, fixture.recovery_record_path
        )
        ambiguous_current_state = dataclasses.replace(
            current_state,
            state="ambiguous",
            terminal=True,
            attributed=False,
            trigger_comment_id=int(recovered_binding["trigger_id"]),
            response_id=recovered_binding["response_id"],
            head_sha=recovered_binding["head"],
            reason="the retained current trigger remains terminally ambiguous",
        )
        with (
            patch.object(
                runtime.hosted, "current_trigger_record_paths", return_value=[fixture.recovery_record_path]
            ),
            patch.object(runtime.hosted, "trigger_state", return_value=ambiguous_current_state),
        ):
            history = observer.history(PR, "hosted")
        self.assertFalse(any(item.get("checkpoint") == "trigger:10" for item in history))
        published = [item for item in history if item.get("comment_id") == public_comment["id"]]
        self.assertEqual(len(published), 1)
        self.assertTrue(published[0]["completed"])
        self.assertTrue(published[0]["attributable"])
        self.assertEqual(published[0]["source_run_id"], run_id)
        self.assertEqual(published[0]["head"], recovered_head)
        self.assertEqual(published[0]["verified_completed_at"], "2026-09-23T00:02:20Z")
        self.assertEqual(published[0]["source_resolution_status"], "resolved")
        original_anchor = published[0]
        allocation = state.ReviewAllocation(
            pr=PR,
            channel="hosted",
            head=recovered_head,
            parent_identity=original_anchor["parent_identity"],
            parent_head=original_anchor["parent_head"],
            merge_base=original_anchor["merge_base"],
            patch_id=original_anchor["patch_id"],
            baseline_checkpoints=(),
            reason="recovered publication fixture",
            min_additional_completed=1,
            max_additional_completed=1,
        )
        allocation_snapshot = controller.ReviewController._bounded_allocation_evidence(allocation, history)
        self.assertIsNone(allocation_snapshot["error"])
        self.assertEqual(len(allocation_snapshot["results"]), 1)
        self.assertEqual(allocation_snapshot["results"][0]["source_run_id"], run_id)
        self.assertEqual(allocation_snapshot["in_flight"], 0)
        self.assertEqual(len(allocation_snapshot["results"]), allocation.max_additional_completed)
        minimum_remaining = max(
            0,
            (allocation.min_additional_completed or 0) - len(allocation_snapshot["results"]),
        )
        self.assertEqual(minimum_remaining, 0)
        review_state = state.ReviewState(ordered_prs=(PR,))
        self.assertFalse(
            any(item.get("held") or item.get("unstable") for item in history),
            repr([item for item in history if item.get("held") or item.get("unstable")]),
        )
        self.assertEqual(
            policy.completion_status(review_state, policy.Channel.HOSTED, history),
            policy.ReviewStatus.COMPLETE,
        )
        stop_audit = self.recovered_stop_audit(fixture, payload)
        self.assertEqual(stop_audit["ambiguous_terminal_responses"], [])
        self.assertEqual(stop_audit["active_reservations"], [])
        self.assertEqual(
            stop_audit["ambiguous_responses"], ["response has no preceding full-review trigger"]
        )
        self.assertIn(
            "ambiguous Hosted response: response has no preceding full-review trigger",
            stop_audit["blockers"],
        )
        self.assertEqual(
            stop_audit["recovered_terminal_responses"],
            [
                {
                    "attempt_id": "current-hosted-attempt",
                    "trigger_id": 10,
                    "response_id": 11,
                    "captured_head": recovered_head,
                    "run_id": run_id,
                    "checkpoint_id": public_comment["id"],
                    "finished_at": "2026-09-23T00:02:20Z",
                }
            ],
        )
        self.assertEqual(store.attempt("current-hosted-attempt"), current_attempt)
        self.assertEqual(store.attempt_artifacts("current-hosted-attempt"), original_artifacts)
        self.assertEqual(store.history(PR)["provider_origins"], original_history["provider_origins"])

    def test_review_stop_audit_keeps_recovered_terminal_ambiguous_without_exact_public_association(self):
        for association in ("missing", "conflicting"):
            with self.subTest(association=association):
                fixture, payload, run_id = self.recovered_run(resolved_threads=True)
                _, status = self.finalize_recovered(fixture, run_id)
                self.assertEqual(status, 0)
                recovered_head = fixture.records.history(PR)["runs"][0]["source_head"]
                pull = payload["data"]["repository"]["pullRequest"]
                pull["headRefOid"] = recovered_head
                pull["changedFiles"] = 1
                if association == "conflicting":
                    public_comment = self.github.comments[0]
                    conflicting_body = public_comment["body"].replace(
                        "<!-- firemud-hosted-review: 11 -->",
                        "<!-- firemud-hosted-review: 12 -->",
                    )
                    pull["comments"]["nodes"].append(
                        {
                            "databaseId": public_comment["id"],
                            "body": conflicting_body,
                            "createdAt": public_comment["created_at"],
                            "updatedAt": public_comment["updated_at"],
                            "author": {"login": public_comment["user"]["login"]},
                        }
                    )

                audit = self.recovered_stop_audit(fixture, payload)
                self.assertEqual(audit["recovered_terminal_responses"], [])
                self.assertEqual(len(audit["ambiguous_terminal_responses"]), 1)
                self.assertEqual(audit["ambiguous_terminal_responses"][0]["trigger_id"], 10)
                self.assertEqual(audit["ambiguous_terminal_responses"][0]["response_id"], 11)
                self.assertEqual(audit["active_reservations"], ["ambiguous"])
                self.assertIn("unattributed trigger response", audit["ambiguous_responses"])

    def test_recovered_public_checkpoint_without_exact_durable_association_is_not_attributed(self):
        fixture, payload, run_id = self.recovered_run()
        _, status = self.finalize_recovered(fixture, run_id)
        self.assertEqual(status, 0)
        public_comment = self.github.comments[0]
        pull = payload["data"]["repository"]["pullRequest"]
        recovered_head = fixture.records.history(PR)["runs"][0]["source_head"]
        pull["headRefOid"] = recovered_head
        pull["changedFiles"] = 1
        pull["comments"]["nodes"].append(
            {
                "databaseId": public_comment["id"],
                "body": public_comment["body"],
                "createdAt": public_comment["created_at"],
                "updatedAt": public_comment["updated_at"],
                "author": {"login": public_comment["user"]["login"]},
            }
        )
        original_state = fixture.records.checkpoint_publication_state(run_id)
        observer = runtime.LiveEvidence(REPO, None, records=fixture.records)
        observer.prefetch_payload(PR, payload)
        current_record = runtime.hosted.load_trigger_reservation(fixture.recovery_record_path, REPO, PR)
        current_state = runtime.hosted.trigger_state(
            REPO, PR, payload, current_record, fixture.recovery_record_path
        )
        ambiguous_current_state = dataclasses.replace(
            current_state,
            state="ambiguous",
            terminal=True,
            attributed=False,
            response_id=11,
            reason="the retained current trigger remains terminally ambiguous",
        )
        with (
            patch.object(
                fixture.records,
                "checkpoint_publication_state",
                return_value={**original_state, "events": []},
            ),
            patch.object(
                runtime.hosted, "current_trigger_record_paths", return_value=[fixture.recovery_record_path]
            ),
            patch.object(runtime.hosted, "trigger_state", return_value=ambiguous_current_state),
            patch.object(observer, "_hosted_trigger_for_checkpoint") as legacy_attribution,
        ):
            history = observer.history(PR, "hosted")
        legacy_attribution.assert_not_called()
        self.assertFalse(any(item.get("comment_id") == public_comment["id"] for item in history))
        unresolved = next(item for item in history if item.get("checkpoint") == "trigger:10")
        self.assertTrue(unresolved["terminal_ambiguous"])
        self.assertTrue(unresolved["held"])

        observer_wrong_head = runtime.LiveEvidence(REPO, None, records=fixture.records)
        observer_wrong_head.prefetch_payload(PR, payload)
        wrong_head_state = dataclasses.replace(ambiguous_current_state, head_sha="b" * 40)
        with (
            patch.object(
                runtime.hosted, "current_trigger_record_paths", return_value=[fixture.recovery_record_path]
            ),
            patch.object(runtime.hosted, "trigger_state", return_value=wrong_head_state),
        ):
            conflicting_history = observer_wrong_head.history(PR, "hosted")
        self.assertTrue(any(item.get("comment_id") == public_comment["id"] for item in conflicting_history))
        conflicting_trigger = next(
            item for item in conflicting_history if item.get("checkpoint") == "trigger:10"
        )
        self.assertTrue(conflicting_trigger["terminal_ambiguous"])

    def test_recovered_matching_readback_and_uncertain_post_bind_without_reposting(self):
        fixture, _, run_id = self.recovered_run()
        body, _ = publication.checkpoint_body(fixture.records, repo=REPO, source_pr=PR, run_id=run_id)
        self.github.uncertain = True
        self.github.hide = True
        with self.assertRaisesRegex(ReviewRecordsError, "retry read recovery later"):
            publication.publish_checkpoint(
                fixture.records, repo=REPO, source_pr=PR, run_id=run_id, adapter=self.github
            )
        self.assertEqual(self.github.posts, 1)
        self.assertEqual(
            [event["type"] for event in fixture.records.checkpoint_publication_state(run_id)["events"]],
            ["post_started", "post_outcome"],
        )
        self.github.hide = False
        self.github.uncertain = False
        result = publication.publish_checkpoint(
            fixture.records, repo=REPO, source_pr=PR, run_id=run_id, adapter=self.github
        )
        self.assertEqual(result["status"], "reused")
        self.assertEqual(result["body"], body)
        self.assertEqual(self.github.posts, 1)
        events = fixture.records.checkpoint_publication_state(run_id)["events"]
        self.assertEqual([event["type"] for event in events], ["post_started", "post_outcome", "reused"])
        self.assertEqual(events[-1]["comment_id"], 789)

    def test_recovered_existing_matching_comment_is_durably_associated_on_first_readback(self):
        fixture, _, run_id = self.recovered_run()
        body, _ = publication.checkpoint_body(fixture.records, repo=REPO, source_pr=PR, run_id=run_id)
        self.github.comments.append(
            {
                "id": 790,
                "body": body,
                "user": self.github.fetch_authenticated_user(),
                "created_at": "2026-09-29T02:00:00Z",
                "updated_at": "2026-09-29T02:00:00Z",
                "issue_url": f"https://api.github.com/repos/{REPO}/issues/{PR}",
                "html_url": f"https://github.com/{REPO}/pull/{PR}#issuecomment-790",
            }
        )
        result = publication.publish_checkpoint(
            fixture.records, repo=REPO, source_pr=PR, run_id=run_id, adapter=self.github
        )
        self.assertEqual(result["status"], "reused")
        self.assertEqual(self.github.posts, 0)
        state = fixture.records.checkpoint_publication_state(run_id)
        self.assertEqual([event["type"] for event in state["events"]], ["reused"])
        self.assertEqual(state["events"][0]["comment_id"], 790)

    def test_generic_import_and_changed_recovery_proofs_cannot_publish(self):
        self.store.import_completed_run(
            run_id="imported-hosted-zero",
            source_pr=PR,
            channel="hosted",
            findings=(),
            source_decisions=(),
            source_head=HEAD,
            started_at="2026-09-29T01:00:00Z",
            finished_at="2026-09-29T01:01:00Z",
            finalized_at="2026-09-29T01:01:00Z",
        )
        with self.assertRaises(ReviewRecordsError):
            publication.checkpoint_body(self.store, repo=REPO, source_pr=PR, run_id="imported-hosted-zero")

        mutations = ("origin", "head", "counts", "archive", "time", "files")
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                fixture, _, run_id = self.recovered_run()
                store = fixture.records
                if mutation in {"origin", "head", "counts"}:
                    original = store.history

                    def changed_history(pr, _original=original, _run_id=run_id, _mutation=mutation, **kwargs):
                        value = _original(pr, **kwargs)
                        target = next(item for item in value["runs"] if item["run_id"] == _run_id)
                        if _mutation == "origin":
                            value["provider_origins"][0]["provider_id"] = "review:11"
                        elif _mutation == "head":
                            target["source_head"] = "b" * 40
                        else:
                            target["counts"] = {"found": 1, "accepted": 0, "routed": 0}
                        return value

                    with (
                        patch.object(store, "history", side_effect=changed_history),
                        self.assertRaises(ReviewRecordsError),
                    ):
                        publication.checkpoint_body(store, repo=REPO, source_pr=PR, run_id=run_id)
                elif mutation == "archive":
                    original = store.imported_run_artifacts

                    def changed_archive(selected_run_id, _original=original):
                        value = _original(selected_run_id)
                        damaged = json.loads(value["metadata"]["content"])
                        damaged["head_sha"] = "b" * 40
                        value["metadata"]["content"] = json.dumps(damaged, sort_keys=True)
                        return value

                    with (
                        patch.object(store, "imported_run_artifacts", side_effect=changed_archive),
                        self.assertRaises(ReviewRecordsError),
                    ):
                        publication.checkpoint_body(store, repo=REPO, source_pr=PR, run_id=run_id)
                elif mutation == "time":
                    original = store.history

                    def changed_time(pr, _original=original, **kwargs):
                        value = _original(pr, **kwargs)
                        target = next(
                            item
                            for item in value["attempts"]
                            if item["attempt_id"] == "current-hosted-attempt"
                        )
                        target["finished_at"] = "2026-09-23T00:02:07Z"
                        return value

                    with (
                        patch.object(store, "history", side_effect=changed_time),
                        self.assertRaises(ReviewRecordsError),
                    ):
                        publication.checkpoint_body(store, repo=REPO, source_pr=PR, run_id=run_id)
                else:
                    original = store.attempt

                    def changed_files(attempt_id, _original=original, **kwargs):
                        value = _original(attempt_id, **kwargs)
                        if attempt_id == "current-hosted-attempt":
                            value["metadata"]["reviewed_file_count"] = 0
                        return value

                    with (
                        patch.object(store, "attempt", side_effect=changed_files),
                        self.assertRaises(ReviewRecordsError),
                    ):
                        publication.checkpoint_body(store, repo=REPO, source_pr=PR, run_id=run_id)

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
            with self.subTest(category=category), patch.object(github.subprocess, "run", side_effect=error):
                with self.assertRaises(github.CommentPostError) as caught:
                    github.post_issue_comment(REPO, PR, secret)
                self.assertEqual((caught.exception.category, caught.exception.http_status), (category, status))
                self.assertNotIn(secret, str(caught.exception))
                self.assertNotIn(secret, repr(caught.exception.__dict__))
        for output in ("not json " + secret, "[]"):
            with patch.object(github.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, output)):
                with self.assertRaises(github.CommentPostError) as caught:
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
