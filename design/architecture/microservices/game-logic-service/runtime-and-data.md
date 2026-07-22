# Game Logic Service Runtime and Data

This document defines the Game Logic Service runtime model, dependency ownership assumptions, publish-gating role, and Redis/data boundaries.

## Implementation Status

The sections below define the target-state runtime contract. Current implementation and proof status is:

- The current implementation attests an empty owned-rule manifest and returns synthetic `appliedCommitId = "version:<versionId>"` because Game Logic does not yet own version-scoped draft rule tables. The empty-manifest digest is intentional and does not represent hidden service state.
- Same-type Game Logic takeover behind an existing edge connection is target behavior, but suppression of a client-visible reconnect after an ordinary qualifying Game Logic restart remains implementation or proof debt under ADR 0013.

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
- All commands are scoped by `tenantId` so rules execute only against data for the active game instance.
- Gameplay gRPC requests do not include JWTs. Game Session provides player identity from Redis via `SessionContext`, may refresh a JWT from Account Service if roles change, and does not validate tokens for gameplay. Service-to-service traffic still uses mutual TLS as described in the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.
- Flyway is enabled for consistency with other services, but the initial migration is empty because no tables are required.
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
