#!/usr/bin/env python3
"""Hermetic contracts for the unified review controller integration layer."""

from __future__ import annotations

import dataclasses
import fcntl
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "dev-tools"))

from pr_review import cli_runner, hosted, stack
from pr_review.cli import _parser
from pr_review.controller import (
    HOSTED_ACTIVE_RESPONSE_REASON,
    HOSTED_CLI_OVERLAP_HOLD_REASON,
    ControllerError,
    DefaultGitProvider,
    LivePullRequest,
    PullRequestSnapshot,
    ReviewController,
    StaleReviewTarget,
    WrongStackTarget,
    compact_result,
    json_result,
)
from pr_review.git_merge import test_merge_tree
from pr_review.patch_identity import patch_diff_args
from pr_review.policy import (
    Channel,
    ChannelDecision,
    Evidence,
    ReviewStatus,
    completion_status,
    fresh_taper_history,
    required_taper,
    select_review_target,
    taper_satisfied,
)
from pr_review.runtime import LiveEvidence
from pr_review.state import (
    Judgment,
    LegacyEvidenceTransition,
    StackReconciliationDecision,
    StateStore,
    observation_fingerprint,
)

BASE = "a" * 40
PARENT = "b" * 40
HEAD_1 = "c" * 40
HEAD_2 = "d" * 40
HEAD_3 = "7" * 40
MERGE_1 = "e" * 40
MERGE_2 = "f" * 40


