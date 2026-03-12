# Architecture Review Prompt: Environments, Secrets, and Deployment

Read the following documents. Follow references only when a listed document clearly delegates a canonical contract needed to resolve an implementation-blocking deployment, secrets, or recovery question. Do not recursively fan out through the whole infrastructure corpus.

- `design/architecture/infrastructure/README.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- `design/architecture/infrastructure/schedule.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-backup-recovery.md`

Then:

- Review environments, secrets management, CI or CD, and deployment or recovery flows as a single, end-to-end design.
- Do not summarize the environment matrix or restate how CI or CD is generally supposed to work.
- Focus on gaps that would block safe first deployment, secret handling, promotion, rollback, or incident recovery.
- Ignore non-blocking process refinement, future compliance enhancements, and optional platform hardening unless they materially affect the current implementation path.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and optionally list up to 3 deferred follow-ups.
- Stop once only non-blocking refinement remains.
