# Architecture Review Prompt: Monetization and Account Lifecycle

Read the following documents. Follow references only when a listed document clearly delegates a canonical contract needed to resolve a contradiction or implementation-blocking account/billing rule. Do not recursively traverse unrelated docs.

- `design/architecture/system-architecture-frontend.md` (if it covers authentication, payments, or account flows)
- `design/architecture/microservices/account-service/README.md`
- `design/architecture/microservices/account-service/stripe-integration.md`
- `design/architecture/microservices/account-service/subscription-management.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/project-management/core-requirements.md`
- `design/project-management/core-requirements-summary.md`

Then:

- Review monetization, billing, and account lifecycle as a unified design: onboarding, subscription purchase and renewal, entitlement management, downgrade or cancellation, and account deactivation or deletion.
- Do not summarize the happy-path billing flows or restate API details that are already clear.
- Focus on issues that would block correct implementation of billing, entitlement enforcement, or account lifecycle transitions.
- Ignore non-blocking policy refinement and future business-model ideas unless they materially affect the first implementation path.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and optionally list up to 3 deferred follow-ups.
- Stop once only non-blocking refinement remains.
