# FireMUD Redis Script Rollout and Compatibility

This document defines adopter, registry, test, and runbook mechanics for Lua script evolution without violating the replay and reset assumptions of Coordination Redis. The deterministic script, outcome, schema-validation, and compatibility authority remains [Redis Lua Patterns](./system-architecture-redis-lua-patterns.md).

## Lua Compatibility Modes and Rollout Matrix

Script changes are classified into a small set of compatibility modes:

- `compatible`
  - semantically safe for every caller and stored-payload version evidenced to coexist, with unchanged ownership, fencing, idempotency, validation, outcomes, and no-partial-mutation guarantees
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

- `schemaVersionsSupported`, with the evidence and retention window that makes each version possible
- `KEYS` / `ARGV` contract
- `outcomesSupported`
- compatibility tag and rationale
- versionless legacy interpretation only when its one unambiguous shape is proven

For the purposes of this registry, `compatible` is intentionally narrow and evidence-scoped:

- it does not allow changes that alter return codes for valid input
- it does not allow turning a no-op into a mutating path or vice versa
- it does not allow reinterpreting existing payload semantics during AOF replay
- it does not imply a permanent `N`/`N-1` support promise; obsolete versions may be removed only after deployment and retained-data evidence closes their coexistence window
- an unknown or ambiguous version must fail before mutation, and a missing version is accepted only under the recorded, proven legacy rule

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
4. If all changes are `compatible` for the evidenced coexistence set, deploy through the normal rollout path.
5. If any script is tagged `requires_*_reset`, use the smallest required reset scope:
   - `requires_region_reset`
   - `requires_tenant_reset`
   - `requires_cluster_reset`
6. Confirm reset safety before resuming:
   - no `SCHEDULED` ledger rows remain for the affected scope
   - in-flight commands are retried or marked terminal appropriately

The registry, together with its golden compatibility tests, remains the single source of truth for whether coordination state can be safely replayed across script versions or must be reset.

When coexistence is unsafe, select the smallest reset scope that removes the incompatible coordination state under the fenced reset and reconciliation workflow. Do not widen a reset merely because a script change is difficult to classify. The canonical evidence gate and reset consequence are recorded in [ADR 0084](./decisions/adr-0084-evidence-scoped-redis-lua-compatibility.md).
