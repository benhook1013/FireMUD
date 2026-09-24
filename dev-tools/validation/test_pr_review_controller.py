#!/usr/bin/env python3
"""Hermetic contracts for the unified review controller integration layer."""

from __future__ import annotations

import dataclasses
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli_runner
from pr_review.cli import _parser
from pr_review.controller import (
    ControllerError,
    DefaultGitProvider,
    LivePullRequest,
    PullRequestSnapshot,
    ReviewController,
    WrongStackTarget,
    compact_result,
    json_result,
)
from pr_review.patch_identity import patch_diff_args
from pr_review.policy import Channel, Evidence, taper_satisfied
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
        self.remote_heads_calls = 0

    def remote_heads(self):
        self.remote_heads_calls += 1
        return dict(self.heads)

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


def pr(number, head, base_ref="develop", base_tip=BASE, *, merged=False, head_repository="owner/repo"):
    return LivePullRequest(
        number,
        head,
        base_ref,
        base_tip,
        f"feature-{number}",
        merged=merged,
        head_repository=head_repository,
    )


class ControllerTests(unittest.TestCase):
    def make(self, values, evidence=None, *, heads=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        return ReviewController(
            store=StateStore(Path(directory.name) / "state.json"),
            github=FakeGitHub(values),
            git=FakeGit(heads),
            evidence=evidence or {},
            repository="owner/repo",
        )

    def test_default_git_provider_translates_timeout_expired(self):
        provider = DefaultGitProvider(timeout_seconds=7)
        with patch(
            "pr_review.controller.subprocess.run",
            side_effect=subprocess.TimeoutExpired(["git"], 7),
        ) as run, self.assertRaisesRegex(ControllerError, "git command timed out after 7 seconds"):
            provider.branch_exists("develop")
        self.assertEqual(run.call_args.kwargs["timeout"], 7)

    def test_default_git_provider_hashes_raw_diff_bytes(self):
        raw_diff = b"diff --git a/file b/file\n\xff\x80\x00\n"
        results = [
            CompletedProcess(["git"], 0, b"", b""),
            CompletedProcess(["git"], 0, b"", b""),
            CompletedProcess(["git"], 0, raw_diff, b""),
        ]
        with patch("pr_review.controller.subprocess.run", side_effect=results) as run:
            actual = DefaultGitProvider(timeout_seconds=9).patch_identity("a" * 40, "b" * 40)
        self.assertEqual(actual, hashlib.sha256(raw_diff).hexdigest())
        self.assertFalse(run.call_args.kwargs["text"])
        self.assertEqual(run.call_args.kwargs["timeout"], 9)
        self.assertEqual(
            run.call_args.args[0],
            ["git", "-C", str(DefaultGitProvider().root), *patch_diff_args("a" * 40, "b" * 40)],
        )

    def test_default_git_provider_rejects_ambiguous_remote_head_snapshot(self):
        with patch(
            "pr_review.controller.subprocess.run",
            return_value=CompletedProcess(
                ["git"],
                0,
                f"{HEAD_1} refs/heads/develop\nmalformed\n",
                "",
            ),
        ), self.assertRaisesRegex(ControllerError, "remote branch snapshot line 2 is malformed"):
            DefaultGitProvider().remote_heads()

    def test_reconciliation_uses_one_remote_head_snapshot(self):
        controller = self.make({1: pr(1, HEAD_1)})
        controller.set_stack([1])
        controller.status()
        self.assertEqual(controller.git.remote_heads_calls, 1)

    def test_one_private_ordered_stack_and_effective_parent(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)}
        controller = self.make(values, heads={"feature-1": HEAD_1})
        self.assertEqual(controller.set_stack([1, 2])["ordered_prs"], [1, 2])
        result = controller.status()
        self.assertEqual(result["prs"][1]["parent"], "1")
        self.assertEqual(controller.show_stack()["ordered_prs"], [1, 2])

    def test_stack_set_rejects_unknown_or_cross_repository_heads_before_writing(self):
        for identity in (None, "fork/repo"):
            with self.subTest(head_repository=identity):
                controller = self.make({1: pr(1, HEAD_1, head_repository=identity)})
                with self.assertRaisesRegex(ControllerError, "head repository|cross-repository"):
                    controller.set_stack([1])
                self.assertEqual(controller.show_stack()["ordered_prs"], [])

    def test_persisted_cross_repository_stack_cannot_select_a_review_target(self):
        controller = self.make({1: pr(1, HEAD_1, head_repository="fork/repo")})
        controller.store.update(lambda current: dataclasses.replace(current, ordered_prs=(1,)))

        with self.assertRaisesRegex(ControllerError, "unsupported cross-repository head"):
            controller.resolve_cli_target()

    def test_unsupported_parent_and_descendant_remain_unreconciled_after_second_pass(self):
        values = {
            1: pr(1, HEAD_1, head_repository="fork/repo"),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
        }
        controller = self.make(values, heads={"feature-1": HEAD_1, "feature-2": HEAD_2})
        controller.store.update(lambda current: dataclasses.replace(current, ordered_prs=(1, 2)))

        result = controller.status()

        for index, expected_reason in enumerate(
            (
                "uses unsupported cross-repository head 'fork/repo'",
                "effective parent PR #1 has an unsupported head repository",
            )
        ):
            self.assertEqual(result["prs"][index]["reconciliation"], "UNRECONCILED")
            self.assertEqual(result["prs"][index]["channels"]["hosted"], "UNRECONCILED")
            self.assertEqual(result["prs"][index]["channels"]["cli"], "UNRECONCILED")
            self.assertIn(expected_reason, result["prs"][index]["reason"])
        with self.assertRaisesRegex(ControllerError, "unsupported cross-repository head"):
            controller.resolve_cli_target()

    def test_parent_move_marks_descendants_and_blocks_target(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)}
        controller = self.make(values, heads={"feature-1": "1" * 40})
        controller.set_stack([1, 2])
        result = controller.status()
        self.assertEqual(result["prs"][0]["reconciliation"], "UNRECONCILED")
        self.assertIn("source branch tip differs from the live PR head", result["prs"][0]["reason"])
        self.assertEqual(result["prs"][1]["reconciliation"], "PARENT_MOVED")
        with self.assertRaises(ControllerError):
            controller.resolve_hosted_target()

    def test_wrong_target_expectation_is_checked_before_adapter(self):
        values = {1: pr(1, HEAD_1)}
        controller = self.make(values)
        controller.set_stack([1])
        with self.assertRaises(WrongStackTarget):
            controller.resolve_cli_target(expected_pr=2)

    def test_run_cli_requires_and_uses_the_configured_adapter(self):
        controller = self.make({1: pr(1, HEAD_1)})
        controller.set_stack([1])

        with patch.object(cli_runner, "run_cli_review") as lower_level_runner:
            with self.assertRaisesRegex(ControllerError, "CLI adapter is not configured"):
                controller.run_cli()
            lower_level_runner.assert_not_called()

            controller.cli_adapter = lambda target, **kwargs: (target, kwargs)
            target, options = controller.run_cli()

        self.assertEqual(target.snapshot.number, 1)
        self.assertEqual(options, {"allow_unreconciled": False, "reason": None})

    def test_pull_request_snapshot_number_must_match_requested_pr(self):
        values = {
            1: PullRequestSnapshot(
                2,
                "OPEN",
                "develop",
                BASE,
                HEAD_1,
                head_ref_name="feature-2",
                changed_files=1,
            )
        }
        controller = self.make(values)
        controller.store.update(lambda current: dataclasses.replace(current, ordered_prs=(1,)))
        with self.assertRaisesRegex(ControllerError, "provider returned the wrong pull request"):
            controller.status()

    def test_hosted_rate_limit_and_cross_channel_head_change_do_not_advance(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2)}
        evidence = {
            (1, "hosted"): [
                Evidence(
                    1,
                    HEAD_1,
                    "h1",
                    anchored=True,
                    completed=True,
                    attributable=True,
                    corrected_state=True,
                    rate_limited=True,
                )
            ],
            (1, "cli"): [
                Evidence(1, "9" * 40, "c1", anchored=True, completed=True, attributable=True) for _ in range(3)
            ],
        }
        controller = self.make(values, evidence)
        controller.set_stack([1, 2])
        with self.assertRaises(ControllerError):
            controller.resolve_hosted_target()

    def test_exact_bound_judgment_allows_cross_channel_history(self):
        values = {1: pr(1, HEAD_1)}
        history = [
            Evidence(
                1,
                HEAD_1,
                f"c{i}",
                patch_id=f"patch-{HEAD_1[:4]}",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            )
            for i in range(3)
        ]
        evidence = {
            (1, "cli"): history,
            (1, "hosted"): [
                Evidence(1, "9" * 40, "h", anchored=True, completed=True, attributable=True, corrected_state=True)
            ],
        }
        controller = self.make(values, evidence)
        controller.set_stack([1])
        self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "JUDGMENT_REQUIRED")
        with self.assertRaisesRegex(ControllerError, "cli review cannot run: JUDGMENT_REQUIRED"):
            controller.resolve_cli_target()
        controller.decide_judgment(
            pr=1,
            channel="cli",
            decision="retain",
            head=HEAD_1,
            checkpoint="c2",
            reason="same reviewed candidate",
        )
        self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "COMPLETE")
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
            controller.resolve_cli_target()

    def test_equivalent_history_judgments_bind_prior_head_and_current_patch(self):
        live_head = HEAD_2
        patch_id = f"patch-{live_head[:4]}"
        cases = (
            ("hosted", "retain", 2, "COMPLETE"),
            ("cli", "reopen", 3, "READY"),
            ("hosted", "reopen", 2, "READY"),
            ("cli", "retain", 3, "COMPLETE"),
        )
        for channel, decision, count, expected_status in cases:
            with self.subTest(channel=channel, decision=decision):
                history = [
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": f"{channel}-{index}",
                        "completed": True,
                        "attributable": True,
                        "anchored": True,
                        # Equivalent-history retention is an explicit judgment
                        # over the old reviewed head; it must not pretend that
                        # that historical evidence was corrected on the new
                        # head.
                        "corrected_state": False,
                        "accepted": 0,
                        "child_head": HEAD_1,
                        "parent_identity": "develop",
                        "parent_head": BASE,
                        "merge_base": BASE,
                        "patch_id": patch_id,
                    }
                    for index in range(count)
                ]
                controller = self.make({1: pr(1, live_head)}, {(1, channel): history})
                controller.set_stack([1])
                checkpoint = f"{channel}-{count - 1}"

                self.assertEqual(
                    controller.status()["prs"][0]["channels"][channel],
                    "JUDGMENT_REQUIRED",
                )
                result = controller.decide_judgment(
                    pr=1,
                    channel=channel,
                    decision=decision,
                    head=live_head,
                    checkpoint=checkpoint,
                    reason="same reviewed patch after equivalent history",
                )

                self.assertEqual(result["decision"]["head"], HEAD_1)
                self.assertEqual(result["decision"]["patch_id"], patch_id)
                self.assertEqual(controller.status()["prs"][0]["channels"][channel], expected_status)

    def test_equivalent_history_judgment_rejects_wrong_patch_wrong_live_head_and_policy_override(self):
        live_head = HEAD_2
        current_patch = f"patch-{live_head[:4]}"
        prior_evidence = {
            "pr": 1,
            "head": HEAD_1,
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 0,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": current_patch,
        }
        history = [
            dict(prior_evidence, checkpoint="wrong-patch", patch_id="different-patch"),
            dict(prior_evidence, checkpoint="latest"),
        ]
        controller = self.make({1: pr(1, live_head)}, {(1, "hosted"): history})
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "latest policy-effective"):
            controller.decide_judgment(
                pr=1,
                channel="hosted",
                decision="retain",
                head=live_head,
                checkpoint="wrong-patch",
                reason="wrong patch must not retain review",
            )
        with self.assertRaisesRegex(ControllerError, "decision head does not match the live"):
            controller.decide_judgment(
                pr=1,
                channel="hosted",
                decision="retain",
                head=HEAD_1,
                checkpoint="latest",
                reason="caller head must be current",
            )
        with self.assertRaisesRegex(ControllerError, "decision checkpoint head must match the live"):
            controller.decide_policy(
                pr=1,
                head=live_head,
                checkpoint="latest",
                hosted_zero_useful=1,
                reason="policy overrides cannot bind prior-head evidence",
            )

    def test_decision_checkpoint_must_be_latest_policy_effective_review(self):
        patch_id = f"patch-{HEAD_1[:4]}"
        history = [
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "older",
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "patch_id": patch_id,
            },
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "latest",
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "patch_id": patch_id,
            },
        ]
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): history})
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "latest policy-effective"):
            controller.decide_policy(
                pr=1,
                head=HEAD_1,
                checkpoint="older",
                hosted_zero_useful=1,
                reason="stale checkpoint must not authorize a policy override",
            )

        result = controller.decide_policy(
            pr=1,
            head=HEAD_1,
            checkpoint="latest",
            hosted_zero_useful=1,
            reason="latest checkpoint is policy-effective",
        )
        self.assertEqual(result["policy_override"]["checkpoint"], "latest")

    def test_equivalent_history_judgment_rejects_moved_topology(self):
        live_head = HEAD_2
        history = [
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "prior",
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "child_head": HEAD_1,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{live_head[:4]}",
            }
        ]
        controller = self.make({1: pr(1, live_head, base_tip="9" * 40)}, {(1, "hosted"): history})
        controller.set_stack([1])

        self.assertEqual(controller.status()["prs"][0]["reconciliation"], "PARENT_MOVED")
        with self.assertRaisesRegex(ControllerError, "requires coherent live topology"):
            controller.decide_judgment(
                pr=1,
                channel="hosted",
                decision="retain",
                head=live_head,
                checkpoint="prior",
                reason="moved topology must not retain history",
            )

    def test_policy_override_requires_current_corrected_zero_useful_patch_bound_checkpoint(self):
        values = {1: pr(1, HEAD_1)}
        evidence = {
            (1, "hosted"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "hosted-close",
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 0,
                    "patch_id": f"patch-{HEAD_1[:4]}",
                }
            ]
        }
        controller = self.make(values, evidence)
        controller.set_stack([1])
        result = controller.decide_policy(
            pr=1,
            head=HEAD_1.upper(),
            checkpoint="hosted-close",
            hosted_zero_useful=1,
            reason="narrow corrected-state close-out",
        )
        self.assertEqual(result["policy_override"]["patch_id"], f"patch-{HEAD_1[:4]}")
        self.assertEqual(result["policy_override"]["head"], HEAD_1)
        self.assertEqual(controller.status()["prs"][0]["channels"]["hosted"], "COMPLETE")

        with self.assertRaises(ControllerError):
            controller.decide_policy(
                pr=1,
                head=HEAD_1,
                checkpoint="hosted-close",
                hosted_zero_useful=0,
                reason="must not bypass taper",
            )

    def test_policy_override_rejects_accepted_or_stale_checkpoint(self):
        values = {1: pr(1, HEAD_1)}
        accepted = {
            (1, "hosted"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "accepted",
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 1,
                    "patch_id": f"patch-{HEAD_1[:4]}",
                }
            ]
        }
        controller = self.make(values, accepted)
        controller.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "zero-useful"):
            controller.decide_policy(
                pr=1, head=HEAD_1, checkpoint="accepted", hosted_zero_useful=1, reason="accepted is not dry"
            )

        stale = {
            (1, "hosted"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "stale",
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 0,
                    "patch_id": "different-patch",
                }
            ]
        }
        controller = self.make(values, stale)
        controller.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "patch identity"):
            controller.decide_policy(
                pr=1, head=HEAD_1, checkpoint="stale", hosted_zero_useful=1, reason="stale patch"
            )
    def test_provisional_discovery_is_one_exact_pass_and_never_tapers(self):
        values = {1: pr(1, HEAD_1, "unrelated-base", "9" * 40)}
        evidence = {
            (1, "cli"): [
                Evidence(1, HEAD_1, "p1", anchored=True, completed=True, attributable=True, provisional=True)
            ]
        }
        controller = self.make(values, evidence)
        controller.set_stack([1])
        # The exact reason is checked by the controller before a runner is invoked.
        selected = controller._target("cli")
        self.assertEqual(selected.status.value, "UNRECONCILED")
        evidence[(1, "cli")].append(
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "p2",
                "completed": True,
                "anchored": True,
                "attributable": True,
                "provisional": True,
                "parent_head": selected.anchor.parent_head,
                "reason": "one",
            }
        )
        with self.assertRaisesRegex(
            ControllerError,
            "one provisional CLI discovery is already recorded for this exact child/parent identity",
        ):
            controller.run_cli(allow_unreconciled=True, reason="one")

    def test_reconciled_provisional_history_allows_target_without_tapering(self):
        completed = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "completed",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 0,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{HEAD_1[:4]}",
        }
        provisional = dict(
            completed,
            checkpoint="provisional",
            anchored=False,
            corrected_state=False,
            provisional=True,
        )
        history = [completed, provisional]
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "cli"): history})
        controller.set_stack([1])

        result = controller.status()

        self.assertEqual(result["prs"][0]["reconciliation"], "COHERENT")
        self.assertEqual(result["prs"][0]["channels"]["cli"], "READY")
        self.assertEqual(controller.resolve_cli_target().snapshot.head_sha, HEAD_1)
        self.assertFalse(taper_satisfied(Channel.CLI, history, required=2))

    def test_provisional_discovery_allows_parent_moved_child_with_reason(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
        }
        parent_history = [
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": f"parent-{index}",
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "child_head": HEAD_1,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{HEAD_1[:4]}",
            }
            for index in range(3)
        ]
        evidence = {
            (1, "cli"): parent_history,
            (2, "cli"): [
                {
                    "pr": 2,
                    "head": HEAD_2,
                    "checkpoint": "child-before-parent-move",
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 0,
                    "child_head": HEAD_2,
                    "parent_identity": "1",
                    "parent_head": PARENT,
                    "merge_base": BASE,
                    "patch_id": f"patch-{HEAD_2[:4]}",
                }
            ],
        }
        controller = self.make(
            values,
            evidence,
            heads={"feature-1": HEAD_1, "feature-2": HEAD_2},
        )
        controller.set_stack([1, 2])
        self.assertEqual(controller.status()["prs"][1]["reconciliation"], "PARENT_MOVED")
        controller.cli_adapter = lambda target, **kwargs: (target, kwargs)

        target, options = controller.run_cli(
            expected_pr=2,
            allow_unreconciled=True,
            reason="one provisional pass before parent reconciliation",
        )

        self.assertEqual(target.snapshot.number, 2)
        self.assertEqual(
            options,
            {
                "allow_unreconciled": True,
                "reason": "one provisional pass before parent reconciliation",
            },
        )
        with self.assertRaisesRegex(ControllerError, "provisional CLI requires an unreconciled target and a reason"):
            controller.run_cli(expected_pr=2, allow_unreconciled=True)

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
                    "anchored": True,
                    "attributable": True,
                    "corrected_state": True,
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

    def test_anchorless_historical_evidence_is_readable_without_false_parent_movement(self):
        values = {1: pr(1, HEAD_1)}
        evidence = {
            (1, "cli"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "legacy",
                    "completed": True,
                    "attributable": True,
                    "anchored": False,
                    "accepted": 0,
                    "parent_head": "",
                }
            ]
        }
        controller = self.make(values, evidence)
        controller.set_stack([1])
        result = controller.status()
        self.assertEqual(result["prs"][0]["reconciliation"], "COHERENT")
        self.assertEqual(result["prs"][0]["channels"]["cli"], "READY")

    def test_legacy_transition_reopens_both_channels_without_counting_or_erasing_history(self):
        old_parent = "1" * 40
        old_head = "7" * 40
        legacy_cli = {
            "pr": 1,
            "head": old_head,
            "checkpoint": "legacy-cli",
            "completed": True,
            "attributable": True,
            "anchored": False,
            "corrected_state": True,
            "accepted": 3,
            "raw": 4,
            "child_head": old_head,
            "parent_identity": "develop",
            "parent_head": old_parent,
            "patch_id": "",
        }
        legacy_hosted = {
            "pr": 1,
            "head": old_head,
            "checkpoint": "trigger-uncheckpointed:42",
            "held": True,
        }
        evidence = {(1, "cli"): [legacy_cli], (1, "hosted"): [legacy_hosted]}
        controller = self.make({1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])
        self.assertEqual(controller.status()["prs"][0]["reconciliation"], "PARENT_MOVED")

        result = controller.decide_legacy_transition(
            pr=1,
            head=HEAD_1,
            reason="legacy review records lack modern linked anchors",
        )

        self.assertTrue(result["recorded"])
        self.assertEqual(controller.status()["prs"][0]["reconciliation"], "COHERENT")
        self.assertEqual(controller.status()["prs"][0]["channels"], {"hosted": "READY", "cli": "READY"})
        controller.hosted_adapter = lambda target, **kwargs: (target, kwargs)
        controller.cli_adapter = lambda target, **kwargs: (target, kwargs)
        self.assertEqual(controller.run_hosted(expected_pr=1)[0].snapshot.head_sha, HEAD_1)
        self.assertEqual(controller.run_cli(expected_pr=1)[0].snapshot.head_sha, HEAD_1)
        self.assertEqual(controller.evidence(1)["1"]["cli"][0]["checkpoint"], "legacy-cli")
        self.assertEqual(controller.evidence(1)["1"]["hosted"][0]["checkpoint"], "trigger-uncheckpointed:42")
        evidence[(1, "cli")].append(
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "new-modern-but-moved",
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "child_head": HEAD_1,
                "parent_identity": "develop",
                "parent_head": old_parent,
                "merge_base": BASE,
                "patch_id": f"patch-{HEAD_1[:4]}",
            }
        )
        self.assertEqual(controller.status()["prs"][0]["reconciliation"], "PARENT_MOVED")

    def test_legacy_transition_fails_closed_for_active_actionable_or_spoofed_records(self):
        scenarios = {
            "active reservation": {
                "pr": 1,
                "head": "7" * 40,
                "checkpoint": "trigger:42",
                "held": True,
            },
            "actionable thread": {
                "pr": 1,
                "head": "7" * 40,
                "checkpoint": "review-threads:1:0",
                "held": True,
            },
            "spoofed patch": {
                "pr": 1,
                "head": "7" * 40,
                "checkpoint": "legacy-cli",
                "completed": True,
                "attributable": True,
                "anchored": False,
                "patch_id": "spoofed",
            },
            "spoofed projection": {
                "pr": 1,
                "head": "7" * 40,
                "checkpoint": "legacy-cli",
                "completed": True,
                "attributable": True,
                "anchored": False,
                "non_counting": True,
            },
            "same-head completed legacy evidence": {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "legacy-same-head",
                "completed": True,
                "attributable": True,
                "anchored": False,
            },
            "malformed old head": {
                "pr": 1,
                "head": "not-a-sha",
                "checkpoint": "legacy-malformed-head",
                "completed": True,
                "attributable": True,
                "anchored": False,
            },
            "malformed held marker": {
                "pr": 1,
                "head": "7" * 40,
                "checkpoint": "trigger-uncheckpointed:not-numeric",
                "held": True,
            },
            "ambiguous held marker": {
                "pr": 1,
                "head": "7" * 40,
                "checkpoint": "trigger-uncheckpointed:01",
                "held": True,
            },
        }
        for name, record in scenarios.items():
            with self.subTest(name=name):
                controller = self.make(
                    {1: pr(1, HEAD_1)}, {(1, "cli"): [record]}, heads={"feature-1": HEAD_1}
                )
                controller.set_stack([1])
                with self.assertRaisesRegex(ControllerError, "legacy transition"):
                    controller.decide_legacy_transition(
                        pr=1,
                        head=HEAD_1,
                        reason="must not dismiss unsafe legacy state",
                    )

    def test_legacy_transition_reauthorization_keeps_newer_review_counting_after_head_advance(self):
        old_head = "7" * 40
        old_parent = "1" * 40
        legacy_cli = {
            "pr": 1,
            "head": old_head,
            "checkpoint": "legacy-cli",
            "completed": True,
            "attributable": True,
            "anchored": False,
            "corrected_state": True,
            "accepted": 3,
            "raw": 4,
            "child_head": old_head,
            "parent_head": old_parent,
            "patch_id": "",
        }
        legacy_hosted = {
            "pr": 1,
            "head": old_head,
            "checkpoint": "trigger-uncheckpointed:42",
            "held": True,
        }
        evidence = {(1, "cli"): [legacy_cli], (1, "hosted"): [legacy_hosted]}
        values = {1: pr(1, HEAD_1)}
        controller = self.make(values, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])
        controller.decide_legacy_transition(
            pr=1,
            head=HEAD_1,
            reason="retire the unanchored legacy observations",
        )

        modern_hosted = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "modern-hosted",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 1,
            "raw": 1,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{HEAD_1[:4]}",
        }
        evidence[(1, "hosted")].append(modern_hosted)
        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2

        self.assertEqual(controller.status()["prs"][0]["channels"]["hosted"], "HELD")
        with self.assertRaisesRegex(ControllerError, "explicit reauthorization"):
            controller.decide_legacy_transition(
                pr=1,
                head=HEAD_2,
                reason="move legacy retirement to the fixed head",
            )

        active_new_reservation = {
            "pr": 1,
            "head": HEAD_2,
            "checkpoint": "trigger:99",
            "held": True,
        }
        evidence[(1, "hosted")].append(active_new_reservation)
        with self.assertRaisesRegex(ControllerError, "active or ambiguous"):
            controller.decide_legacy_transition(
                pr=1,
                head=HEAD_2,
                reason="must not dismiss a newer active reservation",
                reauthorize=True,
            )
        evidence[(1, "hosted")].pop()

        result = controller.decide_legacy_transition(
            pr=1,
            head=HEAD_2,
            reason="move the same immutable legacy retirement to the fixed head",
            reauthorize=True,
        )
        self.assertTrue(result["recorded"])
        status = controller.status()["prs"][0]
        self.assertEqual(status["reconciliation"], "COHERENT")
        self.assertEqual(status["channels"], {"hosted": "READY", "cli": "READY"})
        self.assertEqual(len(controller._state().legacy_transitions), 2)
        self.assertEqual(controller.evidence(1)["1"]["hosted"][1]["checkpoint"], "modern-hosted")

    def test_legacy_transition_requires_exact_coherent_current_topology(self):
        legacy = {
            "pr": 1,
            "head": "7" * 40,
            "checkpoint": "legacy-cli",
            "completed": True,
            "attributable": True,
            "anchored": False,
        }
        controller = self.make(
            {1: pr(1, HEAD_1, base_tip="9" * 40)},
            {(1, "cli"): [legacy]},
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "coherent exact-current live topology"):
            controller.decide_legacy_transition(
                pr=1,
                head=HEAD_1,
                reason="moved topology must remain blocked",
            )

    def test_reconciliation_uses_latest_completed_attributable_review_anchor(self):
        review = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "review",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{HEAD_1[:4]}",
        }
        trailing_status = {
            "pr": 1,
            "head": "9" * 40,
            "checkpoint": "pending-capture:run.old",
            "completed": False,
            "attributable": False,
            "parent_head": "8" * 40,
        }
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): [review, trailing_status]})
        controller.set_stack([1])
        result = controller.status()
        self.assertEqual(result["prs"][0]["reconciliation"], "COHERENT")

    def test_reconciliation_ignores_provisional_and_correction_anchors(self):
        prior = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "prior",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{HEAD_1[:4]}",
        }
        newer_provisional = dict(prior, checkpoint="provisional", provisional=True, parent_head="9" * 40)
        newer_correction = dict(
            prior,
            checkpoint="correction",
            correction=True,
            parent_head="9" * 40,
        )

        for newer in (newer_provisional, newer_correction):
            with self.subTest(checkpoint=newer["checkpoint"]):
                controller = self.make(
                    {1: pr(1, HEAD_1, base_tip="9" * 40)},
                    {(1, "hosted"): [prior, newer]},
                )
                controller.set_stack([1])
                self.assertEqual(controller.status()["prs"][0]["reconciliation"], "PARENT_MOVED")

    def test_exact_stack_reconciliation_reopens_review_and_unblocks_descendants(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
            3: pr(3, "7" * 40, "feature-2", HEAD_2),
        }
        evidence = {
            (1, "cli"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": f"parent-cli-{index}",
                    "completed": True,
                    "anchored": True,
                    "attributable": True,
                    "corrected_state": True,
                    "accepted": 0,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": f"patch-{HEAD_1[:4]}",
                }
                for index in range(3)
            ],
            (2, "cli"): [
                {
                    "pr": 2,
                    "head": "9" * 40,
                    "checkpoint": "cli-old-parent",
                    "completed": True,
                    "anchored": True,
                    "attributable": True,
                    "child_head": "9" * 40,
                    "parent_identity": "1",
                    "parent_head": PARENT,
                    "merge_base": BASE,
                    "patch_id": "old-patch",
                }
            ],
        }
        controller = self.make(
            values,
            evidence,
            heads={"feature-1": HEAD_1, "feature-2": HEAD_2, "feature-3": "7" * 40},
        )
        controller.set_stack([1, 2, 3])
        before = controller.status()
        self.assertEqual(before["prs"][1]["reconciliation"], "PARENT_MOVED")
        self.assertEqual(before["prs"][2]["reconciliation"], "PARENT_MOVED")

        decision = controller.decide_reconciliation(
            pr=2,
            channel="cli",
            checkpoint="cli-old-parent",
            prior_head="9" * 40,
            reason="rebased onto the current parent and reopen review",
        )
        self.assertEqual(decision["decision"]["child_head"], HEAD_2)
        after = controller.status()
        self.assertEqual(after["prs"][1]["reconciliation"], "COHERENT")
        self.assertEqual(after["prs"][1]["channels"]["hosted"], "MISSING_EVIDENCE")
        self.assertEqual(after["prs"][1]["channels"]["cli"], "READY")
        self.assertEqual(after["prs"][2]["reconciliation"], "COHERENT")
        self.assertEqual(controller.resolve_cli_target(expected_pr=2).snapshot.number, 2)

    def test_stale_cli_patch_evidence_does_not_block_hosted_completion(self):
        live_head = HEAD_2
        current_hosted = {
            "pr": 1,
            "head": live_head,
            "checkpoint": "hosted",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 0,
            "child_head": live_head,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{live_head[:4]}",
        }
        stale_cli = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "cli-old",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "accepted": 0,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{HEAD_1[:4]}",
        }
        controller = self.make(
            {1: pr(1, live_head)},
            {
                (1, "hosted"): [dict(current_hosted, checkpoint="h1"), dict(current_hosted, checkpoint="h2")],
                (1, "cli"): [stale_cli],
            },
        )
        controller.set_stack([1])

        result = controller.status()["prs"][0]

        self.assertEqual(result["reconciliation"], "COHERENT")
        self.assertEqual(result["channels"]["hosted"], "COMPLETE")
        self.assertEqual(result["channels"]["cli"], "READY")
        with self.assertRaisesRegex(ControllerError, "all hosted targets are complete"):
            controller.resolve_hosted_target(expected_pr=1)

    def test_equivalent_history_is_classified_per_channel(self):
        live_head = HEAD_2
        hosted = {
            "pr": 1,
            "head": live_head,
            "checkpoint": "hosted",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 0,
            "child_head": live_head,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{live_head[:4]}",
        }
        old_cli = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "cli-old",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "accepted": 0,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{live_head[:4]}",
        }
        controller = self.make(
            {1: pr(1, live_head)},
            {
                (1, "hosted"): [dict(hosted, checkpoint="h1"), dict(hosted, checkpoint="h2")],
                (1, "cli"): [old_cli],
            },
        )
        controller.set_stack([1])

        result = controller.status()["prs"][0]

        self.assertEqual(result["reconciliation"], "COHERENT")
        self.assertEqual(result["channels"]["hosted"], "COMPLETE")
        self.assertEqual(result["channels"]["cli"], "JUDGMENT_REQUIRED")

    def test_git_anchor_failures_are_unreconciled_per_pr_in_both_reconciliation_paths(self):
        scenarios = {
            "initial anchor check": ({"feature-1": HEAD_1, "feature-2": HEAD_2}, 1),
            "review evidence anchor check": ({"feature-1": HEAD_1}, 2),
        }
        for name, (heads, failed_pr) in scenarios.items():
            with self.subTest(path=name):
                controller = self.make(
                    {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)},
                    heads=heads,
                )
                controller.set_stack([1, 2])
                original_merge_base = controller.git.merge_base
                failing_calls = 0
                failed_head = {1: HEAD_1, 2: HEAD_2}[failed_pr]

                def fail_one_pr(
                    parent,
                    child,
                    merge_base=original_merge_base,
                    expected_head=failed_head,
                ):
                    nonlocal failing_calls
                    if child == expected_head:
                        failing_calls += 1
                        raise OSError("simulated merge-base failure")
                    return merge_base(parent, child)

                controller.git.merge_base = fail_one_pr
                result = controller.status()

                self.assertEqual(failing_calls, 1)
                failed_index = failed_pr - 1
                healthy_index = 1 - failed_index
                self.assertEqual(result["prs"][failed_index]["reconciliation"], "UNRECONCILED")
                self.assertIn("simulated merge-base failure", result["prs"][failed_index]["reason"])
                self.assertEqual(result["prs"][failed_index]["channels"]["hosted"], "UNRECONCILED")
                self.assertEqual(result["prs"][failed_index]["channels"]["cli"], "UNRECONCILED")
                self.assertEqual(result["prs"][healthy_index]["reconciliation"], "COHERENT")

        controller = self.make(
            {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)},
            heads={"feature-1": HEAD_1, "feature-2": HEAD_2},
        )
        controller.set_stack([1, 2])
        original_is_ancestor = controller.git.is_ancestor
        failing_calls = 0

        def fail_child_ancestry(parent, child):
            nonlocal failing_calls
            if parent == HEAD_1 and child == HEAD_2:
                failing_calls += 1
                raise OSError("simulated ancestry lookup failure")
            return original_is_ancestor(parent, child)

        controller.git.is_ancestor = fail_child_ancestry
        result = controller.status()

        self.assertEqual(failing_calls, 2)
        self.assertEqual(result["prs"][1]["reconciliation"], "UNRECONCILED")
        self.assertEqual(result["prs"][1]["channels"]["hosted"], "UNRECONCILED")
        self.assertEqual(result["prs"][1]["channels"]["cli"], "UNRECONCILED")
        self.assertEqual(result["prs"][0]["pr"], 1)
        self.assertEqual(result["prs"][0]["reconciliation"], "COHERENT")

    def test_reconciliation_decision_is_available_under_decide_command(self):
        args = _parser().parse_args(
            [
                "decide",
                "reconcile",
                "--pr",
                "2",
                "--channel",
                "cli",
                "--checkpoint",
                "cli-old-parent",
                "--prior-head",
                "9" * 40,
                "--reason",
                "reopen after rebase",
            ]
        )
        self.assertEqual(args.decide_command, "reconcile")

    def test_unsupported_decision_error_lists_reconcile(self):
        controller = self.make({})
        with self.assertRaisesRegex(ControllerError, "retain, reopen, policy, or reconcile"):
            controller.decide("unsupported")

    def test_stack_reconciliation_rejects_wrong_checkpoint_and_incoherent_live_topology(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)}
        evidence = {
            (2, "hosted"): [
                {
                    "pr": 2,
                    "head": "9" * 40,
                    "checkpoint": "hosted-old-parent",
                    "completed": True,
                    "anchored": True,
                    "attributable": True,
                    "child_head": "9" * 40,
                    "parent_identity": "1",
                    "parent_head": PARENT,
                    "merge_base": BASE,
                    "patch_id": "old-patch",
                }
            ]
        }
        controller = self.make(values, evidence, heads={"feature-1": HEAD_1, "feature-2": HEAD_2})
        controller.set_stack([1, 2])
        with self.assertRaisesRegex(ControllerError, "attributable evidence"):
            controller.decide_reconciliation(
                pr=2, channel="hosted", checkpoint="wrong", prior_head="9" * 40, reason="reconcile"
            )
        controller.git.heads["feature-1"] = "8" * 40
        with self.assertRaisesRegex(ControllerError, "coherent exact-current live topology"):
            controller.decide_reconciliation(
                pr=2,
                channel="hosted",
                checkpoint="hosted-old-parent",
                prior_head="9" * 40,
                reason="reconcile",
            )

    def test_recorded_reconciliation_stops_applying_after_anchor_changes(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)}
        evidence = {
            (2, "cli"): [
                {
                    "pr": 2,
                    "head": "9" * 40,
                    "checkpoint": "cli-old-parent",
                    "completed": True,
                    "anchored": True,
                    "attributable": True,
                    "child_head": "9" * 40,
                    "parent_identity": "1",
                    "parent_head": PARENT,
                    "merge_base": BASE,
                    "patch_id": "old-patch",
                }
            ]
        }
        controller = self.make(values, evidence, heads={"feature-1": HEAD_1, "feature-2": HEAD_2})
        controller.set_stack([1, 2])
        controller.decide_reconciliation(
            pr=2,
            channel="cli",
            checkpoint="cli-old-parent",
            prior_head="9" * 40,
            reason="rebased and ready for a fresh review",
        )
        values[2] = pr(2, "8" * 40, "feature-1", HEAD_1)
        controller.git.heads["feature-2"] = "8" * 40
        self.assertEqual(controller.status()["prs"][1]["reconciliation"], "PARENT_MOVED")

    def test_reconciled_parent_move_classifies_review_by_patch_identity(self):
        values = {1: pr(1, HEAD_1), 2: pr(2, HEAD_2, "feature-1", HEAD_1)}
        for prior_patch_id, expected_channel_status in (
            (f"patch-{HEAD_2[:4]}", "EQUIVALENT_HISTORY"),
            ("different-patch", "PATCH_CHANGED"),
        ):
            with self.subTest(expected_channel_status=expected_channel_status):
                evidence = {
                    (2, "cli"): [
                        {
                            "pr": 2,
                            "head": HEAD_2,
                            "checkpoint": "cli-before-parent-move",
                            "completed": True,
                            "anchored": True,
                            "attributable": True,
                            "child_head": HEAD_2,
                            "parent_identity": "1",
                            "parent_head": PARENT,
                            "merge_base": BASE,
                            "patch_id": prior_patch_id,
                        }
                    ]
                }
                controller = self.make(
                    values,
                    evidence,
                    heads={"feature-1": HEAD_1, "feature-2": HEAD_2},
                )
                controller.set_stack([1, 2])
                controller.decide_reconciliation(
                    pr=2,
                    channel="cli",
                    checkpoint="cli-before-parent-move",
                    prior_head=HEAD_2,
                    reason="reconcile the child after its parent advanced",
                )

                _, reconciliation = controller._reconciliation(controller._state())

                self.assertEqual(reconciliation.status_for(2), "COHERENT")
                self.assertEqual(reconciliation.status_for(2, "cli"), expected_channel_status)

    def test_later_channel_anchor_cannot_downgrade_parent_moved_or_unreconciled(self):
        moved_evidence = {
            (1, "hosted"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "hosted-parent-moved",
                    "completed": True,
                    "anchored": True,
                    "attributable": True,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": PARENT,
                    "merge_base": BASE,
                    "patch_id": "old-patch",
                }
            ],
            (1, "cli"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "cli-patch-changed",
                    "completed": True,
                    "anchored": True,
                    "attributable": True,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": "old-patch",
                }
            ],
        }
        moved = self.make({1: pr(1, HEAD_1)}, moved_evidence)
        moved.set_stack([1])
        self.assertEqual(moved.status()["prs"][0]["reconciliation"], "PARENT_MOVED")

        unreconciled_evidence = {
            (1, "hosted"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "hosted-merge-base-changed",
                    "completed": True,
                    "anchored": True,
                    "attributable": True,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": PARENT,
                    "patch_id": f"patch-{HEAD_1[:4]}",
                }
            ],
            (1, "cli"): [
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "cli-patch-changed",
                    "completed": True,
                    "attributable": True,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": "old-patch",
                }
            ],
        }
        unreconciled = self.make({1: pr(1, HEAD_1)}, unreconciled_evidence)
        unreconciled.set_stack([1])
        self.assertEqual(unreconciled.status()["prs"][0]["reconciliation"], "UNRECONCILED")


if __name__ == "__main__":
    unittest.main()
