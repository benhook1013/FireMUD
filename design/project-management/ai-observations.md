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

- `2026-04-14`: Dual presence stores need one lifecycle coordinator, not repeated paired calls
  - Context: `02.1.3` follow-through in `game-session-service` after presence and recent-presence seams had spread across `PLAY`, websocket close, TCP proxy disconnect handling, and `LOGOUT`
  - Observation: when live presence and bounded recent-presence are both updated directly from multiple handlers, the system keeps working only as long as every caller remembers the same order and pairing, which makes future lifecycle changes easy to miss and hard to prove
  - Expected pattern: route connect, activity, and disconnect mutations through one authoritative lifecycle service so handler code stays thin and proof coverage can target one canonical seam

- `2026-04-14`: Publish completion cannot be modeled as "persist version now, best-effort export later"
  - Context: starting `08.1` in `game-design-service`, where full publish created a `version` row and then swallowed asset-export failures in an after-commit callback
  - Observation: that shape lets the system report a version as published even when export or release attestation never succeeded, which is exactly the kind of half-complete launchable state the design is trying to eliminate
  - Expected pattern: full publish should finish only after required exported artifacts and immutable release attestation both exist, and failures in that path should fail closed instead of degrading into warning-only background behavior

- `2026-04-14`: Failure-audit ledgers need their own transaction boundary when the main operation is expected to roll back
  - Context: implementing the `08.1` durable `publish_attempt` / participant-observation framework in `game-design-service`
  - Observation: if failed publish-attempt rows and participant observations are written in the same transaction as the publish operation itself, the exact audit trail you wanted to keep for diagnosis disappears with the rollback, leaving only logs and making the durable ledger misleadingly sparse
  - Expected pattern: durable failure/audit ledgers that are meant to explain rolled-back operations should use an explicit independent transaction boundary or equivalent commit mechanism so failure evidence survives the main operation rollback

- `2026-04-14`: Cross-service tests that disable Flyway need one shared canonical schema helper for runtime tables
  - Context: landing `08.3` launch-descriptor/preflight changes while validating tcp-proxy and game-session cross-service proofs
  - Observation: several cross-service suites run `game-session-service` under the `test` profile with Flyway disabled and then hand-create `game_instances`, so the new launch-descriptor columns existed in the real service schema but not in the test-only manual table definitions, causing misleading websocket/login failures far away from the actual contract change
  - Expected pattern: cross-service suites that bypass migrations should derive runtime-table setup from one shared helper or fixture that tracks the canonical entity shape, rather than duplicating hand-written `CREATE TABLE` fragments in each test class

- `2026-04-15`: Repo-wide migration scanners must tolerate partially populated `build/` trees
  - Context: running the required final `./gradlew check` after the `08.1` digest-participant batch
  - Observation: `dev-tools/check-flyway-versions.py` walked `services/**` broadly enough that a missing generated directory under `services/account-service/build/resources/main/db` raised `FileNotFoundError` and failed the whole build, even though the actual migration inputs live under source trees and build output population is intentionally task-dependent
  - Expected pattern: repo-wide source scanners should either ignore `build/` trees or gracefully skip disappearing/generated directories so validation reflects source-state invariants instead of incidental task ordering

- `2026-04-15`: Nested cross-service migration helpers must resolve the target module from repo root, not the host test module
  - Context: fixing `:tcp-proxy-service:crossServiceTest` after `game-session-service` gained `V7`/`V8` migrations for launch descriptors and admission-pointer authority
  - Observation: a fallback helper that returned `src/main/resources/db/migration` from the current working directory silently pointed the nested Game Session app at `tcp-proxy-service`'s own migration folder, so Flyway validated and applied only `V1__init.sql` and the failure showed up later as missing Game Session tables
  - Expected pattern: nested-service harnesses should walk up to repo root and then target `services/<module>/src/main/resources/db/migration` explicitly, so Gradle's per-module working directory does not redirect Flyway to an unrelated service schema

- `2026-04-16`: Fresh-bootstrap-safe schema changes must live in one canonical migration layer
  - Context: fixing CI smoke after `account-service` fresh startup failed on `profiles.presence_visibility_policy` already existing
  - Observation: the same feature had been expressed both by editing `V1__init.sql` and by keeping a later `V16__profile_presence_visibility.sql`, which let existing upgraded databases appear fine while fresh bootstrap replayed both definitions and broke at startup
  - Expected pattern: when initial-development cleanup folds a schema change into the bootstrap schema, later numbered migrations for that same change must be removed or made explicitly idempotent in the same batch so fresh and upgraded database paths stay aligned

- `2026-04-18`: Canonical smoke proofs must gate on full compose health, not only one happy-path client flow
  - Context: investigating why `dev-tools/verify-fresh-bootstrap.sh` and `dev-tools/verify-restart-state.sh` still passed while `game-design-service` was crash-looping in the local Docker stack
  - Observation: the scripts booted compose and immediately ran the Telnet `WORLDS -> LOGIN -> PLAY -> LOOK` proof, which let a non-gameplay-critical service spin in the background without failing the supposedly canonical bootstrap check
  - Expected pattern: smoke bootstrap scripts should first assert that every long-running required compose service is running and healthy, then run the gameplay proof so false-positive stack health cannot hide behind one successful player path
