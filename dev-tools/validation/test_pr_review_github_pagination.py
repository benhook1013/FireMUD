#!/usr/bin/env python3
"""Focused proof that GitHub GraphQL pagination fails closed on cursor loops."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import github


class GithubPaginationTests(unittest.TestCase):
    @staticmethod
    def _issue_comment(comment_id: int) -> dict[str, object]:
        return {
            "id": f"comment-node-{comment_id}",
            "databaseId": comment_id,
            "author": {"login": "maintainer"},
            "body": f"comment {comment_id}",
            "createdAt": "2026-10-08T00:00:00Z",
            "updatedAt": None,
            "url": f"https://example.test/comments/{comment_id}",
        }

    @staticmethod
    def _issue_comment_page(number, comments, *, has_next=False, cursor=None):
        return {
            "number": number,
            "comments": {
                "nodes": comments,
                "pageInfo": {"hasNextPage": has_next, "endCursor": cursor},
            },
        }

    def test_issue_comment_batch_paginates_each_pr_with_its_own_cursor(self):
        first_page = {
            "data": {
                "repository": {
                    "pr_42": self._issue_comment_page(
                        42, [self._issue_comment(421)], has_next=True, cursor="cursor-42"
                    ),
                    "pr_43": self._issue_comment_page(
                        43, [self._issue_comment(431)], has_next=True, cursor="cursor-43"
                    ),
                }
            }
        }
        second_page = {
            "data": {
                "repository": {
                    "pr_42": self._issue_comment_page(42, [self._issue_comment(422)]),
                    "pr_43": self._issue_comment_page(43, [self._issue_comment(432)]),
                }
            }
        }

        with patch.object(github, "run_gh_query", side_effect=[first_page, second_page]) as query:
            result = github.fetch_issue_comments_batch("owner/repo", (42, 43))

        self.assertEqual([item["databaseId"] for item in result[42]], [421, 422])
        self.assertEqual([item["databaseId"] for item in result[43]], [431, 432])
        self.assertEqual(query.call_count, 2)
        self.assertEqual(
            query.call_args_list[1].args[1],
            {
                "owner": "owner",
                "repo": "repo",
                "after_42": "cursor-42",
                "after_43": "cursor-43",
            },
        )
        self.assertIn("comments(first:100, after:$after_42)", query.call_args_list[1].args[0])
        self.assertIn("comments(first:100, after:$after_43)", query.call_args_list[1].args[0])

    def test_issue_comment_batch_rejects_missing_identity_and_repeated_cursor(self):
        malformed_identity = {
            "data": {
                "repository": {
                    "pr_42": self._issue_comment_page(43, []),
                }
            }
        }
        repeated_cursor = {
            "data": {
                "repository": {
                    "pr_42": self._issue_comment_page(
                        42, [self._issue_comment(422)], has_next=True, cursor="cursor-42"
                    ),
                }
            }
        }
        initial = {
            "data": {
                "repository": {
                    "pr_42": self._issue_comment_page(
                        42, [self._issue_comment(421)], has_next=True, cursor="cursor-42"
                    ),
                }
            }
        }

        with (
            patch.object(github, "run_gh_query", return_value=malformed_identity),
            self.assertRaisesRegex(RuntimeError, "identity is missing or mismatched"),
        ):
            github.fetch_issue_comments_batch("owner/repo", (42,))

        with (
            patch.object(github, "run_gh_query", side_effect=[initial, repeated_cursor]),
            self.assertRaisesRegex(RuntimeError, "pagination repeated cursor for PR #42"),
        ):
            github.fetch_issue_comments_batch("owner/repo", (42,))

    def test_issue_comment_batch_rejects_malformed_connections_ids_and_cursors(self):
        malformed_connections = (
            (
                {"nodes": [], "pageInfo": {}},
                "page info is malformed",
            ),
            (
                {
                    "nodes": [{"id": "opaque-id", "body": "comment"}],
                    "pageInfo": {"hasNextPage": False},
                },
                "identity is missing",
            ),
            (
                {"nodes": [], "pageInfo": {"hasNextPage": True}},
                "pagination has no cursor",
            ),
        )

        for connection, expected in malformed_connections:
            payload = {
                "data": {
                    "repository": {
                        "pr_42": {"number": 42, "comments": connection},
                    }
                }
            }
            with (
                self.subTest(expected=expected),
                patch.object(github, "run_gh_query", return_value=payload),
                self.assertRaisesRegex((RuntimeError, TypeError), expected),
            ):
                github.fetch_issue_comments_batch("owner/repo", (42,))

    def test_issue_comment_batch_limits_each_query_to_25_pull_requests(self):
        with self.assertRaisesRegex(ValueError, "limited to 25 PRs"):
            github.fetch_issue_comments_batch("owner/repo", tuple(range(1, 27)))

    def test_expired_phase_transition_preserves_previous_nonzero_progress(self):
        with patch.object(github.time, "monotonic", return_value=0) as monotonic:
            budget = github.HostedPreflightBudget(timeout_seconds=10)
            budget.set_phase("manual_trigger_verification", completed=2, total=3)
            monotonic.return_value = 11
            with self.assertRaises(github.HostedPreflightDeadlineExceeded) as raised:
                budget.set_phase("selected_pr_final_identity", completed=0, total=1)

        error = raised.exception
        self.assertEqual(error.phase, "manual_trigger_verification")
        self.assertEqual((error.completed, error.total), (2, 3))
        self.assertEqual(error.elapsed_seconds, 11)
        self.assertEqual(error.budget_seconds, 10)
        self.assertEqual(budget.current_phase, "manual_trigger_verification")
        self.assertEqual((budget.completed, budget.total), (2, 3))
        self.assertNotIn("manual_trigger_verification", budget.phase_progress)

    @staticmethod
    def _file_input_payload(*, thread_page_info=None):
        thread = {"id": "thread-1", "comments": {"nodes": []}}
        if thread_page_info is not None:
            thread["comments"]["pageInfo"] = thread_page_info
        pull_request = {
            "reviewThreads": {"nodes": [thread]},
            "comments": {"nodes": []},
            "reviews": {"nodes": []},
        }
        return {"data": {"repository": {"pullRequest": pull_request}}}

    def test_file_input_rejects_incomplete_top_level_review_connection(self):
        for connection in github.REVIEW_CONNECTIONS:
            payload = self._file_input_payload()
            payload["data"]["repository"]["pullRequest"][connection]["pageInfo"] = {"hasNextPage": True}
            with self.subTest(connection=connection), tempfile.TemporaryDirectory() as directory:
                path = Path(directory) / "pull-request.json"
                path.write_text(json.dumps(payload), encoding="utf-8")

                with self.assertRaisesRegex(TypeError, f"{connection} connection has incomplete pages"):
                    github.load_pull_request(path, "owner/repo", 42)

    def test_file_input_rejects_incomplete_nested_thread_comments(self):
        payload = self._file_input_payload(thread_page_info={"hasNextPage": True})
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pull-request.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            with self.assertRaisesRegex(TypeError, "review-thread comments connection has incomplete pages"):
                github.load_pull_request(path, "owner/repo", 42)

    def test_file_input_accepts_connections_without_optional_page_info(self):
        payload = self._file_input_payload()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "pull-request.json"
            path.write_text(json.dumps(payload), encoding="utf-8")

            self.assertEqual(github.load_pull_request(path, "owner/repo", 42), payload)

    def test_thread_comment_pagination_rejects_repeated_cursor(self):
        thread = {
            "id": "thread-1",
            "comments": {
                "nodes": ["first"],
                "pageInfo": {"hasNextPage": True, "endCursor": "cursor-1"},
            },
        }
        repeated_page = {
            "data": {
                "node": {
                    "comments": {
                        "nodes": ["second"],
                        "pageInfo": {"hasNextPage": True, "endCursor": "cursor-1"},
                    }
                }
            }
        }

        with (
            patch.object(github, "run_gh_query", return_value=repeated_page) as query,
            self.assertRaisesRegex(RuntimeError, "repeated cursor 'cursor-1'"),
        ):
            github._paginate_thread_comments(thread)

        query.assert_called_once()
        self.assertEqual(query.call_args.args[1]["after"], "cursor-1")

    def test_pull_request_connection_pagination_rejects_repeated_cursor(self):
        initial = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "headRefOid": "a" * 40,
                        "commits": {"nodes": []},
                        "reviewThreads": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                        "comments": {
                            "nodes": ["first"],
                            "pageInfo": {"hasNextPage": True, "endCursor": "cursor-1"},
                        },
                        "reviews": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                    }
                }
            }
        }
        repeated_page = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "comments": {
                            "nodes": ["second"],
                            "pageInfo": {"hasNextPage": True, "endCursor": "cursor-1"},
                        }
                    }
                }
            }
        }

        with (
            patch.object(github, "run_gh_query", side_effect=[initial, repeated_page]) as query,
            self.assertRaisesRegex(RuntimeError, "GitHub comments pagination repeated cursor 'cursor-1'"),
        ):
            github.fetch_pull_request("owner/repo", 42)

        self.assertEqual(query.call_count, 2)
        self.assertEqual(query.call_args_list[1].args[1]["after"], "cursor-1")

    def test_pull_request_pages_share_the_hosted_preflight_deadline(self):
        initial = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "headRefOid": "a" * 40,
                        "commits": {"nodes": []},
                        "reviewThreads": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                        "comments": {
                            "nodes": [],
                            "pageInfo": {"hasNextPage": True, "endCursor": "comments-1"},
                        },
                        "reviews": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                    }
                }
            }
        }
        next_page = {
            "data": {"repository": {"pullRequest": {"comments": {"nodes": [], "pageInfo": {"hasNextPage": False}}}}}
        }

        class Clock:
            now = 0.0

            def monotonic(self):
                return self.now

        clock = Clock()
        timeouts = []

        def gh_call(args, *, timeout, **_kwargs):
            timeouts.append(timeout)
            clock.now += 25 if len(timeouts) == 1 else 15
            payload = initial if len(timeouts) == 1 else next_page
            return subprocess.CompletedProcess(args, 0, json.dumps(payload), "")

        with (
            patch.object(github.time, "monotonic", side_effect=clock.monotonic),
            github.activate_hosted_preflight_budget(timeout_seconds=45),
            patch.object(github.subprocess, "run", side_effect=gh_call),
        ):
            github.fetch_pull_request("owner/repo", 42)

        self.assertEqual(timeouts, [30, 20])

    def test_pull_request_pagination_stops_when_shared_budget_expires(self):
        initial = {
            "data": {
                "repository": {
                    "pullRequest": {
                        "headRefOid": "a" * 40,
                        "commits": {"nodes": []},
                        "reviewThreads": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                        "comments": {
                            "nodes": [],
                            "pageInfo": {"hasNextPage": True, "endCursor": "comments-1"},
                        },
                        "reviews": {"nodes": [], "pageInfo": {"hasNextPage": False}},
                    }
                }
            }
        }

        class Clock:
            now = 0.0

            def monotonic(self):
                return self.now

        clock = Clock()
        calls = []

        def gh_call(args, **_kwargs):
            calls.append(args)
            clock.now = 21
            return subprocess.CompletedProcess(args, 0, json.dumps(initial), "")

        with (
            patch.object(github.time, "monotonic", side_effect=clock.monotonic),
            github.activate_hosted_preflight_budget(timeout_seconds=20),
            patch.object(github.subprocess, "run", side_effect=gh_call),
            self.assertRaisesRegex(github.HostedPreflightDeadlineExceeded, "phase=starting"),
        ):
            github.fetch_pull_request("owner/repo", 42)

        self.assertEqual(len(calls), 1)

    def test_branch_head_read_uses_remaining_hosted_preflight_budget(self):
        class Clock:
            now = 0.0

            def monotonic(self):
                return self.now

        clock = Clock()
        timeouts = []

        def gh_call(args, *, timeout, **_kwargs):
            timeouts.append(timeout)
            clock.now = 11
            return subprocess.CompletedProcess(args, 0, "a" * 40, "")

        with (
            patch.object(github.time, "monotonic", side_effect=clock.monotonic),
            github.activate_hosted_preflight_budget(timeout_seconds=10),
            patch.object(github.subprocess, "run", side_effect=gh_call),
            self.assertRaisesRegex(github.HostedPreflightDeadlineExceeded, "phase=starting"),
        ):
            github.branch_head("owner/repo", "develop")

        self.assertEqual(timeouts, [10])


if __name__ == "__main__":
    unittest.main()
