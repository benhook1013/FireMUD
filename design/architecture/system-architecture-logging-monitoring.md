# 📊 FireMUD Logging & Monitoring Overview

This document consolidates the platform's observability architecture. It replaces duplicated descriptions found in other docs.

---

## 🔍 Logging Pipeline

- **Fluent Bit** sidecars collect service logs.
- Logs are stored in **Elasticsearch** and explored through **Kibana** dashboards.
- The **Logging & Admin Service** exposes moderation tools and log queries.
- Logs are emitted in JSON with request tracing fields (e.g., `traceId`, `playerId`)
  so troubleshooting across services is straightforward.
- Log retention defaults to **14 days** in development and **90 days** in production,
  after which indices are archived. These values can be tuned via the
  [Deployment Environments](./infrastructure/deployment-environments.md) settings.
- Operators search logs primarily through Kibana, but the Logging & Admin Service
  offers a focused UI for moderation and audit trails.

## 📈 Metrics & Tracing

- **Prometheus** scrapes metrics from all services and triggers alerts via **Alertmanager**.
- **Grafana** dashboards visualize performance data.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests.
  Traces are collected by an OpenTelemetry Collector and visualized with Jaeger.
  See [Tracing](./system-architecture-tracing.md) for deployment details.
- Most services expose a `/actuator/prometheus` endpoint for metrics. Scrape intervals
  are tuned per environment (typically 15s in development and 30s in production).
- Distributed traces are exported via OTLP and correlated with logs using the same
  `traceId` value.

## 🩺 Health Checks

- Spring Boot `/actuator/health` endpoints feed Kubernetes readiness and liveness probes.
- See [Deployment Environments](./infrastructure/deployment-environments.md#🩺-kubernetes-health-monitoring) for probe behavior.

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Redis Architecture](./system-architecture-redis.md#📈-observability-and-reliability)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
