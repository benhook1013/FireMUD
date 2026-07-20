# ADR 0138: Expiring Playtest Grants with Bounded Active Revocation

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PLAYTEST-01`
- Primary capability: `AR-3.4`
- Affected capabilities: `AA-3.2`, `AA-2.2`, `PO-1.2`, `EA-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of grant authority, expiry, revocation, connected-session treatment, and scheduled playtest endings

## Context

Playtest realms contain unreleased designs and may contain isolated copies of production-derived state. They therefore require explicit per-account access rather than implicit tenant membership or public discovery. FireMUD already has an Account-owned grant substrate and admission-time checks, but the earlier creator journey treated revocation as forward-looking: a removed tester could keep using an already connected session until it ended naturally. That conflicts with the accepted authority rule in [ADR 0030](./adr-0030-risk-based-active-session-revocation.md), under which loss of a required private-realm grant terminates the affected binding.

Revocation and a friendly scheduled ending solve different problems. Revocation means the account no longer has authority now. A scheduled ending may give testers advance notice and a bounded opportunity to finish before authority is removed. Using one ambiguous operation for both would either preserve revoked access or make ordinary planned endings unnecessarily abrupt.

The current grant row also has no expiry, request-replay record, scoped tenant-admin surface, or retained revocation tombstone. Deleting a row discards the ordering evidence needed to stop a stale grant retry from recreating access.

## Decision

### Account Owns Explicit Grants

Account Service is the sole grant writer and read authority for non-public realms. `tenantAdmin` routinely creates, extends, lists, and revokes tester grants only within the exact tenant it administers. `platformAdmin` may perform the same operation only through a distinct reasoned and audited break-glass path. A grant mutation validates the exact tenant, world, realm, account, current fork lifecycle, and caller authority; a realm slug or caller-supplied tenant identifier is not sufficient proof.

The normal playtest access record is scoped by `{accountId, tenantId, worldSlug, realmSlug}`. It carries its active or revoked state, monotonic `grantRevision`, grantor and audit metadata, `requestId`, and explicit `grantExpiresAt`. Account remains the authority for this record; Game Session and client surfaces consume its result and do not maintain independent grant stores.

### Expiry Is Bounded and Explicit

Every playtest fork has an expiry, and every tester grant has an explicit expiry no later than the fork's then-current expiry. Effective access ends at:

```text
min(grantExpiresAt, forkExpiresAt)
```

Extending the fork does not silently extend its tester grants. Extending one tester grant does not extend another. Every extension is an explicit authorized mutation, revalidates the current fork expiry, advances the grant revision, and is visible in the audit trail.

At or beyond effective expiry the grant is not valid for discovery, connect-token issuance, `PLAY`, reconnect, or continued gameplay authority. Expiry follows the same active-binding cutoff contract as explicit revocation.

### Monotonic Idempotent Mutation

Create, extend, revoke, and deliberate re-grant commands are idempotent by stable `requestId` and ordered by a monotonic revision for the grant scope. Repeating a completed request returns the same semantic outcome. Older or duplicate commands cannot replace newer state.

Revocation records a tombstone with its revision rather than immediately deleting the authority history. A late retry of the earlier grant or extension cannot resurrect access. A later intentional re-grant is a new authorized command that observes the latest tombstone, advances beyond it, and supplies a new bounded expiry. Tombstone compaction is allowed only after the retention and deduplication horizons make stale mutation replay impossible.

### Revocation Removes Current Authority

Revocation or expiry:

- hides the playtest realm from the affected account's discovery results;
- denies fresh connect-token issuance, `PLAY`, and reconnect;
- fences new command admission for every affected active binding; and
- causes Game Session, as gameplay-binding and socket owner, to terminate those bindings within the bounded authority-revocation contract from ADR 0030.

Already durably admitted work may finish idempotently once after the fence. This prevents partially committed operations without allowing another command to enter under removed authority. Account publishes the monotonic authority change; Game Session consumes it through bounded indexes and reconciliation rather than performing an Account read on every gameplay command.

A friendly scheduled playtest ending is separate: close admission, complete the bounded realm drain, then revoke or expire grants. Grant revocation itself is never reinterpreted as a request to let current sessions drain indefinitely.