- 2026-04-18: Cross-service auth seams are drifting because shared enforcement helpers still encode legacy role names (`admin`) and generic bearer-token assumptions while the canonical architecture now standardizes `tenantAdmin` and Game Session-issued `SessionAttestation` for gameplay-domain delegation. Add one canonical shared auth contract test suite that validates role-name acceptance/rejection and delegated gameplay identity rules across common middleware, service interceptors, and service-level docs before new slices extend these seams.

- `2026-04-18`: Dev-profile JWT fallbacks must not generate per-service secrets in any topology that exercises cross-service auth
  - Context: investigating runtime-images smoke after direct WebSocket `WORLDS -> LOGIN -> PLAY -> LOOK` timed out even though the Docker stack was otherwise healthy
  - Observation: several services still omitted `firemud.auth.jwt-secret` in `application.yml`, so the `dev` profile silently generated a different random JWT secret per service; basic gameplay flows kept working until a cross-service auth seam like Game Session -> Account profile lookup was exercised, where it failed as `UNAUTHENTICATED: Invalid token`
  - Expected pattern: every service that signs or validates shared JWTs must bind the same env-backed secret in the base config used by local Docker and smoke paths, so dev/smoke behaves like one coherent topology instead of a collection of isolated random secrets

- `2026-04-18`: Shared JWT auto-configuration must key off the canonical bean name, not any bean of the same type
  - Context: centralizing per-service `AuthConfig` / `AuthProperties` boilerplate into `common-security`
  - Observation: `game-session-service` already had a second `JwtUtil` for first-party connect-context tokens, so `@ConditionalOnMissingBean(JwtUtil.class)` and `@ConditionalOnBean(JwtUtil.class)` in shared auth auto-config incorrectly treated that auxiliary token utility as the canonical cross-service auth signer and suppressed the named `jwtUtil` bean needed by gRPC client auth
  - Expected pattern: shared security auto-config should guard and depend on the explicit canonical bean name (`jwtUtil`) when multiple token utilities can coexist in one service, so auxiliary JWT seams do not accidentally disable the main cross-service auth path

- `2026-04-18`: Shared gRPC auth must be registered through one canonical path, not both global and per-service bindings
  - Context: moving repeated per-service `GrpcConfig` auth wrappers into `common-security`
  - Observation: once `AuthTokenInterceptor` was auto-configured as a global server interceptor, services that still attached the same interceptor directly on `@GrpcService` double-applied auth and produced misleading cross-service/runtime failures that looked like game logic or communication regressions
  - Expected pattern: register shared server auth once, either globally or by service-local binding, and remove the other path in the same change so the interceptor contract stays singular and predictable

- `2026-04-18`: Nested multi-service test harnesses must set one explicit shared JWT secret
  - Context: validating the shared-auth cleanup in `game-session-service` and `tcp-proxy-service` cross-service suites
  - Observation: after auth bootstrap was centralized, nested test apps launched under the `test` profile started honestly failing cross-service gRPC because each service generated its own fallback JWT secret unless the harness injected a common `firemud.auth.jwt-secret`
  - Expected pattern: any test harness that boots multiple Spring services which authenticate to each other should provide one shared explicit JWT secret up front, rather than depending on dev/test fallback generation or service-local defaults

- `2026-04-18`: Shared JWT/session auth should cover servlet HTTP the same way it covers gRPC
  - Context: reviewing the next round of per-service Spring/config boilerplate after centralizing gRPC auth bootstrap
  - Observation: six services had kept near-identical `JwtAuthInterceptor` and `WebConfig` classes even though the only real variation was path allowlists and whether the route needed any authenticated caller or a privileged admin/moderator role
  - Expected pattern: common security modules should own one servlet JWT interceptor plus property-driven public-path and role-requirement policy, so service auth differences stay declarative in `application.yml` rather than drifting through copied interceptor code

- `2026-04-18`: Shared env-backed properties should not be re-declared in every service YAML
  - Context: cleaning up the remaining per-service Spring/config boilerplate after `common-security` owned the canonical `firemud.auth` contract
  - Observation: many services still repeated `firemud.auth.jwt-secret`, `jwt-secret-path`, and `jwt-expiration-ms` in `application.yml` purely as `${ENV_VAR}` passthrough, even though Spring already binds environment variables directly into the shared `JwtAuthProperties` bean and the repeated YAML only adds drift-prone noise
  - Expected pattern: when a shared module owns a `@ConfigurationProperties` namespace, service YAML should contain only real local policy and non-default values; environment-backed shared settings should be bound once through the shared bean, not mirrored across every service config file

