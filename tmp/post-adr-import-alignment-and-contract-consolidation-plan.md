# Post-ADR Import Alignment And Contract Consolidation Plan

Status: Agreed work, deferred until the current ADR parcel PR stack has merged. This is an ignored temporary handoff, not a canonical design or implementation tracker.

## Purpose

Complete the documentation-system cleanup and contract-authority consolidation before importing further large ADR parcels. The work should make the relationship between capability taxonomy, canonical design, decisions, implementation tracking, code, and proof easy to understand while reducing duplicated contract prose and transitional audit artifacts.

The result must preserve the useful alignment system rather than collapsing distinct authorities merely because their names sound similar.

## Current Preconditions

Do not edit the overlapping alignment and architecture surfaces until this current stacked sequence has merged:

1. PR `#2583`, `design/adr-parcel-03-authority-decisions` -> `develop`.
2. PR `#2581`, `design/adr-parcel-03-identity-session-enforcement` -> PR `#2583`.
3. PR `#2529`, `design/adr-parcel-03-security-and-account-lifecycle` -> PR `#2581`.

After the stack merges, rebase `design/adversarial-decision-review` onto fresh `develop` before classifying current repository state.

The human review worktree records `183/183` decisions reviewed. That is review-stage evidence, not a claim that all resulting decisions are canonical on `develop`. Only merged ADR and architecture changes count as applied target state.

## Permanent Document Model

Keep these layers distinct:

1. `design/architecture/product-capability-taxonomy.md` defines what product capabilities exist.
2. Canonical documents under `design/architecture/**` define current target-state behavior.
3. ADRs under `design/architecture/decisions/**` record consequential accepted choices, rationale, alternatives, and status; they do not replace complete current contracts.
4. Design capability allocation maps canonical design sources to capability ownership.
5. Implementation capability allocation maps every capability leaf to one primary status tracker and explicit handoffs.
6. The ten domain implementation trackers record implementation state, verification state, evidence anchors, and remaining gaps.
7. Code, schemas, configuration, tests, smoke checks, and operational proof provide implementation evidence.
8. Reconciliation artifacts compare those layers at a point in time; they are not additional design authorities.

The two capability-allocation surfaces are intentionally different and must not be merged:

- design allocation: architecture source -> product capability;
- implementation allocation: capability leaf -> implementation tracker.

## Relationship Diagram

Add one Mermaid diagram to `design/project-management/design-alignment/README.md`. It should replace explanatory prose rather than create a new diagram file or authority.

The diagram should show:

```text
product capability taxonomy
  -> design-source allocation
  -> source decision inventories
  -> consequential human-review queue
  -> application/import status
  -> merged ADRs and canonical architecture

product capability taxonomy
  -> implementation-tracker allocation
  -> ten domain trackers

canonical architecture + code/schema/config + executable/operational proof
  -> domain trackers and point-in-time reconciliation
```

Visually distinguish non-normative alignment artifacts from canonical architecture and evidence. Make the branch boundary explicit: reviewed decisions become canonical only after their ADR/design changes merge to `develop`.

## Point-In-Time Application Status

Add a compact table to the existing design-alignment README rather than creating `decision-application-status.md`.

Track each coherent ADR parcel or contract family through:

- human review complete;
- imported to branch/PR;
- merged to `develop`;
- canonical design and ADR alignment complete;
- contract-authority consolidation complete;
- implementation/proof follow-up recorded in the owning tracker.

The table must describe the merged repository state. Pending worktree decisions may be shown as reviewed/pending import, but never as accepted canonical behavior merely because the human review is complete.

Retire the ignored `tmp/pr-adr-parcel-*.md` handoffs once their durable status has been transferred to this table and their PRs have merged.

## Existing Complexity Audit And Cleanup

### Keep

- Product capability taxonomy.
- Canonical architecture and ADR registry.
- Design allocation summary plus its system and microservice detail ledgers. The summary/detail split is justified by ledger size.
- Implementation-tracking README, capability allocation, and ten domain trackers.
- Four source-scoped decision inventories while reviewed decisions are still being imported. Their repeated keys preserve source-specific evidence and contradictions rather than creating alternate decision authority.
- Consequential decision inventory as the completed human-review record.

### Simplify Or Retire

1. Update `design/project-management/design-alignment/README.md`: it still describes human review as in progress even though the review worktree records `183/183` complete.
2. Update `design/architecture/product-capability-taxonomy.md`: it still says allocation is underway.
3. Replace or regenerate the stale hand-maintained ADR subsection in `design-capability-allocation.md`. It lists only the original ADR set and must not become a second incomplete ADR registry.
4. Reduce the nine legacy alias rows in `consequential-decision-inventory.md` to alias, current key, disposition, and canonical link. Remove duplicated outdated current-choice prose already owned by the source ledger/current ADR.
5. Normalize the ten tracker structures where headings drifted, especially target-state authority, validation/proof, and service/contract-map sections. Preserve content while standardizing navigation.
6. Treat `capability-implementation-reconciliation.md` as a point-in-time reconciliation artifact, not a permanent parallel tracker. Move any uniquely durable cross-domain boundary, gap, or priority to its owning architecture document, implementation tracker, project-shape history, or alignment README. Then retire the file or clearly freeze it as historical evidence.
7. After all reviewed decisions are imported, classify the four source decision inventories as completed evidence rather than everyday active navigation. Preserve their evidence unless a verified consolidation proves it exists elsewhere; do not blindly merge their large ledgers into one larger file.

