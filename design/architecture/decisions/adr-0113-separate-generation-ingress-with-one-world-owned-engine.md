# ADR 0113: Separate Generation Ingress with One World-Owned Engine

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PROC-01`
- Primary capability: `AR-1.1` world, entity, rule, and content authoring
- Affected capabilities: `AR-1.5`, `AR-3.2`, `GR-2.1`, `AS-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of design-time and runtime generation, service authority, typed ingress, persistence targets, and Automation boundaries

## Context

FireMUD needs procedural generation both as a creator operation that changes a Draft and as a runtime operation that creates instance topology. The two uses need different authorization, identifiers, persistence targets, and lifecycle rules, but duplicating the generator implementation would let their algorithms and validation drift.

A caller-supplied `generationMode` enum is not an adequate authority boundary. A generic endpoint could be asked to combine a Draft target with runtime semantics, or an instance target with design semantics, and every implementation would have to prove that it never trusts the contradictory mode. The authenticated ingress and typed target should make invalid combinations unrepresentable.

The current code also contradicts the intended owner: generator implementations and their registry are located in Automation & Scripting, while World Management has the Draft topology mutation path but no design-time or runtime generation API that invokes a generator.

## Decision

World Management owns one pure procedural-generation engine. Generator implementations take immutable admitted inputs and return an abstract topology result without persisting data or invoking external services. Both design-time and runtime generation use this same engine and the same output validation rules.

The two uses have separate typed ingress and target contracts:

- A design-generation endpoint accepts a typed Draft target such as `(tenantId, versionId, DraftScopeTarget)`. Game Design is the sole design orchestration authority: it authenticates and records the creator's generation intent as a Draft revision, obtains any required preview or approval, and invokes World Management's design ingress. World Management validates the request and output and persists only World-owned Draft topology under the revision, scope-epoch, and publication contracts.
- A runtime-generation endpoint accepts a typed instance target such as `(tenantId, gameInstanceId, InstanceScopeTarget)`. It is invoked only through the authorized world-lifecycle or gameplay command path. World Management validates and persists only instance topology under that instance's lifecycle and idempotency contract.

Namespace and behavior are derived from the authenticated endpoint and the target union. A free caller-supplied `generationMode` is not an authority selector and must not be accepted as a way to reinterpret a target.

Published template topology is immutable. Design generation may target only Draft versions, while runtime generation may never write template rows.

Automation & Scripting may populate already-persisted topology through the canonical declarative binding or runtime command paths. It must not own generator implementations, generate topology for World Management to persist, or persist topology itself.

## Consequences

- One engine prevents design-time and runtime algorithms from drifting while separate ingress prevents their authority and persistence semantics from being confused.
- Game Design remains the sole owner of Draft revision intent and history without becoming the topology schema or persistence owner.
- World Management has one validation and persistence boundary for all generated topology.
- API and test work is required for both typed ingress paths even though they share an internal engine.
- Automation remains useful for post-generation population but is removed from topology authority.

## Alternatives Considered

### One Generic Endpoint with a Caller-Supplied Mode

Rejected because the mode, target namespace, authenticated caller, and persistence destination can contradict one another. Repeated conditional validation is weaker and harder to audit than separate typed contracts.

### Separate Design and Runtime Generator Implementations

Rejected because fixes, algorithms, validation, and provenance behavior would drift. The ingress contracts need separation; the pure engine does not.

### Automation-Owned Generation

Rejected because procedural topology is a World-owned graph. Letting Automation generate topology for another service to accept would split semantic validation from the code that creates the result and make Automation a competing world-design authority.

### Game Design-Owned Generation and Persistence

Rejected because Game Design owns Draft orchestration and history, not World topology schema or persistence. It should carry typed intent to the domain owner rather than duplicate the graph.

## Implementation and Proof Obligations

Move or replace the current Automation-owned generator implementations and registry with a World-owned pure engine. Add separate typed design-Draft and runtime-instance generation APIs whose target unions cannot express the other namespace. Authorization proof must show that only Game Design can use the design ingress and only approved lifecycle or gameplay paths can use the runtime ingress.

Proof must show that Draft generation rejects Published versions, runtime ingress cannot write template rows, design ingress cannot write instance rows, conflicting or incomplete targets fail before generation, identical admitted inputs use the same engine behavior, and World validation completes before atomic persistence. Automation proof must show that it can populate persisted topology through the approved paths but cannot generate or persist topology.

The current implementation does not satisfy this contract. Generator code and its registry exist in Automation & Scripting, World Management accepts a generated Draft mutation payload, and there is no implemented World-owned design-generation or runtime-generation API that invokes the engine.

## Reversibility and Revisit Triggers

Endpoint shapes and generator plugin discovery may evolve while preserving one World-owned pure engine and separate typed authority boundaries. A future remote compute worker may execute the pure algorithm for capacity reasons, but World Management must still admit inputs, validate output, and own persistence. Moving topology authority out of World Management or combining the two ingress contracts requires a new decision.

## Required Documentation Alignment

- `design/architecture/system-architecture-procedural-generation.md`
- `design/architecture/microservices/world-management-service/procedural-generation-control.md`
- `design/architecture/microservices/game-design-service/world-editing-tools.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
