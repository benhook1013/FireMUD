# Review Checklists

Use these checklists when creating architecture reviews, completing capabilities in domain implementation trackers, or changing cross-cutting contracts.

## Architecture Review Checklist

- Cross-check findings against relevant domain implementation trackers, canonical design, proto contracts, and current service code before recording an item as an open implementation blocker.
- If code or tracker notes already resolve a target-state doc gap, classify the item as doc import/drift work instead of an implementation blocker.
- Auth/session reviews must include the gateway, session-behavior, authz route matrix, Account runtime docs, Game Session runtime docs, and the `realm-routing-and-playable-state.md` and `player-access-and-session.md` trackers.
- Scripting/runtime reviews must treat `system-architecture-scripting-normative-contract-tables.md` as the first update target before sibling runtime, service, observability, proto, or tracker docs add exceptions.
- Observability reviews must check architecture docs, reference PromQL, dashboards, and the relevant capability-support docs under `slice-support/` when metric-label policy changes.

## Capability/Tracker Completion Guide

- Verify the claimed capability outcome against every public contract it owns: HTTP/OpenAPI, gRPC/proto, event or outbox, and operator-facing contracts where applicable. Apply each check only to surfaces the capability owns; for an absent HTTP/OpenAPI or proto/gRPC surface, an explicit `N/A` with a brief reason is valid, but it does not permit omitting a required surface.
- Verify the live implementation seam, including concrete services, controllers, handlers, and orchestration, rather than only adjacent architecture direction.
- Confirm that the named canonical owner in the tracker is the owner in code and that no local fallback or competing authority is silently carrying the behavior.
- Verify focused unit, integration, or cross-service tests for the exact seam. If the capability claims fail-closed or replay-safe behavior, verify the negative-path tests too.
- Prefer narrow unit/integration/cross-service proof over interpreting an unrelated broad test pass as evidence.
- For account lifecycle, tenant authorization, and routing capabilities, confirm whether the seam is account-global, tenant-scoped, realm-scoped, or game-instance-scoped and make the tracker wording match the implementation.
- Update the relevant domain implementation tracker and canonical design when the closure proof changes the recorded behavior, ownership, or remaining work. Update the implementation-tracking index only when tracker scope changes.
- For cross-service contract growth, update shared fakes/test fixtures in the same change as the canonical RPC.

Before closing a capability, answer yes to each applicable question and record `N/A` with a brief reason for an absent surface:

1. For each owned public API surface, does the schema match the capability claim?
2. For each owned proto/gRPC surface, does the contract match the capability claim?
3. Does the implementation route through the canonical owner rather than a local fallback?
4. Do focused tests cover the exact seam, including required negative paths?
5. Does the domain tracker describe only the current implementation and real remaining work?

If any answer is no, leave the capability incomplete or complete only at its explicitly bounded current boundary. Add an `Implementation Notes` or `Current Remaining Work` entry naming the unresolved seam and record the follow-up in the owning domain tracker rather than relying on a doc-only completion claim.
