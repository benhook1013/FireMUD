# Orchestrated Review Workstream Contract

Apply this contract when a broad review prompt links to it. It supplements the [shared review contract](./00-shared-review-contract.md) with execution rules for work that is too large to treat as one undivided review assignment.

This contract does not apply to the shared contract itself, the whole-system orchestration guide, the per-service review template, the release and traffic gates, or the engineering-maintenance prompts unless one of those prompts is explicitly changed to adopt it.

## Invocation Mode

The caller declares one of these modes:

- `full` covers the prompt's complete declared boundary; or
- `focused` names the exact capabilities, journeys, services, workflows, contracts, or evidence items included and records every material exclusion.

A full invocation is an orchestrated review workstream. The invoking main thread takes primary ownership, decomposes the boundary into bounded evidence lanes, delegates those lanes under the repository's [AI delegation and review workflow](../../../developer-workflows/ai-delegation-and-review.md), maintains the combined coverage ledger, reconciles the results, and makes the final findings and completion decision. Do not hand the complete prompt, synthesis, or completion decision to one delegated worker.

A focused invocation may remain in the primary thread only when its declared boundary is small enough to review directly without sampling. Record that choice and why further decomposition would not improve coverage. A focused label does not excuse implicit exclusions or sampled evidence.

## Primary Orchestrator Responsibilities

Before delegation, the primary orchestrator:

1. records the repository revision, review date, invocation mode, included boundary, exclusions, and permissions;
2. expands the prompt's suggested partition into concrete lanes whose owned review items do not overlap;
3. assigns every declared review item to exactly one primary lane while identifying intentional cross-lane evidence;
4. creates the response-local coverage ledger required by the shared contract; and
5. identifies authority or scope questions that require resolution before evidence collection can proceed.

The primary orchestrator retains responsibility for authority interpretation, product or architecture ambiguity, finding adjudication, duplicate detection, cross-lane contradictions, risk decisions, and the aggregate review state. Use bounded waves that fit the available worker capacity. Establish ownership and allocation before running lanes that depend on them.

When this workstream runs inside the whole-system review, the whole-system primary remains the primary orchestrator. Apply this contract directly rather than creating a nested orchestrator for each prompt. Delegated workers do not create further workers.

## Evidence-Lane Assignments

Each delegated lane must receive:

- the exact lane boundary and owned coverage items;
- the shared review contract, this contract, the selected review prompt, and the authoritative starting sources;
- required negative and failure paths;
- explicit exclusions, permissions, and validation or command allowlists; and
- the handoff structure below.

The repository delegation workflow still governs worktree verification, model selection, file safety, validation allowlists, and review, CI, and pull-request prohibitions. Delegation does not expand the invoking review's permissions.

Workers gather and test evidence. They do not choose between competing product outcomes or target architectures, accept security or operational risk, broaden their lane, edit authoritative sources without explicit permission, or declare the workstream complete.

Each worker returns:

1. its exact lane boundary and sources inspected;
2. one coverage result for every assigned item, including negative or failure paths checked;
3. finding candidates with supporting references and the shared contract's required fields;
4. exclusions, unavailable evidence, blockers, and reasoning-sensitive assumptions; and
5. its lane state as `covered`, `incomplete`, or `blocked`, without claiming the aggregate review state.

## Integration And Completion

The primary orchestrator inspects every handoff against its assignment, follows up missing coverage, and reconciles evidence that crosses lanes. Merge findings only when their canonical owner, affected capability or workflow, finding class, and failure mode match. Preserve distinct impacts or conflicting evidence for explicit adjudication.

The workstream may end as `complete` only when every declared item has an integrated coverage row and every exclusion has an acceptable rationale. A worker's `covered` lane, a collection of summaries, or broad source inspection is not sufficient. End with the single aggregate review state required by the shared contract.
