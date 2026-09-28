import concurrent.futures
import dataclasses
import hashlib
import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review.sqlite_store import SQLITE_SCHEMA_VERSION, SqliteStateStore
from pr_review.state import (
    FindingRoute,
    Judgment,
    LegacyEvidenceTransition,
    PolicyOverride,
    ReviewAllocation,
    ReviewState,
    StackReconciliationDecision,
    StateError,
    StateStore,
    SummaryFindingDisposition,
)


class SqliteStateStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)

    @staticmethod
    def representative_state() -> ReviewState:
        head = "a" * 40
        parent_head = "b" * 40
        patch_id = "patch-identity-1"
        route = FindingRoute(
            source_pr=2828,
            source_channel="cli",
            source_review="run-17",
            source_finding="finding-3",
            observations=("owned by the successor", "confirmed after retarget"),
            target_pr=2879,
            target_history=(2870,),
        )
        fingerprint = hashlib.sha256(b"legacy checkpoint").hexdigest()
        return ReviewState(
            ordered_prs=(2828, 2879),
            policy_overrides={
                "2828:cli": PolicyOverride(
                    cli_zero_useful=2,
                    head=head,
                    checkpoint="cli-checkpoint-9",
                    reason="explicit scoped taper",
                    patch_id=patch_id,
                )
            },
            judgments=(
                Judgment(
                    pr=2828,
                    channel="hosted",
                    decision="retain",
                    head=head,
                    checkpoint="hosted-checkpoint-8",
                    reason="same patch remains valid",
                    patch_id=patch_id,
                ),
            ),
            reconciliations=(
                StackReconciliationDecision(
                    pr=2828,
                    channel="cli",
                    checkpoint="cli-checkpoint-7",
                    prior_head="c" * 40,
                    child_head=head,
                    parent_identity="develop",
                    parent_head=parent_head,
                    merge_base="d" * 40,
                    patch_id=patch_id,
                    reason="recorded equivalent parent transition",
                ),
            ),
            legacy_transitions=(
                LegacyEvidenceTransition(
                    pr=2828,
                    child_head=head,
                    parent_identity="develop",
                    parent_head=parent_head,
                    merge_base="d" * 40,
                    patch_id=patch_id,
                    hosted_fingerprints=(fingerprint,),
                    reason="preserve audited legacy evidence as non-counting",
                ),
            ),
            summary_dispositions=(
                SummaryFindingDisposition(
                    pr=2828,
                    head=head,
                    source="comment",
                    summary_id=99201,
                    kind="outside_diff",
                    count=1,
                    decision="accepted_unfixed",
                    reason="valid finding remains an obligation",
                ),
            ),
            routes=(route,),
            allocations={
                "2828:cli": ReviewAllocation(
                    pr=2828,
                    channel="cli",
                    head=head,
                    parent_identity="develop",
                    parent_head=parent_head,
                    merge_base="d" * 40,
                    patch_id=patch_id,
                    baseline_checkpoints=("cli-checkpoint-9",),
                    reason="one additional independent checkpoint",
                    baseline_checkpoint="cli-checkpoint-9",
                    min_additional_completed=1,
                    max_additional_completed=2,
                    reopens_taper=True,
                )
            },
        )

    def test_legacy_import_round_trips_all_validated_state_semantics(self) -> None:
        original = self.representative_state()
        source = self.root / "legacy.json"
        source.write_text(json.dumps(original.to_dict(), indent=2), encoding="utf-8")
        source_bytes = source.read_bytes()
        target = self.root / "review-state.sqlite3"

        imported_store = SqliteStateStore.import_legacy_json(source, target)

        self.assertEqual(imported_store.load().to_dict(), original.to_dict())
        self.assertEqual(source.read_bytes(), source_bytes)
        self.assertTrue(target.exists())
        self.assertTrue(imported_store.status()["compatible"])
        self.assertEqual(imported_store.status()["schema_version"], SQLITE_SCHEMA_VERSION)

    def test_migration_round_trips_state_and_fences_legacy_json_readers_and_writers(self) -> None:
        original = self.representative_state()
        source = self.root / "legacy.json"
        source.write_text(json.dumps(original.to_dict(), indent=2), encoding="utf-8")
        source_bytes = source.read_bytes()
        target = source.with_suffix(".sqlite3")
        old_store = StateStore(source)

        migrated_store = SqliteStateStore.migrate_legacy_json(source, target)

        self.assertEqual(migrated_store.load().to_dict(), original.to_dict())
        self.assertTrue(migrated_store.status()["compatible"])
        self.assertTrue(source.is_dir())
        self.assertTrue((source / "sqlite-cutover.json").is_file())
        retained_source = source.with_name(f"{source.name}.migrated")
        self.assertEqual(retained_source.read_bytes(), source_bytes)

        attempts = (
            ("load", lambda: old_store.load()),
            ("update", lambda: old_store.update(lambda _: ReviewState(ordered_prs=(9999,)))),
            ("save", lambda: old_store.save(ReviewState(ordered_prs=(9999,)))),
        )
        for operation, attempt in attempts:
            with self.subTest(operation=operation), self.assertRaises(StateError):
                attempt()

        # This invokes the old atomic-replace primitive directly, bypassing the
        # new StateStore guard. A regular file cannot replace the cutover directory.
        with self.assertRaises(OSError):
            old_store._save_unlocked(ReviewState(ordered_prs=(9999,)))
        self.assertTrue(source.is_dir())
        self.assertEqual(retained_source.read_bytes(), source_bytes)
        self.assertEqual(migrated_store.load().to_dict(), original.to_dict())
        with self.assertRaisesRegex(StateError, "regular JSON file"):
            SqliteStateStore.migrate_legacy_json(source, target)

    def test_migration_rejects_incompatible_sqlite_preflight_without_cutover(self) -> None:
        original = self.representative_state()
        source = self.root / "legacy.json"
        source.write_text(json.dumps(original.to_dict(), indent=2), encoding="utf-8")
        source_bytes = source.read_bytes()
        target = source.with_suffix(".sqlite3")

        with (
            patch.object(
                SqliteStateStore,
                "status",
                return_value={
                    "compatible": False,
                    "schema_version": SQLITE_SCHEMA_VERSION,
                    "data_model_version": original.schema_version,
                    "min_writer_build": 2,
                    "running_writer_build": 1,
                    "reason": "database requires writer build 2",
                },
            ),
            self.assertRaisesRegex(StateError, "compatibility preflight failed"),
        ):
            SqliteStateStore.migrate_legacy_json(source, target)

        self.assertTrue(source.is_file())
        self.assertEqual(source.read_bytes(), source_bytes)
        self.assertFalse(target.exists())
        self.assertFalse(source.with_name(f"{source.name}.migrated").exists())

    def test_migration_rejects_noncanonical_target_without_creating_artifacts(self) -> None:
        original = self.representative_state()
        source = self.root / "legacy.json"
        source.write_text(json.dumps(original.to_dict(), indent=2), encoding="utf-8")
        source_bytes = source.read_bytes()
        target = self.root / "other" / "review-state.sqlite3"

        with self.assertRaisesRegex(StateError, "canonical sibling path"):
            SqliteStateStore.migrate_legacy_json(source, target)

        self.assertEqual(source.read_bytes(), source_bytes)
        self.assertEqual(StateStore(source).load(), original)
        self.assertFalse(target.parent.exists())
        self.assertFalse(source.with_suffix(".sqlite3").exists())
        self.assertFalse(source.with_name(f"{source.name}.migrated").exists())
        self.assertFalse(source.with_name(".pr-review-stack.lock").exists())

    def test_migration_fails_before_cutover_when_atomic_exchange_is_unsupported(self) -> None:
        original = self.representative_state()
        source = self.root / "legacy.json"
        source.write_text(json.dumps(original.to_dict(), indent=2), encoding="utf-8")
        source_bytes = source.read_bytes()
        target = source.with_suffix(".sqlite3")

        with (
            patch(
                "pr_review.sqlite_store._atomic_exchange",
                side_effect=StateError("filesystem does not support atomic SQLite cutover"),
            ),
            self.assertRaisesRegex(StateError, "filesystem does not support atomic SQLite cutover"),
        ):
            SqliteStateStore.migrate_legacy_json(source, target)

        self.assertTrue(source.is_file())
        self.assertEqual(source.read_bytes(), source_bytes)
        self.assertEqual(StateStore(source).load().to_dict(), original.to_dict())
        self.assertFalse(target.exists())
        self.assertFalse(source.with_name(f"{source.name}.migrated").exists())

    def test_import_refuses_existing_target_without_changing_it(self) -> None:
        source = self.root / "legacy.json"
        source.write_text(json.dumps(ReviewState().to_dict()), encoding="utf-8")
        target = self.root / "existing.sqlite3"
        target.write_bytes(b"keep this target exactly")
        before = target.read_bytes()

        with self.assertRaisesRegex(StateError, "already exists"):
            SqliteStateStore.import_legacy_json(source, target)

        self.assertEqual(target.read_bytes(), before)

    def test_update_rolls_back_when_mutation_fails(self) -> None:
        store = SqliteStateStore(self.root / "state.sqlite3")
        initial = store.update(lambda _: dataclasses.replace(ReviewState(), ordered_prs=(2828,)))

        def fail_after_read(_: ReviewState) -> ReviewState:
            raise RuntimeError("simulated mutation failure")

        with self.assertRaisesRegex(RuntimeError, "simulated mutation failure"):
            store.update(fail_after_read)

        self.assertEqual(store.load(), initial)

    def test_failed_first_update_removes_unusable_database_path(self) -> None:
        def fail_mutator(_: ReviewState) -> ReviewState:
            raise RuntimeError("failed before commit")

        failures = (
            ("mutator", RuntimeError, fail_mutator),
            ("validation", TypeError, lambda _: "not a ReviewState"),
        )
        for name, exception, mutate in failures:
            with self.subTest(failure=name):
                database = self.root / f"first-{name}.sqlite3"
                store = SqliteStateStore(database)

                with self.assertRaises(exception):
                    store.update(mutate)

                self.assertFalse(database.exists())
                self.assertEqual(store.status()["format"], "missing")

                recovered = store.update(lambda _: dataclasses.replace(ReviewState(), ordered_prs=(2828,)))
                self.assertEqual(recovered.ordered_prs, (2828,))

    def test_too_old_writer_is_refused_without_database_change(self) -> None:
        database = self.root / "state.sqlite3"
        newer_store = SqliteStateStore(database, writer_build=2)
        newer_store.update(lambda _: dataclasses.replace(ReviewState(), ordered_prs=(2828,)))
        before = database.read_bytes()
        old_store = SqliteStateStore(database, writer_build=1)

        with self.assertRaisesRegex(StateError, "requires writer build 2"):
            old_store.update(lambda current: dataclasses.replace(current, ordered_prs=(2879,)))

        self.assertEqual(database.read_bytes(), before)
        self.assertEqual(newer_store.load().ordered_prs, (2828,))
        self.assertFalse(old_store.status()["compatible"])

    def test_future_schema_is_refused_without_update_and_status_is_read_only(self) -> None:
        database = self.root / "future.sqlite3"
        store = SqliteStateStore(database)
        store.update(lambda _: dataclasses.replace(ReviewState(), ordered_prs=(2828,)))
        with sqlite3.connect(database) as connection:
            connection.execute("PRAGMA user_version = 999")
        before = database.read_bytes()

        with self.assertRaisesRegex(StateError, "unsupported SQLite review-state schema version: 999"):
            store.update(lambda current: dataclasses.replace(current, ordered_prs=(2879,)))

        status = store.status()
        self.assertEqual(status["schema_version"], 999)
        self.assertFalse(status["compatible"])
        self.assertTrue(status["read_only"])
        self.assertEqual(database.read_bytes(), before)

    def test_status_does_not_create_a_missing_database(self) -> None:
        database = self.root / "not-created.sqlite3"
        status = SqliteStateStore(database).status()

        self.assertEqual(status["format"], "missing")
        self.assertTrue(status["read_only"])
        self.assertFalse(status["compatible"])
        self.assertFalse(database.exists())

    def test_concurrent_updates_are_serialized_without_lost_rows(self) -> None:
        store = SqliteStateStore(self.root / "concurrent.sqlite3", timeout=20)
        store.update(lambda _: ReviewState())

        def append(pr: int) -> ReviewState:
            return store.update(lambda current: dataclasses.replace(current, ordered_prs=current.ordered_prs + (pr,)))

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            list(executor.map(append, range(1000, 1008)))

        self.assertEqual(sorted(store.load().ordered_prs), list(range(1000, 1008)))


if __name__ == "__main__":
    unittest.main()
