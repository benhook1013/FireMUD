# Account Service Operations

This document collects the Account Service operational behavior, readiness model, observability, saga participation, and integration-test guidance.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- `liveness` is process-local only.
- `readiness` is truthful local readiness for the currently implemented authentication/account slice and must fail when the service cannot safely satisfy new authentication traffic with its required local persistence/session infrastructure.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Saga Participation

The Account audit handoff is a mandatory owner-local durable-outbox target, not a compensated Saga step. Global registration creates only global account/security state and emits a platform-scoped audit with no `tenantId` from that transaction; tenant-scoped profile and membership operations are separate and use the exact committed `tenantId`. Explicit `JoinPublicProductionMembership` emits its separate tenant-scoped audit from the membership transaction. Global `account_security_lock`, `platform_access_ban`, recovery, and suspicious-activity audits likewise use platform scope and omit `tenantId`. Producer consequences are defined in [Account Runtime and Data](./runtime-and-data.md#architecture-and-runtime-notes), while the normative ingress, envelope, receipt, readback, retention, and authorization contract is owned by [Logging & Admin API Contracts](../logging-admin-service/api-contracts.md#account-audit-ingress-and-receipt). **Current runtime:** Account persists the account, profile, and membership locally, then attempts the `CreateLogEvent` RPC post-commit on a best-effort basis. Logging failure is swallowed and does not roll back or compensate the committed creation. The owner-local outbox and corresponding proto/receiver implementation must be implemented and proved before mandatory audit delivery is claimed. No Account workflow is currently authorized as a `common-saga` adopter in this document or tracker; existing `accountCreation` and `purchase` `SagaRunner` calls are unclassified implementation drift. Any future Account Saga must first satisfy the explicit owner, boundary, negative-case, and focused-proof classification in [Transaction Strategies](../../system-architecture-transactions.md#mandatory-workflow-adopter-classification).

The current `PurchaseWorkflowService` implementation for one-time payments and donations is non-exposed, unsupported provider-mutating drift, not a V1 product path or entitlement authority. It currently enters the shared runner to create a payment intent, record the transaction with the Logging & Admin Service, and refund through compensation if the log step fails. The target Account containment boundary requires every generic purchase or donation entry point to reject before entering that runner or making any Stripe call; no supported V1 flow may invoke it. Current V1 remains the hosting-billing boundary in [ADR 0143](../../decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md), while the future marketplace direction in [ADR 0179](../../decisions/adr-0179-firemud-managed-creator-commerce-boundary.md) remains deferred and must be implemented and proved separately.

## Metrics and Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods expose `account.*`, `payment.*`, `notification.*`, and `session.*` timers via `@Timed` annotations. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger. No additional configuration is required when running via `./gradlew bootRun` as the default properties target `http://otel-collector:4317`.

## Integration Test Notes

The old disabled GHCR-based cross-service placeholder for Account Service was removed because it did not prove a meaningful current contract. The maintained local application smoke now lives under `src/test/java/integration` and should be treated as the canonical lightweight readiness check for this service. Higher-value cross-service behavior should be covered by targeted current-contract tests rather than by "other container exists" scaffolding.

See [System Architecture Testing](../../system-architecture-testing.md) for the shared testing approach.
