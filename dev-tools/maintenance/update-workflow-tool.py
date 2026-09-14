#!/usr/bin/env python3
"""Update a downloaded workflow tool version together with its trusted checksum."""

from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import stat
import tempfile
import urllib.request
from contextlib import contextmanager
from pathlib import Path

SPECS = {
    "kubectl": ("KUBECTL", "kubectl", "https://dl.k8s.io/release/v{v}/bin/linux/amd64/kubectl.sha256"),
    "helm": ("HELM", "helm-v{v}-linux-amd64.tar.gz", "https://get.helm.sh/helm-v{v}-linux-amd64.tar.gz.sha256sum"),
    "gh": ("GH", "gh_{v}_linux_amd64.tar.gz", "https://github.com/cli/cli/releases/download/v{v}/gh_{v}_checksums.txt"),
    "buf": ("BUF", "buf-Linux-x86_64", "https://github.com/bufbuild/buf/releases/download/v{v}/sha256.txt"),
    "kubeconform": ("KUBECONFORM", "kubeconform-linux-amd64.tar.gz", "https://github.com/yannh/kubeconform/releases/download/v{v}/CHECKSUMS"),
    "velero": ("VELERO", "velero-v{v}-linux-amd64.tar.gz", "https://github.com/vmware-tanzu/velero/releases/download/v{v}/CHECKSUM"),
    "lychee": ("LYCHEE", "lychee-x86_64-unknown-linux-musl.tar.gz", "https://github.com/lycheeverse/lychee/releases/download/lychee-v{v}/lychee-x86_64-unknown-linux-musl.tar.gz.sha256"),
}
CHECKSUM_STEMS = {
    "KUBECTL": "KUBECTL_LINUX_AMD64",
    "HELM": "HELM_LINUX_AMD64", "GH": "GH_LINUX_AMD64", "BUF": "BUF_LINUX_X86_64",
    "KUBECONFORM": "KUBECONFORM_LINUX_AMD64", "VELERO": "VELERO_LINUX_AMD64",
    "LYCHEE": "LYCHEE_LINUX_X86_64_MUSL",
}
RECOVERY_SCHEMA = "firemud-workflow-tool-update-recovery"
RECOVERY_VERSION = 1
RECOVERY_STATES = frozenset(("prepared", "committed"))


def replace(text: str, key: str, value: str) -> str:
    updated, count = re.subn(rf"^{re.escape(key)}=.*$", f"{key}={value}", text, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"expected exactly one {key} assignment")
    return updated


def staged_file(path: Path, text: str, *, preserve_mode: bool = False) -> Path:
    existing_mode = None
    if preserve_mode:
        try:
            existing_mode = stat.S_IMODE(path.stat().st_mode)
        except FileNotFoundError:
            pass
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        if existing_mode is not None:
            os.fchmod(handle.fileno(), existing_mode)
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())
        return Path(handle.name)


