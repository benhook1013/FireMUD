# External Monitoring Contract

This document defines the optional stronger FireMUD monitoring profile that continues to detect and page on outages when the in-cluster Prometheus and Alertmanager stack is degraded or unavailable.

It complements the Prometheus-facing observability contract in `design/architecture/system-architecture-logging-monitoring.md`.

The stronger profile is governed by [ADR 0159: profile-dependent independent deadman and public-path monitoring](../../architecture/decisions/adr-0159-profile-dependent-independent-deadman-and-public-path-monitoring.md).

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

Each `independent-required` profile records its actual heartbeat interval, stale threshold, probe cadence, timeout values where applicable, ingestion/observation delay, evaluation cadence, and resulting maximum detection budget. Retained `independent-required` evidence includes the last successful heartbeat/probe observation timestamp and the timestamp at which the evidence was observed. A source observation timestamp must not be later than `evidenceObservedAt`; validators reject that chronology error before computing age by direct subtraction, without clamping negative ages to zero. `observedStalenessSeconds` is the nonnegative result of the valid difference between `evidenceObservedAt` and `lastSuccessfulHeartbeatObservedAt`, within the established numeric tolerance. A green deadman is valid only when that observed staleness is no greater than the positive finite `staleThresholdSeconds`. Each exposed public-path record must carry a nonnegative finite `observedProbeAgeSeconds` equal, within the established numeric tolerance, to `evidenceObservedAt - lastSuccessfulProbeObservedAt`; a green path is valid only when that age is no greater than the profile's `detectionBudgetSeconds`. The `independent-required` profile's `detectionBudgetSeconds` is derived from the declared heartbeat interval and timeout, probe cadence and timeout, stale threshold, ingestion/observation delay, and evaluation cadence; it remains the outer retained-evidence transport-freshness bound rather than the green-deadman threshold, and is not a universal constant.

Whenever `playerFlowCanary=advertised`, the profile timing declaration must also record every input used to derive its positive `detectionBudgetSeconds` and the reproducible formula: canary cadence, execution timeout, observation/ingestion delay, and evaluation cadence are required for `independent-omitted`, while `independent-required` uses the external timing inputs above as its authoritative budget. For `independent-omitted`, external heartbeat, deadman, and public-path timing inputs are not applicable; the resulting budget is local canary timing only, but it is still retained in `externalAuthority.detectionBudgetSeconds` and mirrored exactly by `playerflow_canary_freshness_budget_seconds`. The omitted profile must not add non-applicable external fields to its retained authority object.

This check exists so FireMUD can still detect a broad cluster or observability-stack failure even when Prometheus cannot evaluate internal rules.

### 2. Public Player Entry-Path Reachability

Each profile declares the complete `exposedPublicPlayerPaths` list from the bounded set `websocket` and `telnet`, without duplicates. For an `independent-required` profile, retained `publicPathChecks` must contain exactly both bounded paths: exposed paths have green real-probe records with per-check `pageEvidenceRef`, `lastSuccessfulProbeObservedAt`, and `observedProbeAgeSeconds`, while non-exposed paths have exactly `{ "status": "not_applicable" }` and no page reference or freshness fields.

The external monitor must probe every path the profile actually exposes:

- `path="websocket"` for an exposed browser / Gateway path
- `path="telnet"` for an exposed TCP Proxy path

These checks must validate the real path handshake, not just TCP port-open status. A non-exposed path is recorded as `not_applicable` and is excluded from the profile's availability claim; omitting a probe for an exposed path fails independent-monitoring readiness.

This check exists so FireMUD can detect public-edge outages independently of internal monitoring health.

## Optional Private Observability Diagnostics

Prometheus, Alertmanager, Grafana, Kibana, Jaeger, and collector endpoints do not need to be externally reachable. Provider-native or in-cluster checks may diagnose them. The independent external contract is limited to heartbeat freshness, the profile's declared exposed public gameplay paths, and off-cluster page delivery.

## Prometheus Mirror

When Prometheus is healthy, selected external-monitor state may be mirrored into low-cardinality metrics so dashboards and runbooks can show the same conditions with stable names.

Canonical mirrored metrics:

- `entrypath_blackbox_probe_success{path,target}`
  - Mirrors the authoritative external result for public entry-path reachability.
  - `path` is a bounded enum and uses `websocket` or `telnet` only when that path is exposed by the profile.
  - `target` is the bounded logical ingress enum (`gateway` or `tcp_proxy`) and must remain low-cardinality; the provider-native endpoint, product, and deployment identities remain in retained external evidence rather than Prometheus labels.
- `observability_deadman_heartbeat_timestamp_seconds{source}`
  - Mirrors the latest externally observed in-cluster heartbeat timestamp.
  - `source` is a bounded environment or heartbeat-observed label, not the exact external monitor identity; the exact monitoring product/deployment remains in retained evidence.
- `observability_deadman_stale{profile}`
  - Boolean-like mirror emitted by the independent monitor after applying that profile's configured stale threshold and evaluation window. It is optional only when the required-profile Prometheus overlay is not installed; a profile using `independent-required-prometheus-published` must emit it because that overlay evaluates its absence fail-closed.
  - Prometheus deadman alerts gate on `profile="independent-required"`; an `independent-omitted` profile does not emit a green authority or trigger the required-profile alert.

