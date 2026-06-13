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

- `2026-05-20`: Workflow metadata should ride the canonical operator read surface instead of creating a side API
  - Context: landing the Game Design Temporal `publish` workflow showed that the useful operator-facing state was not a new workflow endpoint by itself, but the linkage between the existing release-bundle view and the workflow execution that produced it.
  - Observation: when a durable workflow owns a control-plane lifecycle that already has an established read model, adding a second workflow-status API increases drift risk and forces operators to manually correlate two surfaces for one business action.
  - Expected pattern: expose workflow identity and runtime status on the canonical business read surface first, and only add dedicated workflow inspection APIs later if that surface genuinely cannot carry the needed operator state.

- `2026-05-20`: H2-backed test profiles need lowercase identifier mode once a service starts using generated `jOOQ` table metadata
  - Context: the first `02.19.3` Game Session `jOOQ` repositories initially passed focused unit proof but failed broad integration startup because the existing H2 test URLs created unquoted uppercase table names while the generated `jOOQ` metadata queried quoted lowercase identifiers like `gameplay_admission_pointer`.
  - Observation: a service can look fine under JPA/Hibernate and still break the moment generated `jOOQ` code starts issuing explicit identifier SQL if the local H2 profile is not aligned with the repo's canonical lowercase schema naming.

- `2026-05-28`: Reconnect-sensitive first-party selector identity should ride the durable session shell, not only a transient side registry
  - Context: extending the `09.4` bootstrap/connect-scope work in Game Session showed that websocket handshake/login/PLAY consumers already preserved `worldSlug`, `realmSlug`, and `pointerVersion`, but `connectScopeId` and `connectRequestId` still lived only in the auxiliary first-party registry entry.
  - Observation: when reconnect-style consumers depend on selector freshness, preserving only the routing bundle on the durable session shell is not enough; losing the side registry silently weakens behavior back toward route hints instead of true selector identity.
  - Expected pattern: if a reconnect or replay-sensitive contract includes an explicit selector or request id, persist that selector identity on the same durable shell as the routing bundle so later consumers can stay fail-closed even when transient caches or helper registries are missing.
  - Expected pattern: when migrating a service onto generated `jOOQ` tables while it still uses H2-backed Spring test contexts, make the H2 URLs opt into lowercase identifier behavior (for example `DATABASE_TO_LOWER=TRUE`) before treating the repository conversion as complete.

- `2026-05-21`: Heavy Gradle test tasks need clean result directories or strictly sequential execution once the same module is rerun
  - Context: validating the next `02.19.3` Game Session `jOOQ` batch exposed two false negatives after overlapping `:game-session-service:integrationTest` work with a repo-wide `./gradlew check`: Gradle reported an `EOFException` while reading previous test results, and the broader `check` run surfaced stale mixed failure output from the same module.
  - Observation: on heavy suites that reuse `build/test-results/**/results-generic.bin`, rerunning the same module concurrently or before stale binary results are cleared can look like a product regression even when the underlying tests are green.
  - Expected pattern: when a service module has already been exercised by one Gradle test task, rerun later proof for that same module sequentially; if Gradle reports binary test-result read errors or mixed stale failures, clear that module's `build/test-results` and matching `build/reports/tests` directories before trusting the next gate.

- `2026-05-21`: Closing a platform transition slice requires updating the surviving abstraction docs and helper comments in the same pass
  - Context: closing `02.20.4` after the first real Temporal adopters showed that even after product code had converged, the remaining audit drift lived in architecture docs and shared helper comments that still used plain "Saga" as a synonym for durable workflow execution.
  - Observation: when a new shared substrate replaces only part of an older abstraction, leaving the old docs/comments broad guarantees future audits will keep flagging "architecture mismatch" even if the runtime behavior is already canonical.
  - Expected pattern: when a slice narrows an existing shared abstraction, sweep the high-level docs, adopter docs, and the surviving helper/module comments in the same batch so the repo teaches one boundary instead of the old and new stories at once.

