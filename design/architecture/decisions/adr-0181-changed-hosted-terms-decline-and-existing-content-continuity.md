# ADR 0181: Changed Hosted Terms Decline and Existing Content Continuity

## Status

Accepted

## Implementation Status

This decision is target state only. FireMUD currently has no runtime implementation of material-change classification, creator-party and authorized-signer notice/status, renewal protection, penalty-free exit and refund handling, signer offboarding, stale-acceptance write classification, frozen-content continuity, lifecycle-reducing operation classification, finite transition-window enforcement, or focused proof for these behaviors. The [Hosted Content Terms](../../../HOSTED_CONTENT_TERMS.md) file remains a pre-launch policy baseline and is not an operative contract.

## Decision Record

- Human review status: Completed
- Human review date: 2026-08-25
- Human review disposition: Accepted
- Review source: `HOSTED-TERMS-02`
- Decision date: 2026-08-24
- Decision key: `HOSTED-TERMS-02`
- Primary capability: `PO-1.3`
- Affected capabilities: `AA-1.3`, `AA-1.4`, `AA-1.5`, `AR-1.1`, `AR-1.4`, `AR-1.5`, `EA-3.2`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: explicit human approval on 2026-08-24 and direct human refinement on 2026-08-25

## Context

[ADR 0180](./adr-0180-account-owned-hosted-terms-acceptance-gate.md) makes current affirmative acceptance by the tenant's identified creator party of the identified operator's immutable hosted terms the authority for new official-hosted creator-content mutations. It defines Account's party, signer, evidence, and currentness authority, Game Design's fail-closed mutation boundary, and exact committed replay. It deliberately did not decide what happens to already persisted or already published content when a creator party declines or does not reaccept a materially changed terms generation.

Without a separate boundary, a stale acceptance could be treated as an implied ongoing licence, a changed terms version could be applied retroactively, or a refusal could accidentally become a billing, deletion, or marketplace decision. Existing hosted content needs a narrow, finite operational continuity path while the creator can read, export what is supported, retire, remove, or delete it without accepting a new grant.

A bare right for the operator to vary terms would still leave creators exposed if the operator could classify its own substantive changes as minor, give nominal notice, renew a prepaid plan before the effective date, or end paid service without a usable exit and refund. The change process therefore also needs a conservative materiality rule, notice proportionate to the change and subscription term, a narrow urgent-change exception, and a penalty-free exit that does not leave the creator paying for unavailable service.

## Decision

### Material changes require conservative classification and reasonable notice

A change is material when it is reasonably likely to affect a creator's decision to accept or the creator's legal, economic, content, or exit position. Material changes include a different operator legal identity and substantive changes to creator-content rights, the limited hosting grant, price or payment obligations, renewal, suspension or termination, deletion or retention, dispute rights, governing law, or the creator's available remedies. A changed postal or support address, typographical correction, formatting-only change, or clarification with no substantive effect may be nonmaterial, but an operator-party change is never merely a contact-detail update.

Materiality is an audited operator/legal classification under ADR 0180. The classification must record the exact old and new versions, their immutable digests, the affected clauses, the reason for the classification, and the accountable reviewer. If the effect is reasonably uncertain, the operator must treat the change as material and require affirmative reacceptance rather than rely on a narrow classification.

The service gives reasonable advance notice of a material change, proportionate to the change, subscription or prepaid period, applicable law, and the creator party's practical ability to evaluate and exit. The notice identifies the current and proposed versions, exact effective date, operator, plain-language summary of the material changes, and available acceptance, decline, service-exit, unused-prepayment, and content-lifecycle choices. It must be directed to the Creator Party through disclosed durable channels, and Account records sufficient evidence to establish what notice was made available or sent without claiming it was read unless reliable evidence establishes that fact.

A 30-day floor, timing relative to renewal, early-acceptance behavior, exact individual or organization recipient rules, and detailed notice evidence are candidate pre-launch defaults to be finalized with the complete subscription terms and New Zealand legal review. Organization-specific recipient and signer handling applies only before organization hosting is advertised or enabled. These candidates are not universal invariants selected by this ADR.

Until the disclosed effective date, the previously accepted terms remain operative and ordinary authoring may continue under them. Publishing a future version for notice is not activation under ADR 0180: Account must distinguish the operative and proposed versions so notice cannot freeze writes early or apply future terms retroactively. The complete terms and implementation must state when any early acceptance becomes operative.

Shorter notice is permitted only to the extent required by law or when an immediate safety or security necessity makes the normal period impracticable. Existing contractual moderation, quarantine, suspension, and security powers may be exercised immediately on their existing basis. Urgency must not be used to obtain a broader content, intellectual-property, price, monetization, transfer, or promotional right, or to deem acceptance; if a new affirmative grant is still required, the service freezes covered writes and provides the exit path instead.

