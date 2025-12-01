# Grafana Dashboards

This directory stores Grafana dashboard exports that visualize key FireMUD service metrics.

These JSON files can be imported into a Grafana instance connected to the project’s Prometheus data source to bootstrap consistent dashboards across environments.

## Dashboards

- [service-overview.json](./service-overview.json) – High-level service overview with status panels and charts for request rates, error rates, and latency broken down by microservice.

To import a dashboard, use Grafana’s “Import dashboard” feature and either upload the JSON file directly or paste its contents into the “Import via panel JSON” field, then bind it to the correct Prometheus data source.
