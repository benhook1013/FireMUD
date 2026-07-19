# ADR 0067: Abandon Old-Epoch Work and Reschedule with New Lineage

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `TICK-14`
- Primary capability: `GR-1.4` tick failure recovery and epoch reset
- Affected capabilities: `SF-2.3`, `GR-2.1`, `AS-1.4`, `PO-4.4`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of old-epoch terminalization, reconstruction lineage, and reset recovery alternatives

## Context

A region epoch change invalidates the ownership, location, version, and topology assumptions under which earlier tick effects and cross-region follow-ups were admitted. Rewriting an old durable row onto the new epoch would preserve its identifier while changing its meaning and preconditions. Leaving old work pending indefinitely would avoid that false identity but prevent bounded convergence and make reopening a region depend on ambiguous backlog state.

Redis hints cannot enumerate the authoritative backlog because they are disposable coordination projections. Recovery must derive its scope from durable old-epoch work.

## Decision

Every old-epoch `SCHEDULED` effect or follow-up reaches terminal `ABANDONED` with an explicit reset or topology reason. Its identity, original epoch, request, and terminal outcome remain immutable. An old row is never rewritten, rebound, or adopted into a new epoch, and it never remains pending indefinitely.

Before the affected scope reopens, recovery completely enumerates durable old-epoch effects and follow-ups and terminalizes each non-terminal row. Redis hints may accelerate discovery but are irrelevant to the completeness of that enumeration.

If abandoned work remains semantically required, an explicitly authorized feature or maintenance flow creates a new request with a new identity on the current epoch. Before creating it, that flow revalidates current scope, ownership, location, aggregate versions, and the governing feature policy. The new request records lineage to the abandoned identity, and audit evidence preserves both the old terminal outcome and the new request.

Correctness-bearing intent may be reconstructed only through this explicit new request and revalidation path. It is not silently carried across the epoch boundary.

## Consequences

- Epoch identity remains truthful: one effect or follow-up never changes the ownership generation under which it was admitted.
- Reset recovery has a bounded terminalization condition before the region or affected scope reopens.
- Semantically required work may survive a reset only through visible, authorized reconstruction with fresh validation and lineage.
- Operators and support tooling can distinguish abandoned historical work from newly requested work.
- Reconstruction adds durable rows, audit records, and feature-specific policy work instead of reusing the old request.
- Work that is no longer valid is abandoned rather than executed against changed topology or ownership.

## Alternatives Considered

### Silently Rebind Old Work to the New Epoch

Rejected because the same identity would acquire different scope, ownership, location, version, or topology assumptions. Replay and audit could no longer distinguish the originally admitted operation from a newly authorized request.

### Leave Old-Epoch Work Pending Indefinitely

Rejected because it prevents terminal convergence, obscures whether the work may still execute, complicates retention, and can block safe reopening. Durable enumeration and explicit `ABANDONED` outcomes provide a bounded result.

## Implementation and Proof Obligations

Proof must cover complete durable enumeration of old-epoch `SCHEDULED` effects and follow-ups before reopen; terminal `ABANDONED` outcomes with precise reset/topology reasons; immutability of old identities, epochs, requests, and terminal outcomes; rejection of rebinding or rewriting; independence from missing or stale Redis hints; authorized reconstruction with a new current-epoch identity; lineage linking old and new requests; revalidation of scope, ownership, location, versions, and feature policy; audit visibility for both identities; idempotent recovery reruns; and no indefinitely pending old-epoch rows.

The current implementation and runtime proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Reason codes and reconstruction policies may evolve without changing the identity rule. Revisit only if FireMUD adopts a durable cross-epoch workflow whose identity explicitly spans ownership generations and whose migration, replay, authorization, and audit semantics are proven. Until then, old-epoch terminalization and new-identity reconstruction remain mandatory.
