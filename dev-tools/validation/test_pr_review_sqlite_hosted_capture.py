from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import (
    hosted,
    sqlite_finding_text,
    sqlite_hosted_capture,
    sqlite_provider_imports,
    sqlite_review_records,
    sqlite_store,
)

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
    def trigger_comment(*, pr: int = PR, trigger_id: int = 101, created_at: str = TRIGGER_AT) -> dict[str, object]:
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

    def test_two_field_provider_badges_preserve_issue_and_explicit_severity(self) -> None:
        headline = "Before promoting this change, check that the scheduled backup runs build 6."
        explanation = "Check that the status site and the shared controller run the same build."
        for category in ("🩺 Stability & Availability", "📐 Maintainability & Code Quality", "🔒 Security & Privacy",
                         "Data Integrity & Integration", "Functional Correctness", "Bug"):
            with self.subTest(category=category):
                body = f"_{category}_ | _🔵 Trivial_\n\n**{headline}**\n\n{explanation}"
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(4155240659, body)[0]
                self.assertEqual(finding["title"], headline)
                self.assertEqual(finding["display_detail"], explanation)
                self.assertEqual(finding["display_severity"], "Trivial")
        inline = "_🩺 Stability & Availability_ | _🔵 Trivial_ " + explanation
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(4155240659, inline)[0]
        self.assertEqual(finding["display_detail"], explanation)

    def test_two_field_security_badge_does_not_prove_classification_metadata(self) -> None:
        body = ("_🔒 Security & Privacy_ | _🟠 Major_\n**Authored classification**\n"
                "**Exploitability:** Difficult\n**CWE:** CWE-693\n**Keep the authored remedy.**\nKeep the prose.")
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
        self.assertEqual(finding["classification_titles"], [])
        self.assertEqual(finding["title"], "Authored classification")
        self.assertEqual(finding["display_severity"], "Major")
        self.assertIn("**Exploitability:** Difficult", finding["display_detail"])
        self.assertIn("**Keep the authored remedy.**", finding["display_detail"])

    def test_authored_italic_pairs_and_code_samples_are_not_two_field_badges(self) -> None:
        for pair in ("_Authored point_ | _Trivial_", "_Stability & Availability narrative_ | _Trivial_",
                     "_Stability & Availability_ | _Major impact_", "_Stability & Availability_ | _Unknown_",
                     "```md\n_🩺 Stability & Availability_ | _🔵 Trivial_\n```"):
            with self.subTest(pair=pair):
                body = "**Preserve the authored issue.**\n" + pair + "\nActual issue prose."
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
                self.assertIn(pair, finding["display_detail"])
                self.assertIsNone(finding["display_severity"])

    def test_recognized_two_field_examples_after_headline_are_authored_content(self) -> None:
        for category in ("Bug", "Functional Correctness", "🔒 Security & Privacy", "🩺 Stability & Availability"):
            with self.subTest(category=category):
                pair = f"_{category}_ | _Major_"
                body = "**Document the format.**\n" + pair + "\nThis is an authored example."
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
                self.assertEqual(finding["title"], "Document the format.")
                self.assertEqual(finding["display_detail"], pair + "\nThis is an authored example.")
                self.assertIsNone(finding["display_severity"])
        sample_then_example = "```md\nAn ordinary example.\n```\n_Bug_ | _Major_\n**Document the format.**"
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, sample_then_example)[0]
        self.assertIn("_Bug_ | _Major_", finding["display_detail"])
        self.assertIsNone(finding["display_severity"])

    def test_leading_two_field_severity_does_not_read_authored_later_examples(self) -> None:
        body = "_Bug_ | _Trivial_\n**Document the format.**\n_Bug_ | _Major_\nThis is an authored example."
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
        self.assertEqual(finding["display_severity"], "Trivial")
        self.assertIn("_Bug_ | _Major_", finding["display_detail"])

    def test_security_header_binds_only_its_adjacent_classification_block(self) -> None:
        header = "_🔒 Security & Privacy_ | _🟠 Major_ | _🏗️ Heavy lift_"
        classification = "**Authored classification**\n**Exploitability:** Difficult\n**CWE:** CWE-693\n**Keep the authored remedy.**\nKeep the prose."
        detached = header + "\nAn ordinary prose interruption breaks provenance.\n" + classification
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, detached)[0]
        self.assertEqual(finding["classification_titles"], [])
        self.assertIn("**Exploitability:** Difficult", finding["display_detail"])
        bound_then_authored = (header + "\n<details><summary>Analysis chain</summary>Diagnostic noise</details>\n"
                               "**Provider classification**\n**Exploitability:** Difficult\n**CWE:** CWE-693\n"
                               "**Actual remedy.**\nOrdinary unrelated issue prose.\n" + classification)
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, bound_then_authored)[0]
        self.assertEqual(finding["classification_titles"], ["Provider classification"])
        self.assertIn("**Authored classification**", finding["display_detail"])
        self.assertIn("**Exploitability:** Difficult", finding["display_detail"])

    def test_security_metadata_requires_complete_strict_provider_badge(self) -> None:
        metadata = "\n**Authorization Bypass**\n**Exploitability:** Difficult\n**CWE:** CWE-693\n**Require durable proof.**\nKeep the prose."
        valid = ("_🔒 Security & Privacy_ | _🟠 Major_ | _🏗️ Heavy lift_",
                 "_🔒 Security & Privacy_ | _🛡️ Detected with Advanced Tier_ | _🟠 Major_ | _🏗️ Heavy lift_")
        invalid = ("_Security & Privacy_ | arbitrary authored prose",
                   "_Security & Privacy narrative_ | _Major_ | _Heavy lift_",
                   "_Security & Privacy_ | _Major impact_ | _Heavy lift_",
                   "_Security & Privacy_ | _Unknown_ | _Heavy lift_",
                   "_Security & Privacy_ | _Major_ | _Something else_",
                   "_Security & Privacy_ | _Invented tier_ | _Major_ | _Heavy lift_",
                   "```md\n" + valid[0] + "\n```")
        for header in valid:
            with self.subTest(header=header):
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, header + metadata)[0]
                self.assertEqual(finding["classification_titles"], ["Authorization Bypass"])
                self.assertEqual(finding["title"], "Require durable proof.")
        for header in invalid:
            with self.subTest(header=header):
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, header + metadata)[0]
                self.assertEqual(finding["classification_titles"], [])
                self.assertIn("**Exploitability:** Difficult", finding["display_detail"])
                self.assertIn("**CWE:** CWE-693", finding["display_detail"])
                self.assertIn(header, finding["display_detail"])

    def test_provider_security_structure_proves_classification_not_remediation(self) -> None:
        header = "_🔒 Security & Privacy_ | _🛡️ Detected with Advanced Tier_ | _🟠 Major_ | _🏗️ Heavy lift_"
        for classification, reachability, headline in (
            ("Authorization Bypass", "", "Require durability proof for the existing-marker outcome."),
            ("Sensitive Data Exposure", "**Reachability:** Internal\n", "Do not document privileged JWTs with plaintext transport."),
            ("Different provider classification", "", "Preserve the actual remedy."),
        ):
            with self.subTest(classification=classification):
                body = (header + f"\n**{classification}**\n" + reachability +
                        "**Exploitability:** Difficult\n**CWE:** [CWE-693](https://cwe.mitre.org/data/definitions/693.html)\n"
                        f"**{headline}** Keep the substantive explanation.")
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
                self.assertEqual(finding["title"], headline)
                self.assertEqual(finding["classification_titles"], [classification])
                self.assertEqual(finding["display_detail"], "Keep the substantive explanation.")
                self.assertFalse(finding["display_title_is_excerpt"])
        unproven = header + "\n**Authorization Bypass**\n**Exploitability:** Difficult\n**Another authored issue.**"
        self.assertEqual(sqlite_hosted_capture._hosted_comment_finding_segments(202, unproven)[0]["classification_titles"], [])
        ordinary = "**Authorization Bypass**\nKeep this authored issue title and explanation."
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, ordinary)[0]
        self.assertEqual(finding["title"], "Authorization Bypass")
        self.assertEqual(finding["classification_titles"], [])

    def test_atx_headline_levels_and_quotes_share_title_detail_normalization(self) -> None:
        for level in range(1, 7):
            for quote in ("", "> ", "> > "):
                with self.subTest(level=level, quote=quote):
                    body = quote + "#" * level + " Preserve retry identity.\n\nKeep the substantive explanation."
                    finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
                    self.assertEqual(finding["title"], "Preserve retry identity.")
                    self.assertEqual(finding["display_detail"], "Keep the substantive explanation.")
                    self.assertFalse(finding["display_title_is_excerpt"])

    def test_unheaded_hosted_issue_body_and_low_value_badge_are_preserved(self) -> None:
        issue = "The incoming workflow request must match the existing request identity. " * 5
        body = "_📐 Maintainability & Code Quality_ | _🔵 Trivial_ | _💤 Low value_\n" + issue
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(4142913648, body)[0]
        self.assertEqual(finding["display_severity"], "Trivial")
        self.assertEqual(finding["display_detail"], issue.strip())
        self.assertNotIn("Low value", finding["title"])

    def test_hosted_aggregate_display_stays_bounded_with_omitted_long_sections(self) -> None:
        findings = [
            {"title": f"Finding {index}", "display_detail": f"Body {index}. " + "x" * 2_000}
            for index in range(1, 81)
        ]

        detail = sqlite_finding_text._hosted_aggregate_display_detail(findings, "Aggregate")

        self.assertLessEqual(len(detail), 8000)
        self.assertIn("Omitted ", detail)
        self.assertIn("[Section excerpt truncated; see original source comment.]", detail)
        retained = int(detail.split("Omitted ", 1)[1].split(" provider sections", 1)[0])
        self.assertEqual(detail.count("**Provider section "), len(findings) - retained)

    def test_hosted_aggregate_display_preserves_mixed_empty_and_short_sections(self) -> None:
        findings = [
            {"title": "Empty", "display_detail": ""},
            {"title": "Short", "display_detail": "Short explanation."},
            {"title": "Long", "display_detail": "Long explanation. " + "x" * 120},
        ]

        detail = sqlite_finding_text._hosted_aggregate_display_detail(findings, "Aggregate")

        self.assertLessEqual(len(detail), 8000)
        self.assertNotIn("Omitted ", detail)
        self.assertEqual(detail.count("**Provider section "), len(findings))
        self.assertIn("Short explanation.", detail)
        self.assertIn(findings[2]["display_detail"], detail)

    def test_explicit_nested_provider_tools_block_is_removed(self) -> None:
        sample = "```xml\n<Tools>ordinary example</Tools>\n```"
        body = ("**Align the security summary.**\nPreserve the actual explanation.\n" + sample +
                "\n<details>\n<summary>🧰 Tools</summary>\n<details>\n"
                "<summary>🪛 Checkov (3.3.17)</summary>\nCKV_OPENAPI_4/5 diagnostic output\n"
                "</details>\n</details>\n<details><summary>Ordinary evidence</summary>Keep this prose.</details>")
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(4151800013, body)[0]
        self.assertNotIn("Checkov", finding["display_detail"])
        self.assertNotIn("CKV_OPENAPI", finding["display_detail"])
        self.assertIn(sample, finding["display_detail"])
        self.assertIn("Keep this prose.", finding["display_detail"])

    def test_actual_security_and_orphan_prompt_display_shapes(self) -> None:
        header = "_🔒 Security & Privacy_ | _🛡️ Detected with Advanced Tier_ | _🟠 Major_ | _🏗️ Heavy lift_"
        body = (header + "\n<details>\n<summary>🧩 Analysis chain</summary>\n"
                "Script executed:\n```bash\necho diagnostic\n```\n</details>\n"
                "**Broken Authentication**\n\n**Reachability:** External  \n"
                "**Exploitability:** Difficult  \n**CWE:** [CWE-294](https://cwe.mitre.org/data/definitions/294.html)\n"
                "**Quarantine replay admission after marker loss.** Production Redis absence fails closed.\n"
                "```java\nFoo<T> sample;\n```\n<summary>🤖 Prompt for AI Agents</summary>\n"
                "```text\nDo not display this truncated prompt")
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(4152944363, body)[0]
        self.assertEqual(finding["title"], "Quarantine replay admission after marker loss.")
        self.assertEqual(finding["display_severity"], "Major")
        self.assertEqual(finding["display_detail"], "Production Redis absence fails closed.\n```java\nFoo<T> sample;\n```")
        self.assertNotIn("Broken Authentication", finding["display_detail"])
        sample = "```html\n<summary>🤖 Prompt for AI Agents</summary>\nFoo<T>\n```"
        ordinary = sqlite_hosted_capture._hosted_comment_finding_segments(
            202, "**Preserve the sample.**\n" + sample)[0]
        self.assertEqual(ordinary["display_detail"], sample)

    def test_actual_analysis_before_headline_and_inline_duplicate(self) -> None:
        for title in ("Require explicit non-application evidence.", "Keep tenantId consistent."):
            with self.subTest(title=title):
                body = ("_🗄️ Data Integrity & Integration_ | _🟠 Major_ | _⚡ Quick win_\n"
                        "<details><summary>🔎 Supported by static analysis</summary>"
                        "Script executed:\n```bash\n" + "echo diagnostic\n" * 100 + "```\n</details>\n"
                        f"**{title}** Lines 20–25 must preserve `tenantId`.\n"
                        "<details>\n<summary>Suggested fix</summary>\n```diff\n+noise\n```\n</details>\n"
                        "<summary>🤖 Prompt for AI Agents</summary>Rewrite everything")
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(4152944302, body)[0]
                self.assertEqual(finding["title"], title)
                self.assertEqual(finding["display_detail"], "Lines 20–25 must preserve `tenantId`.")
                self.assertEqual(finding["display_severity"], "Major")

    def test_hosted_severity_reads_explicit_badges_only(self) -> None:
        cases = [("_Potential issue_ | _🔴 Critical_ | _Quick win_", "Critical"),
                 ("_Correctness_ | _Trivial_ | _Heavy lift_", "Trivial"),
                 ("**[P1] Bug**", "P1"), ("> **Severity: High**", "High"),
                 ("**Major**", "Major"), ("**Major impact**", None),
                 ("_Bug_ | _Major impact_ | _Quick win_", None),
                 ("The Critical issue has Major impact.", None),
                 ("```md\n**Minor**\n```", None),
                 ("<details><summary>Prompt for AI Agents</summary>**Major**</details>", None),
                 ("<details><summary>Supported by static analysis</summary>**Low**</details>", None),
                 ("**Major**\n**Minor**", None), ("No provider badge.", None)]
        for badge, expected in cases:
            with self.subTest(badge=badge):
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(
                    202, badge + "\n**Validate the target.**\nIssue explanation.")[0]
                self.assertEqual(finding["display_severity"], expected)

    def test_hosted_severity_is_independent_per_fingerprint(self) -> None:
        body = ("_Bug_ | _Major_ | _Quick win_\n**First issue.**\n"
                "<!-- cr-comment:v1:aaaaaaaaaaaaaaaaaaaaaaaa -->\n"
                "_Bug_ | _Minor_ | _Quick win_\n**Second issue.**\n"
                "<!-- cr-comment:v1:bbbbbbbbbbbbbbbbbbbbbbbb -->")
        findings = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)
        self.assertEqual([item["display_severity"] for item in findings], ["Major", "Minor"])
        self.assertEqual(len({item["key"] for item in findings}), 2)

    def test_headline_ignores_analysis_wrappers_and_scripts(self) -> None:
        issue = ("The `AlreadyStarted` path does not compare the incoming "
                 "`PreparedWorldInstanceRequest` with the existing workflow's request identity.")
        body = (
            "_Data Integrity & Integration_ | _Major_ | _Quick win_\n"
            "<details>\n<summary>Supported by static analysis</summary>\n"
            "Script executed:\n```bash\n" + "echo diagnostic\n" * 100 +
            "```\nRepository: owner/repo\nLength of output: 37011\n---\n</details>\n" + issue
        )
        self.assertEqual(sqlite_provider_imports._first_line(body), issue)
        findings = sqlite_hosted_capture._hosted_comment_finding_segments(4142913648, body)
        self.assertEqual(findings[0]["title"], issue)
        self.assertEqual(findings[0]["key"], "hosted-comment:4142913648")
        self.assertNotIn(issue, findings[0]["detail"])
        self.assertEqual(findings[0]["display_detail"], issue)
        self.assertEqual(sqlite_provider_imports._first_line(body + "\n**Check the request identity.**"),
                         "Check the request identity.")

    def test_headline_preserves_issue_code_and_omits_wrapper_only_content(self) -> None:
        for body, title in (
            ("<p><strong>Check `x < y` before returning `<T>`.</strong></p>",
             "Check `x < y` before returning `<T>`."),
            ("> **[P1] Bug**\n> **Validate the current target.**", "Validate the current target."),
            (("<details>\n<summary>Supported by static analysis</summary>\n"
              "Script executed:\n```bash\necho diagnostic\n```\n</details>"), None),
            ("**[P1] Bug**\n---\n<script>alert('bad')</script>", None),
            ("```python\n**Misleading headline**\n```", None),
            ("<details>\n<summary>**Actual issue headline.**</summary>\n"
             + "The issue explanation.\n</details>", "Actual issue headline."),
            ("**\n__\n##\n>\n---", None),
            ("_Functional Correctness_ | _Minor_ | _Quick win_ Keep the issue sentence.",
             "Keep the issue sentence."),
            ("**[P1] Bug** Keep the issue sentence.", "Keep the issue sentence."),
            ("<details>\n<summary>Committable suggestion</summary>\n"
             + "**Apply this patch**\n```diff\n+ fix\n```\n</details>", None),
        ):
            with self.subTest(body=body):
                self.assertEqual(sqlite_provider_imports._first_line(body), title)
        fallback = sqlite_hosted_capture._hosted_comment_finding_segments(202, "<details>\n</details>")
        self.assertEqual(fallback[0]["title"], "CodeRabbit review comment 202")

    def test_hosted_display_detail_keeps_issue_markdown_and_removes_provider_noise(self) -> None:
        body = (
            "_Data Integrity & Integration_ | _Major_ | _Quick win_\n\n"
            "**Compare the workflow request identity.**\n\n"
            "<details>\n<summary>Supported by static analysis</summary>\n"
            "Script executed:\n```bash\n" + "echo diagnostics\n" * 100 + "```\n</details>\n"
            "The incoming `requestId` must match [the contract](https://example.test/contract).\n\n"
            "<p>Return an error when `x < y`.</p>\n\n"
            "<details><summary>Prompt for AI Agents</summary>Rewrite the whole file.</details>"
        )
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
        self.assertEqual(finding["display_detail"],
                         "The incoming `requestId` must match [the contract](https://example.test/contract).\n\n"
                         "Return an error when `x < y`.")
        self.assertIn("Supported by static analysis", finding["detail"])
        self.assertEqual(sqlite_provider_imports._first_line("The script executed with the wrong identity."),
                         "The script executed with the wrong identity.")

    def test_hosted_detail_preserves_literal_ordinary_fenced_samples(self) -> None:
        sample = "```xml\n  <Foo<T>>\n    <script>literal example</script>\n    <!-- literal sample -->\n\n\n  </Foo<T>>\n```"
        body = "**Validate the sample.**\n\n" + sample + "\n\nScript executed:\n```bash\necho diagnostics\n```"
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)[0]
        self.assertEqual(finding["display_detail"], sample)
        self.assertEqual(finding["title"], "Validate the sample.")

    def test_hosted_detail_removes_prose_headline_without_changing_matching_sample(self) -> None:
        sample = "```markdown\n**Validate target.**\n```"
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(
            202, sample + "\n\n**Validate target.**\n\nKeep this issue explanation."
        )[0]
        self.assertEqual(finding["title"], "Validate target.")
        self.assertTrue(finding["display_detail"].startswith(sample))
        self.assertEqual(finding["display_detail"].count("**Validate target.**"), 1)
        self.assertTrue(finding["display_detail"].endswith("Keep this issue explanation."))

    def test_hosted_detail_deduplicates_quoted_headline_only(self) -> None:
        finding = sqlite_hosted_capture._hosted_comment_finding_segments(
            202, "> **Validate target.**\n\n> Keep this distinct quoted explanation."
        )[0]
        self.assertEqual(finding["title"], "Validate target.")
        self.assertEqual(finding["display_detail"], "> Keep this distinct quoted explanation.")

    def test_hosted_metadata_prefixes_do_not_hide_substantive_issue_prose(self) -> None:
        for issue in (
            "Repository: lookup uses the wrong tenant",
            "Analysis results: the route check accepts the wrong target.",
            "Script output: the missing identity is silently ignored.",
            "Committable suggestion does not validate the changed request.",
            "Script executed: the wrong tenant context reaches the worker.",
            "Supported by static analysis but the reported identifier is incorrect.",
            "Length of output: the counter is updated before validation.",
        ):
            with self.subTest(issue=issue):
                finding = sqlite_hosted_capture._hosted_comment_finding_segments(
                    202, "**Validate the target.**\n\n" + issue
                )[0]
                self.assertEqual(finding["display_detail"], issue)
                self.assertEqual(sqlite_provider_imports._first_line(issue), issue)

    def test_hosted_exact_provider_labels_and_metadata_still_stay_out_of_issue(self) -> None:
        for label in (
            "Supported by static analysis", "Script executed:", "Analysis results:",
            "Script output:", "Committable suggestion", "Prompt for AI Agents",
            "Repository: owner/repo", "Length of output: 37011", "**Script output:**",
        ):
            with self.subTest(label=label):
                body = label + "\n\nThe route check accepts the wrong tenant."
                self.assertEqual(sqlite_provider_imports._first_line(body),
                                 "The route check accepts the wrong tenant.")
                self.assertIsNone(sqlite_provider_imports._first_line(label))

    def test_wrapper_fix_keeps_multiple_fingerprinted_finding_keys(self) -> None:
        body = (
            "<details>\n</details>\nFirst actionable issue.\n"
            "<!-- cr-comment:v1:aaaaaaaaaaaaaaaaaaaaaaaa -->\n---\n"
            "**[P1] Bug**\n**Second actionable issue.**\n"
            "<!-- cr-comment:v1:bbbbbbbbbbbbbbbbbbbbbbbb -->"
        )
        findings = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)
        self.assertEqual([item["title"] for item in findings],
                         ["First actionable issue.", "Second actionable issue."])
        self.assertEqual([item["key"] for item in findings], [
            "hosted-comment:202:fingerprint:aaaaaaaaaaaaaaaaaaaaaaaa",
            "hosted-comment:202:fingerprint:bbbbbbbbbbbbbbbbbbbbbbbb",
        ])

    def test_checkpoint_id_matches_hosted_type_case_insensitively_and_exact_review_id(self) -> None:
        checkpoints = [
            SimpleNamespace(type="hOsTeD", hosted_review_id=102, comment_id=801),
            SimpleNamespace(type="HOSTED", hosted_review_id=103, comment_id=802),
            SimpleNamespace(type="CLI", hosted_review_id=103, comment_id=803),
        ]
        with patch.object(
            sqlite_hosted_capture.evidence,
            "parse_checkpoint_comments",
            return_value=(checkpoints, None),
        ):
            checkpoint_id = sqlite_hosted_capture._checkpoint_id({"comments": {"nodes": []}}, 103)

        self.assertEqual(checkpoint_id, "802")

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
        replay_payload["data"]["repository"]["pullRequest"]["comments"]["nodes"][1]["updatedAt"] = (
            "2026-09-29T01:06:00Z"
        )
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

    def test_completed_replay_requires_attempt_to_link_its_exact_source_run(self) -> None:
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
        sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=payload,
        )
        self.records.import_completed_run(
            run_id="other-hosted-run",
            source_pr=PR,
            channel="hosted",
            findings=(),
            source_decisions=(),
            source_head=HEAD,
            started_at=TRIGGER_AT,
            finished_at="2026-09-29T02:00:00Z",
        )
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE review_attempts SET run_id = ? WHERE attempt_id = ?",
                ("other-hosted-run", self.attempt_id),
            )
        with self.assertRaisesRegex(sqlite_hosted_capture.HostedCaptureError, "different source run"):
            sqlite_hosted_capture.record_hosted_terminal_result(
                self.records,
                attempt_id=self.attempt_id,
                repo=REPO,
                source_pr=PR,
                trigger_record=self.trigger_record(),
                payload=payload,
            )

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

    def test_old_cumulative_comments_do_not_overflow_new_result_archive(self) -> None:
        old_comment = {
            "databaseId": 99,
            "author": {"login": "coderabbitai[bot]"},
            "body": "old review text " + "x" * (9 * 1024 * 1024),
            "createdAt": "2026-09-28T01:00:00Z",
            "updatedAt": "2026-09-28T01:00:00Z",
        }
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
        captured = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=self.payload(comments=[old_comment, self.trigger_comment(), reply, summary]),
        )
        self.assertEqual(captured["state"], "completed")
        with sqlite3.connect(self.database) as connection:
            archive = connection.execute(
                "SELECT content FROM review_artifacts WHERE attempt_id = ? AND kind = 'hosted_comments'",
                (self.attempt_id,),
            ).fetchone()[0]
        self.assertLess(len(archive), 8 * 1024 * 1024)
        self.assertEqual(
            [item["databaseId"] for item in json.loads(archive)["comments"]],
            [101, 102, 103],
        )

    def test_archive_window_excludes_replies_after_review_completion(self) -> None:
        thread = {
            "id": "thread-1",
            "comments": {
                "nodes": [
                    {
                        "databaseId": 201,
                        "author": {"login": "coderabbitai[bot]"},
                        "createdAt": "2026-09-29T01:02:00Z",
                        "body": "Fix the retry fence",
                    },
                    {
                        "databaseId": 202,
                        "author": {"login": "maintainer"},
                        "createdAt": "2026-09-29T02:00:00Z",
                        "body": "x" * (9 * 1024 * 1024),
                    },
                ]
            },
        }
        pull_request = sqlite_hosted_capture._complete_pull_request(
            self.payload(comments=[self.trigger_comment()], review_threads=[thread]), PR
        )
        window = sqlite_hosted_capture.archive_window(
            pull_request, self.trigger_record(), finished_at="2026-09-29T01:04:00Z"
        )
        self.assertEqual(
            [item["databaseId"] for item in window["review_threads"][0]["comments"]["nodes"]],
            [201],
        )

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
        self.assertEqual(finding["source_finding_key"], "hosted-comment:202")
        self.assertEqual(finding["title"], "Check the boundary before using this value.")

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

    def test_live_finding_projection_matches_import_headline_and_redacted_detail(self) -> None:
        body = (
            "**[P1] Bug**\n\n"
            "**Check the current route target before recording the decision.**\n\n"
            "Keep the audit note, but redact token=ghp_" + "A" * 30 + " from the detail."
        )
        review = {
            "databaseId": 201,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-29T01:03:00Z",
            "commit": {"oid": HEAD},
        }
        comment = {
            "databaseId": 202,
            "author": {"login": "coderabbitai[bot]"},
            "body": body,
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
            "url": "https://github.example/owner/repo/pull/42#discussion_r202",
        }
        thread = {
            "id": "PRRT_thread_1",
            "isResolved": False,
            "isOutdated": False,
            "path": "src/example.py",
            "comments": {"nodes": [comment]},
        }

        sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=self.payload(comments=[self.trigger_comment()], reviews=[review], review_threads=[thread]),
        )

        finding = self.records.history(PR)["findings"][0]
        self.assertEqual(
            finding["title"],
            sqlite_provider_imports._first_line(body),
        )
        self.assertEqual(
            finding["detail"],
            sqlite_provider_imports._safe_finding_detail(body),
        )
        self.assertEqual(finding["title"], "Check the current route target before recording the decision.")
        self.assertNotIn("ghp_", finding["detail"])
        self.assertIn("[redacted credential]", finding["detail"])

    def test_multiple_fingerprinted_sections_become_findings_on_the_same_thread(self) -> None:
        first_fingerprint = "a1e39b83f15845dc073e0b8b"
        second_fingerprint = "30d1ed367e7421c8d02b1413"
        body = (
            "**Check the first boundary.**\n\n"
            "The first section has its own detail.\n\n"
            "```text\n---\n```\n\n"
            "<!-- fingerprinting:phantom:medusa:pangolin -->\n"
            "<!-- cr-indicator-types:potential_issue -->\n"
            f"<!-- cr-comment:v1:{first_fingerprint} -->\n\n"
            "---\n\n"
            "**Check the second boundary.**\n\n"
            "The second section has different detail.\n\n"
            "<!-- fingerprinting:phantom:medusa:pangolin -->\n"
            "<!-- cr-indicator-types:potential_issue -->\n"
            f"<!-- cr-comment:v1:{second_fingerprint} -->\n\n"
            "_Source: Path instructions_\n"
            "<!-- This is an auto-generated comment by CodeRabbit -->"
        )
        review = {
            "databaseId": 201,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-29T01:03:00Z",
            "commit": {"oid": HEAD},
        }
        comment = {
            "databaseId": 202,
            "author": {"login": "coderabbitai[bot]"},
            "body": body,
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
            "url": "https://github.example/owner/repo/pull/42#discussion_r202",
        }
        thread = {
            "id": "PRRT_thread_1",
            "isResolved": False,
            "isOutdated": False,
            "path": "src/example.py",
            "comments": {"nodes": [comment]},
        }

        captured = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=self.payload(comments=[self.trigger_comment()], reviews=[review], review_threads=[thread]),
        )

        history = self.records.history(PR)
        findings = {item["source_finding_key"]: item for item in history["findings"]}
        self.assertEqual(captured["counts"]["found"], 2)
        self.assertEqual(
            set(findings),
            {
                f"hosted-comment:202:fingerprint:{first_fingerprint}",
                f"hosted-comment:202:fingerprint:{second_fingerprint}",
            },
        )
        self.assertEqual(
            findings[f"hosted-comment:202:fingerprint:{first_fingerprint}"]["title"],
            "Check the first boundary.",
        )
        self.assertEqual(
            findings[f"hosted-comment:202:fingerprint:{second_fingerprint}"]["title"],
            "Check the second boundary.",
        )
        first_detail = findings[f"hosted-comment:202:fingerprint:{first_fingerprint}"]["detail"]
        second_detail = findings[f"hosted-comment:202:fingerprint:{second_fingerprint}"]["detail"]
        self.assertIn("first section has its own detail", first_detail)
        self.assertIn("---", first_detail)
        self.assertNotIn("second section", first_detail)
        self.assertIn("second section has different detail", second_detail)
        self.assertNotIn("---", second_detail)
        self.assertNotIn("fingerprinting:", first_detail + second_detail)
        self.assertNotIn("cr-indicator-types:", first_detail + second_detail)

    def test_multisegment_rejected_titles_keep_distinct_fingerprint_fallbacks(self) -> None:
        first_fingerprint = "a1e39b83f15845dc073e0b8b"
        second_fingerprint = "30d1ed367e7421c8d02b1413"
        body = (
            "**Bearer first-unsafe-value**\n\n"
            "The first section has useful public detail.\n\n"
            f"<!-- cr-comment:v1:{first_fingerprint} -->\n\n---\n\n"
            "**Bearer second-unsafe-value**\n\n"
            "The second section has separate public detail.\n\n"
            f"<!-- cr-comment:v1:{second_fingerprint} -->\n\n"
            "_Source: Coding guidelines_\n<!-- This is an auto-generated comment by CodeRabbit -->\n"
        )
        review = {
            "databaseId": 201,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-29T01:03:00Z",
            "commit": {"oid": HEAD},
        }
        comment = {
            "databaseId": 202,
            "author": {"login": "coderabbitai[bot]"},
            "body": body,
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
            "url": "https://github.example/owner/repo/pull/42#discussion_r202",
        }
        thread = {
            "id": "PRRT_thread_1",
            "isResolved": False,
            "isOutdated": False,
            "path": "src/example.py",
            "comments": {"nodes": [comment]},
        }

        captured = sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=self.payload(comments=[self.trigger_comment()], reviews=[review], review_threads=[thread]),
        )

        segments = sqlite_hosted_capture._hosted_comment_finding_segments(202, body)
        self.assertEqual([item["fingerprint"] for item in segments], [first_fingerprint, second_fingerprint])
        findings = {item["source_finding_key"]: item for item in self.records.history(PR)["findings"]}
        self.assertEqual(captured["counts"]["found"], 2)
        self.assertEqual(
            set(findings),
            {
                f"hosted-comment:202:fingerprint:{first_fingerprint}",
                f"hosted-comment:202:fingerprint:{second_fingerprint}",
            },
        )
        self.assertEqual(
            findings[f"hosted-comment:202:fingerprint:{first_fingerprint}"]["title"],
            f"CodeRabbit review comment 202 finding {first_fingerprint}",
        )
        self.assertEqual(
            findings[f"hosted-comment:202:fingerprint:{second_fingerprint}"]["title"],
            f"CodeRabbit review comment 202 finding {second_fingerprint}",
        )
        self.assertIn(
            "first section has useful public detail",
            findings[f"hosted-comment:202:fingerprint:{first_fingerprint}"]["detail"],
        )
        self.assertIn(
            "second section has separate public detail",
            findings[f"hosted-comment:202:fingerprint:{second_fingerprint}"]["detail"],
        )

    def test_final_fingerprint_accepts_only_empty_or_known_auxiliary_tail(self) -> None:
        fingerprint = "a1e39b83f15845dc073e0b8b"
        prefix = f"**One finding.**\nDetails.\n<!-- cr-comment:v1:{fingerprint} -->"
        valid_tails = (
            "",
            " \n\n",
            "\n\n---\n\n",
            "\n<!-- fingerprinting:phantom:medusa:pangolin -->\n<!-- cr-indicator-types:potential_issue -->",
            "\n<!-- This is an auto-generated comment by CodeRabbit -->\n",
            "\n\n_Source: Path instructions_\n<!-- This is an auto-generated comment by CodeRabbit -->\n",
            "\n\n_Source: Learnings_\n<!-- This is an auto-generated comment by CodeRabbit -->\n",
            "\n\n_Source: Coding guidelines_\n<!-- This is an auto-generated comment by CodeRabbit -->\n",
            "\n\n_Source: Linters/SAST tools_\n<!-- This is an auto-generated comment by CodeRabbit -->\n",
            "\n\n_Source: Linters/SAST tools_\n<!-- This is an auto-generated reply by CodeRabbit -->\n",
            "\n\n_Sources: Coding guidelines_\n<!-- This is an auto-generated comment by CodeRabbit -->\n",
            "\n\n_Sources: Coding guidelines, Path instructions_\n<!-- This is an auto-generated comment by CodeRabbit -->\n",
        )
        for tail in valid_tails:
            with self.subTest(tail=tail):
                findings = sqlite_hosted_capture._hosted_comment_finding_segments(202, prefix + tail)
                self.assertEqual(len(findings), 1)
                self.assertEqual(findings[0]["key"], "hosted-comment:202")

        invalid_tails = (
            "\nAn unmarked substantive finding.",
            "\n```text\nFenced substantive detail.\n```",
            "\n<!-- unknown auxiliary: preserve this finding -->",
            "\n_Source: Unrecognized source_\n<!-- This is an auto-generated comment by CodeRabbit -->",
            "\n<!-- This is an auto-generated comment by CodeRabbit -->\n_Source: Path instructions_",
            "\n_Source: Linters/SAST tools_\nUnmarked text.\n<!-- This is an auto-generated comment by CodeRabbit -->",
            "\n_Source: Coding guidelines_",
            "\n_Source: Linters/SAST tools_\n<!-- This is an auto-generated reply by another bot -->",
            "\n_Sources: Coding guidelines, Unknown source_\n<!-- This is an auto-generated comment by CodeRabbit -->",
            "\n_Sources: Coding guidelines, Path instructions_\nunmarked content\n<!-- This is an auto-generated comment by CodeRabbit -->",
        )
        for tail in invalid_tails:
            with (
                self.subTest(tail=tail),
                self.assertRaisesRegex(sqlite_hosted_capture.HostedCaptureError, "unmarked content after its final"),
            ):
                sqlite_hosted_capture._hosted_comment_finding_segments(202, prefix + tail)

    def test_single_fingerprint_keeps_legacy_key_and_malformed_markers_fail_closed(self) -> None:
        fingerprint = "a1e39b83f15845dc073e0b8b"
        one = sqlite_hosted_capture._hosted_comment_finding_segments(
            202,
            f"**One finding.**\nDetails.\n<!-- cr-comment:v1:{fingerprint} -->",
        )
        self.assertEqual(len(one), 1)
        self.assertEqual(one[0]["key"], "hosted-comment:202")
        self.assertEqual(one[0]["fingerprint"], fingerprint)
        self.assertNotIn("cr-comment:v1", one[0]["detail"])

        prose_mentions = sqlite_hosted_capture._hosted_comment_finding_segments(
            202,
            "**Reject malformed `cr-comment:v1` markers.**\n\n"
            "The cr-comment:v1 prefix is mentioned in ordinary prose.\n\n"
            "<!-- Note about cr-comment:v1 examples. -->\n\n"
            "```text\n<!-- cr-comment:v1:not-a-fingerprint -->\n```\n\n"
            f"Details.\n<!-- cr-comment:v1:{fingerprint} -->",
        )
        self.assertEqual(len(prose_mentions), 1)
        self.assertEqual(prose_mentions[0]["key"], "hosted-comment:202")
        self.assertEqual(prose_mentions[0]["title"], "Reject malformed `cr-comment:v1` markers.")

        with self.assertRaisesRegex(sqlite_hosted_capture.HostedCaptureError, "malformed cr-comment:v1 marker"):
            sqlite_hosted_capture._hosted_comment_finding_segments(
                202, "**Finding.**\n<!-- cr-comment:v1:not-a-fingerprint -->"
            )
        with self.assertRaisesRegex(sqlite_hosted_capture.HostedCaptureError, "malformed cr-comment:v1 marker"):
            sqlite_hosted_capture._hosted_comment_finding_segments(202, "**Finding.**\n<!-- cr-comment:v1:bad")
        with self.assertRaisesRegex(sqlite_hosted_capture.HostedCaptureError, "repeats a cr-comment:v1 fingerprint"):
            sqlite_hosted_capture._hosted_comment_finding_segments(
                202,
                "**First.**\n"
                f"<!-- cr-comment:v1:{fingerprint} -->\n\n---\n\n"
                "**Second.**\n"
                f"<!-- cr-comment:v1:{fingerprint} -->",
            )

    def test_live_finding_uses_safe_fallback_for_secret_headline(self) -> None:
        body = "**Bearer unsafe-value**\nDetails include token=ghp_" + "A" * 30
        review = {
            "databaseId": 201,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-29T01:03:00Z",
            "commit": {"oid": HEAD},
        }
        comment = {
            "databaseId": 202,
            "author": {"login": "coderabbitai[bot]"},
            "body": body,
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
            "url": "https://github.example/owner/repo/pull/42#discussion_r202",
        }
        thread = {
            "id": "PRRT_thread_1",
            "isResolved": False,
            "isOutdated": False,
            "path": "src/example.py",
            "comments": {"nodes": [comment]},
        }

        sqlite_hosted_capture.record_hosted_terminal_result(
            self.records,
            attempt_id=self.attempt_id,
            repo=REPO,
            source_pr=PR,
            trigger_record=self.trigger_record(),
            payload=self.payload(comments=[self.trigger_comment()], reviews=[review], review_threads=[thread]),
        )

        finding = self.records.history(PR)["findings"][0]
        self.assertEqual(finding["title"], "CodeRabbit review comment 202")
        self.assertNotIn("unsafe-value", finding["detail"])
        self.assertNotIn("ghp_", finding["detail"])
        self.assertIn("[redacted credential]", finding["detail"])

    def test_rejected_fallback_detail_is_omitted_without_losing_scrubbed_archive(self) -> None:
        raw_secret = "ghp_" + "A" * 30
        body = f"**Bearer unsafe-value**\nDetails include token={raw_secret}"
        review = {
            "databaseId": 201,
            "author": {"login": "coderabbitai[bot]"},
            "body": f"<!-- walkthrough_start -->\nReviewed {HEAD}",
            "state": "COMMENTED",
            "submittedAt": "2026-09-29T01:03:00Z",
            "commit": {"oid": HEAD},
        }
        comment = {
            "databaseId": 202,
            "author": {"login": "coderabbitai[bot]"},
            "body": body,
            "createdAt": "2026-09-29T01:02:00Z",
            "updatedAt": "2026-09-29T01:02:00Z",
            "url": "https://github.example/owner/repo/pull/42#discussion_r202",
        }
        thread = {
            "id": "PRRT_thread_1",
            "isResolved": False,
            "isOutdated": False,
            "path": "src/example.py",
            "comments": {"nodes": [comment]},
        }

        with patch.object(sqlite_hosted_capture, "_safe_finding_detail", return_value="secret=unsafe-fallback"):
            captured = sqlite_hosted_capture.record_hosted_terminal_result(
                self.records,
                attempt_id=self.attempt_id,
                repo=REPO,
                source_pr=PR,
                trigger_record=self.trigger_record(),
                payload=self.payload(comments=[self.trigger_comment()], reviews=[review], review_threads=[thread]),
            )

        self.assertEqual(captured["state"], "completed")
        self.assertEqual(captured["counts"]["found"], 1)
        finding = self.records.history(PR)["findings"][0]
        self.assertEqual(finding["source_finding_key"], "hosted-comment:202")
        self.assertEqual(finding["title"], "CodeRabbit review comment 202")
        self.assertEqual(finding["detail"], "")
        archived = json.loads(self.records.attempt_artifacts(self.attempt_id)["hosted_comments"])
        archived_body = archived["review_threads"][0]["comments"]["nodes"][0]["body"]
        self.assertNotIn(raw_secret, archived_body)
        self.assertIn("[redacted credential]", archived_body)

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
                attempt = next(item for item in self.records.attempt_history(PR) if item["attempt_id"] == attempt_id)
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

            with patch.object(sqlite_hosted_capture.github, "fetch_pull_request", return_value=payload) as fetch:
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
                replay = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)
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

                with patch.object(sqlite_hosted_capture.github, "fetch_pull_request", return_value=payload) as fetch:
                    report = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)

                fetch.assert_called_once_with(REPO, PR)
                self.assertEqual(len(report[bucket]), 1)
                self.assertEqual(report[bucket][0]["state"], expected_report_state)
                self.assertEqual(records.attempt(attempt_id)["state"], expected_attempt_state)
                self.assertEqual(records.history(PR)["runs"], [])

    def test_sync_records_incomplete_coverage_as_failed_and_replays_idempotently(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory) / "git-common"
            records = self.new_records(Path(directory) / "controller.sqlite3")
            attempt_id = "hosted-sync-incomplete-coverage"
            trigger_at = "2026-09-29T01:00:00Z"
            response_at = "2026-09-29T01:02:00Z"
            trigger_record = self.trigger_record(
                trigger_id=701,
                created_at=trigger_at,
                posting_started_at="2026-09-29T00:59:00Z",
                attempt_id=attempt_id,
            )
            self.write_trigger_record(common, trigger_record)
            response = {
                "databaseId": 702,
                "author": {"login": "coderabbitai[bot]"},
                "body": "Full review finished with incomplete file coverage.",
                "createdAt": response_at,
                "updatedAt": response_at,
            }
            payload = self.payload(
                comments=[
                    self.trigger_comment(trigger_id=701, created_at=trigger_at),
                    response,
                ]
            )
            terminal = hosted.TriggerState(
                "failed_incomplete_coverage",
                True,
                True,
                REPO,
                PR,
                HEAD,
                HEAD,
                701,
                trigger_at,
                trigger_record["trigger"]["url"],
                "full",
                702,
                response_at,
                None,
                None,
                "CodeRabbit finished after explicitly reporting incomplete file coverage",
                duration_seconds=120,
            )

            with (
                patch.object(sqlite_hosted_capture.github, "fetch_pull_request", return_value=payload) as fetch,
                patch.object(sqlite_hosted_capture.hosted, "trigger_state", return_value=terminal),
            ):
                first = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)

            fetch.assert_called_once_with(REPO, PR)
            self.assertEqual(len(first["synced"]), 1)
            self.assertEqual(len(first["ambiguous"]), 0)
            self.assertEqual(len(first["pending"]), 0)
            self.assertEqual(len(first["errors"]), 0)
            self.assertEqual(first["synced"][0]["state"], "failed_incomplete_coverage")
            self.assertEqual(records.attempt(attempt_id)["state"], "failed")
            self.assertEqual(records.history(PR)["runs"], [])

            with patch.object(
                sqlite_hosted_capture.github,
                "fetch_pull_request",
                side_effect=AssertionError("terminal replay must not refetch GitHub"),
            ) as replay_fetch:
                second = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)

            replay_fetch.assert_not_called()
            self.assertEqual(len(second["synced"]), 1)
            self.assertEqual(len(second["ambiguous"]), 0)
            self.assertEqual(len(second["pending"]), 0)
            self.assertEqual(len(second["errors"]), 0)
            self.assertEqual(second["synced"][0]["state"], "failed")
            self.assertTrue(second["synced"][0]["idempotent_replay"])

    def test_sync_buckets_completed_unattributed_as_ambiguous_on_first_and_repeat(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory) / "git-common"
            records = self.new_records(Path(directory) / "controller.sqlite3")
            attempt_id = "hosted-sync-unattributed-completed"
            trigger_at = "2026-09-29T01:00:00Z"
            response_at = "2026-09-29T01:02:00Z"
            trigger_record = self.trigger_record(
                trigger_id=703,
                created_at=trigger_at,
                posting_started_at="2026-09-29T00:59:00Z",
                attempt_id=attempt_id,
            )
            self.write_trigger_record(common, trigger_record)
            response = {
                "databaseId": 704,
                "author": {"login": "coderabbitai[bot]"},
                "body": "Full review finished.",
                "createdAt": response_at,
                "updatedAt": response_at,
            }
            payload = self.payload(
                comments=[
                    self.trigger_comment(trigger_id=703, created_at=trigger_at),
                    response,
                ]
            )
            terminal = hosted.TriggerState(
                "completed",
                True,
                False,
                REPO,
                PR,
                HEAD,
                HEAD,
                703,
                trigger_at,
                trigger_record["trigger"]["url"],
                "full",
                704,
                response_at,
                None,
                None,
                "completed Hosted response is not attributable",
                duration_seconds=120,
            )

            with (
                patch.object(sqlite_hosted_capture.github, "fetch_pull_request", return_value=payload) as fetch,
                patch.object(sqlite_hosted_capture.hosted, "trigger_state", return_value=terminal),
            ):
                first = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)

            fetch.assert_called_once_with(REPO, PR)
            self.assertEqual(len(first["synced"]), 0)
            self.assertEqual(len(first["ambiguous"]), 1)
            self.assertEqual(first["ambiguous"][0]["state"], "completed")
            self.assertEqual(len(first["pending"]), 0)
            self.assertEqual(len(first["errors"]), 0)
            self.assertEqual(records.attempt(attempt_id)["state"], "ambiguous")
            self.assertIsNone(records.attempt(attempt_id)["run_id"])
            self.assertEqual(records.history(PR)["runs"], [])

            with patch.object(
                sqlite_hosted_capture.github,
                "fetch_pull_request",
                side_effect=AssertionError("ambiguous replay must not refetch GitHub"),
            ) as replay_fetch:
                second = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)

            replay_fetch.assert_not_called()
            self.assertEqual(len(second["synced"]), 0)
            self.assertEqual(len(second["ambiguous"]), 1)
            self.assertEqual(second["ambiguous"][0]["state"], "ambiguous")
            self.assertEqual(len(second["pending"]), 0)
            self.assertEqual(len(second["errors"]), 0)
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

            with patch.object(sqlite_hosted_capture.github, "fetch_pull_request", side_effect=fetch_pr):
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
                report = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)

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
            reply = {
                "databaseId": 952,
                "author": {"login": "coderabbitai"},
                "body": "Full review finished.",
                "createdAt": "2026-09-29T01:01:00Z",
                "updatedAt": "2026-09-29T01:05:00Z",
                "url": "https://github.example/owner/repo/pull/42#issuecomment-952",
            }
            summary = {
                "databaseId": 953,
                "author": {"login": "coderabbitai[bot]"},
                "body": (
                    "No actionable comments were generated in the recent review.\n"
                    f"Reviewing files that changed from the base of the PR and between {HEAD[:12]} and {HEAD}."
                ),
                "createdAt": "2026-09-29T01:02:00Z",
                "updatedAt": "2026-09-29T01:02:00Z",
            }
            comments = [self.trigger_comment(trigger_id=951), reply, summary]
            metadata = {
                "state": "completed",
                "terminal": True,
                "attributable": True,
                "reason": "",
                "repository": REPO,
                "pull_request": PR,
                "head_sha": HEAD,
                "trigger_id": 951,
                "response_id": 952,
                "observed_at": "2026-09-29T01:05:00Z",
            }
            records.finish_attempt(
                attempt_id,
                state="completed",
                finished_at="2026-09-29T01:05:00Z",
                trigger_id="951",
                provider_review_id="952",
                artifacts={
                    "hosted_review": json.dumps([]),
                    "hosted_comments": json.dumps({"comments": comments, "review_threads": []}),
                    "metadata": json.dumps(metadata),
                },
            )

            with patch.object(
                sqlite_hosted_capture.github,
                "fetch_pull_request",
                side_effect=AssertionError("archived recovery does not need live GitHub"),
            ) as fetch:
                report = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)
            fetch.assert_not_called()
            self.assertEqual(report["errors"], [])
            self.assertEqual(len(report["synced"]), 1)
            self.assertTrue(report["synced"][0]["recovered_from_archive"])
            self.assertEqual(records.attempt(attempt_id)["run_id"], attempt_id)
            self.assertEqual(records.history(PR)["runs"][0]["counts"]["found"], 0)

    def test_sync_archives_durable_timeout_as_non_counting_attempt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory) / "git-common"
            records = self.new_records(Path(directory) / "controller.sqlite3")
            attempt_id = "hosted-sync-timeout"
            record = self.trigger_record(trigger_id=951, attempt_id=attempt_id)
            record["status"] = "timed_out"
            record["timeout"] = {
                "at": "2026-09-29T02:00:00Z",
                "observed_state": "awaiting_response",
                "observed_response_id": None,
                "reason": hosted.TIMEOUT_REASON,
            }
            self.write_trigger_record(common, record)
            sqlite_hosted_capture.start_hosted_attempt(
                records,
                attempt_id=attempt_id,
                source_pr=PR,
                candidate_sha=HEAD,
                started_at=TRIGGER_AT,
                metadata={"repository": REPO},
            )
            with patch.object(
                sqlite_hosted_capture.github,
                "fetch_pull_request",
                return_value=self.payload(comments=[self.trigger_comment(trigger_id=951)]),
            ):
                report = sqlite_hosted_capture.sync_hosted_pending(records, REPO, common=common, pr_number=PR)
            self.assertEqual(report["errors"], [])
            self.assertEqual(len(report["ambiguous"]), 1, report)
            self.assertEqual(records.attempt(attempt_id)["state"], "timed_out")
            self.assertEqual(records.history(PR)["runs"], [])

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
