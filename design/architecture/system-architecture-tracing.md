# 🔍 FireMUD System Architecture: Tracing

This document explains how distributed traces are collected and visualized across FireMUD services.

---

## 📡 OpenTelemetry Collector

All services emit spans using the [OpenTelemetry](https://opentelemetry.io/) SDK. A dedicated **OpenTelemetry Collector** runs inside the Kubernetes cluster to receive OTLP traffic and forward it to storage backends. A sample manifest is provided at `k8s/monitoring/otel-collector.yaml`.

- Deploy using the official [`opentelemetry-collector`](https://github.com/open-telemetry/opentelemetry-helm-charts) Helm chart or apply the sample manifest for local demos.
- The collector runs as the `otel-collector` service inside the cluster so other pods can reach it via `http://otel-collector:4317`.
- The collector exposes a `4317` gRPC endpoint. Services export spans to `http://otel-collector:4317` by default. The endpoint can be overridden via the `OTEL_ENDPOINT` environment variable (`otel.endpoint` property). See `.env.sample` and [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md#observability) for defaults.

  ```bash
  OTEL_ENDPOINT=http://collector.internal:4317
  ```

- The collector forwards spans to Jaeger over gRPC port `14250`.
- Metrics about the collector itself can be scraped by Prometheus from `/metrics`
  on port `8888`. The example manifest does not currently expose this port, so
  the collector metrics are unavailable. (TODO: Not yet implemented)

- The local Docker Compose stack does not include the collector or Jaeger yet. (TODO: Not yet implemented)

Every service relies on a shared `TracingConfig` in the `common-library` (`services/common-library/src/main/java/net/firedevops/firemud/common/config/TracingConfig.java`). This
configuration sets the `service.name` resource from `spring.application.name`,
uses a `BatchSpanProcessor`, and sends spans to the collector. The
`LoggingInterceptor`, `MetricsInterceptor`, and `TracingInterceptor` from the
[Shared Libraries](./system-architecture-shared-libraries.md) are registered in
each `GrpcServerConfig` so requests are instrumented with logs, metrics, and
spans consistently. See
[Logging & Monitoring](./system-architecture-logging-monitoring.md) for
additional observability details. `TracingInterceptor` opens a span for each
gRPC method and marks it successful or cancelled when the call completes.

## 🎛️ Jaeger UI

Traces are stored and visualized with **Jaeger**. A minimal Jaeger deployment is provided in `k8s/monitoring/jaeger.yaml`.

- Jaeger receives OTLP data from the collector.
- The web UI is exposed on port `16686` within the cluster.
- Retention settings are environment specific; development keeps a few days of data, while production retains up to 30 days. (TODO: Not yet implemented)
- Access the UI locally with:

  ```bash
  kubectl port-forward service/jaeger 16686:16686
  ```

## 📚 Related Documentation

- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Microservice Template](./microservices/service-template.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [System Context Diagram](./system-context-diagram.md)
