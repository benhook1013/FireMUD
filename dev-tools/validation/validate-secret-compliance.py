#!/usr/bin/env python3
"""Validate secret-compliance records used by CI security checks."""

from __future__ import annotations

import datetime as dt
import os
import pathlib
import re
import sys

import yaml

DEV_TOOLS_DIR = pathlib.Path(__file__).resolve().parents[1]
if str(DEV_TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(DEV_TOOLS_DIR))

from evidence_digest import canonical_evidence_digest

REQUIRED = {
    "jwt-signing-keys-jwks",
    "postgres-application-credentials",
    "operator-credentials",
}
IMMUTABLE_ARTIFACT_ID_RE = re.compile(r"^sha256:[0-9a-f]{64}$")
EXPECTED_BINDING_FILES = {
    "production": pathlib.Path("design/operations/environments/production/expected-bindings.yaml"),
    "staging": pathlib.Path("design/operations/environments/staging/expected-bindings.yaml"),
    "hobby-self-hosted": pathlib.Path(
        "design/operations/environments/hobby-self-hosted/expected-bindings.yaml"
    ),
}
ENV_FILES = {
    "production": pathlib.Path("design/operations/secret-compliance/production.yaml"),
    "staging": pathlib.Path("design/operations/secret-compliance/staging.yaml"),
    "hobby-self-hosted": pathlib.Path(
        "design/operations/secret-compliance/hobby-self-hosted.yaml"
    ),
}
PROVISIONING_STATES = {"not-provisioned", "noncompliant", "provisioned"}
BOOTSTRAP_OPERATION_STATUSES = {"pending", "blocked", "failed", "completed"}
BOOTSTRAP_OPERATION_FIELDS = {
    "bootstrapOperationId",
    "bootstrapOperationStatus",
    "provisioningGeneration",
}


def utc_now() -> dt.datetime:
    today_override = os.environ.get("SECRET_COMPLIANCE_TODAY")
    if today_override:
        try:
            return parse_timestamp(today_override)
        except ValueError as exc:
            raise SystemExit(
                "SECRET_COMPLIANCE_TODAY override must be an ISO-8601 timestamp "
                "with an explicit timezone"
            ) from exc
    return dt.datetime.now(dt.timezone.utc)


