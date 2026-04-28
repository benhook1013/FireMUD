#!/usr/bin/env python3
"""Validate secret-compliance records used by CI security checks."""

from __future__ import annotations

import datetime as dt
import os
import pathlib
import sys

import yaml

REQUIRED = {
    "jwt-signing-keys-jwks",
    "postgres-application-credentials",
    "backup-object-store-credentials",
    "operator-credentials",
}
ENV_FILES = {
    "production": pathlib.Path("design/operations/secret-compliance/production.yaml"),
    "staging": pathlib.Path("design/operations/secret-compliance/staging.yaml"),
    "hobby-self-hosted": pathlib.Path(
        "design/operations/secret-compliance/hobby-self-hosted.yaml"
    ),
}


def utc_now() -> dt.datetime:
    today_override = os.environ.get("SECRET_COMPLIANCE_TODAY")
    if today_override:
        return parse_timestamp(today_override)
    return dt.datetime.now(dt.timezone.utc)


def parse_timestamp(value: str) -> dt.datetime:
    parsed = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=dt.timezone.utc)
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

    def hard_gated(env: str) -> bool:
        if env == "production":
            return True
        if env == "staging":
            return today >= staging_hard_gate_date
        return False

    def record_issue(env: str, msg: str) -> None:
        if enforcement_mode == "strict" and hard_gated(env):
            failures.append(msg)
        else:
            warnings.append(msg)

    for env, relative_path in ENV_FILES.items():
        path = root / relative_path
        if not path.exists():
            record_issue(env, f"{env}: missing compliance file at {relative_path}")
            continue

        try:
            data = yaml.safe_load(path.read_text(encoding="utf-8"))
        except Exception as exc:
            record_issue(env, f"{env}: cannot parse {relative_path} as YAML: {exc}")
            continue

        classes = data.get("credentialClasses", {})
        missing = sorted(REQUIRED - set(classes.keys()))
        if missing:
            record_issue(
                env,
                f"{env}: missing required credential classes: {', '.join(missing)}",
            )

        for cls in sorted(REQUIRED & set(classes.keys())):
            rec = classes.get(cls, {})
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
            evidence_time = (
                last_rotation if evidence_field == "lastRotationAt" else last_provisioned
            )
            try:
                recorded_at = parse_timestamp(evidence_time)
                age_days = (today - recorded_at).days
            except Exception as exc:
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
            except Exception as exc:
                record_issue(env, f"{env}:{cls}: evidence file unreadable: {exc}")
                continue

            records = (evidence or {}).get("records", {})
            record = records.get(evidence_key)
            if not record:
                record_issue(
                    env,
                    f"{env}:{cls}: missing evidence record key '{evidence_key}' "
                    f"in {evidence_ref}",
                )
                continue

            immutable_id = str(record.get("immutableArtifactId", ""))
            if not immutable_id or "sha256:" not in immutable_id:
                record_issue(
                    env,
                    f"{env}:{cls}: immutableArtifactId is missing or non-immutable "
                    f"in {evidence_ref}",
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
        if warnings:
            lines.extend(["", "#### Warnings", *[f"- {msg}" for msg in warnings]])
        if failures:
            lines.extend(["", "#### Failures", *[f"- {msg}" for msg in failures]])
        pathlib.Path(summary_path).write_text("\n".join(lines) + "\n", encoding="utf-8")

    if failures:
        print("Secret compliance validation failed")
        return 1

    print("Secret compliance validation passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