- `2026-05-21`: Shared `jOOQ` codegen will force old Flyway DDL into a stricter canonical SQL subset
  - Context: landing the first `02.19.4` Game Design `jOOQ` repositories surfaced several older migrations that Flyway/Postgres had always accepted but the shared `jOOQ` DDL parser could not interpret, including mixed `ALTER TABLE` statements and an `UPDATE ... FROM`-style data repair.
  - Observation: once a service adopts shared schema-driven codegen, legacy migration text is no longer inert archaeology; parser-incompatible DDL becomes an immediate blocker for every later repository migration in that service.
  - Expected pattern: when enabling shared `jOOQ` codegen on an older service, normalize historical migrations into simple parser-friendly statements at the source instead of adding service-local workarounds around codegen or silently treating the schema baseline as “special.”

- `2026-05-21`: Shared `jOOQ` paging helpers must treat `Pageable.unpaged()` as a first-class contract, not just `null`
  - Context: closing the full `02.19.5` Entity Management repository migration exposed integration failures in room/container inventory flows because the new explicit SQL repositories reused `JooqPersistenceSupport.limitOrDefault(...)`, which still called `getPageSize()` on Spring's `Unpaged` implementation and threw `UnsupportedOperationException`.
  - Observation: once multiple services share explicit SQL paging helpers, assuming "paged means non-null" creates a repo-wide trap because many canonical gameplay/control-plane call sites deliberately pass `Pageable.unpaged()` while still expecting repository methods to return ordinary `Page` containers.
  - Expected pattern: shared `jOOQ` pagination helpers should branch on both `null` and `pageable.isUnpaged()` for limit/offset behavior, and service tests should mock repository page-returning methods as non-null empty pages rather than `null` sentinels so the contract stays aligned with Spring Data semantics.

- `2026-05-21`: Shared `jOOQ` migration work should collapse old ORM-masked schema drift back into Flyway, not into repository adapters
  - Context: landing the `02.19.8` Social Groups repository migration exposed that the service’s Java model had long assumed surrogate ids, tenant scoping, and `Long` id widths for `guild_members`, `guild_storage_items`, and `guild_alliances`, while the older Flyway DDL still described a composite-key guild-member table and narrower `SERIAL` ids that only stayed invisible because JPA had been filling the gap.
  - Observation: once a service moves to shared schema-driven SQL codegen, "the entity is the truth" stops being enough; any old mismatch between ORM-mutated runtime schema and checked-in Flyway DDL becomes a direct blocker to compilation, fresh boot, and future audits.
  - Expected pattern: when a `jOOQ` migration exposes service-local schema drift that Hibernate had been masking, fix the canonical Flyway DDL to the current contract in the same batch rather than teaching repositories or tests to live with two competing schema stories.

- `2026-05-21`: Post-Hibernate integration tests should join the shared Postgres/Flyway contract instead of reviving H2-only datasource paths
  - Context: closing `02.19.10` exposed that a couple of Game Session integration suites still forced `firemud.database.enabled=false` while trying to prove new `jOOQ` repositories, which left them booting the fallback H2 datasource even after the shared Postgres-backed helper existed and made the squashed Postgres baseline look like an H2 portability bug.
  - Observation: once repo-wide Hibernate default-schema behavior is removed, mixed "real Postgres in some tests, H2 fallback in others" proof paths become a source of false persistence regressions because Flyway, JDBC schema selection, and generated SQL metadata are no longer being exercised under one canonical contract.
  - Expected pattern: SQL-backed integration suites that need real repository proof should prefer the shared `PostgresBackedServiceTestSupport` contract, keep `firemud.database.enabled=true`, and only use embedded/H2 Flyway paths for tests that are intentionally scoped away from the service-owned Postgres runtime model.

- `2026-05-21`: Public-edge smoke must default to the gateway route family, not direct service ports
  - Context: closing the remaining audit findings showed that a player-experience harness can look "real" while still bypassing edge bugs if it defaults to direct service URLs for bootstrap and token issuance. The first honest rerun against `/api/account/**` exposed a real gateway rate-limiter keying gap that never appeared when the harness called `account-service` directly.
  - Observation: for first-party browser-style flows, proving the connect-token handshake on a direct internal service URL is not operationally equivalent to proving the public edge contract.
  - Expected pattern: public-ingress smoke should default to Gateway-owned routes and only allow direct-service endpoints as explicit overrides for isolated debugging, so edge policy, routing, and rate-limiter regressions stay visible.

