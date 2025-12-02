# Observability Dashboards

The observability directory contains dashboards and saved objects used to monitor the FireMUD platform in production and non-production environments.

These assets complement the architecture logging and monitoring design by providing ready-to-import visualizations for Grafana and Kibana.

## Subdirectories

- [grafana/](./grafana/) – JSON exports of Grafana dashboards for service health, latency, and request/command volume.
- [kibana/](./kibana/) – JSON exports of Kibana index patterns, searches, and dashboards focused on log exploration and error investigation.

For a conceptual overview of the observability stack (Prometheus, Elasticsearch, Fluent Bit, OpenTelemetry, Alertmanager, and dashboarding tools), see [System Architecture – Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md) and [Observability Components](../architecture/system-architecture-diagram.md#-observability-components).
