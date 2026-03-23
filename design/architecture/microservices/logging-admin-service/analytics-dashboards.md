# Operator Analytics Dashboards

This document describes the default Grafana and Kibana dashboards shipped with the
**Logging & Admin Service**. These dashboards provide operators with visibility
into game health, player activity and moderation trends. Sample JSON templates
live under [`design/observability`](../../../observability) for local testing.
For an overview of the pipeline that feeds these dashboards see
[Logging & Monitoring](../../system-architecture-logging-monitoring.md).

The dashboards include real-time analytics and export options.

## Grafana Dashboards

- **Service Overview** – CPU, memory, and request rates for each microservice. See [`service-overview.json`](../../../observability/grafana/service-overview.json).
- **Game Sessions** – active player counts, tick durations, and command latency.
- **Database Health** – PostgreSQL connection statistics and slow query logs.
- **Redis Metrics** – cache hit ratios and eviction counts.
- **Alert Summary** – current Alertmanager alerts grouped by severity.

Dashboards are powered by Prometheus metrics scraped from `/actuator/prometheus` endpoints.
Operators can adjust queries or add panels to suit their games.

## Kibana Dashboards

- **Log Volume** – ingest rates, log levels, and error hotspots. See [`log-volume.json`](../../../observability/kibana/log-volume.json).
- **Player Reports** – breakdown of abuse or bug reports by category.
- **Moderation Actions** – bans, warnings, and feature toggles over time.
- **Search by Trace ID** – correlate logs and traces using the `traceId` field.

These dashboards rely on structured JSON logs shipped via Fluent Bit. Saved
searches make it easy to pivot on `tenantId` or `characterId`.

## Additional Capabilities

- Real-time charts for saga workflow states.
- Per-game custom dashboards generated from shared templates.
- Export options for long-term analytics in external BI tools.