- `2026-05-23`: Demo/runtime seeders for prod-like smoke must reassert canonical state, not just seed empty tables
  - Context: hosted preview bootstrap triage exposed that several services still treated `count() == 0` as their smoke-fixture contract, which let persistent preview namespaces drift into unusable state even though the canonical demo rows were known.
  - Observation: create-once seeders are too weak for restart-heavy preview/demo environments because any surviving stale row can block the bootstrap path while still looking "seeded" to the service.
  - Expected pattern: seeders that support canonical smoke, preview, or operator demo flows should find rows by stable business identity and reassert the intended state on every run, while authored/runtime proof scripts should be able to rely on that repair behavior instead of manual database cleanup.

- `2026-05-24`: Shared Postgres-backed test support must carry the full Flyway history-table contract
  - Context: closing the remaining `02.19` SQL audit tail exposed that runtime containers, Helm values, and reset tooling were all using service-local `flyway_schema_history_<service_schema>` tables while plain service boot and `PostgresBackedServiceTestSupport` could still fall back to bare `flyway_schema_history`.
  - Observation: proving only schema, locations, and default schema is not enough once services stop sharing one history table; tests can look green while validating a different Flyway contract than runtime.
  - Expected pattern: shared Postgres-backed test helpers and base service config should register `spring.flyway.table` explicitly alongside schema/default-schema so fresh boot, integration tests, and local reset tooling all exercise the same service-local history table identity.

- `2026-05-25`: Metrics-cardinality lint must scan shipped rules and canonical catalogs, not only a few explanatory docs
  - Context: closing the remaining observability audit tail showed that the existing `check-metrics-cardinality.py` guardrail passed while `prometheus-rules-firemud.yaml`, the Redis metrics catalog, and the scripting quotas/operations doc still taught raw `tenantId` / `regionId` / per-script label shapes.
  - Observation: a static policy check can give false confidence if it inspects only a narrow prose subset and ignores the repo's actual shipped rules and authoritative metric catalogs.
  - Expected pattern: metrics-cardinality enforcement should scan the canonical rule/config/doc surfaces that operators and later contributors actually copy from, including PromQL grouping/join clauses, not just metric examples embedded in one or two design docs.

- `2026-05-25`: Shared workflow contracts should propagate workflow family all the way to operator read surfaces
  - Context: the first Temporal adopters already used stable workflow family constants internally, but the world lifecycle, script-patch readiness, and publish read models still dropped `workflowFamily` while the shared Temporal contract claimed operators would see it.
  - Observation: keeping workflow-family truth only in workflow ids and internal constants leaves operator surfaces and docs drifting even though the runtime already knows the answer.
  - Expected pattern: when a shared workflow contract defines `workflowFamily` as part of the canonical identity, adopter DTOs/protos/read APIs should expose it directly rather than forcing operators to parse it back out of workflow ids or infer it from service-specific context.

- `2026-05-25`: Expected-binding manifests should own exact rendered binding identity, not just schema fields
  - Context: tightening `02.15.8` showed that preflight already required `internalBindings.registry.imagePullSecretRef`, but staging/production overlays still rendered no matching image-pull binding and the contract tests only exercised a synthetic hobby manifest.
  - Observation: a manifest can look authoritative while still being advisory if the proof checks only presence of fields and not whether the rendered workloads actually reference the named Secrets or pull credentials.
  - Expected pattern: environment binding manifests should drive exact rendered Secret and image-pull binding names, and contract proof should run against the real staging/production renders in addition to synthetic examples.

- `2026-05-25`: Traffic-open evidence should be generated from canonical preflight proof instead of hand-authored JSON
  - Context: continuing `02.15.8` showed that the repo could validate hobby/production traffic-open evidence shape, but still left operators to assemble those records manually even though the same gates already depended on canonical preflight reports and deployment refs.
  - Observation: once traffic-open records are hand-authored, they drift toward decorative JSON and can omit the exact report linkage or operator evidence fields the gate is supposed to enforce.
  - Expected pattern: traffic-open records should be emitted by a repo-owned writer that validates the referenced preflight report before writing the evidence file, and the preflight consumer should reject traffic-open evidence that is missing that canonical preflight linkage.

