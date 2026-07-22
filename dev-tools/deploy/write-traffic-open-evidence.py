#!/usr/bin/env python3

from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import subprocess
import sys
from pathlib import Path
from typing import Any, NoReturn


def fail(message: str) -> NoReturn:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def repo_root() -> Path:
    result = subprocess.run(
        ["git", "rev-parse", "--show-toplevel"], check=False, capture_output=True, text=True
    )
    if result.returncode != 0:
        fail(result.stderr.strip() or result.stdout.strip() or "Unable to resolve repository root")
    return Path(result.stdout.strip())


def utc_now() -> str:
    return (
        dt.datetime.now(dt.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z")
    )


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def resolve_path(root_dir: Path, ref: str) -> Path:
    path = Path(ref)
    return path if path.is_absolute() else root_dir / ref


def normalize_repo_ref(root_dir: Path, path: Path) -> str:
    try:
        return path.resolve().relative_to(root_dir.resolve()).as_posix()
    except ValueError:
        return str(path)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Write the canonical hobby traffic-open evidence projection."
    )
    parser.add_argument("environment", choices=["hobby-self-hosted"])
    parser.add_argument("deployment_ref")
    parser.add_argument("event_type", choices=["first-live", "reopen"])
    parser.add_argument("--assessed-by", required=True)
    parser.add_argument("--preflight-report", required=True)
    parser.add_argument("--evidence-ref", action="append", default=[])
    parser.add_argument("--output")

    parser.add_argument(
        "--backup-compliance-ref",
        default="design/operations/deployments/hobby-self-hosted/backup-compliance.yaml",
    )
    return parser.parse_args()


def validate_preflight_report(
    report: dict[str, Any], environment: str, deployment_ref: str, root_dir: Path
) -> None:
    preflight_path = root_dir / "dev-tools/deploy/preflight.py"
    spec = importlib.util.spec_from_file_location("firemud_preflight", preflight_path)
    if spec is None or spec.loader is None:
        fail(f"Unable to load canonical preflight validator: {preflight_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    expected_bindings_ref = f"design/operations/environments/{environment}/expected-bindings.yaml"
    status, message = module.validate_preflight_report(
        report,
        environment,
        expected_bindings_ref,
        deployment_ref,
    )
    if status != "pass":
        fail(message)
    if report.get("trafficOpenEvent") is not None:
        fail("Preflight report used to create traffic-open evidence must be the general pre-apply report")


def hobby_record(args: argparse.Namespace, root_dir: Path, preflight_ref: str) -> dict[str, Any]:
    compliance_path = resolve_path(root_dir, args.backup_compliance_ref)
    if not compliance_path.exists():
        fail(f"Hobby backup-compliance record not found: {args.backup_compliance_ref}")
    return {
        "schemaVersion": "traffic-open-record/v1",
        "environment": "hobby-self-hosted",
        "eventType": args.event_type,
        "deploymentRef": args.deployment_ref,
        "assessedAt": utc_now(),
        "assessedBy": args.assessed_by,
        "backupComplianceRef": normalize_repo_ref(root_dir, compliance_path),
        "preflightReportPath": preflight_ref,
        "evidenceRefs": args.evidence_ref,
    }


def default_output(root_dir: Path, args: argparse.Namespace) -> Path:
    if args.output:
        return resolve_path(root_dir, args.output)
    return (
        root_dir
        / "design/operations/deployments/hobby-self-hosted/traffic-open"
        / f"{args.deployment_ref}.json"
    )


def main() -> None:
    args = parse_args()
    root_dir = repo_root()
    preflight_path = resolve_path(root_dir, args.preflight_report)
    if not preflight_path.exists():
        fail(f"Preflight report not found: {args.preflight_report}")
    try:
        preflight_report = load_json(preflight_path)
    except Exception as exc:
        fail(f"Preflight report unreadable: {exc}")
    validate_preflight_report(preflight_report, args.environment, args.deployment_ref, root_dir)
    preflight_ref = normalize_repo_ref(root_dir, preflight_path)
    if not args.evidence_ref:
        fail("At least one --evidence-ref is required")

    record = hobby_record(args, root_dir, preflight_ref)
    output_path = default_output(root_dir, args)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(normalize_repo_ref(root_dir, output_path))


if __name__ == "__main__":
    main()
