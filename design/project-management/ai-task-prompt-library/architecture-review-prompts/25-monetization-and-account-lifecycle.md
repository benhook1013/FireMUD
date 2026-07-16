# Architecture Review Prompt: Monetization and Account Lifecycle

Read the following documents. Follow references and read nearby related files as required when a listed document clearly delegates a canonical contract or when a closely related file is needed to resolve a contradiction or implementation-blocking account or billing rule. Do not recursively traverse unrelated docs.

- `design/architecture/system-architecture-frontend.md` (if it covers authentication, payments, or account flows)
- `design/architecture/microservices/account-service/README.md`
- `design/architecture/microservices/account-service/stripe-integration.md`
- `design/architecture/microservices/account-service/subscription-management.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/project-management/core-requirements.md`

Then:

- Review monetization, billing, and account lifecycle as a unified design: onboarding, subscription purchase and renewal, entitlement management, downgrade or cancellation, and account deactivation or deletion.
- Check each finding against the relevant domain tracker and implementation, in case the issue has already been resolved in code or tracking and the design now needs to import that decision back into the docs.
- Do not summarize the happy-path billing flows or restate API details that are already clear.
- Focus on issues that would block correct implementation of billing, entitlement enforcement, or account lifecycle transitions.
- If trackers, protos, or current implementation already resolve the seam but the design docs are stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not let non-blocking policy refinement and future business-model ideas crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve the design or future implementation safety.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
