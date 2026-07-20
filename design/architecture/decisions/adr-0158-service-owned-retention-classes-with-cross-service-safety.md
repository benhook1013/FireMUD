# ADR 0158: Service-Owned Retention Classes with Cross-Service Safety

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `CAPACITY-02`
- Disposition: `revised`
- Primary capability: `SF-2.1` PostgreSQL ownership, schemas, migrations, and retention
- Affected capabilities: `GR-1.4`, `PO-4.2`, `PO-4.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of database growth, replay and deduplication safety, recovery evidence, audit/privacy obligations, and service ownership

## Context

FireMUD persists high-churn command status, tick batches and effects, remote follow-ups, reconciliation work, consumer idempotency guards, audit records, and diagnostic payloads. Deleting each table according to an isolated age threshold can remove evidence still required by another service, permit duplicate logical effects, or make recovery irreconcilable. Retaining everything indefinitely instead increases index and backup size, vacuum pressure, restore time, cost, and privacy exposure.

One universal duration or a central cross-schema garbage collector would not solve this safely. The records have different purposes, and each service owns its schema and migrations.

## Decision

FireMUD uses one shared retention-class and compatibility contract. Each owning service implements and operates cleanup for its own data.

Every retained family declares its owner, class, terminality and eligibility predicate, blocking references, minimum horizon, compaction/archive method, garbage-collection cadence and batch bound, hold behavior, and bounded health metrics.

The common classes are:

1. **Live or recoverable work.** Nonterminal, inconsistent, quarantined, or still-actionable recovery work is never deleted merely because it is old. It remains until authoritative terminalization or an explicit recovery disposition.
2. **Retry and idempotency receipt.** A compact stable identity, request digest, terminal outcome, and required lineage survive every client, producer, duplicate-delivery, and reconnect retry window.
3. **Recovery and reconciliation lineage.** Tick batches, effects, remote legs, manifests, and consumer guards become eligible only after all related legs are terminal, reconciliation is complete, and supported restore or replay can no longer resurrect dependent work.
4. **Purpose-bound audit or safety evidence.** Its owning governance policy defines finite retention, access, export/erasure treatment, legal holds, and permitted compaction. Ordinary operational cleanup cannot silently shorten that policy.
5. **Diagnostic or content payload.** Command text, manifests, messages, payload/result documents, and failure details may be minimized earlier than correctness receipts once replay, investigation, and governance no longer require their content.

Cross-service dependency inequalities are mandatory. In particular, a consumer receipt or effect guard must not expire while a producer can still retry, replay, restore, or reconcile the same logical action. Command-status receipts must outlive all supported client and internal retry/reconnect windows.

Garbage collection uses safe watermarks and reference/terminal checks, not age alone. Partition drops are allowed only when the whole partition is eligible; otherwise the owner must move protected rows, use row-level sweeping, or choose another physical layout. Archive storage does not replace an online receipt needed for deduplication.

Exact durations are configuration-backed operational policy established from declared retry/recovery/governance horizons and measured growth. This ADR does not invent universal durations.

## Consequences

- Replay, retry, and consumer idempotency records remain mutually safe across service boundaries.
- Services preserve schema ownership and may choose partitions, bounded sweeps, compaction, or archives appropriate to each table.
- Compact receipts can outlive bulky identity-bearing payloads, reducing storage and privacy exposure.
- Policy definition and compatibility validation add operational work, and physical partitioning may require key/index redesign.
- Unresolved rows can prevent a whole time partition from being dropped, so active work may need separate storage or relocation.

## Alternatives Considered

### One Duration and One Central Garbage Collector

This is operationally simple but conflates live work, deduplication, recovery, audit, and diagnostics and violates service schema ownership.

### Independent Per-Service Retention Only

Service-local manifests are useful implementation inputs, but without shared classes and compatibility validation a producer retry-window change can silently invalidate a consumer guard policy.

### Retain Everything Indefinitely

This avoids premature deletion but creates unbounded storage, WAL, vacuum, backup, restore, query, cost, privacy, and breach-exposure growth.

## Implementation and Proof Obligations

The current platform does not implement the complete contract. Core Game Session tick, command, and remote-work tables are unpartitioned and lack coordinated retention. Some service-local cleanup exists, including Automation terminal-work deletion, but database-level preservation of referencing audit rows and cross-service receipt horizons is not fully proved.

Implementation must catalogue all producer retry/replay/restore horizons and consumer guards, classify every high-churn family, expose oldest-blocking-row and cleanup-lag metrics, and validate dependency inequalities. Focused proof must cover unresolved work surviving cleanup, terminal compaction, late duplicate delivery, replay after restore, legal holds, payload minimization, bounded catch-up, and partition-drop eligibility.

## Reversibility and Revisit Triggers

Durations, table layouts, archive backends, and cleanup mechanisms are reversible per service while the class and compatibility invariants remain. Revisit the physical strategy when measured row/index growth, vacuum lag, backup/restore time, privacy requirements, or unresolved-row skew makes the selected approach unsuitable.

## Required Documentation Alignment

- `design/architecture/system-architecture-scaling-runbook.md`
- `design/architecture/system-architecture-testing.md`
- `design/architecture/system-architecture-tick-execution-flows.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`
- service persistence and operations documentation for every classified family
