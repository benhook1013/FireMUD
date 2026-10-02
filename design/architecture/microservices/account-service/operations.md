# Account Service Operations

This document collects the Account Service operational behavior, readiness model, observability, saga participation, and integration-test guidance.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- `liveness` is process-local only.
- `readiness` is truthful local readiness for the currently implemented authentication/account slice and must fail when the service cannot safely satisfy new authentication traffic with its required local persistence/session infrastructure.
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.
- Expired connect-scope cleanup requires PostgreSQL 16 or later because its deletion predicate uses `pg_input_is_valid`; local Docker Compose and Account integration-test containers use PostgreSQL 16. Keep the guarded delete predicate and do not add a pre-16 compatibility query.

### V26 membership migration preflight

Before applying [V26](../../../../services/account-service/src/main/resources/db/migration/V26__global_registration_join_evidence.sql) to a populated target, take an authoritative read-only inventory of `account_tenant_membership` and its account/tenant identities. The migration retains each original `gameplay_admission_allowed` value in `account_legacy_membership_sources.original_gameplay_admission_allowed` as provenance; it is not current player intent or admission authority. V26 quarantines legacy memberships as `LEGACY_UNVERIFIED` and disables admission. Do not restore access from the retained boolean; membership authority and explicit reconciliation remain owned by [Account Runtime and Data](./runtime-and-data.md#membership-and-entitlement-authority).

Quiesce Account mutations and block new admission throughout inventory, migration, and readback so the preflight cannot race with another writer. If that quiescence cannot be proved, do not apply V26. Run these read-only queries before V26 and retain their output with the deployment record:

```sql
SELECT COUNT(*) AS membership_rows,
       COUNT(*) FILTER (WHERE gameplay_admission_allowed) AS legacy_admitting_rows
FROM account_tenant_membership;

SELECT m.id AS membership_id,
       m.account_id,
       m.tenant_id,
       a.tenant_id AS account_legacy_tenant_id,
       m.gameplay_admission_allowed AS original_gameplay_admission_allowed
FROM account_tenant_membership AS m
LEFT JOIN accounts AS a ON a.id = m.account_id
ORDER BY m.tenant_id, m.account_id, m.id;
```

Treat rows as run-owned demo data only when an independent deployment/run record proves that ownership; do not infer it from identifiers, account names, or the legacy boolean. If any real memberships exist, keep deployment and gameplay traffic closed pending explicit owner-validated reconciliation. [TestDataSeeder](../../../../services/account-service/src/main/java/net/firedevops/firemud/accountservice/data/TestDataSeeder.java) is for explicitly run-owned local smoke data and must not be enabled in production. The current stage has no live target inventory or production reconciliation proof; this preflight is not activation approval.

After V26 and before any explicitly enabled non-production demo seed, use read-only readback to verify each retained source has its quarantined membership row:

```sql
SELECT COUNT(membership.id) AS membership_rows,
       (SELECT COUNT(*) FROM account_legacy_membership_sources) AS source_rows,
       COUNT(*) FILTER (WHERE legacy_source.membership_id IS NULL) AS rows_without_source,
       COUNT(*) FILTER (
           WHERE membership.lifecycle_state <> 'LEGACY_UNVERIFIED'
              OR membership.authority_provenance <> 'LEGACY_UNVERIFIED'
              OR membership.gameplay_admission_allowed
       ) AS rows_not_quarantined
FROM account_tenant_membership AS membership
LEFT JOIN account_legacy_membership_sources AS legacy_source
    ON legacy_source.membership_id = membership.id;

SELECT legacy_source.membership_id,
       legacy_source.account_id,
       legacy_source.tenant_id,
       legacy_source.original_gameplay_admission_allowed,
       legacy_source.matches_account_legacy_tenant,
       legacy_source.disposition,
       legacy_source.captured_at,
       membership.lifecycle_state,
       membership.authority_provenance,
       membership.gameplay_admission_allowed
FROM account_legacy_membership_sources AS legacy_source
JOIN account_tenant_membership AS membership
    ON membership.id = legacy_source.membership_id
ORDER BY legacy_source.tenant_id, legacy_source.account_id, legacy_source.membership_id;
```

## Saga Participation

The Account audit handoff is a mandatory owner-local durable-outbox target, not a compensated Saga step. Global registration creates only global account/security state and emits a platform-scoped audit with no `tenantId` from that transaction; tenant-scoped profile and membership operations are separate and use the exact committed `tenantId`. Explicit `JoinPublicProductionMembership` emits its separate tenant-scoped audit from the membership transaction. The target contract also requires global `account_security_lock`, `platform_access_ban`, recovery, and suspicious-activity audits to use platform scope and omit `tenantId`; those producers are not yet converged to the current outbox path. Producer consequences are defined in [Account Runtime and Data](./runtime-and-data.md#architecture-and-runtime-notes), while the normative ingress, envelope, receipt, readback, retention, and authorization contract is owned by [Logging & Admin API Contracts](../logging-admin-service/api-contracts.md#account-audit-ingress-and-receipt). **Current local implementation:** registration and successful explicit JOIN append immutable audit envelopes to Account's SQL outbox in the same local transaction as their owner-state writes. Account's delivery job sends the unchanged envelope and marks it complete only after matching terminal receipt evidence; unavailable, `UNIMPLEMENTED`, timeout, mismatched, or ambiguous responses leave the same event `PENDING` for retry. The Logging & Admin receipt receiver, durable receipt persistence/deduplication, exact payload-digest validation, and readback are separate #2848 work and are not implemented in this Account core. This local producer status does not claim PostgreSQL runtime proof or direct text-client end-to-end proof. Durable recovery/reconciliation of a JOIN operation left `PENDING` after an unknown database commit outcome remains open under the Overseer decision and ADR 0025, with recovery ownership assigned to #2848. These audit paths are not Account Saga steps. No Account workflow is currently authorized as a `common-saga` adopter in this document or tracker; existing `accountCreation` and `purchase` `SagaRunner` calls are unclassified implementation drift. Any future Account Saga must first satisfy the explicit owner, boundary, negative-case, and focused-proof classification in [Transaction Strategies](../../system-architecture-transactions.md#mandatory-workflow-adopter-classification).

The current `PurchaseWorkflowService` implementation for one-time payments and donations is non-exposed, unsupported provider-mutating drift, not a V1 product path or entitlement authority. It currently enters the shared runner to create a payment intent, record the transaction with the Logging & Admin Service, and refund through compensation if the log step fails. The target Account containment boundary requires every generic purchase or donation entry point to reject before entering that runner or making any Stripe call; no supported V1 flow may invoke it. Current V1 remains the hosting-billing boundary in [ADR 0143](../../decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md), while the future marketplace direction in [ADR 0179](../../decisions/adr-0179-firemud-managed-creator-commerce-boundary.md) remains deferred and must be implemented and proved separately.

## Metrics and Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods expose `account.*`, `payment.*`, `notification.*`, and `session.*` timers via `@Timed` annotations. OpenTelemetry spans are exported to the collector service so traces can be viewed in Jaeger. No additional configuration is required when running via `./gradlew bootRun` as the default properties target `http://otel-collector:4317`.

Expired connect-scope cleanup exposes `account.connect_scopes.cleanup.deleted` for deleted rows, `account.connect_scopes.cleanup.failure` for failed runs, the `account.connect_scopes.cleanup` timer, and `account.connect_scopes.cleanup.cap_saturation` for runs in which all five batches return the configured full batch size. The job can process at most five times the batch size per scheduled invocation. With fixed delay, the effective start-to-start period is the configured interval plus the prior run's execution time, so long runs reduce how often cleanup can run. Tune the batch size and interval against measured expired, unreferenced scope insertion load. Cap saturation signals cleanup pressure, not an exact backlog count.

## Integration Test Notes

The old disabled GHCR-based cross-service placeholder for Account Service was removed because it did not prove a meaningful current contract. The maintained local application smoke now lives under `src/test/java/integration` and should be treated as the canonical lightweight readiness check for this service. Higher-value cross-service behavior should be covered by targeted current-contract tests rather than by "other container exists" scaffolding.

See [System Architecture Testing](../../system-architecture-testing.md) for the shared testing approach.
