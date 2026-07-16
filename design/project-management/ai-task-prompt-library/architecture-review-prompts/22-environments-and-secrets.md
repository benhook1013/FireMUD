# Architecture Review Prompt: Environments and Secrets

Read the following documents. Follow references and read nearby related files as required when a listed document clearly delegates a canonical contract or when a closely related file is needed to resolve an implementation-blocking environments or secrets question. Do not recursively fan out through the whole infrastructure corpus.

- `design/architecture/infrastructure/README.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`

Then:

- Review environment structure and secrets handling as a single end-to-end design.
- Check each finding against the relevant domain tracker and implementation, in case the issue has already been resolved in code or tracking and the design now needs to import that decision back into the docs.
- Do not summarize the environment matrix or restate how secrets handling is generally supposed to work.
- Focus on gaps that would block safe first deployment, secret handling, or environment isolation.
- If trackers, protos, or current implementation already resolve the seam but the design docs are stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not let non-blocking process refinement, future compliance enhancements, and optional platform hardening crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
