#!/usr/bin/env python3
"""Create initial or explicitly rotated Account response-envelope ring source records.

This is an unactivated operator bootstrap helper. It creates one version-1 source
record for the Account response-envelope materializer in an operator-supplied,
owner-only directory outside this repository. The source record is intended for
protected durable custody; creating this local file does not prove that custody,
sole-writer RBAC, Kubernetes deployment, or workload readiness exists.

The output is create-once. Existing output is never opened for writing, replaced,
or regenerated. Key material and the source-record JSON are never printed.
"""

from __future__ import annotations

import argparse
import base64
import datetime as dt
import importlib.util
import json
import os
import re
import secrets
import stat
import sys
from collections.abc import Sequence
from pathlib import Path

SOURCE_RECORD_VERSION = 1
KEY_BYTES = 32
OWNER_DIRECTORY_MODE = 0o700
OWNER_FILE_MODE = 0o600
KEY_ID_PATTERN = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
NAMESPACE_PATTERN = re.compile(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?\Z")
RFC3339_UTC_PATTERN = re.compile(
    r"[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,6})?Z\Z"
)


class BootstrapError(Exception):
    """A safe, non-secret-bearing bootstrap failure."""


def format_timestamp(value: dt.datetime) -> str:
    """Render the materializer's canonical UTC timestamp format."""

    if value.tzinfo is None or value.utcoffset() is None:
        raise BootstrapError("timestamp must be timezone-aware")
    rendered = value.astimezone(dt.timezone.utc).isoformat(timespec="microseconds")
    rendered = rendered.replace("+00:00", "Z")
    if "." in rendered:
        rendered = rendered.replace("Z", "").rstrip("0").rstrip(".") + "Z"
    if not RFC3339_UTC_PATTERN.fullmatch(rendered):
        raise BootstrapError("timestamp is outside the canonical RFC 3339 UTC format")
    return rendered


def _validate_environment_id(value: str) -> str:
    if not isinstance(value, str):
        raise BootstrapError("environment ID must be a string")
    try:
        encoded = value.encode("utf-8")
    except UnicodeError as exc:
        raise BootstrapError("environment ID must be valid UTF-8") from exc
    if not value or len(encoded) > 4096:
        raise BootstrapError("environment ID must be a non-empty opaque value")
    if any(ord(character) < 0x20 or ord(character) == 0x7F for character in value):
        raise BootstrapError("environment ID must not contain control characters")
    return value


def _validate_namespace(value: str) -> str:
    if not isinstance(value, str) or len(value) > 63 or not NAMESPACE_PATTERN.fullmatch(value):
        raise BootstrapError("target namespace must be a Kubernetes namespace name")
    return value


def _validate_source_ttl(source_ttl_seconds: int) -> int:
    if type(source_ttl_seconds) is not int or source_ttl_seconds <= 0:
        raise BootstrapError("source TTL must be a positive number of seconds")
    return source_ttl_seconds


def _random_identifier(byte_count: int) -> str:
    identifier = secrets.token_urlsafe(byte_count)
    if not KEY_ID_PATTERN.fullmatch(identifier):
        raise BootstrapError("secure random identifier did not match the source-record contract")
    return identifier


def _manifest() -> bytes:
    key_id = _random_identifier(16)
    bare_login_key = secrets.token_bytes(KEY_BYTES)
    connect_token_key = secrets.token_bytes(KEY_BYTES)
    while connect_token_key == bare_login_key:
        connect_token_key = secrets.token_bytes(KEY_BYTES)
    bare_login_encoded = base64.urlsafe_b64encode(bare_login_key).rstrip(b"=").decode("ascii")
    connect_token_encoded = base64.urlsafe_b64encode(connect_token_key).rstrip(b"=").decode("ascii")
    return (
        f"version=1\nactiveKeyId={key_id}\n"
        f"key:{key_id}:bare-login={bare_login_encoded}\n"
        f"key:{key_id}:connect-token={connect_token_encoded}\n"
    ).encode("ascii")


def _source_record_bytes(
    environment_id: str,
    namespace: str,
    source_ttl_seconds: int,
    now: dt.datetime,
    manifest: bytes,
    previous_source_generation: str | None,
    source_generation: str | None = None,
) -> bytes:
    environment_id = _validate_environment_id(environment_id)
    namespace = _validate_namespace(namespace)
    source_ttl_seconds = _validate_source_ttl(source_ttl_seconds)
    if now.tzinfo is None or now.utcoffset() is None:
        raise BootstrapError("bootstrap clock must be timezone-aware")
    current_time = now.astimezone(dt.timezone.utc)
    try:
        expiry = current_time + dt.timedelta(seconds=source_ttl_seconds)
    except OverflowError as exc:
        raise BootstrapError("source TTL is out of range") from exc
    created_at = format_timestamp(current_time)
    expires_at = format_timestamp(expiry)
    record = {
        "version": SOURCE_RECORD_VERSION,
        "manifestBase64": base64.b64encode(manifest).decode("ascii"),
        "sourceGeneration": source_generation or _random_identifier(32),
        "sourceExpiresAt": expires_at,
        "sourceCreatedAt": created_at,
        "environmentId": environment_id,
        "targetNamespace": namespace,
        "previousSourceGeneration": previous_source_generation,
    }
    return (json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )


def build_source_record(
    environment_id: str,
    namespace: str,
    source_ttl_seconds: int,
    now: dt.datetime | None = None,
) -> bytes:
    """Build one canonical initial source record without writing or printing it."""

    current_time = now or dt.datetime.now(dt.timezone.utc)
    return _source_record_bytes(
        environment_id,
        namespace,
        source_ttl_seconds,
        current_time,
        _manifest(),
        None,
    )


def _is_within(path: Path, directory: Path) -> bool:
    try:
        return os.path.commonpath((str(path), str(directory))) == str(directory)
    except ValueError:
        return False


def _repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _load_materializer():
    materializer_path = Path(__file__).with_name("materialize-account-response-envelope-ring.py")
    spec = importlib.util.spec_from_file_location(
        "account_response_envelope_materializer_for_bootstrap", materializer_path
    )
    if spec is None or spec.loader is None:
        raise BootstrapError("response-envelope materializer is unavailable")
    materializer = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = materializer
    try:
        spec.loader.exec_module(materializer)
    except (ImportError, OSError, SyntaxError) as exc:
        raise BootstrapError("response-envelope materializer is unavailable") from exc
    return materializer


def _read_previous_source_record(path: Path, repository_root: Path):
    if not path.is_absolute():
        raise BootstrapError("previous source record must be an absolute path")
    try:
        resolved_path = path.resolve(strict=True)
        resolved_repository = repository_root.resolve(strict=True)
        metadata = os.lstat(path)
        directory_metadata = os.lstat(path.parent)
    except OSError as exc:
        raise BootstrapError("previous source record is missing or unreadable") from exc
    if _is_within(resolved_path, resolved_repository):
        raise BootstrapError("previous source record must be outside the repository")
    if not stat.S_ISDIR(directory_metadata.st_mode) or stat.S_IMODE(directory_metadata.st_mode) != OWNER_DIRECTORY_MODE:
        raise BootstrapError("previous source directory must be owner-only mode 0700")
    if not hasattr(os, "getuid") or directory_metadata.st_uid != os.getuid():
        raise BootstrapError("previous source directory must be owned by the operator")
    if not stat.S_ISREG(metadata.st_mode) or stat.S_IMODE(metadata.st_mode) & 0o077:
        raise BootstrapError("previous source record must be a regular owner-only file")
    if metadata.st_uid != os.getuid():
        raise BootstrapError("previous source record must be owned by the operator")
    materializer = _load_materializer()
    try:
        previous = materializer.read_source_record(path)
    except materializer.MaterializationError as exc:
        raise BootstrapError("previous source record is invalid or unreadable") from exc
    return materializer, previous


def _rotation_manifest(materializer, previous_manifest: bytes) -> bytes:
    previous = materializer.parse_manifest(previous_manifest)
    previous_material = {
        key_material
        for purposes in previous.keys.values()
        for key_material in purposes.values()
    }
    previous_key_ids = set(previous.keys)
    while True:
        active_key_id = _random_identifier(16)
        if active_key_id not in previous_key_ids:
            break

    def fresh_key() -> bytes:
        while True:
            candidate = secrets.token_bytes(KEY_BYTES)
            if candidate not in previous_material:
                return candidate

    bare_login_key = fresh_key()
    connect_token_key = fresh_key()
    while connect_token_key == bare_login_key:
        connect_token_key = fresh_key()
    lines = ["version=1", f"activeKeyId={active_key_id}"]
    for key_id, purposes in previous.keys.items():
        for purpose in ("bare-login", "connect-token"):
            encoded = base64.urlsafe_b64encode(purposes[purpose]).rstrip(b"=").decode("ascii")
            lines.append(f"key:{key_id}:{purpose}={encoded}")
    for purpose, key_material in (
        ("bare-login", bare_login_key),
        ("connect-token", connect_token_key),
    ):
        encoded = base64.urlsafe_b64encode(key_material).rstrip(b"=").decode("ascii")
        lines.append(f"key:{active_key_id}:{purpose}={encoded}")
    return ("\n".join(lines) + "\n").encode("ascii")


def build_rotated_source_record(
    previous_source_record: Path,
    environment_id: str,
    namespace: str,
    source_ttl_seconds: int,
    now: dt.datetime | None = None,
    repository_root: Path | None = None,
) -> bytes:
    """Build a rotation record while retaining every prior decrypt-only key."""

    repository_root = repository_root or _repository_root()
    environment_id = _validate_environment_id(environment_id)
    namespace = _validate_namespace(namespace)
    source_ttl_seconds = _validate_source_ttl(source_ttl_seconds)
    materializer, previous = _read_previous_source_record(Path(previous_source_record), repository_root)
    if previous.environment_id != environment_id:
        raise BootstrapError("previous source record environment does not match the requested environment")
    if previous.target_namespace != namespace:
        raise BootstrapError("previous source record namespace does not match the requested namespace")
    current_time = now or dt.datetime.now(dt.timezone.utc)
    if current_time.tzinfo is None or current_time.utcoffset() is None:
        raise BootstrapError("bootstrap clock must be timezone-aware")
    current_time = current_time.astimezone(dt.timezone.utc)
    if current_time <= previous.source_created_at:
        raise BootstrapError("rotation source creation time must follow the previous source creation time")
    while True:
        source_generation = _random_identifier(32)
        if source_generation != previous.source_generation:
            break
    source_record = _source_record_bytes(
        environment_id,
        namespace,
        source_ttl_seconds,
        current_time,
        _rotation_manifest(materializer, previous.manifest_bytes),
        previous.source_generation,
        source_generation,
    )
    if len(source_record) > materializer.MAX_SOURCE_RECORD_BYTES:
        raise BootstrapError("rotated source record exceeds the materializer size limit")
    return source_record


def _open_owner_directory(output_path: Path, repository_root: Path) -> tuple[int, str]:
    if not output_path.is_absolute():
        raise BootstrapError("output must be an absolute path")
    try:
        os.lstat(output_path)
    except FileNotFoundError:
        pass
    except OSError as exc:
        raise BootstrapError("output path cannot be inspected") from exc
    else:
        raise BootstrapError("output already exists; refusing to overwrite or regenerate")
    try:
        resolved_output = output_path.resolve(strict=False)
        resolved_repository = repository_root.resolve(strict=True)
    except OSError as exc:
        raise BootstrapError("output path cannot be resolved") from exc
    if _is_within(resolved_output, resolved_repository):
        raise BootstrapError("output must be outside the repository")

    parent = resolved_output.parent
    output_name = resolved_output.name
    if not output_name or output_name in {".", ".."}:
        raise BootstrapError("output must name a file")
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        raise BootstrapError("platform cannot enforce no-follow output creation")
    directory_flag = getattr(os, "O_DIRECTORY", 0)
    try:
        directory_fd = os.open(parent, os.O_RDONLY | directory_flag | nofollow)
    except OSError as exc:
        raise BootstrapError("output parent directory must already exist") from exc
    try:
        metadata = os.fstat(directory_fd)
        if not stat.S_ISDIR(metadata.st_mode):
            raise BootstrapError("output parent must be a directory")
        if stat.S_IMODE(metadata.st_mode) != OWNER_DIRECTORY_MODE:
            raise BootstrapError("output parent directory must be owner-only mode 0700")
        if not hasattr(os, "getuid"):
            raise BootstrapError("platform cannot verify operator ownership")
        if metadata.st_uid != os.getuid():
            raise BootstrapError("output parent directory must be owned by the operator")
        try:
            os.stat(output_name, dir_fd=directory_fd, follow_symlinks=False)
        except FileNotFoundError:
            pass
        except OSError as exc:
            raise BootstrapError("output path cannot be inspected") from exc
        else:
            raise BootstrapError("output already exists; refusing to overwrite or regenerate")
        return directory_fd, output_name
    except Exception:
        os.close(directory_fd)
        raise


def _write_once(directory_fd: int, output_name: str, source_record: bytes) -> None:
    nofollow = getattr(os, "O_NOFOLLOW", None)
    if nofollow is None:
        raise BootstrapError("platform cannot enforce no-follow output creation")
    close_on_exec = getattr(os, "O_CLOEXEC", 0)
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | nofollow | close_on_exec
    output_fd: int | None = None
    try:
        try:
            output_fd = os.open(output_name, flags, OWNER_FILE_MODE, dir_fd=directory_fd)
        except FileExistsError as exc:
            raise BootstrapError("output already exists; refusing to overwrite or regenerate") from exc
        os.fchmod(output_fd, OWNER_FILE_MODE)
        view = memoryview(source_record)
        while view:
            written = os.write(output_fd, view)
            if written <= 0:
                raise OSError("source record write made no progress")
            view = view[written:]
        os.fsync(output_fd)
        metadata = os.fstat(output_fd)
        if stat.S_IMODE(metadata.st_mode) != OWNER_FILE_MODE:
            raise BootstrapError("created source record does not have owner-only mode 0600")
    finally:
        if output_fd is not None:
            os.close(output_fd)
    os.fsync(directory_fd)


def create_initial_source_record(
    output: Path,
    environment_id: str,
    namespace: str,
    source_ttl_seconds: int,
    now: dt.datetime | None = None,
    repository_root: Path | None = None,
) -> None:
    """Create an initial source record exactly once in protected operator custody."""

    output = Path(output)
    directory_fd, output_name = _open_owner_directory(output, repository_root or _repository_root())
    try:
        source_record = build_source_record(environment_id, namespace, source_ttl_seconds, now)
        _write_once(directory_fd, output_name, source_record)
    finally:
        os.close(directory_fd)


def create_rotated_source_record(
    output: Path,
    previous_source_record: Path,
    environment_id: str,
    namespace: str,
    source_ttl_seconds: int,
    now: dt.datetime | None = None,
    repository_root: Path | None = None,
) -> None:
    """Create one explicit rotation record without retiring any prior key."""

    repository_root = repository_root or _repository_root()
    directory_fd, output_name = _open_owner_directory(Path(output), repository_root)
    try:
        source_record = build_rotated_source_record(
            previous_source_record=previous_source_record,
            environment_id=environment_id,
            namespace=namespace,
            source_ttl_seconds=source_ttl_seconds,
            now=now,
            repository_root=repository_root,
        )
        _write_once(directory_fd, output_name, source_record)
    finally:
        os.close(directory_fd)


def _positive_seconds(value: str) -> int:
    try:
        parsed = int(value)
    except ValueError as exc:
        raise argparse.ArgumentTypeError("must be a positive integer") from exc
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def _parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", choices=("create", "rotate"), default=None)
    parser.add_argument("--output", required=True, type=Path, help="absolute owner-only custody output file")
    parser.add_argument("--environment-id", required=True)
    parser.add_argument("--namespace", required=True)
    parser.add_argument(
        "--previous-source-record",
        type=Path,
        help="protected prior source record; required only for explicit rotation",
    )
    parser.add_argument(
        "--source-ttl-seconds",
        required=True,
        type=_positive_seconds,
        help="approved credential-class lifetime for sourceExpiresAt",
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    try:
        args = _parse_args(argv)
        if args.action != "rotate" and args.previous_source_record is not None:
            raise BootstrapError("create action cannot specify a previous source record")
        if args.action == "rotate" and args.previous_source_record is None:
            raise BootstrapError("rotate action requires a previous source record")
        if args.action == "rotate":
            create_rotated_source_record(
                output=args.output,
                previous_source_record=args.previous_source_record,
                environment_id=args.environment_id,
                namespace=args.namespace,
                source_ttl_seconds=args.source_ttl_seconds,
            )
            success_message = "created rotated Account response-envelope source record"
        else:
            create_initial_source_record(
                output=args.output,
                environment_id=args.environment_id,
                namespace=args.namespace,
                source_ttl_seconds=args.source_ttl_seconds,
            )
            success_message = "created initial Account response-envelope source record"
    except BootstrapError as exc:
        print(f"bootstrap failed: {exc}", file=sys.stderr)
        return 1
    except (OSError, OverflowError, TypeError, ValueError):
        print("bootstrap failed: unable to create protected source record", file=sys.stderr)
        return 1
    print(success_message)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
