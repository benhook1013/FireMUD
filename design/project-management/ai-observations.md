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

- `2026-04-27`: Broad local proof catches repo-wide migration drift that service-local checks will miss
  - Context: after landing `game-design-service` and `game-session-service` work, `./gradlew :game-design-service:check -PfullCheck :game-session-service:check -PfullCheck` passed but the broader `./gradlew check` failed immediately on duplicate Flyway versioning in `entity-management-service`.
  - Observation: slice-local validation can look clean while unrelated migration numbering drift elsewhere on the branch still guarantees CI failure.
  - Expected pattern: when multiple branch lanes have been moving, run the repo-wide `./gradlew check` before or alongside push, and treat Flyway version sanity as shared branch hygiene rather than service-local ownership.

- `2026-04-28`: Local trusted-proxy defaults should include IPv6 loopback wherever localhost header trust is intentional
  - Context: removing `dev` profile semantics from `spring-cloud-gateway` exposed websocket bridge integration tests returning `403` on `/ws/game` even though they still sent the expected trusted tcp-proxy headers from localhost.
  - Observation: the gateway header-trust defaults only allowed `127.0.0.1/32`, but Java websocket clients in test mode connected from `::1`, so `HeaderTrustFilter` rejected the proxy headers before gameplay upgrade and the failure looked like a bridge regression.
  - Expected pattern: canonical localhost trust defaults should include both IPv4 and IPv6 loopback (`127.0.0.1/32` and `::1/128`) so local and test traffic do not silently depend on the host stack preferring IPv4.

- `2026-04-28`: Spring profile overrides can silently discard lower-precedence list config within the same subtree
  - Context: after removing the `dev` profile, `spring-cloud-gateway` test startup still returned `403` on trusted tcp-proxy websocket upgrades even though `application-test.yml` only changed the scalar `allow-insecure-headers-from-trusted-cidrs` flag.
  - Observation: the live bound `GatewayHeaderTrustProperties` kept the boolean override from `application-test.yml` but lost the `insecure-trusted-cidrs` list from `application.yml`, so the test profile ended up allowing insecure proxy headers from an empty trust set.
  - Expected pattern: when a profile-specific config file touches a subtree that contains list-valued `@ConfigurationProperties`, restate the full list in that profile or move the differing scalar to a separate property source; do not assume YAML/profile layering will merge list defaults the way map/scalar overrides do.

- `2026-04-28`: Local smoke seed data should be explicit deployment config, not hidden behind a broad Spring profile
  - Context: removing the last `dev` profile usage left the canonical Docker stack healthy, but the WebSocket smoke failed with `AUTH_INVALID_CREDENTIALS` because `account-service` only seeded the `demo@example.com` smoke account when the `dev` profile was active.
  - Observation: profile-gating deterministic smoke fixtures hides an operational contract behind a semantic bucket that is otherwise unrelated to account seeding, so profile cleanup can silently break blackbox verification even when the services boot correctly.
  - Expected pattern: if local or preview smoke depends on seeded demo identities, enable that seeding through an explicit env/property such as a smoke-fixture toggle in the deployment config rather than via a catch-all runtime profile.

- `2026-04-28`: Demo runtime topology fixtures should also be explicit deployment config, not a hidden local profile side effect
  - Context: after removing `application-dev.yml`, the direct Game Session smoke still failed on `LOGIN` with `SESSION_NOT_FOUND` because multiple service seeders were still behind `@Profile("dev")`, including the `game-session-service` bootstrap `GameInstance` and the paired Game Design / World Management / Entity Management demo runtime fixtures.
  - Observation: player-facing smoke proofs may depend on a cross-service seeded runtime graph, not just one demo account, so deleting a broad profile can leave the stack "healthy" while the first real admission path still has no playable bootstrap state.
  - Expected pattern: when blackbox smoke depends on deterministic runtime fixtures, gate those fixtures behind one explicit smoke/runtime-seeding deployment toggle and wire the entire local smoke stack through that toggle instead of scattering hidden `@Profile("dev")` seeders across services.

