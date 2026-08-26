# Operator Analytics Dashboards

This document defines the target dashboard catalog for deployment profiles that advertise the corresponding observability capabilities. Sample JSON assets live under [`design/observability`](../../../observability) for local validation and adaptation, but the Logging & Admin Service does not currently implement Kibana/Grafana API clients, embedded-dashboard endpoints, or a separate admin UI. Its current `/logs/query` path reads only the service-owned PostgreSQL `log_events` table and is not the profile-aware operator-query path. The canonical profile, evidence, and degradation contract remains in [Logging & Monitoring](../../system-architecture-logging-monitoring.md).

When implemented for an applicable profile, the dashboards may include real-time analytics and export options. Omitted capabilities remain `not_applicable` rather than being represented by empty embedded panels.

## Grafana Dashboards (Target Default Indexed Profile)

- **Service Overview** – CPU, memory, and request rates for each microservice. See [`service-overview.json`](../../../observability/grafana/service-overview.json).
- **Game Sessions** – active player counts, tick durations, and command latency.
- **Database Health** – PostgreSQL connection statistics and slow query logs.
- **Redis Metrics** – cache hit ratios and eviction counts.
- **Alert Summary** – current Alertmanager alerts grouped by severity.

These target dashboards use Prometheus metrics scraped from `/actuator/prometheus` endpoints. A selected profile may map an equivalent supported dashboard path or declare the capability omitted.

## Kibana Dashboards (Target Default Indexed Profile)

- **Log Volume** – ingest rates, log levels, and error hotspots. See [`log-volume.json`](../../../observability/kibana/log-volume.json).
- **Player Reports** – breakdown of abuse or bug reports by category.
- **Moderation Actions** – bans, warnings, and feature toggles over time.
- **Search by Trace ID** – correlate logs and traces using the `traceId` field.

For the default indexed profile, these target dashboards rely on structured JSON logs shipped via Fluent Bit and saved searches that pivot on authorized `tenantId` or `characterId` fields when those fields apply. A compatible backend documents its equivalent mapping; a reduced profile does not claim these indexed assets.

## Additional Target Capabilities

- Real-time charts for saga workflow states.
- Per-game custom dashboards generated from shared templates.
- Export options for long-term analytics in external BI tools.
