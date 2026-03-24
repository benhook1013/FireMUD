# External Monitoring Contract

This document is the canonical design source for FireMUD monitoring that must continue to detect outages when the in-cluster Prometheus and Alertmanager stack is degraded or fully unavailable.

It complements the Prometheus-facing observability contracts in `design/architecture/system-architecture-logging-monitoring.md`.

## Purpose

In plain terms: FireMUD must have an external monitoring path that can still page operators when the cluster, Prometheus, Alertmanager, or the public edge are unhealthy.

FireMUD uses two distinct observability layers in prod-like environments:

- **Authoritative external paging path**
  - Runs outside the Prometheus + Alertmanager failure domain.
  - Is responsible for paging on total observability-stack outages and total public-edge outages.
- **Prometheus mirror**
  - Receives a low-cardinality mirror of selected external-monitor state so Grafana dashboards, runbooks, and smoke tests can show the same conditions when Prometheus is healthy.
  - Must never be treated as the only source of truth for the external checks.

If an implementation cannot satisfy the authoritative external paging path, the environment must not be described as meeting FireMUD’s prod-like monitoring contract.

## Mental Model

Use this split when reasoning about the system:

- **External monitor**
  - The real pager.
  - Must open incidents even when Prometheus, Alertmanager, or Grafana are unavailable.
  - Examples: deadman heartbeat, public WebSocket reachability, public Telnet reachability.
- **Prometheus mirror**
  - A convenience view of selected external-monitor state.
  - Lets Grafana dashboards, runbooks, and smoke tests use the same bounded metric names.
  - Must never be treated as proof that independent paging exists.

## Required External Checks

Prod-like environments must configure the following checks in the authoritative external paging system:

1. **Deadman heartbeat freshness**
   - Source: an in-cluster heartbeat emitter publishes a timestamp to the external monitor.
   - Failure rule: page when the heartbeat is stale for more than `3 * heartbeat_interval_seconds`.
   - Default contract: `heartbeat_interval_seconds = 60`, page at `> 180s` stale.

2. **Public player entry-path reachability**
   - Required paths:
     - `path="websocket"` for the browser/Gateway path
     - `path="telnet"` for the TCP Proxy path
   - Probe target must validate the real handshake for that path, not only TCP port-open status.
   - These checks page independently from the deadman path so a public edge outage is still actionable when the internal monitoring stack is healthy.

3. **Public observability entrypoints**
   - At minimum:
     - Prometheus
     - Alertmanager
     - Grafana
     - Kibana or the Elasticsearch-backed log query entrypoint
     - Jaeger query UI or trace query endpoint
   - These checks are primarily for operator-access continuity and do not replace in-cluster health alerts for the underlying services.
   - Each check must have:
     - a documented external target,
     - a canonical environment-scoped identity in the external monitoring product,
     - a non-production failure or test method that proves the check can open an incident without relying on Prometheus.

Concrete example:

- The external monitor checks:
  - WebSocket reachability against the real public browser entry path.
  - Telnet reachability against the real public Telnet entry path.
  - Deadman heartbeat freshness from an in-cluster emitter.
  - Public Grafana and Prometheus reachability for operator access continuity.
- Prometheus mirrors selected results into stable metrics such as:
  - `entrypath_blackbox_probe_success{path="websocket",target="prod-web-gateway"}`
  - `entrypath_blackbox_probe_success{path="telnet",target="prod-telnet-edge"}`
  - `observability_deadman_heartbeat_timestamp_seconds{source="prod"}`

## Prometheus Mirror Contract

When Prometheus is healthy, the external monitoring state must also be mirrored into low-cardinality metrics so dashboards and runbooks can use a single vocabulary:

- `entrypath_blackbox_probe_success{path,target}`
  - Mirror of the authoritative external entry-path reachability result.
  - `path` is a bounded enum and must use `websocket` and `telnet`.
  - `target` identifies the externally probed endpoint and must remain low-cardinality.
