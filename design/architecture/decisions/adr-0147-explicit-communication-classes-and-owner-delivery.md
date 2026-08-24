# ADR 0147: Explicit Communication Classes and Owner Delivery

## Status

Accepted

## Implementation Status

This decision is partially implemented. The bounded authenticated `SAY`, `WHISPER`, and `TELL` gameplay-connected slice is live, but explicit communication-class contracts, complete Game Logic orchestration, owner-local delivery, and bounded end-to-end proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-GR-COMMUNICATION-ORCHESTRATION`
- Decision date: 2026-07-20
- Decision key: `MS-GR-COMMUNICATION-ORCHESTRATION`
- Primary capability: `EA-2.1`
- Affected capabilities: `EA-2.2`, `EA-2.3`, `GR-4.1`, `PO-1.2`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of gameplay semantics, private communication boundaries, moderation and history authority, transport delivery, tenant-script visibility, and communication availability coupling

## Context

The existing communication contract sends in-game `SAY`, `WHISPER`, and `TELL` through Game Logic before Social & Groups. That path is appropriate when world topology, gameplay perception, character abilities, scripted effects, interception, or observation determine what the communication means and who can perceive it.

The documentation had generalized that path to all communication, including guild channels, account-directed messages, and mail. That makes a running game world and Game Logic availability prerequisites for communication whose authority and semantics are social rather than spatial. It also risks exposing private platform communication to tenant-authored gameplay scripts merely because the sender used an in-game command adapter.

The former wording also described Social & Groups as the real-time fanout owner even though Game Session owns connected gameplay transports and final player delivery. Communication needs explicit classes and owner handoffs rather than one universal ingress path or one service claiming end-to-end delivery.

## Decision

### World and Gameplay Communication

World or gameplay communication enters Game Logic. This class includes `SAY`, nearby `WHISPER`, world-topology-aware `SHOUT`, gameplay `TELL`, and game-defined communication whose outcome may depend on gameplay state.

Game Logic resolves the gameplay meaning against authoritative context, including topology, perception, abilities, effects, target rules, and permitted tenant-authored interception. It emits one bounded resolved communication plan with stable identity, communication type, authorized audience or target facts, per-recipient semantic views where required, and the owner handoffs needed to complete the action. One plan may contain at most 100 recipient-view entries, counting the actor view and every ordinary-recipient or observer view. A published type or operator policy may choose a lower cap but cannot raise this platform ceiling. If complete authoritative resolution would exceed the effective cap, Game Logic rejects the whole action as `AUDIENCE_LIMIT_EXCEEDED` before Social persistence or Game Session delivery; no service may truncate, partially commit, or split it into independently meaningful plans. A future chunked or opaque-reference representation requires a separately versioned contract with bounded chunks and explicit completion/failure semantics before it can replace this fail-closed baseline. Game Logic does not open or own player transports.

Social & Groups applies its authoritative responsibilities to that plan, including social membership or audience rules where relevant, communication moderation, history, and any durable social delivery records. Game Session owns final delivery to connected gameplay transports and renders or projects the authorized semantic result for each client.

An existing online `TELL` may remain a gameplay communication when its contract intentionally permits character abilities or game rules, but ordinary gameplay `TELL` is non-observable by default. Any interceptable directed-world message must be a distinct published communication type and version governed by [ADR 0150](./adr-0150-closed-observer-views-and-profile-scoped-shout.md)'s closed observer-view, explicit-mechanic, and proof requirements. Account-to-account direct messaging and mail are social communication even when an in-game command adapts to their Social & Groups APIs.

### Social-Channel Communication

Account messaging, ordinary guild or group channels, mail, browser social interactions, and other non-gameplay social communication enter Social & Groups directly after authentication and the applicable membership, privacy, and moderation checks. They do not require Game Logic or a running game world.

An in-game command may act as an authenticated adapter to a Social & Groups operation. Using that adapter does not turn the operation into a Game Logic action, grant gameplay scripts access to its content, or change its social ownership.

Tenant-authored DSL cannot reclassify private platform communication as gameplay communication or otherwise make private message or mail content available for script inspection. A future product feature that deliberately exposes a new gameplay communication type must define its privacy, observability, and script-access contract explicitly rather than inheriting access from a generic communication envelope.

### Operator and System Communication

Operator and platform-system communication enters through the service that owns the originating operation and its authorization or audit contract. The owner supplies a typed semantic communication request to the relevant audience, history, and transport owners. It does not masquerade as player speech or pass through Game Logic unless it deliberately creates a gameplay-world effect.

### Delivery Ownership

No service owns every stage of communication. Game Logic owns gameplay-semantic resolution, Social & Groups owns social authority, moderation, relevant history, and social-channel delivery state, and Game Session owns connected gameplay transports. Cross-pod notification or routing is an internal delivery mechanism under those ownership boundaries, not an authority transfer.

Accepted durable communication must have stable identity and the bounded recipient or audience representation above. Social & Groups and Game Session validate that the plan remains within the same effective cap and fail without persistence or partial delivery when it does not; neither downstream owner recomputes or truncates the authorized audience. Retries across Game Logic, Social & Groups, and Game Session must not duplicate durable history or semantic effects. A service must not claim successful end-user delivery merely because an upstream plan or history write succeeded; transport delivery and any stronger acknowledgement contract are reported separately.

## Consequences

- Gameplay speech retains topology, perception, abilities, interception, and game-defined observer mechanics.
- Private account communication and ordinary social channels remain available without a running game world or healthy Game Logic path.
- Tenant-authored code cannot inspect private platform messages by reclassifying them as gameplay actions.
- An in-game UI or command can still provide one coherent user experience by adapting to the correct owner API.
- Ownership is more explicit, but callers must classify communication before choosing an ingress contract and must preserve stable identity across owner handoffs.
- Social & Groups cannot equate a history or moderation commit with delivery to a connected player; Game Session remains the gameplay transport owner.
- Gameplay `TELL` and account direct messaging may appear similar to players while intentionally carrying different semantic, privacy, and availability guarantees.

## Alternatives Considered

### Route Every Communication Through Game Logic

Use one configurable communication-intent pipeline for speech, guild chat, account messages, and mail. This offers one extension surface and lets game mechanics inspect every action. Rejected because it couples social communication to game runtime availability, gives gameplay code an unsafe path toward private platform content, and applies world semantics where social membership and privacy are the actual authorities.

### Route Every Communication Through Social & Groups

Let Social & Groups parse all communication intent and derive gameplay audience and observation behavior. This makes chat infrastructure uniform but either strips out topology, abilities, perception, and scripted interception or requires Social & Groups to duplicate Game Logic and World authority. Rejected because gameplay semantics belong in the gameplay orchestration path.

### Add a Global Communication Coordinator

Create a separate service that classifies every communication, calls gameplay and social owners, persists plans, and orchestrates delivery. This could make the split explicit but introduces another hot-path dependency and a broad cross-domain coordinator before independent scaling or security evidence justifies it. Explicit ingress classes and typed owner handoffs provide the required boundary without another service.

### Let Social & Groups Own Client Fanout

Have Social & Groups connect directly to or route independently among all player transports. Rejected because Game Session already owns authenticated gameplay connections, presentation, and client delivery. A second gameplay transport authority would duplicate connection state and complicate ordering, reconnect, and authorization.

## Implementation and Proof Obligations

The current implementation proves only the bounded authenticated `SAY`, `WHISPER`, and `TELL` gameplay-connected slice: those commands reach Game Logic, which forwards a normalized gameplay request projection to a Social & Groups test stub and returns recipient metadata to Game Session. Focused cross-service WebSocket proof covers the canonical actor and live-recipient views for those commands. It does not prove the complete target contract.

Complete durable resolved-plan persistence, idempotent recovery across every owner handoff, and production recipient delivery across Game Session pods remain implementation and proof gaps. The current Social & Groups stub and actor transcript tests do not prove real cross-pod listener delivery, durable social history convergence, recipient-specific projection, or failure recovery after an ambiguous handoff.

Proof must cover each communication class independently. Gameplay tests must exercise topology, perception, interception, bounded audiences, moderation, history, recipient-specific views, duplicate and ambiguous retry, and delivery through a different Game Session pod. Social-channel tests must prove direct Social & Groups operation without Game Logic, membership and privacy enforcement, moderation, durable history where promised, and equivalent behavior when invoked through an in-game adapter. Privacy tests must prove tenant DSL cannot inspect or reclassify account messages, mail, or ordinary social-channel content. Operator/system tests must prove originating-owner authorization and audit plus typed delivery without impersonating player speech.

Failure proof must distinguish rejected semantic resolution, rejected moderation or membership, committed history without live delivery, transport unavailability, and any acknowledged delivery state. Bounds must cover oversized audience expansion, backpressure, partial pod availability, and retry after service restart without duplicate history or semantic effects.

Select validation and runtime evidence according to [`validation and runtime proof`](../../developer-workflows/validation-and-runtime-proof.md); record actual execution results in PR/CI evidence or implementation-tracking documents, not in this ADR.

## Reversibility and Revisit Triggers

Communication type schemas, audience-plan representation, rendering metadata, moderation implementation, and cross-pod routing may evolve while preserving explicit ingress classes and owner authority. Revisit the boundary if a feature genuinely requires private platform communication to participate in gameplay semantics; that change requires a deliberate privacy and tenant-script-access decision. Consider a separate communication coordinator only if measured scale, ordering, or independent deployment needs cannot be met through bounded owner handoffs.

## Required Documentation Alignment

- [`design/architecture/system-architecture-overview.md#communication-flows`](../system-architecture-overview.md#communication-flows)
- [`design/architecture/microservices/game-logic-service/api-contracts.md`](../microservices/game-logic-service/api-contracts.md)
- [`design/architecture/microservices/social-groups-service/README.md`](../microservices/social-groups-service/README.md)
- [`design/architecture/microservices/social-groups-service/api-contracts.md`](../microservices/social-groups-service/api-contracts.md)
- [`design/architecture/microservices/game-session-service/protocols.md#communication-request-flow`](../microservices/game-session-service/protocols.md#communication-request-flow)
- [`design/architecture/microservices/game-session-service/runtime-and-data.md#reconnection-and-disconnect-handling`](../microservices/game-session-service/runtime-and-data.md#reconnection-and-disconnect-handling)
- [`design/architecture/system-architecture-reconnection.md#client-reconnection-behaviour`](../system-architecture-reconnection.md#client-reconnection-behaviour)
