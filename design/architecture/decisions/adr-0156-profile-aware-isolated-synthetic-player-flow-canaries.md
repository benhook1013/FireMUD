# ADR 0156: Profile-Aware Isolated Synthetic Player-Flow Canaries

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `OBS-03`
- Disposition: `revised`
- Primary capability: `PO-4.4` Smoke, canary, incident evidence, and architecture conformance proof
- Affected capabilities: `EA-3.1`, `PO-2.1`, `AA-1.1`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of public-path journey coverage, deployment profiles, synthetic identity isolation, analytics contamination, security treatment, alert severity, missing-run detection, and small-deployment burden

## Context

Infrastructure health and live-player traffic do not reliably detect every player-facing outage. A quiet environment may have no live attempts from which to calculate an availability ratio, while DNS, TLS, ingress, authentication, admission, or gameplay failures may occur beyond the boundary measured by process health.

FireMUD already has a bounded player-experience smoke harness that can execute a real login and `LOOK` through WebSocket or Telnet and emit mirrored canary signals. That substrate does not yet satisfy the intended production contract. One invocation exercises only one selected canary transport, its default credentials belong to the ordinary seeded demo player, the authoritative account model has no synthetic classification, and no checked-in continuous schedule or freshness/no-data proof establishes that the canary remains active.

Synthetic identities also need careful treatment. Excluding an account broadly from moderation or security would create a hidden bypass if its credentials were misused. Conversely, including repeated synthetic traffic in live-player analytics and SLO denominators would contaminate product and reliability reporting, especially in low-traffic environments.

[ADR 0154](./adr-0154-profile-dependent-independent-deadman-and-public-path-monitoring.md) makes independent public-path monitoring profile dependent. Synthetic player-flow requirements follow the same honest assurance boundary instead of imposing hosted monitoring infrastructure on every hobby deployment.

## Decision

### Hosted Player-Experience Claims Require Every Exposed Path

A hosted deployment profile that claims player-experience monitoring runs a complete synthetic journey through every exposed first-party player entry path:

- browser/WebSocket: authenticate through the public first-party bootstrap and connection path, enter gameplay, and execute the read-only `LOOK` command; and
- Telnet: authenticate through the real public Telnet path, enter gameplay, and execute the read-only `LOOK` command when Telnet is exposed for that deployment.

The journey uses real public routing and protocol boundaries rather than an actuator endpoint, cluster-local Service, port-open check, or direct internal API. `LOOK` is the canonical representative command because it proves authenticated in-session gameplay without intentionally mutating ordinary game state.

An unexposed transport has no canary obligation. The deployment profile and public-path inventory establish which journeys are applicable.

### Hobby and Small Profiles May Use a Weaker Posture

Hobby, single-node, and other small deployments may run the same canary locally, schedule it inside their existing failure domain, or omit continuous independent player-flow canaries.

Omission does not block ordinary hobby player traffic. The deployment must expose the weaker posture explicitly and must not claim continuous independent journey detection, externally verified player-flow availability, or off-cluster canary paging. A later move to the hosted monitoring profile does not change gameplay protocols or identity contracts.

### Each Transport Uses a Separate Restricted Character

Each monitored transport uses a separate restricted synthetic character and isolated canary state. A WebSocket canary and a Telnet canary do not share one playable character, because FireMUD's one-session-per-character behavior could otherwise make concurrent or overlapping probes replace each other and create false failures.

Canary characters have only the access needed to enter their designated safe canary realm or data set and execute the required read-only journey. Their world state is deterministic, resettable, and unsuitable for normal player progression, social participation, commerce, or privileged administration.

### Synthetic Status Is Not a Security or Moderation Bypass

Synthetic accounts and characters remain subject to normal:

- authentication and credential validation;
- connection and command abuse protection;
- authorization and gameplay admission;
- moderation enforcement;
- security monitoring; and
- durable audit.

