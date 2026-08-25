# ADR 0159: Profile-Dependent Independent Deadman and Public-Path Monitoring

## Status

Accepted

## Implementation Status

This decision is not implemented. Profile-aware independent monitoring, public-path probes, explicit detection budgets, and bounded failure/recovery proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `OBS-05`
- Decision date: 2026-07-20
- Decision key: `OBS-05`
- Primary capability: `PO-4.4` smoke, canary, incident evidence, and architecture conformance proof
- Affected capabilities: `PO-2.1`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of total monitoring failure, public gameplay-path detection, independent paging, evidence freshness, hosted availability claims, and small-deployment burden

## Context

Prometheus and Alertmanager cannot independently report a failure that removes their own cluster, network path, storage, or alert-routing authority. Mirroring a deadman signal into Prometheus is useful for dashboards and incident correlation while Prometheus is healthy, but it cannot establish that an alert will escape the failed environment.

FireMUD also has two materially different public gameplay paths. An in-cluster heartbeat can prove that a producer and monitor loop are still active without proving that a player can reach and use the public browser/WebSocket or Telnet edge. Conversely, a single shallow HTTP check does not establish that both gameplay transports or the in-cluster monitoring path are functioning.

The required assurance depends on the deployment profile. A hosted production service that claims externally verified availability needs detection outside the environment's failure domain. A hobby, single-node, or otherwise small deployment may reasonably accept manual detection and a common failure domain instead of operating independent monitoring infrastructure. Requiring the hosted posture everywhere would add disproportionate cost and setup burden without changing the smaller deployment's basic availability promise.

## Decision

### Canonical Monitoring-Profile Enum

The serialized monitoring-profile enum is defined here and is shared verbatim by retained evidence (`externalAuthority.profile`), Prometheus canary and deadman labels (`profile`), and profile-aware monitoring rules:

- `independent-required` — the deployment claims independent external detection, public-path coverage, and off-cluster paging.
- `independent-omitted` — the deployment explicitly accepts common-failure-domain or operator-dependent detection and makes no independent availability claim.

The unqualified words “required” and “omitted” are prose descriptions only; they are not alternate serialized profile values.

### Independent Monitoring Is Required for Hosted External-Availability Claims

A hosted production deployment profile that claims externally verified availability or monitoring-resilient readiness must operate an off-cluster monitoring and paging path outside the monitored environment's ordinary cluster and observability failure domain.

That independent path monitors all of the following:

- freshness of the canonical in-cluster heartbeat or deadman signal; and
- every path in the profile's complete `exposedPublicPlayerPaths` set, using the real externally routed first-party browser/WebSocket path for `websocket` and the real public Telnet gameplay path for `telnet`.

The profile must declare the complete bounded path set (`websocket` and/or `telnet`) before claiming independent public-path coverage. A non-exposed path is recorded as `not_applicable`; an exposed path omitted from the monitor configuration or retained evidence is a coverage failure, not an implicit omission. The public-path checks exercise the actual externally routed paths rather than only cluster-local Services or actuator endpoints. The resulting failure notification is routed through an off-cluster paging authority so a total in-cluster monitoring, edge, or cluster failure remains externally visible.

No hosted profile may claim this assurance merely because it emits the expected metrics, installs Prometheus alert rules, or stores a successful historical probe record. It must retain current evidence that the independent monitor, public probes, deadman freshness evaluation, and paging destination are operating.

### Detection Budget Is Explicit and Configurable

The default heartbeat interval is 60 seconds and the default stale threshold is 180 seconds, representing three missed expected intervals. These are defaults rather than universal constants.

Each deployment profile using independent monitoring records its configured heartbeat interval, stale threshold, expected probe cadence, and resulting maximum detection budget. A deployment may choose tighter or looser values for its environment, but it must make the resulting detection promise explicit and validate freshness against those configured values. Changing the cadence without updating the corresponding threshold, evidence, and operational claim is non-compliant.

Whenever `playerFlowCanary` is advertised, retained evidence must also carry the exact positive `externalAuthority.detectionBudgetSeconds` for that profile, including `independent-omitted`. For `independent-required`, that value participates in the independent-monitoring freshness contract; for `independent-omitted`, it governs only local canary timing and does not create deadman, public-path, or other external-monitoring authority.

### Monitoring Components Need Not Be Publicly Reachable

This decision does not require public or off-cluster network reachability to Prometheus, Grafana, Kibana, Jaeger, the OpenTelemetry Collector, or Alertmanager. Those components may remain private to the environment.

