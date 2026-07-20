# External Monitoring Contract

This document defines the optional stronger FireMUD monitoring profile that continues to detect and page on outages when the in-cluster Prometheus and Alertmanager stack is degraded or unavailable.

It complements the Prometheus-facing observability contract in `design/architecture/system-architecture-logging-monitoring.md`.

## Implementation Status

This is the target-state contract for hosted production profiles that claim externally verified availability or monitoring-resilient readiness. The repository provides a runtime smoke harness, retained-evidence validation, and a shared mirrored metric vocabulary. It does not ship the authoritative off-cluster monitor or pager. Hobby, single-node, and small profiles may explicitly omit that infrastructure; preflight warns and records their degraded detection posture without blocking player traffic.

## What This Is

FireMUD uses two observability layers when a deployment selects the stronger independent-monitoring profile:

- **Authoritative external pager**
  - Runs outside the cluster and outside the Prometheus + Alertmanager failure domain.
  - Must still page operators when the public edge is down or the in-cluster heartbeat is stale.
- **Prometheus mirror**
  - Mirrors selected external-monitor state into low-cardinality Prometheus metrics.
  - Exists so Grafana dashboards, runbooks, and smoke tests can use a shared vocabulary when Prometheus is healthy.

The external pager is the real source of truth. The Prometheus mirror is a convenience view. If an environment has only mirrored metrics and no independent external pager, it does not satisfy this stronger contract. It may still be a valid small or hobby deployment when the weaker posture is explicit.

## Required External Checks

Profiles claiming independent monitoring must configure the following checks in the authoritative external monitoring system.

### 1. Deadman Heartbeat Freshness

- An in-cluster heartbeat emitter must publish a timestamp to the external monitor.
- The external monitor must page when the heartbeat exceeds the profile's configured stale threshold.
- Default contract:
  - `heartbeat_interval_seconds = 60`
  - page when stale for more than `180s`

Each profile records its actual heartbeat interval, stale threshold, probe cadence, and resulting maximum detection budget. The defaults are configurable rather than permanent constants.

This check exists so FireMUD can still detect a broad cluster or observability-stack failure even when Prometheus cannot evaluate internal rules.

### 2. Public Player Entry-Path Reachability

The external monitor must probe the real public player entry paths:

- `path="websocket"` for the browser / Gateway path
- `path="telnet"` for the TCP Proxy path

These checks must validate the real path handshake, not just TCP port-open status.

This check exists so FireMUD can detect public-edge outages independently of internal monitoring health.

### 3. Private Observability Diagnostics

Prometheus, Alertmanager, Grafana, Kibana, Jaeger, and collector endpoints do not need to be externally reachable. Provider-native or in-cluster checks may diagnose them. The independent external contract is limited to heartbeat freshness, the two real public gameplay paths, and off-cluster page delivery.

## Prometheus Mirror

When Prometheus is healthy, selected external-monitor state may be mirrored into low-cardinality metrics so dashboards and runbooks can show the same conditions with stable names.

Canonical mirrored metrics:

- `entrypath_blackbox_probe_success{path,target}`
  - Mirrors the authoritative external result for public entry-path reachability.
  - `path` is a bounded enum and must use `websocket` and `telnet`.
  - `target` identifies the externally probed endpoint and must remain low-cardinality.
- `observability_deadman_heartbeat_timestamp_seconds{source}`
  - Mirrors the latest externally observed in-cluster heartbeat timestamp.
  - `source` identifies the environment or monitor instance and must remain low-cardinality.

Prometheus alerts and dashboards may use these mirrored metrics, but the external monitoring system must still page independently using its own native checks and thresholds.

## Ownership and Evidence

- `owner="platform"` is responsible for external monitoring configuration, routing, and periodic validation.

Independent-monitoring readiness evidence must record:

- the external monitoring product or deployment used
- the configured deadman threshold
- the configured `websocket` and `telnet` targets
- evidence that the authoritative external pager can fire without Prometheus
- when mirrored Prometheus metrics are provided, evidence that they match the external monitor state while Prometheus is healthy

Retained evidence for the stronger profile must identify the configured detection budget and the three required independent outcomes. Illustrative target shape:

```json
{
  "profile": "independent-required",
  "heartbeatIntervalSeconds": 60,
  "staleThresholdSeconds": 180,
  "deadmanAuthority": {
    "status": "green",
    "evidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z",
    "target": "staging-deadman-authority",
    "checkRef": "check://staging/deadman"
  },
  "publicPathChecks": {
    "websocket": {
      "status": "green",
      "evidenceRef": "probe://staging/websocket/2026-03-19T10:51:00Z",
      "target": "staging-websocket"
    },
    "telnet": {
      "status": "green",
      "evidenceRef": "probe://staging/telnet/2026-03-19T10:51:00Z",
      "target": "staging-telnet"
    }
  }
}
```

Only simulation may synthesize green authority. Real hosted-assurance smoke points at current retained external evidence. Omitted profiles instead record `profile: independent-omitted` and the reason for the degraded detection posture; they do not manufacture an authority object.

Freshness decision at the current repository boundary:

- the current repository runner still validates a legacy external-authority shape that also requires observability-entrypoint checks; aligning it to the narrower target contract is implementation work;
- the repository does not currently parse or freshness-validate authoritative timestamps from `evidenceRef` or `checkRef`, because those references are intentionally product-specific opaque handles owned by the external monitoring system;
- choosing retained evidence that is contemporaneous with the smoke execution window remains an environment readiness obligation and must be documented in the environment’s own monitoring evidence.

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

This is the contract for hosted production profiles that claim externally verified availability or monitoring-resilient readiness. Local, preview, hobby, single-node, and small deployments may explicitly omit it. Their preflight and status surfaces must expose the weaker posture, and they must not describe themselves as independently monitored.
