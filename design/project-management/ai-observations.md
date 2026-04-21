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

- `2026-04-13`: Cross-service fake authorities must track canonical RPC growth
  - Context: validating the new `EnsurePublicProductionPlayerMembership(...)` boundary after the `09.2` membership/catalog batch.
  - Observation: multiple cross-service suites previously implemented only older membership and entitlement RPCs in inline fake Account Service stubs, so new behavior collapsed into artificial infrastructure failures instead of exercising the intended runtime path.
  - Expected pattern: when a canonical service boundary grows, shared or inline cross-service fakes need to implement the new RPC set in the same change so tests continue exercising behavior rather than timing out or failing as unavailable infrastructure.

- `2026-04-14`: Platform authority docs need a matching de-duplication rule in implementation
  - Context: SaaS/platform coherence review across `account-service` bootstrap discovery and `game-session-service` world/realm admission after the new `09.x` realm-routing work.
  - Observation: the architecture now says realm catalog and admission-pointer truth are control-plane/runtime authorities, but the repo still has places where that truth is represented through Spring config or local projection code while the canonical substrate is being completed.
  - Expected pattern: when a design promotes a concern to canonical control-plane authority, CI or slice planning should actively eliminate duplicated per-service config copies of that concern instead of letting them coexist as a quiet fallback.

- `2026-04-14`: gRPC adapters should normalize absent proto scalars before crossing internal service seams
  - Context: extending `06.3.2` stack-family selectors through the Entity Management gRPC boundary.
  - Observation: optional proto string fields such as `stackFamilyKey` arrive as `""` when unset, and letting that raw value flow into internal service mocks and implementations creates a false third state (`blank but set`) that the canonical Java seam does not actually want.
  - Expected pattern: gRPC adapters should collapse blank optional scalars to `null` or one canonical internal representation at the boundary, so downstream services and tests do not have to reason about transport-default noise.

- `2026-04-18`: Cross-service auth seams need shared contract tests for canonical role and delegation rules
  - Context: shared auth cleanup across common middleware, service interceptors, and gameplay-domain delegation.
  - Observation: shared role names are now centralized, but the deeper cross-service contract still spans JWT caller identity, privileged roles, tenant-scoped roles, and Game Session-issued `SessionAttestation` for gameplay delegation.
  - Expected pattern: keep shared auth contract tests broad enough to validate role-name acceptance/rejection and delegated gameplay identity rules before new slices extend these seams.

- `2026-04-21`: After repeated CI-only failures, switch from narrow local checks to CI-mirroring proof
  - Context: preview/auth/formatting fixes kept surfacing additional issues remotely because local validation used narrower commands than the actual CI jobs, or skipped the canonical Docker-inclusive smoke proof after repeated failures.
  - Observation: once a branch has already shown multiple remote-only failures, continuing to push after targeted local checks wastes time and review bandwidth.
  - Expected pattern: after repeated remote failures, default immediately to CI-mirroring local validation for the touched service (`:<service>:check -PfullCheck`) and run the canonical Docker smoke/bootstrap proof for runtime-sensitive paths before pushing again.

- `2026-04-21`: TCP Proxy cross-service shutdown still emits noisy async disconnect warnings
  - Context: running `:tcp-proxy-service:check -PfullCheck` after adding the Telnet item/equipment parity proof.
  - Observation: the suite passes, but shutdown can still log `CANCELLED: Channel is forcefully shutdown` from async disconnect notifications after the nested Game Session channel has already closed, which makes successful test output look suspicious.
  - Expected pattern: cross-service teardown should drain or suppress expected post-shutdown disconnect notifications so warnings remain actionable.
