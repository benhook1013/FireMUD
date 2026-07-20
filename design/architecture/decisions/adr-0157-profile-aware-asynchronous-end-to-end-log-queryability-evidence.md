# ADR 0157: Profile-Aware Asynchronous End-to-End Log Queryability Evidence

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `OBS-06`
- Disposition: `revised`
- Primary capability: `PO-4.4` smoke, canary, incident evidence, and architecture conformance proof
- Affected capability: `PO-4.1` logging, metrics, tracing, dashboards, and alerting
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of end-to-end log-query proof, observability degradation, hosted and small-deployment assurance, query credentials, backend compatibility, and release-gate cost

## Context

Structured log emission alone does not establish that an operator can investigate an incident. A record may be correctly emitted but lost or made unqueryable by collector configuration, forwarding, authentication, parsing, index routing, mappings, storage, or the supported query path. FireMUD therefore needs proof across the complete deployed pipeline rather than stopping at logger output or collector health.

That proof must not contradict the observability degradation boundary. Elasticsearch, Kibana, and compatible query backends are soft runtime dependencies. A query-backend incident must not make a healthy process fail liveness, remove gameplay-ready pods, close player traffic, or put a synchronous query into the gameplay path.

The assurance burden also differs by deployment profile. Hosted production can reasonably require current indexed-query evidence before claiming observability readiness or promoting a release. A hobby or small deployment may intentionally use console or journal logs and omit indexed search rather than operating an Elasticsearch-class backend.

## Decision

### Queryability Is Proved End to End

An asynchronous synthetic check emits a uniquely identifiable structured record with a known `traceId`, then verifies that the same record passes through the configured collector or forwarder, reaches the selected index or storage backend, and is retrievable through the supported operator query path.

The check verifies the fields expected for the exercised path, including `service`, `traceId`, and applicable request or gameplay context. Success at an intermediate stage does not substitute for the final supported query.

The default starting target is queryability within two minutes of emission. The value is configurable by deployment profile and backend. It is a starting operational target, not an immutable cross-platform constant; the configured target and observed result are retained with the evidence.

### Hosted Assurance Uses Asynchronous Release Evidence

A hosted deployment that claims indexed-log observability keeps current end-to-end queryability evidence. Missing, expired, or failing evidence blocks the applicable promotion or release and prevents an observability-readiness claim until the asynchronous check succeeds.

This evidence never participates in process liveness, Kubernetes pod readiness, gameplay-path readiness, or player-traffic admission. The check runs outside gameplay request processing, and no command, login, session, tick, or moderation action waits for a log-backend query.

If the query backend becomes unavailable while the environment is running, FireMUD reports explicit degraded observability and raises the applicable operational signal. It does not shut down otherwise safe gameplay solely because indexed search is unavailable.

### Small Deployments May Use a Reduced Queryability Profile

Hobby, single-node, and other small deployments may satisfy their selected logging posture by proving that synthetic records are retrievable through their supported console or journal path. They may instead declare indexed log search unavailable.

An environment that omits indexed search does not synthesize successful indexed-query evidence or claim hosted indexed-log observability. The omission and resulting operator limitation are explicit, but omission alone does not block player traffic.

### Query Access Is Narrowly Scoped

The synthetic check uses query credentials scoped only to the required environment, index or log stream, fields, and read operations. The credentials do not grant broad administrative access, cross-environment access, or mutation authority. Evidence does not retain credential material.

Queries use the synthetic `traceId` and expected service context so proof remains bounded and does not require broad browsing of player logs. Tenant or gameplay context is verified only when the exercised path is expected to emit it and the query identity is authorized for that environment.

### The Backend Is Replaceable

`firemud-logs-*` is the default indexed-log convention for the Elasticsearch/Kibana profile. Another backend is conforming when it documents a compatibility mapping and proves the same emitter-to-query behavior, field retrieval, configured delay, scoped access, and degraded-state semantics.

