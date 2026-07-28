#!/usr/bin/env python3

from __future__ import annotations

import datetime as dt
import json
import os
import re
import subprocess
import sys
import uuid
from dataclasses import dataclass
from itertools import islice
from pathlib import Path
from typing import Any, NoReturn

import yaml


USAGE = """Usage: preflight.py <staging|production|hobby-self-hosted>

Environment variables:
  FIREMUD_PREFLIGHT_CONTEXT          Context for applicability (default: operator)
                                     allowed: operator, ci-static
  FIREMUD_PREFLIGHT_OUTPUT           Optional output report path
  FIREMUD_DEPLOYMENT_REF             Optional deployment ref token for report naming
  FIREMUD_PREFLIGHT_RENDER_PATH      Required for hobby-self-hosted; explicit manifest/render path
  FIREMUD_PREFLIGHT_WAIVER           Reserved waiver JSON path; execution is blocked until
                                     one-time consumption authority is implemented
  FIREMUD_PROMOTION_ATTESTATION      Required in operator production context; path to attestation JSON
  FIREMUD_BACKUP_READINESS_EVIDENCE  Required for production roll-forward-only promotions; path to backup-readiness JSON
  FIREMUD_TRAFFIC_OPEN_EVENT         Optional traffic-open gate: first-live or reopen
"""


@dataclass
class CheckResult:
    policy_id: str
    required: bool
    status: str
    message: str


RECOVERY_COMPATIBILITY_STATUSES = {"compatible", "drill_required", "incompatible"}
SAFE_RECOVERY_DISPOSITIONS = {
    "converged",
    "terminalized",
    "invalidated",
}
NON_QUALIFYING_RECOVERY_DISPOSITIONS = {"fenced_disabled_backlog_retained"}

# These are the policy results emitted by this executable. The two JWT policies
# documented as target-state-only are deliberately not included until they are
# implemented and emitted by every applicable run.
EXPECTED_PREFLIGHT_POLICY_IDS = (
    "PREFLIGHT-DIGEST-001",
    "PREFLIGHT-DIGEST-002",
    "PREFLIGHT-SECRETS-001",
    "PREFLIGHT-SECRETS-002",
    "PREFLIGHT-JWT-001",
    "PREFLIGHT-JWKS-001",
    "PREFLIGHT-BRIDGE-001",
    "PREFLIGHT-REDIS-001",
    "PREFLIGHT-BOOTSTRAP-001",
    "PREFLIGHT-EXTERNAL-001",
    "PREFLIGHT-SERVICES-001",
    "PREFLIGHT-PROMOTION-001",
    "PREFLIGHT-BACKUP-001",
    "PREFLIGHT-BACKUP-002",
    "PREFLIGHT-BACKUP-003",
)
EXPECTED_PREFLIGHT_POLICY_ID_SET = set(EXPECTED_PREFLIGHT_POLICY_IDS)

COMMON_REQUIRED_PREFLIGHT_POLICY_IDS = {
    "PREFLIGHT-SECRETS-001",
    "PREFLIGHT-SECRETS-002",
    "PREFLIGHT-JWT-001",
    "PREFLIGHT-JWKS-001",
    "PREFLIGHT-BRIDGE-001",
    "PREFLIGHT-REDIS-001",
    "PREFLIGHT-BOOTSTRAP-001",
    "PREFLIGHT-EXTERNAL-001",
    "PREFLIGHT-SERVICES-001",
}

PREFLIGHT_APPLY_MAX_AGE = dt.timedelta(minutes=30)


def expected_preflight_policy_requirements(
    environment: str,
    traffic_open_event: str | None,
) -> dict[str, bool]:
    required = {policy_id: False for policy_id in EXPECTED_PREFLIGHT_POLICY_IDS}
    for policy_id in COMMON_REQUIRED_PREFLIGHT_POLICY_IDS:
        required[policy_id] = True
    if environment in {"staging", "production"}:
        required["PREFLIGHT-DIGEST-001"] = True
    if environment == "production":
        required["PREFLIGHT-PROMOTION-001"] = True
        required["PREFLIGHT-BACKUP-001"] = True
        required["PREFLIGHT-BACKUP-002"] = traffic_open_event in {"first-live", "reopen"}
    if environment == "hobby-self-hosted":
        required["PREFLIGHT-BACKUP-003"] = traffic_open_event in {"first-live", "reopen"}
    return required

PROMOTION_ATTESTATION_VERSION = "v1"
PROMOTION_ATTESTATION_REQUIRED_FIELDS = (
    "attestationVersion",
    "environment",
    "stagingOverlayCommitSha",
    "stagingDeploymentEventId",
    "productionOverlayRef",
    "serviceDigests",
    "smokeEvidence",
    "generatedAt",
    "approvedBy",
    "rollbackMode",
    "recoveryCompatibility",
)

CANONICAL_RECOVERY_REQUIRED_FIELDS = (
    "schemaVersion",
    "environment",
    "recoveryRef",
    "operationId",
    "recoveryStatus",
    "recoveryPurpose",
    "sourceEnvironmentBinding",
    "targetBoundary",
    "trafficExposure",
    "restoreSource",
    "restoreSafeMode",
    "coordinationRecoveryMode",
    "backupArtifactRef",
    "artifactErasureHighWater",
    "initialCatchupHighWater",
    "restoreHighWater",
    "erasureReplay",
    "erasureOverlayReconciliation",
    "backupArtifactLineage",
    "backupToolDigest",
    "recoveryToolDigest",
    "recoveryContractFingerprint",
    "recoveryParticipantInventoryRef",
    "validatorInventoryRef",
    "externalEffectInventoryRef",
    "quarantineStartedAt",
    "readyToReopenAt",
    "quarantineReleasedAt",
    "finalizedAt",
    "restoredAt",
    "restoredBy",
    "recoveryControllerLineage",
    "expectedBindingsRef",
    "coordinationRecoveryEvidence",
    "backupConfidentialityEvidence",
    "durableParticipantConvergence",
    "externalEffectReconciliation",
    "sessionRecovery",
    "jwtHardening",
    "databaseCredentialRotation",
    "certificateReissuance",
    "externalCredentialValidation",
    "secretComplianceRefresh",
    "smokeStatus",
    "smokeEvidence",
    "reopenApprovedBy",
)

CANONICAL_RECOVERY_STRING_FIELDS = (
    "schemaVersion",
    "environment",
    "recoveryRef",
    "operationId",
    "recoveryStatus",
    "recoveryPurpose",
    "trafficExposure",
    "coordinationRecoveryMode",
    "backupArtifactRef",
    "backupToolDigest",
    "recoveryToolDigest",
    "recoveryContractFingerprint",
    "recoveryParticipantInventoryRef",
    "validatorInventoryRef",
    "externalEffectInventoryRef",
    "quarantineStartedAt",
    "readyToReopenAt",
    "quarantineReleasedAt",
    "finalizedAt",
    "restoredAt",
    "restoredBy",
    "expectedBindingsRef",
    "smokeStatus",
    "reopenApprovedBy",
)

CANONICAL_RECOVERY_OBJECT_FIELDS = (
    "restoreSafeMode",
    "artifactErasureHighWater",
    "initialCatchupHighWater",
    "restoreHighWater",
    "erasureReplay",
    "erasureOverlayReconciliation",
    "backupArtifactLineage",
    "recoveryControllerLineage",
    "coordinationRecoveryEvidence",
    "backupConfidentialityEvidence",
    "durableParticipantConvergence",
    "externalEffectReconciliation",
    "sessionRecovery",
    "jwtHardening",
    "databaseCredentialRotation",
    "certificateReissuance",
    "externalCredentialValidation",
    "secretComplianceRefresh",
)

CANONICAL_RECOVERY_CREDENTIAL_CLASSES = (
    "backup-storage",
    "asset-storage",
    "outbound-comms",
    "operator-credentials",
)

BACKUP_READINESS_REQUIRED_FIELDS = (
    "environment",
    "deploymentRef",
    "promotionAttestationRef",
    "assessedAt",
    "assessedBy",
    "rollbackMode",
    "backupLastSuccessAt",
    "backupVerifyLastSuccessAt",
    "restoreDrillLastSuccessAt",
    "restorePlanRef",
    "restoreRecoveryRecordRef",
    "baselineRecoveryRecordRef",
    "recoveryControllerLineage",
    "backupConfidentialityEvidence",
    "backupCoverage",
    "backupArtifactRef",
    "artifactErasureHighWater",
    "initialCatchupHighWater",
    "restoreHighWater",
    "sourceServiceDigests",
    "candidateServiceDigests",
    "candidateMigrationPathRef",
    "backupToolDigest",
    "recoveryToolDigest",
    "recoveryContractFingerprint",
    "evidenceRefs",
)


def fail(message: str) -> "NoReturn":
    print(message, file=sys.stderr)
    raise SystemExit(1)


def usage() -> "NoReturn":
    print(USAGE, file=sys.stderr)
    raise SystemExit(1)


def run(args: list[str]) -> str:
    result = subprocess.run(args, check=False, capture_output=True, text=True)
    if result.returncode != 0:
        stderr = result.stderr.strip()
        stdout = result.stdout.strip()
        detail = stderr or stdout or f"command failed: {' '.join(args)}"
        fail(detail)
    return result.stdout


def repo_root() -> Path:
    return Path(run(["git", "rev-parse", "--show-toplevel"]).strip())


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def parse_timestamp(value: Any, field_name: str) -> dt.datetime:
    if not isinstance(value, str) or not value:
        raise ValueError(f"missing {field_name}")
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError(f"{field_name} must include a timezone")
    return parsed.astimezone(dt.timezone.utc)


def is_missing(value: Any) -> bool:
    return value in (None, "", [], {})


def validate_safe_dispositions(value: Any, label: str) -> tuple[str, str]:
    if not isinstance(value, dict) or not value:
        return ("fail", f"Recovery compatibility baseline {label} must be a non-empty object")
    for participant, result in value.items():
        disposition = result.get("disposition") if isinstance(result, dict) else None
        if disposition in NON_QUALIFYING_RECOVERY_DISPOSITIONS or disposition not in SAFE_RECOVERY_DISPOSITIONS:
            return (
                "fail",
                f"Recovery compatibility baseline {label} has unsafe or missing disposition: {participant}",
            )
    return ("pass", "")


