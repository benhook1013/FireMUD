#!/usr/bin/env python3

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


NOTICE_FILES = {
    "NOTICE_DEFAULT": "THIRD_PARTY_NOTICES.txt",
    "NOTICE_SUMMARY": "THIRD_PARTY_NOTICE_SUMMARY.txt",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Assemble a release /licenses directory from ORT reporter output.",
    )
    parser.add_argument(
        "--ort-results",
        required=True,
        type=Path,
        help="Path to the ORT results directory",
    )
    parser.add_argument(
        "--output-dir",
        required=True,
        type=Path,
        help="Path to the /licenses directory to create",
    )
    return parser.parse_args()


def find_required_notice(ort_results: Path, notice_name: str) -> Path:
    matches = sorted(ort_results.rglob(notice_name))
    if not matches:
        raise FileNotFoundError(
            f"Required ORT notice report '{notice_name}' was not found under {ort_results}",
        )
    return matches[0]


def main() -> None:
    args = parse_args()
    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    for ort_name, output_name in NOTICE_FILES.items():
        source = find_required_notice(args.ort_results, ort_name)
        shutil.copyfile(source, output_dir / output_name)


if __name__ == "__main__":
    main()