def _git(root, *args, input_text=None):
    return subprocess.run(
        ["git", "-C", str(root), *args],
        input=input_text,
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def _commit_tree_case(root, *, conflict=False):
    subprocess.run(["git", "init", "--quiet", str(root)], check=True, capture_output=True)
    _git(root, "config", "user.name", "FireMUD test")
    _git(root, "config", "user.email", "test@example.invalid")

    def make_tree(files):
        entries = []
        for name, content in sorted(files.items()):
            blob = _git(root, "hash-object", "-w", "--stdin", input_text=content)
            entries.append(f"100644 blob {blob}\t{name}\n")
        return _git(root, "mktree", input_text="".join(entries))

    def make_commit(tree, message, parent=None):
        args = ["-c", "user.name=FireMUD test", "-c", "user.email=test@example.invalid", "commit-tree", tree]
        if parent is not None:
            args.extend(("-p", parent))
        args.extend(("-m", message))
        return _git(root, *args)

    common = make_commit(make_tree({"common.txt": "common\n"}), "common")
    base_files = {"base.txt": "base\n", "common.txt": "base version\n" if conflict else "common\n"}
    head_files = {"head.txt": "head\n", "common.txt": "head version\n" if conflict else "common\n"}
    base = make_commit(make_tree(base_files), "base", common)
    head = make_commit(make_tree(head_files), "head", common)
    expected = None if conflict else make_tree({**base_files, **head_files})
    return base, head, expected


class FakeGit:
    def __init__(self, heads=None):
        self.heads = {"develop": BASE, **(heads or {})}
        self.remote_heads_calls = 0
        self.test_merge_calls = []
        self.test_merge_error = None

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

    def test_merge_tree(self, base, head):
        self.test_merge_calls.append((base, head))
        if self.test_merge_error is not None:
            raise self.test_merge_error
        return MERGE_1


class FakeGitHub:
    def __init__(self, values):
        self.values = values

    def pull_request(self, number):
        return self.values[number]


class AuditedEvidence(dict):
    def __init__(self, *args, audit=None, on_audit=None, **kwargs):
        self.backing = args[0] if args and isinstance(args[0], dict) else None
        super().__init__(*args, **kwargs)
        self.audit = audit or {
            "complete": True,
            "active_reservations": [],
            "unmatched_responses": [],
            "ambiguous_responses": [],
            "unresolved_findings": [],
        }
        self.on_audit = on_audit
        self.audit_calls = []
        self.stop_audit_calls = []

    def get(self, key, default=None):
        if self.backing is not None:
            return self.backing.get(key, default)
        return super().get(key, default)

    def legacy_transition_reauthorization_audit(self, pr_number, fingerprints, anchor):
        self.audit_calls.append((pr_number, fingerprints, anchor))
        if self.on_audit is not None:
            self.on_audit()
        return self.audit

    def review_stop_audit(
        self,
        pr_number,
        anchor,
        retained_ambiguous_fingerprints=(),
        prior_hosted_fingerprints=(),
    ):
        self.stop_audit_calls.append(
            (pr_number, tuple(retained_ambiguous_fingerprints), tuple(prior_hosted_fingerprints))
        )
        if self.on_audit is not None:
            self.on_audit()
        audit = dict(self.audit)
        audit.setdefault("head", anchor["child_head"])
        audit.setdefault("anchor", dict(anchor))
        pinned = set(retained_ambiguous_fingerprints)
        audit["retained_ambiguous"] = [
            item for item in audit.get("retained_ambiguous", ())
            if isinstance(item, dict) and item.get("fingerprint") in pinned
        ]
        return audit


class CountingEvidence(AuditedEvidence):
    def __init__(self, *args, active_targets=(), **kwargs):
        super().__init__(*args, **kwargs)
        self.history_reads = []
        self.active_targets = set(active_targets)
        self.active_target_reads = 0

    def get(self, key, default=None):
        if isinstance(key, tuple) and len(key) == 2 and key[1] in {"hosted", "cli"}:
            self.history_reads.append(key)
        return super().get(key, default)

    def active_review_targets(self, pr_numbers, identities):
        self.active_target_reads += 1
        return self.active_targets.intersection(pr_numbers)


def _stacked_prs(count, *, merged=()):
    merged_numbers = set(merged)
    values = {}
    heads = {"develop": BASE}
    previous = None
    for number in range(1, count + 1):
        head = f"{number:040x}"
        if previous is None:
            base_ref, base_tip = "develop", BASE
        else:
            base_ref, base_tip = f"feature-{previous[0]}", previous[1]
        is_merged = number in merged_numbers
        values[number] = LivePullRequest(
            number,
            head,
            base_ref,
            base_tip,
            f"feature-{number}",
            merged=is_merged,
            state="MERGED" if is_merged else "OPEN",
            mergeable="MERGEABLE",
            head_repository="owner/repo",
        )
        heads[f"feature-{number}"] = head
        if not is_merged:
            previous = (number, head)
    return values, heads


def _batch_identity(item):
    return {
        "number": item.number,
        "state": item.state,
        "isDraft": False,
        "mergedAt": "2026-09-26T00:00:00Z" if item.merged else None,
        "baseRefName": item.base_ref,
        "baseRefOid": item.base_tip,
        "headRefName": item.head_ref,
        "headRefOid": item.head,
        "mergeable": item.mergeable,
        "headRepository": {"nameWithOwner": item.head_repository},
        "comments": {"nodes": []},
        "reviews": {"nodes": []},
    }


def pr(
    number,
    head,
    base_ref="develop",
    base_tip=BASE,
    *,
    merged=False,
    head_repository="owner/repo",
    mergeable="MERGEABLE",
):
    return LivePullRequest(
        number,
        head,
        base_ref,
        base_tip,
        f"feature-{number}",
        merged=merged,
        mergeable=mergeable,
        head_repository=head_repository,
    )


def hosted_anchor(*, parent_identity="develop", parent_head=BASE, merge_base=BASE, patch_id=None):
    return {
        "pr": 1,
        "child_head": HEAD_1,
        "parent_identity": parent_identity,
        "parent_head": parent_head,
        "merge_base": merge_base,
        "patch_id": patch_id or f"patch-{HEAD_1[:4]}",
    }


class ControllerTests(unittest.TestCase):
    def make(self, values, evidence=None, *, heads=None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        provider = evidence if evidence is not None else {}
        if not callable(getattr(provider, "legacy_transition_reauthorization_audit", None)):
            provider = AuditedEvidence(provider)
        return ReviewController(
            store=StateStore(Path(directory.name) / "state.json"),
            github=FakeGitHub(values),
            git=FakeGit(heads),
            evidence=provider,
            repository="owner/repo",
        )

    @staticmethod
    def review_evidence(controller, pr_number, channel, checkpoint):
        state = controller.store.load()
        live, reconciliation = controller._reconciliation(state, evidence_prs=set())
        anchor = controller._anchor(pr_number, live[pr_number], reconciliation.links[pr_number])
        return {
            "pr": pr_number,
            "channel": channel,
            "head": anchor.child_head,
            "checkpoint": checkpoint,
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 0,
            "raw": 0,
            "child_head": anchor.child_head,
            "parent_identity": anchor.parent_identity,
            "parent_head": anchor.parent_head,
            "merge_base": anchor.merge_base,
            "patch_id": anchor.patch_id,
        }

    def test_route_identity_reuses_observations_and_target_disposition_moves_open_work(self):
        controller = self.make({})
        first = controller.record_route(
            source_pr=2828,
            source_channel="hosted",
            source_review="901",
            source_finding="thread-77",
            observation="child-owned behavior",
            target_pr=2879,
        )["route"]
        repeated = controller.record_route(
            source_pr=2828,
            source_channel="hosted",
            source_review="901",
            source_finding="thread-77",
            observation="same finding observed again",
            target_pr=2879,
        )["route"]
        with self.assertRaisesRegex(ControllerError, "explicitly retargeted"):
            controller.record_route(
                source_pr=2828,
                source_channel="hosted",
                source_review="901",
                source_finding="thread-77",
                observation="conflicting target observation",
                target_pr=2880,
            )

        self.assertEqual(first["route_id"], repeated["route_id"])
        self.assertEqual(len(repeated["observations"]), 2)
        self.assertEqual(controller.list_routes(target_pr=2879)["count"], 1)
        self.assertEqual(controller.list_routes(unassigned=True)["count"], 0)
        # A source PR need not remain in the live stack for its route to stay queryable.
        self.assertEqual(controller.status_for_pr(2828)["routes_out"][0]["route_id"], first["route_id"])

        moved_result = controller.decide_route(
            route_id=first["route_id"],
            decision="retargeted",
            target_pr=2880,
            reason="the observed code now belongs to the shared transport PR",
        )
        self.assertEqual(moved_result["status"], "recorded")
        self.assertEqual(moved_result["decision"], "retargeted")
        moved = moved_result["route"]
        self.assertEqual(moved["status"], "open")
        self.assertEqual(moved["target_history"], [2879])
        self.assertEqual(controller.list_routes(target_pr=2879)["count"], 0)
        self.assertEqual(controller.list_routes(target_pr=2880)["count"], 1)

        resolved = controller.decide_route(
            route_id=first["route_id"],
            decision="accepted-fixed",
            proof="verified in commit " + "a" * 40,
        )["route"]
        self.assertEqual(resolved["status"], "accepted_fixed")
        self.assertEqual(controller.list_routes(target_pr=2880)["count"], 0)
        dispositioned_state = controller.store.load()
        with self.assertRaisesRegex(ControllerError, "already dispositioned"):
            controller.record_route(
                source_pr=2828,
                source_channel="hosted",
                source_review="901",
                source_finding="thread-77",
                observation="late duplicate observation",
                target_pr=2880,
            )
        self.assertEqual(controller.store.load(), dispositioned_state)

    def test_retarget_reason_limit_includes_prefix_and_fails_before_state_update(self):
        controller = self.make({})
        route = controller.record_route(
            source_pr=2828,
            source_channel="hosted",
            source_review="901",
            source_finding="thread-88",
            observation="target-owned behavior",
            target_pr=2879,
        )["route"]
        before = controller.store.load()

        with self.assertRaisesRegex(ControllerError, "fit within a 500-character"):
            controller.decide_route(
                route_id=route["route_id"],
                decision="retargeted",
                target_pr=2880,
                reason="x" * 489,
            )
        self.assertEqual(controller.store.load(), before)

        accepted = controller.decide_route(
            route_id=route["route_id"],
            decision="retargeted",
            target_pr=2880,
            reason="x" * 488,
        )["route"]
        self.assertEqual(len(accepted["observations"][-1]), 500)

    def test_route_terminal_text_rejects_controls_and_overlong_values_before_state_update(self):
        controller = self.make({})
        routes = {
            decision: controller.record_route(
                source_pr=2828,
                source_channel="hosted",
                source_review="901",
                source_finding=f"terminal-text-{decision}",
                observation="target-owned behavior",
                target_pr=2879,
            )["route"]
            for decision in ("accepted-fixed", "rejected")
        }
        invalid_values = {
            "accepted-fixed": ("verified\nin commit " + "a" * 40, "x" * 501),
            "rejected": ("duplicate\nreport", "x" * 501),
        }

        for decision, values in invalid_values.items():
            for value in values:
                with self.subTest(decision=decision, value_length=len(value)):
                    before = controller.store.load()
                    kwargs = {"proof": value} if decision == "accepted-fixed" else {"reason": value}
                    with self.assertRaisesRegex(ControllerError, "control characters|500 characters"):
                        controller.decide_route(
                            route_id=routes[decision]["route_id"],
                            decision=decision,
                            **kwargs,
                        )
                    self.assertEqual(controller.store.load(), before)

        accepted = controller.decide_route(
            route_id=routes["accepted-fixed"]["route_id"],
            decision="accepted-fixed",
            proof="verified in commit " + "a" * 40,
        )["route"]
        rejected = controller.decide_route(
            route_id=routes["rejected"]["route_id"],
            decision="rejected",
            reason="the observation is outside this route's scope",
        )["route"]
        self.assertEqual(accepted["status"], "accepted_fixed")
        self.assertEqual(rejected["status"], "rejected")

    def test_stop_uses_the_hosted_request_runner_lock_path(self):
        with tempfile.TemporaryDirectory() as directory:
            common = Path(directory)
            controller = ReviewController(
                store=StateStore(common / "firemud" / "pr-review-stack.json"),
                repository="owner/repo",
            )

            cli_path, hosted_path = controller._stop_lock_paths(42)

            self.assertEqual(cli_path, common / "firemud" / "pr-review" / "cli.lock")
            trigger_path = hosted.default_trigger_record_path("owner/repo", 42, common)
            self.assertEqual(hosted_path, trigger_path.parent / "request.lock")

    def test_stop_cli_accepts_multiple_exact_ambiguity_fingerprints(self):
        fingerprints = ("f" * 64, "e" * 64)

        args = _parser().parse_args(
            [
                "decide",
                "stop",
                "--pr",
                "1",
                "--channel",
                "hosted",
                "--reason",
                "human stop",
                "--retain-ambiguous-fingerprint",
                fingerprints[0],
                "--retain-ambiguous-fingerprint",
                fingerprints[1],
                "--ambiguity-reason",
                "both terminal responses lack attributable review objects",
            ]
        )

        self.assertEqual(args.retain_ambiguous_fingerprint, list(fingerprints))

    def test_stop_cli_exposes_exact_head_over_ceiling_acknowledgment(self):
        args = _parser().parse_args(
            [
                "decide", "stop", "--pr", "1", "--channel", "hosted",
                "--head", HEAD_1, "--reason", "audited human stop",
                "--acknowledge-over-ceiling",
            ]
        )
        self.assertTrue(args.acknowledge_over_ceiling)

    def make_legacy_retirement_case(self, *, audit=None, on_audit=None):
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
            "parent_head": "1" * 40,
            "patch_id": "",
        }
        legacy_hosted = {
            "pr": 1,
            "head": old_head,
            "checkpoint": "trigger-uncheckpointed:42",
            "held": True,
        }
        evidence = AuditedEvidence(
            {(1, "cli"): [legacy_cli], (1, "hosted"): [legacy_hosted]},
            audit=audit,
            on_audit=on_audit,
        )
        values = {1: pr(1, HEAD_1)}
        controller = self.make(values, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])
        controller.decide_legacy_transition(pr=1, head=HEAD_1, reason="record the prior legacy transition")
        legacy_fingerprint = observation_fingerprint(legacy_hosted)
        modern_hosted = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "modern-hosted",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 0,
            "raw": 0,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{HEAD_1[:4]}",
        }
        evidence[(1, "hosted")].append(modern_hosted)
        evidence[(1, "hosted")].remove(legacy_hosted)
        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2
        return controller, evidence, values, legacy_hosted, legacy_fingerprint
    @staticmethod
    def allocation_evidence(
        number=1,
        head=HEAD_1,
        checkpoint="before-allocation",
        *,
        accepted=0,
        completed=True,
        attributable=True,
        anchored=True,
        **extra,
    ):
        return {
            "pr": number,
            "head": head,
            "checkpoint": checkpoint,
            "completed": completed,
            "attributable": attributable,
            "anchored": anchored,
            "corrected_state": completed,
            "accepted": accepted,
            "child_head": head,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{head[:4]}",
            **extra,
        }

    @staticmethod
    def scope_timeline_evidence(number=1, channel="hosted", head=HEAD_1):
        return {
            "pr": number,
            "head": head,
            "channel": channel,
            "kind": "scope_timeline",
            "scope_timeline": True,
            "scope_timeline_complete": True,
            "checkpoint": "scope-timeline:complete",
            "completed": False,
            "attributable": False,
            "anchored": False,
            "accepted": 0,
            "raw": 0,
            "non_counting": True,
        }

    def grant_allocation(self, *, channel="hosted", evidence=None, values=None, heads=None):
        values = values or {1: pr(1, HEAD_1)}
        controller = self.make(
            values,
            evidence or {(1, channel): [self.allocation_evidence(completed=False)]},
            heads=heads or {"feature-1": values[1].head},
        )
        controller.set_stack([1])
        controller.decide_allocation(
            action="grant",
            pr=1,
            channel=channel,
            head=HEAD_1,
            reason="one bounded result before capacity handoff",
        )
        return controller

    def grant_bounded_allocation(
        self,
        *,
        channel="hosted",
        checkpoint: str | None = "bounded-baseline",
        cap=2,
        minimum=1,
        evidence=None,
        values=None,
        heads=None,
        pr_number=1,
        fresh_taper=False,
    ):
        values = values or {pr_number: pr(pr_number, HEAD_1)}
        evidence = evidence or {
            (pr_number, channel): [
                self.allocation_evidence(
                    pr_number,
                    HEAD_1,
                    checkpoint or "before-allocation",
                    channel=channel,
                )
            ]
        }
        for (evidence_pr, evidence_channel), rows in evidence.items():
            if not any(row.get("scope_timeline") is True for row in rows):
                baseline_head = next(
                    (row.get("head") for row in rows if isinstance(row.get("head"), str)),
                    HEAD_1,
                )
                rows.append(
                    self.scope_timeline_evidence(evidence_pr, evidence_channel, baseline_head)
                )
        controller = self.make(
            values,
            evidence,
            heads=heads or {values[pr_number].head_ref: values[pr_number].head},
        )
        controller.set_stack(tuple(values))
        controller.decide_allocation(
            action="grant",
            pr=pr_number,
            channel=channel,
            head=values[pr_number].head,
            checkpoint=checkpoint,
            min_additional_completed=minimum,
            max_additional_completed=cap,
            fresh_taper=fresh_taper,
            reason="bounded additional review allowance",
        )
        return controller

    def hosted_judgment_allocation_fixture(self, *, audit=None, hosted_patch_id=None, cli_patch_id=None):
        values = {1: pr(1, HEAD_3)}
        hosted_review = self.allocation_evidence(
            head=HEAD_2,
            checkpoint="hosted-old-head",
            channel="hosted",
            raw=0,
        )
        if hosted_patch_id is not None:
            hosted_review["patch_id"] = hosted_patch_id
        cli_review = self.allocation_evidence(
            head=HEAD_3,
            checkpoint="cli-current-head",
            channel="cli",
            raw=0,
        )
        if cli_patch_id is not None:
            cli_review["patch_id"] = cli_patch_id
        evidence = AuditedEvidence(
            {
                (1, "hosted"): [
                    hosted_review,
                    self.scope_timeline_evidence(1, "hosted", HEAD_2),
                ],
                (1, "cli"): [
                    cli_review,
                    self.scope_timeline_evidence(1, "cli", HEAD_3),
                ],
            },
            audit=audit,
        )
        controller = self.make(values, evidence, heads={"feature-1": HEAD_3})
        controller.set_stack([1])
        return controller, evidence

    @staticmethod
    def grant_judgment_hosted_review(controller):
        return controller.decide_allocation(
            action="grant",
            pr=1,
            channel="hosted",
            head=HEAD_3,
            reason="one more hosted review after the corrected head",
            min_additional_completed=1,
            max_additional_completed=1,
            fresh_taper=True,
        )

    def test_bounded_hosted_allocation_reopens_judgment_required_after_full_stop_audit(self):
        controller, evidence = self.hosted_judgment_allocation_fixture()

        _, reconciliation = controller._reconciliation(controller._state())
        self.assertEqual(
            reconciliation.status_for(1, "hosted"),
            stack.ReconciliationStatus.PATCH_CHANGED,
        )
        target = controller.status()["review_targets"]["hosted"]
        self.assertEqual((target["pr"], target["status"]), (None, "COMPLETE"))

        result = self.grant_judgment_hosted_review(controller)

        allocation = controller.status()["prs"][0]["allocations"]["hosted"]
        self.assertEqual(result["progress"]["status"], "CAP_ACTIVE")
        self.assertEqual(allocation["status"], "CAP_ACTIVE")
        self.assertEqual(allocation["minimum_additional_completed"], 1)
        self.assertEqual(allocation["maximum_additional_completed"], 1)
        self.assertEqual(controller.status()["review_targets"]["hosted"]["status"], "READY")
        self.assertEqual(controller.resolve_hosted_target().snapshot.head_sha, HEAD_3)
        self.assertGreaterEqual(len(evidence.stop_audit_calls), 3)

    def test_bounded_hosted_judgment_override_requires_fresh_taper(self):
        controller, _ = self.hosted_judgment_allocation_fixture()

        with self.assertRaisesRegex(ControllerError, "must explicitly reopen fresh taper"):
            controller.decide_allocation(
                action="grant",
                pr=1,
                channel="hosted",
                head=HEAD_3,
                reason="bounded review allowance after judgment-required taper",
                min_additional_completed=1,
                max_additional_completed=1,
                fresh_taper=False,
            )

        self.assertNotIn("1:hosted", controller._state().allocations)

    def test_bounded_hosted_allocation_reopens_equivalent_old_head_identity(self):
        controller, _ = self.hosted_judgment_allocation_fixture(
            hosted_patch_id=f"patch-{HEAD_3[:4]}"
        )

        _, reconciliation = controller._reconciliation(controller._state())
        self.assertEqual(
            reconciliation.status_for(1, "hosted"),
            stack.ReconciliationStatus.EQUIVALENT_HISTORY,
        )
        self.assertEqual(
            controller.status()["review_targets"]["hosted"]["status"],
            "COMPLETE",
        )

        self.grant_judgment_hosted_review(controller)

        self.assertEqual(controller.status()["review_targets"]["hosted"]["status"], "READY")
        self.assertEqual(controller.resolve_hosted_target().snapshot.head_sha, HEAD_3)

    def test_hosted_cross_channel_reopen_leaves_ready_decisions_unchanged(self):
        controller, evidence = self.hosted_judgment_allocation_fixture()
        state = controller._state()
        live, reconciliation = controller._reconciliation(state)
        decision = ChannelDecision(
            Channel.HOSTED,
            1,
            ReviewStatus.READY,
            "the target is already ready",
        )

        result = controller._reopen_hosted_cross_channel_judgment(
            state,
            Channel.HOSTED,
            decision,
            live,
            reconciliation,
            {
                Channel.HOSTED: {1: evidence.get((1, "hosted"), ())},
                Channel.CLI: {1: evidence.get((1, "cli"), ())},
            },
            {},
        )

        self.assertIs(result, decision)
        self.assertEqual(evidence.stop_audit_calls, [])

    def test_bounded_hosted_allocation_rejects_current_cli_patch_mismatch(self):
        controller, _ = self.hosted_judgment_allocation_fixture(cli_patch_id="stale-cli-patch")

        self.grant_judgment_hosted_review(controller)

        self.assertEqual(
            controller.status()["review_targets"]["hosted"]["status"],
            "JUDGMENT_REQUIRED",
        )
        with self.assertRaisesRegex(ControllerError, "hosted review cannot run: JUDGMENT_REQUIRED"):
            controller.resolve_hosted_target()

    def test_bounded_hosted_allocation_still_refuses_judgment_required_with_open_findings(self):
        audit = {
            "complete": True,
            "active_reservations": [],
            "unmatched_responses": [],
            "ambiguous_responses": [],
            "unresolved_findings": ["thread:77"],
        }
        controller, evidence = self.hosted_judgment_allocation_fixture(audit=audit)

        with self.assertRaisesRegex(
            ControllerError,
            "review stop is blocked by an unresolved actionable finding or thread",
        ):
            self.grant_judgment_hosted_review(controller)

        self.assertEqual(len(evidence.stop_audit_calls), 1)
        self.assertNotIn("1:hosted", controller._state().allocations)
        self.assertEqual(
            controller.status()["review_targets"]["hosted"]["status"],
            "COMPLETE",
        )

    def test_bounded_hosted_target_stays_judgment_required_when_request_audit_finds_open_threads(self):
        controller, evidence = self.hosted_judgment_allocation_fixture()
        self.grant_judgment_hosted_review(controller)
        evidence.audit["unresolved_findings"] = ["thread:77"]

        self.assertEqual(
            controller.status()["review_targets"]["hosted"]["status"],
            "HELD",
        )
        with self.assertRaisesRegex(
            ControllerError,
            "review stop is blocked by an unresolved actionable finding or thread",
        ):
            controller.resolve_hosted_target()

    def test_bounded_hosted_allocation_does_not_reopen_a_moved_parent(self):
        controller, evidence = self.hosted_judgment_allocation_fixture()
        evidence[(1, "hosted")][0]["parent_head"] = "9" * 40

        with self.assertRaisesRegex(ControllerError, "allocation requires a coherent current stack identity"):
            self.grant_judgment_hosted_review(controller)

        self.assertNotIn("1:hosted", controller._state().allocations)

    def test_cross_channel_reopen_uses_reconciled_anchor_and_existing_caches(self):
        controller, _ = self.hosted_judgment_allocation_fixture()
        self.grant_judgment_hosted_review(controller)
        report = controller.status()
        state = controller._state()
        live, reconciliation = controller._reconciliation(state)
        history_cache = {}
        histories = {
            channel: {
                1: controller._policy_history(
                    state,
                    1,
                    channel,
                    reconciliation,
                    history_cache=history_cache,
                )
            }
            for channel in (Channel.HOSTED, Channel.CLI)
        }
        decision = ChannelDecision(
            Channel.HOSTED,
            1,
            ReviewStatus.JUDGMENT_REQUIRED,
            "cross-channel mismatch",
        )
        allocations = {1: report["prs"][0]["allocations"]["hosted"]}
        stop_audit_cache = {}

        with (
            patch.object(controller, "_anchor", side_effect=AssertionError("should use reconciled anchor")),
            patch.object(controller, "_check_stop_evidence", return_value=(None, None, {})) as stop_evidence,
        ):
            reopened = controller._reopen_hosted_cross_channel_judgment(
                state,
                Channel.HOSTED,
                decision,
                live,
                reconciliation,
                histories,
                allocations,
                stop_audit_cache=stop_audit_cache,
                history_cache=history_cache,
            )

        self.assertEqual(reopened.status, ReviewStatus.READY)
        self.assertEqual(
            stop_evidence.call_args.args[3],
            controller._reconciled_anchor(1, live[1], reconciliation),
        )
        self.assertIs(stop_evidence.call_args.kwargs["stop_audit_cache"], stop_audit_cache)
        self.assertIs(stop_evidence.call_args.kwargs["history_cache"], history_cache)

    def test_cross_channel_reopen_fails_closed_when_evidence_audit_errors(self):
        controller, _ = self.hosted_judgment_allocation_fixture()
        self.grant_judgment_hosted_review(controller)
        report = controller.status()
        state = controller._state()
        live, reconciliation = controller._reconciliation(state)
        histories = {
            channel: {
                1: controller._policy_history(state, 1, channel, reconciliation)
            }
            for channel in (Channel.HOSTED, Channel.CLI)
        }
        decision = ChannelDecision(
            Channel.HOSTED,
            1,
            ReviewStatus.JUDGMENT_REQUIRED,
            "cross-channel mismatch",
        )

        with patch.object(
            controller,
            "_check_stop_evidence",
            side_effect=OSError("evidence snapshot unavailable"),
        ):
            held = controller._reopen_hosted_cross_channel_judgment(
                state,
                Channel.HOSTED,
                decision,
                live,
                reconciliation,
                histories,
                {1: report["prs"][0]["allocations"]["hosted"]},
            )

        self.assertEqual(held.status, ReviewStatus.HELD)
        self.assertIn("evidence snapshot unavailable", held.reason)

    def test_allocation_is_promised_before_review_and_does_not_make_pr_complete(self):
        controller = self.grant_allocation()
        result = controller.status()["prs"][0]

        self.assertEqual(result["allocations"]["hosted"]["status"], "PROMISED")
        allocation = result["allocations"]["hosted"]
        self.assertEqual(allocation["minimum_additional_completed"], 1)
        self.assertEqual(allocation["maximum_additional_completed"], 1)
        self.assertEqual(allocation["completed_count"], 0)
        self.assertEqual(allocation["controlling_reason"], allocation["reason"])
        review_requests = []
        controller.hosted_adapter = lambda *args, **kwargs: review_requests.append((args, kwargs))
        evidence_allocation = controller.evidence(1)["1"]["allocations"]["hosted"]
        self.assertEqual(review_requests, [])
        for field in (
            "minimum_additional_completed",
            "maximum_additional_completed",
            "completed_count",
            "taper_complete",
            "selection_control",
            "controlling_reason",
            "status",
        ):
            self.assertEqual(evidence_allocation[field], allocation[field], field)
        self.assertNotEqual(result["channels"]["hosted"], "COMPLETE")
        self.assertEqual(controller.resolve_hosted_target().snapshot.number, 1)

    def test_default_one_result_allocation_requeues_completed_taper_without_resetting_it(self):
        history = [
            self.allocation_evidence(
                checkpoint="hosted-zero-before-allocation",
                channel="hosted",
                accepted=0,
            )
        ]
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {(1, "hosted"): history},
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])

        before = controller.status()
        self.assertEqual(before["review_targets"]["hosted"]["status"], "COMPLETE")
        self.assertTrue(before["review_targets"]["hosted"]["taper_complete"])

        controller.decide_allocation(
            action="grant",
            pr=1,
            channel="hosted",
            head=HEAD_1,
            reason="one more hosted result after completed taper",
        )

        after = controller.status()
        target = after["review_targets"]["hosted"]
        allocation = after["prs"][0]["allocations"]["hosted"]
        self.assertEqual((target["pr"], target["status"]), (1, "READY"))
        self.assertTrue(target["taper_complete"])
        self.assertEqual(allocation["status"], "PROMISED")
        self.assertFalse(allocation["reopens_taper"])
        self.assertTrue(allocation["historical_taper_complete"])
        self.assertTrue(allocation["taper_complete"])
        self.assertEqual(controller.resolve_hosted_target().snapshot.number, 1)

    def test_selected_evidence_allocation_uses_only_selected_pr_ancestor_scope(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
            3: pr(3, HEAD_3, "feature-2", HEAD_2),
        }
        zero_result = self.allocation_evidence(
            number=2,
            head=HEAD_2,
            checkpoint="hosted-zero-before-allocation",
            accepted=0,
            raw=0,
            parent_identity="1",
            parent_head=HEAD_1,
            patch_id=f"patch-{HEAD_2[:4]}",
        )
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    number=1,
                    head=HEAD_1,
                    checkpoint="hosted-prior-complete",
                    accepted=0,
                    raw=0,
                )
            ],
            (2, "hosted"): [zero_result],
        }
        controller = self.grant_bounded_allocation(
            checkpoint="hosted-zero-before-allocation",
            cap=1,
            minimum=1,
            evidence=evidence,
            values=values,
            heads={"feature-1": HEAD_1, "feature-2": HEAD_2, "feature-3": HEAD_3},
            pr_number=2,
        )
        pull_requests = []
        original_pull_request = controller.github.pull_request

        def record_pull_request(number):
            pull_requests.append(number)
            return original_pull_request(number)

        controller.github.pull_request = record_pull_request

        row = controller.evidence(2)["2"]

        self.assertEqual(pull_requests, [1, 2])
        zero_history = [
            item for item in row["hosted"] if item["checkpoint"] == "hosted-zero-before-allocation"
        ]
        self.assertEqual(len(zero_history), 1)
        self.assertEqual(zero_history[0]["accepted"], 0)
        self.assertTrue(zero_history[0]["completed"])
        allocation = row["allocations"]["hosted"]
        self.assertEqual(allocation["baseline_checkpoint"], "hosted-zero-before-allocation")
        self.assertEqual(allocation["minimum_additional_completed"], 1)
        self.assertEqual(allocation["completed_count"], 0)

    def test_bounded_allocation_can_be_granted_before_first_review(self):
        evidence = {(1, "hosted"): []}
        controller = self.grant_bounded_allocation(
            checkpoint=None,
            cap=2,
            minimum=1,
            evidence=evidence,
        )

        result = controller.status()["prs"][0]
        allocation = result["allocations"]["hosted"]

        self.assertEqual(allocation["status"], "CAP_ACTIVE")
        self.assertIsNone(allocation["baseline_checkpoint"])
        self.assertEqual(allocation["completed_count"], 0)
        self.assertEqual(result["channels"]["hosted"], "MISSING_EVIDENCE")
        self.assertEqual(
            [row["kind"] for row in evidence[(1, "hosted")]],
            ["scope_timeline"],
        )

    def test_bounded_allocation_counts_supplied_post_baseline_result_and_holds_pending_findings(self):
        baseline = self.allocation_evidence(
            checkpoint="5846432587", accepted=0, channel="hosted"
        )
        completed = self.allocation_evidence(
            checkpoint="5847200187", accepted=3, channel="hosted"
        )
        evidence = {(1, "hosted"): [baseline]}
        controller = self.grant_bounded_allocation(
            checkpoint="5846432587",
            cap=2,
            evidence=evidence,
        )
        evidence[(1, "hosted")].append(completed)

        row = controller.status()["prs"][0]
        allocation = row["allocations"]["hosted"]

        self.assertEqual(allocation["baseline_checkpoint"], "5846432587")
        self.assertEqual(allocation["used"], 1)
        self.assertEqual(allocation["cap"], 2)
        self.assertEqual(allocation["remaining"], 1)
        self.assertEqual(allocation["status"], "CAP_FINDINGS_PENDING")
        self.assertIn("accepted findings remain pending", allocation["reason"])
        evidence_readback = controller.evidence(1)["1"]["allocations"]["hosted"]
        self.assertEqual(evidence_readback["baseline_checkpoint"], "5846432587")
        self.assertEqual((evidence_readback["used"], evidence_readback["cap"]), (1, 2))
        for field in (
            "minimum_additional_completed",
            "maximum_additional_completed",
            "completed_count",
            "taper_complete",
            "selection_control",
            "controlling_reason",
            "status",
        ):
            self.assertEqual(evidence_readback[field], allocation[field], field)
        with self.assertRaisesRegex(ControllerError, "accepted findings remain pending"):
            controller.resolve_hosted_target()

    def test_bounded_in_flight_work_keeps_completed_front_selected_ahead_of_next_pr(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
        }
        in_flight = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:2827",
            "trigger_id": 2827,
            "held": True,
            "reason": HOSTED_ACTIVE_RESPONSE_REASON,
            "anchor": {
                "pr": 1,
                "child_head": HEAD_1,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{HEAD_1[:4]}",
            },
        }
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(checkpoint="completed-baseline", channel="hosted"),
                in_flight,
            ],
            (2, "hosted"): [
                self.allocation_evidence(
                    2,
                    HEAD_2,
                    "before-second-pr",
                    completed=False,
                    channel="hosted",
                    parent_identity="1",
                    parent_head=HEAD_1,
                )
            ],
        }
        controller = self.grant_bounded_allocation(
            checkpoint="completed-baseline",
            cap=2,
            evidence=evidence,
            values=values,
            heads={"develop": BASE, "feature-1": HEAD_1, "feature-2": HEAD_2},
        )

        target = controller.select_target("hosted")
        report = controller.status()
        front = report["review_targets"]["hosted"]
        allocation = report["prs"][0]["allocations"]["hosted"]

        self.assertEqual(allocation["status"], "CAP_ACTIVE")
        self.assertEqual(allocation["remaining"], 1)
        self.assertEqual(allocation["selection_control"], "unresolved_work")
        self.assertEqual((target["pr"], target["status"]), (1, "HELD"))
        self.assertEqual((front["pr"], front["status"]), (1, "HELD"))
        with self.assertRaisesRegex(ControllerError, "still in flight"):
            controller.resolve_hosted_target()

    def test_bounded_hosted_allocation_reopens_completed_front_until_taper_finishes(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
        }
        baseline = self.allocation_evidence(
            checkpoint="completed-baseline", channel="hosted", accepted=0
        )
        evidence = {
            (1, "hosted"): [baseline],
            (2, "hosted"): [
                self.allocation_evidence(
                    2,
                    HEAD_2,
                    "before-second-pr",
                    completed=False,
                    channel="hosted",
                    parent_identity="1",
                    parent_head=HEAD_1,
                )
            ],
        }
        controller = self.grant_bounded_allocation(
            checkpoint="completed-baseline",
            cap=2,
            evidence=evidence,
            values=values,
            heads={"develop": BASE, "feature-1": HEAD_1, "feature-2": HEAD_2},
        )

        before_taper = controller.status()

        self.assertEqual(before_taper["prs"][0]["channels"]["hosted"], "COMPLETE")
        self.assertEqual(before_taper["prs"][0]["allocations"]["hosted"]["remaining"], 2)
        self.assertEqual(before_taper["review_targets"]["hosted"]["pr"], 1)
        self.assertEqual(controller.resolve_hosted_target().snapshot.number, 1)

        evidence[(1, "hosted")].append(
            self.allocation_evidence(
                checkpoint="post-baseline-zero-result", channel="hosted", accepted=0
            )
        )
        after_taper = controller.status()

        self.assertEqual(after_taper["prs"][0]["allocations"]["hosted"]["used"], 1)
        self.assertEqual(after_taper["prs"][0]["allocations"]["hosted"]["remaining"], 1)
        self.assertEqual(after_taper["review_targets"]["hosted"]["pr"], 2)
        self.assertEqual(controller.resolve_hosted_target().snapshot.number, 2)

    def test_minimum_only_keeps_requests_open_until_its_count_and_normal_taper(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
        }
        evidence = {
            (1, "cli"): [self.allocation_evidence(checkpoint="cli-baseline", channel="cli")],
            (2, "cli"): [
                self.allocation_evidence(
                    2,
                    HEAD_2,
                    "before-second-pr",
                    completed=False,
                    channel="cli",
                    parent_identity="1",
                    parent_head=HEAD_1,
                )
            ],
        }
        controller = self.grant_bounded_allocation(
            channel="cli",
            checkpoint="cli-baseline",
            cap=None,
            minimum=2,
            evidence=evidence,
            values=values,
            heads={"develop": BASE, "feature-1": HEAD_1, "feature-2": HEAD_2},
        )

        before = controller.status()
        allocation = before["prs"][0]["allocations"]["cli"]
        self.assertEqual(allocation["status"], "CAP_ACTIVE")
        self.assertEqual(allocation["minimum_additional_completed"], 2)
        self.assertIsNone(allocation["maximum_additional_completed"])
        self.assertEqual(allocation["completed_count"], 0)
        self.assertEqual(allocation["selection_control"], "minimum")
        self.assertEqual(before["review_targets"]["cli"]["pr"], 1)

        evidence[(1, "cli")].extend(
            (
                self.allocation_evidence(checkpoint="cli-additional-1", channel="cli"),
                self.allocation_evidence(checkpoint="cli-additional-2", channel="cli"),
            )
        )
        after = controller.status()
        allocation = after["prs"][0]["allocations"]["cli"]
        self.assertEqual(allocation["status"], "CAP_TAPERED")
        self.assertEqual(allocation["completed_count"], 2)
        self.assertTrue(allocation["taper_complete"])
        self.assertEqual(after["review_targets"]["cli"]["pr"], 2)

    def test_one_off_hosted_allocation_preserves_history_and_normal_taper_until_extra_result(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    checkpoint="hosted-earlier-zero",
                    channel="hosted",
                    raw=0,
                    accepted=0,
                ),
                self.allocation_evidence(
                    checkpoint="hosted-zero",
                    channel="hosted",
                    raw=0,
                    accepted=0,
                ),
            ]
        }
        controller = self.grant_bounded_allocation(
            checkpoint="hosted-zero",
            cap=1,
            minimum=1,
            evidence=evidence,
        )

        before_result = controller.status()
        allocation = before_result["prs"][0]["allocations"]["hosted"]
        activity = before_result["prs"][0]["review_activity"]["hosted"]
        self.assertEqual(activity["total"], 2)
        self.assertEqual([item["raw"] for item in activity["recent"]], [0, 0])
        self.assertTrue(allocation["historical_taper_complete"])
        self.assertTrue(allocation["taper_complete"])
        self.assertFalse(allocation["reopens_taper"])
        self.assertEqual(allocation["completed_count"], 0)
        self.assertEqual(allocation["status"], "CAP_ACTIVE")
        self.assertEqual(before_result["review_targets"]["hosted"]["pr"], 1)

        evidence[(1, "hosted")].append(
            self.allocation_evidence(
                checkpoint="hosted-extra-zero",
                channel="hosted",
                raw=0,
                accepted=0,
            )
        )

        after_result = controller.status()
        allocation = after_result["prs"][0]["allocations"]["hosted"]
        activity = after_result["prs"][0]["review_activity"]["hosted"]
        self.assertEqual(activity["total"], 3)
        self.assertEqual([item["raw"] for item in activity["recent"]], [0, 0, 0])
        self.assertEqual(allocation["completed_count"], 1)
        self.assertTrue(allocation["taper_complete"])
        self.assertTrue(allocation["historical_taper_complete"])

    def test_accepted_allocated_result_resets_active_taper_without_hiding_history(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    checkpoint="hosted-before-allocation",
                    channel="hosted",
                    raw=0,
                    accepted=0,
                )
            ]
        }
        controller = self.grant_bounded_allocation(
            checkpoint="hosted-before-allocation",
            cap=2,
            minimum=1,
            evidence=evidence,
        )
        allocation = controller._state().allocations["1:hosted"]
        evidence[(1, "hosted")].append(
            self.allocation_evidence(
                checkpoint="hosted-accepted-after-allocation",
                channel="hosted",
                raw=1,
                accepted=1,
            )
        )
        snapshot = controller._bounded_allocation_evidence(allocation, evidence[(1, "hosted")])
        taper_baseline = controller._bounded_allocation_taper_baseline(
            allocation,
            snapshot,
            evidence[(1, "hosted")],
        )
        active_history = fresh_taper_history(
            controller._state(),
            Channel.HOSTED,
            evidence[(1, "hosted")],
            baseline_checkpoints=taper_baseline,
        )

        self.assertEqual([item.checkpoint for item in active_history], [])
        self.assertFalse(
            taper_satisfied(
                Channel.HOSTED,
                active_history,
                required_taper(controller._state(), Channel.HOSTED, active_history),
            )
        )
        self.assertEqual(
            [
                item["checkpoint"]
                for item in evidence[(1, "hosted")]
                if item.get("non_counting") is not True
            ],
            ["hosted-before-allocation", "hosted-accepted-after-allocation"],
        )

    def test_bounded_taper_cuts_duplicate_zero_rows_at_accepted_history_position(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(checkpoint="hosted-before-allocation", channel="hosted")
            ]
        }
        controller = self.grant_bounded_allocation(
            checkpoint="hosted-before-allocation",
            cap=3,
            minimum=1,
            evidence=evidence,
        )
        duplicate_before = self.allocation_evidence(
            checkpoint="duplicated-zero-result",
            channel="hosted",
            duplicate=True,
        )
        evidence[(1, "hosted")].extend(
            (
                duplicate_before,
                self.allocation_evidence(
                    checkpoint="hosted-accepted-result",
                    channel="hosted",
                    raw=1,
                    accepted=1,
                ),
                {
                    **duplicate_before,
                    "duplicate": True,
                },
            )
        )

        allocation = controller._state().allocations["1:hosted"]
        history = evidence[(1, "hosted")]
        snapshot = controller._bounded_allocation_evidence(allocation, history)
        taper_values = fresh_taper_history(
            controller._state(),
            Channel.HOSTED,
            history,
            baseline_checkpoints=controller._bounded_allocation_taper_baseline(
                allocation,
                snapshot,
                history,
            ),
        )

        self.assertEqual(snapshot["results"][0]["history_index"], 3)
        self.assertEqual(taper_values, [])
        self.assertFalse(taper_satisfied(Channel.HOSTED, taper_values, 1))

    def test_bounded_evidence_snapshot_is_reused_for_allocation_and_target_taper(self):
        controller = self.grant_bounded_allocation()
        with patch.object(
            controller,
            "_bounded_allocation_evidence",
            wraps=controller._bounded_allocation_evidence,
        ) as bounded_evidence:
            controller.status()

        self.assertEqual(bounded_evidence.call_count, 1)

    def test_minimum_and_maximum_after_taper_count_only_the_fresh_streak(self):
        dry_history = [
            self.allocation_evidence(checkpoint=f"cli-dry-{index}", channel="cli")
            for index in range(1, 4)
        ]
        evidence = {(1, "cli"): dry_history}
        controller = self.grant_bounded_allocation(
            channel="cli",
            checkpoint="cli-dry-3",
            cap=3,
            minimum=2,
            evidence=evidence,
            fresh_taper=True,
        )

        allocation = controller.status()["prs"][0]["allocations"]["cli"]
        self.assertEqual(allocation["status"], "CAP_ACTIVE")
        self.assertTrue(allocation["reopens_taper"])
        self.assertTrue(allocation["historical_taper_complete"])
        self.assertFalse(allocation["taper_complete"])
        self.assertEqual(allocation["completed_count"], 0)
        self.assertEqual(allocation["controlling_reason"], "minimum of 2 additional completed reviews is not met")
        self.assertEqual(controller.select_target("cli")["pr"], 1)

        evidence[(1, "cli")].append(
            self.allocation_evidence(checkpoint="cli-additional-1", channel="cli")
        )
        first = controller.status()["prs"][0]["allocations"]["cli"]
        self.assertEqual(first["status"], "CAP_ACTIVE")
        self.assertEqual(first["completed_count"], 1)
        self.assertFalse(first["taper_complete"])
        self.assertEqual(first["selection_control"], "minimum")

        evidence[(1, "cli")].append(
            self.allocation_evidence(checkpoint="cli-additional-2", channel="cli")
        )
        final = controller.status()["prs"][0]["allocations"]["cli"]
        self.assertEqual(final["status"], "CAP_ACTIVE")
        self.assertEqual(final["completed_count"], 2)
        self.assertEqual(final["maximum_additional_completed"], 3)
        self.assertFalse(final["taper_complete"])
        evidence[(1, "cli")].append(
            self.allocation_evidence(checkpoint="cli-additional-3", channel="cli")
        )
        tapered = controller.status()["prs"][0]["allocations"]["cli"]
        self.assertEqual(tapered["status"], "CAP_TAPERED")
        self.assertEqual(tapered["completed_count"], 3)
        self.assertTrue(tapered["taper_complete"])

    def test_maximum_only_after_taper_reopens_fresh_cli_streak(self):
        pre_taper_evidence = {
            (1, "cli"): [
                self.allocation_evidence(checkpoint="cli-first", channel="cli")
            ]
        }
        pre_taper = self.grant_bounded_allocation(
            channel="cli",
            checkpoint="cli-first",
            cap=2,
            minimum=None,
            evidence=pre_taper_evidence,
        )
        pre_taper_allocation = pre_taper.status()["prs"][0]["allocations"]["cli"]
        self.assertEqual(pre_taper_allocation["status"], "CAP_ACTIVE")
        self.assertFalse(pre_taper_allocation["taper_complete"])
        self.assertIsNone(pre_taper_allocation["minimum_additional_completed"])
        self.assertEqual(pre_taper.select_target("cli")["pr"], 1)
        pre_taper_evidence[(1, "cli")].extend(
            (
                self.allocation_evidence(checkpoint="cli-second", channel="cli"),
                self.allocation_evidence(checkpoint="cli-third", channel="cli"),
            )
        )
        self.assertEqual(
            pre_taper.status()["prs"][0]["allocations"]["cli"]["status"],
            "CAP_TAPERED",
        )

        evidence = {
            (1, "cli"): [
                self.allocation_evidence(checkpoint=f"cli-dry-{index}", channel="cli")
                for index in range(1, 4)
            ]
        }
        controller = self.grant_bounded_allocation(
            channel="cli",
            checkpoint="cli-dry-3",
            cap=2,
            minimum=None,
            evidence=evidence,
            fresh_taper=True,
        )

        allocation = controller.status()["prs"][0]["allocations"]["cli"]
        self.assertEqual(allocation["status"], "CAP_ACTIVE")
        self.assertTrue(allocation["reopens_taper"])
        self.assertTrue(allocation["historical_taper_complete"])
        self.assertIsNone(allocation["minimum_additional_completed"])
        self.assertEqual(allocation["maximum_additional_completed"], 2)
        self.assertEqual(allocation["completed_count"], 0)
        self.assertFalse(allocation["taper_complete"])
        self.assertEqual(controller.resolve_cli_target().snapshot.number, 1)

    def test_hosted_maximum_only_after_taper_uses_one_new_dry_result(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    checkpoint="hosted-old-dry",
                    channel="hosted",
                    accepted=0,
                )
            ]
        }
        controller = self.grant_bounded_allocation(
            channel="hosted",
            checkpoint="hosted-old-dry",
            cap=3,
            minimum=None,
            evidence=evidence,
            fresh_taper=True,
        )

        before_new_result = controller.status()["prs"][0]["allocations"]["hosted"]
        self.assertEqual(before_new_result["status"], "CAP_ACTIVE")
        self.assertTrue(before_new_result["reopens_taper"])
        self.assertTrue(before_new_result["historical_taper_complete"])
        self.assertFalse(before_new_result["taper_complete"])
        self.assertEqual(before_new_result["completed_count"], 0)
        self.assertEqual(controller.resolve_hosted_target().snapshot.number, 1)

        evidence[(1, "hosted")].append(
            self.allocation_evidence(checkpoint="hosted-new-dry", channel="hosted", accepted=0)
        )
        after_new_result = controller.status()["prs"][0]["allocations"]["hosted"]
        self.assertEqual(after_new_result["status"], "CAP_TAPERED")
        self.assertEqual(after_new_result["completed_count"], 1)
        self.assertTrue(after_new_result["taper_complete"])

    def test_maximum_stop_is_not_taper_and_does_not_waive_findings(self):
        evidence = {(1, "hosted"): [
            self.allocation_evidence(checkpoint="before-allocation", completed=False, channel="hosted")
        ]}
        controller = self.grant_bounded_allocation(
            channel="hosted",
            checkpoint=None,
            cap=1,
            minimum=0,
            evidence=evidence,
        )
        evidence[(1, "hosted")].append(
            self.allocation_evidence(checkpoint="accepted-useful-result", accepted=1, channel="hosted")
        )

        allocation = controller.status()["prs"][0]["allocations"]["hosted"]
        self.assertEqual(allocation["status"], "CAP_EXHAUSTED_PENDING")
        self.assertEqual(allocation["completed_count"], 1)
        self.assertFalse(allocation["taper_complete"])
        self.assertEqual(allocation["selection_control"], "unresolved_work")
        self.assertEqual(allocation["controlling_reason"], "cap exhausted; findings pending")
        with self.assertRaisesRegex(ControllerError, "cap exhausted; findings pending"):
            controller.resolve_hosted_target()

        values = controller.github.values
        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2
        completed = controller.status()["prs"][0]["allocations"]["hosted"]
        self.assertEqual(completed["status"], "CAP_AUDITED_STOP")
        self.assertFalse(completed["taper_complete"])
        self.assertEqual(completed["selection_control"], "maximum")
        self.assertIn("maximum", completed["controlling_reason"])

    def test_allocation_rejects_minimum_greater_than_maximum(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        with self.assertRaisesRegex(ControllerError, "cannot exceed the maximum"):
            self.grant_bounded_allocation(
                checkpoint="bounded-baseline",
                cap=2,
                minimum=3,
                evidence=evidence,
            )

    def test_bounded_cap_counts_across_corrected_heads_and_selects_next_pr_after_audited_stop(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    checkpoint="allocation-observation",
                    accepted=0,
                    completed=False,
                    channel="hosted",
                ),
            ]
        }
        values = {1: pr(1, HEAD_1)}
        heads = {"develop": BASE, "feature-1": HEAD_1}
        controller = self.grant_bounded_allocation(
            checkpoint=None,
            cap=2,
            evidence=evidence,
            values=values,
            heads=heads,
        )

        values[1] = pr(1, HEAD_2)
        heads["feature-1"] = HEAD_2
        controller.git.heads["feature-1"] = HEAD_2
        evidence[(1, "hosted")].append(
            self.allocation_evidence(
                head=HEAD_2,
                checkpoint="5847200187",
                accepted=3,
                channel="hosted",
            )
        )
        self.assertEqual(
            controller.status()["prs"][0]["allocations"]["hosted"]["used"], 1
        )

        evidence[(1, "hosted")].append(
            self.allocation_evidence(
                head=HEAD_2,
                checkpoint="bounded-second-result",
                accepted=1,
                channel="hosted",
            )
        )
        pending = controller.status()["prs"][0]["allocations"]["hosted"]
        self.assertEqual(pending["used"], 2)
        self.assertEqual(pending["status"], "CAP_EXHAUSTED_PENDING")
        self.assertEqual(pending["reason"], "cap exhausted; findings pending")
        with self.assertRaisesRegex(ControllerError, "cap exhausted; findings pending"):
            controller.resolve_hosted_target()

        values[1] = pr(1, HEAD_3)
        heads["feature-1"] = HEAD_3
        controller.git.heads["feature-1"] = HEAD_3
        values[2] = pr(2, "8" * 40, "feature-1", HEAD_3)
        heads["feature-2"] = "8" * 40
        controller.git.heads["feature-2"] = "8" * 40
        evidence[(2, "hosted")] = [
            self.allocation_evidence(
                2,
                "8" * 40,
                "before-second-pr",
                completed=False,
                channel="hosted",
                parent_identity="1",
                parent_head=HEAD_3,
            )
        ]
        controller.set_stack([1, 2])

        row = controller.status()["prs"][0]
        self.assertEqual(row["allocations"]["hosted"]["status"], "CAP_AUDITED_STOP")
        self.assertEqual(row["allocations"]["hosted"]["stop_basis"], "human_cap")
        self.assertEqual(controller.resolve_hosted_target().snapshot.number, 2)

    def test_bounded_cap_allows_normal_cli_taper_to_finish_early(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
        }
        evidence = {
            (1, "cli"): [
                self.allocation_evidence(checkpoint="cli-baseline", channel="cli")
            ],
            (2, "cli"): [
                self.allocation_evidence(
                    2,
                    HEAD_2,
                    "before-second-pr-cli",
                    completed=False,
                    channel="cli",
                    parent_identity="1",
                    parent_head=HEAD_1,
                )
            ],
        }
        heads = {"develop": BASE, "feature-1": HEAD_1, "feature-2": HEAD_2}
        controller = self.grant_bounded_allocation(
            channel="cli",
            checkpoint="cli-baseline",
            cap=4,
            evidence=evidence,
            values=values,
            heads=heads,
        )
        self.assertEqual(controller.resolve_cli_target().snapshot.number, 1)
        evidence[(1, "cli")].extend(
            (
                self.allocation_evidence(
                    checkpoint="cli-after-baseline-1", channel="cli"
                ),
                self.allocation_evidence(
                    checkpoint="cli-after-baseline-2", channel="cli"
                ),
            )
        )

        progress = controller.status()["prs"][0]["allocations"]["cli"]

        self.assertEqual(progress["status"], "CAP_TAPERED")
        self.assertEqual(progress["used"], 2)
        self.assertEqual(progress["remaining"], 2)
        self.assertEqual(controller.resolve_cli_target().snapshot.number, 2)

    def test_bounded_cap_failures_and_rate_limits_do_not_consume_results(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(checkpoint="baseline")]}
        controller = self.grant_bounded_allocation(
            checkpoint="baseline", cap=3, evidence=evidence
        )
        for index, flags in enumerate(
            (
                {"rate_limited": True},
                {"connection_failed": True},
                {"partial": True},
                {"duplicate": True},
                {"ambiguous": True},
                {"provisional": True},
                {"completed": False},
                {"attributable": False},
            ),
            start=1,
        ):
            evidence[(1, "hosted")].append(
                self.allocation_evidence(
                    checkpoint=f"non-counting-{index}", channel="hosted", **flags
                )
            )

        progress = controller.status()["prs"][0]["allocations"]["hosted"]

        self.assertEqual(progress["status"], "CAP_ACTIVE")
        self.assertEqual(progress["used"], 0)
        self.assertEqual(progress["remaining"], 3)

    def test_bounded_cap_counts_an_exact_in_flight_trigger_once_without_consuming_it(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(checkpoint="baseline")]}
        controller = self.grant_bounded_allocation(
            checkpoint="baseline", cap=2, evidence=evidence
        )
        in_flight = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:45",
            "trigger_id": 45,
            "held": True,
            "reason": HOSTED_ACTIVE_RESPONSE_REASON,
            "anchor": {
                "child_head": HEAD_1,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{HEAD_1[:4]}",
            },
        }
        evidence[(1, "hosted")].extend((in_flight, dict(in_flight)))

        progress = controller.status()["prs"][0]["allocations"]["hosted"]

        self.assertEqual(progress["status"], "CAP_ACTIVE")
        self.assertEqual(progress["used"], 0)
        self.assertEqual(progress["in_flight"], 1)
        self.assertEqual(progress["remaining"], 1)

    def test_bounded_grant_preserves_fixed_accepted_history_and_existing_posted_request(self):
        active = {
            "pr": 1,
            "head": HEAD_2,
            "checkpoint": "trigger:46",
            "trigger_id": 46,
            "held": True,
            "reason": HOSTED_ACTIVE_RESPONSE_REASON,
            "anchor": {
                "pr": 1,
                "child_head": HEAD_2,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{HEAD_2[:4]}",
            },
        }
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    checkpoint="5846432587", accepted=2, channel="hosted"
                ),
                self.allocation_evidence(
                    checkpoint="5847200187", accepted=3, channel="hosted"
                ),
                active,
            ]
        }
        controller = self.grant_bounded_allocation(
            checkpoint="5846432587",
            cap=2,
            evidence=evidence,
            values={1: pr(1, HEAD_2)},
            heads={"develop": BASE, "feature-1": HEAD_2},
        )

        progress = controller.status()["prs"][0]["allocations"]["hosted"]

        self.assertEqual(progress["used"], 0)
        self.assertEqual(progress["in_flight"], 1)
        self.assertEqual(progress["remaining"], 1)
        self.assertEqual(progress["status"], "CAP_ACTIVE")

    def test_bounded_cap_fails_closed_on_changed_parent_or_result_scope(self):
        for mode in ("parent", "result-scope"):
            with self.subTest(mode=mode):
                evidence = {
                    (1, "hosted"): [self.allocation_evidence(checkpoint="baseline")]
                }
                values = {1: pr(1, HEAD_1)}
                controller = self.grant_bounded_allocation(
                    checkpoint="baseline",
                    cap=2,
                    evidence=evidence,
                    values=values,
                    heads={"develop": BASE, "feature-1": HEAD_1},
                )
                if mode == "parent":
                    values[1] = pr(1, HEAD_2, "new-parent", BASE)
                    controller.git.heads["feature-1"] = HEAD_2
                    controller.git.heads["new-parent"] = BASE
                else:
                    evidence[(1, "hosted")].append(
                        self.allocation_evidence(
                            checkpoint="changed-result-scope",
                            parent_identity="another-parent",
                        )
                    )

                progress = controller.status()["prs"][0]["allocations"]["hosted"]

                self.assertEqual(progress["status"], "INVALID")
                self.assertEqual(progress["used"], 0)

    def test_bounded_cap_allows_scope_change_marker_before_exact_baseline(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    checkpoint="baseline",
                    observed_at="2026-09-20T00:00:00Z",
                    comment_id=200,
                )
            ]
        }
        controller = self.grant_bounded_allocation(
            checkpoint="baseline", cap=2, evidence=evidence
        )
        evidence[(1, "hosted")].append(
            {
                "pr": 1,
                "head": HEAD_1,
                "channel": "hosted",
                "kind": "scope_change",
                "scope_changed": True,
                "scope_change_malformed": False,
                "checkpoint": "scope-change:199",
                "comment_id": 199,
                "created_at": "2026-09-19T00:00:00Z",
                "updated_at": None,
                "observed_at": "2026-09-19T00:00:00Z",
                "completed": False,
                "attributable": False,
                "accepted": 0,
                "raw": 0,
                "non_counting": True,
            }
        )

        progress = controller.status()["prs"][0]["allocations"]["hosted"]

        self.assertEqual(progress["status"], "CAP_ACTIVE")
        self.assertEqual(progress["used"], 0)
        self.assertEqual(progress["remaining"], 2)

    def test_bounded_cap_holds_scope_change_effective_after_baseline_without_consumption(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(
                    checkpoint="baseline",
                    observed_at="2026-09-20T00:00:00Z",
                    comment_id=200,
                )
            ]
        }
        controller = self.grant_bounded_allocation(
            checkpoint="baseline", cap=2, evidence=evidence
        )
        evidence[(1, "hosted")].append(
            {
                "pr": 1,
                "head": HEAD_1,
                "channel": "hosted",
                "kind": "scope_change_malformed",
                "scope_changed": True,
                "scope_change_malformed": True,
                "checkpoint": "scope-change:199",
                "comment_id": 199,
                "created_at": "2026-09-19T00:00:00Z",
                "updated_at": "2026-09-21T00:00:00Z",
                "observed_at": "2026-09-21T00:00:00Z",
                "completed": False,
                "attributable": False,
                "accepted": 0,
                "raw": 0,
                "non_counting": True,
            }
        )

        progress = controller.status()["prs"][0]["allocations"]["hosted"]

        self.assertEqual(progress["status"], "INVALID")
        self.assertIn("scope changed after", progress["reason"])
        self.assertEqual(progress["used"], 0)

    def test_bounded_cap_fails_closed_when_scope_marker_timeline_or_time_is_unavailable(self):
        for failure in ("missing-timeline", "malformed-time"):
            with self.subTest(failure=failure):
                evidence = {
                    (1, "hosted"): [
                        self.allocation_evidence(
                            checkpoint="baseline",
                            observed_at="2026-09-20T00:00:00Z",
                            comment_id=200,
                        )
                    ]
                }
                controller = self.grant_bounded_allocation(
                    checkpoint="baseline", cap=2, evidence=evidence
                )
                if failure == "missing-timeline":
                    evidence[(1, "hosted")] = [
                        row
                        for row in evidence[(1, "hosted")]
                        if row.get("scope_timeline") is not True
                    ]
                else:
                    evidence[(1, "hosted")].append(
                        {
                            "pr": 1,
                            "head": HEAD_1,
                            "channel": "hosted",
                            "kind": "scope_change_malformed",
                            "scope_changed": True,
                            "scope_change_malformed": True,
                            "checkpoint": "scope-change:201",
                            "comment_id": 201,
                            "created_at": "not-a-time",
                            "updated_at": None,
                            "observed_at": "not-a-time",
                            "completed": False,
                            "attributable": False,
                            "accepted": 0,
                            "raw": 0,
                            "non_counting": True,
                        }
                    )

                progress = controller.status()["prs"][0]["allocations"]["hosted"]

                self.assertEqual(progress["status"], "INVALID")
                self.assertEqual(progress["used"], 0)

    def test_hosted_and_cli_bounded_allocations_consume_only_their_own_results(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(checkpoint="hosted-baseline", channel="hosted"),
                self.scope_timeline_evidence(1, "hosted"),
            ],
            (1, "cli"): [
                self.allocation_evidence(checkpoint="cli-baseline", channel="cli"),
                self.scope_timeline_evidence(1, "cli"),
            ],
        }
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])
        controller.decide_allocation(
            action="grant",
            pr=1,
            channel="hosted",
            head=HEAD_1,
            checkpoint="hosted-baseline",
            max_additional_completed=2,
            reason="bounded Hosted allowance",
        )
        controller.decide_allocation(
            action="grant",
            pr=1,
            channel="cli",
            head=HEAD_1,
            checkpoint="cli-baseline",
            max_additional_completed=2,
            reason="bounded CLI allowance",
        )
        evidence[(1, "hosted")].append(
            self.allocation_evidence(
                checkpoint="hosted-only-result", accepted=1, channel="hosted"
            )
        )

        allocations = controller.status()["prs"][0]["allocations"]

        self.assertEqual(allocations["hosted"]["used"], 1)
        self.assertEqual(allocations["cli"]["used"], 0)

    def test_bounded_allocation_can_be_renewed_or_cancelled_explicitly(self):
        controller = self.grant_bounded_allocation(checkpoint="baseline", cap=2)

        renewed = controller.decide_allocation(
            action="renew",
            pr=1,
            channel="hosted",
            head=HEAD_1,
            checkpoint="baseline",
            max_additional_completed=3,
            reason="explicitly replace the cap",
        )
        self.assertEqual(renewed["allocation"]["max_additional_completed"], 3)

        cancelled = controller.decide_allocation(
            action="cancel",
            pr=1,
            channel="hosted",
            head=HEAD_1,
            reason="explicitly remove the cap",
        )
        self.assertEqual(cancelled["allocations"], [])

    def test_bounded_dry_result_can_be_renewed_without_stop_authorization(self):
        evidence = {
            (1, "hosted"): [
                self.allocation_evidence(checkpoint="baseline", channel="hosted")
            ]
        }
        controller = self.grant_bounded_allocation(
            checkpoint="baseline",
            cap=2,
            evidence=evidence,
        )
        evidence[(1, "hosted")].append(
            self.allocation_evidence(checkpoint="allocated-dry", channel="hosted")
        )
        provider = controller._evidence_provider
        provider.stop_audit_calls.clear()

        renewed = controller.decide_allocation(
            action="renew",
            pr=1,
            channel="hosted",
            head=HEAD_1,
            checkpoint="allocated-dry",
            min_additional_completed=1,
            max_additional_completed=3,
            fresh_taper=True,
            reason="explicitly open a fresh bounded review tranche",
        )

        self.assertEqual(provider.stop_audit_calls, [])
        self.assertEqual(renewed["progress"]["status"], "CAP_ACTIVE")
        self.assertTrue(renewed["allocation"]["reopens_taper"])

    def test_bounded_renewal_preserves_accepted_finding_and_active_review_holds(self):
        for blocker, expected_error in (
            ("accepted", "unresolved actionable finding or thread"),
            ("active", "current review evidence is blocked"),
        ):
            with self.subTest(blocker=blocker):
                evidence = {
                    (1, "hosted"): [
                        self.allocation_evidence(checkpoint="baseline", channel="hosted")
                    ]
                }
                controller = self.grant_bounded_allocation(
                    checkpoint="baseline",
                    cap=2,
                    evidence=evidence,
                )
                provider = controller._evidence_provider
                if blocker == "accepted":
                    evidence[(1, "hosted")].append(
                        self.allocation_evidence(
                            checkpoint="allocated-findings", channel="hosted", accepted=1
                        )
                    )
                    provider.audit["unresolved_findings"] = ["pending finding"]
                else:
                    evidence[(1, "hosted")].append(
                        {
                            "pr": 1,
                            "head": HEAD_1,
                            "checkpoint": "active-review",
                            "held": True,
                            "reason": "unattributed active review",
                        }
                    )

                with self.assertRaisesRegex(ControllerError, expected_error):
                    controller.decide_allocation(
                        action="renew",
                        pr=1,
                        channel="hosted",
                        head=HEAD_1,
                        checkpoint="baseline",
                        min_additional_completed=1,
                        max_additional_completed=3,
                        reason="renew only after current obligations are safe",
                    )

    def test_first_review_can_be_allocated_without_historical_checkpoints(self):
        controller = self.make({1: pr(1, HEAD_1)})
        controller.set_stack([1])

        granted = controller.decide_allocation(
            action="grant", pr=1, channel="hosted", head=HEAD_1, reason="first complete review"
        )

        self.assertEqual(granted["allocation"]["baseline_checkpoints"], [])
        self.assertEqual(controller.status()["prs"][0]["allocations"]["hosted"]["status"], "PROMISED")

    def test_direct_human_stop_is_a_distinct_review_status_and_needs_no_ci_evidence(self):
        hosted = self.allocation_evidence(checkpoint="latest-hosted")
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {(1, "hosted"): [hosted]},
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="Overseer stopped further review discovery",
        )

        self.assertEqual(stopped["stop_basis"], "direct_human")
        self.assertEqual(stopped["reviewed_head"], HEAD_1)
        self.assertEqual(stopped["stop_head"], HEAD_1)
        self.assertEqual(controller.status()["prs"][0]["channels"]["hosted"], "HUMAN_STOPPED")

    def test_direct_human_stop_ignores_only_audit_proven_terminal_rate_limit(self):
        old_head = "7" * 40
        cooldown_until = "2999-01-01T00:00:00Z"
        latest = self.allocation_evidence(checkpoint="latest-hosted")
        rate_limit = {
            "pr": 1,
            "head": old_head,
            "checkpoint": "trigger:42",
            "rate_limited": True,
            "trigger_id": 42,
            "response_id": 43,
            "terminal": True,
            "attributable": True,
            "cooldown_until": cooldown_until,
        }
        provider = AuditedEvidence(
            {(1, "hosted"): [latest, rate_limit]},
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [],
                "terminal_rate_limits": [
                    {
                        "trigger_id": 42,
                        "response_id": 43,
                        "captured_head": old_head,
                        "cooldown_until": cooldown_until,
                        "terminal": True,
                        "attributable": True,
                    }
                ],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, provider, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="the prior Hosted response was a terminal rate limit",
        )

        self.assertEqual(stopped["stop_basis"], "direct_human")

    def test_direct_human_stop_does_not_ignore_unproven_rate_limit_history(self):
        old_head = "7" * 40
        provider = AuditedEvidence(
            {
                (1, "hosted"): [
                    self.allocation_evidence(checkpoint="latest-hosted"),
                    {
                        "pr": 1,
                        "head": old_head,
                        "checkpoint": "trigger:42",
                        "rate_limited": True,
                        "trigger_id": 42,
                        "response_id": 43,
                        "terminal": True,
                        "attributable": True,
                        "cooldown_until": "2999-01-01T00:00:00Z",
                    },
                ]
            },
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, provider, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "unresolved review evidence"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="a rate-limit flag without audit identity must still block",
            )

    def test_direct_human_stop_can_acknowledge_only_current_head_over_ceiling_notice(self):
        marker = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "over-ceiling:5748509184",
            "over_ceiling": True,
            "reason": "CodeRabbit skipped review because the PR exceeds its file ceiling",
        }
        provider = AuditedEvidence(
            {
                (1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted"), marker],
                (1, "cli"): [marker],
            },
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [marker["checkpoint"]],
            },
        )
        controller = self.make({1: pr(1, HEAD_1)}, provider, heads={"feature-1": HEAD_1})
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "unresolved actionable finding or thread"):
            controller.decide_stop(pr=1, channel="hosted", reason="human stop")
        with self.assertRaisesRegex(ControllerError, "requires the exact live head"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="human stop",
                acknowledge_over_ceiling=True,
            )
        with self.assertRaisesRegex(ControllerError, "does not match the live pull-request head"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                head=HEAD_2,
                reason="human stop",
                acknowledge_over_ceiling=True,
            )

        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            head=HEAD_1,
            reason="Overseer acknowledges the current 101-file provider limitation",
            acknowledge_over_ceiling=True,
        )
        self.assertEqual(stopped["stop_basis"], "direct_human")
        self.assertEqual(
            stopped["allocation"]["stop_reason"],
            "[acknowledged current over-ceiling limitation] Overseer acknowledges the current 101-file provider limitation",
        )
        status = controller.status()["prs"][0]
        self.assertEqual(status["allocations"]["hosted"]["status"], "STOPPED")

    def test_over_ceiling_acknowledgment_rejects_conflicting_duplicate_checkpoint(self):
        marker = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "over-ceiling:5748509184",
            "over_ceiling": True,
            "reason": "CodeRabbit skipped review because the PR exceeds its file ceiling",
        }
        conflicting_marker = {**marker, "head": HEAD_2}
        evidence = AuditedEvidence(
            {
                (1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted"), marker],
                (1, "cli"): [conflicting_marker],
            },
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [marker["checkpoint"]],
            },
        )
        controller = self.make({1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "conflicting duplicate over-ceiling checkpoint metadata"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                head=HEAD_1,
                reason="human stop",
                acknowledge_over_ceiling=True,
            )

    def test_over_ceiling_acknowledgment_cannot_be_spoofed_or_used_by_allocated_stop(self):
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {(1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted")]},
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "reserved acknowledgment prefix"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="[acknowledged current over-ceiling limitation] forged ordinary stop",
            )

        allocated_evidence = AuditedEvidence(
            {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        )
        allocated = self.grant_allocation(evidence=allocated_evidence)
        allocated_evidence[(1, "hosted")].append(
            self.allocation_evidence(checkpoint="allocated-result")
        )
        with self.assertRaisesRegex(ControllerError, "only to a direct human stop"):
            allocated.decide_stop(
                pr=1,
                channel="hosted",
                head=HEAD_1,
                checkpoint="allocated-result",
                reason="human stop",
                acknowledge_over_ceiling=True,
            )

    def test_over_ceiling_acknowledgment_does_not_ignore_other_stop_blockers(self):
        marker = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "over-ceiling:5748509184",
            "over_ceiling": True,
        }
        provider = AuditedEvidence(
            {(1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted"), marker]},
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [marker["checkpoint"], "review-threads:42"],
            },
        )
        controller = self.make({1: pr(1, HEAD_1)}, provider, heads={"feature-1": HEAD_1})
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "unresolved actionable finding or thread"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                head=HEAD_1,
                reason="human stop",
                acknowledge_over_ceiling=True,
            )

    def test_hosted_stop_is_blocked_by_current_head_cli_accepted_finding(self):
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {
                (1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted")],
                (1, "cli"): [self.allocation_evidence(checkpoint="cli-findings", accepted=1, channel="cli")],
            },
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "accepted findings need a published corrected head"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="CLI findings on the live head have no published correction",
            )

    def test_hosted_stop_allows_older_cli_findings_on_corrected_descendant(self):
        values = {1: pr(1, HEAD_2)}
        controller = self.make(
            values,
            {
                (1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted", head=HEAD_1)],
                (1, "cli"): [self.allocation_evidence(checkpoint="cli-findings", head=HEAD_1, accepted=1, channel="cli")],
            },
            heads={"feature-1": HEAD_2},
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="the accepted CLI findings are on an older head with a published descendant",
        )

        self.assertEqual(stopped["stop_head"], HEAD_2)
        self.assertEqual(stopped["stop_basis"], "direct_human")

    def test_resolved_cli_provisional_history_does_not_block_direct_hosted_stop(self):
        provisional = self.allocation_evidence(
            checkpoint="cli-provisional",
            completed=False,
            attributable=False,
            anchored=False,
            provisional=True,
            non_counting=True,
            channel="cli",
        )
        completed_cli = self.allocation_evidence(checkpoint="cli-completed", channel="cli")
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {
                (1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted")],
                (1, "cli"): [provisional, completed_cli],
            },
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="stop after the normal CLI review superseded provisional history",
        )

        self.assertEqual(stopped["stop_basis"], "direct_human")
        self.assertEqual(controller.status()["prs"][0]["channels"]["hosted"], "HUMAN_STOPPED")

    def test_current_cli_provisional_state_still_blocks_direct_hosted_stop(self):
        completed_cli = self.allocation_evidence(checkpoint="cli-completed", channel="cli")
        provisional = self.allocation_evidence(
            checkpoint="cli-current-provisional",
            completed=False,
            attributable=False,
            anchored=False,
            provisional=True,
            non_counting=True,
            channel="cli",
        )
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {
                (1, "hosted"): [self.allocation_evidence(checkpoint="latest-hosted")],
                (1, "cli"): [completed_cli, provisional],
            },
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "cli channel has provisional review evidence"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="a provisional result on the current head is unresolved",
            )

    def test_stale_later_cli_provisional_does_not_replace_latest_attributable_checkpoint(self):
        stale_head = "7" * 40
        provisional = self.allocation_evidence(
            head=stale_head,
            checkpoint="stale-cli-provisional",
            provisional=True,
            non_counting=True,
            channel="cli",
        )
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {
                (1, "cli"): [
                    self.allocation_evidence(checkpoint="latest-cli", channel="cli"),
                    provisional,
                ]
            },
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="cli",
            reason="the later provisional observation belongs to a stale head",
        )

        self.assertEqual(stopped["reviewed_head"], HEAD_1)
        self.assertEqual(stopped["stop_basis"], "direct_human")

    def test_audited_stop_survives_later_head_parent_and_patch_movement(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(checkpoint="reviewed-head")]}
        values = {1: pr(1, HEAD_1)}
        controller = self.make(values, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])
        controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="stop further hosted discovery after human review",
        )
        audit_count = len(controller._evidence_provider.stop_audit_calls)

        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2
        controller.git.heads["develop"] = PARENT
        controller.git.is_ancestor = lambda ancestor, descendant: (ancestor, descendant) != (HEAD_1, HEAD_2)
        moved = controller.status()["prs"][0]
        self.assertEqual(moved["reconciliation"], "PARENT_MOVED")
        self.assertEqual(moved["channels"]["hosted"], "HUMAN_STOPPED")
        self.assertEqual(moved["allocations"]["hosted"]["status"], "STOPPED")
        self.assertEqual(moved["allocations"]["hosted"]["stop_head"], HEAD_1)
        self.assertEqual(len(controller._evidence_provider.stop_audit_calls), audit_count)

    def test_stop_rejects_missing_or_stale_checkpoint_and_active_reservation(self):
        pending = {1: pr(1, HEAD_1)}
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.make(pending, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "completed attributable checkpoint"):
            controller.decide_stop(pr=1, channel="hosted", reason="human stop")

        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="latest"))
        with self.assertRaisesRegex(ControllerError, "does not match the latest attributable"):
            controller.decide_stop(pr=1, channel="hosted", checkpoint="stale", reason="human stop")

        provider = AuditedEvidence(
            {(1, "hosted"): [self.allocation_evidence(checkpoint="latest")]},
            audit={
                "complete": True,
                "active_reservations": ["review active"],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [],
            },
        )
        active = self.make(pending, provider, heads={"feature-1": HEAD_1})
        active.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "active review or reservation"):
            active.decide_stop(pr=1, channel="hosted", reason="human stop")

    def test_all_terminal_ambiguous_responses_require_exact_non_counting_retention(self):
        fingerprints = ("f" * 64, "e" * 64)
        evidence = AuditedEvidence(
            {
                (1, "hosted"): [
                    self.allocation_evidence(checkpoint="latest"),
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:123",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": fingerprints[0],
                        "trigger_id": 123,
                        "response_id": 124,
                    },
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:223",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": fingerprints[1],
                        "trigger_id": 223,
                        "response_id": 224,
                    },
                ]
            },
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [
                    {"fingerprint": fingerprints[0]},
                    {"fingerprint": fingerprints[1]},
                ],
                "retained_ambiguous": [
                    {"fingerprint": fingerprints[0], "trigger_id": 123, "response_id": 124},
                    {"fingerprint": fingerprints[1], "trigger_id": 223, "response_id": 224},
                ],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        state = controller.store.load()
        live, reconciliation = controller._reconciliation(state)
        current = controller._anchor(1, live[1], reconciliation.links[1])
        prior_fingerprint = "d" * 64
        transition = LegacyEvidenceTransition(
            pr=1,
            child_head=current.child_head,
            parent_identity=current.parent_identity,
            parent_head=current.parent_head,
            merge_base=current.merge_base,
            patch_id=current.patch_id,
            hosted_fingerprints=(prior_fingerprint,),
            reason="previous exact-anchor legacy evidence decision",
        )
        controller.store.update(
            lambda current_state: dataclasses.replace(
                current_state,
                legacy_transitions=current_state.legacy_transitions + (transition,),
            )
        )

        with self.assertRaisesRegex(
            ControllerError,
            "review stop is blocked by unretained terminal ambiguity",
        ):
            controller.decide_stop(pr=1, channel="hosted", reason="human stop")
        with self.assertRaisesRegex(ControllerError, "unretained terminal ambiguity"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="human stop",
                retain_ambiguous_fingerprints=(fingerprints[0],),
                ambiguity_reason="response has no attributable review object",
            )
        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="Overseer accepts this review-discovery stop",
            retain_ambiguous_fingerprints=fingerprints,
            ambiguity_reason="terminal response does not provide an attributable review object",
        )

        self.assertEqual(stopped["stop_basis"], "direct_human")
        self.assertEqual(stopped["retained_ambiguous_fingerprints"], list(fingerprints))
        self.assertEqual(
            stopped["allocation"]["retained_ambiguous_reason"],
            "terminal response does not provide an attributable review object",
        )
        self.assertEqual(evidence.stop_audit_calls[-1][1], fingerprints)
        self.assertEqual(evidence.stop_audit_calls[-1][2], ())

    def test_direct_hosted_stop_keeps_archived_ambiguity_historical_and_pins_latest(self):
        old_head = "7" * 40
        old_fingerprint = "d" * 64
        latest_fingerprint = "0072dfb6e4955aa58064a4181dec69a4daeb2b5757b50c9dd449fc4c997cf29b"
        evidence = AuditedEvidence(
            {
                (1, "hosted"): [
                    self.allocation_evidence(checkpoint="latest-hosted"),
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:5826936635",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": latest_fingerprint,
                        "trigger_id": 5826936635,
                        "response_id": 5826937548,
                    },
                ]
            },
            audit={
                "complete": True,
                "active_reservations": ["ambiguous"],
                "unmatched_responses": [],
                "ambiguous_responses": ["unattributed trigger response"],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [
                    {
                        "fingerprint": old_fingerprint,
                        "captured_head": old_head,
                        "response_at": "2026-09-01T00:00:00Z",
                    },
                    {
                        "fingerprint": latest_fingerprint,
                        "captured_head": HEAD_1,
                        "response_at": "2026-09-25T00:00:00Z",
                    },
                ],
                "retained_ambiguous": [
                    {"fingerprint": latest_fingerprint, "trigger_id": 5826936635, "response_id": 5826937548}
                ],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="preserve old non-counting ambiguity and retain the latest response",
            retain_ambiguous_fingerprints=(latest_fingerprint,),
            ambiguity_reason="latest terminal response is explicitly non-counting",
        )

        self.assertEqual(stopped["stop_basis"], "direct_human")
        self.assertEqual(stopped["allocation"]["retained_ambiguous_fingerprints"], [latest_fingerprint])
        self.assertEqual(
            stopped["allocation"]["retained_ambiguous_reason"],
            "latest terminal response is explicitly non-counting",
        )
        self.assertEqual(evidence.stop_audit_calls[-1][1], (latest_fingerprint,))

    def test_direct_hosted_stop_does_not_waive_live_or_unknown_ambiguity(self):
        latest_fingerprint = "0072dfb6e4955aa58064a4181dec69a4daeb2b5757b50c9dd449fc4c997cf29b"
        live_evidence = AuditedEvidence(
            {
                (1, "hosted"): [
                    self.allocation_evidence(checkpoint="latest-hosted"),
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:123",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": latest_fingerprint,
                        "trigger_id": 123,
                        "response_id": 124,
                    },
                ]
            },
            audit={
                "complete": True,
                "active_reservations": ["ambiguous"],
                "unmatched_responses": [],
                "ambiguous_responses": ["unattributed trigger response"],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [
                    {
                        "fingerprint": latest_fingerprint,
                        "captured_head": HEAD_1,
                        "response_at": "2026-09-25T00:00:00Z",
                    }
                ],
                "retained_ambiguous": [],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, live_evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "latest terminal ambiguity"):
            controller.decide_stop(pr=1, channel="hosted", reason="live ambiguity is unpinned")

        old_fingerprint = "d" * 64
        unknown_head_evidence = AuditedEvidence(
            {
                (1, "hosted"): [
                    self.allocation_evidence(checkpoint="latest-hosted"),
                    {
                        "pr": 1,
                        "head": "7" * 40,
                        "checkpoint": "trigger:old-unknown-head",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": old_fingerprint,
                        "trigger_id": 223,
                        "response_id": 224,
                    },
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:latest",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": latest_fingerprint,
                        "trigger_id": 123,
                        "response_id": 124,
                    },
                ]
            },
            audit={
                "complete": True,
                "active_reservations": ["ambiguous", "ambiguous"],
                "unmatched_responses": [],
                "ambiguous_responses": [
                    "unattributed trigger response",
                    "unattributed trigger response",
                ],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [
                    {
                        "fingerprint": old_fingerprint,
                        "response_at": "2026-09-01T00:00:00Z",
                    },
                    {
                        "fingerprint": latest_fingerprint,
                        "captured_head": HEAD_1,
                        "response_at": "2026-09-25T00:00:00Z",
                    },
                ],
                "retained_ambiguous": [
                    {"fingerprint": latest_fingerprint, "trigger_id": 123, "response_id": 124}
                ],
            },
        )
        unknown_head = self.make(
            {1: pr(1, HEAD_1)}, unknown_head_evidence, heads={"feature-1": HEAD_1}
        )
        unknown_head.set_stack([1])
        with self.assertRaisesRegex(
            ControllerError,
            "active review or reservation|unretained terminal ambiguity",
        ):
            unknown_head.decide_stop(
                pr=1,
                channel="hosted",
                reason="unknown old capture remains blocking",
                retain_ambiguous_fingerprints=(latest_fingerprint,),
                ambiguity_reason="latest terminal response is explicitly non-counting",
            )

    def test_direct_hosted_stop_rejects_current_ambiguity_missing_from_complete_audit(self):
        latest_fingerprint = "0072dfb6e4955aa58064a4181dec69a4daeb2b5757b50c9dd449fc4c997cf29b"
        unaudited_fingerprint = "8" * 64
        evidence = AuditedEvidence(
            {
                (1, "hosted"): [
                    self.allocation_evidence(checkpoint="latest-hosted"),
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:latest",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": latest_fingerprint,
                    },
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:unaccounted-current",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": unaudited_fingerprint,
                    },
                ]
            },
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [
                    {
                        "fingerprint": latest_fingerprint,
                        "captured_head": HEAD_1,
                        "response_at": "2026-09-25T00:00:00Z",
                    }
                ],
                "retained_ambiguous": [{"fingerprint": latest_fingerprint}],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "every current terminal response"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="current history has an unaccounted terminal ambiguity",
                retain_ambiguous_fingerprints=(latest_fingerprint,),
                ambiguity_reason="latest terminal response is explicitly non-counting",
            )

    def test_direct_cli_stop_preserves_historical_hosted_evidence_and_pins_latest(self):
        old_head = "7" * 40
        old_fingerprint = "d" * 64
        latest_fingerprint = "0072dfb6e4955aa58064a4181dec69a4daeb2b5757b50c9dd449fc4c997cf29b"
        evidence = AuditedEvidence(
            {
                (1, "cli"): [self.allocation_evidence(checkpoint="latest-cli")],
                (1, "hosted"): [
                    self.allocation_evidence(
                        head=old_head,
                        checkpoint="legacy-hosted-6-5",
                        accepted=5,
                        anchored=False,
                        held=True,
                    ),
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:5826936635",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": latest_fingerprint,
                        "trigger_id": 5826936635,
                        "response_id": 5826937548,
                    },
                ],
            },
            audit={
                "complete": True,
                "active_reservations": ["ambiguous"],
                "unmatched_responses": [],
                "historical_unmatched_responses": [
                    "a public Hosted checkpoint has no unique attributable trigger"
                ],
                "ambiguous_responses": ["unattributed trigger response"],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [
                    {
                        "fingerprint": old_fingerprint,
                        "captured_head": old_head,
                        "response_at": "2026-09-01T00:00:00Z",
                    },
                    {
                        "fingerprint": latest_fingerprint,
                        "captured_head": HEAD_1,
                        "response_at": "2026-09-25T00:00:00Z",
                    },
                ],
                "retained_ambiguous": [
                    {"fingerprint": latest_fingerprint, "trigger_id": 5826936635, "response_id": 5826937548}
                ],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="cli",
            reason="human stops further discovery after the latest CLI checkpoint",
            retain_ambiguous_fingerprints=(latest_fingerprint,),
            ambiguity_reason="latest terminal Hosted response remains explicitly non-counting",
        )

        self.assertEqual(stopped["stop_basis"], "direct_human")
        self.assertEqual(stopped["allocation"]["channel"], "cli")
        self.assertEqual(stopped["allocation"]["retained_ambiguous_fingerprints"], [latest_fingerprint])

    def test_direct_cli_stop_still_blocks_unpinned_current_hosted_ambiguity(self):
        latest_fingerprint = "0072dfb6e4955aa58064a4181dec69a4daeb2b5757b50c9dd449fc4c997cf29b"
        evidence = AuditedEvidence(
            {
                (1, "cli"): [self.allocation_evidence(checkpoint="latest-cli")],
                (1, "hosted"): [
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:5826936635",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": latest_fingerprint,
                        "trigger_id": 5826936635,
                        "response_id": 5826937548,
                    }
                ],
            },
            audit={
                "complete": True,
                "active_reservations": ["ambiguous"],
                "unmatched_responses": [],
                "ambiguous_responses": ["unattributed trigger response"],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [
                    {
                        "fingerprint": latest_fingerprint,
                        "captured_head": HEAD_1,
                        "response_at": "2026-09-25T00:00:00Z",
                    }
                ],
                "retained_ambiguous": [],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "latest terminal ambiguity"):
            controller.decide_stop(pr=1, channel="cli", reason="current Hosted ambiguity is unpinned")

    def test_direct_cli_stop_still_blocks_current_unmatched_response(self):
        evidence = AuditedEvidence(
            {(1, "cli"): [self.allocation_evidence(checkpoint="latest-cli")]},
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": ["current-head response has no attributable trigger"],
                "historical_unmatched_responses": ["old Hosted result remains historical"],
                "ambiguous_responses": [],
                "unresolved_findings": [],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "unmatched review response"):
            controller.decide_stop(pr=1, channel="cli", reason="current unmatched response remains")

    def test_direct_hosted_stop_preserves_old_unanchored_checkpoint_without_reauthorizing_history(self):
        old_head = "7" * 40
        old_checkpoint = self.allocation_evidence(
            head=old_head,
            checkpoint="legacy-hosted-6-5",
            accepted=5,
            anchored=False,
            held=True,
        )
        latest = self.allocation_evidence(checkpoint="latest-hosted", accepted=0)
        evidence = AuditedEvidence(
            {(1, "hosted"): [old_checkpoint, latest]},
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "historical_unmatched_responses": ["a public Hosted checkpoint has no unique attributable trigger"],
                "ambiguous_responses": [],
                "unresolved_findings": [],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            reason="retain historical findings while ending further discovery",
        )

        self.assertEqual(stopped["stop_basis"], "direct_human")
        self.assertEqual(stopped["checkpoint"], "latest-hosted")
        self.assertFalse(stopped["allocation"]["retained_ambiguous_fingerprints"])

    def test_allocated_stop_keeps_existing_unanchored_evidence_hold(self):
        evidence = AuditedEvidence(
            {(1, "hosted"): [self.allocation_evidence(completed=False)]},
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "historical_unmatched_responses": ["legacy checkpoint remains historical"],
                "ambiguous_responses": [],
                "unresolved_findings": [],
            },
        )
        controller = self.grant_allocation(evidence=evidence)
        evidence[(1, "hosted")].extend(
            (
                self.allocation_evidence(
                    head=BASE,
                    checkpoint="old-unanchored",
                    anchored=False,
                ),
                self.allocation_evidence(checkpoint="allocated-dry"),
            )
        )

        with self.assertRaisesRegex(ControllerError, "historical unmatched evidence outside a direct Hosted stop"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="allocated result cannot waive unrelated held evidence",
            )

    def test_direct_stop_still_rejects_unresolved_threads_and_unpublished_old_findings(self):
        latest = self.allocation_evidence(checkpoint="latest-hosted")
        unresolved = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "review-threads:0:1",
            "held": True,
            "reason": "one unresolved outdated thread",
        }
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {(1, "hosted"): [latest, unresolved]},
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "unresolved review evidence"):
            controller.decide_stop(pr=1, channel="hosted", reason="thread is still actionable")

        old_head = "7" * 40
        old_finding = self.allocation_evidence(
            head=old_head,
            checkpoint="legacy-hosted-4-4",
            accepted=4,
            anchored=False,
            held=True,
        )
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {(1, "hosted"): [old_finding, latest]},
            heads={"feature-1": HEAD_1},
        )
        controller.git.is_ancestor = lambda ancestor, descendant: (ancestor, descendant) != (old_head, HEAD_1)
        controller.set_stack([1])
        with self.assertRaisesRegex(ControllerError, "accepted findings need a published corrected head"):
            controller.decide_stop(pr=1, channel="hosted", reason="accepted old findings remain unpublished")

    def test_direct_stop_rejects_changed_head_and_wrong_terminal_fingerprint(self):
        provider = AuditedEvidence(
            {
                (1, "hosted"): [
                    self.allocation_evidence(checkpoint="latest-hosted"),
                    {
                        "pr": 1,
                        "head": HEAD_1,
                        "checkpoint": "trigger:123",
                        "held": True,
                        "terminal_ambiguous": True,
                        "fingerprint": "f" * 64,
                        "trigger_id": 123,
                        "response_id": 124,
                    },
                ]
            },
            audit={
                "complete": True,
                "active_reservations": [],
                "unmatched_responses": [],
                "ambiguous_responses": [],
                "unresolved_findings": [],
                "ambiguous_terminal_responses": [{"fingerprint": "f" * 64}],
                "retained_ambiguous": [{"fingerprint": "f" * 64}],
            },
        )
        controller = self.make(
            {1: pr(1, HEAD_1)}, provider, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        with self.assertRaisesRegex(ControllerError, "does not match the live pull-request head"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                head=HEAD_2,
                reason="pin the wrong live head",
                retain_ambiguous_fingerprints=("f" * 64,),
                ambiguity_reason="terminal response is non-counting",
            )
        with self.assertRaisesRegex(ControllerError, "unretained terminal ambiguity"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                reason="retain the wrong response",
                retain_ambiguous_fingerprints=("0" * 64,),
                ambiguity_reason="terminal response is non-counting",
            )

    def test_completed_result_consumes_once_until_an_explicit_stop(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.grant_allocation(evidence=evidence)
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="allocated-dry", accepted=1))

        result = controller.status()["prs"][0]

        self.assertEqual(result["allocations"]["hosted"]["status"], "EXHAUSTED_PENDING")
        self.assertEqual(result["allocations"]["hosted"]["checkpoint"], "allocated-dry")
        self.assertEqual(result["channels"]["hosted"], "READY")
        with self.assertRaisesRegex(ControllerError, "ALLOCATION_EXHAUSTED"):
            controller.resolve_hosted_target()
        with self.assertRaisesRegex(ControllerError, "accepted findings need a published corrected head"):
            controller.decide_stop(
                pr=1, channel="hosted", head=HEAD_1,
                checkpoint="allocated-dry", reason="accepted findings are not yet published",
            )

    def test_ineligible_completed_observation_does_not_invalidate_one_valid_allocation_result(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.grant_allocation(evidence=evidence)
        evidence[(1, "hosted")].extend(
            (
                self.allocation_evidence(checkpoint="allocated-unanchored", anchored=False),
                self.allocation_evidence(checkpoint="allocated-valid"),
            )
        )

        progress = controller.status()["prs"][0]["allocations"]["hosted"]

        self.assertEqual(progress["status"], "EXHAUSTED_PENDING")
        self.assertEqual(progress["checkpoint"], "allocated-valid")

    def test_two_valid_results_invalidate_one_review_allocation(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.grant_allocation(evidence=evidence)
        evidence[(1, "hosted")].extend(
            (
                self.allocation_evidence(checkpoint="allocated-first"),
                self.allocation_evidence(checkpoint="allocated-second"),
            )
        )

        progress = controller.status()["prs"][0]["allocations"]["hosted"]

        self.assertEqual(progress["status"], "INVALID")
        self.assertIn("multiple completed results match", progress["reason"])

    def test_consumed_dry_and_useful_allocations_cannot_be_renewed(self):
        for label, accepted in (("dry", 0), ("useful", 1)):
            with self.subTest(result=label):
                evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
                controller = self.grant_allocation(evidence=evidence)
                evidence[(1, "hosted")].append(
                    self.allocation_evidence(checkpoint=f"allocated-{label}", accepted=accepted)
                )

                with self.assertRaisesRegex(ControllerError, "consumed or"):
                    controller.decide_allocation(
                        action="renew",
                        pr=1,
                        channel="hosted",
                        head=HEAD_1,
                        reason="do not erase consumed progress",
                    )

    def test_invalid_progress_with_matching_completed_results_cannot_be_renewed(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        values = {1: pr(1, HEAD_1)}
        controller = self.grant_allocation(evidence=evidence, values=values)
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="allocated-nondescendant"))
        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2
        original_is_ancestor = controller.git.is_ancestor

        def reject_review_ancestry(ancestor, descendant):
            if (ancestor, descendant) == (HEAD_1, HEAD_2):
                return False
            return original_is_ancestor(ancestor, descendant)

        with (
            patch.object(controller.git, "is_ancestor", side_effect=reject_review_ancestry),
            self.assertRaisesRegex(ControllerError, "consumed or"),
        ):
            controller.decide_allocation(
                action="renew",
                pr=1,
                channel="hosted",
                head=HEAD_2,
                reason="do not renew after a non-descendant review result",
            )

        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.grant_allocation(evidence=evidence)
        evidence[(1, "hosted")].extend(
            (
                self.allocation_evidence(checkpoint="allocated-first"),
                self.allocation_evidence(checkpoint="allocated-second"),
            )
        )

        with self.assertRaisesRegex(ControllerError, "consumed or"):
            controller.decide_allocation(
                action="renew",
                pr=1,
                channel="hosted",
                head=HEAD_1,
                reason="do not renew after ambiguous completed results",
            )

    def test_stopped_allocation_cannot_be_renewed(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.grant_allocation(evidence=evidence)
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="allocated"))
        stopped = controller.decide_stop(
            pr=1,
            channel="hosted",
            head=HEAD_1,
            checkpoint="allocated",
            reason="stop discovery after the allocated result",
        )
        self.assertEqual(stopped["stop_basis"], "allocated")
        self.assertEqual(controller.status()["prs"][0]["channels"]["hosted"], "HUMAN_STOPPED")

        with self.assertRaisesRegex(ControllerError, "consumed or stopped"):
            controller.decide_allocation(
                action="renew",
                pr=1,
                channel="hosted",
                head=HEAD_1,
                reason="do not reopen a stopped allocation",
            )

    def test_pre_review_identity_move_can_still_renew_allocation(self):
        values = {1: pr(1, HEAD_1)}
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.grant_allocation(evidence=evidence, values=values)
        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2

        renewed = controller.decide_allocation(
            action="renew",
            pr=1,
            channel="hosted",
            head=HEAD_2,
            reason="rebind after a pre-review identity move",
        )

        self.assertEqual(renewed["allocation"]["head"], HEAD_2)
        self.assertEqual(renewed["allocation"]["baseline_checkpoints"], ["before-allocation"])

    def test_allocation_ancestry_lookup_failure_is_invalid_without_breaking_status_or_target_selection(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        values = {1: pr(1, HEAD_1)}
        controller = self.grant_allocation(evidence=evidence, values=values)
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="allocated"))
        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2
        original_is_ancestor = controller.git.is_ancestor

        def fail_corrected_head_lookup(ancestor, descendant):
            if (ancestor, descendant) == (HEAD_1, HEAD_2):
                raise ControllerError("git fetch failed")
            return original_is_ancestor(ancestor, descendant)

        with patch.object(controller.git, "is_ancestor", side_effect=fail_corrected_head_lookup):
            allocation = controller.status()["prs"][0]["allocations"]["hosted"]
            self.assertEqual(allocation["status"], "INVALID")
            self.assertIn("could not verify corrected-head ancestry", allocation["reason"])
            with self.assertRaises(ControllerError):
                controller.resolve_hosted_target()

    def test_productive_result_requires_corrected_head_before_stop(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        controller = self.grant_allocation(evidence=evidence)
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="allocated-findings", accepted=2))

        with self.assertRaisesRegex(ControllerError, "accepted findings need a published corrected head"):
            controller.decide_stop(
                pr=1,
                channel="hosted",
                head=HEAD_1,
                checkpoint="allocated-findings",
                reason="findings remain on the promised head",
            )

    def test_accepted_fix_head_can_be_stopped_after_publication(self):
        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        values = {1: pr(1, HEAD_1)}
        controller = self.grant_allocation(evidence=evidence, values=values)
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="allocated-findings", accepted=2))
        values[1] = pr(1, HEAD_2)
        controller.git.heads["feature-1"] = HEAD_2

        result = controller.decide_stop(
            pr=1,
            channel="hosted",
            head=HEAD_2,
            checkpoint="allocated-findings",
            reason="accepted findings are published and validated",
        )

        self.assertEqual(result["stop_basis"], "allocated")
        self.assertEqual(result["allocation"]["stop_reviewed_head"], HEAD_1)
        self.assertEqual(result["allocation"]["stop_head"], HEAD_2)
        audit_count = len(controller._evidence_provider.stop_audit_calls)
        self.assertEqual(controller.status()["prs"][0]["allocations"]["hosted"]["status"], "STOPPED")

        values[1] = pr(1, HEAD_3)
        controller.git.heads["feature-1"] = HEAD_3
        controller.git.heads["develop"] = PARENT
        moved = controller.status()["prs"][0]
        self.assertEqual(moved["channels"]["hosted"], "HUMAN_STOPPED")
        self.assertEqual(moved["allocations"]["hosted"]["status"], "STOPPED")
        self.assertEqual(len(controller._evidence_provider.stop_audit_calls), audit_count)

    def test_duplicate_stale_partial_and_rate_limited_results_do_not_consume(self):
        cases = (
            (
                "duplicate",
                [self.allocation_evidence(checkpoint="allocated"), self.allocation_evidence(checkpoint="allocated")],
                "INVALID",
            ),
            ("partial", [self.allocation_evidence(checkpoint="partial", completed=False)], "PROMISED"),
            ("rate-limited", [self.allocation_evidence(checkpoint="limited", rate_limited=True)], "PROMISED"),
        )
        for label, later, expected_status in cases:
            with self.subTest(result=label):
                evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
                controller = self.grant_allocation(evidence=evidence)
                evidence[(1, "hosted")].extend(later)
                view = controller.status()["prs"][0]["allocations"]["hosted"]
                self.assertNotEqual(view["status"], "HANDED_OFF")
                self.assertEqual(view["status"], expected_status)

        evidence = {(1, "hosted"): [self.allocation_evidence(completed=False)]}
        values = {1: pr(1, HEAD_1)}
        controller = self.grant_allocation(evidence=evidence, values=values)
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="stale", head=HEAD_2))
        values[1] = pr(1, HEAD_2)
        self.assertEqual(controller.status()["prs"][0]["allocations"]["hosted"]["status"], "INVALID")

    def test_hosted_and_cli_allocations_are_independent(self):
        values = {1: pr(1, HEAD_1)}
        evidence = {
            (1, "hosted"): [self.allocation_evidence(completed=False)],
            (1, "cli"): [self.allocation_evidence(completed=False)],
        }
        controller = self.make(values, evidence)
        controller.set_stack([1])
        controller.decide_allocation(
            action="grant", pr=1, channel="hosted", head=HEAD_1, reason="Hosted handoff"
        )

        result = controller.status()["prs"][0]

        self.assertIn("hosted", result["allocations"])
        self.assertNotIn("cli", result["allocations"])
        self.assertEqual(controller.resolve_cli_target().snapshot.number, 1)

    def test_cancel_removes_allocation_and_renew_rebinds_reason(self):
        controller = self.grant_allocation()
        renewed = controller.decide_allocation(
            action="renew", pr=1, channel="hosted", head=HEAD_1, reason="renewed after operator review"
        )
        self.assertEqual(renewed["allocation"]["reason"], "renewed after operator review")

        cancelled = controller.decide_allocation(
            action="cancel", pr=1, channel="hosted", head=HEAD_1, reason="cancelled by Overseer"
        )
        self.assertEqual(cancelled["allocations"], [])
        self.assertNotIn("hosted", controller.status()["prs"][0]["allocations"])

    def test_next_pr_is_selected_after_stop_but_parent_movement_blocks_it(self):
        values = {
            1: pr(1, HEAD_1),
            2: pr(2, HEAD_2, "feature-1", HEAD_1),
        }
        evidence = {
            (1, "hosted"): [self.allocation_evidence(completed=False)],
            (2, "hosted"): [
                self.allocation_evidence(
                    2,
                    HEAD_2,
                    "child-before",
                    completed=False,
                    parent_identity="1",
                    parent_head=HEAD_1,
                )
            ],
        }
        controller = self.make(values, evidence, heads={"feature-1": HEAD_1, "feature-2": HEAD_2})
        controller.set_stack([1, 2])
        controller.decide_allocation(
            action="grant", pr=1, channel="hosted", head=HEAD_1, reason="parent handoff"
        )
        evidence[(1, "hosted")].append(self.allocation_evidence(checkpoint="allocated"))
        controller.decide_stop(
            pr=1,
            channel="hosted",
            head=HEAD_1,
            checkpoint="allocated",
            reason="stop review discovery on the first stack item",
        )
        self.assertEqual(controller.resolve_hosted_target().snapshot.number, 2)

        values[1] = pr(1, "7" * 40)
        controller.git.heads["feature-1"] = "7" * 40
        with self.assertRaises(ControllerError):
            controller.resolve_hosted_target()
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

    def test_default_git_provider_returns_tree_for_exact_clean_test_merge_on_installed_git(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            base, head, expected_tree = _commit_tree_case(root)
            refs_before = _git(root, "for-each-ref", "--format=%(refname) %(objectname)")
            status_before = _git(root, "status", "--porcelain", "--untracked-files=all")
            worktrees_before = _git(root, "worktree", "list", "--porcelain")

            actual = DefaultGitProvider(root, timeout_seconds=11).test_merge_tree(base, head)

            self.assertEqual(actual, expected_tree)
            self.assertEqual(_git(root, "for-each-ref", "--format=%(refname) %(objectname)"), refs_before)
            self.assertEqual(_git(root, "status", "--porcelain", "--untracked-files=all"), status_before)
            self.assertEqual(_git(root, "worktree", "list", "--porcelain"), worktrees_before)

    def test_default_git_provider_rejects_conflict_and_cleans_temporary_worktree(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "repository"
            root.mkdir()
            base, head, _ = _commit_tree_case(root, conflict=True)
            worktrees_before = _git(root, "worktree", "list", "--porcelain")

            with self.assertRaisesRegex(ControllerError, "do not produce a clean test merge"):
                DefaultGitProvider(root, timeout_seconds=11).test_merge_tree(base, head)

            self.assertEqual(_git(root, "worktree", "list", "--porcelain"), worktrees_before)

    def test_test_merge_falls_back_without_lfs_smudge_when_merge_tree_is_unavailable(self):
        tree = "9" * 40
        calls = []

        def run(args, **kwargs):
            calls.append((args, kwargs))
            git_args = args[args.index("-C") + 2:]
            if git_args[:2] == ["merge-tree", "--write-tree"]:
                return CompletedProcess(args, 129, "", "unknown option --write-tree\n")
            if git_args[:2] == ["worktree", "add"]:
                return CompletedProcess(args, 0, "", "")
            if "merge" in git_args:
                return CompletedProcess(args, 0, "", "")
            if git_args == ["write-tree"]:
                return CompletedProcess(args, 0, f"{tree}\n", "")
            if git_args[:2] == ["cat-file", "-t"]:
                return CompletedProcess(args, 0, "tree\n", "")
            if git_args[:2] == ["worktree", "remove"]:
                return CompletedProcess(args, 0, "", "")
            raise AssertionError(f"unexpected command: {args}")

        with tempfile.TemporaryDirectory() as directory:
            actual = test_merge_tree(directory, BASE, HEAD_1, run=run, timeout_seconds=9)

        self.assertEqual(actual, tree)
        self.assertTrue(calls)
        fallback_add = next(args for args, _ in calls if "worktree" in args and "add" in args)
        self.assertIn("filter.lfs.process=", fallback_add)
        self.assertIn("filter.lfs.smudge=cat", fallback_add)

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

    def test_reconciliation_caches_only_successful_test_merge_trees_by_exact_tuple(self):
        controller = self.make({1: pr(1, HEAD_1)})
        controller.set_stack([1])

        controller.status()
        controller.status()
        self.assertEqual(controller.git.test_merge_calls, [(BASE, HEAD_1)])

        next_head = "8" * 40
        controller.github.values[1] = pr(1, next_head)
        controller.status()
        self.assertEqual(controller.git.test_merge_calls[-1], (BASE, next_head))

        next_base = "9" * 40
        controller.github.values[1] = pr(1, next_head, base_tip=next_base)
        controller.git.heads["develop"] = next_base
        controller.status()
        self.assertEqual(controller.git.test_merge_calls[-1], (next_base, next_head))
        self.assertEqual(len(controller.git.test_merge_calls), 3)

    def test_failed_test_merge_is_not_cached(self):
        controller = self.make({1: pr(1, HEAD_1)})
        controller.set_stack([1])
        controller.git.test_merge_error = ControllerError("temporary test-merge failure")

        controller.status()
        self.assertEqual(controller.git.test_merge_calls, [(BASE, HEAD_1)])

        controller.git.test_merge_error = None
        controller.status()
        controller.status()
        self.assertEqual(controller.git.test_merge_calls, [(BASE, HEAD_1), (BASE, HEAD_1)])

    def _enable_batch_status(self, controller, values, batch_values=None):
        selected = batch_values if batch_values is not None else values
        calls = []

        def fetch(numbers):
            calls.append(tuple(numbers))
            return {number: _batch_identity(selected[number]) for number in numbers}

        controller.github.batch_pull_requests = fetch
        return calls

    def test_status_overview_shifts_four_pr_window_after_front_merge(self):
        ordered = list(range(1, 7))
        before_values, before_heads = _stacked_prs(6)
        before_evidence = CountingEvidence()
        before = self.make(before_values, before_evidence, heads=before_heads)
        before.set_stack(ordered)
        self._enable_batch_status(before, before_values)

        before_report = before.status_overview()

        self.assertEqual(before_report["detail_window"]["deep_prs"], [1, 2, 3, 4])
        self.assertEqual(before_report["prs"][4]["evidence_status"], "unknown")
        self.assertEqual(before_report["prs"][4]["channels"], {"hosted": "NOT_CHECKED", "cli": "NOT_CHECKED"})
        self.assertTrue(all(pr_number <= 4 for pr_number, _ in before_evidence.history_reads))

        after_values, after_heads = _stacked_prs(6, merged=(1,))
        after_evidence = CountingEvidence()
        after = self.make(after_values, after_evidence, heads=after_heads)
        after.set_stack(ordered)
        self._enable_batch_status(after, after_values)

        after_report = after.status_overview()

        self.assertEqual(after_report["detail_window"]["deep_prs"], [2, 3, 4, 5])
        self.assertEqual(after_report["prs"][5]["evidence_status"], "unknown")
        self.assertTrue(all(pr_number <= 5 for pr_number, _ in after_evidence.history_reads))

    def test_status_overview_reports_divergent_controller_selected_targets(self):
        values, heads = _stacked_prs(3)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        hosted = self.review_evidence(controller, 1, "hosted", "hosted-1")
        cli = self.review_evidence(controller, 1, "cli", "cli-1")
        evidence[(1, "hosted")] = [hosted]
        evidence[(1, "cli")] = [cli]
        batch_calls = self._enable_batch_status(controller, values)

        report = controller.status_overview()

        self.assertEqual(report["review_targets"]["hosted"]["pr"], 2)
        self.assertEqual(report["review_targets"]["hosted"]["status"], "MISSING_EVIDENCE")
        self.assertEqual(report["review_targets"]["cli"]["pr"], 1)
        self.assertEqual(report["review_targets"]["cli"]["status"], "READY")
        self.assertEqual(report["ordered_prs"], [1, 2, 3])
        self.assertEqual(batch_calls, [tuple(values)])

    def test_status_overview_keeps_front_target_when_downstream_parent_is_stale(self):
        values, heads = _stacked_prs(6)
        values[2] = dataclasses.replace(values[2], base_tip="9" * 40)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        for channel in ("hosted", "cli"):
            useful = self.review_evidence(controller, 1, channel, f"{channel}-front")
            useful.update({"raw": 1, "accepted": 1})
            evidence[(1, channel)] = [useful]
        self._enable_batch_status(controller, values)

        report = controller.status_overview()

        self.assertEqual(report["prs"][0]["reconciliation"], "COHERENT")
        self.assertEqual(report["prs"][1]["reconciliation"], "UNRECONCILED")
        for channel in ("hosted", "cli"):
            self.assertEqual(report["review_targets"][channel]["pr"], 1)
            self.assertEqual(report["review_targets"][channel]["status"], "READY")

    def test_status_overview_keeps_selected_target_fail_closed_when_its_parent_is_stale(self):
        values, heads = _stacked_prs(4)
        values[1] = dataclasses.replace(values[1], base_tip="9" * 40)
        controller = self.make(values, CountingEvidence(), heads=heads)
        controller.set_stack(list(values))
        self._enable_batch_status(controller, values)

        report = controller.status_overview()

        for channel in ("hosted", "cli"):
            self.assertIsNone(report["review_targets"][channel]["pr"])
            self.assertEqual(report["review_targets"][channel]["status"], "UNKNOWN")

    def test_status_overview_keeps_coherent_deep_target_with_historic_saved_identity(self):
        values, heads = _stacked_prs(3)
        controller = self.make(values, CountingEvidence(), heads=heads)
        controller.set_stack(list(values))
        historical_decision = StackReconciliationDecision(
            pr=1,
            channel="hosted",
            checkpoint="historic-parent",
            prior_head="9" * 40,
            child_head="8" * 40,
            parent_identity="develop",
            parent_head=BASE,
            merge_base=BASE,
            patch_id="historic-patch",
            reason="historical identity predates the coherent current reconciliation",
        )
        controller.store.update(
            lambda state: dataclasses.replace(state, reconciliations=(historical_decision,))
        )
        self._enable_batch_status(controller, values)

        report = controller.status_overview()

        self.assertEqual(report["prs"][0]["reconciliation"], "COHERENT")
        self.assertEqual(report["review_targets"]["hosted"]["pr"], 1)
        self.assertEqual(report["review_targets"]["hosted"]["status"], "MISSING_EVIDENCE")
        self.assertEqual(report["review_targets"]["cli"]["pr"], 1)
        self.assertEqual(report["review_targets"]["cli"]["status"], "MISSING_EVIDENCE")

    def test_status_overview_expands_only_until_both_channel_targets_are_selected(self):
        values, heads = _stacked_prs(12)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        for pr_number in range(1, 5):
            evidence[(pr_number, "hosted")] = [
                self.review_evidence(controller, pr_number, "hosted", f"hosted-{pr_number}")
            ]
            evidence[(pr_number, "cli")] = [
                self.review_evidence(controller, pr_number, "cli", f"cli-{pr_number}-{round_number}")
                for round_number in range(1, 4)
            ]
        batch_calls = self._enable_batch_status(controller, values)
        pull_calls = []
        original_pull = controller.github.pull_request

        def pull(number):
            pull_calls.append(number)
            return original_pull(number)

        controller.github.pull_request = pull

        report = controller.status_overview()

        self.assertEqual(report["review_targets"]["hosted"]["pr"], 5)
        self.assertEqual(report["review_targets"]["cli"]["pr"], 5)
        self.assertEqual(report["detail_window"]["deep_prs"], list(range(1, 9)))
        self.assertEqual(report["prs"][8]["evidence_status"], "unknown")
        self.assertEqual(batch_calls, [tuple(values)])
        self.assertEqual(pull_calls, list(range(1, 9)))
        self.assertEqual(set(evidence.history_reads), {
            (pr_number, channel)
            for pr_number in range(1, 9)
            for channel in ("hosted", "cli")
        })
        self.assertEqual(len(evidence.history_reads), 16)

    def test_status_overview_grows_long_tail_target_window_in_batches(self):
        values, heads = _stacked_prs(80)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        for pr_number in range(1, 49):
            hosted = self.review_evidence(controller, pr_number, "hosted", f"hosted-{pr_number}")
            evidence[(pr_number, "hosted")] = [hosted]
            evidence[(pr_number, "cli")] = [
                {**hosted, "channel": "cli", "checkpoint": f"cli-{pr_number}-{round_number}"}
                for round_number in range(1, 4)
            ]
        self._enable_batch_status(controller, values)
        target_windows = []
        original_status_from_state = controller._status_from_state

        def track_target_window(*args, **kwargs):
            target_windows.append(tuple(kwargs.get("review_target_prs", ())))
            return original_status_from_state(*args, **kwargs)

        controller._status_from_state = track_target_window

        report = controller.status_overview()

        self.assertEqual(report["review_targets"]["hosted"]["pr"], 49)
        self.assertEqual(report["review_targets"]["cli"]["pr"], 49)
        self.assertEqual(
            [len(window) for window in target_windows],
            [4, 8, 16, 32, 64],
        )
        self.assertEqual(report["detail_window"]["deep_prs"], list(range(1, 65)))
        self.assertEqual(report["ordered_prs"], list(range(1, 81)))

    def test_status_overview_bounds_provider_reads_while_growing_window(self):
        values, heads = _stacked_prs(20)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        for pr_number in range(1, 9):
            hosted = self.review_evidence(controller, pr_number, "hosted", f"hosted-{pr_number}")
            evidence[(pr_number, "hosted")] = [hosted]
            evidence[(pr_number, "cli")] = [
                {**hosted, "channel": "cli", "checkpoint": f"cli-{pr_number}-{round_number}"}
                for round_number in range(1, 4)
            ]
        batch_calls = self._enable_batch_status(controller, values)
        pull_calls = []
        original_pull = controller.github.pull_request

        def pull(number):
            pull_calls.append(number)
            return original_pull(number)

        controller.github.pull_request = pull

        report = controller.status_overview()

        deeply_checked_prs = set(range(1, 17))
        self.assertEqual(report["review_targets"]["hosted"]["pr"], 9)
        self.assertEqual(report["review_targets"]["cli"]["pr"], 9)
        self.assertEqual(batch_calls, [tuple(values)])
        self.assertEqual(evidence.active_target_reads, 1)
        self.assertEqual(len(pull_calls), len(deeply_checked_prs))
        self.assertEqual(set(pull_calls), deeply_checked_prs)
        self.assertTrue(all(pull_calls.count(pr_number) == 1 for pr_number in deeply_checked_prs))
        self.assertEqual(len(evidence.history_reads), 2 * len(deeply_checked_prs))
        self.assertEqual(
            set(evidence.history_reads),
            {
                (pr_number, channel)
                for pr_number in deeply_checked_prs
                for channel in ("hosted", "cli")
            },
        )

    def test_status_overview_skips_merged_and_human_stopped_targets(self):
        values, heads = _stacked_prs(4, merged=(1,))
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        stopped = self.review_evidence(controller, 2, "hosted", "hosted-stop-2")
        evidence[(2, "hosted")] = [stopped]
        controller.decide_stop(
            pr=2,
            channel="hosted",
            reason="skip the already adjudicated Hosted target",
            head=stopped["head"],
        )
        self._enable_batch_status(controller, values)

        report = controller.status_overview()

        self.assertEqual(report["review_targets"]["hosted"]["pr"], 3)
        self.assertEqual(report["review_targets"]["cli"]["pr"], 2)
        self.assertEqual(report["prs"][0]["merged"], True)
        self.assertEqual(report["prs"][1]["channels"]["hosted"], "HUMAN_STOPPED")

    def test_status_overview_distinguishes_blocked_no_target_and_unknown_provider_state(self):
        values, heads = _stacked_prs(2)
        blocked_evidence = CountingEvidence({
            (1, "hosted"): [{"pr": 1, "head": HEAD_1, "checkpoint": "held", "held": True}]
        })
        blocked = self.make(values, blocked_evidence, heads=heads)
        blocked.set_stack(list(values))
        self._enable_batch_status(blocked, values)

        blocked_report = blocked.status_overview()

        self.assertEqual(blocked_report["review_targets"]["hosted"]["pr"], 1)
        self.assertEqual(blocked_report["review_targets"]["hosted"]["status"], "HELD")

        merged_values, merged_heads = _stacked_prs(2, merged=(1, 2))
        no_target = self.make(merged_values, heads=merged_heads)
        no_target.set_stack(list(merged_values))
        self._enable_batch_status(no_target, merged_values)

        no_target_report = no_target.status_overview()

        self.assertIsNone(no_target_report["review_targets"]["hosted"]["pr"])
        self.assertEqual(no_target_report["review_targets"]["hosted"]["status"], "COMPLETE")

        partial = self.make(values, CountingEvidence(), heads=heads)
        partial.set_stack(list(values))
        partial.github.batch_pull_requests = lambda numbers: {
            number: _batch_identity(values[number]) for number in numbers if number != 2
        }

        partial_report = partial.status_overview()

        self.assertIsNone(partial_report["review_targets"]["hosted"]["pr"])
        self.assertEqual(partial_report["review_targets"]["hosted"]["status"], "UNKNOWN")
        self.assertIn("missing PR #2", partial_report["review_targets"]["hosted"]["reason"])

    def test_status_overview_deepens_active_tail_target_without_allocation(self):
        values, heads = _stacked_prs(7)
        evidence = CountingEvidence(active_targets={6})
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        batch_calls = self._enable_batch_status(controller, values)

        report = controller.status_overview()

        self.assertEqual(controller.store.load().allocations, {})
        self.assertEqual(report["detail_window"]["active_targets"], [6])
        self.assertEqual(report["detail_window"]["deep_prs"], [1, 2, 3, 4, 6])
        self.assertFalse(any(pr_number == 5 for pr_number, _ in evidence.history_reads))
        self.assertTrue(all(pr_number in {1, 2, 3, 4, 6} for pr_number, _ in evidence.history_reads))
        tail = report["prs"][4]
        self.assertEqual(tail["detail_level"], "identity_only")
        self.assertEqual(tail["evidence_status"], "unknown")
        self.assertEqual(tail["channels"], {"hosted": "NOT_CHECKED", "cli": "NOT_CHECKED"})
        self.assertIn("not fetched", report["detail_window"]["tail_evidence"])
        self.assertEqual(batch_calls, [tuple(values)])

    def test_live_evidence_probe_includes_active_hosted_trigger_without_allocation(self):
        trigger_at = "2026-09-26T01:00:00Z"
        response_at = "2026-09-26T01:02:00Z"
        trigger_id = 701
        identity = {
            "number": 6,
            "headRefOid": HEAD_2,
            "comments": {
                "nodes": [
                    {
                        "databaseId": trigger_id,
                        "author": {"login": "ben"},
                        "body": hosted.FULL_COMMAND,
                        "createdAt": trigger_at,
                        "url": "https://github.test/comments/701",
                    },
                    {
                        "databaseId": 702,
                        "author": {"login": "coderabbitai[bot]"},
                        "body": "Full review triggered",
                        "createdAt": response_at,
                        "url": "https://github.test/comments/702",
                    },
                ]
            },
            "reviews": {"nodes": []},
        }
        record_path = Path("/tmp/current-trigger.json")
        record = {
            "status": "posted",
            "repository": "owner/repo",
            "pr_number": 6,
            "head_sha": HEAD_2,
            "trigger": {
                "id": trigger_id,
                "created_at": trigger_at,
                "url": "https://github.test/comments/701",
                "author_login": "ben",
                "type": "full",
                "command": hosted.FULL_COMMAND,
            },
        }
        evidence = LiveEvidence("owner/repo", object())
        with (
            patch("pr_review.runtime.hosted.current_trigger_record_paths", return_value=[record_path]),
            patch("pr_review.runtime.hosted.load_trigger_reservation", return_value=record),
            patch.object(LiveEvidence, "_request_lock_is_held", return_value=False),
        ):
            active = evidence.active_review_targets((6,), {6: identity})

        self.assertEqual(active, {6})

    def test_live_evidence_probe_detects_manual_hosted_command_without_record(self):
        with tempfile.TemporaryDirectory() as directory:
            state_store = StateStore(Path(directory) / "firemud" / "pr-review-stack.json")
            evidence = LiveEvidence("owner/repo", object(), state_store)
            identity = {
                5: {"comments": {"nodes": []}, "reviews": {"nodes": []}},
                6: {
                    "comments": {
                        "nodes": [
                            {
                                "author": {"login": "human-reviewer"},
                                "body": hosted.FULL_COMMAND,
                            }
                        ]
                    },
                    "reviews": {"nodes": []},
                },
            }
            with (
                patch("pr_review.runtime.hosted.current_trigger_record_paths", return_value=[]),
                patch(
                    "pr_review.runtime.hosted.default_trigger_record_path",
                    side_effect=lambda _repo, pr: Path(directory) / "records" / str(pr) / "trigger.json",
                ),
            ):
                active = evidence.active_review_targets((5, 6), identity)

        self.assertEqual(active, {6})

    def test_live_evidence_probe_deepens_all_candidates_for_unscoped_cli_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            state_store = StateStore(Path(directory) / "firemud" / "pr-review-stack.json")
            lock_path = Path(directory) / "firemud" / "pr-review" / "cli.lock"
            lock_path.parent.mkdir(parents=True)
            lock_path.touch()
            with lock_path.open("r+") as lock_handle:
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                evidence = LiveEvidence("owner/repo", object(), state_store)
                identities = {
                    number: {"comments": {"nodes": []}, "reviews": {"nodes": []}}
                    for number in (5, 6)
                }
                with (
                    patch("pr_review.runtime.hosted.current_trigger_record_paths", return_value=[]),
                    patch(
                        "pr_review.runtime.hosted.default_trigger_record_path",
                        side_effect=lambda _repo, pr: Path(directory) / "records" / str(pr) / "trigger.json",
                    ),
                ):
                    active = evidence.active_review_targets((5, 6), identities)
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)

        self.assertEqual(active, {5, 6})

    def test_status_overview_marks_moved_tail_stale_without_readiness(self):
        values, heads = _stacked_prs(6)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        batch_values = dict(values)
        batch_values[5] = dataclasses.replace(values[5], base_ref="unexpected-parent")
        self._enable_batch_status(controller, values, batch_values)

        report = controller.status_overview()

        tail = report["prs"][4]
        self.assertEqual(tail["evidence_status"], "stale")
        self.assertEqual(tail["reconciliation"], "UNRECONCILED")
        self.assertNotIn("READY", tail["channels"].values())
        self.assertNotIn("COMPLETE", tail["channels"].values())
        self.assertTrue(all(pr_number <= 4 for pr_number, _ in evidence.history_reads))

    def test_status_overview_missing_batch_identity_fails_closed_without_deep_reads(self):
        values, heads = _stacked_prs(6)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        batch_values = dict(values)
        batch_values[5] = None
        controller.github.batch_pull_requests = lambda numbers: {
            number: None if batch_values[number] is None else _batch_identity(batch_values[number])
            for number in numbers
        }

        report = controller.status_overview()

        self.assertEqual(report["status"], "UNKNOWN")
        self.assertEqual(report["detail_window"]["batch_status"], "failed")
        self.assertTrue(all(item["evidence_status"] == "unknown" for item in report["prs"]))
        self.assertEqual(evidence.history_reads, [])

    def test_selected_tail_status_reads_ancestors_but_no_later_prs(self):
        values, heads = _stacked_prs(7)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        pull_calls = []
        original = controller.github.pull_request

        def pull(number):
            pull_calls.append(number)
            return original(number)

        controller.github.pull_request = pull

        report = controller.status_for_pr(6)

        self.assertEqual([item["pr"] for item in report["prs"]], [1, 2, 3, 4, 5, 6])
        self.assertTrue(all(number <= 6 for number in pull_calls))
        self.assertTrue(all(pr_number <= 6 for pr_number, _ in evidence.history_reads))
        self.assertEqual(report["scope"], "selected PR and configured ancestors")

    def test_review_action_preflights_do_not_use_overview_batch_or_tail_scope(self):
        values, heads = _stacked_prs(3)
        evidence = CountingEvidence()
        controller = self.make(values, evidence, heads=heads)
        controller.set_stack(list(values))
        batch_calls = []
        controller.github.batch_pull_requests = lambda numbers: batch_calls.append(tuple(numbers))

        target = controller.resolve_hosted_target()
        allocation = controller.decide_allocation(
            action="grant",
            pr=1,
            channel="hosted",
            head=values[1].head,
            reason="preserve the normal fresh decision boundary",
        )

        self.assertEqual(target.snapshot.number, 1)
        self.assertEqual(allocation["allocation"]["pr"], 1)
        self.assertEqual(batch_calls, [])
        self.assertTrue({1, 2, 3}.issubset({pr_number for pr_number, _ in evidence.history_reads}))

    def test_status_does_not_reaudit_a_persisted_stop_but_decisions_refresh(self):
        values = {1: pr(1, HEAD_1)}
        evidence = CountingEvidence(
            {
                (1, "hosted"): [self.allocation_evidence(checkpoint="hosted-stop")],
                (1, "cli"): [self.allocation_evidence(checkpoint="cli-stop")],
            }
        )
        controller = self.make(values, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])
        controller.decide_stop(pr=1, channel="hosted", reason="hosted channel stop")
        controller.decide_stop(pr=1, channel="cli", reason="CLI channel stop")
        evidence.stop_audit_calls.clear()
        evidence.history_reads.clear()

        controller.status()

        self.assertEqual(evidence.stop_audit_calls, [])
        self.assertEqual(evidence.history_reads.count((1, "hosted")), 1)
        self.assertEqual(evidence.history_reads.count((1, "cli")), 1)
        self.assertEqual(len(evidence.history_reads), 2)
        evidence.stop_audit_calls.clear()
        evidence.backing[(1, "hosted")].append(
            {
                "pr": 1,
                "channel": "hosted",
                "head": HEAD_1,
                "checkpoint": "trigger:99",
                "completed": False,
                "attributable": False,
                "rate_limited": True,
                "trigger_id": 99,
            }
        )
        evidence.history_reads.clear()
        with self.assertRaisesRegex(ControllerError, "unresolved review evidence"):
            controller.decide_stop(pr=1, channel="hosted", reason="recheck current stop")
        self.assertGreaterEqual(len(evidence.stop_audit_calls), 1)
        self.assertGreaterEqual(evidence.history_reads.count((1, "hosted")), 1)

    def test_status_exposes_recent_completed_review_counts_without_changing_policy(self):
        hosted = [
            {
                **dataclasses.asdict(Evidence(
                    1,
                    PARENT,
                    f"h{index}",
                    raw=index,
                    accepted=1,
                    completed=True,
                    attributable=True,
                    non_counting=index == 2,
                )),
                "observed_at": f"2026-09-{index:02d}T12:00:00Z",
            }
            for index in range(1, 6)
        ] + [
            Evidence(1, HEAD_1, "h6", raw=0, accepted=0, completed=True, attributable=True),
            {
                **dataclasses.asdict(Evidence(
                    1, HEAD_1, "unlinked", raw=2, accepted=0, completed=True, attributable=False
                )),
                "observed_at": "invalid",
            },
            Evidence(1, HEAD_1, "pending", raw=0, accepted=0, provisional=True),
            Evidence(1, HEAD_1, "quota", raw=0, accepted=0, rate_limited=True),
        ]
        cli = [{
            **dataclasses.asdict(Evidence(
                1, HEAD_1, "c1", raw=2, accepted=0, completed=True, attributable=True
            )),
            "observed_at": "2026-09-07T14:30:00+12:00",
        }]
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): hosted, (1, "cli"): cli})
        controller.set_stack([1])

        result = controller.status()["prs"][0]
        self.assertEqual(result["review_activity"]["hosted"]["total"], 7)
        self.assertEqual([item["raw"] for item in result["review_activity"]["hosted"]["recent"]], [3, 4, 5, 0, 2])
        self.assertEqual(
            [item["completed_at"] for item in result["review_activity"]["hosted"]["recent"]],
            ["2026-09-03T12:00:00Z", "2026-09-04T12:00:00Z", "2026-09-05T12:00:00Z", None, None],
        )
        self.assertEqual(
            set(result["review_activity"]["hosted"]["recent"][0]),
            {"raw", "accepted", "routed", "completed_at", "attributable", "current_head", "non_counting"},
        )
        self.assertIsNone(result["review_activity"]["hosted"]["recent"][0]["routed"])
        self.assertFalse(result["review_activity"]["hosted"]["recent"][0]["current_head"])
        self.assertFalse(result["review_activity"]["hosted"]["recent"][-1]["attributable"])
        self.assertTrue(result["review_activity"]["hosted"]["recent"][-1]["current_head"])
        self.assertEqual(result["review_activity"]["cli"]["total"], 1)
        self.assertEqual(result["review_activity"]["cli"]["recent"][0]["completed_at"], "2026-09-07T02:30:00Z")
        self.assertNotEqual(result["channels"]["hosted"], "COMPLETE")

    def test_status_exposes_current_head_over_ceiling_reason_and_checkpoint_source(self):
        evidence = {
            (1, "hosted"): [
                {
                    "pr": 1,
                    "head": HEAD_2,
                    "checkpoint": "over-ceiling:stale",
                    "over_ceiling": True,
                    "reason": "stale file ceiling notice",
                },
                {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "over-ceiling:5748509184",
                    "over_ceiling": True,
                    "reason": "CodeRabbit skipped review because the PR exceeds its file ceiling",
                },
            ]
        }
        controller = self.make({1: pr(1, HEAD_1)}, evidence, heads={"feature-1": HEAD_1})
        controller.set_stack([1])

        result = controller.status_for_pr(1)["prs"][0]

        self.assertEqual(
            result["channel_reasons"],
            {
                "hosted": [
                    {
                        "reason": "CodeRabbit skipped review because the PR exceeds its file ceiling",
                        "source": "over-ceiling:5748509184",
                        "non_counting": True,
                    }
                ]
            },
        )

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

    def test_direct_default_base_advance_keeps_unchanged_owned_patch_reviewable(self):
        advanced_base = "9" * 40
        old_review = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "hosted-before-default-advance",
            "completed": True,
            "attributable": True,
            "anchored": True,
            "corrected_state": True,
            "accepted": 1,
            "child_head": HEAD_1,
            "parent_identity": "develop",
            "parent_head": BASE,
            "merge_base": BASE,
            "patch_id": f"patch-{HEAD_1[:4]}",
        }
        controller = self.make(
            {1: pr(1, HEAD_1, base_tip=advanced_base)},
            {(1, "hosted"): [old_review]},
            heads={"develop": advanced_base, "feature-1": HEAD_1},
        )
        controller.git.is_ancestor = lambda ancestor, child: (ancestor, child) in {
            (BASE, HEAD_1),
            (BASE, advanced_base),
        }
        controller.set_stack([1])

        status = controller.status()["prs"][0]
        target = controller.resolve_hosted_target()

        self.assertEqual(status["reconciliation"], "COHERENT")
        self.assertEqual(status["channels"]["hosted"], "READY")
        self.assertTrue(target.default_base_front)
        self.assertEqual(controller.git.test_merge_calls[-1], (advanced_base, HEAD_1))
        self.assertTrue(target.has_current_default_test_merge_proof())
        self.assertEqual(target.parent.head_sha, advanced_base)
        self.assertEqual(target.snapshot.head_sha, HEAD_1)

    def test_direct_default_front_holds_when_local_test_merge_conflicts(self):
        advanced_base = "9" * 40
        controller = self.make(
            {1: pr(1, HEAD_1, base_tip=advanced_base, mergeable="MERGEABLE")},
            heads={"develop": advanced_base, "feature-1": HEAD_1},
        )
        controller.git.is_ancestor = lambda ancestor, child: (ancestor, child) in {
            (BASE, HEAD_1),
            (BASE, advanced_base),
        }
        controller.git.test_merge_error = ControllerError("overlapping changes conflict")
        controller.set_stack([1])

        status = controller.status()["prs"][0]

        self.assertEqual(status["reconciliation"], "UNRECONCILED")
        self.assertIn("test merge is unproven", status["reason"])
        self.assertIn("overlapping changes conflict", status["reason"])
        with self.assertRaisesRegex(ControllerError, "unreconciled|cannot run"):
            controller.resolve_hosted_target()

    def test_direct_default_base_advance_holds_conflicts_and_changed_owned_patch(self):
        advanced_base = "9" * 40
        for label, mergeable, prior_patch in (
            ("conflict", "CONFLICTING", f"patch-{HEAD_1[:4]}"),
            ("patch-change", "MERGEABLE", "old-owned-patch"),
        ):
            with self.subTest(case=label):
                old_review = {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": f"hosted-before-default-advance-{label}",
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 1,
                    "child_head": HEAD_1,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": prior_patch,
                }
                controller = self.make(
                    {1: pr(1, HEAD_1, base_tip=advanced_base, mergeable=mergeable)},
                    {(1, "hosted"): [old_review]},
                    heads={"develop": advanced_base, "feature-1": HEAD_1},
                )
                controller.git.is_ancestor = lambda ancestor, child: (ancestor, child) in {
                    (BASE, HEAD_1),
                    (BASE, advanced_base),
                }
                controller.set_stack([1])

                result = controller.status()["prs"][0]

                self.assertIn(result["reconciliation"], {"PARENT_MOVED", "UNRECONCILED"})
                with self.assertRaises(ControllerError):
                    controller.resolve_hosted_target()

    def test_default_base_advance_reselects_after_stale_hosted_preflight(self):
        advanced_base = "9" * 40
        controller = self.make(
            {1: pr(1, HEAD_1)},
            heads={"develop": BASE, "feature-1": HEAD_1},
        )
        controller.set_stack([1])
        calls = []

        def adapter(target, **kwargs):
            calls.append((target, kwargs))
            if len(calls) == 1:
                controller.git.heads["develop"] = advanced_base
                controller.github.values[1] = pr(1, HEAD_1, base_tip=advanced_base)
                raise StaleReviewTarget("default base advanced before posting")
            return target

        controller.hosted_adapter = adapter

        selected = controller.run_hosted(expected_pr=1)

        self.assertEqual(len(calls), 2)
        self.assertEqual(selected.snapshot.head_sha, HEAD_1)
        self.assertEqual(selected.parent.head_sha, advanced_base)
        self.assertTrue(selected.default_base_front)

    def test_default_base_advance_does_not_relax_stacked_child_ancestry(self):
        advanced_base = "9" * 40
        controller = self.make(
            {
                1: pr(1, HEAD_1, base_tip=advanced_base),
                2: pr(2, HEAD_2, "feature-1", HEAD_1),
            },
            heads={"develop": advanced_base, "feature-1": HEAD_1, "feature-2": HEAD_2},
        )
        controller.git.is_ancestor = lambda ancestor, child: (ancestor, child) in {
            (BASE, HEAD_1),
            (BASE, advanced_base),
        }
        controller.set_stack([1, 2])

        result = controller.status()

        self.assertEqual(result["prs"][0]["reconciliation"], "COHERENT")
        self.assertEqual(result["prs"][1]["reconciliation"], "UNRECONCILED")
        with self.assertRaises(ControllerError):
            controller.resolve_cli_target(expected_pr=2)

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

    def test_terminal_hosted_attribution_ambiguity_does_not_hold_cli(self):
        ambiguous = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:123",
            "held": True,
            "unstable": True,
            "terminal_ambiguous": True,
            "terminal": True,
            "attributable": False,
            "trigger_id": 123,
            "response_id": 124,
            "fingerprint": "f" * 64,
        }
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "cli"): [ambiguous]})
        controller.set_stack([1])

        self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "READY")
        self.assertEqual(controller.resolve_cli_target().snapshot.head_sha, HEAD_1)

    def test_historical_terminal_hosted_ambiguity_does_not_hold_cli_on_later_head(self):
        ambiguous = {
            "pr": 1,
            "head": HEAD_2,
            "captured_head": HEAD_2,
            "checkpoint": "trigger:123",
            "held": True,
            "unstable": True,
            "terminal_ambiguous": True,
            "terminal": True,
            "state": "ambiguous",
            "attributable": False,
            "trigger_id": 123,
            "response_id": 124,
            "fingerprint": "f" * 64,
        }
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): [ambiguous]})
        controller.set_stack([1])

        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["cli"], "MISSING_EVIDENCE")
        self.assertEqual(report["review_targets"]["cli"]["status"], "MISSING_EVIDENCE")
        self.assertEqual(controller.resolve_cli_target().snapshot.head_sha, HEAD_1)

    def test_cli_ambiguity_projection_does_not_read_non_mapping_evidence(self):
        class NonMappingEvidence:
            @property
            def terminal_ambiguous(self):
                raise AssertionError("non-mapping evidence must not be inspected")

        evidence = NonMappingEvidence()

        self.assertEqual(
            ReviewController._project_cli_hosted_ambiguity(Channel.CLI, [evidence]), [evidence]
        )

    def test_active_hosted_reservation_holds_cli_status_and_target(self):
        active = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:123",
            "held": True,
            "reason": "no attributable terminal response",
        }
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): [active]})
        controller.set_stack([1])

        self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "HELD")
        with self.assertRaisesRegex(ControllerError, HOSTED_CLI_OVERLAP_HOLD_REASON):
            controller.resolve_cli_target()

    def test_consumed_cli_allocation_uses_hosted_reservation_projection_for_selection(self):
        evidence = {(1, "cli"): [self.allocation_evidence(completed=False)]}
        controller = self.make({1: pr(1, HEAD_1)}, evidence)
        controller.set_stack([1])
        controller.decide_allocation(
            action="grant", pr=1, channel="cli", head=HEAD_1, reason="one CLI review"
        )
        evidence[(1, "cli")].append(self.allocation_evidence(checkpoint="consumed"))
        evidence[(1, "hosted")] = [
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "trigger:123",
                "held": True,
            }
        ]
        allocation = controller.store.load().allocations["1:cli"]
        handed_off = dataclasses.replace(
            allocation,
            handoff_checkpoint="consumed",
            handoff_head=HEAD_1,
            handoff_validation="operator-validated legacy handoff",
        )
        controller.store.update(
            lambda state: dataclasses.replace(
                state, allocations={**state.allocations, "1:cli": handed_off}
            )
        )

        report_target = controller.status()["review_targets"]["cli"]
        selected_target = controller.select_target("cli")

        self.assertEqual(report_target["pr"], 1)
        self.assertEqual(report_target["status"], "ALLOCATION_EXHAUSTED")
        self.assertEqual(selected_target["pr"], report_target["pr"])
        self.assertEqual(selected_target["status"], report_target["status"])

    def test_same_head_attributable_active_hosted_review_allows_cli_status_and_target(self):
        active = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:123",
            "held": True,
            "reason": HOSTED_ACTIVE_RESPONSE_REASON,
            "anchor": hosted_anchor(),
        }
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): [active]})
        controller.set_stack([1])

        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["cli"], "MISSING_EVIDENCE")
        self.assertEqual(report["review_targets"]["cli"]["status"], "MISSING_EVIDENCE")
        self.assertEqual(controller.resolve_cli_target().snapshot.head_sha, HEAD_1)

    def test_active_review_target_hold_is_channel_specific(self):
        controller = self.make({1: pr(1, HEAD_1)})
        controller.set_stack([1])
        state = controller.store.load()
        live, reconciliation = controller._reconciliation(state, evidence_prs=set())
        hosted_active = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "hosted-active",
            "active_review": True,
        }
        histories = {
            Channel.HOSTED: {1: [hosted_active]},
            Channel.CLI: {1: []},
        }

        cli_decision = ReviewController._select_review_decision(
            state,
            Channel.CLI,
            live,
            reconciliation,
            histories,
            {},
            [1],
        )

        self.assertEqual(cli_decision.target, 1)
        self.assertEqual(cli_decision.status, ReviewStatus.MISSING_EVIDENCE)

        histories[Channel.CLI][1] = [
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "cli-active",
                "active_review": True,
            }
        ]
        cli_active_decision = ReviewController._select_review_decision(
            state,
            Channel.CLI,
            live,
            reconciliation,
            histories,
            {},
            [1],
        )

        self.assertEqual(cli_active_decision.target, 1)
        self.assertEqual(cli_active_decision.status, ReviewStatus.HELD)

    def test_same_head_active_hosted_review_with_changed_anchor_holds_cli(self):
        cases = {
            "missing anchor": None,
            "parent identity": hosted_anchor(parent_identity="17"),
            "parent head": hosted_anchor(parent_head=HEAD_2),
            "merge base": hosted_anchor(merge_base=HEAD_2),
            "patch identity": hosted_anchor(patch_id="different-patch"),
        }
        for label, anchor in cases.items():
            with self.subTest(anchor=label):
                active = {
                    "pr": 1,
                    "head": HEAD_1,
                    "checkpoint": "trigger:123",
                    "held": True,
                    "reason": HOSTED_ACTIVE_RESPONSE_REASON,
                    **({"anchor": anchor} if anchor is not None else {}),
                }
                controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): [active]})
                controller.set_stack([1])

                report = controller.status()

                self.assertEqual(report["prs"][0]["channels"]["cli"], "HELD")
                self.assertEqual(report["review_targets"]["cli"]["status"], "HELD")
                with self.assertRaisesRegex(ControllerError, HOSTED_CLI_OVERLAP_HOLD_REASON):
                    controller.resolve_cli_target()

    def test_active_hosted_review_on_another_head_holds_cli(self):
        active = {
            "pr": 1,
            "head": HEAD_2,
            "checkpoint": "trigger:123",
            "held": True,
            "reason": HOSTED_ACTIVE_RESPONSE_REASON,
        }
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): [active]})
        controller.set_stack([1])

        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["cli"], "HELD")
        self.assertEqual(report["review_targets"]["cli"]["status"], "HELD")
        self.assertEqual(report["review_targets"]["cli"]["reason"], HOSTED_CLI_OVERLAP_HOLD_REASON)
        with self.assertRaisesRegex(ControllerError, HOSTED_CLI_OVERLAP_HOLD_REASON):
            controller.resolve_cli_target()

    def test_active_hosted_review_without_trigger_identity_holds_cli(self):
        active = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:pending",
            "held": True,
            "reason": HOSTED_ACTIVE_RESPONSE_REASON,
        }
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "hosted"): [active]})
        controller.set_stack([1])

        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["cli"], "HELD")
        self.assertEqual(report["review_targets"]["cli"]["status"], "HELD")
        self.assertEqual(report["review_targets"]["cli"]["reason"], HOSTED_CLI_OVERLAP_HOLD_REASON)

    def test_unresolved_accepted_hosted_findings_hold_cli_status_and_target(self):
        accepted = self.allocation_evidence(checkpoint="hosted-findings", accepted=1, channel="hosted")
        accepted["held"] = True
        controller = self.make({1: pr(1, HEAD_1)}, {(1, "cli"): [accepted]})
        controller.set_stack([1])

        self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "HELD")
        with self.assertRaisesRegex(ControllerError, "held"):
            controller.resolve_cli_target()

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

    def test_cli_taper_spans_reviewed_heads_across_hosted_fix_and_selects_no_new_review(self):
        def review(head, checkpoint, *, accepted=0, correction=False):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": accepted,
                "raw": accepted,
                "correction": correction,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
            }

        cli_history = [
            review(HEAD_1, "cli-old-1"),
            review(HEAD_1, "cli-old-2"),
            review(HEAD_2, "cli-current"),
        ]
        hosted_history = [
            review(HEAD_1, "hosted-finding", accepted=1),
            review(HEAD_2, "hosted-fix", correction=True),
        ]
        controller = self.make(
            {1: pr(1, HEAD_2)},
            {(1, "cli"): cli_history, (1, "hosted"): hosted_history},
            heads={"feature-1": HEAD_2},
        )
        controller.set_stack([1])

        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["cli"], "COMPLETE")
        self.assertEqual(report["prs"][0]["channels"]["hosted"], "READY")
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
            controller.resolve_cli_target()

    def test_accepted_cli_finding_resets_only_cli_taper_and_proven_descendant_counts(self):
        def review(head, checkpoint, *, accepted=0):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": accepted,
                "raw": accepted,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
            }

        hosted_history = [review(HEAD_2, "hosted-current")]
        accepted_cli_history = [
            review(HEAD_1, "cli-old-1"),
            review(HEAD_1, "cli-old-2"),
            review(HEAD_1, "cli-accepted", accepted=1),
            review(HEAD_2, "cli-after-fix"),
        ]
        controller = self.make(
            {1: pr(1, HEAD_2)},
            {(1, "hosted"): hosted_history, (1, "cli"): accepted_cli_history},
            heads={"feature-1": HEAD_2},
        )
        controller.set_stack([1])

        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["hosted"], "COMPLETE")
        self.assertEqual(report["prs"][0]["channels"]["cli"], "READY")

        stale_cli_history = [review(HEAD_1, f"cli-stale-{index}") for index in range(1, 4)]
        stale_controller = self.make(
            {1: pr(1, HEAD_2)},
            {(1, "cli"): stale_cli_history},
            heads={"feature-1": HEAD_2},
        )
        stale_controller.set_stack([1])
        stale_report = stale_controller.status()
        self.assertEqual(stale_report["prs"][0]["channels"]["cli"], "COMPLETE")
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
            stale_controller.resolve_cli_target()

    def test_cli_cross_head_lineage_allows_parent_tip_advance_with_stable_merge_base(self):
        def review(head, checkpoint, *, parent_head=BASE, corrected_state=True):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": corrected_state,
                "accepted": 0,
                "raw": 0,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": parent_head,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
            }

        history = [
            review(HEAD_1, "cli-old-1", parent_head=PARENT, corrected_state=False),
            review(HEAD_1, "cli-old-2", parent_head=PARENT, corrected_state=False),
            review(HEAD_2, "cli-current", parent_head=BASE),
        ]
        controller = self.make(
            {1: pr(1, HEAD_2)},
            {(1, "cli"): history},
            heads={"feature-1": HEAD_2},
        )
        controller.set_stack([1])

        self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "COMPLETE")

    def test_cli_cross_head_changed_parent_or_merge_base_needs_exact_retain_judgment(self):
        def review(head, checkpoint, *, parent_identity="develop", merge_base=BASE):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": head == HEAD_2,
                "accepted": 0,
                "raw": 0,
                "child_head": head,
                "parent_identity": parent_identity,
                "parent_head": BASE,
                "merge_base": merge_base,
                "patch_id": f"patch-{HEAD_2[:4]}",
            }

        for label, old_anchor in (
            ("parent", {"parent_identity": "prior-develop"}),
            ("merge base", {"merge_base": "8" * 40}),
        ):
            with self.subTest(discontinuity=label):
                history = [
                    review(HEAD_1, "cli-old-1", **old_anchor),
                    review(HEAD_1, "cli-old-2", **old_anchor),
                    review(HEAD_2, "cli-current"),
                ]
                controller = self.make(
                    {1: pr(1, HEAD_2)},
                    {(1, "cli"): history},
                    heads={"feature-1": HEAD_2},
                )
                controller.set_stack([1])
                controller.store.update(
                    lambda state: dataclasses.replace(
                        state,
                        judgments=state.judgments
                        + (
                            Judgment(
                                1,
                                "cli",
                                "retain",
                                HEAD_1,
                                "cli-old-2",
                                "retained unchanged patch across parent reconciliation",
                                f"patch-{HEAD_2[:4]}",
                            ),
                        ),
                    )
                )

                self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "COMPLETE")

    def test_cli_cross_head_taper_counts_across_unproven_parent_and_merge_base(self):
        current_patch_id = f"patch-{HEAD_2[:4]}"

        def review(head, checkpoint, *, parent_identity="develop", merge_base=BASE, patch_id=current_patch_id):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": head == HEAD_2,
                "accepted": 0,
                "raw": 0,
                "child_head": head,
                "parent_identity": parent_identity,
                "parent_head": BASE,
                "merge_base": merge_base,
                "patch_id": patch_id,
            }

        for label, old_anchor in (
            ("parent", {"parent_identity": "prior-develop"}),
            ("merge base", {"merge_base": "8" * 40}),
        ):
            with self.subTest(discontinuity=label):
                history = [
                    review(HEAD_1, "cli-old-1", **old_anchor),
                    review(HEAD_1, "cli-old-2", **old_anchor),
                    review(HEAD_2, "cli-current"),
                ]
                controller = self.make(
                    {1: pr(1, HEAD_2)},
                    {(1, "cli"): history},
                    heads={"feature-1": HEAD_2},
                )
                controller.set_stack([1])

                self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "COMPLETE")
                with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
                    controller.resolve_cli_target()

        mismatched_patch_history = [
            review(HEAD_1, "cli-old-1", parent_identity="prior-develop"),
            review(HEAD_1, "cli-old-2", parent_identity="prior-develop", patch_id="patch-old"),
            review(HEAD_2, "cli-current"),
        ]
        controller = self.make(
            {1: pr(1, HEAD_2)},
            {(1, "cli"): mismatched_patch_history},
            heads={"feature-1": HEAD_2},
        )
        controller.set_stack([1])
        self.assertEqual(controller.status()["prs"][0]["channels"]["cli"], "COMPLETE")

    def test_cli_taper_ignores_incomplete_rate_limits_and_routed_findings(self):
        def dry(head, checkpoint):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "routed": 2,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
                "streak_break_before": True,
                "lineage_proven_to_next": False,
            }

        history = [
            dry(HEAD_1, "cli-one"),
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "incomplete-accepted",
                "completed": False,
                "attributable": False,
                "anchored": False,
                "accepted": 3,
            },
            {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": "rate-limited",
                "completed": False,
                "attributable": False,
                "anchored": False,
                "accepted": 0,
                "rate_limited": True,
            },
            dry(HEAD_2, "cli-two"),
            {
                **dry(HEAD_2, "unanchored-complete"),
                "anchored": False,
            },
            {
                **dry(HEAD_2, "uncorrected-complete"),
                "corrected_state": False,
            },
            {
                "pr": 1,
                "head": HEAD_2,
                "checkpoint": "partial",
                "completed": False,
                "attributable": False,
                "anchored": False,
                "accepted": 0,
            },
            dry(HEAD_3, "cli-three"),
        ]

        self.assertTrue(taper_satisfied(Channel.CLI, history, required=3))
        hosted_history = [
            {**dry(HEAD_1, "hosted-one"), "channel": "hosted"},
            {**dry(HEAD_2, "hosted-two"), "channel": "hosted"},
        ]
        self.assertTrue(taper_satisfied(Channel.HOSTED, hosted_history, required=2))
        accepted_break = [
            dry(HEAD_1, "cli-one"),
            dry(HEAD_1, "cli-two"),
            {**dry(HEAD_2, "cli-accepted"), "accepted": 1},
            dry(HEAD_2, "cli-three"),
        ]
        self.assertFalse(taper_satisfied(Channel.CLI, accepted_break, required=3))

    def test_retained_patch_taper_skips_mismatched_uncorrected_rows_without_resetting(self):
        def uncorrected(head, checkpoint, patch_id, *, accepted=0):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": False,
                "accepted": accepted,
                "patch_id": patch_id,
            }

        retained_patch = "retained-patch"
        first = uncorrected(HEAD_1, "retained-one", retained_patch)
        unrelated = uncorrected(HEAD_2, "other-patch", "other-patch")
        second = uncorrected(HEAD_3, "retained-two", retained_patch)

        self.assertFalse(
            taper_satisfied(
                Channel.HOSTED,
                [first, unrelated],
                required=2,
                allow_uncorrected_state=True,
                retained_patch_id=retained_patch,
            )
        )
        self.assertTrue(
            taper_satisfied(
                Channel.HOSTED,
                [first, unrelated, second],
                required=2,
                allow_uncorrected_state=True,
                retained_patch_id=retained_patch,
            )
        )
        accepted_other_patch = uncorrected(
            HEAD_2,
            "accepted-other-patch",
            "other-patch",
            accepted=1,
        )
        self.assertFalse(
            taper_satisfied(
                Channel.HOSTED,
                [first, accepted_other_patch, second],
                required=2,
                allow_uncorrected_state=True,
                retained_patch_id=retained_patch,
            )
        )

    def test_cli_taper_persists_on_proven_descendant_controller_workflow_head(self):
        history = [
            {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": checkpoint != "cli-three",
                "accepted": 0,
                "raw": 0,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
            }
            for head, checkpoint in (
                (HEAD_1, "cli-one"),
                (HEAD_2, "cli-two"),
                (HEAD_2, "cli-three"),
            )
        ]
        hosted_history = [
            {
                "pr": 1,
                "head": HEAD_3,
                "checkpoint": "hosted-descendant",
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "raw": 0,
                "child_head": HEAD_3,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{HEAD_3[:4]}",
            }
        ]
        controller = self.make(
            {1: pr(1, HEAD_3)},
            {(1, "cli"): history, (1, "hosted"): hosted_history},
            heads={"feature-1": HEAD_3},
        )
        controller.set_stack([1])

        # HEAD_3 represents the published controller/workflow-only correction;
        # no fourth CLI checkpoint is present at that head.
        state = controller._state()
        live, reconciliation = controller._reconciliation(state)
        current_anchor = controller._reconciled_anchor(1, live[1], reconciliation)
        projected = controller._project_cli_streak_lineage(history, 1, state, current_anchor)
        self.assertTrue(projected[-1]["current_candidate_descendant_proven"])
        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["cli"], "COMPLETE")
        self.assertEqual(report["prs"][0]["channels"]["hosted"], "COMPLETE")
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
            controller.resolve_cli_target()

    def test_hosted_head_mismatch_yields_to_proven_cli_descendant_in_status_and_selection(self):
        controller = self.make(
            {
                1: pr(1, HEAD_2),
                2: pr(2, HEAD_3, "feature-1", HEAD_2),
            },
            heads={"develop": BASE, "feature-1": HEAD_2, "feature-2": HEAD_3},
        )
        controller.set_stack([1, 2])
        state = controller._state()
        live, reconciliation = controller._reconciliation(state)
        # Isolate the cross-channel decision: both review channels are on the
        # same coherent parent and patch identity, while their reviewed heads differ.
        reconciliation = dataclasses.replace(
            reconciliation,
            statuses={1: stack.ReconciliationStatus.COHERENT, 2: stack.ReconciliationStatus.COHERENT},
            channel_statuses={},
        )

        def review(head, checkpoint, *, proven=False, active=False):
            value = {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "raw": 0,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": "same-patch",
            }
            if proven:
                value["current_candidate_descendant_proven"] = True
            if active:
                value["active_review"] = True
            return value

        hosted_history = [review(HEAD_2, "hosted-current")]
        cli_history = [
            review(HEAD_1, "cli-one"),
            review(HEAD_1, "cli-two"),
            review(HEAD_1, "cli-three"),
        ]
        histories = {
            Channel.HOSTED: {1: hosted_history, 2: []},
            Channel.CLI: {1: cli_history, 2: []},
        }

        self.assertEqual(
            completion_status(state, Channel.HOSTED, hosted_history).value,
            "COMPLETE",
        )
        self.assertEqual(
            completion_status(
                state,
                Channel.HOSTED,
                hosted_history,
                other_channel_head=HEAD_1,
            ).value,
            "JUDGMENT_REQUIRED",
        )
        with (
            patch.object(controller, "_reconciliation", return_value=(live, reconciliation)),
            patch.object(
                controller,
                "_policy_history",
                side_effect=lambda _state, pr_number, channel, *_args, **_kwargs: histories[channel][pr_number],
            ),
        ):
            report = controller._status_from_state(
                state,
                review_target_prs=state.ordered_prs,
                review_target_selection_complete=True,
            )
            selected = controller.select_target("hosted")
        self.assertEqual(report["prs"][0]["channels"]["hosted"], "COMPLETE")
        self.assertEqual(
            (report["review_targets"]["hosted"]["pr"], report["review_targets"]["hosted"]["status"]),
            (2, "MISSING_EVIDENCE"),
        )
        self.assertEqual(
            (selected["pr"], selected["status"]),
            (report["review_targets"]["hosted"]["pr"], report["review_targets"]["hosted"]["status"]),
        )

        unproven_histories = {
            Channel.HOSTED: {1: hosted_history, 2: []},
            Channel.CLI: {1: [review(HEAD_1, "cli-three")], 2: []},
        }
        unproven = ReviewController._select_review_decision(
            state,
            Channel.HOSTED,
            live,
            reconciliation,
            unproven_histories,
            {},
            [1, 2],
        )
        self.assertEqual((unproven.target, unproven.status.value), (2, "MISSING_EVIDENCE"))

        active_histories = {
            Channel.HOSTED: {1: hosted_history, 2: []},
            Channel.CLI: {1: [review(HEAD_1, "cli-three", proven=True, active=True)], 2: []},
        }
        active = ReviewController._select_review_decision(
            state,
            Channel.HOSTED,
            live,
            reconciliation,
            active_histories,
            {},
            [1, 2],
        )
        self.assertEqual((active.target, active.status.value), (2, "MISSING_EVIDENCE"))

    def test_cli_taper_is_complete_but_unproven_candidate_needs_explicit_reopen(self):
        def make_controller():
            history = [
                {
                    "pr": 1,
                    "head": head,
                    "checkpoint": checkpoint,
                    "completed": True,
                    "attributable": True,
                    "anchored": True,
                    "corrected_state": True,
                    "accepted": 0,
                    "raw": 0,
                    "child_head": head,
                    "parent_identity": "develop",
                    "parent_head": BASE,
                    "merge_base": BASE,
                    "patch_id": f"patch-{head[:4]}",
                }
                for head, checkpoint in (
                    (HEAD_1, "cli-one"),
                    (HEAD_2, "cli-two"),
                    (HEAD_2, "cli-three"),
                )
            ]
            controller = self.make(
                {1: pr(1, HEAD_3)},
                {(1, "cli"): history},
                heads={"feature-1": HEAD_3},
            )
            controller.set_stack([1])
            return controller

        unproven = make_controller()

        def current_is_not_descendant(ancestor, descendant):
            return not (ancestor == HEAD_2 and descendant == HEAD_3)

        unproven.git.is_ancestor = current_is_not_descendant
        report = unproven.status()
        self.assertNotEqual(report["prs"][0]["channels"]["cli"], "COMPLETE")
        self.assertTrue(report["review_targets"]["cli"]["taper_complete"])
        self.assertEqual(report["review_targets"]["cli"]["status"], "COMPLETE")
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
            unproven.resolve_cli_target()

        reopened = make_controller()
        judgment = reopened.decide_judgment(
            pr=1,
            channel="cli",
            decision="reopen",
            head=HEAD_3,
            checkpoint="cli-three",
            reason="material correction requires fresh discovery",
        )
        self.assertEqual(judgment["decision"]["decision"], "reopen")
        stored = reopened._state().judgments[-1]
        self.assertEqual(stored.decision, "reopen")
        self.assertTrue(stored.applies(1, "cli", HEAD_2, "cli-three", f"patch-{HEAD_2[:4]}"))
        self.assertEqual(reopened.status()["prs"][0]["channels"]["cli"], "READY")

    def test_cli_taper_counts_across_parent_or_merge_base_discontinuity(self):
        def review(head, checkpoint, *, parent_identity="develop", merge_base=BASE):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "raw": 0,
                "child_head": head,
                "parent_identity": parent_identity,
                "parent_head": BASE,
                "merge_base": merge_base,
                "patch_id": f"patch-{head[:4]}",
            }

        cases = (
            ("parent", {"parent_identity": "previous-parent"}),
            ("merge base", {"merge_base": "8" * 40}),
        )
        for label, old_anchor in cases:
            with self.subTest(discontinuity=label):
                history = [
                    review(HEAD_1, "cli-old-1", **old_anchor),
                    review(HEAD_1, "cli-old-2", **old_anchor),
                    review(HEAD_2, "cli-current"),
                ]
                controller = self.make(
                    {1: pr(1, HEAD_2)},
                    {(1, "cli"): history},
                    heads={"feature-1": HEAD_2},
                )
                controller.set_stack([1])

                report = controller.status()

                self.assertEqual(report["prs"][0]["channels"]["cli"], "COMPLETE")
                with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
                    controller.resolve_cli_target()

    def test_cli_taper_counts_when_cross_head_ancestry_is_unproven(self):
        def review(head, checkpoint):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "raw": 0,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
            }

        controller = self.make(
            {1: pr(1, HEAD_2)},
            {
                (1, "cli"): [
                    review(HEAD_1, "cli-old-1"),
                    review(HEAD_1, "cli-old-2"),
                    review(HEAD_2, "cli-current"),
                ]
            },
            heads={"feature-1": HEAD_2},
        )

        def ancestry(ancestor, descendant):
            return ancestor == descendant or ancestor == BASE

        controller.git.is_ancestor = ancestry
        controller.set_stack([1])

        report = controller.status()

        self.assertEqual(report["prs"][0]["channels"]["cli"], "COMPLETE")
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
            controller.resolve_cli_target()

    def test_exact_bound_cross_channel_mismatch_does_not_reopen_completed_taper(self):
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
        self.assertEqual(controller.status()["review_targets"]["cli"]["status"], "COMPLETE")
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
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

    def test_policy_override_threshold_persists_across_later_head_and_patch_changes(self):
        def review(head, checkpoint, *, accepted=0):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": accepted,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
            }

        values = {1: pr(1, HEAD_1)}
        history = [review(HEAD_1, "hosted-one")]
        controller = self.make(values, {(1, "hosted"): history}, heads={"feature-1": HEAD_1})
        controller.set_stack([1])
        controller.decide_policy(
            pr=1,
            head=HEAD_1,
            checkpoint="hosted-one",
            hosted_zero_useful=2,
            reason="require two dry hosted results",
        )

        history.extend(
            [
                review(HEAD_2, "hosted-accepted", accepted=1),
                review(HEAD_2, "hosted-two"),
            ]
        )
        values[1] = pr(1, HEAD_3)
        controller.git.heads["feature-1"] = HEAD_3
        state = controller._state()
        required = required_taper(state, Channel.HOSTED, history)
        self.assertEqual(required, 2)
        self.assertFalse(taper_satisfied(Channel.HOSTED, history, required))
        self.assertFalse(controller.status()["review_targets"]["hosted"]["taper_complete"])

        history.append(review(HEAD_3, "hosted-three"))
        required = required_taper(controller._state(), Channel.HOSTED, history)
        self.assertEqual(required, 2)
        self.assertTrue(taper_satisfied(Channel.HOSTED, history, required))
        self.assertTrue(controller.status()["review_targets"]["hosted"]["taper_complete"])

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

    def test_reconciled_provisional_history_holds_selection_without_counting_or_erasing_taper(self):
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
        self.assertEqual(result["review_targets"]["cli"]["status"], "PROVISIONAL")
        with self.assertRaisesRegex(ControllerError, "cli review cannot run: PROVISIONAL"):
            controller.resolve_cli_target()
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
        with self.assertRaisesRegex(ControllerError, "cli review cannot run: PARENT_MOVED"):
            controller.run_cli(expected_pr=2)
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

    def test_patch_change_does_not_block_advancement_past_completed_taper(self):
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
        report = controller.status()
        self.assertEqual(report["prs"][0]["reconciliation"], "COHERENT")
        self.assertEqual(report["prs"][0]["channels"]["cli"], "JUDGMENT_REQUIRED")
        self.assertEqual(report["review_targets"]["cli"]["status"], "COMPLETE")
        self.assertTrue(report["review_targets"]["cli"]["taper_complete"])
        with self.assertRaisesRegex(ControllerError, "all cli targets are complete"):
            controller.resolve_cli_target()

    def test_target_selection_skips_request_blockers_only_after_taper_completes(self):
        controller = self.make(
            {1: pr(1, HEAD_3), 2: pr(2, HEAD_2, "feature-1", HEAD_3)},
            heads={"feature-1": HEAD_3, "feature-2": HEAD_2},
        )
        controller.set_stack([1, 2])
        state = controller._state()

        def dry(head, checkpoint):
            return {
                "pr": 1,
                "head": head,
                "checkpoint": checkpoint,
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "child_head": head,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{head[:4]}",
                "streak_break_before": True,
                "lineage_proven_to_next": False,
            }

        completed = [
            dry(HEAD_1, "cli-one"),
            dry(HEAD_2, "cli-two"),
            dry(HEAD_3, "cli-three"),
        ]
        topology_blockers = (
            stack.ReconciliationStatus.PARENT_MOVED,
            stack.ReconciliationStatus.UNRECONCILED,
            stack.ReconciliationStatus.PATCH_CHANGED,
        )
        for blocker in topology_blockers:
            with self.subTest(topology=blocker.value):
                decision = select_review_target(
                    state,
                    Channel.CLI,
                    [1, 2],
                    {1: completed, 2: []},
                    reconciliation_by_pr={
                        1: blocker,
                        2: stack.ReconciliationStatus.COHERENT,
                    },
                )
                self.assertEqual(decision.target, 2)

        for flag in ("rate_limited", "unstable", "over_ceiling", "parent_moved", "unreconciled"):
            with self.subTest(evidence_blocker=flag):
                observation = {
                    "pr": 1,
                    "head": HEAD_3,
                    "checkpoint": f"incomplete-{flag}",
                    "completed": False,
                    "attributable": False,
                    "anchored": False,
                    "accepted": 0,
                    flag: True,
                }
                decision = select_review_target(
                    state,
                    Channel.CLI,
                    [1, 2],
                    {1: [*completed, observation], 2: []},
                    reconciliation_by_pr={
                        1: stack.ReconciliationStatus.COHERENT,
                        2: stack.ReconciliationStatus.COHERENT,
                    },
                )
                self.assertEqual(decision.target, 2)

        for flag in ("active_review", "active_reservation"):
            with self.subTest(active=flag):
                observation = {
                    "pr": 1,
                    "head": HEAD_3,
                    "checkpoint": f"active-{flag}",
                    "completed": False,
                    "attributable": False,
                    "anchored": False,
                    "accepted": 0,
                    flag: True,
                }
                decision = select_review_target(
                    state,
                    Channel.CLI,
                    [1, 2],
                    {1: [*completed, observation], 2: []},
                    reconciliation_by_pr={
                        1: stack.ReconciliationStatus.COHERENT,
                        2: stack.ReconciliationStatus.COHERENT,
                    },
                )
                self.assertEqual(decision.target, 1)
                self.assertEqual(decision.status, ReviewStatus.HELD)

        incomplete = select_review_target(
            state,
            Channel.CLI,
            [1, 2],
            {1: completed[:2], 2: []},
            reconciliation_by_pr={
                1: stack.ReconciliationStatus.PARENT_MOVED,
                2: stack.ReconciliationStatus.COHERENT,
            },
        )
        self.assertEqual(incomplete.target, 1)
        self.assertEqual(incomplete.status, ReviewStatus.PARENT_MOVED)
        self.assertFalse(incomplete.taper_complete)

        allocation_hold = select_review_target(
            state,
            Channel.CLI,
            [1, 2],
            {1: completed, 2: []},
            allocation_holds={1: "accepted finding needs a published fix"},
        )
        self.assertEqual(allocation_hold.target, 1)
        self.assertEqual(allocation_hold.status, ReviewStatus.HELD)

        allocated = select_review_target(
            state,
            Channel.CLI,
            [1, 2],
            {1: completed, 2: []},
            reconciliation_by_pr={
                1: stack.ReconciliationStatus.PARENT_MOVED,
                2: stack.ReconciliationStatus.COHERENT,
            },
            allocation_reopen_prs={1},
        )
        self.assertEqual(allocated.target, 1)
        self.assertEqual(allocated.status, ReviewStatus.PARENT_MOVED)

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
        controller.git.is_ancestor = lambda ancestor, child: ancestor != old_parent
        self.assertEqual(controller.status()["prs"][0]["reconciliation"], "PARENT_MOVED")

    def test_legacy_transition_filters_scope_projections_and_keeps_real_guards(self):
        old_head = "7" * 40
        timeline = self.scope_timeline_evidence(1, "hosted")
        legacy = {
            "pr": 1,
            "head": old_head,
            "channel": "hosted",
            "checkpoint": "legacy-hosted",
            "completed": True,
            "attributable": True,
            "anchored": False,
            "corrected_state": True,
            "accepted": 0,
            "raw": 0,
        }
        stored_fingerprint = observation_fingerprint(legacy)
        controller = self.make(
            {1: pr(1, HEAD_1)},
            {(1, "hosted"): [timeline, legacy]},
            heads={"feature-1": HEAD_1},
        )
        controller.set_stack([1])

        result = controller.decide_legacy_transition(
            pr=1,
            head=HEAD_1,
            reason="retire only the old Hosted observation",
        )

        self.assertEqual(
            result["transition"]["hosted_fingerprints"],
            (stored_fingerprint,),
        )
        # Historical records gain routed=None when projected through the
        # current three-count evidence shape; this must not change identity.
        legacy["routed"] = None
        self.assertEqual(observation_fingerprint(legacy), stored_fingerprint)
        state = controller._state()
        _, reconciliation = controller._reconciliation(state)
        projected = controller._policy_history(
            state, 1, Channel.HOSTED, reconciliation
        )
        self.assertTrue(projected[0]["scope_timeline"])
        self.assertTrue(projected[0]["non_counting"])
        self.assertTrue(projected[1]["non_counting"])

        self.assertNotEqual(
            observation_fingerprint({**legacy, "routed": 0}),
            stored_fingerprint,
            "an explicit modern routed count remains part of the observation identity",
        )

        for name, rows, message in (
            (
                "incomplete evidence",
                [timeline, {**legacy, "checkpoint": "still-incomplete", "completed": False}],
                "incomplete or unattributable",
            ),
            (
                "duplicate checkpoint",
                [timeline, legacy, dict(legacy)],
                "ambiguous duplicate checkpoint",
            ),
        ):
            with self.subTest(name=name):
                guarded = self.make(
                    {1: pr(1, HEAD_1)},
                    {(1, "hosted"): rows},
                    heads={"feature-1": HEAD_1},
                )
                guarded.set_stack([1])
                with self.assertRaisesRegex(ControllerError, message):
                    guarded.decide_legacy_transition(
                        pr=1,
                        head=HEAD_1,
                        reason="scope projections cannot weaken transition guards",
                    )

    def test_legacy_transition_write_fails_closed_when_transition_list_changes_concurrently(self):
        legacy = {
            "pr": 1,
            "head": "7" * 40,
            "checkpoint": "legacy-cli",
            "completed": True,
            "attributable": True,
            "anchored": False,
        }
        controller = self.make(
            {1: pr(1, HEAD_1)}, {(1, "cli"): [legacy]}, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])
        original = controller.store.update
        first = controller.decide_legacy_transition(
            pr=1,
            head=HEAD_1,
            reason="capture a transition for the concurrent writer fixture",
        )["transition"]
        transition = controller._state().legacy_transitions[-1]
        original(lambda current: dataclasses.replace(current, legacy_transitions=()))

        def concurrent_update(mutate):
            original(lambda current: dataclasses.replace(current, legacy_transitions=(transition,)))
            return original(mutate)

        with (
            patch.object(controller.store, "update", side_effect=concurrent_update),
            self.assertRaisesRegex(ControllerError, "legacy transitions changed concurrently"),
        ):
            controller.decide_legacy_transition(
                pr=1,
                head=HEAD_1,
                reason="do not overwrite a transition added after the snapshot",
            )

        self.assertEqual(len(controller._state().legacy_transitions), 1)
        self.assertEqual(controller._state().legacy_transitions[0].to_dict(), first)

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

    def test_untrusted_non_counting_marker_does_not_hide_held_evidence_without_transition(self):
        held = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:42",
            "held": True,
            "non_counting": True,
        }
        controller = self.make(
            {1: pr(1, HEAD_1)}, {(1, "hosted"): [held]}, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        self.assertEqual(controller.status()["prs"][0]["channels"]["hosted"], "HELD")

    def test_policy_history_without_selected_fingerprints_does_not_fingerprint_untrusted_records(self):
        held = {
            "pr": 1,
            "head": HEAD_1,
            "checkpoint": "trigger:42",
            "held": True,
            "non_counting": True,
        }
        unsupported = {**held, "unsupported": Path("unsupported-observation")}
        controller = self.make(
            {1: pr(1, HEAD_1)}, {(1, "hosted"): [held, unsupported]}, heads={"feature-1": HEAD_1}
        )
        controller.set_stack([1])

        status = controller.status()
        self.assertEqual(status["prs"][0]["channels"]["hosted"], "HELD")

        projected = controller._policy_history(
            controller._state(),
            1,
            Channel.HOSTED,
            SimpleNamespace(legacy_transition_fingerprints={}),
        )

        self.assertEqual(projected[0], {key: value for key, value in held.items() if key != "non_counting"})
        self.assertEqual(
            projected[1], {key: value for key, value in unsupported.items() if key != "non_counting"}
        )

    def test_transitioned_legacy_history_cannot_override_modern_completion_or_decision_checkpoint(self):
        old_head = "7" * 40
        legacy_cli = {
            "pr": 1,
            "head": old_head,
            "checkpoint": "legacy-cli",
            "completed": True,
            "attributable": True,
            "anchored": False,
            "corrected_state": True,
            "accepted": 0,
            "raw": 0,
            "child_head": old_head,
            "parent_identity": "develop",
            "parent_head": "1" * 40,
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
        controller.decide_legacy_transition(
            pr=1,
            head=HEAD_1,
            reason="retire unanchored history before modern review",
        )

        def modern(channel, index):
            return {
                "pr": 1,
                "head": HEAD_1,
                "checkpoint": f"{channel}-{index}",
                "completed": True,
                "attributable": True,
                "anchored": True,
                "corrected_state": True,
                "accepted": 0,
                "raw": 0,
                "child_head": HEAD_1,
                "parent_identity": "develop",
                "parent_head": BASE,
                "merge_base": BASE,
                "patch_id": f"patch-{HEAD_1[:4]}",
            }

        for channel in ("hosted", "cli"):
            modern_history = [modern(channel, index) for index in range(3)]
            # Provider ordering can put an older transitioned observation after
            # later evidence; the controller marker must still exclude it.
            evidence[(1, channel)] = modern_history + [legacy_hosted if channel == "hosted" else legacy_cli]

        channels = controller.status()["prs"][0]["channels"]
        self.assertEqual(channels, {"hosted": "COMPLETE", "cli": "COMPLETE"})
        result = controller.decide_policy(
            pr=1,
            head=HEAD_1,
            checkpoint="hosted-2",
            hosted_zero_useful=1,
            reason="bind the override to the latest modern checkpoint",
        )
        self.assertEqual(result["policy_override"]["checkpoint"], "hosted-2")

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

    def test_legacy_transition_retires_only_the_exact_missing_hosted_fingerprint_and_keeps_it_non_counting(self):
        controller, evidence, _, legacy_hosted, fingerprint = self.make_legacy_retirement_case()

        result = controller.decide_legacy_transition(
            pr=1,
            head=HEAD_2,
            reason="retire the one audited missing Hosted fingerprint",
            reauthorize=True,
            retire_missing_hosted_fingerprint=fingerprint,
        )

        self.assertTrue(result["recorded"])
        self.assertEqual(result["retirement"]["retired_missing_hosted_fingerprints"], [fingerprint])
        transition = controller._state().legacy_transitions[-1]
        self.assertEqual(transition.hosted_fingerprints, (fingerprint,))
        self.assertEqual(transition.retired_hosted_fingerprints, (fingerprint,))
        self.assertEqual(evidence.audit_calls[0][1], (fingerprint,))
        self.assertEqual(
            controller.status()["legacy_transitions"][-1]["retired_hosted_fingerprints"],
            (fingerprint,),
        )

        evidence[(1, "hosted")].append(legacy_hosted)
        state = controller._state()
        _, reconciliation = controller._reconciliation(state)
        projected = controller._policy_history(state, 1, Channel.HOSTED, reconciliation)
        reappeared = next(item for item in projected if item["checkpoint"] == legacy_hosted["checkpoint"])
        self.assertTrue(reappeared["non_counting"])
        repeated = controller.decide_legacy_transition(
            pr=1,
            head=HEAD_2,
            reason="retain the exact audited transition",
            reauthorize=True,
        )
        self.assertFalse(repeated["recorded"])
        self.assertEqual(repeated["transition"]["retired_hosted_fingerprints"], (fingerprint,))

    def test_legacy_transition_missing_hosted_retirement_rejects_changed_or_nonprior_fingerprints(self):
        controller, evidence, _, _, fingerprint = self.make_legacy_retirement_case()
        changed = {
            "pr": 1,
            "head": "7" * 40,
            "checkpoint": "trigger-uncheckpointed:42",
            "held": True,
            "reason": "changed record",
        }
        evidence[(1, "hosted")].append(changed)
        with self.assertRaisesRegex(ControllerError, "active or ambiguous"):
            controller.decide_legacy_transition(
                pr=1,
                head=HEAD_2,
                reason="a changed observation is not the audited fingerprint",
                reauthorize=True,
                retire_missing_hosted_fingerprint=fingerprint,
            )

        evidence[(1, "hosted")].remove(changed)
        with self.assertRaisesRegex(ControllerError, "exactly match a fingerprint from the prior transition"):
            controller.decide_legacy_transition(
                pr=1,
                head=HEAD_2,
                reason="an unaudited fingerprint cannot be retired",
                reauthorize=True,
                retire_missing_hosted_fingerprint="0" * 64,
            )
        self.assertEqual(len(controller._state().legacy_transitions), 1)

    def test_legacy_transition_missing_hosted_retirement_rejects_incomplete_or_blocked_audits(self):
        blockers = (
            ("active_reservations", "active or unresolved Hosted reservation"),
            ("unmatched_responses", "unmatched Hosted response"),
            ("ambiguous_responses", "ambiguous Hosted response"),
            ("unresolved_findings", "unresolved actionable findings"),
        )
        for field, message in blockers:
            with self.subTest(field=field):
                controller, evidence, _, _, fingerprint = self.make_legacy_retirement_case()
                evidence.audit[field] = ["blocked"]
                with self.assertRaisesRegex(ControllerError, message):
                    controller.decide_legacy_transition(
                        pr=1,
                        head=HEAD_2,
                        reason="all current Hosted evidence must be attributable and complete",
                        reauthorize=True,
                        retire_missing_hosted_fingerprint=fingerprint,
                    )
                self.assertEqual(len(controller._state().legacy_transitions), 1)

    def test_legacy_transition_missing_hosted_retirement_rechecks_moved_parent_and_head(self):
        for move in ("parent", "head"):
            with self.subTest(move=move):
                holder = {}

                def move_during_audit(move=move, holder=holder):
                    controller = holder["controller"]
                    if move == "parent":
                        controller.github.values[1] = pr(1, HEAD_2, base_tip="9" * 40)
                        controller.git.heads["develop"] = "9" * 40
                    else:
                        controller.github.values[1] = pr(1, "8" * 40)
                        controller.git.heads["feature-1"] = "8" * 40

                controller, _, _, _, fingerprint = self.make_legacy_retirement_case(on_audit=move_during_audit)
                holder["controller"] = controller
                with self.assertRaisesRegex(ControllerError, "changed during missing Hosted fingerprint retirement"):
                    controller.decide_legacy_transition(
                        pr=1,
                        head=HEAD_2,
                        reason="the live topology must remain stable through the audit",
                        reauthorize=True,
                        retire_missing_hosted_fingerprint=fingerprint,
                    )
                self.assertEqual(len(controller._state().legacy_transitions), 1)

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

    def test_unsupported_decision_error_lists_supported_operations(self):
        controller = self.make({})
        with self.assertRaisesRegex(
            ControllerError, "decision must be retain, reopen, policy, transition, or reconcile"
        ):
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

                self.assertEqual(reconciliation.status, "COHERENT")
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