- `2026-04-18`: Shared auto-config must isolate servlet-only wiring from reactive consumers
  - Context: fixing runtime-image smoke after centralizing servlet HTTP auth into `common-security`
  - Observation: `CommonSecurityAutoConfiguration` still exposed `WebMvcConfigurer`-based bean methods on the shared auto-config class, so reactive services like `spring-cloud-gateway` crashed during auto-config introspection even though the servlet beans were conditionally guarded
  - Expected pattern: if a common module serves both servlet and reactive services, keep servlet-only beans in a dedicated servlet auto-configuration (or similarly isolated classpath boundary) so reactive consumers never need servlet classes present just to load shared security/bootstrap wiring

- `2026-04-18`: Service YAML needs a static lint gate, not just smoke proof
  - Context: cleaning up shared auth/config refactors after runtime-images smoke exposed duplicate top-level `firemud:` mappings in service `application.yml`
  - Observation: duplicate YAML keys in `account-service`, then `entity-management-service` and `social-groups-service`, were only caught once Docker booted the apps; build/test stayed green until runtime config parsing happened
  - Expected pattern: repo CI should run a tracked-YAML lint pass with duplicate-key detection over service/application, workflow, docker, k8s values, and operations YAML so configuration breakage fails before smoke and preview workflows

- `2026-04-19`: gRPC public-method allowlists should not depend on hand-typed proto package strings
  - Context: fixing runtime-images smoke after shared gRPC auth centralization caused `game-logic-service` readiness to fail with `WorldManagement: Missing token`
  - Observation: multiple services had `firemud.auth.grpc.public-methods` entries written with collapsed package names like `worldmanagement.v1` and `entitymanagement.v1`, while the real gRPC method descriptors use proto packages with underscores like `world_management.v1`; the mismatch silently disabled the intended public-method exemption until Docker smoke exercised the path
  - Expected pattern: any configuration that references gRPC method names should be validated against real service descriptors or generated from canonical proto metadata, so auth allowlists cannot drift through string typos that only surface at runtime

- `2026-04-19`: CI smoke workflows must call the repo-owned smoke script, not hand-maintain a service subset
  - Context: investigating why local source-built smoke was green while GitHub `Smoke Tests (Full Stack)` still failed with `Unable to resolve host game-design-service`
  - Observation: the workflow had drifted into its own manual `docker compose up ... <service list>` path that omitted services now required by the canonical dependency graph, while `dev-tools/verify-compose-health.sh` and the local smoke scripts already encoded the full required stack
  - Expected pattern: GitHub smoke should invoke the repo-owned image-smoke script and share the same health-gated compose model, so dependency drift is fixed in one place instead of forking between local and CI-only service lists

- `2026-04-19`: Dev bootstrap seeders must evolve with the runtime substrate, not just the template data
  - Context: fixing `PLAY -> LOOK` smoke after fresh compose/image stacks admitted `demo/production` into `gameInstanceId=1` but World Management still had no `world_instance` or room-instance topology for that runtime target
  - Observation: `game-session-service` dev bootstrap was still seeding a legacy bare `RUNNING` row while `world-management-service` only seeded template regions/zones/rooms; once runtime lookup became instance-scoped, the old partial bootstrap left admission pointers targeting a runtime instance that had never actually been activated
  - Expected pattern: whenever a service introduces a stricter runtime substrate, any dev/test bootstrap that manufactures admissible runtime IDs must seed the matching downstream runtime state too, or smoke will keep passing one service’s bootstrap assumptions into another service’s missing data

- `2026-04-19`: Canonical smoke proofs must not patch service state directly in Postgres
  - Context: reviewing the remaining test/workaround seams after hardening the compose and image smoke flows
  - Observation: both gameplay smoke scripts were still updating `game_session_service.game_instances.owner_account_id` directly to align the admitted runtime row with the logged-in demo account, which let smoke pass even if service bootstrap ownership was internally inconsistent
  - Expected pattern: smoke should only drive public service interfaces and deterministic dev/test seed data; if a stack needs direct database mutation to become playable, the bootstrap substrate is wrong and should be fixed in the services instead of in the smoke script

- `2026-04-19`: Gradle daemon JVM should be repo-pinned to avoid IDE vs shell cache churn
  - Context: investigating repeated local `configuration cache cannot be reused because an input to unknown location has changed` messages during CLI validation
  - Observation: the actual churn was not a project task mutating random inputs; it came from mixed Gradle launches using different Java homes, with shell runs on `/usr/lib/jvm/java-21-openjdk-amd64` and earlier IDE-triggered daemons on the VS Code Red Hat Java extension JRE. That split invalidated daemon reuse and sometimes degraded configuration-cache reporting to `unknown location`
  - Expected pattern: pin the Gradle daemon JVM with `gradle/gradle-daemon-jvm.properties` (via `updateDaemonJvm`) so shell and IDE launches converge on one daemon/toolchain instead of fighting over whichever JDK happened to launch first