def validate_erasure_overlay_reconciliation(
    value: Any,
    artifact_high_water: dict[str, Any],
    initial_catchup_high_water: dict[str, Any],
    restore_high_water: dict[str, Any],
    stream: str,
) -> tuple[str, str]:
    label = "Recovery compatibility baseline erasureOverlayReconciliation"
    if not isinstance(value, dict):
        return ("fail", f"{label} must be an object")

    overlay_boundaries = {}
    for boundary_name in (
        "artifactErasureHighWater",
        "initialCatchupHighWater",
        "restoreHighWater",
    ):
        boundary = value.get(boundary_name)
        if not isinstance(boundary, dict):
            return ("fail", f"{label} {boundary_name} must be an object")
        sequence = boundary.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool):
            return ("fail", f"{label} {boundary_name}.sequence must be an integer")
        overlay_boundaries[boundary_name] = sequence

    canonical_boundaries = {
        "artifactErasureHighWater": artifact_high_water,
        "initialCatchupHighWater": initial_catchup_high_water,
        "restoreHighWater": restore_high_water,
    }
    canonical_sequences = {}
    for boundary_name, boundary in canonical_boundaries.items():
        if not isinstance(boundary, dict):
            return ("fail", f"{label} canonical {boundary_name} must be an object")
        sequence = boundary.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool):
            return ("fail", f"{label} canonical {boundary_name}.sequence must be an integer")
        canonical_sequences[boundary_name] = sequence

    if not (
        canonical_sequences["artifactErasureHighWater"]
        <= canonical_sequences["initialCatchupHighWater"]
        <= canonical_sequences["restoreHighWater"]
    ):
        return ("fail", f"{label} erasure high-water sequences must be ordered")
    # Check overlay ordering before exact equality so malformed overlay metadata
    # fails closed on its own ordering contract.
    if not (
        overlay_boundaries["artifactErasureHighWater"]
        <= overlay_boundaries["initialCatchupHighWater"]
        <= overlay_boundaries["restoreHighWater"]
    ):
        return ("fail", f"{label} erasure high-water sequences must be ordered")

    if value.get("stream") != stream:
        return ("fail", f"{label} stream must match the canonical erasure stream")
    if value.get("artifactErasureHighWater") != artifact_high_water:
        return ("fail", f"{label} artifactErasureHighWater must match the canonical bound exactly")
    if value.get("initialCatchupHighWater") != initial_catchup_high_water:
        return ("fail", f"{label} initialCatchupHighWater must match the canonical bound exactly")
    if value.get("restoreHighWater") != restore_high_water:
        return ("fail", f"{label} restoreHighWater must match the canonical bound exactly")

    sequence_verification = value.get("sequenceVerification")
    required_sequence_flags = ("contiguous", "complete", "gapFree", "duplicateFree")
    artifact_sequence = canonical_sequences["artifactErasureHighWater"]
    initial_catchup_sequence = canonical_sequences["initialCatchupHighWater"]
    restore_sequence = canonical_sequences["restoreHighWater"]
    if (
        not isinstance(sequence_verification, dict)
        or sequence_verification.get("status") != "pass"
        or sequence_verification.get("exclusiveStart") != artifact_sequence
        or sequence_verification.get("inclusiveEnd") != initial_catchup_sequence
        or sequence_verification.get("ordered") is not True
        or any(sequence_verification.get(flag) is not True for flag in required_sequence_flags)
    ):
        return (
            "fail",
            f"{label} sequenceVerification must prove the canonical bounds and ordered, contiguous, "
            "complete, gap-free, duplicate-free initial catch-up interval",
        )

    integrity_verification = value.get("integrityVerification")
    if (
        not isinstance(integrity_verification, dict)
        or integrity_verification.get("status") != "pass"
        or integrity_verification.get("verified") is not True
    ):
        return ("fail", f"{label} integrityVerification must be verified with status pass")

    sequence_dispositions = value.get("sequenceDispositions")
    if not isinstance(sequence_dispositions, list):
        return ("fail", f"{label} sequenceDispositions must be a list")

    observed_sequences: set[int] = set()
    for index, entry in enumerate(sequence_dispositions):
        if not isinstance(entry, dict):
            return ("fail", f"{label} sequenceDispositions[{index}] must be an object")
        if entry.get("stream") != stream:
            return ("fail", f"{label} sequenceDispositions[{index}] stream must match the canonical erasure stream")
        sequence = entry.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool):
            return ("fail", f"{label} sequenceDispositions[{index}] sequence must be an integer")
        if sequence <= initial_catchup_sequence or sequence > restore_sequence:
            return ("fail", f"{label} sequenceDispositions[{index}] sequence is outside the final interval")
        if sequence in observed_sequences:
            return ("fail", f"{label} sequenceDispositions contains duplicate sequence {sequence}")
        if not isinstance(entry.get("owner"), str) or not entry["owner"].strip():
            return ("fail", f"{label} sequenceDispositions[{index}] owner must be non-empty")
        if entry.get("disposition") not in SAFE_RECOVERY_DISPOSITIONS:
            return ("fail", f"{label} sequenceDispositions[{index}] has an invalid canonical disposition")
        if entry.get("integrityVerified") is not True:
            return ("fail", f"{label} sequenceDispositions[{index}] integrity must be verified")
        observed_sequences.add(sequence)

    expected_sequence_count = restore_sequence - initial_catchup_sequence
    if len(observed_sequences) != expected_sequence_count:
        missing_sequences = list(
            islice(
                (
                    sequence
                    for sequence in range(
                        initial_catchup_sequence + 1, restore_sequence + 1
                    )
                    if sequence not in observed_sequences
                ),
                20,
            )
        )
        return (
            "fail",
            f"{label} sequenceDispositions must cover the exact final interval; "
            f"missing={missing_sequences}",
        )
    return ("pass", "")


def validate_recovery_baseline(
    root_dir: Path,
    baseline_ref: str,
    expected_fingerprint: str,
    evaluated_at: dt.datetime,
    now_dt: dt.datetime,
) -> tuple[str, str]:
    baseline_ref_path = Path(baseline_ref)
    recovery_dir = (root_dir / "design" / "operations" / "deployments" / "production" / "recovery").resolve()
    baseline_path = resolve_repo_path(root_dir, baseline_ref).resolve()
    if baseline_ref_path.is_absolute() or not baseline_path.is_relative_to(recovery_dir):
        return (
            "fail",
            "Recovery compatibility baseline must be a repository-relative record under "
            "design/operations/deployments/production/recovery/",
        )
    if not baseline_path.exists():
        return ("fail", f"Recovery compatibility baseline record not found: {baseline_ref}")
    try:
        baseline = load_json(baseline_path)
    except Exception as exc:
        return ("fail", f"Recovery compatibility baseline record unreadable: {exc}")
    if not isinstance(baseline, dict):
        return ("fail", "Recovery compatibility baseline record must be a JSON object")

    missing_fields = [
        field
        for field in CANONICAL_RECOVERY_REQUIRED_FIELDS
        if field not in baseline or is_missing(baseline[field])
    ]
    if missing_fields:
        return (
            "fail",
            "Recovery compatibility baseline is missing canonical finalized projection fields: "
            + ", ".join(missing_fields),
        )

    invalid_string_fields = [
        field
        for field in CANONICAL_RECOVERY_STRING_FIELDS
        if not isinstance(baseline.get(field), str) or not baseline[field].strip()
    ]
    if invalid_string_fields:
        return (
            "fail",
            "Recovery compatibility baseline canonical fields must be non-empty strings: "
            + ", ".join(invalid_string_fields),
        )

    invalid_object_fields = [
        field
        for field in CANONICAL_RECOVERY_OBJECT_FIELDS
        if not isinstance(baseline.get(field), (dict, list)) or not baseline[field]
    ]
    if invalid_object_fields:
        return (
            "fail",
            "Recovery compatibility baseline canonical evidence groups must be non-empty objects or lists: "
            + ", ".join(invalid_object_fields),
        )

    for field in ("sourceEnvironmentBinding", "targetBoundary", "restoreSource"):
        if is_missing(baseline.get(field)):
            return ("fail", f"Recovery compatibility baseline {field} must be non-empty")

    expected_values = {
        "schemaVersion": "recovery-record/v1",
        "environment": "production",
        "recoveryStatus": "finalized",
        "recoveryPurpose": "production-equivalent-drill",
        "trafficExposure": "isolated-drill",
        "coordinationRecoveryMode": "cold_start_restore",
        "recoveryContractFingerprint": expected_fingerprint,
        "expectedBindingsRef": "design/operations/environments/production/expected-bindings.yaml",
    }
    for field, expected in expected_values.items():
        if baseline.get(field) != expected:
            return ("fail", f"Recovery compatibility baseline {field} must be {expected}")

    restore_safe_mode = baseline.get("restoreSafeMode")
    if not isinstance(restore_safe_mode, dict):
        return ("fail", "Recovery compatibility baseline restoreSafeMode must be an object")
    if restore_safe_mode.get("status") != "pass" or restore_safe_mode.get("playerIngress") != "disabled":
        return ("fail", "Recovery compatibility baseline restoreSafeMode must pass with player ingress disabled")

    controller_lineage = baseline.get("recoveryControllerLineage")
    if not isinstance(controller_lineage, dict):
        return ("fail", "Recovery compatibility baseline recoveryControllerLineage must be an object")
    if controller_lineage.get("recoveryStatus") != "finalized":
        return ("fail", "Recovery compatibility baseline controller lineage must be finalized")
    if controller_lineage.get("scope") != "environment-wide":
        return ("fail", "Recovery compatibility baseline controller lineage must be environment-wide")
    if not isinstance(controller_lineage.get("finalizedReleaseIdentity"), str) or not controller_lineage[
        "finalizedReleaseIdentity"
    ].strip():
        return ("fail", "Recovery compatibility baseline controller lineage missing finalized release identity")

    artifact_high_water = baseline.get("artifactErasureHighWater")
    initial_catchup_high_water = baseline.get("initialCatchupHighWater")
    restore_high_water = baseline.get("restoreHighWater")
    erasure_replay = baseline.get("erasureReplay")
    if not all(
        isinstance(value, dict)
        for value in (
            artifact_high_water,
            initial_catchup_high_water,
            restore_high_water,
            erasure_replay,
        )
    ):
        return ("fail", "Recovery compatibility baseline erasure high-water evidence must be objects")
    artifact_sequence = artifact_high_water.get("sequence")
    initial_catchup_sequence = initial_catchup_high_water.get("sequence")
    restore_sequence = restore_high_water.get("sequence")
    if (
        not isinstance(artifact_sequence, int)
        or isinstance(artifact_sequence, bool)
        or not isinstance(initial_catchup_sequence, int)
        or isinstance(initial_catchup_sequence, bool)
        or not isinstance(restore_sequence, int)
        or isinstance(restore_sequence, bool)
        or not artifact_sequence <= initial_catchup_sequence <= restore_sequence
    ):
        return ("fail", "Recovery compatibility baseline erasure high-water sequences must be ordered non-boolean integers")
    high_water_stream = artifact_high_water.get("stream")
    if (
        not isinstance(high_water_stream, str)
        or not high_water_stream.strip()
        or initial_catchup_high_water.get("stream") != high_water_stream
        or restore_high_water.get("stream") != high_water_stream
    ):
        return ("fail", "Recovery compatibility baseline erasure high-water streams must match")
    if (
        erasure_replay.get("gapFree") is not True
        or erasure_replay.get("exclusiveStart") != artifact_sequence
        or erasure_replay.get("initialCatchupThrough") != initial_catchup_sequence
        or erasure_replay.get("inclusiveEnd") != restore_sequence
        or erasure_replay.get("replayedThrough") != restore_sequence
    ):
        return ("fail", "Recovery compatibility baseline erasure replay must be gap-free through restoreHighWater")
    overlay_status, overlay_message = validate_erasure_overlay_reconciliation(
        baseline.get("erasureOverlayReconciliation"),
        artifact_high_water,
        initial_catchup_high_water,
        restore_high_water,
        high_water_stream,
    )
    if overlay_status != "pass":
        return ("fail", overlay_message)
    backup_artifact_lineage = baseline.get("backupArtifactLineage")
    if not isinstance(backup_artifact_lineage, dict):
        return (
            "fail",
            "Recovery compatibility baseline artifact lineage must be an object",
        )
    pre_snapshot_journal_high_water = backup_artifact_lineage.get("preSnapshotJournalHighWater")
    if backup_artifact_lineage.get("artifactErasureHighWater") != artifact_high_water:
        return (
            "fail",
            "Recovery compatibility baseline artifact lineage artifactErasureHighWater "
            "must match the snapshot-bound artifact high-water sequence",
        )
    if backup_artifact_lineage.get("erasureHighWaterSnapshotBound") is not True:
        return (
            "fail",
            "Recovery compatibility baseline artifact lineage erasureHighWaterSnapshotBound "
            "must be true",
        )
    if not isinstance(pre_snapshot_journal_high_water, dict):
        return (
            "fail",
            "Recovery compatibility baseline artifact lineage must include a valid "
            "preSnapshotJournalHighWater object",
        )
    if pre_snapshot_journal_high_water.get("stream") != high_water_stream:
        return (
            "fail",
            "Recovery compatibility baseline preSnapshotJournalHighWater.stream "
            "must match the canonical erasure stream",
        )
    pre_snapshot_sequence = pre_snapshot_journal_high_water.get("sequence")
    if not isinstance(pre_snapshot_sequence, int) or isinstance(pre_snapshot_sequence, bool):
        return (
            "fail",
            "Recovery compatibility baseline preSnapshotJournalHighWater.sequence "
            "must be an integer",
        )
    if pre_snapshot_sequence < artifact_sequence:
        return (
            "fail",
            "Recovery compatibility baseline preSnapshotJournalHighWater.sequence must be at or above artifactErasureHighWater",
        )

    coordination_evidence = baseline.get("coordinationRecoveryEvidence")
    if not isinstance(coordination_evidence, dict):
        return ("fail", "Recovery compatibility baseline coordinationRecoveryEvidence must be an object")
    if (
        coordination_evidence.get("mode") != "cold_start_restore"
        or coordination_evidence.get("coordinationRedis") != "empty-before-rebuild"
        or coordination_evidence.get("credentialBinding") != "rotated-or-rebound"
        or coordination_evidence.get("targetEnvironmentBound") is not True
        or coordination_evidence.get("snapshotCredentialsRejected") is not True
        or coordination_evidence.get("regionEpochFences") != "advanced-or-recreated"
        or coordination_evidence.get("accountAuthorityProjections") != "rebuilt-and-verified"
        or coordination_evidence.get("replayAdmissionFence") != "advanced"
        or coordination_evidence.get("replayQuarantine") != "lifetime-plus-skew-observed"
        or not isinstance(coordination_evidence.get("accountAuthorityProjectionEvidenceRef"), str)
        or not coordination_evidence["accountAuthorityProjectionEvidenceRef"].strip()
        or not isinstance(coordination_evidence.get("replayConsumeEvidenceRef"), str)
        or not coordination_evidence["replayConsumeEvidenceRef"].strip()
    ):
        return (
            "fail",
            "Recovery compatibility baseline coordination recovery must prove empty Redis, target-environment credential rebinding, advanced region fences, Account authority projection rebuild, and replay-domain readiness",
        )

    for field, label in (
        ("durableParticipantConvergence", "durable participant convergence"),
        ("externalEffectReconciliation", "external-effect reconciliation"),
    ):
        disposition_status, disposition_message = validate_safe_dispositions(baseline.get(field), label)
        if disposition_status != "pass":
            return ("fail", disposition_message)

    confidentiality = baseline.get("backupConfidentialityEvidence")
    if not isinstance(confidentiality, dict):
        return ("fail", "Recovery compatibility baseline backupConfidentialityEvidence must be an object")
    if (
        confidentiality.get("status") != "pass"
        or confidentiality.get("transport") != "encrypted"
        or confidentiality.get("storage") != "encrypted"
    ):
        return ("fail", "Recovery compatibility baseline backup confidentiality evidence must pass")

    required_hardening_fields = {
        "jwtHardening": (
            "rotationJobRef",
            "resultingKeyIds",
            "revocationWatermarkEvidence",
            "validatorConvergenceEvidence",
        ),
        "databaseCredentialRotation": (
            "rotationJobRef",
            "affectedSecretRefs",
            "rolloutRestartEvidence",
        ),
        "certificateReissuance": (
            "workloadLeafEvidence",
            "bridgeLeafEvidence",
            "operatorLeafEvidence",
            "peerConvergenceEvidence",
        ),
        "secretComplianceRefresh": (
            "recordRef",
            "evidenceRef",
            "credentialClasses",
            "freshness",
        ),
    }
    for group_name, required_fields in required_hardening_fields.items():
        group = baseline.get(group_name)
        if not isinstance(group, dict):
            return ("fail", f"Recovery compatibility baseline {group_name} must be an object")
        missing_group_fields = [field for field in required_fields if is_missing(group.get(field))]
        if missing_group_fields:
            return (
                "fail",
                f"Recovery compatibility baseline {group_name} missing fields: "
                + ", ".join(missing_group_fields),
            )

    if baseline.get("smokeStatus") != "pass":
        return ("fail", "Recovery compatibility baseline smokeStatus must be pass")
    if not isinstance(baseline.get("smokeEvidence"), list) or not baseline["smokeEvidence"]:
        return ("fail", "Recovery compatibility baseline smokeEvidence must be a non-empty list")

    session_recovery = baseline.get("sessionRecovery")
    if not isinstance(session_recovery, dict):
        return ("fail", "Recovery compatibility baseline sessionRecovery must be an object")
    for field in ("gameSessionHandling", "authSessionHandling"):
        if session_recovery.get(field) != "invalidated":
            return ("fail", f"Recovery compatibility baseline sessionRecovery.{field} must be invalidated")

    credential_validation = baseline.get("externalCredentialValidation")
    if not isinstance(credential_validation, dict):
        return ("fail", "Recovery compatibility baseline externalCredentialValidation must be an object")
    credential_records = credential_validation.get("records")
    if not isinstance(credential_records, dict):
        return ("fail", "Recovery compatibility baseline externalCredentialValidation.records must be an object")
    missing_credential_records = [
        name for name in CANONICAL_RECOVERY_CREDENTIAL_CLASSES if name not in credential_records
    ]
    if missing_credential_records:
        return (
            "fail",
            "Recovery compatibility baseline externalCredentialValidation.records missing: "
            + ", ".join(missing_credential_records),
        )
    credential_fields = (
        "status",
        "evidenceRef",
        "isolationAssertion",
        "validationMethod",
        "validatedAt",
        "validatedBy",
        "observedValue",
    )
    for class_name in CANONICAL_RECOVERY_CREDENTIAL_CLASSES:
        record = credential_records.get(class_name)
        if not isinstance(record, dict):
            return (
                "fail",
                f"Recovery compatibility baseline external credential record must be an object: {class_name}",
            )
        missing_record_fields = [
            field for field in credential_fields if field not in record or is_missing(record[field])
        ]
        if missing_record_fields:
            return (
                "fail",
                f"Recovery compatibility baseline external credential record missing fields for {class_name}: "
                + ", ".join(missing_record_fields),
            )
        if record.get("status") != "pass":
            return (
                "fail",
                f"Recovery compatibility baseline external credential record status must be pass: {class_name}",
            )
        if not isinstance(record.get("observedValue"), str):
            return (
                "fail",
                f"Recovery compatibility baseline external credential observedValue must be non-secret text: {class_name}",
            )
        try:
            parse_timestamp(record.get("validatedAt"), f"Recovery baseline {class_name}.validatedAt")
        except Exception as exc:
            return ("fail", str(exc))

    try:
        lifecycle_timestamps = {
            field: parse_timestamp(baseline.get(field), f"Recovery compatibility baseline {field}")
            for field in (
                "quarantineStartedAt",
                "readyToReopenAt",
                "quarantineReleasedAt",
                "finalizedAt",
                "restoredAt",
            )
        }
    except Exception as exc:
        return ("fail", str(exc))
    if lifecycle_timestamps["readyToReopenAt"] <= lifecycle_timestamps["quarantineStartedAt"]:
        return ("fail", "Recovery compatibility baseline readyToReopenAt must be later than quarantineStartedAt")
    if lifecycle_timestamps["quarantineReleasedAt"] <= lifecycle_timestamps["readyToReopenAt"]:
        return (
            "fail",
            "Recovery compatibility baseline quarantineReleasedAt must be later than readyToReopenAt",
        )
    if lifecycle_timestamps["finalizedAt"] <= lifecycle_timestamps["quarantineReleasedAt"]:
        return ("fail", "Recovery compatibility baseline finalizedAt must be later than quarantineReleasedAt")
    if lifecycle_timestamps["restoredAt"] > lifecycle_timestamps["finalizedAt"]:
        return ("fail", "Recovery compatibility baseline restoredAt must not be later than finalizedAt")

    finalized_at = lifecycle_timestamps["finalizedAt"]
    if finalized_at > evaluated_at:
        return ("fail", "Recovery compatibility baseline finalizedAt is later than evaluatedAt")
    if finalized_at > now_dt:
        return ("fail", "Recovery compatibility baseline finalizedAt is future-dated")
    if (now_dt - finalized_at).total_seconds() > 30 * 24 * 60 * 60:
        return ("fail", "Recovery compatibility baseline finalized drill is older than 30 days")
    return ("pass", "Recovery compatibility baseline is valid")


