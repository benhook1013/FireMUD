# External Monitoring Contract

This document defines the FireMUD monitoring that must continue to detect and page on outages when the in-cluster Prometheus and Alertmanager stack is degraded or unavailable.

It complements the Prometheus-facing observability contract in `design/architecture/system-architecture-logging-monitoring.md`.

## Implementation Status

This is a prod-like target-state contract. The repository now provides the canonical runtime smoke harness in `dev-tools/observability/run-player-experience-smoke.py`, retained-evidence validation in `dev-tools/observability/validate-player-experience-smoke-evidence.py`, and the shared mirrored metric vocabulary for blackbox, deadman, and player-flow canary signals. It still does not ship an authoritative external monitoring deployment itself; each prod-like environment must wire that deployment and its paging route separately.

## What This Is

FireMUD uses two observability layers in prod-like environments:

- **Authoritative external pager**
  - Runs outside the cluster and outside the Prometheus + Alertmanager failure domain.
  - Must still page operators when the public edge is down or when the observability stack itself is unhealthy.
- **Prometheus mirror**
  - Mirrors selected external-monitor state into low-cardinality Prometheus metrics.
  - Exists so Grafana dashboards, runbooks, and smoke tests can use a shared vocabulary when Prometheus is healthy.

The external pager is the real source of truth. The Prometheus mirror is a convenience view. If an environment has only the mirrored metrics and no independent external pager, it does not satisfy this contract.

## Required External Checks

Prod-like environments must configure the following checks in the authoritative external monitoring system.

### 1. Deadman Heartbeat Freshness

- An in-cluster heartbeat emitter must publish a timestamp to the external monitor.
- The external monitor must page when that heartbeat becomes stale for more than `3 * heartbeat_interval_seconds`.
- Default contract:
  - `heartbeat_interval_seconds = 60`
  - page when stale for more than `180s`

This check exists so FireMUD can still detect a broad cluster or observability-stack failure even when Prometheus cannot evaluate internal rules.

### 2. Public Player Entry-Path Reachability

The external monitor must probe the real public player entry paths:

- `path="websocket"` for the browser / Gateway path
- `path="telnet"` for the TCP Proxy path

These checks must validate the real path handshake, not just TCP port-open status.

This check exists so FireMUD can detect public-edge outages independently of internal monitoring health.

### 3. Public Observability Entrypoints

The external monitor must also check operator-facing observability entrypoints:

- Prometheus
- Alertmanager
- Grafana
- Kibana or the Elasticsearch-backed log-query entrypoint
- Jaeger query UI or trace-query endpoint

These checks are for operator access continuity. They do not replace in-cluster health alerts for the underlying services.

Each check must have:

- a documented external target
- a canonical environment-scoped identity in the external monitoring product
- a non-production failure or test method that proves the check can open an incident without relying on Prometheus

## Prometheus Mirror

When Prometheus is healthy, selected external-monitor state must be mirrored into low-cardinality metrics so dashboards and runbooks can show the same conditions with stable names.

Canonical mirrored metrics:

- `entrypath_blackbox_probe_success{path,target}`
  - Mirrors the authoritative external result for public entry-path reachability.
  - `path` is a bounded enum and must use `websocket` and `telnet`.
  - `target` identifies the externally probed endpoint and must remain low-cardinality.
- `observability_deadman_heartbeat_timestamp_seconds{source}`
  - Mirrors the latest externally observed in-cluster heartbeat timestamp.
  - `source` identifies the environment or monitor instance and must remain low-cardinality.

Prometheus alerts and dashboards may use these mirrored metrics, but the external monitoring system must still page independently using its own native checks and thresholds.

## Observability Entrypoint Mirror Options

Public observability entrypoint checks do not require one universal mirrored metric name today. Each prod-like environment must choose one of these approaches and document it in readiness evidence:

- mirror the checks into a bounded Prometheus-facing metric vocabulary
- keep them authoritative external-only and document that they are verified in the external monitoring product rather than in Prometheus

Recommended bounded mirror vocabulary:

- `observability_entrypoint_probe_success{entrypoint,target}`
  - `entrypoint` is a bounded enum such as `prometheus`, `alertmanager`, `grafana`, `kibana_log_query`, or `jaeger_query`
  - `target` identifies the externally probed endpoint and must remain low-cardinality
  - values are `1` for reachable and `0` for unreachable

This vocabulary is recommended for cross-environment consistency, not mandatory.

## Ownership and Evidence

- `owner="platform"` is responsible for external monitoring configuration, routing, and periodic validation.

Prod-like readiness evidence must record:

- the external monitoring product or deployment used
- the configured deadman threshold
- the configured `websocket` and `telnet` targets
- the configured targets and check identities for Prometheus, Alertmanager, Grafana, Kibana/log-query, and Jaeger/trace-query availability
- evidence that the authoritative external pager can fire without Prometheus
- evidence that mirrored Prometheus metrics match the external monitor state when Prometheus is healthy

## Compatibility Mapping

If a monitoring product cannot expose the canonical mirrored metric names directly, the environment must document a compatibility mapping that preserves:

- deadman freshness semantics
- `path="websocket"` and `path="telnet"` semantics
- independent external paging behavior
- existing runbook behavior and alert naming from the architecture docs

Compatibility mappings are acceptable only for the Prometheus mirror. They are not a substitute for the authoritative external checks themselves.

Illustrative mapping:

- External monitor native checks:
  - `synthetic.tcp.telnet.prod.status`
  - `synthetic.http.websocket.prod.status`
  - `heartbeat.firemud.prod.last_seen_unix`
- Prometheus mirror mapping:
  - `synthetic.tcp.telnet.prod.status -> entrypath_blackbox_probe_success{path="telnet",target="prod-telnet-edge"}`
  - `synthetic.http.websocket.prod.status -> entrypath_blackbox_probe_success{path="websocket",target="prod-web-gateway"}`
  - `heartbeat.firemud.prod.last_seen_unix -> observability_deadman_heartbeat_timestamp_seconds{source="prod"}`

Required preserved semantics:

- the external monitor still pages on its native checks without Prometheus
- `path="telnet"` and `path="websocket"` remain canonical in mirrored views
- runbooks and shared alert naming continue to reference FireMUD’s canonical contract rather than vendor-native names

## Scope

This is the contract for prod-like environments that claim FireMUD-grade monitoring. Early local, preview, or hobby environments may implement a reduced version of this model, but they should not be described as meeting the prod-like monitoring contract unless the independent external pager path exists.
