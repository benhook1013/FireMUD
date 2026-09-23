#!/usr/bin/env python3
"""Focused proof that GitHub GraphQL pagination fails closed on cursor loops."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import github


class GithubPaginationTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
