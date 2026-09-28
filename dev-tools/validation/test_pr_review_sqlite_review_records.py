import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review.sqlite_review_records import FindingObservation, ReviewRecordsError, SqliteReviewRecords
from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, SqliteStateStore


class SqliteReviewRecordsTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.database = Path(self.temporary_directory.name) / "controller.sqlite3"
        SqliteStateStore(self.database).update(lambda state: state)
        self.records = SqliteReviewRecords(self.database)

    def bootstrap(self) -> None:
        self.records.bootstrap()

    @staticmethod
    def observation(
        key: str,
        disposition: str = "unresolved",
        *,
        target_pr: int | None = None,
    ) -> FindingObservation:
        return FindingObservation(
            source_finding_key=key,
            title=f"Finding {key}",
            disposition=disposition,
            target_pr=target_pr,
        )

    def test_bootstrap_is_explicit_and_preserves_controller_schema_version(self) -> None:
        with sqlite3.connect(self.database) as connection:
            before = connection.execute("PRAGMA user_version").fetchone()[0]
            self.assertEqual(
                connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'review_runs'"
                ).fetchone(),
                None,
            )

        self.bootstrap()

        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("PRAGMA user_version").fetchone()[0], before)
            self.assertEqual(before, SQLITE_SCHEMA_VERSION)
            self.assertIsNotNone(
                connection.execute(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'review_runs'"
                ).fetchone()
            )

    def test_duplicate_source_identity_in_one_run_is_rejected_atomically(self) -> None:
        self.bootstrap()
        with self.assertRaisesRegex(ReviewRecordsError, "same stable finding"):
            self.records.record_run(
                run_id="hosted-1",
                source_pr=2828,
                channel="hosted",
                findings=(self.observation("bug-1"), self.observation("bug-1", "accepted")),
            )
        self.assertEqual(self.records.history(2828)["runs"], [])

    def test_record_run_is_exactly_idempotent_and_conflicts_do_not_create_routes(self) -> None:
        self.bootstrap()
        findings = (self.observation("stable-1"),)
        original = {
            "run_id": "manual-run-1",
            "source_pr": 2828,
            "channel": "manual",
            "findings": findings,
            "reviewer": "reviewer",
            "scope": "narrow",
            "coverage_limits": ("no runtime execution",),
            "started_at": "2026-09-28T01:02:03Z",
            "finished_at": "2026-09-28T01:03:03Z",
        }
        first = self.records.record_run(**original)
        replay = self.records.record_run(**original)
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(first["counts"], replay["counts"])
        with self.assertRaisesRegex(ReviewRecordsError, "different immutable content"):
            self.records.record_run(
                **{**original, "findings": (self.observation("stable-1", "routed", target_pr=2879),)}
            )
        self.assertEqual(self.records.open_routes(), [])

        with self.assertRaisesRegex(ReviewRecordsError, "credential or raw secret"):
            FindingObservation("safe-key", "title", detail="token=ghp_" + "A" * 30)

    def test_bounded_free_text_accepts_only_exact_full_commit_shas_among_long_tokens(self) -> None:
        sha1 = "a" * 40
        sha256 = "b" * 64
        observation = FindingObservation(
            "sha-context",
            f"fixed in commit {sha1}",
            detail=f"verified against {sha256}.",
        )
        self.assertEqual(observation.title, f"fixed in commit {sha1}")
        self.assertEqual(observation.detail, f"verified against {sha256}.")

        with self.assertRaisesRegex(ReviewRecordsError, "credential or raw secret"):
            FindingObservation("token-context", "Z" * 40)
        for key, value in (
            ("prefixed-sha", f"prefix_{sha1}"),
            ("suffixed-sha", f"{sha256}_suffix"),
            ("provider-token", f"ghp_{sha1}"),
        ):
            with self.subTest(value=value), self.assertRaisesRegex(
                ReviewRecordsError, "credential or raw secret"
            ):
                FindingObservation(key, value)

    def test_routed_source_finding_cannot_be_changed_into_an_orphan_route(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="manual-route-run",
            source_pr=2828,
            channel="manual",
            findings=(self.observation("route-key"),),
        )
        route = self.records.record_source_decision(
            "manual-route-run",
            "route-key",
            decision_id="source-route",
            decision="routed",
            target_pr=2879,
            actor="reviewer",
            reason="owned by another change",
        )
        with self.assertRaisesRegex(ReviewRecordsError, "cannot be withdrawn"):
            self.records.record_source_decision(
                "manual-route-run",
                "route-key",
                decision_id="source-reject-after-route",
                decision="rejected",
                actor="reviewer",
                reason="changed mind",
            )
        self.assertEqual([item["route_id"] for item in self.records.open_routes()], [route["route_id"]])

    def test_repeated_source_observations_reuse_one_route_and_keep_target_history(self) -> None:
        self.bootstrap()
        first = self.records.record_run(
            run_id="hosted-1",
            source_pr=2828,
            channel="hosted",
            findings=(self.observation("bug-7", "routed", target_pr=2879),),
        )
        second = self.records.record_run(
            run_id="hosted-2",
            source_pr=2828,
            channel="hosted",
            findings=(self.observation("bug-7", "routed", target_pr=2879),),
        )
        source_history = self.records.history(2828)
        target_history = self.records.history(2879)

        self.assertEqual(first["counts"], {"found": 1, "accepted": 0, "routed": 1})
        self.assertEqual(second["counts"], first["counts"])
        self.assertEqual(len(source_history["routes"]), 1)
        self.assertEqual(len(target_history["routes"]), 1)
        self.assertEqual(source_history["routes"][0]["route_id"], target_history["routes"][0]["route_id"])
        self.assertEqual(len(source_history["findings"]), 2)
        self.assertEqual(len(source_history["routes"][0]["target_history"]), 1)

    def test_source_decisions_finalize_counts_and_target_resolution_cannot_rewrite_them(self) -> None:
        self.bootstrap()
        run = self.records.record_run(
            run_id="imported-review",
            source_pr=2828,
            channel="hosted",
            findings=(
                self.observation("accept-me"),
                self.observation("route-me"),
                self.observation("reject-me"),
            ),
        )
        self.assertEqual(run["counts"], {"found": 3, "accepted": 0, "routed": 0})
        self.assertFalse(run["finalized"])

        self.records.record_source_decision(
            "imported-review",
            "accept-me",
            decision_id="source-accept-1",
            decision="accepted",
            actor="reviewer",
            reason="valid source finding",
        )
        routed = self.records.record_source_decision(
            "imported-review",
            "route-me",
            decision_id="source-route-1",
            decision="routed",
            target_pr=2879,
            actor="reviewer",
            reason="owned by the target change",
        )
        rejected = self.records.record_source_decision(
            "imported-review",
            "reject-me",
            decision_id="source-reject-1",
            decision="rejected",
            actor="reviewer",
            reason="not actionable",
        )
        self.assertEqual(routed["counts"], {"found": 3, "accepted": 1, "routed": 1})
        self.assertEqual(rejected["counts"], {"found": 3, "accepted": 1, "routed": 1})
        finalized = self.records.finalize_run("imported-review")
        self.assertEqual(finalized["counts"], {"found": 3, "accepted": 1, "routed": 1})
        self.assertTrue(finalized["finalized"])
        replay = self.records.record_run(
            run_id="imported-review",
            source_pr=2828,
            channel="hosted",
            findings=(
                self.observation("accept-me"),
                self.observation("route-me"),
                self.observation("reject-me"),
            ),
        )
        self.assertTrue(replay["idempotent_replay"])
        self.assertTrue(replay["finalized"])
        self.assertEqual(replay["counts"], {"found": 3, "accepted": 1, "routed": 1})

        route_id = routed["route_id"]
        source_counts_before = self.records.history(2828)["runs"][0]["counts"]

        self.records.record_decision(
            route_id,
            decision_id="target-decision-1",
            decision_pr=2879,
            decision="accepted",
            actor="owner",
            reason="belongs to the target change",
        )
        self.records.record_resolution(
            route_id,
            resolution_id="resolution-1",
            resolution_pr=2879,
            outcome="accepted_fixed",
            actor="owner",
            proof_or_reason="fixed by the target PR",
        )

        history = self.records.history(2828)
        self.assertEqual(history["runs"][0]["counts"], {"found": 3, "accepted": 1, "routed": 1})
        self.assertEqual(history["runs"][0]["counts"], source_counts_before)
        self.assertTrue(history["runs"][0]["finalized"])
        self.assertEqual(len(history["decisions"]), 3)
        self.assertEqual(history["routes"][0]["decisions"][0]["decision"], "accepted")
        self.assertEqual(history["routes"][0]["resolutions"][0]["outcome"], "accepted_fixed")
        self.assertEqual(self.records.open_routes(target_pr=2879), [])
        with self.assertRaisesRegex(ReviewRecordsError, "finalized source-run counts"):
            self.records.record_source_decision(
                "imported-review",
                "accept-me",
                decision_id="late-source-decision",
                decision="rejected",
                actor="reviewer",
                reason="too late",
            )

    def test_completed_import_is_atomic_when_a_later_route_conflicts(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="seed-route",
            source_pr=2828,
            channel="cli",
            findings=(self.observation("later-route", "routed", target_pr=2879),),
        )

        with self.assertRaisesRegex(ReviewRecordsError, "explicitly retargeted"):
            self.records.import_completed_run(
                run_id="atomic-import",
                source_pr=2828,
                channel="cli",
                findings=(self.observation("first-accepted"), self.observation("later-route")),
                source_decisions=(
                    {
                        "source_finding_key": "first-accepted",
                        "decision_id": "atomic-accept",
                        "decision": "accepted",
                        "actor": "reviewer",
                        "reason": "valid source finding",
                    },
                    {
                        "source_finding_key": "later-route",
                        "decision_id": "atomic-route",
                        "decision": "routed",
                        "target_pr": 2999,
                        "actor": "reviewer",
                        "reason": "belongs to another change",
                    },
                ),
            )

        history = self.records.history(2828)
        self.assertEqual([run["run_id"] for run in history["runs"]], ["seed-route"])
        self.assertEqual([finding["source_finding_key"] for finding in history["findings"]], ["later-route"])
        self.assertEqual(self.records.open_routes()[0]["target_pr"], 2879)

    def test_completed_import_replays_exactly_and_refuses_decision_conflict(self) -> None:
        self.bootstrap()
        import_args = {
            "run_id": "completed-import",
            "source_pr": 2828,
            "channel": "manual",
            "findings": (self.observation("accept-me"),),
            "source_decisions": (
                {
                    "source_finding_key": "accept-me",
                    "decision_id": "completed-accept",
                    "decision": "accepted",
                    "actor": "reviewer",
                    "reason": "valid source finding",
                },
            ),
            "reviewer": "reviewer",
            "scope": "narrow",
        }
        first = self.records.import_completed_run(**import_args)
        replay = self.records.import_completed_run(**import_args)
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(first["counts"], {"found": 1, "accepted": 1, "routed": 0})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts with this completed import"):
            self.records.import_completed_run(
                **{
                    **import_args,
                    "source_decisions": (
                        {
                            "source_finding_key": "accept-me",
                            "decision_id": "completed-accept",
                            "decision": "accepted",
                            "actor": "different-reviewer",
                            "reason": "changed reason",
                        },
                    ),
                }
            )
        self.assertEqual(len(self.records.history(2828)["runs"]), 1)

    def test_completed_import_replay_survives_later_route_retargeting(self) -> None:
        self.bootstrap()
        import_args = {
            "run_id": "completed-routed-import",
            "source_pr": 2828,
            "channel": "cli",
            "findings": (self.observation("route-me"),),
            "source_decisions": (
                {
                    "source_finding_key": "route-me",
                    "decision_id": "completed-route",
                    "decision": "routed",
                    "actor": "reviewer",
                    "reason": "owned by another change",
                },
            ),
        }
        first = self.records.import_completed_run(**import_args)
        route_id = self.records.open_routes()[0]["route_id"]
        self.records.retarget_route(
            route_id,
            target_pr=2879,
            actor="target-owner",
            reason="target owner identified",
        )
        replay = self.records.import_completed_run(**import_args)
        self.assertFalse(first["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        self.assertEqual(self.records.open_routes(target_pr=2879)[0]["route_id"], route_id)

    def test_route_targeting_is_validated_and_unassigned_routes_are_readable(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="old-source-review",
            source_pr=2700,
            channel="cli",
            findings=(self.observation("incoming", "routed", target_pr=2879),),
        )
        self.records.record_run(
            run_id="manual-unassigned",
            source_pr=2999,
            channel="manual",
            findings=(self.observation("unassigned", "routed"),),
        )
        incoming = self.records.open_routes(target_pr=2879)
        all_open = self.records.open_routes()
        self.assertEqual([route["source_pr"] for route in incoming], [2700])
        self.assertEqual(incoming[0]["assignment"], "incoming")
        self.assertIn({"source_pr": 2999, "assignment": "unassigned"}, [
            {"source_pr": route["source_pr"], "assignment": route["assignment"]} for route in all_open
        ])
        self.assertEqual(self.records.history(2879)["routes"][0]["source_pr"], 2700)

        with self.assertRaisesRegex(ReviewRecordsError, "current target"):
            self.records.record_resolution(
                incoming[0]["route_id"],
                resolution_id="wrong-owner-resolution",
                resolution_pr=2800,
                outcome="rejected",
                actor="owner",
                proof_or_reason="wrong target",
            )

        self.records.retarget_route(
            all_open[0]["route_id"],
            target_pr=2879,
            actor="operator",
            reason="assign to the owning PR",
        )
        assigned_history = self.records.history(2879)
        self.assertEqual(len(assigned_history["routes"]), 2)
        self.assertEqual(len(assigned_history["routes"][1]["target_history"]), 2)

    def test_manual_and_subagent_runs_do_not_change_controller_policy_state(self) -> None:
        self.bootstrap()
        policy_store = SqliteStateStore(self.database)
        policy_before = policy_store.load().to_dict()
        self.records.record_run(
            run_id="manual-review",
            source_pr=2828,
            channel="manual",
            findings=(self.observation("manual-accepted", "accepted"),),
        )
        self.records.finalize_run("manual-review")
        self.records.record_run(
            run_id="subagent-review",
            source_pr=2828,
            channel="subagent",
            findings=(self.observation("subagent-unresolved"),),
        )
        self.records.record_source_decision(
            "subagent-review",
            "subagent-unresolved",
            decision_id="subagent-source-decision",
            decision="accepted",
            actor="reviewer",
            reason="recordable assistant observation",
        )
        self.records.finalize_run("subagent-review")

        with sqlite3.connect(self.database) as connection:
            taper_table = connection.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'channel_taper'"
            ).fetchone()
        history = self.records.history(2828)
        self.assertEqual(policy_store.load().to_dict(), policy_before)
        self.assertIsNone(taper_table)
        self.assertNotIn("taper", history)
        self.assertEqual([run["channel"] for run in history["runs"]], ["manual", "subagent"])
        self.assertEqual([run["counts"]["accepted"] for run in history["runs"]], [1, 1])

    def test_incompatible_records_metadata_fails_closed_and_readback_is_machine_readable(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="manual-1",
            source_pr=2828,
            channel="manual",
            attributable=False,
            findings=(self.observation("readback", "accepted"),),
        )
        self.records.finalize_run("manual-1")
        reopened = SqliteReviewRecords(self.database)
        result = reopened.history(2828)
        self.assertEqual(result["runs"][0]["counts"], {"found": 1, "accepted": 1, "routed": 0})
        self.assertTrue(result["runs"][0]["finalized"])
        self.assertEqual(result["runs"][0]["reviewer"], "manual")
        self.assertEqual(result["runs"][0]["scope"], "narrow")
        self.assertEqual(result["findings"][0]["source_finding_key"], "readback")
        self.assertNotIn("payload", result["findings"][0])
        self.assertNotIn("taper", result)

        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE review_records_metadata SET records_schema_version = 999 WHERE singleton = 1"
            )
        with self.assertRaisesRegex(ReviewRecordsError, "schema version 999"):
            reopened.history(2828)


if __name__ == "__main__":
    unittest.main()
