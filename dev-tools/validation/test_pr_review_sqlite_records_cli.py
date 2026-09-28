import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review import cli, cli_attempts
from pr_review.controller import ReviewController
from pr_review.sqlite_review_records import FindingObservation, SqliteReviewRecords
from pr_review.sqlite_store import SqliteStateStore
from pr_review.state import FindingRoute, ReviewState, StateStore, SummaryFindingDisposition


class ReviewRecordsCliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        root = Path(self.temporary_directory.name)
        self.database = root / "controller.sqlite3"
        SqliteStateStore(self.database).update(lambda state: state)
        self.import_file = root / "manual-run.json"

    def invoke(self, *arguments: str) -> tuple[int, dict[str, object]]:
        output = io.StringIO()
        errors = io.StringIO()
        with (
            patch.object(cli, "default_controller", side_effect=AssertionError("records must not load controller")),
            contextlib.redirect_stdout(output),
            contextlib.redirect_stderr(errors),
        ):
            result = cli.main(["records", *arguments])
        if result == 0:
            return result, json.loads(output.getvalue())
        return result, {"error": errors.getvalue()}

    def test_history_exposes_failed_cli_attempt_without_counting_success_or_raw_output(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        root = self.database.parent / "pr-review" / "runs"
        failed = root / ("run." + "a" * 32)
        successful = root / ("run." + "b" * 32)
        for directory in (failed, successful):
            directory.mkdir(parents=True)
            (directory / "metadata.json").write_text(json.dumps({"pull_request": 2879}))
        (failed / "exit-status").write_text("1\n")
        (failed / "stderr").write_text("Error: Rate limit exceeded; private provider details")
        (successful / "exit-status").write_text("0\n")

        code, response = self.invoke("history", "--pr", "2879", "--database", str(self.database))

        self.assertEqual(code, 0)
        attempts = response["result"]["cli_attempts"]
        self.assertTrue(attempts["available"])
        self.assertEqual(1, len(attempts["attempts"]))
        self.assertEqual("rate_limited", attempts["attempts"][0]["outcome"])
        self.assertEqual(failed.name, attempts["attempts"][0]["run_id"])
        self.assertNotIn("private provider details", str(response))

    def test_failed_cli_attempt_reads_only_bounded_stderr_prefix_for_rate_limit_classification(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        failed = root / ("run." + "c" * 32)
        failed.mkdir(parents=True)
        (failed / "metadata.json").write_text(json.dumps({"pull_request": 2890}), encoding="utf-8")
        (failed / "exit-status").write_text("1\n", encoding="utf-8")
        stderr_path = failed / "stderr"

        class GuardedStream(io.BytesIO):
            requested_bytes: int | None = None

            def read(self, size: int = -1) -> bytes:
                self.requested_bytes = size
                if size < 0 or size > cli_attempts.MAX_STDERR_BYTES:
                    raise AssertionError("stderr read was not bounded")
                return super().read(size)

        stream = GuardedStream(b"Error: Rate limit exceeded\n" + b"x" * 1_000_000)
        stderr_path.touch()
        original_open = Path.open

        def open_stderr(path: Path, *args: object, **kwargs: object) -> object:
            if path == stderr_path:
                return stream
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=open_stderr):
            attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(stream.requested_bytes, 4096)
        self.assertEqual(attempts["attempts"][0]["outcome"], "rate_limited")

    def test_failed_cli_attempt_metadata_is_bounded_at_the_size_limit(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        exact = root / ("run." + "d" * 32)
        oversized = root / ("run." + "e" * 32)
        exact.mkdir(parents=True)
        oversized.mkdir()
        metadata = b'{"pull_request":2890}'
        exact_bytes = metadata + b" " * (cli_attempts.MAX_METADATA_BYTES - len(metadata))
        (exact / "metadata.json").write_bytes(exact_bytes)
        (exact / "exit-status").write_text("1\n", encoding="utf-8")
        oversized_path = oversized / "metadata.json"
        oversized_path.touch()
        (oversized / "exit-status").write_text("1\n", encoding="utf-8")

        class GuardedStream(io.BytesIO):
            requested_bytes: int | None = None

            def read(self, size: int = -1) -> bytes:
                self.requested_bytes = size
                if size < 0 or size > cli_attempts.MAX_METADATA_BYTES + 1:
                    raise AssertionError("metadata read was not bounded")
                return super().read(size)

        stream = GuardedStream(exact_bytes + b" ")
        original_open = Path.open

        def open_metadata(path: Path, *args: object, **kwargs: object) -> object:
            if path == oversized_path:
                return stream
            return original_open(path, *args, **kwargs)

        with patch.object(Path, "open", autospec=True, side_effect=open_metadata):
            attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(stream.requested_bytes, cli_attempts.MAX_METADATA_BYTES + 1)
        self.assertEqual([exact.name], [attempt["run_id"] for attempt in attempts["attempts"]])

    def test_failed_cli_attempt_ignores_symlinked_metadata(self) -> None:
        root = self.database.parent / "pr-review" / "runs"
        failed = root / ("run." + "f" * 32)
        failed.mkdir(parents=True)
        metadata_target = self.database.parent / "metadata-target.json"
        metadata_target.write_text(json.dumps({"pull_request": 2890}), encoding="utf-8")
        (failed / "metadata.json").symlink_to(metadata_target)
        (failed / "exit-status").write_text("1\n", encoding="utf-8")

        attempts = cli_attempts.failed_attempts(self.database, 2890)

        self.assertEqual(attempts["attempts"], [])

    def test_default_records_require_cutover_and_status_ignores_orphan_sibling_database(self) -> None:
        legacy_path = Path(self.temporary_directory.name) / "controller.json"
        StateStore(legacy_path).save(ReviewState())
        orphan_database = legacy_path.with_suffix(".sqlite3")
        SqliteStateStore(orphan_database).update(lambda state: state)
        SqliteReviewRecords(orphan_database).bootstrap()

        with patch.object(cli, "state_path", return_value=legacy_path):
            status, result = self.invoke("history", "--pr", "2828")
            self.assertEqual(status, 2)
            self.assertIn("has not been migrated to SQLite", result["error"])
            routes, route_state = cli._read_record_incoming_routes(2828)

        self.assertEqual(routes, [])
        self.assertEqual(route_state["status"], "not_bootstrapped")
        self.assertIn("has not been migrated to SQLite", route_state["reason"])

    def test_versioned_manual_run_readback_and_route_disposition(self) -> None:
        _, bootstrapped = self.invoke("bootstrap", "--database", str(self.database))
        self.assertEqual(bootstrapped["api_version"], 1)

        self.import_file.write_text(
            json.dumps(
                {
                    "api_version": 1,
                    "run": {
                        "run_id": "subagent-run-1",
                        "source_pr": 2700,
                        "channel": "subagent",
                        "reviewer": "security-reviewer",
                        "scope": "broad",
                        "coverage_limits": ["static inspection only"],
                        "outcome": "completed",
                    },
                    "findings": [
                        {"source_finding_key": "accept-me", "title": "Validate owner input"},
                        {"source_finding_key": "route-me", "title": "Move check to shared PR", "detail": "Wrong layer"},
                    ],
                }
            ),
            encoding="utf-8",
        )
        _, imported = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertEqual(imported["api_version"], 1)
        self.assertFalse(imported["result"]["finalized"])

        _, accepted = self.invoke(
            "source", "decide", "--run-id", "subagent-run-1", "--finding-key", "accept-me",
            "--decision-id", "source-accept", "--decision", "accepted", "--actor", "owner",
            "--reason", "valid finding", "--database", str(self.database),
        )
        self.assertEqual(accepted["result"]["decision"], "accepted")
        _, routed = self.invoke(
            "source", "decide", "--run-id", "subagent-run-1", "--finding-key", "route-me",
            "--decision-id", "source-route", "--decision", "routed", "--target-pr", "2879",
            "--actor", "owner", "--reason", "belongs to target", "--database", str(self.database),
        )
        route_id = routed["result"]["route_id"]
        _, finalized = self.invoke(
            "source", "finalize", "--run-id", "subagent-run-1", "--database", str(self.database)
        )
        self.assertEqual(finalized["result"]["counts"], {"found": 2, "accepted": 1, "routed": 1})

        _, target_decision = self.invoke(
            "route", "decide", "--route-id", route_id, "--decision-id", "target-decision",
            "--target-pr", "2879", "--decision", "accepted", "--actor", "owner",
            "--reason", "accepted by target owner", "--database", str(self.database),
        )
        self.assertEqual(target_decision["result"]["decision"], "accepted")
        _, resolution = self.invoke(
            "route", "resolve", "--route-id", route_id, "--resolution-id", "target-resolution",
            "--target-pr", "2879", "--outcome", "accepted_fixed", "--actor", "owner",
            "--proof-or-reason", "verified fix", "--database", str(self.database),
        )
        self.assertEqual(resolution["result"]["outcome"], "accepted_fixed")

        _, history = self.invoke("history", "--pr", "2700", "--database", str(self.database))
        self.assertEqual(history["api_version"], 1)
        self.assertEqual(history["result"]["runs"][0]["counts"], {"found": 2, "accepted": 1, "routed": 1})
        self.assertTrue(history["result"]["runs"][0]["finalized"])
        self.assertEqual(history["result"]["routes"][0]["resolutions"][0]["outcome"], "accepted_fixed")
        self.assertNotIn("payload", history["result"]["findings"][0])

    def test_batch_import_rejects_arbitrary_payload_and_provider_channels(self) -> None:
        _, _ = self.invoke("bootstrap", "--database", str(self.database))
        document = {
            "api_version": 1,
            "run": {
                "run_id": "manual-run",
                "source_pr": 2828,
                "channel": "manual",
                "reviewer": "reviewer",
                "scope": "narrow",
                "coverage_limits": [],
                "outcome": "completed",
            },
            "findings": [{"source_finding_key": "f-1", "title": "Finding", "payload": {"stdout": "raw"}}],
        }
        self.import_file.write_text(json.dumps(document), encoding="utf-8")
        status, result = self.invoke(
            "import-run", "--input", str(self.import_file), "--database", str(self.database)
        )
        self.assertEqual(status, 2)
        self.assertIn("arbitrary payload is not accepted", result["error"])

        document["run"]["channel"] = "hosted"
        document["findings"] = []
        self.import_file.write_text(json.dumps(document), encoding="utf-8")
        status, result = self.invoke(
            "import-run", "--input", str(self.import_file), "--database", str(self.database)
        )
        self.assertEqual(status, 2)
        self.assertIn("provider imports are not enabled", result["error"])

    def test_batch_import_bounds_bytes_read_even_if_file_grows_after_validation(self) -> None:
        class OversizedStream(io.BytesIO):
            requested_bytes: int | None = None

            def read(self, size: int = -1) -> bytes:
                self.requested_bytes = size
                return super().read(size)

        stream = OversizedStream(b"{" + b" " * 512_000)
        self.import_file.touch()
        with (
            patch.object(Path, "open", return_value=stream) as open_file,
            self.assertRaisesRegex(cli.CliError, "exceeds the 512 KB limit"),
        ):
            cli._load_records_import(str(self.import_file))

        open_file.assert_called_once_with("rb")
        self.assertEqual(stream.requested_bytes, 512_001)

    def test_complete_batch_import_decides_and_finalizes_atomically(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        self.import_file.write_text(
            json.dumps(
                {
                    "api_version": 1,
                    "run": {
                        "run_id": "manual-complete",
                        "source_pr": 2828,
                        "channel": "manual",
                        "reviewer": "reviewer",
                        "scope": "broad",
                        "coverage_limits": ["static inspection only"],
                        "outcome": "completed",
                    },
                    "findings": [
                        {
                            "source_finding_key": "accept-me",
                            "title": "Validate owner input",
                            "decision": "accepted",
                            "actor": "owner",
                            "reason": "valid source finding",
                        },
                        {
                            "source_finding_key": "route-me",
                            "title": "Move check to shared PR",
                            "decision": "routed",
                            "target_pr": 2879,
                            "actor": "owner",
                            "reason": "belongs to target PR",
                        },
                    ],
                }
            ),
            encoding="utf-8",
        )

        _, imported = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertTrue(imported["result"]["finalized"])
        self.assertFalse(imported["result"]["idempotent_replay"])
        self.assertEqual(imported["result"]["counts"], {"found": 2, "accepted": 1, "routed": 1})

        _, replay = self.invoke("import-run", "--input", str(self.import_file), "--database", str(self.database))
        self.assertTrue(replay["result"]["idempotent_replay"])
        _, history = self.invoke("history", "--pr", "2828", "--database", str(self.database))
        self.assertEqual(len(history["result"]["runs"]), 1)
        self.assertEqual(len(history["result"]["decisions"]), 2)

    def test_empty_incomplete_batch_is_recorded_as_unattributable(self) -> None:
        self.invoke("bootstrap", "--database", str(self.database))
        self.import_file.write_text(
            json.dumps(
                {
                    "api_version": 1,
                    "run": {
                        "run_id": "manual-empty-incomplete",
                        "source_pr": 2828,
                        "channel": "manual",
                        "reviewer": "reviewer",
                        "scope": "narrow",
                        "coverage_limits": ["review did not complete"],
                        "outcome": "incomplete",
                    },
                    "findings": [],
                }
            ),
            encoding="utf-8",
        )

        status, imported = self.invoke(
            "import-run", "--input", str(self.import_file), "--database", str(self.database)
        )
        self.assertEqual(status, 0)
        self.assertFalse(imported["result"]["finalized"])
        _, history = self.invoke("history", "--pr", "2828", "--database", str(self.database))
        run = history["result"]["runs"][0]
        self.assertEqual(run["outcome"], "incomplete")
        self.assertFalse(run["attributable"])

    def test_provider_import_uses_exact_parsed_checkpoint_and_keeps_capture_import_explicit(self) -> None:
        checkpoint = {
            "id": "comment-123",
            "databaseId": 123,
            "body": "Hosted: 1 found / 1 accepted / 0 routed · abcdef0\n<!-- firemud-hosted-review: 998 -->",
            "createdAt": "2026-09-28T01:02:03Z",
            "updatedAt": "2026-09-28T01:02:03Z",
            "author": {"login": "coderabbitai[bot]"},
        }
        payload = {
            "data": {
                "repository": {
                    "pullRequest": {"number": 2828, "comments": {"nodes": [checkpoint]}}
                }
            }
        }
        records = type("FakeRecords", (), {"history": lambda self, pr: {"pr": pr}})()
        with (
            patch.object(cli, "_records_store", return_value=records),
            patch.object(cli.github, "infer_repo", return_value="owner/repo"),
            patch.object(cli.github, "fetch_pull_request", return_value=payload),
            patch.object(
                cli.sqlite_provider_imports,
                "import_hosted_checkpoint",
                return_value={"channel": "hosted", "idempotent_replay": False},
            ) as importer,
        ):
            output = io.StringIO()
            with contextlib.redirect_stdout(output):
                status = cli.main(
                    [
                        "records", "import-provider", "--pr", "2828", "--channel", "hosted",
                        "--checkpoint-id", "123", "--actor", "operator", "--scope", "broad",
                        "--repo", "owner/repo", "--database", str(self.database),
                    ]
                )
        self.assertEqual(status, 0)
        self.assertEqual(json.loads(output.getvalue())["api_version"], 1)
        args, kwargs = importer.call_args
        self.assertIs(args[0], records)
        self.assertEqual(kwargs["repo"], "owner/repo")
        self.assertEqual(kwargs["pr_number"], 2828)
        self.assertEqual(kwargs["checkpoint"].comment_id, 123)
        self.assertEqual(kwargs["checkpoint"].hosted_review_id, 998)
        self.assertEqual(kwargs["actor"], "operator")

    def test_provider_import_requires_explicit_schema_bootstrap_before_reading_provider_state(self) -> None:
        with patch.object(cli.github, "fetch_pull_request") as fetch:
            status, result = self.invoke(
                "import-provider", "--pr", "2828", "--channel", "hosted", "--checkpoint-id", "123",
                "--actor", "operator", "--scope", "broad", "--repo", "owner/repo",
                "--database", str(self.database),
            )
        self.assertEqual(status, 2)
        self.assertIn("schema is not bootstrapped", result["error"])
        fetch.assert_not_called()

    def test_migrated_legacy_routes_are_read_through_and_keep_controller_dispositions_authoritative(self) -> None:
        legacy_path = Path(self.temporary_directory.name) / "legacy-review-state.json"
        database = legacy_path.with_suffix(".sqlite3")
        incoming = FindingRoute(
            source_pr=2600,
            source_channel="hosted",
            source_review="summary:review:901",
            source_finding="duplicate:ref:shared-check",
            observations=("belongs to the shared owner",),
            target_pr=2879,
        )
        unassigned = FindingRoute(
            source_pr=2601,
            source_channel="cli",
            source_review="legacy-cli-review",
            source_finding="legacy-unassigned-check",
            observations=("owner is not known yet",),
        )
        disposition = SummaryFindingDisposition(
            pr=2600,
            head="a" * 40,
            source="review",
            summary_id=901,
            kind="duplicate",
            count=1,
            decision="routed",
            reason="owned by the shared change",
            route_ids=(incoming.route_id,),
        )
        original_state = ReviewState(
            summary_dispositions=(disposition,),
            routes=(incoming, unassigned),
        )
        StateStore(legacy_path).save(original_state)
        migrated = SqliteStateStore.migrate_legacy_json(legacy_path, database)
        records = SqliteReviewRecords(database)
        records.bootstrap()
        records.record_run(
            run_id="new-structured-route",
            source_pr=2700,
            channel="manual",
            findings=(FindingObservation("structured-key", "A separate structured route", "routed", target_pr=2879),),
        )

        _, target_routes = self.invoke("routes", "--target-pr", "2879", "--database", str(database))
        target_items = target_routes["result"]["routes"]
        structured_target = next(item for item in target_items if item["origin"] == "review_records")
        self.assertEqual(
            {item["route_id"] for item in target_items},
            {incoming.route_id, structured_target["route_id"]},
        )
        legacy_target = next(item for item in target_items if item["origin"] == "legacy_controller")
        self.assertEqual(legacy_target["route_id"], incoming.route_id)
        self.assertEqual(legacy_target["source_review"], incoming.source_review)
        self.assertEqual(legacy_target["source_finding"], incoming.source_finding)

        _, unassigned_routes = self.invoke("routes", "--unassigned", "--database", str(database))
        unassigned_items = unassigned_routes["result"]["routes"]
        self.assertEqual([item["route_id"] for item in unassigned_items], [unassigned.route_id])
        self.assertEqual(unassigned_items[0]["origin"], "legacy_controller")

        _, history = self.invoke("history", "--pr", "2600", "--database", str(database))
        history_result = history["result"]
        self.assertEqual(history_result["runs"], [])
        self.assertEqual(history_result["findings"], [])
        self.assertNotIn("taper", history_result)
        self.assertEqual(history_result["routes"][0]["route_id"], incoming.route_id)
        self.assertEqual(history_result["routes"][0]["origin"], "legacy_controller")

        controller = ReviewController(store=migrated)
        controller.decide_route(
            route_id=incoming.route_id,
            decision="accepted-fixed",
            proof="verified in commit " + "b" * 40,
        )
        _, after_decision = self.invoke("routes", "--target-pr", "2879", "--database", str(database))
        remaining = after_decision["result"]["routes"]
        self.assertEqual(len(remaining), 1)
        self.assertEqual(remaining[0]["origin"], "review_records")
        _, closed_history = self.invoke("history", "--pr", "2600", "--database", str(database))
        closed_route = closed_history["result"]["routes"][0]
        self.assertEqual(closed_route["route_id"], incoming.route_id)
        self.assertEqual(closed_route["status"], "accepted_fixed")
        self.assertEqual(closed_route["proof"], "verified in commit " + "b" * 40)
        current_state = migrated.load()
        self.assertEqual(current_state.summary_dispositions, original_state.summary_dispositions)
        self.assertEqual(current_state.summary_dispositions[0].route_ids, (incoming.route_id,))

    def test_selected_status_adds_sqlite_routes_without_replacing_legacy_route_checks(self) -> None:
        records = SqliteReviewRecords(self.database)
        records.bootstrap()
        records.record_run(
            run_id="subagent-route",
            source_pr=2700,
            channel="subagent",
            findings=(FindingObservation("sqlite-finding", "Independent route"),),
        )
        records.record_source_decision(
            "subagent-route",
            "sqlite-finding",
            decision_id="sqlite-source-route",
            decision="routed",
            target_pr=2879,
            actor="reviewer",
            reason="owned by selected PR",
        )

        legacy_route = {
            "route_id": "legacy-json-route",
            "source_pr": 2600,
            "source_channel": "hosted",
            "source_review": "old-review",
            "source_finding": "legacy-finding",
            "observations": ["kept in the legacy state store"],
        }
        controller = type(
            "FakeController",
            (),
            {
                "store": SqliteStateStore(self.database),
                "status_for_pr": lambda self, pr: {
                    "prs": [
                        {
                            "pr": pr,
                            "incoming_routes": [legacy_route],
                            "routes_out": [],
                            "reconciliation": "COHERENT",
                            "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                            "allocations": {},
                            "head": "a" * 40,
                            "base": "main",
                            "parent_head": "b" * 40,
                        }
                    ]
                },
            },
        )()
        base_report = {
            "pr_number": 2879,
            "pull_request": {"headRefOid": "a" * 40, "baseRefName": "main", "baseRefOid": "b" * 40},
            "reasons": [],
            "ready": True,
            "verdict": "READY",
            "mergeability": {"clean": True, "diagnosis": "READY"},
        }
        cutover_state_path = Path(self.temporary_directory.name) / "cutover-state"
        cutover_state_path.mkdir()
        output = io.StringIO()
        with (
            patch.object(cli, "default_controller", return_value=controller),
            patch.object(cli.status_module, "status", return_value=base_report.copy()),
            patch.object(cli, "state_path", return_value=cutover_state_path),
            patch.object(cli, "_records_database_path", return_value=self.database),
            contextlib.redirect_stdout(output),
        ):
            status = cli.main(["status", "--pr", "2879", "--json"])
        self.assertEqual(status, 0)
        report = json.loads(output.getvalue())
        self.assertEqual(report["incoming_routes"], [legacy_route])
        self.assertEqual(len(report["incoming_record_routes"]), 1)
        self.assertEqual(report["incoming_record_routes"][0]["source_pr"], 2700)
        self.assertEqual(report["record_route_store"]["status"], "available")
        self.assertFalse(report["ready"])
        self.assertTrue(any("SQLite-record incoming route" in reason for reason in report["reasons"]))

    def test_selected_status_deduplicates_legacy_route_shadows_before_filtering(self) -> None:
        retargeted = FindingRoute(
            source_pr=2600,
            source_channel="hosted",
            source_review="summary:review:901",
            source_finding="duplicate:ref:retargeted-shadow",
            observations=("originally owned by this PR",),
            target_pr=2879,
        )
        resolved = FindingRoute(
            source_pr=2600,
            source_channel="hosted",
            source_review="summary:review:902",
            source_finding="duplicate:ref:resolved-shadow",
            observations=("originally owned by this PR",),
            target_pr=2879,
        )
        legacy_path = Path(self.temporary_directory.name) / "legacy-shadow-state.json"
        database = legacy_path.with_suffix(".sqlite3")
        StateStore(legacy_path).save(ReviewState(routes=(retargeted, resolved)))
        migrated = SqliteStateStore.migrate_legacy_json(legacy_path, database)
        records = SqliteReviewRecords(database)
        records.bootstrap()
        shadow_findings = (
            FindingObservation("retargeted-shadow-key", "Retargeted route shadow"),
            FindingObservation("resolved-shadow-key", "Resolved route shadow"),
        )
        records.import_completed_run(
            run_id="legacy-route-shadows",
            source_pr=2600,
            channel="hosted",
            findings=shadow_findings,
            source_decisions=(
                {
                    "source_finding_key": "retargeted-shadow-key",
                    "decision_id": "retargeted-shadow-decision",
                    "decision": "routed",
                    "actor": "reviewer",
                    "reason": "preserve the imported legacy route identity",
                    "target_pr": 2879,
                    "route_id": retargeted.route_id,
                    "route_status": "open",
                },
                {
                    "source_finding_key": "resolved-shadow-key",
                    "decision_id": "resolved-shadow-decision",
                    "decision": "routed",
                    "actor": "reviewer",
                    "reason": "preserve the imported legacy route identity",
                    "target_pr": 2879,
                    "route_id": resolved.route_id,
                    "route_status": "open",
                },
            ),
        )
        self.assertEqual(
            {route["route_id"] for route in records.open_routes(target_pr=2879)},
            {retargeted.route_id, resolved.route_id},
        )

        controller = ReviewController(store=migrated)
        controller.decide_route(
            route_id=retargeted.route_id,
            decision="retargeted",
            target_pr=2880,
            reason="the receiving owner changed",
        )
        controller.decide_route(
            route_id=resolved.route_id,
            decision="accepted-fixed",
            proof="verified fix in the receiving owner",
        )

        cutover_state_path = Path(self.temporary_directory.name) / "cutover-shadow-state"
        cutover_state_path.mkdir()
        with (
            patch.object(cli, "state_path", return_value=cutover_state_path),
            patch.object(cli, "_records_database_path", return_value=database),
        ):
            incoming, state = cli._read_record_incoming_routes(2879)
        self.assertEqual(state["status"], "available")
        self.assertEqual(incoming, [])


if __name__ == "__main__":
    unittest.main()
