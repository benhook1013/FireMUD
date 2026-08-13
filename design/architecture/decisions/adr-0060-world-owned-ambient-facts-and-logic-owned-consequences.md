# ADR 0060: World-Owned Ambient Facts and Logic-Owned Consequences

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `MS-GR-AMBIENT-STATE-AUTHORITY`
- Primary capability: `GR-2.3` authoritative spatial and ambient state
- Affected capabilities: `GR-2.2`, `GR-4.1`, `GR-1.4`, `SF-2.3`, `PO-1.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with independent authority validation and Game Logic, Automation, and separate-environment-service alternative analysis

## Context

Doors, weather, hazards, and similar ambient state are persistent facts about a live world instance, but their gameplay meaning belongs to rules. Treating “hazard is active” and “this hazard damages an actor” as one ownership question would either put rules in World Management or duplicate room truth in Game Logic or Automation.

Runtime changes can originate from player actions, scripts, automation, or operators. All must survive retry and failover without granting those callers direct authority over World tables.

## Decision

World Management exclusively owns durable runtime ambient facts and their authoritative versions. Examples include whether a door is open, whether a hazard is present or active, and current typed weather state. Game Design owns authored/template defaults; publishing or replacing authored content does not make a script or template row the authority for a running instance.

Game Logic owns interpretation and gameplay consequences. It decides what an active hazard does, how weather changes visibility, or whether a door state permits an action. It does not persist a competing authoritative copy of the ambient fact.

Runtime ambient mutations enter as typed effect intent:

- Game Session durably admits the logical effect, assigns or preserves its deterministic root identity, records its participant and outcome, dispatches it, and owns retry or terminalization.
- Automation and scripts submit through Game Session and preserve their originating deterministic identity.
- External operator changes enter through Logging and Admin's durable audited workflow and then the live gameplay effect-admission path; they never write World instance tables directly.
- World validates exact instance scope, epoch and relevant current version, applies the typed patch and effect-visible rows atomically with an operation/aggregate/request-digest-bound idempotency result, advances the authoritative World version, and returns that durable result on replay.

Player-significant or correctness-bearing runtime ambient changes use the durable effect path even when World is the only mutation participant. A feature may declare a cosmetic or advisory update lossy only under the class-specific rules of ADR 0058.

Region-wide ambient state has one declared World-owned runtime aggregate and may produce room projections. Those projections are not independent authorities.

Weather is one World-owned region-scoped aggregate. Room weather is derived only through World Management's authoritative room-to-region membership; a room projection or cache is never an independent weather authority.

### Supplemental clarification (2026-08-13)

The region weather aggregate is the sole runtime weather fact. Room-facing weather is a World-derived projection based on current authoritative membership and must not be independently patched, persisted as competing truth, or used to route effects.

## Consequences

- World remains the cohesive source of live room/environment facts without absorbing gameplay rules.
- Game Logic can evolve hazard and weather behavior without migrating ambient persistence.
- Scripts and operator tools remain replaceable intent producers rather than state owners.
- Single-participant ambient effects still incur durable admission and outcome tracking when correctness-bearing; this is accepted for crash recovery and truthful player/operator outcomes.
- The ambient patch contract and Game Session execution path require implementation and focused failure proof.

## Alternatives Considered

### Game Logic Owns Gameplay-Relevant Ambient State

Rejected because persistence authority would change according to whether a fact currently affects gameplay, splitting doors, weather, hazards, and presentation across ambiguous owners.

### Automation or Scripts Own Ambient State

Rejected because authoring/execution technology would become runtime truth and script replay or replacement would risk duplicated or lost mutations.

### Separate Environment Service

Deferred unless an independently scalable environmental aggregate becomes concrete. Introducing it now would divide room truth and add another read and reconciliation participant.

### Direct World Writes from Operators or Scripts

Rejected because they bypass durable admission, gameplay fencing, idempotent effect identity, and centralized outcome reconciliation.

## Implementation and Proof Obligations

Specify the typed ambient patch request/result, root and participant identities, immutable digest, exact preconditions, replay result, World version advancement, and authoritative acknowledgement. Prove player, script, automation, and operator admission; duplicate/conflicting delivery; stale epoch/version rejection; crash before and after World commit; retry and terminal outcome; no direct table-write bypass; region-to-room projection authority; and consequence evaluation from current World facts.

Current durable command execution does not claim ambient effects are migrated, so implementation and proof remain open.

## Reversibility and Revisit Triggers

Typed fact schemas and consequence rules can evolve independently. Revisit ownership only if environmental simulation becomes a demonstrably independent aggregate requiring its own scaling and lifecycle boundary.
