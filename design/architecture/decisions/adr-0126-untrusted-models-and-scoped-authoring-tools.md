# ADR 0126: Untrusted Models and Scoped Authoring Tools

## Status

Accepted

## Implementation Status

No LLM provider integration, model-backed generation endpoint, first-party tool broker, agent proposal store, agent-specific credential, or focused LLM proof exists in the current repository. The tracked `AR-1.2` implementation is partial because procedural generation and typed World mutations exist; those are supporting authoring primitives, not proof of this decision.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `LLM-01`
- Decision date: 2026-07-20
- Decision key: `LLM-01`
- Primary capability: `AR-1.2` procedural, LLM-assisted, and external authoring tools
- Affected capabilities: `AR-1.5`, `AS-1.2`, `EA-3.2`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led review of external AI clients, first-party conversational authoring, public and internal API boundaries, scoped tools, Draft isolation, approval, compound operations, provenance, prompt injection, provider independence, and sensitive-action authority

## Context

FireMUD intends to support AI-assisted authoring in two forms. An external AI or other tool may use explicitly supported creator APIs as an ordinary client. A later first-party conversational agent may iteratively inspect and modify a game through a platform-selected model, harness, system prompt, and tool set.

Neither form should turn model output into authority. Treating every frontend endpoint as a stable public automation contract would freeze private UI implementation details. Conversely, giving a first-party model broad internal credentials or direct storage access would let prompt injection, model error, or an overly coarse tool bypass tenant scope, owner validation, Draft concurrency, human review, or the separate authority required for sensitive and operator actions.

The useful boundary is therefore not where the model process runs. It is which explicitly classified tools and APIs the authenticated client may invoke, which authoritative owner validates each effect, and when a proposed change becomes shared Draft state or a Published release.

## Decision

### External AI and Tool Clients

An external AI or tool is an ordinary client of explicitly public creator APIs. It receives no AI-specific trust, route, authorization bypass, or direct storage access. Authentication, tenant and resource authorization, validation, optimistic concurrency, idempotency, audit, rate limits, and canonical errors are the same as for another client using that public contract.

Only APIs explicitly designated and governed as public creator APIs are supported for this use. An endpoint used by the first-party web frontend is not thereby a stable public API, and private frontend or internal service contracts may evolve without external compatibility promises. A FireMUD skill or instruction package may teach tools how to use supported APIs, but it grants no authority and is not an enforcement boundary.

This decision does not grant a general AI permission over all non-gameplay control-plane operations. Account security, identity, real-money, billing, deletion, global administration, operator, moderation, remediation, publication, activation, and other separately protected actions retain their owning authorization, step-up, confirmation, audit, and idempotency contracts. Any later supported unattended delegation for those actions requires the applicable authority decision; it is not inherited from creator API access.

### First-Party Conversational Authoring

The first-party conversational authoring capability places an untrusted model behind a trusted scoped tool broker. The broker authenticates its workload, preserves the initiating human, tenant, game, version, Draft, and proposal scope, and exposes only allowlisted authoring tools. The model receives neither user credentials nor service secrets and cannot call databases, object storage, arbitrary internal APIs, or runtime mutation paths directly.

The model may iteratively read authorized design context and build an isolated, reviewable Draft proposal without requiring a human prompt for every reversible tool call. Its proposed writes do not silently alter a shared Draft or Published content. The creator reviews the resulting structured diff and explicitly accepts it into the Draft. Publication or activation remains a separate ordinary human-authorized action and is never implied by accepting the proposal.

Tenant-authored text, imported context, tool results, and model output remain untrusted data. The tool broker, owning services, and validation pipeline enforce tenant isolation, data minimization, typed schemas, resource and cost bounds, and denial of secret, private-player, account, billing, or unrelated operator context.

### Tool and Mutation Semantics

First-party internal tools may provide coarser reads or compound authoring operations than public single-action creator APIs. Coarser shape does not grant broader semantic authority: every authoritative read or mutation remains bounded by the initiating actor and proposal scope, and every effect flows through typed owning-service contracts with owner-side authorization and validation. Compound tools do not write authoritative databases or object storage directly.

A mutation or compound application carries a stable request identity bound to its proposed input, the complete affected `(owner, aggregateId, scopeId, epoch)` set, and sufficient digest identity to detect changed-request reuse. Preview and acceptance refer to the same proposal identity and evaluate freshness over that complete set: a synchronized Draft change confined to disjoint scopes does not invalidate the proposal, while a change overlapping an affected scope or a required owner-derived containing scope advances the relevant fence and requires a fresh proposal and review. The immutable base commit remains provenance for the reviewed diff; no stale proposal is silently merged or applied.

