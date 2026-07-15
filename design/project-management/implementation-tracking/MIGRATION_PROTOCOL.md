# Implementation-Tracker Migration Protocol

This protocol moves current implementation facts from vertical-slice delivery records into domain trackers without silently losing detail. It does not change canonical product design, which remains under [`design/architecture`](../../architecture/README.md). Legacy slices and generated evidence remain unchanged only while they are needed for migration verification; they are removed after the standardized trackers pass that gate.

## Workflow

1. Build the [global source allocation map](./migration-ledgers/SOURCE_ALLOCATION_MAP.md) on the main thread. It is the refactor tracker: every legacy source range must have an exact destination tracker, split, or explicit historical disposition before transposition starts. Bounded internal research may help locate source ranges, but main-thread reasoning owns the mapping decision.
2. Use a fresh, independent Luna pass to audit the completed map by disjoint domain. It must find unallocated ranges, overlapping tracker ownership, and incorrect boundaries before any tracker migration begins. It does not make design decisions.
3. Run the deterministic transposition tool on the main thread after the map has passed its audit. It generates every affected domain tracker and supporting ledger from the exact allocation map; the map records allocation and the ledger records completed source-to-destination transposition. This is the lossless evidence layer, not the final reader-facing implementation record.
4. Author the semantic consolidation on the main thread. Each tracker must replace placeholder status text with a concise domain capability record, canonical design sources, active gaps, decisions still to discuss, and a service/contract map. It must preserve the source evidence appendix unchanged and may not silently omit a current fact merely because several source records repeat it.
5. Run independent review after the semantic consolidation. Spark performs the primary source-to-destination coverage and semantic-fidelity audit and returns the implicated service audit queue. A separate higher-capability review may be added for final sign-off when useful. Spark is not an allocation prerequisite.
6. Resolve every review finding in the standardized tracker body, rerun the affected audit, then delete the source-evidence appendices, legacy vertical-slice records, generated allocation/coverage ledgers, and migration-only tooling. Repair all links and indexes so the standardized domain trackers are the only implementation-tracking surface.

## Unit Of Work

The source map is the migration unit. A source slice may contribute to more than one tracker, but every source range must appear in the global allocation map and have one explicit ledger disposition in the relevant tracker ledger. Do not assign work by service alone: a slice often crosses service boundaries, while the tracker owns the domain fact.

## Required Process

1. Allocate the source record and exact line range in the global source allocation map before changing any tracker, then copy the applicable allocation into the domain ledger.
2. Transpose exact allocated source ranges mechanically. Preserve every target-state decision, verified implementation claim, active gap, and unresolved design question. The generated `Implementation Record Index` is a provenance map; source evidence remains in the same tracker below it. Heading depth, same-directory Markdown link destinations, table delimiters, and a split range beginning within a nested list may be structurally rebased only as required by the new document location; no source wording may be dropped.
3. Validate the full map, then validate every generated evidence marker against the source path, allocated line range, source checksum, and exact transposed text. Do not summarize several source sections from memory.
4. Semantically consolidate the tracker on the main thread. Read the evidence, reconcile duplication, and state one current capability/authority model in the header. Keep a real active-gap list, preserve unresolved decisions in `To Discuss`, and link canonical design docs. Do not use another mechanical generator as a substitute for this step.
5. Update the ledger row with the destination heading or anchor and the disposition.
6. Keep the legacy record linked from the tracker. A historical task checklist may remain only in the legacy record, but that disposition must be explicit; no current fact may disappear behind a generic historical link.
7. Run the domain Spark review brief after semantic consolidation. The reviewer compares allocated old source ranges with the tracker evidence and consolidated record, then returns a service-level audit queue for implementation claims and gaps.

## Ledger Rules

Every allocated source range must be one of:

- `copied`: preserved substantially verbatim in the tracker;
- `consolidated`: rewritten only to remove duplication while preserving the listed facts;
- `legacy-delivery-only`: delivery history or obsolete task mechanics intentionally retained only in the source record;
- `superseded`: replaced by a named canonical design or implementation statement, with its source linked; or
- `split`: divided into exact child ranges owned by named trackers.

`unreviewed`, `implicit`, or a bare source-file link are not valid dispositions.

## Completion Gate

A tracker is ready for independent verification when:

- its ledger has no unallocated source range;
- every active source fact has a destination anchor or explicit disposition;
- its consolidated record, index, and evidence appendix agree on the current domain boundary; and
- the standardized body is self-sufficient, with the appendix needed only to detect omissions or drift.

A tracker is independently verified when the Spark domain review reports no unaddressed loss or semantic drift and the resulting service-level audit queue is recorded in the tracker or explicitly scheduled as follow-through.

The repository-wide migration is complete only when every tracker has passed that gate and the temporary evidence appendices, legacy slice records, migration ledgers, and migration-only tooling have been removed without leaving broken links or unique implementation facts behind.

## Service Audits

Service audits follow the domain migration; they are not the migration unit. For each service implicated by migrated facts, the reviewer must identify the relevant architecture doc, public contract or runtime seam, implementation proof to inspect, and whether the tracker claim is verified, stale, or needs a later implementation slice.
