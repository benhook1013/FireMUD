import dataclasses
import json
import multiprocessing
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review import state as state_module
from pr_review.policy import Channel, Evidence, ReviewStatus, completion_status, select_review_target, taper_satisfied
from pr_review.stack import PRSnapshot, ReconciliationStatus, ReviewAnchor, classify_anchor, reconcile_stack
from pr_review.state import (
    Judgment,
    LegacyEvidenceTransition,
    PolicyOverride,
    ReviewAllocation,
    ReviewState,
    StackReconciliationDecision,
    StateError,
    StateStore,
    SummaryFindingDisposition,
    adjudicate_summary_findings,
    observation_fingerprint,
)


def _append_pr(path: str, pr: int) -> None:
    store = StateStore(path)

    def append(current):
        return ReviewState(ordered_prs=current.ordered_prs + (pr,))

    store.update(append)


def _append_prs(path: str, prs: tuple[int, ...]) -> None:
    for pr in prs:
        _append_pr(path, pr)


class ReviewStateStackTest(unittest.TestCase):
    def test_review_allocation_round_trips_and_is_keyed_by_pr_and_channel(self):
        allocation = ReviewAllocation(
            pr=2849,
            channel="hosted",
            head="a" * 40,
            parent_identity="develop",
            parent_head="b" * 40,
            merge_base="c" * 40,
            patch_id="patch-2849",
            baseline_checkpoints=("checkpoint-1", "checkpoint-2"),
            reason="one final Hosted result before handoff",
            handoff_checkpoint="handoff-1",
            handoff_head="d" * 40,
            handoff_validation="focused contracts and required CI passed",
        )
        state = ReviewState(allocations={allocation.identity: allocation})

        restored = ReviewState.from_dict(state.to_dict())

        self.assertEqual(restored, state)
        self.assertEqual(restored.allocations["2849:hosted"].baseline_checkpoints, ("checkpoint-1", "checkpoint-2"))

    def test_old_state_without_allocations_remains_readable(self):
        state = ReviewState.from_dict({"schema_version": 1, "ordered_prs": [2849]})

        self.assertEqual(state.ordered_prs, (2849,))
        self.assertEqual(state.allocations, {})

    def test_review_allocation_rejects_malformed_or_incomplete_records(self):
        base = {
            "pr": 2849,
            "channel": "hosted",
            "head": "a" * 40,
            "parent_identity": "develop",
            "parent_head": "b" * 40,
            "merge_base": "c" * 40,
            "patch_id": "patch-2849",
            "baseline_checkpoints": ["checkpoint-1"],
            "reason": "handoff",
        }
        for name, value in (
            ("head", "short"),
            ("parent_identity", " "),
            ("baseline_checkpoints", ["checkpoint-1", "checkpoint-1"]),
            ("baseline_checkpoints", "checkpoint-1"),
            ("handoff_checkpoint", "handoff-1"),
            ("handoff_head", "not-a-sha"),
        ):
            with self.subTest(name=name, value=value):
                malformed = {**base, name: value}
                with self.assertRaises(StateError):
                    ReviewAllocation.from_dict(malformed)

        with self.assertRaisesRegex(StateError, "outside the private schema"):
            ReviewAllocation.from_dict({**base, "unexpected": True})

    def test_review_state_rejects_mismatched_allocation_key_and_malformed_mapping(self):
        allocation = ReviewAllocation(
            2849,
            "hosted",
            "a" * 40,
            "develop",
            "b" * 40,
            "c" * 40,
            "patch-2849",
            ("checkpoint-1",),
            "handoff",
        )
        with self.assertRaisesRegex(StateError, "matching PR and channel"):
            ReviewState(allocations={"2849:cli": allocation})
        with self.assertRaisesRegex(StateError, "review allocations must be an object"):
            ReviewState.from_dict({"schema_version": 1, "allocations": []})
        with self.assertRaisesRegex(StateError, "review allocation records must be objects"):
            ReviewState.from_dict({"schema_version": 1, "allocations": {"2849:hosted": None}})

    def test_state_is_schema_versioned_and_atomic_store_keeps_only_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "firemud" / "pr-review-stack.json"
            state = ReviewState(
                ordered_prs=(2838, 2818),
                policy_overrides={
                    "2838:hosted": PolicyOverride(
                        hosted_zero_useful=2, head="h1", checkpoint="c1", reason="close-out", patch_id="p1"
                    )
                },
                judgments=(Judgment(2838, "cli", "retain", "h1", "c1", "equivalent history reviewed", "p1"),),
                reconciliations=(
                    StackReconciliationDecision(
                        2838,
                        "cli",
                        "old-checkpoint",
                        "old-child",
                        "current-child",
                        "2818",
                        "parent-head",
                        "merge-base",
                        "patch-id",
                        "rebased to exact current parent",
                    ),
                ),
                summary_dispositions=(
                    SummaryFindingDisposition(
                        2838,
                        "1" * 40,
                        "review",
                        71,
                        "duplicate",
                        2,
                        "rejected",
                        "these duplicate annotations do not apply to this PR",
                    ),
                ),
            )
            store = StateStore(path)
            store.update(lambda _: state)
            loaded = store.load()
            self.assertEqual(loaded, state)
            document = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(document["schema_version"], 1)
            self.assertEqual(document["policy_overrides"]["2838:hosted"]["head"], "h1")
            self.assertEqual(document["reconciliations"][0]["parent_head"], "parent-head")
            self.assertEqual(document["summary_dispositions"][0]["summary_id"], 71)
            self.assertEqual(document["allocations"], {})
            self.assertNotIn("live_head", json.dumps(document))
            self.assertNotIn("base_tip", json.dumps(document))
            self.assertNotIn("evidence", json.dumps(document))

    def test_legacy_transition_round_trips_exact_anchor_and_fingerprints(self):
        transition = LegacyEvidenceTransition(
            2818,
            "a" * 40,
            "develop",
            "b" * 40,
            "c" * 40,
            "patch-id",
            (observation_fingerprint({"checkpoint": "hosted"}),),
            (observation_fingerprint({"checkpoint": "cli"}),),
            "legacy evidence lacks modern anchors",
            (observation_fingerprint({"checkpoint": "hosted"}),),
        )
        state = ReviewState(ordered_prs=(2818,), legacy_transitions=(transition,))
        self.assertEqual(ReviewState.from_dict(state.to_dict()), state)
        self.assertTrue(transition.matches(2818, {
            "child_head": "a" * 40,
            "parent_identity": "develop",
            "parent_head": "b" * 40,
            "merge_base": "c" * 40,
            "patch_id": "patch-id",
        }))

    def test_malformed_nested_state_records_raise_state_error(self):
        malformed_states = (
            {"policy_overrides": None},
            {"policy_overrides": []},
            {"policy_overrides": {"1:hosted": None}},
            {"judgments": [{}]},
            {"reconciliations": [{}]},
            {"summary_dispositions": [{}]},
        )
        for malformed in malformed_states:
            with self.subTest(malformed=malformed), self.assertRaisesRegex(
                StateError, "(policy overrides|policy override records|malformed private records)"
            ):
                ReviewState.from_dict({"schema_version": 1, **malformed})

    def test_state_error_from_nested_record_is_preserved(self):
        with self.assertRaisesRegex(StateError, "hosted_zero_useful must be a positive integer or null"):
            ReviewState.from_dict(
                {
                    "schema_version": 1,
                    "policy_overrides": {"1:hosted": {"hosted_zero_useful": 0}},
                }
            )

    def test_summary_dispositions_are_all_or_nothing_and_exactly_bound(self):
        head = "a" * 40
        summary = {
            "status": "current",
            "head_sha": head,
            "source": "review",
            "identity": 42,
            "findings": [
                {"kind": "outside_diff", "count": 2},
                {"kind": "duplicate", "count": 1},
            ],
        }
        rejected = SummaryFindingDisposition(
            2838, head, "review", 42, "outside_diff", 2, "rejected", "outside annotations are harmless"
        )

        remaining, matched = adjudicate_summary_findings(2838, head, summary, (rejected,))

        self.assertEqual(remaining, [{"kind": "duplicate", "count": 1}])
        self.assertEqual(matched, [rejected.to_dict()])
        self.assertEqual(len(summary["findings"]), 2)
        for disposition in (
            dataclasses.replace(rejected, pr=1),
            dataclasses.replace(rejected, count=1),
            dataclasses.replace(rejected, summary_id=43),
        ):
            with self.subTest(disposition=disposition):
                remaining, matched = adjudicate_summary_findings(2838, head, summary, (disposition,))
                self.assertEqual(remaining, summary["findings"])
                self.assertEqual(matched, [])

        accepted_unfixed = dataclasses.replace(rejected, decision="accepted_unfixed")
        remaining, _ = adjudicate_summary_findings(2838, head, summary, (accepted_unfixed,))
        self.assertEqual(remaining, summary["findings"])

        later_summary = {**summary, "identity": 44}
        remaining, matched = adjudicate_summary_findings(2838, head, later_summary, (rejected,))
        self.assertEqual(remaining, later_summary["findings"])
        self.assertEqual(matched, [])

    def test_accepted_fixed_disposition_cannot_clear_current_head_summary(self):
        head = "a" * 40
        summary = {
            "status": "current",
            "head_sha": head,
            "source": "review",
            "identity": 42,
            "findings": [{"kind": "duplicate", "count": 1}],
        }
        accepted_fixed = SummaryFindingDisposition(
            2838,
            head,
            "review",
            42,
            "duplicate",
            1,
            "accepted_fixed",
            "fix is present on the subsequent head",
            corrected_head="b" * 40,
        )

        remaining, matched = adjudicate_summary_findings(2838, head, summary, (accepted_fixed,))

        self.assertEqual(remaining, summary["findings"])
        self.assertEqual(matched, [accepted_fixed.to_dict()])

    @unittest.skipUnless("fork" in multiprocessing.get_all_start_methods(), "requires the fork start method")
    def test_concurrent_updates_are_serialized_and_leave_valid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "firemud" / "pr-review-stack.json")
            StateStore(path).save(ReviewState())
            context = multiprocessing.get_context("fork")
            pr_batches = [tuple(range(first_pr, first_pr + 5)) for first_pr in (11, 21, 31, 41)]
            expected_prs = {pr for batch in pr_batches for pr in batch}
            processes = [context.Process(target=_append_prs, args=(path, batch)) for batch in pr_batches]
            for process in processes:
                process.start()
            timed_out = []
            try:
                for process in processes:
                    process.join(5)
                    if process.is_alive():
                        timed_out.append(process)
            finally:
                active_processes = [process for process in processes if process.is_alive()]
                for process in active_processes:
                    process.terminate()
                for process in active_processes:
                    process.join(5)

                active_processes = [process for process in active_processes if process.is_alive()]
                for process in active_processes:
                    process.kill()
                for process in active_processes:
                    process.join()

            for process in processes:
                if process not in timed_out:
                    self.assertEqual(process.exitcode, 0)
            if timed_out:
                self.fail(
                    "child process(es) remained alive after the join timeout: "
                    + ", ".join(str(process.pid) for process in timed_out)
                )
            actual_prs = StateStore(path).load().ordered_prs
            self.assertEqual(len(actual_prs), len(expected_prs))
            self.assertEqual(set(actual_prs), expected_prs)

    def test_hosted_and_cli_policy_overrides_can_coexist_for_one_pr(self):
        state = ReviewState(
            ordered_prs=(1,),
            policy_overrides={
                "1:hosted": PolicyOverride(
                    hosted_zero_useful=2, head="h", checkpoint="hosted-1", reason="hosted close-out", patch_id="p"
                ),
                "1:cli": PolicyOverride(
                    cli_zero_useful=1, head="h", checkpoint="cli-1", reason="CLI close-out", patch_id="p"
                ),
            },
        )
        self.assertEqual(state.policy_overrides["1:hosted"].hosted_zero_useful, 2)
        self.assertEqual(state.policy_overrides["1:cli"].cli_zero_useful, 1)

    def test_zero_useful_override_is_rejected_at_the_state_boundary(self):
        with self.assertRaises(StateError):
            PolicyOverride(hosted_zero_useful=0, head="h", checkpoint="c", reason="close-out", patch_id="p")

    def test_legacy_or_stale_policy_override_cannot_apply_without_exact_patch_identity(self):
        state = ReviewState(
            ordered_prs=(1,),
            policy_overrides={
                "1:cli": PolicyOverride(cli_zero_useful=1, head="h", checkpoint="c", reason="close-out", patch_id="p")
            },
        )
        current = Evidence(
            1,
            "h",
            "c",
            patch_id="other",
            anchored=True,
            completed=True,
            attributable=True,
            corrected_state=True,
        )
        self.assertEqual(completion_status(state, Channel.CLI, (current,)), ReviewStatus.READY)
        matching = dataclasses.replace(current, patch_id="p")
        self.assertEqual(completion_status(state, Channel.CLI, (matching,)), ReviewStatus.COMPLETE)
        legacy = ReviewState(
            ordered_prs=(1,),
            policy_overrides={
                "1:cli": PolicyOverride(cli_zero_useful=1, head="h", checkpoint="c", reason="legacy")
            },
        )
        self.assertEqual(completion_status(legacy, Channel.CLI, (dataclasses.replace(current, patch_id=None),)), ReviewStatus.READY)

    def test_git_common_dir_bounds_subprocess_and_translates_timeout(self):
        timeout = state_module.subprocess.TimeoutExpired("git rev-parse --git-common-dir", 30)
        with (
            patch.object(state_module.subprocess, "run", side_effect=timeout) as run,
            self.assertRaisesRegex(StateError, "cannot resolve the repository Git common directory"),
        ):
            state_module.git_common_dir()
        self.assertEqual(run.call_args.kwargs["timeout"], 30)

    def test_override_cannot_bypass_an_accepted_or_non_zero_useful_checkpoint(self):
        state = ReviewState(
            ordered_prs=(1,),
            policy_overrides={
                "1:hosted": PolicyOverride(
                    hosted_zero_useful=1, head="h", checkpoint="c", reason="narrow close-out", patch_id="p"
                )
            },
        )
        accepted = Evidence(
            1,
            "h",
            "c",
            patch_id="p",
            anchored=True,
            completed=True,
            attributable=True,
            corrected_state=True,
            accepted=1,
        )
        self.assertEqual(completion_status(state, Channel.HOSTED, (accepted,)), ReviewStatus.READY)

    def test_valid_complete_requires_true_anchor_and_corrected_state_for_every_round(self):
        unanchored = Evidence(
            1, "h", "c", patch_id="p", completed=True, attributable=True, corrected_state=True, anchored=None
        )
        self.assertFalse(taper_satisfied(Channel.CLI, (unanchored,), 1))
        not_corrected = Evidence(
            1, "h", "c1", patch_id="p", anchored=True, completed=True, attributable=True, corrected_state=False
        )
        corrected = Evidence(
            1, "h", "c2", patch_id="p", anchored=True, completed=True, attributable=True, corrected_state=True
        )
        self.assertFalse(taper_satisfied(Channel.HOSTED, (not_corrected, corrected), 2))
        self.assertFalse(taper_satisfied(Channel.CLI, (not_corrected,), 1))
        self.assertEqual(
            completion_status(ReviewState(ordered_prs=(1,)), Channel.CLI, (not_corrected,)),
            ReviewStatus.MISSING_EVIDENCE,
        )

    def test_hosted_default_requires_two_corrected_state_dry_rounds_but_override_allows_one(self):
        first = Evidence(
            1, "h", "c1", patch_id="p", anchored=True, completed=True, attributable=True, corrected_state=True
        )
        second = Evidence(
            1, "h", "c2", patch_id="p", anchored=True, completed=True, attributable=True, corrected_state=True
        )
        state = ReviewState(ordered_prs=(1,))
        self.assertEqual(completion_status(state, Channel.HOSTED, (first,)), ReviewStatus.READY)
        self.assertEqual(completion_status(state, Channel.HOSTED, (first, second)), ReviewStatus.COMPLETE)
        override = ReviewState(
            ordered_prs=(1,),
            policy_overrides={
                "1:hosted": PolicyOverride(
                    hosted_zero_useful=1, head="h", checkpoint="c1", reason="narrow close-out", patch_id="p"
                )
            },
        )
        self.assertEqual(completion_status(override, Channel.HOSTED, (first,)), ReviewStatus.COMPLETE)

    def test_merged_predecessors_collapse_to_nearest_unmerged_or_default(self):
        snapshots = {
            1: PRSnapshot(1, "a", "develop", "d", merged=True, head_ref="feature-1"),
            2: PRSnapshot(2, "b", "develop", "d", merged=False, head_ref="feature-2"),
            3: PRSnapshot(3, "c", "feature-2", "b", merged=False, head_ref="feature-3"),
            4: PRSnapshot(4, "e", "feature-3", "c", merged=False, head_ref="feature-4"),
        }
        result = reconcile_stack((1, 2, 3, 4), snapshots, "develop", "d", is_ancestor=lambda parent, child: True)
        self.assertIsNone(result.links[1].parent_pr)
        self.assertEqual(result.links[3].parent_pr, 2)
        self.assertEqual(result.links[4].parent_pr, 3)

    def test_parent_movement_marks_every_affected_descendant(self):
        snapshots = {
            1: PRSnapshot(1, "a2", "develop", "d", head_ref="one"),
            2: PRSnapshot(2, "b", "one", "a", head_ref="two"),
            3: PRSnapshot(3, "c", "two", "b", head_ref="three"),
        }
        result = reconcile_stack((1, 2, 3), snapshots, "develop", "d", is_ancestor=lambda parent, child: True)
        self.assertEqual(result.status_for(2), ReconciliationStatus.PARENT_MOVED)
        self.assertEqual(result.status_for(3), ReconciliationStatus.PARENT_MOVED)

    def test_reconciliation_decision_matches_only_its_exact_current_anchor(self):
        decision = StackReconciliationDecision(
            2,
            "cli",
            "checkpoint",
            "prior-head",
            "child-head",
            "parent-pr",
            "parent-head",
            "merge-base",
            "patch-id",
            "rebase confirmed",
        )
        anchor = {
            "child_head": "child-head",
            "parent_identity": "parent-pr",
            "parent_head": "parent-head",
            "merge_base": "merge-base",
            "patch_id": "patch-id",
        }
        self.assertTrue(decision.matches(2, anchor))
        self.assertFalse(decision.matches(1, anchor))
        self.assertFalse(decision.matches(2, {**anchor, "parent_head": "new-parent-head"}))

    def test_unreconciled_child_is_not_cleared_by_an_available_merge_base(self):
        snapshots = {
            1: PRSnapshot(1, "a", "develop", "d", head_ref="one"),
            2: PRSnapshot(2, "c", "one", "a", head_ref="two"),
        }
        result = reconcile_stack(
            (1, 2), snapshots, "develop", "d", is_ancestor=lambda parent, child: False, merge_bases={2: "m"}
        )
        self.assertEqual(result.status_for(2), ReconciliationStatus.UNRECONCILED)
        self.assertFalse(result.allowed(2))

    def test_anchor_classification_never_silently_accepts_history_changes(self):
        old = ReviewAnchor(1, "h1", "develop", "d", "m1", "patch")
        self.assertEqual(
            classify_anchor(old, dataclasses.replace(old, parent_head="d2")), ReconciliationStatus.PARENT_MOVED
        )
        self.assertEqual(
            classify_anchor(old, dataclasses.replace(old, child_head="h2")), ReconciliationStatus.EQUIVALENT_HISTORY
        )
        self.assertEqual(
            classify_anchor(old, dataclasses.replace(old, patch_id="other")), ReconciliationStatus.PATCH_CHANGED
        )

    def test_hosted_rate_limit_and_missing_evidence_never_advance(self):
        state = ReviewState(ordered_prs=(1, 2))
        limited = Evidence(1, "h", "c", completed=True, attributable=True, corrected_state=True, rate_limited=True)
        target = select_review_target(state, Channel.HOSTED, (1, 2), {1: (limited,), 2: ()})
        self.assertEqual((target.target, target.status), (1, ReviewStatus.RATE_LIMITED))
        target = select_review_target(state, Channel.HOSTED, (1, 2), {1: (), 2: ()})
        self.assertEqual((target.target, target.status), (1, ReviewStatus.MISSING_EVIDENCE))

    def test_cli_advances_through_consecutive_stable_prs_after_three_zero_useful(self):
        state = ReviewState(ordered_prs=(1, 2, 3))
        history = tuple(
            Evidence(
                1,
                "h1",
                f"c{i}",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            )
            for i in range(3)
        )
        second_history = tuple(dataclasses.replace(item, pr=2) for item in history)
        target = select_review_target(state, Channel.CLI, (1, 2, 3), {1: history, 2: second_history, 3: ()})
        self.assertEqual((target.target, target.status), (3, ReviewStatus.MISSING_EVIDENCE))

    def test_evidence_bound_to_another_pr_cannot_advance_target(self):
        state = ReviewState(ordered_prs=(1, 2))
        complete_for_first = tuple(
            Evidence(
                1,
                "h",
                f"c{i}",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            )
            for i in range(3)
        )
        target = select_review_target(state, Channel.CLI, (2,), {2: complete_for_first})
        self.assertEqual(target.target, 2)
        self.assertEqual(target.status, ReviewStatus.MISSING_EVIDENCE)

    def test_cli_stops_at_blocked_pr_and_accepted_finding_resets_streak(self):
        state = ReviewState(ordered_prs=(1, 2))
        history = (
            Evidence(1, "h", "c1", anchored=True, completed=True, attributable=True, corrected_state=True),
            Evidence(1, "h", "c2", anchored=True, completed=True, attributable=True, corrected_state=True),
            Evidence(
                1,
                "h",
                "c3",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
                accepted=1,
            ),
            Evidence(1, "h", "c4", anchored=True, completed=True, attributable=True, corrected_state=True),
        )
        target = select_review_target(state, Channel.CLI, (1, 2), {1: history, 2: ()})
        self.assertEqual((target.target, target.status), (1, ReviewStatus.READY))
        blocked = Evidence(2, "h2", "c", held=True)
        target = select_review_target(
            state,
            Channel.CLI,
            (1, 2),
            {
                1: tuple(
                    Evidence(
                        1,
                        "h",
                        f"c{i}",
                        anchored=True,
                        completed=True,
                        attributable=True,
                        corrected_state=True,
                    )
                    for i in range(3)
                ),
                2: (blocked,),
            },
        )
        self.assertEqual((target.target, target.status), (2, ReviewStatus.HELD))

    def test_zero_useful_rounds_do_not_accumulate_across_child_heads(self):
        state = ReviewState(ordered_prs=(1,))
        history = (
            Evidence(1, "old", "c1", completed=True, attributable=True, anchored=True, corrected_state=True),
            Evidence(1, "old", "c2", completed=True, attributable=True, anchored=True, corrected_state=True),
            Evidence(1, "new", "c3", completed=True, attributable=True, anchored=True, corrected_state=True),
        )
        target = select_review_target(state, Channel.CLI, (1,), {1: history})
        self.assertEqual(target.status, ReviewStatus.READY)

    def test_status_only_tail_cannot_hide_completed_review_taper_or_blocker(self):
        state = ReviewState(ordered_prs=(1,))
        reviews = tuple(
            Evidence(
                1,
                "current",
                f"review-{index}",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            )
            for index in range(3)
        )
        old_pending_capture = Evidence(
            1,
            "old",
            "pending-capture:run-old",
            completed=False,
            attributable=False,
            anchored=False,
            held=False,
        )
        history = (*reviews, old_pending_capture)
        self.assertEqual(completion_status(state, Channel.CLI, history), ReviewStatus.COMPLETE)
        self.assertEqual(
            select_review_target(state, Channel.CLI, (1,), {1: history}).status,
            ReviewStatus.COMPLETE,
        )

        held_capture = dataclasses.replace(old_pending_capture, held=True)
        self.assertEqual(completion_status(state, Channel.CLI, (*reviews, held_capture)), ReviewStatus.HELD)

    def test_readable_historical_evidence_without_modern_anchor_cannot_taper(self):
        history = tuple(
            Evidence(1, "h", f"legacy-{index}", completed=True, attributable=True, anchored=False) for index in range(3)
        )
        state = ReviewState(ordered_prs=(1,))
        self.assertFalse(taper_satisfied(Channel.CLI, history, 3))
        self.assertEqual(completion_status(state, Channel.CLI, history), ReviewStatus.READY)

    def test_non_counting_legacy_history_starts_fresh_without_satisfying_taper(self):
        history = tuple(
            Evidence(
                1,
                "h",
                f"legacy-{index}",
                anchored=False,
                completed=True,
                attributable=True,
                non_counting=True,
            )
            for index in range(3)
        )
        state = ReviewState(ordered_prs=(1,))
        self.assertEqual(completion_status(state, Channel.CLI, history), ReviewStatus.READY)
        self.assertFalse(taper_satisfied(Channel.CLI, history, 3))
        self.assertEqual(select_review_target(state, Channel.CLI, (1,), {1: history}).status, ReviewStatus.READY)

    def test_correction_checkpoint_remains_evidence_but_never_changes_the_taper(self):
        reviews = tuple(
            Evidence(
                1,
                "h",
                f"review-{index}",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            )
            for index in range(3)
        )
        correction = Evidence(
            1,
            "h",
            "correction-1",
            completed=True,
            attributable=True,
            accepted=1,
            raw=1,
            correction=True,
            anchored=True,
            corrected_state=True,
        )
        state = ReviewState(ordered_prs=(1,))
        self.assertTrue(taper_satisfied(Channel.CLI, (*reviews, correction), 3))
        self.assertEqual(
            select_review_target(state, Channel.CLI, (1,), {1: (*reviews, correction)}).status,
            ReviewStatus.COMPLETE,
        )
        self.assertFalse(taper_satisfied(Channel.CLI, (correction,), 3))

    def test_cross_channel_change_requires_exact_bound_judgment_and_provisional_never_tapers(self):
        evidence = tuple(
            Evidence(
                1,
                "h",
                f"c{i}",
                patch_id="p",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            )
            for i in range(3)
        )
        state = ReviewState(ordered_prs=(1,))
        target = select_review_target(state, Channel.CLI, (1,), {1: evidence}, other_channel_heads={1: "old"})
        self.assertEqual(target.status, ReviewStatus.JUDGMENT_REQUIRED)
        judged = ReviewState(
            ordered_prs=(1,), judgments=(Judgment(1, "cli", "retain", "h", "c2", "retain exact checkpoint", "p"),)
        )
        target = select_review_target(judged, Channel.CLI, (1,), {1: evidence}, other_channel_heads={1: "old"})
        self.assertEqual(target.status, ReviewStatus.COMPLETE)
        provisional = tuple(
            Evidence(1, "p", f"p{i}", anchored=True, completed=True, attributable=True, provisional=True)
            for i in range(3)
        )
        target = select_review_target(state, Channel.CLI, (1,), {1: provisional})
        self.assertEqual(target.status, ReviewStatus.READY)
        self.assertFalse(target.provisional)
        self.assertFalse(taper_satisfied(Channel.CLI, provisional, 3))

    def test_newer_same_head_provisional_evidence_allows_fresh_review_without_tapering(self):
        reviewed = tuple(
            Evidence(
                1,
                "h",
                f"c{i}",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            )
            for i in range(3)
        )
        provisional = Evidence(
            1,
            "h",
            "provisional-h",
            anchored=True,
            completed=True,
            attributable=True,
            corrected_state=True,
            provisional=True,
        )
        history = (*reviewed, provisional)
        state = ReviewState(ordered_prs=(1,))
        self.assertEqual(completion_status(state, Channel.CLI, history), ReviewStatus.READY)
        target = select_review_target(state, Channel.CLI, (1,), {1: history})
        self.assertEqual(target.status, ReviewStatus.READY)
        self.assertFalse(target.provisional)
        self.assertFalse(taper_satisfied(Channel.CLI, history, 3))

    def test_provisional_history_does_not_override_equivalent_history_judgment(self):
        reviewed = tuple(
            Evidence(
                1,
                "h1",
                f"c{i}",
                patch_id="p1",
                anchored=True,
                completed=True,
                attributable=True,
                # The retain judgment is what authorizes this old-head
                # streak; it must not rewrite the evidence as corrected.
                corrected_state=False,
            )
            for i in range(3)
        )
        provisional = Evidence(
            1,
            "h2",
            "provisional-h2",
            patch_id="p2",
            anchored=True,
            completed=True,
            attributable=True,
            provisional=True,
        )
        state = ReviewState(ordered_prs=(1,))
        history = (*reviewed, provisional)
        self.assertEqual(
            completion_status(
                state,
                Channel.CLI,
                history,
                reconciliation=ReconciliationStatus.EQUIVALENT_HISTORY,
            ),
            ReviewStatus.JUDGMENT_REQUIRED,
        )
        target = select_review_target(
            state,
            Channel.CLI,
            (1,),
            {1: history},
            reconciliation_by_pr={1: ReconciliationStatus.EQUIVALENT_HISTORY},
        )
        self.assertEqual(target.status, ReviewStatus.JUDGMENT_REQUIRED)

        judged = ReviewState(
            ordered_prs=(1,),
            judgments=(Judgment(1, "cli", "retain", "h1", "c2", "retain reviewed history", "p1"),),
        )
        self.assertEqual(
            completion_status(
                judged,
                Channel.CLI,
                history,
                reconciliation=ReconciliationStatus.EQUIVALENT_HISTORY,
            ),
            ReviewStatus.COMPLETE,
        )

    def test_equivalent_history_retain_does_not_count_an_older_patch(self):
        history = (
            Evidence(
                1,
                "h1",
                "c0",
                patch_id="older-patch",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
            ),
            Evidence(
                1,
                "h1",
                "c1",
                patch_id="current-patch",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=False,
            ),
            Evidence(
                1,
                "h1",
                "c2",
                patch_id="current-patch",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=False,
            ),
        )
        state = ReviewState(
            ordered_prs=(1,),
            judgments=(Judgment(1, "cli", "retain", "h1", "c2", "retain current patch only", "current-patch"),),
        )
        self.assertEqual(
            completion_status(
                state,
                Channel.CLI,
                history,
                reconciliation=ReconciliationStatus.EQUIVALENT_HISTORY,
            ),
            ReviewStatus.READY,
        )

    def test_all_provisional_history_is_ready_after_reconciliation(self):
        provisional = tuple(
            Evidence(1, "h2", f"provisional-{i}", anchored=True, completed=True, attributable=True, provisional=True)
            for i in range(3)
        )
        state = ReviewState(ordered_prs=(1,))
        self.assertEqual(
            completion_status(
                state,
                Channel.CLI,
                provisional,
                reconciliation=ReconciliationStatus.EQUIVALENT_HISTORY,
            ),
            ReviewStatus.READY,
        )
        target = select_review_target(
            state,
            Channel.CLI,
            (1,),
            {1: provisional},
            reconciliation_by_pr={1: ReconciliationStatus.EQUIVALENT_HISTORY},
        )
        self.assertEqual(target.status, ReviewStatus.READY)
        self.assertFalse(target.provisional)

    def test_judgment_cannot_apply_to_another_pr_with_same_head_and_checkpoint(self):
        patch_id = "shared-patch"
        history = tuple(
            Evidence(
                2,
                "h",
                f"c{i}",
                anchored=True,
                completed=True,
                attributable=True,
                corrected_state=True,
                patch_id=patch_id,
            )
            for i in range(3)
        )
        state = ReviewState(
            ordered_prs=(2,),
            judgments=(Judgment(1, "cli", "retain", "h", "c2", "belongs to PR one", patch_id),),
        )
        target = select_review_target(
            state,
            Channel.CLI,
            (2,),
            {2: history},
            other_channel_heads={2: "old"},
        )
        self.assertEqual(target.status, ReviewStatus.JUDGMENT_REQUIRED)

        correctly_bound = dataclasses.replace(
            state,
            judgments=(Judgment(2, "cli", "retain", "h", "c2", "belongs to PR two", patch_id),),
        )
        target = select_review_target(
            correctly_bound,
            Channel.CLI,
            (2,),
            {2: history},
            other_channel_heads={2: "old"},
        )
        self.assertEqual(target.status, ReviewStatus.COMPLETE)


if __name__ == "__main__":
    unittest.main()
