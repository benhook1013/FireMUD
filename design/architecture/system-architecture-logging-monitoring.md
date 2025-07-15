# 📊 FireMUD Logging & Monitoring Overview

This document consolidates the platform's observability architecture.

---

## 🔍 Logging Pipeline

- **Fluent Bit** sidecars collect service logs.
- Logs are stored in **Elasticsearch** and explored through **Kibana** dashboards.
- The **Logging & Admin Service** exposes moderation tools and log queries.
- Logs are emitted in JSON with request tracing fields (e.g., `traceId`, `playerId`)
  so troubleshooting across services is straightforward.
- gRPC services use the shared `LoggingInterceptor` to include `traceId` and
  `correlationId` in every log entry. See
  [Shared Libraries](./system-architecture-shared-libraries.md).
- Log retention defaults to **14 days** in development and **90 days** in production,
  after which indices are archived. These values can be tuned via the
  [Deployment Environments](./infrastructure/deployment-environments.md) settings.
- Log storage hosts can be customized via the `FLUENT_ELASTICSEARCH_HOST` and
  `FLUENT_ELASTICSEARCH_PORT` environment variables
  ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)).
- Operators search logs primarily through Kibana, but the Logging & Admin Service
  offers a focused UI for moderation and audit trails.

## 📈 Metrics & Tracing

- **Prometheus** scrapes metrics from all services and triggers alerts via **Alertmanager**.
- **Grafana** dashboards visualize performance data.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests.
  Traces are collected by an OpenTelemetry Collector and visualized with Jaeger.
  See [Tracing](./system-architecture-tracing.md) for deployment details.
- Metrics are recorded with Micrometer and emitted through a shared
  `MetricsInterceptor` so all gRPC endpoints expose consistent counters like
  `grpc.server.requests` and `grpc.app_error`.
- Most services expose a `/actuator/prometheus` endpoint for metrics. Scrape intervals
  are tuned per environment (typically 15s in development and 30s in production).
- Distributed traces are exported via OTLP and correlated with logs using the same
  `traceId` value.
- The OpenTelemetry collector endpoint is configurable via the `OTEL_ENDPOINT`
  environment variable ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)).

## 🩺 Health Checks

- Spring Boot `/actuator/health` endpoints feed Kubernetes readiness and liveness probes.
- See [Deployment Environments](./infrastructure/deployment-environments.md#🩺-kubernetes-health-monitoring) for probe behavior.

## 🚑 Error Tracking and Hotfixes

Logs in Kibana are searched daily for uncaught exceptions or repeated crashes. Alerts from Prometheus trigger on high error rates. When issues arise, operators follow the runbooks to deploy a hotfix image built from the `main` branch.

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Redis Architecture](./system-architecture-redis.md#📈-observability-and-reliability)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md)
