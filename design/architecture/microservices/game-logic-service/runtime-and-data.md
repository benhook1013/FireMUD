# Game Logic Service Runtime and Data

This document defines the Game Logic Service runtime model, dependency ownership assumptions, publish-gating role, and Redis/data boundaries.

## Implementation Status

The sections below define the target-state runtime contract. Current implementation and proof status is:

- Game Logic now has insert-only owner storage for an explicit `EMPTY_RULE_INPUT_MANIFEST/v1` revision on a fresh Version, including the complete shared Draft binding, exact Game Design source proof, epoch-zero claim, immutable local result, and separate content/ability-schema digests. The apply service is unregistered and denies by default because no authenticated current-source plus Account commit-authority verifier is supplied. The existing `GetDraftDesignDigest` handler remains denied and does not infer empty input from missing rows or a test-created manifest; authenticated source, coordinator, and synchronized-visibility integration remain required. The scoped PostgreSQL test definitions use explicitly synthetic owner participants and test authority; no Docker execution is claimed. The publication boundary is recorded in [API Contracts](api-contracts.md).
- Same-type Game Logic takeover behind an existing edge connection is target behavior, but suppression of a client-visible reconnect after an ordinary qualifying Game Logic restart remains implementation or proof debt under ADR 0013.
- Target: `SendCommunication` and every other player-delegated gameplay RPC propagate one complete, validated typed `PlayerExecutionContext` from Game Session through Game Logic and onward. Game Logic validates the context against request and owner evidence, never treats caller-supplied identifiers as authority, remains stateless, and owns semantic gameplay outcomes; Game Session owns session, reconnect, and presentation state. This is the ADR 0024 target contract.
- Current implementation gap: the live `SendCommunicationRequest` is still a flat schema with tenant/session/character/account, communication/text and target metadata, room, game-instance, speaker, and effect fields plus legacy `session_attestation`; its current proto does not carry typed `playableStateNamespaceId`, `playableStateScope`, region/epoch, pointer, or admitted-bundle fields. Complete typed-context propagation is therefore not current behavior or proof.

## Runtime Notes

