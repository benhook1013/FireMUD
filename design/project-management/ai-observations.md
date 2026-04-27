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

- `2026-04-27`: The repository root README should stay an entrypoint, not a docs junk drawer
  - Context: reviewing recent root `README.md` growth after contributor-tooling content and deeper documentation navigation were added directly to the repo landing page.
  - Observation: once the root README starts carrying setup commands, workflow rules, subsystem-specific design links, and long reading lists, it duplicates narrower docs and becomes harder to use as a stable orientation surface.
  - Expected pattern: keep the root README focused on project identity, a compact architecture/docs map, and top-level entry links; move setup, contribution workflow, and deep design navigation into focused docs such as `DEVELOPER_SETUP.md`, `CONTRIBUTING.md`, and `design/README.md`.
