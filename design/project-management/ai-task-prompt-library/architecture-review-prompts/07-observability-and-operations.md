# Architecture Review Prompt: Observability and Operations

Read the following documents:

- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-tracing.md`
- `design/architecture/system-architecture-testing.md`
- `design/architecture/system-architecture-runbooks.md`
- `design/architecture/system-architecture-backup-recovery.md`
- `design/architecture/system-architecture-scaling-runbook.md`
- `design/architecture/system-architecture-redis-operations.md`
- `design/architecture/system-architecture-redis-incident-runbook.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`
- `design/observability/README.md`
- `design/observability/grafana/README.md`
- `design/observability/kibana/README.md`

Then:

- Review observability and operations as a unified design: metrics, logs, traces, dashboards, alerts, and runbooks across the platform.
- Do not summarize what already works well or restate basic descriptions of dashboards or metrics.
- Only identify problems, contradictions, or gaps: missing signals for critical flows, unclear ownership of alerts, runbooks that do not map cleanly to the described failure modes, inconsistent use of tracing or logging across services, or operational scenarios that are not covered.
- For each issue, reference the specific document or documents involved and propose concrete, actionable improvements, such as stronger SLOs or SLIs, additional metrics or spans, clearer alert routing, or more complete, step-by-step runbooks.
