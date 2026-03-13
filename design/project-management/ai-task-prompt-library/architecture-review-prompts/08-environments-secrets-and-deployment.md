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
- Do not let non-blocking process refinement, future compliance enhancements, and optional platform hardening crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
