# ADR 0090: Recorded Script Input Manifests for Reproducible Evaluation

## Status

Accepted

## Implementation Status

Recorded input manifests are target state. Current input-manifest persistence, owner-versioned bounded-read contracts, canonical seed-version capture, historical-input failure handling, retention, and focused replay proof are not claimed as complete. See the [automation and scheduler runtime tracker](../../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

## Canonical Design

- [Scripting DSL reference and lifecycle: Determinism and Allowed Non-Determinism](../system-architecture-scripting-dsl-reference-and-lifecycle.md#determinism--allowed-non-determinism)
- [Scripting DSL reference and lifecycle: Read Consistency Contract](../system-architecture-scripting-dsl-reference-and-lifecycle.md#read-consistency-contract)
- [Scripting normative contract tables: Trigger Identity](../system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `SCRIPT-12`
- Primary capability: `AS-1.1` deterministic scripting language and evaluation
- Affected capabilities: `SF-2.3`, `GR-1.2`, `SF-1.2`, `AR-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of recorded input manifests, trigger-envelope-only evaluation, and universal historical snapshot alternatives
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `SCRIPT-12`

## Context

A script run may combine trigger facts, published script and component artifacts, pseudo-random choices, and authoritative reads from several owners. Re-fetching any of those inputs during retry can produce different commands even when the Trigger Identity is unchanged. A universal cross-service historical snapshot would avoid mixed freshness, but it would require every owner to retain and serve one shared snapshot model whether or not its data participates in the run.

Script evaluation produces typed runtime commands; it does not own the atomic mutation of the aggregates those commands target. Reproducible evaluation therefore needs a durable record of what the script observed without pretending that observations across independent owners form one atomic transaction.

## Decision

Each admitted handler-scoped script run records one durable input manifest for its Trigger Identity. Admission captures the immutable trigger facts, exact script and component artifact versions, the source causal floor, and the canonical seed-derivation version used for the run.

Gameplay-affecting authoritative reads return both the owning service's version identity and a bounded result. Before any evaluated output is accepted, every gameplay-affecting input used by the evaluation is durably captured in the manifest with its owner version. The manifest is the replayable evaluation input; it is not a pointer that permits a later retry to substitute a fresher answer.

An evaluation retry reuses the same captured manifest and deterministic seed inputs. It never fetches newer gameplay-affecting state for the same Trigger Identity. If a required historical input was not captured, cannot be retrieved under its recorded owner version, or cannot be proven to match the manifest, evaluation reaches an explicit failure outcome rather than falling back to the latest value.

Pseudo-random behavior uses the canonical versioned seed derivation over the recorded Trigger Identity and applicable tick context. Scripts cannot observe wall-clock time. Tick replay and tick recovery replay the already emitted typed commands idempotently and never re-execute the DSL for the same trigger.

The recorded causal floor and owner-versioned results provide reproducible inputs, not cross-owner atomicity. Any command whose correctness depends on an aggregate invariant is submitted as a typed runtime command to the owning runtime or domain authority. That owner applies its transaction, exact preconditions, fencing, and idempotency contract when executing the command. Cross-owner atomic invariants remain with the applicable typed-command owner or durable workflow and are not inferred from the script input manifest.

## Consequences

- Evaluation retries cannot silently observe newer gameplay state or artifact definitions for the same Trigger Identity.
- Operators and offline tools can identify the trigger facts, artifact versions, owner-versioned results, causal floor, and seed contract that produced an output.
- Missing historical input becomes an explicit failure instead of non-deterministic latest-state evaluation.
- Input manifests add durable storage, bounded result capture, owner-version retention, and cleanup requirements.
- Large or unbounded reads cannot be hidden behind the manifest contract; script-facing authoritative reads must return bounded results.
- The manifest does not make independently owned data atomic, so typed runtime commands may still fail exact execution-time preconditions after deterministic evaluation.
- Tick recovery remains independent of DSL availability because it replays accepted command identities rather than script graphs.

## Alternatives Considered

### Trigger Envelope Only

Rejected as the universal model because many useful scripts require authoritative facts that the trigger producer does not own or cannot include without creating large, duplicated payload contracts. Trigger-envelope facts remain part of the manifest, but they are not assumed to contain every gameplay-affecting input.

### Universal Historical Snapshot

Rejected because requiring every service to implement and retain one cross-service historical snapshot token would impose a shared storage and retention model while still not creating a cross-owner transaction. Owner-versioned bounded results captured into one durable manifest provide reproducibility without claiming universal snapshot atomicity.

## Implementation and Proof Obligations

Proof must cover one durable manifest per handler-scoped Trigger Identity; immutable trigger facts; exact script and component artifact versions; causal-floor capture; canonical seed-version capture; deterministic RNG; absence of wall-clock input; and bounded authoritative read results paired with owner versions.

Retry proof must cover crashes before, during, and after input capture; duplicate admission; reuse of captured inputs; refusal to fetch newer state; unavailable or mismatched historical owner versions; explicit missing-input failure; no output acceptance before every gameplay-affecting input is durable; and offline reproduction from the recorded manifest.

Runtime proof must show that tick replay does not re-enter the DSL, accepted typed commands retain stable identities, and owning runtime or domain services enforce transactions, fences, exact preconditions, and idempotency without treating the manifest as cross-owner atomic authority.

The current input-manifest persistence, owner-versioned bounded-read contracts, canonical seed-version contract, historical-input failure path, retention behavior, and focused replay proof are not claimed by this decision.

## Reversibility and Revisit Triggers

Manifest encoding, bounded-result ceilings, and retention may be calibrated without changing recorded-input authority. Revisit the model only if measured manifest cost is material or a concrete script family cannot be expressed through bounded owner-versioned inputs and typed runtime commands. Any replacement must retain deterministic seed versioning, explicit missing-input failure, no newer reads on retry, and no DSL re-execution during tick replay.
