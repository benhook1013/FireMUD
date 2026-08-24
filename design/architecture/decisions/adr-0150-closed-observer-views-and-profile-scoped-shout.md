# ADR 0150: Closed Observer Views and Profile-Scoped SHOUT

## Status

Accepted

## Implementation Status

This decision is not implemented. Closed observer-view classes, candidate-specific authorized views, profile-scoped SHOUT, and bounded fanout proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-SOCIAL-OBSERVER-SHOUT-POLICY`
- Decision date: 2026-07-20
- Decision key: `MS-SOCIAL-OBSERVER-SHOUT-POLICY`
- Primary capability: `EA-2.1`
- Affected capabilities: `EA-2.3`, `GR-4.1`, `SF-2.1`, `SF-2.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of communication privacy, gameplay observation, topology scope, authored mechanics, fanout bounds, and delivery ownership

## Context

World communication can participate in gameplay mechanics such as eavesdropping, magical listening, surveillance, perception, or effects that reveal only that speech occurred. Private platform communication must not acquire those observer paths merely because it is accessible from an in-game client. The communication model therefore needs to bound which messages are observable, who resolves that authority, and exactly what each observer may receive.

The earlier layered model allowed full content, partial content, or metadata according to a communication type, target scope, and recipient capability. Without a closed view vocabulary and explicit safe fields, that can grow into a free-form observation policy language and permit later services to infer or expand disclosure. Missing or stale topology resolution can also fail dangerously if a delivery service treats a partial audience as good enough.

`SHOUT` presents a related design trap. It is not universally an area, region, radius, or map broadcast. One game profile may define its standard `SHOUT` as area-wide, while another deliberately defines it as map-wide. Choosing one platform-global spatial meaning would embed an accidental game design into the communication system. The exact area-versus-region taxonomy need not be settled before a concrete profile requires it.

## Decision

### Gameplay Observation Applies Only to World Communication

Only world or gameplay communication is eligible for gameplay observation or interception. Account direct messages, mail, ordinary guild or group channels, browser social interactions, and other private platform communication have no gameplay observer path and cannot be reclassified by tenant-authored code to gain one.

Gameplay `TELL` has no observer path by default. A future distinct communication type may deliberately define an interceptable directed-world message, but ordinary gameplay `TELL` does not inherit eavesdropping merely because it travels through Game Logic.

### Communication Types Declare Closed Observer Views

Each published communication type version declares a small closed set of observer-view classes it permits. Initial platform classes may include:

- `NONE`;
- `METADATA_ONLY`;
- a named redacted or partial view whose exact semantics are defined by that communication type;
- and `FULL`.

The type explicitly lists the metadata fields safe for every permitted non-full class. Metadata safety is not inferred from field availability, and unknown fields are excluded. A partial-content class is added only when a concrete gameplay mechanic defines its content transformation, eligibility, presentation, and proof needs. FireMUD does not introduce a free-form observation-policy or presentation DSL.

The type declaration is a ceiling, not a grant. A capability or authored mechanic can select only a view class already permitted by the exact communication type version and can never promote a candidate beyond that declaration.

### Game Logic Resolves Candidate-Specific Authorized Views