def load_yaml(path: Path) -> Any:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_documents(rendered_text: str) -> list[dict[str, Any]]:
    return [doc for doc in yaml.safe_load_all(rendered_text) if isinstance(doc, dict)]


def resolve_repo_path(root_dir: Path, ref: str) -> Path:
    path = Path(ref)
    return path if path.is_absolute() else root_dir / ref


def default_preflight_output_path(
    root_dir: Path,
    environment: str,
    deployment_ref: str,
    deployment_event_id: str,
) -> Path:
    return (
        root_dir
        / "design"
        / "operations"
        / "deployments"
        / environment
        / "preflight"
        / deployment_ref
        / f"{deployment_event_id}.json"
    )


def validate_preflight_report(
    report: Any,
    environment: str,
    expected_bindings_ref: str,
    deployment_ref: str,
    now_dt: dt.datetime | None = None,
    expected_deployment_event_id: str | None = None,
    completed_by: dt.datetime | None = None,
) -> tuple[str, str]:
    label = environment.capitalize()
    effective_now = now_dt or dt.datetime.now(dt.timezone.utc)
    if not isinstance(report, dict):
        return ("fail", f"{label} preflight report must be a JSON object")
    if report.get("environment") != environment:
        return ("fail", f"{label} preflight report must target {environment}")
    if report.get("expectedBindingsRef") != expected_bindings_ref:
        return ("fail", f"{label} preflight report expectedBindingsRef mismatch")

    deployment_ref_obj = report.get("deploymentRef")
    if not isinstance(deployment_ref_obj, dict):
        return ("fail", f"{label} preflight report deploymentRef must be an object")
    expected_ref_key = "manifestRef" if environment == "hobby-self-hosted" else "overlayCommitSha"
    if deployment_ref_obj.get(expected_ref_key) != deployment_ref:
        return ("fail", f"{label} preflight report deploymentRef mismatch")

    deployment_event_id = report.get("deploymentEventId")
    try:
        parsed_event_id = uuid.UUID(str(deployment_event_id))
    except (ValueError, TypeError, AttributeError):
        return ("fail", f"{label} preflight report deploymentEventId must be a UUID")
    if str(parsed_event_id) != deployment_event_id:
        return ("fail", f"{label} preflight report deploymentEventId must use canonical UUID form")
    if expected_deployment_event_id is not None and deployment_event_id != expected_deployment_event_id:
        return ("fail", f"{label} preflight report deploymentEventId mismatch")

    traffic_open_event = report.get("trafficOpenEvent")
    if traffic_open_event not in {None, "first-live", "reopen"}:
        return ("fail", f"{label} preflight report trafficOpenEvent is invalid")

    if report.get("context") != "operator":
        return ("fail", f"{label} preflight report must come from operator context")
    if report.get("toolVersion") != "preflight.py-v1":
        return ("fail", f"{label} preflight report toolVersion mismatch")
    if "waiverPath" in report:
        return (
            "fail",
            f"{label} preflight report waivers are not consumable until one-time authority is implemented",
        )
    try:
        started_at = parse_timestamp(report.get("startedAt"), f"{label} preflight report startedAt")
        completed_at = parse_timestamp(report.get("completedAt"), f"{label} preflight report completedAt")
    except Exception as exc:
        return ("fail", str(exc))
    if completed_at < started_at:
        return ("fail", f"{label} preflight report completedAt must not precede startedAt")
    if completed_at > effective_now:
        return ("fail", f"{label} preflight report completedAt is future-dated")
    if completed_by is not None and completed_at > completed_by:
        return ("fail", f"{label} preflight report completedAt must not be later than the apply event")
    consumed_at = completed_by or effective_now
    if consumed_at - completed_at > PREFLIGHT_APPLY_MAX_AGE:
        window = "apply" if completed_by is not None else "consumption"
        return ("fail", f"{label} preflight report is older than the 30-minute {window} window")

    preflight_results = report.get("checkResults")
    if not isinstance(preflight_results, list) or not preflight_results:
        return ("fail", f"{label} preflight report missing checkResults")
    policy_ids: list[str] = []
    malformed_results: list[str] = []
    for index, check in enumerate(preflight_results):
        if not isinstance(check, dict):
            malformed_results.append(str(index))
            continue
        policy_id = check.get("policyId")
        status = check.get("status")
        message = check.get("message")
        required = check.get("required")
        if (
            not isinstance(policy_id, str)
            or not policy_id
            or status not in {"pass", "fail", "not_applicable"}
            or not isinstance(message, str)
            or not message
            or not isinstance(required, bool)
        ):
            malformed_results.append(str(index))
            continue
        policy_ids.append(policy_id)
    if malformed_results:
        return (
            "fail",
            f"{label} preflight report contains malformed checkResults entries: "
            + ", ".join(malformed_results),
        )

    duplicate_ids = sorted({policy_id for policy_id in policy_ids if policy_ids.count(policy_id) > 1})
    if duplicate_ids:
        return ("fail", f"{label} preflight report contains duplicate policy IDs: " + ", ".join(duplicate_ids))
    missing_ids = sorted(EXPECTED_PREFLIGHT_POLICY_ID_SET - set(policy_ids))
    if missing_ids:
        return ("fail", f"{label} preflight report missing expected policy IDs: " + ", ".join(missing_ids))
    unknown_ids = sorted(set(policy_ids) - EXPECTED_PREFLIGHT_POLICY_ID_SET)
    if unknown_ids:
        return ("fail", f"{label} preflight report contains unknown policy IDs: " + ", ".join(unknown_ids))

    expected_requirements = expected_preflight_policy_requirements(environment, traffic_open_event)
    requirement_mismatches = sorted(
        check["policyId"]
        for check in preflight_results
        if check["required"] is not expected_requirements[check["policyId"]]
    )
    if requirement_mismatches:
        return (
            "fail",
            f"{label} preflight report has incorrect required applicability for policy IDs: "
            + ", ".join(requirement_mismatches),
        )

    required_pass_ids = {policy_id for policy_id, required in expected_requirements.items() if required}
    status_by_policy = {check["policyId"]: check["status"] for check in preflight_results}
    non_passing_required_ids = sorted(
        policy_id for policy_id in required_pass_ids if status_by_policy.get(policy_id) != "pass"
    )
    if non_passing_required_ids:
        return (
            "fail",
            f"{label} preflight report has non-passing required policy IDs: "
            + ", ".join(non_passing_required_ids),
        )

    invalid_non_applicable_ids = sorted(
        check["policyId"]
        for check in preflight_results
        if not check["required"]
        and not (
            environment == "hobby-self-hosted"
            and check["policyId"] == "PREFLIGHT-DIGEST-002"
        )
        and check["status"] != "not_applicable"
    )
    if invalid_non_applicable_ids:
        return (
            "fail",
            f"{label} preflight report has non-applicable policy IDs with executable statuses: "
            + ", ".join(invalid_non_applicable_ids),
        )
    return ("pass", "")