Synthetic status does not grant gameplay capabilities, bypass bans or limits, suppress security evidence, or make account activity invisible to responders.

Validated synthetic traffic is excluded only from:

- product and engagement analytics;
- ordinary player-behavior interpretation or automated player-quality analysis; and
- live-player SLO denominators.

The exclusion uses an authoritative bounded synthetic classification rather than account IDs, character IDs, usernames, or other high-cardinality metric labels. Canary outcome and latency remain visible through their dedicated bounded canary signals.

### Canary Identity and Data Have an Explicit Lifecycle

Supported canary operation includes automated provisioning and verification of the synthetic account, transport-specific character, realm or data set, membership and admission, and least-privilege credentials. Credentials are stored and delivered through the applicable environment secret contract, rotated through the supported credential workflow, and never embedded as production defaults.

The lifecycle also supplies deterministic reset and repair for canary state, safe credential revocation, and cleanup when a monitored path or environment is retired. Reset must not turn the canary into an ordinary player identity or silently broaden its capabilities.

### Missing or Stale Execution Is an Alertable Failure

Canary monitoring distinguishes:

- a fresh journey result that passed or failed;
- a missing expected run;
- a stale last result; and
- a runner, credential, or canary-data failure that prevents a trustworthy journey result.

Every monitored path emits bounded result, latency, and freshness evidence. Alerting must detect absent or stale execution rather than relying only on a failure-valued success gauge, because a stopped runner can otherwise disappear without producing a failing sample.

Missing/stale-run alerts identify monitoring degradation and do not assert player outage without journey evidence. They remain actionable because a deployment cannot retain its hosted player-experience monitoring claim while required canary execution is stale.

### Severity Requires Sustained and Confirmed Evidence

One failed sample or failure of one synthetic identity is not immediately a P0 player incident. Alert evaluation uses the configured probe cadence and sustained-failure window, distinguishes identity/data/runner faults from service failure where evidence permits, and preserves the affected transport.

A sustained, fresh, confirmed failure of the complete login journey may be treated as P0 even when live traffic is absent. Confirmation does not require real-player reports; it requires trustworthy repeated journey evidence and a healthy enough monitoring path to establish that the failure is not merely a missing run or one broken synthetic identity.

Representative-command failure or excessive canary latency retains its separately configured gameplay-degradation severity. Canary incidents complement rather than replace live-player SLO and public-path blackbox signals.

### Labels and Independent Monitoring Stay Bounded

Canonical mirrored signals use bounded labels such as:

- `flow` from the fixed journey-step set;
- `path` from the exposed transport set; and
- `target` from the deployment's bounded monitored-endpoint set.

Canary account IDs, character IDs, tenant IDs, usernames, trace IDs, and credential identifiers are not Prometheus labels. Those details remain in protected structured evidence, logs, traces, or audit where needed for diagnosis.

Hosted profiles integrate canary scheduling, freshness evaluation, and notification with the independent monitoring posture from ADR 0154 so the canary does not depend entirely on the player environment it is meant to assess. Mirrored Prometheus signals remain useful for dashboards and correlation but are not proof that the independent execution and notification path is healthy.

## Consequences

- Hosted player-experience monitoring covers complete login and read-only gameplay through every exposed first-party transport.
- Separate transport characters prevent probe takeover and session-collision false positives.
- Synthetic activity cannot bypass authentication, abuse controls, moderation, security, or audit.
- Product analytics and live SLOs remain representative of real players rather than scheduled probes.
- Missing and stale canary execution become visible instead of failing silently.
- Sustained confirmed login failure can detect a serious outage during zero real-player traffic without paging P0 on one bad sample.
- Credential, character, data, reset, scheduling, and evidence lifecycles add operational work beyond a simple port probe.
- Hobby and small operators can avoid that continuous independent-monitoring cost while presenting an honest weaker detection posture.
- The runtime load of login plus `LOOK` is small, but probe frequency and path count remain bounded deployment inputs rather than unbounded synthetic traffic.

