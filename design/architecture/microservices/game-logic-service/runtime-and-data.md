# Game Logic Service Runtime and Data

This document defines the Game Logic Service runtime model, dependency ownership assumptions, publish-gating role, and Redis/data boundaries.

## Implementation Status

The sections below define the target-state runtime contract. Current implementation and proof status is:

- Full-version digest reads remain fail closed: the bounded owner-local source-intake construction does not yet establish the complete authenticated producer, terminal settlement and selected-manifest lookup needed for attestation. It does not return an empty-manifest attestation or infer an empty authored set from absent storage. The [authoring tracker](../../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md#current-status) owns construction and proof status; the Draft digest contract below is not a claim of completed producer or intake proof. Runtime activation remains unavailable until publication carries the required release attestation.
- Same-type Game Logic takeover behind an existing edge connection is target behavior, but suppression of a client-visible reconnect after an ordinary qualifying Game Logic restart remains implementation or proof debt under ADR 0013.
- Target: `SendCommunication` and every other player-delegated gameplay RPC propagate one complete, validated typed `PlayerExecutionContext` from Game Session through Game Logic and onward. Game Logic validates the context against request and owner evidence, never treats caller-supplied identifiers as authority, remains stateless, and owns semantic gameplay outcomes; Game Session owns session, reconnect, and presentation state. This is the ADR 0024 target contract.
- Current implementation gap: the live `SendCommunicationRequest` is still a flat schema with tenant/session/character/account, communication/text and target metadata, room, game-instance, speaker, and effect fields plus legacy `session_attestation`; its current proto does not carry typed `playableStateNamespaceId`, `playableStateScope`, region/epoch, pointer, or admitted-bundle fields. Complete typed-context propagation is therefore not current behavior or proof.

## Runtime Notes

- Stateless gameplay execution accessed over gRPC by other microservices; immutable authored-source intake evidence is durable owner storage, not process-local player or session state.
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
- Flyway preserves the empty initial migration and adds owner-local immutable source-intake storage separately; this does not introduce authoritative player or session state here.
- NPC morale and aggression-state evaluation remain part of this service's runtime behavior. When gameplay rules consume faction or reputation signals sourced from Social & Groups, the local morale logic is still owned here, including transitions such as `FLEEING` and `SURRENDERED`; cross-service reputation data informs the decision, but Game Logic owns the gameplay-state consequence.

## Workflow Participation

The Game Logic Service does not orchestrate or own synchronous saga or Temporal workflows. All gameplay commands execute inside ticks using Redis-based rollback and the transaction model described in [Transaction Strategies](../../system-architecture-transactions.md).

Before a game version's publication digest gate, Game Design supplies its authored inputs through the separate authenticated source-intake boundary below, and Game Logic validates and immutably retains those exact inputs. Publication reads that retained evidence; it does not copy or finalize rule data here as a workflow mutation. Game Session remains the runtime orchestrator, not an alternate rule-authoring or source-retention authority. Runtime reads only the published, pinned release for the active `runtime_version`.

Role classification: Game Logic is a digest-gate participant for full publishes, not a workflow-step participant, unless future publish workflows add explicit finalize or compensation steps owned by this service.

## Draft Digest Contract

For full-version publish gating, this service is still a required digest participant even though it does not orchestrate publish-workflow steps. Its authenticated `GetDraftDesignDigest` read binds the publication request and exact retained selection described below, rather than selecting content by tenant and version alone, and publishes a service-local digest input manifest with:

- included objects and source ownership for the complete effective, version-scoped set of supported command inputs that affect runtime command behavior;
- excluded objects such as runtime queues, caches, telemetry tables, and other non-launchability data;
- canonicalization rules covering stable ordering, normalization, and null or default handling before hashing; and
- `digestSchemaVersion` bump criteria, where any include, exclude, or canonicalization change requires an explicit schema bump and replay or re-record workflow.

Publish gating must fail closed if this service cannot attest a digest under its documented manifest for the reported `digestSchemaVersion`.

### Selected-Release Rule Inputs and Intake

Game Design remains the authoring and revision-history owner for authored gameplay rule inputs. Game Logic owns validation and immutable retention of the selected release's rule-input evidence, and attests only the exact selected tenant, version, and applied commit received through an authenticated, operation-bound source operation. A workload identity by itself does not authorize that mutation; the current authority and the source operation's mutation authorization must remain bound through commit. The producer handoff and retained manifest are target behavior and are not established by the current digest receiver.

The retained input set must be complete for the supported command inputs effective in that version. It includes supported ability and action definitions and the referenced actor-state, resource, condition, and effect catalogs; command and action metadata including admission tags; disposition and continuous-overlay policy; observation, targeting, target-selection, and default target-set bindings; and cost, cooldown, feedback, or lifecycle declarations when they are part of the selected authoritative source. The authoring shapes and ownership remain defined by [Ability & Action Design Tools](../game-design-service/ability-action-tools.md) and the owning domain contracts. This list defines the completeness boundary, not a new wire schema or an additional execution engine. Unknown or unsupported authored inputs make intake and attestation fail closed; they must not be filtered out to produce an apparently empty or partial manifest.

Intake is a separate, mutation-authorized source operation. `GetDraftDesignDigest` remains a read-only full-version publication read over the already retained, exact-version evidence; it does not populate or repair that evidence. An empty selected input set is attestable only when an authenticated source operation proves the complete owner inventory for the exact selected commit is empty. Missing storage, a missing producer, or absent rows are not evidence of an empty authored set.

The content-selection binding is distinct from the unchanged `publicationDigestRequest/v1` authorization/replay context. Its `game-logic-publication-source-read/v1` preimage consists of exactly three unsigned canonical ASCII byte-length frames, in order: the UTF-8 schema name, the exact existing publication request canonical preimage, and the complete original finalized Account Game Logic intake authorization bytes retained by Game Design's immutable selected receipt. Lengths use canonical decimal digits followed by `:` and exactly that many bytes; missing, extra, trailing or noncanonical frames deny. Segment and total limits, including framing and transport overhead, must be enforced before allocation. Hash the exact bytes using the existing SHA-256 representation and freeze executable preimage/hash vectors. This binding selects content; it creates no read or mutation authority and does not reinterpret the shared publication schema.

Game Design derives that binding only from its immutable selected receipt, never from caller-selected historical authorization or latest state. Game Logic authenticates the exact Game Design workload and publication method before decoding, independently validates both bindings, then reads the exact genuine retained intake operation and its successful terminal. The complete authorization, canonical tenant/version, selected commit and source must agree byte-for-byte with retained owner evidence. Authorization bytes and digest alone prove neither Account issuance nor successful retention. The numeric Game Design Version row in the unchanged publication scope must agree with the original selected binding's persisted target; retain its canonical UUID and source-row provenance rather than inventing a UUID/numeric alias.

The response binds the complete original lookup, source and commit provenance, positive participant schema/canonicalization evidence, aggregate digest and dedicated ability-schema digest. Game Design exact-checks each before recording an attestation. Missing or mismatched retained evidence denies; there is no tenant/version-only or latest fallback, read-side repair, fresh mutation reauthorization of historical intake, or reuse of the Account-only terminal-read permission for Game Design. Game Logic is not a script-patch participant. This selection plumbing does not establish complete source/settlement or final release-attestation proof; current construction and proof limits remain in the [authoring tracker](../../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md#current-status).

The original Game Design author operation settles before this intake. Game Logic obtains the complete synchronized source from Game Design's authenticated exact-commit owner read and verifies a distinct [Account intake authorization](../account-service/api-contracts.md#grpc-apis) for that same immutable source. That exact finalized authorization also accompanies its [operation-authorized source read](../game-design-service/api-contracts.md#operation-authorized-gameplay-source-read); Game Logic cannot reuse Account's preliminary read-only branch or omit independent Account readback. It does not retrospectively join the original Draft's participant vector or allocate another selected commit. Account source participation remains held through Game Logic's local commit and exact terminal settlement; a remote `HELD` sample alone is insufficient. Retained source, complete manifest/provenance and the immutable intake result commit atomically. A definitive abort uses the same operation serialization and prevents later retention; missing rows or expired waits do not prove abort. Identical recovery returns the original result, never a latest source or replacement manifest. These obligations do not make Game Logic a publication-workflow mutation participant.

The standalone [`RetainGameplayRuleIntake`](../../../../protos/game-logic/v1/gameplay_rule_intake_service.proto) transport admits only verified same-namespace Game Design, authenticated before decoding. It invokes the existing distinct-order intake, not publication authorization. Exact namespace, independent transport correlation and complete original authorization bytes/digest are echoed with the immutable original terminal; both retained and definitive-abort retries preserve their original outcome. The client verifies Game Logic's workload and exact response binding. Game Design obtains the subsequent settlement receipt through the [Account-owned settlement transport](../account-service/api-contracts.md#grpc-apis), never by calling the Account-only terminal reader. These transports remain unregistered; their existence does not establish a fresh authorization producer or publication completion.

The authenticated publication read must preserve the exact source-operation and selected-commit correlation through its retained-manifest lookup and readback. Validating the publication request and then resolving content by tenant/version alone is insufficient when it can select a different source operation; neither a latest lookup nor request authentication repairs missing immutable provenance.

The full-version manifest and the dedicated `abilitySchemaDigest` share the selected commit and Game Logic's existing canonicalization and `digestSchemaVersion` contract, but they attest distinct scopes. The aggregate full-manifest `contentDigest` is not an ability-schema digest, and neither the Automation & Scripting digest nor Game Design's control-plane digest substitutes for either Game Logic attestation. Any change to the dedicated ability-schema input set or its canonicalization requires the same explicit schema bump and digest replay or re-record process as other manifest changes. This contract governs publication evidence only; it does not authorize activation of unpublished rules or make Game Logic a participant in Game Design's publication workflow mutations. Game Logic does not read an admin database for runtime rules.

### Dedicated Ability-Schema Projection v1

The dedicated content preimage is RFC 8785 canonical UTF-8 JSON with exactly `{"schema":"gameplay-ability-schema/v1","families":{...}}`. `families` contains all 15 names in the closed `gameplay-rule-manifest/v1` grammar, including explicit empty arrays. Validate the complete selected manifest before projecting: unknown fields or definitions, missing families, unresolved references and unsupported shapes fail even outside the selected closure. Seed every `ABILITIES` and every `ACTIONS` definition, then retain the complete typed-reference closure keyed by `(family, logical key)`. Preserve every field of each retained definition, its original selected-manifest family order, and every nested-array order. A visited set terminates reachable cycles while retaining all reachable definitions; this does not establish execution support for an otherwise unsupported cycle.

The closed grammar's reference edges are exhaustive:

| Definition family | Typed reference edges |
| --- | --- |
| `ABILITIES` | `actionSequenceId` → `ACTIONS`. |
| `ACTIONS` | `admissionTags` → `ADMISSION_TAGS`; target sets → targeting/selection policies and optional unresolved feedback; costs → `RESOURCES`; effect bindings → `EFFECTS`; `feedbackKeys` → `FEEDBACK`. |
| `CONDITIONS` | `effectKeys` → `EFFECTS`. |
| `EFFECTS` | `statKey` → declared numeric `STATS` or `RESOURCES`; `ADJUST_RESOURCE` and `MAXIMUM` require `RESOURCES`; `conditionKey` → `CONDITIONS`. |
| `OBSERVATION_POLICIES` | Predicate references below. |
| `TARGETING_POLICIES` | `observationPolicyKey` → `OBSERVATION_POLICIES`; eligibility predicate references; `safeFailureFeedbackKey` → `FEEDBACK`. |
| `SELECTION_POLICIES` | Comparator `statKey` → numeric `STATS` or `RESOURCES`; `MAXIMUM` requires `RESOURCES`. |
| `DISPOSITIONS` | `deniedAdmissionTags` → `ADMISSION_TAGS`; `safeFeedbackKey` → `FEEDBACK`. |
| `COMMANDS` | `actionSequenceId` → `ACTIONS`; `admissionTags` → `ADMISSION_TAGS`; commands are not roots or reverse dependencies. |
| `CONTINUOUS_OVERLAYS` | Eligibility predicate references; `deniedAdmissionTags` → `ADMISSION_TAGS`; `effectKeys` → `EFFECTS`. |
| `DEFAULT_BINDINGS` | Target sets → targeting/selection policies and optional unresolved feedback. |
| `STATS`, `RESOURCES`, `ADMISSION_TAGS`, `FEEDBACK` | No external definition references. Their complete fields, tags and feedback arguments remain retained when reached. |

Target-set policy fields are `targetingPolicyKey` → `TARGETING_POLICIES`, `targetSelectionPolicyKey` → `SELECTION_POLICIES`, and non-null `unresolvedFeedbackKey` → `FEEDBACK`. Predicate operands are traversed recursively: `STAT_COMPARE.factKey` selects its declared numeric stat/resource; `CONDITION_PRESENT.factKey` → `CONDITIONS`; `DISPOSITION_EQUALS.factKey` → `DISPOSITIONS`. `SOURCE` and declared target-set keys are action-local, not catalog aliases. Cooldown keys, names, tags, player-selector slots and feedback argument/message identifiers create no guessed external edges. The closed grammar rejects stat/resource key collisions before resolving a numeric reference. Unreferenced catalogs, commands, overlays and default bindings stay outside this dedicated digest, while remaining in the aggregate manifest. An effective implicit runtime dependency not expressible by this grammar must be reported and denied, not silently omitted or guessed from names.

Hash these exact bytes with the existing lowercase `sha256:` encoding. Carry the positive participant `digestSchemaVersion`, canonicalization evidence and exact selected-commit/source-operation provenance separately; source/auth receipts, publication metadata and commit IDs never enter this content-only preimage. A valid explicit-empty manifest has a defined content hash but cannot establish authenticated empty-source provenance: the original complete owner inventory and immutable intake must independently prove it. The executable [projection vectors](../../../../services/common-platform-core/src/test/java/net/firedevops/firemud/common/gamelogic/GameplayAbilitySchemaProjectionTest.java) freeze empty preimage/hash `sha256:0207783645e944996b4f7589d2662d5b8309231769681e162ee05de51d34db2a` and the complete two-action/all-root preimage/hash `sha256:659a321a8fd1a02a4825a81568d67dee5039a1cb538756fb91c212aeaea1c536`, plus transitive-field, ordering, unrelated-input and negative cases. Any include/exclude, edge or canonicalization change requires the existing explicit schema bump and replay/re-record process; do not reinterpret retained evidence. Projection construction alone does not register the digest receiver, authorize publication or activate gameplay.

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