### Signer changes do not rewrite organization acceptance

An organization declines or reaccepts only through a currently authorized signer under ADR 0180. Removing or offboarding the human who made an earlier valid acceptance does not retroactively revoke the organization's old acceptance, delete its content, or change billing. It does prevent that former signer from accepting or declining for the organization, receiving signer-only authority, or selecting a party exit by virtue of the historical signature alone.

If an organization has no current authorized signer when reacceptance becomes due, Account records missing reacceptance rather than inventing authority from a tenant role, organization administrator, billing owner, or continued payment. At the effective date, the party's acceptance becomes stale and follows the same write freeze and continuity path as any other nonresponse. Restoring signer authority permits a future acceptance but does not retroactively authorize blocked writes.

### Decline or nonresponse blocks new creator-intent side effects

A creator party's explicit decline through an authorized actor, or lack of reacceptance after a material hosted-terms generation change, provides no current acceptance for the changed generation. It blocks every new official-hosted creator-content upload, authoring/edit/revision, asset/template/plugin write, new publication or republication, and every equivalent content-bearing creator-intent side effect already covered by ADR 0180. This classification applies at the authoritative Game Design mutation boundary, not only in Gateway or UI.

An exact request already durably committed under the previously accepted evidence retains ADR 0180's replay semantics. Replay of that exact committed outcome is not a new grant. An uncertain or uncommitted request is a new attempt and is blocked until current acceptance exists.

### No retroactive licence or implied acceptance

The changed terms are not applied retroactively. Continued use, continued hosting, a lack of response, subscription payment, or any other conduct does not infer acceptance and does not broaden the licence previously accepted. Reaccepting the current terms permits future covered operations only; it does not retroactively authorize a side effect attempted while acceptance was stale.

Previously persisted Draft or history content may remain temporarily stored and readable, and an unchanged already-published or active release may continue ordinary player delivery and runtime operation under the permissions of the previously accepted terms. The same narrow prior-terms permissions may support technically necessary caching, replication, backup, restart, repair, security, and moderation. This continuity must not:

- create a new creator-authored version, revision, publication, asset, template, plugin, or other content-bearing side effect;
- make changed content newly reachable or expand the authorized audience class, purpose, or delivery surface;
- creatively reuse the content or authorize a new or additional right; or
- turn an unchanged release into consent for a new release or other creator-intent operation.

The creator party's grant to the old operator under the previously accepted terms authorizes only this limited old-operator continuity. A future operator is not an automatic transferee and cannot serve or use the content without current acceptance or another separately reviewed lawful basis. A future FireDevOps company would therefore be a separate operator and a material change requiring the existing ADR 0180 notice and affirmative-reacceptance process; the current operator remains Benjamin James Hook until such a change is expressly identified. Changing the tenant's creator party is likewise not inferred from signer, membership, billing, or display-name changes and must use ADR 0180's explicit party-transfer boundary. Operator cutover mechanics are not settled here.

### Supported reads and lifecycle-reducing operations remain available

Subject to ordinary authentication, current party/tenant authorization, safety, and billing-safe access rules, supported read/download operations and bounded lifecycle-reducing operations remain accessible without accepting the changed terms. This includes unpublish, retire, removal, deletion, account-closure requests, and whatever controlled export the product actually supports. An organization action requires a current authorized party actor; a former signer has no continuing access merely because they signed historically. Each operation must be narrowly authorized, audited, idempotent, and limited to reducing stored content or reachability. It must not carry a new creator-content payload, increase the authorized audience class or reachability, create a new version, or disguise an authoring bypass. This decision does not promise whole-game portability: [ADR 0125](./adr-0125-defer-whole-game-portability-and-external-authoring-formats.md) remains controlling, and no new format or export product is created here.

### Billing and entitlement consequences remain independent

Decline or nonresponse does not itself delete content, cancel a hosting subscription, decide a refund, or change billing or entitlement state. Subscription and billing consequences remain governed independently by [ADR 0143](./adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md) and the Account subscription lifecycle. A billing state or successful payment must not silently imply current hosted-terms acceptance or additional content rights.

The settled subscription terms must not charge the tenant for prepaid ordinary service that becomes unavailable solely because the Creator Party declined or did not reaccept changed hosted terms. They must provide a finite, penalty-free service-exit path and fair treatment of any unused prepayment without treating billing-owner acceptance or payment as Creator Party acceptance.

Preventing a renewal that crosses the effective date without current acceptance, allowing frozen prior-terms hosting to an existing paid-through boundary, providing a proportional refund to the original payment method for an earlier exit, and allowing service credit only by affirmative election are candidate pre-launch defaults. The complete subscription terms and New Zealand legal review may select a simpler lawful combination that preserves the preceding protections. Debt recovery, abuse or security restrictions, legal holds, and provider reconciliation remain separate and do not create acceptance or broaden content rights.

