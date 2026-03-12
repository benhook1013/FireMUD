# Architecture Review Prompt: Observability and Operations

Read the following documents. Follow references only when a listed document points to another canonical source needed to judge an implementation-blocking gap in signals, alerts, or runbooks. Do not recursively traverse the full operations doc tree.

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
- Focus on observability and operations gaps that would leave the first implementation unsafe to operate or impossible to debug.
- Ignore dashboard polish, extra nice-to-have metrics, and distant maturity improvements unless they block safe rollout or recovery.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and optionally list up to 3 deferred follow-ups.
- Stop once only non-blocking refinement remains.
