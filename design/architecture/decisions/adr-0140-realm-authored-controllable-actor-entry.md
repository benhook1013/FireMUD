# ADR 0140: Realm-Authored Controllable Actor Entry

## Status

Accepted

## Decision Record

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

The policy, descriptor or template identity, and applicable published version are explicit admission inputs. Missing creation-descriptor data blocks only `PLAYER_CREATED`. It does not impose a generic platform creation form on other policies. Auto-provision is idempotent for the account and playable-state namespace so retries cannot create multiple actors.

`CHARS` and equivalent bootstrap discovery return only persisted actors valid for the selected realm and account. More than one valid actor requires explicit selection. Exactly one may be selected automatically. Zero actors follows the declared policy: offer creation, perform idempotent provisioning, or return the pre-seeded-only denial.

### Game-Specific State Is Authored

Race, class, attributes, abilities, ship configuration, national resources, and similar concepts are game-authored components and facts tied to the published descriptor or template. They are not fixed fields every FireMUD game must present. The generic actor identity and session attachment remain stable while games choose their own state model.

Global and tenant account profiles remain separate from realm actor identity and gameplay state. A profile may display bounded projections of a player's games or actors, but cannot be used as the gameplay record or as a source for a synthetic character.

### Isolated Copies Receive New Identity

When seeded or snapshot playtest preparation copies a controllable actor into a fresh playtest namespace, Entity Management allocates a distinct fork-local `characterId`. The copy may retain `sourceCharacterId` as audit or diagnostic provenance. That field is never a live cross-namespace identity, ownership, mutation, controller, reconnect, or merge link. ADR 0126's isolation and no-merge-back rules remain unchanged.

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

Proof must cover all three policies; missing, stale, wrong-version, and malformed descriptors/templates; exact account, tenant, realm, and namespace association; zero, one, and multiple actor rosters; duplicate and concurrent auto-provision; repeated creation; actor selection by another account; synthetic-ID rejection; non-RPG components; shared versus isolated realms; fork-copy identity and source-provenance non-authority; reconnect and takeover using persisted identity; and absence of cross-namespace mutation or merge-back.

## Reversibility and Revisit Triggers

Descriptor schemas, authored component families, templates, projections, and UX may evolve while retaining Entity-owned persisted identity, explicit realm entry policy, and namespace isolation. Revisit the one-primary-actor session boundary only when a concrete game supplies requirements for characterless participation or simultaneous primary control and can define its authorization, presence, command, reconnect, and fencing behavior.

## Required Documentation Alignment

- `design/architecture/user-journeys-players.md`
- `design/architecture/system-architecture-authentication.md`
- `design/architecture/microservices/entity-management-service/README.md`
- `design/architecture/microservices/entity-management-service/api-contracts.md`
- `design/architecture/microservices/entity-management-service/runtime-and-data.md`
- `design/architecture/decisions/adr-0126-isolated-playtest-state-modes-and-reset.md`
- `design/architecture/decisions/adr-0128-namespace-scoped-single-character-controller.md`
