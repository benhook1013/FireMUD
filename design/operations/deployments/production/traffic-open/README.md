# Production Traffic-Open Evidence

Store one record per `first-live` or `reopen` production traffic-open event as:

- `<event>-<deployment-ref>.json`

Canonical writer:

- The durable environment-wide recovery controller exports this projection only after it reaches `finalized`. The pre-release gate reads the controller's `ready_to_reopen` state directly; this checked-in JSON is not required to authorize that same release and must not be hand-authored as a substitute.

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`production`)
- `eventType`
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `preflightReportPath`
- `backupLastSuccessAt`
- `backupVerifyLastSuccessAt`
- `restoreDrillLastSuccessAt`
- `quarantineReleasedAt`
- `recoveryControllerLineage`
  - `controllerRef`
  - `environmentScope` (`environment-wide`)
  - `state` (`finalized`)
- `backupConfidentialityEvidence`
- `evidenceRefs`

Production preflight requires the referenced production preflight report to exist, target the canonical production expected-bindings manifest, and contain no failing required checks before the durable controller may reach `ready_to_reopen`. After finalization, this projection must remain immutable and its release timestamp must match the finalized controller lineage. Backup-pause scope is maintenance evidence only, not traffic-open recovery proof.
