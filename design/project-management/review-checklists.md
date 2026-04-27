# Review Checklists

Use these checklists when creating architecture reviews, marking vertical-slice tasks complete, or changing cross-cutting contracts.

## Architecture Review Checklist

- Cross-check findings against relevant vertical-slice docs, proto contracts, and current service code before recording an item as an open implementation blocker.
- If code or slice notes already resolve a target-state doc gap, classify the item as doc import/drift work instead of an implementation blocker.
- Auth/session reviews must include the gateway, session-behavior, authz route matrix, Account runtime docs, Game Session runtime docs, and active `09.x` realm-routing/admission slices.
- Scripting/runtime reviews must treat `system-architecture-scripting-normative-contract-tables.md` as the first update target before sibling runtime, service, observability, proto, or slice docs add exceptions.
- Observability reviews must check architecture docs, reference PromQL, dashboards, and slice-support docs when metric-label policy changes.

## Slice Completion Checklist

- Verify the public API schema or proto method for the exact seam being marked complete.
- Verify the service implementation and focused tests for that exact seam, not only adjacent architecture direction.
- For account lifecycle, tenant authorization, and routing slices, confirm whether the seam is account-global, tenant-scoped, realm-scoped, or game-instance-scoped and make the slice wording match the implementation.
- For cross-service contract growth, update shared fakes/test fixtures in the same change as the canonical RPC.
