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

- `2026-04-27`: WSL helpers should avoid broad recursive scans on `/mnt/c` when exact-name `find` is enough
  - Context: building a Codex account-switching helper that needed to discover `auth.json`, session indexes, and Codex Switch metadata across WSL and Windows-mounted user folders.
  - Observation: Python `Path.rglob()` over broad Windows-mounted trees like `AppData` is noticeably slower and less predictable than shelling out to `find` with a tight filename set and bounded roots.
  - Expected pattern: for WSL automation that inspects Windows-mounted developer state, prefer exact-name `find` scans over broad recursive language-runtime walkers, especially under `AppData`-style trees.

- `2026-04-27`: Broad local proof catches repo-wide migration drift that service-local checks will miss
  - Context: after landing `game-design-service` and `game-session-service` work, `./gradlew :game-design-service:check -PfullCheck :game-session-service:check -PfullCheck` passed but the broader `./gradlew check` failed immediately on duplicate Flyway versioning in `entity-management-service`.
  - Observation: slice-local validation can look clean while unrelated migration numbering drift elsewhere on the branch still guarantees CI failure.
  - Expected pattern: when multiple branch lanes have been moving, run the repo-wide `./gradlew check` before or alongside push, and treat Flyway version sanity as shared branch hygiene rather than service-local ownership.

- `2026-04-27`: T3 checkpoint cleanup should inspect local Codex/T3 metadata before treating checkpoint thread IDs as active or archived
  - Context: extending the repo maintenance script for `refs/t3/checkpoints/<thread-id>/turn/<turn>` cleanup and comparing those checkpoint IDs with local Codex state under `~/.codex`.
  - Observation: checkpoint thread IDs are base64-encoded UUIDs, but they do not necessarily line up with Codex `threads.id` values in `state_5.sqlite`, so deleting "inactive" checkpoints from Git refs alone risks false assumptions about session state.
  - Expected pattern: classification-based checkpoint cleanup should start with a reporting mode that inspects the actual local metadata layout, reports active/archived/orphaned candidates, and only enables destructive cleanup after the mapping is confirmed.

- `2026-04-27`: Docker Desktop compose teardown is safer from Codex without a PTY, and smoke proofs should not parallelize shared-session clients
  - Context: running `dev-tools/verify-fresh-bootstrap.sh` from WSL against the source-built Docker stack while validating `account-service`, `spring-cloud-gateway`, and `tcp-proxy-service` follow-up fixes.
  - Observation: the canonical smoke script hung in `docker compose ... down` when launched through a PTY-backed Codex exec session, while the same compose commands completed immediately in plain-output mode. Separately, running the WebSocket and Telnet smoke scripts in parallel against the same demo account/session produced a false failure on the Telnet path even though the underlying gameplay commands succeeded.
  - Expected pattern: AI-driven Docker smoke proofs should invoke compose in noninteractive/plain mode, and the gameplay smoke clients should be treated as sequential shared-state checks unless they use isolated accounts or session ids.

- `2026-04-28`: Local trusted-proxy defaults should include IPv6 loopback wherever localhost header trust is intentional
  - Context: removing `dev` profile semantics from `spring-cloud-gateway` exposed websocket bridge integration tests returning `403` on `/ws/game` even though they still sent the expected trusted tcp-proxy headers from localhost.
  - Observation: the gateway header-trust defaults only allowed `127.0.0.1/32`, but Java websocket clients in test mode connected from `::1`, so `HeaderTrustFilter` rejected the proxy headers before gameplay upgrade and the failure looked like a bridge regression.
  - Expected pattern: canonical localhost trust defaults should include both IPv4 and IPv6 loopback (`127.0.0.1/32` and `::1/128`) so local and test traffic do not silently depend on the host stack preferring IPv4.

- `2026-04-28`: Spring profile overrides can silently discard lower-precedence list config within the same subtree
  - Context: after removing the `dev` profile, `spring-cloud-gateway` test startup still returned `403` on trusted tcp-proxy websocket upgrades even though `application-test.yml` only changed the scalar `allow-insecure-headers-from-trusted-cidrs` flag.
  - Observation: the live bound `GatewayHeaderTrustProperties` kept the boolean override from `application-test.yml` but lost the `insecure-trusted-cidrs` list from `application.yml`, so the test profile ended up allowing insecure proxy headers from an empty trust set.
  - Expected pattern: when a profile-specific config file touches a subtree that contains list-valued `@ConfigurationProperties`, restate the full list in that profile or move the differing scalar to a separate property source; do not assume YAML/profile layering will merge list defaults the way map/scalar overrides do.