- `2026-05-25`: Once runtime authority moves out of config, live readers should stop using config-binder DTOs as their domain model
  - Context: routing follow-through in Game Session had already moved production authority onto persisted admission-pointer rows, but live WORLDS/REALMS/CHARS/PLAY and routing gRPC reads were still passing around `GameplayCatalogProperties.World` / `Realm`, which kept the old config schema looking like runtime truth even though it was only a bootstrap/test helper.
  - Observation: leaving live consumers on config-binder DTOs after authority has moved makes later contributors more likely to reintroduce local-config shortcuts, because the runtime still "looks" config-backed even when the data source changed.
  - Expected pattern: when a service replaces config authority with persisted or remote authority, the live reader surface should project through a runtime-owned immutable view model and leave the config property classes behind only for bounded bootstrap, fallback, or tests.

- `2026-05-25`: Selector-plus-request retry contracts need replayed results, not only stable logical ids
  - Context: first-party connect-token issuance already derived a stable `jti` from `{accountId, tenantId, realmSlug, requestId}`, but retries still minted fresh JWTs because `issuedAt` and `expiresAt` were tied to wall clock, which broke the documented "same selector + same requestId" idempotency promise under reconnect and cutover races.
  - Observation: stabilizing a logical identifier without replaying the validated result still leaves the observable token payload drifting across retries, so clients and operators see different attempts even when the contract claims one logical issuance.
  - Expected pattern: when a selector plus `requestId` is the retry boundary, services should cache and replay the full post-validation success or deterministic failure for that attempt while the selector is live, instead of recalculating a fresh wall-clock token on every retry.

- `2026-05-27`: Choose account-level active presence after freshness validation, not before it
  - Context: account/friend presence originally asked the gameplay presence store for one preferred active session per account and only then checked whether that session's `{worldSlug, realmSlug, gameInstanceId, pointerVersion}` still matched current admission-pointer authority.
  - Observation: selecting one "best" active session before validating routing freshness lets a stale-but-more-recent gameplay row hide a still-current session for the same account, and can also keep projecting an account as online in a realm after cutover.
  - Expected pattern: when one read model must collapse multiple active sessions to one account-level presence view, validate each candidate against current routing/freshness authority first and only then apply "best session" preference ordering among the still-current candidates.

- `2026-05-27`: Request-id idempotent write boundaries should expose replayed-vs-fresh outcome explicitly
  - Context: `account-service` public-production first admission originally became idempotent only through the eventual membership row and durable audit log, while `connect-token` retries already replayed cached results for the same selector/request pair.
  - Observation: when operators or first-party clients care about one logical attempt, "resource already exists" is not the same thing as "this request id was replayed"; without an explicit replay marker, repeated attempts and later no-op reads look the same even though they mean different things operationally.
  - Expected pattern: when a service declares `requestId` as the retry boundary for a write-like flow, replayed responses should surface the original outcome together with an explicit `replayed` marker and the same `requestId`, instead of leaving callers to infer replay from logs or from eventual resource state alone.

- `2026-05-28`: Shell resets must retire live gameplay presence, not only rewrite session state
  - Context: Game Session already fenced stale admission-pointer and reconnect failures back to a logged-in bootstrap shell, but several of those paths only rewrote `SessionContext` in Redis and left the old gameplay-presence row intact.
  - Observation: clearing command-time authority without clearing the corresponding live presence lets cutover and reconnect fences fail closed for commands while still leaking ghost online or in-room presence from the stale gameplay binding.
  - Expected pattern: any path that intentionally collapses an admitted gameplay session back to a bootstrap or logged-in shell should clear the matching gameplay-presence entry and emit the same bounded region-exit lifecycle signal when the old binding had still been in a concrete room.

- `2026-05-28`: Durable command staging and replay-time execution must consume the same routing fence as interactive session handlers
  - Context: after Game Session fenced stale admission-pointer shells in `LOGIN`, `PLAY`, and gameplay-stage command reads, the durable queue and replay path still resolved raw `SessionContext` rows directly for queue targeting, replay execution, and gameplay-scoped script-event publish.
  - Observation: once queued or replayed gameplay work bypasses the shared stale-pointer normalization, cutover-sensitive sessions can still target old runtimes or emit gameplay follow-up events even though the live session has already fallen back to a non-gameplay shell.
  - Expected pattern: any durable gameplay-command staging or replay-time resolver that starts from session identity should normalize the resolved session through the same routing fence as interactive handlers before selecting queue targets, preserving gameplay bindings, or publishing gameplay-scoped side effects.

