# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, code smells, and "this should be shaped better" patterns discovered during AI work.

Only keep entries whose lesson still matters after the immediate task is done. Do not use this file as a bug log for ordinary fixes that were completed in the same piece of work. Prefer logging reusable observations that suggest a better repo rule, CI guard, design refinement, or shared implementation pattern.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-04-10`: Avoid parallel Gradle invocations against generated proto trees
  - Context: concurrent AI-triggered Gradle runs while validating `account-service` and `game-session-service` after `common-security` changes
  - Observation: overlapping builds against the same workspace produced bogus `javac` read errors in `services/common-security/build/generated/sources/proto/...` and misleading downstream compile failures that disappeared on a serial rerun
  - Expected pattern: run Gradle validations serially when they share the same repo checkout and generated-source directories, especially around protobuf generation and multi-module compile tasks

- `2026-04-12`: Do not collapse gameplay target dimensions into a two-slot command payload
  - Context: implementing `09.1` bootstrap discovery and server-resolved connect scope in `account-service` while checking how the current `PLAY` path in `game-session-service` consumes world selection
  - Observation: the current text-command selection seam effectively treats gameplay target selection as `world + optional secondary`, which makes later realm-aware routing awkward and encourages temporary config-backed shortcuts because `world`, `realm`, and `character` are not modeled as first-class dimensions end-to-end
  - Expected pattern: canonical routing-sensitive command payloads should preserve the full selection structure they need for the target architecture instead of compressing multiple future dimensions into one optional slot

- `2026-04-12`: Localized player-output variants need explicit message keys or updated bundles
  - Context: updating `PLAY` guidance strings in `game-session-service` to become realm-aware after the new `09.1` bootstrap/connect-scope work
  - Observation: changing Java-side error text alone did not change rendered localized output because `TextPlayerOutputRenderer` prefers `messageKey` templates from `presentation-messages*.properties`, so reusing a generic key for a more specific guidance path silently produced stale or wrong user-facing text
  - Expected pattern: when a command path introduces a new user-visible guidance variant, add a distinct message key and update the locale bundles in the same change instead of assuming the raw fallback message will be rendered

- `2026-04-12`: Runtime tenant admission cannot stay encoded as `accounts.tenant_id`
  - Context: tracing `09.2` public-production onboarding from `PLAY` and connect-token issuance back into `account-service`
  - Observation: the current runtime-membership path still answers "can this account play in tenant X?" by comparing `accounts.tenant_id` to the requested tenant, which makes first-join onboarding, multi-realm discovery, and future multi-tenant routing look implemented when they are really resting on a single-tenant shortcut
  - Expected pattern: gameplay admission should read a dedicated membership/grant substrate and use explicit writer boundaries like `EnsurePublicProductionPlayerMembership(...)` rather than treating account ownership fields as the long-term runtime authority

- `2026-04-13`: Do not maintain separate local world/realm catalogs per service once routing becomes a first-class system
  - Context: cohesion review across `account-service` bootstrap discovery and `game-session` lobby discovery after the `09.1` realm-aware command work
  - Observation: `account-service` and `game-session` currently each keep their own world/realm config model, with different fields and different authority assumptions, which makes the player-facing flow look unified while hiding routing drift and duplicated cutover work
  - Expected pattern: once world/realm selection is a canonical gameplay-routing concern, bootstrap discovery, lobby discovery, connect-token issuance, and `PLAY` should all read one shared routing substrate rather than maintaining per-service local catalogs

- `2026-04-13`: Indexed Spring config overrides need null-safe catalog readers
  - Context: `game-session-service` websocket integration coverage after switching to shared `GameplayCatalogProperties`
  - Observation: test-only indexed property overrides that set only nested fields like `realms[0].tenant-id` can leave partially bound parent objects in the list, which made `GameplayWorldCatalog.resolveWorld(...)` crash on `null` slugs instead of treating the malformed entry as invisible
  - Expected pattern: shared config-backed catalogs should filter out null or incomplete entries before command/runtime code touches them, and tests that override indexed config should provide full object definitions when the list is used as canonical routing input

- `2026-04-13`: Cross-service fake authorities must track canonical RPC growth
  - Context: validating the new `EnsurePublicProductionPlayerMembership(...)` boundary after the `09.2` membership/catalog batch
  - Observation: multiple cross-service suites still implemented only the older membership and entitlement RPCs in inline fake Account Service stubs, so behavior that should have failed closed on admission instead degraded into `MEMBERSHIP_AUTH_UNAVAILABLE` or socket timeouts because the fake authority no longer matched the real service boundary
  - Expected pattern: when a canonical service boundary grows, shared or inline cross-service fakes need to implement the new RPC set in the same change so tests continue exercising behavior rather than collapsing into artificial infrastructure failures

- `2026-04-14`: Manual JSON projections on gRPC wrappers drift from DTO contracts
  - Context: extending `account-service` profile data with cross-game presence visibility policy and consuming it from `game-session-service`
  - Observation: `AccountGrpcService.getProfile(...)` was manually rebuilding a tiny JSON object with only `displayName` and `bio`, so the new `presenceVisibilityPolicy` field silently vanished from the cross-service contract even though the DTO, database, and tests all changed together
  - Expected pattern: when a gRPC surface intentionally tunnels a DTO as JSON, serialize the canonical DTO directly or use one shared mapper/projection helper instead of hand-maintaining partial object-node projections field by field

- `2026-04-14`: Platform authority docs need a matching de-duplication rule in implementation
  - Context: SaaS/platform coherence review across `account-service` bootstrap discovery and `game-session-service` world/realm admission after the new `09.x` realm-routing work
  - Observation: the architecture now says realm catalog and admission-pointer truth are control-plane/runtime authorities, but the repo still encodes that truth as duplicated Spring config in multiple services, which makes cutover, visibility, grants, and suspension behavior look coherent in docs while implementation still rests on a local single-game shortcut
  - Expected pattern: when a design promotes a concern to canonical control-plane authority, CI or slice planning should actively eliminate duplicated per-service config copies of that concern instead of letting them coexist as a quiet fallback

- `2026-04-14`: gRPC adapters should normalize absent proto scalars before crossing internal service seams
  - Context: extending `06.3.2` stack-family selectors through the Entity Management gRPC boundary
  - Observation: optional proto string fields such as `stackFamilyKey` arrive as `""` when unset, and letting that raw value flow into internal service mocks and implementations creates a false third state (`blank but set`) that the canonical Java seam does not actually want
  - Expected pattern: gRPC adapters should collapse blank optional scalars to `null` or one canonical internal representation at the boundary, so downstream services and tests do not have to reason about transport-default noise
