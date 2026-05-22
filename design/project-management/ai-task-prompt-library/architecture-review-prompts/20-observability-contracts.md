# Architecture Review Prompt: Observability Contracts

Read the following documents. Follow references and read nearby related files as required when a listed document points to another canonical source or when a closely related file is needed to judge an implementation-blocking gap in signals, alerts, or verification contracts. Do not recursively traverse the full operations doc tree.

- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-tracing.md`
- `design/architecture/system-architecture-testing.md`
- `design/observability/README.md`
- `design/observability/grafana/README.md`
- `design/observability/grafana/core-alerts-snippets.md`
- `design/observability/grafana/redis-alerts-snippets.md`
- `design/observability/grafana/tick-alerts-snippets.md`
- `design/observability/grafana/backup-alerts-snippets.md`
- `design/observability/grafana/player-experience-alerts-snippets.md`
- `design/observability/grafana/observability-stack-alerts-snippets.md`
- `design/observability/kibana/README.md`

Then:

- Review observability as a unified design: metrics, logs, traces, dashboards, alerts, and verification expectations across the platform.
- Check each finding against any already created slices or implementation that are clearly relevant, in case the issue has already been resolved in code or tracking and the design now needs to import that decision back into the docs.
- Do not summarize what already works well or restate basic descriptions of dashboards or metrics.
- Focus on observability gaps that would leave the first implementation unsafe to operate or impossible to debug.
- If slices, protos, or current implementation already resolve the seam but the design docs are stale, classify the issue as "import resolved decision back into design" rather than as an unresolved architecture blocker.
- Do not let dashboard polish, extra nice-to-have metrics, and distant maturity improvements crowd out blockers. Once blockers are cleared, list the highest-value non-blocking improvements if they would materially improve operability.
- Return at most 5 issues, ordered by severity.
- For each issue, include:
  - `Severity`: `blocking` or `important`
  - `Why it matters now`
  - `Docs involved`
  - `Suggested decision or spec change`
- If no implementation-blocking issues remain, say `No implementation-blocking issues found.` and include a `Suggested follow-ups` section with any worthwhile non-blocking improvements, ordered by leverage and not capped at 3.
- Stop once you have either identified the remaining blockers or captured the worthwhile non-blocking follow-ups.
