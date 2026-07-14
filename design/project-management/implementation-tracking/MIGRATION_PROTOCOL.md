# Implementation-Tracker Migration Protocol

This protocol moves current implementation facts from vertical-slice delivery records into domain trackers without silently losing detail. It does not change canonical product design, which remains under [`design/architecture`](../../architecture/README.md), and it does not delete or rewrite legacy slice records during migration.

## Workflow

1. Build and review the [global source allocation map](./migration-ledgers/SOURCE_ALLOCATION_MAP.md). It is the refactor tracker: every legacy source range must have an exact destination tracker, split, or explicit historical disposition before transposition starts.
2. Migrate one domain tracker at a time on the main thread. The global map records allocation; the relevant domain ledger records the completed source-to-destination transposition.
3. Run independent review after each domain batch. Spark performs the primary source-to-destination coverage audit and returns the implicated service audit queue. A separate higher-capability review may be added for final sign-off when useful.

## Unit Of Work

Migrate one domain tracker at a time. A source slice may contribute to more than one tracker, but every source range must appear in the global allocation map and have one explicit ledger disposition in the relevant tracker ledger. Do not assign work by service alone: a slice often crosses service boundaries, while the tracker owns the domain fact.

## Required Process

1. Allocate the source record and exact line range in the global source allocation map before changing any tracker, then copy the applicable allocation into the domain ledger.
2. Transpose one bounded source section at a time. Preserve every target-state decision, verified implementation claim, active gap, and unresolved design question unless the ledger explicitly records why it remains only in the legacy delivery record or is superseded.
3. After each bounded section, inspect the old and new text side by side and inspect `git diff --word-diff` before continuing. Do not summarize several source sections from memory.
4. Update the ledger row with the destination heading or anchor and the disposition.
5. Keep the legacy record linked from the tracker. A historical task checklist may remain only in the legacy record, but that disposition must be explicit; no current fact may disappear behind a generic historical link.
6. Run the domain Spark review brief after the tracker batch is complete. The reviewer compares allocated old source ranges with the new tracker, then returns a service-level audit queue for implementation claims and gaps.

## Ledger Rules

Every allocated source range must be one of:

- `copied`: preserved substantially verbatim in the tracker;
- `consolidated`: rewritten only to remove duplication while preserving the listed facts;
- `legacy-delivery-only`: delivery history or obsolete task mechanics intentionally retained only in the source record;
- `superseded`: replaced by a named canonical design or implementation statement, with its source linked; or
- `split`: divided into exact child ranges owned by named trackers.

`unreviewed`, `implicit`, or a bare source-file link are not valid dispositions.

## Completion Gate

A tracker migration is not complete until:

- its ledger has no unallocated source range;
- every active source fact has a destination anchor or explicit disposition;
- the Spark domain review reports no unaddressed loss or semantic drift; and
- the resulting service-level audit queue is recorded in the tracker or explicitly scheduled as follow-through.

## Service Audits

Service audits follow the domain migration; they are not the migration unit. For each service implicated by migrated facts, the reviewer must identify the relevant architecture doc, public contract or runtime seam, implementation proof to inspect, and whether the tracker claim is verified, stale, or needs a later implementation slice.