- Stateless service accessed over gRPC by other microservices.
- No authoritative gameplay state may exist only in Game Logic process memory. Any state that must survive instance loss belongs in authoritative domain services, durable ledgers, or Game Session-owned coordination, so another Game Logic instance can take over immediately.
- Same-type Game Logic instances must be freely replaceable behind the current caller connection. Meaningful gameplay state needed after a non-edge restart must be reconstructed from authoritative service APIs and Game Session-provided tick/session context rather than preserved only in local memory.
- Uses a modular command parser for extensibility. The text protocol's system commands such as `LOGIN`, `LOGON`, and `PING` are interpreted and completed by Game Session; this service focuses on gameplay commands only, as described in the [Game Session Service](../game-session-service/protocols.md#minimal-text-command-protocol).
- Deterministic rule execution is required; random seeds come from Game Session.
- Fetches contextual world and entity data on demand via gRPC.
- Gameplay rules are read from this service's own versioned data when a version is activated; the runtime service does not query design or admin databases.
- Integrates with the tick system described in [Tick System and Runtime Design](../../system-architecture-ticks.md) to preserve deterministic command ordering.
- Cross-service combat or trade operations run within ticks and rely on Redis-based rollback, not sagas. See [Transaction Strategies](../../system-architecture-transactions.md).
- The target runtime contract is that every player-delegated gameplay RPC carries the complete validated typed `PlayerExecutionContext`, including `tenantId`, `playableStateNamespaceId`, server-derived `playableStateScope`, active `gameInstanceId`, and applicable region/epoch or executor fences. The live per-RPC schemas, including `SendCommunication` and its legacy `session_attestation`, remain an implementation gap; see the [PlayerExecutionContext contract](../../system-architecture-authentication.md#gameplay-player-execution-context-contract-normative).
- Gameplay gRPC requests do not include JWTs. Game Session provides player identity from Redis via `SessionContext`, may refresh a JWT from Account Service if roles change, and does not validate tokens for gameplay. Service-to-service traffic still uses mutual TLS as described in the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- Flyway is enabled. The initial migration remains empty; V2 adds only the explicit immutable empty-manifest ledger. No versioned non-empty rule or ability source exists yet, so this ledger does not authorize a substitute source in another service or process-local state.
- NPC morale and aggression-state evaluation remain part of this service's runtime behavior. When gameplay rules consume faction or reputation signals sourced from Social & Groups, the local morale logic is still owned here, including transitions such as `FLEEING` and `SURRENDERED`; cross-service reputation data informs the decision, but Game Logic owns the gameplay-state consequence.

## Workflow Participation

The Game Logic Service does not orchestrate or own synchronous saga or Temporal workflows. All gameplay commands execute inside ticks using Redis-based rollback and the transaction model described in [Transaction Strategies](../../system-architecture-transactions.md).

When a game version is published, its rule data is prepared and finalized by the Game Design and Game Session services; this service reads the already-published, versioned rule data for the active `runtime_version` and does not participate directly in the durable `publish` workflow.

Role classification: Game Logic is a digest-gate participant for full publishes, not a workflow-step participant, unless future publish workflows add explicit finalize or compensation steps owned by this service.

## Draft Digest Contract

For full-version publish gating, this service is still a required digest participant even though it does not orchestrate publish-workflow steps. It must expose `GetDraftDesignDigest(tenantId, versionId)` and publish a service-local digest input manifest with:

- included objects such as version-scoped rule and configuration tables this service owns that affect runtime command behavior;
- excluded objects such as runtime queues, caches, telemetry tables, and other non-launchability data;
- canonicalization rules covering stable ordering, normalization, and null or default handling before hashing; and
- `digestSchemaVersion` bump criteria, where any include, exclude, or canonicalization change requires an explicit schema bump and replay or re-record workflow.

Publish gating must fail closed if this service cannot attest a digest under its documented manifest for the reported `digestSchemaVersion`.

The typed digest response distinguishes the complete rule-input `contentDigest` from the dedicated `abilitySchemaDigest`. Both belong to the same exact tenant/version, `appliedCommitId`, and supported `digestSchemaVersion`; a value from Automation, another participant, another commit or an unsupported source schema cannot substitute for Game Logic's ability evidence. The optional carrier represents absent evidence, not a proved empty schema. A successful full-version publish requires the dedicated Game Logic value, preserves it in the immutable release participant evidence and compares it exactly for plugin compatibility. The non-empty versioned rule/ability source and its complete producer remain future implementation work; the current public digest handler must continue rejecting requests rather than manufacture either digest.

### Fresh-version explicit empty rule-input manifest

The only owner apply shape currently supported is one explicit `EMPTY_RULE_INPUT_MANIFEST/v1` revision for a genuinely fresh Version. Its canonical Game Logic revision payload is `{"intentKind":"EMPTY_RULE_INPUT_MANIFEST/v1","manifest":{"abilitySchemas":[],"ruleInputs":[],"schemaVersion":1}}`. Both arrays are present and empty by creator intent; query absence, an absent row, or a fabricated version marker is not proof of an empty source. Any non-empty or configured rule/ability input is rejected until a future owner source and contract are defined.

The owner operation consumes the complete existing `common.authoring.DraftCommitBinding` unchanged. Its target retains the exact canonical tenant and Version UUID plus Game Design Version/source row, private tenant-key, and provenance evidence. The full five-owner declaration remains mandatory. Game Logic derives exactly one affected tuple: owner `GAME_LOGIC`, aggregate `VERSION_RULE_MANIFEST`, aggregate ID equal to the canonical Version UUID, scope type `VERSION`, scope ID equal to that UUID, and expected epoch `0`. It rejects an omitted or additional Game Logic revision or affected unit and any nonzero expected epoch. The current-source and Account commit-authority verifier must validate that exact binding and keep authority effective through the Game Logic transaction; no production verifier is currently supplied, and the apply service remains unregistered and default-denied.

The owner transaction inserts one immutable row keyed by canonical tenant and Version. That insert is the storage-level epoch-zero compare-and-swap: it retains the exact source proof, complete canonical binding and digest, explicit owner revision, request ID, actual commit ID, base commit, semantic digest preimages, aggregate/scope epoch advances from `0` to `1`, and exact `GAME_LOGIC` owner result together. A duplicate request returns the original row only when every retained binding and input byte matches. Any changed field, including source, request, commit, base, revision, or binding, conflicts; concurrent first claims retain one original result. The database rejects update and delete of the row. This local owner result is not proof that the other four owners applied or that the synchronized Game Design read fence advanced.

Digest schema `1` hashes only the canonical RFC 8785 JSON semantic input `{"abilitySchemas":[],"digestSchemaVersion":1,"ruleInputs":[]}` as SHA-256 lowercase hexadecimal without a prefix. Its golden vector is `71b4da6a96f68a6f85d48731f5b4e448d05d21241ca75016ef0c9a0000c14f74`. The separate ability-schema digest hashes its own canonical RFC 8785 JSON preimage `{"abilitySchemaDigestDomain":"firemud.game-logic.ability-schema/v1","abilitySchemaVersion":1,"abilitySchemas":[]}`; its golden vector is `a6c1b6d52654bce002ddb64de2d84853061aa87cb15407547038cc3d4e7cfbd4`. The digest preimages include the explicit semantic arrays and their schema/domain versions. Tenant/version scope, Game Design source proof, full binding, request/commit/base identity, owner epochs, owner result, and storage timestamps are retained as provenance but excluded from these content hashes. Any change to included inputs, exclusions, canonicalization, or digest schema requires an explicit schema bump and replay/re-record workflow.

The storage readback is internal owner evidence for later composition. It does not enable `GetDraftDesignDigest`: the public protected method remains fail-closed until an authenticated current-source and Account verifier, exact applied binding, and coordinated synchronized-visibility read path are integrated. Missing or unsupported rule/ability evidence remains a hard publication gate failure.

## Redis Role and Prefixes

### Coordination Redis

- This service does not access Coordination Redis directly.
- It never issues commands against `tick:*`, `timer:*`, `retry:*`, `session:*`, or other coordination prefixes; all tick scheduling, locking, and staging live in Game Session and its Lua registry as described in [Redis Architecture](../../system-architecture-redis.md).
- Tick context is provided by Game Session via gRPC, for example `tickId`, region metadata, and effect-guard identifiers, rather than by reading Redis state.

### Cache/Rate-Limit Redis

- The Game Logic Service does not maintain its own Redis-backed caches today.
- Any future read-side caches for rules or computed aggregates must use Cache/Rate-Limit Redis and the key naming, TTL, and versioning patterns in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md), never Coordination Redis.
- Game Logic does not read or write shared cache prefixes owned by other services such as `view:room-look:*`, `inventory:*`, `character-cache:*`, or `chat:*` directly; it treats World Management, Entity Management, and Social & Groups as the owners of those aggregates and accesses them via their gRPC APIs.
- Correctness-critical flows such as combat, visibility, movement, and chat delivery decisions are always driven from authoritative service APIs and Class A caches, not from TTL-only caches such as `view:room-look:*`. This matches the central Redis cache restriction that Game Session is the sole writer for `view:room-look:*`, and Game Logic consumes LOOK results only via gRPC.
- Any future Redis usage in this service should adhere to the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md), including prefix registration, role selection, and slotting rules.

## Data and Command Flow

This service is largely stateless. It relies on:

- contextual entity and world data fetched from other services via gRPC; and
- temporary command queues stored in Redis by Game Session.

Command flow:

1. Commands are queued in Redis by Game Session.
2. The lease-owning Game Session executor invokes this service over gRPC with the queued command plus tick and session context, and this service loads the required world and entity context to resolve the action.
3. The gRPC response returns the structured result to Game Session for commit and delivery to players.

This execution shape is deliberate for failover: Game Logic computes from supplied tick/session context plus authoritative service reads, then returns a structured result. Because meaningful state is not anchored to one process, same-type Game Logic instances can take over behind an existing edge connection.

This means an ordinary Game Logic restart is a short stall or explicit command failure behind the caller connection, not a client-visible transport reconnect event. ADR 0013's 10-second ordinary target and 30-second hard hidden-recovery cutoff apply to the player-facing recovery envelope; an individual ambiguously delivered command still follows the at-most-once edge and durable internal effect rules rather than being replayed blindly.
