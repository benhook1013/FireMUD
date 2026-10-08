import dataclasses
import fcntl
import json
import multiprocessing
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review import github
from pr_review import state as state_module
from pr_review.controller import ControllerError, LivePullRequest, ReviewController
from pr_review.policy import (
    Channel,
    Evidence,
    ReviewStatus,
    completion_status,
    select_review_target,
    taper_satisfied,
    taper_satisfied_for_state,
)
from pr_review.stack import PRSnapshot, ReconciliationStatus, ReviewAnchor, classify_anchor, reconcile_stack
from pr_review.state import (
    FindingRoute,
    Judgment,
    LegacyEvidenceTransition,
    PolicyOverride,
    ReviewAllocation,
    ReviewState,
    StackReconciliationDecision,
    StateError,
    StateLockTimeout,
    StateStore,
    SummaryFindingDisposition,
    adjudicate_summary_findings,
    merge_open_route,
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


class StackMutationGitHub:
    def __init__(self, *, repository="owner/repo", on_pull=None):
        self.repository = repository
        self.on_pull = on_pull
        self.calls = []

    def pull_request(self, number):
        self.calls.append(number)
        if self.on_pull is not None:
            self.on_pull()
        return LivePullRequest(
            number=number,
            head="a" * 40,
            base_ref="develop",
            base_tip="b" * 40,
            head_repository=self.repository,
        )


class StackMutationTests(unittest.TestCase):
    def make_store(self, directory, ordered_prs=(100, 200, 300)):
        path = Path(directory) / "stack.json"
        store = StateStore(path)
        override = PolicyOverride(
            hosted_zero_useful=1,
            head="a" * 40,
            checkpoint="checkpoint-100",
            reason="preserve the audited decision",
        )
        original = ReviewState(ordered_prs=ordered_prs, policy_overrides={"100:hosted": override})
        store.save(original)
        return store, original

    def test_add_inserts_one_validated_pr_and_preserves_non_order_state(self):
        with tempfile.TemporaryDirectory() as directory:
            store, original = self.make_store(directory)
            github_provider = StackMutationGitHub()
            controller = ReviewController(store=store, github=github_provider, repository="owner/repo")

            result = controller.add_stack_pr(250, before=200)

            updated = store.load()
            self.assertEqual(updated.ordered_prs, (100, 250, 200, 300))
            self.assertEqual(updated.policy_overrides, original.policy_overrides)
            self.assertEqual(github_provider.calls, [250])
            self.assertEqual(result["action"], "add")
            self.assertEqual(result["position"], "before #200")
            self.assertEqual(result["ordered_prs"], [100, 250, 200, 300])

    def test_add_supports_after_and_default_end_positions(self):
        for kwargs, expected, position in (
            ({"after": 100}, (100, 250, 200, 300), "after #100"),
            ({}, (100, 200, 300, 250), "end"),
        ):
            with self.subTest(position=position), tempfile.TemporaryDirectory() as directory:
                store, _ = self.make_store(directory)
                result = ReviewController(store=store).add_stack_pr(250, **kwargs)
                self.assertEqual(store.load().ordered_prs, expected)
                self.assertEqual(result["position"], position)

    def test_add_refuses_duplicate_self_or_missing_anchor_before_provider_read(self):
        with tempfile.TemporaryDirectory() as directory:
            store, _ = self.make_store(directory)
            github_provider = StackMutationGitHub()
            controller = ReviewController(store=store, github=github_provider, repository="owner/repo")

            cases = (
                ((200,), {}, "already configured"),
                ((250,), {"before": 250}, "own stack anchor"),
                ((250,), {"after": 999}, "anchor PR #999 is not configured"),
            )
            for (pr,), kwargs, message in cases:
                with self.subTest(message=message), self.assertRaisesRegex(ControllerError, message):
                    controller.add_stack_pr(pr, **kwargs)

            self.assertEqual(github_provider.calls, [])
            self.assertEqual(store.load().ordered_prs, (100, 200, 300))

    def test_add_refuses_cross_repository_target_and_concurrent_queue_change(self):
        with tempfile.TemporaryDirectory() as directory:
            store, _ = self.make_store(directory)
            cross_repository = StackMutationGitHub(repository="fork/repo")
            controller = ReviewController(store=store, github=cross_repository, repository="owner/repo")
            with self.assertRaisesRegex(ControllerError, "unsupported cross-repository head"):
                controller.add_stack_pr(250)
            self.assertEqual(store.load().ordered_prs, (100, 200, 300))

        with tempfile.TemporaryDirectory() as directory:
            store, _ = self.make_store(directory)

            def concurrent_append():
                store.update(lambda current: dataclasses.replace(current, ordered_prs=(*current.ordered_prs, 400)))

            github_provider = StackMutationGitHub(on_pull=concurrent_append)
            controller = ReviewController(store=store, github=github_provider, repository="owner/repo")
            with self.assertRaisesRegex(ControllerError, "changed during PR validation"):
                controller.add_stack_pr(250)
            self.assertEqual(store.load().ordered_prs, (100, 200, 300, 400))

    def test_move_preserves_other_entries_and_is_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            store, original = self.make_store(directory, (100, 200, 300, 400))
            controller = ReviewController(store=store)

            moved = controller.move_stack_pr(200, before=400)
            self.assertEqual(store.load().ordered_prs, (100, 300, 200, 400))
            self.assertTrue(moved["changed"])

            no_op = controller.move_stack_pr(200, before=400)
            self.assertFalse(no_op["changed"])
            self.assertEqual(store.load().ordered_prs, (100, 300, 200, 400))
            self.assertEqual(store.load().policy_overrides, original.policy_overrides)

    def test_move_supports_after_first_last_and_rejects_bad_targets(self):
        with tempfile.TemporaryDirectory() as directory:
            store, _ = self.make_store(directory, (100, 200, 300, 400))
            controller = ReviewController(store=store)

            self.assertEqual(controller.move_stack_pr(200, after=300)["ordered_prs"], [100, 300, 200, 400])
            self.assertEqual(controller.move_stack_pr(400, first=True)["ordered_prs"], [400, 100, 300, 200])
            self.assertEqual(controller.move_stack_pr(400, last=True)["ordered_prs"], [100, 300, 200, 400])

            cases = (
                ((999,), {"first": True}, "not configured in the review stack"),
                ((200,), {"before": 200}, "own stack anchor"),
                ((200,), {"before": 999}, "anchor PR #999 is not configured"),
                ((200,), {}, "exactly one of"),
                ((200,), {"before": 100, "after": 300}, "exactly one of"),
            )
            for (pr,), kwargs, message in cases:
                with self.subTest(message=message), self.assertRaisesRegex(ControllerError, message):
                    controller.move_stack_pr(pr, **kwargs)


class ReviewStateStackTest(unittest.TestCase):
    def test_routes_round_trip_with_stable_identity_and_old_state_defaults_empty_routes(self):
        route = FindingRoute(
            source_pr=2828,
            source_channel="hosted",
            source_review="review-901",
            source_finding="thread-77",
            observations=("parent-owned observation",),
            target_pr=2879,
        )
        state = ReviewState(routes=(route,))

        restored = ReviewState.from_dict(state.to_dict())

        self.assertEqual(restored, state)
        self.assertEqual(restored.routes[0].route_id, route.route_id)
        old_state = state.to_dict()
        del old_state["routes"]
        self.assertEqual(ReviewState.from_dict(old_state).routes, ())

    def test_route_loader_requires_target_history_array_before_conversion(self):
        route = FindingRoute(
            source_pr=2828,
            source_channel="hosted",
            source_review="review-901",
            source_finding="thread-77",
            observations=("child-owned observation",),
            target_pr=2879,
        )
        serialized = route.to_dict()
        del serialized["target_history"]
        self.assertEqual(FindingRoute.from_dict(serialized).target_history, ())

        for malformed in (None, "2879", {"target": 2879}, 2879):
            with self.subTest(target_history=malformed), self.assertRaisesRegex(StateError, "outside its schema"):
                FindingRoute.from_dict({**route.to_dict(), "target_history": malformed})

    def test_open_route_merge_deduplicates_observations_and_rejects_conflicting_targets(self):
        existing = FindingRoute(
            source_pr=2828,
            source_channel="hosted",
            source_review="review-901",
            source_finding="thread-77",
            observations=("first observation", "shared observation"),
            target_pr=2879,
        )
        incoming = dataclasses.replace(
            existing,
            observations=("shared observation", "new observation"),
        )

        merged = merge_open_route(existing, incoming)

        self.assertEqual(merged.observations, ("first observation", "shared observation", "new observation"))
        self.assertEqual(merged.target_pr, 2879)
        with self.assertRaisesRegex(StateError, "explicitly retargeted"):
            merge_open_route(existing, dataclasses.replace(incoming, target_pr=2880))
        with self.assertRaisesRegex(StateError, "same stable source finding"):
            merge_open_route(existing, dataclasses.replace(incoming, source_finding="thread-78"))

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
        self.assertNotIn("baseline_checkpoint", allocation.to_dict())
        self.assertNotIn("max_additional_completed", allocation.to_dict())

    def test_bounded_review_allocation_round_trips_without_changing_schema_v1(self):
        allocation = ReviewAllocation(
            pr=2827,
            channel="hosted",
            head="a" * 40,
            parent_identity="develop",
            parent_head="b" * 40,
            merge_base="c" * 40,
            patch_id="patch-2827",
            baseline_checkpoints=("5846432587", "5847200187"),
            reason="two additional Hosted results after the substantive review",
            baseline_checkpoint="5846432587",
            min_additional_completed=1,
            max_additional_completed=2,
            reopens_taper=True,
        )

        restored = ReviewAllocation.from_dict(allocation.to_dict())

        self.assertEqual(restored, allocation)
        self.assertTrue(restored.reopens_taper)
        state = ReviewState(ordered_prs=(2827,), allocations={allocation.identity: allocation})
        self.assertEqual(ReviewState.from_dict(state.to_dict()), state)
        self.assertEqual(state.to_dict()["schema_version"], 1)

    def test_review_allocation_accepts_decision_time_bounds_without_a_pinned_checkpoint(self):
        allocation = ReviewAllocation(
            pr=2827,
            channel="cli",
            head="a" * 40,
            parent_identity="develop",
            parent_head="b" * 40,
            merge_base="c" * 40,
            patch_id="patch-2827",
            baseline_checkpoints=("decision-baseline",),
            reason="two more completed CLI reviews",
            min_additional_completed=2,
            max_additional_completed=3,
        )

        restored = ReviewAllocation.from_dict(allocation.to_dict())

        self.assertEqual(restored, allocation)
        self.assertIsNone(restored.baseline_checkpoint)
        self.assertEqual(restored.min_additional_completed, 2)

    def test_human_allowance_roundtrips_unavailable_git_audit_facts(self):
        allocation = ReviewAllocation(
            pr=2827,
            channel="cli",
            head="a" * 40,
            parent_identity="develop",
            parent_head="b" * 40,
            merge_base=None,
            patch_id=None,
            baseline_checkpoints=(),
            reason="human requires exactly two more completed reviews",
            min_additional_completed=2,
            max_additional_completed=2,
        )
        self.assertEqual(ReviewAllocation.from_dict(allocation.to_dict()), allocation)
        for changed in ({"max_additional_completed": 3}, {"min_additional_completed": None}):
            with self.subTest(changed=changed):
                restored = ReviewAllocation.from_dict({**allocation.to_dict(), **changed})
                self.assertIsNone(restored.merge_base)
                self.assertIsNone(restored.patch_id)

    def test_bounded_review_allocation_rejects_missing_baseline_or_invalid_cap(self):
        base = {
            "pr": 2827,
            "channel": "hosted",
            "head": "a" * 40,
            "parent_identity": "develop",
            "parent_head": "b" * 40,
            "merge_base": "c" * 40,
            "patch_id": "patch-2827",
            "baseline_checkpoints": ["5846432587"],
            "reason": "bounded Hosted results",
            "baseline_checkpoint": "5846432587",
            "max_additional_completed": 2,
        }
        for malformed in (
            {**base, "baseline_checkpoint": "missing"},
            {**base, "max_additional_completed": 0},
            {**base, "max_additional_completed": True},
            {**base, "min_additional_completed": 3},
            {**base, "min_additional_completed": -1},
            {**base, "min_additional_completed": True},
        ):
            with self.subTest(malformed=malformed), self.assertRaises(StateError):
                ReviewAllocation.from_dict(malformed)

        without_checkpoint = dict(base)
        without_checkpoint["baseline_checkpoint"] = None
        self.assertEqual(
            ReviewAllocation.from_dict(without_checkpoint).max_additional_completed,
            2,
        )

    def test_target_holds_at_cap_with_findings_and_skips_audited_cap_stop(self):
        state = ReviewState(ordered_prs=(1, 2))

        pending = select_review_target(
            state,
            Channel.HOSTED,
            (1, 2),
            {1: (), 2: ()},
            allocation_holds={1: "cap exhausted; findings pending"},
        )
        skipped = select_review_target(
            state,
            Channel.HOSTED,
            (1, 2),
            {1: (), 2: ()},
            handed_off_prs=(1,),
        )

        self.assertEqual((pending.target, pending.status), (1, ReviewStatus.HELD))
        self.assertEqual(pending.reason, "cap exhausted; findings pending")
        self.assertEqual(skipped.target, 2)

    def test_review_allocation_stop_round_trips_reviewed_and_final_heads_separately(self):
        allocation = ReviewAllocation(
            pr=2818,
            channel="hosted",
            head="a" * 40,
            parent_identity="develop",
            parent_head="b" * 40,
            merge_base="c" * 40,
            patch_id="current-owned-patch",
            baseline_checkpoints=("latest-checkpoint",),
            reason="pre-granted one-result allocation",
            stop_basis="allocated",
            stop_checkpoint="latest-checkpoint",
            stop_reviewed_head="d" * 40,
            stop_reviewed_patch_id="reviewed-owned-patch",
            stop_head="a" * 40,
            stop_parent_identity="develop",
            stop_parent_head="b" * 40,
            stop_merge_base="c" * 40,
            stop_patch_id="current-owned-patch",
            stop_reason="human stopped discovery after adjudication",
            stop_summary_disposition_fingerprints=("e" * 64,),
            retained_ambiguous_fingerprints=("f" * 64, "e" * 64),
            retained_ambiguous_reason="terminal response lacks attributable review object",
        )

        restored = ReviewAllocation.from_dict(allocation.to_dict())

        self.assertEqual(restored, allocation)
        self.assertNotEqual(restored.stop_reviewed_head, restored.stop_head)
        self.assertEqual(restored.retained_ambiguous_fingerprints, ("f" * 64, "e" * 64))

    def test_review_allocation_reads_the_legacy_single_retained_fingerprint(self):
        legacy = {
            "pr": 2818,
            "channel": "hosted",
            "head": "a" * 40,
            "parent_identity": "develop",
            "parent_head": "b" * 40,
            "merge_base": "c" * 40,
            "patch_id": "current-owned-patch",
            "baseline_checkpoints": ["latest-checkpoint"],
            "reason": "pre-granted one-result allocation",
            "stop_basis": "direct_human",
            "stop_checkpoint": "latest-checkpoint",
            "stop_reviewed_head": "d" * 40,
            "stop_reviewed_patch_id": "reviewed-owned-patch",
            "stop_head": "a" * 40,
            "stop_parent_identity": "develop",
            "stop_parent_head": "b" * 40,
            "stop_merge_base": "c" * 40,
            "stop_patch_id": "current-owned-patch",
            "stop_reason": "human stopped discovery after adjudication",
            "stop_summary_disposition_fingerprints": [],
            "retained_ambiguous_fingerprint": "f" * 64,
            "retained_ambiguous_reason": "terminal response lacks attributable review object",
        }

        restored = ReviewAllocation.from_dict(legacy)

        self.assertEqual(restored.retained_ambiguous_fingerprints, ("f" * 64,))
        self.assertEqual(restored.to_dict()["retained_ambiguous_fingerprints"], ["f" * 64])

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

    def test_json_store_reads_direct_human_stop_but_refuses_to_write_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "firemud" / "pr-review-stack.json"
            path.parent.mkdir(parents=True)
            allocation = ReviewAllocation(
                pr=2849,
                channel="hosted",
                head="a" * 40,
                parent_identity="develop",
                parent_head="b" * 40,
                merge_base="c" * 40,
                patch_id="current-patch",
                baseline_checkpoints=(),
                reason="human stopped further review discovery",
                stop_basis="direct_human",
                stop_checkpoint="review-2849",
                stop_reviewed_head="d" * 40,
                stop_reviewed_patch_id="reviewed-patch",
                stop_head="a" * 40,
                stop_parent_identity="develop",
                stop_parent_head="b" * 40,
                stop_merge_base=None,
                stop_patch_id="current-patch",
                stop_reason="human stopped further review discovery",
            )
            state = ReviewState(ordered_prs=(2849,), allocations={"2849:hosted": allocation})
            path.write_text(json.dumps(state.to_dict()), encoding="utf-8")
            store = StateStore(path)
            original_bytes = path.read_bytes()

            self.assertEqual(store.load(), state)
            with self.assertRaisesRegex(StateError, "requires compatible SQLite"):
                store.update(lambda _current: state)

            self.assertEqual(path.read_bytes(), original_bytes)
            self.assertEqual(list(path.parent.glob(f".{path.name}.*")), [])

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
        anchor = {
            "child_head": "a" * 40,
            "parent_identity": "develop",
            "parent_head": "b" * 40,
            "merge_base": "c" * 40,
            "patch_id": "patch-id",
        }
        self.assertTrue(transition.matches(2818, anchor))
        self.assertFalse(transition.matches(1, anchor))
        for field in ("child_head", "parent_identity", "parent_head", "merge_base", "patch_id"):
            with self.subTest(field=field):
                self.assertFalse(transition.matches(2818, {**anchor, field: f"wrong-{field}"}))

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
            with (
                self.subTest(malformed=malformed),
                self.assertRaisesRegex(
                    StateError, "(policy overrides|policy override records|malformed private records)"
                ),
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

    def test_routed_summary_bucket_links_to_one_durable_route_and_clears_source_hold(self):
        head = "a" * 40
        routes = (
            FindingRoute(
                source_pr=2838,
                source_channel="hosted",
                source_review="summary:review:42",
                source_finding="outside_diff:2:workitem-base",
                observations=("WorkItem base observation",),
                target_pr=2879,
            ),
            FindingRoute(
                source_pr=2838,
                source_channel="hosted",
                source_review="summary:review:42",
                source_finding="outside_diff:2:timer-audit-base",
                observations=("timer-audit base observation",),
                target_pr=2879,
            ),
        )
        disposition = SummaryFindingDisposition(
            2838,
            head,
            "review",
            42,
            "outside_diff",
            2,
            "routed",
            "outside-diff observations belong to PR #2879",
            route_ids=tuple(route.route_id for route in routes),
        )
        state = ReviewState(routes=routes, summary_dispositions=(disposition,))
        summary = {
            "status": "current",
            "head_sha": head,
            "source": "review",
            "identity": 42,
            "findings": [{"kind": "outside_diff", "count": 2}],
        }

        remaining, matched = adjudicate_summary_findings(2838, head, summary, (disposition,))

        self.assertEqual(remaining, [])
        self.assertEqual(matched, [disposition.to_dict()])
        self.assertEqual(ReviewState.from_dict(state.to_dict()), state)
        with self.assertRaisesRegex(StateError, "link to a durable route"):
            ReviewState(summary_dispositions=(disposition,))
        self.assertEqual(len(state.routes), 2)
        self.assertNotEqual(state.routes[0].route_id, state.routes[1].route_id)

    def test_routed_summary_routes_match_exact_source_and_bucket_with_legacy_count_support(self):
        head = "a" * 40
        route = FindingRoute(
            source_pr=2838,
            source_channel="hosted",
            source_review="summary:review:42",
            source_finding="outside_diff:ref:workitem-base",
            observations=("base behavior belongs to another PR",),
        )
        disposition = SummaryFindingDisposition(
            2838,
            head,
            "review",
            42,
            "outside_diff",
            2,
            "routed",
            "route the matching observations",
            route_ids=(route.route_id,),
        )
        state = ReviewState(
            routes=(route,),
            summary_dispositions=(disposition,),
        )
        self.assertEqual(state.routes[0].source_finding, "outside_diff:ref:workitem-base")

        legacy_route = dataclasses.replace(route, source_finding="outside_diff:2:workitem-base")
        self.assertEqual(
            ReviewState(
                routes=(legacy_route,),
                summary_dispositions=(dataclasses.replace(disposition, route_ids=(legacy_route.route_id,)),),
            )
            .routes[0]
            .source_finding,
            "outside_diff:2:workitem-base",
        )

        for changed in (
            dataclasses.replace(route, source_pr=1),
            dataclasses.replace(route, source_channel="cli"),
            dataclasses.replace(route, source_review="summary:comment:42"),
            dataclasses.replace(route, source_review="summary:review:43"),
            dataclasses.replace(route, source_finding="duplicate:ref:workitem-base"),
            dataclasses.replace(route, source_finding="outside_diff:1:workitem-base"),
        ):
            with self.subTest(route=changed), self.assertRaisesRegex(StateError, "must match its source PR"):
                ReviewState(
                    routes=(changed,),
                    summary_dispositions=(dataclasses.replace(disposition, route_ids=(changed.route_id,)),),
                )

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
            policy_overrides={"1:cli": PolicyOverride(cli_zero_useful=1, head="h", checkpoint="c", reason="legacy")},
        )
        self.assertEqual(
            completion_status(legacy, Channel.CLI, (dataclasses.replace(current, patch_id=None),)), ReviewStatus.READY
        )

    def test_git_common_dir_bounds_subprocess_and_translates_timeout(self):
        timeout = state_module.subprocess.TimeoutExpired("git rev-parse --git-common-dir", 30)
        with (
            patch.object(state_module.subprocess, "run", side_effect=timeout) as run,
            self.assertRaisesRegex(StateError, "cannot resolve the repository Git common directory"),
        ):
            state_module.git_common_dir()
        self.assertEqual(run.call_args.kwargs["timeout"], 30)

    def test_git_common_dir_uses_remaining_hosted_preflight_budget(self):
        class Clock:
            now = 0.0

            def monotonic(self):
                return self.now

        clock = Clock()

        def git_call(args, **_kwargs):
            clock.now = 13
            return state_module.subprocess.CompletedProcess(args, 0, ".git\n", "")

        with (
            patch.object(github.time, "monotonic", side_effect=clock.monotonic),
            github.activate_hosted_preflight_budget(timeout_seconds=12),
            patch.object(state_module.subprocess, "run", side_effect=git_call) as run,
            self.assertRaises(github.HostedPreflightDeadlineExceeded),
        ):
            state_module.git_common_dir()

        self.assertEqual(run.call_args.kwargs["timeout"], 12)

    def test_state_update_bounds_a_held_lock_to_its_deadline(self):
        class Clock:
            now = 0.0

            def monotonic(self):
                return self.now

            def sleep(self, seconds):
                self.now += seconds

        clock = Clock()
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "state.json"
            store = StateStore(path)
            store.save(ReviewState(ordered_prs=(42,)))
            with store.lock_path.open("a+") as lock_handle:
                fcntl.flock(lock_handle.fileno(), fcntl.LOCK_EX)
                try:
                    with (
                        patch.object(state_module.time, "monotonic", side_effect=clock.monotonic),
                        patch.object(state_module.time, "sleep", side_effect=clock.sleep),
                        self.assertRaisesRegex(StateLockTimeout, "review-stack state lock"),
                    ):
                        store.update(
                            lambda current: ReviewState(ordered_prs=current.ordered_prs + (43,)),
                            lock_deadline=0.03,
                        )
                finally:
                    fcntl.flock(lock_handle.fileno(), fcntl.LOCK_UN)
            self.assertGreaterEqual(clock.now, 0.03)
            self.assertEqual(store.load().ordered_prs, (42,))

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

    def test_valid_complete_requires_true_anchor_but_not_a_current_head_match(self):
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
        self.assertTrue(taper_satisfied(Channel.HOSTED, (not_corrected, corrected), 2))
        self.assertTrue(taper_satisfied(Channel.CLI, (not_corrected,), 1))
        self.assertEqual(
            completion_status(ReviewState(ordered_prs=(1,)), Channel.CLI, (not_corrected,)),
            ReviewStatus.READY,
        )

    def test_hosted_default_requires_one_zero_useful_round_and_cli_keeps_three(self):
        state = ReviewState(ordered_prs=(1,))
        corrected = Evidence(
            1, "h", "corrected", patch_id="p", anchored=True, completed=True, attributable=True, corrected_state=True
        )
        self.assertEqual(completion_status(state, Channel.HOSTED, (corrected,)), ReviewStatus.COMPLETE)

        accepted = dataclasses.replace(corrected, checkpoint="accepted", accepted=1, raw=1)
        uncorrected = dataclasses.replace(corrected, checkpoint="uncorrected", corrected_state=False)
        unattributable = dataclasses.replace(corrected, checkpoint="unattributable", attributable=False)
        self.assertEqual(completion_status(state, Channel.HOSTED, (accepted,)), ReviewStatus.READY)
        self.assertEqual(completion_status(state, Channel.HOSTED, (uncorrected,)), ReviewStatus.COMPLETE)
        self.assertEqual(completion_status(state, Channel.HOSTED, (unattributable,)), ReviewStatus.READY)

        cli_rounds = tuple(dataclasses.replace(corrected, checkpoint=f"cli-{index}") for index in range(1, 4))
        self.assertEqual(completion_status(state, Channel.CLI, cli_rounds[:1]), ReviewStatus.READY)
        self.assertEqual(completion_status(state, Channel.CLI, cli_rounds[:2]), ReviewStatus.READY)
        self.assertEqual(completion_status(state, Channel.CLI, cli_rounds), ReviewStatus.COMPLETE)

    def test_empty_scope_timeline_is_missing_evidence_but_preserves_real_blockers(self):
        state = ReviewState(ordered_prs=(1,))
        timeline = {
            "pr": 1,
            "head": "h",
            "checkpoint": "scope-timeline:complete",
            "scope_timeline": True,
            "scope_timeline_complete": True,
            "non_counting": True,
        }

        self.assertEqual(completion_status(state, Channel.HOSTED, (timeline,)), ReviewStatus.MISSING_EVIDENCE)
        target = select_review_target(state, Channel.HOSTED, (1,), {1: (timeline,)})
        self.assertEqual((target.target, target.status), (1, ReviewStatus.MISSING_EVIDENCE))

        held = {**timeline, "scope_timeline": False, "non_counting": False, "held": True}
        self.assertEqual(
            completion_status(state, Channel.HOSTED, (timeline, held)),
            ReviewStatus.HELD,
        )

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

    def test_explicit_channel_stop_skips_only_that_pr_and_reports_distinct_status(self):
        state = ReviewState(ordered_prs=(1, 2))
        target = select_review_target(
            state,
            Channel.HOSTED,
            (1, 2),
            {1: (), 2: ()},
            human_stopped_prs=(1,),
        )

        self.assertEqual((target.target, target.status), (2, ReviewStatus.MISSING_EVIDENCE))

    def test_all_explicitly_stopped_targets_report_human_stopped_not_tapered(self):
        target = select_review_target(
            ReviewState(ordered_prs=(1, 2)),
            Channel.CLI,
            (1, 2),
            {1: (), 2: ()},
            human_stopped_prs=(1, 2),
        )

        self.assertIsNone(target.target)
        self.assertEqual(target.status, ReviewStatus.HUMAN_STOPPED)
        self.assertIn("explicitly stopped", target.reason)

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

    def test_cli_zero_useful_rounds_accumulate_across_heads_with_current_review(self):
        state = ReviewState(ordered_prs=(1,))
        history = (
            Evidence(
                1,
                "old",
                "c1",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
                lineage_proven_to_next=True,
            ),
            Evidence(
                1,
                "old",
                "c2",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
                lineage_proven_to_next=True,
            ),
            Evidence(1, "new", "c3", completed=True, attributable=True, anchored=True, corrected_state=True),
        )
        target = select_review_target(state, Channel.CLI, (1,), {1: history})
        self.assertEqual(target.status, ReviewStatus.COMPLETE)
        self.assertTrue(taper_satisfied(Channel.CLI, history, 3))

    def test_cli_cross_head_taper_requires_current_candidate_and_coherent_parent(self):
        state = ReviewState(ordered_prs=(1,))
        old_history = tuple(
            Evidence(1, "old", f"c{index}", completed=True, attributable=True, anchored=True, corrected_state=True)
            for index in range(1, 4)
        )
        self.assertEqual(
            completion_status(state, Channel.CLI, old_history, reconciliation=ReconciliationStatus.PATCH_CHANGED),
            ReviewStatus.COMPLETE,
        )
        self.assertEqual(
            completion_status(state, Channel.CLI, old_history, reconciliation=ReconciliationStatus.PARENT_MOVED),
            ReviewStatus.COMPLETE,
        )
        self.assertEqual(
            completion_status(state, Channel.CLI, old_history, reconciliation=ReconciliationStatus.EQUIVALENT_HISTORY),
            ReviewStatus.COMPLETE,
        )

    def test_cli_taper_can_persist_on_a_proven_descendant_candidate_but_not_an_unproven_one(self):
        state = ReviewState(ordered_prs=(1,))
        history = tuple(
            Evidence(
                1,
                "reviewed-head",
                f"c{index}",
                patch_id="reviewed-patch",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
            )
            for index in range(1, 4)
        )
        self.assertEqual(
            completion_status(state, Channel.CLI, history, reconciliation=ReconciliationStatus.PATCH_CHANGED),
            ReviewStatus.COMPLETE,
        )
        uncorrected_latest = dataclasses.replace(history[-1], corrected_state=False)
        self.assertTrue(taper_satisfied(Channel.CLI, (*history[:-1], uncorrected_latest), 3))
        self.assertEqual(
            completion_status(
                state,
                Channel.CLI,
                (*history[:-1], uncorrected_latest),
                reconciliation=ReconciliationStatus.COHERENT,
            ),
            ReviewStatus.COMPLETE,
        )
        descendant = (
            *history[:-1],
            dataclasses.replace(
                uncorrected_latest,
                current_candidate_descendant_proven=True,
            ),
        )
        self.assertTrue(taper_satisfied(Channel.CLI, descendant, 3))
        self.assertEqual(
            completion_status(state, Channel.CLI, descendant, reconciliation=ReconciliationStatus.PATCH_CHANGED),
            ReviewStatus.COMPLETE,
        )

    def test_changed_patch_requires_judgment_only_after_taper_is_complete(self):
        state = ReviewState(ordered_prs=(1,))
        dry_hosted = Evidence(
            1,
            "reviewed-head",
            "hosted-dry",
            patch_id="reviewed-patch",
            completed=True,
            attributable=True,
            anchored=True,
            corrected_state=True,
        )
        self.assertEqual(
            completion_status(
                state,
                Channel.HOSTED,
                (dry_hosted,),
                reconciliation=ReconciliationStatus.PATCH_CHANGED,
            ),
            ReviewStatus.COMPLETE,
        )

        useful_hosted = dataclasses.replace(dry_hosted, checkpoint="hosted-useful", accepted=1, raw=1)
        self.assertEqual(
            completion_status(
                state,
                Channel.HOSTED,
                (useful_hosted,),
                reconciliation=ReconciliationStatus.PATCH_CHANGED,
            ),
            ReviewStatus.READY,
        )

    def test_cli_accepted_finding_resets_streak_across_heads(self):
        history = (
            Evidence(1, "old", "c1", completed=True, attributable=True, anchored=True, corrected_state=True),
            Evidence(1, "old", "c2", completed=True, attributable=True, anchored=True, corrected_state=True),
            Evidence(
                1,
                "middle",
                "c3",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
                accepted=1,
                raw=1,
            ),
            Evidence(1, "current", "c4", completed=True, attributable=True, anchored=True, corrected_state=True),
        )
        self.assertFalse(taper_satisfied(Channel.CLI, history, 3))
        self.assertEqual(completion_status(ReviewState(ordered_prs=(1,)), Channel.CLI, history), ReviewStatus.READY)

    def test_unanchored_or_uncorrected_accepted_result_resets_taper_streak(self):
        dry_before = tuple(
            Evidence(1, "h", f"before-{index}", completed=True, attributable=True, anchored=True, corrected_state=True)
            for index in range(2)
        )
        dry_after = Evidence(1, "h", "after", completed=True, attributable=True, anchored=True, corrected_state=True)
        accepted_variants = (
            Evidence(
                1,
                "h",
                "accepted-unanchored",
                completed=True,
                attributable=True,
                anchored=False,
                corrected_state=True,
                accepted=1,
            ),
            Evidence(
                1,
                "h",
                "accepted-uncorrected",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=False,
                accepted=1,
            ),
        )

        self.assertTrue(taper_satisfied(Channel.CLI, (*dry_before, dry_after), 3))
        for accepted in accepted_variants:
            with self.subTest(checkpoint=accepted.checkpoint):
                history = (*dry_before, accepted, dry_after)
                self.assertFalse(taper_satisfied(Channel.CLI, history, 3))

    def test_completed_taper_survives_later_fixes_and_review_identity_changes(self):
        state = ReviewState(ordered_prs=(1,))
        dry_rounds = tuple(
            Evidence(
                1,
                "old-head",
                f"cli-{index}",
                patch_id="old-patch",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
            )
            for index in range(1, 4)
        )
        later_fix = dataclasses.replace(
            dry_rounds[-1],
            head="corrected-descendant",
            checkpoint="hosted-fix",
            patch_id="corrected-patch",
            corrected_state=False,
            accepted=1,
            streak_break_before=True,
        )
        history = (*dry_rounds, later_fix)

        self.assertTrue(taper_satisfied(Channel.CLI, history, 3))
        decision = select_review_target(
            state,
            Channel.CLI,
            (1,),
            {1: history},
            reconciliation_by_pr={1: ReconciliationStatus.PATCH_CHANGED},
        )
        self.assertEqual(decision.status, ReviewStatus.COMPLETE)
        self.assertIsNone(decision.target)
        self.assertTrue(decision.to_dict()["taper_complete"])

        topology_decision = select_review_target(
            state,
            Channel.CLI,
            (1,),
            {1: history},
            reconciliation_by_pr={1: ReconciliationStatus.PARENT_MOVED},
        )
        self.assertEqual(topology_decision.status, ReviewStatus.COMPLETE)
        self.assertIsNone(topology_decision.target)
        self.assertTrue(topology_decision.to_dict()["taper_complete"])

        hosted_dry = Evidence(
            1,
            "old-hosted-head",
            "hosted-dry",
            patch_id="old-hosted-patch",
            completed=True,
            attributable=True,
            anchored=True,
            corrected_state=True,
        )
        hosted_fix = dataclasses.replace(
            hosted_dry,
            head="hosted-corrected-descendant",
            checkpoint="hosted-fix",
            patch_id="corrected-hosted-patch",
            corrected_state=False,
            accepted=1,
        )
        self.assertTrue(taper_satisfied(Channel.HOSTED, (hosted_dry, hosted_fix), 1))

    def test_explicit_reopen_starts_fresh_cli_streak_after_useful_result(self):
        old_dry = tuple(
            Evidence(
                1,
                "review-head",
                f"old-dry-{index}",
                patch_id="review-patch",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
            )
            for index in range(1, 4)
        )
        reopen = Judgment(
            1,
            "cli",
            "reopen",
            old_dry[-1].head,
            old_dry[-1].checkpoint,
            "fresh CLI discovery is required",
            old_dry[-1].patch_id,
        )
        useful = Evidence(
            1,
            "review-head",
            "fresh-useful-2-of-2",
            patch_id="review-patch",
            completed=True,
            attributable=True,
            anchored=True,
            accepted=2,
            raw=2,
            corrected_state=True,
        )
        first_fresh_dry = Evidence(
            1,
            "review-head",
            "fresh-dry-1",
            patch_id="review-patch",
            completed=True,
            attributable=True,
            anchored=True,
            corrected_state=True,
        )
        state = ReviewState(ordered_prs=(1,), judgments=(reopen,))
        history = (*old_dry, useful, first_fresh_dry)

        self.assertTrue(taper_satisfied(Channel.CLI, old_dry, 3))
        self.assertFalse(taper_satisfied_for_state(state, Channel.CLI, history))
        decision = select_review_target(state, Channel.CLI, (1,), {1: history})
        self.assertEqual(decision.status, ReviewStatus.READY)
        self.assertFalse(decision.to_dict()["taper_complete"])

        two_fresh_dry = (*history, dataclasses.replace(first_fresh_dry, checkpoint="fresh-dry-2"))
        self.assertFalse(taper_satisfied_for_state(state, Channel.CLI, two_fresh_dry))
        three_fresh_dry = (*two_fresh_dry, dataclasses.replace(first_fresh_dry, checkpoint="fresh-dry-3"))
        self.assertTrue(taper_satisfied_for_state(state, Channel.CLI, three_fresh_dry))

    def test_latest_exact_reopen_reopens_taper_while_retain_preserves_it(self):
        history = (
            Evidence(
                1,
                "review-head",
                "hosted-dry",
                patch_id="review-patch",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
            ),
        )
        for reconciliation in (
            ReconciliationStatus.PATCH_CHANGED,
            ReconciliationStatus.EQUIVALENT_HISTORY,
        ):
            for judgment_decision in ("retain", "reopen"):
                with self.subTest(reconciliation=reconciliation, judgment=judgment_decision):
                    judgment = Judgment(
                        1,
                        "hosted",
                        judgment_decision,
                        "review-head",
                        "hosted-dry",
                        f"explicitly {judgment_decision} the latest review identity",
                        "review-patch",
                    )
                    decision = select_review_target(
                        ReviewState(ordered_prs=(1,), judgments=(judgment,)),
                        Channel.HOSTED,
                        (1,),
                        {1: history},
                        reconciliation_by_pr={1: reconciliation},
                    )

                    self.assertNotEqual(decision.status, ReviewStatus.JUDGMENT_REQUIRED)
                    if judgment_decision == "reopen":
                        self.assertEqual(decision.status, ReviewStatus.READY)
                        self.assertEqual(decision.target, 1)
                        self.assertFalse(decision.to_dict()["taper_complete"])
                    else:
                        self.assertEqual(decision.status, ReviewStatus.COMPLETE)
                        self.assertIsNone(decision.target)

    def test_cli_taper_counts_across_heads_despite_identity_marker(self):
        history = (
            Evidence(1, "old", "c1", completed=True, attributable=True, anchored=True, corrected_state=True),
            Evidence(1, "old", "c2", completed=True, attributable=True, anchored=True, corrected_state=True),
            Evidence(
                1,
                "new",
                "c3",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
                streak_break_before=True,
            ),
            Evidence(1, "new", "c4", completed=True, attributable=True, anchored=True, corrected_state=True),
        )
        self.assertTrue(taper_satisfied(Channel.CLI, history, 3))
        self.assertTrue(
            taper_satisfied(
                Channel.CLI,
                (
                    *history,
                    Evidence(1, "new", "c5", completed=True, attributable=True, anchored=True, corrected_state=True),
                ),
                3,
            )
        )
        hosted_history = tuple(
            Evidence(
                1,
                "hosted-head",
                f"h{index}",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
                streak_break_before=index == 2,
            )
            for index in range(1, 4)
        )
        self.assertTrue(taper_satisfied(Channel.HOSTED, hosted_history, 3))

    def test_cli_prior_uncorrected_rounds_count_without_proven_links(self):
        history = (
            Evidence(
                1,
                "old",
                "c1",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=False,
                lineage_proven_to_next=True,
            ),
            Evidence(
                1,
                "middle",
                "c2",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=False,
                lineage_proven_to_next=True,
            ),
            Evidence(1, "current", "c3", completed=True, attributable=True, anchored=True, corrected_state=True),
        )
        self.assertTrue(taper_satisfied(Channel.CLI, history, 3))
        self.assertEqual(
            completion_status(ReviewState(ordered_prs=(1,)), Channel.CLI, history),
            ReviewStatus.COMPLETE,
        )

        unproven_history = (
            dataclasses.replace(history[0], lineage_proven_to_next=False),
            history[1],
            history[2],
        )
        self.assertTrue(taper_satisfied(Channel.CLI, unproven_history, 3))

    def test_non_counting_and_unattributable_rounds_cannot_fill_cross_head_taper(self):
        first = Evidence(1, "old", "c1", completed=True, attributable=True, anchored=True, corrected_state=True)
        current = Evidence(1, "current", "c3", completed=True, attributable=True, anchored=True, corrected_state=True)
        excluded = (
            dataclasses.replace(first, checkpoint="excluded", non_counting=True),
            dataclasses.replace(first, checkpoint="unattributable", attributable=False),
        )
        for middle in excluded:
            with self.subTest(checkpoint=middle.checkpoint):
                history = (first, middle, current)
                self.assertFalse(taper_satisfied(Channel.CLI, history, 3))
                self.assertEqual(
                    completion_status(ReviewState(ordered_prs=(1,)), Channel.CLI, history),
                    ReviewStatus.READY,
                )

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

    def test_completed_taper_survives_request_only_topology_warnings(self):
        state = ReviewState(ordered_prs=(1,))
        reviews = tuple(
            Evidence(
                1,
                "old-parent",
                f"review-{index}",
                completed=True,
                attributable=True,
                anchored=True,
                corrected_state=True,
            )
            for index in range(3)
        )
        moved = (*reviews, Evidence(1, "current", "old-parent-warning", parent_moved=True))

        self.assertEqual(
            completion_status(state, Channel.CLI, moved, reconciliation=ReconciliationStatus.PARENT_MOVED),
            ReviewStatus.COMPLETE,
        )
        self.assertEqual(
            select_review_target(
                state,
                Channel.CLI,
                (1,),
                {1: moved},
                reconciliation_by_pr={1: ReconciliationStatus.PARENT_MOVED},
            ).status,
            ReviewStatus.COMPLETE,
        )
        rate_limited = (*reviews, Evidence(1, "current", "cooldown", rate_limited=True))
        self.assertEqual(completion_status(state, Channel.CLI, rate_limited), ReviewStatus.COMPLETE)
        active = (*moved, Evidence(1, "current", "active", active_review=True))
        self.assertEqual(completion_status(state, Channel.CLI, active), ReviewStatus.HELD)

    def test_incomplete_or_reopened_taper_does_not_hide_cooldown_or_active_work(self):
        one_dry = Evidence(
            1,
            "reviewed",
            "one-dry",
            completed=True,
            attributable=True,
            anchored=True,
            corrected_state=True,
        )
        cooldown = Evidence(1, "current", "cooldown", rate_limited=True)
        state = ReviewState(ordered_prs=(1,))
        self.assertEqual(completion_status(state, Channel.CLI, (one_dry, cooldown)), ReviewStatus.RATE_LIMITED)

        reviews = tuple(
            dataclasses.replace(one_dry, checkpoint=f"review-{index}", patch_id="review-patch") for index in range(3)
        )
        reopened = ReviewState(
            ordered_prs=(1,),
            judgments=(Judgment(1, "cli", "reopen", "reviewed", "review-2", "fresh review required", "review-patch"),),
        )
        active = Evidence(1, "current", "active", active_review=True)
        self.assertEqual(completion_status(reopened, Channel.CLI, (*reviews, active)), ReviewStatus.HELD)
        self.assertEqual(completion_status(reopened, Channel.CLI, (*reviews, cooldown)), ReviewStatus.RATE_LIMITED)

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

    def test_non_counting_history_does_not_hide_reconciliation_blockers(self):
        history = (Evidence(1, "h", "legacy", completed=True, attributable=True, non_counting=True),)
        state = ReviewState(ordered_prs=(1,))

        self.assertEqual(
            completion_status(state, Channel.CLI, history, reconciliation=ReconciliationStatus.PARENT_MOVED),
            ReviewStatus.PARENT_MOVED,
        )
        self.assertEqual(
            completion_status(state, Channel.CLI, history, reconciliation=ReconciliationStatus.UNRECONCILED),
            ReviewStatus.UNRECONCILED,
        )
        self.assertEqual(
            completion_status(state, Channel.CLI, history, reconciliation=ReconciliationStatus.COHERENT),
            ReviewStatus.READY,
        )

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

    def test_completed_taper_skips_cross_channel_identity_and_provisional_never_tapers(self):
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
        target = select_review_target(state, Channel.CLI, (1,), {1: evidence})
        self.assertEqual(target.status, ReviewStatus.COMPLETE)
        self.assertIsNone(target.target)
        judged = ReviewState(
            ordered_prs=(1,), judgments=(Judgment(1, "cli", "retain", "h", "c2", "retain exact checkpoint", "p"),)
        )
        target = select_review_target(judged, Channel.CLI, (1,), {1: evidence})
        self.assertEqual(target.status, ReviewStatus.COMPLETE)
        provisional = tuple(
            Evidence(1, "p", f"p{i}", anchored=True, completed=True, attributable=True, provisional=True)
            for i in range(3)
        )
        target = select_review_target(state, Channel.CLI, (1,), {1: provisional})
        self.assertEqual(target.status, ReviewStatus.READY)
        self.assertFalse(target.provisional)
        self.assertFalse(taper_satisfied(Channel.CLI, provisional, 3))

    def test_newer_legacy_provisional_observation_does_not_reopen_taper(self):
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
        self.assertEqual(completion_status(state, Channel.CLI, history), ReviewStatus.COMPLETE)
        target = select_review_target(state, Channel.CLI, (1,), {1: history})
        self.assertEqual(target.status, ReviewStatus.COMPLETE)
        self.assertFalse(target.provisional)
        self.assertTrue(taper_satisfied(Channel.CLI, history, 3))

    def test_provisional_history_does_not_reopen_completed_equivalent_history_taper(self):
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
            ReviewStatus.COMPLETE,
        )
        target = select_review_target(
            state,
            Channel.CLI,
            (1,),
            {1: history},
            reconciliation_by_pr={1: ReconciliationStatus.EQUIVALENT_HISTORY},
        )
        self.assertEqual(target.status, ReviewStatus.COMPLETE)
        self.assertIsNone(target.target)

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

    def test_equivalent_history_taper_counts_results_across_patches(self):
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

    def test_completed_taper_skips_cross_pr_judgment_identity_gate(self):
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
        )
        self.assertEqual(target.status, ReviewStatus.COMPLETE)
        self.assertIsNone(target.target)

        correctly_bound = dataclasses.replace(
            state,
            judgments=(Judgment(2, "cli", "retain", "h", "c2", "belongs to PR two", patch_id),),
        )
        target = select_review_target(
            correctly_bound,
            Channel.CLI,
            (2,),
            {2: history},
        )
        self.assertEqual(target.status, ReviewStatus.COMPLETE)

    def test_incomplete_channel_remains_selected_before_later_completed_taper(self):
        incomplete = tuple(
            Evidence(1, "h1", f"c{i}", anchored=True, completed=True, attributable=True, corrected_state=True)
            for i in range(2)
        )
        complete = tuple(
            Evidence(2, "h2", f"d{i}", anchored=True, completed=True, attributable=True, corrected_state=True)
            for i in range(3)
        )
        target = select_review_target(
            ReviewState(ordered_prs=(1, 2)),
            Channel.CLI,
            (1, 2),
            {1: incomplete, 2: complete},
        )

        self.assertEqual(target.target, 1)
        self.assertEqual(target.status, ReviewStatus.READY)
        self.assertFalse(target.to_dict()["taper_complete"])


if __name__ == "__main__":
    unittest.main()
