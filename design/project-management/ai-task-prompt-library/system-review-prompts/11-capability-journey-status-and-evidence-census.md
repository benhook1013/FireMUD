# Capability, Journey, Status, And Evidence Census

Use this prompt for the comprehensive post-design census of intended product coverage, current implementation status, and focused proof. It may be rerun later only when the human deliberately commissions another complete census.

Apply the [shared review contract](./00-shared-review-contract.md).

## Starting Sources

- `design/product/requirements.md`
- `design/product/capability-taxonomy.md`
- `design/product/user-journeys/overview.md`
- `design/product/user-journeys/players.md`
- `design/product/user-journeys/creators.md`
- `design/product/user-journeys/operators.md`
- `design/project-management/design-alignment/design-capability-allocation.md`
- `design/project-management/design-alignment/capability-implementation-reconciliation.md`
- `design/project-management/implementation-tracking/README.md`
- `design/project-management/implementation-tracking/capability-allocation.md`
- every implementation tracker listed by that index

Inspect the canonical design, production anchors, and focused proof named by each capability row. A link or tracker claim is not proof by itself.

## Review

For every current leaf capability in the product taxonomy, check:

- the intended product outcome and affected personas;
- allocation to canonical product and architecture sources;
- exactly one primary implementation tracker and the necessary secondary handoffs;
- consistency between the tracker status and the current production boundary;
- whether `implemented`, `partial`, `not-implemented`, `design-unresolved`, or `not-applicable` is supported;
- whether `proven`, `audited`, `unverified`, `drift-found`, or `not-applicable` is supported by the named evidence;
- positive, negative, cross-service, and operational proof required by the claimed boundary; and
- whether relevant player, creator, or operator journeys have an explicit home.

Keep missing design, missing implementation, and missing proof as separate findings. Do not downgrade a product capability merely because it is not yet implemented, and do not promote target behavior based on a tracker or test.

An accurately recorded `partial` or `not-implemented` capability is coverage information, not a newly discovered defect. Open a finding only when the design or allocation is incomplete, the recorded state is misleading, the named anchors do not support it, a required handoff is absent, or the accepted scope needs an explicit human decision.

## Output

Provide:

1. one coverage row per current leaf capability;
2. journey steps without a capability or canonical design home;
3. missing or conflicting tracker allocations and handoffs;
4. implementation-status drift;
5. unsupported or missing proof claims; and
6. the review state required by the shared contract.

The result is incomplete if any leaf capability, persona journey, primary tracker, production anchor, or claimed focused proof was sampled rather than checked.