The evidence contract depends on supported query behavior rather than an Elasticsearch-specific client embedded into gameplay services.

## Consequences

- Hosted observability claims are backed by proof that operators can retrieve real deployed records, not merely by logger configuration or backend health.
- A broken forwarding, indexing, mapping, or query layer fails the same evidence check.
- The two-minute starting target may add bounded time to promotion or release evidence, but never to gameplay requests.
- Runtime loss of indexed search is visible as degraded observability without turning a soft backend into a gameplay dependency.
- Hobby and small operators can use console or journal logging without deploying a full indexed-search stack, provided the reduced posture is explicit.
- Scoped query credentials and synthetic identifiers reduce the access needed for automation but add credential provisioning and rotation work.
- Backend compatibility avoids a permanent Elasticsearch/Kibana requirement while retaining one behavioral proof contract.

## Alternatives Considered

### Treat Structured Logger Output as Sufficient Proof

This cannot detect forwarding, parsing, indexing, mapping, storage, or query-path failures. It is rejected as incomplete evidence.

### Treat Collector or Backend Health as Sufficient Proof

A healthy component endpoint does not prove that a specific application record is delivered and retrievable. Component health remains useful diagnostics but does not replace the synthetic record.

### Make Indexed Queryability a Gameplay Readiness Dependency

This would turn an observability outage into a gameplay outage and contradict the soft-dependency boundary. Queryability is asynchronous promotion, release, and observability-readiness evidence only.

### Require Elasticsearch and Kibana for Every Deployment

This provides one uniform path but imposes disproportionate infrastructure on hobby and small deployments and creates unnecessary backend lock-in. Profile-aware console, journal, and compatible indexed backends are supported instead.

### Run a Synchronous Query During Each Gameplay Action

This would add latency, cost, failure coupling, and sensitive query access to the gameplay path. It is rejected.

## Implementation and Proof Obligations

Current implementation is partial. Critical paths emit the first bounded set of structured identity and correlation fields, and static validation checks the expected fields in saved query objects. Broad context coverage remains incomplete in some paths.

There is no implemented runtime check that emits a known synthetic record, retains its `traceId`, polls the configured backend, and proves retrieval through the supported query path within the configured target. Existing player-experience evidence can record a supplied log-query status but does not itself perform that emit-and-query proof.

Logging & Admin's current `LogQueryServiceImpl` and `LogEventRepository` query the PostgreSQL `log_events` domain table. That surface is distinct from the deployed structured-log pipeline and does not prove collector forwarding, `firemud-logs-*` indexing, Kibana or compatible backend queryability, or indexing delay.

Implementation must provide profile-aware asynchronous emit-and-query automation, configurable delay and evidence freshness, narrowly scoped read credentials, explicit degraded-observability reporting, and compatibility mapping for non-default backends. It must integrate only with hosted promotion, release, and observability-readiness evidence or the selected small-deployment posture.

Focused proof must cover successful retrieval; collector or forwarder loss; authentication failure; malformed parsing or mapping; wrong index or environment; indexing delay beyond the configured target; query-backend outage; expired evidence; cross-environment and unauthorized query rejection; runtime degradation without gameplay readiness loss; console or journal proof for a small deployment; explicit indexed-search omission; and a compatible non-default backend. It must also prove that gameplay processing performs no synchronous backend query and continues safely when the indexed-log backend is unavailable.

## Reversibility and Revisit Triggers

The query backend, index convention, configured delay, evidence cadence, and deployment-profile requirements may change while preserving asynchronous end-to-end proof and the soft runtime dependency. Revisit the two-minute starting target after measured indexing and incident-response evidence. Revisit a profile's requirement when its operational assurance claim changes, not by coupling the backend to gameplay readiness.

## Required Documentation Alignment

- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-observability-incident-runbook.md`
- `design/architecture/system-architecture-testing.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/observability/README.md`
- `design/observability/kibana/`
- `services/logging-admin-service` architecture documentation
