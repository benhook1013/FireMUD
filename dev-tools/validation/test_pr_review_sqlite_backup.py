from __future__ import annotations

import hashlib
import json
import shlex
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))

from pr_review import sqlite_backup
from pr_review.sqlite_backup import (
    BackupError,
    backup_database,
    create_snapshot,
    restore_remote_backup,
    restore_snapshot,
)
from pr_review.sqlite_review_records import FindingObservation, SqliteReviewRecords
from pr_review.sqlite_store import SqliteStateStore


class SqliteBackupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.root = Path(self.temporary_directory.name)
        self.database = self.root / "review-state.sqlite3"
        self.identity = self.root / "backup-key"
        self.identity.write_bytes(b"synthetic private key placeholder")
        self.identity.chmod(0o600)
        self.known_hosts = self.root / "known_hosts"
        self.known_hosts.write_text("backup.example ssh-ed25519 synthetic-host-key\n", encoding="utf-8")
        self.known_hosts.chmod(0o600)
        self.remote_directory = "/srv/firemud/pr-review-backups"
        self.remote_directories = {
            "/": (0, 0o755),
            "/srv": (0, 0o755),
            "/srv/firemud": (0, 0o755),
            self.remote_directory: (1001, 0o700),
        }
        self.remote_files: dict[str, bytes] = {}
        self.remote_modes: dict[str, int] = {}
        self.remote_owner = 1001
        self.sftp_batches: list[str] = []
        self._create_database()

    def _create_database(self) -> None:
        SqliteStateStore(self.database).update(lambda state: state)
        records = SqliteReviewRecords(self.database)
        records.bootstrap()
        records.record_run(
            run_id="backup-fixture-run",
            source_pr=123,
            channel="manual",
            reviewer="fixture reviewer",
            findings=[
                FindingObservation(
                    source_finding_key="backup-fixture-finding",
                    title="Synthetic backup finding",
                    detail="Synthetic bounded review detail.",
                )
            ],
        )

    def _assert_fixture_records(self, path: Path) -> None:
        history = SqliteReviewRecords(path).history(123)
        self.assertEqual([run["run_id"] for run in history["runs"]], ["backup-fixture-run"])
        self.assertEqual([finding["title"] for finding in history["findings"]], ["Synthetic backup finding"])

    def _fake_sftp(self, remote: object, binary: str, batch: str) -> str:
        self.assertEqual(binary, "sftp")
        self.sftp_batches.append(batch)
        output: list[str] = []
        for line in batch.splitlines():
            parts = shlex.split(line)
            command = parts[0].lstrip("-")
            if command in {"cd", "pwd"}:
                if command == "cd":
                    self.assertEqual(parts[1], self.remote_directory)
                else:
                    output.append(f"Remote working directory: {self.remote_directory}")
            elif command == "put":
                local_path, remote_path = parts[1:]
                self.remote_files[remote_path] = Path(local_path).read_bytes()
                self.remote_modes[remote_path] = 0o644
            elif command == "chmod":
                mode, remote_path = parts[1:]
                self.assertEqual(mode, "600")
                self.remote_modes[remote_path] = 0o600
            elif command == "rename":
                source, target = parts[1:]
                self.assertIn(source, self.remote_files)
                self.assertNotIn(target, self.remote_files)
                self.remote_files[target] = self.remote_files.pop(source)
                self.remote_modes[target] = self.remote_modes.pop(source)
            elif command == "ls":
                path = parts[-1]
                if path == self.remote_directory:
                    for candidate, content in sorted(self.remote_files.items()):
                        output.append(self._listing_line(candidate, content))
                elif path in self.remote_directories:
                    for child, (uid, mode) in self.remote_directories.items():
                        if Path(child).parent.as_posix() == path and child != "/":
                            output.append(self._directory_listing_line(child, uid, mode))
                elif path in self.remote_files:
                    output.append(self._listing_line(path, self.remote_files[path]))
                else:
                    raise BackupError("missing remote file")
            elif command == "get":
                remote_path, local_path = parts[1:]
                Path(local_path).write_bytes(self.remote_files[remote_path])
            elif command == "rm":
                remote_path = parts[1]
                self.remote_files.pop(remote_path, None)
                self.remote_modes.pop(remote_path, None)
            else:
                self.fail(f"unexpected SFTP command: {line}")
        return "\n".join(output)

    def _listing_line(self, path: str, content: bytes) -> str:
        return f"-rw-------    ? {self.remote_owner}        {self.remote_owner} {len(content)} Sep 28 12:00 {path}"

    def _directory_listing_line(self, path: str, uid: int, mode: int) -> str:
        permission_chars = "rwxrwxrwx"
        permissions = "".join(char if mode & (1 << (8 - index)) else "-" for index, char in enumerate(permission_chars))
        return f"d{permissions}    ? {uid}        1001 4096 Sep 28 12:00 {path}"

    def _transport_patches(self):
        return (
            patch("pr_review.sqlite_backup._run_sftp", side_effect=self._fake_sftp),
        )

    def _backup_arguments(self) -> dict[str, str | Path | int]:
        return {
            "host": "backup@backup.example",
            "identity_file": self.identity,
            "known_hosts_file": self.known_hosts,
            "remote_directory": self.remote_directory,
            "remote_uid": 1001,
        }

    def test_snapshot_and_restore_are_consistent_private_and_non_overwriting(self) -> None:
        writer = sqlite3.connect(self.database)
        self.addCleanup(writer.close)
        writer.execute("PRAGMA journal_mode = WAL")
        writer.execute("BEGIN IMMEDIATE")
        writer.execute("UPDATE review_runs SET reviewer = ?", ("uncommitted reviewer",))

        snapshot_path = self.root / "snapshot.sqlite3"
        snapshot = create_snapshot(self.database, snapshot_path)
        writer.commit()
        self.assertEqual(snapshot.sha256, hashlib.sha256(snapshot_path.read_bytes()).hexdigest())
        self.assertEqual(snapshot.size_bytes, snapshot_path.stat().st_size)
        self.assertEqual(snapshot_path.stat().st_mode & 0o777, 0o600)

        restored_path = self.root / "restored.sqlite3"
        restore_snapshot(snapshot_path, restored_path)
        self.assertEqual(restored_path.stat().st_mode & 0o777, 0o600)
        self._assert_fixture_records(restored_path)

        with self.assertRaisesRegex(BackupError, "already exists"):
            restore_snapshot(snapshot_path, restored_path)

    def test_backup_reads_back_versioned_remote_copy_and_restores_it(self) -> None:
        (sftp_patch,) = self._transport_patches()
        with sftp_patch:
            receipt = backup_database(self.database, **self._backup_arguments())
            restored_path = self.root / "remote-restored.sqlite3"
            restored = restore_remote_backup(receipt.filename, restored_path, **self._backup_arguments())

        self.assertIn(receipt.remote_path, self.remote_files)
        self.assertEqual(receipt.sha256, hashlib.sha256(self.remote_files[receipt.remote_path]).hexdigest())
        self.assertEqual(restored.sha256, receipt.sha256)
        self.assertEqual(restored_path.stat().st_mode & 0o777, 0o600)
        self._assert_fixture_records(restored_path)
        self.assertTrue(self.sftp_batches)
        self.assertTrue(any(line.startswith("rename ") for batch in self.sftp_batches for line in batch.splitlines()))
        self.assertTrue(any(line.startswith("chmod 600 ") for batch in self.sftp_batches for line in batch.splitlines()))
        self.assertTrue(any(line.startswith("ls -ln ") for batch in self.sftp_batches for line in batch.splitlines()))
        commands = {line.split(maxsplit=1)[0].lstrip("-") for batch in self.sftp_batches for line in batch.splitlines()}
        self.assertLessEqual(commands, {"cd", "pwd", "put", "chmod", "ls", "get", "rename", "rm"})

    def test_final_remote_verification_failure_removes_new_final_artifact(self) -> None:
        original_verify = sqlite_backup._verify_remote_file
        verifications = 0

        def fail_final_verification(remote: object, binary: str, path: str) -> None:
            nonlocal verifications
            verifications += 1
            if verifications == 2:
                raise BackupError("synthetic final verification failure")
            original_verify(remote, binary, path)

        (sftp_patch,) = self._transport_patches()
        with (
            sftp_patch,
            patch("pr_review.sqlite_backup._verify_remote_file", side_effect=fail_final_verification),
            self.assertRaisesRegex(BackupError, "synthetic final verification failure"),
        ):
            backup_database(self.database, **self._backup_arguments())

        self.assertEqual(verifications, 2)
        self.assertEqual(self.remote_files, {})

    def test_arbitrary_sqlite_schema_is_rejected_before_sftp(self) -> None:
        arbitrary = self.root / "arbitrary.sqlite3"
        with sqlite3.connect(arbitrary) as connection:
            connection.execute("CREATE TABLE unrelated (secret TEXT)")
            connection.execute("INSERT INTO unrelated VALUES ('ordinary text')")

        (sftp_patch,) = self._transport_patches()
        with sftp_patch, self.assertRaisesRegex(BackupError, "allowlisted FireMUD"):
            backup_database(arbitrary, **self._backup_arguments())
        self.assertEqual(self.sftp_batches, [])

    def test_secret_like_persisted_text_is_rejected_before_sftp(self) -> None:
        with sqlite3.connect(self.database) as connection:
            connection.execute(
                "UPDATE finding_observations SET detail = ?",
                ("provider returned Bearer synthetic-secret-value",),
            )

        (sftp_patch,) = self._transport_patches()
        with sftp_patch, self.assertRaisesRegex(BackupError, "credential- or raw-secret"):
            backup_database(self.database, **self._backup_arguments())
        self.assertEqual(self.sftp_batches, [])

    def test_snapshot_is_revalidated_before_sftp_after_concurrent_secret_write(self) -> None:
        original_create_snapshot = sqlite_backup.create_snapshot

        def create_snapshot_after_secret_write(database_path: Path, snapshot_path: Path):
            with sqlite3.connect(database_path) as connection:
                connection.execute(
                    "UPDATE finding_observations SET detail = ?",
                    ("Bearer concurrent-synthetic-secret",),
                )
            return original_create_snapshot(database_path, snapshot_path)

        (sftp_patch,) = self._transport_patches()
        with (
            sftp_patch,
            patch("pr_review.sqlite_backup.create_snapshot", side_effect=create_snapshot_after_secret_write),
            self.assertRaisesRegex(BackupError, "credential- or raw-secret"),
        ):
            backup_database(self.database, **self._backup_arguments())
        self.assertEqual(self.sftp_batches, [])

    def test_secret_screen_allows_embedded_git_hashes_and_long_code_identifiers(self) -> None:
        sha1 = "abcdef0123456789" * 2 + "abcdef01"
        sha256 = "0123456789abcdef" * 4
        code_identifier = "controller_review_route_reconciliation_source_proof_identifier_db"

        self.assertEqual(len(sha1), 40)
        self.assertEqual(len(sha256), 64)
        self.assertEqual(len(code_identifier), 65)
        self.assertFalse(sqlite_backup._looks_secret(f"reconciliation head {sha1} was checked"))
        self.assertFalse(sqlite_backup._looks_secret(f"review target {sha256} matched"))
        self.assertFalse(sqlite_backup._looks_secret(f"route proof names {code_identifier}"))
        self.assertFalse(sqlite_backup._looks_secret("GameSessionOperatorControlPlaneServiceTest fallback fixture"))

    def test_secret_screen_matches_write_valid_segmented_identifiers(self) -> None:
        long_identifier = "review_v2_route-reconciliation_source-proof_identifier_with-many-segments"
        very_long_identifier = "_".join(["review"] + [f"segment{index}" for index in range(1, 18)])

        self.assertFalse(sqlite_backup._looks_secret(f"identifier {long_identifier}"))
        self.assertFalse(sqlite_backup._looks_secret(f"identifier {very_long_identifier}"))

    def test_secret_screen_still_rejects_unknown_tokens_and_credentials(self) -> None:
        high_entropy_token = "Q2hhbmdlTWVOb3RGb3JUaGlzVmFsdWVfS2VlcFNlY3JldA"

        self.assertTrue(sqlite_backup._looks_secret(f"opaque value {high_entropy_token}"))
        self.assertTrue(sqlite_backup._looks_secret("opaque value QrTzPabLmNuvWxyZabcDefGhiJklMnoPqrStuVwxYzTest"))
        self.assertTrue(sqlite_backup._looks_secret("Bearer synthetic-token-value"))
        self.assertTrue(sqlite_backup._looks_secret("-----BEGIN OPENSSH PRIVATE KEY-----"))
        self.assertTrue(sqlite_backup._looks_secret("access_token_rotation_material_for_operator_storage"))

    def test_restore_rejects_integral_database_with_invalid_controller_state(self) -> None:
        malformed = self.root / "malformed-state.sqlite3"
        with sqlite3.connect(self.database) as connection, sqlite3.connect(malformed) as copied:
            connection.backup(copied)
        with sqlite3.connect(malformed) as connection:
            connection.execute("UPDATE review_state SET state_json = ?", ("{}",))
        destination = self.root / "invalid-logical-restore.sqlite3"

        with self.assertRaisesRegex(BackupError, "logical readback"):
            restore_snapshot(malformed, destination)
        self.assertFalse(destination.exists())

    def test_remote_digest_mismatch_and_weak_identity_fail_closed(self) -> None:
        (sftp_patch,) = self._transport_patches()
        with sftp_patch:
            receipt = backup_database(self.database, **self._backup_arguments())
            self.remote_files[receipt.remote_path] += b"tamper"
            destination = self.root / "tampered-restore.sqlite3"
            with self.assertRaisesRegex(BackupError, "digest does not match"):
                restore_remote_backup(receipt.filename, destination, **self._backup_arguments())
            self.assertFalse(destination.exists())

        self.identity.chmod(0o644)
        calls_before_rejection = len(self.sftp_batches)
        (sftp_patch,) = self._transport_patches()
        with sftp_patch, self.assertRaisesRegex(BackupError, "permissions"):
            backup_database(self.database, **self._backup_arguments())
        self.assertEqual(len(self.sftp_batches), calls_before_rejection)

    def test_unsafe_remote_directory_and_unversioned_names_are_rejected(self) -> None:
        arguments = self._backup_arguments()
        arguments["remote_directory"] = "/srv/firemud/../public"
        with self.assertRaisesRegex(BackupError, "absolute path"):
            backup_database(self.database, **arguments)

        arguments["remote_directory"] = self.remote_directory
        arguments["host"] = "root@backup.example"
        with self.assertRaisesRegex(BackupError, "non-root"):
            backup_database(self.database, **arguments)
        arguments["host"] = "backup.example"
        with self.assertRaisesRegex(BackupError, "non-root"):
            backup_database(self.database, **arguments)

        with self.assertRaisesRegex(BackupError, "versioned FireMUD"):
            restore_remote_backup("latest.sqlite3", self.root / "restore.sqlite3", **self._backup_arguments())

    def test_remote_uid_must_be_pinned_and_positive(self) -> None:
        arguments = self._backup_arguments()
        arguments["remote_uid"] = 0
        with self.assertRaisesRegex(BackupError, "positive integer"):
            backup_database(self.database, **arguments)

    def test_one_shot_cli_writes_private_minimal_success_and_failure_reports(self) -> None:
        (sftp_patch,) = self._transport_patches()
        report = self.root / "backup-status.json"
        arguments = [
            str(self.database), "--host", "backup@backup.example", "--identity-file", str(self.identity),
            "--known-hosts-file", str(self.known_hosts), "--remote-directory", self.remote_directory,
            "--remote-uid", "1001", "--report-file", str(report),
        ]
        with sftp_patch, patch("sys.stdout.write"):
            self.assertEqual(sqlite_backup.main(arguments), 0)
        self.assertEqual(report.stat().st_mode & 0o777, 0o600)
        report_text = report.read_text(encoding="utf-8")
        success = json.loads(report_text)
        self.assertEqual(success["lastAttempt"]["status"], "success")
        self.assertEqual(len(success["lastSuccess"]["sha256"]), 64)
        self.assertTrue(success["lastSuccess"]["filename"].endswith(".sqlite3"))
        self.assertNotIn("identity", report_text)
        self.assertNotIn("synthetic review state", report_text)
        self.assertNotIn("Synthetic backup finding", report_text)
        self.assertNotIn("Synthetic bounded review detail.", report_text)
        self.assertNotIn("fixture reviewer", report_text)

        arguments[0] = str(self.root / "missing.sqlite3")
        batches_before_failure = len(self.sftp_batches)
        with sftp_patch, patch("sys.stderr.write"):
            self.assertEqual(sqlite_backup.main(arguments), 1)
        self.assertEqual(len(self.sftp_batches), batches_before_failure)
        failure = json.loads(report.read_text(encoding="utf-8"))
        self.assertEqual(failure["lastAttempt"]["status"], "failure")
        self.assertEqual(failure["lastSuccess"], success["lastSuccess"])
        self.assertIsInstance(failure["lastFailureAt"], str)

    def test_restore_cli_restores_named_backup_without_requiring_report_file(self) -> None:
        (sftp_patch,) = self._transport_patches()
        with sftp_patch:
            receipt = backup_database(self.database, **self._backup_arguments())
            destination = self.root / "restored-from-cli.sqlite3"
            arguments = [
                str(destination), "--restore", receipt.filename, "--host", "backup@backup.example",
                "--identity-file", str(self.identity), "--known-hosts-file", str(self.known_hosts),
                "--remote-directory", self.remote_directory, "--remote-uid", "1001",
            ]
            with patch("builtins.print"):
                self.assertEqual(sqlite_backup.main(arguments), 0)

        self.assertEqual(destination.stat().st_mode & 0o777, 0o600)
        self._assert_fixture_records(destination)

    def test_restore_cli_refuses_existing_destination_before_sftp_access(self) -> None:
        (sftp_patch,) = self._transport_patches()
        with sftp_patch:
            receipt = backup_database(self.database, **self._backup_arguments())
            destination = self.root / "already-there.sqlite3"
            destination.write_bytes(b"preserve this file")
            batches_before = len(self.sftp_batches)
            arguments = [
                str(destination), "--restore", receipt.filename, "--host", "backup@backup.example",
                "--identity-file", str(self.identity), "--known-hosts-file", str(self.known_hosts),
                "--remote-directory", self.remote_directory, "--remote-uid", "1001",
            ]
            with patch("sys.stderr.write"):
                self.assertEqual(sqlite_backup.main(arguments), 1)
            self.assertEqual(len(self.sftp_batches), batches_before)
        self.assertEqual(destination.read_bytes(), b"preserve this file")

    def test_retention_keeps_new_backup_and_prunes_only_versioned_artifacts(self) -> None:
        (sftp_patch,) = self._transport_patches()
        with sftp_patch:
            older = backup_database(self.database, retention_count=3, **self._backup_arguments())
            newer = backup_database(self.database, retention_count=1, **self._backup_arguments())
        self.assertIn(newer.remote_path, self.remote_files)
        self.assertNotIn(older.remote_path, self.remote_files)
        self.assertEqual(len(self.remote_files), 1)

    def test_remote_artifact_with_wrong_owner_fails_closed_and_removes_partial(self) -> None:
        self.remote_owner = 1002
        (sftp_patch,) = self._transport_patches()
        with sftp_patch, self.assertRaisesRegex(BackupError, "not private and owned"):
            backup_database(self.database, **self._backup_arguments())
        self.assertFalse(self.remote_files)

    def test_remote_directory_mode_or_owner_mismatch_fails_before_upload(self) -> None:
        self.remote_directories[self.remote_directory] = (1002, 0o755)
        (sftp_patch,) = self._transport_patches()
        with sftp_patch, self.assertRaisesRegex(BackupError, "mode 0700"):
            backup_database(self.database, **self._backup_arguments())
        self.assertFalse(self.remote_files)

    def test_remote_directory_with_wrong_numeric_uid_fails_before_upload(self) -> None:
        self.remote_directories[self.remote_directory] = (1002, 0o700)
        (sftp_patch,) = self._transport_patches()
        with sftp_patch, self.assertRaisesRegex(BackupError, "pinned remote UID"):
            backup_database(self.database, **self._backup_arguments())
        self.assertFalse(self.remote_files)

    def test_transport_executes_sftp_with_pinned_host_key_and_no_remote_command(self) -> None:
        remote = sqlite_backup._remote_config(
            "backup@backup.example", self.identity, self.known_hosts, self.remote_directory, 1001
        )
        result = SimpleNamespace(returncode=0, stdout="", stderr="")
        with patch("pr_review.sqlite_backup.subprocess.run", return_value=result) as run:
            sqlite_backup._run_sftp(remote, "sftp", "pwd\n")
        arguments = run.call_args.args[0]
        self.assertEqual(arguments[0], "sftp")
        self.assertIn("-b", arguments)
        self.assertIn("-", arguments)
        self.assertIn("StrictHostKeyChecking=yes", arguments)
        self.assertIn(f"UserKnownHostsFile={self.known_hosts}", arguments)
        self.assertEqual(arguments[-1], "backup@backup.example")
        self.assertEqual(run.call_args.kwargs["input"], "pwd\n")


if __name__ == "__main__":
    unittest.main()