Game Logic resolves authoritative world topology, communication scope, recipient location, capabilities, senses, effects, and explicit authored interception mechanics. It emits one bounded resolved plan containing the authorized view for each ordinary recipient and qualified observer candidate. The plan is bound to the communication type version and the freshness or fence evidence used for resolution. [ADR 0147](./adr-0147-explicit-communication-classes-and-owner-delivery.md#world-and-gameplay-communication) owns the shared maximum of 100 recipient-view entries, including the actor and every observer view, and the whole-action `AUDIENCE_LIMIT_EXCEEDED` outcome before any persistence or delivery. Observer expansion cannot obtain a separate allowance, and no over-limit path truncates or partially delivers the audience.

`WHISPER` interception requires an explicit published mechanic such as eavesdropping or magical listening. The current boolean eligibility and entity observer flag are only an initial implementation seam; they are not the final authoring, capability, freshness, or privacy contract.

Missing, stale, contradictory, or oversized resolution fails without over-delivery. A caller or downstream service cannot fill gaps, reuse an expired plan, convert an unknown view to full content, or silently retain an incomplete audience as if it were complete.

Game Session delivers the candidate-specific authorized views and does not recompute gameplay observation. Social & Groups applies social authorization, moderation, history, retention, and delivery-state constraints to the resolved plan but does not infer spatial observers or broaden their views. History and later retrieval remain bounded by each recipient's original authorized view under [ADR 0149](./adr-0149-communication-type-specific-history-and-retention.md).

### SHOUT Is a Profile-Defined Topology Contract

`SHOUT` remains unimplemented until a game or default profile defines a named, published, bounded topology scope for that communication type. `SHOUT` is not intrinsically area-wide, region-wide, radius-based, or map-wide:

- one profile may define its standard `SHOUT` as area-wide;
- another may define it as map-wide;
- and another game may omit `SHOUT` or provide differently named communication types.

The named scope resolves through authoritative World and Game Logic topology rather than a Social alias or flat distance assumption. The platform may settle or refine the distinction between area and region when concrete world profiles require it; implementation must not silently equate them beforehand.

Every scope is subject to operator fanout and resource caps. Large but permitted audiences use bounded or chunkable delivery with stable identity, backpressure, and explicit completion or partial-failure reporting. Exceeding a cap or losing required resolution produces a typed failure or explicit non-delivery outcome plus diagnostics and metrics. Audience members are never silently truncated.

## Consequences

- Private platform communication cannot leak through game-authored surveillance or observer mechanics.
- Communication authors choose from a small reviewable set of views rather than constructing an open-ended disclosure language.
- Rich eavesdropping remains possible, but partial content requires a concrete typed mechanic and contract.
- Game Logic bears the cost of bounded candidate-specific resolution; Social and Game Session consume rather than recreate that authority.
- A missing or stale topology read reduces availability for the affected communication instead of risking over-delivery.
- Default profiles may give the familiar word `SHOUT` materially different topology semantics, so clients and help text must derive meaning from the selected published profile.
- Map-wide or otherwise large broadcasts require capped, observable, backpressured delivery and may fail explicitly when their audience exceeds supported limits.

## Alternatives Considered

### One Free-Form Observer Policy DSL

A general policy language could express arbitrary metadata, transformations, senses, and observer rules. It would create another security-sensitive language, complicate static validation, make disclosure hard to audit, and overlap existing typed communication, topology, capability, and effect models. Closed view classes plus explicit authored mechanics cover current needs.

### Let Social Resolve Observers

Social already handles communication moderation, history, and some audiences, so it could infer observers during delivery. It does not own authoritative world topology, current gameplay effects, senses, or spatial capability state. Reconstructing them would duplicate Game Logic and World authority and permit stale or inconsistent disclosure.

### Metadata-Only Is Always Safe

Deliver any non-content fields when full content is forbidden. Speaker identity, target identity, location, timing, communication type, and even the fact that a whisper occurred can be sensitive. Each type must explicitly allow fields for each view class.

### Make Gameplay TELL Observable by Default

Treat every directed gameplay message as interceptable. This enables spy mechanics without another type but surprises players, expands the observer surface, and conflates direct communication with an explicitly authored world mechanic. The default remains no observer path.

### Give SHOUT One Platform-Global Scope

Define `SHOUT` as area-wide, radius-based, region-wide, or map-wide everywhere. This is simple to explain but embeds one game's topology vocabulary and balance choice into the platform. Published profiles choose a named bounded scope instead.

### Silently Truncate Large Audiences

Deliver to the first recipients up to an operator ceiling. This protects resources but creates arbitrary and invisible semantic failure, unfairness, and unreproducible moderation or gameplay outcomes. Over-limit delivery fails explicitly or uses a bounded chunked contract with observable completion.

## Implementation and Proof Obligations

The current implementation is partial. `SendCommunication` accepts `SAY`, `WHISPER`, and `TELL`, returns structured per-recipient view metadata, and has an initial boolean or entity-flag observer seam. Downstream Social delivery remains a stub. The implementation does not prove a versioned closed observer-view declaration, explicit safe metadata fields, authored `WHISPER` mechanics, gameplay-`TELL` non-observability, authoritative freshness fences, cross-pod candidate delivery, original-view history, or bounded chunked fanout.

`SHOUT` and area, region, map, or other topology propagation remain unimplemented. No existing verb default, room broadcast, numeric radius, or Redis list may be treated as the target `SHOUT` contract.

Proof must cover every allowed view class, rejection of undeclared and unknown fields, no-observer types, metadata-only and full observers, a concretely defined partial mechanic, capability loss, stale topology or effect evidence, missing resolution, duplicate plans, oversized candidates, retry, and cross-pod delivery without over-delivery. It must also prove that private social communication never enters gameplay observation and that default gameplay `TELL` has no observer candidates.

`WHISPER` proof must replace the initial boolean or flag with a published mechanic and test ordinary recipients, unauthorized bystanders, qualifying observers, stale capability state, and history retrieval. `SHOUT` proof must use at least two profile declarations with intentionally different scopes, including area-wide and map-wide examples; validate named topology resolution and operator caps; exercise chunking and backpressure; emit diagnostics and metrics; and prove that no over-limit path silently truncates the audience.

Select validation and runtime evidence according to [`validation and runtime proof`](../../developer-workflows/validation-and-runtime-proof.md); record actual execution results in PR/CI evidence or implementation-tracking documents, not in this ADR.

## Reversibility and Revisit Triggers

The closed platform view classes, safe metadata schemas, topology scope vocabulary, and delivery mechanism may evolve while preserving type-version ceilings, candidate-specific Game Logic authority, no private-platform observation, and no silent truncation. Add a partial view only with a concrete authored mechanic. Revisit area-versus-region taxonomy when real profiles require a stable distinction; do not force that taxonomy merely to implement a universal `SHOUT` default.

## Required Documentation Alignment

- [`design/architecture/microservices/game-logic-service/api-contracts.md`](../microservices/game-logic-service/api-contracts.md)
- [`design/architecture/microservices/social-groups-service/README.md`](../microservices/social-groups-service/README.md)
- [`design/architecture/microservices/social-groups-service/api-contracts.md`](../microservices/social-groups-service/api-contracts.md)
- [`design/architecture/microservices/game-session-service/protocols.md#communication-request-flow`](../microservices/game-session-service/protocols.md#communication-request-flow)
- [`design/architecture/microservices/game-session-service/runtime-and-data.md#reconnection-and-disconnect-handling`](../microservices/game-session-service/runtime-and-data.md#reconnection-and-disconnect-handling)
- [`design/architecture/system-architecture-reconnection.md#client-reconnection-behaviour`](../system-architecture-reconnection.md#client-reconnection-behaviour)
