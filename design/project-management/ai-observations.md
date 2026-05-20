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

- `2026-05-03`: Source-built Docker smoke should split `compose build` from `compose up`
  - Context: running `dev-tools/verify-fresh-bootstrap.sh` locally on WSL/Docker Desktop repeatedly rebuilt all service images successfully but then left `docker compose up -d --build --remove-orphans` hung with no containers created and no further output.
  - Observation: the combined `up --build` path can wedge after successful image export, while the underlying compose/build steps still succeed; this makes the canonical smoke proof look like a product failure when the problem is the Docker Desktop compose workflow.
  - Expected pattern: canonical source-built smoke scripts should run `docker compose build` and `docker compose up -d` as separate steps so local Docker hangs are easier to distinguish from actual runtime/bootstrap regressions.

- `2026-05-03`: Canonical WSL Docker smoke should default compose builds to sequential mode
  - Context: after splitting `compose build` from `compose up`, the same `dev-tools/verify-fresh-bootstrap.sh` proof still stalled inside `docker compose build` on WSL/Docker Desktop while multiple service contexts were building in parallel.
  - Observation: the local failure mode is not limited to `up --build`; parallel compose builds themselves can wedge after partial progress even when the service Dockerfiles and jars are valid.
  - Expected pattern: canonical source-built smoke scripts should build compose services one-by-one instead of relying on a single multi-service `docker compose build` invocation.

- `2026-05-11`: Shared proto modules need non-incremental Java compilation after message-shape expansion
  - Context: extending `social_groups_service.proto` with new friend roster request/response messages and visibility-policy fields generated the expected Java message classes, but `:common-saga:compileJava` still failed on the regenerated gRPC stub during incremental compilation until the module was cleaned.
  - Observation: in this repo's shared-proto modules, Gradle incremental Java compilation can miss freshly generated protobuf source files even when `generateProto` itself succeeded, which makes proto-surface expansion look like a random compile break.
  - Expected pattern: protobuf-bearing modules should favor deterministic full Java recompilation after `generateProto` rather than incremental compilation, or CI/local conventions should otherwise force compile inputs to refresh whenever new generated message files appear.

- `2026-05-15`: Shared smoke command catalogs do not prevent drift if each transport still owns its own socket loop
  - Context: reviewing gameplay proof convergence across `services/game-session-service/websocket-login-look-smoke.sh`, `services/tcp-proxy-service/telnet-login-look-smoke.sh`, and `dev-tools/hosted/shared/hosted-login-look-smoke.sh`.
  - Observation: the repo now shares command-step catalogs and readiness/account checks through `dev-tools/smoke/smoke_common.py`, but the telnet hosted smoke, local telnet smoke, and local websocket smoke still carry separate transport read/drain/send loops and partial retry behavior, so smoke proof can still drift one layer below the shared step definitions.
  - Expected pattern: when a smoke flow is canonical across environments, share both the command plan and the transport executor semantics so hosted and local smoke paths do not fork on timeout, draining, or partial-response handling.

- `2026-05-19`: Shared reactive test app bootstraps should disable Spring Cloud discovery and pin InetUtils localhost defaults
  - Context: a repo-wide `./gradlew check` hung in `:tcp-proxy-service:crossServiceTest` after the test worker finished product work because `ReactiveTestApplicationSupport.startReactiveApp(...)` was starting stub reactive apps with a live `spring.cloud.inetutils` thread still resolving host metadata under heavier test load.
  - Observation: reactive stub apps in this repo do not need service discovery, and leaving Spring Cloud InetUtils enabled can turn a test bootstrap helper into an intermittent startup hang that looks like a random module teardown stall.
  - Expected pattern: shared reactive test bootstrap helpers should set `spring.cloud.discovery.enabled=false` and short localhost InetUtils defaults unless a specific test explicitly needs discovery behavior.

- `2026-05-19`: Gradle Flyway task support for PostgreSQL needs explicit buildscript classpath wiring, not only service runtime dependencies
  - Context: validating the first `02.17.2` destructive baseline squash exposed that `dev-tools/restores/reset-service-db.sh` could drop tables correctly but `:entity-management-service:flywayMigrate` still failed with `No Flyway database plugin found to handle jdbc:postgresql://...` even though the service already carried `flyway-database-postgresql` on its runtime classpath.
  - Observation: in this repo, making Spring Boot startup migrations work is not enough to make the standalone Gradle Flyway tasks work; the Gradle Flyway plugin also needs the PostgreSQL database module on the buildscript classpath, and reset tooling should export standard `FLYWAY_*` connection variables rather than only the repo-local `FIREMUD_POSTGRES_*` names.
  - Expected pattern: whenever local tooling or docs rely on `:service:flywayMigrate`/`flywayInfo`/`flywayValidate`, verify the Gradle plugin path directly and keep both the buildscript Flyway database module wiring and standard `FLYWAY_*` env mapping in place.

