#!/usr/bin/env python3

from __future__ import annotations

import datetime as dt
import hashlib
import itertools
import json
import os
import re
import subprocess
import sys
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, NoReturn
from urllib.parse import urlsplit

import yaml

DEV_TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(DEV_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(DEV_TOOLS_DIR))

from evidence_digest import canonical_evidence_digest

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

    @property
    def category(self) -> str:
        return PREFLIGHT_POLICY_CATALOG[self.policy_id]


RECOVERY_COMPATIBILITY_STATUSES = {"compatible", "drill_required", "incompatible"}
SAFE_RECOVERY_DISPOSITIONS = {
    "converged",
    "terminalized",
    "invalidated",
}
MISSING_SEQUENCE_DISPLAY_LIMIT = 20
JsonValue = bool | int | float | str | list["JsonValue"] | dict[str, "JsonValue"] | None
JsonObject = dict[str, JsonValue]
JSON_READ_ERRORS = (OSError, UnicodeError, json.JSONDecodeError)
RECOVERY_JSON_READ_ERRORS = JSON_READ_ERRORS + (ValueError,)
YAML_READ_ERRORS = (OSError, UnicodeError, yaml.YAMLError)
TIMESTAMP_ERRORS = (TypeError, ValueError, AttributeError, OverflowError)
SECRET_LOOKUP_TIMEOUT_SECONDS = 30
JWT_CUSTODY_MODES = (
    "LEGACY_SECRET_DIAGNOSTIC",
    "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK",
    "TARGET_NON_EXPORTABLE_SIGNER",
)
IMPLEMENTED_JWT_CUSTODY_MODE = "LEGACY_SECRET_DIAGNOSTIC"
LEGACY_PLAYER_JWKS_REF = "secret://firemud/jwt-jwks"
BRIDGE_WS_PATHS = {
    "FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH": "/tls/client.crt",
    "FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH": "/tls/client.key",
    "FIREMUD_GATEWAY_WS_CA_CERT_PATH": "/tls/ca.crt",
}
BRIDGE_WS_SECRET_ITEM_PATHS = {
    "client.crt": "client.crt",
    "client.key": "client.key",
    "ca.crt": "ca.crt",
}
GRPC_TLS_PATH_NAMES = (
    "FIREMUD_GRPC_CERT_CHAIN_PATH",
    "FIREMUD_GRPC_PRIVATE_KEY_PATH",
    "FIREMUD_GRPC_CA_CERT_PATH",
)
BASE_SECRET_COMPLIANCE_CLASSES = frozenset(
    {
        "jwt-signing-keys-jwks",
        "postgres-application-credentials",
        "operator-credentials",
    }
)
IMMUTABLE_ARTIFACT_ID_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
DEPLOYMENT_REF_RE = re.compile(r"^[a-z0-9-]+$")
GIT_COMMIT_SHA_RE = re.compile(r"^[0-9a-f]{40}$")

PREFLIGHT_POLICY_CATALOG_VERSION = "preflight-policy-v1"
PREFLIGHT_POLICY_CATEGORIES = frozenset(
    {
        "advisory",
        "apply-blocking",
        "non-waivable-promotion-traffic-open",
    }
)

# This is the machine-readable implementation mirror of the complete
# design-owned policy catalogue. Target-state-only policies remain in the
# catalogue so their IDs and enforcement categories cannot drift while they
# are excluded from current executable reports.
PREFLIGHT_POLICY_CATALOG = {
    "PREFLIGHT-DIGEST-001": "apply-blocking",
    "PREFLIGHT-DIGEST-002": "advisory",
    "PREFLIGHT-SECRETS-001": "apply-blocking",
    "PREFLIGHT-SECRETS-002": "apply-blocking",
    "PREFLIGHT-JWT-001": "advisory",
    "PREFLIGHT-JWT-INTERIM-001": "non-waivable-promotion-traffic-open",
    "PREFLIGHT-JWKS-001": "advisory",
    "PREFLIGHT-JWT-002": "non-waivable-promotion-traffic-open",
    "PREFLIGHT-JWT-ROTATION-001": "non-waivable-promotion-traffic-open",
    "PREFLIGHT-TELNET-001": "non-waivable-promotion-traffic-open",
    "PREFLIGHT-BRIDGE-001": "apply-blocking",
    "PREFLIGHT-REDIS-001": "apply-blocking",
    "PREFLIGHT-BOOTSTRAP-001": "apply-blocking",
    "PREFLIGHT-EXTERNAL-001": "apply-blocking",
    "PREFLIGHT-SERVICES-001": "apply-blocking",
    "PREFLIGHT-PROMOTION-001": "non-waivable-promotion-traffic-open",
    "PREFLIGHT-BACKUP-001": "non-waivable-promotion-traffic-open",
    "PREFLIGHT-BACKUP-002": "non-waivable-promotion-traffic-open",
    "PREFLIGHT-BACKUP-003": "non-waivable-promotion-traffic-open",
}

DOCUMENTED_PREFLIGHT_POLICY_ID_SET = frozenset(PREFLIGHT_POLICY_CATALOG)
TARGET_ONLY_PREFLIGHT_POLICY_IDS = frozenset(
    {
        "PREFLIGHT-JWT-INTERIM-001",
        "PREFLIGHT-JWT-002",
        "PREFLIGHT-JWT-ROTATION-001",
        "PREFLIGHT-TELNET-001",
    }
)


def validate_preflight_policy_catalog(catalog: Any) -> str | None:
    if not isinstance(catalog, dict):
        return "preflight policy catalogue must be a mapping"
    catalog_ids = set(catalog)
    missing_ids = sorted(DOCUMENTED_PREFLIGHT_POLICY_ID_SET - catalog_ids)
    unknown_ids = sorted(catalog_ids - DOCUMENTED_PREFLIGHT_POLICY_ID_SET)
    if missing_ids or unknown_ids:
        details = []
        if missing_ids:
            details.append("missing policy IDs: " + ", ".join(missing_ids))
        if unknown_ids:
            details.append("unknown policy IDs: " + ", ".join(unknown_ids))
        return "invalid preflight policy catalogue: " + "; ".join(details)
    invalid_categories = sorted(
        policy_id
        for policy_id, category in catalog.items()
        if not isinstance(category, str) or category not in PREFLIGHT_POLICY_CATEGORIES
    )
    if invalid_categories:
        return (
            "invalid preflight policy catalogue categories for policy IDs: "
            + ", ".join(invalid_categories)
        )
    return None


# These are the policy results emitted by this executable. The target-state-only
# entries remain excluded until their checks are implemented and emitted by
# every applicable run.
EXPECTED_PREFLIGHT_POLICY_IDS = tuple(
    policy_id
    for policy_id in PREFLIGHT_POLICY_CATALOG
    if policy_id not in TARGET_ONLY_PREFLIGHT_POLICY_IDS
)
EXPECTED_PREFLIGHT_POLICY_ID_SET = set(EXPECTED_PREFLIGHT_POLICY_IDS)

COMMON_REQUIRED_PREFLIGHT_POLICY_IDS = {
    "PREFLIGHT-SECRETS-001",
    "PREFLIGHT-SECRETS-002",
    "PREFLIGHT-BRIDGE-001",
    "PREFLIGHT-REDIS-001",
    "PREFLIGHT-BOOTSTRAP-001",
    "PREFLIGHT-EXTERNAL-001",
    "PREFLIGHT-SERVICES-001",
}

PREFLIGHT_APPLY_MAX_AGE = dt.timedelta(minutes=30)
VERIFIED_RESTORABLE_POINT_MAX_AGE_SECONDS = 15 * 60
PLAYER_EXPERIENCE_SMOKE_VALIDATOR = (
    Path(__file__).resolve().parents[1]
    / "observability"
    / "validate-player-experience-smoke-evidence.py"
)
PROMOTION_SMOKE_EVIDENCE_ENTRY_FIELDS = {"ref", "contentDigest"}


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
JWT_ROTATION_POLICY_ID = "PREFLIGHT-JWT-ROTATION-001"
ACCEPTED_JWT_CUSTODY_PROOF_TUPLES = frozenset(
    {
        ("PREFLIGHT-JWT-INTERIM-001", "INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK", 1),
        ("PREFLIGHT-JWT-002", "TARGET_NON_EXPORTABLE_SIGNER", 1),
    }
)
PROMOTION_ATTESTATION_REQUIRED_FIELDS = (
    "attestationVersion",
    "environment",
    "stagingOverlayCommitSha",
    "stagingDeploymentEventId",
    "jwtCustodyProof",
    "jwtRotationEvidenceRef",
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
    "credentialApplicability",
    "credentialDispositions",
    "jwtHardening",
    "databaseCredentialRotation",
    "certificateReissuance",
    "externalCredentialValidation",
    "secretComplianceRefresh",
    "smokeStatus",
    "smokeEvidence",
    "evidenceRefs",
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
    "credentialApplicability",
    "credentialDispositions",
    "jwtHardening",
    "databaseCredentialRotation",
    "certificateReissuance",
    "externalCredentialValidation",
    "secretComplianceRefresh",
    "evidenceRefs",
)

CANONICAL_RECOVERY_CREDENTIAL_CLASSES = (
    "backup-storage",
    "asset-storage",
    "outbound-comms",
    "operator-credentials",
)

CANONICAL_RECOVERY_CREDENTIAL_DISPOSITION_CLASSES = (
    "jwt-signing-keys-jwks",
    "postgres-application-credentials",
    "workload-leaf",
    "bridge-leaf",
    "operator-leaf",
)
CANONICAL_RECOVERY_REQUIRED_APPLICABLE_CLASSES = (
    *CANONICAL_RECOVERY_CREDENTIAL_DISPOSITION_CLASSES,
    "backup-storage",
)
CANONICAL_RECOVERY_CREDENTIAL_UNIVERSE = (
    *CANONICAL_RECOVERY_CREDENTIAL_DISPOSITION_CLASSES,
    *CANONICAL_RECOVERY_CREDENTIAL_CLASSES,
)
RECOVERY_CREDENTIAL_APPLICABILITY = {"applicable", "not_applicable"}
RECOVERY_CREDENTIAL_DISPOSITIONS = {
    "rotated",
    "reissued",
    "rebound",
    "verified_not_restored",
}
JWT_COMPROMISE_EVIDENCE_FIELDS = (
    "compromisedKid",
    "candidateKid",
    "compromisedPublicKeyFingerprint",
    "candidatePublicKeyFingerprint",
)
JWT_REPLACEMENT_EVIDENCE_FIELDS = {
    "oldKid",
    "candidateKid",
    "oldKidRejected",
    "candidateKidAccepted",
    "validatorEvidenceRef",
}
RECOVERY_FRESHNESS_ENTRY_FIELDS = {
    "lineage",
    "field",
    "value",
    "previousField",
    "previousValue",
}