- `2026-05-28`: Transport replay, recipient fan-out, and debug reads must not bypass stale-shell normalization
  - Context: after Game Session fenced interactive commands and durable queue/replay work through `SessionAuthenticationService`, reconnect-facing websocket helpers, communication-recipient delivery, and operator effective-settings reads still consumed raw persisted `SessionContext` rows directly.
  - Observation: leaving those adjacent consumers on raw Redis session state reopens stale-pointer leaks even after command admission is fixed, because reconnect redraw, recipient delivery, or settings inspection can still project gameplay-scoped state from a shell that should already have been collapsed back to login-only state.
  - Expected pattern: any transport-side redraw/buffer helper, recipient resolver, or operator/debug read that starts from session identity should resolve or normalize through the same stale-pointer shell fence as command admission instead of trusting raw persisted gameplay bindings.

- `2026-05-28`: Disconnect lifecycle and recent-presence projection must normalize stale gameplay shells too
  - Context: after Game Session fenced command handling, replay, redraw, recipient delivery, and settings reads, disconnect/logout/takeover lifecycle emission and account-recent presence snapshots still loaded persisted session shells directly when projecting region-exit or routing evidence.
  - Observation: if those lifecycle-side projections skip stale-shell normalization, cutover fencing can already have collapsed the admitted gameplay binding to login-only state while logout/takeover/transport-loss evidence or recent-presence reads still preserve stale world/realm/runtime routing as if the session were in-world.
  - Expected pattern: any disconnect lifecycle publisher or recent-presence snapshotter that starts from session identity should normalize through the same stale-pointer routing fence before projecting gameplay-scoped state, and should treat live presence as advisory only when the normalized shell still has a gameplay binding.

- `2026-05-28`: Login refresh paths must project through the same routing fence before preserving gameplay state
  - Context: after Game Session fenced active commands, replay, redraw helpers, and disconnect/recent-presence projections, `LOGIN` still read raw persisted session shells to pick a bootstrap game instance, recover persisted first-party context, preserve relogin gameplay bindings, and clear failed login state.
  - Observation: when account re-authentication keeps gameplay or routing state from a raw shell, a stale admitted gameplay binding can survive cutover fencing simply because the session refreshed through `LOGIN` instead of a later gameplay consumer.
  - Expected pattern: any login/bootstrap refresh path that starts from persisted session identity should resolve or normalize through the same stale-pointer shell fence before it reuses gameplay bindings, persisted connect context, or bootstrap routing metadata.

- `2026-05-30`: Resume/takeover continuity should normalize the stored gameplay binding, not only the incoming session
  - Context: after Game Session fenced incoming session shells for commands, replay, disconnect projection, and login refresh, `PLAY` still looked up a prior gameplay binding by gameplay identity and reused its room/takeover continuity directly.
  - Observation: when resume or takeover continuity trusts a stored gameplay binding without re-validating that stored shell against current pointer authority, cutover-sensitive sessions can inherit stale room/runtime continuity even though the incoming session itself already fails closed correctly.
  - Expected pattern: any resume/takeover path that reuses an existing gameplay binding should normalize that stored binding through the same stale-pointer fence first, and should fall back to a fresh entry when the prior binding no longer survives current routing authority.

- `2026-05-30`: Fair-selected work sources need one durable source-local ordering key in addition to batch-local claim position
  - Context: continuing `02.18.8` remote-followup drain work showed the durable `REMOTE_FOLLOWUP_QUEUE` manifest still used batch-local claim slot order as `queueSourceOrdinal`, even though gameplay-command manifests already preserve one source-local ordering fact (`enqueueSeq`) across claim and replay cycles.
  - Observation: batch-local fairness order (`claimOrdinal`) and source-local comparable ordering are different facts; collapsing them into one field makes replay and control-plane reads look deterministic while actually rewriting source order every time work is reclaimed into a new batch.
  - Expected pattern: later durable work sources should persist one stable source-local ordering key on the source row itself and keep batch-local claim position as a separate field, so manifests and operator reads can compare or replay work without depending on whichever batch happened to claim it last.

