# LLM-Assisted Content Authoring

This document defines the target boundary for AI-assisted game authoring. FireMUD supports ordinary external AI/tool clients through explicitly public creator APIs and may later provide a first-party conversational authoring agent. In both cases the model is an untrusted authoring participant, not a source of authority.

See [ADR 0126](./decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md) for the accepted decision and the [Game Design Service](./microservices/game-design-service/README.md) for the canonical authoring boundary.

## Implementation Status

The current implementation does not provide the target LLM authoring boundary: no LLM integration, scoped tool broker, agent proposal store, or focused LLM proof is implemented. Ordinary authoring remains available through typed service APIs; model assistance is optional target-state behavior and is not required for authoring, publication, activation, or gameplay.

## Capability Forms

### External AI and Tool Clients

An external AI or tool may perform any design-time operation that an explicitly public creator API authorizes for its authenticated caller. It receives no special route, trust, or bypass. The API and its owning service enforce the same tenant scope, validation, optimistic concurrency, idempotency, audit, rate limits, and errors used for other public clients.

Frontend usage alone does not make an endpoint a stable public API. Only a creator API deliberately classified, documented, versioned, and exposed for external use carries that compatibility commitment. FireMUD may publish a skill or instruction package to help tools use those APIs, but instructions do not grant authority or weaken runtime enforcement.

The external creator surface does not absorb unrelated control-plane authority. Account security, identity, real-money, billing, deletion, operator, moderation, remediation, publication, activation, and other sensitive actions keep their existing authorization, recent-authentication, confirmation, audit, and execution contracts.

### First-Party Conversational Agent

A future first-party agent may help a creator inspect and iteratively modify rooms, NPCs, items, quests, scripts, rules, and related design data. An untrusted model operates behind a trusted scoped tool broker:

- the broker preserves the initiating human, tenant, game, version, Draft, and proposal scope;
- the model sees only allowlisted authoring tools and authorized, minimized results;
- credentials and service secrets remain in the broker and are never model context;
- authoritative effects flow through typed owning-service APIs with owner-side authorization and validation; and
- neither the model nor the broker writes authoritative databases, object storage, or runtime state directly.

Internal tools may combine several public-style actions or expose a coarser read optimized for agent use. They may be more convenient, but not more authoritative: they cannot expand the human's readable scope, bypass domain invariants, or turn the broker's workload identity into user authority.

## Proposal, Review, and Publication

The first-party agent works inside an isolated proposal. It may iterate through reversible reads and proposed Draft writes without asking for approval after every tool call. Those changes remain reviewable and cannot silently alter a shared Draft or Published content.

The creator reviews one structured diff and explicitly accepts the proposal into the Draft. Acceptance binds the exact proposal identity to ADR 0129's immutable base commit, canonical digest of the complete diff, and complete affected owner/aggregate/scope epoch set. A stale base is rejected for updated review rather than silently merged. Publication and runtime activation remain later, separate, ordinary human-authorized actions.

Each mutation or compound apply carries a stable request identity bound to that same ADR 0129 base, complete-diff digest, and complete affected owner/aggregate/scope epoch set. The concrete tool and API shape remains deferred until it preserves those bindings. Compound tools declare whether application is atomic. Prefer a staged proposal with one fenced finalize/apply boundary; a multi-owner operation that cannot be all-or-nothing must expose durable per-step outcomes and explicit retry, repair, or abort behavior rather than reporting ambiguous partial success.

## Security and Data Boundary

Tenant-authored content, retrieved context, tool output, and model output are untrusted data and may contain prompt-injection instructions. Enforcement remains outside the model and system prompt:

- tools are allowlisted, typed, scope-complete, and bounded;
- tenant isolation and owning-service authorization are rechecked on every authoritative operation;
- player-private, account, billing, security, secret, and unrelated operator data are excluded from authoring context;
- arbitrary database, object-store, filesystem, shell, internal-API, and network access is unavailable; and
- execution has bounded time, tokens, output, concurrency, temporary storage, and cost.

Provider retention, training use, data residency, tenant-content policy, and any future retrieval source must be approved before that provider or source is enabled. These choices do not move enforcement into provider prompts or policies.

## Provenance

Revision and audit records bind the initiating human, external client or first-party agent session, tenant/Draft scope, proposal and request identities, tool calls and outcomes, resulting revisions, and accepting human. First-party runs also retain the applicable model, harness, tool-set, and prompt-template versions or digests needed to explain the run without promising deterministic regeneration.

The accepted Draft content and normal revision history are authoritative. Raw sensitive prompts are not retained merely to make a nondeterministic model run appear reproducible.

## Deferred Implementation Choices

The exact provider, model, harness, system prompt, tool schemas and granularity, broker topology, proposal-store representation, delegated-credential shape, quota values, review UI, and retrieval approach remain deferred. Public creator APIs plus a FireMUD skill are a valid first implementation; internal compound tools should be introduced only where their measured coherence, latency, or atomicity benefit justifies the additional contract.

The implementation-status boundary above remains in force until those target choices are implemented and proved. LLM availability is never required for ordinary authoring, validation, publication, activation, or gameplay.

## Non-Goals

- No direct model access to authoritative storage or unrestricted internal APIs.
- No automatic publication or activation from accepting an agent proposal.
- No inheritance of account, financial, operator, or other sensitive authority from authoring access.
- No in-game NPC chatbot or live conversational gameplay agent under this decision.
- No whole-game filesystem, round-trip JSON, or external Git authoring format; see [ADR 0125](./decisions/adr-0125-defer-whole-game-portability-and-external-authoring-formats.md).

## Related Documentation

- [ADR 0126](./decisions/adr-0126-untrusted-models-and-scoped-authoring-tools.md)
- [Game Design Service](./microservices/game-design-service/README.md)
- [Authentication and Authorization](./system-architecture-authentication.md)
- [Authorization Route Matrix](./system-architecture-authz-route-matrix.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Scripting and Automation Framework](./system-architecture-scripting.md)
