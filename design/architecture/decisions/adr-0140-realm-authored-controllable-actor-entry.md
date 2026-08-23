# ADR 0140: Realm-Authored Controllable Actor Entry

## Status

Accepted

## Implementation Status

This decision is not implemented. Persisted character rows and realm-aware listing are partial, but synthetic-ID fallbacks, fixed RPG-oriented fields, published entry-policy resolution, versioned descriptors/templates, policy-specific zero/one/many handling, and fork-local identity proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `PLAYER-01`
- Decision date: 2026-07-20
- Decision key: `PLAYER-01`
- Primary capability: `AA-2.1`
- Affected capabilities: `AA-3.2`, `GR-3.1`, `EA-3.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of realm entry, character creation and selection, generic controllable actors, identity ownership, and isolated copies

## Context

Gameplay needs a stable persisted subject for session fencing, reconnect, state ownership, targeting, presence, and audit. Deriving that subject from an account ID, username, or hash is convenient for a prototype but creates identities that were never persisted or validated by the owning domain. It also prevents one account from owning multiple realm-valid choices and risks collisions or state aliasing.

The earlier player journey assumed every game exposed conventional character creation and selection with platform-shaped race, class, ability, and stat concepts. That is too narrow for games where the player's primary actor is a ship, nation, creature, party leader, or one pre-assigned role. Removing the persisted subject entirely would, however, push substantially different identity and controller semantics into every gameplay service.

Current ownership documentation is inconsistent: some player-facing text assigns character ownership to Account, while Entity Management persists and resolves the authoritative character rows. Isolated playtest copies also need new fork-local identity rather than a live reference to production state.

## Decision

### One Persisted Primary Controllable Actor

Every normal gameplay session binds to exactly one persisted, realm-valid primary controllable actor. FireMUD retains `characterId` and “character” as the generic shared contract name, but does not require the actor to be humanoid or to use traditional RPG concepts.

Entity Management allocates canonical character identity and owns the authoritative `{accountId, tenantId, playableStateNamespaceId, characterId}` association. Account owns global identity, tenant membership, access grants, and global/tenant profile data. It may consume a bounded Entity projection for discovery but does not become the character writer or ownership authority. Game Session owns only the active session attachment and controller fence.

No service may synthesize `characterId` from `accountId`, username, display name, a hash, or another local fallback. Absence of a persisted valid actor is a policy-specific entry result, not permission to invent one.

### Realm-Authored Entry Policy

Every published realm declares exactly one v1 entry policy:

- `PLAYER_CREATED`: the player creates an actor using the realm's exact versioned creation descriptor;
- `PRESEEDED_ONLY`: entry is limited to actors already seeded, assigned, or copied for that account and realm; or
- `AUTO_PROVISIONED`: after explicit join or equivalent entry, Entity Management idempotently provisions at most one actor from the realm's published template.

The policy, applicable descriptor or template identity, and applicable published version are explicit admission inputs resolved from the published realm snapshot. `PLAYER_CREATED` requires an exact valid descriptor; `AUTO_PROVISIONED` requires the exact current published template identity and version, and missing, stale, wrong-version, or malformed required descriptor/template data fails closed before creation or provisioning. `PRESEEDED_ONLY` has no descriptor/template requirement. Auto-provision enforces at most one actor for `{accountId, playableStateNamespaceId}` so retries or concurrent requests cannot create multiple actors. Each operation is separately replay-guarded by `{tenantId, playableStateNamespaceId, accountId, autoProvisionRequestId}` and an exact-match `mutationDigest`. Entity persists the exact published template identity/version with the guarded outcome; an exact retry returns the existing actor only when the digest and stored provenance match, while a mismatch fails closed as an idempotency conflict rather than silently reusing or replacing the actor.

`CHARS` and equivalent bootstrap discovery return only persisted actors valid for the trusted account and the server-resolved `{tenantId, worldSlug, realmSlug, playableStateNamespaceId, playableStateScope}` target; the active `gameInstanceId` remains a runtime fence. More than one valid actor requires explicit selection. Exactly one may be selected automatically. Zero actors follows the declared policy: offer creation, perform idempotent provisioning, or return the pre-seeded-only denial. These resolved values are reused for validation, not indiscriminately as durable keys: the durable actor key is `{tenantId, playableStateNamespaceId, characterId}`, auto-provision actor uniqueness uses `{accountId, playableStateNamespaceId}`, operation replay uses the separate request guard and digest above, and `playableStateScope` plus `gameInstanceId` remain policy/routing and active-runtime evidence rather than durable identity dimensions.

Every actor-entry mutation digest reserves one fixed field position for `canonicalCreationInput`. `PLAYER_CREATED` encodes a present, descriptor-validated canonical input there. `AUTO_PROVISIONED` encodes the exact `mutationDigest/v1` absent value `type=absent`, `presence=0`, and an empty payload; omission, `null`, and present-empty input are distinct values or invalid and never aliases for that absent value. Game Session and Entity use the same cross-language golden vectors for these presence cases and for the complete policy-specific mutation tuple.

### Game-Specific State Is Authored

Race, class, attributes, abilities, ship configuration, national resources, and similar concepts are game-authored components and facts tied to the published descriptor or template. They are not fixed fields every FireMUD game must present. The generic actor identity and session attachment remain stable while games choose their own state model.

Global and tenant account profiles remain separate from realm actor identity and gameplay state. A profile may display bounded projections of a player's games or actors, but cannot be used as the gameplay record or as a source for a synthetic character.

### Isolated Copies Receive New Identity

When seeded or snapshot playtest preparation copies a controllable actor into a fresh playtest namespace, Entity Management allocates a distinct fork-local `characterId`. The copy may retain `sourceCharacterId` only with its immutable `{sourceTenantId, sourcePlayableStateNamespaceId}` binding as audit or diagnostic provenance; `sourceCharacterId` alone is not a complete source reference. That source tuple is never a live cross-namespace identity, ownership, mutation, controller, reconnect, or merge link and is never authority for any of those operations. [ADR 0137](./adr-0137-isolated-playtest-state-modes-and-reset.md)'s isolation and no-merge-back rules remain unchanged.

Retained actor rows and any convergence from legacy controller or index identity must pass the existing [legacy controller-index migration gate](../system-architecture-session-behavior.md#legacy-controller-index-migration-gate) before namespace-qualified admission is enabled. This decision does not define a general compatibility or mapping layer: an exact typed mapping may be used only when it is unambiguous; unresolved or ambiguous legacy or fallback identity is quarantined and fails closed, never synthesized as an alias. Active sessions must be fenced or invalidated and then rebound under the converged identity before cutover.

### Deferred Session Shapes

Truly characterless gameplay and simultaneous control of multiple primary actors are deferred until a concrete game requires them. They need deliberate command routing, presence, takeover, reconnect, authorization, and state-fencing semantics rather than an ambiguous empty or repeated `characterId`. Secondary controlled entities, pets, summons, units, or indirect nation assets can exist under the one primary-controller model without deciding those future session shapes.

## Consequences

- Session, reconnect, controller, state, and audit boundaries always refer to a persisted owner-validated subject.
- FireMUD supports non-humanoid and non-RPG games without abandoning one coherent gameplay identity model.
- Realms that do not need a creation UI can seed or auto-provision the correct actor.
- Each published realm must define and validate an entry policy plus any descriptor or template it references.
- Isolated forks duplicate identity records deliberately, increasing materialization work but preventing production/playtest aliasing.
- Characterless or multi-primary-control games require a later explicit architecture extension.

## Alternatives Considered

### Require Conventional Character Creation Everywhere

Always present race, class, stats, and selection. This is familiar for MUDs but creates fake choices for games centered on ships, nations, or a single assigned role and hard-codes one genre into the platform.

### Synthesize One Character from the Account

Use the account ID, username, or a deterministic hash when no Entity row exists. This removes an onboarding write but creates an unowned identity, prevents policy-correct zero/one/many handling, and can alias or collide across realms and namespaces.

### Make Account the Character Owner

Store the account-to-character roster in Account and project gameplay state into Entity. This puts game-authored lifecycle and namespace semantics into the global identity service and creates a second authority beside Entity's persisted rows. Account remains identity and membership owner only.

### Support Characterless and Multi-Primary Sessions Now

Generalize the session to zero or many primary subjects immediately. This could serve additional game forms, but it changes controller uniqueness, command targeting, presence, reconnect, and state-fencing contracts without a concrete required use case. It is deferred.

## Implementation and Proof Obligations

Current implementation is materially partial and drifted. Entity Management has persisted character rows and realm-aware listing, but the schema and creation surface still expose fixed RPG-oriented fields. Game Session's unresolved-character path may use `accountId` when no selector exists or derive an ID from a hash of tenant, runtime instance, and name. The richer versioned creation descriptor is absent, policy-specific pre-seeded and auto-provision flows are incomplete, and documentation has assigned character ownership inconsistently between Account and Entity.

Implementation must remove synthetic ID fallbacks, make Entity allocation and account association mandatory, add explicit published entry-policy resolution, idempotent auto-provisioning, versioned descriptor validation, policy-specific zero/one/many roster handling, and distinct fork-local copy identity with optional provenance. Fixed legacy RPG columns must not remain mandatory platform semantics; implementation may migrate them into authored component state as the actor model converges.

Proof must cover all three policies; missing, stale, wrong-version, and malformed descriptors/templates; exact account, tenant, realm, and namespace association; zero, one, and multiple actor rosters; duplicate and concurrent auto-provision; repeated creation and concurrent distinct-request `PLAYER_CREATED` attempts against the same zero roster; actor selection by another account; synthetic-ID rejection; non-RPG components; shared versus isolated realms; fork-copy identity and source-provenance non-authority; reconnect and takeover using persisted identity; and absence of cross-namespace mutation or merge-back.

## Reversibility and Revisit Triggers

Descriptor schemas, authored component families, templates, projections, and UX may evolve while retaining Entity-owned persisted identity, explicit realm entry policy, and namespace isolation. Revisit the one-primary-actor session boundary only when a concrete game supplies requirements for characterless participation or simultaneous primary control and can define its authorization, presence, command, reconnect, and fencing behavior.

## Required Documentation Alignment

- [Player journeys](../../product/user-journeys/players.md)
- [Authentication](../system-architecture-authentication.md)
- [Entity Management README](../microservices/entity-management-service/README.md)
- [Entity Management API contracts](../microservices/entity-management-service/api-contracts.md)
- [Entity Management runtime and data](../microservices/entity-management-service/runtime-and-data.md)
- [ADR 0137](./adr-0137-isolated-playtest-state-modes-and-reset.md)
- [ADR 0132](./adr-0132-namespace-scoped-single-character-controller.md)
