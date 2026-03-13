# Architecture Review Prompt: Auth, Sessions, and Multi-tenancy

Read the following documents. Follow references only when a listed document clearly delegates a canonical contract needed to resolve an implementation-blocking contradiction or missing security rule. Do not recursively expand through all related auth docs.

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/system-architecture-frontend.md` (if it covers auth or session flows)
- `design/architecture/microservices/account-service/README.md`
- `design/architecture/microservices/account-service/stripe-integration.md`
- `design/architecture/microservices/account-service/subscription-management.md`
- `design/architecture/microservices/game-session-service/README.md`

Then:

- Review authentication, session management, and multi-tenancy as a single, end-to-end design (identity, authentication and authorization, session lifecycle, tenant scoping, and subscription or entitlement checks).
- Do not summarize behavior or highlight what is already good.
- Focus on issues that would create ambiguous trust boundaries, incompatible identity/session handling, or unsafe tenant isolation in the first implementation.
- Do not let non-blocking UX refinements and future policy sophistication crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve clarity, safety, or maintainability.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
