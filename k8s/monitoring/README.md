# Monitoring Manifests (Sample)

This directory contains sample Kubernetes manifests for FireMUD’s observability stack components.

These manifests are intentionally minimal and are meant to be adapted into your environment’s Helm/Kustomize overlays (for example, `kube-prometheus-stack` for Prometheus + Alertmanager + Grafana).

## Files

- `otel-collector.yaml` – OpenTelemetry Collector deployment used by services to export spans.
- `jaeger.yaml` – Minimal Jaeger deployment for trace visualization.
- `redis-exporter.yaml` – Redis exporter deployment for Redis core metrics.
- `alertmanager.yaml` – Example Alertmanager configuration.
- `prometheus-rules-firemud.yaml` – Sample `PrometheusRule` resource containing recording rules and alerts aligned with the observability contract.

## Contract Links

- Alert label contract and metric naming/units: `design/architecture/system-architecture-logging-monitoring.md`
- Redis + tick metrics catalog: `design/architecture/system-architecture-redis-operations.md`
- Tick incident response guidance: `design/architecture/system-architecture-tick-incident-runbook.md`
