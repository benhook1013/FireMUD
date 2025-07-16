# 📊 FireMUD Logging & Monitoring Overview

This document consolidates the platform's observability architecture.

---

## 🔍 Logging Pipeline

- **Fluent Bit** sidecars collect service logs from every microservice.
- Logs are stored in **Elasticsearch** and explored through **Kibana** dashboards.
- The **Logging & Admin Service** exposes moderation tools and log queries.
- Logs are emitted in JSON with request tracing fields (e.g., `traceId`).
  Including the active `playerId` in log entries is planned
  for better moderation context. (TODO: Not yet implemented)
- gRPC services use the shared `LoggingInterceptor` to include `traceId` and
  `correlationId` in every log entry. See
  [Shared Libraries](./system-architecture-shared-libraries.md).
- Log retention defaults to **14 days** in development and **90 days** in production,
  after which indices are archived. These values can be tuned via the
  [Deployment Environments](./infrastructure/deployment-environments.md) settings.
- Log storage hosts can be customized via the `FLUENT_ELASTICSEARCH_HOST` and
  `FLUENT_ELASTICSEARCH_PORT` environment variables
  ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability)).
- Operators search logs primarily through Kibana.
  The Logging & Admin Service will provide a dedicated UI for moderation
  and audit trails. (TODO: Not yet implemented)

## 📈 Metrics & Tracing

- **Prometheus** scrapes metrics from all services and triggers alerts via **Alertmanager**.
- **Grafana** dashboards visualize performance data.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests.
  Traces are collected by an OpenTelemetry Collector and visualized with Jaeger.
  See [Tracing](./system-architecture-tracing.md) for deployment details.
- Sample Kubernetes manifests under [`k8s/monitoring`](../../k8s/monitoring) deploy the collector and Jaeger (`otel-collector.yaml`, `jaeger.yaml`).
- Metrics are recorded with Micrometer. The shared `MetricsInterceptor`
  tracks `grpc.server.requests` for each call. Services increment the
  `grpc.app_error` counter in their `error()` helpers as described in the
  [gRPC API Style guidelines](./system-architecture-grpc.md).
- Business methods in services are annotated with `@Timed` to publish custom Prometheus timers.
- Most services expose a `/actuator/prometheus` endpoint for metrics. Scrape intervals
  are tuned per environment (typically 15s in development and 30s in production).
- Metrics for Redis are collected via the [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment. A PostgreSQL exporter can also be added for database metrics. (TODO: Not yet implemented)
- Distributed traces are exported via OTLP and correlated with logs using the same
  `traceId` value.
- Metrics use the same `traceId` label via the `MetricsInterceptor`, making it easy
  to correlate latency spikes with specific traces and log entries. (TODO: Not yet implemented)
- The OpenTelemetry collector endpoint is configurable via the `OTEL_ENDPOINT` environment variable
  ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)).

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