- `2026-04-28`: Fail-fast smoke harnesses need an explicit expected-error mode for negative-path assertions
  - Context: after hardening the WebSocket and Telnet smoke clients to abort immediately on explicit `ERROR` and `DISCONNECT` responses, the clean runtime smoke still failed at the final incompatible-wear assertion even though gameplay behavior was correct and the script intentionally expected `ERROR SLOT_INCOMPATIBLE`.
  - Observation: generic fail-fast transport handling and intentional application-error assertions are different concerns; if the harness does not distinguish them, negative-path proofs become false failures and hide the real product result.
  - Expected pattern: smoke helpers that short-circuit on explicit app errors should allow an expected-error mode whenever a scenario is intentionally proving a rejected command or policy outcome.

- `2026-04-28`: WebSocket cross-service transcript assertions can still be flaky even after the underlying behavior is correct
  - Context: validating the `09.1` account-presence and friend-presence routing bundle carry-through changes with `:game-session-service:check -PfullCheck`.
  - Observation: `CommunicationWebSocketCrossServiceTest` failed on two different transcript assertions in separate full-suite runs, but each individual failing test reran clean immediately with no code changes, pointing to timing-sensitive live-response capture rather than the presence-routing change itself.
  - Expected pattern: cross-service websocket transcript tests should either wait on the specific canonical message they are asserting or be treated as retryable/flaky until the harness stops relying on exact live ordering under suite load.

- `2026-04-29`: Shared gameplay harnesses must preserve full prompt-and-block transcript semantics, not just command markers
  - Context: migrating the large Telnet chained gameplay/account suite onto a shared `GameplayTelnetDriver` initially caused several false regressions because the first driver version returned as soon as it saw `OK LOOK`, truncating the rest of the multiline room/inventory transcript and breaking reconnect prompt-only paths.
  - Observation: transport helpers that optimize for marker detection can silently change the behavior under test when the real contract is a whole transcript block including prompt placement, blank-line boundaries, and timeout-returned partial output.
  - Expected pattern: FireMUD gameplay transport drivers should model canonical transcript boundaries deliberately, and any `...OrTimeout` helpers should preserve the old behavior of returning partial or prompt-only transcript truth when that is the thing the test is proving.

- `2026-04-29`: Full Gradle service checks can appear hung after green TCP proxy test result files are already written
  - Context: validating the `09.1` TCP proxy routing-bundle carry-through with `./gradlew :tcp-proxy-service:check -PfullCheck` completed compile, test, integration, and cross-service execution with fresh green XML result files, but the wrapper process remained alive quietly afterward.
  - Observation: for some larger service checks, the actionable proof may already be present in `build/test-results/**/TEST-*.xml` even while the wrapper is stuck in a quiet long-tail teardown or reporting phase, which can waste time if treated as an immediate source bug signal.
  - Expected pattern: when a service-wide Gradle run goes quiet after executing the meaningful suites, inspect the fresh result files and task progress before assuming a new product failure; if this remains common, add a repo-owned helper or guidance for distinguishing green-result long-tail hangs from real failing validation.

- `2026-04-29`: Shared cross-service test stacks must reset back to each suite's configured baseline fixtures, not a single global default
  - Context: the second-pass gameplay proof convergence work introduced a shared `GameplayCrossServiceStack`, and the first generic reset path silently reset the Entity Management stub back to the default room/entity fixture even for suites that intentionally booted chat-specific entities.
  - Observation: once mutable stub ownership moves into a shared stack, a generic `reset()` that restores only one global default can break unrelated suites by erasing their configured baseline room state, character identities, or names while still looking like a harmless cleanup helper.
  - Expected pattern: shared gameplay stack reset helpers should preserve or reapply the suite-specific baseline fixtures captured at stack construction time, and mutable stub reset should be treated as part of scenario isolation rather than a hardcoded global default.

- `2026-04-29`: Shared gameplay cross-service stacks also need a canonical clean-baseline helper, not just startup helpers
  - Context: re-auditing the converged gameplay proof showed `GameplayCrossServiceStack` now owns the expensive nested app bootstrap, but large websocket and telnet suites still hand-roll per-suite cleanup around it, including Redis flushes, `game_instances` deletion, screen-buffer clears, and first/default session seeding.
  - Observation: once startup is shared but reset-to-known-state remains local, new suites still copy slightly different isolation steps and the harness convergence stalls one layer short of the real repeated pattern.
  - Expected pattern: shared gameplay stack fixtures should expose one canonical “fresh gameplay baseline” helper that resets mutable stubs, clears Redis and replay buffers, wipes seeded runtime rows, and optionally seeds the default running game instance so cross-service suites do not keep rebuilding that cleanup choreography inline.