## Contract-Authority Consolidation

Run this as a focused phase after the current three PRs merge and before importing the remaining large ADR batches. Its purpose is to reduce repeated normative contract text and future review cost.

For each contract family touched by merged ADR work:

1. Identify one owning canonical architecture contract.
2. Keep complete current semantics there: state machines, schemas, identifiers, error behavior, retry/fencing rules, lifecycle, and authority.
3. Keep ADRs focused on decision, rationale, alternatives, constraints, and supersession.
4. Reduce service documents to local ownership, API, persistence, and operational consequences with links to the owning contract.
5. Reduce journey, protocol, and frontend documents to observable sequences and outcomes without copying internal field lists.
6. Keep runbooks focused on procedure and evidence, linking to semantic authority.
7. Keep inventories and trackers status-oriented; they may quote a one-sentence invariant and local consequence but must not restate complete contracts.

Initially add a concise contract-authority table to `design/architecture/README.md`. Create a separate `contract-authority-map.md` only if the table becomes too large for useful navigation. Do not create the extra file speculatively.

Pending reviewed decisions may inform which contract families will need later work, but must not be written into merged canonical design before their application PR lands. Review pending material by bounded contract family rather than loading all 183 decisions into one pass.

## ADR PR Finding Triage

Use this policy while finishing the current and later ADR import PRs:

1. Fix an incorrect accepted decision, ADR disposition, canonical owning contract, authority boundary, security rule, lifecycle, or externally observable behavior immediately.
2. Fix a secondary document immediately when its current wording contradicts the owning contract, would mislead implementation, or states an incorrect local consequence.
3. When duplicated normative prose is already scheduled for removal, do not repeatedly make every copy word-for-word equivalent. Prefer reducing the touched secondary copy to a link plus its local consequence when that is safe and coherent.
4. If removing the duplication would materially widen the active PR, preserve correctness with the smallest unambiguous wording and record the contract family for the consolidation phase. Do not silently leave a competing target state.
5. Keep implementation and verification claims point-in-time accurate. An accepted design decision may remain `partial` or `not-implemented`; do not manufacture code alignment inside a design import PR merely to make the documents appear converged.
6. Classify review findings as `fix-primary-now`, `fix-local-consequence-now`, `consolidate-duplicate`, or `implementation/proof-follow-up`. This prevents repeated review cycles from treating all textual differences as equally valuable.

The acceptance bar for an ADR import PR is therefore semantic correctness and absence of active contradiction, not exhaustive synchronization of prose that will be deleted. Broader alignment remains staged: high-churn contract consolidation after the current stack, a cross-family alignment pass after the main ADR imports, and capability-specific design/code/proof reconciliation during subsequent implementation.

## Execution Sequence

1. Merge the current ADR stack.
2. Rebase the completed decision-review branch onto fresh `develop` and inventory the exact merged/pending boundary.
3. Update the alignment README with the Mermaid relationship diagram and point-in-time application table.
4. Correct stale statuses and duplicated legacy rows.
5. Normalize tracker structure without changing capability meaning or status silently.
6. Reconcile and retire/freeze the one-time capability reconciliation artifact.
7. Consolidate contract authority for already merged ADR families, recording any implementation/proof gaps in the owning trackers.
8. Run focused structural validators and Markdown/link checks.
9. Use a bounded independent review of the resulting diff for authority loss, status overstatement, and accidental information deletion.
10. Resume importing remaining reviewed ADR parcels against the simplified document model.

## Completion Criteria

- A reader can determine what each documentation layer owns from one diagram and short prose.
- `develop` distinguishes reviewed, imported, merged, consolidated, implemented, and proven states.
- No active status surface claims all 183 decisions are canonical before their changes merge.
- Every repeated contract has one named canonical owner or an explicit unresolved ownership gap.
- Secondary documents link to canonical contracts and state only their local consequences.
- Completed audit ledgers are visibly evidence/history rather than competing active trackers.
- No source evidence or consequential decision is lost during simplification.
- Taxonomy, allocations, trackers, ADR registry, and structural validators agree.

## Non-Goals

- Do not perform the human adversarial decision review again.
- Do not implement pending product functionality as part of documentation consolidation.
- Do not treat reviewed but unmerged decisions as canonical.
- Do not create new ledgers, diagrams, or authority maps unless the existing README surfaces cannot remain readable.
- Do not delete completed evidence merely to reduce file count; first prove its useful facts are preserved or intentionally historical.
