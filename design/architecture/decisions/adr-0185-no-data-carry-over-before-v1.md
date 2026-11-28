# ADR 0185: No Data Carry-Over Before v1

## Status

Accepted

## Implementation Status

Not implemented. Develop still carries legacy and retained numeric-identifier tables, migration dispositions and a dev-demo writer-quiesce path that this decision removes.

## Canonical Design

- [Database Migrations](../system-architecture-database-migrations.md)
- [Identifier Glossary](../system-architecture-identifier-glossary.md)

## Decision Record

- Human review status: Completed
- Human review date: 2026-11-28
- Human review disposition: Accepted
- Review source: `DATA-CARRY-01`
- Decision date: 2026-11-18
- Decision key: `DATA-CARRY-01`
- Primary capability: `SF-2.3` durable cross-service coordination and idempotency
- Affected capabilities: `SF-2.1`, `AR-1.5`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: direct human decision on 2026-11-18 that all pre-v1 preview data is disposable; the human owner accepted this record as written on 2026-11-28

## Context

[ADR 0020](./adr-0020-scoped-domain-and-operational-identifiers.md) and [ADR 0082](./adr-0082-semantic-boundary-for-cross-service-identifier-migration.md) make UUIDs canonical and require explicit mappings when an identity changes. Applied to data that exists only in pre-v1 environments, those rules produced legacy source tables, retained numeric rows, migration dispositions and quiesced migration paths. No pre-v1 environment holds data worth keeping: every database is seeded from scripts.

## Decision

Until the first v1 release, pre-v1 application data is disposable in every FireMUD environment, subject to the reset safety gate below. A schema or identifier change may replace the old representation directly, and environments reset their databases instead of migrating rows. No legacy or retained tables, old-to-new mappings, disposition records or compatibility readers are built for pre-v1 data, and existing ones are removed. Expand/migrate/contract, including old/new binary overlap compatibility, and the ADR 0082 mapping obligations apply from the first v1 release; before v1 a schema change resets the environment behind the quiesced writer boundary in the migration guide, so old and new binaries never share a database. Reset must not begin while any external-action intent is in progress or its outcome is unproved.

This decision covers data only. Security, protocol and in-flight runtime correctness rules, such as crash-recovery evidence, still apply. Before resetting any store that contains irreversible external-action results, establish that every still-eligible exact retry or reconciliation read can recover its original durable owner result under [ADR 0048](./adr-0048-durable-idempotent-operator-write-execution.md), including committed actions whose response was lost. Otherwise, prove the action family's documented retry and reconciliation window has ended and fence delayed requests from executing again before discarding the receipt. If either outcome evidence or retry eligibility is uncertain, block the reset. A provider reset or a replay rejection does not substitute for returning a result to a still-eligible retry.

## Consequences

- Identifier and schema migrations before v1 are direct replacements followed by a reset.
- Preview and dev-demo deploys always start from an empty, reseeded database.
- External-action receipts remain recoverable for their required retry and reconciliation windows, or reset waits for the proven retirement and fencing gate above. This is protocol evidence, not a legacy application-data migration.
- Legacy and retained-row machinery is deleted rather than maintained.

## Alternatives Considered

### Keep migrating pre-v1 data

Rejected because no pre-v1 data is worth keeping and the migration machinery delayed PLAY and the UUID cutover.

## Reversibility and Revisit Triggers

Ends at the first v1 release. Revisit earlier if any environment is declared to hold data worth keeping.
