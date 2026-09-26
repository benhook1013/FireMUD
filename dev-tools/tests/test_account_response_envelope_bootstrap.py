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
from types import SimpleNamespace
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


class FakeKubectl:
    def __init__(self) -> None:
        self.secret: dict | None = None

    def __call__(self, command: list[str], **kwargs: object) -> SimpleNamespace:
        operation = command[1]
        if operation == "get":
            return SimpleNamespace(
                returncode=0,
                stdout="" if self.secret is None else json.dumps(self.secret),
                stderr="",
            )
        request = json.loads(kwargs["input"])
        if operation == "create":
            if self.secret is not None:
                return SimpleNamespace(returncode=1, stdout="", stderr="already exists")
            resource_version = "1"
        elif operation == "replace":
            if self.secret is None:
                return SimpleNamespace(returncode=1, stdout="", stderr="not found")
            if request["metadata"].get("resourceVersion") != self.secret["metadata"]["resourceVersion"]:
                return SimpleNamespace(returncode=1, stdout="", stderr="conflict")
            resource_version = str(int(self.secret["metadata"]["resourceVersion"]) + 1)
        else:
            raise AssertionError(f"unexpected kubectl operation: {operation}")
        request["metadata"]["resourceVersion"] = resource_version
        self.secret = request
        return SimpleNamespace(returncode=0, stdout=json.dumps(self.secret), stderr="")


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

    def test_rotation_is_parser_and_materializer_compatible_and_retains_old_keys(self) -> None:
        self.create()
        previous = MATERIALIZER.read_source_record(self.output)
        rotated_output = self.custody_directory / "rotated-source-record.json"
        rotation_time = NOW + dt.timedelta(minutes=10)

        BOOTSTRAP.create_rotated_source_record(
            output=rotated_output,
            previous_source_record=self.output,
            environment_id=ENVIRONMENT_ID,
            namespace=TARGET_NAMESPACE,
            source_ttl_seconds=3600,
            now=rotation_time,
        )

        rotated = MATERIALIZER.read_source_record(rotated_output)
        self.assertEqual(previous.source_generation, rotated.previous_source_generation)
        self.assertGreater(rotated.source_created_at, previous.source_created_at)
        self.assertEqual(ENVIRONMENT_ID, rotated.environment_id)
        self.assertEqual(TARGET_NAMESPACE, rotated.target_namespace)
        previous_manifest = MATERIALIZER.parse_manifest(previous.manifest_bytes)
        rotated_manifest = MATERIALIZER.parse_manifest(rotated.manifest_bytes)
        self.assertNotEqual(previous_manifest.active_key_id, rotated_manifest.active_key_id)
        self.assertTrue(previous_manifest.key_ids < rotated_manifest.key_ids)
        for key_id, purposes in previous_manifest.keys.items():
            self.assertEqual(purposes, rotated_manifest.keys[key_id])
        self.assertEqual({"bare-login", "connect-token"}, set(rotated_manifest.keys[rotated_manifest.active_key_id]))

        fake_kubectl = FakeKubectl()
        with patch.object(MATERIALIZER.subprocess, "run", side_effect=fake_kubectl):
            self.assertTrue(
                MATERIALIZER.materialize(
                    self.output,
                    ENVIRONMENT_ID,
                    TARGET_NAMESPACE,
                    7200,
                    now=NOW + dt.timedelta(minutes=1),
                )
            )
            self.assertTrue(
                MATERIALIZER.materialize(
                    rotated_output,
                    ENVIRONMENT_ID,
                    TARGET_NAMESPACE,
                    7200,
                    now=rotation_time + dt.timedelta(minutes=1),
                )
            )

    def test_rotation_rejects_wrong_environment_or_namespace_without_output(self) -> None:
        self.create()
        wrong_environment_output = self.custody_directory / "wrong-environment.json"
        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "environment"):
            BOOTSTRAP.create_rotated_source_record(
                output=wrong_environment_output,
                previous_source_record=self.output,
                environment_id="other-environment",
                namespace=TARGET_NAMESPACE,
                source_ttl_seconds=3600,
                now=NOW + dt.timedelta(minutes=1),
            )
        self.assertFalse(wrong_environment_output.exists())

        wrong_namespace_output = self.custody_directory / "wrong-namespace.json"
        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "namespace"):
            BOOTSTRAP.create_rotated_source_record(
                output=wrong_namespace_output,
                previous_source_record=self.output,
                environment_id=ENVIRONMENT_ID,
                namespace="other-namespace",
                source_ttl_seconds=3600,
                now=NOW + dt.timedelta(minutes=1),
            )
        self.assertFalse(wrong_namespace_output.exists())

    def test_rotation_rejects_unprotected_previous_directory(self) -> None:
        self.create()
        protected_output_directory = self.custody_directory / "protected-output"
        protected_output_directory.mkdir(mode=0o700)
        rotated_output = protected_output_directory / "rotated-source-record.json"
        os.chmod(self.custody_directory, 0o750)
        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "directory must be owner-only"):
            BOOTSTRAP.create_rotated_source_record(
                output=rotated_output,
                previous_source_record=self.output,
                environment_id=ENVIRONMENT_ID,
                namespace=TARGET_NAMESPACE,
                source_ttl_seconds=3600,
                now=NOW + dt.timedelta(minutes=1),
            )
        self.assertFalse(rotated_output.exists())

    def test_rotation_rejects_oversized_record_before_writing(self) -> None:
        self.create()
        rotated_output = self.custody_directory / "rotated-source-record.json"
        size_limited_materializer = SimpleNamespace(
            read_source_record=MATERIALIZER.read_source_record,
            parse_manifest=MATERIALIZER.parse_manifest,
            MaterializationError=MATERIALIZER.MaterializationError,
            MAX_SOURCE_RECORD_BYTES=1,
        )
        with patch.object(BOOTSTRAP, "_load_materializer", return_value=size_limited_materializer), self.assertRaisesRegex(
            BOOTSTRAP.BootstrapError, "size limit"
        ):
            BOOTSTRAP.create_rotated_source_record(
                output=rotated_output,
                previous_source_record=self.output,
                environment_id=ENVIRONMENT_ID,
                namespace=TARGET_NAMESPACE,
                source_ttl_seconds=3600,
                now=NOW + dt.timedelta(minutes=1),
            )
        self.assertFalse(rotated_output.exists())

    def test_rotation_refuses_same_output_without_overwrite_or_regeneration(self) -> None:
        self.create()
        first_bytes = self.output.read_bytes()
        with patch.object(BOOTSTRAP.secrets, "token_bytes", side_effect=AssertionError("regenerated")), patch.object(
            BOOTSTRAP.secrets, "token_urlsafe", side_effect=AssertionError("regenerated")
        ), self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "already exists"):
            BOOTSTRAP.create_rotated_source_record(
                output=self.output,
                previous_source_record=self.output,
                environment_id=ENVIRONMENT_ID,
                namespace=TARGET_NAMESPACE,
                source_ttl_seconds=3600,
                now=NOW + dt.timedelta(minutes=1),
            )
        self.assertEqual(first_bytes, self.output.read_bytes())

    def test_rotation_cli_does_not_print_source_material(self) -> None:
        previous_output = self.custody_directory / "previous-source-record.json"
        self.create(output=previous_output, now=dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=1))
        rotated_output = self.custody_directory / "rotated-source-record.json"
        stdout = io.StringIO()
        stderr = io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            result = BOOTSTRAP.main(
                [
                    "rotate",
                    "--output",
                    str(rotated_output),
                    "--previous-source-record",
                    str(previous_output),
                    "--environment-id",
                    ENVIRONMENT_ID,
                    "--namespace",
                    TARGET_NAMESPACE,
                    "--source-ttl-seconds",
                    "3600",
                ]
            )
        self.assertEqual(0, result)
        self.assertIn("created rotated Account response-envelope source record", stdout.getvalue())
        self.assertEqual("", stderr.getvalue())
        output_text = stdout.getvalue() + stderr.getvalue()
        rotated_text = rotated_output.read_text(encoding="utf-8")
        self.assertNotIn(rotated_text, output_text)
        self.assertNotIn(MATERIALIZER.read_source_record(rotated_output).manifest_bytes.decode("ascii"), output_text)

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

    def test_dangling_output_symlink_cannot_redirect_creation(self) -> None:
        target = self.custody_directory / "absent-target"
        self.output.symlink_to(target)

        with self.assertRaisesRegex(BOOTSTRAP.BootstrapError, "already exists"):
            self.create()

        self.assertFalse(target.exists())

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