The independent contract is the externally observable heartbeat/deadman outcome, the real public gameplay paths, and off-cluster notification. Implementations may export or push the minimum bounded signals needed for that contract without turning dashboards, query APIs, trace stores, or alert-management interfaces into public surfaces.

### Small and Hobby Profiles May Explicitly Omit Independent Infrastructure

Hobby/self-hosted, single-node, and other small deployment profiles may omit the independent off-cluster monitor and pager. Preflight records that posture and emits an explicit degraded-detection warning; omission alone does not block apply or player traffic.

An omitted profile cannot claim:

- independent detection of total cluster or monitoring-stack failure;
- externally verified public-path availability;
- off-cluster paging; or
- monitoring-resilient readiness.

Its operational documentation and status surfaces must describe detection as operator-dependent or common-failure-domain monitoring rather than silently inheriting the hosted production assurance. It may later adopt the independent profile without changing gameplay service contracts.

### Prometheus Is a Mirror, Not the Independent Authority

Prometheus may mirror deadman freshness and external public-probe outcomes for dashboards, alert correlation, SLO views, and runbooks. That mirror is a convenience view while the in-cluster monitoring stack is healthy. It is not the authoritative evidence that independent paging works, and loss of the mirror does not erase the off-cluster monitor's current state.

The independent monitor and pager retain their own freshness and delivery evidence outside the monitored failure domain. In-cluster consumers must not convert a stale Prometheus mirror into a false claim that the external authority is healthy.

### No Monitoring Vendor Is Mandated

FireMUD does not require a specific external monitoring or paging vendor. A managed service, separately hosted blackbox system, independent operator service, or another implementation is acceptable when it is outside the monitored failure domain and satisfies the heartbeat, public-path, freshness, paging, and evidence contracts.

Vendor selection does not change the required deployment-profile claim or allow the independent path to depend on the same cluster, network boundary, credentials, or alert-routing authority it is intended to detect as failed.

### Detection Does Not Grant Broad Recovery Authority

Kubernetes remains responsible for ordinary in-cluster self-healing: removing unready pods from traffic, replacing failed pods, rescheduling after ordinary node loss, and applying the approved rollout strategy. The off-cluster monitor detects and pages; it does not gain general cluster-administrator or database-recovery authority merely because it can observe an outage.

A hosted profile may later add a minimal external remediation runner when measured recovery time or one-operator availability requires unattended non-destructive repair. That runner may invoke only predeclared, idempotent actions such as provider-supported node or cluster repair, reapplying the exact attested manifests and digests, starting a replacement boundary in quarantine, and running preflight, health, and smoke checks. It uses least-privilege credentials, bounded retries, cooldowns, durable audit, and a kill switch. A deployment claiming unattended recovery from total cluster loss must place the required invocation path outside that cluster, but it may use CI, provider automation, a managed service, serverless execution, or a small independent host rather than another Kubernetes cluster.

The runner does not infer that unreachability means data corruption, select or apply a PostgreSQL rewind, create a competing live gameplay authority, destroy durable storage, or switch player traffic to a replacement boundary. Those actions require the recovery authorization, fencing, candidate proof, and controlled-reopen contract. A second management cluster or warm/active standby gameplay cluster is not part of the baseline. Adopting one requires a separate RPO/RTO and multi-cluster authority decision covering old-boundary fencing, replicated durable state, Redis/session treatment, external effects, routing, failback, credentials, and split-brain proof.

## Consequences

- Hosted production availability claims remain detectable during total in-cluster observability or edge failure.
- Browser/WebSocket and Telnet failures are checked through their real public routes rather than inferred from internal process health.
- Prometheus retains useful mirrored signals without becoming circular proof of its own availability.
- Monitoring dashboards and query systems can remain private.
- The explicit interval, threshold, and detection budget make the claimed response time reviewable and testable.
- Hobby and small operators can run FireMUD without a second monitoring environment, but must accept and expose the weaker detection posture.
- Multiple acceptable implementations avoid vendor lock-in while still requiring genuine failure-domain independence.
- Hosted operators incur the cost of external probes, paging, evidence retention, and periodic failure testing as a condition of the stronger availability claim.
- External detection does not create a second broad production control plane or allow monitoring failure signals to authorize destructive recovery.

## Alternatives Considered

### Require Independent Monitoring for Every Player-Facing Deployment

