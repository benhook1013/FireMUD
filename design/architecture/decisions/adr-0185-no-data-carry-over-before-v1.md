# ADR 0185: No Data Carry-Over Before v1

## Status

Proposed - Pending Human Review

## Implementation Status

Not implemented. Develop still carries legacy and retained numeric-identifier tables, migration dispositions and a dev-demo writer-quiesce path that this decision removes.

## Canonical Design

- [Database Migrations](../system-architecture-database-migrations.md)
- [Identifier Glossary](../system-architecture-identifier-glossary.md)

## Decision Record

- Human review status: Pending
- Human review date: Not yet reviewed
- Human review disposition: Pending
- Review source: `AI-AUTHORED-PENDING`
- Decision date: 2026-11-18
- Primary capability: `SF-2.3` durable cross-service coordination and idempotency
- Affected capabilities: `SF-2.1`, `AR-1.5`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: direct human decision on 2026-11-18 that all pre-v1 preview data is disposable

## Context

[ADR 0020](./adr-0020-scoped-domain-and-operational-identifiers.md) and [ADR 0082](./adr-0082-semantic-boundary-for-cross-service-identifier-migration.md) make UUIDs canonical and require explicit mappings when an identity changes. Applied to data that exists only in pre-v1 environments, those rules produced legacy source tables, retained numeric rows, migration dispositions and quiesced migration paths. No pre-v1 environment holds data worth keeping: every database is seeded from scripts.

## Decision

Until the first v1 release, all data in every FireMUD environment is disposable. A schema or identifier change may replace the old representation directly, and environments reset their databases instead of migrating rows. No legacy or retained tables, old-to-new mappings, disposition records or compatibility readers are built for pre-v1 data, and existing ones are removed. Expand/migrate/contract, including old/new binary overlap compatibility, and the ADR 0082 mapping obligations apply from the first v1 release; before v1 a schema change resets the environment behind the quiesced writer boundary in the migration guide, once no `IN_PROGRESS` external-action intent is pending, so old and new binaries never share a database.

This decision covers data only. Security, protocol and in-flight runtime correctness rules, such as crash-recovery evidence, still apply.

## Consequences

- Identifier and schema migrations before v1 are direct replacements followed by a reset.
- Preview and dev-demo deploys always start from an empty, reseeded database.
- A reset also discards completed external-action receipts, so any external provider used before v1 must be reset with the environment or reject replays.
- Legacy and retained-row machinery is deleted rather than maintained.

## Alternatives Considered

### Keep migrating pre-v1 data

Rejected because no pre-v1 data is worth keeping and the migration machinery delayed PLAY and the UUID cutover.

## Reversibility and Revisit Triggers

Ends at the first v1 release. Revisit earlier if any environment is declared to hold data worth keeping.
