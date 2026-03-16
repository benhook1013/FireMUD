# Architecture Review Prompt: Environments and Secrets

Read the following documents. Follow references only when a listed document clearly delegates a canonical contract needed to resolve an implementation-blocking environments or secrets question. Do not recursively fan out through the whole infrastructure corpus.

- `design/architecture/infrastructure/README.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`

Then:

- Review environment structure and secrets handling as a single end-to-end design.
- Do not summarize the environment matrix or restate how secrets handling is generally supposed to work.
- Focus on gaps that would block safe first deployment, secret handling, or environment isolation.
- Do not let non-blocking process refinement, future compliance enhancements, and optional platform hardening crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
