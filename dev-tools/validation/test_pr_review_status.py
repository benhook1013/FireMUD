#!/usr/bin/env python3
"""Hermetic tests for the unified PR-review status evidence adapter."""

from __future__ import annotations

import dataclasses
import json
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli, github, status
from pr_review.state import SummaryFindingDisposition

BASE = "a" * 40
HEAD = "b" * 40
MERGE_BASE = "c" * 40
REQUIRED_CONTEXTS = ["Validation Gate", "Security Gate", "License Gate", "Smoke Gate", "CodeQL Gate"]


def _review_thread_node_fields_without_comments(query: str) -> str:
    """Return review-thread node fields excluding the nested comment connection."""

    thread_nodes = re.search(r"\breviewThreads\s*\([^)]*\)\s*\{\s*nodes\s*\{", query)
    if thread_nodes is None:
        raise AssertionError("query does not select reviewThreads.nodes")

    def selection_end(source: str, opening_brace: int) -> int:
        depth = 0
        for index in range(opening_brace, len(source)):
            if source[index] == "{":
                depth += 1
            elif source[index] == "}":
                depth -= 1
                if depth == 0:
                    return index
        raise AssertionError("query contains an unterminated GraphQL selection")

    node_opening = thread_nodes.end() - 1
    node_end = selection_end(query, node_opening)
    node_fields = query[node_opening + 1 : node_end]
    comments = re.search(r"\bcomments\s*\([^)]*\)\s*\{", node_fields)
    if comments is None:
        return node_fields
    comments_opening = comments.end() - 1
    comments_end = selection_end(node_fields, comments_opening)
    return node_fields[: comments.start()] + node_fields[comments_end + 1 :]


def checkpoint_payload() -> dict:
    hosted = {
        "comment_id": 101,
        "created_at": "2026-09-21T01:00:00Z",
        "type": "Hosted",
        "raw_found": 2,
        "accepted": 0,
        "reviewed_sha": HEAD[:12],
        "file_count": 4,
        "correction": False,
        "duration_seconds": 14,
    }
    cli = {
        "comment_id": 102,
        "created_at": "2026-09-22T01:00:00Z",
        "type": "CLI",
        "raw_found": 1,
        "accepted": 1,
        "reviewed_sha": HEAD[:12],
        "file_count": 4,
        "correction": False,
    }
    return {
        "checkpoints": [hosted, cli],
        "timeline": [{"kind": "checkpoint", **hosted}, {"kind": "checkpoint", **cli}],
        "warnings": [],
        "unparsed_candidates": 0,
    }


def github_payload(checks: list[dict] | None = None) -> dict:
    default_checks = [
        {
            "__typename": "CheckRun",
            "workflowName": context,
            "name": context,
            "startedAt": "2026-09-23T00:00:00Z",
            "status": "COMPLETED",
            "conclusion": "SUCCESS",
            "head_sha": HEAD,
            "app": {"id": 42, "slug": "github-actions"},
        }
        for context in REQUIRED_CONTEXTS
    ]
    selected_checks = checks or default_checks
    lifecycle = {
        str(value.get(key)).upper()
        for value in selected_checks
        if isinstance(value, dict)
        for key in ("status", "state", "conclusion")
        if value.get(key) is not None
    }
    aggregate_state = (
        "FAILURE"
        if lifecycle & {"FAILURE", "ERROR", "CANCELLED"}
        else ("PENDING" if lifecycle & {"EXPECTED", "IN_PROGRESS", "PENDING", "QUEUED", "RUNNING"} else "SUCCESS")
    )
    return {
        "data": {
            "repository": {
                "pullRequest": {
                    "number": 2838,
                    "title": "Review controller",
                    "headRefName": "codex/review-controller",
                    "headRefOid": HEAD,
                    "baseRefName": "develop",
                    "baseRefOid": BASE,
                    "changedFiles": 4,
                    "mergeable": "MERGEABLE",
                    "mergeStateStatus": "CLEAN",
                    "isDraft": False,
                    "url": "https://github.test/pull/2838",
                    "commits": {"nodes": [{"commit": {"oid": HEAD, "statusCheckRollup": {"state": aggregate_state}}}]},
                    "body": (
                        "<!-- firemud:cloc-report:start -->\n"
                        "<!-- firemud:cloc-report:metadata "
                        + json.dumps(
                            {
                                "base_oid": BASE,
                                "head_oid": HEAD,
                                "merge_base": MERGE_BASE,
                                "classifier_sha256": "d" * 64,
                            }
                        )
                        + " -->\n"
                        "<!-- firemud:cloc-report:end -->"
                    ),
                    "statusCheckRollup": selected_checks,
                    "required_status_checks": {
                        "available": True,
                        "contexts": REQUIRED_CONTEXTS,
                        "checks": [{"context": context, "app_id": 42} for context in REQUIRED_CONTEXTS],
                    },
                    "reviewThreads": {
                        "nodes": [
                            {
                                "id": "PRRT_1",
                                "isResolved": False,
                                "isOutdated": False,
                                "comments": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                            },
                            {
                                "id": "PRRT_2",
                                "isResolved": True,
                                "isOutdated": False,
                                "comments": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                            },
                        ]
                    },
                    "comments": {"nodes": []},
                    "reviews": {"nodes": []},
                }
            }
        }
    }


