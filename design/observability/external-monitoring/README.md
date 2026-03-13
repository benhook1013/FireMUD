# External Monitoring Contract

This document is the canonical design source for FireMUD monitoring that must continue to detect outages when the in-cluster Prometheus and Alertmanager stack is degraded or fully unavailable.

It complements the Prometheus-facing observability contracts in `design/architecture/system-architecture-logging-monitoring.md`.

## Purpose

FireMUD uses two distinct observability layers in prod-like environments:

- **Authoritative external paging path**
  - Runs outside the Prometheus + Alertmanager failure domain.
  - Is responsible for paging on total observability-stack outages and total public-edge outages.
- **Prometheus mirror**
  - Receives a low-cardinality mirror of selected external-monitor state so Grafana dashboards, runbooks, and smoke tests can show the same conditions when Prometheus is healthy.
  - Must never be treated as the only source of truth for the external checks.

If an implementation cannot satisfy the authoritative external paging path, the environment must not be described as meeting FireMUD’s prod-like monitoring contract.

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

## Prometheus Mirror Contract

When Prometheus is healthy, the external monitoring state must also be mirrored into low-cardinality metrics so dashboards and runbooks can use a single vocabulary:

- `entrypath_blackbox_probe_success{path,target}`
  - Mirror of the authoritative external entry-path reachability result.
  - `path` is a bounded enum and must use `websocket` and `telnet`.
  - `target` identifies the externally probed endpoint and must remain low-cardinality.
- `observability_deadman_heartbeat_timestamp_seconds{source}`
  - Mirror of the latest externally observed in-cluster heartbeat timestamp.
  - `source` identifies the emitting environment or monitor instance and must remain low-cardinality.

Prometheus alerts and dashboards may use these mirrored metrics, but the external monitor must page independently using its own native checks and thresholds.

## Ownership and Evidence

- `owner="platform"` is responsible for the external monitoring configuration, routing, and periodic validation.
- Prod-like readiness evidence must record:
  - the external monitoring product or deployment used,
  - the configured deadman threshold,
  - the configured `websocket` and `telnet` targets,
  - evidence that the authoritative external pager can fire without Prometheus,
  - evidence that mirrored Prometheus metrics match the external monitor state when Prometheus is healthy.

## Compatibility Mapping

If a hosted monitoring product cannot expose the canonical mirrored metric names directly, the environment must document a compatibility mapping that preserves:

- deadman freshness semantics,
- `path="websocket"` and `path="telnet"` semantics,
- independent external paging behavior,
- the existing runbook behavior and alert naming described in the architecture docs.

Compatibility mappings are acceptable only for the Prometheus mirror. They are not a substitute for the authoritative external checks themselves.