def parse_timestamp(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("timestamp must include an explicit timezone")
    return parsed


def main() -> int:
    root = pathlib.Path(os.environ.get("SECRET_COMPLIANCE_ROOT", "."))
    today = utc_now()
    staging_hard_gate_date = dt.datetime(2026, 7, 1, tzinfo=dt.timezone.utc)
    enforcement_mode = (
        os.environ.get("SECRET_COMPLIANCE_ENFORCEMENT_MODE", "strict").strip().lower()
    )
    warning_window_days = int(
        os.environ.get("SECRET_COMPLIANCE_WARNING_WINDOW_DAYS", "7")
    )
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")

    if enforcement_mode not in {"strict", "advisory"}:
        raise SystemExit(
            "Unsupported SECRET_COMPLIANCE_ENFORCEMENT_MODE="
            f"{enforcement_mode!r}; expected 'strict' or 'advisory'"
        )

    failures: list[str] = []
    warnings: list[str] = []
    non_authorizing_environments: list[str] = []

    def hard_gated(env: str) -> bool:
        if env == "production":
            return True
        if env == "staging":
            return today >= staging_hard_gate_date
        return False

    def mark_non_authorizing(env: str) -> None:
        if env not in non_authorizing_environments:
            non_authorizing_environments.append(env)

    def record_issue(env: str, msg: str) -> None:
        if enforcement_mode == "strict" and hard_gated(env):
            failures.append(msg)
        else:
            warnings.append(msg)
            mark_non_authorizing(env)

    def record_schema_issue(msg: str) -> None:
        if enforcement_mode == "strict":
            failures.append(msg)
        else:
            warnings.append(msg)
            issue_env = msg.partition(":")[0]
            if issue_env not in ENV_FILES:
                raise ValueError(f"schema issue lacks an environment prefix: {msg}")
            mark_non_authorizing(issue_env)

    for env, relative_path in ENV_FILES.items():
        path = root / relative_path
        if not path.exists():
            record_issue(env, f"{env}: missing compliance file at {relative_path}")
            continue

        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, yaml.YAMLError) as exc:
            record_issue(env, f"{env}: cannot parse {relative_path} as YAML: {exc}")
            continue

        if not isinstance(data, dict):
            record_schema_issue(f"{env}: compliance record must be a mapping")
            continue

        record_environment = data.get("environment")
        if record_environment != env:
            record_schema_issue(
                f"{env}: compliance record environment must be '{env}', got "
                f"{record_environment!r}",
            )
            continue

        provisioning_state = data.get("provisioningState")
        if (
            not isinstance(provisioning_state, str)
            or provisioning_state not in PROVISIONING_STATES
        ):
            record_schema_issue(
                f"{env}: provisioningState must be one of "
                f"{', '.join(sorted(PROVISIONING_STATES))}",
            )
            continue

        if "credentialClasses" not in data:
            record_schema_issue(f"{env}: credentialClasses must be present")
            continue
        classes = data["credentialClasses"]
        if not isinstance(classes, dict):
            record_schema_issue(f"{env}: credentialClasses must be a mapping")
            continue

        expected_binding_path = root / EXPECTED_BINDING_FILES[env]
        asset_storage_enabled = False
        backup_storage_enabled = False
        expected_bindings_valid = True
        try:
            expected_bindings = yaml.safe_load(
                expected_binding_path.read_text(encoding="utf-8")
            )
        except (OSError, UnicodeError, yaml.YAMLError) as exc:
            record_schema_issue(
                f"{env}: cannot read canonical expected-bindings manifest "
                f"{EXPECTED_BINDING_FILES[env]}: {exc}"
            )
            expected_bindings = {}
            expected_bindings_valid = False
        if expected_bindings_valid and (
            not isinstance(expected_bindings, dict)
            or expected_bindings.get("environment") != env
        ):
            record_schema_issue(
                f"{env}: canonical expected-bindings manifest must target '{env}'"
            )
            expected_bindings_valid = False
        elif expected_bindings_valid:
            backup_storage = expected_bindings.get("backupStorage")
            if not isinstance(backup_storage, dict) or not isinstance(
                backup_storage.get("enabled"), bool
            ):
                record_schema_issue(
                    f"{env}: backupStorage.enabled must be a boolean"
                )
                expected_bindings_valid = False
            else:
                backup_storage_enabled = backup_storage["enabled"]
                if env == "production" and not backup_storage_enabled:
                    record_schema_issue(
                        "production: backupStorage.enabled must be true"
                    )
                    expected_bindings_valid = False
            asset_storage = expected_bindings.get("assetStorage")
            if "assetStorage" in expected_bindings and not isinstance(
                asset_storage, dict
            ):
                record_schema_issue(f"{env}: assetStorage must be a mapping when present")
                expected_bindings_valid = False
            elif isinstance(asset_storage, dict):
                enabled = asset_storage.get("enabled")
                if not isinstance(enabled, bool):
                    record_schema_issue(
                        f"{env}: assetStorage.enabled must be a boolean when assetStorage is present"
                    )
                    expected_bindings_valid = False
                else:
                    asset_storage_enabled = enabled

        required_classes = set(REQUIRED)
        if backup_storage_enabled:
            required_classes.add("backup-object-store-credentials")
        if asset_storage_enabled:
            required_classes.add("asset-store-credentials")

        if provisioning_state == "not-provisioned":
            illegal_operation_fields = sorted(
                BOOTSTRAP_OPERATION_FIELDS & set(data.keys())
            )
            if illegal_operation_fields:
                record_schema_issue(
                    f"{env}: not-provisioned compliance records must not contain "
                    "bootstrap operation fields: "
                    f"{', '.join(illegal_operation_fields)}",
                )
            if classes:
                record_schema_issue(
                    f"{env}: not-provisioned compliance records must not list "
                    "credential classes",
                )
            mark_non_authorizing(env)
            continue

        if not expected_bindings_valid:
            continue

        bootstrap_status = data.get("bootstrapOperationStatus")
        bootstrap_operation_id = data.get("bootstrapOperationId")
        provisioning_generation = data.get("provisioningGeneration")
        operation_fields_valid = True

        if (
            not isinstance(bootstrap_status, str)
            or bootstrap_status not in BOOTSTRAP_OPERATION_STATUSES
        ):
            record_schema_issue(
                f"{env}: bootstrapOperationStatus must be one of "
                f"{', '.join(sorted(BOOTSTRAP_OPERATION_STATUSES))}",
            )
            operation_fields_valid = False
        if (
            not isinstance(bootstrap_operation_id, str)
            or not bootstrap_operation_id.strip()
        ):
            record_schema_issue(
                f"{env}: {provisioning_state} records require a non-empty "
                "bootstrapOperationId",
            )
            operation_fields_valid = False
        if (
            isinstance(provisioning_generation, bool)
            or not isinstance(provisioning_generation, int)
            or provisioning_generation <= 0
        ):
            record_schema_issue(
                f"{env}: {provisioning_state} records require a positive integer "
                "provisioningGeneration",
            )
            operation_fields_valid = False

        if provisioning_state == "noncompliant":
            record_schema_issue(
                f"{env}: provisioningState=noncompliant cannot satisfy a "
                "provisioning compliance gate",
            )
            continue

        if bootstrap_status != "completed":
            record_schema_issue(
                f"{env}: provisioningState=provisioned requires "
                "bootstrapOperationStatus=completed",
            )

        missing = sorted(required_classes - set(classes.keys()))
        if missing:
            record_schema_issue(
                f"{env}: missing required credential classes: {', '.join(missing)}",
            )

        for cls in sorted(required_classes & set(classes.keys())):
            rec = classes.get(cls, {})
            if not isinstance(rec, dict):
                record_schema_issue(f"{env}:{cls}: credential record must be a mapping")
                continue

            max_age = rec.get("maxAgeDays")
            last_rotation = rec.get("lastRotationAt")
            last_provisioned = rec.get("lastProvisionedAt")
            evidence_ref = rec.get("evidenceRef")
            evidence_key = rec.get("evidenceKey")
            evidence_fields = [
                name
                for name, value in (
                    ("lastRotationAt", last_rotation),
                    ("lastProvisionedAt", last_provisioned),
                )
                if value
            ]
            if max_age is None or len(evidence_fields) != 1:
                record_issue(
                    env,
                    f"{env}:{cls}: missing maxAgeDays or exactly one of "
                    "lastRotationAt/lastProvisionedAt",
                )
                continue

            evidence_field = evidence_fields[0]
            bootstrap_record = evidence_field == "lastProvisionedAt"
            if bootstrap_record and operation_fields_valid:
                if rec.get("bootstrapOperationId") != bootstrap_operation_id:
                    record_schema_issue(
                        f"{env}:{cls}: bootstrap credential record "
                        "bootstrapOperationId must exactly match top-level "
                        "bootstrapOperationId",
                    )
                if rec.get("provisioningGeneration") != provisioning_generation:
                    record_schema_issue(
                        f"{env}:{cls}: bootstrap credential record "
                        "provisioningGeneration must exactly match top-level "
                        "provisioningGeneration",
                    )
            evidence_time = (
                last_rotation if evidence_field == "lastRotationAt" else last_provisioned
            )
            try:
                recorded_at = parse_timestamp(evidence_time)
                if recorded_at > today:
                    raise ValueError("freshness timestamp must not be in the future")
                age_days = (today - recorded_at).days
            except (TypeError, ValueError, AttributeError) as exc:
                record_issue(
                    env,
                    f"{env}:{cls}: invalid {evidence_field} '{evidence_time}': {exc}",
                )
                continue

            if age_days > int(max_age):
                record_issue(
                    env,
                    f"{env}:{cls}: credential age {age_days}d exceeds maxAgeDays={max_age}",
                )
            elif age_days >= int(max_age) - warning_window_days:
                remaining_days = int(max_age) - age_days
                warnings.append(
                    f"{env}:{cls}: credential age {age_days}d reaches maxAgeDays="
                    f"{max_age} in {remaining_days}d"
                )

            if not evidence_ref or not evidence_key:
                record_issue(env, f"{env}:{cls}: missing evidenceRef/evidenceKey")
                continue

            evidence_path = root / pathlib.Path(evidence_ref)
            if not evidence_path.exists():
                record_issue(
                    env,
                    f"{env}:{cls}: evidence file not found: {evidence_ref}",
                )
                continue

            try:
                evidence = yaml.safe_load(evidence_path.read_text(encoding="utf-8"))
            except (OSError, UnicodeError, yaml.YAMLError) as exc:
                record_issue(env, f"{env}:{cls}: evidence file unreadable: {exc}")
                continue

            if not isinstance(evidence, dict):
                record_issue(
                    env,
                    f"{env}:{cls}: evidence payload must be a mapping in "
                    f"{evidence_ref}",
                )
                continue

            if bootstrap_record and operation_fields_valid:
                if evidence.get("bootstrapOperationId") != bootstrap_operation_id:
                    record_schema_issue(
                        f"{env}:{cls}: bootstrap evidence payload "
                        "bootstrapOperationId must exactly match top-level "
                        "bootstrapOperationId",
                    )
                if evidence.get("provisioningGeneration") != provisioning_generation:
                    record_schema_issue(
                        f"{env}:{cls}: bootstrap evidence payload "
                        "provisioningGeneration must exactly match top-level "
                        "provisioningGeneration",
                    )

            records = evidence.get("records", {})
            if not isinstance(records, dict):
                record_issue(
                    env,
                    f"{env}:{cls}: evidence records must be a mapping in "
                    f"{evidence_ref}",
                )
                continue
            record = records.get(evidence_key)
            if not record:
                record_issue(
                    env,
                    f"{env}:{cls}: missing evidence record key '{evidence_key}' "
                    f"in {evidence_ref}",
                )
                continue
            if not isinstance(record, dict):
                record_issue(
                    env,
                    f"{env}:{cls}: evidence record must be a mapping in "
                    f"{evidence_ref}",
                )
                continue

            if evidence.get("environment") != env:
                record_schema_issue(
                    f"{env}:{cls}: evidence payload environment must exactly match '{env}'"
                )
            if record.get("targetEnvironment") != env:
                record_schema_issue(
                    f"{env}:{cls}: evidence record targetEnvironment must exactly match '{env}'"
                )
            if record.get("credentialClass") != cls:
                record_schema_issue(
                    f"{env}:{cls}: evidence record credentialClass must exactly match '{cls}'"
                )
            if not bootstrap_record:
                evidence_operation_id = rec.get("evidenceOperationId")
                if not isinstance(evidence_operation_id, str) or not evidence_operation_id.strip():
                    record_schema_issue(
                        f"{env}:{cls}: non-bootstrap credential records require a stable "
                        "evidenceOperationId"
                    )
                elif record.get("evidenceOperationId") != evidence_operation_id:
                    record_schema_issue(
                        f"{env}:{cls}: evidenceOperationId must exactly match the selected "
                        "credential record"
                    )

            if bootstrap_record and operation_fields_valid:
                if record.get("bootstrapOperationId") != bootstrap_operation_id:
                    record_schema_issue(
                        f"{env}:{cls}: bootstrap evidence record "
                        "bootstrapOperationId must exactly match top-level "
                        "bootstrapOperationId",
                    )
                if record.get("provisioningGeneration") != provisioning_generation:
                    record_schema_issue(
                        f"{env}:{cls}: bootstrap evidence record "
                        "provisioningGeneration must exactly match top-level "
                        "provisioningGeneration",
                    )

            immutable_id = record.get("immutableArtifactId")
            if not isinstance(immutable_id, str) or not IMMUTABLE_ARTIFACT_ID_RE.fullmatch(
                immutable_id
            ):
                record_schema_issue(
                    f"{env}:{cls}: immutableArtifactId must match sha256:<64 lowercase hex> "
                    f"in {evidence_ref}"
                )
            else:
                try:
                    expected_digest = canonical_evidence_digest(record)
                except (TypeError, ValueError) as exc:
                    record_schema_issue(
                        f"{env}:{cls}: evidence record cannot be canonically hashed: {exc}"
                    )
                else:
                    if immutable_id != expected_digest:
                        record_schema_issue(
                            f"{env}:{cls}: immutableArtifactId does not match the selected "
                            f"evidence record in {evidence_ref}"
                        )

    for msg in warnings:
        print(f"::warning::{msg}")
    for msg in failures:
        print(f"::error::{msg}")

    if summary_path:
        lines = [
            "### Secret Compliance Validation",
            f"- Enforcement mode: `{enforcement_mode}`",
            f"- Warning window: `{warning_window_days}` day(s)",
            f"- Warnings: `{len(warnings)}`",
            f"- Failures: `{len(failures)}`",
        ]
        if non_authorizing_environments:
            lines.append(
                "- Non-authorizing record validation only (inventory corroboration not performed): "
                + ", ".join(non_authorizing_environments)
            )
        if warnings:
            lines.extend(["", "#### Warnings", *[f"- {msg}" for msg in warnings]])
        if failures:
            lines.extend(["", "#### Failures", *[f"- {msg}" for msg in failures]])
        pathlib.Path(summary_path).write_text("\n".join(lines) + "\n", encoding="utf-8")

    if failures:
        print("Secret compliance validation failed")
        return 1

    if non_authorizing_environments:
        print(
            "Secret compliance record validation passed; credential compliance "
            "authorization/readiness was not established for: "
            + ", ".join(non_authorizing_environments)
        )
    else:
        print("Secret compliance validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
