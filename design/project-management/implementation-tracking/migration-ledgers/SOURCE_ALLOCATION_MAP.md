# Legacy Source Allocation Map

This is the source of truth for the implementation-tracker refactor. Before transposition, every current-fact range in [`../../vertical-slices`](../../vertical-slices/README.md) must be allocated to exactly one target tracker or explicitly split into child ranges. The destination ledger records the completed migration and review result.

Do not use file names alone. Allocate exact line ranges after reading the source content. A source file can have multiple rows.

| Legacy source record | Source lines | Target tracker | Destination ledger | Allocation rationale | Allocation review |
| --- | --- | --- | --- | --- | --- |

## Mapping Status

Not started. Populate this map from the domain Phase 1 Spark allocation reviews, then run the cross-domain allocation review before the first tracker migration.