- `observability_deadman_heartbeat_timestamp_seconds{source}`
  - Mirror of the latest externally observed in-cluster heartbeat timestamp.
  - `source` identifies the emitting environment or monitor instance and must remain low-cardinality.

Prometheus alerts and dashboards may use these mirrored metrics, but the external monitor must still page independently using its own native checks and thresholds.

Public observability entrypoint checks do not require a universal mirrored metric name today, but each prod-like environment must choose one of the following and document it in readiness evidence:

- a bounded Prometheus-facing mirror vocabulary for those checks, or
- an explicit statement that the checks are authoritative external-only and are verified through the external monitoring product rather than mirrored metrics.

Either way, operators and smoke tests must be able to identify which external checks correspond to Prometheus, Alertmanager, Grafana, Kibana/log-query, and Jaeger/trace-query availability.

Illustrative bounded mirror vocabulary for observability entrypoints:

- `observability_entrypoint_probe_success{entrypoint,target}`
  - `entrypoint` is a bounded enum such as `prometheus`, `alertmanager`, `grafana`, `kibana_log_query`, or `jaeger_query`.
  - `target` identifies the externally probed endpoint and must remain low-cardinality.
  - Values are boolean-like: `1` when the external check can reach the entrypoint and `0` when it cannot.
- Example mappings:
  - `vendor.prometheus.staging.status -> observability_entrypoint_probe_success{entrypoint="prometheus",target="staging-prometheus"}`
  - `vendor.kibana.staging.status -> observability_entrypoint_probe_success{entrypoint="kibana_log_query",target="staging-kibana"}`

This vocabulary is recommended for cross-environment consistency, not mandatory. Environments may keep entrypoint checks external-only or use another bounded mirror vocabulary if they document the mapping in readiness evidence.

## Ownership and Evidence

- `owner="platform"` is responsible for the external monitoring configuration, routing, and periodic validation.
- Prod-like readiness evidence must record:
  - the external monitoring product or deployment used,
  - the configured deadman threshold,
  - the configured `websocket` and `telnet` targets,
  - the configured targets and check identities for Prometheus, Alertmanager, Grafana, Kibana/log-query, and Jaeger/trace-query availability,
  - evidence that the authoritative external pager can fire without Prometheus,
  - evidence that mirrored Prometheus metrics match the external monitor state when Prometheus is healthy.

For early or hobby environments, this level of external monitoring may be intentionally reduced. This document is the contract for prod-like environments that claim FireMUD-grade monitoring, not a requirement that every local or throwaway stack must satisfy in full.

## Compatibility Mapping

If a hosted monitoring product cannot expose the canonical mirrored metric names directly, the environment must document a compatibility mapping that preserves:

- deadman freshness semantics,
- `path="websocket"` and `path="telnet"` semantics,
- independent external paging behavior,
  - the existing runbook behavior and alert naming described in the architecture docs.

Compatibility mappings are acceptable only for the Prometheus mirror. They are not a substitute for the authoritative external checks themselves.

Example compatibility mapping:

- External monitor native checks:
  - `synthetic.tcp.telnet.prod.status`
  - `synthetic.http.websocket.prod.status`
  - `heartbeat.firemud.prod.last_seen_unix`
- Prometheus mirror mapping:
  - `synthetic.tcp.telnet.prod.status -> entrypath_blackbox_probe_success{path="telnet",target="prod-telnet-edge"}`
  - `synthetic.http.websocket.prod.status -> entrypath_blackbox_probe_success{path="websocket",target="prod-web-gateway"}`
  - `heartbeat.firemud.prod.last_seen_unix -> observability_deadman_heartbeat_timestamp_seconds{source="prod"}`
- Required preserved semantics:
  - the external monitor still pages on its native checks without Prometheus,
  - `path="telnet"` and `path="websocket"` remain canonical in mirrored views,
  - runbooks and shared alert naming continue to reference the canonical FireMUD contract rather than the vendor-native signal names.
