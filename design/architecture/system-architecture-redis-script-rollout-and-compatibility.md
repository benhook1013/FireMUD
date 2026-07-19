# FireMUD Redis Script Rollout and Compatibility

This document defines how Lua script evolution is classified, tested, and rolled out without violating the replay and reset assumptions of Coordination Redis.

## Lua Compatibility Modes and Rollout Matrix

Script changes are classified into a small set of compatibility modes:

- `compatible`
  - semantically safe across every caller and stored-payload version that can actually coexist during supported rollout, rollback, recovery, or retained-data windows
  - may be deployed through normal rolling deployments without coordination resets
- `requires_region_reset`
  - safe only when region-scoped coordination state is cleared
- `requires_tenant_reset`
  - safe only when affected tenant-scoped coordination state is cleared
- `requires_cluster_reset`
  - requires a wider deployment-scoped reset because the change cannot be isolated safely

The Lua Script Registry records the compatibility mode for each script version. CI should reject script changes that downgrade from a stricter mode to a looser one without explicit design updates.

## Cache/Rate-Limit Redis Reset Relationship

Script rollout compatibility applies to Coordination Redis. Cache/Rate-Limit Redis reset remains separate and must not be entangled with coordination reset tooling.

## Lua Compatibility Registry and Script Upgrades

The Lua Compatibility Registry lives in the shared `firemud-common` module alongside key builders and Lua descriptors. It is owned by platform/coordination maintainers and declares per script:

- `schemaVersionsSupported`
- `KEYS` / `ARGV` contract
- `outcomesSupported`
- compatibility tag and rationale
- optional minimum/maximum `schemaVersion` values known to exist in production deployments
- every evidenced caller/payload coexistence combination, any proven versionless legacy interpretation, and immutable version-specific script routing where used

For the purposes of this registry, `compatible` is intentionally narrow:

- it does not allow changes that alter return codes for valid input
- it does not allow turning a no-op into a mutating path or vice versa
- it does not allow reinterpreting existing payload semantics during AOF replay
- it does not mean speculative compatibility with unknown future versions or permanent current/previous support; the supported set follows concrete coexistence evidence

## Concrete Examples

Not compatible, therefore requiring reset or explicit multi-version handling:

- changing a script return code for any valid input
- turning a previously non-mutating error path into a mutating recovery path
- reinterpreting existing payload fields without draining or migrating old data
- introducing new keys or members that would appear on replay for historic entries

Potentially compatible when proven by tests:

- pure refactors that preserve key mutations and return values
- extra observability that does not affect control flow
- bug fixes where the previous behavior was already outside the documented contract and the compatibility rationale calls this out explicitly

## Runbook: Upgrading Scripts

1. Classify changes and update the registry rationale.
2. Add or update compatibility tests in `firemud-common` for any script tagged `compatible`.
3. Run the coordination upgrade planner from `dev-tools`.
4. If all changes are `compatible`, deploy through the normal rollout path.
5. If any script is tagged `requires_*_reset`, use the smallest required reset scope:
   - `requires_region_reset`
   - `requires_tenant_reset`
   - `requires_cluster_reset`
6. Confirm reset safety before resuming:
   - no `SCHEDULED` ledger rows remain for the affected scope
   - in-flight commands are retried or marked terminal appropriately

The registry, together with its golden compatibility tests, remains the single source of truth for whether coordination state can be safely replayed across script versions or must be reset.