def load_preflight_report(
    path_ref: str,
    environment: str,
    expected_bindings_ref: str,
    deployment_ref: str,
    root_dir: Path,
    expected_traffic_open_event: str | None = None,
    expected_deployment_event_id: str | None = None,
) -> tuple[str, str]:
    if not path_ref:
        return ("fail", f"{environment.capitalize()} traffic-open evidence missing preflightReportPath")
    path = resolve_repo_path(root_dir, path_ref)
    if not path.exists():
        return ("fail", f"{environment.capitalize()} preflight report not found: {path_ref}")
    try:
        report = load_json(path)
    except Exception as exc:
        return ("fail", f"{environment.capitalize()} preflight report unreadable: {exc}")
    status, message = validate_preflight_report(
        report,
        environment,
        expected_bindings_ref,
        deployment_ref,
        expected_deployment_event_id=expected_deployment_event_id,
    )
    if status != "pass":
        return (status, message)
    if report.get("trafficOpenEvent") != expected_traffic_open_event:
        return ("fail", f"{environment.capitalize()} preflight report trafficOpenEvent mismatch")
    return ("pass", "")


def walk(node: Any):
    if isinstance(node, dict):
        yield node
        for value in node.values():
            yield from walk(value)
    elif isinstance(node, list):
        for item in node:
            yield from walk(item)


def get(data: Any, dotted: str) -> Any:
    cur = data
    for part in dotted.split("."):
        if not isinstance(cur, dict) or part not in cur:
            return None
        cur = cur[part]
    return cur


def player_facing_environments() -> tuple[str, ...]:
    return ("staging", "production", "hobby-self-hosted")


def normalize_binding_value(raw: Any) -> tuple[str | None, bool, str]:
    if isinstance(raw, dict):
        shared = bool(raw.get("shared"))
        rationale = str(raw.get("sharedRationale") or "")
        for key in ("value", "bindingRef", "fingerprint"):
            value = raw.get(key)
            if isinstance(value, str) and value:
                return (value, shared, rationale)
        return (None, shared, rationale)
    if isinstance(raw, str) and raw:
        return (raw, False, "")
    return (None, False, "")


def extract_service_discovery_overrides(rendered_text: str) -> dict[str, str]:
    overrides = {}
    for raw_key, raw_value in re.findall(
        r"(FIREMUD_SERVICES_[A-Z0-9_]+):\s*([^\n]+)", rendered_text
    ):
        value = str(raw_value).strip().strip("\"'")
        overrides[raw_key] = value
    return overrides


def external_binding_uniqueness_issues(
    manifests_root: Path, env_class: str, current_data: dict[str, Any]
) -> list[str]:
    current_backup = current_data.get("backupStorage") or {}
    current_asset = current_data.get("assetStorage") or {}
    current_outbound = current_data.get("outboundComms") or {}
    current_operator = current_data.get("operatorCredentials") or {}

    def add_candidate(
        issues: list[str],
        candidates: list[tuple[str, str, bool, str]],
        label: str,
        raw_value: Any,
    ) -> None:
        value, shared, rationale = normalize_binding_value(raw_value)
        if value is None:
            return
        if shared and not rationale:
            issues.append(f"{label} is marked shared but missing sharedRationale")
            return
        candidates.append((label, value, shared, rationale))

    issues: list[str] = []
    candidates: list[tuple[str, str, bool, str]] = []
    add_candidate(issues, candidates, "backupStorage.bucket", current_backup.get("bucket"))
    add_candidate(
        issues,
        candidates,
        "backupStorage.bindingRef",
        current_backup.get("bindingRef") or current_backup.get("fingerprint"),
    )
    add_candidate(issues, candidates, "assetStorage.bucket", current_asset.get("bucket"))
    add_candidate(issues, candidates, "assetStorage.endpoint", current_asset.get("endpoint"))
    add_candidate(
        issues,
        candidates,
        "assetStorage.bindingRef",
        current_asset.get("bindingRef") or current_asset.get("fingerprint"),
    )
    add_candidate(issues, candidates, "outboundComms.smtpHost", current_outbound.get("smtpHost"))
    for target_name, raw_target in sorted((current_outbound.get("webhookTargets") or {}).items()):
        add_candidate(
            issues, candidates, f"outboundComms.webhookTargets.{target_name}", raw_target
        )
    add_candidate(
        issues,
        candidates,
        "operatorCredentials.bindingRef",
        current_operator.get("bindingRef") or current_operator.get("fingerprint"),
    )
    if issues:
        return issues

    for other_env in player_facing_environments():
        if other_env == env_class:
            continue
        other_path = manifests_root / other_env / "expected-bindings.yaml"
        if not other_path.exists():
            issues.append(f"Missing expected-bindings manifest for {other_env}")
            continue
        try:
            other_data = load_yaml(other_path) or {}
        except Exception as exc:
            issues.append(f"Unreadable expected-bindings manifest for {other_env}: {exc}")
            continue
        other_backup = other_data.get("backupStorage") or {}
        other_asset = other_data.get("assetStorage") or {}
        other_outbound = other_data.get("outboundComms") or {}
        other_operator = other_data.get("operatorCredentials") or {}
        other_lookup = {
            "backupStorage.bucket": other_backup.get("bucket"),
            "backupStorage.bindingRef": other_backup.get("bindingRef") or other_backup.get("fingerprint"),
            "assetStorage.bucket": other_asset.get("bucket"),
            "assetStorage.endpoint": other_asset.get("endpoint"),
            "assetStorage.bindingRef": other_asset.get("bindingRef") or other_asset.get("fingerprint"),
            "outboundComms.smtpHost": other_outbound.get("smtpHost"),
            "operatorCredentials.bindingRef": other_operator.get("bindingRef") or other_operator.get("fingerprint"),
        }
        for target_name, raw_target in sorted((other_outbound.get("webhookTargets") or {}).items()):
            other_lookup[f"outboundComms.webhookTargets.{target_name}"] = raw_target

        for label, value, current_shared, current_rationale in candidates:
            other_value, other_shared, other_rationale = normalize_binding_value(
                other_lookup.get(label)
            )
            if other_value != value:
                continue
            if current_shared != other_shared:
                issues.append(
                    f"{label} matches {other_env} but shared declaration does not match on both manifests"
                )
                continue
            if current_shared and (
                not current_rationale or not other_rationale or current_rationale != other_rationale
            ):
                issues.append(
                    f"{label} matches {other_env} but sharedRationale is missing or inconsistent"
                )
                continue
            if not current_shared:
                issues.append(f"{label} matches {other_env} without explicit shared binding approval")
    return issues


def metadata_name(document: dict[str, Any]) -> str | None:
    metadata = document.get("metadata") or {}
    return metadata.get("name")


def rendered_has_resource(documents: list[dict[str, Any]], kind: str, name: str) -> bool:
    return any(document.get("kind") == kind and metadata_name(document) == name for document in documents)


def rendered_references_secret(documents: list[dict[str, Any]], name: str) -> bool:
    for document in documents:
        for node in walk(document):
            if not isinstance(node, dict):
                continue
            if node.get("secretName") == name:
                return True
            secret_ref = node.get("secretRef")
            if isinstance(secret_ref, dict) and secret_ref.get("name") == name:
                return True
            secret_key_ref = node.get("secretKeyRef")
            if isinstance(secret_key_ref, dict) and secret_key_ref.get("name") == name:
                return True
    return False


def parse_binding_ref(ref: Any) -> tuple[str, str, list[str]] | None:
    if not isinstance(ref, str):
        return None
    match = re.match(r"^(?P<scheme>[a-z][a-z0-9-]*)://(?P<namespace>[^/]+)/(?P<path>.+)$", ref)
    if not match:
        return None
    segments = [segment for segment in match.group("path").split("/") if segment]
    if not segments:
        return None
    return match.group("scheme"), match.group("namespace"), segments


def binding_ref_format_error(
    label: str,
    ref: Any,
    *,
    allowed_schemes: set[str] | None = None,
    exact_segment_count: int | None = None,
    allowed_leading_segments: set[str] | None = None,
) -> str | None:
    parsed = parse_binding_ref(ref)
    if parsed is None:
        return f"{label} must use <scheme>://<namespace>/<binding> format"
    scheme, namespace, segments = parsed
    if allowed_schemes and scheme not in allowed_schemes:
        allowed = ", ".join(sorted(allowed_schemes))
        return f"{label} must use one of the allowed schemes: {allowed}"
    if exact_segment_count is not None and len(segments) != exact_segment_count:
        return f"{label} must include exactly {exact_segment_count} binding path segment(s)"
    if allowed_leading_segments is not None:
        if len(segments) < 2 or segments[0] not in allowed_leading_segments:
            allowed = ", ".join(sorted(allowed_leading_segments))
            return f"{label} must use one of the allowed binding kinds: {allowed}"
    return None


def secret_binding_name(ref: Any) -> str | None:
    parsed = parse_binding_ref(ref)
    if parsed is None:
        return None
    scheme, _, segments = parsed
    if scheme != "secret" or len(segments) != 1:
        return None
    return segments[0] or None


def rendered_references_image_pull_secret(documents: list[dict[str, Any]], name: str) -> bool:
    for document in documents:
        if document.get("kind") == "ServiceAccount":
            for entry in document.get("imagePullSecrets") or []:
                if isinstance(entry, dict) and entry.get("name") == name:
                    return True
        for node in walk(document):
            if not isinstance(node, dict):
                continue
            for entry in node.get("imagePullSecrets") or []:
                if isinstance(entry, dict) and entry.get("name") == name:
                    return True
    return False


def config_value(documents: list[dict[str, Any]], name: str) -> str | None:
    for document in documents:
        if document.get("kind") != "ConfigMap":
            continue
        data = document.get("data") or {}
        if name in data:
            return str(data[name])
    return None


def primary_containers(document: dict[str, Any]) -> list[tuple[str | None, dict[str, Any], dict[str, str | None]]]:
    if document.get("kind") not in {"Deployment", "StatefulSet", "DaemonSet"}:
        return []
    spec = (((document.get("spec") or {}).get("template") or {}).get("spec") or {})
    volumes = {
        volume.get("name"): ((volume.get("secret") or {}).get("secretName"))
        for volume in spec.get("volumes") or []
        if isinstance(volume, dict)
    }
    containers: list[tuple[str | None, dict[str, Any], dict[str, str | None]]] = []
    for container in spec.get("containers") or []:
        name = container.get("name") or ""
        if name.endswith("-service") or name == "spring-cloud-gateway":
            containers.append((metadata_name(document), container, volumes))
    return containers


def env_value(container: dict[str, Any], name: str) -> str | None:
    for entry in container.get("env") or []:
        if entry.get("name") == name:
            return entry.get("value")
    return None


def has_secret_mount(
    container: dict[str, Any],
    volumes: dict[str, str | None],
    secret_name: str,
    required_mount: str,
) -> bool:
    for mount in container.get("volumeMounts") or []:
        mounted_secret = volumes.get(mount.get("name"))
        mount_path = str(mount.get("mountPath") or "")
        if mounted_secret == secret_name and mount_path == required_mount:
            return True
    return False


def has_secret_reference(documents: list[dict[str, Any]], name: str) -> bool:
    for document in documents:
        for _, container, volumes in primary_containers(document):
            for mounted_secret in volumes.values():
                if mounted_secret == name:
                    return True
            for entry in container.get("envFrom") or []:
                secret_ref = entry.get("secretRef")
                if isinstance(secret_ref, dict) and secret_ref.get("name") == name:
                    return True
    return False


def extract_service_images(rendered_text: str) -> list[str]:
    pattern = re.compile(
        r"^[ \t]*image:[ \t]*(ghcr\.io/benhook1013/(?:[^ \t\r\n]+-service|spring-cloud-gateway)(?:[@:][^ \t\r\n]+))",
        re.MULTILINE,
    )
    return sorted(set(pattern.findall(rendered_text)))


def append_result(
    check_results: list[CheckResult],
    policy_id: str,
    required: bool,
    status: str,
    message: str,
) -> bool:
    check_results.append(CheckResult(policy_id, required, status, message))
    return required and status == "fail"


