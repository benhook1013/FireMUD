# Architecture Review Prompt: Environments, Secrets, and Deployment

Read the following documents:

- `design/architecture/infrastructure/README.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- `design/architecture/infrastructure/environment-and-secrets-backup.md`
- `design/architecture/infrastructure/schedule.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-backup-recovery.md`

Then:

- Review environments, secrets management, CI or CD, and deployment or recovery flows as a single, end-to-end design.
- Do not summarize the environment matrix or restate how CI or CD is generally supposed to work.
- Only identify problems, contradictions, or gaps: unclear environment roles, redundant or missing secret sources, inconsistent handling of secrets between services, weak rotation or incident-response stories, unclear promotion or rollback flows between environments, or security assumptions that are unrealistic.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as clarified environment roles, standardized secret-handling patterns, explicit deployment and rollback procedures, or stronger security and compliance controls.
