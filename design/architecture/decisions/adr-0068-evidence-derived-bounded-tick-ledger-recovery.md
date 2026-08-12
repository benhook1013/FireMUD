# ADR 0068: Evidence-Derived Bounded Tick Ledger Recovery

## Status

Accepted

## Implementation Status

Game Session recovery ownership and evidence-derived SLOs are target state; bounded fair workers, verifier/reconcile authority, quarantine paths, and measured calibration remain implementation and proof gaps.

## Canonical Design

- [Tick Failure and Operations](../system-architecture-tick-failures-and-operations.md)
- [Tick Incident Runbook](../system-architecture-tick-incident-runbook.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-15`
- Primary capability: `PO-4.2` runtime reconciliation and recovery
- Affected capabilities: `GR-1.4`, `SF-2.3`, `PO-1.4`, `SF-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent recovery-authority validation and fixed-formula, manual-recovery, direct-SQL, and separate-service alternative analysis
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `TICK-15`

## Context

Tick effect ledger rows must converge after crashes, lost coordination state, owner timeouts, and retry exhaustion. A permanent formula such as `max(60 seconds, 20 ticks)` does not prove that the deployed database, workers, and domain owners can recover the admitted workload within that time. It also conflates a bootstrap alert with a production acceptance guarantee.

Recovery must remain bounded and fair without allowing operators or infrastructure automation to manufacture domain success or loss outcomes.

## Decision

Game Session owns automated tick-ledger recovery. Recovery runs through isolated, bounded, fair workers across region and tenant scopes so one large or unhealthy scope cannot monopolize reconciliation capacity or starve others.

The production convergence SLO is environment-emitted and derived from measured evidence, including:

- admitted and recovery backlog distributions;
- durable scan and claim latency;
- available worker throughput and fair-share behavior;
- owning-domain response latency and error rates; and
- tested recovery capacity under representative load and injected faults.

The former 60-second value is only a clearly provisional bootstrap alert until load and fault evidence establishes a numeric production acceptance threshold. A numeric target cannot be declared from tick cadence alone and cannot weaken terminal correctness when breached.

When a `SCHEDULED` row exceeds the supported stale-work budget, the affected runtime scope escalates to `DEGRADED` or `STALLED` according to operational policy. Recovery continues through the isolated workers where safe, and metrics must expose backlog age, scan lag, throughput, errors, scope fairness, and starvation.

Operators may inspect durable ledger rows with read-only SQL. Any disposition must go through Game Session's supported verifier/reconcile operation, which rechecks authoritative participant evidence and records an auditable transition. Operators must never directly edit a row to `APPLIED`.

Technical retry exhaustion moves unresolved work to `DEAD_LETTER` or quarantine for diagnosis and explicit disposition. It does not fabricate `ABANDONED`; that terminal result requires authoritative evidence or a declared policy that permits abandonment.

`REPLAY_NOOP` is an `APPLIED` outcome or reason demonstrating that the intended logical effect was already durably present. It is not a separate ledger status.

Active, unresolved, dead-lettered, or quarantined rows are never garbage-collected. Retention or compaction applies only after terminal evidence and the applicable diagnostic, audit, and recovery obligations are satisfied.

## Consequences

- Recovery capacity and acceptance thresholds reflect measured deployment behavior instead of an arbitrary universal formula.
- Tenant- and region-fair isolation prevents one pathological scope from consuming all recovery workers.
- A convergence-SLO breach becomes an explicit availability and operations incident without changing truthful effect outcomes.
- Operator intervention remains auditable and cannot bypass domain verification.
- Durable backlog, quarantine, retention, fairness, and starvation telemetry add storage and operational cost.

## Alternatives Considered

### Permanent Fixed Recovery Formula

Rejected because `max(60 seconds, 20 ticks)` is not evidence of achievable recovery under real backlog, database, worker, or owner-service conditions. Sixty seconds remains useful only as a provisional bootstrap alert.

### Unbounded Manual Recovery

Rejected because routine crash and coordination-loss recovery would depend on operator availability, provide no convergence capacity, and permit silent starvation between scopes.

### Direct SQL Disposition

Rejected because editing ledger state cannot prove the owning domain committed the intended effect and could create false `APPLIED` or `ABANDONED` outcomes.

### Separate Recovery Service

Deferred because Game Session already owns effect lifecycle and reconciliation authority. A separate service would split ownership and still require Game Session verification; isolate and scale workers first.

## Implementation and Proof Obligations

Implement bounded durable scans, atomic claims, per-region and per-tenant fairness, isolated worker capacity, retry and quarantine transitions, supported verifier/reconcile operations, terminal-evidence retention, and the required metrics and alerts.

Prove recovery after crashes and Redis loss; duplicate workers and claims; owner timeouts and errors; large and adversarial per-scope backlogs; bounded healthy-scope progress; starvation detection; `DEGRADED` and `STALLED` escalation; retry exhaustion without fabricated abandonment; replay-no-op as applied evidence; rejected direct or unsupported disposition; no garbage collection of unresolved rows; and SLO calibration under representative load and fault injection.

The current implementation and focused proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Environment-specific SLOs, worker counts, scan sizes, and fair-share parameters may be recalibrated from evidence without changing recovery authority or terminal correctness. Revisit service ownership only if measured recovery workload requires an independently operated boundary and that split can preserve one verifier, one ledger lifecycle, and one auditable disposition authority.