def expected_binding_checks(
    expected_bindings_path: Path,
    expected_bindings_ref: str,
    env_class: str,
    documents: list[dict[str, Any]],
) -> list[CheckResult]:
    try:
        data = load_yaml(expected_bindings_path) or {}
    except Exception as exc:
        return [
            CheckResult("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings manifest is unreadable: {exc}"),
            CheckResult("PREFLIGHT-BOOTSTRAP-001", True, "fail", "Expected-bindings manifest is unreadable"),
            CheckResult("PREFLIGHT-EXTERNAL-001", True, "fail", "Expected-bindings manifest is unreadable"),
            CheckResult("PREFLIGHT-SERVICES-001", True, "fail", "Expected-bindings manifest is unreadable"),
        ]

    results: list[CheckResult] = []
    if data.get("environment") != env_class:
        results.append(
            CheckResult("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings environment mismatch in {expected_bindings_ref}")
        )
    else:
        required_internal = [
            "internalBindings.postgres.endpoint",
            "internalBindings.postgres.credentialsRef",
            "internalBindings.redis.coordination.endpoint",
            "internalBindings.redis.cache.endpoint",
            "internalBindings.jwt.signingKeysRef",
            "internalBindings.jwt.jwksRef",
            "internalBindings.certificates.issuerRef",
            "internalBindings.certificates.workloadMtlsRef",
            "internalBindings.certificates.gatewayInternalWsListenerRef",
            "internalBindings.certificates.tcpProxyBridgeClientRef",
            "internalBindings.registry.imagePullSecretRef",
        ]
        pause_config = data.get("backupMaintenancePause")
        certificates = get(data, "internalBindings.certificates")
        backup_control_plane_declared = (
            isinstance(certificates, dict)
            and "backupControlPlaneClientRef" in certificates
        )
        pause_config_error = None
        if pause_config is None:
            exceptional_pause_enabled = False
        elif not isinstance(pause_config, dict):
            exceptional_pause_enabled = False
            pause_config_error = "backupMaintenancePause must be an object"
        elif not isinstance(pause_config.get("enabled"), bool):
            exceptional_pause_enabled = False
            pause_config_error = "backupMaintenancePause.enabled must be a boolean"
        else:
            exceptional_pause_enabled = pause_config["enabled"]
        if (
            pause_config_error is None
            and not exceptional_pause_enabled
            and backup_control_plane_declared
        ):
            pause_config_error = (
                "internalBindings.certificates.backupControlPlaneClientRef must be omitted "
                "unless backupMaintenancePause.enabled is true"
            )
        if exceptional_pause_enabled:
            required_internal.append("internalBindings.certificates.backupControlPlaneClientRef")
        missing = [key for key in required_internal if not get(data, key)]
        secret_refs = [
            get(data, "internalBindings.postgres.credentialsRef"),
            get(data, "internalBindings.jwt.signingKeysRef"),
            get(data, "internalBindings.jwt.jwksRef"),
        ]
        invalid_internal_refs = [
            error
            for error in [
                binding_ref_format_error(
                    "internalBindings.postgres.credentialsRef",
                    get(data, "internalBindings.postgres.credentialsRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.jwt.signingKeysRef",
                    get(data, "internalBindings.jwt.signingKeysRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.jwt.jwksRef",
                    get(data, "internalBindings.jwt.jwksRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.issuerRef",
                    get(data, "internalBindings.certificates.issuerRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=2,
                    allowed_leading_segments={"clusterissuers", "issuers"},
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.workloadMtlsRef",
                    get(data, "internalBindings.certificates.workloadMtlsRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.gatewayInternalWsListenerRef",
                    get(data, "internalBindings.certificates.gatewayInternalWsListenerRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=1,
                ),
                binding_ref_format_error(
                    "internalBindings.certificates.tcpProxyBridgeClientRef",
                    get(data, "internalBindings.certificates.tcpProxyBridgeClientRef"),
                    allowed_schemes={"cert-manager"},
                    exact_segment_count=1,
                ),
                (
                    binding_ref_format_error(
                        "internalBindings.certificates.backupControlPlaneClientRef",
                        get(data, "internalBindings.certificates.backupControlPlaneClientRef"),
                        allowed_schemes={"cert-manager"},
                        exact_segment_count=1,
                    )
                    if exceptional_pause_enabled
                    else None
                ),
                binding_ref_format_error(
                    "internalBindings.registry.imagePullSecretRef",
                    get(data, "internalBindings.registry.imagePullSecretRef"),
                    allowed_schemes={"secret"},
                    exact_segment_count=1,
                ),
            ]
            if error
        ]
        missing_rendered_refs = []
        for ref_value in secret_refs:
            name = secret_binding_name(ref_value)
            if name and not rendered_references_secret(documents, name):
                missing_rendered_refs.append(name)
        registry_pull_secret = secret_binding_name(get(data, "internalBindings.registry.imagePullSecretRef"))
        if pause_config_error:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    f"Expected-bindings backup maintenance configuration is invalid: {pause_config_error}",
                )
            )
        elif missing:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Expected-bindings missing internal keys: " + ", ".join(missing),
                )
            )
        elif invalid_internal_refs:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Expected-bindings internal binding refs are invalid: " + "; ".join(invalid_internal_refs),
                )
            )
        elif missing_rendered_refs:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Rendered workloads do not reference expected Secret bindings: " + ", ".join(missing_rendered_refs),
                )
            )
        elif registry_pull_secret and not rendered_references_image_pull_secret(documents, registry_pull_secret):
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    f"Rendered workloads do not reference expected image pull Secret binding: {registry_pull_secret}",
                )
            )
        else:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "pass",
                    f"Internal state/trust bindings match {expected_bindings_ref}",
                )
            )

    bootstrap_names = [
        name
        for name in (
            secret_binding_name(get(data, "internalBindings.postgres.credentialsRef")),
            secret_binding_name(get(data, "internalBindings.jwt.signingKeysRef")),
            secret_binding_name(get(data, "internalBindings.jwt.jwksRef")),
        )
        if name
    ]
    missing_bootstrap = [name for name in bootstrap_names if not rendered_references_secret(documents, name)]
    if missing_bootstrap:
        results.append(
            CheckResult(
                "PREFLIGHT-BOOTSTRAP-001",
                True,
                "fail",
                "Rendered workloads do not reference bootstrap bindings: " + ", ".join(missing_bootstrap),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-BOOTSTRAP-001",
                True,
                "pass",
                "Rendered workloads reference the minimum bootstrap secret bindings",
            )
        )

    external_requirements = [
        ("backupStorage.bucket", None),
        ("backupStorage.bindingRef", "backupStorage.fingerprint"),
        ("assetStorage.bucket", None),
        ("assetStorage.endpoint", None),
        ("assetStorage.bindingRef", "assetStorage.fingerprint"),
        ("outboundComms.smtpHost", None),
        ("operatorCredentials.bindingRef", "operatorCredentials.fingerprint"),
    ]
    missing_external = []
    for primary, alternate in external_requirements:
        if not get(data, primary) and (alternate is None or not get(data, alternate)):
            missing_external.append(primary if alternate is None else f"{primary} or {alternate}")
    webhook_targets = get(data, "outboundComms.webhookTargets")
    invalid_external_refs = [
        error
        for error in [
            binding_ref_format_error("backupStorage.bindingRef", get(data, "backupStorage.bindingRef")),
            binding_ref_format_error("assetStorage.bindingRef", get(data, "assetStorage.bindingRef")),
            binding_ref_format_error(
                "operatorCredentials.bindingRef", get(data, "operatorCredentials.bindingRef")
            ),
        ]
        if error
    ]
    if missing_external:
        results.append(
            CheckResult(
                "PREFLIGHT-EXTERNAL-001",
                True,
                "fail",
                "Expected-bindings missing external binding keys: " + ", ".join(missing_external),
            )
        )
    elif invalid_external_refs:
        results.append(
            CheckResult(
                "PREFLIGHT-EXTERNAL-001",
                True,
                "fail",
                "Expected-bindings external binding refs are invalid: " + "; ".join(invalid_external_refs),
            )
        )
    elif not isinstance(webhook_targets, dict) or not webhook_targets:
        results.append(
            CheckResult(
                "PREFLIGHT-EXTERNAL-001",
                True,
                "fail",
                "Expected-bindings missing outboundComms.webhookTargets entries",
            )
        )
    else:
        uniqueness_issues = external_binding_uniqueness_issues(
            expected_bindings_path.parent.parent, env_class, data
        )
        if uniqueness_issues:
            results.append(
                CheckResult(
                    "PREFLIGHT-EXTERNAL-001",
                    True,
                    "fail",
                    "External binding isolation check failed: " + "; ".join(uniqueness_issues),
                )
            )
        else:
            results.append(
                CheckResult(
                    "PREFLIGHT-EXTERNAL-001",
                    True,
                    "pass",
                    f"External bindings are environment-scoped in {expected_bindings_ref}",
                )
            )

    mode = get(data, "serviceDiscovery.mode")
    rendered_text = yaml.safe_dump_all(documents, sort_keys=False)
    override_lines = extract_service_discovery_overrides(rendered_text)
    if mode == "kubernetes-dns-default" and override_lines:
        results.append(
            CheckResult(
                "PREFLIGHT-SERVICES-001",
                True,
                "fail",
                "Rendered manifests contain FIREMUD_SERVICES_* overrides while expected bindings require Kubernetes DNS defaults",
            )
        )
    elif mode == "explicit-overrides":
        allowed = get(data, "serviceDiscovery.allowedOverrides")
        if not isinstance(allowed, dict) or not allowed:
            results.append(
                CheckResult(
                    "PREFLIGHT-SERVICES-001",
                    True,
                    "fail",
                    "serviceDiscovery.allowedOverrides is required for explicit-overrides mode",
                )
            )
        else:
            failures: list[str] = []
            for override_name, rendered_value in sorted(override_lines.items()):
                if override_name not in allowed:
                    failures.append(
                        f"Rendered override {override_name} is not declared in serviceDiscovery.allowedOverrides"
                    )
                    continue
                expected_value = allowed.get(override_name)
                if not isinstance(expected_value, str):
                    failures.append(f"serviceDiscovery.allowedOverrides[{override_name}] must be a string")
                    continue
                if expected_value != rendered_value:
                    failures.append(
                        f"Rendered {override_name}='{rendered_value}' does not match allowed value '{expected_value}'"
                    )
            if failures:
                results.append(
                    CheckResult(
                        "PREFLIGHT-SERVICES-001",
                        True,
                        "fail",
                        "Explicit service-discovery override values do not match expected contract: "
                        + "; ".join(failures),
                    )
                )
            elif not override_lines:
                results.append(
                    CheckResult(
                        "PREFLIGHT-SERVICES-001",
                        True,
                        "fail",
                        "No FIREMUD_SERVICES_* overrides were rendered for explicit-overrides mode",
                    )
                )
            else:
                results.append(
                    CheckResult(
                        "PREFLIGHT-SERVICES-001",
                        True,
                        "pass",
                        "Rendered FIREMUD_SERVICES_* overrides match expected explicit contract",
                    )
                )
    elif mode == "kubernetes-dns-default":
        results.append(
            CheckResult(
                "PREFLIGHT-SERVICES-001",
                True,
                "pass",
                "Rendered manifests use default in-environment service discovery",
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-SERVICES-001",
                True,
                "fail",
                "serviceDiscovery.mode must be kubernetes-dns-default or explicit-overrides",
            )
        )
    return results


def jwt_jwks_checks(documents: list[dict[str, Any]]) -> list[CheckResult]:
    inline_secret = False
    missing_secret_path: list[str] = []
    missing_signing_mount: list[str] = []
    missing_jwks_mount: list[str] = []
    global_secret_path = config_value(documents, "FIREMUD_AUTH_JWT_SECRET_PATH")
    global_jwks_path = config_value(documents, "FIREMUD_AUTH_JWKS_PATH")
    for workload_name, container, volumes in [item for document in documents for item in primary_containers(document)]:
        container_name = container.get("name") or "<unknown>"
        if env_value(container, "FIREMUD_AUTH_JWT_SECRET") is not None:
            inline_secret = True
        secret_path = env_value(container, "FIREMUD_AUTH_JWT_SECRET_PATH") or global_secret_path
        if not secret_path:
            missing_secret_path.append(f"{workload_name}/{container_name}")
        elif str(secret_path).startswith("/var/run/secrets/firemud/jwt/") and not has_secret_mount(
            container,
            volumes,
            "jwt-signing-keys",
            "/var/run/secrets/firemud/jwt",
        ):
            missing_signing_mount.append(f"{workload_name}/{container_name}")
        jwks_path = env_value(container, "FIREMUD_AUTH_JWKS_PATH") or global_jwks_path
        if (
            container_name == "account-service"
            and jwks_path
            and str(jwks_path).startswith("/var/run/secrets/firemud/jwks/")
            and not has_secret_mount(container, volumes, "jwt-jwks", "/var/run/secrets/firemud/jwks")
        ):
            missing_jwks_mount.append(f"{workload_name}/{container_name}")

    results: list[CheckResult] = []
    if inline_secret:
        results.append(CheckResult("PREFLIGHT-JWT-001", True, "fail", "Inline JWT secret material detected in rendered workloads"))
    elif missing_secret_path:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                True,
                "fail",
                "FIREMUD_AUTH_JWT_SECRET_PATH is missing for workloads: " + ", ".join(missing_secret_path),
            )
        )
    elif missing_signing_mount:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                True,
                "fail",
                "JWT signing Secret is not mounted at the configured path for workloads: " + ", ".join(missing_signing_mount),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                True,
                "pass",
                "JWT file-path contract and signing Secret mounts are satisfied",
            )
        )

    if rendered_has_resource(documents, "ConfigMap", "jwt-jwks"):
        results.append(CheckResult("PREFLIGHT-JWKS-001", True, "fail", "jwt-jwks is configured as a ConfigMap in player-facing context"))
    elif not rendered_has_resource(documents, "Secret", "jwt-jwks") and not has_secret_reference(documents, "jwt-jwks"):
        results.append(CheckResult("PREFLIGHT-JWKS-001", True, "fail", "Rendered workloads do not reference jwt-jwks as a Secret"))
    elif missing_jwks_mount:
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                True,
                "fail",
                "Account Service does not mount jwt-jwks at the configured JWKS path: " + ", ".join(missing_jwks_mount),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                True,
                "pass",
                "jwt-jwks Secret contract and Account Service mount are satisfied",
            )
        )
    return results


