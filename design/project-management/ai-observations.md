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

- `2026-04-21`: Source-built Docker smoke scripts must rebuild boot jars before rebuilding images
  - Context: extending the local fresh-bootstrap smoke proof exposed that `docker compose up --build` rebuilt images from stale `build/libs/*bootJar` artifacts, so the supposedly current source-built stack was still running an old World Management jar without runtime room-instance migrations.
  - Observation: rebuilding Docker layers is not enough when service Dockerfiles copy prebuilt Gradle jars; the local proof can look fresh while silently testing old application code.
  - Expected pattern: canonical source-built Docker smoke scripts should run the service `bootJar` tasks before `docker compose ... --build`, and AI workflows should treat stale packaged artifacts as a runtime/bootstrap risk.

- `2026-04-21`: Repo-local Gradle project caches interfere with Docker Desktop build contexts
  - Context: tightening source-built Docker image rebuilds exposed intermittent `Access is denied` failures while Docker Desktop walked the repo `.gradle/configuration-cache` tree on WSL-backed builds.
  - Observation: keeping the Gradle project cache under the checkout creates needless Docker context noise, can trip host/filesystem permission quirks, and makes local build behavior depend on generated repo state unrelated to the image inputs.
  - Expected pattern: set the Gradle project cache outside the repository by default, and keep repo-local Docker contexts free of Gradle project-cache churn.

- `2026-04-21`: Canonical Redis prefix docs need one enforced key-shape source
  - Context: running the Redis runtime/data-contract architecture review exposed drift between the central cache docs and service-facing docs for the same World Management cache family (`world-dynamic:*` appears both as a room-scoped contract and as a generic `aggregateId` prefix).
  - Observation: once Redis key shapes are repeated across the hub doc, cheat sheet, reset matrix, and service docs, contract drift becomes easy and implementers cannot tell which invalidation/read path is canonical.
  - Expected pattern: central Redis catalogs should be the single enforced key-shape source, and derived docs should either reference that exact shape verbatim or be generated/validated against it in CI.

- `2026-04-21`: Publish lifecycle docs need one canonical state-transition source
  - Context: running the persistence/assets/migrations architecture review exposed direct contradictions between `system-architecture-versioning-runtime.md`, `microservices/game-design-service/asset-storage.md`, and `system-architecture-asset-store-runbook.md` about whether failed asset artifacts become `FAILED` first or are automatically `TOMBSTONED`, and whether purge control-plane APIs are already live.
  - Observation: when lifecycle states and current implementation notes are repeated across architecture, service, and runbook docs without one declared source of truth, the repo can carry mutually incompatible operational and implementation guidance at the same time.
  - Expected pattern: choose one canonical lifecycle contract doc for publish/repair/purge states, have other docs reference it for transition semantics, and add CI or doc-review checks for contradictory "implementation notes" across duplicated surfaces.

- `2026-04-21`: Environment-isolation contracts need schema enforcement against checked-in manifests and preflight output
  - Context: running the environments/secrets architecture review exposed that the documented expected-bindings/preflight contract is stricter than both the checked-in `design/operations/environments/*/expected-bindings.yaml` manifests and the canonical `dev-tools/deploy/preflight.sh` output.
  - Observation: once environment isolation is specified across architecture docs, runbooks, manifests, and scripts without one enforced schema/output check, the repo can appear deployment-ready while the actual gating path still misses required bindings and policy IDs.
  - Expected pattern: validate expected-binding manifests and preflight report shape in CI against one canonical schema, and fail changes that add or remove required policy IDs, binding identity fields, or evidence references without updating the contract everywhere.

- `2026-04-21`: Target-state ops docs need explicit current-implementation notes when slices land narrower control-plane seams
  - Context: checking the Redis operations/recovery review findings against the live `02.18.x` runtime slices showed that the code now has real current-boundary ownership and command-status substrate, while the top-level Redis reset docs still read as if the full region/tenant/cluster maintenance CLI already exists.
  - Observation: when slice docs honestly record "current-boundary" implementation limits but the higher-level architecture/runbook docs omit that note, reviews over-report some gaps as if nothing landed and operators are left with an overstated picture of what the repo can actually do today.
  - Expected pattern: any target-state architecture/runbook that depends on not-yet-landed control-plane breadth should carry a short implementation-notes section pointing at the live narrower slice boundary until the broader tooling is actually shipped.

- `2026-04-21`: Architecture-review findings need a mandatory live-slice cross-check before being treated as open blockers
  - Context: checking the world/content-authoring review findings against live `08.x` slices, protos, and service code showed that some apparent blockers were already resolved in canonical RPCs and implementations while service docs still carried older wording.
  - Observation: architecture reviews that read only target-state docs can misclassify doc drift as an implementation gap when slice notes, proto contracts, or live services have already narrowed or resolved the seam.
  - Expected pattern: before recording a blocker as still open, cross-check the relevant vertical slice, proto, and current implementation and then either (a) downgrade it to "import code back into design" or (b) confirm that no live slice/code seam exists yet.

