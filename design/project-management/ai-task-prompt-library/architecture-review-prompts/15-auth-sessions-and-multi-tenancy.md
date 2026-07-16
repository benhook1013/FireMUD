# Architecture Review Prompt: Auth, Sessions, and Multi-tenancy

Read the following documents. Follow references and read nearby related files as required when a listed document clearly delegates a canonical contract or when a closely related file is needed to resolve an implementation-blocking contradiction or missing security rule. Do not recursively expand through all related auth docs.

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-authz-route-matrix.md`
- `design/architecture/system-architecture-gateway.md` (for `/ws/game/**` connect-token and connect-context boundaries)
- `design/architecture/system-architecture-multi-tenancy.md`
- `design/architecture/system-architecture-versioning-runtime.md`
- `design/architecture/system-architecture-frontend.md` (if it covers auth or session flows)
- `design/architecture/microservices/account-service/README.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/architecture/microservices/account-service/stripe-integration.md`
- `design/architecture/microservices/account-service/subscription-management.md`
- `design/architecture/microservices/game-session-service/README.md`
- `design/architecture/microservices/game-session-service/protocols.md`
- `design/project-management/implementation-tracking/player-access-and-session.md` (for current first-party `/ws/game/**` implementation status)
- `design/project-management/implementation-tracking/realm-routing-and-playable-state.md` (for current first-join membership status)
- `design/project-management/implementation-tracking/realm-routing-and-playable-state.md` (for current bootstrap/connect-token status)

Then:

- Review authentication, session management, and multi-tenancy as a single, end-to-end design (identity, authentication and authorization, session lifecycle, tenant scoping, and subscription or entitlement checks).
- Do not summarize behavior or highlight what is already good.
- Focus on issues that would create ambiguous trust boundaries, incompatible identity/session handling, or unsafe tenant isolation in the first implementation.
- Before classifying a finding as an open blocker, cross-check the relevant domain tracker, proto/API contract, and current implementation status when the listed docs indicate that the behavior has already landed or is in progress. If code or tracker notes already resolve the seam but a higher-level doc is stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not let non-blocking UX refinements and future policy sophistication crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve clarity, safety, or maintainability.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
