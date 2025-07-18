# 📊 Operator Analytics Dashboards

This document describes the default Grafana and Kibana dashboards shipped with the
**Logging & Admin Service**.
These dashboards provide operators with visibility into game health, player activity, and moderation trends.
Sample JSON templates live under [`design/observability`](../../observability) for local testing.

> **Status: In Progress** – Many dashboards are placeholders. Additional real-time analytics and export options are planned. (TODO: Not yet implemented)

## Grafana Dashboards

- **Service Overview** – CPU, memory, and request rates for each microservice. See `service-overview.json`. (TODO: Not yet implemented)
- **Game Sessions** – active player counts, tick durations, and command latency. (TODO: Not yet implemented)
- **Database Health** – PostgreSQL connection statistics and slow query logs. (TODO: Not yet implemented)
- **Redis Metrics** – cache hit ratios and eviction counts. (TODO: Not yet implemented)
- **Alert Summary** – current Alertmanager alerts grouped by severity. (TODO: Not yet implemented)

Dashboards are powered by Prometheus metrics scraped from `/actuator/prometheus` endpoints.
Operators can adjust queries or add panels to suit their games.

## Kibana Dashboards

- **Log Volume** – ingest rates, log levels, and error hotspots. See `log-volume.json`. (TODO: Not yet implemented)
- **Player Reports** – breakdown of abuse or bug reports by category. (TODO: Not yet implemented)
- **Moderation Actions** – bans, warnings, and feature toggles over time. (TODO: Not yet implemented)
- **Search by Trace ID** – correlate logs and traces using the `traceId` field. (TODO: Not yet implemented)

These dashboards rely on structured JSON logs shipped via Fluent Bit.
Saved searches make it easy to pivot on `tenantId` and player identifiers. (TODO: Not yet implemented)

## Future Enhancements

- Real-time charts for saga workflow states. (TODO: Not yet implemented)
- Custom dashboards per game with shared templates. (TODO: Not yet implemented)
- Export options for longer-term analytics in external BI tools. (TODO: Not yet implemented)
