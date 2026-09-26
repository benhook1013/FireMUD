from __future__ import annotations

import base64
import contextlib
import copy
import datetime as dt
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

SCRIPT_PATH = (
    Path(__file__).resolve().parents[1]
    / "deploy"
    / "materialize-account-response-envelope-ring.py"
)
SPEC = importlib.util.spec_from_file_location("account_response_envelope_materializer", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
MATERIALIZER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MATERIALIZER
SPEC.loader.exec_module(MATERIALIZER)


NOW = dt.datetime(2026, 9, 27, 12, 0, 0, tzinfo=dt.timezone.utc)
SOURCE_EXPIRY = "2030-01-01T00:00:00Z"
MAX_AGE_SECONDS = 86_400


def manifest_for(*, active: str, ids: tuple[str, ...], first_material_number: int = 1) -> bytes:
    lines = ["version=1", f"activeKeyId={active}"]
    material_number = first_material_number
    for key_id in ids:
        for purpose in ("bare-login", "connect-token"):
            material = bytes([material_number]) * 32
            material_number += 1
            encoded = base64.urlsafe_b64encode(material).rstrip(b"=").decode("ascii")
            lines.append(f"key:{key_id}:{purpose}={encoded}")
    return ("\n".join(lines) + "\n").encode("ascii")


def source_record_for(manifest: bytes, generation: str, expires_at: str) -> bytes:
    record = {
        "version": 1,
        "manifestBase64": base64.b64encode(manifest).decode("ascii"),
        "sourceGeneration": generation,
        "sourceExpiresAt": expires_at,
    }
    encoded = json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return (encoded + "\n").encode("utf-8")


class FakeKubectl:
    def __init__(self) -> None:
        self.secret: dict | None = None
        self.mutations: list[tuple[str, dict]] = []
        self.operations: list[str] = []
        self.attempts: list[tuple[str, dict]] = []
        self.reads = 0
        self.mutate_readback = False
        self.fail_operation: str | None = None
        self.raise_timeout = False
        self.raise_unicode_error = False
        self.timeouts: list[int | None] = []

    def __call__(self, command: list[str], **kwargs) -> SimpleNamespace:
        operation = command[1]
        self.operations.append(operation)
        self.timeouts.append(kwargs.get("timeout"))
        if self.raise_timeout:
            raise MATERIALIZER.subprocess.TimeoutExpired(
                command, kwargs.get("timeout"), output="sensitive output"
            )
        if self.raise_unicode_error:
            raise UnicodeDecodeError("utf-8", b"\xff", 0, 1, "invalid test output")
        if operation == "get":
            self.reads += 1
            return SimpleNamespace(
                returncode=0,
                stdout="" if self.secret is None else json.dumps(self.secret),
                stderr="",
            )
        request_object = json.loads(kwargs["input"])
        self.attempts.append((operation, copy.deepcopy(request_object)))
        if operation == self.fail_operation:
            return SimpleNamespace(returncode=1, stdout="", stderr="provider error")
        if operation == "create":
            if self.secret is not None:
                return SimpleNamespace(returncode=1, stdout="", stderr="already exists")
            resource_version = "1"
        elif operation == "replace":
            if self.secret is None:
                return SimpleNamespace(returncode=1, stdout="", stderr="not found")
            expected_resource_version = self.secret["metadata"]["resourceVersion"]
            if request_object["metadata"].get("resourceVersion") != expected_resource_version:
                return SimpleNamespace(returncode=1, stdout="", stderr="conflict")
            resource_version = str(int(expected_resource_version) + 1)
        else:
            raise AssertionError(f"unexpected kubectl operation: {operation}")
        self.mutations.append((operation, copy.deepcopy(request_object)))
        request_object["metadata"]["resourceVersion"] = resource_version
        self.secret = request_object
        if self.mutate_readback:
            self.secret["data"][MATERIALIZER.SECRET_KEY] = base64.b64encode(b"tampered").decode("ascii")
        return SimpleNamespace(returncode=0, stdout=json.dumps(self.secret), stderr="")


class AccountResponseEnvelopeMaterializerTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary_directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary_directory.cleanup)
        self.source_path = Path(self.temporary_directory.name) / "source-record.json"
        self.source_manifest = manifest_for(active="k1", ids=("k1",))
        self.source_generation = "custody-generation-1"
        self.source_expires_at = SOURCE_EXPIRY
        self.write_source_record()
        self.fake_kubectl = FakeKubectl()
        self.kubectl_patch = patch.object(
            MATERIALIZER.subprocess, "run", side_effect=self.fake_kubectl
        )
        self.kubectl_patch.start()
        self.addCleanup(self.kubectl_patch.stop)

    def write_source_record(
        self,
        *,
        manifest: bytes | None = None,
        generation: str | None = None,
        expires_at: str | None = None,
        raw: bytes | None = None,
    ) -> None:
        if manifest is not None:
            self.source_manifest = manifest
        if generation is not None:
            self.source_generation = generation
        if expires_at is not None:
            self.source_expires_at = expires_at
        source_record = raw or source_record_for(
            self.source_manifest, self.source_generation, self.source_expires_at
        )
        self.source_path.write_bytes(source_record)
        os.chmod(self.source_path, 0o600)

    def run_materializer(self, *, now: dt.datetime = NOW) -> bool:
        return MATERIALIZER.materialize(
            source_record_path=self.source_path,
            namespace="account-prod",
            class_max_age_seconds=MAX_AGE_SECONDS,
            kubectl="kubectl-test-double",
            now=now,
        )

    def test_rfc3339_utc_accepts_canonical_fractional_precision(self) -> None:
        timestamp = "2026-09-27T12:00:00.00101Z"
        parsed = MATERIALIZER.parse_rfc3339_utc(timestamp, "source expiry")

        self.assertEqual(1010, parsed.microsecond)
        self.assertEqual(timestamp, MATERIALIZER.format_timestamp(parsed))

    def test_create_then_exact_retry_preserves_bytes_and_timestamps_without_write(self) -> None:
        self.assertTrue(self.run_materializer())
        created = json.loads(json.dumps(self.fake_kubectl.secret))
        self.assertEqual(["create"], [operation for operation, _ in self.fake_kubectl.mutations])
        self.assertEqual([30], self.fake_kubectl.timeouts[:1])
        annotations = created["metadata"]["annotations"]
        self.assertEqual("custody-generation-1", annotations[MATERIALIZER.ANNOTATION_SOURCE_GENERATION])
        self.assertEqual("2026-09-28T12:00:00Z", annotations[MATERIALIZER.ANNOTATION_EXPIRES_AT])
        self.assertEqual(
            base64.b64encode(self.source_manifest).decode("ascii"),
            created["data"][MATERIALIZER.SECRET_KEY],
        )

        self.assertFalse(
            self.run_materializer(now=NOW + dt.timedelta(hours=1))
        )
        self.assertEqual(["create"], [operation for operation, _ in self.fake_kubectl.mutations])
        self.assertEqual(created, self.fake_kubectl.secret)

    def test_same_generation_with_different_bytes_fails_without_write(self) -> None:
        self.run_materializer()
        self.write_source_record(manifest=manifest_for(active="k2", ids=("k2",)))

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "same source generation"):
            self.run_materializer()

        self.assertEqual(1, len(self.fake_kubectl.mutations))

    def test_changed_generation_with_identical_bytes_fails_without_write(self) -> None:
        self.run_materializer()
        self.write_source_record(generation="custody-generation-2")

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "generation cannot advance"):
            self.run_materializer()

        self.assertEqual(["get", "create", "get", "get"], self.fake_kubectl.operations)
        self.assertEqual(1, len(self.fake_kubectl.mutations))

    def test_rotation_uses_resource_version_and_retains_prior_ids_and_material(self) -> None:
        self.run_materializer()
        previous = copy.deepcopy(self.fake_kubectl.secret)
        self.write_source_record(
            manifest=manifest_for(active="k2", ids=("k1", "k2")),
            generation="custody-generation-2",
        )

        self.assertTrue(
            self.run_materializer(
                now=NOW + dt.timedelta(hours=2),
            )
        )

        self.assertEqual(["create", "replace"], [op for op, _ in self.fake_kubectl.mutations])
        replacement = self.fake_kubectl.mutations[-1][1]
        self.assertEqual(previous["metadata"]["resourceVersion"], replacement["metadata"]["resourceVersion"])
        self.assertEqual({"k1", "k2"}, MATERIALIZER.parse_manifest(self.source_manifest).key_ids)
        self.assertEqual("custody-generation-2", self.fake_kubectl.secret["metadata"]["annotations"][MATERIALIZER.ANNOTATION_SOURCE_GENERATION])

    def test_rotation_cannot_drop_prior_decrypt_key(self) -> None:
        self.run_materializer()
        self.write_source_record(
            manifest=manifest_for(active="k2", ids=("k2",)),
            generation="custody-generation-2",
        )

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "removes a prior key ID"):
            self.run_materializer()

        self.assertEqual(1, len(self.fake_kubectl.mutations))

    def test_rotation_cannot_reactivate_old_decrypt_key(self) -> None:
        self.run_materializer()
        self.write_source_record(
            manifest=manifest_for(active="k2", ids=("k1", "k2")),
            generation="custody-generation-2",
        )
        self.run_materializer()
        self.write_source_record(
            manifest=manifest_for(active="k1", ids=("k1", "k2")),
            generation="custody-generation-3",
        )

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "new active key ID"):
            self.run_materializer()

        self.assertEqual(2, len(self.fake_kubectl.mutations))

    def test_rotation_cannot_change_retained_key_bytes(self) -> None:
        self.run_materializer()
        self.write_source_record(
            manifest=manifest_for(active="k2", ids=("k1", "k2"), first_material_number=10),
            generation="custody-generation-2",
        )

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "changes material"):
            self.run_materializer()

        self.assertEqual(["get", "create", "get", "get"], self.fake_kubectl.operations)
        self.assertEqual(1, len(self.fake_kubectl.mutations))

    def test_same_generation_metadata_mismatch_fails_closed(self) -> None:
        self.run_materializer()
        self.fake_kubectl.secret["metadata"]["annotations"][MATERIALIZER.ANNOTATION_MATERIALIZED_AT] = "2026-09-27T11:00:00Z"

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "expiry metadata"):
            self.run_materializer()

        self.assertEqual(1, len(self.fake_kubectl.mutations))

    def test_post_write_readback_mismatch_fails_without_exposing_bytes(self) -> None:
        self.fake_kubectl.mutate_readback = True

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "readback manifest bytes"):
            self.run_materializer()

        self.assertEqual(1, len(self.fake_kubectl.mutations))
        self.assertEqual(["get", "create", "get"], self.fake_kubectl.operations)
        self.assertNotIn("delete", self.fake_kubectl.operations)
        self.assertEqual(1, len(self.fake_kubectl.attempts))

    def test_failed_create_does_not_delete_retry_or_change_source_bytes(self) -> None:
        self.fake_kubectl.fail_operation = "create"
        source_record_bytes = self.source_path.read_bytes()

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "Kubernetes operation failed"):
            self.run_materializer()

        self.assertEqual(["get", "create"], self.fake_kubectl.operations)
        self.assertNotIn("delete", self.fake_kubectl.operations)
        self.assertEqual(1, len(self.fake_kubectl.attempts))
        attempted_manifest = base64.b64decode(
            self.fake_kubectl.attempts[0][1]["data"][MATERIALIZER.SECRET_KEY]
        )
        self.assertEqual(self.source_manifest, attempted_manifest)
        self.assertEqual(source_record_bytes, self.source_path.read_bytes())

    def test_failed_replace_does_not_delete_retry_or_change_source_bytes(self) -> None:
        self.run_materializer()
        self.fake_kubectl.fail_operation = "replace"
        replacement_bytes = manifest_for(active="k2", ids=("k1", "k2"))
        self.write_source_record(manifest=replacement_bytes, generation="custody-generation-2")

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "Kubernetes operation failed"):
            self.run_materializer()

        self.assertEqual(["get", "create", "get", "get", "replace"], self.fake_kubectl.operations)
        self.assertNotIn("delete", self.fake_kubectl.operations)
        self.assertEqual(["create", "replace"], [operation for operation, _ in self.fake_kubectl.attempts])
        attempted_manifest = base64.b64decode(
            self.fake_kubectl.attempts[-1][1]["data"][MATERIALIZER.SECRET_KEY]
        )
        self.assertEqual(replacement_bytes, attempted_manifest)
        self.assertEqual(replacement_bytes, self.source_manifest)

    def test_kubectl_timeout_fails_closed_without_leaking_output_or_retry(self) -> None:
        self.fake_kubectl.raise_timeout = True

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "timed out") as raised:
            self.run_materializer()

        self.assertNotIn("sensitive output", str(raised.exception))
        self.assertEqual(["get"], self.fake_kubectl.operations)
        self.assertEqual([30], self.fake_kubectl.timeouts)
        self.assertEqual([], self.fake_kubectl.attempts)

    def test_kubectl_invalid_text_fails_closed_without_leaking_output_or_retry(self) -> None:
        self.fake_kubectl.raise_unicode_error = True

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "invalid text") as raised:
            self.run_materializer()

        self.assertNotIn("invalid test output", str(raised.exception))
        self.assertEqual(["get"], self.fake_kubectl.operations)
        self.assertEqual([], self.fake_kubectl.attempts)

    def test_missing_source_fails_before_kubernetes_access(self) -> None:
        self.source_path.unlink()

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "source record"):
            self.run_materializer()

        self.assertEqual(0, self.fake_kubectl.reads)
        self.assertEqual([], self.fake_kubectl.mutations)

    def test_source_record_rejects_duplicate_metadata_fields(self) -> None:
        encoded_manifest = base64.b64encode(self.source_manifest).decode("ascii")
        raw_record = (
            '{"version":1,"manifestBase64":"'
            + encoded_manifest
            + '","sourceGeneration":"custody-generation-1",'
            '"sourceGeneration":"custody-generation-2",'
            '"sourceExpiresAt":"2030-01-01T00:00:00Z"}\n'
        ).encode("utf-8")
        self.write_source_record(raw=raw_record)

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "duplicate JSON fields"):
            self.run_materializer()

        self.assertEqual(0, self.fake_kubectl.reads)

    def test_source_record_rejects_contradictory_version(self) -> None:
        source_record = {
            "version": 2,
            "manifestBase64": base64.b64encode(self.source_manifest).decode("ascii"),
            "sourceGeneration": self.source_generation,
            "sourceExpiresAt": self.source_expires_at,
        }
        raw_record = (
            json.dumps(source_record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode("utf-8")
        self.write_source_record(raw=raw_record)

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "version must be the integer 1"):
            self.run_materializer()

        self.assertEqual(0, self.fake_kubectl.reads)

    def test_source_record_rejects_expired_source_metadata(self) -> None:
        self.write_source_record(expires_at="2026-09-27T11:59:59Z")

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "source expiry must be in the future"):
            self.run_materializer()

        self.assertEqual(0, self.fake_kubectl.reads)

    def test_source_file_with_group_or_world_access_is_rejected(self) -> None:
        os.chmod(self.source_path, 0o640)

        with self.assertRaisesRegex(MATERIALIZER.MaterializationError, "owner-only permissions"):
            self.run_materializer()

        self.assertEqual(0, self.fake_kubectl.reads)

    def test_cli_uses_protected_source_record_without_printing_material(self) -> None:
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            result = MATERIALIZER.main(
                [
                    "--source-record",
                    str(self.source_path),
                    "--namespace",
                    "account-prod",
                    "--class-max-age-seconds",
                    str(MAX_AGE_SECONDS),
                    "--kubectl",
                    "kubectl-test-double",
                ]
            )

        self.assertEqual(0, result)
        self.assertEqual(1, len(self.fake_kubectl.mutations))
        self.assertEqual(
            "materialized account-response-envelope-key-ring in namespace account-prod\n",
            output.getvalue(),
        )
        self.assertNotIn(self.source_generation, output.getvalue())
        self.assertNotIn(self.source_manifest.decode("ascii"), output.getvalue())


if __name__ == "__main__":
    unittest.main()
