#!/usr/bin/env python3

from __future__ import annotations

import argparse
import datetime as dt
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
        description="Write canonical traffic-open evidence records for production or hobby."
    )
    parser.add_argument("environment", choices=["production", "hobby-self-hosted"])
    parser.add_argument("deployment_ref")
    parser.add_argument("event_type", choices=["first-live", "reopen"])
    parser.add_argument("--assessed-by", required=True)
    parser.add_argument("--preflight-report", required=True)
    parser.add_argument("--evidence-ref", action="append", default=[])
    parser.add_argument("--output")

    parser.add_argument("--backup-storage-binding")
    parser.add_argument("--backup-coverage", default="environment-wide-postgresql")
    parser.add_argument("--backup-artifact-ref")
    parser.add_argument("--backup-last-success-at")
    parser.add_argument("--backup-verify-last-success-at")
    parser.add_argument("--restore-drill-last-success-at")
    parser.add_argument("--backup-tool-digest")
    parser.add_argument("--recovery-tool-digest")
    parser.add_argument("--recovery-contract-fingerprint")
    parser.add_argument("--backup-readiness-ref")
    parser.add_argument("--baseline-recovery-record-ref")
    parser.add_argument("--actual-recovery-record-ref")
    parser.add_argument("--source-environment-binding")
    parser.add_argument("--drill-target-boundary")
    parser.add_argument("--player-facing-target-boundary")
    parser.add_argument("--traffic-exposure", default="isolated-drill")
    parser.add_argument("--traffic-opened-at")

    parser.add_argument(
        "--backup-compliance-ref",
        default="design/operations/deployments/hobby-self-hosted/backup-compliance.yaml",
    )
    return parser.parse_args()


def validate_preflight_report(
    report: dict[str, Any], environment: str, deployment_ref: str
) -> None:
    if report.get("environment") != environment:
        fail(f"Preflight report must target {environment}")
    expected_bindings_ref = (
        f"design/operations/environments/{environment}/expected-bindings.yaml"
    )
    if report.get("expectedBindingsRef") != expected_bindings_ref:
        fail("Preflight report expectedBindingsRef mismatch")
    deployment_ref_obj = report.get("deploymentRef", {})
    if not isinstance(deployment_ref_obj, dict):
        fail("Preflight report deploymentRef must be an object")
    report_ref = (
        str(deployment_ref_obj.get("overlayCommitSha", ""))
        if environment == "production"
        else str(deployment_ref_obj.get("manifestRef", ""))
    )
    if report_ref != deployment_ref:
        fail("Preflight report deploymentRef mismatch")
    check_results = report.get("checkResults")
    if not isinstance(check_results, list) or not check_results:
        fail("Preflight report missing checkResults")
    required_failures = [
        str(check.get("policyId"))
        for check in check_results
        if isinstance(check, dict)
        and check.get("status") == "fail"
        and check.get("policyId") != "PREFLIGHT-DIGEST-002"
    ]
    if required_failures:
        fail("Preflight report contains failing required checks: " + ", ".join(required_failures))


def production_record(args: argparse.Namespace, root_dir: Path, preflight_ref: str) -> dict[str, Any]:
    required = {
        "--backup-storage-binding": args.backup_storage_binding,
        "--backup-artifact-ref": args.backup_artifact_ref,
        "--backup-last-success-at": args.backup_last_success_at,
        "--backup-verify-last-success-at": args.backup_verify_last_success_at,
        "--restore-drill-last-success-at": args.restore_drill_last_success_at,
        "--backup-tool-digest": args.backup_tool_digest,
        "--recovery-tool-digest": args.recovery_tool_digest,
        "--recovery-contract-fingerprint": args.recovery_contract_fingerprint,
        "--backup-readiness-ref": args.backup_readiness_ref,
        "--baseline-recovery-record-ref": args.baseline_recovery_record_ref,
        "--source-environment-binding": args.source_environment_binding,
        "--drill-target-boundary": args.drill_target_boundary,
        "--traffic-opened-at": args.traffic_opened_at,
    }
    if args.event_type == "reopen":
        required["--actual-recovery-record-ref"] = args.actual_recovery_record_ref
        required["--player-facing-target-boundary"] = args.player_facing_target_boundary
    missing = [flag for flag, value in required.items() if not value]
    if missing:
        fail("Missing required production arguments: " + ", ".join(missing))
    if args.backup_coverage != "environment-wide-postgresql":
        fail("Production --backup-coverage must be environment-wide-postgresql")
    if args.traffic_exposure != "isolated-drill":
        fail("Production --traffic-exposure must be isolated-drill")
    return {
        "schemaVersion": "traffic-open-record/v1",
        "environment": "production",
        "eventType": args.event_type,
        "deploymentRef": args.deployment_ref,
        "trafficOpenStatus": "finalized",
        "assessedAt": utc_now(),
        "assessedBy": args.assessed_by,
        "preflightReportPath": preflight_ref,
        "backupStorageBinding": args.backup_storage_binding,
        "backupLastSuccessAt": args.backup_last_success_at,
        "backupVerifyLastSuccessAt": args.backup_verify_last_success_at,
        "restoreDrillLastSuccessAt": args.restore_drill_last_success_at,
        "backupReadinessRef": args.backup_readiness_ref,
        "baselineRecoveryRecordRef": args.baseline_recovery_record_ref,
        "backupCoverage": args.backup_coverage,
        "backupArtifactRef": args.backup_artifact_ref,
        "backupToolDigest": args.backup_tool_digest,
        "recoveryToolDigest": args.recovery_tool_digest,
        "recoveryContractFingerprint": args.recovery_contract_fingerprint,
        "sourceEnvironmentBinding": args.source_environment_binding,
        "drillTargetBoundary": args.drill_target_boundary,
        "trafficExposure": args.traffic_exposure,
        "trafficOpenedAt": args.traffic_opened_at,
        "evidenceRefs": args.evidence_ref,
        **(
            {
                "actualRecoveryRecordRef": args.actual_recovery_record_ref,
                "playerFacingTargetBoundary": args.player_facing_target_boundary,
            }
            if args.event_type == "reopen"
            else {}
        ),
    }


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
    if args.environment == "production":
        return (
            root_dir
            / "design/operations/deployments/production/traffic-open"
            / f"{args.event_type}-{args.deployment_ref}.json"
        )
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
    validate_preflight_report(preflight_report, args.environment, args.deployment_ref)
    preflight_ref = normalize_repo_ref(root_dir, preflight_path)
    if not args.evidence_ref:
        fail("At least one --evidence-ref is required")

    record = (
        production_record(args, root_dir, preflight_ref)
        if args.environment == "production"
        else hobby_record(args, root_dir, preflight_ref)
    )
    output_path = default_output(root_dir, args)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(normalize_repo_ref(root_dir, output_path))


if __name__ == "__main__":
    main()
