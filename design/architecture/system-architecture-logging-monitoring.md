# 📊 FireMUD Logging & Monitoring Overview

This document consolidates the platform's observability architecture.

---

## 🔍 Logging Pipeline

- **Fluent Bit** sidecars collect service logs from every microservice.
- Logs are stored in **Elasticsearch** and explored through **Kibana** dashboards.
- The **Logging & Admin Service** exposes moderation tools and log queries.
- Logs are emitted in JSON with request tracing fields (e.g., `traceId`) and the active `playerId` for moderation context.
- Kibana dashboards filter by both `traceId` and `playerId` to narrow investigations quickly.
- gRPC services use the shared `LoggingInterceptor` to include `traceId` and `correlationId` in every log entry. See [Shared Libraries](./system-architecture-shared-libraries.md).
- Log retention defaults to **14 days** in development and **90 days** in production, after which indices are archived. These values can be tuned via the [Deployment Environments](./infrastructure/deployment-environments.md) settings.
- Log storage hosts can be customized via the `FLUENT_ELASTICSEARCH_HOST` and `FLUENT_ELASTICSEARCH_PORT` environment variables ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability)).
- The local Docker Compose stack includes Fluent Bit, Prometheus, Grafana, and Jaeger in addition to streaming logs to the console.
- Operators search logs primarily through Kibana. Sample Grafana and Kibana dashboards live under [`design/observability`](../observability) and are described in [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md). The Logging & Admin Service provides a dedicated UI for moderation and audit trails.

## 📈 Metrics & Tracing

- **Prometheus** scrapes metrics from all services and triggers alerts via **Alertmanager**.
- **Grafana** dashboards visualize performance data.
- The Logging & Admin Service queries Prometheus for metrics and Jaeger for trace analysis to power moderation dashboards and investigations. See [Operator Dashboards](./microservices/logging-admin-service/analytics-dashboards.md) for examples.
- The service consumes Alertmanager notifications so operators can triage alerts inside the admin UI.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests. Traces are collected by an OpenTelemetry Collector and visualized with Jaeger. See [Tracing](./system-architecture-tracing.md) for deployment details.
- Sample Kubernetes manifests under [`k8s/monitoring`](../../k8s/monitoring) deploy the collector and Jaeger (`otel-collector.yaml`, `jaeger.yaml`).
- Metrics are recorded with Micrometer. The shared `MetricsInterceptor` tracks `grpc.server.requests` for each call. Services increment the `grpc.app_error` counter in their `error()` helpers as described in the [gRPC API Style guidelines](./system-architecture-grpc.md).
- Business methods in services are annotated with `@Timed` to publish custom Prometheus timers.
- Most services expose a `/actuator/prometheus` endpoint for metrics. Scrape intervals are tuned per environment (typically 15s in development and 30s in production).
- Metrics for Redis are collected via the [`redis-exporter`](../../k8s/monitoring/redis-exporter.yaml) deployment, and a PostgreSQL exporter is available for database metrics.
- Distributed traces are exported via OTLP and correlated with logs using the same `traceId` value.
- Metrics reuse the `traceId` label via the `MetricsInterceptor`, making it easy to correlate latency spikes with specific traces and log entries.
- The OpenTelemetry collector endpoint is configurable via the `OTEL_ENDPOINT` environment variable ([Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)).

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
