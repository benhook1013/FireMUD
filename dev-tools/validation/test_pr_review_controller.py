#!/usr/bin/env python3
"""Hermetic contracts for the unified review controller integration layer."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review.controller import (
    ControllerError,
    LivePullRequest,
    ReviewController,
    WrongStackTarget,
    compact_result,
    json_result,
)
from pr_review.policy import Evidence
from pr_review.state import StateStore

BASE = "a" * 40
PARENT = "b" * 40
HEAD_1 = "c" * 40
HEAD_2 = "d" * 40
MERGE_1 = "e" * 40
MERGE_2 = "f" * 40


class FakeGit:
    def __init__(self, heads=None):
        self.heads = {"develop": BASE, **(heads or {})}

    def branch_head(self, ref_name):
        return self.heads[ref_name]

    def branch_exists(self, ref_name):
        return ref_name in self.heads

    def is_ancestor(self, ancestor, descendant):
        return True

    def merge_base(self, left, right):
        return BASE

    def patch_identity(self, merge_base, head):
        return f"patch-{head[:4]}"


class FakeGitHub:
    def __init__(self, values):
        self.values = values

    def pull_request(self, number):
        return self.values[number]


def pr(number, head, base_ref="develop", base_tip=BASE, *, merged=False):
    return LivePullRequest(number, head, base_ref, base_tip, f"feature-{number}", merged=merged)


class ControllerTests(unittest.TestCase):
    def make(self, values, evidence=None, *, heads=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return ReviewController(
            store=StateStore(Path(directory.name) / "state.json"),
            github=FakeGitHub(values),
            git=FakeGit(heads),
            evidence=evidence or {},
        )

    def test_one_private_ordered_stack_and_effective_parent(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)}
        controller = self.make(values, heads={"feature-1": HEAD_1})
        self.assertEqual(controller.set_stack([1, 2])["ordered_prs"], [1, 2])
        result = controller.status()
        self.assertEqual(result["prs"][1]["parent"], "1")
        self.assertEqual(controller.show_stack()["ordered_prs"], [1, 2])

    def test_parent_move_marks_descendants_and_blocks_target(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)}
        controller = self.make(values, heads={"feature-1": "1" * 40})
        controller.set_stack([1, 2])
        result = controller.status()
        self.assertEqual(result["prs"][1]["reconciliation"], "PARENT_MOVED")
        with self.assertRaises(ControllerError):
            controller.resolve_hosted_target()

    def test_wrong_target_expectation_is_checked_before_adapter(self):
        values = {1: pr(1, HEAD_1)}
        controller = self.make(values)
        controller.set_stack([1])
        with self.assertRaises(WrongStackTarget):
            controller.resolve_cli_target(expected_pr=2)

    def test_hosted_rate_limit_and_cross_channel_head_change_do_not_advance(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2)}
        evidence = {
            (1, "hosted"): [
                Evidence(1, HEAD_1, "h1", completed=True, attributable=True, corrected_state=True, rate_limited=True)
            ],
            (1, "cli"): [Evidence(1, "9" * 40, "c1", completed=True, attributable=True) for _ in range(3)],
        }
        controller = self.make(values, evidence)
        controller.set_stack([1, 2])
        with self.assertRaises(ControllerError):
            controller.resolve_hosted_target()

    def test_exact_bound_judgment_allows_cross_channel_history(self):
        values = {1: pr(1, HEAD_1)}
        history = [Evidence(1, HEAD_1, f"c{i}", completed=True, attributable=True) for i in range(3)]
        evidence = {
            (1, "cli"): history,
            (1, "hosted"): [Evidence(1, "9" * 40, "h", completed=True, attributable=True, corrected_state=True)],
        }
        controller = self.make(values, evidence)
        controller.set_stack([1])
        with self.assertRaises(ControllerError):
            controller.resolve_cli_target()
        controller.decide_judgment(
            pr=1, channel="cli", decision="retain", head=HEAD_1, checkpoint="c2", reason="same reviewed candidate"
        )
        with self.assertRaises(ControllerError):
            controller.resolve_cli_target()

    def test_provisional_discovery_is_one_exact_pass_and_never_tapers(self):
        values = {1: pr(1, HEAD_1, base_tip="9" * 40)}
        evidence = {(1, "cli"): [Evidence(1, HEAD_1, "p1", completed=True, attributable=True, provisional=True)]}
        controller = self.make(values, evidence)
        controller.set_stack([1])
        # The exact reason is checked by the controller before a runner is invoked.
        selected = controller._target("cli")
        evidence[(1, "cli")].append(
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "p2",
                "completed": True,
                "attributable": True,
                "provisional": True,
                "parent_head": selected.anchor.parent_head,
                "reason": "one",
            }
        )
        with self.assertRaises(ControllerError):
            controller.run_cli(allow_unreconciled=True, reason="one")

    def test_compact_and_json_results_are_structured(self):
        value = {"status": "READY", "pr": 1, "anchor": {"head": HEAD_1}}
        self.assertIn("status=READY", compact_result(value))
        self.assertEqual(json.loads(json.dumps(json_result(value))), value)

    def test_patch_change_reopens_review_without_becoming_unreconciled(self):
        values = {1: pr(1, HEAD_1)}
        evidence = {
            (1, "cli"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": f"c{index}",
                    "completed": True,
                    "attributable": True,
                    "accepted": 0,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": "old-patch",
                }
                for index in range(3)
            ]
        }
        controller = self.make(values, evidence)
        controller.set_stack([1])
        target = controller.resolve_cli_target()
        self.assertTrue(target.reconciled)
        self.assertTrue(target.ancestor_links_valid)


if __name__ == "__main__":
    unittest.main()
