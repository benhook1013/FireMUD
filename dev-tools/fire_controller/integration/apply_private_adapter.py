#!/usr/bin/env python3
"""Apply or verify the narrow private status-site adapter against exact source fingerprints."""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import subprocess
import sys
from pathlib import Path

PATCH = Path(__file__).with_name("private-status-site.patch")
BASELINE = {
    "render.py": "8e66c84f5919cdbf9e2c77cafb8e6d1c1cf2ae333b20e172c433f66f8c42ab02",
    "server.py": "f882ad287c87d23c79a3f6fdda08aa0e10b89853a228a54b943b8575bff0c193",
    "publish-hetzner.py": "0fd388caf3a5919ff5e4242009e6fc26e9d95a4e39d0df76b09f79d7647416de",
    "render_progress.py": "4f9fe30c9d8d6ba0f433445625758e494af60d28069dc49d2fd056e3d39a2adb",
}
EXPECTED_FILES = frozenset(BASELINE)
UNCHANGED = {
    "shared.css": "cff6958f26f5b65dc4e14f63d43fd08fd8481dcdb830a9025190c85e51614596",
    "test_render.py": "831b29a35518fcfe60d0331444ad83f92f90f92b126f810fbcc5e810949bd549",
    "test_server.py": "21d1423f86281497b07a74e7e14d6e6564819f90df6bdc3015f227a4aa8643d9",
    "test_publish.py": "ac798bb369ec9aab76a6028a2185f0fd28c8ca681059f278ab668d52745a29c1",
}
POST_PATCH = {
    "render.py": "51f9a81bd921c1e6c3d864236859404b4c238e8ec2c34d540c3f60ba05e66f23",
    "server.py": "b53044ff02ef98508484689b590e18cef2f22087a4fb5a75cfb3c33af02ef9ce",
    "publish-hetzner.py": "6d3a4dad2de8cdccd760aaaa17843926cbbeb97db4b392d17227c6162dee4298",
    "render_progress.py": "d1f2b3bdc2862ba46166e8d30348920f0836e52eec1bdde28ed2378a0b0c47a2",
}


def fingerprint(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check_source(site: Path, expected: dict[str, str]) -> None:
    mismatches = []
    for name, digest in expected.items():
        source = site / name
        if not source.is_file():
            mismatches.append(f"{name}: missing")
            continue
        observed = fingerprint(source)
        if observed != digest:
            mismatches.append(f"{name}: expected {digest}, found {observed}")
    if mismatches:
        raise ValueError("website source fingerprint mismatch; refusing to apply or verify: "
                         + "; ".join(mismatches))


def check_patch_scope() -> None:
    content = PATCH.read_text(encoding="utf-8")
    files = set(re.findall(r"(?m)^diff --git a/([^\s]+) b/([^\s]+)$", content))
    normalized = {left for left, right in files if left == right}
    if normalized != EXPECTED_FILES or any(left != right for left, right in files):
        raise ValueError("adapter patch must modify only render.py, server.py, publish-hetzner.py, and render_progress.py")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-site", required=True, type=Path,
                        help="current private website source used for the fingerprint comparison")
    parser.add_argument("--site-copy", required=True, type=Path,
                        help="isolated copy of the private status website")
    parser.add_argument("--verify", action="store_true", help="verify the post-patch source fingerprints")
    args = parser.parse_args(argv)
    source = args.source_site.resolve()
    site = args.site_copy.resolve()
    if source == site:
        parser.error("refusing to modify the original private website; pass an isolated copy")
    if not source.is_dir():
        parser.error(f"website source does not exist: {source}")
    if not site.is_dir():
        parser.error(f"website copy does not exist: {site}")
    try:
        check_patch_scope()
        check_source(source, {**BASELINE, **UNCHANGED})
        if args.verify:
            check_source(site, {**POST_PATCH, **UNCHANGED})
            print("private adapter fingerprints verified")
            return 0
        check_source(site, {**BASELINE, **UNCHANGED})
        # The isolated copy can sit under another Git worktree. Avoid parent
        # discovery filtering our root-relative patch paths out silently.
        apply_environment = os.environ.copy()
        for variable in ("GIT_DIR", "GIT_COMMON_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE"):
            apply_environment.pop(variable, None)
        apply_environment["GIT_CEILING_DIRECTORIES"] = str(site.parent)
        checked = subprocess.run(
            ["git", "apply", "--check", str(PATCH)],
            cwd=site,
            env=apply_environment,
            capture_output=True,
            text=True,
            check=False,
        )
        if checked.returncode:
            raise ValueError(f"patch does not apply cleanly: {checked.stderr.strip()}")
        applied = subprocess.run(
            ["git", "apply", str(PATCH)],
            cwd=site,
            env=apply_environment,
            capture_output=True,
            text=True,
            check=False,
        )
        if applied.returncode:
            raise ValueError(f"patch application failed: {applied.stderr.strip()}")
        check_source(site, {**POST_PATCH, **UNCHANGED})
    except (OSError, ValueError) as error:
        print(f"private adapter: {error}", file=sys.stderr)
        return 2
    print("private adapter applied to isolated website copy")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