### Continuity is finite and ends through the supported lifecycle

Before official launch, the applicable terms and lifecycle policy must disclose reasonable notice and a finite transition window for changed-term decline or nonresponse. Numeric duration, paid-through and operator cutoffs, and detailed transition choreography remain candidate pre-launch defaults to be finalized with the complete subscription terms and New Zealand legal review. At the finalized boundary, absent current acceptance, content must leave normal service use through the supported retirement, removal, or deletion lifecycle. The service must not claim instant physical erasure: [ADR 0050](./adr-0050-versioned-export-retention-and-erasure-policy.md) governs finite, purpose-specific retention, minimized restricted backups, and legal holds after normal use ends.

Self-hosted community operation remains outside this official-hosted continuity decision. Marketplace, promotion, resale, AI/model training, ownership transfer, and all other broader rights remain excluded by the hosted-content policy and accepted terms.

## Rationale

The choice preserves the legal and product distinction between an old, narrow operational permission and a new affirmative grant. It protects creators from retroactive terms application and surprise renewal while preventing a refusal from forcing immediate, unsafe physical deletion or from silently canceling billing. It gives a creator a practicable no-penalty exit when an operator changes material terms, gives operators a finite, auditable path to drain content from ordinary use, and keeps lifecycle-reducing user controls available without turning them into a write bypass.

The choice supplements ADR 0180 rather than superseding it: ADR 0180 remains authoritative for the Account catalog, acceptance evidence, currentness evaluation, Game Design mutation gate, and exact replay. ADR 0181 supplies only the changed-terms materiality, notice/effective-date, decline, existing-content continuity, lifecycle-reducing access, renewal/exit/refund, finite transition, operator, and independent-billing consequences.

## Alternatives Considered

### Treat continued use or subscription payment as acceptance

Rejected because acceptance must remain an explicit affirmative act bound to an immutable terms version, identified operator, and Account evidence. Inference would undermine ADR 0180's currentness and evidence boundary.

### Charge for an unavailable prepaid service period

Rejected because a creator must not pay for ordinary prepaid service that becomes unavailable solely through changed-terms decline or nonresponse. The complete terms must select a lawful finite exit and unused-prepayment treatment; this ADR does not require one renewal, refund-channel, or service-credit choreography.

### Use an urgent-change clause to add broader rights

Rejected because an immediate safety, security, or legal restriction can operate on its existing authority without manufacturing a new content grant. A broader right that still requires affirmative consent must wait for consent; otherwise covered writes freeze and the exit path applies.

### Apply the new terms to all existing content immediately

Rejected because it retroactively changes the creator's accepted rights and could broaden the licence without a new affirmative act.

### Delete or unpublish all content immediately on decline

Rejected because decline alone is not a deletion, refund, or billing decision, and immediate physical deletion would conflict with supported lifecycle, safety, backup, and legal-hold requirements. It would also remove accessible lifecycle-reducing controls before the finite transition path completes.

### Permit all creator operations on existing content until deletion

Rejected because it would let stale acceptance create new authored versions, broaden reachability, or become a hidden reacceptance bypass. Only exact replay, ordinary unchanged-release operation, technical continuity, supported reads/downloads, and bounded lifecycle-reducing operations remain available.

### Promise whole-game export or portability at transition

Rejected because whole-game portability remains deferred under ADR 0125. Existing controlled export remains limited to the formats and lifecycle product actually implemented and reviewed.

### Treat a new operator as the old operator's transferee

Rejected because operator identity is part of acceptance evidence and legal authority. A cutover requires its own reviewed basis and is not resolved by this ADR.

## Consequences

- Account must expose creator-party and signer currentness, conservative materiality, notice, effective-date, decline/nonresponse, renewal, exit/refund, and independent billing/entitlement consequences without treating any of them as a content licence.
- Game Design must classify stale-acceptance creator-intent writes separately from exact replay, ordinary unchanged-release continuity, supported reads/downloads, and lifecycle-reducing operations.
- Existing Draft/history storage and unchanged release delivery may need bounded temporary continuity, but no new content-bearing side effect may be accepted during stale acceptance.
- Terms and lifecycle policy must provide reasonable notice, a finite transition and penalty-free exit, and fair unused-prepayment treatment before official hosted launch. A 30-day notice floor, renewal timing, early-acceptance behavior, automatic-renewal choreography, exact paid-through freeze behavior, proportional refund destination, service-credit election, recipient rules, and detailed evidence remain candidate pre-launch defaults to be finalized with the complete subscription terms and New Zealand legal review; none is a universal invariant selected by this ADR.
- Retirement/removal/deletion and controlled export remain narrowly authorized, audited, idempotent, and content-nonexpanding, with ADR 0050 retention and legal-hold treatment after normal use ends.
- Billing, subscriptions, entitlements, refunds, marketplace rights, promotion, resale, AI/model training, and ownership remain governed by their separate authorities and are not changed by decline.
- Self-hosting remains unaffected, and future operator cutover requires separate review.

