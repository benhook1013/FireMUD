# Staging Recovery Sanitization Evidence

Store one record per staging restore that originates from production data:

- `<recovery-ref>.json`

Every player-facing staging recovery record must follow `design/architecture/system-architecture-backup-recovery-evidence-and-compliance.md#canonical-recovery-record`. A restore sourced from production also adds the sanitization requirements below.

Staging production-origin requirements:

- `environment` (`staging`)
- `recoveryRef`
- `sourceBackup`
- `sanitizedAt`
- `sanitizedBy`
- `controlsApplied` (list of redaction/anonymization controls)
- `validationEvidence` (checks proving sanitized state before reopening traffic)
- `certificateReissuance`
- `jwtHardening`
- `databaseCredentialRotation`
- `externalCredentialValidation` with records for:
  - `backup-storage`
  - `asset-storage`
  - `outbound-comms`
  - `operator-credentials`

`dev-tools/restores/validate-external-credentials.sh staging` requires `SANITIZATION_EVIDENCE_REF` to point to one of these records, but it still expects the legacy hardening key names. It must be updated to the canonical control-group names; a legacy-script pass is not complete recovery proof.

Sanitization evidence supplements the environment-wide cold-start, quarantine, convergence, hardening, smoke, and controlled-reopen controller state; it does not replace those controls. The checked-in staging record is an immutable projection exported after the controller reaches `finalized`, not runtime authority.
