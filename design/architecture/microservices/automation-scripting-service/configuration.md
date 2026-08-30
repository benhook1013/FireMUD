# Automation & Scripting Service Configuration

This document summarizes the Automation & Scripting Service configuration contract, supported environment variables, and proto source location.

## Core Configuration

This service follows the shared configuration scheme in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md). It requires:

- [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
- [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
- gRPC TLS certificates via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates)
- peer service discovery via variables prefixed `FIREMUD_SERVICES_`
- optional OpenTelemetry collector override via `OTEL_ENDPOINT`

For day-to-day operations, environment variables fall into three broad categories:

- Stable operator knobs – part of the supported operational surface and expected to remain compatible across minor releases.
- Advanced or experimental – powerful tuning knobs that should be changed only with guidance from maintainers.
- Internal implementation details – not intended for direct use and may change or be removed without notice.

## Target Configuration Semantics

- The output knobs below are runtime-cap inputs only. Publication resolves the cap values and pins the normalized cap payload together with its versioned `artifactRuntimeCapDigest`; publish-time analysis and runtime validation/enforcement use that artifact-pinned pair, owned by [Scripting Runtime Execution](../../system-architecture-scripting-runtime-execution.md#static-output-cost-contract), and never substitute newer local cap values. A later local/configuration reduction requires compatibility preflight or rejection for already-`READY`/pinned artifacts rather than silently changing their contract.
- Scheduler admission uses the immutable artifact-pinned estimated millisecond cost and defers the remainder after the admitted ordered prefix; actual runtime is calibration telemetry only.

**Target state:** script-transition configuration is local policy, not pin authority. In particular, `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` triggers a bounded authoritative Game Session refresh when projection evidence is absent or stale at any applicable instance-scoped admission or fence boundary; that boundary rejects with `pin_state_unavailable` when the refresh fails, and the threshold must never be widened into a degraded stale-pin override.

**Target-state retention and recovery:** [ADR 0107](../../decisions/adr-0107-stage-aware-script-dead-letter-recovery.md) requires that a row advertised as recoverable and all of its stage, manifest, evaluated-output, and child-dispatch evidence form one coherent retention bundle. Age/capacity cleanup or explicit purge may remove the whole bundle when eligible, but must not delete supporting evidence independently while leaving the row advertised recoverable; configuration validation covers settings only, while runtime cleanup and explicit purge fail closed on incoherent retained evidence. The bounded `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS` and `SCRIPT_DEAD_LETTER_MAX_ROWS` controls select cleanup candidates rather than guaranteeing deletion of an ineligible bundle or authorizing partial evidence removal; there is no additional numeric floor. [ADR 0110](../../decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md) keeps timer continuity declarations in authored artifact data rather than a service-wide configuration switch: `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` applies only to interval schedules, while one-shot recovery uses its durable intent/outcome semantics.

These knobs are the authoritative defaults referenced by the scripting architecture docs:

- publish-time validation and runtime enforcement share `SCRIPT_OUTPUT_MAX_COMMANDS_PER_RUN`, `SCRIPT_OUTPUT_MAX_COMMANDS_PER_ENTITY_PER_TRIGGER`, and `SCRIPT_OUTPUT_MAX_SERIALIZED_WORK_ITEM_BYTES` as the runtime-cap ceilings, while the artifact-pinned cost metadata/digests determine whether a component graph may publish or run;
- the durable quota owner records one full-Trigger-Identity handler admission charge and a separate execution-start marker; `SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOW_SECONDS` and the live tenant-tier settings are inputs to those decisions. Queued work holds no capacity; execution uses a separately fenced/reclaimable lease, and `PUBLISH_READINESS` `onLoad` work is isolated;
- live `PUBLISH_READINESS` `onLoad` work uses dedicated `SCRIPT_READINESS_MAX_CONCURRENCY` and `SCRIPT_READINESS_MAX_CLUSTER_CONCURRENCY` capacity instead of an unbounded live-budget bypass, and exhausted readiness work is canceled as `onload_budget_exceeded`;
- dry-run/test traffic uses `SCRIPT_TEST_MAX_RUNS_PER_MINUTE`, `SCRIPT_TEST_MAX_RUNS_PER_MINUTE_PER_PRINCIPAL`, `SCRIPT_TEST_MAX_CONCURRENCY`, and `SCRIPT_TEST_MAX_CLUSTER_CONCURRENCY` rather than consuming live per-script quota or tenant runtime budget;
- pin and rollout convergence reads use `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` to derive the public `isProjectionStale` wire field; internal Java projection summaries use `projectionStale` and map it to that public field rather than exposing a second public field or relying on hardcoded local thresholds;
- enabled plugin runtime states are rechecked against current publication, signer-revocation, and component-policy metadata using `SCRIPT_PLUGIN_POLICY_RECONCILE_INTERVAL_SECONDS` and `SCRIPT_PLUGIN_POLICY_RECONCILE_BATCH_SIZE`; plugin-trigger ingress fails closed when the last successful policy check is older than `SCRIPT_PLUGIN_POLICY_STALE_THRESHOLD_SECONDS`;
- outbox cleanup and diagnosis for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` rows must follow the documented retention knobs above rather than ad hoc cleanup windows; and
- queue rebuild cadence and scan bounds for the derived `automation:queue:*` projection must follow `SCRIPT_OUTBOX_QUEUE_REBUILD_INTERVAL_SECONDS` and `SCRIPT_OUTBOX_QUEUE_REBUILD_BATCH_SIZE` rather than unbounded best-effort loops; and
- the durable evaluator cadence and claim bounds must follow `SCRIPT_OUTBOX_EXECUTION_INTERVAL_SECONDS` and `SCRIPT_OUTBOX_EXECUTION_BATCH_SIZE` rather than ad hoc polling loops.

## Implementation Status

The formation REST family is currently exposed under the service's HTTP auth configuration without a route-specific tenant binding; with the source OpenAPI's `security: []` and no controller guard, it is publicly reachable wherever the service is exposed. Configuration must not be treated as a substitute for domain authorization: the target removes public exposure and requires exact internal/admin route policy plus caller-to-tenant and Entity-owned NPC ownership checks, with denial proof for unauthenticated and cross-tenant formation operations.

Current live bindings in the service are narrower than the full target-state scripting design:

- the live runtime binds live per-script quota, priority-tagged live tenant-budget, dry-run quota/capacity, output-budget, pin-projection freshness, signer/component-policy reconciliation cadence, outbox retention, queue rebuild, and dead-letter size/age knobs listed below;
- the current runtime does not implement artifact-estimate ordered-prefix reservation;
- live cleanup expires `HANDED_OFF` and `CANCELED` rows by status-specific retention and `DEAD_LETTERED` rows by max age plus a row-count cap; stage-aware recovery and a coherent retained-evidence bundle remain unimplemented;
- live instance-bound admission reads Game Session state and compares `scriptPatchVersion`, but the current projection/wire omits `scriptPinEpoch` and therefore does not prove exact-epoch admission;
- current live properties enforce only a positive minimum for `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS`; target upper-bound validation and focused proof remain an implementation gap.

## Service-Specific Variables

| Variable | Purpose | Default | Class |
| --- | --- | --- | --- |
| `SCRIPT_QUOTA_LIMIT` | Number of events a script may process per window | `50` | Stable operator knob |
| `SCRIPT_QUOTA_WINDOW_SECONDS` | Length of the quota window in seconds | `60` | Stable operator knob |
| `SCRIPT_TENANT_BUDGET_HIGH_RUNS_PER_MINUTE` | Live execution reservations allowed per tenant per minute for high-priority automation work | `120` | Stable operator knob |
| `SCRIPT_TENANT_BUDGET_NORMAL_RUNS_PER_MINUTE` | Live execution reservations allowed per tenant per minute for normal-priority automation work | `60` | Stable operator knob |
| `SCRIPT_TENANT_BUDGET_BACKGROUND_RUNS_PER_MINUTE` | Live execution reservations allowed per tenant per minute for background automation work | `30` | Stable operator knob |
| `AUTOMATION_TICK_DURATION_MS` | Duration of a processing tick in milliseconds | `1000` | Stable operator knob |
| `AUTOMATION_TICK_MAX_EVENTS` | Max events staged from the automation queue each tick | `50` | Stable operator knob |
| `AUTOMATION_TICK_BUDGET_MS` | Target-state cumulative estimated-millisecond reservation budget for deterministic ordered-prefix admission in one automation tick; current runtime does not implement this admission | `100` | Advanced/experimental |
| `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` | Number of days to retain script audit records before cleanup | `30` | Stable operator knob |
| `SCRIPT_EVENT_AUDIT_MAX_ROWS` | Maximum number of rows to keep in the script audit store before truncation | `1000000` | Stable operator knob |
| `SCRIPT_READINESS_MAX_CONCURRENCY` | Maximum concurrent live publish-readiness (`PUBLISH_READINESS`) executions per tenant | `4` | Stable operator knob |
| `SCRIPT_READINESS_MAX_CLUSTER_CONCURRENCY` | Maximum concurrent live publish-readiness (`PUBLISH_READINESS`) executions across the Automation & Scripting cluster | `20` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE` | Maximum dry-run/test executions allowed per tenant per minute | `60` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE_PER_PRINCIPAL` | Maximum dry-run/test executions allowed per principal per tenant per minute | `30` | Stable operator knob |
| `SCRIPT_TEST_MAX_CONCURRENCY` | Maximum concurrent dry-run/test executions per tenant | `10` | Stable operator knob |
| `SCRIPT_TEST_MAX_CLUSTER_CONCURRENCY` | Maximum concurrent dry-run/test executions across the Automation & Scripting cluster | `50` | Stable operator knob |
| `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` | Maximum synthetic catch-up timer firings admitted per resume window | `200` | Stable operator knob |
| `SCRIPT_OUTPUT_MAX_COMMANDS_PER_RUN` | Maximum commands one live or dry-run execution may emit before output-budget failure | `64` | Stable operator knob |
| `SCRIPT_OUTPUT_MAX_COMMANDS_PER_ENTITY_PER_TRIGGER` | Maximum commands a single trigger may emit for one entity before output-budget failure | `8` | Stable operator knob |
| `SCRIPT_OUTPUT_MAX_SERIALIZED_WORK_ITEM_BYTES` | Maximum serialized work-item payload size before persistence or handoff rejection | `32768` | Stable operator knob |
| `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` | Maximum acceptable Automation pin/rollout projection lag before convergence reads report stale state; target validation range `1..30_000 ms`, with `30_000 ms` tied to the canonical P99 projection freshness SLO | `5000` | Stable operator knob |
| `SCRIPT_PLUGIN_POLICY_RECONCILE_INTERVAL_SECONDS` | Scheduled cadence for rechecking enabled plugin versions against current Game Design signer/component-policy publication metadata | `60` | Stable operator knob |
| `SCRIPT_PLUGIN_POLICY_RECONCILE_BATCH_SIZE` | Maximum enabled plugin runtime states inspected per plugin-policy reconciliation sweep | `100` | Stable operator knob |
| `SCRIPT_PLUGIN_POLICY_STALE_THRESHOLD_SECONDS` | Maximum age of the last successful enabled-plugin signer/component-policy check before plugin triggers fail closed | `300` | Stable operator knob |
| `SCRIPT_OUTBOX_HANDED_OFF_RETENTION_DAYS` | Retention window for successfully handed-off outbox rows needed for rollback and replay diagnosis | `7` | Stable operator knob |
| `SCRIPT_OUTBOX_CANCELED_RETENTION_DAYS` | Retention window for canceled outbox rows needed for rollback and drain diagnosis | `7` | Stable operator knob |
| `SCRIPT_OUTBOX_TERMINAL_CLEANUP_INTERVAL_SECONDS` | Cleanup sweep interval for terminal outbox rows (`HANDED_OFF`, `CANCELED`, `DEAD_LETTERED`) | `300` | Stable operator knob |
| `SCRIPT_OUTBOX_QUEUE_REBUILD_INTERVAL_SECONDS` | Scheduled interval for bounded rebuild of missing `automation:queue:*` pointer entries from durable pending work items | `60` | Stable operator knob |
| `SCRIPT_OUTBOX_QUEUE_REBUILD_BATCH_SIZE` | Maximum durable pending work items inspected per queue-rebuild sweep | `200` | Stable operator knob |
| `SCRIPT_OUTBOX_EXECUTION_INTERVAL_SECONDS` | Scheduled interval for the durable work-item execution loop that claims and evaluates pending work | `5` | Stable operator knob |
| `SCRIPT_OUTBOX_EXECUTION_BATCH_SIZE` | Maximum claimed work items processed per execution sweep | `50` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_MAX_ROWS` | Maximum dead-lettered automation work items retained before cleanup | `100000` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS` | Maximum age for dead-lettered work items | `604800` | Stable operator knob |

Any additional, less common tuning variables should be documented alongside their introduction and clearly marked as advanced or internal. Operational runbooks should treat only stable operator knobs as supported surface for routine adjustments.

## Proto Files

API definitions are located in [`protos/automation-scripting/v1`](../../../../protos/automation-scripting/v1). Run `./gradlew generateProto` after modifying these schemas to update the gRPC stubs.
