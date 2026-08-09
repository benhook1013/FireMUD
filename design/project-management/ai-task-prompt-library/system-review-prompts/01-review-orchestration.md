# Review Orchestration

Use this guide for the deliberate whole-system review after the main target-state design is complete. It coordinates a bounded review pass; it does not establish a recurring post-change process.

Normal pull-request review owns change-level design, implementation, and proof review. Do not run this library after every pull request and do not add a step that decides which prompt each change should trigger.

Apply the [shared review contract](./00-shared-review-contract.md) throughout.

## Whole-System Pass

Run the pass in this order:

1. Run [authority and decision closure](./10-authority-and-decision-closure.md).
2. Run the [capability, journey, status, and evidence census](./11-capability-journey-status-and-evidence-census.md).
3. Run [cross-service invariant and workflow closure](./12-cross-service-invariant-and-workflow-closure.md).
4. Run the [service boundary review](./20-service-boundary-review.md) once for each deployed service in the current microservice index. Review shared modules only through the contracts and services that consume them; do not treat them as deployed services.
5. Run the focused system reviews numbered `21` through `27` against the full declared design boundary.
6. Reconcile overlapping findings and issue one combined completion assessment.

Related scopes may run in parallel after authority and capability ownership are established. Parallel work does not remove the need for final cross-scope reconciliation.

## Review Packet

The invocation records:

- repository revision and review date;
- included product and architecture scope;
- service set from the current microservice index;
- capability and journey sets from the current product indexes;
- expected review prompts;
- explicit exclusions; and
- any permissions beyond the shared read-only default, including the exact write scope, command allowlist, execution environment, and required validation.

Keep the coverage table and working findings in the review response or another explicitly authorized ephemeral surface. Do not create a permanent review database.

## Synthesis

Merge wording variants only when they describe the same canonical owner, capability or workflow, finding class, and failure mode. Preserve distinct impacts or evidence gaps as linked findings rather than hiding them in one broad item.

When scopes disagree:

- product documents own the intended outcome;
- architecture owns the target technical contract;
- implementation tracking owns status only;
- code and tests provide implementation and proof evidence; and
- the human resolves competing target states or consequential risk decisions.

The final synthesis contains:

1. blocking human decisions;
2. target-design conflicts or missing contracts;
3. implementation and proof drift;
4. security, operations, and user-experience gaps;
5. excluded or unavailable evidence; and
6. the aggregate state: `complete`, `incomplete`, or `blocked`.

The aggregate must cover every declared capability, journey, service, invariant, workflow, route, persistence boundary, environment, and evidence item, or explicitly exclude it with an acceptable rationale. It cannot be `complete` when a required prompt is incomplete or blocked, a declared item is missing from coverage, a target-only behavior is presented as shipped, or an unresolved finding lacks an owner or requested human decision.

## After The Review

The human decides which findings to accept and how to prioritize them. Put accepted outcomes into the existing authoritative surface rather than retaining the review packet as a second task system.

Rerun the full pass only after substantial design revision or when the human explicitly requests another comprehensive audit. Focused reviews may be commissioned independently, but they are not automatically triggered by ordinary changes.
