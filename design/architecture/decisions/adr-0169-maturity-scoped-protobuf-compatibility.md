# ADR 0169: Maturity-Scoped Protobuf Compatibility

## Status

Accepted

## Decision Record

- Decision date: 2026-07-21
- Decision key: `GRPC-02`
- Disposition: `revised`
- Primary capability: `SF-1.1` service contracts and compatibility
- Affected capabilities: `SF-2.3`, `PO-3.1`, `PO-4.3`, `AS-1.5`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of pre-v1 convergence, rolling deployment, rollback, external consumers, persisted messages, Buf baselines, and at-least-once event safety

## Context

All current protobuf packages use a `v1` namespace, but FireMUD has not released a supported external gRPC API. Treating every current field and RPC as permanently compatibility-protected would canonize pre-v1 design residue and force meaningless `v2` packages. The opposite extreme is also unsafe: internal old and new binaries may overlap during a rolling deployment or rollback, and persisted messages or delayed events can outlive the producing binary.

The repository describes Buf breaking checks, but current CI generates protobuf sources without enforcing a deliberate compatibility baseline. A moving development branch is not automatically the correct baseline for either deployed binaries or a future supported external release.

## Decision

The `v1` package name is the current namespace, not by itself a promise that every pre-v1 contract is permanently frozen.

An internal pre-v1 contract may converge incompatibly in its existing `v1` package when there is no supported external consumer, no retained wire representation requiring the old schema, and every caller and server can move as one coordinated change. Its release is explicitly recreate/coordinated or roll-forward-only unless compatibility with the previous binaries is separately proven. Pre-v1 breakage does not require a `v2` package.

When old and new internal binaries must overlap for rolling deployment or bounded rollback, the change uses a temporary additive bridge in the existing `v1` contract. Both sides tolerate the transition, rollout ordering is explicit, and the obsolete field or RPC is removed only after no supported binary or rollback target requires it. The final pre-v1 contract still converges in `v1`.

A contract becomes compatibility-protected for the relevant support window when any of these conditions applies:

- external clients or SDKs may upgrade independently;
- a deployment claims mixed-version rolling compatibility;
- a release claims binary rollback to an older build;
- retained protobuf messages, delayed events, or durable records may be read by another schema generation.

Protected contracts evolve additively for the promised window. A parallel `v2` is required only when a formally supported `v1` contract must continue serving independently upgraded external consumers or another deliberately long-lived compatibility window while an incompatible replacement is introduced. It is not the normal response to pre-v1 internal redesign.

Buf linting applies now. Breaking checks use a baseline that matches the promise being made: the exact deployed or rollback-target proto set for operational compatibility, and an immutable supported release/tag/digest for external compatibility. The project does not canonize an arbitrary development snapshot as the permanent first baseline.

Within a protected window, new fields use new numbers, presence-sensitive fields use explicit presence, removed fields reserve both number and name, enum consumers handle unknown values, and existing field meaning or type is not silently changed.

At-least-once event sinks and streams carry stable event identity and retain dedupe state for the declared retry window. Dedupe is not sole authority: authoritative binding, generation, version, or mutation guards must make a duplicate or late event harmless after dedupe expiry or coordination reset. If that cannot be proven, the owning durable store retains the idempotency result.

## Consequences

- Pre-v1 contracts can converge directly without accumulating fake public API generations.
- Rolling deployment and rollback remain explicit compatibility claims rather than accidental consequences of using a `v1` package.
- Temporary bridges add bounded migration work when mixed versions genuinely must coexist.
- CI needs environment/release-aware baselines instead of one simplistic global comparison.
- Event consumers cannot rely on a short-lived Redis dedupe key to protect a newer authoritative binding.

## Alternatives Considered

### Freeze Every Current `v1` Contract

This is mechanically simple but turns unreleased internal shapes into permanent compatibility debt and produces unnecessary `v2` packages during pre-v1 design convergence.

### Allow Arbitrary Pre-v1 Breakage During Rolling Deployment

This preserves development speed but can make old and new pods mutually unintelligible and invalidate rollback. Pre-v1 product status does not remove binary-overlap requirements.

### Introduce `v2` for Every Incompatible Change

This is appropriate after a supported contract must coexist with its replacement, but it is needless ceremony for coordinated pre-v1 internal convergence.

## Implementation and Proof Obligations

CI must run Buf lint and support deliberate breaking baselines for compatibility-protected releases. Release evidence must classify changed contracts as coordinated/recreate, roll-forward-only, rolling-compatible, or rollback-compatible and prove any claimed overlap. Focused tests must cover transitional old/new caller-server combinations where a bridge is used, unknown enum handling, reserved removals, persisted-message readers where applicable, and late/duplicate event delivery after dedupe loss.

## Reversibility and Revisit Triggers

Protection can be added without changing the model when a contract gains external consumers, retained wire data, or rolling/rollback support. Revisit the package-version policy when FireMUD publishes its first supported external gRPC API or needs two externally supported major contract generations concurrently.
