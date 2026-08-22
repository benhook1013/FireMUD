# FireMUD User Journeys: Creators

This guide summarizes typical workflows for game creators using FireMUD. It focuses on flows such as game creation, world design, scripting, publishing, and live updates. Each numbered step links to the microservice or design document that manages that portion of the flow. Use it alongside the [Architecture Overview](../../architecture/README.md), the [System Architecture Overview](../../architecture/system-architecture-overview.md), the [System Architecture Diagram](../../architecture/system-architecture-diagram.md), and the [System Context Diagram](../../architecture/system-context-diagram.md).

For other personas, see:

- [Player Journeys](./players.md)
- [Operator Journeys](./operators.md)
- [User Journeys Hub](./overview.md)

These journeys define observable product behavior and user-facing outcomes; technical contracts remain in the linked architecture documents.

## Implementation Status

The journeys below describe the target creator experience. The current implementation boundary is:

- **Publish and launch** – The external tenant-admin launch path, platform-admin emergency launch, and audited billing-recovery path are not complete. Game Session still owns the current internal lifecycle hooks, while Account remains authoritative for billing and runtime entitlements. See [Game Session Service API Contracts](../../architecture/microservices/game-session-service/api-contracts.md), [Account Service API Contracts](../../architecture/microservices/account-service/api-contracts.md), and [Logging & Admin Service API Contracts](../../architecture/microservices/logging-admin-service/api-contracts.md).
- **Live cutover** – Replacement cutover preparation exists, but activation and route switching are not yet one atomic implementation. The current path clears active bindings instead of providing the target bounded drain and reconnect experience. See [Versioning & Runtime Configuration](../../architecture/system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) and [Game Session Service API Contracts](../../architecture/microservices/game-session-service/api-contracts.md).
- **Linked plugins** – Publication remains separate from instance activation. Signed ZIP publication/intake and runtime activation/preflight seams exist, but complete bundle-to-compiled-runtime wiring remains partial; the accepted operator-permitted unsigned, platform-attested intake target remains incomplete. See [ADR 0111](../../architecture/decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md).
- **Draft authoring and model assistance** – Starter-profile materialization/upgrades and optional model-assisted proposals are target workflows and are not currently implemented. The target remains editable Draft content rather than runtime defaults, with scoped proposals that cannot publish or change live content; conflicting Draft bases require an explicit retry/resolution. See [ADR 0124](../../architecture/decisions/adr-0124-materialized-starter-profiles-with-conservative-draft-upgrades.md), [ADR 0126](../../architecture/decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md), and [ADR 0129](../../architecture/decisions/adr-0129-durable-fenced-multi-owner-draft-commits.md).
- **Whole-game portability** – Import/export, filesystem/Git interchange, and portable whole-game snapshots are not a v1 creator workflow. Typed Game Design APIs remain the supported authoring boundary. See [ADR 0125](../../architecture/decisions/adr-0125-defer-whole-game-portability-and-external-authoring-formats.md).
- **Script-transition handoff** – The live Game Session handoff lacks `scriptPinEpoch` and cannot yet reject same-version work from an older epoch at that boundary; the exact epoch-fencing guarantees below remain target-state rather than live proof. See [Game Session Service API Contracts](../../architecture/microservices/game-session-service/api-contracts.md) and the [Game Session runtime and tick coordination tracker](../../project-management/implementation-tracking/game-session-runtime-and-tick-coordination.md#capability-status).
- **Playtest access** – Account owns playtest-grant state, mutation, expiry, and revocation authority; Game Session owns gameplay admission, command fencing, and termination of affected active bindings. Creator-facing grant management, expiry handling, and account selection are incomplete. The target revocation behavior is not fully implemented: it must remove the affected tester's future visibility and admission, fence new commands, terminate that tester's connected fork sessions, and leave the fork and unrelated access intact. See [Account API Contracts](../../architecture/microservices/account-service/api-contracts.md#account-owned-playtest-grant-contract) for grant mutation and [Session Behavior](../../architecture/system-architecture-session-behavior.md#playtest-grant-and-lifecycle-session-consequences) for the local binding consequence.

## Table of Contents

- [Implementation Status](#implementation-status)
- [Goals](#goals)
- [Quick Reference](#quick-reference)
- [1. Game Creation](#1-game-creation)
- [2. World and Entity Design](#2-world-and-entity-design)
- [3. Add Automation & Scripting](#3-add-automation--scripting)
- [4. Publish and Start a Game Instance](#4-publish-and-start-a-game-instance)
- [5. Patch and Update a Live Game](#5-patch-and-update-a-live-game)
- [6. Branding and Customization](#6-branding-and-customization)
- [7. Playtesting & Analytics](#7-playtesting--analytics)
- [8. Extensibility & External Tools](#8-extensibility--external-tools)
- [Related Documentation](#related-documentation)

---

## Goals

- Describe creator-centric flows from initial game setup through live operations.
- Map each step to the microservices and tools used by creators.
- Connect creator flows to player and operator journeys without duplicating details.

---

## Quick Reference

- [Game Creation](#1-game-creation) – Start a new game project.
- [World and Entity Design](#2-world-and-entity-design) – Build worlds, entities, and content.
- [Add Automation & Scripting](#3-add-automation--scripting) – Add behaviors and scripted logic.
- [Publish and Start a Game Instance](#4-publish-and-start-a-game-instance) – Launch playable game instances.
- [Patch and Update a Live Game](#5-patch-and-update-a-live-game) – Ship updates and hotfixes.
- [Branding and Customization](#6-branding-and-customization) – Configure visual identity and theme.
- [Playtesting & Analytics](#7-playtesting--analytics) – Iterate using tests, feedback, and telemetry.
- [Extensibility & External Tools](#8-extensibility--external-tools) – Integrate external tools and plugins.

Account creation and login flows are covered in the [Player Journeys](./players.md#1-sign-up). Deployment, CI/CD, and platform upgrades are covered in the [Operator Journeys](./operators.md).

---

## 1. Game Creation

After signing up, creators start a new project using the [Game Design Service](../../architecture/microservices/game-design-service/README.md).

Each new game maps to a tenant (`tenantId`) under the [Multi-Tenancy](../../architecture/system-architecture-multi-tenancy.md#identity--tenant-model) design. Hosting and resource limits for that tenant are controlled by subscriptions as described in the [Subscription Management Design](../../architecture/microservices/account-service/subscription-management.md).

For v1, the creator lifecycle is:

1. **Create a Draft Tenant** – A creator can create and edit a tenant before paying for production gameplay. Draft tenants support authoring and internal setup but do not expose a public production realm.
2. **Assign Roles** – `designer` authors content and publishes versions. `tenantAdmin` owns routine runtime lifecycle for that tenant, while `platformAdmin` emergency controls are reserved for platform incidents or support.
3. **Resolve Billing and Go-Live Readiness** – Before the first public production realm starts, the tenant must satisfy plan and entitlement requirements and have a published version ready to launch. The creator can choose or repair a hosting plan, view high-level entitlement status, and understand why launch is blocked without requiring routine operator intervention.

Choosing a starter profile at creation copies its content into the tenant's editable Draft. A creator may revise or remove that content without changing the profile, and later profile edits do not silently overwrite the Draft. Launch is blocked until that Draft produces a published version; the runtime never falls back to the profile.

```plaintext
Account Service (user) → Game Design Service (new game)
```

---

## 2. World and Entity Design

Creators refine the world and its inhabitants using several services:

- **[Game Design Service](../../architecture/microservices/game-design-service/README.md)** – Provides versioned templates, ability editors, and runtime flag definitions.
- **[World Management Service](../../architecture/microservices/world-management-service/README.md)** – Stores zones and maps, generates new areas, and maintains pathfinding data. Scheduled world events notify other services when the environment changes.
- **[Entity Management Service](../../architecture/microservices/entity-management-service/README.md)** – Manages characters, NPCs, items, and inventory with deferred writes coordinated by the Game Session Service.
- **Procedural Generation** – The [World Management Service](../../architecture/microservices/world-management-service/README.md) owns generation of the selected world’s topology and persists the generated result. The [Automation & Scripting Service](../../architecture/microservices/automation-scripting-service/README.md) supplies post-generation population hooks after that topology is persisted. See [Procedural Generation](../../architecture/system-architecture-procedural-generation.md).
- **Classic-client extensions** – Plain-text gameplay is universal. MCP, GMCP, and other classic-client semantic extensions are deferred and unsupported; a future adapter requires current-client research, Game Session ownership, and exact end-to-end proof before it can be advertised. TCP Proxy and Gateway remain opaque bounded transport edges and do not negotiate or interpret extension semantics. The first-party WebSocket may use the versioned `PlayerOutput` projection described in [Mud Client Protocol and Classic-Client Extensions](../../architecture/system-architecture-mud-client-protocol.md).
- [Game Customization](../../architecture/system-architecture-game-customization.md) covers themes and branding tweaks.
- **World Editing Tools** – Use the [World Editing & Customization Tools](../../architecture/microservices/game-design-service/world-editing-tools.md) for room and region editing.
- **Ability & Action Tools** – Build combat mechanics with the [Ability & Action Design Tools](../../architecture/microservices/game-design-service/ability-action-tools.md).
- **Item & Equipment Balancing** – Tune gear progression in the [Item & Equipment Balancing Tools](../../architecture/microservices/game-design-service/item-equipment-balancing.md).
- **Visual Interface** – A [web-based visual editor](../../architecture/microservices/game-design-service/web-visual-interface.md) provides drag-and-drop editing.
- **Asset Storage** – Upload icons and sound effects via the [Asset Storage Setup](../../architecture/microservices/game-design-service/asset-storage.md).
- **Version Control & Templates** – [Version Control](../../architecture/microservices/game-design-service/version-control.md) and [Game Templates](../../architecture/microservices/game-design-service/game-templates.md) streamline collaboration and new projects.

World and entity changes are versioned so creators can iterate safely and roll back as needed. See [Game Templates](../../architecture/microservices/game-design-service/game-templates.md) for starting points.

Account creation and bootstrap establish identity only; they do not provision a default actor. Before a realm admits actors, the creator publishes the versioned, generic realm-authored entry policy and descriptor/template consumed by Entity Management after explicit realm entry. This handoff is defined by [ADR 0140](../../architecture/decisions/adr-0140-realm-authored-controllable-actor-entry.md); its fields remain game-authored rather than an RPG-specific platform schema.

For item and equipment authoring, creators define more than item names and stats. They also define game-specific equipment slots, optional slot groups, body-layout slot membership, item stackability, and item slot compatibility. Familiar slot names such as `HEAD` or `HAND` are content choices, not platform-global enums; a game can instead define slots such as `TAIL_RING`, `WING`, `PAW`, or `MODULE_BAY` and attach those slots only to body layouts that support them. Runtime equipment validation uses those published definitions, so a player cannot equip an item into a slot their selected character body layout does not expose.
If a published equipment vocabulary or body layout is incomplete, publication and launch remain blocked with a repairable validation outcome only for an equipment-enabled version whose actors and schema support equipping. A version without equip-capable actors and equipment schema may proceed without those equipment checks. When a live version changes that vocabulary, the creator must review the required remapping before cutover; the platform does not silently reinterpret existing equipment.

---

## 3. Add Automation & Scripting

Dynamic behavior is implemented via the [Automation & Scripting Service](../../architecture/microservices/automation-scripting-service/README.md):

- Script quests and NPC routines.
- Trigger world events in response to player actions.
- See [Scripting & Automation Framework](../../architecture/system-architecture-scripting.md) for details on the component-based DSL and sandboxing model.
- [Modding Framework](../../architecture/microservices/game-design-service/modding-framework.md) enables runtime plugins using the same scripting sandbox.

Creators experience embedded scripts as part of their authored game version or script-only patch. A successful design publish is followed by a separate runtime-readiness phase; it does not silently change a running game. Linked plugins use the same behavior language and safety limits; publication establishes an immutable plugin version, while instance activation remains a separate compatibility, preflight, and policy-gated creator-visible outcome.

For optional model-assisted authoring, the creator submits a scoped request and receives a proposal against the selected Draft base. The creator can inspect, edit, accept, or reject it like any other Draft change; a model has no direct live-game or storage access. A proposal becomes stale only when a newer commit overlaps its complete affected aggregate/scope set, including any required containing scopes; a disjoint-scope commit does not invalidate it. An overlapping change produces a visible conflict/retry outcome rather than a silent merge.

---

## 4. Publish and Start a Game Instance

Once the world is ready:

1. **Publish a Version** – A `designer` or `tenantAdmin` publishes the current design in the Game Design Service. A successful publish produces a version that can be selected for launch; failed validation or asset preparation leaves it unavailable until the creator repairs and republishes it. See [Versioning & Runtime Configuration](../../architecture/system-architecture-versioning-runtime.md#game-version-publishing).
2. **Launch the Production Realm** – A `tenantAdmin` submits the published version for launch. The platform reports the launch as progressing, successful, or failed; on success, [Game Session Service](../../architecture/microservices/game-session-service/README.md) creates the game instance and [World Creation Workflow](../../architecture/microservices/world-management-service/world-creation-workflow.md) seeds it from the published world data. If required version, world, authorization, or runtime checks cannot be satisfied, launch fails closed and player admission does not open. An uncertain result remains recoverable through the same launch status rather than requiring the creator to create a duplicate instance. The audited control-plane boundary is described by [Logging & Admin Service](../../architecture/microservices/logging-admin-service/README.md), and billing and entitlement decisions are owned by [Account Service](../../architecture/microservices/account-service/README.md).
3. **Check Entitlements** – Launch fails closed unless billing and plan entitlements permit gameplay for the tenant. The creator sees an entitlement or billing reason that can be resolved in the control plane rather than a generic launch failure. See [Account Runtime and Data](../../architecture/microservices/account-service/runtime-and-data.md#membership-and-entitlement-authority).
4. **Open Player Admission** – Once the realm is healthy, it becomes the default production realm surfaced to players in `WORLDS` / `REALMS` / `PLAY`. In v1, this production realm is also the only realm that may be publicly discoverable to authenticated players who do not already hold tenant membership. If the realm never reaches a healthy admissible state, it remains unavailable to players and the creator sees the terminal launch outcome.
5. **Emergency Override** – A `platformAdmin` may use a separate break-glass path for platform incidents or support. A successful override launches only the specifically authorized target; missing, expired, stale, or mismatched authorization fails closed without a partial launch. Routine tenant-admin launches do not depend on this path. See [Account Service API Contracts](../../architecture/microservices/account-service/api-contracts.md#operator-authorization-references), [Logging & Admin Service API Contracts](../../architecture/microservices/logging-admin-service/api-contracts.md), and [Game Session Service API Contracts](../../architecture/microservices/game-session-service/api-contracts.md).

The creator sees explicit lifecycle progress and failure outcomes for a launch. A replacement can remain in preparation or fail before player admission; a failed pre-activation instance remains visibly pending cleanup until the required owner acknowledgements complete, and failure alone does not imply termination. The current healthy realm remains the player-facing target until the replacement is ready, and cleanup of the old realm is part of completion rather than an invisible background assumption. See [ADR 0123](../../architecture/decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md).

If launch fails for billing or entitlement reasons, the creator remains in the control plane and sees a billing-safe recovery path rather than a generic launch failure. A tenant admin may inspect tenant subscription and invoice state, but only the current billing-owner account may access its payment-instrument wallet after recent reauthentication. A `billingAdmin` or `platformAdmin` may intervene only through a separately audited cross-tenant recovery action, not as ordinary owner-wallet access. The recovery action does not impersonate the billing owner or grant standing wallet access. After the subscription is repaired, a fresh launch preflight must confirm the tenant lifecycle is `trialing` or `active` and the entitlement snapshot reports `allowNewInstanceStarts=true`; only then may the `tenantAdmin` retry launch. Missing, stale, or otherwise unavailable `allowNewInstanceStarts` evidence blocks retry. `allowPublicJoin` and `allowNewGameplayBindings` govern their own admission operations and are not additional prerequisites for retrying the launch. A `platformAdmin` is not the routine retry actor and may intervene only through the separately described emergency override. Starting or editing live gameplay configuration is not billing-safe and remains blocked while the tenant is `suspended` or `canceled`. See [Account Service Subscription Management](../../architecture/microservices/account-service/subscription-management.md) and [Logging & Admin Service API Contracts](../../architecture/microservices/logging-admin-service/api-contracts.md).

```plaintext
Creator → Game Design Service (publish) → Control plane → Game Session Service (launch) → World Management (create instance) → Player admission
```

---

## 5. Patch and Update a Live Game

1. **Iterate on Content** – Creators modify worlds, items, or rules using the [Game Design Service](../../architecture/microservices/game-design-service/README.md).
2. **Publish a New Version** – The updated design is published with patch notes so players can review changes.
3. **Publish a Script Patch** – For quick fixes, the [Game Design Service](../../architecture/microservices/game-design-service/README.md) emits a `scriptPatchVersion` like `v42-script.3` linked to the current version.
4. **Choose the Rollout Path**
   - **Target-state script-only patch** – After Automation reports `READY` for the exact `scriptPatchVersion` and its `baseVersionId` matches the instance `runtimeVersionId`, Game Session explicitly repins the target `scriptPatchVersion` and advances `scriptPinEpoch`. Automation's readiness and observed-pin projection gate new scripted admission until it observes the matching exact Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple. That projection is local evidence only: missing, stale, or mismatched tuple evidence fails closed, and Automation cannot select, advance, override, or authorize the Game Session tuple. Game Session remains the script-pin authority and enforces that exact tuple as the admission fence at runtime handoff and enqueue, fencing older script work; ordinary non-script gameplay admission and ticks continue while the script transition converges. Any interval carry-forward follows the timer identity's explicit continuity declaration, stable logical key, and typed compatibility checks; script-only rollout does not imply schedule continuity.
   - **Full-version content change** – A `tenantAdmin` creates a replacement-instance cutover to a new published version. The replacement must satisfy the complete [full-version launch/cutover predicate](../../architecture/system-architecture-versioning-runtime.md#launch-descriptor-version-resolution-rules) and [replacement-instance compatibility preflight](../../architecture/system-architecture-versioning-runtime.md#replacement-instance-upgrade-contract) before the realm route changes. A successful cutover makes the new instance the target for new admissions; an incompatible or otherwise failed preparation leaves the replacement unavailable and reports the reason to the creator. See [Game Session Service API Contracts](../../architecture/microservices/game-session-service/api-contracts.md).
   - **Plugin-only change** – A published plugin version uses the separate instance-scoped [`SetPluginActiveVersion` activation path](../../architecture/system-architecture-scripting-control-plane-api.md#setpluginactiveversion) after the canonical compatibility and policy preflight; it does not use replacement-instance cutover.
5. **Player Experience During Cutover** – After a successful full-version replacement cutover, new admissions and reconnects follow the new realm target. Already connected players receive a bounded drain and then normal lobby reconnection. During script-only transition convergence, ordinary non-script gameplay admission and ticks continue, and no replacement drain or lobby reconnection is required; Automation's observed exact-tuple projection remains a local fail-closed gate and cannot select, advance, override, or authorize the tuple, which remains Game Session-owned. If the full-version cutover is rejected, the creator sees the failure and no unready replacement is exposed. See the canonical [realm routing contract](../../architecture/system-architecture-versioning-runtime.md#realm-routing-contract-for-player-addressable-realms) and [Game Session Service API Contracts](../../architecture/microservices/game-session-service/api-contracts.md).
6. **Rollback** – A `tenantAdmin` may explicitly choose the applicable rollback path, subject to authorization and the predicates defined for that rollback path:
   - **Full-version rollback** uses a replacement-instance cutover to a previously published compatible version, with release-attestation, version-state, compatibility/remap, lifecycle-fence, and route-swap predicates; the new instance is prepared and admitted before the route changes.
   - **Target-state script-patch rollback** uses a Game Session repin to a previously published, tenant-`READY`, base-compatible script patch under the [RollbackScriptPatchVersion compatibility predicates](../../architecture/system-architecture-scripting-control-plane-api.md#rollbackscriptpatchversion), with the exact current pin/epoch fence, and advances `scriptPinEpoch`; Automation must satisfy the [Pin Convergence Acknowledgment Predicate](../../architecture/system-architecture-scripting-rollout-and-rollback.md#pin-convergence-acknowledgment-predicate) for the exact tuple before script admission resumes.
   - **Linked-plugin-only rollback** follows the separate script/plugin runtime path, selecting exact `(pluginId, pluginVersionId, bindingId)` and using the complete [`SetPluginActiveVersion` activation predicate](../../architecture/system-architecture-scripting-control-plane-api.md#setpluginactiveversion); it is not a full-version replacement-instance cutover.
   The creator sees the scoped convergence result. `platformAdmin` is break-glass override only.
7. **Safe Activation** – Cross-service updates use the documented rollback and reconciliation boundaries: pre-activation failures are reported before the new version serves, while post-activation issues converge through retry and reconciliation. See [Transaction Strategies](../../architecture/system-architecture-transactions.md) and [Game Session Service API Contracts](../../architecture/microservices/game-session-service/api-contracts.md).
8. **Verify Performance** – Check metrics, traces, and rollout signals after deployment; see [Logging & Monitoring](../../architecture/system-architecture-logging-monitoring.md) and [Testing Strategy](../../architecture/system-architecture-testing.md).

```plaintext
Routine actor: tenantAdmin → Game Design Service (publication/descriptor compatibility) → Game Session (script-pin authority and exact-tuple admission fence at runtime handoff/enqueue) + Automation & Scripting (readiness, plugin activation, and observed-pin projection gate for new scripted admission) → Script Patch Pin, Plugin Activation, or Replacement-Instance Cutover
Break-glass: platformAdmin → separately authorized emergency path; no bypass of other admission, compatibility, or readiness predicates
```

### Example Hotfix DSL

```yaml
- action: hotfix_script
  version: "v42"
  patchVersion: "v42-script.3"
  scripts:
    - "npc-barkeep"
    - "docks-rat-encounter"
  reason: "Live AI bug fix during event"
```

Hotfixes follow the [emergency hotfix procedure](../../operations/README.md#emergency-hotfix-procedure) to ensure minimal downtime.

Example rollout choice:

- **Use a script-patch pin** when the change is limited to automation behavior, such as fixing an NPC conversation tree or encounter trigger while keeping the same published world, entity, and asset bundle.
- **Use a replacement-instance cutover** when the change includes new rooms, altered entity templates, balance data, assets, or any other non-script content that requires a new published version to become active.

Hotfix procedures and runtime rollout steps are shared with operators for auditability and incident response; see [Testing & Continuous Delivery](./operators.md#3-testing--continuous-delivery) and [Platform Service Updates](./operators.md#6-platform-service-updates) for CI/CD details.

---

## 6. Branding and Customization

Creators adjust the look and feel of their games through the Game Design Service at design time. When a version is published, branding assets are uploaded to tenant- and version-scoped object storage and a `manifest.json` is generated. Runtime clients fetch the manifest for the bundle actually resolved at `PLAY` time, not just "the tenant in general," so production and fork realms can present different branding when they run different published builds. See [Frontend Architecture](../../architecture/system-architecture-frontend.md) and [Game Customization](../../architecture/system-architecture-game-customization.md) for details.

---

## 7. Playtesting & Analytics

Before launch or after major updates, creators validate changes with **forked playtest realms**:

1. **Initialize the playtest** – A `tenantAdmin` selects the target realm and one explicit mode: `fresh`, `seeded`, or `snapshot`. Only `snapshot` selects a source realm, and its scope is exactly `whole-realm` or `selected-roster`; selected-roster includes the complete dependency closure required for the chosen accounts/characters. The canonical owner-manifest, namespace, generation, and no-merge rules are in [Versioning & Runtime Configuration](../../architecture/system-architecture-versioning-runtime.md#fork-snapshot-boundary-for-playtest-realms).
2. **Choose the target build** – The isolated lifecycle may run the source version for reproduction or a newer `versionId` / `scriptPatchVersion`, subject to the same publication, compatibility, readiness, entitlement, and quota gates as any capacity-creating runtime operation. Each lifecycle/reset receives a fresh `playableStateNamespaceId`; replacing its runtime retains that namespace.
3. **Invite testers** – Access is explicit and Account-owned. Only testers with a current grant and `ACTIVE` tenant membership see or enter the fork. `tenantAdmin` manages grants in the exact tenant through the Account contract; `platformAdmin` is a distinct audited break-glass path. Grant expiry is bounded by the fork expiry, revisions are monotonic, and Account tombstones revocations so delayed retries cannot restore access. Account remains authoritative for grant state and revocation; Game Session consumes that authority for gameplay admission, command fencing, and active-binding termination. See [Account API Contracts](../../architecture/microservices/account-service/api-contracts.md#account-owned-playtest-grant-contract).
   - Grant expiry or revocation removes only the affected account's visibility and gameplay authority, fences new commands, terminates its connected fork bindings through Game Session, and blocks `PLAY`/reconnect. It does not delete the fork or affect unrelated tenant/world/realm access. A friendly scheduled ending is different: close admission and complete bounded drain before revoking grants; revocation is not an indefinite drain request. See [Session Behavior](../../architecture/system-architecture-session-behavior.md#playtest-grant-and-lifecycle-session-consequences).
4. **Collect Feedback** – Feedback is collected per the [Playtesting & Feedback](../../project-management/slice-support/playtesting-feedback.md) flow and correlated with the fork realm in analytics.
5. **Reset or expire the fork** – A reset starts a new lifecycle generation with a fresh namespace and explicit fresh/seeded/snapshot input; failed preparation leaves the prior generation authoritative. Fork expiry closes admission and follows the lifecycle cleanup contract. Runtime writes remain isolated and never merge automatically into production or another playtest.
6. **Promote by Normal Launch/Cutover** – Successful playtests inform a normal production rollout; there is no direct "promote this fork" merge path for runtime state.

Common fork use cases:

- **Reproduce the current live problem** – Fork the current production realm on the same `versionId` and `scriptPatchVersion` to reproduce a bug against copied live gameplay state without risking the public realm.
- **Validate an upcoming release** – Fork the current production realm but launch the fork on a newer `versionId` or `scriptPatchVersion` so testers can evaluate the new build against realistic copied state before the production cutover.

Fork lifecycle choices:

- **Reset an existing fork** – Reuse the same playtest realm identity, but begin a new playtest lifecycle with a fresh playable-state namespace and replace its fork-local gameplay state with a fresh application of the chosen source snapshot. Use this when the same tester group and fork purpose remain valid across iterations; the realm identity is reused, but the lifecycle namespace is not.
- **Create a new fork** – Create a new playtest realm with a new identity and fresh visibility/access configuration. Use this when the next test cycle needs a separate audience, separate audit history, or side-by-side comparison with another fork.

---

## 8. Extensibility & External Tools

Creators extend gameplay using external editors and runtime plugins:

1. **Classic-client semantic extensions** – MCP, GMCP, and other classic-client extensions are deferred and unsupported. Plain-text Telnet remains the universal gameplay contract; TCP Proxy and Gateway carry opaque bounded transport only and do not negotiate or interpret extensions. If a future adapter is selected, Game Session must own its negotiation and semantic state, and exact current-client interoperability proof is required before advertisement. See [Mud Client Protocol and Classic-Client Extensions](../../architecture/system-architecture-mud-client-protocol.md).
2. **Modding Framework** – Plugins packaged through the [Game Design Service](../../architecture/microservices/game-design-service/modding-framework.md) inject custom logic at runtime. The [Automation & Scripting Service](../../architecture/microservices/automation-scripting-service/README.md) executes them in a sandbox.

```plaintext
Plain-text client → TCP Proxy Service → Spring Cloud Gateway → Game Session Service → other backend services
```

---

## Related Documentation

- [Analytics Dashboards](../../architecture/microservices/logging-admin-service/analytics-dashboards.md)
- [Game Creator Guide](../../user-guides/game-creator-guide.md)
- [Game Customization](../../architecture/system-architecture-game-customization.md)
- [Game Templates](../../architecture/microservices/game-design-service/game-templates.md)
- [Modding Framework](../../architecture/microservices/game-design-service/modding-framework.md)
- [Playtesting & Feedback](../../project-management/slice-support/playtesting-feedback.md)
- [Procedural Generation](../../architecture/system-architecture-procedural-generation.md)
- [Scripting & Automation Framework](../../architecture/system-architecture-scripting.md)
- [Versioning & Runtime Configuration](../../architecture/system-architecture-versioning-runtime.md)
