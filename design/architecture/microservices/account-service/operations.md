# Account Service Operations

This document collects the Account Service operational behavior, readiness model, observability, saga participation, and integration-test guidance.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- `liveness` is process-local only.
- `readiness` is truthful local readiness for the currently implemented authentication/account slice and must fail when the service cannot safely satisfy new authentication traffic with its required local persistence/session infrastructure.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Saga Participation

Account creation uses the shared `SagaBuilder` from `firemud-common` to persist the account record, create the profile, and log creation in the Logging & Admin Service. If any step fails, compensation actions roll back the database writes so the workflow remains consistent across services. See [Transaction Strategies](../../system-architecture-transactions.md) for details on the saga pattern.

The current `PurchaseWorkflowService` implementation for one-time payments and donations is non-exposed, unsupported provider-mutating drift, not a V1 product path or entitlement authority. It currently enters the shared runner to create a payment intent, record the transaction with the Logging & Admin Service, and refund through compensation if the log step fails. The target Account containment boundary requires every generic purchase or donation entry point to reject before entering that runner or making any Stripe call; no supported V1 flow may invoke it. Current V1 remains the hosting-billing boundary in [ADR 0143](../../decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md), while the future marketplace direction in [ADR 0179](../../decisions/adr-0179-firemud-managed-creator-commerce-boundary.md) remains deferred and must be implemented and proved separately.

## Metrics and Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods expose `account.*`, `payment.*`, `notification.*`, and `session.*` timers via `@Timed` annotations. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger. No additional configuration is required when running via `./gradlew bootRun` as the default properties target `http://otel-collector:4317`.

## Integration Test Notes

The old disabled GHCR-based cross-service placeholder for Account Service was removed because it did not prove a meaningful current contract. The maintained local application smoke now lives under `src/test/java/integration` and should be treated as the canonical lightweight readiness check for this service. Higher-value cross-service behavior should be covered by targeted current-contract tests rather than by "other container exists" scaffolding.

See [System Architecture Testing](../../system-architecture-testing.md) for the shared testing approach.
