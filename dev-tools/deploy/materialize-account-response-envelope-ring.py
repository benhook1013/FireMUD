#!/usr/bin/env python3
"""Materialize an externally custodied Account response-envelope ring into Kubernetes.

This tool accepts an owner-only source record containing version=1, canonical Base64 for the
exact manifest bytes, an opaque source generation, source expiry, target environment and
namespace, predecessor generation, and an immutable source-created timestamp. That timestamp is
a conservative age anchor for the Secret's materialized-at and expiry metadata, not a claim of
the later Kubernetes write time. The source record must come from protected durable custody. The
tool never creates or rotates key material. The caller supplies the trusted class maximum age
separately. Re-running an exact generation is read-only and preserves its timestamps.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import datetime as dt
import json
import os
import re
import stat
import subprocess
import sys
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SECRET_NAME = "account-response-envelope-key-ring"
SECRET_KEY = "manifest.v1"
MAX_MANIFEST_BYTES = 64 * 1024
MAX_SOURCE_RECORD_BYTES = 2 * MAX_MANIFEST_BYTES
KUBECTL_TIMEOUT_SECONDS = 30
ANNOTATION_MATERIALIZED_AT = "firemud.io/materialized-at"
ANNOTATION_EXPIRES_AT = "firemud.io/expires-at"
ANNOTATION_SOURCE_GENERATION = "firemud.io/source-generation"
ANNOTATION_ENVIRONMENT_ID = "firemud.io/environment-id"
ANNOTATION_TARGET_NAMESPACE = "firemud.io/target-namespace"
ANNOTATION_PREVIOUS_SOURCE_GENERATION = "firemud.io/previous-source-generation"
REQUIRED_ANNOTATIONS = (
    ANNOTATION_MATERIALIZED_AT,
    ANNOTATION_EXPIRES_AT,
    ANNOTATION_SOURCE_GENERATION,
    ANNOTATION_ENVIRONMENT_ID,
    ANNOTATION_TARGET_NAMESPACE,
    ANNOTATION_PREVIOUS_SOURCE_GENERATION,
)
KEY_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
KEY_BYTES_PATTERN = re.compile(rb"[A-Za-z0-9_-]{43}\Z")
RFC3339_UTC_PATTERN = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z\Z"
)


class MaterializationError(Exception):
    """A safe, non-secret-bearing error suitable for command-line output."""


@dataclass(frozen=True)
class ParsedManifest:
    active_key_id: str
    keys: dict[str, dict[str, bytes]]

    @property
    def key_ids(self) -> frozenset[str]:
        return frozenset(self.keys)


@dataclass(frozen=True)
class ExistingSecret:
    manifest_bytes: bytes
    manifest: ParsedManifest
    materialized_at: dt.datetime
    materialized_at_text: str
    expires_at: dt.datetime
    expires_at_text: str
    source_generation: str
    previous_source_generation: str | None
    environment_id: str
    target_namespace: str
    resource_version: str
    labels: dict[str, str]
    annotations: dict[str, str]


@dataclass(frozen=True)
class SourceRecord:
    manifest_bytes: bytes
    source_generation: str
    source_expires_at: dt.datetime
    source_expires_at_text: str
    source_created_at: dt.datetime
    source_created_at_text: str
    environment_id: str
    target_namespace: str
    previous_source_generation: str | None


def parse_rfc3339_utc(value: str, field_name: str) -> dt.datetime:
    if not isinstance(value, str) or not RFC3339_UTC_PATTERN.fullmatch(value):
        raise MaterializationError(f"{field_name} must be an RFC 3339 UTC timestamp ending in Z")
    try:
        timestamp_format = "%Y-%m-%dT%H:%M:%S.%fZ" if "." in value else "%Y-%m-%dT%H:%M:%SZ"
        parsed = dt.datetime.strptime(value, timestamp_format).replace(tzinfo=dt.timezone.utc)
    except ValueError as exc:
        raise MaterializationError(f"{field_name} is not a valid RFC 3339 UTC timestamp") from exc
    if format_timestamp(parsed) != value:
        raise MaterializationError(f"{field_name} must use canonical RFC 3339 UTC formatting")
    return parsed


def format_timestamp(value: dt.datetime) -> str:
    normalized = value.astimezone(dt.timezone.utc)
    rendered = normalized.isoformat(timespec="microseconds")
    rendered = rendered.replace("+00:00", "Z")
    if "." in rendered:
        rendered = rendered.replace("Z", "").rstrip("0").rstrip(".") + "Z"
    return rendered


def validate_source_generation(value: str) -> str:
    try:
        encoded = value.encode("utf-8")
    except UnicodeError as exc:
        raise MaterializationError("source generation must be valid UTF-8") from exc
    if not value or len(encoded) > 4096:
        raise MaterializationError("source generation must be a non-empty opaque annotation value")
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        raise MaterializationError("source generation must not contain control characters")
    return value


def validate_environment_id(value: str) -> str:
    try:
        encoded = value.encode("utf-8")
    except UnicodeError as exc:
        raise MaterializationError("environment ID must be valid UTF-8") from exc
    if not value or len(encoded) > 4096:
        raise MaterializationError("environment ID must be a non-empty opaque value")
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        raise MaterializationError("environment ID must not contain control characters")
    return value


def validate_namespace(value: str) -> str:
    if not isinstance(value, str) or len(value) > 63 or not re.fullmatch(
        r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", value
    ):
        raise MaterializationError("target namespace must be a Kubernetes namespace name")
    return value


def read_protected_source_record(path: Path) -> bytes:
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except OSError as exc:
        raise MaterializationError("source record is missing or unreadable") from exc
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_mode & 0o077:
            raise MaterializationError("source record must be a regular file with owner-only permissions")
        with os.fdopen(descriptor, "rb", closefd=False) as source:
            source_record = source.read(MAX_SOURCE_RECORD_BYTES + 1)
        if not source_record or len(source_record) > MAX_SOURCE_RECORD_BYTES:
            raise MaterializationError("source record is empty or exceeds the format size limit")
        return source_record
    except OSError as exc:
        raise MaterializationError("source record is missing or unreadable") from exc
    finally:
        os.close(descriptor)


def _unique_json_members(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise MaterializationError("source record contains duplicate JSON fields")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> None:
    raise MaterializationError("source record contains a non-standard JSON value")


def read_source_record(path: Path) -> SourceRecord:
    source_record_bytes = read_protected_source_record(path)
    try:
        source_record_text = source_record_bytes.decode("utf-8")
        record = json.loads(
            source_record_text,
            object_pairs_hook=_unique_json_members,
            parse_constant=_reject_json_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, RecursionError) as exc:
        raise MaterializationError("source record must be valid UTF-8 JSON") from exc
    expected_fields = {
        "version",
        "manifestBase64",
        "sourceGeneration",
        "sourceExpiresAt",
        "sourceCreatedAt",
        "environmentId",
        "targetNamespace",
        "previousSourceGeneration",
    }
    if not isinstance(record, dict) or set(record) != expected_fields:
        raise MaterializationError("source record fields do not match the version 1 contract")
    if type(record["version"]) is not int or record["version"] != 1:
        raise MaterializationError("source record version must be the integer 1")
    if not isinstance(record["manifestBase64"], str):
        raise MaterializationError("source record manifestBase64 must be a string")
    if not isinstance(record["sourceGeneration"], str):
        raise MaterializationError("source record sourceGeneration must be a string")
    if not isinstance(record["sourceExpiresAt"], str):
        raise MaterializationError("source record sourceExpiresAt must be a string")
    if not isinstance(record["sourceCreatedAt"], str):
        raise MaterializationError("source record sourceCreatedAt must be a string")
    if not isinstance(record["environmentId"], str):
        raise MaterializationError("source record environmentId must be a string")
    if not isinstance(record["targetNamespace"], str):
        raise MaterializationError("source record targetNamespace must be a string")
    if record["previousSourceGeneration"] is not None and not isinstance(
        record["previousSourceGeneration"], str
    ):
        raise MaterializationError("source record previousSourceGeneration must be a string or null")
    try:
        canonical_record = (
            json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
        ).encode("utf-8")
        encoded_manifest = record["manifestBase64"].encode("ascii")
    except (UnicodeEncodeError, TypeError, ValueError) as exc:
        raise MaterializationError("source record contains invalid text") from exc
    if source_record_bytes != canonical_record:
        raise MaterializationError("source record must use canonical JSON encoding and one trailing LF")
    try:
        manifest_bytes = base64.b64decode(encoded_manifest, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise MaterializationError("source record manifestBase64 is malformed") from exc
    if base64.b64encode(manifest_bytes) != encoded_manifest:
        raise MaterializationError("source record manifestBase64 is not canonical")
    if not manifest_bytes or len(manifest_bytes) > MAX_MANIFEST_BYTES:
        raise MaterializationError("source record manifest is empty or exceeds the format size limit")
    source_generation = validate_source_generation(record["sourceGeneration"])
    environment_id = validate_environment_id(record["environmentId"])
    target_namespace = validate_namespace(record["targetNamespace"])
    previous_source_generation = record["previousSourceGeneration"]
    if previous_source_generation is not None:
        previous_source_generation = validate_source_generation(previous_source_generation)
    source_expires_at_text = record["sourceExpiresAt"]
    source_expires_at = parse_rfc3339_utc(source_expires_at_text, "source expiry")
    source_created_at_text = record["sourceCreatedAt"]
    source_created_at = parse_rfc3339_utc(source_created_at_text, "source creation time")
    if source_expires_at <= source_created_at:
        raise MaterializationError("source expiry must follow source creation time")
    return SourceRecord(
        manifest_bytes=manifest_bytes,
        source_generation=source_generation,
        source_expires_at=source_expires_at,
        source_expires_at_text=source_expires_at_text,
        source_created_at=source_created_at,
        source_created_at_text=source_created_at_text,
        environment_id=environment_id,
        target_namespace=target_namespace,
        previous_source_generation=previous_source_generation,
    )


def parse_manifest(manifest: bytes) -> ParsedManifest:
    if not manifest or len(manifest) > MAX_MANIFEST_BYTES or not manifest.endswith(b"\n"):
        raise MaterializationError("source manifest does not match Account manifest v1 framing")
    if any(value == 0x0D or value > 0x7E or (value < 0x21 and value != 0x0A) for value in manifest):
        raise MaterializationError("source manifest is not strict printable ASCII")
    try:
        lines = manifest[:-1].decode("ascii").split("\n")
    except UnicodeDecodeError as exc:
        raise MaterializationError("source manifest is not ASCII") from exc
    if len(lines) < 4 or any(not line for line in lines):
        raise MaterializationError("source manifest has missing or empty lines")
    if lines[0] != "version=1" or not lines[1].startswith("activeKeyId="):
        raise MaterializationError("source manifest does not match Account manifest v1 headers")
    active_key_id = lines[1][len("activeKeyId=") :]
    if not KEY_ID_PATTERN.fullmatch(active_key_id):
        raise MaterializationError("source manifest has an invalid active key ID")

    keys: dict[str, dict[str, bytes]] = {}
    seen_material: set[bytes] = set()
    allowed_purposes = {"bare-login", "connect-token"}
    for line in lines[2:]:
        if line.count("=") != 1:
            raise MaterializationError("source manifest contains a malformed key line")
        identity, encoded_text = line.split("=", 1)
        parts = identity.split(":")
        if len(parts) != 3 or parts[0] != "key":
            raise MaterializationError("source manifest contains a malformed key line")
        _, key_id, purpose = parts
        if not KEY_ID_PATTERN.fullmatch(key_id) or purpose not in allowed_purposes:
            raise MaterializationError("source manifest contains an invalid key identity")
        encoded = encoded_text.encode("ascii")
        if not KEY_BYTES_PATTERN.fullmatch(encoded):
            raise MaterializationError("source manifest contains invalid encoded key material")
        try:
            key_bytes = base64.urlsafe_b64decode(encoded + b"=")
        except (binascii.Error, ValueError) as exc:
            raise MaterializationError("source manifest contains invalid encoded key material") from exc
        if len(key_bytes) != 32 or base64.urlsafe_b64encode(key_bytes).rstrip(b"=") != encoded:
            raise MaterializationError("source manifest key material is not a canonical 32-byte value")
        if key_bytes in seen_material:
            raise MaterializationError("source manifest reuses key material")
        by_purpose = keys.setdefault(key_id, {})
        if purpose in by_purpose:
            raise MaterializationError("source manifest repeats a key identity")
        by_purpose[purpose] = key_bytes
        seen_material.add(key_bytes)

    if not keys or active_key_id not in keys:
        raise MaterializationError("source manifest does not contain its active key ID")
    if any(set(by_purpose) != allowed_purposes for by_purpose in keys.values()):
        raise MaterializationError("every retained key ID must contain both Account purposes")
    return ParsedManifest(active_key_id, keys)


def _decode_secret_manifest(secret: dict[str, Any], namespace: str) -> tuple[bytes, dict[str, Any]]:
    metadata = secret.get("metadata")
    if not isinstance(metadata, dict):
        raise MaterializationError("existing Secret metadata is missing or malformed")
    if metadata.get("name") != SECRET_NAME or metadata.get("namespace") != namespace:
        raise MaterializationError("existing Secret identity is inconsistent")
    if secret.get("apiVersion") != "v1" or secret.get("kind") != "Secret" or secret.get("type") != "Opaque":
        raise MaterializationError("existing Secret type or API identity is inconsistent")
    data = secret.get("data")
    if not isinstance(data, dict) or set(data) != {SECRET_KEY} or not isinstance(data.get(SECRET_KEY), str):
        raise MaterializationError("existing Secret must contain exactly the canonical manifest key")
    try:
        encoded = data[SECRET_KEY].encode("ascii", errors="strict")
        manifest_bytes = base64.b64decode(encoded, validate=True)
    except (binascii.Error, UnicodeEncodeError, ValueError) as exc:
        raise MaterializationError("existing Secret manifest encoding is malformed") from exc
    if base64.b64encode(manifest_bytes) != encoded:
        raise MaterializationError("existing Secret manifest encoding is not canonical")
    return manifest_bytes, metadata


def _load_existing_secret(
    secret: dict[str, Any], namespace: str, expected_environment_id: str
) -> ExistingSecret:
    manifest_bytes, metadata = _decode_secret_manifest(secret, namespace)
    parsed_manifest = parse_manifest(manifest_bytes)
    annotations = metadata.get("annotations")
    labels = metadata.get("labels", {})
    resource_version = metadata.get("resourceVersion")
    if not isinstance(annotations, dict) or not isinstance(labels, dict):
        raise MaterializationError("existing Secret annotations or labels are malformed")
    if not isinstance(resource_version, str) or not resource_version:
        raise MaterializationError("existing Secret resource version is missing")
    if any(key not in annotations or not isinstance(annotations[key], str) for key in REQUIRED_ANNOTATIONS):
        raise MaterializationError("existing Secret freshness metadata is missing")
    generation = validate_source_generation(annotations[ANNOTATION_SOURCE_GENERATION])
    environment_id = validate_environment_id(annotations[ANNOTATION_ENVIRONMENT_ID])
    target_namespace = validate_namespace(annotations[ANNOTATION_TARGET_NAMESPACE])
    previous_generation_text = annotations[ANNOTATION_PREVIOUS_SOURCE_GENERATION]
    previous_generation = (
        None
        if previous_generation_text == ""
        else validate_source_generation(previous_generation_text)
    )
    if environment_id != expected_environment_id or target_namespace != namespace:
        raise MaterializationError("existing Secret environment or target namespace is inconsistent")
    materialized_text = annotations[ANNOTATION_MATERIALIZED_AT]
    expires_text = annotations[ANNOTATION_EXPIRES_AT]
    materialized_at = parse_rfc3339_utc(materialized_text, "existing materialized-at")
    expires_at = parse_rfc3339_utc(expires_text, "existing expires-at")
    if expires_at <= materialized_at:
        raise MaterializationError("existing Secret expiry does not follow materialization")
    if any(not isinstance(key, str) or not isinstance(value, str) for key, value in labels.items()):
        raise MaterializationError("existing Secret labels are malformed")
    if any(not isinstance(key, str) or not isinstance(value, str) for key, value in annotations.items()):
        raise MaterializationError("existing Secret annotations are malformed")
    return ExistingSecret(
        manifest_bytes=manifest_bytes,
        manifest=parsed_manifest,
        materialized_at=materialized_at,
        materialized_at_text=materialized_text,
        expires_at=expires_at,
        expires_at_text=expires_text,
        source_generation=generation,
        previous_source_generation=previous_generation,
        environment_id=environment_id,
        target_namespace=target_namespace,
        resource_version=resource_version,
        labels=dict(labels),
        annotations=dict(annotations),
    )


def _run_kubectl(command: Sequence[str], request_object: dict[str, Any] | None = None) -> str:
    try:
        completed = subprocess.run(
            list(command),
            input=None if request_object is None else json.dumps(request_object, separators=(",", ":")),
            capture_output=True,
            text=True,
            check=False,
            timeout=KUBECTL_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as exc:
        raise MaterializationError("Kubernetes operation timed out") from exc
    except UnicodeError as exc:
        raise MaterializationError("Kubernetes operation returned invalid text") from exc
    except OSError as exc:
        raise MaterializationError("kubectl could not be started") from exc
    if completed.returncode != 0:
        raise MaterializationError("Kubernetes operation failed; existing Secret was left for diagnosis")
    return completed.stdout


def _read_secret(kubectl: str, namespace: str) -> dict[str, Any] | None:
    output = _run_kubectl(
        [
            kubectl,
            "get",
            "secret",
            SECRET_NAME,
            "--namespace",
            namespace,
            "--ignore-not-found=true",
            "--output=json",
        ]
    )
    if not output.strip():
        return None
    try:
        secret = json.loads(output)
    except json.JSONDecodeError as exc:
        raise MaterializationError("Kubernetes returned malformed Secret readback") from exc
    if not isinstance(secret, dict):
        raise MaterializationError("Kubernetes returned malformed Secret readback")
    return secret


def _bounded_expiry(
    source_expires_at: dt.datetime, materialized_at: dt.datetime, max_age_seconds: int
) -> dt.datetime:
    return min(source_expires_at, _class_age_deadline(materialized_at, max_age_seconds))


def _class_age_deadline(materialized_at: dt.datetime, max_age_seconds: int) -> dt.datetime:
    try:
        return materialized_at + dt.timedelta(seconds=max_age_seconds)
    except OverflowError as exc:
        raise MaterializationError("class maximum age is out of range") from exc


def _secret_for_write(
    manifest_bytes: bytes,
    namespace: str,
    source_generation: str,
    environment_id: str,
    previous_source_generation: str | None,
    materialized_at: dt.datetime,
    expires_at: dt.datetime,
    existing: ExistingSecret | None,
) -> dict[str, Any]:
    metadata: dict[str, Any] = {"name": SECRET_NAME, "namespace": namespace}
    if existing is not None:
        metadata["resourceVersion"] = existing.resource_version
        if existing.labels:
            metadata["labels"] = existing.labels
        annotations = existing.annotations
    else:
        annotations = {}
    annotations = dict(annotations)
    annotations.update(
        {
            ANNOTATION_MATERIALIZED_AT: format_timestamp(materialized_at),
            ANNOTATION_EXPIRES_AT: format_timestamp(expires_at),
            ANNOTATION_SOURCE_GENERATION: source_generation,
            ANNOTATION_ENVIRONMENT_ID: environment_id,
            ANNOTATION_TARGET_NAMESPACE: namespace,
            ANNOTATION_PREVIOUS_SOURCE_GENERATION: previous_source_generation or "",
        }
    )
    metadata["annotations"] = annotations
    return {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": metadata,
        "type": "Opaque",
        "data": {SECRET_KEY: base64.b64encode(manifest_bytes).decode("ascii")},
    }


def _verify_readback(
    secret: dict[str, Any] | None,
    namespace: str,
    environment_id: str,
    manifest_bytes: bytes,
    source_generation: str,
    materialized_at_text: str,
    expires_at_text: str,
    expected_labels: dict[str, str],
    expected_annotations: dict[str, str],
) -> None:
    if secret is None:
        raise MaterializationError("Secret readback is missing")
    observed_manifest, _ = _decode_secret_manifest(secret, namespace)
    if observed_manifest != manifest_bytes:
        raise MaterializationError("Secret readback manifest bytes do not match the source")
    current = _load_existing_secret(secret, namespace, environment_id)
    if current.source_generation != source_generation:
        raise MaterializationError("Secret readback source generation does not match")
    if current.materialized_at_text != materialized_at_text or current.expires_at_text != expires_at_text:
        raise MaterializationError("Secret readback freshness metadata does not match")
    if current.labels != expected_labels or current.annotations != expected_annotations:
        raise MaterializationError("Secret readback metadata does not match the materialized object")


def materialize(
    source_record_path: Path,
    environment_id: str,
    namespace: str,
    class_max_age_seconds: int,
    kubectl: str = "kubectl",
    now: dt.datetime | None = None,
) -> bool:
    environment_id = validate_environment_id(environment_id)
    namespace = validate_namespace(namespace)
    if class_max_age_seconds <= 0:
        raise MaterializationError("class maximum age must be a positive number of seconds")
    source_record = read_source_record(source_record_path)
    if source_record.environment_id != environment_id:
        raise MaterializationError("source record environment ID does not match the expected environment")
    if source_record.target_namespace != namespace:
        raise MaterializationError("source record target namespace does not match the expected namespace")
    source_generation = source_record.source_generation
    source_expires_at = source_record.source_expires_at
    source_created_at = source_record.source_created_at
    source_bytes = source_record.manifest_bytes
    source_manifest = parse_manifest(source_bytes)
    current_time = now or dt.datetime.now(dt.timezone.utc)
    if current_time.tzinfo is None:
        raise MaterializationError("materializer clock must be timezone-aware")
    current_time = current_time.astimezone(dt.timezone.utc)
    if source_expires_at <= current_time:
        raise MaterializationError("source expiry must be in the future")
    if source_created_at > current_time:
        raise MaterializationError("source creation time must not be in the future")
    expires_at = _bounded_expiry(source_expires_at, source_created_at, class_max_age_seconds)
    if expires_at <= current_time:
        raise MaterializationError("source generation has expired under the class maximum age")

    secret = _read_secret(kubectl, namespace)
    existing: ExistingSecret | None = None
    if secret is not None:
        existing = _load_existing_secret(secret, namespace, environment_id)
        if existing.materialized_at > current_time:
            raise MaterializationError("existing Secret materialized-at timestamp is in the future")
        if existing.source_generation == source_generation:
            if source_record.previous_source_generation != existing.previous_source_generation:
                raise MaterializationError("same-generation predecessor does not match materialization lineage")
            if (
                existing.materialized_at_text != source_record.source_created_at_text
                or existing.expires_at != expires_at
            ):
                raise MaterializationError("same-generation freshness metadata does not match the source record")
            if existing.manifest_bytes != source_bytes:
                raise MaterializationError("same source generation has different manifest bytes")
            return False
        if source_record.previous_source_generation != existing.source_generation:
            raise MaterializationError("source predecessor does not match the current Secret generation")
        if source_created_at < existing.materialized_at:
            raise MaterializationError("source creation time regresses the current Secret generation")
        previous_age_deadline = _class_age_deadline(existing.materialized_at, class_max_age_seconds)
        if existing.expires_at > previous_age_deadline:
            raise MaterializationError("existing Secret expiry exceeds the supplied class maximum age")
        if existing.manifest_bytes == source_bytes:
            raise MaterializationError("source generation cannot advance while manifest bytes stay unchanged")
        if source_manifest.active_key_id in existing.manifest.key_ids:
            raise MaterializationError("replacement must introduce a new active key ID")
        if not existing.manifest.key_ids.issubset(source_manifest.key_ids):
            raise MaterializationError("replacement removes a prior key ID needed for decryption")
        for key_id in existing.manifest.key_ids:
            if source_manifest.keys[key_id] != existing.manifest.keys[key_id]:
                raise MaterializationError("replacement changes material for a retained prior key ID")
    elif source_record.previous_source_generation is not None:
        raise MaterializationError("source predecessor requires an existing Secret generation")

    materialized_at = source_created_at
    if expires_at <= materialized_at:
        raise MaterializationError("bounded Secret expiry must follow source creation time")
    manifest_object = _secret_for_write(
        source_bytes,
        namespace,
        source_generation,
        environment_id,
        source_record.previous_source_generation,
        materialized_at,
        expires_at,
        existing,
    )
    expected_annotations = dict(manifest_object["metadata"]["annotations"])
    expected_labels = dict(manifest_object["metadata"].get("labels", {}))
    write_command = [kubectl, "create" if existing is None else "replace", "--namespace", namespace, "--filename", "-", "--output=json"]
    _run_kubectl(write_command, manifest_object)
    readback = _read_secret(kubectl, namespace)
    _verify_readback(
        readback,
        namespace,
        environment_id,
        source_bytes,
        source_generation,
        format_timestamp(materialized_at),
        format_timestamp(expires_at),
        expected_labels,
        expected_annotations,
    )
    return True


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-record", required=True, type=Path)
    parser.add_argument("--environment-id", required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument("--class-max-age-seconds", required=True, type=int)
    parser.add_argument("--kubectl", default="kubectl")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = _parse_args(argv)
        changed = materialize(
            source_record_path=args.source_record,
            environment_id=args.environment_id,
            namespace=args.namespace,
            class_max_age_seconds=args.class_max_age_seconds,
            kubectl=args.kubectl,
        )
    except MaterializationError as exc:
        print(f"materialization failed: {exc}", file=sys.stderr)
        return 1
    except (OSError, ValueError, TypeError):
        print("materialization failed: invalid input or unavailable dependency", file=sys.stderr)
        return 1
    action = "materialized" if changed else "already materialized"
    print(f"{action} {SECRET_NAME} in namespace {args.namespace}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
