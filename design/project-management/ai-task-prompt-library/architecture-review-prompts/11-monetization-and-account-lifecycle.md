# Architecture Review Prompt: Monetization and Account Lifecycle

Read the following documents:

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
- Only identify problems, contradictions, or gaps: inconsistencies between billing state and access control, unclear handling of grace periods or payment failures, missing rules for refunds or chargebacks, weak security or privacy stories around payment data and account deletion, or UX risks around surprise lockouts or entitlement changes.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as clearer state models, explicit lifecycle diagrams, better coordination between billing and authorization, or additional safeguards for payment and account transitions.