The independent monitor owns the `observability_deadman_stale` decision: it emits and updates that gauge only after applying the profile timing. The smoke runner never synthesizes or freezes the stale decision. Select `prometheusMirrors=published` only when the environment separately publishes this gauge and installs the matching overlay; the runner's `observability_deadman_heartbeat_timestamp_seconds` is a diagnostic timestamp mirror and does not derive the stale decision. This separate publication and overlay selection are deployment/monitor-owned and do not add `observability_deadman_stale` to the retained player-experience smoke evidence schema; the evidence validator does not require or derive this gauge.

Prometheus alerts and dashboards may use these mirrored metrics, but the external monitoring system must still page independently using its own native checks and thresholds.

### Independent Capability Declarations

Retained player-experience smoke evidence declares optional capabilities separately from the external-monitoring profile:

```json
{
  "executionMode": "live",
  "externalAuthorityProvenance": "retained-external",
  "capabilities": {
    "prometheusMirrors": "published",
    "playerFlowCanary": "advertised"
  }
}
```

`prometheusMirrors` is declared as `published` or `omitted`, independently of `playerFlowCanary`, which is `advertised` or `omitted`. For `independent-required`, `prometheusMirrors=published` selects the `independent-required-prometheus-published` overlay and requires the `observability_deadman_stale{profile="independent-required"}` mirror; `prometheusMirrors=omitted` selects the corresponding overlay without that rule and requires the deadman mirror to be absent. An `independent-omitted` profile never emits the required-profile deadman mirror or alert. The external deadman requirement remains authoritative and is not weakened by omitting its Prometheus convenience mirror. An advertised player-flow canary runs and retains its complete login/command metric family, last-run timestamps, profile-derived freshness budget, and canonical alert-exercise records regardless of Prometheus mirror publication; an omitted canary retains none of those canary-specific records. For `independent-omitted`, `detectionBudgetSeconds` is canary timing only and is not external-monitoring authority. `externalAuthority.profile` is not a canary declaration. In particular, `independent-required` still requires the authoritative deadman and every exposed public-path result whether either optional capability is omitted.

`executionMode` is `live` for a network-executed smoke and `simulated` for `--simulate`. Live evidence must use `externalAuthorityProvenance="retained-external"`; simulation may use retained external input, but remains non-authorizing, while simulation without that input records `externalAuthorityProvenance="synthetic"`. Synthetic authority references are valid only in simulated evidence and must not be consumed as promotion, recovery, or traffic-open authority.

## Ownership and Evidence

- `owner="platform"` is responsible for external monitoring configuration, routing, and periodic validation.

Independent-monitoring readiness evidence must record:

- the external monitoring product or deployment used
- the configured deadman threshold
- the declared `exposedPublicPlayerPaths` set and the configured target for every exposed path, with non-exposed paths recorded as `not_applicable`
- evidence that the authoritative external pager can fire without Prometheus
- when mirrored Prometheus metrics are provided, evidence that they match the external monitor state while Prometheus is healthy

Retained evidence for the stronger profile must identify the configured detection budget, deadman and pager outcomes, and every declared exposed public-path outcome. Illustrative target shape for a profile exposing both paths:

```json
{
  "profile": "independent-required",
  "exposedPublicPlayerPaths": ["websocket", "telnet"],
  "monitoringProduct": "external-monitoring-product",
  "monitoringDeployment": "staging-deadman-monitor",
  "heartbeatIntervalSeconds": 60,
  "probeCadenceSeconds": 60,
  "heartbeatTimeoutSeconds": 15,
  "probeTimeoutSeconds": 15,
  "staleThresholdSeconds": 180,
  "ingestionObservationDelaySeconds": 10,
  "evaluationCadenceSeconds": 5,
  "detectionBudgetSeconds": 195,
  "evidenceObservedAt": "2026-03-19T10:50:00Z",
  "lastSuccessfulHeartbeatObservedAt": "2026-03-19T10:49:18Z",
  "observedStalenessSeconds": 42,
  "deadmanAuthority": {
    "status": "green",
    "evidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z",
    "pageEvidenceRef": "pager://staging/player-experience/2026-03-19T10:50:00Z/delivery",
    "target": "staging-deadman-authority",
    "checkRef": "check://staging/deadman"
  },
  "publicPathChecks": {
    "websocket": {
      "status": "green",
      "evidenceRef": "probe://staging/websocket/2026-03-19T10:49:40Z",
      "pageEvidenceRef": "pager://staging/websocket/2026-03-19T10:50:00Z/delivery",
      "target": "staging-websocket",
      "lastSuccessfulProbeObservedAt": "2026-03-19T10:49:40Z",
      "observedProbeAgeSeconds": 20
    },
    "telnet": {
      "status": "green",
      "evidenceRef": "probe://staging/telnet/2026-03-19T10:49:43Z",
      "pageEvidenceRef": "pager://staging/telnet/2026-03-19T10:50:00Z/delivery",
      "target": "staging-telnet",
      "lastSuccessfulProbeObservedAt": "2026-03-19T10:49:43Z",
      "observedProbeAgeSeconds": 17
    }
  }
}
```

