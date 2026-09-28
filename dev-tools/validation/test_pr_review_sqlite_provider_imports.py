from __future__ import annotations

import dataclasses
import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

import pr_review.evidence  # noqa: I001
import pr_review.sqlite_provider_imports
import pr_review.sqlite_review_records
import pr_review.sqlite_store
from pr_review.state import ControllerStateStore, FindingRoute, SummaryFindingDisposition, state_path


HEAD = "abcdef0123456789abcdef0123456789abcdef01"
REPO = "owner/repo"
PR = 42


class SqliteProviderImportsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.common = Path(self.temporary_directory.name)
        self.database = self.common / "controller.sqlite3"
        pr_review.sqlite_store.SqliteStateStore(self.database).update(lambda state: state)
        self.records = pr_review.sqlite_review_records.SqliteReviewRecords(self.database)
        self.records.bootstrap()

    @staticmethod
    def checkpoint(
        channel: str,
        marker: str,
        *,
        raw: int = 1,
        accepted: int = 1,
        routed: int | None = 0,
    ):
        counts = f"{raw} found / {accepted} accepted"
        if routed is not None:
            counts += f" / {routed} routed"
        suffix = f"{channel}: {counts} · {HEAD[:12]} · 1 files"
        comments, unparsed = pr_review.evidence.parse_checkpoint_comments(
            [{"id": 10, "body": suffix + "\n" + marker, "created_at": "2026-09-27T12:00:00Z"}]
        )
        if unparsed or len(comments) != 1:
            raise AssertionError("fixture checkpoint did not parse canonically")
        return comments[0]

    def hosted_capture(
        self,
        review_id: int = 700,
        *,
        state: str = "COMMENTED",
        review_body: str | None = None,
        finding_body: str = "Use the checked value before dereferencing it.",
        decision_text: str = "701\taccepted\tvalid source finding\n",
    ) -> None:
        capture_dir = self.common / "firemud" / f"hosted-review.{review_id}"
        capture_dir.mkdir(parents=True)
        snapshot = {
            "source": "hosted",
            "repository": REPO,
            "pull_request": PR,
            "review": {
                "id": review_id,
                "user": {"login": "coderabbitai[bot]"},
                "state": state,
                "submitted_at": "2026-09-27T11:59:00Z",
                "commit_id": HEAD,
            },
            "comments": [
                {
                    "id": 701,
                    "pull_request_review_id": review_id,
                    "in_reply_to_id": None,
                    "user": {"login": "coderabbitai[bot]"},
                    "body": finding_body,
                    "created_at": "2026-09-27T11:58:00Z",
                }
            ],
        }
        if review_body is not None:
            snapshot["review"]["body"] = review_body
        (capture_dir / "snapshot.json").write_text(json.dumps(snapshot), encoding="utf-8")
        (capture_dir / "decisions.tsv").write_text(decision_text, encoding="utf-8")

    def cli_capture(self, run_id: str = "run.Importer") -> None:
        capture_dir = self.common / "coderabbit-review-logs" / run_id
        capture_dir.mkdir(parents=True)
        (capture_dir / "metadata").write_text(
            f"run_id={run_id}\nrepository={REPO}\npull_request={PR}\ncandidate_sha={HEAD}\ncandidate_files=1\n",
            encoding="utf-8",
        )
        events = [
            {
                "type": "finding",
                "codegenInstructions": (
                    "Validate the route before using it.\n"
                    "Then replace the surrounding control flow and update the caller."
                ),
            },
            {"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a.py"]},
        ]
        (capture_dir / "stdout").write_text("\n".join(json.dumps(event) for event in events) + "\n", encoding="utf-8")
        (capture_dir / "exit-status").write_text("0\n", encoding="utf-8")
        (capture_dir / "decisions.tsv").write_text("1\trouted\towner PR #2879\n", encoding="utf-8")

    def test_hosted_import_replay_is_idempotent_and_keeps_raw_finding_and_decision(self) -> None:
        self.hosted_capture()
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")

        first = pr_review.sqlite_provider_imports.import_hosted_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="reviewer",
            common=self.common,
            scope="broad",
        )
        replay = pr_review.sqlite_provider_imports.import_hosted_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="reviewer",
            common=self.common,
            scope="broad",
        )

        history = self.records.history(PR)
        self.assertEqual(first["counts"], {"found": 1, "accepted": 1, "routed": 0})
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(len(history["runs"]), 1)
        self.assertEqual(history["runs"][0]["started_at"], "2026-09-27T11:59:00Z")
        self.assertEqual(history["runs"][0]["finished_at"], "2026-09-27T11:59:00Z")
        self.assertEqual(len(history["findings"]), 1)
        self.assertEqual(history["findings"][0]["title"], "Use the checked value before dereferencing it.")
        self.assertEqual(len(history["decisions"]), 1)
        self.assertEqual(history["decisions"][0]["reason"], "valid source finding")

    def test_hosted_import_prefers_bold_actionable_headline_over_badge(self) -> None:
        self.hosted_capture(
            finding_body=(
                "**[P1] Bug**\n\n"
                "**Validate the current route target before recording the decision.**\n\n"
                "The target can change after this row is read."
            )
        )
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")

        pr_review.sqlite_provider_imports.import_hosted_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="reviewer",
            common=self.common,
            scope="broad",
        )

        self.assertEqual(
            self.records.history(PR)["findings"][0]["title"],
            "Validate the current route target before recording the decision.",
        )
        self.assertEqual(
            pr_review.sqlite_provider_imports._first_line("**[P1] Bug**\nA non-bold explanation follows."),
            "A non-bold explanation follows.",
        )

    def test_cli_import_replay_records_provider_finding_and_route_reason(self) -> None:
        self.cli_capture()
        stdout_path = self.common / "coderabbit-review-logs" / "run.Importer" / "stdout"
        stdout_before = stdout_path.read_bytes()
        checkpoint = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Importer -->", accepted=0, routed=1)

        first = pr_review.sqlite_provider_imports.import_cli_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="reviewer",
            common=self.common,
            scope="broad",
        )
        replay = pr_review.sqlite_provider_imports.import_cli_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="reviewer",
            common=self.common,
            scope="broad",
        )

        history = self.records.history(PR)
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(history["runs"][0]["channel"], "cli")
        self.assertEqual(history["runs"][0]["started_at"], checkpoint.created_at)
        self.assertEqual(history["runs"][0]["finished_at"], checkpoint.created_at)
        self.assertEqual(history["findings"][0]["title"], "Validate the route before using it.")
        self.assertNotIn("Then replace the surrounding control flow", history["findings"][0]["title"])
        self.assertEqual(history["findings"][0]["disposition"], "routed")
        self.assertEqual(history["decisions"][0]["reason"], "owner PR #2879")
        self.assertEqual(stdout_path.read_bytes(), stdout_before)
        routes = self.records.open_routes()
        self.assertEqual(len(routes), 1)
        self.assertIsNone(routes[0]["target_pr"])

    def test_legacy_two_count_checkpoints_import_routed_hosted_and_cli_findings(self) -> None:
        self.hosted_capture(decision_text="701\trouted\towned by another PR\n")
        hosted = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", accepted=0, routed=None)
        hosted_result = pr_review.sqlite_provider_imports.import_hosted_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=hosted,
            actor="reviewer",
            common=self.common,
            scope="broad",
        )

        self.cli_capture()
        cli = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Importer -->", accepted=0, routed=None)
        cli_result = pr_review.sqlite_provider_imports.import_cli_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=cli,
            actor="reviewer",
            common=self.common,
            scope="broad",
        )

        self.assertIsNone(hosted.routed)
        self.assertEqual(hosted_result["counts"], {"found": 1, "accepted": 0, "routed": 1})
        self.assertIsNone(cli.routed)
        self.assertEqual(cli_result["counts"], {"found": 1, "accepted": 0, "routed": 1})

    def test_explicit_modern_routed_counts_remain_strict_for_hosted_and_cli(self) -> None:
        self.hosted_capture(decision_text="701\trouted\towned by another PR\n")
        hosted = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", accepted=0, routed=0)
        with self.assertRaisesRegex(
            pr_review.sqlite_provider_imports.ProviderImportError,
            "checkpoint counts do not match",
        ):
            pr_review.sqlite_provider_imports.import_hosted_checkpoint(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoint=hosted,
                actor="reviewer",
                common=self.common,
                scope="broad",
            )

        self.cli_capture()
        cli = self.checkpoint("CLI", "<!-- firemud-cli-run: run.Importer -->", accepted=0, routed=0)
        with self.assertRaisesRegex(pr_review.evidence.CaptureInvalid, "routed count"):
            pr_review.sqlite_provider_imports.import_cli_checkpoint(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoint=cli,
                actor="reviewer",
                common=self.common,
                scope="broad",
            )

    def test_provider_import_rolls_back_when_a_later_decision_conflicts(self) -> None:
        self.records.record_run(
            run_id="seed-provider-route",
            source_pr=PR,
            channel="cli",
            findings=(
                pr_review.sqlite_review_records.FindingObservation(
                    "provider-route", "Existing routed finding", disposition="routed", target_pr=2879
                ),
            ),
        )

        with self.assertRaisesRegex(
            pr_review.sqlite_provider_imports.ProviderImportError,
            "explicitly retargeted",
        ):
            pr_review.sqlite_provider_imports._persist_import(
                self.records,
                repo=REPO,
                pr_number=PR,
                channel="cli",
                origin="atomic-provider",
                source_head=HEAD,
                reviewer="CodeRabbit CLI",
                scope="broad",
                coverage_limits=(),
                started_at=None,
                finished_at=None,
                observations=(
                    pr_review.sqlite_review_records.FindingObservation("provider-accept", "Accept this"),
                    pr_review.sqlite_review_records.FindingObservation("provider-route", "Route this"),
                ),
                decisions=(
                    {
                        "source_finding_key": "provider-accept",
                        "decision_id": "provider-accept-decision",
                        "decision": "accepted",
                        "actor": "reviewer",
                        "reason": "valid",
                        "target_pr": None,
                    },
                    {
                        "source_finding_key": "provider-route",
                        "decision_id": "provider-route-decision",
                        "decision": "routed",
                        "actor": "reviewer",
                        "reason": "owned elsewhere",
                        "target_pr": 2999,
                    },
                ),
                actor="reviewer",
            )

        history = self.records.history(PR)
        self.assertEqual([run["run_id"] for run in history["runs"]], ["seed-provider-route"])
        self.assertEqual(
            [finding["source_finding_key"] for finding in history["findings"]], ["provider-route"]
        )

    def test_hosted_import_preserves_summary_cardinality_with_exact_dispositions(self) -> None:
        self.hosted_capture(review_body="### Outside diff range comments (2)\nDuplicate comments (1)")
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", raw=4, accepted=3, routed=1)
        legacy_route = FindingRoute(
            source_pr=PR,
            source_channel="hosted",
            source_review="summary:review:700",
            source_finding="duplicate:ref:legacy-summary",
            observations=("owned by another PR",),
            target_pr=2879,
        )
        add_legacy_route = lambda state: dataclasses.replace(state, routes=(*state.routes, legacy_route))
        ControllerStateStore(state_path(self.common)).update(add_legacy_route)
        pr_review.sqlite_store.SqliteStateStore(self.database).update(add_legacy_route)
        dispositions = (
            SummaryFindingDisposition(PR, HEAD, "review", 700, "outside_diff", 2, "accepted_unfixed", "not actionable"),
            SummaryFindingDisposition(
                PR,
                HEAD,
                "review",
                700,
                "duplicate",
                1,
                "routed",
                "owned by another PR",
                route_ids=(legacy_route.route_id,),
            ),
        )

        result = pr_review.sqlite_provider_imports.import_hosted_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="reviewer",
            common=self.common,
            scope="broad",
            summary_dispositions=dispositions,
        )

        history = self.records.history(PR)
        self.assertEqual(result["counts"], {"found": 4, "accepted": 3, "routed": 1})
        self.assertEqual(len(history["findings"]), 4)
        summary_findings = [item for item in history["findings"] if item["source_finding_key"].startswith("hosted-summary:")]
        self.assertEqual(len(summary_findings), 3)
        self.assertTrue(all("aggregate" in item["detail"] for item in summary_findings))
        self.assertEqual(len(history["routes"]), 1)
        combined_history = self.records.history(PR, include_legacy_routes=True)
        self.assertEqual(len(combined_history["routes"]), 1)
        self.assertEqual(combined_history["routes"][0]["origin"], "legacy_controller")
        self.assertEqual(combined_history["routes"][0]["route_id"], legacy_route.route_id)
        self.assertEqual(combined_history["routes"][0]["target_pr"], 2879)
        self.assertEqual(
            [route["route_id"] for route in self.records.open_routes(target_pr=2879, include_legacy_routes=True)],
            [legacy_route.route_id],
        )
        retargeted = dataclasses.replace(legacy_route, target_pr=2999, target_history=(2879, 2999))
        replace_legacy_route = lambda state: dataclasses.replace(
            state,
            routes=tuple(retargeted if route.route_id == legacy_route.route_id else route for route in state.routes),
        )
        ControllerStateStore(state_path(self.common)).update(replace_legacy_route)
        pr_review.sqlite_store.SqliteStateStore(self.database).update(replace_legacy_route)
        replay = pr_review.sqlite_provider_imports.import_hosted_checkpoint(
            self.records,
            repo=REPO,
            pr_number=PR,
            checkpoint=checkpoint,
            actor="reviewer",
            common=self.common,
            scope="broad",
            summary_dispositions=dispositions,
        )
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(
            [route["route_id"] for route in self.records.open_routes(target_pr=2999, include_legacy_routes=True)],
            [legacy_route.route_id],
        )
        self.assertEqual(self.records.open_routes(target_pr=2879, include_legacy_routes=True), [])
        self.assertEqual(self.records.history(2879, include_legacy_routes=True)["routes"], [])
        new_target_history = self.records.history(2999, include_legacy_routes=True)["routes"]
        self.assertEqual(len(new_target_history), 1)
        self.assertEqual(new_target_history[0]["route_id"], legacy_route.route_id)
        self.assertEqual(new_target_history[0]["target_pr"], 2999)

    def test_hosted_import_refuses_summary_bucket_without_exact_disposition(self) -> None:
        self.hosted_capture(review_body="### Outside diff range comments (1)")
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", raw=2, accepted=2)

        with self.assertRaisesRegex(
            pr_review.sqlite_provider_imports.ProviderImportError,
            "no exact structural disposition",
        ):
            pr_review.sqlite_provider_imports.import_hosted_checkpoint(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoint=checkpoint,
                actor="reviewer",
                common=self.common,
                scope="broad",
            )
        self.assertEqual(self.records.history(PR)["runs"], [])

    def test_hosted_import_refuses_summary_route_reference_without_legacy_route(self) -> None:
        self.hosted_capture(review_body="### Duplicate comments (1)")
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->", raw=2, accepted=1, routed=1)
        dispositions = (
            SummaryFindingDisposition(PR, HEAD, "review", 700, "duplicate", 1, "routed", "owned elsewhere", route_ids=("a" * 24,)),
        )
        with self.assertRaisesRegex(
            pr_review.sqlite_provider_imports.ProviderImportError,
            "unavailable legacy route",
        ):
            pr_review.sqlite_provider_imports.import_hosted_checkpoint(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoint=checkpoint,
                actor="reviewer",
                common=self.common,
                scope="broad",
                summary_dispositions=dispositions,
            )
        self.assertEqual(self.records.history(PR)["runs"], [])

    def test_refuses_correction_and_unmarked_non_counting_checkpoints(self) -> None:
        correction_comments, _ = pr_review.evidence.parse_checkpoint_comments(
            [
                {
                    "id": 11,
                    "body": (
                        "Correction — Hosted: 1 found / 1 accepted / 0 routed · "
                        f"{HEAD[:12]}\n<!-- firemud-hosted-review: 700 -->"
                    ),
                    "created_at": "2026-09-27T12:00:00Z",
                }
            ]
        )
        unmarked = self.checkpoint("Hosted", "")
        for checkpoint in (correction_comments[0], unmarked):
            with self.subTest(checkpoint=checkpoint), self.assertRaises(
                pr_review.sqlite_provider_imports.ProviderImportError
            ):
                pr_review.sqlite_provider_imports.import_hosted_checkpoint(
                    self.records,
                    repo=REPO,
                    pr_number=PR,
                    checkpoint=checkpoint,
                    actor="reviewer",
                    common=self.common,
                    scope="broad",
                )
        self.assertEqual(self.records.history(PR)["runs"], [])

    def test_refuses_hosted_capture_that_is_not_a_completed_submission(self) -> None:
        self.hosted_capture(state="PENDING")
        checkpoint = self.checkpoint("Hosted", "<!-- firemud-hosted-review: 700 -->")

        with self.assertRaises(pr_review.evidence.CaptureInvalid):
            pr_review.sqlite_provider_imports.import_hosted_checkpoint(
                self.records,
                repo=REPO,
                pr_number=PR,
                checkpoint=checkpoint,
                actor="reviewer",
                common=self.common,
                scope="broad",
            )
        self.assertEqual(self.records.history(PR)["runs"], [])


if __name__ == "__main__":
    unittest.main()
