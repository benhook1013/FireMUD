# 📊 Operator Analytics Dashboards

This document describes the default Grafana and Kibana dashboards shipped with the **Logging & Admin Service**. They give operators visibility into game health, player activity, and moderation trends.

> **Status: In Progress** – Additional real-time analytics and export options are planned.

## Grafana Dashboards

- **Service Overview** – CPU, memory, and request rates for each microservice.
- **Game Sessions** – active player counts, tick durations, and command latency.
- **Database Health** – PostgreSQL connection statistics and slow query logs.
- **Redis Metrics** – cache hit ratios and eviction counts.
- **Alert Summary** – current Alertmanager alerts grouped by severity.

Dashboards are powered by Prometheus metrics scraped from `/actuator/prometheus` endpoints. Operators can adjust queries or add panels to suit their games.

## Kibana Dashboards

- **Log Volume** – ingest rates, log levels, and error hotspots.
- **Player Reports** – breakdown of abuse or bug reports by category.
- **Moderation Actions** – bans, warnings, and feature toggles over time.
- **Search by Trace ID** – correlate logs and traces using the `traceId` field.

These dashboards rely on structured JSON logs shipped via Fluent Bit. Saved searches make it easy to pivot on `tenantId` and player identifiers.

## Future Enhancements

- Real-time charts for saga workflow states.
- Custom dashboards per game with shared templates.
- Export options for longer-term analytics in external BI tools.
