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

## Service-Specific Variables

| Variable | Purpose | Default | Class |
| --- | --- | --- | --- |
| `SCRIPT_QUOTA_LIMIT` | Number of events a script may process per window | `50` | Stable operator knob |
| `SCRIPT_QUOTA_WINDOWSECONDS` | Length of the quota window in seconds | `60` | Stable operator knob |
| `AUTOMATION_TICK_DURATION_MS` | Duration of a processing tick in milliseconds | `1000` | Stable operator knob |
| `AUTOMATION_TICK_MAX_EVENTS` | Max events staged from the automation queue each tick | `50` | Stable operator knob |
| `AUTOMATION_TICK_BUDGET_MS` | Soft execution budget for a script tick in milliseconds | `100` | Advanced/experimental |
| `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` | Number of days to retain script audit records before cleanup | `30` | Stable operator knob |
| `SCRIPT_EVENT_AUDIT_MAX_ROWS` | Maximum number of rows to keep in the script audit store before truncation | `1000000` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE` | Maximum dry-run/test executions allowed per tenant per minute | `60` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE_PER_PRINCIPAL` | Maximum dry-run/test executions allowed per principal per tenant per minute | `30` | Stable operator knob |
| `SCRIPT_TEST_MAX_CONCURRENCY` | Maximum concurrent dry-run/test executions per tenant or cluster | `10` | Stable operator knob |
| `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` | Maximum synthetic catch-up timer firings admitted per resume window | `200` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_MAX_ROWS` | Maximum dead-lettered automation work items retained before cleanup | `100000` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS` | Maximum age for dead-lettered work items | `604800` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_CLEANUP_INTERVAL_SECONDS` | Cleanup sweep interval for dead-lettered work items | `300` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_ALERT_THRESHOLD_ROWS` | Alert threshold for dead-letter store growth | `80000` | Stable operator knob |

Any additional, less common tuning variables should be documented alongside their introduction and clearly marked as advanced or internal. Operational runbooks should treat only stable operator knobs as supported surface for routine adjustments.

## Proto Files

API definitions are located in [`protos/automation-scripting/v1`](../../../../protos/automation-scripting/v1). Run `./gradlew generateProto` after modifying these schemas to update the gRPC stubs.
