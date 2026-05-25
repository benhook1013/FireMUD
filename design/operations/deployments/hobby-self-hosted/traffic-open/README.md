# Hobby Traffic-Open Evidence

Store one record per `first-live` or `reopen` hobby traffic-open event as:

- `<deployment-ref>.json`

Canonical writer:

- `python3 dev-tools/deploy/write-traffic-open-evidence.py hobby-self-hosted <deployment-ref> <first-live|reopen> --assessed-by <operator> --preflight-report design/operations/deployments/hobby-self-hosted/preflight/<deployment-ref>.json --evidence-ref <ref>`

Required fields:

- `schemaVersion` (`traffic-open-record/v1`)
- `environment` (`hobby-self-hosted`)
- `eventType`
- `deploymentRef`
- `assessedAt`
- `assessedBy`
- `backupComplianceRef`
- `preflightReportPath`
- `evidenceRefs`

Hobby preflight requires the traffic-open record to reference the canonical backup-compliance file and a successful hobby preflight report that consumed `design/operations/environments/hobby-self-hosted/expected-bindings.yaml`.
