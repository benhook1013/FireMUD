from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import hosted, sqlite_hosted_capture, sqlite_review_records, sqlite_store  # noqa: I001


REPO = "owner/repo"
PR = 42
HEAD = "abcdef0123456789abcdef0123456789abcdef01"
TRIGGER_AT = "2026-09-29T01:00:00Z"


class SqliteHostedCaptureTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.database = Path(self.temporary_directory.name) / "controller.sqlite3"
        sqlite_store.SqliteStateStore(self.database).update(lambda state: state)
        self.records = sqlite_review_records.SqliteReviewRecords(self.database)
        self.records.bootstrap()
        self.attempt_id = "hosted-attempt-101"
        sqlite_hosted_capture.start_hosted_attempt(
            self.records,
            attempt_id=self.attempt_id,
            source_pr=PR,
            candidate_sha=HEAD,
            started_at=TRIGGER_AT,
            metadata={"repository": REPO},
        )

    @staticmethod
    def trigger_record(
        *,
        pr: int = PR,
        head: str = HEAD,
        trigger_id: int = 101,
        created_at: str = TRIGGER_AT,
        posting_started_at: str = TRIGGER_AT,
        attempt_id: str | None = None,
    ) -> dict[str, object]:
        record: dict[str, object] = {
            "schema_version": 1,
            "status": "posted",
            "repository": REPO,
            "pr_number": pr,
            "head_sha": head,
            "posting_started_at": posting_started_at,
            "trigger": {
                "id": trigger_id,
                "created_at": created_at,
                "url": f"https://github.example/owner/repo/pull/{pr}#issuecomment-{trigger_id}",
                "author_login": "maintainer",
                "type": "full",
                "command": hosted.FULL_COMMAND,
            },
        }
        if attempt_id is not None:
            record["sqlite_attempt_id"] = attempt_id
        return record

    @staticmethod
    def payload(
        *,
        comments: list[dict[str, object]],
        reviews: list[dict[str, object]] | None = None,
        review_threads: list[dict[str, object]] | None = None,
        pr: int = PR,
        head: str = HEAD,
    ) -> dict[str, object]:
        return {
            "data": {
                "repository": {
                    "pullRequest": {
                        "number": pr,
                        "headRefOid": head,
                        "comments": {"nodes": comments},
                        "reviews": {"nodes": reviews or []},
                        "reviewThreads": {"nodes": review_threads or []},
                    }
                }
            }
        }

    @staticmethod
    def trigger_comment(
        *, pr: int = PR, trigger_id: int = 101, created_at: str = TRIGGER_AT
    ) -> dict[str, object]:
        return {
            "databaseId": trigger_id,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": created_at,
            "updatedAt": created_at,
            "url": f"https://github.example/owner/repo/pull/{pr}#issuecomment-{trigger_id}",
        }

    @staticmethod
    def write_trigger_record(common: Path, record: dict[str, object], *, archived: bool = False) -> Path:
        pr = int(record["pr_number"])
        trigger = record["trigger"]
        trigger_id = int(trigger["id"])
        current = hosted.default_trigger_record_path(REPO, pr, common)
        current.parent.mkdir(parents=True, exist_ok=True)
        path = current.with_name(f"trigger-{trigger_id}.json") if archived else current
        path.write_text(json.dumps(record), encoding="utf-8")
        return path

    @staticmethod
    def new_records(path: Path) -> sqlite_review_records.SqliteReviewRecords:
        sqlite_store.SqliteStateStore(path).update(lambda state: state)
        records = sqlite_review_records.SqliteReviewRecords(path)
        records.bootstrap()
        return records

    def test_edited_zero_finding_completion_is_attributed_and_archived(self) -> None:
        summary = {
            "databaseId": 103,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "No actionable comments were generated in the recent review.\n"
                f"Reviewing files that changed from the base of the PR and between {HEAD[:12]} and {HEAD}."
            ),
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
        }
        edited_reply = {
            "databaseId": 102,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": "2026-09-29T01:01:00Z",
            "updatedAt": "2026-09-29T01:04:00Z",
            "url": "https://github.example/owner/repo/pull/42#issuecomment-102",
        }
        payload = self.payload(comments=[self.trigger_comment(), edited_reply, summary])

        captured = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=payload,
            observed_at="2026-09-29T01:05:00Z",
        )
        replay_payload = json.loads(json.dumps(payload))
        replay_payload["data"]["repository"]["pullRequest"]["comments"]["nodes"][1][
            "updatedAt"
        ] = "2026-09-29T01:06:00Z"
        replay = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=replay_payload,
            observed_at="2026-09-29T01:07:00Z",
        )

        self.assertEqual(captured["state"], "completed")
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(captured["response_id"], 102)
        self.assertEqual(captured["counts"], {"found": 0, "accepted": 0, "routed": 0})
        self.assertEqual(captured["run_id"], self.attempt_id)
        history = self.records.history(PR)
        self.assertEqual(len(history["runs"]), 1)
        self.assertTrue(history["runs"][0]["finalized"])
        self.assertEqual(history["runs"][0]["finished_at"], edited_reply["updatedAt"])
        attempt = self.records.attempt_history(PR)[0]
        self.assertEqual(attempt["state"], "completed")
        self.assertEqual(attempt["trigger_id"], "101")
        self.assertEqual(attempt["provider_review_id"], "102")
        self.assertEqual(attempt["checkpoint_id"], None)

        with sqlite3.connect(self.database) as connection:
            artifacts = dict(
                connection.execute(
                    "SELECT kind, content FROM review_artifacts WHERE attempt_id = ?",
                    (self.attempt_id,),
                ).fetchall()
            )
        self.assertEqual(json.loads(artifacts["hosted_review"]), [])
        comments_archive = json.loads(artifacts["hosted_comments"])
        self.assertEqual(comments_archive["comments"][1]["updatedAt"], edited_reply["updatedAt"])
        self.assertEqual(comments_archive["review_threads"], [])
        self.assertEqual(json.loads(artifacts["metadata"])["response_id"], 102)

    def test_completed_review_rolls_back_attempt_when_run_write_fails(self) -> None:
        reply = {
            "databaseId": 102,
            "author": {"login": "coderabbitai"},
            "body": "Full review finished.",
            "createdAt": "2026-09-29T01:01:00Z",
            "updatedAt": "2026-09-29T01:04:00Z",
            "url": "https://github.example/owner/repo/pull/42#issuecomment-102",
        }
        summary = {
            "databaseId": 103,
            "author": {"login": "coderabbitai[bot]"},
            "body": (
                "No actionable comments were generated in the recent review.\n"
                f"Reviewing files that changed from the base of the PR and between {HEAD[:12]} and {HEAD}."
            ),
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
        }
        payload = self.payload(comments=[self.trigger_comment(), reply, summary])
        args = {
            "attempt_id": self.attempt_id,
            "repo": REPO,
            "source_pr": PR,
            "trigger_record": self.trigger_record(),
            "payload": payload,
            "observed_at": "2026-09-29T01:05:00Z",
        }
        with (
            patch.object(self.records, "record_run", side_effect=RuntimeError("injected run failure")),
            self.assertRaisesRegex(RuntimeError, "injected run failure"),
        ):
            sqlite_hosted_capture.record_hosted_terminal_result(self.records, **args)
        self.assertEqual(self.records.attempt(self.attempt_id)["state"], "started")
        self.assertEqual(self.records.history(PR)["runs"], [])
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("SELECT COUNT(*) FROM review_artifacts").fetchone()[0], 0)

        captured = sqlite_hosted_capture.record_hosted_terminal_result(self.records, **args)
        self.assertEqual(captured["state"], "completed")
        self.assertEqual(self.records.attempt(self.attempt_id)["run_id"], self.attempt_id)

    def test_completed_review_records_its_attributed_inline_finding(self) -> None:
        review = {
            "databaseId": 201,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-29T01:03:00Z",
            "commit": {"oid": HEAD},
            "customField": "preserved review JSON",
        }
        finding_comment = {
            "databaseId": 202,
            "author": {"login": "coderabbitai[bot]"},
            "body": "**Check the boundary before using this value.**\nMore detail.",
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
            "url": "https://github.example/owner/repo/pull/42#discussion_r202",
            "customField": "preserved comment JSON",
        }
        thread = {
            "id": "PRRT_thread_1",
            "isResolved": False,
            "isOutdated": False,
            "path": "src/example.py",
            "comments": {"nodes": [finding_comment]},
        }
        payload = self.payload(
            comments=[self.trigger_comment()],
            reviews=[review],
            review_threads=[thread],
        )

        captured = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=payload,
        )

        self.assertEqual(captured["state"], "completed")
        self.assertEqual(captured["counts"]["found"], 1)
        run = self.records.history(PR)["runs"][0]
        self.assertFalse(run["finalized"])
        finding = self.records.history(PR)["findings"][0]
        self.assertEqual(finding["source_finding_key"], "hosted-trigger:101:comment:202")
        self.assertEqual(finding["title"], "**Check the boundary before using this value.**")

        with sqlite3.connect(self.database) as connection:
            artifacts = dict(
                connection.execute(
                    "SELECT kind, content FROM review_artifacts WHERE attempt_id = ?",
                    (self.attempt_id,),
                ).fetchall()
            )
        self.assertEqual(json.loads(artifacts["hosted_review"])[0]["customField"], "preserved review JSON")
        archived_thread = json.loads(artifacts["hosted_comments"])["review_threads"][0]
        self.assertEqual(archived_thread["comments"]["nodes"][0]["customField"], "preserved comment JSON")

    def test_nonterminal_observation_keeps_the_attempt_open(self) -> None:
        active = {
            "databaseId": 104,
            "author": {"login": "coderabbitai[bot]"},
            "body": "Full review triggered.",
            "createdAt": "2026-09-29T01:01:00Z",
            "updatedAt": "2026-09-29T01:01:00Z",
        }

        captured = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=self.payload(comments=[self.trigger_comment(), active]),
        )

        self.assertFalse(captured["terminal"])
        self.assertEqual(captured["state"], "active")
        self.assertEqual(self.records.attempt_history(PR)[0]["state"], "started")
        with sqlite3.connect(self.database) as connection:
            artifact_count = connection.execute(
                "SELECT COUNT(*) FROM review_artifacts WHERE attempt_id = ?", (self.attempt_id,)
            ).fetchone()[0]
        self.assertEqual(artifact_count, 0)

    def test_rate_limit_and_ambiguous_terminal_results_do_not_create_source_runs(self) -> None:
        scenarios = (
            (
                "hosted-rate-limit",
                [
                    {
                        "databaseId": 301,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": "Review rate limited. Next reviews available in: 20 minutes.",
                        "createdAt": "2026-09-29T01:01:00Z",
                        "updatedAt": "2026-09-29T01:01:00Z",
                    }
                ],
                "rate_limited",
            ),
            (
                "hosted-ambiguous",
                [
                    {
                        "databaseId": 302,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": "Full review finished.",
                        "createdAt": "2026-09-29T01:01:00Z",
                        "updatedAt": "2026-09-29T01:01:00Z",
                    },
                    {
                        "databaseId": 303,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": "Additional provider output without a head-bound result.",
                        "createdAt": "2026-09-29T01:02:00Z",
                        "updatedAt": "2026-09-29T01:02:00Z",
                    },
                ],
                "ambiguous",
            ),
        )

        for attempt_id, responses, expected_state in scenarios:
            with self.subTest(state=expected_state):
                trigger_record = self.trigger_record()
                trigger_record["sqlite_attempt_id"] = attempt_id
                sqlite_hosted_capture.start_hosted_attempt(
                    self.records,
                    attempt_id=attempt_id,
                    source_pr=PR,
                    candidate_sha=HEAD,
                    started_at=TRIGGER_AT,
                )
                payload = self.payload(comments=[self.trigger_comment(), *responses])
                captured = sqlite_hosted_capture.record_hosted_terminal_result(
                    self.records,
                    attempt_id=attempt_id,
                    repo=REPO,
                    source_pr=PR,
                    trigger_record=trigger_record,
                    payload=payload,
                    observed_at="2026-09-29T01:03:00Z",
                )
                replay = sqlite_hosted_capture.record_hosted_terminal_result(
                    self.records,
                    attempt_id=attempt_id,
                    repo=REPO,
                    source_pr=PR,
                    trigger_record=trigger_record,
                    payload=payload,
                    observed_at="2026-09-29T01:03:00Z",
                )

                self.assertTrue(captured["terminal"])
                self.assertEqual(captured["state"], expected_state)
                self.assertTrue(replay["idempotent_replay"])
                attempt = next(
                    item for item in self.records.attempt_history(PR) if item["attempt_id"] == attempt_id
                )
                self.assertEqual(attempt["state"], expected_state)
                self.assertIsNone(attempt["run_id"])

        self.assertEqual(self.records.history(PR)["runs"], [])

    def test_sync_backfills_missing_attempt_and_skips_linked_replay(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory) / "git-common"
            records = self.new_records(Path(directory) / "controller.sqlite3")
            attempt_id = "hosted-sync-zero"
            started_at = "2026-09-29T00:59:00Z"
            trigger_at = "2026-09-29T01:00:00Z"
            trigger_record = self.trigger_record(
                trigger_id=501,
                created_at=trigger_at,
                posting_started_at=started_at,
                attempt_id=attempt_id,
            )
            self.write_trigger_record(common, trigger_record)
            edited_reply = {
                "databaseId": 502,
                "author": {"login": "coderabbitai"},
                "body": "Full review finished.",
                "createdAt": "2026-09-29T01:01:00Z",
                "updatedAt": "2026-09-29T01:04:00Z",
                "url": "https://github.example/owner/repo/pull/42#issuecomment-502",
            }
            summary = {
                "databaseId": 503,
                "author": {"login": "coderabbitai[bot]"},
                "body": (
                    "No actionable comments were generated in the recent review.\n"
                    f"Reviewing files that changed from the base of the PR and between {HEAD[:12]} and {HEAD}."
                ),
                "createdAt": "2026-09-29T01:02:00Z",
                "updatedAt": "2026-09-29T01:02:00Z",
            }
            payload = self.payload(
                comments=[self.trigger_comment(trigger_id=501, created_at=trigger_at), edited_reply, summary]
            )

            with patch.object(
                sqlite_hosted_capture.github, "fetch_pull_request", return_value=payload
            ) as fetch:
                first = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)
            fetch.assert_called_once_with(REPO, PR)

            self.assertEqual(len(first["synced"]), 1)
            self.assertEqual(first["synced"][0]["attempt_id"], attempt_id)
            self.assertEqual(first["synced"][0]["state"], "completed")
            self.assertEqual(first["synced"][0]["counts"], {"found": 0, "accepted": 0, "routed": 0})
            attempt = records.attempt(attempt_id)
            self.assertEqual(attempt["started_at"], started_at)
            self.assertEqual(attempt["metadata"]["repository"], REPO)
            self.assertEqual(records.history(PR)["runs"][0]["run_id"], attempt_id)
            self.assertTrue(records.history(PR)["runs"][0]["finalized"])

            with patch.object(
                sqlite_hosted_capture.github,
                "fetch_pull_request",
                side_effect=AssertionError("linked replay must not refetch GitHub"),
            ) as replay_fetch:
                replay = sqlite_hosted_capture.sync_hosted_pending(
                    records, REPO, common=common, pr_number=PR
                )
            replay_fetch.assert_not_called()
            self.assertTrue(replay["synced"], replay)
            self.assertTrue(replay["synced"][0]["idempotent_replay"])

    def test_sync_keeps_active_pending_and_records_rate_limit(self) -> None:
        scenarios = (
            (
                "active",
                601,
                "Full review triggered. I am reviewing the pull request now.",
                "pending",
                "active",
                "started",
            ),
            (
                "rate-limit",
                602,
                "Review rate limited. Next reviews available in: 20 minutes.",
                "synced",
                "rate_limited",
                "rate_limited",
            ),
        )
        for suffix, trigger_id, response_body, bucket, expected_report_state, expected_attempt_state in scenarios:
            with self.subTest(state=expected_attempt_state), tempfile.TemporaryDirectory() as directory:
                common = Path(directory) / "git-common"
                records = self.new_records(Path(directory) / "controller.sqlite3")
                attempt_id = f"hosted-sync-{suffix}"
                started_at = "2026-09-29T00:59:00Z"
                trigger_at = "2026-09-29T01:00:00Z"
                trigger_record = self.trigger_record(
                    trigger_id=trigger_id,
                    created_at=trigger_at,
                    posting_started_at=started_at,
                    attempt_id=attempt_id,
                )
                self.write_trigger_record(common, trigger_record)
                sqlite_hosted_capture.start_hosted_attempt(
                    records,
                    attempt_id=attempt_id,
                    source_pr=PR,
                    candidate_sha=HEAD,
                    started_at=started_at,
                    metadata={"repository": REPO},
                )
                response = {
                    "databaseId": trigger_id + 100,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": response_body,
                    "createdAt": "2026-09-29T01:01:00Z",
                    "updatedAt": "2026-09-29T01:01:00Z",
                }
                payload = self.payload(
                    comments=[self.trigger_comment(trigger_id=trigger_id, created_at=trigger_at), response]
                )

                with patch.object(
                    sqlite_hosted_capture.github, "fetch_pull_request", return_value=payload
                ) as fetch:
                    report = sqlite_hosted_capture.sync_hosted_pending(
                        records, REPO, common=common, pr_number=PR
                    )

                fetch.assert_called_once_with(REPO, PR)
                self.assertEqual(len(report[bucket]), 1)
                self.assertEqual(report[bucket][0]["state"], expected_report_state)
                self.assertEqual(records.attempt(attempt_id)["state"], expected_attempt_state)
                self.assertEqual(records.history(PR)["runs"], [])

    def test_sync_isolates_bad_records_and_reuses_one_payload_per_pr(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory) / "git-common"
            records = self.new_records(Path(directory) / "controller.sqlite3")
            requests = (
                ("hosted-sync-first", 801, "2026-09-29T00:59:00Z", "2026-09-29T01:00:00Z"),
                ("hosted-sync-second", 802, "2026-09-29T01:59:00Z", "2026-09-29T02:00:00Z"),
            )
            for attempt_id, trigger_id, started_at, created_at in requests:
                record = self.trigger_record(
                    trigger_id=trigger_id,
                    created_at=created_at,
                    posting_started_at=started_at,
                    attempt_id=attempt_id,
                )
                self.write_trigger_record(common, record, archived=trigger_id == 801)
                sqlite_hosted_capture.start_hosted_attempt(
                    records,
                    attempt_id=attempt_id,
                    source_pr=PR,
                    candidate_sha=HEAD,
                    started_at=started_at,
                    metadata={"repository": REPO},
                )

            malformed = self.trigger_record(
                trigger_id=803,
                created_at="2026-09-29T03:00:00Z",
                posting_started_at="not-a-time",
                attempt_id="hosted-sync-malformed",
            )
            self.write_trigger_record(common, malformed, archived=True)

            third_pr_record = self.trigger_record(
                pr=43,
                trigger_id=901,
                created_at="2026-09-29T01:00:00Z",
                posting_started_at="2026-09-29T00:59:00Z",
                attempt_id="hosted-sync-fetch-failure",
            )
            self.write_trigger_record(common, third_pr_record)
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id="hosted-sync-fetch-failure",
                source_pr=43,
                candidate_sha=HEAD,
                started_at="2026-09-29T00:59:00Z",
                metadata={"repository": REPO},
            )

            payload = self.payload(
                comments=[
                    self.trigger_comment(trigger_id=801, created_at="2026-09-29T01:00:00Z"),
                    self.trigger_comment(trigger_id=802, created_at="2026-09-29T02:00:00Z"),
                ],
                reviews=[
                    {
                        "databaseId": 811,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
                        "state": "COMMENTED",
                        "submittedAt": "2026-09-29T01:30:00Z",
                        "commit": {"oid": HEAD},
                    },
                    {
                        "databaseId": 812,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
                        "state": "COMMENTED",
                        "submittedAt": "2026-09-29T02:30:00Z",
                        "commit": {"oid": HEAD},
                    },
                ],
            )
            fetched: list[tuple[str, int]] = []

            def fetch_pr(selected_repo: str, selected_pr: int) -> dict[str, object]:
                fetched.append((selected_repo, selected_pr))
                if selected_pr == 43:
                    raise RuntimeError("injected history failure")
                return payload

            with patch.object(
                sqlite_hosted_capture.github, "fetch_pull_request", side_effect=fetch_pr
            ):
                report = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common)

            self.assertEqual(fetched, [(REPO, PR), (REPO, 43)])
            self.assertEqual({item["state"] for item in report["synced"]}, {"completed"})
            self.assertEqual(len(report["synced"]), 2)
            self.assertEqual(len(report["errors"]), 2)
            self.assertEqual(records.attempt("hosted-sync-first")["state"], "completed")
            self.assertEqual(records.attempt("hosted-sync-second")["state"], "completed")
            self.assertEqual(records.attempt("hosted-sync-fetch-failure")["state"], "started")

    def test_sync_reports_partial_completed_attempt_without_rewriting_it(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory) / "git-common"
            records = self.new_records(Path(directory) / "controller.sqlite3")
            attempt_id = "hosted-sync-partial"
            started_at = "2026-09-29T00:59:00Z"
            record = self.trigger_record(
                trigger_id=951,
                created_at="2026-09-29T01:00:00Z",
                posting_started_at=started_at,
                attempt_id=attempt_id,
            )
            self.write_trigger_record(common, record)
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id=attempt_id,
                source_pr=PR,
                candidate_sha=HEAD,
                started_at=started_at,
                metadata={"repository": REPO},
            )
            records.finish_attempt(
                attempt_id,
                state="completed",
                finished_at="2026-09-29T01:05:00Z",
                trigger_id="951",
                provider_review_id="952",
            )

            with patch.object(
                sqlite_hosted_capture.github,
                "fetch_pull_request",
                side_effect=AssertionError("partial completed attempt must not be rewritten"),
            ) as fetch:
                report = sqlite_hosted_capture.sync_hosted_pending(
                    records, REPO, common=common, pr_number=PR
                )

            fetch.assert_not_called()
            self.assertEqual(len(report["errors"]), 1)
            self.assertIn("lacks exact archived evidence", report["errors"][0]["error"])
            self.assertEqual(records.attempt(attempt_id)["state"], "completed")
            self.assertIsNone(records.attempt(attempt_id)["run_id"])
            self.assertEqual(records.history(PR)["runs"], [])

    def test_sync_recovers_partial_completed_attempt_from_its_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory) / "git-common"
            records = self.new_records(Path(directory) / "controller.sqlite3")
            attempt_id = "hosted-sync-recover"
            started_at = "2026-09-29T00:59:00Z"
            record = self.trigger_record(
                trigger_id=951, created_at="2026-09-29T01:00:00Z",
                posting_started_at=started_at, attempt_id=attempt_id,
            )
            self.write_trigger_record(common, record)
            sqlite_hosted_capture.start_hosted_attempt(
                records, attempt_id=attempt_id, source_pr=PR, candidate_sha=HEAD,
                started_at=started_at, metadata={"repository": REPO},
            )
            reply = {
                "databaseId": 952, "author": {"login": "coderabbitai"},
                "body": "Full review finished.",
                "createdAt": "2026-09-29T01:01:00Z",
                "updatedAt": "2026-09-29T01:05:00Z",
                "url": "https://github.example/owner/repo/pull/42#issuecomment-952",
            }
            summary = {
                "databaseId": 953, "author": {"login": "coderabbitai[bot]"},
                "body": (
                    "No actionable comments were generated in the recent review.\n"
                    f"Reviewing files that changed from the base of the PR and between {HEAD[:12]} and {HEAD}."
                ),
                "createdAt": "2026-09-29T01:02:00Z",
                "updatedAt": "2026-09-29T01:02:00Z",
            }
            comments = [self.trigger_comment(trigger_id=951), reply, summary]
            metadata = {
                "state": "completed", "terminal": True, "attributable": True,
                "reason": "", "repository": REPO, "pull_request": PR,
                "head_sha": HEAD, "trigger_id": 951, "response_id": 952,
                "observed_at": "2026-09-29T01:05:00Z",
            }
            records.finish_attempt(
                attempt_id, state="completed", finished_at="2026-09-29T01:05:00Z",
                trigger_id="951", provider_review_id="952",
                artifacts={
                    "hosted_review": json.dumps([]),
                    "hosted_comments": json.dumps({"comments": comments, "review_threads": []}),
                    "metadata": json.dumps(metadata),
                },
            )

            with patch.object(
                sqlite_hosted_capture.github, "fetch_pull_request",
                side_effect=AssertionError("archived recovery does not need live GitHub"),
            ) as fetch:
                report = sqlite_hosted_capture.sync_hosted_pending(
                    records, REPO, common=common, pr_number=PR
                )
            fetch.assert_not_called()
            self.assertEqual(report["errors"], [])
            self.assertEqual(len(report["synced"]), 1)
            self.assertTrue(report["synced"][0]["recovered_from_archive"])
            self.assertEqual(records.attempt(attempt_id)["run_id"], attempt_id)
            self.assertEqual(records.history(PR)["runs"][0]["counts"]["found"], 0)

    def test_incomplete_github_page_is_refused_without_finishing_attempt(self) -> None:
        payload = self.payload(comments=[self.trigger_comment()])
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["reviews"]["pageInfo"] = {"hasNextPage": True, "endCursor": "cursor"}

        with self.assertRaisesRegex(sqlite_hosted_capture.HostedCaptureError, "incomplete pages"):
            sqlite_hosted_capture.record_hosted_terminal_result(
                self.records,
                attempt_id=self.attempt_id,
                repo=REPO,
                source_pr=PR,
                trigger_record=self.trigger_record(),
                payload=payload,
            )

        self.assertEqual(self.records.attempt_history(PR)[0]["state"], "started")


if __name__ == "__main__":
    unittest.main()
