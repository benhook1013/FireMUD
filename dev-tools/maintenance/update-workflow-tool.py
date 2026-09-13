#!/usr/bin/env python3
"""Update a downloaded workflow tool version together with its trusted checksum."""

from __future__ import annotations

import argparse
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
    "lychee": ("LYCHEE", "lychee-x86_64-unknown-linux-gnu.tar.gz", "https://github.com/lycheeverse/lychee/releases/download/lychee-v{v}/lychee-x86_64-unknown-linux-gnu.tar.gz.sha256"),
}
CHECKSUM_STEMS = {
    "HELM": "HELM_LINUX_AMD64", "GH": "GH_LINUX_AMD64", "BUF": "BUF_LINUX_X86_64",
    "KUBECONFORM": "KUBECONFORM_LINUX_AMD64", "VELERO": "VELERO_LINUX_AMD64",
    "LYCHEE": "LYCHEE_LINUX_X86_64",
}


def replace(text: str, key: str, value: str) -> str:
    updated, count = re.subn(rf"^{re.escape(key)}=.*$", f"{key}={value}", text, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"expected exactly one {key} assignment")
    return updated


def atomic_write(path: Path, text: str) -> None:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as handle:
        handle.write(text)
        temporary = Path(handle.name)
    os.replace(temporary, path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tool", choices=SPECS)
    parser.add_argument("version", help="exact three-part version, without v")
    parser.add_argument("--checksum-file", type=Path, help="offline checksum manifest")
    parser.add_argument("--authority", type=Path, default=Path("config/workflow-tool-versions.env"))
    parser.add_argument("--velero-manifest", type=Path, default=Path("k8s/velero/verify-backups-cronjob.yaml"))
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
        manifest = args.velero_manifest.read_text(encoding="utf-8")
        manifest, count = re.subn(r"image: velero/velero:v\d+\.\d+\.\d+", f"image: velero/velero:v{args.version}", manifest)
        if count != 1:
            raise SystemExit("expected one Velero image projection")
    atomic_write(args.authority, authority)
    if manifest is not None:
        atomic_write(args.velero_manifest, manifest)


if __name__ == "__main__":
    main()
