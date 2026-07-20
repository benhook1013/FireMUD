# ADR 0155: Staged Profile-Aware Player-Experience SLO Contract

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `OBS-02`
- Primary capability: `PO-4.2` Health, readiness, reliability policy, SLOs, and degraded operation
- Affected capabilities: `PO-2.4`, `GR-1.2`, `EA-2.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of player-visible reliability signals, outcome eligibility, latency boundaries, low-traffic behavior, alerting windows, deployment-profile obligations, and the difference between calibration targets and enforceable promises
- Inventory disposition: `revised`

## Context

Infrastructure health alone does not establish whether players can enter and use FireMUD. The existing observability design therefore defines player-facing indicators for login success, command latency, public entry-path availability, and chat delivery latency. It also contains initial numeric targets and checked-in rules and dashboards shaped around those targets.

Those numbers were written before FireMUD had complete metric producers, representative live baselines, or target-profile load results. Treating them as release gates or public promises now would turn unmeasured assumptions into policy. In particular, the current Game Session bootstrap tick cadence is `1000ms`, so a generic claim that tick-bound movement, look, or combat completes within `250ms` conflicts with flows that may legitimately wait for an authoritative tick. FireMUD must retain player-visible measurement while distinguishing a useful calibration hypothesis from an enforceable reliability commitment.

SLIs also need precise population and completion boundaries. Invalid credentials, malformed player input, explicit policy denials, and abuse-rate rejection do not mean that the platform failed to execute an available service. Zero traffic must not appear as perfect availability, and raw tenant, player, session, game-instance, or region identifiers must not be introduced as Prometheus labels merely to make the signals more specific.

## Decision

### Retain Four Player-Experience SLI Families

FireMUD retains these canonical player-experience SLI families:

- login success;
- core gameplay command latency;
- public entry-path availability for the supported browser/WebSocket and Telnet paths; and
- chat delivery latency.

These signals describe separate player-visible boundaries. Infrastructure health, synthetic canaries, and internal stage metrics complement them but do not replace them.

### Existing Numeric Targets Are Calibration Starting Points

The current values remain initial calibration hypotheses:

- login success of at least `99.5%` over `15m`;
- the existing core-command `p99 < 250ms` view over `5m`;
- entry-path availability of at least `99.9%` over `1d`; and
- chat-delivery `p99 < 1s` over `5m`.

They are not current release promises, contractual availability claims, universal hard gates, or proof that every command family can meet the same latency envelope. Dashboards and provisional alerts may use them during calibration only when their wording and severity make that status explicit.

Before a hosted profile enforces any target, representative measurements must establish the eligible population, normal distribution, tail behavior, traffic volume, tick cadence and wait contribution, dependency behavior, and realistic error budget for that profile. The resulting enforceable target may retain or revise the starting number. A changed calibrated number does not remove the underlying SLI family.

### Eligible Attempts and Outcomes Are Explicit

Every SLI producer uses a bounded outcome vocabulary that distinguishes at least:

- `success` or completed service delivery;
- `server_failure`, including platform timeout, unavailable dependency on the required path, internal error, or platform-owned capacity rejection;
- `user_rejection`, including malformed or semantically invalid player input and incorrect credentials; and
- `policy_rejection`, including an intentional authorization, moderation, entitlement, admission, or abuse-rate decision.

Availability denominators include successful outcomes and server-attributable failures for requests that reached the measured platform boundary. `user_rejection` and `policy_rejection` remain observable through bounded diagnostic counters but do not count as availability failures. A producer must not classify an ambiguous internal error as a user or policy rejection merely to protect the SLI.

Protocol negotiation failures, client disconnects, and caller timeouts count only when the documented boundary can attribute them to the platform. External public-path canaries remain necessary to detect failures that prevent an attempt from reaching the in-service producer at all.

### Login and Entry-Path Boundaries

A login attempt enters the availability population after the supported login request is parsed sufficiently to reach the authentication decision boundary. Successful authentication is `success`; platform errors and platform-owned timeouts are `server_failure`; incorrect credentials, malformed submissions, ineligible accounts, explicit authorization or moderation denials, and abuse rejection use the corresponding non-failure outcome.

An entry-path attempt begins at the first measurable public Gateway or TCP Proxy admission boundary and succeeds when that path completes the documented connection or gameplay-admission handshake. Edge, bridge, or required-upstream failures are server-attributable. Invalid TLS/protocol use, malformed handshakes, caller cancellation, and intentional policy or abuse rejection are excluded from availability failure when they can be classified reliably.

### Command Completion and Stage Boundaries

Command latency begins when an authenticated, admitted command is accepted by the owning public gameplay ingress after bounded parsing and policy checks. It ends when FireMUD has produced the authoritative terminal command result needed for outbound projection. Client rendering, network transit after FireMUD's outbound handoff, and time spent correcting an invalid command are outside this latency boundary.

The end-to-end observation retains bounded stage measurements sufficient to distinguish at least:

- `edge_queue`;
- `dispatch`;
- `tick_wait`; and
- `domain_commit`.

A command that requires tick-aligned authoritative completion includes `tick_wait` in its end-to-end latency. It must not be reported as complete merely because it was accepted into a queue. Calibration classifies command families and profiles by their actual execution boundary; the `250ms` starting point must not force tick-bound work to invent an earlier success point or bypass the canonical tick model.

Malformed commands, gameplay-rule denials, authorization or moderation denials, and abuse rejection do not enter the successful-latency distribution. Server failures are counted separately and remain part of availability/error analysis rather than disappearing from the player-experience view.

### Chat Delivery Boundary

Chat latency begins when the canonical chat owner accepts a valid, authorized message. It ends when the message has been handed successfully to the authoritative outbound delivery queue or session stream of every intended recipient who was eligible and actively reachable at acceptance time. It does not wait for client rendering or acknowledgment beyond FireMUD's controlled delivery boundary.

The producer records whether all eligible recipients reached that boundary. Moderation rejection, sender abuse rejection, invalid channels, and recipients who were already offline or ineligible are not server delivery failures. Loss, timeout, or failed handoff inside the FireMUD delivery path is server-attributable. Recipient counts and channel types remain bounded diagnostic dimensions; recipient identities are not metric labels.

### No Data and Minimum Samples Produce Unknown

Every evaluated SLI has a documented, profile-specific minimum eligible sample count. With no eligible traffic or fewer than that minimum, the result is `unknown`, not green, compliant, breached, or zero-percent availability.

Synthetic canaries may provide independent outage detection during low traffic, but their success does not manufacture a live-traffic SLO result. Dashboards, alerts, release evidence, and user-facing availability claims preserve the distinction between `unknown`, provisional calibration, and an enforceable evaluated result.

### Alerts Use Multi-Window Burn Evaluation

Enforceable hosted SLOs use error-budget burn alerts with at least one short window for fast material failures and one longer window for sustained consumption. A single noisy window or isolated low-volume failure does not create the same incident claim as sustained burn. Each alert also applies the minimum-sample rule and exposes the evaluated scope, window pair, outcome family, and current calibration or enforcement state.

The exact window pairs, burn multipliers, minimum samples, and severity mapping are calibrated from representative traffic and incident exercises. The existing `15m`, `5m`, and `1d` views may remain useful dashboard and recording-rule windows, but they do not by themselves satisfy the multi-window alert contract.

### Scope and Deployment Profiles Are Bounded

Player-experience metrics use only an approved bounded `scope` vocabulary and other documented bounded enums such as path, command family, command stage, outcome, or channel type. They do not use raw tenant, region, game-instance, session, player, character, trace, or arbitrary command values as ordinary metric labels.

Hosted production may enforce calibrated SLOs as release, paging, or reliability gates only after the relevant producers, representative baseline, minimum-sample rule, scope mapping, and multi-window behavior are proved for that profile. Until then, the SLI remains visible and the starting thresholds remain informational calibration aids.

Hobby, single-node, and small profiles treat these SLO views as informational unless the deployment explicitly claims managed availability. A profile making that stronger claim adopts the same measured-calibration, bounded-scope, sample, alert, and proof obligations as hosted production for the claimed boundary.

## Consequences

- FireMUD keeps player-visible reliability measurement instead of substituting CPU, pod health, or generic error rates.
- Initial dashboards and alerts remain useful for gathering evidence, but they cannot silently become release blockers or public promises.
- Operators can distinguish platform failures from bad credentials, invalid gameplay actions, explicit policy decisions, and abuse rejection.
- Low-traffic environments report honest uncertainty instead of artificial perfect availability or unstable one-request percentages.
- Tick-bound command flows preserve their real authoritative completion semantics. This may lead to command-family or profile-specific calibrated targets rather than one universal `250ms` promise.
- Multi-window burn alerts add configuration and proof work but reduce noise and connect paging to error-budget consumption.
- Hosted profiles incur instrumentation, load testing, baseline retention, and periodic recalibration work before enforcing a reliability commitment.
- Hobby and small deployments retain a low-overhead informational posture unless they choose to advertise managed availability.
- Bounded scoping limits metric cost and privacy exposure but prevents arbitrary per-player or per-tenant Prometheus drilldown; exact identity investigation remains in logs and traces.

## Alternatives Considered

### Enforce the Existing Numbers Immediately

This would create a simple uniform contract, but the producers and representative baselines do not exist. It would also make the generic `250ms` command target contradict tick-bound flows under the current `1000ms` default cadence. The numbers remain calibration starting points instead.

### Remove Numeric Starting Points Until Production Data Exists

This avoids premature promises but leaves dashboards, load tests, and operational calibration without an initial hypothesis. Retaining clearly provisional values gives measurement work a concrete starting point without granting them gate authority.

### Use Only Infrastructure Health and Error Rate

Healthy processes and infrastructure can coexist with failed login, unusable entry paths, slow gameplay, or delayed chat. Infrastructure signals remain diagnostic inputs but cannot replace the player-experience SLIs.

### Count Every Rejected Attempt as an Availability Failure

This is mechanically simple but makes player mistakes, moderation, authorization, and abuse controls degrade the platform's availability score. Explicit bounded outcome classification preserves those events for diagnostics without misrepresenting intentional rejection as a server outage.

### Use One Threshold Window Per SLI

Single-window threshold alerts are easy to understand but either page too quickly on noise or react too slowly to severe failure. Multi-window burn evaluation better distinguishes rapid and sustained budget consumption while minimum samples prevent low-volume distortion.

### Apply One Enforceable Contract to Every Deployment Profile

This makes claims uniform but imposes production measurement and paging overhead on hobby deployments that make no managed-availability promise. Profile-aware enforcement preserves honest claims without making the SLI vocabulary itself profile-specific.

## Implementation Reality

The player-experience metric catalog, Prometheus recording and alert rules, Grafana dashboards, and incident-runbook shapes already exist. They are useful target-state and calibration assets, not proof of an operating SLO contract.

The live service producers and representative environment baselines required for login, end-to-end and stage-level command latency, entry-path attempt availability, and complete chat delivery are not implemented or proved as a cohesive surface. Existing synthetic player-flow tooling provides complementary outage evidence but does not replace live-traffic producers.

The checked-in threshold rules still present the initial values directly. They do not yet prove the revised eligibility rules, minimum-sample `unknown` behavior, calibrated multi-window burn policy, or profile-aware enforcement state. The current Game Session configuration documents `GAME_TICK_DURATION_MS=1000`, making the existing generic `250ms` command view especially unsuitable as an immediate hard promise for tick-bound flows.

## Implementation and Proof Obligations

- Implement canonical producers for all four SLI families with one bounded outcome vocabulary and documented start, completion, and attribution boundaries.
- Prove that invalid credentials, malformed input, gameplay-rule rejection, authorization or moderation denial, and abuse rejection remain observable without counting as server-availability failure.
- Prove that internal errors, required-dependency failures, platform timeouts, and platform-owned capacity failures cannot be mislabeled as caller rejection.
- Implement command end-to-end and bounded stage measurements and prove that tick-bound commands complete only at their authoritative terminal boundary.
- Implement chat recipient-eligibility and all-recipient handoff accounting without identity-bearing metric labels.
- Define and test the minimum eligible sample count for each SLI and profile, including zero traffic, one request, traffic transitions, and synthetic success while live traffic remains unknown.
- Define an approved bounded scope vocabulary and pass metrics-cardinality validation under representative tenant, region, game, and player volume.
- Collect representative hosted-profile latency, success, traffic-volume, tick-cadence, dependency-failure, and recovery baselines before approving enforceable thresholds.
- Replace single-window hard-threshold paging with calibrated multi-window burn alerts where an SLO becomes enforceable; prove fast failure, slow burn, brief noise, low volume, recovery, and exhausted-budget behavior.
- Ensure dashboards, alerts, release evidence, and status surfaces distinguish informational starting points, calibration state, `unknown`, enforceable compliance, and breach.
- Prove that hobby and small profiles remain informational by default and that a managed-availability claim activates the complete hosted proof obligations for its declared boundary.
- Reconcile the existing Prometheus rules, Grafana dashboards, observability documentation, and player-experience incident runbook with this decision before any threshold is used as a release or contractual gate.

## Reversibility and Revisit Triggers

Numeric targets, sample minima, burn-window pairs, burn multipliers, bounded command families, and profile mappings may change as measured evidence improves. Revisit an enforced target when gameplay cadence, command completion semantics, entry routing, chat delivery architecture, target hardware, deployment topology, or observed player expectations materially change. Changes must preserve explicit eligibility, server-attribution, honest `unknown` state, bounded labels, and the distinction between informational calibration and enforceable promises.

## Required Documentation Alignment

- `design/architecture/system-architecture-logging-monitoring.md`
- `design/architecture/system-architecture-player-experience-incident-runbook.md`
- `design/observability/grafana/player-experience-alerts-snippets.md`
- `design/observability/grafana/player-experience.json`
- `design/observability/grafana/player-experience-drilldown.json`
- `k8s/monitoring/prometheus-rules-firemud.yaml`
- `design/architecture/microservices/game-session-service/configuration.md`
