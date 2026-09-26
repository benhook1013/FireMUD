from __future__ import annotations

import contextlib
import datetime as dt
import importlib.util
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT_PATH = (
    Path(__file__).resolve().parents[1]
    / "deploy"
    / "bootstrap-account-response-envelope-ring.py"
)
SPEC = importlib.util.spec_from_file_location("account_response_envelope_bootstrap", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
BOOTSTRAP = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = BOOTSTRAP
SPEC.loader.exec_module(BOOTSTRAP)

MATERIALIZER_PATH = (
    Path(__file__).resolve().parents[1]
    / "deploy"
    / "materialize-account-response-envelope-ring.py"
)
MATERIALIZER_SPEC = importlib.util.spec_from_file_location("account_response_envelope_materializer", MATERIALIZER_PATH)
assert MATERIALIZER_SPEC is not None and MATERIALIZER_SPEC.loader is not None
MATERIALIZER = importlib.util.module_from_spec(MATERIALIZER_SPEC)
sys.modules[MATERIALIZER_SPEC.name] = MATERIALIZER
MATERIALIZER_SPEC.loader.exec_module(MATERIALIZER)


NOW = dt.datetime(2026, 9, 27, 12, 0, 0, 123400, tzinfo=dt.timezone.utc)
ENVIRONMENT_ID = "player-facing-prod"
TARGET_NAMESPACE = "account-prod"


class AccountResponseEnvelopeBootstrapTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.custody_directory = Path(self.temporary_directory.name)
        os.chmod(self.custody_directory, 0o700)
        self.output = self.custody_directory / "source-record.json"

    def create(self, **kwargs: object) -> None:
        values = {
            "output": self.output,
            "environment_id": ENVIRONMENT_ID,
            "namespace": TARGET_NAMESPACE,
            "source_ttl_seconds": 3600,
            "now": NOW,
        }
        values.update(kwargs)
        BOOTSTRAP.create_initial_source_record(**values)

    def test_output_is_materializer_compatible_with_valid_timestamps_and_null_predecessor(self) -> None:
        self.create()

        source = MATERIALIZER.read_source_record(self.output)
        self.assertEqual(ENVIRONMENT_ID, source.environment_id)
        self.assertEqual(TARGET_NAMESPACE, source.target_namespace)
        self.assertIsNone(source.previous_source_generation)
        self.assertEqual("2026-09-27T12:00:00.1234Z", source.source_created_at_text)
        self.assertEqual("2026-09-27T13:00:00.1234Z", source.source_expires_at_text)
        self.assertGreater(source.source_expires_at, source.source_created_at)
        self.assertEqual(source.source_created_at, NOW)

        manifest = MATERIALIZER.parse_manifest(source.manifest_bytes)
        self.assertEqual(manifest.active_key_id, next(iter(manifest.keys)))
        self.assertEqual({"bare-login", "connect-token"}, set(manifest.keys[manifest.active_key_id]))

    def test_purpose_keys_are_distinct_random_32_byte_values(self) -> None:
        self.create()

        source = MATERIALIZER.read_source_record(self.output)
        manifest = MATERIALIZER.parse_manifest(source.manifest_bytes)
        keys = manifest.keys[manifest.active_key_id]
        self.assertEqual(32, len(keys["bare-login"]))
        self.assertEqual(32, len(keys["connect-token"]))
        self.assertNotEqual(keys["bare-login"], keys["connect-token"])
        self.assertNotEqual("key-id", manifest.active_key_id)
        self.assertNotEqual("source-generation", source.source_generation)

    def test_output_is_created_once_and_retry_preserves_bytes_without_regeneration(self) -> None:
        self.create()
        first_bytes = self.output.read_bytes()

        with patch.object(BOOTSTRAP.secrets, "token_bytes", side_effect=AssertionError("regenerated")), patch.object(
            BOOTSTRAP.secrets, "token_urlsafe", side_effect=AssertionError("regenerated")
        ), self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "already exists"):
            self.create()

        self.assertEqual(first_bytes, self.output.read_bytes())

    def test_existing_output_symlink_cannot_replace_its_target(self) -> None:
        retained = self.custody_directory / "retained-source"
        retained.write_bytes(b"retain")
        self.output.symlink_to(retained)

        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "already exists"):
            self.create()

        self.assertEqual(b"retain", retained.read_bytes())

    def test_file_and_parent_permissions_are_owner_only(self) -> None:
        self.create()

        self.assertEqual(0o700, stat.S_IMODE(self.custody_directory.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(self.output.stat().st_mode))

    def test_missing_parent_is_rejected_without_creating_output(self) -> None:
        missing_parent = self.custody_directory / "missing"
        output = missing_parent / "source-record.json"

        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "must already exist"):
            self.create(output=output)

        self.assertFalse(output.exists())
        self.assertFalse(missing_parent.exists())

    def test_parent_with_group_access_is_rejected(self) -> None:
        os.chmod(self.custody_directory, 0o750)
        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "owner-only mode 0700"):
            self.create()
        self.assertFalse(self.output.exists())

    def test_repository_output_is_rejected(self) -> None:
        repository_root = Path(__file__).resolve().parents[2]
        output = repository_root / "bootstrap-source-record-test.json"

        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "outside the repository"):
            self.create(output=output)

        self.assertFalse(output.exists())

    def test_cli_requires_explicit_output_and_does_not_print_material(self) -> None:
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = BOOTSTRAP.main(
                [
                    "--output",
                    str(self.output),
                    "--environment-id",
                    ENVIRONMENT_ID,
                    "--namespace",
                    TARGET_NAMESPACE,
                    "--source-ttl-seconds",
                    "3600",
                ]
            )

        self.assertEqual(0, result)
        self.assertIn("created initial Account response-envelope source record", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())
        output_text = stdout.getvalue() + stderr.getvalue()
        source_text = self.output.read_text(encoding="utf-8")
        source = json.loads(source_text)
        manifest = MATERIALIZER.read_source_record(self.output).manifest_bytes.decode("ascii")
        self.assertNotIn(source_text, output_text)
        self.assertNotIn(manifest, output_text)
        self.assertNotIn(source["manifestBase64"], output_text)

    def test_cli_rejects_missing_output_argument(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            BOOTSTRAP.main(
                ["--environment-id", ENVIRONMENT_ID, "--namespace", TARGET_NAMESPACE, "--source-ttl-seconds", "3600"]
            )
        self.assertEqual(2, raised.exception.code)

    def test_cli_requires_explicit_credential_class_lifetime(self) -> None:
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as raised:
            BOOTSTRAP.main(["--output", str(self.output), "--environment-id", ENVIRONMENT_ID, "--namespace", TARGET_NAMESPACE])
        self.assertEqual(2, raised.exception.code)
        self.assertFalse(self.output.exists())

    def test_relative_output_is_rejected(self) -> None:
        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "absolute path"):
            self.create(output=Path("source-record.json"))


if __name__ == "__main__":
    unittest.main()