- `2026-05-19`: Destructive local Flyway reset must preserve the service-local schema and history table, not just the database connection
  - Context: validating the second `02.17.2` squash target showed `dev-tools/restores/reset-service-db.sh automation-scripting-service` still produced a Flyway checksum mismatch even after dropping the discovered service tables, because the local Gradle `flywayMigrate` path was silently using `public.flyway_schema_history` while the runtime service configuration uses `automation_scripting_service.flyway_schema_history_automation_scripting_service`.
  - Observation: exporting only `FLYWAY_URL`/`USER`/`PASSWORD` is not enough in this repo's per-service-schema topology; a destructive reset can look successful while reusing stale history in the wrong schema if the tool does not also preserve the owning schema and Flyway table identity.
  - Expected pattern: local reset/rebuild tooling should always export the same schema/table contract the service container uses (`SERVICE_SCHEMA`, `SPRING_FLYWAY_TABLE`, `FLYWAY_SCHEMAS`, `FLYWAY_DEFAULT_SCHEMA`, `FLYWAY_TABLE`) and should qualify destructive drops against that schema instead of relying on `public` search-path fallbacks.

- `2026-05-19`: Export alert-threshold config as metrics when the operational contract depends on consecutive-cycle gauges
  - Context: closing `02.18.6` exposed that the tick scheduler already tracked consecutive rejection/merge/queue-depth pressure cycles, but the canonical alert snippets still hardcoded numeric defaults instead of following the actual configured thresholds that the runtime was using.
  - Observation: once runtime alerting depends on "N consecutive cycles above threshold" semantics, publishing only the live signal without the configured threshold guarantees alert-rule drift between code, docs, and environment overlays.
  - Expected pattern: when a service exports consecutive-cycle or sustained-pressure gauges, it should also export the corresponding configured threshold values so rule files and dashboards can compare against runtime truth rather than copying stale constants.

- `2026-05-20`: Shared Temporal foundation proof should avoid dragging an incompatible in-process test server into the base substrate
  - Context: landing `02.20.1` against the repo's current gRPC line showed `io.temporal:temporal-testing` failing at startup with an `AbstractMethodError` in `InProcessServerBuilder` because the in-process test server path expected an older gRPC internal method shape than the repo-wide dependency set provided.
  - Observation: a minimal shared workflow foundation should prove its host/registration contract without forcing the entire repo to align around Temporal's in-process test server stack before the first real workflow adopters actually need end-to-end execution proof.
  - Expected pattern: keep the shared Temporal foundation limited to runtime beans, identity/task-queue helpers, and host-registration proof; defer heavier Temporal test-server or containerized workflow execution proof to the first real adopter slices unless the foundation itself truly requires it.
- `2026-05-20`: Optional shared workflow adopters must keep their own beans conditional too
  - Context: landing the first real Temporal adopter in `world-management-service` showed that making `common-temporal` conditional was not enough; app contexts with Temporal disabled still failed because the service-local orchestrator and worker registrar eagerly required `WorkflowClient`.
  - Observation: optional shared runtime modules do not stay optional if adopter-side components assume the shared beans always exist.
  - Expected pattern: when a shared workflow substrate is opt-in, service-local orchestrators, worker registrars, and similar adopter beans must also be conditional on the shared runtime beans instead of relying only on the common module's property gate.

- `2026-05-20`: H2-backed test profiles need lowercase identifier mode once a service starts using generated `jOOQ` table metadata
  - Context: the first `02.19.3` Game Session `jOOQ` repositories initially passed focused unit proof but failed broad integration startup because the existing H2 test URLs created unquoted uppercase table names while the generated `jOOQ` metadata queried quoted lowercase identifiers like `gameplay_admission_pointer`.
  - Observation: a service can look fine under JPA/Hibernate and still break the moment generated `jOOQ` code starts issuing explicit identifier SQL if the local H2 profile is not aligned with the repo's canonical lowercase schema naming.
  - Expected pattern: when migrating a service onto generated `jOOQ` tables while it still uses H2-backed Spring test contexts, make the H2 URLs opt into lowercase identifier behavior (for example `DATABASE_TO_LOWER=TRUE`) before treating the repository conversion as complete.
