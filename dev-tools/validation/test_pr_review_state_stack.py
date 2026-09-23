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
from pr_review.state import Judgment, PolicyOverride, ReviewState, StackReconciliationDecision, StateError, StateStore


def _append_pr(path: str, pr: int) -> None:
    store = StateStore(path)

    def append(current):
        return ReviewState(ordered_prs=current.ordered_prs + (pr,))

    store.update(append)


class ReviewStateStackTest(unittest.TestCase):
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
            )
            store = StateStore(path)
            store.update(lambda _: state)
            loaded = store.load()
            self.assertEqual(loaded, state)
            document = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(document["schema_version"], 1)
            self.assertEqual(document["policy_overrides"]["2838:hosted"]["head"], "h1")
            self.assertEqual(document["reconciliations"][0]["parent_head"], "parent-head")
            self.assertNotIn("live_head", json.dumps(document))
            self.assertNotIn("base_tip", json.dumps(document))
            self.assertNotIn("evidence", json.dumps(document))

    def test_concurrent_updates_are_serialized_and_leave_valid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = str(Path(directory) / "firemud" / "pr-review-stack.json")
            StateStore(path).save(ReviewState())
            context = multiprocessing.get_context("fork")
            processes = [context.Process(target=_append_pr, args=(path, pr)) for pr in (11, 22)]
            for process in processes:
                process.start()
            for process in processes:
                process.join(5)
                self.assertEqual(process.exitcode, 0)
            self.assertEqual(set(StateStore(path).load().ordered_prs), {11, 22})

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

    def test_valid_complete_requires_true_anchor_and_hosted_corrected_state_for_every_round(self):
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
            Evidence(1, "h1", f"c{i}", anchored=True, completed=True, attributable=True) for i in range(3)
        )
        target = select_review_target(state, Channel.CLI, (1, 2, 3), {1: history, 2: history, 3: ()})
        self.assertEqual((target.target, target.status), (3, ReviewStatus.MISSING_EVIDENCE))

    def test_cli_stops_at_blocked_pr_and_accepted_finding_resets_streak(self):
        state = ReviewState(ordered_prs=(1, 2))
        history = (
            Evidence(1, "h", "c1", anchored=True, completed=True, attributable=True),
            Evidence(1, "h", "c2", anchored=True, completed=True, attributable=True),
            Evidence(1, "h", "c3", anchored=True, completed=True, attributable=True, accepted=1),
            Evidence(1, "h", "c4", anchored=True, completed=True, attributable=True),
        )
        target = select_review_target(state, Channel.CLI, (1, 2), {1: history, 2: ()})
        self.assertEqual((target.target, target.status), (1, ReviewStatus.READY))
        blocked = Evidence(2, "h2", "c", held=True)
        target = select_review_target(
            state,
            Channel.CLI,
            (1, 2),
            {
                1: tuple(Evidence(1, "h", f"c{i}", anchored=True, completed=True, attributable=True) for i in range(3)),
                2: (blocked,),
            },
        )
        self.assertEqual((target.target, target.status), (2, ReviewStatus.HELD))

    def test_zero_useful_rounds_do_not_accumulate_across_child_heads(self):
        state = ReviewState(ordered_prs=(1,))
        history = (
            Evidence(1, "old", "c1", completed=True, attributable=True),
            Evidence(1, "old", "c2", completed=True, attributable=True),
            Evidence(1, "new", "c3", completed=True, attributable=True),
        )
        target = select_review_target(state, Channel.CLI, (1,), {1: history})
        self.assertEqual(target.status, ReviewStatus.READY)

    def test_status_only_tail_cannot_hide_completed_review_taper_or_blocker(self):
        state = ReviewState(ordered_prs=(1,))
        reviews = tuple(
            Evidence(1, "current", f"review-{index}", anchored=True, completed=True, attributable=True)
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

    def test_correction_checkpoint_remains_evidence_but_never_changes_the_taper(self):
        reviews = tuple(
            Evidence(1, "h", f"review-{index}", anchored=True, completed=True, attributable=True)
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
            Evidence(1, "h", f"c{i}", patch_id="p", anchored=True, completed=True, attributable=True)
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

    def test_provisional_history_does_not_override_equivalent_history_judgment(self):
        reviewed = tuple(
            Evidence(1, "h1", f"c{i}", patch_id="p1", anchored=True, completed=True, attributable=True)
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
        history = tuple(
            Evidence(2, "h", f"c{i}", anchored=True, completed=True, attributable=True) for i in range(3)
        )
        state = ReviewState(
            ordered_prs=(2,),
            judgments=(Judgment(1, "cli", "retain", "h", "c2", "belongs to PR one", "p"),),
        )
        target = select_review_target(
            state,
            Channel.CLI,
            (2,),
            {2: history},
            other_channel_heads={2: "old"},
        )
        self.assertEqual(target.status, ReviewStatus.JUDGMENT_REQUIRED)


if __name__ == "__main__":
    unittest.main()
