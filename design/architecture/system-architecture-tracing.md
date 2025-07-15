# 🔍 FireMUD System Architecture: Tracing

This document explains how distributed traces are collected and visualized across FireMUD services.

---

## 📡 OpenTelemetry Collector

All services emit spans using the [OpenTelemetry](https://opentelemetry.io/) SDK. A dedicated **OpenTelemetry Collector** runs inside the Kubernetes cluster to receive OTLP traffic and forward it to storage backends.

- Deploy using the official [`opentelemetry-collector`](https://github.com/open-telemetry/opentelemetry-helm-charts) Helm chart or the sample manifest in `k8s/monitoring/otel-collector.yaml`.
- The collector exposes a `4317` gRPC endpoint. Services export spans to `http://otel-collector:4317` by default. The endpoint can be overridden via the `OTEL_ENDPOINT` environment variable (`otel.endpoint` property).
- The collector forwards spans to Jaeger over gRPC port `14250`.
- Metrics about the collector itself are scraped by Prometheus at `/metrics`.

Each service includes a small `TracingConfig` bean that sets `service.name` and exports spans to the collector. A `TracingInterceptor` registered in `GrpcServerConfig` wraps every gRPC call so that request processing is recorded as spans automatically.

## 🎛️ Jaeger UI

Traces are stored and visualized with **Jaeger**. A minimal Jaeger deployment is provided in `k8s/monitoring/jaeger.yaml`.

- Jaeger receives OTLP data from the collector.
- The web UI is exposed on port `16686` within the cluster.
- Retention settings are environment specific; development keeps a few days of data, while production retains up to 30 days.
- Access the UI locally with:

  ```bash
  kubectl port-forward service/jaeger 16686:16686
  ```

## 📚 Related Documentation

- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Microservice Template](./microservices/service-template.md)
- [Infrastructure Overview](./infrastructure/README.md)
