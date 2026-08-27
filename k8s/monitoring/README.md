# Monitoring Manifests (Sample)

This directory contains sample Kubernetes manifests for FireMUD’s observability stack components.

These manifests are intentionally minimal and are meant to be adapted into your environment’s Helm/Kustomize overlays (for example, `kube-prometheus-stack` for Prometheus + Alertmanager + Grafana).

## Files

- `otel-collector.yaml` – Local/demo-only OpenTelemetry Collector fixture used by services to export spans. It accepts plaintext OTLP and is not valid shared/player-facing deployment evidence.
- `jaeger.yaml` – Minimal Jaeger deployment for trace visualization.
- `redis-exporter.yaml` – Redis exporter deployment for Redis core metrics.
- `alertmanager.yaml` – Example Alertmanager configuration.
- `prometheus-rules-firemud.yaml` – Sample `PrometheusRule` resource containing recording rules and alerts aligned with the target-state observability contract. Some player-experience, external-signal, and recovery-convergence rules depend on producers or mirrors that are not yet implemented or proven in the current services; the recovery missing-source alert is not readiness evidence. The profile overlays below are the installation boundary for the profile-dependent deadman rule. Public-path blackbox alert installation remains target-only until a deployment-owned expected-series inventory can gate each exposed path.

The shared rules deliberately do not install the target-state `PlayerFlowCanary*` alert family or use a global `absent()` expression for player-flow canary series. The advertised capability and exposed-path set are deployment-owned: the retained-evidence validator checks completeness of each retained advertised canary artifact, while the player-experience and observability runbooks classify missing or unavailable advertised evidence as `unknown`/degraded. The reference alert family remains target-only until a deployment-owned `playerFlowCanary=advertised` applicability and expected-series gate exists; the profile overlays do not supply that target-only gate.

The base Kustomization and profile overlays install only the `PrometheusRule` resources. They do not install `otel-collector.yaml`, `jaeger.yaml`, `redis-exporter.yaml`, or `alertmanager.yaml`; those sample stack manifests require separate installation or adaptation for the target environment. The Kustomize profile overlays are the canonical installation path for the Prometheus rules. Only the `independent-required-prometheus-published` overlay has a distinct rendered manifest: it adds the profile-bound deadman rules. Public-path blackbox zero and absent-evidence rules, and the `PlayerFlowCanary*` alert family, are not installed at the current boundary because no deployment-owned applicability/expected-series gates exist for them. The two omitted overlays render only the shared non-profile-dependent rules.

Do not apply `otel-collector.yaml` directly. Before adapting this local/demo fixture into an environment-owned deployment, configure TLS/mTLS on every telemetry hop or prove producer-side redaction before each unavoidable plaintext hop, with credentials supplied by the environment rather than this fixture. The `tls.insecure: true` exporter setting is therefore never a shared/player-facing deployment setting.

Player-flow canary metrics, retained evidence, and the `PlayerFlowCanary*` alert contracts remain target-state. The current runner downgrades advertised canaries to `omitted`, and the shared PrometheusRule and all profile overlays withhold the canary alert family until a deployment-owned `playerFlowCanary=advertised` applicability/expected-series gate and safe advertised-to-omitted transition cleanup exist. The reference rules and validator preserve the target contract for that future boundary.

- `kubectl apply -k k8s/overlays/monitoring/independent-required-prometheus-published` installs the profile-aware `ObservabilityDeadmanHeartbeatStale`/`Missing` rules only for an explicitly selected `independent-required` profile with `prometheusMirrors=published`. It consumes `observability_deadman_stale{profile="independent-required"}`, which must be emitted only after the external monitor applies that profile's configured stale threshold and evaluation window. Public-path blackbox alerts are not installed at this current boundary: their zero and absent-mirror rules require a deployment-owned expected-series configuration that identifies each exposed path, while non-exposed paths remain `not_applicable`.
- `kubectl apply -k k8s/overlays/monitoring/independent-required-prometheus-omitted` selects the shared rules without any profile-dependent deadman or blackbox P0 rules; an `independent-required` profile with `prometheusMirrors=omitted` must not install or page from absent mirrors. This overlay currently renders the same output as `independent-omitted`; the profile/configuration and external authority remain the operator's responsibility.
- `kubectl apply -k k8s/overlays/monitoring/independent-omitted` selects the same shared rules without any profile-dependent deadman or blackbox P0 rules; an `independent-omitted` profile must not install these rules or emit the required-profile deadman signal. The overlay name documents the intended profile but does not provide an independent rendered-profile assertion.

When switching away from `independent-required-prometheus-published`, remove the previously applied `firemud-independent-required-observability` `PrometheusRule` before or as part of applying the replacement overlay, then verify both the rendered and live alert sets. Applying an omitted overlay does not delete resources that were installed by the published overlay; leaving that resource in place can retain stale deadman pages after the external mirror is intentionally omitted. The repository does not currently own a monitoring deployment controller or apply/prune wrapper that can perform and attest this transition atomically, so adopters must include the deletion and live readback in their environment-owned deployment procedure. A supported repository-owned profile-transition cleanup and proof path remains an implementation gap rather than an implied property of these sample overlays.

Do not apply either `prometheus-rules-firemud.yaml` or the base `k8s/monitoring` Kustomization directly when deploying profile-bound monitoring; use the matching profile overlay and verify the selected deployment profile, external authority, and evidence together. The published overlay enforces its profile-bound rule in rendered output; the two omitted overlays do not reject a mismatched profile by themselves.

## Contract Links

- Alert label contract and metric naming/units: `design/architecture/system-architecture-logging-monitoring.md`
- Redis + tick metrics catalog: `design/architecture/system-architecture-redis-operations.md`
- Tick incident response guidance: `design/architecture/system-architecture-tick-incident-runbook.md`