class StatusTest(unittest.TestCase):
    def test_repository_lookup_uses_remaining_status_budget(self) -> None:
        clock = {"now": 0.0}

        def lookup(args, *, timeout, **_kwargs):
            self.assertEqual(timeout, 3)
            clock["now"] += 1
            return subprocess.CompletedProcess(args, 0, "owner/repo\n", "")

        with (
            patch.object(github.time, "monotonic", side_effect=lambda: clock["now"]),
            patch.dict(github.os.environ, {"GH_REPO": "", "GITHUB_REPOSITORY": ""}),
            github.activate_hosted_preflight_budget(preflight_name="PR status") as budget,
            patch.object(github.subprocess, "run", side_effect=lookup) as run,
        ):
            budget.set_phase("controller_construction", total=1)
            clock["now"] = 117
            self.assertEqual(status._repo_name(None), "owner/repo")
            self.assertEqual(status._repo_name("owner/repo"), "owner/repo")

        run.assert_called_once()

    def test_repository_lookup_preserves_status_deadline_diagnostic(self) -> None:
        clock = {"now": 0.0}

        def lookup(args, *, timeout, **_kwargs):
            self.assertEqual(timeout, 3)
            clock["now"] = 120
            raise subprocess.TimeoutExpired(args, timeout)

        with (
            patch.object(github.time, "monotonic", side_effect=lambda: clock["now"]),
            patch.dict(github.os.environ, {"GH_REPO": "", "GITHUB_REPOSITORY": ""}),
            github.activate_hosted_preflight_budget(preflight_name="PR status") as budget,
            patch.object(github.subprocess, "run", side_effect=lookup),
        ):
            budget.set_phase("controller_construction", total=1)
            clock["now"] = 117
            with self.assertRaisesRegex(
                github.HostedPreflightDeadlineExceeded,
                r"PR status deadline exceeded \(phase=controller_construction, .*budget=120s",
            ):
                status._repo_name(None)

    def test_loc_merge_base_uses_remaining_status_budget_and_preserves_expiry(self) -> None:
        pull_request = github_payload()["data"]["repository"]["pullRequest"]
        for outcome in ("within-budget", "timeout", "late-success", "late-failure"):
            with self.subTest(outcome=outcome):
                clock = {"now": 0.0}

                def merge_base(args, *, timeout, clock=clock, outcome=outcome, **_kwargs):
                    self.assertEqual(args, ["git", "merge-base", BASE, HEAD])
                    self.assertEqual(timeout, 3)
                    clock["now"] = 118 if outcome == "within-budget" else 120
                    if outcome == "timeout":
                        raise subprocess.TimeoutExpired(args, timeout)
                    if outcome == "late-failure":
                        raise subprocess.CalledProcessError(1, args)
                    return subprocess.CompletedProcess(args, 0, MERGE_BASE + "\n", "")

                with (
                    patch.object(github.time, "monotonic", side_effect=lambda clock=clock: clock["now"]),
                    github.activate_hosted_preflight_budget(preflight_name="PR status") as budget,
                    patch.object(status.subprocess, "run", side_effect=merge_base),
                ):
                    budget.set_phase("pr_review_evidence", total=1)
                    clock["now"] = 117
                    if outcome == "within-budget":
                        self.assertEqual(status._loc_status(pull_request)["status"], "fresh")
                    else:
                        with self.assertRaisesRegex(
                            github.HostedPreflightDeadlineExceeded,
                            r"PR status deadline exceeded \(phase=pr_review_evidence, .*budget=120s",
                        ):
                            status._loc_status(pull_request)

    def test_loc_timeout_without_shared_expiry_remains_unverified(self) -> None:
        pull_request = github_payload()["data"]["repository"]["pullRequest"]
        with patch.object(status.subprocess, "run", side_effect=subprocess.TimeoutExpired("git", 30)) as run:
            result = status._loc_status(pull_request)
        self.assertEqual(run.call_args.kwargs["timeout"], 30)
        self.assertEqual(result["status"], "unverified")

    def test_cli_status_defaults_to_windowed_overview_and_keeps_full_scan_explicit(self) -> None:
        controller = Mock()
        controller.status_overview.return_value = {"mode": "windowed"}
        controller.status.return_value = {"mode": "full"}

        with patch.object(cli, "default_controller", return_value=controller):
            overview, overview_exit = cli._dispatch(cli._parser().parse_args(["status", "--json"]))
            full, full_exit = cli._dispatch(cli._parser().parse_args(["status", "--full-scan", "--json"]))

        self.assertEqual((overview_exit, full_exit), (0, 0))
        self.assertEqual(overview, {"mode": "windowed"})
        self.assertEqual(full, {"mode": "full"})
        controller.status_overview.assert_called_once_with()
        controller.status.assert_called_once_with()

    def test_target_incoming_routes_hold_readiness_while_source_routes_are_trace_only(self) -> None:
        route = {
            "route_id": "1" * 24,
            "source_pr": 2828,
            "source_channel": "hosted",
            "source_review": "901",
            "source_finding": "thread-77",
            "observations": ["child-owned behavior"],
            "target_pr": 2879,
            "status": "open",
        }

        def stack_report(pr: int):
            return {
                "prs": [
                    {
                        "pr": pr,
                        "head": HEAD,
                        "base": "develop",
                        "parent_head": BASE,
                        "pr_base_oid": BASE,
                        "reconciliation": "COHERENT",
                        "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                        "allocations": {},
                        "incoming_routes": [route] if pr == 2879 else [],
                        "routes_out": [route] if pr == 2828 else [],
                    }
                ]
            }

        def report_for(pr: int, **_kwargs):
            return {
                "pr_number": pr,
                "pull_request": {"headRefOid": HEAD, "baseRefName": "develop", "baseRefOid": BASE},
                "reasons": [],
                "ready": True,
                "mergeability": {"clean": True, "diagnosis": "READY"},
            }

        controller = Mock()
        controller.store.load.return_value = SimpleNamespace(summary_dispositions=())
        controller.status_for_pr.side_effect = stack_report
        with (
            patch.object(cli, "default_controller", return_value=controller),
            patch.object(status, "status", side_effect=report_for),
            patch.object(cli, "_read_record_incoming_routes", return_value=([], {"status": "not_bootstrapped"})),
        ):
            target, _ = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2879", "--json"]))
            source, _ = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2828", "--json"]))

        self.assertFalse(target["ready"])
        self.assertIn("target-owner disposition", target["reasons"][-1])
        self.assertEqual(target["incoming_routes"], [route])
        self.assertEqual(target["routes_out"], [])
        self.assertTrue(source["ready"])
        self.assertEqual(source["routes_out"], [route])
        self.assertEqual(source["incoming_routes"], [])

    def _ready_report(self, payload: dict, **kwargs) -> dict:
        with patch.object(status, "_loc_status", return_value={"status": "fresh", "merge_base_checked": True}):
            return status.build_report(
                "owner/repo",
                2838,
                pull_request_payload=payload,
                checkpoint_payload=checkpoint_payload(),
                **kwargs,
            )

    @staticmethod
    def _coderabbit_review(
        body: str,
        commit: str = HEAD,
        submitted_at: str = "2026-09-23T01:00:00Z",
        database_id: int = 501,
        author: str = "coderabbitai[bot]",
    ) -> dict:
        return {
            "databaseId": database_id,
            "author": {"login": author},
            "body": body,
            "state": "COMMENTED",
            "submittedAt": submitted_at,
            "url": f"https://github.test/reviews/{database_id}",
            "commit": {"oid": commit},
        }

    def test_historical_checkpoint_duration_and_counts_are_retained(self) -> None:
        report = status.build_report(
            "owner/repo",
            2838,
            pull_request_payload=github_payload(),
            checkpoint_payload=checkpoint_payload(),
        )
        self.assertEqual(report["review_sequence"][0]["duration_seconds"], 14)
        self.assertEqual(report["checkpoint_counts"]["by_type"]["Hosted"]["count"], 1)
        self.assertEqual(report["checkpoint_counts"]["by_type"]["CLI"]["accepted"], 1)
        self.assertIsNone(report["checkpoint_counts"]["by_type"]["Hosted"]["routed"])
        self.assertFalse(report["checkpoint_counts"]["by_type"]["Hosted"]["routed_complete"])
        self.assertEqual(report["threads"], {"current": 1, "outdated": 0, "total": 1})
        self.assertFalse(report["ready"])

    def test_three_count_checkpoint_totals_are_exposed_without_reclassifying_old_rounds(self) -> None:
        counts = status._counts(
            [
                {
                    "type": "Hosted",
                    "raw_found": 4,
                    "accepted": 1,
                    "routed": 2,
                    "correction": False,
                    "created_at": "2026-09-23T00:00:00Z",
                },
                {
                    "type": "Hosted",
                    "raw_found": 2,
                    "accepted": 1,
                    "routed": None,
                    "correction": False,
                    "created_at": "2026-09-22T00:00:00Z",
                },
            ]
        )

        self.assertEqual(counts["by_type"]["Hosted"]["raw_found"], 6)
        self.assertEqual(counts["by_type"]["Hosted"]["accepted"], 2)
        self.assertIsNone(counts["by_type"]["Hosted"]["routed"])
        self.assertFalse(counts["by_type"]["Hosted"]["routed_complete"])

    def test_review_thread_queries_use_opaque_ids_without_database_id(self) -> None:
        for query in (github._BASE_QUERY, github._connection_query("reviewThreads")):
            with self.subTest(query=query):
                self.assertNotRegex(_review_thread_node_fields_without_comments(query), r"\bdatabaseId\b")
        report = self._ready_report(github_payload())
        self.assertEqual(report["unresolved_threads"][0]["id"], "PRRT_1")

    def test_graphql_errors_and_missing_review_connections_fail_closed(self) -> None:
        graphql_error = {"errors": [{"message": "partial response"}], "data": {}}
        with (
            patch.object(github, "run_gh_query", return_value=graphql_error),
            self.assertRaisesRegex(RuntimeError, "GraphQL response contains errors"),
        ):
            github.fetch_pull_request("owner/repo", 2838)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "graphql-error.json"
            path.write_text(json.dumps(graphql_error), encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "GraphQL response contains errors"):
                github.load_pull_request(path, "owner/repo", 2838)

        for connection in github.REVIEW_CONNECTIONS:
            for missing in (False, True):
                payload = github_payload()
                pull_request = payload["data"]["repository"]["pullRequest"]
                for existing in github.REVIEW_CONNECTIONS:
                    if isinstance(pull_request.get(existing), dict):
                        pull_request[existing]["pageInfo"] = {"hasNextPage": False}
                if missing:
                    del pull_request[connection]
                else:
                    pull_request[connection] = None
                expected = f"missing {connection} connection"
                with self.subTest(connection=connection, missing=missing):
                    with (
                        patch.object(github, "run_gh_query", return_value=payload),
                        self.assertRaisesRegex(TypeError, expected),
                    ):
                        github.fetch_pull_request("owner/repo", 2838)
                    with tempfile.TemporaryDirectory() as directory:
                        path = Path(directory) / "missing-connection.json"
                        path.write_text(json.dumps(payload), encoding="utf-8")
                        with self.assertRaisesRegex(TypeError, expected):
                            github.load_pull_request(path, "owner/repo", 2838)

    def test_live_status_captures_complete_unenriched_paginated_payload(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        metadata = {
            "number": pull_request["number"],
            "title": pull_request["title"],
            "state": "OPEN",
            "headRefName": pull_request["headRefName"],
            "headRefOid": HEAD,
            "headRepository": {"nameWithOwner": "owner/repo"},
            "headRepositoryOwner": {"login": "owner"},
            "baseRefName": "develop",
            "baseRefOid": BASE,
            "changedFiles": 4,
            "body": pull_request["body"],
            "statusCheckRollup": pull_request["statusCheckRollup"],
            "mergeable": "MERGEABLE",
            "mergeStateStatus": "CLEAN",
            "reviewDecision": "",
            "isDraft": False,
            "url": "https://github.test/pull/2838",
            "mergedAt": None,
        }
        required_checks = pull_request["required_status_checks"]
        check_inventory = pull_request["statusCheckRollup"]
        for field in (
            "title",
            "headRefName",
            "body",
            "statusCheckRollup",
            "mergeable",
            "mergeStateStatus",
            "reviewDecision",
            "isDraft",
            "url",
            "required_status_checks",
        ):
            pull_request.pop(field, None)
        pull_request["comments"] = {
            "nodes": [
                {
                    "id": "comment-1",
                    "databaseId": 1,
                    "author": {"login": "ben"},
                    "body": "first page",
                    "createdAt": "2026-09-23T00:00:00Z",
                    "updatedAt": "2026-09-23T00:00:00Z",
                    "url": "https://github.test/comments/1",
                }
            ],
            "pageInfo": {"hasNextPage": True, "endCursor": "comments-page-1"},
        }
        for connection in ("reviewThreads", "reviews"):
            pull_request[connection]["pageInfo"] = {"hasNextPage": False, "endCursor": None}
        for thread in pull_request["reviewThreads"]["nodes"]:
            thread["comments"]["pageInfo"] = {"hasNextPage": False, "endCursor": None}
        continuation = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "comments": {
                            "nodes": [
                                {
                                    "id": "comment-2",
                                    "databaseId": 2,
                                    "author": {"login": "ben"},
                                    "body": "second page",
                                    "createdAt": "2026-09-23T00:01:00Z",
                                    "updatedAt": "2026-09-23T00:01:00Z",
                                    "url": "https://github.test/comments/2",
                                }
                            ],
                            "pageInfo": {"hasNextPage": False, "endCursor": None},
                        }
                    }
                }
            }
        }
        observed: list[dict] = []

        def graphql(query: str, variables: dict) -> dict:
            if "after" in variables:
                self.assertEqual(variables["after"], "comments-page-1")
                self.assertIn("comments(first:100, after:$after)", query)
                return continuation
            return payload

        with (
            patch.object(github, "run_gh_query", side_effect=graphql) as query,
            patch.object(github, "fetch_pr_metadata", return_value=metadata),
            patch.object(status, "_loc_status", return_value={"status": "fresh", "merge_base_checked": True}),
        ):
            report = status.build_report(
                "owner/repo",
                2838,
                checkpoint_payload=checkpoint_payload(),
                required_status_checks_payload=required_checks,
                check_inventory_payload=check_inventory,
                raw_payload_observer=observed.append,
            )

        self.assertEqual(query.call_count, 2)
        self.assertEqual(len(observed), 1)
        self.assertIs(observed[0], payload)
        captured_pull = observed[0]["data"]["repository"]["pullRequest"]
        self.assertEqual([item["id"] for item in captured_pull["comments"]["nodes"]], ["comment-1", "comment-2"])
        self.assertNotIn("title", captured_pull)
        self.assertNotIn("mergeStateStatus", captured_pull)
        self.assertEqual(report["pull_request"]["title"], metadata["title"])

    def test_identity_batch_names_every_configured_pr_without_repository_list_limit(self) -> None:
        numbers = (12, 931)

        def identity(number: int) -> dict:
            return {
                "number": number,
                "state": "OPEN",
                "isDraft": False,
                "mergedAt": None,
                "baseRefName": "develop",
                "baseRefOid": BASE,
                "headRefName": f"feature-{number}",
                "headRefOid": HEAD,
                "mergeable": "MERGEABLE",
                "headRepository": {"nameWithOwner": "owner/repo"},
                "comments": {"nodes": []},
                "reviews": {"nodes": []},
            }

        payload = {"data": {"repository": {f"pr_{number}": identity(number) for number in numbers}}}
        with patch.object(github, "run_gh_query", return_value=payload) as query:
            result = github.fetch_pr_identity_batch("owner/repo", numbers)

        self.assertEqual(set(result), set(numbers))
        self.assertTrue(all(result[number]["number"] == number for number in numbers))
        query_text = query.call_args.args[0]
        self.assertIn("pr_12: pullRequest(number:12)", query_text)
        self.assertIn("pr_931: pullRequest(number:931)", query_text)
        self.assertNotIn("pullRequests(", query_text)
        self.assertNotIn("--limit", query_text)

    def test_identity_batch_marks_missing_alias_unknown_and_fails_on_graphql_errors(self) -> None:
        item = {
            "number": 12,
            "state": "OPEN",
            "isDraft": False,
            "mergedAt": None,
            "baseRefName": "develop",
            "baseRefOid": BASE,
            "headRefName": "feature-12",
            "headRefOid": HEAD,
            "mergeable": "MERGEABLE",
            "headRepository": {"nameWithOwner": "owner/repo"},
            "comments": {"nodes": []},
            "reviews": {"nodes": []},
        }
        with patch.object(
            github,
            "run_gh_query",
            return_value={"data": {"repository": {"pr_12": item, "pr_931": None}}},
        ):
            result = github.fetch_pr_identity_batch("owner/repo", (12, 931))
        self.assertIsNone(result[931])

        with (
            patch.object(
                github,
                "run_gh_query",
                return_value={"errors": [{"message": "partial"}], "data": {"repository": {}}},
            ),
            self.assertRaisesRegex(RuntimeError, "contains errors"),
        ):
            github.fetch_pr_identity_batch("owner/repo", (12,))

    def test_cli_selected_tail_uses_scoped_status_instead_of_default_full_scan(self) -> None:
        class FakeStore:
            @staticmethod
            def load():
                return type("State", (), {"summary_dispositions": ()})()

        class FakeController:
            store = FakeStore()
            repository = "owner/repo"

            def __init__(self) -> None:
                self.full_status_calls = 0
                self.selected = []

            def status(self):
                self.full_status_calls += 1
                return {"prs": []}

            def status_for_pr(self, number: int):
                self.selected.append(number)
                return {
                    "prs": [
                        {
                            "pr": value,
                            "head": HEAD,
                            "base": "feature-parent",
                            "parent_head": BASE,
                            "reconciliation": "COHERENT",
                            "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                            "allocations": {
                                "hosted": {"status": "HANDED_OFF", "reason": ""},
                                "cli": {"status": "HANDED_OFF", "reason": ""},
                            },
                        }
                        for value in range(1, number + 1)
                    ]
                }

        controller = FakeController()
        live_report = {
            "pr_number": 6,
            "pull_request": {
                "headRefOid": HEAD,
                "baseRefName": "feature-parent",
                "baseRefOid": BASE,
            },
            "reasons": [],
            "ready": True,
            "verdict": "READY",
            "mergeability": {"clean": True, "diagnosis": "CLEAN"},
        }
        args = cli._parser().parse_args(["status", "--pr", "6", "--json"])
        with (
            patch.object(cli, "_controller", return_value=(controller, None)),
            patch.object(cli.status_module, "status", return_value=live_report),
        ):
            result, exit_status = cli._dispatch(args)

        self.assertEqual(exit_status, 0)
        self.assertEqual(controller.selected, [6])
        self.assertEqual(controller.full_status_calls, 0)
        self.assertEqual([item["pr"] for item in result["review_stack"]["prs"]], list(range(1, 7)))

    def test_selected_status_seeds_each_command_with_its_fresh_raw_conversation(self) -> None:
        class FakeController:
            repository = "owner/repo"

            def __init__(self, selected: dict) -> None:
                self.store = SimpleNamespace(load=lambda: SimpleNamespace(summary_dispositions=()))
                self.selected = selected
                self.conversation_payloads: list[dict] = []
                self.summary_modes: list[bool] = []

            def status_for_pr(
                self,
                number: int,
                *,
                conversation_payload: dict | None = None,
                summary_only: bool = False,
            ) -> dict:
                self.conversation_payloads.append(conversation_payload)
                self.summary_modes.append(summary_only)
                return {
                    "prs": [
                        {
                            "pr": number,
                            "head": self.selected["head"],
                            "base": self.selected["base"],
                            "pr_base_oid": self.selected["base_oid"],
                            "reconciliation": "COHERENT",
                            "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                            "allocations": {
                                "hosted": {"status": "HANDED_OFF", "reason": ""},
                                "cli": {"status": "HANDED_OFF", "reason": ""},
                            },
                            "incoming_routes": [],
                            "routes_out": [],
                        }
                    ]
                }

        def conversation(head: str, base: str, base_oid: str) -> dict:
            return {
                "data": {
                    "repository": {
                        "pullRequest": {
                            "number": 2838,
                            "headRefOid": head,
                            "baseRefName": base,
                            "baseRefOid": base_oid,
                            "comments": {
                                "nodes": [{"id": head}],
                                "pageInfo": {"hasNextPage": False, "endCursor": None},
                            },
                        }
                    }
                }
            }

        selected = [
            {"head": HEAD, "base": "develop", "base_oid": BASE},
            {"head": "e" * 40, "base": "release", "base_oid": "f" * 40},
        ]
        payloads = [conversation(item["head"], item["base"], item["base_oid"]) for item in selected]
        controllers = [FakeController(item) for item in selected]
        next_payload = iter(payloads)

        def report_for(
            pr: int,
            *,
            raw_payload_observer,
            **_kwargs,
        ) -> dict:
            payload = next(next_payload)
            raw_payload_observer(payload)
            pull = payload["data"]["repository"]["pullRequest"]
            return {
                "pr_number": pr,
                "pull_request": {
                    "headRefOid": pull["headRefOid"],
                    "baseRefName": pull["baseRefName"],
                    "baseRefOid": pull["baseRefOid"],
                },
                "reasons": [],
                "ready": True,
                "verdict": "READY",
                "mergeability": {"clean": True, "diagnosis": "READY"},
            }

        summary_args = cli._parser().parse_args(["status", "--pr", "2838", "--summary", "--json"])
        detailed_args = cli._parser().parse_args(["status", "--pr", "2838", "--json"])
        with (
            patch.object(cli, "_controller", side_effect=[(controllers[0], None), (controllers[1], None)]),
            patch.object(status, "status", side_effect=report_for),
            patch.object(cli, "_read_record_incoming_routes", return_value=([], {"status": "available"})),
        ):
            first, first_exit = cli._dispatch(summary_args)
            second, second_exit = cli._dispatch(detailed_args)

        self.assertEqual((first_exit, second_exit), (0, 0))
        self.assertTrue(first["ready"])
        self.assertTrue(second["ready"])
        self.assertIs(controllers[0].conversation_payloads[0], payloads[0])
        self.assertIs(controllers[1].conversation_payloads[0], payloads[1])
        self.assertIsNot(controllers[0].conversation_payloads[0], controllers[1].conversation_payloads[0])
        self.assertEqual(controllers[0].summary_modes, [True])
        self.assertEqual(controllers[1].summary_modes, [False])

    def test_live_comment_shape_is_converted_to_historical_checkpoint_evidence(self) -> None:
        payload = github_payload()
        payload["data"]["repository"]["pullRequest"]["comments"] = {
            "nodes": [
                {
                    "id": "101",
                    "databaseId": 101,
                    "body": (
                        f"Hosted: 1 found / 0 accepted · `{HEAD[:12]}` · 4 files · 17s\n"
                        "<!-- firemud-hosted-review: 500 -->\n"
                        "<!-- firemud-review-duration-seconds: 17 -->"
                    ),
                    "createdAt": "2026-09-21T01:00:00Z",
                    "updatedAt": "2026-09-21T01:00:00Z",
                    "author": {"login": "ben"},
                    "url": "https://github.test/comments/101",
                }
            ]
        }
        report = status.build_report("owner/repo", 2838, pull_request_payload=payload)
        self.assertEqual(report["review_sequence"][0]["hosted_review_id"], 500)
        self.assertEqual(report["review_sequence"][0]["duration_seconds"], 17)
        self.assertEqual(report["checkpoint_counts"]["by_type"]["Hosted"]["count"], 1)

    def test_pr_level_review_decision_is_not_labeled_exact_head(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["reviewDecision"] = "CHANGES_REQUESTED"
        decision = status._review_decision(payload, HEAD, pull_request)
        self.assertEqual(decision["status"], "CHANGES_REQUESTED")
        self.assertEqual(decision["scope"], "pull_request")
        self.assertIsNone(decision["head_sha"])
        self.assertFalse(decision["exact_head"])

    def test_review_node_fallback_remains_exact_head_scoped(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["reviews"]["nodes"] = [
            {
                "databaseId": 501,
                "state": "APPROVED",
                "submittedAt": "2026-09-23T01:00:00Z",
                "commit": {"oid": HEAD},
            }
        ]
        decision = status._review_decision(payload, HEAD, pull_request)
        self.assertEqual(decision["status"], "APPROVED")
        self.assertEqual(decision["scope"], "exact_head_review_node")
        self.assertEqual(decision["head_sha"], HEAD)
        self.assertTrue(decision["exact_head"])

    def test_latest_effective_review_per_reviewer_clears_older_changes_request(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["reviews"]["nodes"] = [
            {
                "databaseId": 501,
                "author": {"login": "reviewer-a"},
                "state": "CHANGES_REQUESTED",
                "submittedAt": "2026-09-23T01:00:00Z",
                "commit": {"oid": HEAD},
            },
            {
                "databaseId": 502,
                "author": {"login": "reviewer-a"},
                "state": "APPROVED",
                "submittedAt": "2026-09-23T02:00:00Z",
                "commit": {"oid": HEAD},
            },
            {
                "databaseId": 503,
                "author": {"login": "reviewer-b"},
                "state": "APPROVED",
                "submittedAt": "2026-09-23T03:00:00Z",
                "commit": {"oid": HEAD},
            },
        ]

        decision = status._review_decision(payload, HEAD, pull_request)

        self.assertEqual(decision["status"], "APPROVED")
        self.assertEqual(decision["head_sha"], HEAD)

    def test_commented_review_does_not_clear_changes_requested(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["reviews"]["nodes"] = [
            {
                "databaseId": 504,
                "author": {"login": "reviewer-a"},
                "state": "CHANGES_REQUESTED",
                "submittedAt": "2026-09-23T01:00:00Z",
                "commit": {"oid": HEAD},
            },
            {
                "databaseId": 505,
                "author": {"login": "reviewer-a"},
                "state": "COMMENTED",
                "submittedAt": "2026-09-23T02:00:00Z",
                "commit": {"oid": HEAD},
            },
        ]

        decision = status._review_decision(payload, HEAD, pull_request)

        self.assertEqual(decision["status"], "CHANGES_REQUESTED")

    def test_commented_review_does_not_clear_approval(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["reviews"]["nodes"] = [
            {
                "databaseId": 506,
                "author": {"login": "reviewer-a"},
                "state": "APPROVED",
                "submittedAt": "2026-09-23T01:00:00Z",
                "commit": {"oid": HEAD},
            },
            {
                "databaseId": 507,
                "author": {"login": "reviewer-a"},
                "state": "COMMENTED",
                "submittedAt": "2026-09-23T02:00:00Z",
                "commit": {"oid": HEAD},
            },
        ]

        decision = status._review_decision(payload, HEAD, pull_request)

        self.assertEqual(decision["status"], "APPROVED")

    def test_later_comment_by_another_reviewer_does_not_clear_approval(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["reviews"]["nodes"] = [
            {
                "databaseId": 508,
                "author": {"login": "reviewer-a"},
                "state": "APPROVED",
                "submittedAt": "2026-09-23T01:00:00Z",
                "commit": {"oid": HEAD},
            },
            {
                "databaseId": 509,
                "author": {"login": "reviewer-b"},
                "state": "COMMENTED",
                "submittedAt": "2026-09-23T02:00:00Z",
                "commit": {"oid": HEAD},
            },
        ]

        decision = status._review_decision(payload, HEAD, pull_request)

        self.assertEqual(decision["status"], "APPROVED")

    def test_loc_markers_must_be_complete_lines_before_metadata(self) -> None:
        payload = github_payload()
        pull_request = payload["data"]["repository"]["pullRequest"]
        pull_request["body"] = (
            "prefix <!-- firemud:cloc-report:start -->\n"
            "<!-- firemud:cloc-report:metadata "
            + json.dumps(
                {
                    "base_oid": BASE,
                    "head_oid": HEAD,
                    "merge_base": MERGE_BASE,
                    "classifier_sha256": "d" * 64,
                }
            )
            + " -->\n"
            "<!-- firemud:cloc-report:end -->"
        )

        result = status._loc_status(pull_request)

        self.assertEqual(result["status"], "ambiguous")
        self.assertIn("markers", result["reason"])

    def test_archived_hosted_trigger_is_historical_not_current(self) -> None:
        payload = github_payload()
        record = {
            "schema_version": 1,
            "status": "posted",
            "repository": "owner/repo",
            "pr_number": 2838,
            "head_sha": HEAD,
            "trigger": {
                "id": 10,
                "created_at": "2026-09-22T00:00:00Z",
                "url": "https://github.test/comments/10",
                "type": "full",
                "command": "@coderabbitai full review",
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            archive = Path(directory) / "trigger-7.json"
            archive.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(status.evidence, "git_common_dir", return_value=Path(directory)),
                patch.object(status.hosted, "trigger_record_paths", return_value=[archive]),
            ):
                result = status._trigger("owner/repo", 2838, payload, HEAD)
        self.assertEqual(result["state"], "unavailable")
        self.assertEqual(result["classification"], "unavailable/no-current-request")
        self.assertEqual(result["historical"]["classification"], "historical")
        self.assertNotEqual(result["historical"]["state"], result["state"])

    def test_current_rate_limit_status_identifies_local_retry_backoff(self) -> None:
        payload = github_payload()
        payload["data"]["repository"]["pullRequest"]["comments"] = {
            "nodes": [
                {
                    "databaseId": 10,
                    "author": {"login": "maintainer"},
                    "body": status.hosted.FULL_COMMAND,
                    "createdAt": "2026-09-21T00:00:00Z",
                    "url": "https://github.test/comments/10",
                },
                {
                    "databaseId": 11,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": "Review rate limited.",
                    "createdAt": "2026-09-22T01:00:00Z",
                    "updatedAt": "2026-09-25T01:00:00Z",
                    "url": "https://github.test/comments/11",
                },
            ]
        }
        record = {
            "schema_version": 1,
            "status": "posted",
            "repository": "owner/repo",
            "pr_number": 2838,
            "head_sha": HEAD,
            "trigger": {
                "id": 10,
                "created_at": "2026-09-21T00:00:00Z",
                "url": "https://github.test/comments/10",
                "type": "full",
                "command": "@coderabbitai full review",
            },
        }
        with tempfile.TemporaryDirectory() as directory:
            current = Path(directory) / "trigger.json"
            current.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(status.evidence, "git_common_dir", return_value=Path(directory)),
                patch.object(status.hosted, "trigger_record_paths", return_value=[current]),
            ):
                result = status._trigger("owner/repo", 2838, payload, HEAD)
        self.assertEqual(result["state"], "rate_limited", result)
        self.assertEqual(result["cooldown_basis"], "local_retry_backoff")
        self.assertEqual(result["cooldown_until"], "2026-09-22T02:00:00+00:00")
        self.assertIn("local one-hour retry backoff", result["reason"])

    def test_current_posting_reservation_is_ambiguous_not_malformed(self) -> None:
        payload = github_payload()
        record = {
            "schema_version": 2,
            "status": "posting",
            "repository": "owner/repo",
            "pr_number": 2838,
            "head_sha": HEAD,
            "posting_started_at": "2026-09-22T00:00:00Z",
            "posting_actor_login": "maintainer",
            "posting_comment_id_floor": 101,
        }
        with tempfile.TemporaryDirectory() as directory:
            current = Path(directory) / "trigger.json"
            current.write_text(json.dumps(record), encoding="utf-8")
            with (
                patch.object(status.evidence, "git_common_dir", return_value=Path(directory)),
                patch.object(status.hosted, "trigger_record_paths", return_value=[current]),
            ):
                result = status._trigger("owner/repo", 2838, payload, HEAD)
        self.assertEqual(result["state"], "ambiguous")
        self.assertEqual(result["validation_outcome"], "valid")
        self.assertTrue(result["record_available"])

    def test_compact_and_json_views_use_the_same_report(self) -> None:
        report = status.build_report(
            "owner/repo", 2838, pull_request_payload=github_payload(), checkpoint_payload=checkpoint_payload()
        )
        compact = status.emit_text(report)
        encoded = json.dumps(report, sort_keys=True)
        decoded = json.loads(encoded)
        self.assertIn("PR #2838", compact)
        self.assertIn("Review controller", compact)
        self.assertIn(
            "threads: current=1 · outdated=0 · total=1",
            compact,
        )
        self.assertIn(report["verdict"], compact)
        self.assertEqual(decoded, report)
        self.assertEqual(decoded["threads"], {"current": 1, "outdated": 0, "total": 1})

    def test_compact_incoming_route_handles_missing_or_empty_observations(self) -> None:
        base_route = {
            "route_id": "1" * 24,
            "source_pr": 2828,
            "source_channel": "cli",
            "source_review": "review-1",
            "source_finding": "finding-1",
        }
        routes = [base_route, {**base_route, "route_id": "2" * 24, "observations": []}]
        report = {
            "pr_number": 2879,
            "pull_request": {
                "title": "Route target",
                "headRefName": "route-target",
                "headRefOid": HEAD,
                "baseRefName": "develop",
                "baseRefOid": BASE,
                "changedFiles": 1,
                "isDraft": False,
                "mergeable": "MERGEABLE",
                "mergeStateStatus": "CLEAN",
            },
            "threads": {"current": 0, "outdated": 0, "total": 0},
            "incoming_routes": routes,
            "routes_out": [],
            "review_decision": {"status": "ready"},
            "ci": {
                "required": {"status": "success", "contexts": []},
                "pending": [],
                "failed": [],
                "aggregate": {"state": "SUCCESS"},
                "optional": {"failed": []},
                "observed": True,
            },
            "checkpoint_counts": {
                "by_type": {
                    "Hosted": {"raw_found": 0, "accepted": 0, "routed": 0},
                    "CLI": {"raw_found": 0, "accepted": 0, "routed": 0},
                }
            },
            "verdict": "READY",
            "reasons": [],
        }

        compact = status.emit_text(report)

        self.assertEqual(compact.count("incoming route:"), 2)
        self.assertIn("finding finding-1 · ", compact)

    def test_ci_coalescing_keeps_latest_failed_check_and_contexts(self) -> None:
        checks = [
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-20T00:00:00Z",
                "status": "COMPLETED",
                "conclusion": "FAILURE",
            },
            {
                "__typename": "CheckRun",
                "workflowName": "CI",
                "name": "build",
                "startedAt": "2026-09-20T01:00:00Z",
                "status": "COMPLETED",
                "conclusion": "SUCCESS",
            },
            {"__typename": "StatusContext", "context": "deploy", "state": "FAILURE"},
        ]
        pending, failed, observed = status.normalize_checks(checks)
        self.assertEqual(observed, 3)
        self.assertEqual([item["name"] for item in failed], ["deploy"])
        self.assertEqual(pending, [])

    def test_malformed_check_fails_closed(self) -> None:
        with self.assertRaises(status.StatusError):
            status.build_report(
                "owner/repo",
                2838,
                pull_request_payload=github_payload([{"name": "build", "status": 3}]),
                checkpoint_payload=checkpoint_payload(),
            )

    def test_status_returns_one_structure_for_compact_and_json_renderers(self) -> None:
        with patch.object(status, "build_report", return_value={"verdict": "READY", "pr_number": 2838}):
            report = status.status(2838, as_json=True, repo="owner/repo")
        self.assertEqual(report["verdict"], "READY")

    def test_2838_required_gates_and_aggregate_success_can_still_be_blocked(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["mergeStateStatus"] = "BLOCKED"
        pr["reviewThreads"] = {"nodes": []}

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["required"]["status"], "passed")
        self.assertEqual(
            [item["status"] for item in report["ci"]["required"]["contexts"]],
            ["success"] * 5,
        )
        self.assertEqual(report["ci"]["aggregate"], {"state": "SUCCESS", "source": "github"})
        self.assertEqual(report["threads"]["total"], 0)
        self.assertFalse(report["ready"])
        self.assertEqual(report["verdict"], "BLOCKED — cause not exposed by available API")
        self.assertEqual(report["reasons"], ["BLOCKED — cause not exposed by available API"])
        json.dumps(report)

    def test_2838_close_reopen_fixture_shows_newly_pending_required_gates(self) -> None:
        pending_checks = [
            {
                "__typename": "CheckRun",
                "workflowName": context,
                "name": context,
                "startedAt": "2026-09-23T03:00:00Z",
                "status": "IN_PROGRESS",
                "conclusion": None,
                "head_sha": HEAD,
                "app": {"id": 42, "slug": "github-actions"},
            }
            for context in REQUIRED_CONTEXTS
        ]
        payload = github_payload(pending_checks)
        pr = payload["data"]["repository"]["pullRequest"]
        pr["mergeStateStatus"] = "BLOCKED"
        pr["reviewThreads"] = {"nodes": []}

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["aggregate"]["state"], "PENDING")
        self.assertEqual(report["ci"]["required"]["status"], "pending")
        self.assertEqual(
            {item["status"] for item in report["ci"]["required"]["contexts"]},
            {"pending"},
        )
        self.assertFalse(report["ready"])
        self.assertIn("required status checks are pending", report["reasons"][-1])

    def test_required_context_prefers_expected_app_over_later_wrong_app(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        wrong_app = dict(pr["statusCheckRollup"][0])
        wrong_app.update(
            {
                "startedAt": "2026-09-23T02:00:00Z",
                "conclusion": "FAILURE",
                "app": {"id": 99, "slug": "untrusted-app"},
            }
        )
        pr["statusCheckRollup"].append(wrong_app)

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["status"], "success")
        self.assertEqual(validation["result"]["app"]["id"], 42)
        self.assertEqual(report["ci"]["aggregate"]["state"], "SUCCESS")

    def test_expected_app_failure_blocks_required_check_readiness(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["statusCheckRollup"][0]["conclusion"] = "FAILURE"
        pr["commits"]["nodes"][0]["commit"]["statusCheckRollup"]["state"] = "SUCCESS"

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["expected_app"], {"id": 42})
        self.assertEqual(validation["status"], "failed")
        self.assertEqual(report["ci"]["required"]["status"], "failed")
        self.assertEqual(report["ci"]["aggregate"]["state"], "SUCCESS")
        self.assertFalse(report["ready"])
        self.assertIn("one or more required status checks failed or used the wrong app", report["reasons"])

    def test_required_context_with_only_wrong_app_is_visible_failure(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["statusCheckRollup"][0]["app"] = {"id": 99, "slug": "untrusted-app"}
        pr["statusCheckRollup"][0]["conclusion"] = "SUCCESS"

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["status"], "wrong_app")
        self.assertEqual(validation["wrong_app_results"][0]["app"]["id"], 99)
        self.assertEqual(report["ci"]["required"]["status"], "failed")
        self.assertIn("wrong app", " ".join(report["reasons"]))

    def test_required_context_without_app_identity_is_unknown(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["statusCheckRollup"][0].pop("app")

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["status"], "unknown")
        self.assertEqual(validation["wrong_app_results"], [])
        self.assertEqual(report["ci"]["required"]["status"], "pending")

    def test_status_context_creator_user_id_is_not_an_app_identity(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        check = pr["statusCheckRollup"][0]
        check.pop("app")
        check.update({"__typename": "StatusContext", "creator": {"id": 99, "login": "ben"}})

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["status"], "unknown")
        self.assertEqual(validation["result"]["app"], None)
        self.assertEqual(validation["wrong_app_results"], [])
        self.assertEqual(report["ci"]["required"]["status"], "pending")

    def test_matching_check_run_app_identity_satisfies_required_context(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}

        report = self._ready_report(payload)

        validation = report["ci"]["required"]["contexts"][0]
        self.assertEqual(validation["status"], "success")
        self.assertEqual(validation["result"]["kind"], "CheckRun")
        self.assertEqual(validation["result"]["app"]["id"], 42)

    def test_missing_or_mismatched_aggregate_head_fails_visibly(self) -> None:
        for mutate in (
            lambda pr: pr.pop("commits"),
            lambda pr: pr["commits"]["nodes"][0]["commit"].update({"oid": "c" * 40}),
        ):
            with self.subTest(mutate=mutate):
                payload = github_payload()
                pr = payload["data"]["repository"]["pullRequest"]
                pr["reviewThreads"] = {"nodes": []}
                mutate(pr)

                report = self._ready_report(payload)

                self.assertEqual(report["ci"]["aggregate"]["state"], "UNKNOWN")
                self.assertFalse(report["ready"])
                self.assertTrue(any("aggregate rollup" in reason for reason in report["reasons"]))

    def test_optional_cancelled_check_is_separate_from_required_gates(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["statusCheckRollup"].append(
            {
                "__typename": "CheckRun",
                "workflowName": "Optional Workflow",
                "name": "Optional Summary",
                "startedAt": "2026-09-23T02:00:00Z",
                "status": "COMPLETED",
                "conclusion": "CANCELLED",
                "head_sha": HEAD,
                "app": {"id": 42, "slug": "github-actions"},
            }
        )
        pr["commits"]["nodes"][0]["commit"]["statusCheckRollup"]["state"] = "FAILURE"

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["required"]["status"], "passed")
        self.assertEqual([item["name"] for item in report["ci"]["optional"]["failed"]], ["Optional Summary"])
        self.assertEqual(report["ci"]["aggregate"]["state"], "FAILURE")
        self.assertFalse(report["ready"])
        self.assertIn("GitHub aggregate rollup is FAILURE", report["reasons"])

    def test_missing_branch_protection_authority_is_visible_and_blocks_readiness(self) -> None:
        payload = github_payload()
        del payload["data"]["repository"]["pullRequest"]["required_status_checks"]

        report = self._ready_report(payload)

        self.assertFalse(report["ci"]["required"]["available"])
        self.assertIn("authoritative branch protection is unavailable", report["ci"]["required"]["reason"])
        self.assertFalse(report["ready"])

    def test_success_rollup_reports_conflicting_failed_or_pending_inventory(self) -> None:
        for conclusion, lifecycle, category in (
            ("FAILURE", "COMPLETED", "failed"),
            (None, "IN_PROGRESS", "pending"),
        ):
            with self.subTest(category=category):
                payload = github_payload()
                pr = payload["data"]["repository"]["pullRequest"]
                pr["reviewThreads"] = {"nodes": []}
                pr["statusCheckRollup"][0].update(status=lifecycle, conclusion=conclusion)
                report = self._ready_report(payload)

                self.assertEqual(report["ci"]["aggregate"], {"state": "SUCCESS", "source": "github"})
                self.assertTrue(report["ci"]["aggregate_inventory_conflict"])
                self.assertEqual([item["name"] for item in report["ci"][category]], ["Validation Gate"])
                self.assertEqual(report["verdict"], "NOT READY")
                self.assertFalse(report["ready"])
                compact = status.emit_text(report)
                self.assertIn("GitHub statusCheckRollup=SUCCESS", compact)
                self.assertIn(f"inventory_{category}=1", compact)
                self.assertIn("CI evidence conflict:", compact)

    def test_unavailable_required_authority_does_not_classify_inventory_as_optional(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["required_status_checks"] = {"available": False, "reason": "protection unavailable"}
        pr["statusCheckRollup"][0].update(conclusion="FAILURE")
        pr["statusCheckRollup"][1].update(status="IN_PROGRESS", conclusion=None)

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["required"]["status"], "unavailable")
        self.assertEqual(report["ci"]["optional"], {"available": False, "pending": [], "failed": []})
        self.assertEqual([item["name"] for item in report["ci"]["failed"]], ["Validation Gate"])
        self.assertEqual([item["name"] for item in report["ci"]["pending"]], ["Security Gate"])
        self.assertEqual(report["verdict"], "NOT READY")
        self.assertIn("protection unavailable", report["reasons"])
        self.assertIn("optional_failed=unknown", status.emit_text(report))

    def test_success_rollup_with_known_optional_failure_preserves_readiness(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["statusCheckRollup"].append(
            {
                "__typename": "CheckRun",
                "workflowName": "Optional Workflow",
                "name": "Optional Summary",
                "startedAt": "2026-09-23T02:00:00Z",
                "status": "COMPLETED",
                "conclusion": "FAILURE",
                "head_sha": HEAD,
                "app": {"id": 42, "slug": "github-actions"},
            }
        )

        report = self._ready_report(payload)

        self.assertEqual(report["ci"]["required"]["status"], "passed")
        self.assertTrue(report["ci"]["optional"]["available"])
        self.assertEqual([item["name"] for item in report["ci"]["optional"]["failed"]], ["Optional Summary"])
        self.assertTrue(report["ci"]["aggregate_inventory_conflict"])
        self.assertTrue(report["ready"])
        self.assertEqual(report["verdict"], "READY")
        self.assertIn("optional_failed=1", status.emit_text(report))

    def test_unavailable_inventory_labels_empty_and_failed_fallback_as_rollup_observations(self) -> None:
        for failure in (False, True):
            with self.subTest(failure=failure):
                payload = github_payload()
                pr = payload["data"]["repository"]["pullRequest"]
                pr["reviewThreads"] = {"nodes": []}
                pr["statusCheckRollup"] = (
                    [{"name": "Account", "status": "COMPLETED", "conclusion": "FAILURE"}]
                    if failure else []
                )
                report = self._ready_report(
                    payload,
                    check_inventory_payload={"available": False, "reason": "complete check inventory unavailable"},
                )

                self.assertFalse(report["ci"]["inventory_available"])
                self.assertEqual(report["ci"]["inventory_status"], "unavailable")
                self.assertEqual(report["ci"]["inventory_reason"], "complete check inventory unavailable")
                self.assertEqual(report["ci"]["aggregate"], {"state": "SUCCESS", "source": "github"})
                self.assertFalse(report["ci"]["aggregate_inventory_conflict"])
                self.assertFalse(report["ready"])
                compact = status.emit_text(report)
                self.assertIn("inventory=unavailable", compact)
                self.assertNotIn("inventory_pending=", compact)
                self.assertNotIn("inventory_failed=", compact)
                self.assertNotIn("inventory checks:", compact)
                self.assertIn(f"rollup_observed={int(failure)}", compact)
                self.assertIn(f"rollup_failed={int(failure)}", compact)
                self.assertIn(f"failed rollup observations: {'Account' if failure else 'none'}", compact)
                self.assertIn("CI inventory unavailable: complete check inventory unavailable", compact)

    def test_cli_status_fails_closed_when_snapshots_have_different_base_or_head(self) -> None:
        report = {
            "pull_request": {
                "headRefOid": HEAD,
                "baseRefName": "develop",
                "baseRefOid": BASE,
            },
            "reasons": [],
            "ready": True,
            "verdict": "READY",
            "mergeability": {"clean": True, "diagnosis": "READY"},
        }
        for stack_head, stack_base, stack_pr_base_oid in (
            ("e" * 40, "develop", BASE),
            (HEAD, "release", BASE),
            (HEAD, "develop", "f" * 40),
        ):
            with self.subTest(head=stack_head, base=stack_base, pr_base_oid=stack_pr_base_oid):
                controller = Mock()
                controller.status.return_value = {
                    "prs": [
                        {
                            "pr": 2838,
                            "head": stack_head,
                            "base": stack_base,
                            "parent_head": BASE,
                            "pr_base_oid": stack_pr_base_oid,
                            "reconciliation": "COHERENT",
                            "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                        }
                    ]
                }
                controller.status_for_pr.return_value = controller.status.return_value

                with (
                    patch.object(cli, "default_controller", return_value=controller),
                    patch.object(status, "status", return_value=report),
                ):
                    value, exit_status = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2838", "--json"]))

                self.assertEqual(exit_status, 0)
                self.assertFalse(value["ready"])
                self.assertEqual(value["verdict"], "NOT READY")
                self.assertIn("PR base/head changed between status snapshots", value["reasons"])
                self.assertFalse(value["mergeability"]["clean"])
                self.assertEqual(value["mergeability"]["diagnosis"], "NOT READY")

    def test_cli_status_remains_ready_when_base_and_head_snapshots_match(self) -> None:
        report = {
            "pull_request": {
                "headRefOid": HEAD,
                "baseRefName": "develop",
                "baseRefOid": BASE,
            },
            "reasons": [],
            "ready": True,
            "verdict": "READY",
            "mergeability": {"clean": True, "diagnosis": "READY"},
        }
        controller = Mock()
        controller.status.return_value = {
            "prs": [
                {
                    "pr": 2838,
                    "head": HEAD,
                    "base": "develop",
                    "parent_head": "f" * 40,
                    "pr_base_oid": BASE,
                    "reconciliation": "COHERENT",
                    "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                }
            ]
        }
        controller.status_for_pr.return_value = controller.status.return_value

        with (
            patch.object(cli, "default_controller", return_value=controller),
            patch.object(status, "status", return_value=report),
            patch.object(cli, "_read_record_incoming_routes", return_value=([], {"status": "not_bootstrapped"})),
        ):
            value, exit_status = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2838", "--json"]))

        self.assertEqual(exit_status, 0)
        self.assertTrue(value["ready"], value["reasons"])
        self.assertEqual(value["verdict"], "READY")
        self.assertTrue(value["mergeability"]["clean"])
        self.assertEqual(value["mergeability"]["diagnosis"], "READY")

    def test_human_stop_satisfies_only_the_taper_gate(self) -> None:
        report = {
            "pull_request": {
                "headRefOid": HEAD,
                "baseRefName": "develop",
                "baseRefOid": BASE,
            },
            "reasons": [],
            "ready": True,
            "verdict": "READY",
            "mergeability": {"clean": True, "diagnosis": "READY"},
        }
        controller = Mock()
        controller.status.return_value = {
            "prs": [
                {
                    "pr": 2838,
                    "head": HEAD,
                    "base": "develop",
                    "parent_head": BASE,
                    "pr_base_oid": BASE,
                    "reconciliation": "COHERENT",
                    "channels": {"hosted": "HUMAN_STOPPED", "cli": "HUMAN_STOPPED"},
                    "review_obligations": {"hosted": [], "cli": []},
                }
            ]
        }
        controller.status_for_pr.return_value = controller.status.return_value

        with (
            patch.object(cli, "default_controller", return_value=controller),
            patch.object(status, "status", return_value=report),
            patch.object(cli, "_read_record_incoming_routes", return_value=([], {"status": "not_bootstrapped"})),
        ):
            value, exit_status = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2838", "--json"]))

        self.assertEqual(exit_status, 0)
        self.assertTrue(value["ready"], value["reasons"])
        self.assertEqual(value["verdict"], "READY")
        self.assertTrue(value["mergeability"]["clean"])
        self.assertEqual(value["mergeability"]["diagnosis"], "READY")

    def test_human_stop_keeps_review_and_fix_obligations_as_readiness_gates(self) -> None:
        for obligation in ("active review", "pending capture", "accepted finding", "unattributed result"):
            for channel in ("hosted", "cli"):
                with self.subTest(obligation=obligation, channel=channel):
                    report = {
                        "pull_request": {"headRefOid": HEAD, "baseRefName": "develop", "baseRefOid": BASE},
                        "reasons": [],
                        "ready": True,
                        "verdict": "READY",
                        "mergeability": {"clean": True, "diagnosis": "READY"},
                    }
                    controller = Mock()
                    controller.status_for_pr.return_value = {
                        "prs": [
                            {
                                "pr": 2838,
                                "head": HEAD,
                                "base": "develop",
                                "parent_head": BASE,
                                "reconciliation": "COHERENT",
                                "channels": {"hosted": "HUMAN_STOPPED", "cli": "HUMAN_STOPPED"},
                                "review_obligations": {channel: [obligation]},
                            }
                        ]
                    }
                    with (
                        patch.object(cli, "default_controller", return_value=controller),
                        patch.object(status, "status", return_value=report),
                        patch.object(
                            cli, "_read_record_incoming_routes", return_value=([], {"status": "not_bootstrapped"})
                        ),
                    ):
                        value, _ = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2838", "--json"]))
                    self.assertFalse(value["ready"])
                    self.assertIn(f"{channel}: {obligation}", value["reasons"])

    def test_completed_discovery_keeps_fresh_controller_obligations_as_readiness_gates(self) -> None:
        for obligation in (
            "accepted findings remain pending; source resolution proof is uncertain",
            "accepted findings remain pending; their fixes are mandatory",
            "unresolved review thread discovered after public status read",
        ):
            for channel in ("hosted", "cli"):
                with self.subTest(obligation=obligation, channel=channel):
                    report = {
                        "pull_request": {"headRefOid": HEAD, "baseRefName": "develop", "baseRefOid": BASE},
                        "reasons": [],
                        "ready": True,
                        "verdict": "READY",
                        "mergeability": {"clean": True, "diagnosis": "READY"},
                    }
                    stack_report = {
                        "prs": [
                            {
                                "pr": 2838,
                                "head": HEAD,
                                "base": "develop",
                                "pr_base_oid": BASE,
                                "reconciliation": "COHERENT",
                                "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                                "review_obligations": {channel: [obligation]},
                            }
                        ],
                        "review_fronts": {"hosted": 2839, "cli": 2839},
                    }
                    controller = Mock()

                    def fresh_stack_status(number: int, report: dict = report, stack_report: dict = stack_report) -> dict:
                        public_status.assert_called_once()
                        self.assertEqual(number, 2838)
                        self.assertTrue(report["ready"])
                        self.assertEqual(report["reasons"], [])
                        return stack_report

                    controller.status_for_pr.side_effect = fresh_stack_status
                    with (
                        patch.object(cli, "default_controller", return_value=controller),
                        patch.object(status, "status", return_value=report) as public_status,
                        patch.object(
                            cli, "_read_record_incoming_routes", return_value=([], {"status": "not_bootstrapped"})
                        ),
                    ):
                        value, exit_status = cli._dispatch(
                            cli._parser().parse_args(["status", "--pr", "2838", "--json"])
                        )
                    self.assertEqual(exit_status, 0)
                    self.assertFalse(value["ready"])
                    self.assertEqual(value["verdict"], "NOT READY")
                    self.assertEqual(value["reasons"], [f"{channel}: {obligation}"])
                    self.assertFalse(value["mergeability"]["clean"])
                    self.assertEqual(value["mergeability"]["diagnosis"], "NOT READY")
                    self.assertEqual(value["review_stack"], stack_report)
                    self.assertEqual(stack_report["review_fronts"], {"hosted": 2839, "cli": 2839})
                    controller.status_for_pr.assert_called_once_with(2838)
                    controller.status.assert_not_called()

    def test_cli_status_supplies_persisted_summary_dispositions_to_report(self) -> None:
        disposition = SummaryFindingDisposition(
            2838, HEAD, "review", 501, "outside_diff", 1, "rejected", "finding does not apply"
        )
        controller = Mock()
        controller.store.load.return_value = Mock(summary_dispositions=(disposition,))
        controller.status.return_value = {
            "prs": [
                {
                    "pr": 2838,
                    "head": HEAD,
                    "base": "develop",
                    "parent_head": BASE,
                    "pr_base_oid": BASE,
                    "reconciliation": "COHERENT",
                    "channels": {"hosted": "COMPLETE", "cli": "COMPLETE"},
                }
            ]
        }
        controller.status_for_pr.return_value = controller.status.return_value
        report = {
            "pull_request": {"headRefOid": HEAD, "baseRefName": "develop", "baseRefOid": BASE},
            "reasons": [],
            "ready": True,
            "verdict": "READY",
        }

        with (
            patch.object(cli, "default_controller", return_value=controller),
            patch.object(status, "status", return_value=report) as status_call,
            patch.object(cli, "_read_record_incoming_routes", return_value=([], {"status": "not_bootstrapped"})),
        ):
            value, exit_status = cli._dispatch(cli._parser().parse_args(["status", "--pr", "2838", "--json"]))

        self.assertEqual(exit_status, 0)
        self.assertTrue(value["ready"])
        self.assertEqual(status_call.call_args.kwargs["summary_dispositions"], (disposition,))

    def test_latest_exact_head_summary_only_outside_diff_finding_blocks_readiness(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["reviews"] = {
            "nodes": [
                self._coderabbit_review("Duplicate comments (1)", commit="c" * 40, database_id=501),
                self._coderabbit_review("Outside diff range comments (1)", database_id=502),
            ]
        }

        report = self._ready_report(payload)

        self.assertFalse(report["ready"])
        self.assertEqual(
            report["coderabbit_summary"]["findings"],
            [{"kind": "outside_diff", "count": 1}],
        )
        self.assertTrue(any("actionable duplicate/outside-diff" in reason for reason in report["reasons"]))

    def test_exact_rejected_summary_bucket_clears_only_that_bucket_and_preserves_raw_evidence(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["reviews"] = {"nodes": [self._coderabbit_review("Outside diff range comments (2)")]}
        rejected = SummaryFindingDisposition(
            2838,
            HEAD,
            "review",
            501,
            "outside_diff",
            2,
            "rejected",
            "the outside-diff annotations are unrelated to this candidate",
        )

        report = self._ready_report(payload, summary_dispositions=(rejected,))

        self.assertTrue(report["ready"], report["reasons"])
        self.assertEqual(report["coderabbit_summary"]["findings"], [{"kind": "outside_diff", "count": 2}])
        self.assertEqual(report["coderabbit_summary"]["unresolved_findings"], [])
        self.assertEqual(report["coderabbit_summary"]["dispositions"], [rejected.to_dict()])

        for stale in (
            dataclasses.replace(rejected, summary_id=502),
            dataclasses.replace(rejected, count=1),
            dataclasses.replace(rejected, head="c" * 40),
        ):
            with self.subTest(disposition=stale):
                blocked = self._ready_report(payload, summary_dispositions=(stale,))
                self.assertFalse(blocked["ready"])
                self.assertEqual(
                    blocked["coderabbit_summary"]["unresolved_findings"],
                    blocked["coderabbit_summary"]["findings"],
                )

        pr["reviews"] = {"nodes": [self._coderabbit_review("Outside diff range comments (2)\nDuplicate comments (1)")]}
        partial = self._ready_report(payload, summary_dispositions=(rejected,))
        self.assertFalse(partial["ready"])
        self.assertEqual(partial["coderabbit_summary"]["unresolved_findings"], [{"kind": "duplicate", "count": 1}])

    def test_summary_disposition_does_not_bypass_review_thread_gate(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": [{"id": "PRRT_open", "isResolved": False, "isOutdated": False}]}
        pr["reviews"] = {"nodes": [self._coderabbit_review("Outside diff range comments (1)")]}
        rejected = SummaryFindingDisposition(
            2838, HEAD, "review", 501, "outside_diff", 1, "rejected", "finding does not apply"
        )

        report = self._ready_report(payload, summary_dispositions=(rejected,))

        self.assertFalse(report["ready"])
        self.assertEqual(report["coderabbit_summary"]["unresolved_findings"], [])
        self.assertEqual(report["threads"]["current"], 1)
        self.assertTrue(any("unresolved review thread" in reason for reason in report["reasons"]))
        self.assertFalse(any("actionable duplicate/outside-diff" in reason for reason in report["reasons"]))

    def test_latest_exact_head_summary_replaces_older_actionable_summary(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["reviews"] = {
            "nodes": [
                self._coderabbit_review("Duplicate comments (1)", submitted_at="2026-09-23T00:00:00Z", database_id=501),
                self._coderabbit_review("Duplicate comments (0)", submitted_at="2026-09-23T02:00:00Z", database_id=502),
            ]
        }

        report = self._ready_report(payload)

        self.assertEqual(report["coderabbit_summary"]["findings"], [])
        self.assertTrue(report["ready"], report["reasons"])

    def test_latest_exact_head_summary_comment_replaces_older_actionable_comment(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["comments"] = {
            "nodes": [
                {
                    "databaseId": 601,
                    "author": {"login": "coderabbitai"},
                    "body": (
                        f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and `{HEAD[:12]}`.\n\n"
                        "Duplicate comments (1)"
                    ),
                    "createdAt": "2026-09-23T01:30:00Z",
                    "updatedAt": "2026-09-23T01:30:00Z",
                    "url": "https://github.test/comments/601",
                },
                {
                    "databaseId": 602,
                    "author": {"login": "coderabbitai"},
                    "body": f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and `{HEAD[:12]}`.\n\nDuplicate comments (0)",
                    "createdAt": "2026-09-23T02:00:00Z",
                    "updatedAt": "2026-09-23T02:00:00Z",
                    "url": "https://github.test/comments/602",
                },
            ]
        }

        report = self._ready_report(payload)

        self.assertTrue(report["ready"], report["reasons"])
        self.assertEqual(report["coderabbit_summary"]["source"], "comment")
        self.assertEqual(report["coderabbit_summary"]["identity"], 602)
        self.assertEqual(report["coderabbit_summary"]["findings"], [])

    def test_later_section_free_exact_head_summary_comment_clears_older_finding(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviewThreads"] = {"nodes": []}
        pr["comments"] = {
            "nodes": [
                {
                    "databaseId": 608,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": (
                        f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and "
                        f"`{HEAD[:12]}`.\n\nDuplicate comments (1)"
                    ),
                    "createdAt": "2026-09-23T01:00:00Z",
                    "updatedAt": "2026-09-23T01:00:00Z",
                },
                {
                    "databaseId": 609,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": (
                        "<!-- walkthrough_start -->\n"
                        f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and "
                        f"`{HEAD[:12]}`.\n\nThe current changes look clean."
                    ),
                    "createdAt": "2026-09-23T02:00:00Z",
                    "updatedAt": "2026-09-23T02:00:00Z",
                },
            ]
        }

        report = self._ready_report(payload)

        self.assertTrue(report["ready"], report["reasons"])
        self.assertEqual(report["coderabbit_summary"]["source"], "comment")
        self.assertEqual(report["coderabbit_summary"]["identity"], 609)
        self.assertEqual(report["coderabbit_summary"]["findings"], [])

    def test_walkthrough_comment_without_summary_section_cannot_hide_exact_head_review(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["reviews"] = {
            "nodes": [
                self._coderabbit_review("Duplicate comments (1)", submitted_at="2026-09-23T02:00:00Z", database_id=603)
            ]
        }
        pr["comments"] = {
            "nodes": [
                {
                    "databaseId": 604,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": (
                        f"<!-- walkthrough_start -->\nReviewing files that changed from the base of the PR and between "
                        f"`{BASE[:12]}` and `{HEAD[:12]}`."
                    ),
                    "createdAt": "2026-09-23T01:00:00Z",
                    "updatedAt": "2026-09-23T03:00:00Z",
                    "url": "https://github.test/comments/604",
                }
            ]
        }

        selected = status._summary_evidence(payload, HEAD)

        self.assertEqual(selected["source"], "review")
        self.assertEqual(selected["identity"], 603)
        self.assertEqual(selected["findings"], [{"kind": "duplicate", "count": 1}])

    def test_summary_comment_selection_uses_created_at_and_keeps_canonical_zero_counts(self) -> None:
        payload = github_payload()
        pr = payload["data"]["repository"]["pullRequest"]
        pr["comments"] = {
            "nodes": [
                {
                    "databaseId": 605,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": (
                        f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and "
                        f"`{HEAD[:12]}`.\nDuplicate comments (1)"
                    ),
                    "createdAt": "2026-09-23T01:00:00Z",
                    "updatedAt": "2026-09-23T03:00:00Z",
                    "url": "https://github.test/comments/605",
                },
                {
                    "databaseId": 606,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": (
                        f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and "
                        f"`{HEAD[:12]}`.\n### Duplicate comments (0)"
                    ),
                    "createdAt": "2026-09-23T02:00:00Z",
                    "updatedAt": "2026-09-23T02:00:00Z",
                    "url": "https://github.test/comments/606",
                },
            ]
        }

        selected = status._summary_evidence(payload, HEAD)

        self.assertEqual(selected["source"], "comment")
        self.assertEqual(selected["identity"], 606)
        self.assertEqual(selected["findings"], [])

    def test_malformed_explicit_summary_comment_fails_closed(self) -> None:
        payload = github_payload()
        payload["data"]["repository"]["pullRequest"]["comments"] = {
            "nodes": [
                {
                    "databaseId": 607,
                    "author": {"login": "coderabbitai[bot]"},
                    "body": (
                        f"Reviewing files that changed from the base of the PR and between `{BASE[:12]}` and "
                        f"`{HEAD[:12]}`.\n## Duplicate comments: 1"
                    ),
                    "createdAt": "2026-09-23T02:00:00Z",
                    "updatedAt": "2026-09-23T02:00:00Z",
                }
            ]
        }

        with self.assertRaisesRegex(status.StatusError, "summary section has no canonical count"):
            status._summary_evidence(payload, HEAD)

    def test_malformed_exact_head_summary_fails_closed(self) -> None:
        payload = github_payload()
        payload["data"]["repository"]["pullRequest"]["reviews"] = {
            "nodes": [self._coderabbit_review("Duplicate comments\n\n## Other section\ncontent")]
        }

        with self.assertRaisesRegex(status.StatusError, "summary section has no canonical count"):
            self._ready_report(payload)

    def test_missing_paginated_review_evidence_fails_closed(self) -> None:
        payload = github_payload()
        del payload["data"]["repository"]["pullRequest"]["reviews"]

        with self.assertRaisesRegex(status.StatusError, "summary evidence is malformed or missing"):
            self._ready_report(payload)


if __name__ == "__main__":
    unittest.main()
