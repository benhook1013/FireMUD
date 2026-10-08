#!/usr/bin/env python3
"""Focused proof for complete batched Hosted repository comment reads."""

from __future__ import annotations

import re
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import github, hosted
from pr_review.controller import ControllerError
from pr_review.runtime import HostedRunner, LiveGitHub

HEAD = "b" * 40


class RepositoryCommentBatchTests(unittest.TestCase):
    @staticmethod
    def _open_prs(numbers):
        return [{"number": number, "state": "open"} for number in numbers]

    def test_repository_scan_batches_53_comment_reads_into_three_graphql_requests(self):
        numbers = tuple(range(100, 153))
        open_prs = self._open_prs((42, *numbers))
        runner = HostedRunner("owner/repo", LiveGitHub("owner/repo"))
        queries = []
        query_budgets = []

        def run_graphql(query, _variables):
            queries.append(query)
            query_budgets.append(github.active_hosted_preflight_budget())
            selected_numbers = tuple(
                int(match.group(1)) for match in re.finditer(r"pr_(\d+): pullRequest\(number:(\d+)\)", query)
            )
            return {
                "data": {
                    "repository": {
                        f"pr_{number}": {
                            "number": number,
                            "comments": {
                                "nodes": [],
                                "pageInfo": {"hasNextPage": False, "endCursor": None},
                            },
                        }
                        for number in selected_numbers
                    }
                }
            }

        def fetch_rest(endpoint):
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return open_prs
            self.fail(f"complete GraphQL batch should avoid per-PR REST reads: {endpoint}")

        with (
            github.activate_hosted_preflight_budget(timeout_seconds=30) as budget,
            patch.object(runner, "_repository_current_trigger_paths", return_value={}),
            patch.object(github, "run_gh_query", side_effect=run_graphql),
            patch.object(github, "fetch_api_endpoint", side_effect=fetch_rest) as rest_read,
        ):
            runner._assert_no_other_active_reservations(42, Path("/unused"))

        query_number_groups = [
            tuple(int(match.group(1)) for match in re.finditer(r"pr_(\d+): pullRequest\(number:(\d+)\)", query))
            for query in queries
        ]
        self.assertEqual(sorted(len(group) for group in query_number_groups), [3, 25, 25])
        self.assertEqual({number for group in query_number_groups for number in group}, set(numbers))
        self.assertEqual(len(queries), 3)
        self.assertTrue(all(active is budget for active in query_budgets))
        self.assertEqual(rest_read.call_count, 1)
        self.assertEqual(rest_read.call_args.args[0], "repos/owner/repo/pulls?state=open&per_page=100")

    def test_malformed_batch_falls_back_to_full_rest_for_its_entire_group(self):
        runner = HostedRunner("owner/repo", LiveGitHub("owner/repo"))
        open_prs = self._open_prs((42, 43, 44))
        batch_budgets = []
        rest_budgets = []
        rest_endpoints = []

        def fetch_batch(_repo, _numbers):
            batch_budgets.append(github.active_hosted_preflight_budget())
            return {43: []}

        def fetch_rest(endpoint):
            rest_budgets.append(github.active_hosted_preflight_budget())
            rest_endpoints.append(endpoint)
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return open_prs
            return []

        with (
            github.activate_hosted_preflight_budget(timeout_seconds=30) as budget,
            patch.object(runner, "_repository_current_trigger_paths", return_value={}),
            patch.object(github, "fetch_issue_comments_batch", side_effect=fetch_batch),
            patch.object(github, "fetch_api_endpoint", side_effect=fetch_rest),
        ):
            runner._assert_no_other_active_reservations(42, Path("/unused"))

        self.assertEqual(batch_budgets, [budget])
        self.assertTrue(rest_budgets and all(active is budget for active in rest_budgets))
        self.assertCountEqual(
            [
                "repos/owner/repo/issues/43/comments?per_page=100",
                "repos/owner/repo/issues/44/comments?per_page=100",
            ],
            [endpoint for endpoint in rest_endpoints if "/issues/" in endpoint],
        )

    def test_expired_batch_does_not_restart_with_rest_fallback(self):
        runner = HostedRunner("owner/repo", LiveGitHub("owner/repo"))
        rest_endpoints = []

        def fetch_rest(endpoint):
            rest_endpoints.append(endpoint)
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return self._open_prs((42, 43))
            self.fail("expired GraphQL budget must not start REST comment fallback")

        deadline = github.HostedPreflightDeadlineExceeded("repository_issue_comments", 31, 30, 0, 1)
        with (
            github.activate_hosted_preflight_budget(timeout_seconds=30),
            patch.object(runner, "_repository_current_trigger_paths", return_value={}),
            patch.object(github, "fetch_issue_comments_batch", side_effect=deadline),
            patch.object(github, "fetch_api_endpoint", side_effect=fetch_rest),
            self.assertRaisesRegex(ControllerError, "repository_issue_comments.*budget=30s"),
        ):
            runner._assert_no_other_active_reservations(42, Path("/unused"))

        self.assertEqual(rest_endpoints, ["repos/owner/repo/pulls?state=open&per_page=100"])

    def test_batched_off_queue_manual_command_still_requires_full_attribution(self):
        manual = {
            "id": "comment-node-901",
            "databaseId": 901,
            "author": {"login": "maintainer"},
            "body": hosted.FULL_COMMAND,
            "createdAt": "2026-10-08T00:00:00Z",
            "updatedAt": "2026-10-08T00:00:00Z",
            "url": "https://example.test/comments/901",
        }
        pull_request = {
            "number": 99,
            "headRefOid": HEAD,
            "comments": {"nodes": [manual]},
            "reviews": {"nodes": []},
            "reviewThreads": {"nodes": []},
        }
        payload = {"data": {"repository": {"pullRequest": pull_request}}}
        runner = HostedRunner("owner/repo", LiveGitHub("owner/repo"))

        def fetch_rest(endpoint):
            if endpoint == "repos/owner/repo/pulls?state=open&per_page=100":
                return self._open_prs((42, 99))
            self.fail(f"GraphQL comment batch was expected: {endpoint}")

        with (
            patch.object(runner, "_repository_current_trigger_paths", return_value={}),
            patch.object(github, "fetch_api_endpoint", side_effect=fetch_rest),
            patch.object(github, "fetch_issue_comments_batch", return_value={99: [manual]}),
            patch.object(github, "fetch_pull_request", return_value=payload) as fetch_full,
            patch.object(hosted, "unresolved_preceding_full_trigger", return_value=False),
            self.assertRaisesRegex(ControllerError, "manual Hosted request is unresolved for PR #99"),
        ):
            runner._assert_no_other_active_reservations(42, Path("/unused"))

        fetch_full.assert_called_once_with("owner/repo", 99)


if __name__ == "__main__":
    unittest.main()
