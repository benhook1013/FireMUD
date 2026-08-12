# ADR 0081: Objective Compatibility Gates for Database Evolution

## Status

Accepted

## Implementation Status

The objective compatibility gates are target state. The repository does not yet provide required mixed-version CI, representative old-era fixtures, or an authoritative per-service record of direct-replacement eligibility.

## Canonical Design

- [Database Migrations](../system-architecture-database-migrations.md#objective-compatibility-gates)
- [Versioning & Runtime Configuration](../system-architecture-versioning-runtime.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `DB-03`
- Primary capability: `SF-2.1` relational persistence and migration
- Affected capabilities: `AR-3.2`, `PO-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of direct replacement, expand/migrate/contract, fixed support windows, and indefinite compatibility
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `DB-03`

## Context

The previous rule allowed direct schema replacement during “initial development” and required compatibility once live or retained game versions existed. That phase label is not an objective safety boundary. A pre-v1 service can already have durable data or old and new binaries overlapping during deployment, while a later isolated service may still have neither obligation.

Two independent compatibility dimensions matter: application binaries may overlap during rolling deployment or rollback, and retained durable data or non-Retired game versions may need to remain readable after the schema changes. Conflating them can either permit destructive changes too early or preserve compatibility indefinitely after every real dependency has ended.

## Decision

Every shape-changing database migration evaluates two objective questions:

1. Can an older and newer application binary read or write the database during deployment, rollback, recovery, or another supported compatibility window?
2. Does retained durable data, including any non-Retired game version, still require the old representation to remain readable or reconstructable?

If either answer is yes, the change uses expand/migrate/contract. The expand phase introduces a representation compatible with the supported readers and writers. The migrate phase backfills and verifies retained data. The contract phase occurs only after the binary compatibility and rollback window has closed and every retained data or game-version dependency on the old representation has ended under the owning lifecycle and retention policy.

Direct replacement is permitted only when both answers are no: there is no retained data requiring compatibility, no supported overlap or rollback to an older binary, and all call sites, tests, migrations, generated SQL access, deployment configuration, and documentation can converge atomically. Pre-v1 or “initial development” status alone does not grant this exception. The qualifying evidence is service- and environment-specific and must be recorded with the change.

Compatibility is bounded by declared deployment, rollback, game-version, and data-retention lifecycles. Retired or unsupported representations need not remain executable forever, but contraction must not outrun those declared obligations.

## Consequences

- Destructive migrations are gated by concrete compatibility obligations rather than a subjective project phase.
- Rolling deployment compatibility and retained game-data compatibility are evaluated separately and must both close before contraction.
- Services without old readers, rollback obligations, or retained data can still converge directly without unnecessary dual-read or dual-write scaffolding.
- Retained versions can delay contraction and therefore increase temporary schema, backfill, testing, and operational complexity.
- The repository needs explicit evidence for compatibility windows, representative old-era fixtures, backfill verification, and contract-phase eligibility.

## Alternatives Considered

### Always Use Expand/Migrate/Contract

Rejected because it imposes dual representations and rollout machinery even when no durable data, old reader, writer, or rollback target exists.

### Fixed Compatibility Window with Export-and-Upgrade for Older Data

Not selected as the universal rule. A bounded support window is useful and may be declared by release policy, but time alone cannot make an actively retained game version or other durable-data obligation safe to destroy. Export-and-upgrade may be an owning feature's migration mechanism, provided it proves that the old representation is no longer required before contraction.

### Direct Destructive Migration on Every Release

Rejected because mixed binaries and retained game versions would become unreadable or could be corrupted during deployment and rollback.

## Implementation and Proof Obligations

Each affected service must identify its supported binary overlap and rollback window, retained-data and non-Retired-version dependencies, expansion compatibility, migration/backfill verification, and the evidence authorizing contraction. Proof must include representative old-era fixtures whenever retained data exists and mixed-version coverage whenever old and new binaries may overlap.

The repository currently does not provide the required mixed-version CI, old-era fixture coverage, or an authoritative per-service record of direct-replacement eligibility. Existing Flyway version-number checks do not prove these obligations. This decision does not claim implementation.

## Reversibility and Revisit Triggers

The objective gates are durable; individual support windows and migration mechanisms may change under their owning release and retention policies. Revisit if FireMUD adopts atomic fleet replacement with no binary rollback, a formal export-and-upgrade boundary for retained worlds, or a storage architecture that changes what constitutes a compatible representation.
