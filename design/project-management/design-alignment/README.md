# Design Decision and Implementation Alignment

This workstream establishes a complete, reviewable chain from FireMUD product capabilities through canonical design decisions to implementation tracking and proof. Product documents define requirements and observable product behavior; architecture documents define technical contracts; ADRs explain consequential decisions; trackers record implementation and proof. This workstream remains non-normative.

## Status

Completed phases: capability allocation, implementation/proof reconciliation, cross-domain convergence, structural enforcement, independent validation, human-led adversarial review of all `183` queue/navigation rows in the remotely backed review archive, and the completed baseline authority pass for ADRs 0001-0050 plus its major adjacent non-ADR contract families. Packet 3 is complete: ADRs 0051-0092 and the no-ADR TICK-05 outcome are selectively applied with family-local contract consolidation; they do not redefine the completed baseline. Packet 4 is complete: the prior publication/assets/promotion/procedural-generation, patch-pin/script-transition/timer and unified DSL/plugin-lifecycle, runtime-controls, and final lifecycle/authoring parcels apply all `36` reviewed outcomes. Packet 5 tail moves applied provenance `134→140` through six reviewed outcomes, bringing Packet 5 to all `21` applied rows through ADRs 0131-0150, including the superseded `SOCIAL-01` provenance. Packet 6 P0 front moves `140→143` through the three reviewed compliance/preflight outcomes represented by ADRs 0151-0152; the coupled preflight pair shares ADR 0152. Both parcels have family-local contract consolidation. The remaining Packet 6 operations/delivery tail and Packet 7 remain pending selective import. The separately tracked service-scan `MS-AA-TOKEN-REVOCATION` row remains the only excluded navigation alias. The canonical registry now contains `150` ADR records (`139` reviewed and `11` pre-formal), with seven new front ADR records. `143` of the `182` distinct decision keys have checked applied provenance; the other `39` reviewed active decision keys remain pending selective import and are not yet canonical repository state. Canonical ADRs 0093-0103 and 0106-0152 are repository state here; unimported source-archive identifiers remain archive-local evidence and may collide numerically with canonical ADRs, so this parcel preserves its canonical numbering rather than importing source numbers.

