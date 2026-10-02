import dataclasses
import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review import sqlite_backup, sqlite_hosted_capture, sqlite_provider_imports, sqlite_review_records
from pr_review.evidence import Checkpoint
from pr_review.sqlite_finding_text import _hosted_aggregate_display_detail, _safe_finding_detail
from pr_review.sqlite_review_records import (
    AttemptNotFound,
    FindingObservation,
    RecordsNotBootstrapped,
    RecordsSchemaIncompatible,
    ReviewRecordsError,
    SqliteReviewRecords,
    _archive_artifact,
)
from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, WRITER_BUILD, SqliteStateStore
from pr_review.state import FindingRoute, StateError


class SqliteReviewRecordsTest(unittest.TestCase):
    def duration_import(self, run_id, channel, *, checkpoint_duration=90, capture_duration=None, fields=True):
        self.bootstrap()
        self._duration_index = getattr(self, "_duration_index", 0) + 1
        index = self._duration_index
        self.records.record_run(run_id=run_id, source_pr=2839, channel=channel, findings=())
        checkpoint = Checkpoint(
            comment_id=123 + index, created_at="2026-10-01T00:00:00Z", type="Hosted" if channel == "hosted" else "CLI",
            raw_found=0, accepted=0, reviewed_sha="a" * 40, file_count=1, correction=False,
            updated_at=None, run_id=f"run.Duration{index}" if channel == "cli" else None,
            hosted_review_id=700 + index if channel == "hosted" else None, duration_seconds=checkpoint_duration,
        )
        fingerprint = sqlite_provider_imports._checkpoint_fingerprint(checkpoint)
        metadata = {"repository": "owner/repo", "pull_request": 2839,
                    "checkpoint": checkpoint.as_json(), "checkpoint_fingerprint": fingerprint}
        if fields:
            metadata["checkpoint_fields"] = dataclasses.asdict(checkpoint)
        if capture_duration is not None:
            metadata["review_duration_seconds"] = capture_duration
        self.records.archive_imported_artifacts(run_id, {"metadata": json.dumps(metadata)})
        self.records.link_provider_origin(
            repository="owner/repo", source_pr=2839, channel=channel,
            provider_id=f"review:{700 + index}" if channel == "hosted" else f"run:run.Duration{index}",
            checkpoint_id=123 + index, checkpoint_fingerprint=fingerprint, run_id=run_id,
        )

    def test_duration_projection_uses_exact_completed_attempt(self) -> None:
        self.bootstrap()
        self.records.record_run(run_id="duration-native", source_pr=2839, channel="hosted", findings=())
        self.records.start_attempt(attempt_id="native-timer", source_pr=2839, channel="hosted",
                                   started_at="2026-10-01T00:00:00Z")
        self.records.finish_attempt("native-timer", state="completed", duration_seconds=180,
                                    finished_at="2026-10-01T00:03:00Z")
        self.records.link_attempt_run("native-timer", "duration-native")
        history = self.records.history(2839)
        self.assertEqual(history["runs"][0]["duration_seconds"], 180)
        self.assertEqual(history["attempts"][0]["duration_seconds"], 180)

    def test_duration_projection_uses_validated_imported_checkpoint_and_capture(self) -> None:
        self.duration_import("duration-hosted", "hosted")
        self.duration_import("duration-cli", "cli", checkpoint_duration=None, capture_duration="120\n")
        self.duration_import("duration-old", "hosted", fields=False)
        runs = {run["run_id"]: run for run in self.records.history(2839)["runs"]}
        self.assertEqual(runs["duration-hosted"]["duration_seconds"], 90)
        self.assertEqual(runs["duration-cli"]["duration_seconds"], 120)
        self.assertEqual(runs["duration-old"]["duration_seconds"], 90)

    def test_duration_conflict_null_blocks_same_attempt_fallback(self) -> None:
        self.duration_import("duration-conflicting-attempt", "cli", checkpoint_duration=90)
        self.records.start_attempt(attempt_id="conflicting-attempt", source_pr=2839, channel="cli")
        self.records.finish_attempt("conflicting-attempt", state="completed", duration_seconds=120)
        self.records.link_attempt_run("conflicting-attempt", "duration-conflicting-attempt")
        history = self.records.history(2839)
        self.assertIsNone(history["runs"][0]["duration_seconds"])
        self.assertEqual(history["attempts"][0]["duration_seconds"], 120)

    def test_duration_projection_omits_unknown_invalid_conflicting_or_unassociated_evidence(self) -> None:
        self.duration_import("duration-unknown", "hosted", checkpoint_duration=None)
        self.duration_import("duration-invalid", "cli", capture_duration="unknown")
        self.duration_import("duration-conflict", "cli", capture_duration="120")
        self.duration_import("duration-hash-conflict", "hosted")
        self.duration_import("duration-origin-conflict", "hosted")
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE provider_origins SET checkpoint_fingerprint = ? WHERE run_id = ?",
                               ("0" * 64, "duration-hash-conflict"))
            connection.execute("UPDATE provider_origins SET source_pr = ? WHERE run_id = ?",
                               (2879, "duration-origin-conflict"))
            before = list(connection.iterdump())
        with patch.object(self.records, "_write_connection", side_effect=AssertionError("read wrote")):
            history = self.records.history(2839)
        self.assertTrue(all(run["duration_seconds"] is None for run in history["runs"]))
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(list(connection.iterdump()), before)

    def hosted_display_run(self, run_id="display-run", *, title="<details>", routed=True):
        self.bootstrap()
        key = "hosted-comment:4142913648"
        self.records.import_completed_run(
            run_id=run_id, source_pr=2839, channel="hosted",
            findings=(FindingObservation(source_finding_key=key, title=title, detail="Old bounded detail"),),
            source_decisions=({
                "source_finding_key": key, "decision_id": "decision-" + run_id,
                "decision": "routed" if routed else "accepted",
                "target_pr": 2879 if routed else None,
                "actor": "reviewer", "reason": "Original adjudication",
            },),
            reviewer="coderabbitai[bot]", scope="broad",
        )
        return key

    @staticmethod
    def hosted_display_archive(*, comment_id=4142913648, issue="Check the existing workflow request identity.\n\nKeep `requestId` consistent with [the contract](https://example.test/contract)."):
        return json.dumps({"pull_request": 2839, "comments": [{
            "id": comment_id, "user": {"login": "coderabbitai[bot]"},
            "body": ("<details>\n<summary>Supported by static analysis</summary>\n"
                     "Script executed:\n```bash\n" + "echo diagnostic\n" * 100 +
                     "```\n</details>\n" + issue),
        }]})

    def test_two_field_badge_archive_projects_full_body_without_repeated_heading(self) -> None:
        headline = "Before promoting this change, check that the scheduled backup runs build 6."
        explanation = "Existing backup promotion checks must remain attributable. " * 25
        explanation += "Check that the status site and the shared controller run the same build."
        self.hosted_display_run(title=headline)
        archive = self.hosted_display_archive(issue="_🩺 Stability & Availability_ | _🔵 Trivial_\n\n"
                                              f"**{headline}**\n\n{explanation}")
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": archive})
        with sqlite3.connect(self.database) as connection:
            before = list(connection.iterdump())
        history = self.records.history(2839)
        finding = history["findings"][0]
        self.assertEqual(finding["title"], headline)
        self.assertEqual(finding["detail"], "Old bounded detail")
        self.assertEqual(finding["display_detail"], explanation)
        self.assertEqual(finding["display_severity"], "Trivial")
        route = self.records.history(2879)["routes"][0]
        self.assertEqual(route["display_detail"], explanation)
        self.assertEqual(route["display_severity"], "Trivial")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(list(connection.iterdump()), before)

    def test_archived_authored_two_field_example_keeps_text_without_severity(self) -> None:
        self.hosted_display_run(title="Document the format.")
        body = ("**Document the format.**\n_Bug_ | _Major_\n"
                "This is an authored example, not provider metadata.\n"
                "_Functional Correctness_ | _Major_\n"
                "_Security & Privacy_ | _Major_\n**Authored classification**\n"
                "**Exploitability:** Difficult\n**CWE:** CWE-693\n**Keep the authored remedy.**")
        self.records.archive_imported_artifacts("display-run", {
            "hosted_comments": self.hosted_display_archive(issue=body),
        })
        finding = self.records.history(2839)["findings"][0]
        self.assertEqual(finding["title"], "Document the format.")
        self.assertIn("_Bug_ | _Major_", finding["display_detail"])
        self.assertIn("_Functional Correctness_ | _Major_", finding["display_detail"])
        self.assertIn("_Security & Privacy_ | _Major_", finding["display_detail"])
        self.assertIn("**Authored classification**", finding["display_detail"])
        self.assertIn("**Exploitability:** Difficult", finding["display_detail"])
        self.assertIsNone(finding["display_severity"])
        route = self.records.history(2879)["routes"][0]
        self.assertEqual(route["display_detail"], finding["display_detail"])
        self.assertIsNone(route["display_severity"])

    def test_invalid_and_ambiguous_comment_ids_leave_independent_archive_siblings(self) -> None:
        self.hosted_display_run()
        archive = json.loads(self.hosted_display_archive())
        valid = archive["comments"][0]
        valid["body"] = "_Bug_ | _Major_ | _Quick win_\n**Preserve the independent issue.**\nActual issue explanation."
        ambiguous = {**valid, "id": 99, "body": "**Variant one.**"}
        archive["comments"] += [None, "not a comment", {**valid, "id": False},
                                {**valid, "id": "invalid"}, {**valid, "id": "²"},
                                {**valid, "id": 100, "body": None}, ambiguous,
                                {**ambiguous, "body": "**Variant two.**"}, ambiguous]
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
        with sqlite3.connect(self.database) as connection:
            before = list(connection.iterdump())
            projected = self.records._hosted_display_titles(connection, "display-run", 2839)
        self.assertNotIn("hosted-comment:99", projected)
        history = self.records.history(2839)
        finding = history["findings"][0]
        self.assertEqual(finding["display_title"], "Preserve the independent issue.")
        self.assertEqual(finding["display_detail"], "Actual issue explanation.")
        self.assertEqual(finding["display_severity"], "Major")
        self.assertEqual(self.records.history(2879)["routes"][0]["display_severity"], "Major")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(list(connection.iterdump()), before)

    def test_invalid_archived_title_only_omits_that_finding_projection(self) -> None:
        self.bootstrap()
        first_key = "hosted-comment:4142913648"
        second_key = "hosted-comment:4142913649"
        self.records.record_run(
            run_id="invalid-title-sibling",
            source_pr=2839,
            channel="hosted",
            findings=(
                FindingObservation(source_finding_key=first_key, title="<details>"),
                FindingObservation(source_finding_key=second_key, title="<details>"),
            ),
        )
        archive = {
            "pull_request": 2839,
            "comments": [
                {
                    "id": 4142913648,
                    "user": {"login": "coderabbitai[bot]"},
                    "body": "_Bug_ | _Major_ | _Quick win_\n**Invalid archived title.**\nInvalid title detail.",
                },
                {
                    "id": 4142913649,
                    "user": {"login": "coderabbitai[bot]"},
                    "body": "_Bug_ | _Minor_ | _Quick win_\n**Preserve this sibling.**\nValid sibling detail.",
                },
            ],
        }
        self.records.archive_imported_artifacts("invalid-title-sibling", {"hosted_comments": json.dumps(archive)})

        original_segments = sqlite_hosted_capture._hosted_comment_finding_segments

        def invalid_first_title(comment_id, body):
            findings = original_segments(comment_id, body)
            if comment_id == 4142913648:
                findings[0]["title"] = "x" * 301
            return findings

        with patch.object(
            sqlite_hosted_capture,
            "_hosted_comment_finding_segments",
            side_effect=invalid_first_title,
        ):
            history = self.records.history(2839)
        findings = {finding["source_finding_key"]: finding for finding in history["findings"]}
        invalid = findings[first_key]
        self.assertNotIn("display_title", invalid)
        self.assertEqual(invalid["display_detail"], "Invalid title detail.")
        self.assertEqual(invalid["display_severity"], "Major")
        sibling = findings[second_key]
        self.assertEqual(sibling["display_title"], "Preserve this sibling.")
        self.assertEqual(sibling["display_detail"], "Valid sibling detail.")
        self.assertEqual(sibling["display_severity"], "Minor")

    @staticmethod
    def without_display_projection(history):
        result = json.loads(json.dumps(history))
        for collection in ("findings", "routes"):
            for record in result[collection]:
                for field in ("display_title", "display_title_is_excerpt", "display_detail", "display_severity"):
                    record.pop(field, None)
        for run in result["runs"]:
            run.pop("duration_seconds", None)
        return result

    def test_history_display_projection_can_be_disabled_without_changing_canonical_history(self) -> None:
        self.hosted_display_run()
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": self.hosted_display_archive()})
        self.duration_import("history-display-duration", "hosted")

        default_history = self.records.history(2839)
        self.assertEqual(
            next(finding for finding in default_history["findings"] if finding["run_id"] == "display-run")[
                "display_title"
            ],
            "Check the existing workflow request identity.",
        )
        self.assertEqual(
            next(run for run in default_history["runs"] if run["run_id"] == "history-display-duration")[
                "duration_seconds"
            ],
            90,
        )

        with patch.object(self.records, "_add_hosted_display_titles", wraps=self.records._add_hosted_display_titles) as titles, \
             patch.object(self.records, "_add_run_durations", wraps=self.records._add_run_durations) as durations:
            no_display = self.records.history(2839, include_display=False)
            batch = self.records.history_batch((2839, 2879), include_display=False)
            titles.assert_not_called()
            durations.assert_not_called()

        self.assertEqual(self.without_display_projection(default_history), no_display)
        self.assertEqual(self.without_display_projection(self.records.history(2879)), batch[2879])
        self.assertNotIn("display_title", no_display["findings"][0])
        self.assertNotIn("duration_seconds", next(
            run for run in no_display["runs"] if run["run_id"] == "history-display-duration"
        ))

    def test_native_unheaded_title_excerpt_signal_preserves_full_body(self) -> None:
        paragraph = "An existing workflow must validate the incoming request identity before accepting repeated admission. " * 5
        captured = sqlite_hosted_capture._hosted_comment_finding_segments(4142913648, paragraph)[0]
        self.hosted_display_run(title=captured["title"])
        self.records.start_attempt(attempt_id="native-unheaded", source_pr=2839, channel="hosted")
        archive = json.loads(self.hosted_display_archive())
        archive["comments"][0]["body"] = paragraph
        self.records.finish_attempt("native-unheaded", state="completed", artifacts={"hosted_comments": json.dumps(archive)})
        self.records.link_attempt_run("native-unheaded", "display-run")
        history = self.records.history(2839)
        finding = history["findings"][0]
        self.assertEqual(finding["title"], paragraph[:300])
        self.assertTrue(finding["display_title_is_excerpt"])
        self.assertEqual(finding["display_detail"], paragraph.strip())
        self.assertTrue(self.records.history(2879)["routes"][0]["display_title_is_excerpt"])

    def test_archived_atx_heading_projects_clean_title_and_only_body(self) -> None:
        self.hosted_display_run()
        archive = self.hosted_display_archive(issue="### Preserve retry identity.\n\nKeep the substantive explanation.")
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": archive})
        finding = self.records.history(2839)["findings"][0]
        self.assertEqual(finding["title"], "<details>")
        self.assertEqual(finding["display_title"], "Preserve retry identity.")
        self.assertEqual(finding["display_detail"], "Keep the substantive explanation.")
        self.assertNotIn("display_title_is_excerpt", finding)
        route = self.records.history(2879)["routes"][0]
        self.assertEqual(route["display_title"], finding["display_title"])
        self.assertEqual(route["display_detail"], finding["display_detail"])

    def test_authored_heading_does_not_acquire_excerpt_signal(self) -> None:
        self.hosted_display_run(title="Preserve the actual authored headline.")
        archive = json.loads(self.hosted_display_archive())
        archive["comments"][0]["body"] = "**Preserve the actual authored headline.**\nDistinct issue prose."
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
        finding = self.records.history(2839)["findings"][0]
        self.assertNotIn("display_title_is_excerpt", finding)
        self.assertEqual(finding["display_detail"], "Distinct issue prose.")

    def test_structural_security_classification_override_requires_same_archive(self) -> None:
        self.hosted_display_run(title="Authorization Bypass")
        archive = json.loads(self.hosted_display_archive())
        archive["comments"][0]["body"] = (
            "_🔒 Security & Privacy_ | _🛡️ Detected with Advanced Tier_ | _🟠 Major_ | _🏗️ Heavy lift_\n"
            "**Authorization Bypass**\n**Exploitability:** Difficult\n**CWE:** CWE-693\n"
            "**Require durability proof for the existing-marker outcome.**\nKeep the proof.")
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
        finding = self.records.history(2839)["findings"][0]
        self.assertEqual(finding["title"], "Authorization Bypass")
        self.assertEqual(finding["display_title"], "Require durability proof for the existing-marker outcome.")
        self.assertEqual(self.records.history(2879)["routes"][0]["display_title"], finding["display_title"])

    def test_aggregate_long_first_body_keeps_later_section_with_excerpt_notice(self) -> None:
        self.hosted_display_run(title="First actual issue.")
        archive = json.loads(self.hosted_display_archive())
        archive["comments"][0]["body"] = (
            "**First actual issue.**\n" + "Long first section prose. " * 400 +
            "\n<!-- cr-comment:v1:" + "a" * 24 + " -->\n"
            "**Second actual issue.**\nLater issue explanation must remain visible.\n"
            "<!-- cr-comment:v1:" + "b" * 24 + " -->")
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
        history = self.records.history(2839)
        detail = history["findings"][0]["display_detail"]
        self.assertLessEqual(len(detail), 8000)
        self.assertIn("Provider section 1", detail)
        self.assertIn("Provider section 2: Second actual issue.", detail)
        self.assertIn("Later issue explanation must remain visible.", detail)
        self.assertIn("Section excerpt truncated", detail)
        self.assertEqual(history["runs"][0]["counts"], {"found": 1, "accepted": 0, "routed": 1})
        many = [{"title": "Authored issue " + "x" * 280, "display_detail": "Long issue body. " * 600} for _ in range(1000)]
        bounded = _hosted_aggregate_display_detail(many, many[0]["title"])
        self.assertLessEqual(len(bounded), 8000)
        self.assertIn("Omitted ", bounded)
        self.assertIn("provider sections; see original source comment.", bounded)
        self.assertIn("Historical aggregate: 1000", bounded)

    def test_existing_legacy_aggregate_exposes_both_sections_without_new_findings(self) -> None:
        self.hosted_display_run(title="First actual issue.")
        for second_severity, expected in (("Major", "Major"), ("Minor", None)):
            with self.subTest(second_severity=second_severity):
                archive = json.loads(self.hosted_display_archive())
                archive["comments"][0]["body"] = (
                    "_Bug_ | _Major_ | _Quick win_\n**First actual issue.**\nFirst issue explanation.\n"
                    "<!-- cr-comment:v1:" + "a" * 24 + " -->\n"
                    f"_Bug_ | _{second_severity}_ | _Quick win_\n**Second actual issue.**\nSecond issue explanation.\n"
                    "<!-- cr-comment:v1:" + "b" * 24 + " -->")
                with sqlite3.connect(self.database) as connection:
                    connection.execute("DELETE FROM imported_artifacts WHERE run_id = 'display-run'")
                self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
                with sqlite3.connect(self.database) as connection:
                    before = list(connection.iterdump())
                history = self.records.history(2839)
                self.assertEqual(len(history["findings"]), 1)
                self.assertEqual(history["runs"][0]["counts"], {"found": 1, "accepted": 0, "routed": 1})
                self.assertEqual(len(history["decisions"]), 1)
                finding = history["findings"][0]
                self.assertEqual(finding["title"], "First actual issue.")
                self.assertEqual(finding["source_finding_key"], "hosted-comment:4142913648")
                self.assertEqual(finding["display_severity"], expected)
                self.assertEqual(finding["display_detail"],
                                 "Historical aggregate: 2 provider findings were recorded as one item\n\n"
                                 "**Provider section 1**\n\nFirst issue explanation.\n\n"
                                 "**Provider section 2: Second actual issue.**\n\nSecond issue explanation.")
                self.assertEqual(self.records.history(2879)["routes"][0]["display_detail"], finding["display_detail"])
                with sqlite3.connect(self.database) as connection:
                    self.assertEqual(list(connection.iterdump()), before)

    def test_malformed_hosted_sibling_does_not_hide_exact_valid_projection(self) -> None:
        self.hosted_display_run()
        archive = json.loads(self.hosted_display_archive())
        archive["comments"].append({"id": 4144044340, "user": {"login": "coderabbitai[bot]"},
                                   "body": "**Invalid sibling.**\n<!-- cr-comment:v1:" + "a" * 24 + " -->\nUnmarked tail."})
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
        with sqlite3.connect(self.database) as connection:
            before = list(connection.iterdump())
        history = self.records.history(2839)
        finding = history["findings"][0]
        self.assertEqual(finding["display_title"], "Check the existing workflow request identity.")
        self.assertIn("Keep `requestId` consistent", finding["display_detail"])
        self.assertEqual(self.records.history(2879)["routes"][0]["display_title"], finding["display_title"])
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(list(connection.iterdump()), before)

    def test_archived_security_metadata_title_has_specific_read_projection(self) -> None:
        self.hosted_display_run(title="Broken Authentication")
        archive = json.loads(self.hosted_display_archive())
        archive["comments"][0]["body"] = (
            "_🔒 Security & Privacy_ | _🛡️ Detected with Advanced Tier_ | _🟠 Major_ | _🏗️ Heavy lift_\n"
            "**Broken Authentication**\n**Reachability:** External\n**Exploitability:** Difficult\n**CWE:** CWE-294\n"
            "**Quarantine replay admission after marker loss.** Keep the fence closed.")
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
        finding = self.records.history(2839)["findings"][0]
        self.assertEqual(finding["title"], "Broken Authentication")
        self.assertEqual(finding["display_title"], "Quarantine replay admission after marker loss.")
        self.assertEqual(finding["display_detail"], "Keep the fence closed.")
        self.assertEqual(self.records.history(2879)["routes"][0]["display_title"], finding["display_title"])

    def test_hosted_severity_history_and_incoming_routes_are_read_only(self) -> None:
        self.hosted_display_run()
        for badge, expected in (("**Major**", "Major"), ("No badge", None),
                                ("**Major**\n**Minor**", None)):
            with self.subTest(badge=badge):
                archive = json.loads(self.hosted_display_archive())
                archive["comments"][0]["body"] = badge + "\n**Validate the target.**\nIssue explanation."
                # Set up each retained archive before the protected read snapshot.
                with sqlite3.connect(self.database) as connection:
                    connection.execute("DELETE FROM imported_artifacts WHERE run_id = 'display-run'")
                self.records.archive_imported_artifacts("display-run", {"hosted_comments": json.dumps(archive)})
                with sqlite3.connect(self.database) as connection:
                    before = list(connection.iterdump())
                with patch.object(self.records, "_write_connection", side_effect=AssertionError("read wrote")):
                    source = self.records.history(2839)
                    incoming = self.records.history(2879)
                    routes = self.records.list_routes(status="all", source_pr=2839)
                self.assertEqual(source["findings"][0]["display_severity"], expected)
                for route in [*source["routes"], *incoming["routes"], *routes]:
                    self.assertEqual(route["display_severity"], expected)
                self.assertEqual(source["findings"][0]["title"], "<details>")
                with sqlite3.connect(self.database) as connection:
                    self.assertEqual(list(connection.iterdump()), before)

    def test_hosted_display_reads_full_archive_once_and_preserves_history(self) -> None:
        self.hosted_display_run()
        archive = self.hosted_display_archive()
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": archive})
        with sqlite3.connect(self.database) as connection:
            before = list(connection.iterdump())
        with patch.object(self.records, "_write_connection", side_effect=AssertionError("read wrote")), \
             patch.object(self.records, "_hosted_display_titles", wraps=self.records._hosted_display_titles) as parser:
            source = self.records.history(2839)
            self.assertEqual(parser.call_count, 1)
            target = self.records.history(2879)
            worklist = self.records.list_routes(target_pr=2879)
        finding = source["findings"][0]
        self.assertEqual(finding["title"], "<details>")
        self.assertEqual(finding["detail"], "Old bounded detail")
        self.assertEqual(finding["display_title"], "Check the existing workflow request identity.")
        for route in (source["routes"][0], target["routes"][0], worklist[0]):
            self.assertEqual(route["display_title"], finding["display_title"])
            self.assertEqual(route["display_detail"], finding["display_detail"])
            self.assertEqual(route["finding_id"], finding["finding_id"])
            self.assertEqual(route["source_pr"], 2839)
            self.assertEqual(route["target_pr"], 2879)
        self.assertEqual(worklist[0]["title"], "<details>")
        self.assertEqual(source["runs"][0]["counts"], {"found": 1, "accepted": 0, "routed": 1})
        self.assertTrue(source["runs"][0]["finalized"])
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(list(connection.iterdump()), before)

    def test_hosted_display_reads_exact_native_attempt_archive(self) -> None:
        self.hosted_display_run()
        self.records.start_attempt(attempt_id="native-display", source_pr=2839, channel="hosted")
        imported = json.loads(self.hosted_display_archive())["comments"][0]
        comment = {"databaseId": imported["id"], "author": imported["user"], "body": imported["body"]}
        native = json.dumps({"comments": [], "review_threads": [{"comments": {"nodes": [comment]}}]})
        self.records.finish_attempt("native-display", state="completed", artifacts={"hosted_comments": native})
        self.records.link_attempt_run("native-display", "display-run")
        self.assertEqual(self.records.history(2839)["findings"][0]["display_title"],
                         "Check the existing workflow request identity.")

    def test_hosted_display_omits_missing_ambiguous_or_unusable_archive(self) -> None:
        cases = [None, "not json", "[]", self.hosted_display_archive(comment_id=9),
                 self.hosted_display_archive(issue=""),
                 self.hosted_display_archive(issue="<!-- cr-comment:v1:bad -->")]
        wrong_pr = json.loads(self.hosted_display_archive())
        wrong_pr["pull_request"] = 2879
        cases.append(json.dumps(wrong_pr))
        conflicting = json.loads(self.hosted_display_archive())
        conflicting["comments"].append({**conflicting["comments"][0], "body": "Different issue."})
        cases.append(json.dumps(conflicting))
        for index, archive in enumerate(cases):
            with self.subTest(index=index):
                run_id = f"invalid-display-{index}"
                self.hosted_display_run(run_id, routed=False)
                if archive is not None:
                    self.records.archive_imported_artifacts(
                        run_id, {"hosted_comments": archive if archive != "not json" else "{}"}
                    )
                    if archive == "not json":
                        # Reproduce corrupt retained evidence only in this disposable fixture.
                        with sqlite3.connect(self.database) as connection:
                            connection.execute("UPDATE imported_artifacts SET content = ? WHERE run_id = ?",
                                               (archive, run_id))
                finding = next(item for item in self.records.history(2839)["findings"] if item["run_id"] == run_id)
                self.assertNotIn("display_title", finding)
                self.assertEqual(finding["title"], "<details>")

    def test_hosted_display_matches_each_atomic_fingerprint(self) -> None:
        self.bootstrap()
        fingerprints = ("a" * 24, "b" * 24)
        keys = ["hosted-comment:4142913648:fingerprint:" + fingerprint for fingerprint in fingerprints]
        self.records.record_run(
            run_id="atomic-display", source_pr=2839, channel="hosted",
            findings=tuple(FindingObservation(source_finding_key=key, title="<details>") for key in keys),
        )
        archive = json.loads(self.hosted_display_archive())
        archive["comments"][0]["body"] = (
            "<details>\n</details>\nFirst issue.\n<!-- cr-comment:v1:" + fingerprints[0] + " -->\n---\n"
            "<details>\n</details>\nSecond issue.\n<!-- cr-comment:v1:" + fingerprints[1] + " -->"
        )
        self.records.archive_imported_artifacts("atomic-display", {"hosted_comments": json.dumps(archive)})
        with patch.object(self.records, "_hosted_display_titles", wraps=self.records._hosted_display_titles) as parser:
            history = self.records.history(2839)
            self.assertEqual(parser.call_count, 1)
        self.assertEqual({finding["source_finding_key"]: finding["display_title"] for finding in history["findings"]},
                         dict(zip(keys, ("First issue.", "Second issue."))))
        self.assertEqual(history["runs"][0]["counts"]["found"], 2)

    def test_hosted_display_keeps_proper_title_and_exact_fingerprint_key(self) -> None:
        self.hosted_display_run(title="Existing meaningful title.")
        self.records.archive_imported_artifacts("display-run", {"hosted_comments": self.hosted_display_archive()})
        finding = self.records.history(2839)["findings"][0]
        self.assertNotIn("display_title", finding)
        self.assertEqual(finding["title"], "Existing meaningful title.")
        self.assertEqual(finding["display_detail"],
                         "Check the existing workflow request identity.\n\n"
                         "Keep `requestId` consistent with [the contract](https://example.test/contract).")
        incoming = self.records.history(2879)["routes"][0]
        self.assertEqual(incoming["title"], finding["title"])
        self.assertEqual(incoming["title"], self.records.list_routes(target_pr=2879)[0]["title"])
        self.assertEqual(incoming["finding_id"], finding["finding_id"])
        self.hosted_display_run("grouped-old", routed=False)
        archive = json.loads(self.hosted_display_archive())
        archive["comments"][0]["body"] = (
            "First issue.\n<!-- cr-comment:v1:aaaaaaaaaaaaaaaaaaaaaaaa -->\n---\n"
            "Second issue.\n<!-- cr-comment:v1:bbbbbbbbbbbbbbbbbbbbbbbb -->"
        )
        self.records.archive_imported_artifacts("grouped-old", {"hosted_comments": json.dumps(archive)})
        finding = next(item for item in self.records.history(2839)["findings"] if item["run_id"] == "grouped-old")
        # The old aggregate remains one record, with both archived sections explicitly labelled.
        self.assertEqual(finding["source_finding_key"], "hosted-comment:4142913648")
        self.assertEqual(finding["title"], "<details>")
        self.assertEqual(finding["display_title"], "First issue.")
        self.assertIn("Historical aggregate: 2 provider findings were recorded as one item", finding["display_detail"])
        self.assertIn("Second issue.", finding["display_detail"])

    def test_new_subagent_start_bounds_coverage_before_persisting(self) -> None:
        self.bootstrap()
        self.records.start_attempt(
            attempt_id="coverage-200",
            source_pr=2893,
            channel="subagent",
            metadata={"coverage_limits": ["n" * 200]},
        )
        with self.assertRaisesRegex(ReviewRecordsError, "at most 200"):
            self.records.start_attempt(
                attempt_id="coverage-201",
                source_pr=2893,
                channel="subagent",
                metadata={"coverage_limits": ["n" * 201]},
            )
        self.assertEqual([item["attempt_id"] for item in self.records.attempt_history(2893)], ["coverage-200"])

    def test_existing_long_subagent_note_completes_losslessly_only_for_exact_source(self) -> None:
        self.bootstrap()
        note = "Original audit coverage and context retained. " * 7
        metadata = {"reviewer": "Sol medium", "scope": "narrow", "coverage_limits": [note]}
        # Reproduce metadata accepted by the old start path, without rewriting it.
        with patch.object(sqlite_review_records, "_coverage_limits", return_value=(note,)):
            self.records.start_attempt(
                attempt_id="legacy-long-note",
                source_pr=2893,
                channel="subagent",
                candidate_sha="a" * 40,
                metadata=metadata,
            )
        attempt = self.records.attempt("legacy-long-note")
        with sqlite3.connect(self.database) as connection:
            stored = connection.execute(
                "SELECT metadata_json FROM review_attempts WHERE attempt_id = ?", ("legacy-long-note",)
            ).fetchone()[0]
        arguments = {
            "run_id": "legacy-long-note",
            "source_pr": 2893,
            "channel": "subagent",
            "source_head": "a" * 40,
            "reviewer": "Sol medium",
            "scope": "narrow",
            "coverage_limits": [note],
            "started_at": attempt["started_at"],
            "findings": (),
            "source_decisions": (),
        }
        for changed in (
            {"run_id": "forged-note"},
            {"source_pr": 2894},
            {"source_head": "b" * 40},
            {"reviewer": "other reviewer"},
            {"scope": "broad"},
            {"coverage_limits": [note + " altered"]},
            {"started_at": "2026-09-01T00:00:00Z"},
        ):
            with self.subTest(changed=changed), self.assertRaisesRegex(ReviewRecordsError, "at most 200"):
                self.records.import_completed_run(**{**arguments, **changed})
        self.assertEqual(self.records.history(2893)["runs"], [])
        self.records.import_completed_run(**arguments)
        self.assertEqual(self.records.history(2893)["runs"][0]["coverage_limits"], [note])
        self.assertEqual(self.records.attempt("legacy-long-note")["metadata"], metadata)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT metadata_json FROM review_attempts WHERE attempt_id = ?", ("legacy-long-note",)
                ).fetchone()[0],
                stored,
            )

    def test_retained_coverage_still_checks_text_and_secrets(self) -> None:
        for note in ("n" * 201 + "\n", "n" * 201 + " token=ghp_" + "A" * 30):
            with self.subTest(note=note), self.assertRaises(ReviewRecordsError):
                sqlite_review_records._coverage_limits([note], retained=[note])

    def test_retained_coverage_is_string_validated_and_capped_at_1000(self) -> None:
        note = "n" * 1000
        self.assertEqual(sqlite_review_records._coverage_limits([note], retained=[note]), (note,))
        with self.assertRaisesRegex(ReviewRecordsError, "at most 1000"):
            sqlite_review_records._coverage_limits([note + "n"], retained=[note + "n"])
        with self.assertRaisesRegex(ReviewRecordsError, "must be text"):
            sqlite_review_records._coverage_limits([123], retained=[123])

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

    def test_bootstrap_raises_controller_writer_fence(self) -> None:
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE controller_metadata SET min_writer_build = 2")

        old_writer = SqliteStateStore(self.database, writer_build=2)
        self.assertEqual(old_writer.status()["min_writer_build"], 2)
        self.bootstrap()

        self.assertEqual(SqliteStateStore(self.database).status()["min_writer_build"], WRITER_BUILD)
        with self.assertRaisesRegex(Exception, f"requires writer build {WRITER_BUILD}"):
            old_writer.update(lambda state: state)

    def test_finding_severity_accepts_only_exact_display_labels_or_unspecified(self) -> None:
        for severity in (None, "Critical", "Major", "Minor", "Trivial"):
            with self.subTest(severity=severity):
                self.assertEqual(
                    FindingObservation("severity-key", "Finding", display_severity=severity).display_severity,
                    severity,
                )
        for severity in ("major", "Blocker", ["Major"], {"label": "Major"}):
            with self.subTest(severity=severity), self.assertRaisesRegex(
                ReviewRecordsError,
                "display severity is invalid",
            ):
                FindingObservation("severity-key", "Finding", display_severity=severity)  # type: ignore[arg-type]

    def test_archived_json_rejects_non_finite_numbers(self) -> None:
        for kind, content in (
            ("cli_events", '{"type":"finding","score":NaN}\n'),
            ("hosted_review", '{"score":Infinity}'),
        ):
            with self.subTest(kind=kind), self.assertRaisesRegex(ReviewRecordsError, "JSON"):
                _archive_artifact(kind, content)

    def test_archived_text_rejects_redaction_expansion_over_stored_byte_limit(self) -> None:
        content = "secret=x " * 12_000
        self.assertLessEqual(len(content.encode("utf-8")), 128 * 1024)

        with self.assertRaisesRegex(ReviewRecordsError, "cli_diagnostic exceeds its evidence size limit"):
            _archive_artifact("cli_diagnostic", content)

    def test_archived_json_rejects_normalization_expansion_over_stored_byte_limit(self) -> None:
        content = json.dumps({"body": "é" * 700_000}, ensure_ascii=False)
        self.assertLessEqual(len(content.encode("utf-8")), 4 * 1024 * 1024)

        with self.assertRaisesRegex(ReviewRecordsError, "hosted_review exceeds its evidence size limit"):
            _archive_artifact("hosted_review", content)

    def test_archived_json_parse_recursion_is_wrapped_without_leaking_raw_error(self) -> None:
        for kind, content, message in (
            ("cli_events", "{}\n", "CLI events must be JSON objects, one per line"),
            ("hosted_review", "{}", "hosted_review must be JSON"),
        ):
            with (
                self.subTest(kind=kind),
                patch.object(sqlite_review_records.json, "loads", side_effect=RecursionError("parse recursion")),
                self.assertRaisesRegex(ReviewRecordsError, f"^{message}$") as raised,
            ):
                _archive_artifact(kind, content)
            self.assertIsInstance(raised.exception.__cause__, RecursionError)

    def test_archived_json_scrub_and_serialization_recursion_are_wrapped(self) -> None:
        nested: object = {}
        for _ in range(sys.getrecursionlimit() + 10):
            nested = {"nested": nested}

        with (
            patch.object(sqlite_review_records.json, "loads", return_value=nested),
            self.assertRaisesRegex(ReviewRecordsError, "^hosted_review JSON is too deeply nested$") as scrub_raised,
        ):
            _archive_artifact("hosted_review", "{}")
        self.assertIsInstance(scrub_raised.exception.__cause__, RecursionError)

        with (
            patch.object(sqlite_review_records, "_json", side_effect=RecursionError("serialization recursion")),
            self.assertRaisesRegex(
                ReviewRecordsError, "^hosted_review JSON is too deeply nested$"
            ) as serialization_raised,
        ):
            _archive_artifact("hosted_review", "{}")
        self.assertIsInstance(serialization_raised.exception.__cause__, RecursionError)

    def test_cli_event_archive_splits_only_on_ascii_newlines_and_accepts_crlf(self) -> None:
        first = {"type": "finding", "body": "before\u2028middle\u2029after"}
        second = {"type": "complete", "status": "review_completed"}
        content = json.dumps(first, ensure_ascii=False) + "\r\n" + json.dumps(second) + "\n"

        archived, source_digest, redactions = _archive_artifact("cli_events", content)

        self.assertEqual([json.loads(line) for line in archived.splitlines()], [first, second])
        self.assertEqual(source_digest, hashlib.sha256(content.encode("utf-8")).hexdigest())
        self.assertEqual(redactions, 0)

    def test_attempt_write_and_read_database_errors_are_wrapped(self) -> None:
        self.bootstrap()
        with (
            patch.object(self.records, "_write_connection", side_effect=sqlite3.OperationalError("write failed")),
            self.assertRaisesRegex(ReviewRecordsError, "start_attempt") as raised,
        ):
            self.records.start_attempt(attempt_id="attempt-write-error", source_pr=2890, channel="cli")
        self.assertIsInstance(raised.exception.__cause__, sqlite3.OperationalError)

        with (
            patch.object(self.records, "_connect", side_effect=sqlite3.DatabaseError("read failed")),
            self.assertRaisesRegex(ReviewRecordsError, "attempt_history") as raised,
        ):
            self.records.attempt_history(2890)
        self.assertIsInstance(raised.exception.__cause__, sqlite3.DatabaseError)

    def test_attempt_missing_has_a_specific_error_type(self) -> None:
        self.bootstrap()

        with self.assertRaises(AttemptNotFound) as raised:
            self.records.attempt("missing-attempt")

        self.assertEqual(str(raised.exception), "review attempt does not exist")

    def test_partial_schema_without_metadata_is_incompatible_not_unbootstrapped(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            connection.execute("DROP TABLE review_records_metadata")
        with self.assertRaisesRegex(RecordsSchemaIncompatible, "schema is partial"):
            self.records.attempt("partial-schema-attempt")

    def test_attempt_distinguishes_unbootstrapped_and_incompatible_records_schemas(self) -> None:
        with self.assertRaises(RecordsNotBootstrapped):
            self.records.attempt("attempt-on-unbootstrapped-database")

        self.bootstrap()
        for schema_version in (4, 5):
            with self.subTest(schema_version=schema_version), sqlite3.connect(self.database) as connection:
                connection.execute(
                    "UPDATE review_records_metadata SET records_schema_version = ? WHERE singleton = 1",
                    (schema_version,),
                )
            with self.subTest(schema_version=schema_version), self.assertRaises(RecordsSchemaIncompatible):
                self.records.attempt("attempt-on-old-schema")

        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 999 WHERE singleton = 1")
        with self.assertRaises(RecordsSchemaIncompatible):
            self.records.attempt("attempt-on-unknown-schema")

    def test_attempt_metadata_errors_are_not_schema_incompatibility(self) -> None:
        self.bootstrap()
        attempt_id = "attempt-malformed-metadata"
        self.records.start_attempt(attempt_id=attempt_id, source_pr=2893, channel="hosted")
        for serialized, expected_error in (
            ("{", "review attempt metadata is malformed"),
            ("[]", "review attempt metadata is not an object"),
        ):
            with self.subTest(serialized=serialized), sqlite3.connect(self.database) as connection:
                connection.execute(
                    "UPDATE review_attempts SET metadata_json = ? WHERE attempt_id = ?",
                    (serialized, attempt_id),
                )

            with self.subTest(serialized=serialized), self.assertRaises(ReviewRecordsError) as raised:
                self.records.attempt(attempt_id)

            self.assertIs(type(raised.exception), ReviewRecordsError)
            self.assertEqual(str(raised.exception), expected_error)

    def test_start_attempt_rejects_nonserializable_metadata_as_review_records_error(self) -> None:
        self.bootstrap()
        with self.assertRaisesRegex(ReviewRecordsError, "metadata must be JSON") as raised:
            self.records.start_attempt(
                attempt_id="attempt-invalid-metadata",
                source_pr=2890,
                channel="cli",
                metadata={"unsupported": object()},
            )
        self.assertIsInstance(raised.exception.__cause__, TypeError)

    def test_history_validates_attempt_metadata_once_as_an_object(self) -> None:
        self.bootstrap()
        self.records.start_attempt(attempt_id="attempt-corrupt-metadata", source_pr=2890, channel="cli")
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE review_attempts SET metadata_json = ? WHERE attempt_id = ?",
                ("{", "attempt-corrupt-metadata"),
            )
        with self.assertRaisesRegex(ReviewRecordsError, "metadata is malformed"):
            self.records.history(2890)

        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE review_attempts SET metadata_json = ? WHERE attempt_id = ?",
                ("[]", "attempt-corrupt-metadata"),
            )
        with self.assertRaisesRegex(ReviewRecordsError, "metadata is not an object"):
            self.records.history(2890)

    def test_attempt_archives_complete_json_events_and_redacts_credentials(self) -> None:
        self.bootstrap()
        start = self.records.start_attempt(
            attempt_id="run.archive-1",
            source_pr=2890,
            channel="cli",
            candidate_sha="a" * 40,
            started_at="2026-09-29T01:00:00Z",
            metadata={"published_head": "a" * 40},
        )
        self.assertFalse(start["idempotent_replay"])
        self.assertTrue(
            self.records.start_attempt(
                attempt_id="run.archive-1",
                source_pr=2890,
                channel="cli",
                candidate_sha="a" * 40,
                started_at="2026-09-29T01:00:00Z",
                metadata={"published_head": "a" * 40},
            )["idempotent_replay"]
        )
        code_identifier = "ReviewCandidateImmutablePublicationBindingForExactHead" * 2
        events = (
            json.dumps(
                {
                    "type": "finding",
                    "body": "token=ghp_" + "A" * 30,
                    "symbol": code_identifier,
                    "summary": "The complete non-secret review explanation remains available.",
                    "password": "p",
                    "api_key": "k1",
                    "nested": {"access_token": "t"},
                }
            )
            + "\n"
        )
        finish_args = {
            "state": "completed",
            "finished_at": "2026-09-29T01:01:00Z",
            "duration_seconds": 60,
            "exit_status": 0,
            "artifacts": {"cli_events": events, "cli_diagnostic": "provider connected"},
        }
        self.assertFalse(self.records.finish_attempt("run.archive-1", **finish_args)["idempotent_replay"])
        self.assertTrue(self.records.finish_attempt("run.archive-1", **finish_args)["idempotent_replay"])
        with sqlite3.connect(self.database) as connection:
            archived = connection.execute(
                "SELECT content, source_sha256, redactions FROM review_artifacts "
                "WHERE attempt_id = 'run.archive-1' AND kind = 'cli_events'"
            ).fetchone()
        archived_event = json.loads(archived[0])
        self.assertEqual(archived_event["body"], "token=[redacted credential]")
        self.assertEqual(archived_event["password"], "[redacted credential]")
        self.assertEqual(archived_event["api_key"], "[redacted credential]")
        self.assertEqual(archived_event["nested"]["access_token"], "[redacted credential]")
        self.assertEqual(archived_event["symbol"], code_identifier)
        self.assertEqual(archived_event["summary"], "The complete non-secret review explanation remains available.")
        self.assertEqual(archived[2], 4)
        self.assertEqual(archived[1], hashlib.sha256(events.encode("utf-8")).hexdigest())
        self.assertEqual(self.records.attempt_history(2890)[0]["state"], "completed")
        with self.assertRaisesRegex(ReviewRecordsError, "different content"):
            self.records.finish_attempt("run.archive-1", **{**finish_args, "duration_seconds": 61})

    def test_omitted_attempt_timestamps_are_ignored_only_for_exact_replays(self) -> None:
        self.bootstrap()
        attempt_id = "run.omitted-timestamps"
        first_start = self.records.start_attempt(
            attempt_id=attempt_id,
            source_pr=2890,
            channel="cli",
            candidate_sha="a" * 40,
            metadata={"published_head": "a" * 40},
        )
        self.assertFalse(first_start["idempotent_replay"])
        stored_start = self.records.attempt(attempt_id)["started_at"]
        self.assertTrue(
            self.records.start_attempt(
                attempt_id=attempt_id,
                source_pr=2890,
                channel="cli",
                candidate_sha="a" * 40,
                metadata={"published_head": "a" * 40},
            )["idempotent_replay"]
        )
        self.assertEqual(self.records.attempt(attempt_id)["started_at"], stored_start)
        with self.assertRaisesRegex(ReviewRecordsError, "different content or is terminal"):
            self.records.start_attempt(
                attempt_id=attempt_id,
                source_pr=2890,
                channel="cli",
                candidate_sha="a" * 40,
                started_at="2026-09-29T01:00:00Z",
                metadata={"published_head": "a" * 40},
            )

        first_finish = self.records.finish_attempt(attempt_id, state="failed", diagnostic="provider closed")
        self.assertFalse(first_finish["idempotent_replay"])
        stored_finish = self.records.attempt(attempt_id)["finished_at"]
        self.assertTrue(
            self.records.finish_attempt(attempt_id, state="failed", diagnostic="provider closed")["idempotent_replay"]
        )
        with self.assertRaisesRegex(ReviewRecordsError, "terminal attempt replay has different content"):
            self.records.finish_attempt(
                attempt_id,
                state="failed",
                finished_at="2026-09-29T02:00:00Z",
                diagnostic="provider closed",
            )
        self.assertEqual(self.records.attempt(attempt_id)["finished_at"], stored_finish)

    def test_correct_source_decision_wraps_sqlite_write_failures(self) -> None:
        self.bootstrap()
        for error, message in (
            (
                sqlite3.IntegrityError("injected constraint failure"),
                "source decision conflicts with existing immutable records",
            ),
            (sqlite3.OperationalError("injected write failure"), "cannot record SQLite source decision"),
        ):
            with (
                self.subTest(error=type(error).__name__),
                patch.object(self.records, "_write_connection", side_effect=error),
                self.assertRaisesRegex(ReviewRecordsError, message),
            ):
                self.records.correct_source_decision(
                    "run.correction-sql-error",
                    "cli-run:run.correction-sql-error:finding:1",
                    supersedes_id="original",
                    correction_id="correction",
                    decision="accepted",
                    actor="reviewer",
                    reason="corrected",
                )

    def test_v4_upgrade_is_atomic_and_fences_older_writers(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            connection.execute("ALTER TABLE finding_observations DROP COLUMN display_severity")
            connection.execute("DROP TABLE review_artifacts")
            connection.execute("DROP TABLE review_attempts")
            connection.execute("DROP TABLE source_decision_corrections")
            connection.execute("DROP TABLE provider_origins")
            connection.execute("DROP TABLE imported_artifacts")
            connection.execute("DROP TABLE historical_gap_artifacts")
            connection.execute("DROP TABLE historical_provider_gaps")
            connection.execute("DROP TABLE source_finding_resolutions")
            connection.execute("DROP TABLE source_finding_resolution_corrections")
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 4, min_writer_build = 2")
            connection.execute("UPDATE controller_metadata SET min_writer_build = 2")
        old_writer = SqliteStateStore(self.database, writer_build=2)
        self.assertEqual(old_writer.status()["min_writer_build"], 2)
        self.records.migrate()
        self.records.migrate()
        self.assertEqual(SqliteStateStore(self.database).status()["min_writer_build"], WRITER_BUILD)
        with self.assertRaisesRegex(Exception, f"requires writer build {WRITER_BUILD}"):
            old_writer.update(lambda state: state)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT records_schema_version FROM review_records_metadata").fetchone()[0], 9
            )

    def test_v5_upgrade_preserves_attempts_and_fences_previous_writer(self) -> None:
        self.bootstrap()
        self.records.start_attempt(attempt_id="run.previous-v5", source_pr=2893, channel="cli")
        self.records.finish_attempt("run.previous-v5", state="rate_limited")
        with sqlite3.connect(self.database) as connection:
            connection.execute("ALTER TABLE finding_observations DROP COLUMN display_severity")
            connection.execute("DROP TABLE imported_artifacts")
            connection.execute("DROP TABLE provider_origins")
            connection.execute("DROP TABLE historical_gap_artifacts")
            connection.execute("DROP TABLE historical_provider_gaps")
            connection.execute("DROP TABLE source_finding_resolutions")
            connection.execute("DROP TABLE source_finding_resolution_corrections")
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 5, min_writer_build = 3")
            connection.execute("UPDATE controller_metadata SET min_writer_build = 3")
        self.records.migrate()
        self.records.migrate()
        self.assertEqual(self.records.attempt_history(2893)[0]["state"], "rate_limited")
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT records_schema_version FROM review_records_metadata").fetchone()[0], 9
            )
        with self.assertRaisesRegex(Exception, rf"requires writer build {WRITER_BUILD}\b"):
            SqliteStateStore(self.database, writer_build=3).update(lambda state: state)

    def test_current_version_migration_repairs_controller_writer_fence(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE controller_metadata SET min_writer_build = 2")

        old_writer = SqliteStateStore(self.database, writer_build=2)
        self.assertEqual(old_writer.status()["min_writer_build"], 2)
        self.records.migrate()

        self.assertEqual(SqliteStateStore(self.database).status()["min_writer_build"], WRITER_BUILD)
        with self.assertRaisesRegex(Exception, f"requires writer build {WRITER_BUILD}"):
            old_writer.update(lambda state: state)

    def test_history_batch_uses_one_read_snapshot_across_prs(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(connection.execute("PRAGMA journal_mode=WAL").fetchone()[0], "wal")
        original_history = self.records.history

        def read_then_write(pr, **kwargs):
            selected = original_history(pr, **kwargs)
            if pr == 2890:
                self.records.record_run(
                    run_id="later-run",
                    source_pr=2893,
                    channel="subagent",
                    findings=(),
                    started_at="2026-09-29T01:00:00Z",
                    finished_at="2026-09-29T01:01:00Z",
                )
            return selected

        with patch.object(self.records, "history", side_effect=read_then_write):
            batch = self.records.history_batch((2890, 2893))
        self.assertEqual(batch[2893]["runs"], [])
        self.assertEqual(len(self.records.history(2893)["runs"]), 1)

    def test_provider_origin_is_durable_idempotent_and_conflict_checked(self) -> None:
        self.bootstrap()
        self.records.import_completed_run(
            run_id="provider-clean-1",
            source_pr=2893,
            channel="hosted",
            findings=(),
            source_decisions=(),
            reviewer="CodeRabbit Hosted",
            started_at="2026-09-29T01:00:00Z",
            finished_at="2026-09-29T01:01:00Z",
        )
        origin = {
            "repository": "BenHook1013/FireMUD",
            "source_pr": 2893,
            "channel": "hosted",
            "provider_id": "trigger:987654321",
            "checkpoint_id": 123456789,
            "checkpoint_fingerprint": "a" * 64,
            "run_id": "provider-clean-1",
        }
        self.assertFalse(self.records.link_provider_origin(**origin)["idempotent_replay"])
        self.assertTrue(self.records.link_provider_origin(**origin)["idempotent_replay"])
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.link_provider_origin(**{**origin, "checkpoint_id": 123456790})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.link_provider_origin(**{**origin, "checkpoint_fingerprint": "b" * 64})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.link_provider_origin(
                **{
                    **origin,
                    "provider_id": "trigger:987654322",
                    "checkpoint_id": 123456791,
                    "checkpoint_fingerprint": "c" * 64,
                }
            )
        artifact = {"hosted_comments": json.dumps({"reply": "Full review finished", "symbol": "X" * 60})}
        self.assertFalse(self.records.archive_imported_artifacts("provider-clean-1", artifact)["idempotent_replay"])
        self.assertTrue(self.records.archive_imported_artifacts("provider-clean-1", artifact)["idempotent_replay"])
        with self.assertRaisesRegex(ReviewRecordsError, "conflict"):
            self.records.archive_imported_artifacts(
                "provider-clean-1", {"hosted_comments": json.dumps({"reply": "different"})}
            )
        self.assertEqual(self.records.history(2893)["provider_origins"][0]["repository"], "benhook1013/firemud")
        self.assertEqual(self.records.history(2893)["imported_artifacts"][0]["kind"], "hosted_comments")

    def test_provider_origin_sqlite_failures_are_wrapped(self) -> None:
        self.bootstrap()
        self.records.import_completed_run(
            run_id="provider-sql-error",
            source_pr=2893,
            channel="hosted",
            findings=(),
            source_decisions=(),
            reviewer="CodeRabbit Hosted",
            started_at="2026-09-29T01:00:00Z",
            finished_at="2026-09-29T01:01:00Z",
        )
        with (
            patch.object(
                self.records,
                "_write_connection",
                side_effect=sqlite3.OperationalError("synthetic SQLite failure"),
            ),
            self.assertRaisesRegex(ReviewRecordsError, "cannot link provider origin"),
        ):
            self.records.link_provider_origin(
                repository="BenHook1013/FireMUD",
                source_pr=2893,
                channel="hosted",
                provider_id="trigger:987654323",
                checkpoint_id=123456792,
                checkpoint_fingerprint="d" * 64,
                run_id="provider-sql-error",
            )

    def test_historical_gap_preserves_evidence_without_run_and_can_be_superseded(self) -> None:
        self.bootstrap()
        self.records.record_historical_gap(
            repository="benhook1013/firemud",
            source_pr=2894,
            channel="cli",
            checkpoint_id=123456790,
            checkpoint_fingerprint="d" * 64,
            checkpoint={"comment_id": 123456790, "body": "Only surviving source"},
            artifacts={},
            missing_reason="Original CLI event capture unavailable",
        )
        self.assertEqual(self.records.history(2894)["historical_gap_artifacts"], [])
        gap = {
            "repository": "BenHook1013/FireMUD",
            "source_pr": 2893,
            "channel": "hosted",
            "checkpoint_id": 123456789,
            "checkpoint_fingerprint": "a" * 64,
            "checkpoint": {"body": "Review checkpoint", "comment_id": 123456789},
            "artifacts": {
                "hosted_comments": json.dumps(
                    {
                        "body": "finding visible",
                        "credential": "Bearer synthetic-secret-value",
                    }
                )
            },
            "missing_reason": "Private decision capture never existed",
        }
        self.assertFalse(self.records.record_historical_gap(**gap)["idempotent_replay"])
        self.assertTrue(self.records.record_historical_gap(**gap)["idempotent_replay"])
        history = self.records.history(2893)
        self.assertEqual(history["runs"], [])
        self.assertEqual(history["attempts"], [])
        self.assertEqual(history["historical_gaps"][0]["checkpoint"], gap["checkpoint"])
        self.assertEqual(history["historical_gaps"][0]["missing_reason"], gap["missing_reason"])
        self.assertIsNone(history["historical_gaps"][0]["superseded_by_run_id"])
        self.assertNotIn("content", history["historical_gap_artifacts"][0])
        self.assertEqual(history["historical_gap_artifacts"][0]["kind"], "hosted_comments")
        self.assertEqual(history["historical_gap_artifacts"][0]["redactions"], 1)
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.record_historical_gap(**{**gap, "missing_reason": "Different reason"})
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts"):
            self.records.record_historical_gap(
                **{
                    **gap,
                    "artifacts": {
                        "hosted_comments": '{"body":"different"}',
                    },
                }
            )
        with self.assertRaisesRegex(ReviewRecordsError, "size limit"):
            self.records.record_historical_gap(
                **{
                    **gap,
                    "artifacts": {
                        "hosted_review": "x" * (4 * 1024 * 1024 + 1),
                    },
                }
            )
        self.records.import_completed_run(
            run_id="recovered-run",
            source_pr=2893,
            channel="hosted",
            findings=(),
            source_decisions=(),
            reviewer="CodeRabbit Hosted",
            started_at="2026-09-29T01:00:00Z",
            finished_at="2026-09-29T01:01:00Z",
        )
        with self.assertRaisesRegex(ReviewRecordsError, "historical checkpoint evidence"):
            self.records.link_provider_origin(
                repository=gap["repository"],
                source_pr=2893,
                channel="hosted",
                provider_id="trigger:987654321",
                checkpoint_id=gap["checkpoint_id"],
                checkpoint_fingerprint="c" * 64,
                run_id="recovered-run",
            )
        self.records.link_provider_origin(
            repository=gap["repository"],
            source_pr=2893,
            channel="hosted",
            provider_id="trigger:987654321",
            checkpoint_id=gap["checkpoint_id"],
            checkpoint_fingerprint=gap["checkpoint_fingerprint"],
            run_id="recovered-run",
        )
        recovered = self.records.history(2893)
        self.assertEqual(recovered["historical_gaps"][0]["superseded_by_run_id"], "recovered-run")
        self.assertEqual(len(recovered["historical_gap_artifacts"]), 1)
        with self.assertRaisesRegex(ReviewRecordsError, "attributed provider origin"):
            self.records.record_historical_gap(**gap)

    def test_cli_decision_correction_is_append_only_and_exact_prior(self) -> None:
        self.bootstrap()
        self.records.start_attempt(
            attempt_id="run.correction",
            source_pr=2890,
            channel="cli",
            started_at="2026-09-29T01:00:00Z",
        )
        self.records.finish_attempt(
            "run.correction",
            state="completed",
            finished_at="2026-09-29T01:01:00Z",
        )
        key = "cli-run:run.correction:finding:1"
        self.records.record_run(
            run_id="run.correction",
            source_pr=2890,
            channel="cli",
            findings=(self.observation(key),),
            started_at="2026-09-29T01:00:00Z",
            finished_at="2026-09-29T01:01:00Z",
        )
        self.records.link_attempt_run("run.correction", "run.correction")
        self.records.record_source_decision(
            "run.correction",
            key,
            decision_id="original-decision",
            decision="rejected",
            actor="reviewer",
            reason="initial reading",
        )
        with self.assertRaisesRegex(ReviewRecordsError, "latest exact decision"):
            self.records.correct_source_decision(
                "run.correction",
                key,
                supersedes_id="wrong",
                correction_id="fix-1",
                decision="accepted",
                actor="reviewer",
                reason="corrected reading",
            )
        corrected = self.records.correct_source_decision(
            "run.correction",
            key,
            supersedes_id="original-decision",
            correction_id="fix-1",
            decision="accepted",
            actor="reviewer",
            reason="corrected reading",
        )
        self.assertEqual(corrected["counts"], {"found": 1, "accepted": 1, "routed": 0})
        self.assertEqual(self.records.cli_source_decisions("run.correction"), {1: ("accepted", "corrected reading")})
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM decisions WHERE run_id = 'run.correction'").fetchone()[0], 1
            )
            self.assertEqual(
                connection.execute("SELECT supersedes_id FROM source_decision_corrections").fetchone()[0],
                "original-decision",
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

    def test_review_text_preserves_code_identifiers_and_redacts_recognizable_credentials(self) -> None:
        sha1 = "a" * 40
        sha256 = "b" * 64
        observation = FindingObservation(
            "sha-context",
            f"fixed in commit {sha1}",
            detail=f"verified against {sha256}.",
        )
        self.assertEqual(observation.title, f"fixed in commit {sha1}")
        self.assertEqual(observation.detail, f"verified against {sha256}.")

        identifiers = FindingObservation(
            "identifier-context",
            "test_v2_long_snake_case_identifier_for_review_context",
            detail="hyphenated-review-context-identifier-with-many-parts",
        )
        self.assertEqual(identifiers.title, "test_v2_long_snake_case_identifier_for_review_context")
        self.assertEqual(identifiers.detail, "hyphenated-review-context-identifier-with-many-parts")

        self.assertEqual(FindingObservation("token-context", "Z" * 40).title, "Z" * 40)
        self.assertEqual(FindingObservation("mixed-case-token", "AbCdEf0123456789" * 3).title, "AbCdEf0123456789" * 3)
        for key, value in (("provider-token", f"ghp_{sha1}"), ("jwt-token", "eyJabcdefgh.eyJabcdefgh.eyJabcdefgh")):
            with self.subTest(value=value), self.assertRaisesRegex(ReviewRecordsError, "credential or raw secret"):
                FindingObservation(key, value)

    def test_safe_finding_detail_strips_csi_color_and_normalizes_c0_c1_controls(self) -> None:
        detail = _safe_finding_detail("before\x1b[31mafter\x1b[0m\x00token=ghp_" + "A" * 30 + "\x9b31m\x85tail")

        self.assertEqual(detail, "beforeafter token=[redacted credential] tail")
        self.assertFalse(any(ord(character) < 0x20 or 0x7F <= ord(character) <= 0x9F for character in detail))
        self.assertEqual(FindingObservation("control-text", "title", detail=detail).detail, detail)

    def test_redacted_archive_artifact_uses_sentinel_and_passes_backup_screening(self) -> None:
        marker = "[redacted test credential]"
        content = json.dumps({"body": "token=ghp_" + "A" * 30}) + "\n"

        with patch.object(sqlite_review_records, "_REDACTED_CREDENTIAL", marker):
            archived, _source_digest, redactions = _archive_artifact("cli_events", content)

        self.assertEqual(json.loads(archived)["body"], "token=" + marker)
        self.assertEqual(redactions, 1)
        sqlite_backup._screen_json_artifact("cli_events", archived)

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
        with self.assertRaisesRegex(ReviewRecordsError, "already decided"):
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

    def test_cli_marker_without_sql_association_retains_legacy_handling(self) -> None:
        self.bootstrap()
        self.assertIsNone(self.records.cli_capture_snapshot("run.legacy", source_pr=2828))
        self.assertIsNone(
            self.records.source_resolution_status(
                "run.legacy",
                source_pr=2828,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=1,
            )
        )

    def test_native_cli_capture_snapshot_binds_attempt_run_artifacts_and_decisions(self) -> None:
        self.bootstrap()
        run_id = "run.native-snapshot"
        head = "a" * 40
        metadata = {
            "run_id": run_id,
            "kind": "cli",
            "pull_request": 2828,
            "candidate_sha": head,
            "child_head_sha": head,
            "parent_pr": None,
            "parent_ref": "main",
            "parent_sha": "b" * 40,
            "merge_base": "b" * 40,
            "patch_identity": "c" * 64,
            "candidate_files": 1,
            "capture_completion_marker": "capture-complete",
        }
        events = (
            json.dumps({"type": "finding", "message": "complete source text", "severity": "major"})
            + "\n"
            + json.dumps(
                {"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["src/a.py"]}
            )
            + "\n"
        )
        result_metadata = {**metadata, "duration_seconds": 9, "exit_status": 0}
        self.records.start_attempt(
            attempt_id=run_id,
            source_pr=2828,
            channel="cli",
            candidate_sha=head,
            started_at="2026-09-30T00:00:00Z",
            metadata=metadata,
        )
        self.records.complete_attempt_run(
            run_id,
            finish={
                "state": "completed",
                "finished_at": "2026-09-30T00:00:09Z",
                "duration_seconds": 9,
                "exit_status": 0,
                "artifacts": {"cli_events": events, "metadata": json.dumps(result_metadata)},
            },
            run={
                "run_id": run_id,
                "source_pr": 2828,
                "channel": "cli",
                "source_head": head,
                "reviewer": "CodeRabbit CLI",
                "scope": "broad",
                "started_at": "2026-09-30T00:00:00Z",
                "finished_at": "2026-09-30T00:00:09Z",
                "findings": (self.observation(f"cli-run:{run_id}:finding:1"),),
            },
        )
        self.records.record_source_decision(
            run_id,
            f"cli-run:{run_id}:finding:1",
            decision_id=f"{run_id}.decision.1",
            decision="accepted",
            actor="reviewer",
            reason="Useful finding",
        )
        self.records.finalize_run(run_id, finalized_at="2026-09-30T00:00:10Z")

        snapshot = self.records.cli_capture_snapshot(run_id, source_pr=2828)
        self.assertEqual(snapshot["attempt"]["state"], "completed")
        self.assertEqual(snapshot["run"]["counts"], {"found": 1, "accepted": 1, "routed": 0})
        self.assertIn("cli_events", snapshot["artifacts"])
        self.assertIn("metadata", snapshot["artifacts"])
        self.assertEqual(snapshot["decisions"], {1: ("accepted", "Useful finding")})
        self.assertEqual(
            [item["attempt"]["attempt_id"] for item in self.records.completed_cli_capture_snapshots(2828)],
            [run_id],
        )

    def test_cli_severity_native_unknown_missing_and_conflicting_archive(self) -> None:
        self.test_native_cli_capture_snapshot_binds_attempt_run_artifacts_and_decisions()
        run_id = "run.native-snapshot"
        self.assertEqual(self.records.history(2828)["findings"][0]["display_severity"], "Major")
        for severity in (None, "not a provider label", 7):
            with self.subTest(severity=severity):
                with sqlite3.connect(self.database) as connection:
                    content = connection.execute(
                        "SELECT content FROM review_artifacts WHERE attempt_id = ? AND kind = 'cli_events'",
                        (run_id,),
                    ).fetchone()[0]
                    lines = content.splitlines()
                    event = json.loads(lines[0])
                    event["severity"] = severity
                    lines[0] = json.dumps(event)
                    connection.execute("UPDATE review_artifacts SET content = ? WHERE attempt_id = ? AND kind = 'cli_events'",
                                       ("\n".join(lines), run_id))
                    before = list(connection.iterdump())
                with patch.object(self.records, "_write_connection", side_effect=AssertionError("read wrote")):
                    finding = self.records.history(2828)["findings"][0]
                self.assertIsNone(finding["display_severity"])
                with sqlite3.connect(self.database) as connection:
                    self.assertEqual(list(connection.iterdump()), before)
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE review_attempts SET source_pr = 999 WHERE attempt_id = ?", (run_id,))
        self.assertNotIn("display_severity", self.records.history(2828)["findings"][0])
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE review_attempts SET source_pr = 2828 WHERE attempt_id = ?", (run_id,))
            connection.execute("DELETE FROM review_artifacts WHERE attempt_id = ? AND kind = 'cli_events'", (run_id,))
        self.assertNotIn("display_severity", self.records.history(2828)["findings"][0])

    def test_started_cli_association_cannot_be_read_as_legacy_sql_snapshot(self) -> None:
        self.bootstrap()
        run_id = "run.started-snapshot"
        self.records.start_attempt(
            attempt_id=run_id, source_pr=2828, channel="cli", candidate_sha="a" * 40
        )
        with self.assertRaisesRegex(ReviewRecordsError, "still in progress"):
            self.records.cli_capture_snapshot(run_id, source_pr=2828)
        self.assertEqual(self.records.completed_cli_capture_snapshots(2828), [])

    def test_completed_cli_capture_discovery_excludes_curated_import_without_native_keys(self) -> None:
        self.bootstrap()
        self.records.import_completed_run(
            run_id="curated-cli-import",
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            reviewer="CodeRabbit CLI",
            findings=(self.observation("curated-finding"),),
            source_decisions=({
                "source_finding_key": "curated-finding",
                "decision_id": "curated-cli-import-decision",
                "decision": "rejected",
                "actor": "reviewer",
                "reason": "Curated historical observation",
            },),
        )

        self.assertEqual(self.records.completed_cli_capture_snapshots(2828), [])

    def test_completed_cli_capture_discovery_keeps_malformed_native_attemptless_run_fail_closed(self) -> None:
        self.bootstrap()
        run_id = "run.orphan-native"
        finding_key = f"cli-run:{run_id}:finding:1"
        self.records.import_completed_run(
            run_id=run_id,
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            reviewer="CodeRabbit CLI",
            findings=(self.observation(finding_key),),
            source_decisions=({
                "source_finding_key": finding_key,
                "decision_id": "orphan-native-decision",
                "decision": "rejected",
                "actor": "reviewer",
                "reason": "Malformed native association fixture",
            },),
        )

        with self.assertRaisesRegex(ReviewRecordsError, "missing its attempt"):
            self.records.completed_cli_capture_snapshots(2828)

    def test_partial_cli_association_cannot_fall_back_to_legacy_handling(self) -> None:
        self.bootstrap()
        run_id = "run.partial"
        self.records.start_attempt(
            attempt_id=run_id, source_pr=2828, channel="cli", candidate_sha="a" * 40
        )
        self.assertEqual(
            self.records.source_resolution_status(
                run_id, source_pr=2828, source_channel="cli", source_head="a" * 40, accepted_count=1
            ),
            "pending",
        )
        self.records.record_run(
            run_id=run_id,
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            findings=(self.observation("finding"),),
        )
        self.records.finish_attempt(run_id, state="completed")
        self.records.link_attempt_run(run_id, run_id)
        # A missing run link or source row must not disguise a modern capture
        # as legacy. Retain the attempt identity while simulating corruption.
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE review_attempts SET run_id = NULL WHERE attempt_id = ?", (run_id,))
            connection.execute("DELETE FROM review_runs WHERE run_id = ?", (run_id,))
        self.assertEqual(
            self.records.source_resolution_status(
                run_id, source_pr=2828, source_channel="cli", source_head="a" * 40, accepted_count=1
            ),
            "pending",
        )

    def test_sql_run_without_attempt_still_requires_exact_source_proof(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="run.structured",
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            findings=(self.observation("finding"),),
        )
        for source_pr in (2828, 2879):
            with self.subTest(source_pr=source_pr):
                self.assertEqual(
                    self.records.source_resolution_status(
                        "run.structured",
                        source_pr=source_pr,
                        source_channel="cli",
                        source_head="a" * 40,
                        accepted_count=1,
                    ),
                    "pending",
                )

    def test_source_finding_resolution_requires_exact_accepted_source_and_preserves_counts(self) -> None:
        self.bootstrap()
        self.records.import_completed_run(
            run_id="source-resolution-run",
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            reviewer="CodeRabbit",
            findings=(
                self.observation("fixed-one"),
                self.observation("fixed-two"),
                self.observation("routed-one"),
                self.observation("rejected-one"),
            ),
            source_decisions=(
                {
                    "source_finding_key": "fixed-one",
                    "decision_id": "source-resolution-accepted-one",
                    "decision": "accepted",
                    "actor": "reviewer",
                    "reason": "owned by this PR",
                },
                {
                    "source_finding_key": "fixed-two",
                    "decision_id": "source-resolution-accepted-two",
                    "decision": "accepted",
                    "actor": "reviewer",
                    "reason": "owned by this PR",
                },
                {
                    "source_finding_key": "routed-one",
                    "decision_id": "source-resolution-routed",
                    "decision": "routed",
                    "target_pr": 2879,
                    "actor": "reviewer",
                    "reason": "belongs to the target PR",
                },
                {
                    "source_finding_key": "rejected-one",
                    "decision_id": "source-resolution-rejected",
                    "decision": "rejected",
                    "actor": "reviewer",
                    "reason": "not actionable",
                },
            ),
        )
        before = self.records.history(2828)["runs"][0]["counts"]
        self.assertEqual(before, {"found": 4, "accepted": 2, "routed": 1})

        def resolve(key: str, resolution_id: str) -> dict[str, object]:
            return self.records.record_source_resolution(
                "source-resolution-run",
                key,
                source_pr=2828,
                resolution_id=resolution_id,
                fix_sha="b" * 40,
                actor="owner",
                proof_note="Verified as fixed in the retained source commit",
                resolved_at="2026-09-30T12:00:00Z",
            )

        with self.assertRaisesRegex(ReviewRecordsError, "source PR does not match"):
            self.records.record_source_resolution(
                "source-resolution-run",
                "fixed-one",
                source_pr=2879,
                resolution_id="wrong-source-pr",
                fix_sha="b" * 40,
                actor="owner",
                proof_note="Wrong PR must not claim this finding",
            )
        with self.assertRaisesRegex(ReviewRecordsError, "only an effectively accepted"):
            resolve("routed-one", "routed-resolution")
        with self.assertRaisesRegex(ReviewRecordsError, "only an effectively accepted"):
            resolve("rejected-one", "rejected-resolution")

        self.assertEqual(
            self.records.source_resolution_status(
                "source-resolution-run",
                source_pr=2828,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=2,
            ),
            "pending",
        )
        first = resolve("fixed-one", "source-resolution-one")
        self.assertFalse(first["idempotent_replay"])
        self.assertEqual(
            self.records.source_resolution_status(
                "source-resolution-run",
                source_pr=2828,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=2,
            ),
            "pending",
        )
        resolve("fixed-two", "source-resolution-two")
        self.assertEqual(
            self.records.source_resolution_status(
                "source-resolution-run",
                source_pr=2828,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=2,
            ),
            "resolved",
        )
        self.assertEqual(
            self.records.source_resolution_status(
                "source-resolution-run",
                source_pr=2828,
                source_channel="cli",
                source_head="c" * 40,
                accepted_count=2,
            ),
            "pending",
        )
        self.assertEqual(
            self.records.source_resolution_status(
                "source-resolution-run",
                source_pr=2879,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=2,
            ),
            "pending",
        )

        repeated = self.records.record_source_resolution(
            "source-resolution-run",
            "fixed-one",
            source_pr=2828,
            resolution_id="source-resolution-one",
            fix_sha="b" * 40,
            actor="owner",
            proof_note="Verified as fixed in the retained source commit",
            resolved_at="2026-10-01T12:00:00Z",
        )
        self.assertTrue(repeated["idempotent_replay"])
        history = self.records.history(2828)
        self.assertEqual(history["runs"][0]["counts"], before)
        self.assertEqual(len(history["source_resolutions"]), 2)
        self.assertEqual(history["source_resolutions"][0]["fix_sha"], "b" * 40)
        with self.assertRaisesRegex(ReviewRecordsError, "different immutable resolution"):
            self.records.record_source_resolution(
                "source-resolution-run",
                "fixed-one",
                source_pr=2828,
                resolution_id="replacement-resolution",
                fix_sha="c" * 40,
                actor="owner",
                proof_note="A later claim cannot overwrite the recorded proof",
            )

    def test_source_finding_resolution_rejects_partial_or_invalid_proof(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="source-resolution-incomplete",
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            findings=(self.observation("unfinalized"),),
        )
        self.records.record_source_decision(
            "source-resolution-incomplete",
            "unfinalized",
            decision_id="unfinalized-accepted",
            decision="accepted",
            actor="reviewer",
            reason="owned by this PR",
        )
        with self.assertRaisesRegex(ReviewRecordsError, "finalized run"):
            self.records.record_source_resolution(
                "source-resolution-incomplete",
                "unfinalized",
                source_pr=2828,
                resolution_id="unfinalized-resolution",
                fix_sha="b" * 40,
                actor="owner",
                proof_note="A nonfinalized run cannot have a resolution",
            )
        self.records.finalize_run("source-resolution-incomplete")
        for fix_sha, proof_note in (
            ("short", "invalid commit identifier"),
            ("b" * 40, "password=not-allowed"),
        ):
            with self.subTest(fix_sha=fix_sha, proof_note=proof_note), self.assertRaises(ReviewRecordsError):
                self.records.record_source_resolution(
                    "source-resolution-incomplete",
                    "unfinalized",
                    source_pr=2828,
                    resolution_id="invalid-proof",
                    fix_sha=fix_sha,
                    actor="owner",
                    proof_note=proof_note,
                )

    def test_source_resolution_correction_is_append_only_and_uses_expected_effective_sha(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="source-proof-correction-run",
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            findings=(self.observation("corrected-proof"),),
        )
        self.records.record_source_decision(
            "source-proof-correction-run",
            "corrected-proof",
            decision_id="corrected-proof-decision",
            decision="accepted",
            actor="reviewer",
            reason="owned by the source PR",
        )
        self.records.finalize_run("source-proof-correction-run")
        self.records.record_source_resolution(
            "source-proof-correction-run",
            "corrected-proof",
            source_pr=2828,
            resolution_id="corrected-proof-original",
            fix_sha="b" * 40,
            actor="owner",
            proof_note="Original operator transcription",
        )
        counts_before = self.records.history(2828)["runs"][0]["counts"]
        correction = {
            "resolution_id": "corrected-proof-original",
            "expected_fix_sha": "b" * 40,
            "fix_sha": "c" * 40,
            "correction_id": "corrected-proof-correction-1",
            "actor": "Overseer",
            "reason": "Correct a verified SHA transcription error",
            "proof_note": "The published commit was checked from the immutable branch ref",
        }
        for run_id, finding_key, source_pr, resolution_id in (
            ("different-run", "corrected-proof", 2828, "corrected-proof-original"),
            ("source-proof-correction-run", "different-finding", 2828, "corrected-proof-original"),
            ("source-proof-correction-run", "corrected-proof", 2829, "corrected-proof-original"),
            ("source-proof-correction-run", "corrected-proof", 2828, "different-resolution"),
        ):
            with (
                self.subTest(run_id=run_id, finding_key=finding_key, source_pr=source_pr, resolution_id=resolution_id),
                self.assertRaisesRegex(ReviewRecordsError, "does not exist|does not match"),
            ):
                self.records.correct_source_resolution(
                    run_id,
                    finding_key,
                    source_pr=source_pr,
                    **{**correction, "resolution_id": resolution_id},
                )
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM source_finding_resolution_corrections").fetchone()[0], 0
            )
            self.assertEqual(
                connection.execute(
                    "SELECT fix_sha FROM source_finding_resolutions WHERE resolution_id = ?",
                    ("corrected-proof-original",),
                ).fetchone()[0],
                "b" * 40,
            )
        result = self.records.correct_source_resolution(
            "source-proof-correction-run",
            "corrected-proof",
            source_pr=2828,
            **correction,
        )
        self.assertFalse(result["idempotent_replay"])
        replay = self.records.correct_source_resolution(
            "source-proof-correction-run",
            "corrected-proof",
            source_pr=2828,
            **correction,
        )
        self.assertTrue(replay["idempotent_replay"])
        with self.assertRaisesRegex(ReviewRecordsError, "correction ID is already used"):
            self.records.correct_source_resolution(
                "source-proof-correction-run",
                "corrected-proof",
                source_pr=2828,
                **{**correction, "fix_sha": "f" * 40},
            )
        self.assertEqual(self.records.history(2828)["runs"][0]["counts"], counts_before)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT COUNT(*) FROM source_finding_resolution_corrections").fetchone()[0], 1
            )
        with self.assertRaisesRegex(ReviewRecordsError, "expected fix SHA does not match"):
            self.records.correct_source_resolution(
                "source-proof-correction-run",
                "corrected-proof",
                source_pr=2828,
                resolution_id="corrected-proof-original",
                expected_fix_sha="b" * 40,
                fix_sha="d" * 40,
                correction_id="competing-correction",
                actor="Overseer",
                reason="Stale concurrent correction",
                proof_note="Must lose to the first append",
            )
        self.records.correct_source_resolution(
            "source-proof-correction-run",
            "corrected-proof",
            source_pr=2828,
            resolution_id="corrected-proof-original",
            expected_fix_sha="c" * 40,
            fix_sha="d" * 40,
            correction_id="corrected-proof-correction-2",
            actor="Overseer",
            reason="Follow-up correction with the now-current proof",
            proof_note="The first corrected SHA was subsequently checked against the publication record",
        )

        history = self.records.history(2828)
        proof = history["source_resolutions"][0]
        self.assertEqual(proof["fix_sha"], "b" * 40)
        self.assertEqual(proof["effective_fix_sha"], "d" * 40)
        self.assertEqual([item["fix_sha"] for item in proof["corrections"]], ["c" * 40, "d" * 40])
        self.assertEqual(len(history["source_resolution_corrections"]), 2)
        self.assertEqual(history["runs"][0]["counts"], counts_before)
        self.assertEqual(
            self.records.source_resolution_status(
                "source-proof-correction-run",
                source_pr=2828,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=1,
            ),
            "resolved",
        )
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT fix_sha FROM source_finding_resolutions WHERE resolution_id = ?",
                    ("corrected-proof-original",),
                ).fetchone()[0],
                "b" * 40,
            )

    def test_v6_upgrade_adds_source_resolution_schema_and_rejects_v6_writers(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="v6-retained-run",
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            findings=(self.observation("finding"),),
        )
        with sqlite3.connect(self.database) as connection:
            connection.execute("ALTER TABLE finding_observations DROP COLUMN display_severity")
            connection.execute("DROP TABLE source_finding_resolutions")
            connection.execute("DROP TABLE source_finding_resolution_corrections")
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 6 WHERE singleton = 1")
            connection.execute("UPDATE controller_metadata SET min_writer_build = 5 WHERE singleton = 1")

        self.records.migrate()
        self.assertEqual(self.records.history(2828)["runs"][0]["run_id"], "v6-retained-run")
        self.assertEqual(SqliteStateStore(self.database).status()["min_writer_build"], WRITER_BUILD)
        with self.assertRaisesRegex(StateError, f"requires writer build {WRITER_BUILD}"):
            SqliteStateStore(self.database, writer_build=5).update(lambda state: state)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT records_schema_version FROM review_records_metadata WHERE singleton = 1"
                ).fetchone()[0],
                9,
            )
            self.assertIsNotNone(
                connection.execute(
                    "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'source_finding_resolutions'"
                ).fetchone()
            )

        with (
            patch.object(sqlite_review_records, "_RECORDS_SCHEMA_VERSION", 8),
            self.assertRaisesRegex(RecordsSchemaIncompatible, "schema version 9"),
        ):
            self.records.record_source_decision(
                "v6-retained-run",
                "finding",
                decision_id="old-writer-attempt",
                decision="accepted",
                actor="old writer",
                reason="must fail closed",
            )

    def test_v7_upgrade_adds_resolution_correction_log_and_fences_build_six(self) -> None:
        self.bootstrap()
        self.records.record_run(
            run_id="v7-retained-accepted-run",
            source_pr=2828,
            channel="cli",
            source_head="a" * 40,
            findings=(self.observation("retained-finding"),),
        )
        self.records.record_source_decision(
            "v7-retained-accepted-run",
            "retained-finding",
            decision_id="v7-retained-decision",
            decision="accepted",
            actor="reviewer",
            reason="owned by the source PR",
        )
        self.records.finalize_run("v7-retained-accepted-run")
        self.records.record_source_resolution(
            "v7-retained-accepted-run",
            "retained-finding",
            source_pr=2828,
            resolution_id="v7-retained-resolution",
            fix_sha="b" * 40,
            actor="owner",
            proof_note="Original v7 accepted-fix proof",
        )
        counts_before = self.records.history(2828)["runs"][0]["counts"]
        with sqlite3.connect(self.database) as connection:
            connection.execute("ALTER TABLE finding_observations DROP COLUMN display_severity")
            connection.execute("DROP TABLE source_finding_resolution_corrections")
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 7, min_writer_build = 6")
            connection.execute("UPDATE controller_metadata SET min_writer_build = 6")

        old_records = SqliteReviewRecords(self.database, writer_build=6)
        old_state = SqliteStateStore(self.database, writer_build=6)
        self.records.migrate()
        self.records.migrate()
        with self.assertRaisesRegex(RecordsSchemaIncompatible, "requires writer build 8"):
            old_records.history(2828)
        with self.assertRaisesRegex(StateError, "requires writer build 8"):
            old_state.update(lambda state: state)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT records_schema_version FROM review_records_metadata").fetchone()[0], 9
            )
            self.assertEqual(
                connection.execute("SELECT min_writer_build FROM review_records_metadata").fetchone()[0], 8
            )
            self.assertEqual(
                connection.execute("SELECT min_writer_build FROM controller_metadata").fetchone()[0], 8
            )
        history = self.records.history(2828)
        self.assertEqual(history["runs"][0]["counts"], counts_before)
        self.assertEqual(history["source_resolutions"][0]["fix_sha"], "b" * 40)
        self.assertEqual(history["source_resolutions"][0]["effective_fix_sha"], "b" * 40)
        self.records.correct_source_resolution(
            "v7-retained-accepted-run",
            "retained-finding",
            source_pr=2828,
            resolution_id="v7-retained-resolution",
            expected_fix_sha="b" * 40,
            fix_sha="c" * 40,
            correction_id="v7-retained-resolution-correction",
            actor="Overseer",
            reason="Correct a verified source SHA transcription",
            proof_note="The original row and counts are preserved across migration",
        )
        corrected = self.records.history(2828)
        self.assertEqual(corrected["source_resolutions"][0]["fix_sha"], "b" * 40)
        self.assertEqual(corrected["source_resolutions"][0]["effective_fix_sha"], "c" * 40)
        self.assertEqual(corrected["runs"][0]["counts"], counts_before)
        self.assertEqual(
            self.records.source_resolution_status(
                "v7-retained-accepted-run",
                source_pr=2828,
                source_channel="cli",
                source_head="a" * 40,
                accepted_count=1,
            ),
            "resolved",
        )

    def test_v8_upgrade_adds_nullable_severity_preserves_old_payload_and_fences_build_seven(self) -> None:
        self.bootstrap()
        imported = {
            "run_id": "v8-retained-subagent-run",
            "source_pr": 2828,
            "channel": "subagent",
            "findings": (FindingObservation("v8-retained-finding", "Retained finding"),),
            "source_decisions": (
                {
                    "source_finding_key": "v8-retained-finding",
                    "decision_id": "v8-retained-decision",
                    "decision": "rejected",
                    "actor": "historical reviewer",
                    "reason": "retained historical decision",
                    "decided_at": "2026-09-01T00:01:00Z",
                },
            ),
            "started_at": "2026-09-01T00:00:00Z",
            "finished_at": "2026-09-01T00:01:00Z",
        }
        self.records.import_completed_run(**imported)
        with sqlite3.connect(self.database) as connection:
            payload_before = connection.execute(
                "SELECT import_payload_json FROM review_runs WHERE run_id = ?",
                (imported["run_id"],),
            ).fetchone()[0]
            self.assertNotIn("display_severity", payload_before)
            connection.execute("ALTER TABLE finding_observations DROP COLUMN display_severity")
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 8, min_writer_build = 7")
            connection.execute("UPDATE controller_metadata SET min_writer_build = 7")

        old_records = SqliteReviewRecords(self.database, writer_build=7)
        old_state = SqliteStateStore(self.database, writer_build=7)
        self.assertEqual(old_state.status()["min_writer_build"], 7)
        self.records.migrate()
        self.records.migrate()

        with self.assertRaisesRegex(RecordsSchemaIncompatible, "requires writer build 8"):
            old_records.history(2828)
        with self.assertRaisesRegex(StateError, "requires writer build 8"):
            old_state.update(lambda state: state)
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute("SELECT records_schema_version FROM review_records_metadata").fetchone()[0],
                9,
            )
            self.assertEqual(
                connection.execute("SELECT min_writer_build FROM review_records_metadata").fetchone()[0],
                8,
            )
            self.assertEqual(
                connection.execute("SELECT min_writer_build FROM controller_metadata").fetchone()[0],
                8,
            )
            self.assertEqual(
                connection.execute(
                    "SELECT import_payload_json FROM review_runs WHERE run_id = ?",
                    (imported["run_id"],),
                ).fetchone()[0],
                payload_before,
            )
        replay = self.records.import_completed_run(**imported)
        self.assertTrue(replay["idempotent_replay"])
        history = self.records.history(2828)
        self.assertEqual(history["runs"][0]["counts"], {"found": 1, "accepted": 0, "routed": 0})
        self.assertIsNone(history["findings"][0]["display_severity"])

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

    def test_replayed_decision_uses_its_run_target_after_route_retargeting(self) -> None:
        self.bootstrap()
        first_import = {
            "run_id": "route-owner-first-run",
            "source_pr": 2828,
            "channel": "hosted",
            "findings": (self.observation("shared-route"),),
            "source_decisions": (
                {
                    "source_finding_key": "shared-route",
                    "decision_id": "route-owner-first-decision",
                    "decision": "routed",
                    "target_pr": 2879,
                    "actor": "reviewer",
                    "reason": "initial owner",
                },
            ),
        }
        self.records.import_completed_run(**first_import)
        route_id = self.records.open_routes()[0]["route_id"]
        self.records.retarget_route(
            route_id,
            target_pr=2999,
            actor="reviewer",
            reason="ownership moved",
        )
        second_import = {
            **first_import,
            "run_id": "route-owner-second-run",
            "source_decisions": (
                {
                    "source_finding_key": "shared-route",
                    "decision_id": "route-owner-second-decision",
                    "decision": "routed",
                    "target_pr": 2999,
                    "actor": "reviewer",
                    "reason": "current owner",
                },
            ),
        }

        imported = self.records.import_completed_run(**second_import)
        replay = self.records.import_completed_run(**second_import)
        self.assertFalse(imported["idempotent_replay"])
        self.assertTrue(replay["idempotent_replay"])
        source_decisions = self.records.history(2828)["decisions"]
        self.assertEqual(
            {decision["decision_id"]: decision["target_pr"] for decision in source_decisions},
            {"route-owner-first-decision": 2879, "route-owner-second-decision": 2999},
        )

        conflicting_replay = {
            **second_import,
            "source_decisions": (
                {
                    **second_import["source_decisions"][0],
                    "target_pr": 2879,
                },
            ),
        }
        with self.assertRaisesRegex(ReviewRecordsError, "conflicts with this completed import"):
            self.records.import_completed_run(**conflicting_replay)

    def test_previous_records_schema_version_fails_closed_without_migration(self) -> None:
        self.bootstrap()
        with sqlite3.connect(self.database) as connection:
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 3 WHERE singleton = 1")

        with self.assertRaisesRegex(ReviewRecordsError, "schema version 3"):
            self.records.bootstrap()
        with sqlite3.connect(self.database) as connection:
            self.assertEqual(
                connection.execute(
                    "SELECT records_schema_version FROM review_records_metadata WHERE singleton = 1"
                ).fetchone()[0],
                3,
            )

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
        self.assertIn(
            {"source_pr": 2999, "assignment": "unassigned"},
            [{"source_pr": route["source_pr"], "assignment": route["assignment"]} for route in all_open],
        )
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

    def test_route_listing_filters_status_source_target_and_assignment(self) -> None:
        self.bootstrap()
        routes = (
            (2700, "active-target", 2879),
            (2701, "resolved-target", 2879),
            (2702, "unassigned", None),
            (2703, "other-target", 2880),
        )
        for source_pr, key, target_pr in routes:
            self.records.record_run(
                run_id=f"route-list-{source_pr}",
                source_pr=source_pr,
                channel="manual",
                findings=(self.observation(key, "routed", target_pr=target_pr),),
            )

        resolved_route = self.records.list_routes(source_pr=2701)[0]
        self.records.record_resolution(
            resolved_route["route_id"],
            resolution_id="route-list-resolution",
            resolution_pr=2879,
            outcome="accepted_fixed",
            actor="owner",
            proof_or_reason="verified fix",
        )

        self.assertEqual(len(self.records.list_routes()), 3)
        self.assertEqual(
            [route["status"] for route in self.records.list_routes(status="resolved")],
            ["accepted_fixed"],
        )
        self.assertEqual(len(self.records.list_routes(status="all")), 4)
        self.assertEqual(
            {route["source_pr"] for route in self.records.list_routes(target_pr=2879, status="all")},
            {2700, 2701},
        )
        self.assertEqual(
            [route["source_pr"] for route in self.records.list_routes(source_pr=2701, status="resolved")],
            [2701],
        )
        self.assertEqual(
            [route["source_pr"] for route in self.records.list_routes(source_pr=2703, target_pr=2880)],
            [2703],
        )
        self.assertEqual(
            [route["source_pr"] for route in self.records.list_routes(unassigned=True)],
            [2702],
        )
        with self.assertRaisesRegex(ReviewRecordsError, "route status"):
            self.records.list_routes(status="pending")
        with self.assertRaisesRegex(ReviewRecordsError, "cannot be combined"):
            self.records.list_routes(target_pr=2879, unassigned=True)

    def test_legacy_controller_route_rejects_sqlite_target_writes(self) -> None:
        self.bootstrap()
        legacy_route = FindingRoute(
            source_pr=2828,
            source_channel="hosted",
            source_review="summary:review:700",
            source_finding="duplicate:ref:legacy-summary",
            observations=("owned by another PR",),
            target_pr=2879,
            target_history=(2879,),
        )
        state_store = SqliteStateStore(self.database)
        state_store.update(lambda state: dataclasses.replace(state, routes=(legacy_route,)))
        self.records.import_completed_run(
            run_id="legacy-shadow-run",
            source_pr=2828,
            channel="manual",
            findings=(self.observation("legacy-shadow"),),
            source_decisions=(
                {
                    "source_finding_key": "legacy-shadow",
                    "decision_id": "legacy-shadow-source-route",
                    "decision": "routed",
                    "route_id": legacy_route.route_id,
                    "route_status": "open",
                    "target_pr": 2879,
                    "actor": "reviewer",
                    "reason": "mirrors the controller-owned route",
                },
            ),
        )

        visible_routes_before = self.records.open_routes(target_pr=2879, include_legacy_routes=True)
        visible_history_before = self.records.history(2879, include_legacy_routes=True)["routes"]
        shadow_history_before = self.records.history(2828)["routes"]
        self.assertEqual([route["origin"] for route in visible_routes_before], ["legacy_controller"])
        self.assertEqual(visible_routes_before[0]["route_id"], legacy_route.route_id)

        with self.assertRaisesRegex(ReviewRecordsError, "legacy controller owns this route.*decide route"):
            self.records.retarget_route(
                legacy_route.route_id,
                target_pr=2999,
                actor="owner",
                reason="retarget legacy route",
            )
        with self.assertRaisesRegex(ReviewRecordsError, "legacy controller owns this route.*decide route"):
            self.records.record_decision(
                legacy_route.route_id,
                decision_id="legacy-target-decision",
                decision_pr=2879,
                decision="accepted",
                actor="owner",
                reason="decide legacy route",
            )
        with self.assertRaisesRegex(ReviewRecordsError, "legacy controller owns this route.*decide route"):
            self.records.record_resolution(
                legacy_route.route_id,
                resolution_id="legacy-target-resolution",
                resolution_pr=2879,
                outcome="accepted_fixed",
                actor="owner",
                proof_or_reason="resolve legacy route",
            )

        self.assertEqual(state_store.load().routes, (legacy_route,))
        self.assertEqual(self.records.open_routes(target_pr=2879, include_legacy_routes=True), visible_routes_before)
        self.assertEqual(self.records.history(2879, include_legacy_routes=True)["routes"], visible_history_before)
        self.assertEqual(self.records.history(2828)["routes"], shadow_history_before)

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
            connection.execute("UPDATE review_records_metadata SET records_schema_version = 999 WHERE singleton = 1")
        with self.assertRaisesRegex(ReviewRecordsError, "schema version 999"):
            reopened.history(2828)


if __name__ == "__main__":
    unittest.main()
