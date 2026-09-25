#!/usr/bin/env python3
"""Focused proof for the unified GitHub, evidence, and Hosted mechanics."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from contextlib import nullcontext
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli as cli_module
from pr_review import evidence, github, hosted

REPO = "owner/repo"
PR = 42
HEAD = "a" * 40
BASE = "b" * 40


def comment(item_id: int, author: str, body: str, created: str, *, url: str | None = None):
    return {
        "databaseId": item_id,
        "author": {"login": author},
        "body": body,
        "createdAt": created,
        "updatedAt": created,
        "url": url or f"https://example.test/comments/{item_id}",
    }


def review_payload(
    comments: list[dict] | None = None,
    reviews: list[dict] | None = None,
    *,
    head: str = HEAD,
    threads: list[dict] | None = None,
):
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "headRefOid": head,
                    "commits": {"nodes": [{"commit": {"oid": head, "committedDate": "2026-09-23T00:00:00Z"}}]},
                    "reviewThreads": {"nodes": threads or []},
                    "comments": {"nodes": comments or []},
                    "reviews": {"nodes": reviews or []},
                }
            }
        }
    }


def trigger_record(head: str = HEAD):
    return {
        "schema_version": 1,
        "status": "posted",
        "repository": REPO,
        "pr_number": PR,
        "head_sha": head,
        "trigger": {
            "id": 10,
            "created_at": "2026-09-23T00:01:00Z",
            "url": "https://example.test/comments/10",
            "type": "full",
            "command": hosted.FULL_COMMAND,
        },
    }


class GithubAndEvidenceTests(unittest.TestCase):
    def test_summary_counts_require_anchored_canonical_lines(self):
        body = (
            "The review discusses Outside diff range comments and Duplicate comments in prose.\n"
            "### Outside diff range comments (2)\n"
            "**Duplicate comments (1)**"
        )
        self.assertEqual(evidence.summary_action_counts(body), (2, 1))

    def test_summary_marker_without_count_fails_closed(self):
        with self.assertRaisesRegex(evidence.EvidenceError, "summary section has no canonical count"):
            evidence.summary_action_counts("## Outside diff range comments")

    def test_summary_tag_and_emoji_markup_accept_canonical_counts(self):
        body = (
            "<summary>⚠️ Outside diff range comments (2)</summary>\n"
            "### :warning: **Duplicate comments (1)**"
        )
        self.assertEqual(evidence.summary_action_counts(body), (2, 1))

    def test_quoted_explicit_summary_counts_but_quoted_prose_does_not(self):
        self.assertEqual(
            evidence.summary_action_counts(
                "> **⚠️ Outside diff range comments (1)**\n"
                "> Outside diff range comments are discussed in prose.\n"
                "> Duplicate comments are discussed in prose."
            ),
            (1, 0),
        )

    def test_same_line_details_summary_and_trailing_blockquote_accept_counts(self):
        body = (
            "<details><summary>⚠️ Outside diff range comments (2)</summary><blockquote>\n"
            "<details><summary>Duplicate comments (1)</summary><blockquote>"
        )
        self.assertEqual(evidence.summary_action_counts(body), (2, 1))

    def test_details_summary_markup_inside_prose_is_not_a_summary_section(self):
        body = "Example syntax: <details><summary>Duplicate comments (9)</summary><blockquote>"
        self.assertFalse(evidence.has_summary_action_sections(body))
        self.assertEqual(evidence.summary_action_counts(body), (0, 0))

    def test_malformed_heading_or_bold_summary_fails_closed(self):
        for body in (
            "### Outside diff range comments: 2",
            "**Duplicate comments: 1**",
            "<summary>⚠️ Outside diff range comments: 2</summary>",
            "<details><summary>Duplicate comments: 1</summary><blockquote>",
            "### :warning: **Duplicate comments**",
        ):
            with self.subTest(body=body), self.assertRaisesRegex(
                evidence.EvidenceError, "summary section has no canonical count"
            ):
                evidence.summary_action_counts(body)

    def test_summary_phrase_in_body_prose_is_not_a_marker(self):
        body = (
            "This paragraph mentions Outside the diff and Duplicate comments without reporting summary counts.\n"
            "Outside diff range comments are discussed in prose.\n"
            "Duplicate comments are discussed in prose."
        )
        self.assertEqual(evidence.summary_action_counts(body), (0, 0))

    def test_list_prose_does_not_become_explicit_summary_markup(self):
        body = (
            "- **Duplicate comments** handling is unified.\n"
            "* Outside the diff, the runner records the explanation.\n"
            "- **Outside diff range comments (2)**\n"
            "- Duplicate comments (1)"
        )
        self.assertEqual(evidence.summary_action_counts(body), (2, 1))

    def test_graphql_variables_preserve_strings_and_type_only_non_boolean_integers(self):
        query = "query($owner:String!, $repo:String!, $number:Int!, $after:String!) { viewer { login } }"
        variables = {"owner": "123", "repo": "@project", "number": 42, "after": "007", "enabled": True}

        with patch.object(github.subprocess, "run", return_value=SimpleNamespace(stdout="{}")) as run:
            self.assertEqual(github.run_gh_query(query, variables), {})

        self.assertEqual(
            run.call_args.args[0],
            [
                "gh",
                "api",
                "graphql",
                "-f",
                f"query={query}",
                "-f",
                "owner=123",
                "-f",
                "repo=@project",
                "-F",
                "number=42",
                "-f",
                "after=007",
                "-f",
                "enabled=True",
            ],
        )

    def test_graphql_paginates_threads_comments_and_reviews(self):
        thread_1 = {
            "id": "thread-1",
            "isResolved": False,
            "isOutdated": False,
            "path": "README.md",
            "line": 1,
            "comments": {
                "nodes": ["thread-comment-1"],
                "pageInfo": {"hasNextPage": True, "endCursor": "tc1"},
            },
        }
        initial = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "headRefOid": HEAD,
                        "commits": {"nodes": []},
                        "reviewThreads": {
                            "nodes": [thread_1],
                            "pageInfo": {"hasNextPage": True, "endCursor": "t1"},
                        },
                        "comments": {"nodes": ["comment-1"], "pageInfo": {"hasNextPage": True, "endCursor": "c1"}},
                        "reviews": {"nodes": ["review-1"], "pageInfo": {"hasNextPage": True, "endCursor": "r1"}},
                    }
                }
            }
        }
        pages = {
            "reviewThreads": {
                "nodes": [
                    {
                        "id": "thread-2",
                        "isResolved": False,
                        "isOutdated": False,
                        "path": "README.md",
                        "line": 2,
                        "comments": {"nodes": ["thread-comment-2"], "pageInfo": {"hasNextPage": False}},
                    }
                ],
                "pageInfo": {"hasNextPage": False},
            },
            "comments": {"nodes": ["comment-2"], "pageInfo": {"hasNextPage": False}},
            "reviews": {"nodes": ["review-2"], "pageInfo": {"hasNextPage": False}},
        }

        nested_page = {
            "data": {
                "node": {
                    "comments": {
                        "nodes": ["thread-comment-1b"],
                        "pageInfo": {"hasNextPage": False},
                    }
                }
            }
        }
        seen: list[tuple[str, str | None, str | None]] = []

        def query(query_text, variables):
            after = variables.get("after")
            seen.append((query_text, variables.get("threadId"), after))
            if variables.get("threadId") == "thread-1":
                self.assertEqual(after, "tc1")
                return nested_page
            if after == "t1":
                connection = "reviewThreads"
            elif after == "c1":
                connection = "comments"
            elif after == "r1":
                connection = "reviews"
            else:
                self.fail(f"unexpected pagination cursor: {variables!r}")
            return {"data": {"repository": {"pullRequest": {connection: pages[connection]}}}}

        def run_query(query_text, variables):
            if "after" not in variables:
                return initial
            return query(query_text, variables)

        with patch.object(github, "run_gh_query", side_effect=run_query) as run:
            payload = github.fetch_pull_request(REPO, PR)
        pr = payload["data"]["repository"]["pullRequest"]
        self.assertEqual(
            pr["reviewThreads"]["nodes"][0]["comments"]["nodes"],
            ["thread-comment-1", "thread-comment-1b"],
        )
        self.assertEqual(
            [call.args[1].get("after") for call in run.call_args_list[1:]],
            ["tc1", "t1", "c1", "r1"],
        )
        self.assertEqual(
            [(thread_id, after) for _, thread_id, after in seen],
            [("thread-1", "tc1"), (None, "t1"), (None, "c1"), (None, "r1")],
        )
        self.assertIn("node(id:$threadId)", run.call_args_list[1].args[0])
        self.assertEqual(pr["reviewThreads"]["nodes"][1]["id"], "thread-2")
        self.assertEqual(pr["comments"]["nodes"], ["comment-1", "comment-2"])
        self.assertEqual(pr["reviews"]["nodes"], ["review-1", "review-2"])

    def test_historical_durationless_and_markered_checkpoint_forms(self):
        comments = [
            {
                "id": 1,
                "body": "Hosted: 2 found / 1 accepted · `abcdef1` · 3 files",
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "id": 2,
                "body": "CLI: 1 found / 1 accepted · `abcdef1` · 3 files · 9s\n<!-- firemud-cli-run: run.A1 -->\n<!-- firemud-review-duration-seconds: 9 -->",
                "created_at": "2026-09-23T00:01:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)
        self.assertEqual(unparsed, 0)
        self.assertIsNone(parsed[0].duration_seconds)
        self.assertEqual(parsed[1].duration_seconds, 9)

    def test_malformed_duration_is_explicit_and_not_inferred(self):
        comments = [
            {
                "body": "Hosted: 1 found / 0 accepted · `abcdef1` · 3 files · 12s\n<!-- firemud-review-duration-seconds: nope -->",
                "created_at": "2026-09-23T00:00:00Z",
            }
        ]
        report = evidence.collect_evidence(comments)
        self.assertIsNone(report["checkpoints"][0].get("duration_seconds"))
        self.assertTrue(report["warnings"])

    def test_duration_evidence_requires_matching_visible_and_hidden_values(self):
        comments = [
            {
                "id": 1,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files",
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "id": 2,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-hosted-review: 10 -->\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:01:00Z",
            },
            {
                "id": 3,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:02:00Z",
            },
            {
                "id": 4,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:03:00Z",
            },
            {
                "id": 5,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: nope -->",
                "created_at": "2026-09-23T00:04:00Z",
            },
            {
                "id": 6,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: 5 -->",
                "created_at": "2026-09-23T00:05:00Z",
            },
            {
                "id": 7,
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files · 4s\n"
                "<!-- firemud-review-duration-seconds: 4 -->\n"
                "<!-- firemud-review-duration-seconds: 4 -->",
                "created_at": "2026-09-23T00:06:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)

        self.assertEqual(unparsed, 0)
        self.assertIsNone(parsed[0].duration_seconds)
        self.assertFalse(parsed[0].duration_invalid)
        self.assertEqual(parsed[1].duration_seconds, 4)
        self.assertEqual(parsed[2].duration_seconds, 4)
        self.assertFalse(parsed[2].duration_invalid)
        self.assertTrue(all(item.duration_invalid for item in parsed[3:]))
        self.assertTrue(evidence.hosted_checkpoint_evidence(parsed[5], [], HEAD)["status"] == "missing")
        report = evidence.collect_evidence(comments)
        self.assertEqual(report["duration_audit"]["malformed_count"], 1)
        self.assertEqual(report["duration_audit"]["duplicate_count"], 1)
        self.assertEqual(report["duration_audit"]["missing_count"], 2)
        self.assertEqual(report["duration_audit"]["mismatch_count"], 1)

    def test_malformed_private_capture_fails_closed(self):
        checkpoint = evidence.Checkpoint(
            1, "2026-09-23T00:00:00Z", "CLI", 1, 1, HEAD[:7], 1, False, None, "run.A1", None
        )
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory) / "coderabbit-review-logs" / checkpoint.run_id
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                "run_id=run.A1\nrepository=owner/repo\npull_request=42\ncandidate_sha=" + HEAD + "\ncandidate_files=1\n"
            )
            (run / "stdout").write_text("not-json\n")
            (run / "exit-status").write_text("0\n")
            with self.assertRaises(evidence.CaptureInvalid):
                evidence.load_cli_capture(checkpoint, REPO, PR, Path(directory))

    def _cli_capture(
        self,
        common: Path,
        *,
        decision_text: str | None,
        rejection_text: str | None = None,
        accepted: int = 0,
    ):
        run_id = "run.Decision"
        run = common / "coderabbit-review-logs" / run_id
        run.mkdir(parents=True)
        (run / "metadata").write_text(
            f"run_id={run_id}\nrepository={REPO}\npull_request={PR}\ncandidate_sha={HEAD}\ncandidate_files=1\n",
            encoding="utf-8",
        )
        (run / "stdout").write_text(
            json.dumps({"type": "finding", "message": "one"})
            + "\n"
            + json.dumps({"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a"]})
            + "\n",
            encoding="utf-8",
        )
        (run / "exit-status").write_text("0\n", encoding="utf-8")
        if decision_text is not None:
            (run / "decisions.tsv").write_text(decision_text, encoding="utf-8")
        if rejection_text is not None:
            (run / "rejections.tsv").write_text(rejection_text, encoding="utf-8")
        checkpoint = evidence.Checkpoint(
            1, "2026-09-23T00:00:00Z", "CLI", 1, accepted, HEAD[:12], 1, False, None, run_id, None
        )
        return evidence.load_cli_capture(checkpoint, REPO, PR, common)

    def _hosted_capture_snapshot(self, common: Path) -> Path:
        review_id = 99
        capture_dir = common / "firemud" / f"hosted-review.{review_id}"
        capture_dir.mkdir(parents=True)
        snapshot_path = capture_dir / "snapshot.json"
        snapshot_path.write_text(
            json.dumps(
                {
                    "source": "hosted",
                    "repository": REPO,
                    "pull_request": PR,
                    "review": {
                        "id": review_id,
                        "user": {"login": "coderabbitai[bot]"},
                        "state": "COMMENTED",
                        "submitted_at": "2026-09-23T00:00:00Z",
                        "commit_id": HEAD,
                    },
                    "comments": [],
                }
            ),
            encoding="utf-8",
        )
        return snapshot_path

    def test_hosted_capture_snapshot_invalid_utf8_is_capture_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot_path = self._hosted_capture_snapshot(Path(directory))
            snapshot_path.write_bytes(b"\xff\xfe")

            with self.assertRaisesRegex(evidence.CaptureInvalid, "not valid UTF-8"):
                evidence.load_hosted_capture(REPO, PR, 99, Path(directory))

    def test_hosted_capture_decisions_invalid_utf8_is_capture_invalid(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot_path = self._hosted_capture_snapshot(Path(directory))
            (snapshot_path.parent / "decisions.tsv").write_bytes(b"\xff\xfe")

            with self.assertRaisesRegex(evidence.CaptureInvalid, "not valid UTF-8"):
                evidence.load_hosted_capture(REPO, PR, 99, Path(directory))

    def test_raw_positive_cli_capture_requires_complete_decisions_and_matching_accepted_count(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            with self.assertRaisesRegex(evidence.CaptureInvalid, "complete linked"):
                self._cli_capture(common, decision_text=None)

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            with self.assertRaisesRegex(evidence.CaptureInvalid, "accepted count"):
                self._cli_capture(common, decision_text="1\taccepted\tuseful fix\n", accepted=0)

        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            capture = self._cli_capture(common, decision_text="1\trejected\tduplicate finding\n")
            self.assertEqual(capture.decisions, {1: ("rejected", "duplicate finding")})

    def test_uncheckpointed_raw_positive_capture_is_discovered_without_decision_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run_id = "run.Pending"
            run = common / "coderabbit-review-logs" / run_id
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                f"run_id={run_id}\nrepository={REPO}\npull_request={PR}\n"
                f"candidate_sha={HEAD}\ncandidate_files=1\n",
                encoding="utf-8",
            )
            (run / "stdout").write_text(
                json.dumps({"type": "finding", "message": "one"})
                + "\n"
                + json.dumps(
                    {"type": "complete", "status": "review_completed", "findings": 1, "reviewedFiles": ["a"]}
                )
                + "\n",
                encoding="utf-8",
            )
            (run / "exit-status").write_text("0\n", encoding="utf-8")

            captures = evidence.discover_cli_captures(REPO, PR, common)

            self.assertEqual(len(captures), 1)
            self.assertEqual(captures[0].metadata["run_id"], run_id)
            self.assertEqual(len(captures[0].findings), 1)
            self.assertEqual(captures[0].decisions, {})
            self.assertFalse(captures[0].decision_file_present)

            checkpoint = evidence.Checkpoint(
                1, "2026-09-23T00:00:00Z", "CLI", 1, 0, HEAD[:12], 1, False, None, run_id, None
            )
            with self.assertRaisesRegex(evidence.CaptureInvalid, "complete linked"):
                evidence.load_cli_capture(checkpoint, REPO, PR, common)

    def test_cli_duration_evidence_fails_closed_and_valid_duration_checks_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            run = common / "coderabbit-review-logs" / "run.Decision"
            run.mkdir(parents=True)
            (run / "metadata").write_text(
                f"run_id=run.Decision\nrepository={REPO}\npull_request={PR}\n"
                f"candidate_sha={HEAD}\ncandidate_files=1\nreview_duration_seconds=9\n",
                encoding="utf-8",
            )
            (run / "stdout").write_text(
                json.dumps({"type": "complete", "status": "review_completed", "findings": 0, "reviewedFiles": ["a"]})
                + "\n",
                encoding="utf-8",
            )
            (run / "exit-status").write_text("0\n", encoding="utf-8")
            checkpoint = evidence.Checkpoint(
                1,
                "2026-09-23T00:00:00Z",
                "CLI",
                0,
                0,
                HEAD[:12],
                1,
                False,
                None,
                "run.Decision",
                None,
                9,
            )
            with self.assertRaisesRegex(evidence.CaptureUnavailable, "duration"):
                evidence.load_cli_capture(checkpoint, REPO, PR, common)
            (run / "review-duration-seconds").write_text("8\n", encoding="utf-8")
            with self.assertRaisesRegex(evidence.CaptureInvalid, "duration"):
                evidence.load_cli_capture(checkpoint, REPO, PR, common)
            (run / "review-duration-seconds").write_text("9\n", encoding="utf-8")
            capture = evidence.load_cli_capture(checkpoint, REPO, PR, common)
            self.assertEqual(capture.metadata["review_duration_seconds"], "9")

    def test_cli_capture_text_read_failures_are_classified(self):
        artifact_names = (
            "metadata",
            "stdout",
            "exit-status",
            "review-duration-seconds",
            "decisions.tsv",
            "rejections.tsv",
        )
        original_read_text = Path.read_text
        for artifact_name in artifact_names:
            for failure, expected_error in (
                ("unreadable", evidence.CaptureUnavailable),
                ("invalid_utf8", evidence.CaptureInvalid),
            ):
                with self.subTest(artifact=artifact_name, failure=failure), tempfile.TemporaryDirectory() as directory:
                    common = Path(directory)
                    run_id = "run.ReadFailure"
                    run = common / "coderabbit-review-logs" / run_id
                    run.mkdir(parents=True)
                    (run / "metadata").write_text(
                        f"run_id={run_id}\nrepository={REPO}\npull_request={PR}\n"
                        f"candidate_sha={HEAD}\ncandidate_files=1\nreview_duration_seconds=3\n",
                        encoding="utf-8",
                    )
                    (run / "stdout").write_text(
                        json.dumps(
                            {
                                "type": "complete",
                                "status": "review_completed",
                                "findings": 0,
                                "reviewedFiles": ["a"],
                            }
                        )
                        + "\n",
                        encoding="utf-8",
                    )
                    (run / "exit-status").write_text("0\n", encoding="utf-8")
                    (run / "review-duration-seconds").write_text("3\n", encoding="utf-8")
                    (run / "decisions.tsv").write_text("", encoding="utf-8")
                    (run / "rejections.tsv").write_text("", encoding="utf-8")
                    if artifact_name == "rejections.tsv":
                        (run / "decisions.tsv").unlink()
                    target = run / artifact_name
                    if failure == "invalid_utf8":
                        target.write_bytes(b"\xff\xfe")

                    checkpoint = evidence.Checkpoint(
                        1,
                        "2026-09-23T00:00:00Z",
                        "CLI",
                        0,
                        0,
                        HEAD[:12],
                        1,
                        False,
                        None,
                        run_id,
                        None,
                        3,
                    )
                    if failure == "unreadable":
                        def fail_target_read(path, *args, expected_target=target, **kwargs):
                            if path == expected_target:
                                raise OSError("permission denied")
                            return original_read_text(path, *args, **kwargs)

                        read_context = patch.object(Path, "read_text", autospec=True, side_effect=fail_target_read)
                    else:
                        read_context = nullcontext()

                    expected_message = "cannot be read" if failure == "unreadable" else "not valid UTF-8"
                    with read_context, self.assertRaisesRegex(expected_error, expected_message):
                        evidence.load_cli_capture(checkpoint, REPO, PR, common)

    def test_historical_rejections_capture_is_attributable_only_for_zero_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            capture = self._cli_capture(
                Path(directory), decision_text=None, rejection_text="1\tlegacy-ref\tduplicate finding\n"
            )
            self.assertEqual(capture.decisions, {1: ("rejected", "duplicate finding")})

        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(
            evidence.CaptureInvalid, "complete linked|cannot explain"
        ):
            self._cli_capture(Path(directory), decision_text=None, rejection_text="2\tlegacy-ref\tduplicate finding\n")

        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(evidence.CaptureInvalid, "cannot explain"):
            self._cli_capture(
                Path(directory),
                decision_text=None,
                rejection_text="1\tlegacy-ref\tduplicate finding\n",
                accepted=1,
            )

    def test_duplicate_hidden_linkage_markers_are_ambiguous(self):
        comments = [
            {
                "body": (
                    "CLI: 1 found / 0 accepted · `abcdef1` · 1 files\n"
                    "<!-- firemud-cli-run: run.A1 -->\n"
                    "<!-- firemud-cli-run: run.A2 -->"
                ),
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "body": (
                    "Hosted: 1 found / 0 accepted · `abcdef1` · 1 files\n"
                    "<!-- firemud-hosted-review: 10 -->\n"
                    "<!-- firemud-hosted-review: 11 -->"
                ),
                "created_at": "2026-09-23T00:01:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)
        self.assertEqual(parsed, [])
        self.assertEqual(unparsed, 2)

    def test_single_hidden_linkage_marker_and_missing_duration_remain_valid(self):
        comments = [
            {
                "body": "CLI: 1 found / 0 accepted · `abcdef1` · 1 files\n<!-- firemud-cli-run: run.A1 -->",
                "created_at": "2026-09-23T00:00:00Z",
            },
            {
                "body": "Hosted: 0 found / 0 accepted · `abcdef1` · 1 files\n<!-- firemud-hosted-review: 10 -->",
                "created_at": "2026-09-23T00:01:00Z",
            },
        ]
        parsed, unparsed = evidence.parse_checkpoint_comments(comments)
        self.assertEqual(unparsed, 0)
        self.assertEqual(parsed[0].run_id, "run.A1")
        self.assertEqual(parsed[1].hosted_review_id, 10)
        self.assertIsNone(parsed[0].duration_seconds)


class HostedEvidenceTests(unittest.TestCase):
    def test_wrong_target_assertion_happens_before_request_preparation(self):
        with self.assertRaises(ValueError):
            hosted.prepare_full_trigger(PR, PR + 1)
        self.assertEqual(hosted.prepare_full_trigger(PR)["command"], hosted.FULL_COMMAND)

    def test_not_reviewed_label_is_excluded_from_reviewed_count(self):
        summary = (
            "Files selected: 89. Files not reviewed due to moderation or processing errors: 28. "
            "Files reviewed: 61."
        )

        self.assertEqual(hosted._reviewed_label_counts(summary), {61})
        self.assertTrue(hosted._summary_has_explicit_incomplete_coverage(summary))
        self.assertTrue(hosted._summary_has_explicit_incompleteness(summary))
        self.assertFalse(hosted._summary_proves_complete_file_coverage(summary))

    def test_not_reviewed_only_label_does_not_supply_a_reviewed_count(self):
        summary = "Files selected: 89. Files not reviewed: 28."

        self.assertEqual(hosted._reviewed_label_counts(summary), set())
        self.assertTrue(hosted._summary_has_explicit_incomplete_coverage(summary))
        self.assertTrue(hosted._summary_has_explicit_incompleteness(summary))
        self.assertFalse(hosted._summary_proves_complete_file_coverage(summary))

    def test_reviewed_count_still_proves_complete_coverage_and_conflicts_fail_closed(self):
        complete = "Files selected: 89. Files not reviewed: 0. Files reviewed: 89."
        inconsistent = (
            "Files selected: 89. Files not reviewed: 28. "
            "Files reviewed: 61. Files reviewed: 60."
        )

        self.assertEqual(hosted._reviewed_label_counts(complete), {89})
        self.assertFalse(hosted._summary_has_explicit_incompleteness(complete))
        self.assertTrue(hosted._summary_proves_complete_file_coverage(complete))
        self.assertEqual(hosted._reviewed_label_counts(inconsistent), {60, 61})
        self.assertTrue(hosted._summary_has_explicit_incompleteness(inconsistent))

    def test_rate_limit_cannot_count_as_completed_review(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        reply = comment(
            11, "coderabbitai", "Review rate limited; next reviews available in 30 minutes", "2026-09-23T00:02:00Z"
        )
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, reply]), trigger_record())
        self.assertEqual(state.state, "rate_limited")
        self.assertNotEqual(state.state, "completed")

    def test_missing_hosted_evidence_cannot_count_as_completion(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        finished = comment(11, "coderabbitai", "Full review finished.", "2026-09-23T00:02:00Z")
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, finished]), trigger_record())
        self.assertEqual(state.state, "ambiguous")
        self.assertTrue(state.terminal)
        self.assertFalse(state.attributed)
        self.assertEqual(state.response_id, 11)
        self.assertEqual(state.response_url, "https://example.test/comments/11")
        self.assertIn("without a head-attributed result", state.reason)
        self.assertNotEqual(state.state, "completed")
        checkpoint = evidence.Checkpoint(1, "2026-09-23T00:03:00Z", "Hosted", 0, 0, HEAD[:7], 1, False, None, None, 99)
        self.assertEqual(evidence.hosted_checkpoint_evidence(checkpoint, [], HEAD)["status"], "missing")

    def test_finished_reply_without_zero_sentence_requires_clean_exact_head_summary_and_empty_history(self):
        trigger_at = "2026-09-23T00:01:00Z"
        summary_at = "2026-09-23T00:03:20Z"
        reply_at = "2026-09-23T00:03:00Z"
        trigger = comment(10, "owner", hosted.FULL_COMMAND, trigger_at)
        summary = comment(
            12,
            "coderabbitai[bot]",
            (
                "0 actionable comments found.\n"
                "Files selected: 89. Files reviewed: 89.\n"
                "Files not reviewed due to moderation or processing errors: 0.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
            "2026-09-23T00:00:30Z",
        )
        summary["updatedAt"] = summary_at
        reply = comment(11, "coderabbitai", "Full review finished.", reply_at)
        reply["updatedAt"] = "2026-09-23T00:03:30Z"

        def state_for(selected_summary=summary, selected_reply=reply, *, extra_comments=None, threads=None):
            return hosted.trigger_state(
                REPO,
                PR,
                review_payload(
                    [trigger, selected_summary, selected_reply, *(extra_comments or [])],
                    reviews=[],
                    threads=threads,
                ),
                trigger_record(),
            )

        self.assertEqual(state_for().state, "completed")

        strict_but_incomplete = {
            **summary,
            "body": (
                "No actionable comments were generated in the recent review.\n"
                "Files selected: 89. Files reviewed: 61.\n"
                "Files not reviewed due to moderation or processing errors: 28.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
        }
        self.assertNotEqual(state_for(strict_but_incomplete).state, "completed")

        incomplete = {
            **summary,
            "body": (
                "0 actionable comments found.\n"
                "Files selected: 89. Files reviewed: 61.\n"
                "Files not reviewed due to moderation or processing errors: 28.\n"
                "3 reported issues remain open.\n"
                f"Reviewing files that changed from the base of the PR and between {BASE} and {HEAD}."
            ),
        }
        stale = {**summary, "updatedAt": trigger_at}
        mismatched_head = {
            **summary,
            "body": summary["body"].replace(HEAD, "d" * 40),
        }
        inline_finding = {
            "databaseId": 21,
            "author": {"login": "coderabbitai[bot]"},
            "body": "A post-trigger inline finding.",
            "createdAt": "2026-09-23T00:02:30Z",
            "updatedAt": "2026-09-23T00:02:30Z",
        }
        extra_issue_comment = comment(22, "coderabbitai", "A post-trigger bot comment.", "2026-09-23T00:02:30Z")
        incomplete_state = state_for(incomplete)
        self.assertEqual(incomplete_state.state, "failed")
        self.assertTrue(incomplete_state.terminal)
        self.assertTrue(incomplete_state.attributed)
        self.assertEqual(incomplete_state.head_sha, HEAD)
        self.assertEqual(incomplete_state.response_id, 11)
        self.assertIn("incomplete file coverage", incomplete_state.reason)

        for invalid_summary in (stale, mismatched_head):
            with self.subTest(summary=invalid_summary["body"]):
                self.assertEqual(state_for(invalid_summary).state, "ambiguous")
        self.assertEqual(
            state_for(threads=[{"comments": {"nodes": [inline_finding]}}]).state,
            "ambiguous",
        )
        self.assertEqual(state_for(extra_comments=[extra_issue_comment]).state, "ambiguous")

        intervening_trigger = comment(13, "owner", hosted.FULL_COMMAND, "2026-09-23T00:02:45Z")
        self.assertEqual(state_for(extra_comments=[intervening_trigger]).state, "ambiguous")

        bot_review = {
            "databaseId": 23,
            "author": {"login": "coderabbitai[bot]"},
            "body": "",
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:02:30Z",
            "commit": {"oid": HEAD},
        }
        self.assertEqual(
            hosted.trigger_state(
                REPO,
                PR,
                review_payload([trigger, incomplete, reply], [bot_review]),
                trigger_record(),
            ).state,
            "ambiguous",
        )

        missing_threads = review_payload([trigger, incomplete, reply])
        del missing_threads["data"]["repository"]["pullRequest"]["reviewThreads"]
        self.assertEqual(hosted.trigger_state(REPO, PR, missing_threads, trigger_record()).state, "ambiguous")

        summary_after_reply = {**incomplete, "updatedAt": "2026-09-23T00:03:40Z"}
        post_reply_incomplete_state = state_for(summary_after_reply)
        self.assertEqual(post_reply_incomplete_state.state, "failed")
        self.assertTrue(post_reply_incomplete_state.terminal)
        self.assertTrue(post_reply_incomplete_state.attributed)

        wrapped_reply = {
            **reply,
            "body": (
                "<!-- This is an auto-generated reply by CodeRabbit -->\n"
                "<!-- CodeRabbit review command invocation: "
                "v2:6a355c47ffcf4ddd532604823fb155b76256a51f568b7e5a4e92ef6f3565c6f9 -->\n"
                "<details>\n"
                "<summary>✅ Action performed</summary>\n"
                "Full review finished.\n"
                "</details>"
            ),
        }
        self.assertEqual(
            state_for(summary_after_reply, selected_reply=wrapped_reply).state,
            "failed",
        )
        self.assertEqual(state_for(summary, selected_reply=wrapped_reply).state, "ambiguous")

        altered_action = {
            **wrapped_reply,
            "body": wrapped_reply["body"].replace("✅ Action performed", "✅ Review complete"),
        }
        extra_text = {
            **wrapped_reply,
            "body": wrapped_reply["body"].replace(
                "Full review finished.", "Full review finished.\nAn issue requires attention."
            ),
        }
        self.assertEqual(state_for(summary_after_reply, selected_reply=altered_action).state, "ambiguous")
        self.assertEqual(state_for(summary_after_reply, selected_reply=extra_text).state, "ambiguous")

        later_bot_output = comment(24, "coderabbitai[bot]", "Another review update.", "2026-09-23T00:03:45Z")
        self.assertEqual(
            state_for(summary_after_reply, extra_comments=[intervening_trigger]).state,
            "ambiguous",
        )
        self.assertEqual(
            state_for(summary_after_reply, extra_comments=[later_bot_output]).state,
            "ambiguous",
        )

        limited = {**reply, "body": "Review rate limited; next reviews available in 30 minutes"}
        active = {**reply, "body": "Full review triggered"}
        unattributed = {key: value for key, value in reply.items() if key != "databaseId"}
        self.assertEqual(state_for(selected_reply=limited).state, "rate_limited")
        self.assertEqual(state_for(selected_reply=active).state, "active")
        self.assertNotEqual(state_for(selected_reply=unattributed).state, "completed")

    def test_matching_completed_review_is_attributable_and_has_duration(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        summary = comment(
            11,
            "coderabbitai",
            f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
            "2026-09-23T00:02:00Z",
        )
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, summary]), trigger_record())
        self.assertEqual(state.state, "completed")
        self.assertEqual(state.duration_seconds, 60)

    def test_substantive_exact_head_review_wins_over_incidental_failure_wording(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        summary = comment(
            11,
            "coderabbitai",
            f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.\n"
            "The review failed to identify an issue in the previous implementation; this review found it.",
            "2026-09-23T00:02:00Z",
        )
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, summary]), trigger_record())
        self.assertEqual(state.state, "completed")

    def test_explicit_review_failure_without_substantive_evidence_remains_failed(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        failure = comment(11, "coderabbitai", "The review failed. Something went wrong.", "2026-09-23T00:02:00Z")
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, failure]), trigger_record())
        self.assertEqual(state.state, "failed")

    def test_recorded_trigger_author_login_comparison_is_case_insensitive(self):
        trigger = comment(10, "Owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        record = trigger_record()
        record["trigger"]["author_login"] = "owner"
        state = hosted.trigger_state(REPO, PR, review_payload([trigger]), record)
        self.assertEqual(state.state, "awaiting_response")
        self.assertTrue(state.attributed)

    def test_suffixed_coderabbit_bot_identity_is_attributable(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        summary = comment(
            11,
            "coderabbitai[bot]",
            f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
            "2026-09-23T00:02:00Z",
        )
        state = hosted.trigger_state(REPO, PR, review_payload([trigger, summary]), trigger_record())
        self.assertEqual(state.state, "completed")

    def test_new_writes_use_firemud_and_legacy_paths_remain_discoverable(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            new = hosted.default_trigger_record_path(REPO, PR, common)
            old = common / "coderabbit-review-logs" / "hosted" / "owner_repo" / "pr-42" / "trigger.json"
            old.parent.mkdir(parents=True)
            old.write_text("{}")
            self.assertTrue(str(new).startswith(str(common / "firemud")))
            self.assertIn(old, hosted.trigger_record_paths(REPO, PR, common))

    def test_legacy_posting_reservation_without_trigger_identity_is_readable_as_ambiguous(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "legacy.json"
            path.write_text(
                json.dumps(
                    {
                        "status": "posting",
                        "repository": REPO,
                        "pr_number": PR,
                        "head_sha": HEAD,
                    }
                ),
                encoding="utf-8",
            )
            record = hosted.load_trigger_reservation(path, REPO, PR)
            state = hosted.trigger_state(REPO, PR, review_payload(), record, path)
            self.assertEqual(state.state, "ambiguous")
            self.assertFalse(state.attributed)

    def test_posting_recovery_fails_closed_when_multiple_live_commands_match(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "status": "posting",
                        "repository": REPO,
                        "pr_number": PR,
                        "head_sha": HEAD,
                        "posting_started_at": "2026-09-23T00:00:00Z",
                        "posting_actor_login": "maintainer",
                        "posting_comment_id_floor": 30,
                    }
                ),
                encoding="utf-8",
            )
            comments = [
                comment(31, "maintainer", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z"),
                comment(32, "maintainer", hosted.FULL_COMMAND, "2026-09-23T00:02:00Z"),
            ]
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                self.assertRaisesRegex(ValueError, "exactly one live command"),
            ):
                hosted.adopt_posting_reservation(path, REPO, PR, HEAD, review_payload(comments))
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")

    def test_posting_recovery_uses_new_comment_id_when_github_clock_precedes_reservation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "status": "posting",
                        "repository": REPO,
                        "pr_number": PR,
                        "head_sha": HEAD,
                        "posting_started_at": "2026-09-23T00:00:00Z",
                        "posting_actor_login": "maintainer",
                        "posting_comment_id_floor": 30,
                    }
                ),
                encoding="utf-8",
            )
            observed = comment(31, "maintainer", hosted.FULL_COMMAND, "2026-09-22T23:00:00Z")
            with patch.object(hosted, "default_trigger_record_path", return_value=path):
                recovered = hosted.adopt_posting_reservation(path, REPO, PR, HEAD, review_payload([observed]))
            self.assertEqual(recovered["status"], "posted")
            self.assertEqual(recovered["trigger"]["id"], 31)

    def test_retirement_refuses_an_active_review(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        active = comment(11, "coderabbitai", "Full review triggered", "2026-09-23T00:02:00Z")
        payload = review_payload([trigger, active])
        record = trigger_record()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            lock_path = Path(directory) / "lock-trigger.json"
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                self.assertRaisesRegex(ValueError, "active"),
            ):
                hosted.retire_trigger_record(path, REPO, PR, 10, HEAD, "stale", lambda: payload)

    def test_retirement_fetches_live_payload_under_lock_and_rejects_advanced_head(self):
        current_head = "c" * 40
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        record = trigger_record()
        advanced_payload = review_payload([trigger], head=current_head)
        lock_acquired = False

        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            lock_path = Path(directory) / "lock-trigger.json"
            original_with_lock = hosted._with_lock

            def acquire_tracked_lock(lock_file):
                nonlocal lock_acquired
                descriptor = original_with_lock(lock_file)
                lock_acquired = True
                return descriptor

            def fetch_after_lock():
                self.assertTrue(lock_acquired)
                return advanced_payload

            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "_with_lock", side_effect=acquire_tracked_lock),
                self.assertRaisesRegex(ValueError, "current head does not match"),
            ):
                hosted.retire_trigger_record(path, REPO, PR, 10, HEAD, "stale", fetch_after_lock)
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted")

    def test_retirement_refuses_unresolved_timed_out_trigger_on_same_head(self):
        record = trigger_record()
        record["status"] = "timed_out"
        record["timeout"] = {
            "at": "2026-09-23T00:02:00Z",
            "observed_state": "awaiting_response",
            "reason": hosted.TIMEOUT_REASON,
        }
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"),
                self.assertRaisesRegex(ValueError, "same head"),
            ):
                hosted.retire_trigger_record(
                    path, REPO, PR, 10, HEAD, "stale", lambda: review_payload([trigger])
                )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "timed_out")

    def test_retirement_accepts_only_durable_later_exact_head_completion(self):
        earlier = trigger_record("c" * 40)
        later = {
            **trigger_record(HEAD),
            "anchor": {
                "child_head": HEAD,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": "d" * 64,
            },
            "trigger": {
                "id": 20,
                "created_at": "2026-09-23T00:03:00Z",
                "url": "https://example.test/comments/20",
                "type": "full",
                "command": hosted.FULL_COMMAND,
            },
        }
        earlier["status"] = "posted"
        later_trigger = comment(20, "owner", hosted.FULL_COMMAND, "2026-09-23T00:03:00Z")
        completed = {
            **comment(
                21,
                "coderabbitai[bot]",
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
                "2026-09-23T00:04:00Z",
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:04:00Z",
            "commit": {"oid": HEAD},
        }
        payload = review_payload([later_trigger], [completed])
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            lock_path = hosted.default_trigger_record_path(REPO, PR, common)
            old_path = common / "old.json"
            old_path.write_text(json.dumps(earlier), encoding="utf-8")
            later_path = lock_path
            later_path.parent.mkdir(parents=True)
            later_path.write_text(json.dumps(later), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "trigger_record_paths", return_value=[later_path]),
            ):
                result = hosted.retire_trigger_record(
                    old_path, REPO, PR, 10, HEAD, "superseded", lambda: payload
                )
            self.assertEqual(result["status"], "retired")

            unverified_path = common / "unverified.json"
            unverified_path.write_text(json.dumps(earlier), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "trigger_record_paths", return_value=[]),
                self.assertRaisesRegex(ValueError, "later completed exact-head"),
            ):
                hosted.retire_trigger_record(
                    unverified_path, REPO, PR, 10, HEAD, "superseded", lambda: payload
                )

    def test_stuck_trigger_recovery_requires_explicit_wait_and_a_new_exact_head(self):
        current_head = "c" * 40
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        active = comment(11, "coderabbitai", "Full review triggered", "2026-09-23T00:02:00Z")
        record = trigger_record()
        live_payload = review_payload([trigger, active], head=current_head)
        fetch = unittest.mock.Mock(return_value=live_payload)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            lock_path = Path(directory) / "lock-trigger.json"
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
            ):
                result = hosted.retire_stuck_trigger_after_head_advance(
                    path, REPO, PR, 10, current_head, "bounded wait expired on old head", True, fetch
                )
            self.assertEqual(result["status"], "retired")
            self.assertEqual(result["captured_head_sha"], HEAD)
            self.assertEqual(result["current_head_sha"], current_head)
            self.assertEqual(result["observed_live_state"], "active")
            self.assertFalse(result["late_responses_counted"])
            fetch.assert_called_once_with()
            retired = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(retired["status"], "retired")
            self.assertEqual(retired["retirement"]["observed_live_state"], "active")
            self.assertTrue(retired["retirement"]["confirmed_wait_expired"])

            # A late completion remains retired even if it names the old exact SHA.
            late = comment(
                12,
                "coderabbitai",
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
                "2026-09-23T00:03:00Z",
            )
            late_state = hosted.trigger_state(
                REPO,
                PR,
                review_payload([trigger, active, late], head=current_head),
                retired,
                path,
            )
            self.assertEqual(late_state.state, "retired")
            self.assertIsNone(late_state.response_id)

    def test_stuck_trigger_recovery_accepts_awaiting_response_after_head_advance(self):
        current_head = "c" * 40
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        record = trigger_record()
        live_payload = review_payload([trigger], head=current_head)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
            ):
                result = hosted.retire_stuck_trigger_after_head_advance(
                    path,
                    REPO,
                    PR,
                    10,
                    current_head,
                    "bounded wait expired with no response",
                    True,
                    lambda: live_payload,
                )
        self.assertEqual(result["observed_live_state"], "awaiting_response")

    def test_posted_boundary_changed_trigger_can_only_be_retired_after_head_advance(self):
        current_head = "c" * 40
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        record = trigger_record()
        record["status"] = "posted_boundary_changed"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
            ):
                result = hosted.retire_stuck_trigger_after_head_advance(
                    path,
                    REPO,
                    PR,
                    10,
                    current_head,
                    "bounded wait expired after head advance",
                    True,
                    lambda: review_payload([trigger], head=current_head),
                )
            retired = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(result["observed_live_state"], "awaiting_response")
        self.assertEqual(retired["status"], "retired")
        self.assertEqual(retired["retirement"]["action"], "operator_retire_stuck_after_head_advance")
        self.assertFalse(retired["retirement"]["late_responses_counted"])

    def test_posted_boundary_changed_stuck_recovery_accepts_later_trigger_ambiguity(self):
        current_head = "c" * 40
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        later_trigger = comment(12, "owner", hosted.FULL_COMMAND, "2026-09-23T00:03:00Z")
        record = trigger_record()
        record["status"] = "posted_boundary_changed"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
            ):
                result = hosted.retire_stuck_trigger_after_head_advance(
                    path,
                    REPO,
                    PR,
                    10,
                    current_head,
                    "bounded wait expired after head advance",
                    True,
                    lambda: review_payload([trigger, later_trigger], head=current_head),
                )
            self.assertEqual(result["observed_live_state"], "ambiguous")
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "retired")

    def test_stuck_trigger_recovery_rejects_other_ambiguity(self):
        current_head = "c" * 40
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        mismatched_review = {
            **comment(
                11,
                "coderabbitai",
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
                "2026-09-23T00:02:00Z",
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:02:00Z",
            "commit": {"oid": "d" * 40},
        }
        record = trigger_record()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "live state ambiguous"),
            ):
                hosted.retire_stuck_trigger_after_head_advance(
                    path,
                    REPO,
                    PR,
                    10,
                    current_head,
                    "bounded wait expired after head advance",
                    True,
                    lambda: review_payload([trigger], [mismatched_review], head=current_head),
                )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted")

    def test_posted_boundary_changed_stuck_recovery_rejects_same_head(self):
        record = trigger_record()
        record["status"] = "posted_boundary_changed"
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "same-head"),
            ):
                hosted.retire_stuck_trigger_after_head_advance(
                    path, REPO, PR, 10, HEAD, "bounded wait expired", True, lambda: review_payload([trigger])
                )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted_boundary_changed")

    def test_late_old_head_review_cannot_complete_a_new_head_trigger(self):
        current_head = "c" * 40
        old_trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        current_trigger = comment(20, "owner", hosted.FULL_COMMAND, "2026-09-23T00:04:00Z")
        late_old_review = {
            **comment(
                21,
                "coderabbitai[bot]",
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
                "2026-09-23T00:05:00Z",
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:05:00Z",
            "commit": {"oid": HEAD},
        }
        current_record = trigger_record(current_head)
        current_record["trigger"] = {
            "id": 20,
            "created_at": "2026-09-23T00:04:00Z",
            "url": current_trigger["url"],
            "author_login": "owner",
            "type": "full",
            "command": hosted.FULL_COMMAND,
        }
        state = hosted.trigger_state(
            REPO,
            PR,
            review_payload([old_trigger, current_trigger], [late_old_review], head=current_head),
            current_record,
        )
        self.assertEqual(state.state, "ambiguous")
        self.assertNotEqual(state.state, "completed")

    def test_explicit_wrong_review_commit_cannot_be_rescued_by_body_head(self):
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        review = {
            **comment(
                11,
                "coderabbitai[bot]",
                f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
                "2026-09-23T00:02:00Z",
            ),
            "state": "COMMENTED",
            "submittedAt": "2026-09-23T00:02:00Z",
            "commit": {"oid": "c" * 40},
        }
        state = hosted.trigger_state(REPO, PR, review_payload([trigger], [review]), trigger_record())
        self.assertEqual(state.state, "ambiguous")

    def test_stuck_trigger_recovery_fails_closed_without_wait_assertion_or_on_same_head(self):
        record = trigger_record()
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            lock_path = Path(directory) / "lock.json"
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "confirmed-wait-expired"),
            ):
                hosted.retire_stuck_trigger_after_head_advance(
                    path, REPO, PR, 10, "c" * 40, "expired", False, lambda: review_payload([trigger], head="c" * 40)
                )
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=lock_path),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "same-head"),
            ):
                hosted.retire_stuck_trigger_after_head_advance(
                    path, REPO, PR, 10, HEAD, "expired", True, lambda: review_payload([trigger])
                )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted")

    def test_stuck_trigger_recovery_rejects_live_completed_state(self):
        current_head = "c" * 40
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        completed = comment(
            11,
            "coderabbitai",
            f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between {BASE} and {HEAD}.",
            "2026-09-23T00:02:00Z",
        )
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            record = trigger_record()
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "live state completed"),
            ):
                hosted.retire_stuck_trigger_after_head_advance(
                    path,
                    REPO,
                    PR,
                    10,
                    current_head,
                    "operator confirms bounded wait expired",
                    True,
                    lambda: review_payload([trigger, completed], head=current_head),
                )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted")

    def test_stuck_trigger_command_requires_explicit_wait_assertion(self):
        args = cli_module._parser().parse_args(
            [
                "decide",
                "trigger-retire-stuck",
                "--pr",
                str(PR),
                "--trigger-id",
                "10",
                "--head",
                "c" * 40,
                "--reason",
                "bounded wait expired",
            ]
        )
        self.assertFalse(args.confirmed_wait_expired)
        with (
            patch.object(cli_module, "_controller", return_value=(SimpleNamespace(repository=REPO), None)),
            self.assertRaisesRegex(cli_module.CliError, "confirmed-wait-expired"),
        ):
            cli_module._dispatch(args)

    def test_stuck_trigger_recovery_rejects_live_head_mismatch(self):
        current_head = "c" * 40
        actual_head = "d" * 40
        record = trigger_record()
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(
                    hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"
                ),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "current pull-request head"),
            ):
                hosted.retire_stuck_trigger_after_head_advance(
                    path,
                    REPO,
                    PR,
                    10,
                    current_head,
                    "bounded wait expired",
                    True,
                    lambda: review_payload([trigger], head=actual_head),
                )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posted")

    def test_stuck_trigger_recovery_rejects_malformed_persisted_timeout(self):
        current_head = "c" * 40
        record = trigger_record()
        record["status"] = "timed_out"
        record["timeout"] = {"at": "not-a-time", "observed_state": "active"}
        trigger = comment(10, "owner", hosted.FULL_COMMAND, "2026-09-23T00:01:00Z")
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(
                    hosted, "default_trigger_record_path", return_value=Path(directory) / "lock.json"
                ),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "valid unresolved-state timeout"),
            ):
                hosted.retire_stuck_trigger_after_head_advance(
                    path,
                    REPO,
                    PR,
                    10,
                    current_head,
                    "bounded wait expired",
                    True,
                    lambda: review_payload([trigger], head=current_head),
                )

    def test_prepost_recovery_tolerates_bounded_clock_skew_without_missing_a_post(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trigger.json"
            path.write_text(
                json.dumps(
                    {
                        "schema_version": 2,
                        "status": "posting",
                        "repository": REPO,
                        "pr_number": PR,
                        "head_sha": HEAD,
                        "posting_started_at": "2026-09-23T00:00:00Z",
                        "posting_actor_login": "maintainer",
                        "posting_comment_id_floor": 30,
                    }
                ),
                encoding="utf-8",
            )
            # GitHub's timestamp is slightly before the local reservation
            # stamp, but the ID is newer than the durable pre-POST floor.
            observed = comment(31, "maintainer", hosted.FULL_COMMAND, "2026-09-22T23:00:00Z")
            with (
                patch.object(hosted, "default_trigger_record_path", return_value=path),
                patch.object(hosted, "current_trigger_record_paths", return_value=[path]),
                self.assertRaisesRegex(ValueError, "matching full-review command"),
            ):
                hosted.recover_prepost_reservation(
                    path,
                    REPO,
                    PR,
                    HEAD,
                    "operator verified no POST was issued",
                    True,
                    lambda: review_payload([observed]),
                )
            self.assertEqual(json.loads(path.read_text(encoding="utf-8"))["status"], "posting")


if __name__ == "__main__":
    unittest.main()