Rejected because a second monitoring and paging failure domain is disproportionate for hobby, single-node, and small deployments that do not claim hosted external-availability assurance. Their weaker posture is made explicit instead of blocking traffic.

### Rely Only on In-Cluster Prometheus and Alertmanager

Rejected for hosted external-availability claims because the alerting system cannot reliably report a failure that removes its own cluster, network path, storage, or routing authority.

### Probe Only an In-Cluster Heartbeat

Rejected because heartbeat freshness does not prove that players can traverse the actual public browser/WebSocket and Telnet paths.

### Probe Only Public HTTP or Gameplay Endpoints

Rejected because public reachability alone does not establish that the in-cluster monitoring heartbeat remains current or that its total failure will be detected independently.

### Expose the Entire Observability Stack Externally

Rejected because independent detection needs only bounded monitor outcomes and notification. Public Prometheus, Grafana, Kibana, Jaeger, collector, or Alertmanager surfaces would add unnecessary exposure and authentication burden.

### Mandate One External Monitoring Vendor

Rejected because the architecture requires failure-domain independence and measurable behavior, not a particular commercial implementation.

## Implementation and Proof Obligations

Select and report the required checks and evidence under the shared [Validation and Runtime Proof](../../developer-workflows/validation-and-runtime-proof.md) workflow; record execution results in PR/CI evidence or the owning implementation tracker rather than in this decision record.

Define deployment-profile configuration for independent monitoring, including whether it is required or omitted, the complete `exposedPublicPlayerPaths` set, heartbeat interval, stale threshold, public-probe cadence, maximum detection budget, external monitor identity, paging evidence identity, and the degraded-detection claim exposed when omitted. The configuration and retained evidence must identify every exposed path, record non-exposed paths as `not_applicable`, and reject a stronger assurance claim when any exposed path lacks its real public probe.

For hosted production profiles claiming the stronger assurance, implement an off-cluster monitor that evaluates the canonical in-cluster heartbeat plus every real public gameplay path declared in `exposedPublicPlayerPaths`. Retain current evidence of probe execution for each exposed path, explicit `not_applicable` results for non-exposed paths, heartbeat freshness, monitor health, paging-route validation, failure and recovery timestamps, and the configured detection budget outside the monitored environment. Evidence must expire when the monitor, probe, route, or paging validation becomes stale.

Preflight must fail the stronger hosted assurance when required independent monitoring configuration or current evidence is absent. For hobby, single-node, and small profiles that omit it, preflight records the omission and degraded-detection warning without blocking traffic. No generated report or user-facing readiness claim may label the omitted posture as independently monitored.

Proof must cover in-cluster heartbeat loss; complete Prometheus and Alertmanager loss; each exposed public path failing with the other exposed paths healthy; explicit non-exposed-path `not_applicable` evidence; an exposed path omitted from monitor configuration; total cluster or edge loss; external monitor failure; paging delivery failure; stale evidence; configured cadence and threshold changes; Prometheus mirror disagreement; private observability components; a conforming hosted production profile; and a conforming non-blocked hobby or single-node profile with explicit degraded detection.

Current implementation is partial. FireMUD has player-flow smoke tooling, mirrored metric vocabulary, Prometheus alert rules, retained-evidence validation, and documented external deadman behavior. The authoritative off-cluster monitor and paging deployment remain environment-specific and are not checked in or proved as a complete hosted production path. Current repository evidence does not establish ongoing freshness, real external failure-domain separation, or successful off-cluster page delivery for a live production environment.

## Reversibility and Revisit Triggers

Probe implementations, vendors, intervals, thresholds, evidence formats, and deployment-profile names may evolve while preserving explicit detection budgets and honest assurance claims. Revisit the optional small-deployment posture if a supported profile begins advertising managed availability or monitoring-resilient readiness. Revisit the hosted contract if measured incident detection shows that the heartbeat or public probes do not cover a material player-facing failure mode.

## Required Documentation Alignment

- [design/architecture/system-architecture-logging-monitoring.md](../system-architecture-logging-monitoring.md)
- [design/architecture/system-architecture-observability-incident-runbook.md](../system-architecture-observability-incident-runbook.md)
- [design/architecture/system-architecture-player-experience-incident-runbook.md](../system-architecture-player-experience-incident-runbook.md)
- [design/architecture/infrastructure/deployment-environments.md](../infrastructure/deployment-environments.md)
- [design/observability/external-monitoring/README.md](../../observability/external-monitoring/README.md)