- `2026-04-21`: Slice completion checkboxes need verification against proto and service seams
  - Context: checking monetization/account-lifecycle review findings against `02.1.6` showed the slice marks account export/delete/recovery as account-owned, while current Account Service REST, gRPC, and service methods still pass `tenantId` through export/delete and delete tenant-scoped billing records.
  - Observation: a checklist can drift from implementation when a broad slice lands adjacent auth/model work but leaves one claimed seam only partially changed.
  - Expected pattern: before marking a slice task complete, verify the public API schema, proto contracts, service implementation, and focused tests for that exact seam, not only the related architecture direction.

- `2026-04-21`: Auth architecture review prompts need canonical gateway/session/runtime docs in scope
  - Context: checking the auth/sessions/multi-tenancy review findings against live slices and code showed that connect-token enforcement, signed connect context, bootstrap discovery, and first-join membership behavior are resolved or narrowed in `system-architecture-gateway.md`, `system-architecture-session-behavior.md`, Account Service runtime docs, and `09.x` slice docs, but the review prompt did not include all of those sources.
  - Observation: auth trust boundaries are now split across account, gateway, Game Session, and slice-specific runtime docs; reading only the older top-level auth/frontend/service README set can report stale blockers that are really "import resolved slice/code decisions back into canonical docs" tasks.
  - Expected pattern: architecture-review prompts for auth/session flows should include the canonical gateway, session-behavior, authz-route-matrix, account runtime, and relevant 09.x slice docs, or explicitly require a live-slice/code cross-check before classifying a blocker.

- `2026-04-21`: Target-state API docs need proto-drift callouts until implementation lands
  - Context: checking designer tooling/modding review findings found Game Design docs and slices describing plugin publication/read APIs while `game_design_service.proto` and the service implementation do not yet expose those methods.
  - Observation: when API contract docs list target-state RPCs beside live proto-owned APIs without an implementation-status callout, reviewers and implementers can mistake planned control-plane seams for shipped wire contracts.
  - Expected pattern: keep proto-owned API docs explicit about whether each listed RPC is live or target-state, and add a follow-up verification task to update protos/services or remove stale target-state entries before marking the slice complete.

- `2026-04-21`: Secret-compliance validators must stay aligned with bootstrap evidence semantics
  - Context: checking deployment/CI/CD/security review findings against `validate-secret-compliance.yml` showed the architecture allows exactly one of `lastProvisionedAt` or `lastRotationAt`, but the current validator still requires `lastRotationAt`.
  - Observation: when first-deployment/bootstrap semantics are added to the design but the CI validator only understands rotation semantics, operators can either be blocked by valid bootstrap evidence or work around the gate with misleading rotation timestamps.
  - Expected pattern: evidence-schema changes should update the validating workflow in the same slice, including tests for bootstrap provisioning and post-restore refreshed evidence.

- `2026-04-21`: Scripting contract tables must be updated before sibling docs introduce new runtime exceptions
  - Context: checking scripting DSL/runtime review findings showed newer sibling docs had narrowed `onLoad`, dry-run, and event-scope ingress behavior while the high-precedence normative tables still required a live handler-style identity and success model.
  - Observation: when exception-heavy contracts are patched into service or runtime docs before the normative tables, architecture reviews keep rediscovering contradictions even though the intended decisions already exist nearby.
  - Expected pattern: for scripting/runtime contract changes, update `system-architecture-scripting-normative-contract-tables.md` first, then align service docs, observability docs, protos, and slices against that table in the same change.

- `2026-04-21`: Observability assets must import metric-label policy changes with the slice
  - Context: checking observability review findings against `02.14.4` showed the cardinality slice removed raw tenant/session labels from ordinary gameplay metrics, but older player-experience dashboard/snippet docs still described raw `tenantId` SLO labels.
  - Observation: when a later implementation slice hardens metric-label policy without updating all reference PromQL and dashboard docs, architecture reviews keep finding stale contradictions even though the intended direction is already implemented.
  - Expected pattern: future observability slices should update the architecture doc, reference PromQL, dashboards, and slice-support docs in the same batch when metric label policy changes.

- `2026-04-22`: Untracked repo files must be treated as potentially valuable local work unless a human confirms disposal
  - Context: an untracked audit markdown file tripped `lintMarkdown`, and deleting it to clear local hygiene would have destroyed live design work that only existed in the working tree.
  - Observation: in this repo, untracked files are not safe to treat as disposable temp noise just because they are outside Git or fail checks; they may be active human or AI work products.
  - Expected pattern: if an untracked file is not unquestionably generated junk and not the direct target of the task, leave it alone and ask before deleting or cleaning it up.
