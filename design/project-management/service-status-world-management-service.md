# World Management Service Status

## Current Coverage

- World/region/room persistence, room snapshots, exits, and the data needed for the current `LOOK` slice are implemented.
- Procedural-generation control and world-creation responsibilities are thoroughly documented.
- World geometry and snapshot APIs are available for downstream gameplay aggregation.
- The first canonical design-time mutation surface is live: Game Design can apply typed Draft world mutations through World Management with revision-ledger idempotency, aggregate/scope epoch conflict checks, spawn-binding reference validation, scoped topology validation, and first `REPLACE_SCOPE` / `SEED_APPEND_ONLY` scope-policy enforcement.

## Current Role In The Platform

- Owns authoritative world topology, room/region metadata, and instance-aware world state boundaries.
- Supplies room snapshot and world context to gameplay services.
- Provides generation-oriented control-plane surfaces for world creation and publication workflows, including the first canonical Draft world-mutation ingress used by Game Design revision saves.

## Partial / Stubbed / Deferred Areas

- Some broader shard/load-balancing and world-notification concerns remain future work.
- Cache hardening and some publish-copy/version-driven flows are still architectural obligations rather than finished runtime hardening.
- Movement-facing world usage exists indirectly through geometry/snapshot data, but a full movement slice still needs to be built on top of it.
- The remaining `08.5` work is breadth rather than missing substrate: broader generation payload coverage and broader caller adoption still need to move onto the canonical mutation seam.

## Planning Notes

- Treat World Management as ready enough for the next movement-oriented slice, with hardening and richer generation behavior to follow later.
