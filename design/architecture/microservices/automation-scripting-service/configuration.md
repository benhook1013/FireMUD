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

## Implementation Notes

Current live bindings in the service are narrower than the full target-state scripting design:

- the live runtime binds live quota, dry-run quota, output-budget, pin-projection freshness, outbox retention, queue rebuild, and dead-letter size/age knobs listed below;
- signer-policy freshness, separate dead-letter alert thresholds, and any split dead-letter cleanup cadence remain target-state follow-through in the `10.3` / `10.5` scripting slices and are not current live service bindings.

## Service-Specific Variables

| Variable | Purpose | Default | Class |
| --- | --- | --- | --- |
| `SCRIPT_QUOTA_LIMIT` | Number of events a script may process per window | `50` | Stable operator knob |
| `SCRIPT_QUOTA_WINDOW_SECONDS` | Length of the quota window in seconds | `60` | Stable operator knob |
| `AUTOMATION_TICK_DURATION_MS` | Duration of a processing tick in milliseconds | `1000` | Stable operator knob |
| `AUTOMATION_TICK_MAX_EVENTS` | Max events staged from the automation queue each tick | `50` | Stable operator knob |
| `AUTOMATION_TICK_BUDGET_MS` | Soft execution budget for a script tick in milliseconds | `100` | Advanced/experimental |
| `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` | Number of days to retain script audit records before cleanup | `30` | Stable operator knob |
| `SCRIPT_EVENT_AUDIT_MAX_ROWS` | Maximum number of rows to keep in the script audit store before truncation | `1000000` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE` | Maximum dry-run/test executions allowed per tenant per minute | `60` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE_PER_PRINCIPAL` | Maximum dry-run/test executions allowed per principal per tenant per minute | `30` | Stable operator knob |
| `SCRIPT_TEST_MAX_CONCURRENCY` | Maximum concurrent dry-run/test executions per tenant or cluster | `10` | Stable operator knob |
| `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` | Maximum synthetic catch-up timer firings admitted per resume window | `200` | Stable operator knob |
| `SCRIPT_OUTPUT_MAX_COMMANDS_PER_RUN` | Maximum commands one live or dry-run execution may emit before output-budget failure | `64` | Stable operator knob |
| `SCRIPT_OUTPUT_MAX_COMMANDS_PER_ENTITY_PER_TRIGGER` | Maximum commands a single trigger may emit for one entity before output-budget failure | `8` | Stable operator knob |
| `SCRIPT_OUTPUT_MAX_SERIALIZED_WORK_ITEM_BYTES` | Maximum serialized work-item payload size before persistence or handoff rejection | `32768` | Stable operator knob |
| `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` | Maximum acceptable Automation pin/rollout projection lag before convergence reads report stale state | `5000` | Stable operator knob |
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

These knobs are the authoritative defaults referenced by the scripting architecture docs:

- publish-time validation and runtime enforcement share `SCRIPT_OUTPUT_MAX_COMMANDS_PER_RUN`, `SCRIPT_OUTPUT_MAX_COMMANDS_PER_ENTITY_PER_TRIGGER`, and `SCRIPT_OUTPUT_MAX_SERIALIZED_WORK_ITEM_BYTES` as the canonical output-budget ceilings;
- pin and rollout convergence reads use `SCRIPT_PIN_PROJECTION_STALE_THRESHOLD_MS` to set `isProjectionStale` / `projectionStale` rather than relying on hardcoded local thresholds;
- outbox cleanup and diagnosis for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` rows must follow the documented retention knobs above rather than ad hoc cleanup windows; and
- queue rebuild cadence and scan bounds for the derived `automation:queue:*` projection must follow `SCRIPT_OUTBOX_QUEUE_REBUILD_INTERVAL_SECONDS` and `SCRIPT_OUTBOX_QUEUE_REBUILD_BATCH_SIZE` rather than unbounded best-effort loops; and
- the durable evaluator cadence and claim bounds must follow `SCRIPT_OUTBOX_EXECUTION_INTERVAL_SECONDS` and `SCRIPT_OUTBOX_EXECUTION_BATCH_SIZE` rather than ad hoc polling loops.

## Proto Files

API definitions are located in [`protos/automation-scripting/v1`](../../../../protos/automation-scripting/v1). Run `./gradlew generateProto` after modifying these schemas to update the gRPC stubs.