- `2026-04-27`: The repository root README should stay an entrypoint, not a docs junk drawer
  - Context: reviewing recent root `README.md` growth after contributor-tooling content and deeper documentation navigation were added directly to the repo landing page.
  - Observation: once the root README starts carrying setup commands, workflow rules, subsystem-specific design links, and long reading lists, it duplicates narrower docs and becomes harder to use as a stable orientation surface.
  - Expected pattern: keep the root README focused on project identity, a compact architecture/docs map, and top-level entry links; move setup, contribution workflow, and deep design navigation into focused docs such as `DEVELOPER_SETUP.md`, `CONTRIBUTING.md`, and `design/README.md`.

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

- `2026-04-28`: Kubernetes docs drift quickly when hosted workflows become the real source of truth before the declared canonical path converges
  - Context: re-reviewing `k8s/` after preview/dev-demo became real Helm deploy paths showed `k8s/preview/README.md`, Helm docs, and deployment architecture docs still describing render-only preview milestones and persistent PR state while the live workflow was already doing namespace-reset redeploys and hosted smoke.
  - Observation: once workflows and helper scripts become the exercised deployment truth, repo docs that still describe the earlier intended state become actively misleading rather than merely stale.
  - Expected pattern: when a hosted environment becomes live before the broader canonical deployment path is finished, update the environment docs immediately to reflect real workflow behavior and move the larger “make the canonical path real” work into an explicit follow-on slice.

- `2026-04-28`: Selective CI must still include one canonical packaging proof for backend changes
  - Context: open Dependabot PRs showed `CI — Validation` passing for affected services while `Build Runtime Images` later failed compiling `:game-design-service:bootJar`, and a separate automatic dependency submission job failed only on GitHub-side upload.
  - Observation: a branch can look healthy when service `check` runs but no required job proves the packaged runtime artifact shape that image builds consume; meanwhile external metadata publication failures create noisy red PRs without reflecting source correctness.
  - Expected pattern: for backend-affecting PRs, always require one canonical generate/compile/package proof in the main validation workflow, and treat external dependency-graph submission as advisory rather than a merge gate.

- `2026-04-28`: WebSocket cross-service transcript assertions can still be flaky even after the underlying behavior is correct
  - Context: validating the `09.1` account-presence and friend-presence routing bundle carry-through changes with `:game-session-service:check -PfullCheck`.
  - Observation: `CommunicationWebSocketCrossServiceTest` failed on two different transcript assertions in separate full-suite runs, but each individual failing test reran clean immediately with no code changes, pointing to timing-sensitive live-response capture rather than the presence-routing change itself.
  - Expected pattern: cross-service websocket transcript tests should either wait on the specific canonical message they are asserting or be treated as retryable/flaky until the harness stops relying on exact live ordering under suite load.

- `2026-04-28`: When exercised and intended deployment lanes diverge, Kubernetes docs must label them explicitly instead of describing both as equally canonical
  - Context: final `k8s/` cleanup showed the hosted Helm workflows were the real exercised deployment path while the staged Kustomize overlays were still thin includes over `base/` with placeholder resources, yet several docs still described both surfaces as if they were equally current operational truth.
  - Observation: once a repo carries both a live hosted path and a still-converging player-facing path, readers cannot safely infer deployment truth unless the docs say which lane is exercised today, which lane is intended but incomplete, and which gaps are explicit rather than accidental.
  - Expected pattern: top-level environment and `k8s/` docs should mark each deployment lane as exercised, staged, or reference-only, and any hosted exceptions such as missing network policies or temporary transport relaxations should be called out in place rather than left to workflow inspection.

- `2026-04-28`: Player-facing Kubernetes overlays should validate external secret ownership, not embed placeholder Secret content
  - Context: finishing the `k8s/` audit required removing checked-in bootstrap JWT/Postgres/TLS placeholder data from the staging/production Kustomize renders while keeping `preflight.sh` and overlay CI meaningful.
  - Observation: once player-facing bootstrap bindings are supposed to be environment-owned, leaving fake Secret manifests in the canonical render path teaches the wrong operator contract and masks whether preflight is validating real external ownership versus inline sample data.
  - Expected pattern: keep player-facing overlays free of placeholder Secret content, validate secret names/mount contracts statically from rendered workloads, and let operator-mode preflight prove that the expected environment-owned bindings exist in the target cluster.
