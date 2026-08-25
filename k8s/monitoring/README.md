# Monitoring Manifests (Sample)

This directory contains sample Kubernetes manifests for FireMUD’s observability stack components.

These manifests are intentionally minimal and are meant to be adapted into your environment’s Helm/Kustomize overlays (for example, `kube-prometheus-stack` for Prometheus + Alertmanager + Grafana).

## Files

- `otel-collector.yaml` – OpenTelemetry Collector deployment used by services to export spans.
- `jaeger.yaml` – Minimal Jaeger deployment for trace visualization.
- `redis-exporter.yaml` – Redis exporter deployment for Redis core metrics.
- `alertmanager.yaml` – Example Alertmanager configuration.
- `prometheus-rules-firemud.yaml` – Sample `PrometheusRule` resource containing recording rules and alerts aligned with the target-state observability contract. Some player-experience, external-signal, and recovery-convergence rules depend on producers or mirrors that are not yet implemented or proven in the current services; the recovery missing-source alert is not readiness evidence. The profile overlays below are the installation boundary for the profile-dependent deadman rule.

The base Kustomization and profile overlays install only the `PrometheusRule` resources. They do not install `otel-collector.yaml`, `jaeger.yaml`, `redis-exporter.yaml`, or `alertmanager.yaml`; those sample stack manifests require separate installation or adaptation for the target environment. The Kustomize profile overlays are the canonical installation path for the Prometheus rules:

- `kubectl apply -k k8s/overlays/monitoring/independent-required-prometheus-published` installs the profile-aware `ObservabilityDeadmanHeartbeatStale` rule only when the profile is `independent-required` and `prometheusMirrors=published`. It consumes `observability_deadman_stale{profile="independent-required"}`, which must be emitted only after the external monitor applies that profile's configured stale threshold and evaluation window.
- `kubectl apply -k k8s/overlays/monitoring/independent-required-prometheus-omitted` installs the shared rules without `ObservabilityDeadmanHeartbeatStale`; an `independent-required` profile with `prometheusMirrors=omitted` must not install or page from the absent mirror.
- `kubectl apply -k k8s/overlays/monitoring/independent-omitted` installs the shared rules without `ObservabilityDeadmanHeartbeatStale`; an `independent-omitted` profile must not install this rule or emit the required-profile deadman signal.

Do not apply either `prometheus-rules-firemud.yaml` or the base `k8s/monitoring` Kustomization directly when deploying profile-bound monitoring; use the matching profile overlay so manifest installation enforces the profile boundary.

## Contract Links

- Alert label contract and metric naming/units: `design/architecture/system-architecture-logging-monitoring.md`
- Redis + tick metrics catalog: `design/architecture/system-architecture-redis-operations.md`
- Tick incident response guidance: `design/architecture/system-architecture-tick-incident-runbook.md`