BACKUP_READINESS_REQUIRED_FIELDS = (
    "environment",
    "deploymentRef",
    "promotionAttestationRef",
    "assessedAt",
    "assessedBy",
    "rollbackMode",
    "newestVerifiedRestorablePointAt",
    "newestVerifiedRestorablePointRef",
    "newestVerifiedRestorablePointDigest",
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

VERIFIED_RESTORABLE_POINT_SCHEMA_VERSION = "verified-restorable-point/v1"
VERIFIED_RESTORABLE_POINT_DIRECTORY = (
    "design"
    "/operations/deployments/production/verified-restorable-points"
)
VERIFIED_RESTORABLE_POINT_FIELDS = (
    "schemaVersion",
    "environment",
    "databaseIdentity",
    "backupArtifact",
    "verification",
    "recordDigest",
)
VERIFIED_RESTORABLE_POINT_DATABASE_FIELDS = ("clusterIdentity", "databaseName")
VERIFIED_RESTORABLE_POINT_ARTIFACT_FIELDS = (
    "artifactRef",
    "artifactIdentity",
    "artifactDigest",
    "lineageRef",
    "snapshotAt",
)
VERIFIED_RESTORABLE_POINT_VERIFICATION_FIELDS = (
    "operationId",
    "verifiedAt",
    "restoreToolIdentity",
)
VERIFIED_RESTORABLE_POINT_TOOL_FIELDS = ("name", "version", "digest")
RECOVERY_COMPATIBILITY_VERIFIED_POINT_FIELDS = (
    "newestVerifiedRestorablePointRef",
    "newestVerifiedRestorablePointDigest",
    "newestVerifiedRestorablePointAt",
)


def canonical_verified_restorable_point_bytes(record: Any) -> bytes:
    """Return the RFC 8785 payload bytes for a verified-point record.

    The v1 schema intentionally contains only non-empty ASCII strings and
    objects. For that schema, compact ``json.dumps`` with lexicographic member
    ordering is the RFC 8785 representation: it has no number-format or
    Unicode-normalization ambiguity. The recordDigest member is the sole
    excluded member from the hash preimage.
    """

    if not isinstance(record, dict):
        raise TypeError("verified restorable point record must be an object")

    def require_exact_fields(value: Any, fields: tuple[str, ...], label: str) -> dict[str, Any]:
        if not isinstance(value, dict) or set(value) != set(fields):
            raise ValueError(f"{label} must contain exactly: {', '.join(fields)}")
        for field in fields:
            field_value = value[field]
            if not isinstance(field_value, str) or not field_value or not field_value.isascii():
                raise ValueError(f"{label}.{field} must be a non-empty ASCII string")
        return value

    if set(record) != set(VERIFIED_RESTORABLE_POINT_FIELDS):
        raise ValueError(
            "verified restorable point record must contain exactly: "
            + ", ".join(VERIFIED_RESTORABLE_POINT_FIELDS)
        )
    for field in ("schemaVersion", "environment", "recordDigest"):
        value = record[field]
        if not isinstance(value, str) or not value or not value.isascii():
            raise ValueError(f"verified restorable point {field} must be a non-empty ASCII string")
    if record["schemaVersion"] != VERIFIED_RESTORABLE_POINT_SCHEMA_VERSION:
        raise ValueError("verified restorable point schemaVersion is unsupported")

    require_exact_fields(
        record["databaseIdentity"],
        VERIFIED_RESTORABLE_POINT_DATABASE_FIELDS,
        "verified restorable point databaseIdentity",
    )
    require_exact_fields(
        record["backupArtifact"],
        VERIFIED_RESTORABLE_POINT_ARTIFACT_FIELDS,
        "verified restorable point backupArtifact",
    )
    verification = record["verification"]
    if not isinstance(verification, dict) or set(verification) != set(
        VERIFIED_RESTORABLE_POINT_VERIFICATION_FIELDS
    ):
        raise ValueError(
            "verified restorable point verification must contain exactly: "
            + ", ".join(VERIFIED_RESTORABLE_POINT_VERIFICATION_FIELDS)
        )
    for field in ("operationId", "verifiedAt"):
        value = verification[field]
        if not isinstance(value, str) or not value or not value.isascii():
            raise ValueError(f"verified restorable point verification.{field} must be a non-empty ASCII string")
    require_exact_fields(
        verification["restoreToolIdentity"],
        VERIFIED_RESTORABLE_POINT_TOOL_FIELDS,
        "verified restorable point verification.restoreToolIdentity",
    )

    payload = dict(record)
    payload.pop("recordDigest")
    return json.dumps(
        payload,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def load_verified_restorable_point(path: Path) -> Any:
    return load_json_rejecting_duplicate_keys(path)


def _validate_verified_restorable_point_reference(
    root_dir: Path,
    point_ref: str,
    expected_environment: str,
    expected_snapshot_at: str,
    expected_verified_at: str | None,
    expected_digest: str,
    expected_artifact_ref: str | None,
    *,
    context: str,
    schema_invalid_message: str | None = None,
) -> tuple[str, str]:
    point_ref_path = Path(point_ref)
    if point_ref_path.is_absolute():
        return ("fail", f"{context} reference must be repository-relative")
    verified_point_directory = (root_dir / VERIFIED_RESTORABLE_POINT_DIRECTORY).resolve()
    verified_point_path = resolve_repo_path(root_dir, point_ref).resolve()
    if not verified_point_path.is_relative_to(verified_point_directory):
        return (
            "fail",
            f"{context} reference must be under {VERIFIED_RESTORABLE_POINT_DIRECTORY}/",
        )
    if not verified_point_path.is_file():
        return ("fail", f"{context} record not found: {point_ref}")
    try:
        verified_point = load_verified_restorable_point(verified_point_path)
    except RECOVERY_JSON_READ_ERRORS as exc:
        return ("fail", f"{context} record unreadable: {exc}")

    try:
        if expected_verified_at is None:
            expected_verified_at = verified_point["verification"]["verifiedAt"]
        if expected_artifact_ref is None:
            expected_artifact_ref = verified_point["backupArtifact"]["artifactRef"]
        point_status, point_message = validate_verified_restorable_point(
            verified_point,
            expected_environment,
            expected_snapshot_at,
            expected_verified_at,
            expected_digest,
            expected_artifact_ref,
        )
    except (KeyError, TypeError):
        if schema_invalid_message is None:
            raise
        return ("fail", schema_invalid_message)
    if point_status != "pass":
        return ("fail", point_message)
    return ("pass", "")


def validate_verified_restorable_point(
    record: Any,
    expected_environment: str,
    expected_snapshot_at: str,
    expected_verified_at: str,
    expected_digest: str,
    expected_artifact_ref: str,
) -> tuple[str, str]:
    try:
        canonical_bytes = canonical_verified_restorable_point_bytes(record)
    except (TypeError, ValueError) as exc:
        return ("fail", f"Verified restorable point record is invalid: {exc}")

    if record.get("environment") != expected_environment:
        return ("fail", "Verified restorable point environment does not match the target environment")
    backup_artifact = record["backupArtifact"]
    if backup_artifact["artifactRef"] != expected_artifact_ref:
        return ("fail", "Verified restorable point artifactRef does not match backupArtifactRef")
    verification = record["verification"]
    try:
        snapshot_at = parse_timestamp(
            backup_artifact["snapshotAt"],
            "verified restorable point backupArtifact.snapshotAt",
        )
        verified_at = parse_timestamp(
            verification["verifiedAt"],
            "verified restorable point verification.verifiedAt",
        )
    except TIMESTAMP_ERRORS as exc:
        return ("fail", str(exc))
    if backup_artifact["snapshotAt"] != expected_snapshot_at:
        return ("fail", "Verified restorable point snapshotAt does not match newestVerifiedRestorablePointAt")
    if verification["verifiedAt"] != expected_verified_at:
        return ("fail", "Verified restorable point verifiedAt does not match backupVerifyLastSuccessAt")
    if verified_at < snapshot_at:
        return ("fail", "Verified restorable point verifiedAt must not precede backupArtifact.snapshotAt")

    record_digest = record["recordDigest"]
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", record_digest):
        return ("fail", "Verified restorable point recordDigest must be lowercase sha256: plus 64 hex characters")
    recomputed_digest = "sha256:" + hashlib.sha256(canonical_bytes).hexdigest()
    if record_digest != recomputed_digest:
        return ("fail", "Verified restorable point recordDigest does not match canonical UTF-8 bytes")
    if expected_digest != record_digest:
        return ("fail", "newestVerifiedRestorablePointDigest does not match the verified-point record")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", record["backupArtifact"]["artifactDigest"]):
        return ("fail", "Verified restorable point artifactDigest must be a lowercase sha256 digest")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", verification["restoreToolIdentity"]["digest"]):
        return ("fail", "Verified restorable point restore-tool digest must be a lowercase sha256 digest")
    return ("pass", "Verified restorable point is valid")


def validate_compact_verified_restorable_point(
    recovery_compatibility: dict[str, Any],
    root_dir: Path,
    now_dt: dt.datetime,
) -> tuple[str, str]:
    """Validate promotion freshness/integrity without claiming artifact authority.

    The compact compatibility result proves that its selected repository record is
    a current, canonical verified-point record. Database, artifact, lineage, and
    restore-tool registration bindings remain owner-authoritative evidence that is
    intentionally outside this compact check.
    """

    point_ref = recovery_compatibility.get("newestVerifiedRestorablePointRef")
    point_digest = recovery_compatibility.get("newestVerifiedRestorablePointDigest")
    point_at = recovery_compatibility.get("newestVerifiedRestorablePointAt")
    for field, value in (
        ("newestVerifiedRestorablePointRef", point_ref),
        ("newestVerifiedRestorablePointDigest", point_digest),
        ("newestVerifiedRestorablePointAt", point_at),
    ):
        if not isinstance(value, str) or not value.strip():
            return (
                "fail",
                f"recoveryCompatibility.{field} must be a non-empty string",
            )
    if not re.fullmatch(r"sha256:[0-9a-f]{64}", point_digest):
        return (
            "fail",
            "recoveryCompatibility.newestVerifiedRestorablePointDigest must be a lowercase sha256 digest",
        )
    try:
        point_at_dt = parse_timestamp(point_at, "recoveryCompatibility.newestVerifiedRestorablePointAt")
    except TIMESTAMP_ERRORS as exc:
        return ("fail", str(exc))
    if point_at_dt > now_dt:
        return ("fail", "recoveryCompatibility.newestVerifiedRestorablePointAt is future-dated")
    if (now_dt - point_at_dt).total_seconds() > VERIFIED_RESTORABLE_POINT_MAX_AGE_SECONDS:
        return (
            "fail",
            "recoveryCompatibility.newestVerifiedRestorablePointAt older than 15 minutes",
        )

    point_status, point_message = _validate_verified_restorable_point_reference(
        root_dir,
        point_ref,
        "production",
        point_at,
        None,
        point_digest,
        None,
        context="recoveryCompatibility verified-point",
        schema_invalid_message="recoveryCompatibility verified-point record is schema-invalid",
    )
    if point_status != "pass":
        return ("fail", point_message)
    return ("pass", "Compact verified restorable point freshness and integrity are valid")


def fail(message: str) -> NoReturn:
    print(message, file=sys.stderr)
    raise SystemExit(1)


def usage() -> NoReturn:
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


def secret_lookup_failure(secret_name: str, namespace: str = "firemud") -> str | None:
    try:
        result = subprocess.run(
            ["kubectl", "get", "secret", "-n", namespace, secret_name],
            check=False,
            capture_output=True,
            text=True,
            timeout=SECRET_LOOKUP_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.TimeoutExpired, UnicodeError) as exc:
        return f"Secret lookup could not be verified for {namespace}/{secret_name}: {exc}"
    if result.returncode == 0:
        return None

    stderr = result.stderr.strip()
    if "(NotFound)" in stderr:
        return f"Missing required Secret in cluster: {namespace}/{secret_name}"
    detail = stderr or "kubectl returned a non-zero status without stderr"
    return f"Secret lookup could not be verified for {namespace}/{secret_name}: {detail}"


def expected_player_secret_bindings(
    expected: dict[str, Any],
) -> tuple[tuple[str | None, str | None, str], ...]:
    return tuple(
        (
            secret_binding_name(get(expected, binding_path)),
            secret_binding_namespace(get(expected, binding_path)),
            binding_path,
        )
        for binding_path in (
            "internalBindings.postgres.credentialsRef",
            "internalBindings.jwt.signingKeysRef",
            "internalBindings.jwt.jwksRef",
        )
    )


def is_missing(value: Any) -> bool:
    return value in (None, "", [], {})


def validate_safe_dispositions(value: Any, label: str) -> tuple[str, str]:
    if not isinstance(value, dict) or not value:
        return ("fail", f"Recovery compatibility baseline {label} must be a non-empty object")
    for participant, result in value.items():
        disposition = result.get("disposition") if isinstance(result, dict) else None
        if disposition not in SAFE_RECOVERY_DISPOSITIONS:
            return (
                "fail",
                f"Recovery compatibility baseline {label} has unsafe or missing disposition: {participant}",
            )
    return ("pass", "")


def validate_jwt_hardening_contract(
    value: Any,
    jwt_disposition: str,
) -> tuple[str, str]:
    if not isinstance(value, dict):
        return ("fail", "Recovery compatibility baseline jwtHardening must be an object")
    compromise_classified = value.get("compromiseClassified")
    if not isinstance(compromise_classified, bool):
        return (
            "fail",
            "Recovery compatibility baseline jwtHardening.compromiseClassified must be a boolean",
        )
    resulting_key_ids = value.get("resultingKeyIds")
    if (
        not isinstance(resulting_key_ids, list)
        or not resulting_key_ids
        or any(not isinstance(key_id, str) or not key_id.strip() for key_id in resulting_key_ids)
    ):
        return (
            "fail",
            "Recovery compatibility baseline jwtHardening.resultingKeyIds must be a non-empty list of strings",
        )
    compromise_fields_present = [
        field for field in JWT_COMPROMISE_EVIDENCE_FIELDS if field in value
    ]
    if compromise_classified:
        if jwt_disposition == "verified_not_restored":
            return (
                "fail",
                "Recovery compatibility baseline compromise-classified JWT hardening cannot use verified_not_restored",
            )
        missing_fields = [
            field
            for field in JWT_COMPROMISE_EVIDENCE_FIELDS
            if not isinstance(value.get(field), str) or not value[field].strip()
        ]
        if missing_fields:
            return (
                "fail",
                "Recovery compatibility baseline compromise-classified JWT hardening missing fields: "
                + ", ".join(missing_fields),
            )
        if value["compromisedKid"] == value["candidateKid"]:
            return (
                "fail",
                "Recovery compatibility baseline compromise-classified JWT hardening requires distinct compromisedKid and candidateKid",
            )
        if value["compromisedPublicKeyFingerprint"] == value["candidatePublicKeyFingerprint"]:
            return (
                "fail",
                "Recovery compatibility baseline compromise-classified JWT hardening requires distinct compromised and candidate public-key fingerprints",
            )
        for field in (
            "compromisedPublicKeyFingerprint",
            "candidatePublicKeyFingerprint",
        ):
            if not re.fullmatch(r"sha256:[0-9a-f]{64}", value[field]):
                return (
                    "fail",
                    "Recovery compatibility baseline compromise-classified JWT hardening fingerprints must use lowercase sha256:<64 hex>: "
                    + field,
                )
        if value["candidateKid"] not in resulting_key_ids:
            return (
                "fail",
                "Recovery compatibility baseline compromise-classified JWT hardening candidateKid must be present in resultingKeyIds",
            )
        if value["compromisedKid"] in resulting_key_ids:
            return (
                "fail",
                "Recovery compatibility baseline compromise-classified JWT hardening compromisedKid must be absent from resultingKeyIds",
            )
    elif compromise_fields_present:
        return (
            "fail",
            "Recovery compatibility baseline ordinary JWT hardening must not include compromise identity fields: "
            + ", ".join(compromise_fields_present),
        )
    if not compromise_classified and jwt_disposition != "verified_not_restored":
        replacement_evidence = value.get("replacementEvidence")
        if not isinstance(replacement_evidence, dict) or set(replacement_evidence) != JWT_REPLACEMENT_EVIDENCE_FIELDS:
            return (
                "fail",
                "Recovery compatibility baseline ordinary JWT hardening replacementEvidence must contain exactly oldKid, candidateKid, oldKidRejected, candidateKidAccepted, and validatorEvidenceRef",
            )
        old_kid = replacement_evidence.get("oldKid")
        candidate_kid = replacement_evidence.get("candidateKid")
        if (
            not isinstance(old_kid, str)
            or not old_kid.strip()
            or not isinstance(candidate_kid, str)
            or not candidate_kid.strip()
        ):
            return (
                "fail",
                "Recovery compatibility baseline ordinary JWT hardening replacementEvidence IDs must be non-empty strings",
            )
        if old_kid == candidate_kid:
            return (
                "fail",
                "Recovery compatibility baseline ordinary JWT hardening replacementEvidence IDs must be distinct",
            )
        if replacement_evidence.get("oldKidRejected") is not True or replacement_evidence.get("candidateKidAccepted") is not True:
            return (
                "fail",
                "Recovery compatibility baseline ordinary JWT hardening replacementEvidence rejection/acceptance flags must be true",
            )
        if replacement_evidence.get("validatorEvidenceRef") != value.get("validatorConvergenceEvidence"):
            return (
                "fail",
                "Recovery compatibility baseline ordinary JWT hardening replacementEvidence.validatorEvidenceRef must match validatorConvergenceEvidence",
            )
        if candidate_kid not in resulting_key_ids:
            return (
                "fail",
                "Recovery compatibility baseline ordinary JWT hardening replacementEvidence candidateKid must be present in resultingKeyIds",
            )
        if old_kid in resulting_key_ids:
            return (
                "fail",
                "Recovery compatibility baseline ordinary JWT hardening replacementEvidence oldKid must be absent from resultingKeyIds",
            )
    elif "replacementEvidence" in value:
        return (
            "fail",
            "Recovery compatibility baseline replacementEvidence is prohibited for compromise-classified or verified_not_restored JWT hardening",
        )
    return ("pass", "")


def validate_recovery_freshness(
    value: Any,
    credential_dispositions: dict[str, str],
    finalized_at: dt.datetime,
) -> tuple[str, str]:
    if not isinstance(value, dict):
        return (
            "fail",
            "Recovery compatibility baseline secretComplianceRefresh must be an object",
        )
    refreshed_classes = value.get("credentialClasses")
    freshness = value.get("freshness")
    if (
        not isinstance(refreshed_classes, list)
        or not refreshed_classes
        or any(not isinstance(class_name, str) or not class_name.strip() for class_name in refreshed_classes)
        or len(refreshed_classes) != len(set(refreshed_classes))
    ):
        return (
            "fail",
            "Recovery compatibility baseline secretComplianceRefresh.credentialClasses must be a non-empty unique list of strings",
        )
    if not isinstance(freshness, dict) or not freshness:
        return (
            "fail",
            "Recovery compatibility baseline secretComplianceRefresh.freshness must be a non-empty object",
        )
    refreshed_class_set = set(refreshed_classes)
    freshness_class_set = set(freshness)
    if refreshed_class_set != freshness_class_set:
        missing = sorted(refreshed_class_set - freshness_class_set)
        extra = sorted(freshness_class_set - refreshed_class_set)
        details = []
        if missing:
            details.append("missing: " + ", ".join(missing))
        if extra:
            details.append("extra: " + ", ".join(extra))
        return (
            "fail",
            "Recovery compatibility baseline secretComplianceRefresh freshness keys must exactly match credentialClasses ("
            + "; ".join(details)
            + ")",
        )
    for class_name in refreshed_classes:
        if class_name not in credential_dispositions:
            return (
                "fail",
                "Recovery compatibility baseline secretComplianceRefresh references a class without a disposition: "
                + class_name,
            )
        entry = freshness[class_name]
        if not isinstance(entry, dict) or set(entry) != RECOVERY_FRESHNESS_ENTRY_FIELDS:
            return (
                "fail",
                "Recovery compatibility baseline freshness entry must contain exactly lineage, field, value, previousField, and previousValue: "
                + class_name,
            )
        lineage = entry.get("lineage")
        field = entry.get("field")
        value_timestamp = entry.get("value")
        previous_field = entry.get("previousField")
        previous_value = entry.get("previousValue")
        if lineage not in {"new", "existing"}:
            return (
                "fail",
                f"Recovery compatibility baseline freshness lineage is invalid: {class_name}",
            )
        if field not in {"lastProvisionedAt", "lastRotationAt"}:
            return (
                "fail",
                f"Recovery compatibility baseline freshness field is invalid: {class_name}",
            )
        try:
            selected_timestamp = parse_timestamp(
                value_timestamp,
                f"Recovery compatibility baseline freshness value for {class_name}",
            )
        except TIMESTAMP_ERRORS as exc:
            return ("fail", str(exc))
        if selected_timestamp > finalized_at:
            return (
                "fail",
                f"Recovery compatibility baseline freshness value must not be later than finalizedAt: {class_name}",
            )

        disposition = credential_dispositions[class_name]
        if lineage == "new":
            if disposition not in {"rotated", "reissued"}:
                return (
                    "fail",
                    f"Recovery compatibility baseline new freshness lineage requires rotated or reissued disposition: {class_name}",
                )
            if field != "lastProvisionedAt":
                return (
                    "fail",
                    f"Recovery compatibility baseline new freshness lineage must use lastProvisionedAt: {class_name}",
                )
            if previous_field is not None or previous_value is not None:
                return (
                    "fail",
                    f"Recovery compatibility baseline new freshness lineage must not carry previous field/value: {class_name}",
                )
            continue

        if not isinstance(previous_field, str) or previous_field not in {
            "lastProvisionedAt",
            "lastRotationAt",
        }:
            return (
                "fail",
                f"Recovery compatibility baseline existing freshness lineage requires a valid previousField: {class_name}",
            )
        try:
            previous_timestamp = parse_timestamp(
                previous_value,
                f"Recovery compatibility baseline previous freshness value for {class_name}",
            )
        except TIMESTAMP_ERRORS as exc:
            return ("fail", str(exc))
        if previous_timestamp > finalized_at:
            return (
                "fail",
                f"Recovery compatibility baseline previous freshness value must not be later than finalizedAt: {class_name}",
            )

        if disposition in {"rotated", "reissued"}:
            if field != "lastRotationAt":
                return (
                    "fail",
                    f"Recovery compatibility baseline {disposition} freshness must use lastRotationAt for existing lineage: {class_name}",
                )
            if selected_timestamp <= previous_timestamp:
                return (
                    "fail",
                    f"Recovery compatibility baseline {disposition} freshness must advance the existing timestamp: {class_name}",
                )
        elif disposition in {"rebound", "verified_not_restored"}:
            if field != previous_field or value_timestamp != previous_value:
                return (
                    "fail",
                    f"Recovery compatibility baseline {disposition} freshness must preserve the existing field/value: {class_name}",
                )
    return ("pass", "")


def validate_intervening_erasure_coverage_header(
    value: JsonValue,
    stream: str,
    exclusive_start: int,
    inclusive_end: int,
) -> tuple[str, str, JsonObject | None]:
    label = "Recovery compatibility baseline interveningErasureCoverageProof"
    if not isinstance(value, dict):
        return ("fail", f"{label} must be an object when the pre-snapshot high-water is lower", None)
    if (
        value.get("stream") != stream
        or value.get("exclusiveStart") != exclusive_start
        or value.get("inclusiveEnd") != inclusive_end
    ):
        return ("fail", f"{label} must match the exact pre-snapshot-to-artifact interval", None)
    for field in ("snapshotLedgerEvidenceRef", "externalJournalEvidenceRef"):
        field_value = value.get(field)
        if not isinstance(field_value, str) or not field_value.strip():
            return ("fail", f"{label}.{field} must be a non-empty immutable evidence reference", None)
    return ("pass", "", value)


def validate_pre_snapshot_journal_boundary_witness(
    lineage: JsonObject,
    pre_snapshot_high_water: JsonObject,
) -> tuple[str, str]:
    label = "Recovery compatibility baseline artifact lineage"
    snapshot_identity = lineage.get("snapshotIdentity")
    if not isinstance(snapshot_identity, str) or not snapshot_identity.strip():
        return ("fail", f"{label}.snapshotIdentity must be a non-empty immutable identity")
    snapshot_at = lineage.get("snapshotAt")
    try:
        snapshot_opened_at = parse_timestamp(snapshot_at, f"{label}.snapshotAt")
    except ValueError as exc:
        return ("fail", f"{label}.snapshotAt must be a valid timestamp: {exc}")

    observation_id = pre_snapshot_high_water.get("observationId")
    if not isinstance(observation_id, str) or not observation_id.strip():
        return ("fail", f"{label}.preSnapshotJournalHighWater.observationId must be non-empty")
    observed_at = pre_snapshot_high_water.get("observedAt")
    try:
        observed_at_dt = parse_timestamp(
            observed_at,
            f"{label}.preSnapshotJournalHighWater.observedAt",
        )
    except ValueError as exc:
        return (
            "fail",
            f"{label}.preSnapshotJournalHighWater.observedAt must be a valid timestamp: {exc}",
        )
    observation_digest = pre_snapshot_high_water.get("observationDigest")
    if (
        not isinstance(observation_digest, str)
        or not observation_digest.startswith("sha256:")
        or not observation_digest[len("sha256:") :].strip()
    ):
        return (
            "fail",
            f"{label}.preSnapshotJournalHighWater.observationDigest must be a non-empty sha256-prefixed digest",
        )

    witness = lineage.get("preSnapshotJournalBoundaryWitness")
    if not isinstance(witness, dict):
        return ("fail", f"{label}.preSnapshotJournalBoundaryWitness must be an object")
    witness_observation_id = witness.get("observationId")
    if not isinstance(witness_observation_id, str) or not witness_observation_id.strip():
        return ("fail", f"{label}.preSnapshotJournalBoundaryWitness.observationId must be non-empty")
    if witness_observation_id != observation_id:
        return (
            "fail",
            f"{label}.preSnapshotJournalBoundaryWitness.observationId must match preSnapshotJournalHighWater.observationId",
        )
    witness_observation_digest = witness.get("observationDigest")
    if not isinstance(witness_observation_digest, str) or not witness_observation_digest.strip():
        return ("fail", f"{label}.preSnapshotJournalBoundaryWitness.observationDigest must be non-empty")
    if witness_observation_digest != observation_digest:
        return (
            "fail",
            f"{label}.preSnapshotJournalBoundaryWitness.observationDigest must match preSnapshotJournalHighWater.observationDigest",
        )
    witness_snapshot_identity = witness.get("snapshotIdentity")
    if not isinstance(witness_snapshot_identity, str) or not witness_snapshot_identity.strip():
        return ("fail", f"{label}.preSnapshotJournalBoundaryWitness.snapshotIdentity must be non-empty")
    if witness_snapshot_identity != snapshot_identity:
        return (
            "fail",
            f"{label}.preSnapshotJournalBoundaryWitness.snapshotIdentity must match snapshotIdentity",
        )
    witness_snapshot_opened_at = witness.get("snapshotOpenedAt")
    try:
        parse_timestamp(
            witness_snapshot_opened_at,
            f"{label}.preSnapshotJournalBoundaryWitness.snapshotOpenedAt",
        )
    except ValueError as exc:
        return (
            "fail",
            f"{label}.preSnapshotJournalBoundaryWitness.snapshotOpenedAt must be a valid timestamp: {exc}",
        )
    if witness_snapshot_opened_at != snapshot_at:
        return (
            "fail",
            f"{label}.preSnapshotJournalBoundaryWitness.snapshotOpenedAt must exactly equal snapshotAt",
        )
    evidence_ref = witness.get("evidenceRef")
    if not isinstance(evidence_ref, str) or not evidence_ref.strip():
        return ("fail", f"{label}.preSnapshotJournalBoundaryWitness.evidenceRef must be non-empty")
    if observed_at_dt >= snapshot_opened_at:
        return (
            "fail",
            f"{label}.preSnapshotJournalHighWater.observedAt must strictly precede snapshot opening",
        )
    return ("pass", "")


def validate_intervening_erasure_coverage_entry(
    value: JsonValue,
    label: str,
) -> tuple[str, str, int | None]:
    if not isinstance(value, dict):
        return ("fail", f"{label}.entries must contain objects", None)
    sequence = value.get("sequence")
    if not isinstance(sequence, int) or isinstance(sequence, bool):
        return ("fail", f"{label}.entries[].sequence must be an integer", None)

    snapshot_entry = value.get("snapshotVisibleLedger")
    journal_entry = value.get("externalJournal")
    if not isinstance(snapshot_entry, dict) or not isinstance(journal_entry, dict):
        return (
            "fail",
            f"{label} sequence {sequence} must include snapshotVisibleLedger and externalJournal evidence",
            None,
        )
    for source_name, source_entry in (
        ("snapshotVisibleLedger", snapshot_entry),
        ("externalJournal", journal_entry),
    ):
        for field in ("identity", "digest"):
            field_value = source_entry.get(field)
            if not isinstance(field_value, str) or not field_value.strip():
                return (
                    "fail",
                    f"{label} sequence {sequence} {source_name}.{field} must be non-empty",
                    None,
                )
    if (
        snapshot_entry["identity"] != journal_entry["identity"]
        or snapshot_entry["digest"] != journal_entry["digest"]
    ):
        return (
            "fail",
            f"{label} sequence {sequence} must have matching identity and digest in both sources",
            None,
        )
    return ("pass", "", sequence)


def validate_intervening_erasure_coverage_proof(
    value: JsonValue,
    stream: str,
    exclusive_start: int,
    inclusive_end: int,
) -> tuple[str, str]:
    label = "Recovery compatibility baseline interveningErasureCoverageProof"
    header_status, header_message, proof = validate_intervening_erasure_coverage_header(
        value,
        stream,
        exclusive_start,
        inclusive_end,
    )
    if header_status != "pass" or proof is None:
        return (header_status, header_message)
    entries = proof.get("entries")
    if not isinstance(entries, list):
        return ("fail", f"{label}.entries must be an ordered list")
    if len(entries) != inclusive_end - exclusive_start:
        return (
            "fail",
            f"{label}.entries must cover every sequence in order exactly once",
        )
    for offset, entry in enumerate(entries, start=1):
        entry_status, entry_message, sequence = validate_intervening_erasure_coverage_entry(entry, label)
        if entry_status != "pass" or sequence is None:
            return (entry_status, entry_message)
        if sequence != exclusive_start + offset:
            return (
                "fail",
                f"{label}.entries must cover every sequence in order exactly once",
            )
    return ("pass", "")


def validate_erasure_overlay_boundaries(
    value: JsonObject,
    artifact_high_water: JsonObject,
    initial_catchup_high_water: JsonObject,
    restore_high_water: JsonObject,
    stream: str,
) -> tuple[str, str, tuple[int, int, int] | None]:
    label = "Recovery compatibility baseline erasureOverlayReconciliation"
    canonical_boundaries: dict[str, JsonObject] = {
        "artifactErasureHighWater": artifact_high_water,
        "initialCatchupHighWater": initial_catchup_high_water,
        "restoreHighWater": restore_high_water,
    }
    canonical_sequences: dict[str, int] = {}
    for boundary_name, boundary in canonical_boundaries.items():
        if not isinstance(boundary, dict):
            return ("fail", f"{label} canonical {boundary_name} must be an object", None)
        sequence = boundary.get("sequence")
        if not isinstance(sequence, int) or isinstance(sequence, bool):
            return ("fail", f"{label} canonical {boundary_name}.sequence must be an integer", None)
        canonical_sequences[boundary_name] = sequence

    if not (
        canonical_sequences["artifactErasureHighWater"]
        <= canonical_sequences["initialCatchupHighWater"]
        <= canonical_sequences["restoreHighWater"]
    ):
        return (
            "fail",
            f"{label} canonical erasure high-water sequences must be ordered",
            None,
        )
    if value.get("stream") != stream:
        return ("fail", f"{label} stream must match the canonical erasure stream", None)
    # Exact equality preserves boundary shape, fields, values, and ordering
    # because the canonical boundaries above have already been validated.
    if value.get("artifactErasureHighWater") != artifact_high_water:
        return ("fail", f"{label} artifactErasureHighWater must match the canonical bound exactly", None)
    if value.get("initialCatchupHighWater") != initial_catchup_high_water:
        return ("fail", f"{label} initialCatchupHighWater must match the canonical bound exactly", None)
    if value.get("restoreHighWater") != restore_high_water:
        return ("fail", f"{label} restoreHighWater must match the canonical bound exactly", None)

    return (
        "pass",
        "",
        (
            canonical_sequences["artifactErasureHighWater"],
            canonical_sequences["initialCatchupHighWater"],
            canonical_sequences["restoreHighWater"],
        ),
    )


def validate_erasure_overlay_verification(
    value: JsonObject,
    artifact_sequence: int,
    initial_catchup_sequence: int,
    label: str,
) -> tuple[str, str]:
    sequence_verification = value.get("sequenceVerification")
    required_sequence_flags = ("contiguous", "complete", "gapFree", "duplicateFree")
    exclusive_start = sequence_verification.get("exclusiveStart") if isinstance(sequence_verification, dict) else None
    inclusive_end = sequence_verification.get("inclusiveEnd") if isinstance(sequence_verification, dict) else None
    if (
        not isinstance(sequence_verification, dict)
        or sequence_verification.get("status") != "pass"
        or not isinstance(exclusive_start, int)
        or isinstance(exclusive_start, bool)
        or not isinstance(inclusive_end, int)
        or isinstance(inclusive_end, bool)
        or exclusive_start != artifact_sequence
        or inclusive_end != initial_catchup_sequence
        or sequence_verification.get("ordered") is not True
        or any(sequence_verification.get(flag) is not True for flag in required_sequence_flags)
    ):
        return (
            "fail",
            (
                f"{label} sequenceVerification must prove the canonical bounds and ordered, contiguous, "
                "complete, gap-free, duplicate-free initial catch-up interval"
            ),
        )

    integrity_verification = value.get("integrityVerification")
    if (
        not isinstance(integrity_verification, dict)
        or integrity_verification.get("status") != "pass"
        or integrity_verification.get("verified") is not True
    ):
        return ("fail", f"{label} integrityVerification must be verified with status pass")
    return ("pass", "")


def validate_erasure_overlay_dispositions(
    value: JsonObject,
    initial_catchup_sequence: int,
    restore_sequence: int,
    stream: str,
    label: str,
) -> tuple[str, str]:
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
        missing_sequence_count = expected_sequence_count - len(observed_sequences)
        displayed_missing_sequences = list(
            itertools.islice(
                (
                    sequence
                    for sequence in range(initial_catchup_sequence + 1, restore_sequence + 1)
                    if sequence not in observed_sequences
                ),
                MISSING_SEQUENCE_DISPLAY_LIMIT,
            )
        )
        omitted_count = missing_sequence_count - len(displayed_missing_sequences)
        return (
            "fail",
            (
                f"{label} sequenceDispositions must cover the exact final interval; "
                f"missingCount={missing_sequence_count}, "
                f"missing={displayed_missing_sequences}, omittedCount={omitted_count}"
            ),
        )
    return ("pass", "")


def validate_erasure_overlay_reconciliation(
    value: JsonValue,
    artifact_high_water: JsonObject,
    initial_catchup_high_water: JsonObject,
    restore_high_water: JsonObject,
    stream: str,
) -> tuple[str, str]:
    label = "Recovery compatibility baseline erasureOverlayReconciliation"
    if not isinstance(value, dict):
        return ("fail", f"{label} must be an object")

    boundary_status, boundary_message, boundary_sequences = validate_erasure_overlay_boundaries(
        value,
        artifact_high_water,
        initial_catchup_high_water,
        restore_high_water,
        stream,
    )
    if boundary_status != "pass" or boundary_sequences is None:
        return (
            "fail",
            boundary_message or f"{label} canonical boundaries could not be resolved",
        )
    artifact_sequence, initial_catchup_sequence, restore_sequence = boundary_sequences

    verification_status, verification_message = validate_erasure_overlay_verification(
        value,
        artifact_sequence,
        initial_catchup_sequence,
        label,
    )
    if verification_status != "pass":
        return ("fail", verification_message)

    return validate_erasure_overlay_dispositions(
        value,
        initial_catchup_sequence,
        restore_sequence,
        stream,
        label,
    )


def validate_retained_smoke_evidence(
    root_dir: Path,
    smoke_evidence: Any,
    label: str,
) -> tuple[str, str]:
    """Validate smoke evidence through the canonical player-experience validator.

    Promotion and recovery authorization currently support retained JSON evidence
    in the repository. External URLs and opaque artifact identifiers are not
    dereferenced by this executable, so they fail closed rather than becoming
    implicit authorization.
    """

    if not isinstance(smoke_evidence, list) or not smoke_evidence:
        return ("fail", f"{label} must be a non-empty list")

    resolved_root = root_dir.resolve()
    for index, evidence_ref in enumerate(smoke_evidence):
        entry_label = f"{label}[{index}]"
        if not isinstance(evidence_ref, str) or not evidence_ref.strip():
            return ("fail", f"{entry_label} must be a non-empty repository-relative JSON reference")
        reference_path = Path(evidence_ref)
        if reference_path.is_absolute():
            return ("fail", f"{entry_label} must be a repository-relative JSON reference")

        evidence_path = resolve_repo_path(root_dir, evidence_ref).resolve()
        if not evidence_path.is_relative_to(resolved_root):
            return ("fail", f"{entry_label} must resolve within the repository")
        if not evidence_path.is_file():
            return ("fail", f"{entry_label} retained evidence file not found: {evidence_ref}")
        try:
            evidence = load_json(evidence_path)
        except JSON_READ_ERRORS as exc:
            return ("fail", f"{entry_label} retained evidence JSON unreadable: {exc}")
        if not isinstance(evidence, dict):
            return ("fail", f"{entry_label} retained evidence must be a JSON object")
        if evidence.get("executionMode") != "live":
            return ("fail", f"{entry_label} executionMode must be live for promotion or recovery authority")
        if evidence.get("externalAuthorityProvenance") != "retained-external":
            return (
                "fail",
                f"{entry_label} externalAuthorityProvenance must be retained-external for promotion or recovery authority",
            )

        try:
            validation = subprocess.run(
                [sys.executable, str(PLAYER_EXPERIENCE_SMOKE_VALIDATOR), str(evidence_path)],
                check=False,
                capture_output=True,
                text=True,
                timeout=60,
            )
        except subprocess.TimeoutExpired:
            return (
                "fail",
                f"{entry_label} canonical player-experience smoke evidence validation timed out",
            )
        except (OSError, UnicodeError) as exc:
            detail = " ".join(str(exc).split())
            return (
                "fail",
                f"{entry_label} canonical player-experience smoke evidence validation could not run: {detail}",
            )
        if validation.returncode != 0:
            detail = " ".join((validation.stderr or validation.stdout).split())
            return (
                "fail",
                f"{entry_label} failed canonical player-experience smoke evidence validation: {detail}",
            )

    return ("pass", f"{label} is valid retained player-experience smoke evidence")


def validate_promotion_smoke_evidence_entry_shape(
    smoke_evidence: Any,
    label: str,
) -> tuple[str, str]:
    if not isinstance(smoke_evidence, list) or not smoke_evidence:
        return ("fail", f"{label} must be a non-empty list")
    references: set[str] = set()
    for index, entry in enumerate(smoke_evidence):
        entry_label = f"{label}[{index}]"
        if not isinstance(entry, dict) or set(entry) != PROMOTION_SMOKE_EVIDENCE_ENTRY_FIELDS:
            return (
                "fail",
                f"{entry_label} must be an object with exactly ref and contentDigest",
            )
        reference = entry.get("ref")
        if not isinstance(reference, str) or not reference.strip():
            return ("fail", f"{entry_label}.ref must be a non-empty repository-relative JSON reference")
        if reference in references:
            return ("fail", f"{entry_label}.ref must be unique within {label}")
        references.add(reference)
        content_digest = entry.get("contentDigest")
        if not isinstance(content_digest, str) or not re.fullmatch(
            r"sha256:[0-9a-f]{64}", content_digest
        ):
            return (
                "fail",
                f"{entry_label}.contentDigest must be lowercase sha256:<64 hex>",
            )
    return ("pass", "")


def validate_promotion_smoke_evidence(
    root_dir: Path,
    smoke_evidence: Any,
    label: str,
    staging_deployment_ref: str,
    staging_event_id: str,
) -> tuple[str, str]:
    shape_status, shape_message = validate_promotion_smoke_evidence_entry_shape(
        smoke_evidence,
        label,
    )
    if shape_status != "pass":
        return (shape_status, shape_message)

    resolved_root = root_dir.resolve()
    references: list[str] = []
    loaded_evidence: list[tuple[int, Path, dict[str, Any]]] = []
    for index, entry in enumerate(smoke_evidence):
        entry_label = f"{label}[{index}]"
        reference = entry["ref"]
        reference_path = Path(reference)
        if reference_path.is_absolute():
            return ("fail", f"{entry_label}.ref must be a repository-relative JSON reference")
        evidence_path = resolve_repo_path(root_dir, reference).resolve()
        if not evidence_path.is_relative_to(resolved_root):
            return ("fail", f"{entry_label}.ref must resolve within the repository")
        if not evidence_path.is_file():
            return ("fail", f"{entry_label} retained evidence file not found: {reference}")
        try:
            evidence_bytes = evidence_path.read_bytes()
        except (OSError, UnicodeError) as exc:
            return ("fail", f"{entry_label} retained evidence file could not be read: {exc}")
        actual_digest = "sha256:" + hashlib.sha256(evidence_bytes).hexdigest()
        if entry["contentDigest"] != actual_digest:
            return (
                "fail",
                f"{entry_label}.contentDigest does not match the exact retained evidence file bytes",
            )
        try:
            evidence = load_json(evidence_path)
        except JSON_READ_ERRORS as exc:
            return ("fail", f"{entry_label} retained evidence JSON unreadable: {exc}")
        if not isinstance(evidence, dict):
            return ("fail", f"{entry_label} retained evidence must be a JSON object")
        references.append(reference)
        loaded_evidence.append((index, evidence_path, evidence))

    retained_status, retained_message = validate_retained_smoke_evidence(
        root_dir,
        references,
        label,
    )
    if retained_status != "pass":
        return (retained_status, retained_message)
    for index, _, evidence in loaded_evidence:
        if evidence.get("deploymentRef") != staging_deployment_ref:
            return (
                "fail",
                f"{label}[{index}] deploymentRef must match stagingOverlayCommitSha",
            )
        if evidence.get("deploymentEventId") != staging_event_id:
            return (
                "fail",
                f"{label}[{index}] deploymentEventId must match stagingDeploymentEventId",
            )
    return ("pass", f"{label} is valid bound retained player-experience smoke evidence")


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
            (
                "Recovery compatibility baseline must be a repository-relative record under "
                "design/operations/deployments/production/recovery/"
            ),
        )
    if not baseline_path.exists():
        return ("fail", f"Recovery compatibility baseline record not found: {baseline_ref}")
    try:
        baseline = load_json_rejecting_duplicate_keys(baseline_path)
    except RECOVERY_JSON_READ_ERRORS as exc:
        return ("fail", f"Recovery compatibility baseline record unreadable: {exc}")
    if not isinstance(baseline, dict):
        return ("fail", "Recovery compatibility baseline record must be a JSON object")

    missing_fields = [
        field
        for field in CANONICAL_RECOVERY_REQUIRED_FIELDS
        if field not in baseline
        or (is_missing(baseline[field]) and field != "credentialDispositions")
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
        if not isinstance(baseline.get(field), (dict, list))
        or (not baseline[field] and field != "credentialDispositions")
    ]
    if invalid_object_fields:
        return (
            "fail",
            "Recovery compatibility baseline canonical evidence groups must be non-empty objects or lists: "
            + ", ".join(invalid_object_fields),
        )
    if not isinstance(baseline["evidenceRefs"], list) or not baseline["evidenceRefs"]:
        return (
            "fail",
            "Recovery compatibility baseline evidenceRefs must be a non-empty list",
        )
    invalid_evidence_refs = [
        str(index)
        for index, evidence_ref in enumerate(baseline["evidenceRefs"])
        if not isinstance(evidence_ref, str) or not evidence_ref.strip()
    ]
    if invalid_evidence_refs:
        return (
            "fail",
            "Recovery compatibility baseline evidenceRefs must contain only non-empty strings; invalid indexes: "
            + ", ".join(invalid_evidence_refs),
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
    if backup_artifact_lineage.get("artifactErasureHighWater") != artifact_high_water:
        return (
            "fail",
            (
                "Recovery compatibility baseline artifact lineage artifactErasureHighWater "
                "must match the snapshot-bound artifact high-water sequence"
            ),
        )
    if backup_artifact_lineage.get("erasureHighWaterSnapshotBound") is not True:
        return (
            "fail",
            (
                "Recovery compatibility baseline artifact lineage erasureHighWaterSnapshotBound "
                "must be true"
            ),
        )
    pre_snapshot_journal_high_water = backup_artifact_lineage.get("preSnapshotJournalHighWater")
    if not isinstance(pre_snapshot_journal_high_water, dict):
        return (
            "fail",
            (
                "Recovery compatibility baseline artifact lineage must include a valid "
                "preSnapshotJournalHighWater object"
            ),
        )
    if pre_snapshot_journal_high_water.get("stream") != high_water_stream:
        return (
            "fail",
            (
                "Recovery compatibility baseline preSnapshotJournalHighWater.stream "
                "must match the canonical erasure stream"
            ),
        )
    pre_snapshot_sequence = pre_snapshot_journal_high_water.get("sequence")
    if not isinstance(pre_snapshot_sequence, int) or isinstance(pre_snapshot_sequence, bool):
        return (
            "fail",
            (
                "Recovery compatibility baseline preSnapshotJournalHighWater.sequence "
                "must be an integer"
            ),
        )
    if pre_snapshot_sequence > restore_sequence:
        return (
            "fail",
            "Recovery compatibility baseline preSnapshotJournalHighWater.sequence must be at or below restoreHighWater",
        )
    witness_status, witness_message = validate_pre_snapshot_journal_boundary_witness(
        backup_artifact_lineage,
        pre_snapshot_journal_high_water,
    )
    if witness_status != "pass":
        return (witness_status, witness_message)
    intervening_coverage_proof = backup_artifact_lineage.get("interveningErasureCoverageProof")
    if pre_snapshot_sequence < artifact_sequence:
        proof_status, proof_message = validate_intervening_erasure_coverage_proof(
            intervening_coverage_proof,
            high_water_stream,
            pre_snapshot_sequence,
            artifact_sequence,
        )
        if proof_status != "pass":
            return (proof_status, proof_message)
    elif intervening_coverage_proof is not None:
        return (
            "fail",
            (
                "Recovery compatibility baseline interveningErasureCoverageProof must be absent "
                "when preSnapshotJournalHighWater is at or above artifactErasureHighWater"
            ),
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
            "compromiseClassified",
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
    smoke_status, smoke_message = validate_retained_smoke_evidence(
        root_dir,
        baseline["smokeEvidence"],
        "Recovery compatibility baseline smokeEvidence",
    )
    if smoke_status != "pass":
        return ("fail", smoke_message)

    session_recovery = baseline.get("sessionRecovery")
    if not isinstance(session_recovery, dict):
        return ("fail", "Recovery compatibility baseline sessionRecovery must be an object")
    for field in ("gameSessionHandling", "authSessionHandling"):
        if session_recovery.get(field) != "invalidated":
            return ("fail", f"Recovery compatibility baseline sessionRecovery.{field} must be invalidated")

    credential_applicability = baseline.get("credentialApplicability")
    if not isinstance(credential_applicability, dict) or not credential_applicability:
        return (
            "fail",
            "Recovery compatibility baseline credentialApplicability must be a non-empty object",
        )
    expected_credential_classes = set(CANONICAL_RECOVERY_CREDENTIAL_UNIVERSE)
    actual_credential_classes = set(credential_applicability)
    missing_applicability = sorted(expected_credential_classes - actual_credential_classes)
    extra_applicability = sorted(actual_credential_classes - expected_credential_classes)
    if missing_applicability or extra_applicability:
        details = []
        if missing_applicability:
            details.append("missing: " + ", ".join(missing_applicability))
        if extra_applicability:
            details.append("extra: " + ", ".join(extra_applicability))
        return (
            "fail",
            "Recovery compatibility baseline credentialApplicability keys must exactly cover "
            "the closed credential class universe (" + "; ".join(details) + ")",
        )
    invalid_applicability = [
        class_name
        for class_name, applicability in credential_applicability.items()
        if not isinstance(applicability, str) or applicability not in RECOVERY_CREDENTIAL_APPLICABILITY
    ]
    if invalid_applicability:
        return (
            "fail",
            "Recovery compatibility baseline credentialApplicability has an unknown or malformed "
            "value for: "
            + ", ".join(sorted(invalid_applicability)),
        )
    non_applicable_required_classes = [
        class_name
        for class_name in CANONICAL_RECOVERY_REQUIRED_APPLICABLE_CLASSES
        if credential_applicability[class_name] != "applicable"
    ]
    if non_applicable_required_classes:
        return (
            "fail",
            "Recovery compatibility baseline required credential classes must be applicable: "
            + ", ".join(sorted(non_applicable_required_classes)),
        )

    credential_validation = baseline.get("externalCredentialValidation")
    if not isinstance(credential_validation, dict):
        return ("fail", "Recovery compatibility baseline externalCredentialValidation must be an object")
    credential_records = credential_validation.get("records")
    if not isinstance(credential_records, dict):
        return ("fail", "Recovery compatibility baseline externalCredentialValidation.records must be an object")
    expected_external_classes = set(CANONICAL_RECOVERY_CREDENTIAL_CLASSES)
    actual_external_classes = set(credential_records)
    missing_credential_records = sorted(expected_external_classes - actual_external_classes)
    extra_credential_records = sorted(actual_external_classes - expected_external_classes)
    if missing_credential_records or extra_credential_records:
        details = []
        if missing_credential_records:
            details.append("missing: " + ", ".join(missing_credential_records))
        if extra_credential_records:
            details.append("extra: " + ", ".join(extra_credential_records))
        return (
            "fail",
            "Recovery compatibility baseline externalCredentialValidation.records keys must exactly cover "
            "the closed external credential class universe (" + "; ".join(details) + ")",
        )
    credential_fields = {
        "status",
        "evidenceRef",
        "isolationAssertion",
        "validationMethod",
        "validatedAt",
        "validatedBy",
        "observedValue",
    }
    not_applicable_credential_fields = {"status", "reason", "evidenceRef"}
    for class_name in CANONICAL_RECOVERY_CREDENTIAL_CLASSES:
        record = credential_records.get(class_name)
        if not isinstance(record, dict):
            return (
                "fail",
                f"Recovery compatibility baseline external credential record must be an object: {class_name}",
            )
        applicability = credential_applicability[class_name]
        if applicability == "not_applicable":
            if record.get("status") != "not_applicable":
                return (
                    "fail",
                    f"Recovery compatibility baseline external credential record must be not_applicable for non-applicable class: {class_name}",
                )
            if set(record) != not_applicable_credential_fields:
                return (
                    "fail",
                    f"Recovery compatibility baseline non-applicable external credential record must contain exactly status, reason, and evidenceRef: {class_name}",
                )
            if record.get("reason") != "credential-class-not-present":
                return (
                    "fail",
                    f"Recovery compatibility baseline non-applicable external credential record reason must be credential-class-not-present: {class_name}",
                )
            if not isinstance(record.get("evidenceRef"), str) or not record["evidenceRef"].strip():
                return (
                    "fail",
                    f"Recovery compatibility baseline non-applicable external credential evidenceRef must be non-empty: {class_name}",
                )
            continue
        if record.get("status") != "pass":
            return (
                "fail",
                f"Recovery compatibility baseline applicable external credential record status must be pass: {class_name}",
            )
        if set(record) != credential_fields:
            return (
                "fail",
                f"Recovery compatibility baseline applicable external credential record must contain exactly its validation fields: {class_name}",
            )
        missing_record_fields = [
            field for field in credential_fields if field not in record or is_missing(record[field])
        ]
        if missing_record_fields:
            return (
                "fail",
                f"Recovery compatibility baseline external credential record missing fields for {class_name}: "
                + ", ".join(sorted(missing_record_fields)),
            )
        if not isinstance(record.get("observedValue"), str):
            return (
                "fail",
                f"Recovery compatibility baseline external credential observedValue must be non-secret text: {class_name}",
            )
        try:
            parse_timestamp(record.get("validatedAt"), f"Recovery baseline {class_name}.validatedAt")
        except TIMESTAMP_ERRORS as exc:
            return ("fail", str(exc))

    credential_dispositions = baseline.get("credentialDispositions")
    if not isinstance(credential_dispositions, dict):
        return (
            "fail",
            "Recovery compatibility baseline credentialDispositions must be an object",
        )
    expected_disposition_classes = {
        class_name
        for class_name, applicability in credential_applicability.items()
        if applicability == "applicable"
    }
    actual_disposition_classes = set(credential_dispositions)
    missing_dispositions = sorted(expected_disposition_classes - actual_disposition_classes)
    extra_dispositions = sorted(actual_disposition_classes - expected_disposition_classes)
    if missing_dispositions or extra_dispositions:
        details = []
        if missing_dispositions:
            details.append("missing: " + ", ".join(missing_dispositions))
        if extra_dispositions:
            details.append("extra: " + ", ".join(extra_dispositions))
        return (
            "fail",
            "Recovery compatibility baseline credentialDispositions keys must exactly cover "
            "the applicable credential classes (" + "; ".join(details) + ")",
        )
    invalid_dispositions = [
        class_name
        for class_name, disposition in credential_dispositions.items()
        if not isinstance(disposition, str) or disposition not in RECOVERY_CREDENTIAL_DISPOSITIONS
    ]
    if invalid_dispositions:
        return (
            "fail",
            "Recovery compatibility baseline credentialDispositions has an unknown or malformed "
            "disposition for: "
            + ", ".join(sorted(invalid_dispositions)),
        )

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
    except TIMESTAMP_ERRORS as exc:
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

    jwt_status, jwt_message = validate_jwt_hardening_contract(
        baseline.get("jwtHardening"),
        credential_dispositions["jwt-signing-keys-jwks"],
    )
    if jwt_status != "pass":
        return ("fail", jwt_message)
    freshness_status, freshness_message = validate_recovery_freshness(
        baseline.get("secretComplianceRefresh"),
        credential_dispositions,
        finalized_at,
    )
    if freshness_status != "pass":
        return ("fail", freshness_message)
    return ("pass", "Recovery compatibility baseline is valid")


def load_yaml(path: Path) -> Any:
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_json_rejecting_duplicate_keys(path: Path) -> Any:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON member: {key}")
            result[key] = value
        return result

    return json.loads(
        path.read_text(encoding="utf-8"),
        object_pairs_hook=reject_duplicate_keys,
    )


def parse_documents(rendered_text: str) -> list[dict[str, Any]]:
    return [doc for doc in yaml.safe_load_all(rendered_text) if isinstance(doc, dict)]


def resolve_repo_path(root_dir: Path, ref: str) -> Path:
    path = Path(ref)
    return path if path.is_absolute() else root_dir / ref


def immutable_file_digest(path: Path) -> str:
    return "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_expected_bindings_ref(environment: str) -> str:
    return f"design/operations/environments/{environment}/expected-bindings.yaml"


def canonical_promotion_attestation_ref(deployment_ref: str) -> str | None:
    if not isinstance(deployment_ref, str) or not GIT_COMMIT_SHA_RE.fullmatch(deployment_ref):
        return None
    return f"design/operations/deployments/production/attestations/{deployment_ref}.json"


def is_canonical_promotion_attestation_ref(
    value: str, deployment_ref: str, *, root_dir: Path | None = None
) -> bool:
    canonical_ref = canonical_promotion_attestation_ref(deployment_ref)
    if canonical_ref is None or root_dir is None or not isinstance(value, str):
        return False
    path = Path(value)
    if path.is_absolute() or path.as_posix() != canonical_ref:
        return False
    if root_dir.is_symlink():
        return False
    repository_root = root_dir.resolve()
    candidate = repository_root / path
    canonical_directory = (
        repository_root / "design" / "operations" / "deployments" / "production" / "attestations"
    ).resolve()
    if not candidate.resolve(strict=False).is_relative_to(canonical_directory):
        return False
    if candidate.resolve(strict=False) != canonical_directory / f"{deployment_ref}.json":
        return False
    current = candidate
    while current != repository_root:
        if current.is_symlink():
            return False
        current = current.parent
    return True


def operator_deployment_ref_is_current(
    environment: str, deployment_ref: str, checked_out_commit_sha: str
) -> bool:
    if environment == "hobby-self-hosted":
        return bool(DEPLOYMENT_REF_RE.fullmatch(deployment_ref))
    return bool(
        GIT_COMMIT_SHA_RE.fullmatch(deployment_ref)
        and deployment_ref == checked_out_commit_sha
    )


def jwt_custody_proof_error(label: str, value: Any) -> str | None:
    if not isinstance(value, dict):
        return f"{label} must be an object"
    required = {"proofId", "custodyMode", "contractVersion"}
    if set(value) != required:
        return f"{label} must contain exactly proofId, custodyMode, and contractVersion"
    if not isinstance(value["contractVersion"], int) or isinstance(value["contractVersion"], bool):
        return f"{label}.contractVersion must be an integer"
    tuple_value = (
        value.get("proofId"),
        value.get("custodyMode"),
        value.get("contractVersion"),
    )
    if tuple_value not in ACCEPTED_JWT_CUSTODY_PROOF_TUPLES:
        return f"{label} does not select an accepted JWT custody proof tuple"
    return None


def load_immutable_json_evidence(
    root_dir: Path, reference: Any, label: str
) -> tuple[dict[str, Any] | None, str | None]:
    if not isinstance(reference, str) or not reference.strip():
        return None, f"{label} must be a non-empty immutable digest-qualified reference"
    path_ref, separator, digest = reference.rpartition("#")
    if not separator or not path_ref or not IMMUTABLE_ARTIFACT_ID_RE.fullmatch(digest):
        return None, f"{label} must use <repository-path>#sha256:<digest> format"
    path_value = Path(path_ref)
    evidence_root = (
        root_dir / "design" / "operations" / "deployments"
    ).resolve()
    evidence_path = resolve_repo_path(root_dir, path_ref).resolve()
    if path_value.is_absolute() or not evidence_path.is_relative_to(evidence_root):
        return None, f"{label} must resolve under design/operations/deployments"
    if evidence_path.suffix != ".json":
        return None, f"{label} must reference a JSON evidence record"
    if not evidence_path.exists():
        return None, f"{label} evidence record not found: {path_ref}"
    try:
        evidence = load_json(evidence_path)
    except JSON_READ_ERRORS as exc:
        return None, f"{label} evidence record unreadable: {exc}"
    if not isinstance(evidence, dict):
        return None, f"{label} evidence record must be a JSON object"
    try:
        actual_digest = canonical_evidence_digest(evidence)
    except (TypeError, ValueError) as exc:
        return None, f"{label} evidence record cannot be canonically hashed: {exc}"
    if actual_digest != digest:
        return None, f"{label} digest does not match the referenced evidence record"
    return evidence, None


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
    *,
    allowed_supplemental_policy_ids: tuple[str, ...] = (),
    expected_bindings_digest: str | None = None,
) -> tuple[str, str]:
    label = environment.capitalize()
    effective_now = now_dt or dt.datetime.now(dt.timezone.utc)
    if not isinstance(report, dict):
        return ("fail", f"{label} preflight report must be a JSON object")
    catalog_error = validate_preflight_policy_catalog(PREFLIGHT_POLICY_CATALOG)
    if catalog_error:
        return ("fail", catalog_error)
    if report.get("policyCatalogVersion") != PREFLIGHT_POLICY_CATALOG_VERSION:
        return ("fail", f"{label} preflight report policyCatalogVersion mismatch")
    if report.get("environment") != environment:
        return ("fail", f"{label} preflight report must target {environment}")
    if report.get("expectedBindingsRef") != expected_bindings_ref:
        return ("fail", f"{label} preflight report expectedBindingsRef mismatch")
    if expected_bindings_digest is not None and report.get("expectedBindingsDigest") != expected_bindings_digest:
        return ("fail", f"{label} preflight report expectedBindingsDigest mismatch")

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
    except TIMESTAMP_ERRORS as exc:
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
        category = check.get("category")
        status = check.get("status")
        message = check.get("message")
        required = check.get("required")
        if (
            not isinstance(policy_id, str)
            or not policy_id
            or not isinstance(category, str)
            or category not in PREFLIGHT_POLICY_CATEGORIES
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
    unknown_ids = sorted(
        set(policy_ids)
        - EXPECTED_PREFLIGHT_POLICY_ID_SET
        - set(allowed_supplemental_policy_ids)
    )
    if unknown_ids:
        return ("fail", f"{label} preflight report contains unknown policy IDs: " + ", ".join(unknown_ids))

    category_mismatches = sorted(
        check["policyId"]
        for check in preflight_results
        if check["category"] != PREFLIGHT_POLICY_CATALOG[check["policyId"]]
    )
    if category_mismatches:
        return (
            "fail",
            f"{label} preflight report has mismatched policy categories: "
            + ", ".join(category_mismatches),
        )

    expected_requirements = expected_preflight_policy_requirements(environment, traffic_open_event)
    requirement_mismatches = sorted(
        check["policyId"]
        for check in preflight_results
        if check["policyId"] in expected_requirements
        and check["required"] is not expected_requirements[check["policyId"]]
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
            check["category"] == "advisory"
            and (
                check["policyId"] != "PREFLIGHT-DIGEST-002"
                or environment == "hobby-self-hosted"
            )
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
    except JSON_READ_ERRORS as exc:
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


def optional_integration_state(
    data: dict[str, Any], section: str
) -> tuple[bool, str | None]:
    """Return enabled state and validate the explicit optional section contract."""
    if section not in data:
        return False, None
    raw_section = data[section]
    if not isinstance(raw_section, dict):
        return False, f"{section} must be an object"
    enabled = raw_section.get("enabled")
    if not isinstance(enabled, bool):
        return False, f"{section}.enabled must be a boolean"
    if not enabled:
        leftovers = [
            f"{section}.{field}"
            for field in sorted(raw_section)
            if field != "enabled"
        ]
        if leftovers:
            return False, f"{section} fields must be omitted when disabled: {', '.join(leftovers)}"
        return False, None
    if section == "assetStorage":
        missing = [
            f"assetStorage.{field}"
            for field in ("bucket", "endpoint")
            if normalize_binding_value(raw_section.get(field))[0] is None
        ]
        if (
            normalize_binding_value(raw_section.get("bindingRef"))[0] is None
            and normalize_binding_value(raw_section.get("fingerprint"))[0] is None
        ):
            missing.append("assetStorage.bindingRef or assetStorage.fingerprint")
        if missing:
            return True, "Missing enabled asset storage binding keys: " + ", ".join(missing)
    elif section == "outboundComms":
        smtp_host = raw_section.get("smtpHost")
        webhook_targets = raw_section.get("webhookTargets")
        if smtp_host is not None and normalize_binding_value(smtp_host)[0] is None:
            return True, "outboundComms.smtpHost must be a non-empty binding value when present"
        if webhook_targets is not None and (
            not isinstance(webhook_targets, dict) or not webhook_targets
        ):
            return True, "outboundComms.webhookTargets must be a non-empty mapping when present"
        if isinstance(webhook_targets, dict):
            invalid_targets = [
                str(target_name)
                for target_name, target in webhook_targets.items()
                if normalize_binding_value(target)[0] is None
            ]
            if invalid_targets:
                return (
                    True,
                    "outboundComms.webhookTargets entries must be non-empty binding values: "
                    + ", ".join(sorted(invalid_targets)),
                )
        if not smtp_host and not webhook_targets:
            return True, "Enabled outbound communications require smtpHost or webhookTargets"
    return True, None


# Sharing is a binding-type decision, not a generic escape hatch. Internal
# state/trust and credential principals are environment-exclusive. Only the
# non-secret endpoint/target fields below may be conditionally shared.
BINDING_SHAREABILITY = {
    "internalBindings.postgres.endpoint": "exclusive",
    "internalBindings.postgres.credentialsRef": "exclusive",
    "internalBindings.redis.coordination.endpoint": "exclusive",
    "internalBindings.redis.cache.endpoint": "exclusive",
    "internalBindings.jwt.signingKeysRef": "exclusive",
    "internalBindings.jwt.jwksRef": "exclusive",
    "internalBindings.certificates.issuerRef": "exclusive",
    "internalBindings.certificates.workloadMtlsRef": "exclusive",
    "internalBindings.certificates.gatewayInternalWsListenerRef": "exclusive",
    "internalBindings.certificates.tcpProxyBridgeClientRef": "exclusive",
    "internalBindings.certificates.backupControlPlaneClientRef": "exclusive",
    "internalBindings.registry.imagePullSecretRef": "exclusive",
    "backupStorage.bucket": "conditional",
    "backupStorage.endpoint": "conditional",
    "backupStorage.bindingRef": "exclusive",
    "backupStorage.fingerprint": "exclusive",
    "assetStorage.bucket": "conditional",
    "assetStorage.endpoint": "conditional",
    "assetStorage.bindingRef": "exclusive",
    "assetStorage.fingerprint": "exclusive",
    "outboundComms.smtpHost": "conditional",
    "observability.otelCollectorEndpoint": "conditional",
    "operatorCredentials.bindingRef": "exclusive",
    "operatorCredentials.fingerprint": "exclusive",
}


def binding_declarations(data: dict[str, Any]):
    for label, shareability in BINDING_SHAREABILITY.items():
        raw = get(data, label)
        if raw is not None:
            yield label, raw, shareability
    outbound = data.get("outboundComms")
    if isinstance(outbound, dict) and outbound.get("enabled") is True:
        targets = outbound.get("webhookTargets")
        if isinstance(targets, dict):
            for target_name, raw_target in sorted(targets.items()):
                yield (
                    f"outboundComms.webhookTargets.{target_name}",
                    raw_target,
                    "conditional",
                )


def binding_shareability_issues(data: dict[str, Any]) -> list[str]:
    issues: list[str] = []
    for label, raw, shareability in binding_declarations(data):
        if shareability == "conditional" and isinstance(raw, dict):
            credential_keys = sorted({"bindingRef", "fingerprint"} & raw.keys())
            if credential_keys:
                issues.append(
                    f"{label} is a non-sensitive target and cannot use credential fields: "
                    + ", ".join(credential_keys)
                )
                continue
        _, shared, rationale = normalize_binding_value(raw)
        if not shared:
            continue
        if shareability == "exclusive":
            issues.append(f"{label} is environment-exclusive and cannot be marked shared")
        elif not rationale:
            issues.append(f"{label} is marked shared but missing sharedRationale")
    return issues


def required_secret_compliance_classes(root_dir: Path, environment: str) -> set[str]:
    expected_path = root_dir / "design/operations/environments" / environment / "expected-bindings.yaml"
    try:
        expected_data = load_yaml(expected_path) or {}
    except YAML_READ_ERRORS as exc:
        raise ValueError(f"Cannot read canonical expected-bindings manifest: {exc}") from exc
    if not isinstance(expected_data, dict) or expected_data.get("environment") != environment:
        raise ValueError(
            f"Canonical expected-bindings manifest must target {environment}: {expected_path}"
        )
    asset_enabled, asset_error = optional_integration_state(expected_data, "assetStorage")
    if asset_error:
        raise ValueError(f"Canonical assetStorage configuration is invalid: {asset_error}")
    required = set(BASE_SECRET_COMPLIANCE_CLASSES)
    backup_storage = expected_data.get("backupStorage")
    if not isinstance(backup_storage, dict) or not isinstance(
        backup_storage.get("enabled"), bool
    ):
        raise TypeError("Canonical backupStorage.enabled must be a boolean")
    if backup_storage["enabled"]:
        required.add("backup-object-store-credentials")
    if asset_enabled:
        required.add("asset-store-credentials")
    return required


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


def workload_namespace(document: dict[str, Any]) -> str:
    return metadata_namespace(document) or "firemud"


def effective_container_env(
    documents: list[dict[str, Any]],
    document: dict[str, Any],
    container: dict[str, Any],
    *,
    relevant_prefixes: tuple[str, ...] | None = None,
    relevant_names: set[str] | None = None,
) -> tuple[dict[str, str], list[str]]:
    """Resolve the inspectable effective environment for this workload container."""
    namespace = workload_namespace(document)
    prefixes = relevant_prefixes or ()
    names = relevant_names or set()
    inspect_all = relevant_prefixes is None and relevant_names is None

    def is_relevant(name: Any) -> bool:
        return inspect_all or (
            isinstance(name, str)
            and (name in names or any(name.startswith(prefix) for prefix in prefixes))
        )

    def secret_env_from_is_relevant(prefix: str) -> bool:
        # A prefix that is unrelated to the requested contract cannot expose a
        # relevant key. With no prefix, an absent or malformed external Secret
        # remains opaque; any concrete keys are still checked below when they
        # can be inspected safely.
        return inspect_all or (
            bool(prefix)
            and (
                any(prefix.startswith(candidate) or candidate.startswith(prefix) for candidate in prefixes)
                or any(name.startswith(prefix) for name in names)
            )
        )

    configmaps: dict[tuple[str, str], Any] = {}
    secrets: dict[tuple[str, str], Any] = {}
    for source in documents:
        source_name = metadata_name(source)
        if not source_name:
            continue
        source_key = (workload_namespace(source), source_name)
        if source.get("kind") == "ConfigMap":
            configmaps[source_key] = source.get("data")
        elif source.get("kind") == "Secret":
            secrets[source_key] = source
    values: dict[str, str] = {}
    issues: list[str] = []

    def add(name: Any, value: Any, source: str) -> None:
        if not isinstance(name, str) or not name:
            return
        if isinstance(value, str):
            values[name] = value
        else:
            issues.append(f"{source} value for {name} is not a string")

    for entry in container.get("envFrom") or []:
        if not isinstance(entry, dict):
            continue
        prefix = str(entry.get("prefix") or "")
        ref = entry.get("configMapRef")
        source_kind = "ConfigMap"
        if isinstance(ref, dict):
            source_values = configmaps.get((namespace, str(ref.get("name") or "")))
        else:
            ref = entry.get("secretRef")
            source_kind = "Secret"
            source_values = secrets.get((namespace, str(ref.get("name") or ""))) if isinstance(ref, dict) else None
        if not isinstance(ref, dict) or not ref.get("name"):
            continue
        ref_name = str(ref["name"])
        optional = ref.get("optional", False)
        if not isinstance(optional, bool):
            issues.append(f"{source_kind} {namespace}/{ref_name} optional must be a boolean")
            continue
        if source_values is None:
            if (
                (source_kind == "ConfigMap" and not optional)
                or (
                    source_kind == "Secret"
                    and secret_env_from_is_relevant(prefix)
                    and not optional
                )
            ):
                issues.append(f"{source_kind} {namespace}/{ref_name} referenced by workload is missing")
            continue
        if not isinstance(source_values, dict):
            if source_kind == "ConfigMap" or inspect_all:
                issues.append(f"{source_kind} {namespace}/{ref_name} data must be a mapping")
            continue
        source_data = source_values.get("data") if source_kind == "Secret" else source_values
        source_string_data = source_values.get("stringData") if source_kind == "Secret" else None
        if source_kind == "Secret" and (
            ("data" in source_values and not isinstance(source_data, dict))
            or ("stringData" in source_values and not isinstance(source_string_data, dict))
        ):
            if secret_env_from_is_relevant(prefix):
                issues.append(f"Secret {namespace}/{ref_name} data and stringData must be mappings")
            continue
        source_entries = dict(source_data or {})
        source_entries.update(source_string_data or {})
        for key, value in source_entries.items():
            effective_name = prefix + str(key)
            if not is_relevant(effective_name):
                continue
            if source_kind == "Secret":
                issues.append(
                    f"Secret {namespace}/{ref_name} cannot provide relevant environment configuration {effective_name}"
                )
                continue
            add(effective_name, value, f"ConfigMap {namespace}/{ref_name}")

    for entry in container.get("env") or []:
        if not isinstance(entry, dict) or not entry.get("name"):
            continue
        name = entry["name"]
        if not is_relevant(name):
            continue
        if "value" in entry:
            add(name, entry.get("value"), "direct env")
            continue
        value_from = entry.get("valueFrom")
        if not isinstance(value_from, dict):
            continue
        ref = value_from.get("configMapKeyRef")
        source_kind = "ConfigMap"
        if isinstance(ref, dict):
            source_values = configmaps.get((namespace, str(ref.get("name") or "")))
        else:
            ref = value_from.get("secretKeyRef")
            source_kind = "Secret"
            source_values = secrets.get((namespace, str(ref.get("name") or ""))) if isinstance(ref, dict) else None
        if not isinstance(ref, dict) or not ref.get("name") or not ref.get("key"):
            continue
        ref_name = str(ref["name"])
        optional = ref.get("optional", False)
        if not isinstance(optional, bool):
            issues.append(f"{source_kind} key {namespace}/{ref_name}:{ref['key']} optional must be a boolean")
            continue
        if source_values is None:
            if not optional:
                issues.append(f"{source_kind} key {namespace}/{ref_name}:{ref['key']} referenced by workload is missing")
            continue
        if not isinstance(source_values, dict):
            issues.append(f"{source_kind} {namespace}/{ref_name} data must be a mapping")
            continue
        if source_kind == "Secret":
            source_data = source_values.get("data")
            source_string_data = source_values.get("stringData")
            if (
                (source_data is not None and not isinstance(source_data, dict))
                or (source_string_data is not None and not isinstance(source_string_data, dict))
            ):
                issues.append(f"Secret {namespace}/{ref_name} data and stringData must be mappings")
                continue
            if ref["key"] not in (source_data or {}) and ref["key"] not in (source_string_data or {}):
                if not optional:
                    issues.append(f"Secret key {namespace}/{ref_name}:{ref['key']} referenced by workload is missing")
                continue
            issues.append(
                f"Secret {namespace}/{ref_name} cannot provide relevant environment configuration {name}"
            )
            continue
        if ref["key"] not in source_values:
            if not optional:
                issues.append(f"ConfigMap key {namespace}/{ref_name}:{ref['key']} referenced by workload is missing")
            continue
        source_value = source_values[ref["key"]]
        add(name, source_value, f"{source_kind} {namespace}/{ref_name}")
    return values, issues


def extract_service_discovery_overrides(documents: list[dict[str, Any]]) -> tuple[dict[str, str], list[str]]:
    overrides: dict[str, str] = {}
    issues: list[str] = []
    for document in documents:
        for _, container, _ in primary_containers(document):
            values, env_issues = effective_container_env(
                documents,
                document,
                container,
                relevant_prefixes=("FIREMUD_SERVICES_",),
            )
            issues.extend(env_issues)
            for key, value in values.items():
                if not key.startswith("FIREMUD_SERVICES_"):
                    continue
                if key in overrides and overrides[key] != value:
                    issues.append(f"effective {key} values conflict across workloads")
                overrides[key] = value
    return overrides, issues


def external_binding_uniqueness_issues(
    manifests_root: Path, env_class: str, current_data: dict[str, Any]
) -> list[str]:
    current_backup = current_data.get("backupStorage")
    current_asset = current_data.get("assetStorage")
    current_outbound = current_data.get("outboundComms")
    current_operator = current_data.get("operatorCredentials")
    current_backup = current_backup if isinstance(current_backup, dict) else {}
    current_asset = current_asset if isinstance(current_asset, dict) else {}
    current_outbound = current_outbound if isinstance(current_outbound, dict) else {}
    current_operator = current_operator if isinstance(current_operator, dict) else {}
    current_observability = current_data.get("observability")
    current_observability = (
        current_observability if isinstance(current_observability, dict) else {}
    )

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

    issues: list[str] = binding_shareability_issues(current_data)
    candidates: list[tuple[str, str, bool, str]] = []
    current_backup_enabled = (
        isinstance(current_backup, dict) and current_backup.get("enabled") is True
    )
    if current_backup_enabled:
        add_candidate(issues, candidates, "backupStorage.bucket", current_backup.get("bucket"))
        add_candidate(issues, candidates, "backupStorage.endpoint", current_backup.get("endpoint"))
        add_candidate(
            issues,
            candidates,
            "backupStorage.bindingRef",
            current_backup.get("bindingRef") or current_backup.get("fingerprint"),
        )
    if current_asset.get("enabled") is True:
        add_candidate(issues, candidates, "assetStorage.bucket", current_asset.get("bucket"))
        add_candidate(issues, candidates, "assetStorage.endpoint", current_asset.get("endpoint"))
        add_candidate(
            issues,
            candidates,
            "assetStorage.bindingRef",
            current_asset.get("bindingRef") or current_asset.get("fingerprint"),
        )
    if current_outbound.get("enabled") is True:
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
    add_candidate(
        issues,
        candidates,
        "observability.otelCollectorEndpoint",
        current_observability.get("otelCollectorEndpoint"),
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
        except YAML_READ_ERRORS as exc:
            issues.append(f"Unreadable expected-bindings manifest for {other_env}: {exc}")
            continue
        other_backup = other_data.get("backupStorage")
        other_asset = other_data.get("assetStorage")
        other_outbound = other_data.get("outboundComms")
        other_operator = other_data.get("operatorCredentials")
        other_observability = other_data.get("observability")
        other_backup = other_backup if isinstance(other_backup, dict) else {}
        other_asset = other_asset if isinstance(other_asset, dict) else {}
        other_outbound = other_outbound if isinstance(other_outbound, dict) else {}
        other_operator = other_operator if isinstance(other_operator, dict) else {}
        other_observability = (
            other_observability if isinstance(other_observability, dict) else {}
        )
        issues.extend(
            f"{other_env}: {issue}" for issue in binding_shareability_issues(other_data)
        )
        other_backup_enabled = (
            isinstance(other_backup, dict) and other_backup.get("enabled") is True
        )
        other_asset_enabled = (
            isinstance(other_asset, dict) and other_asset.get("enabled") is True
        )
        other_outbound_enabled = (
            isinstance(other_outbound, dict) and other_outbound.get("enabled") is True
        )
        other_lookup = {
            "backupStorage.bucket": (
                other_backup.get("bucket") if other_backup_enabled else None
            ),
            "backupStorage.endpoint": (
                other_backup.get("endpoint") if other_backup_enabled else None
            ),
            "backupStorage.bindingRef": (
                other_backup.get("bindingRef") or other_backup.get("fingerprint")
                if other_backup_enabled
                else None
            ),
            "assetStorage.bucket": other_asset.get("bucket") if other_asset_enabled else None,
            "assetStorage.endpoint": other_asset.get("endpoint") if other_asset_enabled else None,
            "assetStorage.bindingRef": (
                other_asset.get("bindingRef") or other_asset.get("fingerprint")
                if other_asset_enabled
                else None
            ),
            "outboundComms.smtpHost": other_outbound.get("smtpHost") if other_outbound_enabled else None,
            "operatorCredentials.bindingRef": other_operator.get("bindingRef") or other_operator.get("fingerprint"),
            "observability.otelCollectorEndpoint": other_observability.get(
                "otelCollectorEndpoint"
            ),
        }
        if other_outbound_enabled:
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


def metadata_namespace(document: dict[str, Any]) -> str | None:
    metadata = document.get("metadata") or {}
    namespace = metadata.get("namespace")
    return namespace if isinstance(namespace, str) and namespace else None


def rendered_namespace_matches(
    document: dict[str, Any],
    namespace: str | None,
    *,
    default_namespace: str | None = None,
) -> bool:
    rendered_namespace = metadata_namespace(document) or default_namespace
    return namespace is None or rendered_namespace == namespace


def rendered_has_resource(
    documents: list[dict[str, Any]],
    kind: str,
    name: str,
    namespace: str | None = None,
    *,
    default_namespace: str | None = None,
) -> bool:
    return any(
        document.get("kind") == kind
        and metadata_name(document) == name
        and rendered_namespace_matches(
            document,
            namespace,
            default_namespace=default_namespace,
        )
        for document in documents
    )


def rendered_references_secret(
    documents: list[dict[str, Any]],
    name: str,
    namespace: str | None = None,
    *,
    default_namespace: str | None = None,
) -> bool:
    for document in documents:
        if not rendered_namespace_matches(
            document,
            namespace,
            default_namespace=default_namespace,
        ):
            continue
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


def rendered_secret_binding_is_owned(
    documents: list[dict[str, Any]],
    name: str,
    namespace: str | None,
    binding_path: str,
) -> bool:
    expected_workloads = {
        "internalBindings.postgres.credentialsRef": frozenset(
            {
                "account-service",
                "automation-scripting-service",
                "entity-management-service",
                "game-design-service",
                "game-logic-service",
                "game-session-service",
                "logging-admin-service",
                "social-groups-service",
                "tcp-proxy-service",
                "world-management-service",
            }
        ),
        "internalBindings.jwt.signingKeysRef": frozenset(
            {
                "account-service",
                "automation-scripting-service",
                "entity-management-service",
                "game-design-service",
                "game-logic-service",
                "game-session-service",
                "logging-admin-service",
                "social-groups-service",
                "world-management-service",
                "spring-cloud-gateway",
                "tcp-proxy-service",
            }
        ),
        "internalBindings.jwt.jwksRef": frozenset({"account-service"}),
    }.get(binding_path, frozenset())
    required_mounts = {
        "internalBindings.jwt.signingKeysRef": "/var/run/secrets/firemud/jwt",
        "internalBindings.jwt.jwksRef": "/var/run/secrets/firemud/jwks",
    }
    required_mount = required_mounts.get(binding_path)
    if not expected_workloads or (
        binding_path != "internalBindings.postgres.credentialsRef" and required_mount is None
    ):
        return False
    if not isinstance(name, str) or not name.strip() or not isinstance(namespace, str) or not namespace.strip():
        return False
    owner_counts: dict[str, int] = {}
    workload_counts: dict[str, int] = {}

    def valid_metadata(value: Any) -> bool:
        return (
            isinstance(value, str)
            and bool(value)
            and len(value) <= 63
            and re.fullmatch(r"[a-z0-9](?:[-a-z0-9]*[a-z0-9])?", value) is not None
        )

    def primary_container(document: dict[str, Any]) -> tuple[str | None, dict[str, Any] | None, list[dict[str, Any]]]:
        metadata = document.get("metadata")
        workload_name = metadata.get("name") if isinstance(metadata, dict) else None
        workload_namespace_value = metadata.get("namespace") if isinstance(metadata, dict) else None
        spec = (((document.get("spec") or {}).get("template") or {}).get("spec") or {})
        containers = spec.get("containers")
        if not isinstance(containers, list):
            return workload_name, None, []
        typed_containers = [container for container in containers if isinstance(container, dict)]
        if (
            not valid_metadata(workload_name)
            or not valid_metadata(workload_namespace_value)
            or workload_name not in expected_workloads
        ):
            return workload_name, None, typed_containers
        matches = [container for container in typed_containers if container.get("name") == workload_name]
        if len(matches) != 1:
            return workload_name, None, typed_containers
        return workload_name, matches[0], typed_containers

    def binding_reference_parts(
        container: dict[str, Any], volumes: dict[str, str | None]
    ) -> tuple[list[dict[str, Any]], list[dict[str, Any]], list[dict[str, Any]]]:
        env_from_refs: list[dict[str, Any]] = []
        secret_key_refs: list[dict[str, Any]] = []
        for entry in (container.get("envFrom") or []) + (container.get("env") or []):
            if not isinstance(entry, dict):
                continue
            secret_ref = entry.get("secretRef")
            if isinstance(secret_ref, dict) and secret_ref.get("name") == name:
                env_from_refs.append(entry)
            value_from = entry.get("valueFrom")
            secret_key_ref = value_from.get("secretKeyRef") if isinstance(value_from, dict) else None
            if isinstance(secret_key_ref, dict) and secret_key_ref.get("name") == name:
                secret_key_refs.append(entry)
        mount_refs = [
            mount
            for mount in container.get("volumeMounts") or []
            if isinstance(mount, dict) and volumes.get(mount.get("name")) == name
        ]
        return env_from_refs, secret_key_refs, mount_refs

    def references_binding(container: dict[str, Any], volumes: dict[str, str | None]) -> bool:
        env_from_refs, secret_key_refs, mount_refs = binding_reference_parts(container, volumes)
        return bool(env_from_refs or secret_key_refs or mount_refs)

    def correct_binding(container: dict[str, Any], volumes: dict[str, str | None]) -> bool:
        env_from_refs, secret_key_refs, mount_refs = binding_reference_parts(container, volumes)
        if binding_path == "internalBindings.postgres.credentialsRef":
            return len(env_from_refs) == 1 and not secret_key_refs and not mount_refs
        return (
            len(mount_refs) == 1
            and mount_refs[0].get("mountPath") == required_mount
            and mount_refs[0].get("readOnly") is True
            and not env_from_refs
            and not secret_key_refs
        )

    for document in documents:
        if document.get("kind") not in {"Deployment", "StatefulSet", "DaemonSet"}:
            continue
        metadata = document.get("metadata")
        workload_name = metadata.get("name") if isinstance(metadata, dict) else None
        workload_namespace_value = metadata.get("namespace") if isinstance(metadata, dict) else None
        spec = (((document.get("spec") or {}).get("template") or {}).get("spec") or {})
        raw_containers = spec.get("containers")
        if not isinstance(raw_containers, list):
            continue
        volumes = {
            volume.get("name"): ((volume.get("secret") or {}).get("secretName"))
            for volume in spec.get("volumes") or []
            if isinstance(volume, dict)
        }
        typed_containers = [container for container in raw_containers if isinstance(container, dict)]
        if workload_name in expected_workloads:
            if not valid_metadata(workload_name) or not valid_metadata(workload_namespace_value):
                return False
            if workload_namespace_value != namespace:
                return False
            workload_counts[workload_name] = workload_counts.get(workload_name, 0) + 1
            if workload_counts[workload_name] > 1:
                return False
        has_reference = any(references_binding(container, volumes) for container in typed_containers)
        if not has_reference:
            continue
        if (
            not valid_metadata(workload_name)
            or not valid_metadata(workload_namespace_value)
            or workload_namespace_value != namespace
            or workload_name not in expected_workloads
        ):
            return False
        _, primary, _ = primary_container(document)
        if primary is None:
            return False
        for container in typed_containers:
            if not references_binding(container, volumes):
                continue
            if container is not primary or not correct_binding(container, volumes):
                return False
            owner_counts[workload_name] = owner_counts.get(workload_name, 0) + 1
    return bool(owner_counts) and all(count == 1 for count in owner_counts.values())


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
    scheme, _namespace, segments = parsed
    if allowed_schemes and scheme not in allowed_schemes:
        allowed = ", ".join(sorted(allowed_schemes))
        return f"{label} must use one of the allowed schemes: {allowed}"
    if exact_segment_count is not None and len(segments) != exact_segment_count:
        return f"{label} must include exactly {exact_segment_count} binding path segment(s)"
    if allowed_leading_segments is not None and (len(segments) < 2 or segments[0] not in allowed_leading_segments):
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


def secret_binding_namespace(ref: Any) -> str | None:
    parsed = parse_binding_ref(ref)
    if parsed is None:
        return None
    scheme, namespace, segments = parsed
    if scheme != "secret" or len(segments) != 1:
        return None
    return namespace


def rendered_references_image_pull_secret(
    documents: list[dict[str, Any]], name: str, namespace: str | None = None
) -> bool:
    for document in documents:
        if not rendered_namespace_matches(document, namespace, default_namespace=namespace):
            continue
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


def normalize_service_endpoint(host: str, port: str, namespace: str) -> str:
    host = host.strip().lower().rstrip(".")
    if (
        not host
        or "://" in host
        or "/" in host
        or any(
            not label or not re.fullmatch(r"[a-z0-9-]+", label)
            for label in host.split(".")
        )
    ):
        raise ValueError("Redis host must be a DNS host name")
    port = port.strip()
    if not port.isdigit() or not 1 <= int(port) <= 65535:
        raise ValueError("Redis port must be an integer from 1 to 65535")
    if "." not in host:
        host = f"{host}.{namespace}.svc.cluster.local"
    return f"{host}:{port}"


def normalize_redis_url(value: Any, namespace: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("Redis URL must be a non-empty string")
    parsed = urlsplit(value.strip())
    if parsed.scheme not in {"redis", "rediss"} or parsed.username or parsed.password:
        raise ValueError("Redis URL must use redis:// or rediss:// without credentials")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment or not parsed.hostname:
        raise ValueError("Redis URL must identify only a host and port")
    port = parsed.port or 6379
    return normalize_service_endpoint(parsed.hostname, str(port), namespace)


def service_override_in_environment(value: Any, namespace: str) -> bool:
    if not isinstance(value, str) or not value.strip() or "://" in value or "/" in value:
        return False
    host_port = value.strip().lower().rstrip(".")
    host = host_port
    if host_port.count(":") == 1:
        host, port = host_port.rsplit(":", 1)
        if not port.isdigit() or not 1 <= int(port) <= 65535:
            return False
    if not host or any(not label or not re.fullmatch(r"[a-z0-9-]+", label) for label in host.split(".")):
        return False
    if "." not in host:
        return True
    allowed_suffixes = (
        f".{namespace}",
        f".{namespace}.svc",
        f".{namespace}.svc.cluster.local",
    )
    return host.endswith(allowed_suffixes)


def expected_redis_endpoint(data: dict[str, Any], role: str) -> str | None:
    value = get(data, f"internalBindings.redis.{role}.endpoint")
    return value.strip().lower() if isinstance(value, str) and value.strip() else None


def effective_redis_endpoints(
    documents: list[dict[str, Any]], expected: dict[str, Any]
) -> tuple[set[str], list[str]]:
    expected_coord = expected_redis_endpoint(expected, "coordination")
    expected_cache = expected_redis_endpoint(expected, "cache")
    endpoints: set[str] = set()
    issues: list[str] = []
    for document in documents:
        namespace = workload_namespace(document)
        for _, container, _ in primary_containers(document):
            values, env_issues = effective_container_env(
                documents,
                document,
                container,
                relevant_prefixes=("FIREMUD_REDIS_",),
            )
            issues.extend(env_issues)
            if not any(key.startswith("FIREMUD_REDIS_") for key in values):
                continue
            resolved: dict[str, str] = {}
            for role, prefix in (("coordination", "FIREMUD_REDIS_COORD"), ("cache", "FIREMUD_REDIS_CACHE")):
                url = values.get(prefix + "_URL")
                try:
                    if url:
                        endpoint = normalize_redis_url(url, namespace)
                    else:
                        host = values.get(prefix + "_HOST")
                        port = values.get(prefix + "_PORT")
                        if not host or not port:
                            raise ValueError(
                                f"{prefix} requires URL or host and port"
                            )
                        endpoint = normalize_service_endpoint(host, port, namespace)
                except ValueError as exc:
                    issues.append(f"{prefix} effective endpoint is invalid: {exc}")
                    continue
                resolved[role] = endpoint
                expected_endpoint = expected_coord if role == "coordination" else expected_cache
                if expected_endpoint != endpoint:
                    issues.append(f"{prefix} effective endpoint {endpoint} does not match expected {expected_endpoint}")
            if len(resolved) == 2:
                endpoints.update(resolved.values())
                if resolved["coordination"] == resolved["cache"]:
                    issues.append("Coordination and Cache Redis endpoints resolve to the same host:port")
    if not endpoints:
        issues.append("Could not resolve Redis endpoints from referenced workload configuration")
    return endpoints, issues


def canonical_gateway_ws_endpoint(
    documents: list[dict[str, Any]], expected: dict[str, Any]
) -> tuple[str | None, list[str]]:
    listener_ref = get(expected, "internalBindings.certificates.gatewayInternalWsListenerRef")
    parsed_ref = parse_binding_ref(listener_ref)
    if (
        parsed_ref is None
        or parsed_ref[0] != "cert-manager"
        or not parsed_ref[1]
        or len(parsed_ref[2]) != 1
    ):
        return None, [
            "internalBindings.certificates.gatewayInternalWsListenerRef must be a cert-manager binding with one namespace-local name"
        ]
    namespace = parsed_ref[1]
    services = [
        document for document in documents
        if document.get("kind") == "Service"
        and metadata_name(document) == "spring-cloud-gateway-mtls"
        and rendered_namespace_matches(document, namespace, default_namespace=namespace)
    ]
    if len(services) != 1:
        return None, ["exactly one rendered internal Gateway mTLS Service is required"]
    service = services[0]
    if (service.get("spec") or {}).get("type", "ClusterIP") != "ClusterIP":
        return None, ["Gateway mTLS Service must remain ClusterIP/internal-only"]
    ports = [entry for entry in (service.get("spec") or {}).get("ports") or [] if isinstance(entry, dict)]
    if len(ports) != 1 or not isinstance(ports[0].get("port"), int):
        return None, ["Gateway mTLS Service must expose exactly one numeric port"]
    host = f"spring-cloud-gateway-mtls.{namespace}.svc.cluster.local"
    return f"{host}:{ports[0]['port']}", []


def validate_gateway_ws_values(
    documents: list[dict[str, Any]], expected: dict[str, Any]
) -> tuple[list[str], list[str]]:
    canonical, issues = canonical_gateway_ws_endpoint(documents, expected)
    values: list[str] = []
    if canonical is None:
        return values, issues
    canonical_host, canonical_port = canonical.rsplit(":", 1)
    for document in documents:
        for workload_name, container, volumes in primary_containers(document):
            declared_names = {
                entry.get("name")
                for entry in container.get("env") or []
                if isinstance(entry, dict)
            }
            if workload_name != "tcp-proxy-service" and "GATEWAY_WS_URL" not in declared_names:
                continue
            env, env_issues = effective_container_env(
                documents,
                document,
                container,
                relevant_names={"GATEWAY_WS_URL", *BRIDGE_WS_PATHS, *GRPC_TLS_PATH_NAMES},
            )
            issues.extend(env_issues)
            for path_name, expected_path in BRIDGE_WS_PATHS.items():
                if env.get(path_name) != expected_path:
                    issues.append(
                        f"{path_name} must be exactly {expected_path!r} for the player-facing bridge"
                    )
            grpc_path_values = {
                name: env.get(name) for name in GRPC_TLS_PATH_NAMES
            }
            missing_grpc_paths = [
                name
                for name, path in grpc_path_values.items()
                if not isinstance(path, str) or not path.startswith("/")
            ]
            if missing_grpc_paths:
                issues.append(
                    "all canonical gRPC TLS path variables must be configured as absolute paths: "
                    + ", ".join(missing_grpc_paths)
                )
            grpc_paths = {
                path
                for path in grpc_path_values.values()
                if isinstance(path, str) and path.startswith("/")
            }

            def path_is_under(path: str, mount_path: str) -> bool:
                return path == mount_path or path.startswith(mount_path.rstrip("/") + "/")

            grpc_mounts = [
                mount
                for mount in container.get("volumeMounts") or []
                if isinstance(mount, dict)
                and mount.get("readOnly") is True
                and volumes.get(mount.get("name"))
                and isinstance(mount.get("mountPath"), str)
                and any(path_is_under(path, mount["mountPath"]) for path in grpc_paths)
            ]
            if len(grpc_mounts) != 1:
                issues.append(
                    "exactly one dedicated read-only Secret-backed gRPC TLS mount is required"
                )
            grpc_secret_names = {
                volumes.get(mount.get("name")) for mount in grpc_mounts
            }
            bridge_ref = parse_binding_ref(
                get(expected, "internalBindings.certificates.tcpProxyBridgeClientRef")
            )
            expected_bridge_namespace = (
                bridge_ref[1]
                if bridge_ref and bridge_ref[0] == "cert-manager" and len(bridge_ref[2]) == 1
                else None
            )
            expected_bridge_name = (
                bridge_ref[2][0]
                if bridge_ref and bridge_ref[0] == "cert-manager" and len(bridge_ref[2]) == 1
                else None
            )
            pod_spec = (((document.get("spec") or {}).get("template") or {}).get("spec") or {})
            volume_definitions = {
                volume.get("name"): volume
                for volume in pod_spec.get("volumes") or []
                if isinstance(volume, dict) and volume.get("name")
            }

            def bridge_volume_is_valid(
                mount: dict[str, Any],
                *,
                bridge_name: str | None = expected_bridge_name,
                bridge_namespace: str | None = expected_bridge_namespace,
                current_document: dict[str, Any] = document,
                current_volumes: dict[str, str | None] = volumes,
                current_volume_definitions: dict[str, dict[str, Any]] = volume_definitions,
            ) -> bool:
                if bridge_name is None or bridge_namespace is None:
                    return False
                if workload_namespace(current_document) != bridge_namespace:
                    issues.append(
                        "Gateway WebSocket bridge workload namespace does not match "
                        f"expected {bridge_namespace}"
                    )
                    return False
                secret_name = current_volumes.get(mount.get("name"))
                if secret_name != bridge_name:
                    issues.append(
                        "Gateway WebSocket bridge mount must reference Secret "
                        f"{bridge_namespace}/{bridge_name}"
                    )
                    return False
                if "subPath" in mount:
                    issues.append("Gateway WebSocket bridge Secret mount must not use subPath")
                    return False
                volume = current_volume_definitions.get(mount.get("name")) or {}
                secret = volume.get("secret") if isinstance(volume, dict) else None
                if not isinstance(secret, dict):
                    return False
                items = secret.get("items")
                expected_item_pairs = set(BRIDGE_WS_SECRET_ITEM_PATHS.items())
                if (
                    not isinstance(items, list)
                    or len(items) != len(expected_item_pairs)
                    or any(
                        not isinstance(item, dict)
                        or set(item) != {"key", "path"}
                        or not isinstance(item.get("key"), str)
                        or not isinstance(item.get("path"), str)
                        for item in items
                    )
                ):
                    issues.append(
                        "Gateway WebSocket bridge Secret volume items must select exactly "
                        "client.crt->client.crt, client.key->client.key, and ca.crt->ca.crt"
                    )
                    return False
                item_pairs = {(item["key"], item["path"]) for item in items}
                if item_pairs != expected_item_pairs:
                    issues.append(
                        "Gateway WebSocket bridge Secret volume items must select exactly "
                        "client.crt->client.crt, client.key->client.key, and ca.crt->ca.crt"
                    )
                    return False
                return True

            bridge_mounts = [
                mount
                for mount in container.get("volumeMounts") or []
                if isinstance(mount, dict)
                and mount.get("mountPath") == "/tls"
                and mount.get("readOnly") is True
                and volumes.get(mount.get("name"))
                and volumes.get(mount.get("name")) not in grpc_secret_names
                and bridge_volume_is_valid(mount)
            ]
            if len(bridge_mounts) != 1:
                issues.append(
                    "exactly one dedicated read-only Secret-backed /tls mount is required for the Gateway WebSocket bridge"
                )
            value = env.get("GATEWAY_WS_URL")
            if not value:
                issues.append("GATEWAY_WS_URL is not explicitly configured")
                continue
            try:
                parsed = urlsplit(value)
                parsed_host = parsed.hostname
            except ValueError:
                issues.append(f"GATEWAY_WS_URL {value!r} is malformed")
                continue
            try:
                parsed_port = parsed.port
            except ValueError:
                issues.append(f"GATEWAY_WS_URL {value!r} has an invalid port")
                continue
            effective_port = 443 if parsed_port is None else parsed_port
            if not 1 <= effective_port <= 65535:
                issues.append(f"GATEWAY_WS_URL {value!r} has an invalid port")
                continue
            if (
                parsed.scheme != "wss"
                or parsed.username is not None
                or parsed.password is not None
                or parsed_host != canonical_host
                or str(effective_port) != canonical_port
                or parsed.path != "/ws/game"
                or parsed.query
                or parsed.fragment
            ):
                issues.append(f"GATEWAY_WS_URL {value!r} does not match canonical {canonical_host}:{canonical_port}/ws/game")
                continue
            values.append(value)
    if not values:
        issues.append("no valid effective GATEWAY_WS_URL values were found")
    elif len(set(values)) != 1:
        issues.append("effective GATEWAY_WS_URL values conflict across workloads")
    return values, issues


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


def has_secret_reference(
    documents: list[dict[str, Any]],
    name: str,
    namespace: str | None = None,
    *,
    default_namespace: str | None = None,
) -> bool:
    for document in documents:
        if not rendered_namespace_matches(
            document,
            namespace,
            default_namespace=default_namespace,
        ):
            continue
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
    if policy_id not in PREFLIGHT_POLICY_CATALOG:
        raise ValueError(f"Unknown preflight policy ID: {policy_id}")
    category = PREFLIGHT_POLICY_CATALOG[policy_id]
    effective_required = required and category != "advisory"
    check_results.append(CheckResult(policy_id, effective_required, status, message))
    return effective_required and status == "fail"


def expected_bindings_schema_issues(data: Any) -> list[str]:
    """Reject unknown expected-bindings keys before optional defaults are applied."""
    if not isinstance(data, dict):
        return ["expected-bindings manifest must be a mapping"]

    nested_keys: dict[str, set[str] | None] = {
        "internalBindings": {"postgres", "redis", "jwt", "certificates", "registry"},
        "backupStorage": {"enabled", "bucket", "endpoint", "bindingRef", "fingerprint"},
        "assetStorage": {"enabled", "bucket", "endpoint", "bindingRef", "fingerprint"},
        "outboundComms": {"enabled", "smtpHost", "webhookTargets"},
        "operatorCredentials": {"bindingRef", "fingerprint"},
        "serviceDiscovery": {"mode", "allowedOverrides"},
        "observability": {"otelCollectorEndpoint"},
        "backupMaintenancePause": {"enabled"},
    }
    child_keys: dict[str, set[str] | None] = {
        "internalBindings.postgres": {"endpoint", "credentialsRef"},
        "internalBindings.redis": {"coordination", "cache"},
        "internalBindings.redis.coordination": {"endpoint"},
        "internalBindings.redis.cache": {"endpoint"},
        "internalBindings.jwt": {"custodyMode", "signingKeysRef", "jwksRef"},
        "internalBindings.certificates": {
            "issuerRef",
            "workloadMtlsRef",
            "gatewayInternalWsListenerRef",
            "tcpProxyBridgeClientRef",
            "backupControlPlaneClientRef",
        },
        "internalBindings.registry": {"imagePullSecretRef"},
        "observability.otelCollectorEndpoint": {"value", "shared", "sharedRationale"},
    }
    conditional_value_keys = {"value", "shared", "sharedRationale"}
    allowed_top_level = {
        "environment",
        *nested_keys,
    }
    issues: list[str] = []
    for key in data:
        if key not in allowed_top_level:
            issues.append(f"unknown top-level key '{key}'")

    def check_mapping(path: str, value: Any, allowed: set[str] | None) -> None:
        if not isinstance(value, dict) or allowed is None:
            return
        for key in value:
            if key not in allowed:
                issues.append(f"unknown expected-bindings key '{path}.{key}'")

    for path, allowed in nested_keys.items():
        check_mapping(path, get(data, path), allowed)
    for path, allowed in child_keys.items():
        check_mapping(path, get(data, path), allowed)
    for path in (
        "backupStorage.bucket",
        "backupStorage.endpoint",
        "assetStorage.bucket",
        "assetStorage.endpoint",
        "outboundComms.smtpHost",
    ):
        check_mapping(path, get(data, path), conditional_value_keys)
    webhook_targets = get(data, "outboundComms.webhookTargets")
    if isinstance(webhook_targets, dict):
        for target_name, target_value in webhook_targets.items():
            check_mapping(
                f"outboundComms.webhookTargets.{target_name}",
                target_value,
                conditional_value_keys,
            )
    return issues


def expected_binding_checks(
    expected_bindings_path: Path,
    expected_bindings_ref: str,
    env_class: str,
    documents: list[dict[str, Any]],
    context: str = "ci-static",
    *,
    expected_bindings: Any | None = None,
    expected_bindings_error: str | None = None,
) -> list[CheckResult]:
    if expected_bindings_error is not None:
        return [
            CheckResult(
                "PREFLIGHT-SECRETS-002",
                True,
                "fail",
                f"Expected-bindings manifest is unreadable: {expected_bindings_error}",
            ),
            CheckResult("PREFLIGHT-BOOTSTRAP-001", True, "fail", "Expected-bindings manifest is unreadable"),
            CheckResult("PREFLIGHT-EXTERNAL-001", True, "fail", "Expected-bindings manifest is unreadable"),
            CheckResult("PREFLIGHT-SERVICES-001", True, "fail", "Expected-bindings manifest is unreadable"),
        ]

    if expected_bindings is None:
        try:
            expected_bindings = load_yaml(expected_bindings_path) or {}
        except YAML_READ_ERRORS as exc:
            return [
                CheckResult("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings manifest is unreadable: {exc}"),
                CheckResult("PREFLIGHT-BOOTSTRAP-001", True, "fail", "Expected-bindings manifest is unreadable"),
                CheckResult("PREFLIGHT-EXTERNAL-001", True, "fail", "Expected-bindings manifest is unreadable"),
                CheckResult("PREFLIGHT-SERVICES-001", True, "fail", "Expected-bindings manifest is unreadable"),
            ]

    data = expected_bindings

    if not isinstance(data, dict):
        return [
            CheckResult("PREFLIGHT-SECRETS-002", True, "fail", "Expected-bindings manifest must be a mapping"),
            CheckResult("PREFLIGHT-BOOTSTRAP-001", True, "fail", "Expected-bindings manifest is invalid"),
            CheckResult("PREFLIGHT-EXTERNAL-001", True, "fail", "Expected-bindings manifest is invalid"),
            CheckResult("PREFLIGHT-SERVICES-001", True, "fail", "Expected-bindings manifest is invalid"),
        ]

    results: list[CheckResult] = []
    schema_issues = expected_bindings_schema_issues(data)
    if data.get("environment") != env_class:
        results.append(
            CheckResult("PREFLIGHT-SECRETS-002", True, "fail", f"Expected-bindings environment mismatch in {expected_bindings_ref}")
        )
    elif schema_issues:
        results.append(
            CheckResult(
                "PREFLIGHT-SECRETS-002",
                True,
                "fail",
                "Expected-bindings schema contains unknown keys: " + "; ".join(schema_issues),
            )
        )
    else:
        required_internal = [
            "internalBindings.postgres.endpoint",
            "internalBindings.postgres.credentialsRef",
            "internalBindings.redis.coordination.endpoint",
            "internalBindings.redis.cache.endpoint",
            "internalBindings.jwt.custodyMode",
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
        custody_mode = get(data, "internalBindings.jwt.custodyMode")
        custody_mode_invalid = (
            not isinstance(custody_mode, str) or custody_mode not in JWT_CUSTODY_MODES
        )
        secret_refs = [
            (
                "internalBindings.postgres.credentialsRef",
                get(data, "internalBindings.postgres.credentialsRef"),
            ),
            (
                "internalBindings.jwt.signingKeysRef",
                get(data, "internalBindings.jwt.signingKeysRef"),
            ),
            (
                "internalBindings.jwt.jwksRef",
                get(data, "internalBindings.jwt.jwksRef"),
            ),
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
        if (
            custody_mode == IMPLEMENTED_JWT_CUSTODY_MODE
            and get(data, "internalBindings.jwt.jwksRef") != LEGACY_PLAYER_JWKS_REF
        ):
            invalid_internal_refs.append(
                "internalBindings.jwt.jwksRef must be exactly "
                + LEGACY_PLAYER_JWKS_REF
                + " for the current legacy player-facing contract"
            )
        missing_rendered_refs = []
        for binding_path, ref_value in secret_refs:
            name = secret_binding_name(ref_value)
            namespace = secret_binding_namespace(ref_value)
            if name and not rendered_secret_binding_is_owned(
                documents,
                name,
                namespace,
                binding_path,
            ):
                missing_rendered_refs.append(name)
        registry_pull_secret = secret_binding_name(get(data, "internalBindings.registry.imagePullSecretRef"))
        registry_pull_namespace = secret_binding_namespace(
            get(data, "internalBindings.registry.imagePullSecretRef")
        )
        if missing and "internalBindings.jwt.custodyMode" in missing:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Expected-bindings missing internal keys: " + ", ".join(missing),
                )
            )
        elif custody_mode_invalid:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    "Expected-bindings internalBindings.jwt.custodyMode must be one of: "
                    + ", ".join(JWT_CUSTODY_MODES),
                )
            )
        elif custody_mode != IMPLEMENTED_JWT_CUSTODY_MODE:
            results.append(
                CheckResult(
                    "PREFLIGHT-SECRETS-002",
                    True,
                    "fail",
                    f"JWT custody mode {custody_mode} is recognized but not currently implemented; "
                    f"only {IMPLEMENTED_JWT_CUSTODY_MODE} is supported by this executable",
                )
            )
        elif pause_config_error:
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
        elif registry_pull_secret and not rendered_references_image_pull_secret(
            documents, registry_pull_secret, registry_pull_namespace
        ):
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

    bootstrap_bindings = [
        (
            "internalBindings.postgres.credentialsRef",
            secret_binding_name(get(data, "internalBindings.postgres.credentialsRef")),
            secret_binding_namespace(get(data, "internalBindings.postgres.credentialsRef")),
        ),
        (
            "internalBindings.jwt.signingKeysRef",
            secret_binding_name(get(data, "internalBindings.jwt.signingKeysRef")),
            secret_binding_namespace(get(data, "internalBindings.jwt.signingKeysRef")),
        ),
    ]
    missing_bootstrap = [
        name
        for binding_path, name, namespace in bootstrap_bindings
        if name and not rendered_secret_binding_is_owned(
            documents,
            name,
            namespace,
            binding_path,
        )
    ]
    custody_mode = get(data, "internalBindings.jwt.custodyMode")
    custody_gate_required = context == "operator" and env_class in player_facing_environments()
    if custody_gate_required:
        results.append(
            CheckResult(
                "PREFLIGHT-BOOTSTRAP-001",
                True,
                "fail",
                "No accepted player-facing JWT custody proof is implemented for selected mode: "
                + str(custody_mode),
            )
        )
    elif missing_bootstrap:
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

    backup_storage = data.get("backupStorage")
    backup_storage_error = None
    backup_storage_enabled = False
    if not isinstance(backup_storage, dict) or not isinstance(backup_storage.get("enabled"), bool):
        backup_storage_error = "Expected-bindings backupStorage.enabled must be a boolean"
    else:
        backup_storage_enabled = backup_storage["enabled"]
        if not backup_storage_enabled:
            placeholder_fields = sorted(
                field
                for field in ("bucket", "endpoint", "bindingRef", "fingerprint")
                if field in backup_storage
            )
            if placeholder_fields:
                backup_storage_error = (
                    "Expected-bindings backupStorage fields must be omitted when disabled; "
                    "enabled=false must omit backup binding fields: "
                    + ", ".join(placeholder_fields)
                )
        if env_class == "production" and not backup_storage_enabled:
            production_error = "backupStorage.enabled must be true for production"
            if backup_storage_error:
                backup_storage_error += f"; {production_error}"
            else:
                backup_storage_error = production_error

    asset_storage_enabled, asset_storage_error = optional_integration_state(
        data, "assetStorage"
    )
    outbound_comms_enabled, outbound_comms_error = optional_integration_state(
        data, "outboundComms"
    )
    if backup_storage_enabled:
        missing_backup = []
        if not backup_storage.get("bucket"):
            missing_backup.append("backupStorage.bucket")
        if not backup_storage.get("bindingRef") and not backup_storage.get("fingerprint"):
            missing_backup.append("backupStorage.bindingRef or backupStorage.fingerprint")
        if missing_backup:
            backup_storage_error = (
                "Expected-bindings enabled backup storage missing keys: "
                + ", ".join(missing_backup)
            )
    external_requirements = [
        ("operatorCredentials.bindingRef", "operatorCredentials.fingerprint"),
    ]
    if backup_storage_enabled:
        external_requirements[0:0] = [
            ("backupStorage.bucket", None),
            ("backupStorage.bindingRef", "backupStorage.fingerprint"),
        ]
    if asset_storage_enabled:
        external_requirements[2:2] = [
            ("assetStorage.bucket", None),
            ("assetStorage.endpoint", None),
            ("assetStorage.bindingRef", "assetStorage.fingerprint"),
        ]
    missing_external = []
    for primary, alternate in external_requirements:
        if not get(data, primary) and (alternate is None or not get(data, alternate)):
            missing_external.append(primary if alternate is None else f"{primary} or {alternate}")
    webhook_targets = get(data, "outboundComms.webhookTargets")
    invalid_external_refs = [
        error
        for error in [
            (
                binding_ref_format_error("backupStorage.bindingRef", get(data, "backupStorage.bindingRef"))
                if backup_storage_enabled
                and get(data, "backupStorage.bindingRef")
                else None
            ),
            (
                binding_ref_format_error(
                    "assetStorage.bindingRef", get(data, "assetStorage.bindingRef")
                )
                if asset_storage_enabled and get(data, "assetStorage.bindingRef")
                else None
            ),
            (
                binding_ref_format_error(
                    "operatorCredentials.bindingRef", get(data, "operatorCredentials.bindingRef")
                )
                if get(data, "operatorCredentials.bindingRef")
                else None
            ),
        ]
        if error
    ]
    optional_errors = [
        error for error in (asset_storage_error, outbound_comms_error) if error
    ]
    if backup_storage_error or optional_errors:
        results.append(
            CheckResult(
                "PREFLIGHT-EXTERNAL-001",
                True,
                "fail",
                "Expected-bindings external configuration is invalid: "
                + "; ".join(
                    error
                    for error in ([backup_storage_error] if backup_storage_error else [])
                    + optional_errors
                    if error
                ),
            )
        )
    elif missing_external:
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
    elif outbound_comms_enabled and (
        not isinstance(webhook_targets, dict) or not webhook_targets
    ) and not get(data, "outboundComms.smtpHost"):
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
    target_namespace = next(
        (workload_namespace(document) for document in documents if primary_containers(document)),
        "firemud",
    )
    override_lines, override_issues = extract_service_discovery_overrides(documents)
    if mode == "kubernetes-dns-default" and (override_lines or override_issues):
        results.append(
            CheckResult(
                "PREFLIGHT-SERVICES-001",
                True,
                "fail",
                "Effective workloads contain service-discovery overrides or unresolved sources: "
                + "; ".join(override_issues or sorted(override_lines)),
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
            for override_name, expected_value in allowed.items():
                if not isinstance(override_name, str) or not re.fullmatch(
                    r"FIREMUD_SERVICES_[A-Z0-9_]+", str(override_name)
                ):
                    failures.append(
                        "serviceDiscovery.allowedOverrides keys must match "
                        "FIREMUD_SERVICES_[A-Z0-9_]+"
                    )
                elif not isinstance(expected_value, str):
                    failures.append(
                        f"serviceDiscovery.allowedOverrides[{override_name}] must be a string"
                    )
                elif not service_override_in_environment(expected_value, target_namespace):
                    failures.append(
                        f"serviceDiscovery.allowedOverrides[{override_name}] must target the {target_namespace} Kubernetes environment"
                    )
                elif override_name not in override_lines:
                    failures.append(
                        f"Allowed override {override_name} is missing from effective workloads"
                    )
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
                if not service_override_in_environment(rendered_value, target_namespace):
                    failures.append(
                        f"Rendered {override_name} must target the {target_namespace} Kubernetes environment"
                    )
                    continue
                if expected_value != rendered_value:
                    failures.append(
                        f"Rendered {override_name}='{rendered_value}' does not match allowed value '{expected_value}'"
                    )
            failures.extend(override_issues)
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


def jwt_jwks_checks(
    documents: list[dict[str, Any]], jwks_namespace: str
) -> list[CheckResult]:
    inline_secret = False
    missing_secret_path: list[str] = []
    missing_signing_mount: list[str] = []
    missing_jwks_mount: list[str] = []
    account_jwks_workloads: list[str] = []
    global_secret_path = config_value(documents, "FIREMUD_AUTH_JWT_SECRET_PATH")
    global_jwks_path = config_value(documents, "FIREMUD_AUTH_JWKS_PATH")
    for document in documents:
        for workload_name, container, volumes in primary_containers(document):
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
            if container_name == "account-service":
                account_label = f"{workload_name}/{container_name}"
                account_jwks_workloads.append(account_label)
                jwks_path = env_value(container, "FIREMUD_AUTH_JWKS_PATH") or global_jwks_path
                if (
                    not jwks_path
                    or not str(jwks_path).startswith("/var/run/secrets/firemud/jwks/")
                    or not has_secret_mount(
                        container,
                        volumes,
                        "jwt-jwks",
                        "/var/run/secrets/firemud/jwks",
                    )
                ):
                    missing_jwks_mount.append(account_label)
    if not account_jwks_workloads:
        missing_jwks_mount.append("<missing account-service>")

    results: list[CheckResult] = []
    if inline_secret:
        results.append(CheckResult("PREFLIGHT-JWT-001", False, "fail", "Inline JWT secret material detected in rendered workloads"))
    elif missing_secret_path:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                False,
                "fail",
                "FIREMUD_AUTH_JWT_SECRET_PATH is missing for workloads: " + ", ".join(missing_secret_path),
            )
        )
    elif missing_signing_mount:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                False,
                "fail",
                "JWT signing Secret is not mounted at the configured path for workloads: " + ", ".join(missing_signing_mount),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-JWT-001",
                False,
                "pass",
                "JWT file-path contract and signing Secret mounts are satisfied",
            )
        )

    if rendered_has_resource(
        documents,
        "ConfigMap",
        "jwt-jwks",
        jwks_namespace,
        default_namespace=jwks_namespace,
    ):
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                False,
                "fail",
                "jwt-jwks is configured as a ConfigMap in player-facing context",
            )
        )
    elif any(
        document.get("kind") == "Secret"
        and metadata_name(document) == "jwt-jwks"
        and not rendered_namespace_matches(
            document,
            jwks_namespace,
            default_namespace=jwks_namespace,
        )
        for document in documents
    ):
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                False,
                "fail",
                f"jwt-jwks Secret resource is not in the expected namespace: {jwks_namespace}",
            )
        )
    elif not rendered_has_resource(
        documents,
        "Secret",
        "jwt-jwks",
        jwks_namespace,
        default_namespace=jwks_namespace,
    ) and not has_secret_reference(
        documents,
        "jwt-jwks",
        jwks_namespace,
        default_namespace=jwks_namespace,
    ):
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                False,
                "fail",
                "Rendered workloads do not reference jwt-jwks as a Secret",
            )
        )
    elif missing_jwks_mount:
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                False,
                "fail",
                "Account Service does not mount jwt-jwks at the configured JWKS path: "
                + ", ".join(missing_jwks_mount),
            )
        )
    else:
        results.append(
            CheckResult(
                "PREFLIGHT-JWKS-001",
                False,
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
        *RECOVERY_COMPATIBILITY_VERIFIED_POINT_FIELDS,
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
        *RECOVERY_COMPATIBILITY_VERIFIED_POINT_FIELDS,
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
    except TIMESTAMP_ERRORS as exc:
        return ("fail", str(exc))
    if evaluated_at > now_dt:
        return ("fail", "recoveryCompatibility.evaluatedAt is future-dated")
    try:
        generated_at = parse_timestamp(attestation.get("generatedAt"), "Attestation generatedAt")
    except TIMESTAMP_ERRORS as exc:
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

    point_status, point_message = validate_compact_verified_restorable_point(
        recovery_compatibility,
        root_dir,
        now_dt,
    )
    if point_status != "pass":
        return ("fail", point_message)

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
    except JSON_READ_ERRORS as exc:
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
    missing_non_jwt_attestation_fields = [
        field
        for field in missing_attestation_fields
        if field not in {"jwtCustodyProof", "jwtRotationEvidenceRef"}
    ]
    if missing_non_jwt_attestation_fields:
        return (
            "fail",
            str(att.get("rollbackMode", "unknown")),
            "Attestation missing required canonical fields: "
            + ", ".join(missing_non_jwt_attestation_fields),
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
    if not isinstance(att.get("approvedBy"), str) or not att["approvedBy"].strip():
        return ("fail", rollback_mode, "Attestation approvedBy must be non-empty")

    try:
        generated_at = parse_timestamp(att.get("generatedAt"), "Attestation generatedAt")
    except TIMESTAMP_ERRORS as exc:
        return ("fail", rollback_mode, str(exc))
    if generated_at > now_dt:
        return ("fail", rollback_mode, "Attestation generatedAt is future-dated")

    if recovery_status != "pass":
        return ("fail", rollback_mode, recovery_message)

    if missing_attestation_fields:
        return (
            "fail",
            rollback_mode,
            "Attestation missing required canonical fields: " + ", ".join(missing_attestation_fields),
        )
    custody_proof_error = jwt_custody_proof_error(
        "Attestation jwtCustodyProof", att["jwtCustodyProof"]
    )
    if custody_proof_error:
        return ("fail", rollback_mode, custody_proof_error)

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
    if not GIT_COMMIT_SHA_RE.fullmatch(staging_sha):
        return ("fail", rollback_mode, "Attestation stagingOverlayCommitSha must be a full Git commit SHA")
    if not git_commit_exists(root_dir, staging_sha):
        return ("fail", rollback_mode, f"Staging overlay commit does not exist in Git: {staging_sha}")

    staging_event_id = att.get("stagingDeploymentEventId")
    try:
        parsed_staging_event_id = uuid.UUID(str(staging_event_id))
    except (ValueError, TypeError, AttributeError):
        return ("fail", rollback_mode, "Attestation stagingDeploymentEventId must be a UUID")
    if str(parsed_staging_event_id) != staging_event_id:
        return ("fail", rollback_mode, "Attestation stagingDeploymentEventId must use canonical UUID form")

    smoke_status, smoke_message = validate_promotion_smoke_evidence(
        root_dir,
        att["smokeEvidence"],
        "Attestation smokeEvidence",
        staging_sha,
        staging_event_id,
    )
    if smoke_status != "pass":
        return ("fail", rollback_mode, smoke_message)

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
    except JSON_READ_ERRORS as exc:
        return ("fail", rollback_mode, f"Staging deployment record unreadable: {exc}")

    if not isinstance(record, dict):
        return ("fail", rollback_mode, "Staging deployment record must be a JSON object")

    expected_bindings_ref = canonical_expected_bindings_ref("staging")
    expected_bindings_path = resolve_repo_path(root_dir, expected_bindings_ref)
    if not expected_bindings_path.exists():
        return ("fail", rollback_mode, f"Canonical expected-bindings manifest not found: {expected_bindings_ref}")
    try:
        expected_bindings_digest = immutable_file_digest(expected_bindings_path)
    except OSError as exc:
        return ("fail", rollback_mode, f"Canonical expected-bindings manifest unreadable: {exc}")

    secret_evidence, secret_evidence_error = load_immutable_json_evidence(
        root_dir, record.get("secretComplianceEvidenceRef"), "secretComplianceEvidenceRef"
    )
    if secret_evidence_error:
        return ("fail", rollback_mode, secret_evidence_error)
    if secret_evidence is None:
        return (
            "fail",
            rollback_mode,
            "secretComplianceEvidenceRef loader returned no evidence without an error",
        )
    if secret_evidence.get("environment") != "staging":
        return ("fail", rollback_mode, "Staging secret compliance evidence environment must be staging")
    secret_records = secret_evidence.get("records", {})
    if not isinstance(secret_records, dict):
        return ("fail", rollback_mode, "Staging secret compliance evidence records must be an object")
    bootstrap_fields_present = (
        "bootstrapOperationId" in secret_evidence
        or "provisioningGeneration" in secret_evidence
        or any(
            isinstance(secret_record, dict)
            and (
                "bootstrapOperationId" in secret_record
                or "provisioningGeneration" in secret_record
            )
            for secret_record in secret_records.values()
        )
    )

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
        "expectedBindingsRef",
        "expectedBindingsDigest",
        "jwtCustodyProof",
        "jwtRotationEvidenceRef",
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
    record_custody_proof_error = jwt_custody_proof_error(
        "Staging deployment record jwtCustodyProof", record["jwtCustodyProof"]
    )
    if record_custody_proof_error:
        return ("fail", rollback_mode, record_custody_proof_error)
    if att["jwtCustodyProof"] != record["jwtCustodyProof"]:
        return (
            "fail",
            rollback_mode,
            "Staging deployment record jwtCustodyProof does not match the attestation",
        )
    if att.get("jwtRotationEvidenceRef") != record.get("jwtRotationEvidenceRef"):
        return (
            "fail",
            rollback_mode,
            "Staging deployment record jwtRotationEvidenceRef does not match the attestation",
        )
    if not isinstance(record.get("appliedBy"), str) or not record["appliedBy"].strip():
        return ("fail", rollback_mode, "Staging deployment record appliedBy must be non-empty")
    if not isinstance(record.get("serviceDigests"), dict) or not record["serviceDigests"]:
        return ("fail", rollback_mode, "Staging deployment record serviceDigests must be a non-empty object")
    record_smoke_status, record_smoke_message = validate_promotion_smoke_evidence_entry_shape(
        record["smokeEvidence"],
        "Staging deployment record smokeEvidence",
    )
    if record_smoke_status != "pass":
        return ("fail", rollback_mode, record_smoke_message)
    if record["smokeEvidence"] != att["smokeEvidence"]:
        return ("fail", rollback_mode, "Staging deployment record smokeEvidence does not match the attestation")

    record_timestamps: dict[str, dt.datetime] = {}
    for field in ("appliedAt", "secretComplianceSnapshotAt"):
        try:
            record_timestamp = parse_timestamp(record.get(field), f"Staging deployment record {field}")
        except TIMESTAMP_ERRORS as exc:
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
    if record.get("expectedBindingsRef") != expected_bindings_ref:
        return ("fail", rollback_mode, "Staging deployment record expectedBindingsRef mismatch")
    if not isinstance(record.get("expectedBindingsDigest"), str) or not IMMUTABLE_ARTIFACT_ID_RE.fullmatch(
        record["expectedBindingsDigest"]
    ):
        return ("fail", rollback_mode, "Staging deployment record expectedBindingsDigest must be sha256-qualified")
    if record["expectedBindingsDigest"] != expected_bindings_digest:
        return ("fail", rollback_mode, "Staging deployment record expectedBindingsDigest mismatch")

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
    except JSON_READ_ERRORS as exc:
        return ("fail", rollback_mode, f"Staging preflight report unreadable: {exc}")
    preflight_status, preflight_message = validate_preflight_report(
        preflight_report,
        "staging",
        expected_bindings_ref,
        staging_sha,
        now_dt=now_dt,
        expected_deployment_event_id=str(record["deploymentEventId"]),
        completed_by=record_timestamps["appliedAt"],
        expected_bindings_digest=expected_bindings_digest,
        allowed_supplemental_policy_ids=(
            JWT_ROTATION_POLICY_ID,
            att["jwtCustodyProof"]["proofId"],
        ),
    )
    if preflight_status != "pass":
        return ("fail", rollback_mode, preflight_message)
    if preflight_report.get("expectedBindingsRef") != record["expectedBindingsRef"]:
        return ("fail", rollback_mode, "Staging preflight report expectedBindingsRef does not match the deployment record")
    if preflight_report.get("expectedBindingsDigest") != record["expectedBindingsDigest"]:
        return ("fail", rollback_mode, "Staging preflight report expectedBindingsDigest does not match the deployment record")
    if preflight_report.get("deploymentEventId") != record["deploymentEventId"]:
        return ("fail", rollback_mode, "Staging preflight report deploymentEventId does not match the deployment record")
    preflight_custody_proof_error = jwt_custody_proof_error(
        "Staging operator preflight report jwtCustodyProof",
        preflight_report.get("jwtCustodyProof"),
    )
    if preflight_custody_proof_error:
        return ("fail", rollback_mode, preflight_custody_proof_error)
    if preflight_report.get("jwtCustodyProof") != att["jwtCustodyProof"]:
        return (
            "fail",
            rollback_mode,
            "Staging operator preflight report jwtCustodyProof does not match the attestation",
        )
    custody_policy_ids = {
        proof_id for proof_id, _, _ in ACCEPTED_JWT_CUSTODY_PROOF_TUPLES
    }
    custody_checks = [
        check
        for check in preflight_report.get("checkResults", [])
        if isinstance(check, dict) and check.get("policyId") in custody_policy_ids
    ]
    selected_custody_policy_id = att["jwtCustodyProof"]["proofId"]
    if any(check.get("policyId") != selected_custody_policy_id for check in custody_checks):
        return (
            "fail",
            rollback_mode,
            "Staging operator preflight report contains an alternate JWT custody policy result",
        )
    if (
        len(custody_checks) != 1
        or custody_checks[0].get("status") != "pass"
        or custody_checks[0].get("required") is not True
    ):
        return (
            "fail",
            rollback_mode,
            "Staging operator preflight report must contain one passing required result for the selected JWT custody policy",
        )
    rotation_checks = [
        check
        for check in preflight_report.get("checkResults", [])
        if isinstance(check, dict) and check.get("policyId") == JWT_ROTATION_POLICY_ID
    ]
    if len(rotation_checks) != 1 or rotation_checks[0].get("status") != "pass":
        return (
            "fail",
            rollback_mode,
            "Staging operator preflight report must contain one passing PREFLIGHT-JWT-ROTATION-001 result",
        )
    if rotation_checks[0].get("required") is not True:
        return (
            "fail",
            rollback_mode,
            "Staging operator preflight report JWT rotation result must be event-scoped",
        )

    rotation_evidence, rotation_evidence_error = load_immutable_json_evidence(
        root_dir,
        att["jwtRotationEvidenceRef"],
        "jwtRotationEvidenceRef",
    )
    if rotation_evidence_error:
        return ("fail", rollback_mode, rotation_evidence_error)
    if rotation_evidence is None:
        return (
            "fail",
            rollback_mode,
            "jwtRotationEvidenceRef loader returned no evidence without an error",
        )
    if rotation_evidence.get("policyId") != JWT_ROTATION_POLICY_ID:
        return (
            "fail",
            rollback_mode,
            "jwtRotationEvidenceRef evidence policyId must be PREFLIGHT-JWT-ROTATION-001",
        )
    if rotation_evidence.get("status") != "pass":
        return ("fail", rollback_mode, "jwtRotationEvidenceRef evidence status must be pass")
    if rotation_evidence.get("deploymentEventId") != staging_event_id:
        return (
            "fail",
            rollback_mode,
            "jwtRotationEvidenceRef evidence deploymentEventId does not match the staging event",
        )
    rotation_custody_proof_error = jwt_custody_proof_error(
        "jwtRotationEvidenceRef evidence jwtCustodyProof",
        rotation_evidence.get("jwtCustodyProof"),
    )
    if rotation_custody_proof_error:
        return ("fail", rollback_mode, rotation_custody_proof_error)
    if rotation_evidence.get("jwtCustodyProof") != att["jwtCustodyProof"]:
        return (
            "fail",
            rollback_mode,
            "jwtRotationEvidenceRef evidence jwtCustodyProof does not match the attestation",
        )

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
    if secret_status != "pass":
        return ("fail", rollback_mode, "Staging deployment record secretComplianceStatus must be pass")
    try:
        required_secret_classes = required_secret_compliance_classes(root_dir, "staging")
    except (TypeError, ValueError) as exc:
        return ("fail", rollback_mode, str(exc))
    records = secret_records

    # Bootstrap evidence is authorized by the validator through the immutable
    # bootstrap operation and provisioning generation. Rotation evidence uses
    # the per-record evidence operation instead. Treat a partially supplied
    # bootstrap pair as bootstrap-shaped so it fails closed rather than being
    # silently accepted as rotation evidence.
    top_bootstrap_operation_id = secret_evidence.get("bootstrapOperationId")
    top_provisioning_generation = secret_evidence.get("provisioningGeneration")
    if bootstrap_fields_present:
        if (
            not isinstance(top_bootstrap_operation_id, str)
            or not top_bootstrap_operation_id.strip()
        ):
            return (
                "fail",
                rollback_mode,
                "Staging secret compliance bootstrap evidence requires a non-empty bootstrapOperationId",
            )
        if (
            isinstance(top_provisioning_generation, bool)
            or not isinstance(top_provisioning_generation, int)
            or top_provisioning_generation <= 0
        ):
            return (
                "fail",
                rollback_mode,
                "Staging secret compliance bootstrap evidence requires a positive integer provisioningGeneration",
            )
    for key in required_secret_classes:
        rec = records.get(key)
        if not isinstance(rec, dict):
            return ("fail", rollback_mode, f"Staging secret compliance evidence missing record: {key}")
        if rec.get("targetEnvironment") != "staging":
            return (
                "fail",
                rollback_mode,
                f"Staging secret compliance evidence targetEnvironment mismatch: {key}",
            )
        if rec.get("credentialClass") != key:
            return (
                "fail",
                rollback_mode,
                f"Staging secret compliance evidence credentialClass mismatch: {key}",
            )
        if bootstrap_fields_present:
            if rec.get("bootstrapOperationId") != top_bootstrap_operation_id:
                return (
                    "fail",
                    rollback_mode,
                    f"Staging secret compliance bootstrapOperationId mismatch: {key}",
                )
            if rec.get("provisioningGeneration") != top_provisioning_generation:
                return (
                    "fail",
                    rollback_mode,
                    f"Staging secret compliance provisioningGeneration mismatch: {key}",
                )
        else:
            evidence_operation_id = rec.get("evidenceOperationId")
            if not isinstance(evidence_operation_id, str) or not evidence_operation_id.strip():
                return (
                    "fail",
                    rollback_mode,
                    f"Staging secret compliance evidence missing evidenceOperationId: {key}",
                )
        immutable_id = rec.get("immutableArtifactId")
        if not isinstance(immutable_id, str) or not IMMUTABLE_ARTIFACT_ID_RE.fullmatch(immutable_id):
            return (
                "fail",
                rollback_mode,
                f"Staging secret compliance evidence record is not immutable: {key}",
            )
        try:
            expected_digest = canonical_evidence_digest(rec)
        except (TypeError, ValueError) as exc:
            return (
                "fail",
                rollback_mode,
                f"Staging secret compliance evidence record cannot be canonically hashed: {key}: {exc}",
            )
        if immutable_id != expected_digest:
            return (
                "fail",
                rollback_mode,
                f"Staging secret compliance evidence record digest mismatch: {key}",
            )

    return ("pass", rollback_mode, "Production promotion attestation and staging deployment evidence are valid")


def backup_readiness_check(path: Path, now: str, deployment_ref: str, root_dir: Path) -> tuple[str, str]:
    try:
        data = load_json(path)
    except JSON_READ_ERRORS as exc:
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
                "newestVerifiedRestorablePointAt",
                "backupLastSuccessAt",
                "backupVerifyLastSuccessAt",
                "restoreDrillLastSuccessAt",
            )
        }
    except TIMESTAMP_ERRORS as exc:
        return ("fail", str(exc))

    future_timestamps = [name for name, timestamp in evidence_timestamps.items() if timestamp > now_dt]
    if future_timestamps:
        return ("fail", "Backup-readiness evidence contains future-dated timestamps: " + ", ".join(future_timestamps))

    backup_ts = evidence_timestamps["backupLastSuccessAt"]
    newest_verified_point_ts = evidence_timestamps["newestVerifiedRestorablePointAt"]
    verify_ts = evidence_timestamps["backupVerifyLastSuccessAt"]
    drill_ts = evidence_timestamps["restoreDrillLastSuccessAt"]
    if (now_dt - backup_ts).total_seconds() > 90 * 60:
        return ("fail", "Backup-readiness evidence is stale: backupLastSuccessAt older than 90 minutes")
    if (now_dt - newest_verified_point_ts).total_seconds() > VERIFIED_RESTORABLE_POINT_MAX_AGE_SECONDS:
        return (
            "fail",
            "Backup-readiness evidence is stale: newestVerifiedRestorablePointAt older than 15 minutes",
        )
    if (now_dt - verify_ts).total_seconds() > 36 * 60 * 60:
        return ("fail", "Backup-readiness evidence is stale: backupVerifyLastSuccessAt older than 36 hours")
    if (now_dt - drill_ts).total_seconds() > 30 * 24 * 60 * 60:
        return ("fail", "Backup-readiness evidence is stale: restoreDrillLastSuccessAt older than 30 days")

    try:
        attestation = load_json(attestation_path)
    except JSON_READ_ERRORS as exc:
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
    for field in RECOVERY_COMPATIBILITY_VERIFIED_POINT_FIELDS:
        if data.get(field) != recovery_compatibility.get(field):
            return ("fail", f"Backup-readiness evidence {field} does not match the attestation")
    try:
        compatibility_evaluated_at = parse_timestamp(
            recovery_compatibility.get("evaluatedAt"), "recoveryCompatibility.evaluatedAt"
        )
    except TIMESTAMP_ERRORS as exc:
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

    point_status, point_message = _validate_verified_restorable_point_reference(
        root_dir,
        str(data["newestVerifiedRestorablePointRef"]),
        str(data["environment"]),
        str(data["newestVerifiedRestorablePointAt"]),
        str(data["backupVerifyLastSuccessAt"]),
        str(data["newestVerifiedRestorablePointDigest"]),
        str(data["backupArtifactRef"]),
        context="Verified restorable point",
    )
    if point_status != "pass":
        return ("fail", point_message)

    return (
        "fail",
        (
            "Roll-forward-only promotion remains blocked until canonical recovery-controller, "
            "participant, confidentiality, hardening, and controlled-reopen evidence validation is implemented"
        ),
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
        (
            "Production traffic-open gate unavailable: durable environment-wide "
            "recovery-controller authority is not implemented; checked-in projections "
            "and caller-supplied tenant/region/timestamp evidence cannot authorize traffic"
        ),
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
    expected_bindings_digest: str,
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
        "policyCatalogVersion": PREFLIGHT_POLICY_CATALOG_VERSION,
        "startedAt": started_at,
        "completedAt": completed_at,
        "checkResults": [
            {
                "policyId": check.policy_id,
                "category": check.category,
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
    report["expectedBindingsDigest"] = expected_bindings_digest
    output_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with output_path.open("x", encoding="utf-8") as handle:
            json.dump(report, handle, indent=2)
            handle.write("\n")
    except FileExistsError:
        fail(f"Preflight report output already exists and will not be overwritten: {output_path}")


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
    checked_out_commit_sha = run(["git", "rev-parse", "HEAD"]).strip()
    deployment_ref = os.environ.get("FIREMUD_DEPLOYMENT_REF") or checked_out_commit_sha
    if env_class == "hobby-self-hosted":
        if not isinstance(deployment_ref, str) or not operator_deployment_ref_is_current(
            env_class, deployment_ref, checked_out_commit_sha
        ):
            fail("FIREMUD_DEPLOYMENT_REF must contain only lowercase ASCII letters, digits, and hyphens")
    elif not isinstance(deployment_ref, str) or not operator_deployment_ref_is_current(
        env_class, deployment_ref, checked_out_commit_sha
    ):
        fail("staging/production FIREMUD_DEPLOYMENT_REF must equal the checked-out full Git commit SHA")
    waiver_path = os.environ.get("FIREMUD_PREFLIGHT_WAIVER", "")
    if waiver_path:
        fail("Preflight waiver execution remains blocked until one-time consumption authority is implemented")
    deployment_event_id = str(uuid.uuid4())
    expected_bindings_ref = canonical_expected_bindings_ref(env_class)
    expected_bindings_path = root_dir / expected_bindings_ref
    if not expected_bindings_path.exists():
        fail(f"Expected-bindings manifest not found: {expected_bindings_path}")
    try:
        expected_bindings_digest = immutable_file_digest(expected_bindings_path)
    except OSError as exc:
        fail(f"Expected-bindings manifest unreadable: {exc}")
    expected_bindings_load_error = None
    try:
        expected_bindings = load_yaml(expected_bindings_path) or {}
    except YAML_READ_ERRORS as exc:
        expected_bindings = {}
        expected_bindings_load_error = str(exc)
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

    for check in expected_binding_checks(
        expected_bindings_path,
        expected_bindings_ref,
        env_class,
        documents,
        context=context,
        expected_bindings=expected_bindings,
        expected_bindings_error=expected_bindings_load_error,
    ):
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
        for secret_name, secret_namespace, binding_path in expected_player_secret_bindings(expected_bindings):
            if not secret_name or not rendered_secret_binding_is_owned(
                documents,
                secret_name,
                secret_namespace,
                binding_path,
            ):
                has_required_failure = append_result(
                    check_results,
                    "PREFLIGHT-SECRETS-001", True, "fail",
                    f"Rendered workloads do not prove ownership of required Secret binding: {binding_path} ({secret_name})",
                ) or has_required_failure
                secret_check_failed = True
                break
        if not secret_check_failed:
            has_required_failure = append_result(
                check_results, "PREFLIGHT-SECRETS-001", True, "pass", "Rendered workloads reference required player-facing Secret bindings",
            ) or has_required_failure
    else:
        for secret_name, secret_namespace, binding_path in expected_player_secret_bindings(
            expected_bindings
        ):
            if not secret_name or not secret_namespace:
                failure_message = (
                    "Cannot resolve required Secret binding from expected bindings: "
                    + binding_path
                )
            else:
                failure_message = secret_lookup_failure(secret_name, secret_namespace)
            if failure_message is not None:
                has_required_failure = append_result(
                    check_results,
                    "PREFLIGHT-SECRETS-001", True, "fail", failure_message,
                ) or has_required_failure
                secret_check_failed = True
                break
        if not secret_check_failed:
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-SECRETS-001", True, "pass",
                "Required player-facing Secrets exist in the target cluster",
            ) or has_required_failure

    jwks_namespace = secret_binding_namespace(
        get(expected_bindings, "internalBindings.jwt.jwksRef")
    )
    if jwks_namespace is None:
        has_required_failure = append_result(
            check_results,
            "PREFLIGHT-JWT-001",
            False,
            "fail",
            "Cannot evaluate the JWT signing contract without a resolvable jwt-jwks namespace",
        ) or has_required_failure
        has_required_failure = append_result(
            check_results,
            "PREFLIGHT-JWKS-001",
            False,
            "fail",
            "Cannot resolve the jwt-jwks namespace from the expected binding",
        ) or has_required_failure
    else:
        for check in jwt_jwks_checks(documents, jwks_namespace):
            has_required_failure = append_result(
                check_results,
                check.policy_id, check.required, check.status, check.message,
            ) or has_required_failure

    _, bridge_issues = validate_gateway_ws_values(documents, expected_bindings)
    if bridge_issues:
        has_required_failure = append_result(
            check_results, "PREFLIGHT-BRIDGE-001", True, "fail", "Gateway bridge validation failed: " + "; ".join(bridge_issues)
        ) or has_required_failure
    else:
        has_required_failure = append_result(check_results, "PREFLIGHT-BRIDGE-001", True, "pass", "Gateway bridge alignment is valid") or has_required_failure

    _, redis_issues = effective_redis_endpoints(documents, expected_bindings)
    if redis_issues:
        has_required_failure = append_result(
            check_results, "PREFLIGHT-REDIS-001", True, "fail", "Redis effective configuration validation failed: " + "; ".join(redis_issues)
        ) or has_required_failure
    else:
        has_required_failure = append_result(check_results, "PREFLIGHT-REDIS-001", True, "pass", "Redis role split contract is satisfied") or has_required_failure

    promotion_attestation = os.environ.get("FIREMUD_PROMOTION_ATTESTATION", "")
    canonical_attestation_ref = canonical_promotion_attestation_ref(deployment_ref)
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
        elif canonical_attestation_ref is None or not is_canonical_promotion_attestation_ref(
            promotion_attestation, deployment_ref, root_dir=root_dir
        ):
            has_required_failure = append_result(
                check_results,
                "PREFLIGHT-PROMOTION-001",
                True,
                "fail",
                "FIREMUD_PROMOTION_ATTESTATION must be the canonical repository-relative path "
                + str(canonical_attestation_ref),
            ) or has_required_failure
            has_required_failure = append_result(check_results, "PREFLIGHT-BACKUP-001", True, "fail", "Recovery compatibility cannot be evaluated because the promotion attestation path is not canonical") or has_required_failure
        elif not (root_dir / canonical_attestation_ref).exists():
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
                root_dir / canonical_attestation_ref,
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
                (
                    "Hobby traffic-open gate unavailable: durable environment-wide recovery-controller "
                    "authority is not implemented; checked-in projections and backup-compliance evidence "
                    "cannot authorize traffic"
                ),
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
        expected_bindings_digest,
    )

    if has_required_failure:
        print(f"Preflight failed; report written to: {output_path}", file=sys.stderr)
        return 1

    print(f"Preflight passed; report written to: {output_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
