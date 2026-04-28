#!/usr/bin/env python3
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

LEGACY_PATH = ("grpc", "server")
SERVICE_CONFIG_GLOB = "services/*/src/main/resources/application*.yml"
ALLOWLIST_RELATIVE = Path("config/grpc/legacy-server-tls-allowlist.txt")


def load_allowlist(repo_root: Path) -> set[str]:
    allowlist_path = repo_root / ALLOWLIST_RELATIVE
    if not allowlist_path.is_file():
        return set()

    entries: set[str] = set()
    for raw in allowlist_path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            entries.add(line)
    return entries


def discover_legacy_files(repo_root: Path) -> list[Path]:
    legacy_files: list[Path] = []
    for path in sorted(repo_root.glob(SERVICE_CONFIG_GLOB)):
        if contains_legacy_path(path):
            legacy_files.append(path.relative_to(repo_root))
    return legacy_files


def contains_legacy_path(path: Path) -> bool:
    stack: list[tuple[int, str]] = []
    in_legacy_block = False
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        stripped = raw_line.split("#", 1)[0].rstrip()
        if not stripped:
            continue
        if stripped.lstrip().startswith("- "):
            continue

        indent = len(raw_line) - len(raw_line.lstrip(" "))
        while stack and indent <= stack[-1][0]:
            stack.pop()
        current_path = [key for _, key in stack]
        key = stripped.split(":", 1)[0].strip()

        if key == "server" and current_path[-1:] == ["grpc"] and current_path[-2:] != ["spring", "grpc"]:
            in_legacy_block = True
            break

        if stripped.endswith(":"):
            stack.append((indent, key))

    return in_legacy_block


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Check FireMUD gRPC transport configuration for legacy server TLS property usage."
        )
    )
    parser.add_argument(
        "--root",
        default=Path(os.environ.get("FIREMUD_REPO_ROOT", Path(__file__).resolve().parents[2])),
        type=Path,
        help="Repository root to scan. Defaults to the FireMUD checkout root.",
    )
    parser.add_argument(
        "--enforce",
        action="store_true",
        help="Fail on any legacy server TLS property usage, even if it appears in the temporary allowlist.",
    )
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="Run a small built-in validation suite against temporary fixtures.",
    )
    return parser.parse_args()


def run_self_test() -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        allowlist = repo_root / ALLOWLIST_RELATIVE
        allowlist.parent.mkdir(parents=True, exist_ok=True)
        allowlist.write_text(
            "services/allowed/src/main/resources/application.yml\n",
            encoding="utf-8",
        )
        allowed = repo_root / "services/allowed/src/main/resources/application.yml"
        allowed.parent.mkdir(parents=True, exist_ok=True)
        allowed.write_text(
            "spring:\n  grpc:\n    server:\n      ssl:\n        bundle: firemud-grpc\n",
            encoding="utf-8",
        )
        legacy = repo_root / "services/legacy/src/main/resources/application.yml"
        legacy.parent.mkdir(parents=True, exist_ok=True)
        legacy.write_text(
            "grpc:\n  server:\n    ssl:\n      bundle: firemud-grpc\n",
            encoding="utf-8",
        )
        assert discover_legacy_files(repo_root) == [legacy.relative_to(repo_root)]

    with tempfile.TemporaryDirectory() as tmp:
        repo_root = Path(tmp)
        deny = repo_root / "services/deny/src/main/resources/application.yml"
        deny.parent.mkdir(parents=True, exist_ok=True)
        deny.write_text(
            "spring:\n  grpc:\n    server:\n      ssl:\n        bundle: firemud-grpc\n",
            encoding="utf-8",
        )
        assert discover_legacy_files(repo_root) == []


def main() -> int:
    args = parse_args()
    if args.self_test:
        run_self_test()
        print("gRPC transport config guard self-test passed.")
        return 0

    repo_root = args.root.resolve()
    allowlist = load_allowlist(repo_root)
    legacy_files = discover_legacy_files(repo_root)

    if args.enforce:
        offenders = legacy_files
        allowlisted = []
    else:
        offenders = [path for path in legacy_files if path.as_posix() not in allowlist]
        allowlisted = [path for path in legacy_files if path.as_posix() in allowlist]

    if offenders:
        print("gRPC transport configuration sanity check failed:", file=sys.stderr)
        for path in offenders:
            print(f"- legacy server TLS config present in {path.as_posix()}", file=sys.stderr)
        if allowlisted:
            print(
                "Note: the following legacy files are still temporarily allowlisted and should be migrated next:",
                file=sys.stderr,
            )
            for path in allowlisted:
                print(f"- {path.as_posix()}", file=sys.stderr)
        return 1

    if allowlisted:
        print(
            f"gRPC transport config guard passed in staged mode: {len(allowlisted)} temporary allowlisted file(s) still use the legacy pattern."
        )
    else:
        print("gRPC transport config guard passed: no legacy server TLS config remains.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
