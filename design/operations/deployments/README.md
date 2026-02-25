# Deployment Evidence Records

This directory stores environment deployment evidence used by architecture contracts.

- `staging/deployments/<overlayCommitSha>.json`: staging apply records used by production promotion attestation validation.
- `<environment>/preflight/<deployment-ref>.json`: preflight policy reports.
- `<environment>/preflight/<deployment-ref>.waiver.json`: break-glass waiver records for a single deployment event.
- `hobby-self-hosted/deployments/<deployment-ref>.json`: hobby deploy evidence records.
- `hobby-self-hosted/recovery/<recovery-ref>.json`: hobby restore-hardening evidence records.
