#!/usr/bin/env python3
"""Update a downloaded workflow tool version together with its trusted checksum."""

from __future__ import annotations

import argparse
import json
import os
import re
import tempfile
import urllib.request
from pathlib import Path

SPECS = {
    "helm": ("HELM", "helm-v{v}-linux-amd64.tar.gz", "https://get.helm.sh/helm-v{v}-linux-amd64.tar.gz.sha256sum"),
    "gh": ("GH", "gh_{v}_linux_amd64.tar.gz", "https://github.com/cli/cli/releases/download/v{v}/gh_{v}_checksums.txt"),
    "buf": ("BUF", "buf-Linux-x86_64", "https://github.com/bufbuild/buf/releases/download/v{v}/sha256.txt"),
    "kubeconform": ("KUBECONFORM", "kubeconform-linux-amd64.tar.gz", "https://github.com/yannh/kubeconform/releases/download/v{v}/CHECKSUMS"),
    "velero": ("VELERO", "velero-v{v}-linux-amd64.tar.gz", "https://github.com/vmware-tanzu/velero/releases/download/v{v}/CHECKSUM"),
    "lychee": ("LYCHEE", "lychee-x86_64-unknown-linux-musl.tar.gz", "https://github.com/lycheeverse/lychee/releases/download/lychee-v{v}/lychee-x86_64-unknown-linux-musl.tar.gz.sha256"),
}
CHECKSUM_STEMS = {
    "HELM": "HELM_LINUX_AMD64", "GH": "GH_LINUX_AMD64", "BUF": "BUF_LINUX_X86_64",
    "KUBECONFORM": "KUBECONFORM_LINUX_AMD64", "VELERO": "VELERO_LINUX_AMD64",
    "LYCHEE": "LYCHEE_LINUX_X86_64_MUSL",
}


def replace(text: str, key: str, value: str) -> str:
    updated, count = re.subn(rf"^{re.escape(key)}=.*$", f"{key}={value}", text, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"expected exactly one {key} assignment")
    return updated


def staged_file(path: Path, text: str) -> Path:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())
        return Path(handle.name)


def transactional_write(updates: list[tuple[Path, str]]) -> None:
    originals = {path: path.read_text(encoding="utf-8") for path, _ in updates}
    staged = {path: staged_file(path, text) for path, text in updates}
    replaced: list[Path] = []
    try:
        for path, _ in updates:
            os.replace(staged[path], path)
            replaced.append(path)
    except OSError:
        for path in reversed(replaced):
            rollback = staged_file(path, originals[path])
            os.replace(rollback, path)
        raise
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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tool", choices=SPECS)
    parser.add_argument("version", help="exact three-part version, without v")
    parser.add_argument("--checksum-file", type=Path, help="offline checksum manifest")
    parser.add_argument("--authority", type=Path, default=Path("config/workflow-tool-versions.env"))
    parser.add_argument("--velero-manifest", type=Path, default=Path("k8s/velero/verify-backups-cronjob.yaml"))
    parser.add_argument("--image-digest", help="verified Velero image digest (offline/test override)")
    args = parser.parse_args()
    if not re.fullmatch(r"\d+\.\d+\.\d+", args.version):
        parser.error("version must have exactly three numeric parts")

    prefix, asset_template, url_template = SPECS[args.tool]
    asset = asset_template.format(v=args.version)
    if args.checksum_file:
        checksum_text = args.checksum_file.read_text(encoding="utf-8")
    else:
        with urllib.request.urlopen(url_template.format(v=args.version), timeout=30) as response:
            checksum_text = response.read().decode("utf-8")
    matches = re.findall(rf"(?m)^([0-9a-f]{{64}})\s+\*?{re.escape(asset)}$", checksum_text)
    if len(matches) != 1:
        raise SystemExit(f"could not identify exactly one checksum for {asset}")

    authority = args.authority.read_text(encoding="utf-8")
    authority = replace(authority, f"{prefix}_VERSION", args.version)
    stem = CHECKSUM_STEMS[prefix]
    authority = replace(authority, f"{stem}_CHECKSUM_VERSION", args.version)
    authority = replace(authority, f"{stem}_SHA256", matches[0])
    manifest = None
    if args.tool == "velero":
        image_digest = args.image_digest or dockerhub_digest("velero/velero", f"v{args.version}")
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_digest):
            raise SystemExit("Velero image digest must be a lowercase SHA-256")
        authority = replace(authority, "VELERO_IMAGE_DIGEST", image_digest)
        manifest = args.velero_manifest.read_text(encoding="utf-8")
        manifest, count = re.subn(
            r"image: velero/velero:v\d+\.\d+\.\d+@sha256:[0-9a-f]{64}",
            f"image: velero/velero:v{args.version}@{image_digest}",
            manifest,
        )
        if count != 1:
            raise SystemExit("expected one Velero image projection")
    updates = [(args.authority, authority)]
    if manifest is not None:
        updates.append((args.velero_manifest, manifest))
    transactional_write(updates)


if __name__ == "__main__":
    main()