Each compound tool declares its atomicity and failure contract. Prefer staging the complete proposal and using one fenced apply/finalize boundary. When one all-or-nothing owner transaction is impossible, the broker exposes durable per-step outcomes and explicit retry, repair, or abort semantics; it must not report an ambiguous partial result as success.

Revision and audit provenance binds the initiating human, agent or client session, proposal and request identities, tool calls and outcomes, produced revisions, and accepting human. First-party execution also records the model, harness, tool-set, and applicable prompt-template versions or digests needed to explain the run without claiming deterministic regeneration. The accepted Draft content and revision history remain authoritative, not a future attempt to reproduce model output.

### Deferred Mechanics and Availability

The exact provider, model, harness, system prompt, tool catalog and schemas, broker process topology, proposal storage representation, delegated-credential wire format, quota values, review interface, retrieval mechanism, and provider retention configuration remain deferred implementation and product choices. They must conform to this authority boundary when selected.

LLM availability is never a prerequisite for ordinary authoring, validation, publication, activation, or gameplay. Provider failure, quota exhaustion, or disabling the feature affects only the optional assisted-authoring operation.

## Consequences

- Creators can use external AI and automation through stable creator APIs without creating a second authoring authority or whole-game file format.
- FireMUD may build a richer first-party conversational workflow without giving the model ambient platform authority.
- Isolated proposals and explicit human acceptance add storage, diff, concurrency, provenance, and review work but avoid per-tool approval prompts during normal iteration.
- Internal compound tools can reduce round trips and provide coherent authoring operations, but require explicit idempotency, atomicity, failure, and owner-validation contracts.
- Sensitive and operator actions do not become reachable merely because an authoring agent exists.
- Model and provider choices can evolve while accepted content remains ordinary FireMUD revisions and Published versions.

## Alternatives Considered

### Public Creator APIs and Instructions Only

Provide typed public APIs plus a FireMUD skill or instruction package and make every agent, including the initial first-party chat experience, an ordinary API client. This is the strongest simpler alternative and remains a valid implementation starting point. It may require excessive client-side orchestration or round trips for coherent multi-object changes, so this decision permits later scoped compound tools without making them mandatory.

### Give the Model Direct Draft or Storage Authority

Let a model hold broad credentials, call internal APIs freely, or write Game Design/domain storage directly. Rejected because process isolation and prompting cannot replace tenant authorization, schema validation, owner invariants, concurrency control, audit, and human acceptance.

### Require Human Approval for Every Tool Call

Prompt before every read and Draft mutation. Rejected as the general authoring interaction because it prevents useful iterative work while adding little assurance for reversible activity inside an isolated proposal. Approval occurs on the reviewable proposal; separately protected actions retain their own confirmation rules.

### Let Proposal Acceptance Publish or Activate Automatically

Treat the generated diff as deployable once accepted. Rejected because Draft acceptance, publication validation, release attestation, and runtime activation are separate authority and lifecycle transitions.

### Make One Provider or Model Part of the Domain Contract

Fix one model, harness, or system prompt as permanent architecture. Rejected because these are replaceable implementation and product choices. Their versions remain execution provenance when used, not stable game-content authority.

## Implementation and Proof Reality

Implementation proof must cover public/private API classification; ordinary-client authorization and tenant isolation for external tools; broker enforcement when model instructions are hostile; absence of model access to secrets and raw storage; cross-tenant and private-data denial; proposal isolation; stable request and digest reuse; stale Draft epochs; preview-to-accept binding; compound success, rejection, partial failure, retry, and recovery; explicit human acceptance; separate publish and activation; provenance; provider outage and quota exhaustion; and ordinary authoring/runtime operation with the LLM capability disabled.

## Reversibility and Revisit Triggers

Providers, models, prompts, broker implementation, proposal storage, tool granularity, and user experience may change while retaining untrusted models, scoped tools, owner-side validation, reviewable proposals, and separate publication. Revisit this boundary before granting a first-party agent publish/activation authority, sensitive account or financial capabilities, operator actions, access to private player/runtime data, arbitrary network or code execution, or any in-game conversational role.

## Required Documentation Alignment

- [LLM-Assisted Content Authoring](../system-architecture-llm-content-tools.md)
- [Game Design Service](../microservices/game-design-service/README.md)