### Playtest State Remains Isolated

This access decision does not change [ADR 0126](./adr-0126-isolated-playtest-state-modes-and-reset.md). Fresh, seeded, and snapshot playtests retain fork-local playable-state namespaces, all-or-nothing preparation, isolated external effects, reset by namespace replacement, and no automatic merge-back into production.

## Consequences

- Removing a tester actually removes access within an established bound instead of leaving unreleased state reachable through an old socket.
- Planned endings can still be friendly, but creators must initiate close-and-drain before revoking grants.
- Explicit per-grant expiry prevents a fork extension from unexpectedly renewing every prior invitation.
- Monotonic tombstones and idempotent commands add durable state and retention work, but prevent delayed retries from reversing revocation.
- Runtime commands do not gain a per-action Account lookup. Revocation uses the existing event and bounded reconciliation model.
- Creator tooling must expose expiry and extension clearly rather than presenting grants as timeless membership.

## Alternatives Considered

### Forward-Looking Revocation

Block future discovery, reconnect, and `PLAY` but let a connected tester remain until natural disconnect. This is operationally simple and can make planned endings feel gentle, but it allows a removed tester to inspect and mutate unreleased playtest state for an unbounded session. Close-and-drain provides the friendly planned path without weakening revocation.

### Always Eject Every Tester at a Scheduled End Time

Treat expiry and every planned closure as an abrupt kick. This removes access decisively but loses the normal lifecycle's bounded drain and conflates scheduling with emergency authority removal. The selected model lets creators close and drain before the effective grant cutoff.

### Implicit Tenant or Creator Membership

Let tenant membership, content roles, or platform staff status imply playtest visibility. This reduces grant administration but exposes private realms to broader groups than the creator explicitly invited and makes removal depend on unrelated role changes. Explicit Account-owned grants are retained.

### Hard-Delete Revocations

Delete the grant row and treat absence as denial. This is easy to query, but an old create or extension retry can recreate the row because no newer revocation evidence remains. Revisioned tombstones preserve ordering.

## Implementation and Proof Obligations

The current implementation is partial. Account persists and reads explicit grants, and admission paths can deny a missing grant. It does not provide grant expiry or effective fork-expiry evaluation, tenant-scoped role enforcement on creator management APIs, request-replay idempotency, durable revocation tombstones, or an end-to-end active-binding ejection path. Current mutation is exposed only through a globally privileged internal surface, revocation deletes the row, and creator-facing account search, list, extension, and expiry UX are absent.

Implementation must add monotonic persisted grant state, explicit bounded expiry, idempotent mutation outcomes, retained tombstones, exact realm and tenant validation, tenant-admin and audited break-glass surfaces, authority-change delivery, and targeted Game Session termination using the established active-binding indexes and reconciliation bound. Grant reads and all discovery/admission surfaces must agree on the effective expiry and latest revision.

Proof must cover grant, extension, expiry, explicit revocation, deliberate re-grant, duplicate and reordered commands, delayed create after revoke, concurrent extension and revoke, fork expiry and extension, wrong tenant/realm/caller, tenant-admin versus platform break-glass authority, discovery hiding, connect-token and `PLAY` denial, active command fencing, already-admitted work completion, targeted socket termination, missed-event reconciliation, authority outage, reconnect denial, scheduled close-and-drain, reset isolation, and absence of production merge-back.

## Reversibility and Revisit Triggers

Grant UI, expiry defaults, tombstone retention, delivery transport, and reconciliation interval may evolve while preserving explicit Account authority, bounded effective expiry, monotonic idempotent mutation, immediate removal of gameplay authority, and separate scheduled-drain semantics. Revisit the per-account grant shape only if a concrete playtest product requires group invitations or delegated tester cohorts; any replacement must retain exact audience accountability and equally strong revocation ordering.

## Required Documentation Alignment

- `design/architecture/system-architecture-authentication.md`
- `design/architecture/microservices/account-service/api-contracts.md`
- `design/architecture/user-journeys-creators.md`
- `design/architecture/user-journeys-operators.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/decisions/adr-0030-risk-based-active-session-revocation.md`
- `design/architecture/decisions/adr-0126-isolated-playtest-state-modes-and-reset.md`