def git_commit_exists(root_dir: Path, commit_sha: str) -> bool:
    if not re.fullmatch(r"[0-9a-f]{7,40}", commit_sha):
        return False
    result = subprocess.run(
        ["git", "-C", str(root_dir), "cat-file", "-e", f"{commit_sha}^{{commit}}"],
        check=False,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0


def recovery_compatibility_check(
    attestation: dict[str, Any],
    rollback_mode: str,
    root_dir: Path,
    now_dt: dt.datetime,
) -> tuple[str, str]:
    recovery_compatibility = attestation.get("recoveryCompatibility")
    if not isinstance(recovery_compatibility, dict):
        return ("fail", "Attestation missing recoveryCompatibility result")
    compatibility_status = recovery_compatibility.get("compatibilityStatus")
    if compatibility_status not in RECOVERY_COMPATIBILITY_STATUSES:
        return ("fail", "Attestation recoveryCompatibility compatibilityStatus is missing or invalid")
    required_fields = (
        "baselineRecoveryRecordRef",
        "baselineRecoveryContractFingerprint",
        "candidateRecoveryContractFingerprint",
        "changedDimensions",
        "compatibilityRationale",
        "evaluatedAt",
        "evaluatorToolDigest",
        "newDrillRequired",
    )
    missing_fields = [
        field
        for field in required_fields
        if field not in recovery_compatibility
        or (field != "changedDimensions" and is_missing(recovery_compatibility[field]))
    ]
    if missing_fields:
        return (
            "fail",
            "Attestation recoveryCompatibility missing required fields: " + ", ".join(missing_fields),
        )
    string_fields = (
        "baselineRecoveryRecordRef",
        "baselineRecoveryContractFingerprint",
        "candidateRecoveryContractFingerprint",
        "compatibilityRationale",
        "evaluatorToolDigest",
    )
    invalid_string_fields = [
        field
        for field in string_fields
        if not isinstance(recovery_compatibility.get(field), str)
        or not recovery_compatibility[field].strip()
    ]
    if invalid_string_fields:
        return (
            "fail",
            "Attestation recoveryCompatibility fields must be non-empty strings: "
            + ", ".join(invalid_string_fields),
        )
    changed_dimensions = recovery_compatibility.get("changedDimensions")
    if not isinstance(changed_dimensions, list):
        return ("fail", "Attestation recoveryCompatibility changedDimensions must be a list")
    if any(not isinstance(dimension, str) or not dimension for dimension in changed_dimensions):
        return ("fail", "Attestation recoveryCompatibility changedDimensions entries must be non-empty strings")
    if not isinstance(recovery_compatibility.get("newDrillRequired"), bool):
        return ("fail", "Attestation recoveryCompatibility newDrillRequired must be a boolean")
    new_drill_required = recovery_compatibility["newDrillRequired"]
    if compatibility_status == "drill_required" and new_drill_required is not True:
        return ("fail", "recoveryCompatibility drill_required status must set newDrillRequired")
    if new_drill_required:
        backup_readiness_ref = recovery_compatibility.get("backupReadinessRef")
        if not isinstance(backup_readiness_ref, str) or not backup_readiness_ref.strip():
            return ("fail", "recoveryCompatibility newDrillRequired result must include backupReadinessRef")
    try:
        evaluated_at = parse_timestamp(
            recovery_compatibility.get("evaluatedAt"), "recoveryCompatibility.evaluatedAt"
        )
    except Exception as exc:
        return ("fail", str(exc))
    if evaluated_at > now_dt:
        return ("fail", "recoveryCompatibility.evaluatedAt is future-dated")
    try:
        generated_at = parse_timestamp(attestation.get("generatedAt"), "Attestation generatedAt")
    except Exception as exc:
        return ("fail", str(exc))
    if evaluated_at > generated_at:
        return (
            "fail",
            "recoveryCompatibility.evaluatedAt must not be after attestation generatedAt",
        )
    if compatibility_status != "compatible":
        return (
            "fail",
            "Attestation recoveryCompatibility compatibilityStatus blocks promotion: "
            + str(compatibility_status),
        )

    baseline_status, baseline_message = validate_recovery_baseline(
        root_dir,
        str(recovery_compatibility["baselineRecoveryRecordRef"]),
        str(recovery_compatibility["baselineRecoveryContractFingerprint"]),
        evaluated_at,
        now_dt,
    )
    if baseline_status != "pass":
        return ("fail", baseline_message)

    if rollback_mode == "roll-forward-only":
        if new_drill_required is not True:
            return ("fail", "roll-forward-only attestation must set recoveryCompatibility.newDrillRequired")
    elif rollback_mode == "rollback-compatible":
        if new_drill_required is True:
            return ("fail", "rollback-compatible attestation cannot require a new recovery drill")
        if changed_dimensions:
            return ("fail", "rollback-compatible attestation cannot declare changed recovery dimensions")
        if recovery_compatibility.get("baselineRecoveryContractFingerprint") != recovery_compatibility.get(
            "candidateRecoveryContractFingerprint"
        ):
            return ("fail", "rollback-compatible attestation recovery-contract fingerprint changed")
    else:
        return ("fail", "Attestation rollbackMode is missing or invalid")
    return ("pass", "Recovery compatibility evidence is valid")


def promotion_check(
    attestation_path: Path,
    images: list[str],
    root_dir: Path,
    expected_production_overlay_ref: str | None = None,
) -> tuple[str, str, str, str, str]:
    try:
        att = load_json(attestation_path)
    except Exception as exc:
        message = f"Attestation unreadable: {exc}"
        return ("fail", "unknown", message, "fail", f"Recovery compatibility attestation unreadable: {exc}")

    if not isinstance(att, dict):
        message = "Attestation must be a JSON object"
        return ("fail", "unknown", message, "fail", "Recovery compatibility attestation must be a JSON object")

    rollback_mode = str(att.get("rollbackMode", "unknown"))
    now_dt = dt.datetime.now(dt.timezone.utc)
    recovery_status, recovery_message = recovery_compatibility_check(att, rollback_mode, root_dir, now_dt)
    promotion_status, promotion_rollback_mode, promotion_message = _promotion_check(
        att,
        images,
        root_dir,
        expected_production_overlay_ref,
        now_dt,
        recovery_status,
        recovery_message,
    )
    return (
        promotion_status,
        promotion_rollback_mode,
        promotion_message,
        recovery_status,
        recovery_message,
    )


def _promotion_check(
    att: dict[str, Any],
    images: list[str],
    root_dir: Path,
    expected_production_overlay_ref: str | None,
    now_dt: dt.datetime,
    recovery_status: str,
    recovery_message: str,
) -> tuple[str, str, str]:

    missing_attestation_fields = [
        field for field in PROMOTION_ATTESTATION_REQUIRED_FIELDS if field not in att or is_missing(att[field])
    ]
    if missing_attestation_fields:
        return (
            "fail",
            str(att.get("rollbackMode", "unknown")),
            "Attestation missing required canonical fields: " + ", ".join(missing_attestation_fields),
        )

    if att.get("attestationVersion") != PROMOTION_ATTESTATION_VERSION:
        return (
            "fail",
            str(att.get("rollbackMode", "unknown")),
            f"Attestation attestationVersion must be {PROMOTION_ATTESTATION_VERSION}",
        )

    if att.get("environment") != "staging":
        return ("fail", "unknown", "Attestation environment must be staging")

    rollback_mode = str(att.get("rollbackMode", "unknown"))
    if rollback_mode not in {"rollback-compatible", "roll-forward-only"}:
        return ("fail", "unknown", "Attestation rollbackMode is missing or invalid")

    if not isinstance(att.get("productionOverlayRef"), str) or not att["productionOverlayRef"].strip():
        return ("fail", rollback_mode, "Attestation productionOverlayRef must be non-empty")
    if expected_production_overlay_ref and att.get("productionOverlayRef") != expected_production_overlay_ref:
        return ("fail", rollback_mode, "Attestation productionOverlayRef does not match the current deployment")

    if not isinstance(att.get("serviceDigests"), dict) or not att["serviceDigests"]:
        return ("fail", rollback_mode, "Attestation serviceDigests must be a non-empty object")
    if not isinstance(att.get("smokeEvidence"), list) or not att["smokeEvidence"]:
        return ("fail", rollback_mode, "Attestation smokeEvidence must be a non-empty list")
    if any(not isinstance(evidence, str) or not evidence for evidence in att["smokeEvidence"]):
        return ("fail", rollback_mode, "Attestation smokeEvidence entries must be non-empty strings")
    if not isinstance(att.get("approvedBy"), str) or not att["approvedBy"].strip():
        return ("fail", rollback_mode, "Attestation approvedBy must be non-empty")

    try:
        generated_at = parse_timestamp(att.get("generatedAt"), "Attestation generatedAt")
    except Exception as exc:
        return ("fail", rollback_mode, str(exc))
    if generated_at > now_dt:
        return ("fail", rollback_mode, "Attestation generatedAt is future-dated")

    if recovery_status != "pass":
        return ("fail", rollback_mode, recovery_message)

    service_digests = att.get("serviceDigests", {})
    expected_service_names = set()
    for image in images:
        name = image.split("/")[-1].split("@")[0].split(":")[0]
        expected_service_names.add(name)
        expected = service_digests.get(name)
        if not expected:
            return ("fail", rollback_mode, f"Missing digest for service {name} in attestation")
        if expected != image:
            return ("fail", rollback_mode, f"Digest mismatch for service {name}")
    if set(service_digests) != expected_service_names:
        missing = sorted(expected_service_names - set(service_digests))
        extra = sorted(set(service_digests) - expected_service_names)
        details = []
        if missing:
            details.append("missing " + ", ".join(missing))
        if extra:
            details.append("unexpected " + ", ".join(extra))
        return ("fail", rollback_mode, "Attestation serviceDigests do not match rendered workload images: " + "; ".join(details))
    if any(
        not isinstance(value, str) or "@sha256:" not in value
        for value in service_digests.values()
    ):
        return ("fail", rollback_mode, "Attestation serviceDigests values must be immutable image@sha256 references")

    staging_sha = att.get("stagingOverlayCommitSha", "")
    if not isinstance(staging_sha, str) or not staging_sha:
        return ("fail", rollback_mode, "Attestation missing stagingOverlayCommitSha")
    if not git_commit_exists(root_dir, staging_sha):
        return ("fail", rollback_mode, f"Staging overlay commit does not exist in Git: {staging_sha}")

    staging_event_id = att.get("stagingDeploymentEventId")
    try:
        parsed_staging_event_id = uuid.UUID(str(staging_event_id))
    except (ValueError, TypeError, AttributeError):
        return ("fail", rollback_mode, "Attestation stagingDeploymentEventId must be a UUID")
    if str(parsed_staging_event_id) != staging_event_id:
        return ("fail", rollback_mode, "Attestation stagingDeploymentEventId must use canonical UUID form")

    record_path = (
        root_dir
        / "design"
        / "operations"
        / "deployments"
        / "staging"
        / "deployments"
        / staging_sha
        / f"{staging_event_id}.json"
    )
    if not record_path.exists():
        return ("fail", rollback_mode, f"Staging deployment record not found: {record_path}")

    try:
        record = load_json(record_path)
    except Exception as exc:
        return ("fail", rollback_mode, f"Staging deployment record unreadable: {exc}")

    if not isinstance(record, dict):
        return ("fail", rollback_mode, "Staging deployment record must be a JSON object")

    required_record_fields = (
        "environment",
        "overlayCommitSha",
        "deploymentEventId",
        "appliedAt",
        "appliedBy",
        "deployStatus",
        "smokeStatus",
        "serviceDigests",
        "preflightReportPath",
        "liveStateEvidence",
        "secretComplianceSnapshotAt",
        "secretComplianceStatus",
        "secretComplianceEvidenceRef",
        "smokeEvidence",
    )
    missing_record_fields = [
        field for field in required_record_fields if field not in record or is_missing(record[field])
    ]
    if missing_record_fields:
        return (
            "fail",
            rollback_mode,
            "Staging deployment record missing required canonical fields: " + ", ".join(missing_record_fields),
        )
    if not isinstance(record.get("appliedBy"), str) or not record["appliedBy"].strip():
        return ("fail", rollback_mode, "Staging deployment record appliedBy must be non-empty")
    if not isinstance(record.get("serviceDigests"), dict) or not record["serviceDigests"]:
        return ("fail", rollback_mode, "Staging deployment record serviceDigests must be a non-empty object")
    if not isinstance(record.get("smokeEvidence"), list) or not record["smokeEvidence"]:
        return ("fail", rollback_mode, "Staging deployment record smokeEvidence must be a non-empty list")
    if any(not isinstance(evidence, str) or not evidence for evidence in record["smokeEvidence"]):
        return ("fail", rollback_mode, "Staging deployment record smokeEvidence entries must be non-empty strings")
    if record["smokeEvidence"] != att["smokeEvidence"]:
        return ("fail", rollback_mode, "Staging deployment record smokeEvidence does not match the attestation")

    record_timestamps: dict[str, dt.datetime] = {}
    for field in ("appliedAt", "secretComplianceSnapshotAt"):
        try:
            record_timestamp = parse_timestamp(record.get(field), f"Staging deployment record {field}")
        except Exception as exc:
            return ("fail", rollback_mode, str(exc))
        if record_timestamp > now_dt:
            return ("fail", rollback_mode, f"Staging deployment record {field} is future-dated")
        record_timestamps[field] = record_timestamp

    if record.get("environment") != "staging":
        return ("fail", rollback_mode, "Staging deployment record has wrong environment")
    if record.get("overlayCommitSha") != staging_sha:
        return ("fail", rollback_mode, "Staging deployment record overlayCommitSha mismatch")
    if record.get("deploymentEventId") != staging_event_id:
        return ("fail", rollback_mode, "Staging deployment record deploymentEventId mismatch")

    record_digests = record["serviceDigests"]
    if record_digests != service_digests:
        return ("fail", rollback_mode, "Staging deployment record serviceDigests do not match the attestation")

    if record.get("deployStatus") != "pass":
        return ("fail", rollback_mode, "Staging deployment record deployStatus must be pass")
    if record.get("smokeStatus") != "pass":
        return ("fail", rollback_mode, "Staging deployment record smokeStatus must be pass")
    preflight_ref = record.get("preflightReportPath")
    if not isinstance(preflight_ref, str) or not preflight_ref.strip():
        return ("fail", rollback_mode, "Staging deployment record preflightReportPath must be non-empty")
    expected_preflight_ref = (
        f"design/operations/deployments/staging/preflight/{staging_sha}/{staging_event_id}.json"
    )
    if preflight_ref != expected_preflight_ref:
        return ("fail", rollback_mode, "Staging deployment record must reference the canonical preflight report path")
    preflight_path = resolve_repo_path(root_dir, str(preflight_ref))
    if not preflight_path.exists():
        return ("fail", rollback_mode, f"Staging preflight report not found: {preflight_ref}")
    try:
        preflight_report = load_json(preflight_path)
    except Exception as exc:
        return ("fail", rollback_mode, f"Staging preflight report unreadable: {exc}")
    preflight_status, preflight_message = validate_preflight_report(
        preflight_report,
        "staging",
        "design/operations/environments/staging/expected-bindings.yaml",
        staging_sha,
        now_dt=now_dt,
        expected_deployment_event_id=str(record["deploymentEventId"]),
        completed_by=record_timestamps["appliedAt"],
    )
    if preflight_status != "pass":
        return ("fail", rollback_mode, preflight_message)

    live_state = record.get("liveStateEvidence")
    if not isinstance(live_state, dict):
        return ("fail", rollback_mode, "Staging deployment record missing liveStateEvidence")
    if live_state.get("status") != "pass":
        return ("fail", rollback_mode, "Staging deployment record liveStateEvidence must be pass")
    if not isinstance(live_state.get("observedOverlaySha"), str) or live_state.get("observedOverlaySha") != staging_sha:
        return ("fail", rollback_mode, "Staging deployment record liveStateEvidence overlay SHA mismatch")
    if not isinstance(live_state.get("observedDigests"), dict) or not live_state["observedDigests"]:
        return ("fail", rollback_mode, "Staging deployment record missing observedDigests")
    observed_digests = live_state.get("observedDigests", {})
    if observed_digests != service_digests:
        return ("fail", rollback_mode, "Staging live-state evidence observedDigests do not match the attestation")

    secret_status = record.get("secretComplianceStatus")
    secret_ref = record.get("secretComplianceEvidenceRef")
    if secret_status != "pass":
        return ("fail", rollback_mode, "Staging deployment record secretComplianceStatus must be pass")
    if not isinstance(secret_ref, str) or not secret_ref.strip():
        return ("fail", rollback_mode, "Staging deployment record secretComplianceEvidenceRef must be non-empty")
    secret_path = resolve_repo_path(root_dir, str(secret_ref))
    if not secret_path.exists():
        return ("fail", rollback_mode, f"Staging secret compliance evidence not found: {secret_ref}")
    try:
        secret_evidence = load_json(secret_path)
    except Exception as exc:
        return ("fail", rollback_mode, f"Staging secret compliance evidence unreadable: {exc}")
    required_secret_classes = {
        "jwt-signing-keys-jwks",
        "postgres-application-credentials",
        "backup-object-store-credentials",
        "operator-credentials",
    }
    if not isinstance(secret_evidence, dict):
        return ("fail", rollback_mode, "Staging secret compliance evidence must be a JSON object")
    records = secret_evidence.get("records", {})
    if not isinstance(records, dict):
        return ("fail", rollback_mode, "Staging secret compliance evidence records must be an object")
    for key in required_secret_classes:
        rec = records.get(key)
        if not isinstance(rec, dict):
            return ("fail", rollback_mode, f"Staging secret compliance evidence missing record: {key}")
        immutable_id = rec.get("immutableArtifactId")
        if not isinstance(immutable_id, str) or not re.fullmatch(
            r"[^\s]+:sha256:[0-9a-fA-F]{64}", immutable_id
        ):
            return ("fail", rollback_mode, f"Staging secret compliance evidence record is not immutable: {key}")

    return ("pass", rollback_mode, "Production promotion attestation and staging deployment evidence are valid")


def backup_readiness_check(path: Path, now: str, deployment_ref: str, root_dir: Path) -> tuple[str, str]:
    try:
        data = load_json(path)
    except Exception as exc:
        return ("fail", f"Backup-readiness evidence unreadable: {exc}")
    if not isinstance(data, dict):
        return ("fail", "Backup-readiness evidence must be a JSON object")

    if data.get("environment") != "production":
        return ("fail", "Backup-readiness evidence must target production")
    if data.get("rollbackMode") != "roll-forward-only":
        return ("fail", "Backup-readiness evidence rollbackMode must be roll-forward-only")
    missing_fields = [
        field
        for field in BACKUP_READINESS_REQUIRED_FIELDS
        if field not in data or data[field] in (None, "", [], {})
    ]
    if missing_fields:
        return ("fail", "Backup-readiness evidence missing required target-state fields: " + ", ".join(missing_fields))
    if deployment_ref and str(data.get("deploymentRef")) != str(deployment_ref):
        return ("fail", "Backup-readiness evidence deploymentRef does not match the current deployment")
    attestation_ref = str(data.get("promotionAttestationRef", ""))
    attestation_path = (root_dir / attestation_ref).resolve()
    if not attestation_path.exists():
        return ("fail", "Backup-readiness evidence references missing promotionAttestationRef")

    if not isinstance(data.get("evidenceRefs"), list) or not data["evidenceRefs"]:
        return ("fail", "Backup-readiness evidence evidenceRefs must be a non-empty list")
    if data.get("backupCoverage") != "environment-wide-postgresql":
        return ("fail", "Backup-readiness evidence backupCoverage must be environment-wide-postgresql")
    if not isinstance(data.get("sourceServiceDigests"), dict) or not data["sourceServiceDigests"]:
        return ("fail", "Backup-readiness evidence sourceServiceDigests must be a non-empty object")
    if not isinstance(data.get("candidateServiceDigests"), dict) or not data["candidateServiceDigests"]:
        return ("fail", "Backup-readiness evidence candidateServiceDigests must be a non-empty object")

    try:
        now_dt = parse_timestamp(now, "current time")
        evidence_timestamps = {
            name: parse_timestamp(data.get(name), name)
            for name in (
                "assessedAt",
                "backupLastSuccessAt",
                "backupVerifyLastSuccessAt",
                "restoreDrillLastSuccessAt",
            )
        }
    except Exception as exc:
        return ("fail", str(exc))

    future_timestamps = [name for name, timestamp in evidence_timestamps.items() if timestamp > now_dt]
    if future_timestamps:
        return ("fail", "Backup-readiness evidence contains future-dated timestamps: " + ", ".join(future_timestamps))

    backup_ts = evidence_timestamps["backupLastSuccessAt"]
    verify_ts = evidence_timestamps["backupVerifyLastSuccessAt"]
    drill_ts = evidence_timestamps["restoreDrillLastSuccessAt"]
    if (now_dt - backup_ts).total_seconds() > 90 * 60:
        return ("fail", "Backup-readiness evidence is stale: backupLastSuccessAt older than 90 minutes")
    if (now_dt - verify_ts).total_seconds() > 36 * 60 * 60:
        return ("fail", "Backup-readiness evidence is stale: backupVerifyLastSuccessAt older than 36 hours")
    if (now_dt - drill_ts).total_seconds() > 30 * 24 * 60 * 60:
        return ("fail", "Backup-readiness evidence is stale: restoreDrillLastSuccessAt older than 30 days")

    try:
        attestation = load_json(attestation_path)
    except Exception as exc:
        return ("fail", f"Backup-readiness attestation unreadable: {exc}")
    if not isinstance(attestation, dict):
        return ("fail", "Backup-readiness attestation must be a JSON object")
    if attestation.get("rollbackMode") != "roll-forward-only":
        return ("fail", "Backup-readiness evidence does not match a roll-forward-only attestation")
    recovery_compatibility = attestation.get("recoveryCompatibility")
    if not isinstance(recovery_compatibility, dict) or recovery_compatibility.get("compatibilityStatus") != "compatible":
        return ("fail", "Backup-readiness evidence references an attestation without a compatible recoveryCompatibility result")
    if recovery_compatibility.get("newDrillRequired") is not True:
        return ("fail", "roll-forward-only backup-readiness evidence requires recoveryCompatibility.newDrillRequired")
    if not recovery_compatibility.get("backupReadinessRef"):
        return ("fail", "roll-forward-only backup-readiness evidence missing recoveryCompatibility.backupReadinessRef")
    referenced_readiness_path = (root_dir / str(recovery_compatibility["backupReadinessRef"])).resolve()
    if referenced_readiness_path != path.resolve():
        return ("fail", "Backup-readiness evidence does not match recoveryCompatibility.backupReadinessRef")
    if data.get("baselineRecoveryRecordRef") != recovery_compatibility.get("baselineRecoveryRecordRef"):
        return ("fail", "Backup-readiness evidence baselineRecoveryRecordRef does not match the attestation")
    try:
        compatibility_evaluated_at = parse_timestamp(
            recovery_compatibility.get("evaluatedAt"), "recoveryCompatibility.evaluatedAt"
        )
    except Exception as exc:
        return ("fail", str(exc))
    baseline_status, baseline_message = validate_recovery_baseline(
        root_dir,
        str(data["baselineRecoveryRecordRef"]),
        str(recovery_compatibility.get("baselineRecoveryContractFingerprint", "")),
        compatibility_evaluated_at,
        now_dt,
    )
    if baseline_status != "pass":
        return ("fail", baseline_message)
    if attestation.get("serviceDigests") != data.get("candidateServiceDigests"):
        return ("fail", "Backup-readiness evidence candidateServiceDigests do not match the attestation")
    if data.get("recoveryContractFingerprint") != recovery_compatibility.get("candidateRecoveryContractFingerprint"):
        return ("fail", "Backup-readiness evidence recoveryContractFingerprint does not match the attestation")

    return (
        "fail",
        "Roll-forward-only promotion remains blocked until canonical recovery-controller, "
        "participant, confidentiality, hardening, and controlled-reopen evidence validation is implemented",
    )


def production_recovery_check(
    compatibility_status: str,
    rollback_mode: str,
    compatibility_message: str,
    backup_readiness_evidence: str,
    deployment_ref: str,
    root_dir: Path,
) -> tuple[str, str]:
    if rollback_mode == "rollback-compatible":
        return (compatibility_status, compatibility_message)
    if rollback_mode != "roll-forward-only":
        return ("fail", "Recovery compatibility cannot be evaluated because attestation rollbackMode is invalid")
    if compatibility_status != "pass":
        return ("fail", compatibility_message)
    if not backup_readiness_evidence:
        return ("fail", "FIREMUD_BACKUP_READINESS_EVIDENCE is required for roll-forward-only promotions")
    backup_path = Path(backup_readiness_evidence)
    if not backup_path.exists():
        return ("fail", f"Backup-readiness evidence file not found: {backup_readiness_evidence}")
    return backup_readiness_check(backup_path, utc_now(), deployment_ref, root_dir)


def production_traffic_check() -> tuple[str, str]:
    return (
        "fail",
        "Production traffic-open gate unavailable: durable environment-wide "
        "recovery-controller authority is not implemented; checked-in projections "
        "and caller-supplied tenant/region/timestamp evidence cannot authorize traffic",
    )


def write_report(
    output_path: Path,
    env_class: str,
    deployment_ref: str,
    started_at: str,
    completed_at: str,
    check_results: list[CheckResult],
    context: str,
    expected_bindings_ref: str,
    deployment_event_id: str,
    traffic_open_event: str,
) -> None:
    if env_class == "hobby-self-hosted":
        deployment_ref_obj = {"manifestRef": deployment_ref}
    else:
        deployment_ref_obj = {"overlayCommitSha": deployment_ref}
    report: dict[str, Any] = {
        "environment": env_class,
        "deploymentRef": deployment_ref_obj,
        "deploymentEventId": deployment_event_id,
        "trafficOpenEvent": traffic_open_event or None,
        "startedAt": started_at,
        "completedAt": completed_at,
        "checkResults": [
            {
                "policyId": check.policy_id,
                "required": check.required,
                "status": check.status,
                "message": check.message,
            }
            for check in check_results
        ],
        "expectedBindingsRef": expected_bindings_ref,
        "toolVersion": "preflight.py-v1",
        "context": context,
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    if len(sys.argv) != 2:
        usage()
    env_class = sys.argv[1]
    if env_class not in {"staging", "production", "hobby-self-hosted"}:
        usage()

    root_dir = repo_root()
    context = os.environ.get("FIREMUD_PREFLIGHT_CONTEXT", "operator")
    if context not in {"operator", "ci-static"}:
        fail(f"Invalid FIREMUD_PREFLIGHT_CONTEXT: {context}")
    deployment_ref = os.environ.get("FIREMUD_DEPLOYMENT_REF") or run(["git", "rev-parse", "--short=12", "HEAD"]).strip()
    waiver_path = os.environ.get("FIREMUD_PREFLIGHT_WAIVER", "")
    if waiver_path:
        fail("Preflight waiver execution remains blocked until one-time consumption authority is implemented")
    deployment_event_id = str(uuid.uuid4())
    expected_bindings_ref = f"design/operations/environments/{env_class}/expected-bindings.yaml"
    expected_bindings_path = root_dir / expected_bindings_ref
    if not expected_bindings_path.exists():
        fail(f"Expected-bindings manifest not found: {expected_bindings_path}")

    if env_class == "hobby-self-hosted":
        render_path_env = os.environ.get("FIREMUD_PREFLIGHT_RENDER_PATH")
        if not render_path_env:
            fail("FIREMUD_PREFLIGHT_RENDER_PATH is required for hobby-self-hosted preflight.")
        render_path = Path(render_path_env)
        if not render_path.is_absolute():
            render_path = root_dir / render_path
        if render_path.is_dir():
            rendered = run(["kubectl", "kustomize", str(render_path)])
        elif render_path.is_file():
            rendered = render_path.read_text(encoding="utf-8")
        else:
            fail(f"FIREMUD_PREFLIGHT_RENDER_PATH does not exist: {render_path}")
    else:
        overlay_name = "stage" if env_class == "staging" else "prod"
        rendered = run(["kubectl", "kustomize", str(root_dir / "k8s" / "overlays" / overlay_name)])

    documents = parse_documents(rendered)
    default_output = default_preflight_output_path(
        root_dir,
        env_class,
        deployment_ref,
        deployment_event_id,
    )
    output_path = Path(os.environ.get("FIREMUD_PREFLIGHT_OUTPUT", str(default_output)))
    started_at = utc_now()
    traffic_open_event = os.environ.get("FIREMUD_TRAFFIC_OPEN_EVENT", "")
    if traffic_open_event not in {"", "first-live", "reopen"}:
        fail(f"Invalid FIREMUD_TRAFFIC_OPEN_EVENT: {traffic_open_event}")

    check_results: list[CheckResult] = []
    has_required_failure = False

    for check in expected_binding_checks(expected_bindings_path, expected_bindings_ref, env_class, documents):
        has_required_failure = append_result(
            check_results,
            check.policy_id,
            check.required,
            check.status,
            check.message,
        ) or has_required_failure

    service_images = extract_service_images(rendered)
    if env_class == "hobby-self-hosted":
        if not service_images:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-DIGEST-002", False, "not_applicable", "No workload images found for hobby manifest rendering",
            ) or has_required_failure
        elif any("@sha256:" not in image for image in service_images):
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-DIGEST-002", False, "fail", "One or more hobby workload images are not digest-pinned",
            ) or has_required_failure
        else:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-DIGEST-002", False, "pass", "All hobby workload images are digest-pinned",
            ) or has_required_failure
        has_required_failure = append_result(
            check_results,
            "PREFLIGHT-DIGEST-001", False, "not_applicable", "Overlay digest policy does not apply to hobby deployments",
        ) or has_required_failure
    else:
        if not service_images:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-DIGEST-001", True, "fail", "No workload images found in rendered overlay",
            ) or has_required_failure
        elif any("@sha256:" not in image for image in service_images):
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-DIGEST-001", True, "fail", "Staging/production overlay contains non-digest service image references",
            ) or has_required_failure
        else:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-DIGEST-001", True, "pass", "All rendered workload images are digest-pinned",
            ) or has_required_failure
        has_required_failure = append_result(
            check_results,
            "PREFLIGHT-DIGEST-002", False, "not_applicable", "Hobby digest advisory does not apply to overlay deployment",
        ) or has_required_failure

    secret_check_failed = False
    if context == "ci-static":
        for secret_name in ("postgres-credentials", "jwt-signing-keys", "jwt-jwks"):
            if not rendered_references_secret(documents, secret_name):
                has_required_failure = append_result(
                    check_results,
                    "PREFLIGHT-SECRETS-001", True, "fail", f"Rendered workloads do not reference required Secret binding: {secret_name}",
                ) or has_required_failure
                secret_check_failed = True
                break
        if not secret_check_failed:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-SECRETS-001", True, "pass", "Rendered workloads reference required player-facing Secret bindings",
            ) or has_required_failure
    else:
        for secret_name in ("postgres-credentials", "jwt-signing-keys", "jwt-jwks"):
            result = subprocess.run(["kubectl", "get", "secret", "-n", "firemud", secret_name], capture_output=True, text=True)
            if result.returncode != 0:
                has_required_failure = append_result(
                    check_results,
                    "PREFLIGHT-SECRETS-001", True, "fail", f"Missing required Secret in cluster: firemud/{secret_name}",
                ) or has_required_failure
                secret_check_failed = True
                break
        if not secret_check_failed:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-SECRETS-001", True, "pass", "Required player-facing Secrets exist in the target cluster",
            ) or has_required_failure

    for check in jwt_jwks_checks(documents):
        has_required_failure = append_result(
            check_results,
            check.policy_id, check.required, check.status, check.message,
        ) or has_required_failure

    gw_value = None
    for document in documents:
        for _, container, _ in primary_containers(document):
            value = env_value(container, "GATEWAY_WS_URL")
            if value:
                gw_value = value
                break
        if gw_value:
            break
    if not gw_value:
        has_required_failure = append_result(check_results, "PREFLIGHT-BRIDGE-001", True, "fail", "GATEWAY_WS_URL is not explicitly configured") or has_required_failure
    elif not gw_value.startswith("wss://"):
        has_required_failure = append_result(check_results, "PREFLIGHT-BRIDGE-001", True, "fail", "GATEWAY_WS_URL must use wss:// in player-facing environments") or has_required_failure
    elif "spring-cloud-gateway-mtls" not in gw_value:
        has_required_failure = append_result(check_results, "PREFLIGHT-BRIDGE-001", True, "fail", "GATEWAY_WS_URL does not target the internal gateway mTLS listener") or has_required_failure
    else:
        has_required_failure = append_result(check_results, "PREFLIGHT-BRIDGE-001", True, "pass", "Gateway bridge alignment is valid") or has_required_failure

    coord_host = config_value(documents, "FIREMUD_REDIS_COORD_HOST")
    coord_port = config_value(documents, "FIREMUD_REDIS_COORD_PORT")
    cache_host = config_value(documents, "FIREMUD_REDIS_CACHE_HOST")
    cache_port = config_value(documents, "FIREMUD_REDIS_CACHE_PORT")
    if not coord_host or not cache_host:
        has_required_failure = append_result(check_results, "PREFLIGHT-REDIS-001", True, "fail", "Could not resolve both Coordination and Cache Redis endpoints") or has_required_failure
    elif f"{coord_host}:{coord_port}" == f"{cache_host}:{cache_port}":
        has_required_failure = append_result(check_results, "PREFLIGHT-REDIS-001", True, "fail", "Coordination and Cache Redis endpoints resolve to the same host:port") or has_required_failure
    else:
        has_required_failure = append_result(check_results, "PREFLIGHT-REDIS-001", True, "pass", "Redis role split contract is satisfied") or has_required_failure

    promotion_attestation = os.environ.get("FIREMUD_PROMOTION_ATTESTATION", "")
    backup_readiness_evidence = os.environ.get("FIREMUD_BACKUP_READINESS_EVIDENCE", "")
    if env_class != "production":
        has_required_failure = append_result(check_results, "PREFLIGHT-PROMOTION-001", False, "not_applicable", "Promotion attestation applies only to production") or has_required_failure
        has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-001", False, "not_applicable", "Recovery compatibility applies only to production promotions") or has_required_failure
    elif context == "ci-static" and not promotion_attestation:
        has_required_failure = append_result(check_results, "PREFLIGHT-PROMOTION-001", False, "not_applicable", "Static CI validation without production attestation context") or has_required_failure
        has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-001", False, "not_applicable", "Static CI validation without production attestation context") or has_required_failure
    else:
        if not promotion_attestation:
            has_required_failure = append_result(check_results, "PREFLIGHT-PROMOTION-001", True, "fail", "FIREMUD_PROMOTION_ATTESTATION is required for production operator preflight") or has_required_failure
            has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-001", True, "fail", "Recovery compatibility cannot be evaluated without a promotion attestation") or has_required_failure
        elif not Path(promotion_attestation).exists():
            has_required_failure = append_result(check_results, "PREFLIGHT-PROMOTION-001", True, "fail", f"Attestation file not found: {promotion_attestation}") or has_required_failure
            has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-001", True, "fail", "Recovery compatibility cannot be evaluated because the promotion attestation is missing") or has_required_failure
        else:
            (
                promotion_status,
                recovery_rollback_mode,
                promotion_message,
                recovery_status,
                recovery_message,
            ) = promotion_check(
                Path(promotion_attestation),
                service_images,
                root_dir,
                expected_production_overlay_ref=deployment_ref,
            )
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-PROMOTION-001",
                True,
                promotion_status,
                promotion_message,
            ) or has_required_failure
            recovery_status, recovery_message = production_recovery_check(
                recovery_status,
                recovery_rollback_mode,
                recovery_message,
                backup_readiness_evidence,
                deployment_ref,
                root_dir,
            )
            has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-001", True, recovery_status, recovery_message) or has_required_failure

    if env_class == "production":
        if not traffic_open_event:
            has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-002", False, "not_applicable", "Production traffic-open backup gate applies only to first-live or reopen events") or has_required_failure
        else:
            traffic_status, traffic_message = production_traffic_check()
            has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-002", True, traffic_status, traffic_message) or has_required_failure
    else:
        has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-002", False, "not_applicable", "Production traffic-open backup gate applies only to production") or has_required_failure

    if env_class == "hobby-self-hosted":
        if not traffic_open_event:
            has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-003", False, "not_applicable", "Hobby traffic-open backup gate applies only to first-live or reopen events") or has_required_failure
        else:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-BACKUP-003",
                True,
                "fail",
                "Hobby traffic-open gate unavailable: durable environment-wide recovery-controller "
                "authority is not implemented; checked-in projections and backup-compliance evidence "
                "cannot authorize traffic",
            ) or has_required_failure
    else:
        has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-003", False, "not_applicable", "Hobby traffic-open backup gate applies only to hobby-self-hosted") or has_required_failure

    completed_at = utc_now()
    write_report(
        output_path,
        env_class,
        deployment_ref,
        started_at,
        completed_at,
        check_results,
        context,
        expected_bindings_ref,
        deployment_event_id,
        traffic_open_event,
    )

    if has_required_failure:
        print(f"Preflight failed; report written to: {output_path}", file=sys.stderr)
        return 1

    print(f"Preflight passed; report written to: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
