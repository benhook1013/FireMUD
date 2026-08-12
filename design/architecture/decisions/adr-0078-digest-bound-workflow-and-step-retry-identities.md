# ADR 0078: Digest-Bound Workflow and Step Retry Identities

## Status

Accepted

## Implementation Status

Digest-bound workflow and step identity is target state; durable guard storage, conflict enforcement, adopter migration, and focused retry/replay proof remain incomplete.

## Canonical Design

- [Transaction Strategies](../system-architecture-transactions.md)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `ID-03`
- Primary capability: `SF-2.3` durable cross-service effects, retries, and workflow coordination
- Affected capabilities: `SF-2.4`, `SF-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review with caller-issued idempotency-token and execution-identity alternative analysis
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `ID-03`

## Context

Workflow work may be retried after caller retry, worker restart, redelivery, Temporal run replacement, or an ambiguous acknowledgement. Process-local, delivery-local, and attempt-local identifiers change across those events and therefore cannot identify the one logical workflow or step whose effects must be deduplicated.

A stable step name is also insufficient by itself. Loops, branches, and repeated invocations can execute the same named step more than once, while forward execution and compensation must never collide. Stable identity must remain bound to immutable request intent so accidental key reuse cannot turn different work into a false replay.

## Decision

Every retryable workflow has one durable workflow/request identity derived from its stable business identity and workflow scope. That identity remains unchanged across caller retries, worker restarts, redelivery, and workflow run replacement.

Every logical workflow step has a durable retry identity containing:

- the durable workflow/request identity;
- a stable step name;
- a deterministic occurrence key that distinguishes repeated or branched occurrences of that step;
- the execution role, distinguishing forward work from compensation; and
- an immutable request digest covering the request represented by that step identity.

The workflow/request identity, step name, occurrence key, and role form the stable step guard identity. The immutable request digest is stored and compared as part of that identity record. Reuse of the same guard identity with the same digest is the same logical step and must deduplicate or replay its recorded outcome. Reuse of the same guard identity with a different digest is an identity conflict and must fail closed rather than execute or return a prior outcome as though the requests matched.

Occurrence keys are stable semantic keys for the repeated or branched position. They are not loop-worker IDs, thread IDs, attempt numbers, delivery sequence numbers, or another execution-local value. Compensation uses the same workflow/request scope and applicable occurrence key as the work it compensates, but its compensation role keeps its identity distinct from the forward step.

Workflow run IDs, process or JVM execution IDs, retry-attempt IDs, and message or delivery IDs are trace and diagnostic metadata only. They may be recorded alongside the durable identity but are never the sole or authoritative deduplication key.

### Current Automation Adopter Mapping

The Temporal mapping for `script-patch-readiness` is `workflowFamily=script-patch-readiness`, `tenantId=<tenantId>`, `scopeKey=script-patch-version`, and `businessKey=<scriptPatchVersion>`, yielding `workflowId=script-patch-readiness:<tenantId>:script-patch-version:<scriptPatchVersion>`. Each readiness `onLoad` step uses the stable `onLoad` step name and the `FORWARD` execution role. Its deterministic occurrence key is the canonical ordered serialization of the complete applicable handler-scoped tenant-readiness Trigger Identity: `<tenantId, scriptId, eventType=onLoad, eventSchemaVersion, scriptPatchVersion, scriptEventId, isDryRun=false, pluginId?, pluginVersionId?, bindingId?>`. The optional plugin fields are included exactly when applicable and remain absent otherwise; `scriptId` and, for a plugin handler, `bindingId` identify the resolved handler occurrence, so no attempt, delivery, worker, or runtime ordinal participates.

The immutable request digest covers those admitted identity fields plus immutable handler descriptor content/version. Runtime-only game, playable-state, region, epoch, and entity fields are absent. Adopter proof must show that the same complete guard and digest replays the recorded readiness step across Temporal run replacement without repeating its logical effect, while the same guard with any different digest fails closed without execution or false replay.

## Consequences

- Caller retry, worker restart, redelivery, and workflow run replacement converge on the same logical workflow and step identities.
- Repeated steps, branches, forward execution, and compensation cannot collide merely because they share a step name.
- Digest comparison detects accidental or malicious reuse of a stable identity for different request intent.
- Trace identifiers remain useful for diagnosing individual executions without becoming gameplay or workflow authority.
- Workflow adopters must define stable business scope, occurrence keys, roles, and canonical request-digest inputs.
- Durable identity records, atomic conflict checks, and retained outcomes add schema, storage, and integration work.

## Alternatives Considered

### Caller-Issued Idempotency Token

A caller-issued opaque token is simple, works across process boundaries, and can represent the business request when the caller persists and reuses it correctly. Rejected as the complete identity contract because it delegates semantic correctness to every caller, does not by itself distinguish repeated or branched step occurrences from forward and compensation roles, and cannot detect reuse for changed request content without the immutable digest binding. A stable caller token may contribute the business request identity, but it does not replace the canonical workflow and step identity record.

### Run, Process, Attempt, or Message Identity

Rejected because these values identify one execution or delivery rather than one logical workflow step. A retry or replacement would mint a new value and could apply the same logical effect again.

## Implementation and Proof Obligations

Implement canonical construction and durable storage for workflow/request identity, stable step name, deterministic occurrence key, forward or compensation role, immutable request digest, and recorded step outcome. Admission must atomically distinguish a new step, same-identity same-digest replay, and same-identity different-digest conflict.

Prove stable identity across caller retry, restart, redelivery, and Temporal run replacement; repeated and branched occurrences with the same step name; distinct forward and compensation identities; concurrent duplicate admission; crash before and after step-effect commit and outcome recording; same-digest replay without duplicate logical effects; different-digest conflict without execution or false replay; and exclusion of run, process, attempt, and message identifiers from authoritative deduplication.

The current `common-saga` implementation lacks the promised durable step guard: its runner creates new saga and step rows, and its schema has no stable guard identity, request digest, or uniqueness enforcement. The current Temporal helper constructs canonical workflow and business-step keys, but adopter-side durable step enforcement is incomplete and some uses provide diagnostic logging rather than a durable idempotency guard. The current implementation and focused retry, conflict, crash, and replay proof are not claimed by this decision.

## Reversibility and Revisit Triggers

The encoded key format, digest algorithm, storage layout, and retention policy may evolve without changing the stable semantic identity fields, immutable digest binding, conflict behavior, or trace-only status of execution identifiers. Revisit the semantic tuple only if a concrete workflow cannot express repeated or branched occurrences and compensation with deterministic occurrence and role keys, or if a stronger durable workflow substrate supplies equivalent identity, digest-conflict, and replay guarantees.
