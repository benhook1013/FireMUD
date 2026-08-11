# Authority And Decision Closure Review

Use this prompt once the main target-state design is believed to be complete, or after a substantial reallocation of canonical design authority.

Apply the [shared review contract](./00-shared-review-contract.md).
Apply the [orchestrated review workstream contract](./02-orchestrated-review-workstream-contract.md).

## Orchestrated Execution

A full invocation is an orchestrated review workstream: the invoking main thread takes primary ownership and delegates bounded evidence lanes for:

- product outcomes and allocated design authority;
- architecture, service, and public-traffic contract ownership, including secondary-document duplication;
- consequential-decision and ADR state and incorporation; and
- tracker and status authority, including target, current, and proof claims.

The primary thread reconciles contract families and retains judgment over ambiguity and consequential decisions.

## Starting Sources

- `design/product/README.md`
- `design/architecture/README.md`
- `design/architecture/system-architecture-overview.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/decisions/README.md`
- `design/project-management/design-alignment/README.md`
- `design/project-management/design-alignment/design-capability-allocation.md`
- `design/project-management/design-alignment/consequential-decision-inventory.md`
- `design/project-management/implementation-tracking/README.md`

Follow the allocation and decision indexes to the canonical sources and ADRs needed to assess their claims.

## Review

Check that:

- every normative product outcome and technical contract has one clear canonical owner;
- secondary documents link to that owner and state only their local consequences;
- product, architecture, ADR, tracker, and implementation claims follow the repository's authority direction;
- accepted consequential decisions are reflected in current canonical design;
- pending, rejected, superseded, or human-unreviewed decisions are not presented as accepted;
- current implementation status is not presented as target design, and target-only behavior is not presented as shipped;
- service responsibilities and public traffic ownership agree across their canonical summaries; and
- explicit design gaps remain visible rather than being silently filled from code or tracker behavior.

Do not judge whether every capability is implemented or proven; that belongs to the capability census. Do not choose between competing target states.

## Output

Provide:

1. an authority-coverage table organized by contract family or allocated design source;
2. authority conflicts, duplicate normative claims, orphan contracts, and unapplied accepted decisions;
3. pending human decisions that prevent closure;
4. stale target/current or acceptance-status statements; and
5. the review state required by the shared contract.
