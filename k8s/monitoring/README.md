# Monitoring Manifests (Sample)

This directory contains sample Kubernetes manifests for FireMUD’s observability stack components.

These manifests are intentionally minimal and are meant to be adapted into your environment’s Helm/Kustomize overlays (for example, `kube-prometheus-stack` for Prometheus + Alertmanager + Grafana).

## Files

- `otel-collector.yaml` – OpenTelemetry Collector deployment used by services to export spans.
- `jaeger.yaml` – Minimal Jaeger deployment for trace visualization.
- `redis-exporter.yaml` – Redis exporter deployment for Redis core metrics.
- `alertmanager.yaml` – Example Alertmanager configuration.
- `prometheus-rules-firemud.yaml` – Sample `PrometheusRule` resource containing recording rules and alerts aligned with the target-state observability contract. Some player-experience, external-signal, and recovery-convergence rules depend on producers or mirrors that are not yet implemented or proven in the current services; the recovery missing-source alert is not readiness evidence. The profile overlays below are the installation boundary for the profile-dependent deadman rule.

The shared rules deliberately do not use a global `absent()` expression for player-flow canary series. The advertised capability and exposed-path set are deployment-owned: the retained-evidence validator checks completeness of each retained advertised canary artifact, while the player-experience and observability runbooks classify missing or unavailable advertised evidence as `unknown`/degraded. `PlayerFlowCanaryEvidenceStale` detects a present `playerflow_canary_success` series whose matching last-run series is missing, in addition to a present run timestamp that is future-dated or older than the profile budget. Continuous detection of a wholly absent tuple still requires a deployment-owned expected-series inventory and monitor; the profile overlays do not supply that target-only inventory.

The base Kustomization and profile overlays install only the `PrometheusRule` resources. They do not install `otel-collector.yaml`, `jaeger.yaml`, `redis-exporter.yaml`, or `alertmanager.yaml`; those sample stack manifests require separate installation or adaptation for the target environment. The Kustomize profile overlays are the canonical installation path for the Prometheus rules. Only the `independent-required-prometheus-published` overlay has a distinct rendered manifest: it adds the profile-bound deadman rule. The two omitted overlays currently render byte-identical shared rules, so their profile distinction is documentation-level selection rather than a separate rendered-manifest check.

The current sample overlays do not select the shared player-flow canary rules by the independent `playerFlowCanary` capability: all three overlays install them. A clean `playerFlowCanary=omitted` deployment emits no canary series, so these rules remain quiet and the canary capability is `not_applicable`; however, selecting an omitted overlay does not remove the rules or guarantee cleanup of residual canary series from a prior advertised deployment. During an `advertised` to `omitted` transition, retained residual series may therefore still produce `PlayerFlowCanaryEvidenceStale` until they expire. Capability-specific rule installation and residual-series cleanup, or an equivalent deployment-owned capability gate, remain unimplemented.

- `kubectl apply -k k8s/overlays/monitoring/independent-required-prometheus-published` installs the profile-aware `ObservabilityDeadmanHeartbeatStale` rule only when the profile is `independent-required` and `prometheusMirrors=published`. It consumes `observability_deadman_stale{profile="independent-required"}`, which must be emitted only after the external monitor applies that profile's configured stale threshold and evaluation window.
- `kubectl apply -k k8s/overlays/monitoring/independent-required-prometheus-omitted` selects the shared rules without `ObservabilityDeadmanHeartbeatStale`; an `independent-required` profile with `prometheusMirrors=omitted` must not install or page from the absent mirror. This overlay currently renders the same output as `independent-omitted`; the profile/configuration and external authority remain the operator's responsibility.
- `kubectl apply -k k8s/overlays/monitoring/independent-omitted` selects the same shared rules without `ObservabilityDeadmanHeartbeatStale`; an `independent-omitted` profile must not install this rule or emit the required-profile deadman signal. The overlay name documents the intended profile but does not provide an independent rendered-profile assertion.

Do not apply either `prometheus-rules-firemud.yaml` or the base `k8s/monitoring` Kustomization directly when deploying profile-bound monitoring; use the matching profile overlay and verify the selected deployment profile, external authority, and evidence together. The published overlay enforces its profile-bound rule in rendered output; the two omitted overlays do not reject a mismatched profile by themselves.

## Contract Links

- Alert label contract and metric naming/units: `design/architecture/system-architecture-logging-monitoring.md`
- Redis + tick metrics catalog: `design/architecture/system-architecture-redis-operations.md`
- Tick incident response guidance: `design/architecture/system-architecture-tick-incident-runbook.md`
