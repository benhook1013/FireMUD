# Architecture Review Prompt: Auth, Sessions, and Multi-tenancy

Read the following documents:

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
- Only identify problems, contradictions, or gaps: unclear trust boundaries between services; inconsistent definitions of “user”, “account”, “session”, or “tenant”; missing rules for cross-tenant isolation; ambiguous session invalidation or expiry; under-specified interaction between billing, subscriptions, and access control; or flows that create security, data-leak, or user-experience risks.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as clearer role or identity models, more precise session contracts, explicit tenant-isolation rules, or better coordination between account and session services.