def fsync_directory(path: Path) -> None:
    """Make directory entry changes durable where the current platform permits it."""

    try:
        descriptor = os.open(path, os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


@contextmanager
def authority_lock(authority: Path):
    """Serialize updates with an exclusive lock on the authority parent directory."""

    descriptor = os.open(authority.resolve().parent, os.O_RDONLY)
    acquired = False
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        acquired = True
        yield
    finally:
        try:
            if acquired:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
        finally:
            os.close(descriptor)


def atomic_text_replace(path: Path, text: str) -> None:
    temporary = staged_file(path, text)
    try:
        os.replace(temporary, path)
        fsync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def recovery_journal_path(authority: Path) -> Path:
    resolved_authority = authority.resolve()
    return resolved_authority.with_name(f".{resolved_authority.name}.recovery.json")


def recovery_payload(state: str, originals: dict[Path, str]) -> str:
    return json.dumps(
        {
            "schema": RECOVERY_SCHEMA,
            "version": RECOVERY_VERSION,
            "state": state,
            "targets": [
                {"path": str(path), "contents": originals[path]}
                for path in sorted(originals, key=str)
            ],
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ) + "\n"


def _reject_duplicate_json_keys(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def read_recovery_journal(
    path: Path, allowed_target_sets: list[frozenset[Path]]
) -> tuple[str, dict[Path, str]]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_reject_duplicate_json_keys)
    except (OSError, UnicodeError, ValueError) as exc:
        raise OSError(f"invalid workflow updater recovery journal {path}") from exc

    if not isinstance(payload, dict) or set(payload) != {"schema", "version", "state", "targets"}:
        raise OSError(f"invalid workflow updater recovery journal {path}")
    if (
        payload["schema"] != RECOVERY_SCHEMA
        or isinstance(payload["version"], bool)
        or payload["version"] != RECOVERY_VERSION
    ):
        raise OSError(f"unsupported workflow updater recovery journal {path}")
    if not isinstance(payload["state"], str) or payload["state"] not in RECOVERY_STATES:
        raise OSError(f"invalid workflow updater recovery state in {path}")
    entries = payload["targets"]
    if not isinstance(entries, list) or not entries:
        raise OSError(f"invalid workflow updater recovery targets in {path}")

    originals: dict[Path, str] = {}
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"path", "contents"}:
            raise OSError(f"invalid workflow updater recovery target in {path}")
        target_text = entry["path"]
        contents = entry["contents"]
        if not isinstance(target_text, str) or not target_text:
            raise OSError(f"invalid workflow updater recovery target path in {path}")
        if not isinstance(contents, str):
            raise OSError(f"invalid workflow updater recovery contents in {path}")
        try:
            target = Path(target_text)
            resolved_target = target.resolve()
        except (OSError, RuntimeError, ValueError) as exc:
            raise OSError(f"invalid workflow updater recovery target path in {path}") from exc
        if not target.is_absolute() or target != resolved_target or target in originals:
            raise OSError(f"invalid workflow updater recovery target path in {path}")
        originals[target] = contents

    resolved_allowed = {
        frozenset(target.resolve() for target in target_set) for target_set in allowed_target_sets
    }
    if frozenset(originals) not in resolved_allowed:
        raise OSError(f"workflow updater recovery target set does not match transaction in {path}")
    return payload["state"], originals


def remove_recovery_journal(path: Path) -> None:
    path.unlink(missing_ok=True)
    fsync_directory(path.parent)


def restore_recovery_journal(path: Path, allowed_target_sets: list[frozenset[Path]]) -> None:
    state, originals = read_recovery_journal(path, allowed_target_sets)
    if state == "committed":
        remove_recovery_journal(path)
        return

    staged: dict[Path, Path] = {}
    try:
        for target in sorted(originals, key=str):
            staged[target] = staged_file(target, originals[target], preserve_mode=True)
        for target in sorted(staged, key=str, reverse=True):
            os.replace(staged[target], target)
            fsync_directory(target.parent)
        remove_recovery_journal(path)
    finally:
        for temporary in staged.values():
            temporary.unlink(missing_ok=True)


def reconcile_recovery_journal(authority: Path, allowed_target_sets: list[frozenset[Path]]) -> None:
    journal = recovery_journal_path(authority)
    if os.path.lexists(journal):
        try:
            journal_mode = journal.lstat().st_mode
        except OSError as exc:
            raise OSError(f"could not inspect workflow updater recovery journal {journal}") from exc
        if not stat.S_ISREG(journal_mode):
            raise OSError(f"workflow updater recovery journal is not a regular file: {journal}")
        restore_recovery_journal(journal, allowed_target_sets)


def transactional_write(updates: list[tuple[Path, str]], authority: Path | None = None) -> None:
    if not updates:
        raise ValueError("workflow updater transaction requires at least one target")
    resolved_updates = [(path.resolve(), text) for path, text in updates]
    authority_path = (authority or updates[0][0]).resolve()
    expected_targets = [path for path, _ in resolved_updates]
    if len(set(expected_targets)) != len(expected_targets):
        raise ValueError("workflow updater transaction cannot contain duplicate targets")
    if authority_path not in expected_targets:
        raise ValueError("workflow updater recovery authority must be a transaction target")
    reconcile_recovery_journal(authority_path, [frozenset(expected_targets)])
    originals = {path: path.read_text(encoding="utf-8") for path, _ in resolved_updates}
    journal = recovery_journal_path(authority_path)
    staged = {path: staged_file(path, text, preserve_mode=True) for path, text in resolved_updates}
    replaced: list[Path] = []
    try:
        atomic_text_replace(journal, recovery_payload("prepared", originals))
        for path, _ in resolved_updates:
            os.replace(staged[path], path)
            replaced.append(path)
            fsync_directory(path.parent)
    except OSError as replacement_error:
        try:
            for path in reversed(replaced):
                rollback = staged_file(path, originals[path], preserve_mode=True)
                try:
                    os.replace(rollback, path)
                    fsync_directory(path.parent)
                finally:
                    rollback.unlink(missing_ok=True)
        except OSError:
            raise
        remove_recovery_journal(journal)
        raise replacement_error
    else:
        atomic_text_replace(journal, recovery_payload("committed", originals))
        remove_recovery_journal(journal)
    finally:
        for temporary in staged.values():
            temporary.unlink(missing_ok=True)


def dockerhub_digest(repository: str, tag: str) -> str:
    token_url = (
        "https://auth.docker.io/token?service=registry.docker.io&scope="
        f"repository:{repository}:pull"
    )
    with urllib.request.urlopen(token_url, timeout=30) as response:
        token = json.load(response)["token"]
    request = urllib.request.Request(
        f"https://registry-1.docker.io/v2/{repository}/manifests/{tag}",
        method="HEAD",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json",
        },
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        digest = response.headers.get("Docker-Content-Digest", "")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise SystemExit("registry did not return a valid Velero image digest")
    return digest


def velero_image_digest_from_evidence(path: Path, version: str) -> str:
    try:
        evidence = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise SystemExit(f"could not read Velero image evidence file {path}: {exc}") from exc

    lines = evidence.splitlines()
    if len(lines) != 1 or not lines[0].strip():
        raise SystemExit("Velero image evidence must contain exactly one immutable image reference")

    reference = lines[0].strip()
    match = re.fullmatch(
        r"(?P<repository>[^:\s]+/[^:\s]+):(?P<tag>[^@\s]+)@(?P<digest>sha256:[0-9a-f]{64})",
        reference,
    )
    if match is None:
        raise SystemExit(
            "Velero image evidence must be a full immutable image reference with a lowercase SHA-256"
        )
    if match.group("repository") != "velero/velero":
        raise SystemExit("Velero image evidence must reference the exact repository velero/velero")
    if match.group("tag") != f"v{version}":
        raise SystemExit(f"Velero image evidence must reference the exact tag v{version}")
    return match.group("digest")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tool", choices=SPECS)
    parser.add_argument("version", help="exact three-part version, without v")
    parser.add_argument("--checksum-file", type=Path, help="offline checksum manifest")
    parser.add_argument("--authority", type=Path, default=Path("config/workflow-tool-versions.env"))
    parser.add_argument("--velero-manifest", type=Path, default=Path("k8s/velero/verify-backups-cronjob.yaml"))
    parser.add_argument(
        "--image-evidence-file",
        type=Path,
        help="offline Velero image evidence containing one full immutable image reference",
    )
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        parser.error("version must have exactly three numeric parts")
    if args.image_evidence_file and args.tool != "velero":
        parser.error("--image-evidence-file is only valid for velero")

    resolved_authority = args.authority.resolve()
    resolved_velero_manifest = args.velero_manifest.resolve()
    allowed_target_sets = [
        frozenset((resolved_authority,)),
        frozenset((resolved_authority, resolved_velero_manifest)),
    ]
    with authority_lock(args.authority):
        reconcile_recovery_journal(args.authority, allowed_target_sets)

    image_digest = None
    if args.tool == "velero" and args.image_evidence_file:
        image_digest = velero_image_digest_from_evidence(args.image_evidence_file, args.version)

    prefix, asset_template, url_template = SPECS[args.tool]
    asset = asset_template.format(v=args.version)
    if args.checksum_file:
        checksum_text = args.checksum_file.read_text(encoding="utf-8")
    else:
        with urllib.request.urlopen(url_template.format(v=args.version), timeout=30) as response:
            checksum_text = response.read().decode("utf-8")
    if args.tool == "kubectl":
        checksum_lines = checksum_text.splitlines()
        matches = (
            [checksum_lines[0].strip()]
            if len(checksum_lines) == 1 and re.fullmatch(r"[0-9a-f]{64}", checksum_lines[0].strip())
            else []
        )
    else:
        matches = re.findall(rf"(?m)^([0-9a-f]{{64}})\s+\*?{re.escape(asset)}$", checksum_text)
    if len(matches) != 1:
        raise SystemExit(f"could not identify exactly one checksum for {asset}")

    if args.tool == "velero" and image_digest is None:
        image_digest = dockerhub_digest("velero/velero", f"v{args.version}")

    with authority_lock(args.authority):
        reconcile_recovery_journal(args.authority, allowed_target_sets)

        authority = args.authority.read_text(encoding="utf-8")
        authority = replace(authority, f"{prefix}_VERSION", args.version)
        stem = CHECKSUM_STEMS[prefix]
        authority = replace(authority, f"{stem}_CHECKSUM_VERSION", args.version)
        authority = replace(authority, f"{stem}_SHA256", matches[0])
        manifest = None
        if args.tool == "velero":
            if image_digest is None:
                raise SystemExit("Velero image digest could not be resolved")
            authority = replace(authority, "VELERO_IMAGE_DIGEST", image_digest)
            manifest = args.velero_manifest.read_text(encoding="utf-8")
            manifest, count = re.subn(
                r"image: velero/velero:v\d+\.\d+\.\d+(?:@sha256:[0-9a-f]{64})?",
                f"image: velero/velero:v{args.version}@{image_digest}",
                manifest,
            )
            if count != 1:
                raise SystemExit("expected one Velero image projection")
        updates = [(args.authority, authority)]
        if manifest is not None:
            updates.append((args.velero_manifest, manifest))
        transactional_write(updates, authority=args.authority)


if __name__ == "__main__":
    main()
