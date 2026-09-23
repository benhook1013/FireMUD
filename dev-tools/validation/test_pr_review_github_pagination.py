#!/usr/bin/env python3
"""Focused proof that GitHub GraphQL pagination fails closed on cursor loops."""

from __future__ import annotations

import json
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


if __name__ == "__main__":
    unittest.main()