For a retained `independent-required` profile, the derived budget must be reproducible from its declared values. A compatible target formula is `detectionBudgetSeconds = max(heartbeatIntervalSeconds + heartbeatTimeoutSeconds, probeCadenceSeconds + probeTimeoutSeconds, staleThresholdSeconds) + ingestionObservationDelaySeconds + evaluationCadenceSeconds`; profiles may document an equivalent formula when their monitor's timing model differs. An `independent-omitted` profile with an advertised player-flow canary must likewise document a reproducible canary-timing formula over all of its declared canary cadence, execution-timeout, observation/ingestion-delay, and evaluation-cadence inputs; that local budget does not establish external-monitoring authority. Timestamps are RFC 3339 UTC values. Every explicit source observation timestamp must be at or before `evidenceObservedAt`; after that chronology check, observed ages are computed by direct subtraction rather than `max(0, ...)` clamping.

Only simulation may synthesize green authority. Real hosted-assurance smoke points at current retained external evidence. Hobby/self-hosted, single-node, and other small profiles retain an explicit omitted record instead of manufacturing external authority:

```json
{
  "profile": "independent-omitted",
  "reason": "single-node deployment uses operator-dependent outage detection",
  "exposedPublicPlayerPaths": ["websocket"]
}
```

They do not include `deadmanAuthority`, `publicPathChecks`, or any other synthesized external authority fields in that omitted record. Hosted profiles claiming independent monitoring retain the external deadman, per-check page-delivery evidence, and complete bounded path-check map above. The canonical shape has no separate monitor-level pager-delivery field: each authoritative check carries its own `pageEvidenceRef`.

Freshness decision at the current repository boundary:

- the checked-in runner and validator consume this profile-aware shape; `publicPathChecks` replaces the obsolete observability-entrypoint checks, and `independent-omitted` is accepted without synthesizing green authority;
- readiness, promotion, and recovery consumers invoke the validator without `--allow-failure-evidence`; only green `independent-required` deadman and exposed-path records can authorize those gates, and each green authoritative record must carry its `pageEvidenceRef`;
- `--allow-failure-evidence` is an incident-evidence mode only. Pass it to the smoke runner when its retained authority source has a current red deadman or exposed-path outcome, and to the validator for the resulting artifact. It may retain those fresh red outcomes, zero-valued mirrored canary/path outcomes (with complete record coverage), and explicitly `not_exercised` canary-alert records, while keeping timestamps, reference provenance, chronology, and structural checks strict. Red or unexercised incident records do not authorize readiness; red authority records may omit `pageEvidenceRef` when no page-delivery observation exists;
- for `independent-required`, the checked-in runner and validator require the explicit `evidenceObservedAt` timestamp and reject future or over-budget evidence relative to the retained evidence evaluation timestamp `verifiedAt`;
- the repository does not parse or freshness-validate authoritative timestamps from `evidenceRef`, `pageEvidenceRef`, or `checkRef`, because those references are intentionally product-specific opaque handles owned by the external monitoring system; retained evidence must use real references, while synthetic references are allowed only for simulated evidence;
- choosing retained evidence that is contemporaneous with the smoke execution window remains an environment readiness obligation and must be documented in the environment’s own monitoring evidence.

## Compatibility Mapping

If a monitoring product cannot expose the canonical mirrored metric names directly, the environment must document a compatibility mapping that preserves:

- deadman freshness semantics
- the declared exposed `path="websocket"` and `path="telnet"` semantics
- independent external paging behavior
- existing runbook behavior and alert naming from the architecture docs

Compatibility mappings are acceptable only for the Prometheus mirror. They are not a substitute for the authoritative external checks themselves.

Illustrative mapping:

- External monitor native checks:
  - `synthetic.tcp.telnet.prod.status`
  - `synthetic.http.websocket.prod.status`
  - `heartbeat.firemud.prod.last_seen_unix`
- Prometheus mirror mapping:
  - `synthetic.tcp.telnet.prod.status -> entrypath_blackbox_probe_success{path="telnet",target="tcp_proxy"}`
  - `synthetic.http.websocket.prod.status -> entrypath_blackbox_probe_success{path="websocket",target="gateway"}`
  - `heartbeat.firemud.prod.last_seen_unix -> observability_deadman_heartbeat_timestamp_seconds{source="prod"}`

Required preserved semantics:

- the external monitor still pages on its native checks without Prometheus
- `path="telnet"` and `path="websocket"` remain canonical in mirrored views
- runbooks and shared alert naming continue to reference FireMUD’s canonical contract rather than vendor-native names

## Scope

This is the contract for hosted production profiles that claim externally verified availability or monitoring-resilient readiness. Local, preview, hobby, single-node, and small deployments may explicitly omit it. Their preflight and status surfaces must expose the weaker posture, and they must not describe themselves as independently monitored.
