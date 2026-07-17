# Design Decision and Implementation Alignment

This workstream establishes a complete, reviewable chain from FireMUD product capabilities through canonical design decisions to implementation tracking and proof. It does not make project-management documents an alternative source of product or architecture truth. Canonical target-state design remains under [`design/architecture`](../../architecture/README.md).

## Status

Current phase: capability allocation, implementation/proof reconciliation, cross-domain convergence, structural enforcement, and independent validation are complete. Human-led adversarial decision review remains deliberately unstarted.

| Phase | Status | Output |
| --- | --- | --- |
| Product capability taxonomy | Complete | [`product-capability-taxonomy.md`](../../architecture/product-capability-taxonomy.md) |
| Canonical design allocation | Complete and independently coverage-audited | [`design-capability-allocation.md`](./design-capability-allocation.md) |
| Consequential-decision inventory | Complete and independently coverage/fidelity-audited | [`consequential-decision-inventory.md`](./consequential-decision-inventory.md) |
| Implementation tracker reshaping | Complete | [Capability allocation](../implementation-tracking/capability-allocation.md) and ten permanent domain trackers |
| Code and proof reconciliation | Complete and independently validated | Per-capability implementation/verification states and evidence anchors in the domain trackers |
| Cross-domain convergence | Complete and independently validated | [Capability implementation reconciliation](./capability-implementation-reconciliation.md) |
| Human-led adversarial decision review | Not started; explicitly outside automated completion | Human decisions and later canonical design/ADR updates |

## Authority Boundaries

- The product capability taxonomy defines stable navigation and ownership categories, not runtime behavior.
- Canonical architecture documents define target-state behavior.
- Architecture decision records explain consequential accepted choices and their tradeoffs; they do not replace the current design contract.
- This directory contains allocation, inventory, review, and status artifacts. It is deliberately non-normative.
- Implementation trackers report implementation and proof against accepted design. They do not define design.
- Automated inventory work may identify evidence, conflicts, alternatives, and review questions, but it must not conduct or resolve the adversarial decision review. The human decision owner runs that later process manually against the completed register and canonical sources.

## Coverage Rules

- Every canonical design document and every separately normative mixed-document section must have exactly one primary capability allocation.
- Cross-domain effects are recorded as secondary handoffs rather than duplicate primary ownership.
- Every consequential explicit or implicit decision must map to at least one capability and its canonical source.
- An inventory entry is not an accepted decision merely because current design or code implies it.
- Human product or architecture decisions remain unresolved until explicitly discussed and accepted.
- Routine local implementation choices do not require ADRs. ADR candidates are cross-cutting, expensive to reverse, authority-setting, security-sensitive, or supported by credible competing target states.

## Work Sequence

1. Finalize the complete product capability taxonomy.
2. Allocate every canonical design source to a primary capability and explicit secondary handoffs.
3. Inventory consequential explicit and implicit decisions against that allocation.
4. Resolve only direct canonical contradictions that block trustworthy implementation classification.
5. Allocate every leaf capability to one primary implementation tracker and explicit secondary handoffs.
6. Reconcile code, contracts, schemas, configuration, tests, and operational proof against every capability, then validate cross-domain authority and gaps.
7. The human decision owner manually runs adversarial review against the completed register and chooses whether to accept, revise, defer, withdraw, or supersede each consequential decision.
8. Apply human-accepted decisions to canonical design and create, amend, or supersede ADRs where warranted.

## Automated Gates

The design-allocation and decision-inventory phases were completed against these gates:

- all intended FireMUD user, creator, operator, runtime, authoring, automation, platform, and commercial concerns have a capability home;
- every Markdown source under `design/architecture/**` is present in the allocation ledger or explicitly classified as non-allocatable generated/index material;
- mixed canonical documents have heading-level allocations where file-level allocation would hide a real ownership split;
- every capability has been inspected for consequential explicit and implicit decisions;
- existing ADRs are mapped, including superseded and withdrawn records;
- conflicts, unsupported assumptions, missing rationale, and human-review questions are visible rather than silently normalized; and
- an independent exhaustive review finds no unallocated canonical design or unexplained decision-bearing claim.

The capability implementation reconciliation additionally requires:

- every taxonomy leaf appears exactly once in the primary-tracker allocation and exactly once in a tracker status table;
- implementation and verification are represented independently with approved states;
- each capability names canonical design, production evidence, focused proof, handoffs, and its remaining gap or decision;
- cross-domain ownership and terminology are consistent with canonical design rather than inferred from implementation convenience;
- direct canonical contradictions are resolved before status is claimed; and
- the structural contract and independent evidence-quality review both pass before this automated phase is called complete.
