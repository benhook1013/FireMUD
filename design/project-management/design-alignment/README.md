# Design Decision and Implementation Alignment

This workstream establishes a complete, reviewable chain from FireMUD product capabilities through canonical design decisions to implementation tracking and proof. It does not make project-management documents an alternative source of product or architecture truth. Canonical target-state design remains under [`design/architecture`](../../architecture/README.md).

## Status

Current phase: the first three automated phases are complete and ready for the human-led adversarial decision review.

| Phase | Status | Output |
| --- | --- | --- |
| Product capability taxonomy | Complete | [`product-capability-taxonomy.md`](../../architecture/product-capability-taxonomy.md) |
| Canonical design allocation | Complete and independently coverage-audited | [`design-capability-allocation.md`](./design-capability-allocation.md) |
| Consequential-decision inventory | Complete and independently coverage/fidelity-audited | [`consequential-decision-inventory.md`](./consequential-decision-inventory.md) |
| Human-led adversarial decision review | Not started; outside the automated completion of the first three phases | Accepted decisions and canonical design updates |
| Implementation tracker reshaping | Not started | Final domain overview and capability trackers |
| Code and proof reconciliation | Not started | Current implementation and verification status per capability |
| Cross-domain convergence | Not started | Reconciled authority, handoffs, terminology, and active gaps |

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
4. The human decision owner manually runs adversarial decision review against the completed register and resolves genuine product or architecture questions.
5. Update canonical design and create or supersede ADRs for accepted consequential decisions.
6. Reshape implementation tracking around the accepted capability taxonomy.
7. Reconcile code, contracts, schemas, configuration, tests, and operational proof against every designed capability.

## Completed Automated Gate

The first three phases were completed against these gates:

- all intended FireMUD user, creator, operator, runtime, authoring, automation, platform, and commercial concerns have a capability home;
- every Markdown source under `design/architecture/**` is present in the allocation ledger or explicitly classified as non-allocatable generated/index material;
- mixed canonical documents have heading-level allocations where file-level allocation would hide a real ownership split;
- every capability has been inspected for consequential explicit and implicit decisions;
- existing ADRs are mapped, including superseded and withdrawn records;
- conflicts, unsupported assumptions, missing rationale, and human-review questions are visible rather than silently normalized; and
- an independent exhaustive review finds no unallocated canonical design or unexplained decision-bearing claim.
