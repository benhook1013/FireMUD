# Account Service Operations

This document collects the Account Service operational behavior, readiness model, observability, saga participation, and integration-test guidance.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- `liveness` is process-local only.
- `readiness` is truthful local readiness for the currently implemented authentication/account slice and must fail when the service cannot safely satisfy new authentication traffic with its required local persistence/session infrastructure.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Saga Participation

Account creation uses the shared `SagaBuilder` from `firemud-common` to persist the account record, create the profile, and log creation in the Logging & Admin Service. If any step fails, compensation actions roll back the database writes so the workflow remains consistent across services. See [Transaction Strategies](../../system-architecture-transactions.md) for details on the saga pattern.

Purchase workflows (one-time payments or donations) reuse this same runner. The `PurchaseWorkflowService` creates the payment intent and then records the transaction with the Logging & Admin Service. Should the log step fail, the saga automatically refunds the Stripe payment via a compensation action.

## Metrics and Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods expose `account.*`, `payment.*`, `notification.*`, and `session.*` timers via `@Timed` annotations. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger. No additional configuration is required when running via `./gradlew bootRun` as the default properties target `http://otel-collector:4317`.

## Cross-Service Integration Test

An integration test under `src/test/java/crossservice` starts this service alongside the Logging & Admin Service using Testcontainers. Execute it once dependent images are available:

```bash
./gradlew :account-service:test --tests "*CrossServiceIntegrationTest"
```

See [System Architecture Testing](../../system-architecture-testing.md) for more details.