| Phase | Status | Output |
| --- | --- | --- |
| Product capability taxonomy | Complete | [`capability-taxonomy.md`](../../product/capability-taxonomy.md) |
| Canonical design allocation | Complete and independently coverage-audited | [`design-capability-allocation.md`](./design-capability-allocation.md) |
| Consequential-decision inventory | Complete and independently coverage/fidelity-audited | [`consequential-decision-inventory.md`](./consequential-decision-inventory.md) |
| Implementation tracker reshaping | Complete | [Capability allocation](../implementation-tracking/capability-allocation.md) and ten permanent domain trackers |
| Code and proof reconciliation | Complete and independently validated | Per-capability implementation/verification states and evidence anchors in the domain trackers |
| Cross-domain convergence | Complete and independently validated as a point-in-time baseline | [Frozen capability implementation reconciliation](./capability-implementation-reconciliation.md) |
| Human-led adversarial decision review | Complete in the `design/adversarial-decision-review` source archive | Human-owned dispositions for all `183` queue/navigation rows |
| Accepted-decision application | In progress | `143` distinct decision keys have checked applied provenance; `39` reviewed active decision keys remain pending selective import |
| Contract-authority consolidation | Baseline pass complete for ADRs 0001-0050 and major adjacent non-ADR families; Packets 3-4 (ADRs 0051-0103 and 0106-0130 plus TICK-05), Packet 5 (ADRs 0131-0150), and the front of Packet 6 P0 (ADRs 0151-0152) are selectively applied with family-local consolidation; continue through the remaining Packet 6 operations/delivery tail and Packet 7, then perform a whole-corpus authority review | [Architecture contract authority map](../../architecture/README.md#contract-authority-map) and owner-link-plus-local-consequence conversions |

## Implementation Status

`Complete` in the phase table means that the allocation, inventory, reconciliation, or human review work is complete; it does not mean every reviewed decision is merged or every product capability is implemented and proven. The ten [domain implementation trackers](../implementation-tracking/README.md) are the live implementation and verification authority. [Capability Implementation Reconciliation](./capability-implementation-reconciliation.md) is a frozen point-in-time baseline.

## Documentation And Evidence Flow

```mermaid
flowchart LR
    PRODUCT[Product requirements and journeys]:::canonical --> ARCH[Canonical architecture]:::canonical
    TAXONOMY[Product capability taxonomy]:::canonical --> DESIGN_ALLOC[Design-source allocation]:::alignment
    DESIGN_ALLOC --> SOURCE_INV[Source decision inventories]:::alignment
    SOURCE_INV --> HUMAN_QUEUE[Completed human-review queue]:::alignment
    HUMAN_QUEUE --> APPLY[Point-in-time application status]:::alignment
    APPLY -->|merged to develop| APPLIED_DECISIONS[143 applied decision keys, including complete Packets 3-4, Packet 5 through ADR 0150, and the front of Packet 6 P0 through ADR 0152, plus owning design changes]:::status
    APPLIED_DECISIONS -. explains; does not replace .-> ARCH
    APPLY -->|reviewed; pending selective import| PENDING[39 active decision keys pending selective import, including the Packet 6 operations/delivery tail and Packet 7]:::pending

    TAXONOMY --> TRACKER_ALLOC[Implementation-tracker allocation]:::alignment
    TRACKER_ALLOC --> TRACKERS[Ten live domain trackers]:::status
    ARCH --> TRACKERS
    CODE[Code, schemas, and configuration]:::evidence --> TRACKERS
    PROOF[Tests, smoke, and operational proof]:::evidence --> TRACKERS

    ARCH --> RECON[Frozen point-in-time reconciliation]:::alignment
    CODE --> RECON
    PROOF --> RECON

    classDef canonical fill:#e8f1ff,stroke:#1f4f8f
    classDef alignment fill:#f3f3f3,stroke:#666
    classDef pending fill:#fff4d6,stroke:#9a6700
    classDef status fill:#eaf7ea,stroke:#2f6f3e
    classDef evidence fill:#f8eef8,stroke:#7a4e7a
```

Product and architecture documents are normative within their stated boundaries. ADRs explain accepted consequential choices. Allocation, inventory, application-status, tracker, and reconciliation artifacts are non-normative. Code and proof are implementation evidence. A human-reviewed decision becomes canonical only after any required ADR and its owning design changes merge to `develop`.

## Decision Application Status

This table describes merged repository state, not merely completed human review. The completed review archive remains source evidence; each remaining import must start from current `develop` and selectively apply its reviewed outcome rather than wholesale rebasing the archive.

| Decision parcel | Human review | Applied to `develop` | Contract consolidation | Implementation and proof |
| --- | --- | --- | --- | --- |
| Existing ADR baseline, ADRs 0001-0011 | Complete in the review archive | ADR records and accepted design are present; record presence alone is not applied-review provenance | Baseline owner-and-secondary consolidation complete through #2593 and #2594 | Live gaps remain in the domain trackers |
| Applied review packet 1, 9 active decision keys | Complete | Checked provenance merged through ADRs 0012-0019 by #2527, with recovery and CI follow-through in #2537 | Baseline owner-and-secondary consolidation complete through #2593 and #2594 | Live gaps remain in the domain trackers |
| Applied review packet 2, 31 active decision keys | Complete | Checked provenance merged through ADRs 0020-0050 across #2528, #2574, #2583, #2581, and #2529 | Baseline owner-and-secondary consolidation complete through #2593 and #2594 | Live gaps remain in the domain trackers |
| Selective Packet 3, ADRs 0051-0092 plus TICK-05 | Complete | Selectively applied with checked provenance for all 43 reviewed outcomes | Family-local owner-link and local-consequence consolidation; the ADRs explain the choices but do not replace their owning contracts | Live gaps remain in the domain trackers |
| Selective Packet 4, all 36 reviewed publishing, settings, authored-behavior, lifecycle, and authoring outcomes | Complete in the review archive | All four Packet 4 parcels are applied with checked provenance, including ADRs 0093-0103 and 0106-0130, the deferred portability boundary in ADR 0125, and the formal superseded equipment-history ADR 0130; the separately tracked service-scan `MS-AA-TOKEN-REVOCATION` alias remains the only excluded navigation alias | Family-local owner-link and local-consequence consolidation is complete for Packet 4; later parcels continue the same process before the whole-corpus authority review | Reconcile owning trackers only where accepted target state changes implementation/proof gaps |
| Selective Packet 5 connection/output lane, six reviewed outcomes | Complete in the review archive | Checked provenance applied for `EDGE-05`, `SESSION-02`, `SESSION-03`, `CMD-04`, `CMD-03`, and `CMD-05` through ADRs 0131-0136 | Family-local owner-link and local-consequence consolidation for Gateway close taxonomy, session control/reconnect, durable context, output, and localization | Reconcile owning trackers only where accepted target state changes implementation/proof gaps |
| Selective Packet 5 playtest/lifecycle/player-entry lane, four reviewed outcomes | Complete in the review archive | Checked provenance applied for `TENANT-03`, `PLAYTEST-01`, `LIFE-01`, and `PLAYER-01` through ADRs 0137-0140 | Family-local owner-link and local-consequence consolidation for playtest namespaces/grants, tenant-owned lifecycle, and realm-authored actor entry | Reconcile owning trackers only where accepted target state changes implementation/proof gaps |
| Selective Packet 5 moderation/commerce/frontend/protocol lane, five reviewed outcomes | Complete in the review archive | Checked provenance applied for `SAFETY-01`, `MS-PO-MODERATION-APPEALS`, `COMMERCE-01`, `FRONT-01`, and `MCP-01` through ADRs 0141-0145 | Family-local owner-link and local-consequence consolidation for safety categories/appeals, Stripe hosting billing, the stateless frontend boundary, and plain-text gameplay with deferred classic-client extensions | Reconcile owning trackers only where accepted target state changes implementation/proof gaps |
| Selective Packet 5 communication/moderation/social lane, six reviewed outcomes | Complete in the review archive | Checked provenance applied for `MOD-01`, `MS-GR-COMMUNICATION-ORCHESTRATION`, `MS-SOCIAL-RELATIONSHIP-AUTHORITY`, `MS-SOCIAL-HISTORY-DURABILITY`, and `MS-SOCIAL-OBSERVER-SHOUT-POLICY` through ADRs 0146-0150; `SOCIAL-01` is checked as superseded provenance by ADRs 0147, 0149, and 0150 | Family-local owner-link and local-consequence consolidation for owner-local moderation, communication classes/delivery, social relationship/value authority, type-specific history, and closed observer/profile-scoped shout behavior | Reconcile owning trackers only where accepted target state changes implementation/proof gaps |
| Selective Packet 6 P0 front lane, three reviewed outcomes | Complete in the review archive | Checked provenance applied for `COMPLIANCE-01`, `PREFLIGHT-01`, and `PREFLIGHT-02` through ADRs 0151-0152; `PREFLIGHT-01` and `PREFLIGHT-02` share ADR 0152 | Family-local owner-link and local-consequence consolidation for compliance and phased preflight/bindings | Reconcile owning trackers only where accepted target state changes implementation/proof gaps |

## Contract Authority Consolidation Scope

Contract-authority consolidation applies to repeated normative product and architecture contracts whether or not an ADR records their rationale. ADRs organize the selective application process, but they are not the boundary of the deduplication work. PRs #2593 and #2594 complete the baseline pass for ADRs 0001-0050 and the major adjacent non-ADR contract families encountered across those design areas. Packets 3-4 (ADRs 0051-0103 and 0106-0130 plus TICK-05), Packet 5 (ADRs 0131-0150), and the front of Packet 6 P0 (ADRs 0151-0152) are now selectively applied with family-local consolidation; the remaining Packet 6 operations/delivery tail and Packet 7 continue the same owner-link and local-consequence process without redefining that baseline.

Consolidation names one canonical owner for a target contract and reduces competing secondary definitions to owner links plus local API, persistence, transport, operational, user-visible, implementation-drift, or proof consequences. It is not editorial deduplication: useful examples, runbooks, evidence schemas, local constraints, and explanatory context remain where they serve their owning document.

Each pending decision-family import must consolidate all related normative duplication in the affected design area, including non-ADR contracts. After packets 3-7 are applied, perform one broad design-area authority review across the complete product and architecture corpus to identify residual owner conflicts and non-ADR duplication that family-local imports did not expose.

The working rule is to continue selective ADR-family imports rather than pause for another standalone broad cleanup, applying owner-link and local-consequence deduplication within each parcel before the planned whole-corpus authority pass after packets 3-7. During PR review, follow the linked [PR lifecycle](../../developer-workflows/pr-lifecycle.md); stop additional review cycles when new findings have tapered to duplicates, stylistic polish, or immaterial restatement rather than requiring zero conceivable suggestions.

## Authority Boundaries

- Product documents define product requirements and observable product behavior; the [product requirements overview](../../product/requirements.md) is the canonical product scope summary.
- The product capability taxonomy defines stable navigation and ownership categories, not technical runtime behavior.
- Canonical architecture documents define target-state technical contracts.
- Architecture decision records explain consequential accepted choices and their tradeoffs; they do not replace the current product or technical contract.
- This directory contains allocation, inventory, review, and status artifacts. It is deliberately non-normative.
- Implementation trackers report implementation and proof against accepted design. They do not define design.
- Automated inventory work may identify evidence, conflicts, alternatives, and review questions, but it must not conduct or resolve a future adversarial decision review. The completed review archive records the human-owned dispositions used by the selective import process.

## Coverage Rules

- Every canonical Markdown source under `design/product/**` and `design/architecture/**`, plus every separately normative mixed-document section, must have exactly one primary capability allocation.
- Cross-domain effects are recorded as secondary handoffs rather than duplicate primary ownership.
- Every consequential explicit or implicit decision must map to at least one capability and its canonical source.
- An inventory entry is not an accepted decision merely because current design or code implies it.
- Human product or architecture decisions remain unresolved until explicitly discussed and accepted.
- Routine local implementation choices do not require ADRs. ADR candidates are cross-cutting, expensive to reverse, authority-setting, security-sensitive, or supported by credible competing target states.

## Current Work Sequence

1. Select a reviewed, pending decision family from the source archive against current `develop` rather than rebasing the archive wholesale.
2. Verify its human-owned disposition and strongest evidence against current canonical product and architecture documents.
3. Import any required ADR and the owning design changes, consolidate affected ADR and non-ADR contract ownership, and add checked applied-review provenance in the same change.
4. Reconcile the live domain trackers only where the merged decision changes implementation, verification, or remaining-gap state.
5. Validate and merge the family before importing a dependent family. The completed taxonomy, allocation, inventory, reconciliation baseline, and human-led review are historical prerequisites, not steps to rerun for each import.
6. After packets 3-7 are applied, run the whole-corpus authority review defined above before declaring post-ADR design alignment complete.

## Automated Gates

The Packet 5 tail and Packet 6 preflight-foundation validation completed with `325` discovered sources (`322` allocated, `3` explicit exemptions), ADR review status `139` reviewed / `11` pre-formal, `linkCheck` at `5,866` total links (`5,829` OK, `37` excluded, `0` errors), `lintMarkdown` at `502` files (`0` issues), allocation regression `28` tests, ADR review regression `137` tests, and authorization-route regression `189` tests passing within the architecture suite. Focused Gateway route tests, Kustomize/Helm render validation, preflight, secret-compliance, and Helm JWKS contract proof also passed, with clean `git diff --check`. No hosted environment-backed deployment proof was run. Because this packet changes Gateway runtime routes and Kubernetes/Helm wiring, the validation workflow additionally requires canonical fresh-image rebuild-and-boot proof; that evidence is reported separately from these static and focused gates.

- `python3 dev-tools/validation/check-design-capability-allocation.py` derives the product and architecture source sets, parses each allocation ledger, and reconciles the declared current coverage summary (`325` discovered sources, `322` allocated sources, and the canonical `2` governance/template exemptions plus `1` registry exemption).
- all intended FireMUD user, creator, operator, runtime, authoring, automation, platform, and commercial concerns have a capability home;
- every Markdown source under `design/product/**` and `design/architecture/**` is present and classified in the allocation ledger, including generated/index material, unless it is one of the two explicit governance/template exemptions or the decision-registry exemption;
- mixed canonical documents have heading-level allocations where file-level allocation would hide a real ownership split;
- every capability has been inspected for consequential explicit and implicit decisions;
- existing ADRs are mapped, including superseded and withdrawn records;
- conflicts, unsupported assumptions, missing rationale, and human-review questions are visible rather than silently normalized; and
- an independent exhaustive review finds no unallocated canonical design or unexplained decision-bearing claim.

The canonical non-allocatable taxonomy is exactly `2` governance/template sources plus `1` registry exemption; these are classifications, not additional capabilities or decision owners.

The capability implementation reconciliation additionally requires:

- `python3 dev-tools/validation/check-implementation-capability-tracking.py` derives the capability and per-tracker totals from the allocation table before accepting its Coverage Summary;
- every taxonomy leaf appears exactly once in the primary-tracker allocation and exactly once in a tracker status table;
- implementation and verification are represented independently with approved states;
- each capability names canonical design, production evidence, focused proof, handoffs, and its remaining gap or decision;
- cross-domain ownership and terminology are consistent with canonical design rather than inferred from implementation convenience;
- direct canonical contradictions are resolved before status is claimed; and
- the structural contract and independent evidence-quality review both pass before this automated phase is called complete.
