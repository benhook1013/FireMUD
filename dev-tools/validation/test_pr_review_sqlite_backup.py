from __future__ import annotations

import hashlib
import shlex
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
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
        self.remote_files: dict[str, bytes] = {}
        self.remote_modes: dict[str, int] = {}
        self.ssh_calls: list[tuple[str, str]] = []
        self._create_database()

    def _create_database(self) -> None:
        with sqlite3.connect(self.database) as connection:
            connection.execute("CREATE TABLE decisions (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            connection.execute("INSERT INTO decisions (value) VALUES (?)", ("synthetic review state",))

    def _fake_ssh(self, remote: object, binary: str, command: str) -> None:
        self.ssh_calls.append((binary, command))
        lexer = shlex.shlex(command, posix=True, punctuation_chars=";")
        tokens = list(lexer)
        assignments = {
            token.split("=", 1)[0]: token.split("=", 1)[1]
            for token in tokens
            if token.startswith(("s=", "d=", "p=")) and "=" in token
        }
        if '[ -d "$d" ]' in command:
            self.assertEqual(assignments["d"], self.remote_directory)
            self.assertIn('"$(stat -c %a -- "$d")" = 700', command)
            self.assertIn('"$(stat -c %u -- "$d")" = "$(id -u)"', command)
            self.assertIn('[ ! -L "$d" ]', command)
            self.assertIn('[ "$(id -u)" -ne 0 ]', command)
            return
        if 'ln -- "$s" "$d"' in command:
            source = assignments["s"]
            target = assignments["d"]
            self.assertIn(source, self.remote_files)
            self.assertNotIn(target, self.remote_files)
            self.remote_files[target] = self.remote_files.pop(source)
            self.remote_modes[target] = self.remote_modes.pop(source, 0o600)
            return
        if 'chmod 600 -- "$p"' in command:
            path = assignments["p"]
            self.assertIn(path, self.remote_files)
            self.remote_modes[path] = 0o600
            return
        if '[ -f "$p" ]' in command:
            path = assignments["p"]
            self.assertIn(path, self.remote_files)
            self.assertEqual(self.remote_modes.get(path), 0o600)
            self.assertIn('[ ! -L "$p" ]', command)
            return
        self.fail(f"unexpected SSH command: {command}")

    def _fake_sftp(self, remote: object, binary: str, batch: str) -> None:
        self.assertEqual(binary, "sftp")
        for line in batch.splitlines():
            parts = shlex.split(line)
            command = parts[0].lstrip("-")
            if command == "put":
                local_path, remote_path = parts[1:]
                self.remote_files[remote_path] = Path(local_path).read_bytes()
                self.remote_modes[remote_path] = 0o600
            elif command == "get":
                remote_path, local_path = parts[1:]
                Path(local_path).write_bytes(self.remote_files[remote_path])
            elif command == "rm":
                remote_path = parts[1]
                self.remote_files.pop(remote_path, None)
                self.remote_modes.pop(remote_path, None)
            else:
                self.fail(f"unexpected SFTP command: {line}")

    def _transport_patches(self):
        return (
            patch("pr_review.sqlite_backup._run_ssh", side_effect=self._fake_ssh),
            patch("pr_review.sqlite_backup._run_sftp", side_effect=self._fake_sftp),
        )

    def _backup_arguments(self) -> dict[str, str | Path]:
        return {
            "host": "backup@backup.example",
            "identity_file": self.identity,
            "known_hosts_file": self.known_hosts,
            "remote_directory": self.remote_directory,
        }

    def test_snapshot_and_restore_are_consistent_private_and_non_overwriting(self) -> None:
        writer = sqlite3.connect(self.database)
        self.addCleanup(writer.close)
        writer.execute("PRAGMA journal_mode = WAL")
        writer.execute("BEGIN IMMEDIATE")
        writer.execute("INSERT INTO decisions (value) VALUES (?)", ("uncommitted writer state",))

        snapshot_path = self.root / "snapshot.sqlite3"
        snapshot = create_snapshot(self.database, snapshot_path)
        writer.commit()
        self.assertEqual(snapshot.sha256, hashlib.sha256(snapshot_path.read_bytes()).hexdigest())
        self.assertEqual(snapshot.size_bytes, snapshot_path.stat().st_size)
        self.assertEqual(snapshot_path.stat().st_mode & 0o777, 0o600)

        restored_path = self.root / "restored.sqlite3"
        restore_snapshot(snapshot_path, restored_path)
        self.assertEqual(restored_path.stat().st_mode & 0o777, 0o600)
        with sqlite3.connect(restored_path) as connection:
            self.assertEqual(
                connection.execute("SELECT value FROM decisions").fetchall(),
                [("synthetic review state",)],
            )

        with self.assertRaisesRegex(BackupError, "already exists"):
            restore_snapshot(snapshot_path, restored_path)

    def test_backup_reads_back_versioned_remote_copy_and_restores_it(self) -> None:
        ssh_patch, sftp_patch = self._transport_patches()
        with ssh_patch, sftp_patch:
            receipt = backup_database(self.database, **self._backup_arguments())
            restored_path = self.root / "remote-restored.sqlite3"
            restored = restore_remote_backup(receipt.filename, restored_path, **self._backup_arguments())

        self.assertIn(receipt.remote_path, self.remote_files)
        self.assertEqual(receipt.sha256, hashlib.sha256(self.remote_files[receipt.remote_path]).hexdigest())
        self.assertEqual(restored.sha256, receipt.sha256)
        self.assertEqual(restored_path.stat().st_mode & 0o777, 0o600)
        with sqlite3.connect(restored_path) as connection:
            self.assertEqual(
                connection.execute("SELECT value FROM decisions").fetchall(),
                [("synthetic review state",)],
            )
        remote = sqlite_backup._remote_config(
            "backup@backup.example", self.identity, self.known_hosts, self.remote_directory
        )
        ssh_options = sqlite_backup._ssh_base(remote)
        self.assertIn("/dev/null", ssh_options)
        self.assertIn("StrictHostKeyChecking=yes", ssh_options)
        self.assertIn("PasswordAuthentication=no", ssh_options)
        self.assertTrue(any('ln -- "$s" "$d"' in command for _, command in self.ssh_calls))

    def test_remote_digest_mismatch_and_weak_identity_fail_closed(self) -> None:
        ssh_patch, sftp_patch = self._transport_patches()
        with ssh_patch, sftp_patch:
            receipt = backup_database(self.database, **self._backup_arguments())
            self.remote_files[receipt.remote_path] += b"tamper"
            destination = self.root / "tampered-restore.sqlite3"
            with self.assertRaisesRegex(BackupError, "digest does not match"):
                restore_remote_backup(receipt.filename, destination, **self._backup_arguments())
            self.assertFalse(destination.exists())

        self.identity.chmod(0o644)
        calls_before_rejection = len(self.ssh_calls)
        ssh_patch, sftp_patch = self._transport_patches()
        with ssh_patch, sftp_patch, self.assertRaisesRegex(BackupError, "permissions"):
            backup_database(self.database, **self._backup_arguments())
        self.assertEqual(len(self.ssh_calls), calls_before_rejection)

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


if __name__ == "__main__":
    unittest.main()