- `2026-05-30`: Durable remote rows should outrank payload blobs once schedule-time authority has been stamped
  - Context: the `02.18.8` remote-followup target-side executor already persisted routing bundle, requested command, target entity, provenance, origin-source tuple, and trigger-script-event identity onto coordinator/followup rows at schedule time, but target-side execution still let payload JSON override several of those fields later.
  - Observation: when replay or retry-time execution rereads a payload blob as higher authority than the durable row contract, later payload drift can silently rewrite target-leg admission truth even though the scheduler already validated and persisted the canonical fields.
  - Expected pattern: once a scheduling path stamps first-class fields onto durable coordinator/followup rows, target-side execution should prefer those stored fields and only fall back to payload JSON for older or partially populated rows that predate the explicit durable authority.

- `2026-05-31`: Routing authority drift often survives in read models after command paths are fixed
  - Context: after the main `09.1` routing-fence work landed, the next real drift showed up in projection and lifecycle code rather than in admission commands.
  - Observation: account-presence reads and shared-runtime logout still consulted `GameplayWorldCatalog` even though admitted routing bundle plus persisted pointer authority were already the canonical runtime truth.
  - Expected pattern: when a routing slice claims local catalog copies are no longer authoritative, follow-up audits should explicitly inspect read models, lifecycle policies, and display-name decoration code for reverse runtime-target fallbacks or local world/realm validation, not just the interactive command paths.

- `2026-05-31`: Availability checks need the same normalization fence as later delivery
  - Context: routing-fence cleanup had already normalized stale gameplay bindings in downstream communication delivery paths.
  - Observation: `TELL` could still mark a target as “online” from a raw gameplay-name Redis hit even though recipient delivery would immediately clear that same target back to a non-gameplay shell.
  - Expected pattern: any availability or “is target live” check that starts from session identity should normalize the candidate through the same routing fence as the later delivery or execution path before it reports success.

- `2026-06-01`: Session-scoped reads must not guess tenant authority from runtime ids
  - Context: `QueryState(sessionId)` still tried to derive tenant scope by treating the transport `sessionId` as if it were also a runtime `gameInstanceId`.
  - Observation: numeric identifier reuse across session, runtime, and operator surfaces is exactly the kind of shortcut that bypasses routing-fence work later if read-model paths are left behind.
  - Expected pattern: session-scoped operator or debug reads should fail closed when the session shell is absent or tenantless instead of projecting a guessed Redis key from a different authority domain.

- `2026-06-01`: Pre-login bootstrap resolution needs explicit shell authority too
  - Context: transport-id fallbacks had already been removed from post-login and operator paths, but credential `LOGIN` still had one bootstrap-time shortcut left.
  - Observation: if credential `LOGIN` can still guess a bootstrap runtime from the same numeric `sessionId`, a missing shell can reopen admission whenever a transport id happens to collide with a real runtime id.
  - Expected pattern: pre-login flows should resolve a runtime target from the canonical bootstrap shell or fail closed when that authority is missing.

- `2026-06-13`: Reverse runtime-state reads must expose pointer multiplicity instead of picking one sorted realm identity
  - Context: after the main `09.1` routing-fence work landed, `GetGameInstanceRuntimeState` still reverse-mapped a runtime target back to one singular `{worldSlug, realmSlug, pointerVersion}` bundle by selecting the first sorted admission pointer row.
  - Observation: once multiple visible admission pointers can legitimately share one runtime target, a reverse read that silently picks one sorted row teaches downstream consumers a fake canonical realm identity and reopens arbitrary world/realm drift in operator or Automation projections.
  - Expected pattern: reverse runtime-state reads should expose the full current pointer set explicitly, keep legacy singular routing fields only for the one-pointer case, and force downstream consumers to fail closed when runtime-to-pointer projection is ambiguous.

- `2026-06-13`: Operator stale flags must fail closed when current routing authority is ambiguous or incomplete
  - Context: extending the `09.1.6` runtime-state reverse-projection cleanup into Game Session command-status and remote control-plane reads exposed a quieter seam: those read models could already clear singular current routing fields, but still report `is...RoutingBundleStale=false` when current authority was missing, partial, or multi-pointer.
  - Observation: once a control-plane read publishes both persisted routing and derived current routing, clearing only the visible current bundle is not enough; callers still misread the row as current if stale signaling stays tied only to successful bundle comparison.
  - Expected pattern: operator and control-plane stale indicators should fail closed to `true` whenever current authority cannot prove one complete singular `{playableStateScope, worldSlug, realmSlug, pointerVersion}` bundle, not only when two complete bundles can be compared directly.
