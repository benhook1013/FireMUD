#!/usr/bin/env python3
"""Create the one-time initial Account response-envelope ring source record.

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


def build_source_record(
    environment_id: str,
    namespace: str,
    source_ttl_seconds: int,
    now: dt.datetime | None = None,
) -> bytes:
    """Build one canonical initial source record without writing or printing it."""

    environment_id = _validate_environment_id(environment_id)
    namespace = _validate_namespace(namespace)
    source_ttl_seconds = _validate_source_ttl(source_ttl_seconds)
    current_time = now or dt.datetime.now(dt.timezone.utc)
    if current_time.tzinfo is None or current_time.utcoffset() is None:
        raise BootstrapError("bootstrap clock must be timezone-aware")
    current_time = current_time.astimezone(dt.timezone.utc)
    try:
        expiry = current_time + dt.timedelta(seconds=source_ttl_seconds)
    except OverflowError as exc:
        raise BootstrapError("source TTL is out of range") from exc
    created_at = format_timestamp(current_time)
    expires_at = format_timestamp(expiry)
    record = {
        "version": SOURCE_RECORD_VERSION,
        "manifestBase64": base64.b64encode(_manifest()).decode("ascii"),
        "sourceGeneration": _random_identifier(32),
        "sourceExpiresAt": expires_at,
        "sourceCreatedAt": created_at,
        "environmentId": environment_id,
        "targetNamespace": namespace,
        "previousSourceGeneration": None,
    }
    return (json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )


def _is_within(path: Path, directory: Path) -> bool:
    try:
        return os.path.commonpath((str(path), str(directory))) == str(directory)
    except ValueError:
        return False


def _repository_root() -> Path:
    return Path(__file__).resolve().parents[2]


def _open_owner_directory(output_path: Path, repository_root: Path) -> tuple[int, str]:
    if not output_path.is_absolute():
        raise BootstrapError("output must be an absolute path")
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
    parser.add_argument("--output", required=True, type=Path, help="absolute owner-only custody output file")
    parser.add_argument("--environment-id", required=True)
    parser.add_argument("--namespace", required=True)
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
        create_initial_source_record(
            output=args.output,
            environment_id=args.environment_id,
            namespace=args.namespace,
            source_ttl_seconds=args.source_ttl_seconds,
        )
    except BootstrapError as exc:
        print(f"bootstrap failed: {exc}", file=sys.stderr)
        return 1
    except (OSError, OverflowError, TypeError, ValueError):
        print("bootstrap failed: unable to create protected source record", file=sys.stderr)
        return 1
    print("created initial Account response-envelope source record")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
