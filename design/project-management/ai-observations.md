# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-04-12`: Do not collapse gameplay target dimensions into a two-slot command payload
  - Context: implementing `09.1` bootstrap discovery and server-resolved connect scope in `account-service` while checking how the current `PLAY` path in `game-session-service` consumes world selection.
  - Observation: the current text-command selection seam still risks treating gameplay target selection as `world + optional secondary`, which makes realm-aware routing awkward if `world`, `realm`, and `character` are not modeled as first-class dimensions end-to-end.
  - Expected pattern: canonical routing-sensitive command payloads should preserve the full selection structure they need for the target architecture instead of compressing multiple dimensions into one optional slot.

- `2026-04-13`: Do not maintain separate local world/realm catalogs per service once routing becomes a first-class system
  - Context: cohesion review across `account-service` bootstrap discovery and `game-session-service` lobby discovery after realm-aware command work.
  - Observation: the implementation has moved toward shared catalog and admission-pointer surfaces, but some code still carries config-backed world/realm discovery assumptions while `09.1` continues toward a canonical routing authority.
  - Expected pattern: bootstrap discovery, lobby discovery, connect-token issuance, and `PLAY` should all read one shared routing substrate rather than maintaining per-service local catalog truth.

- `2026-04-14`: Platform authority docs need a matching de-duplication rule in implementation
  - Context: SaaS/platform coherence review across `account-service` bootstrap discovery and `game-session-service` world/realm admission after the new `09.x` realm-routing work.
  - Observation: the architecture now says realm catalog and admission-pointer truth are control-plane/runtime authorities, but the repo still has places where that truth is represented through Spring config or local projection code while the canonical substrate is being completed.
  - Expected pattern: when a design promotes a concern to canonical control-plane authority, CI or slice planning should actively eliminate duplicated per-service config copies of that concern instead of letting them coexist as a quiet fallback.

- `2026-04-21`: Slice completion checkboxes need verification against proto and service seams
  - Context: checking monetization/account-lifecycle review findings against `02.1.6` showed the slice marks account export/delete/recovery as account-owned, while current Account Service REST, gRPC, and service methods still pass `tenantId` through export/delete and delete tenant-scoped billing records.
  - Observation: a checklist can drift from implementation when a broad slice lands adjacent auth/model work but leaves one claimed seam only partially changed.
  - Expected pattern: before marking a slice task complete, verify the public API schema, proto contracts, service implementation, and focused tests for that exact seam, not only the related architecture direction.