## Implementation and Proof Obligations

Before official hosted launch, implement and prove:

- Account creator-party/signer currentness and party-facing notice/status that records explicit decline or missing reacceptance without inferring authority or acceptance, immediately canceling billing, or deleting content, while keeping the finalized exit and unused-prepayment treatment as explicit subscription-lifecycle outcomes;
- individual creator-party currentness, notice/status, explicit decline or missing reacceptance, and rejection of billing-owner or payment substitutes;
- organization signer removal/offboarding, multi-recipient notice, missing-current-signer nonresponse, persistence of valid historical party acceptance, and rejection of tenant-role, organization-admin, billing-owner, or former-signer substitutes only before organization hosting is advertised or enabled;
- audited conservative materiality classification, exact version/digest and change-summary evidence, reasonable notice, effective-date handling, a narrow lawful-necessity exception, and proof that urgency cannot broaden rights or deem acceptance; numeric notice and transition choreography remain candidate defaults pending complete terms and New Zealand legal review;
- Game Design classification and fail-closed enforcement for every new creator-intent content-bearing operation, including upload, authoring/edit/revision, asset, template, plugin, publication, republication, and equivalent future writes;
- exact committed replay under ADR 0180 evidence, with uncertain or stale attempts blocked and reacceptance unable to retroactively authorize an earlier blocked side effect;
- frozen Draft/history storage and unchanged published/active delivery continuity limited to prior-terms permissions and technically necessary cache, replication, backup, restart, repair, security, and moderation operations;
- prevention of newly reachable changed content, authorized-audience-class or purpose expansion, creator-authored new versions, and any new/additional right during continuity;
- read/download and lifecycle-reducing operation classification, narrow authorization, audit, idempotency, no-new-creator-content-payload enforcement, and no whole-game portability promise;
- a finite transition window in applicable accepted terms and lifecycle policy, with New Zealand legal review and ADR 0050 purpose-specific retention, restricted backups, and legal holds;
- independent billing/subscription/refund/entitlement handling under ADR 0143 and Account subscription management, including a finite penalty-free exit and the finalized fair unused-prepayment treatment; renewal prevention, original-payment-method preference, service-credit substitution, exact renewal choreography, and detailed transition mechanics remain candidate defaults pending complete terms and New Zealand legal review; and
- focused route inventory and proof for materiality/notice evidence, future-version activation, prompts/status, stale-write rejection, continuity, lifecycle-reducing operations, renewal/exit/refund handling, finite transition, operator identity, and billing independence.

No runtime code, API, schema, route, or proof currently implements these obligations. This ADR must not be reported as implementation.

## Reversibility and Revisit Triggers

The transition-window duration, notice wording and recipients, renewal and unused-prepayment treatment, lifecycle operation names, prompt/status presentation, and evidence-reference shape may evolve while preserving conservative materiality, reasonable notice, no inferred acceptance, no retroactive or broadened rights, prior-terms-only continuity, narrow lifecycle reduction, finite transition, penalty-free exit and fair unused-prepayment treatment, independent billing, and operator-specific lawful authority. Revisit this decision for a different operator-transfer model, whole-game portability/export product, marketplace or promotion right, AI/model-training right, ownership transfer, or any change to ADR 0180's acceptance authority; each requires explicit human legal and architecture review.

## Required Documentation Alignment

- [ADR 0180](./adr-0180-account-owned-hosted-terms-acceptance-gate.md)
- [ADR 0050](./adr-0050-versioned-export-retention-and-erasure-policy.md)
- [ADR 0125](./adr-0125-defer-whole-game-portability-and-external-authoring-formats.md)
- [ADR 0143](./adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md)
- [Hosted Content Terms baseline](../../../HOSTED_CONTENT_TERMS.md)
- [Account Service API Contracts](../microservices/account-service/api-contracts.md)
- [Account Service Runtime and Data](../microservices/account-service/runtime-and-data.md)
- [Account Subscription Management](../microservices/account-service/subscription-management.md)
- [Game Design Service](../microservices/game-design-service/README.md)
- [Game Design Service API Contracts](../microservices/game-design-service/api-contracts.md)
- [Asset Storage Setup](../microservices/game-design-service/asset-storage.md)
- [Product Requirements](../../product/requirements.md)
- [Creator User Journey](../../product/user-journeys/creators.md)
- [Player Access and Session tracker](../../project-management/implementation-tracking/player-access-and-session.md)
- [Game Authoring, Publishing, and Activation tracker](../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md)
- [Architecture authority map](../README.md#contract-authority-map)
- [Service responsibility matrix](../service-responsibility-matrix.md)