## Alternatives Considered

### Infer Player Availability From Process Health and Live Traffic

This misses quiet-period failures and outages before requests reach the services. Synthetic journeys remain required for hosted player-experience monitoring claims.

### Use One Character for WebSocket and Telnet

This reduces fixture count but can trigger the canonical one-session-per-character replacement behavior when probes overlap. Separate restricted characters provide deterministic transport isolation.

### Exempt Canaries From Moderation and Security

This could reduce false operational records but would create a hidden bypass if credentials were stolen or the canary executed unexpected actions. Exclusion is limited to product analytics, player-behavior interpretation, and live SLO denominators.

### Count Canaries as Ordinary Live SLO Traffic

Repeated successful probes can inflate low-volume success ratios, while probe-specific failure can depress them without representing ordinary player demand. Dedicated canary metrics preserve visibility without contaminating the live-player contract.

### Page P0 on the First Failed Sample

This detects quickly but makes credential, fixture, transient-network, or single-identity faults indistinguishable from total player outage. Sustained confirmed journey failure may page P0; isolated or stale-run failure uses its appropriate monitoring-degradation path.

### Require Continuous Independent Canaries for Every Hobby Deployment

This provides uniform assurance at disproportionate setup and paging cost for small deployments. Hobby profiles may use local or omitted canaries while giving up the corresponding assurance claim.

## Implementation and Proof Obligations

The current implementation is partial. `dev-tools/observability/run-player-experience-smoke.py` performs real WebSocket or Telnet `LOGIN -> PLAY -> LOOK` through `run_websocket_canary` and `run_telnet_canary`, but `--canary-path` selects only one full journey per invocation. Its defaults use the ordinary seeded demo credentials, Account has no authoritative synthetic classification, no checked-in schedule proves continuous execution for every exposed path, and the current success gauges and rules do not prove missing/stale-run detection. Existing contract tests prove simulated evidence shape and failure injection, not a live independently scheduled hosted deployment.

Implementation must:

- add authoritative bounded synthetic account/character classification and propagate it safely to analytics and SLO exclusion without weakening security or audit;
- provision separate restricted characters and deterministic canary data for every exposed monitored transport;
- automate credential provisioning, delivery, rotation, revocation, state reset, and retirement;
- schedule every applicable hosted journey through the real public path and retain fresh result, latency, runner-health, and last-run evidence;
- add explicit missing/stale execution detection and distinct diagnostics for journey, identity/data, credential, and runner failure;
- prevent synthetic events from entering product analytics, player-behavior interpretation, or live SLO denominators;
- integrate hosted execution and notification with the independent monitoring profile while allowing local or omitted hobby posture; and
- preserve bounded metric labels and protected detailed evidence.

Focused proof must cover each exposed browser/WebSocket and Telnet journey; concurrent probes without takeover; synthetic classification and least privilege; normal auth, abuse, moderation, security, and audit enforcement; analytics and live-SLO exclusion; credential rotation and revocation; deterministic reset; missing, stale, delayed, and stopped runner behavior; one failed sample without immediate P0; sustained confirmed login failure with no live traffic; representative-command and latency degradation; low-cardinality metrics; independent notification; and explicit local or omitted hobby posture.

## Reversibility and Revisit Triggers

Canary frequency, sustained-failure windows, notification providers, restricted fixture shape, and evidence formats may evolve by deployment profile while preserving complete public-path coverage, transport isolation, no security bypass, live-SLO exclusion, and stale-run detection. Revisit required journeys when FireMUD adds or removes a public player transport or when a read-only command provides materially better end-to-end coverage than `LOOK`.

## Required Documentation Alignment

- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-player-experience-incident-runbook.md`
- `design/architecture/system-architecture-testing.md`
- `design/architecture/decisions/adr-0154-profile-dependent-independent-deadman-and-public-path-monitoring.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/observability/external-monitoring/README.md`
