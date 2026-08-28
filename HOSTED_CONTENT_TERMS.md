# FireMUD Hosted Content Terms Baseline

## Status and intended use

This document is the pre-launch policy baseline intended for incorporation into the official FireMUD hosted-service terms. It is not, by itself, a creator contract. The document's existence in the FireMUD repository does not create a grant, bind a creator, or make a hosted operator party to terms. Before this baseline can be relied on, the actual hosted-service operator must be identified in the applicable terms and the creator must accept those terms through the hosted service's creator acceptance flow.

The target acceptance and enforcement boundary is [ADR 0180](design/architecture/decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md): Account owns hosted creator parties, signer authority, immutable terms versions, exact operator identity, and append-only party-wide acceptance evidence; Game Design must fail closed before the first persisted official-hosted creator content, including Drafts, and before later creator-intent content mutations. [ADR 0181](design/architecture/decisions/adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md) supplements that gate for changed terms: stale acceptance permits no new creator-intent content side effect, while narrow prior-terms continuity and lifecycle-reducing operations follow the finite transition and retention boundary below. This policy baseline does not itself provide that party registry, signer verification, catalog, API, storage, UI, evidence binding, transfer workflow, or implementation proof.

FireDevOps is a project brand, not a legal entity. Until a company or other operator is expressly identified in applicable accepted terms, those terms may identify Benjamin James Hook as the operator. A future company or other entity must be expressly identified in the applicable terms accepted by the creator; this baseline does not treat it as already existing, make it the operator, or automatically transfer creator agreements merely because it uses the FireDevOps brand.

This baseline is not legal advice and is not represented as legally sufficient, privacy-compliant, consumer-law compliant, or enforceable. New Zealand legal review is required before official hosted launch.

## Separate legal and product lanes

These terms govern only the official FireMUD hosted-service operation of creator content when incorporated into accepted hosted terms. They do not replace or amend:

- [LICENSE.md](LICENSE.md), which governs the FireMUD repository software and other repository material;
- [CONTRIBUTOR_LICENSE_AGREEMENT.md](CONTRIBUTOR_LICENSE_AGREEMENT.md), which governs an intentional repository contribution and its inbound contribution grant;
- [LICENSING.md](LICENSING.md), including the community self-hosted lane and its noncommercial, no-money policy;
- [TRADEMARKS.md](TRADEMARKS.md), which governs FireMUD and FireDevOps names, logos, and official-service identity; or
- future marketplace, commerce, settlement, entitlement, or player-purchase terms, which require a separate accepted decision and creator opt-in.

Hosting through the official service does not make creator content repository material, a repository contribution, or a community self-hosting permission. A creator's repository contribution remains governed by the applicable repository terms and accepted CLA rather than by this hosted-content grant.

## Creator contracting party and signer authority

The **Creator Party** is the person or legal entity that contracts with the identified operator and owns or controls the rights needed to grant the limited hosting permission. It is either an individual aged 18 years or older acting personally, including a sole trader, or an identified legal entity capable of contracting. A trading name, brand, project, informal team, or unincorporated group does not become a separate party merely because it has a display name or tenant. Joint, partnership, trust, minor/guardian, and other party forms are unsupported until separately reviewed.

For the official-hosted product, an individual creator or organization signer must be aged 18 years or older and otherwise have legal capacity to enter or accept the applicable hosted terms. This is a product eligibility rule, not a claim that New Zealand law universally defines adulthood or contractual capacity at 18.

An individual accepts only for themself through their authenticated account. A legal entity accepts only through an authenticated signer aged 18 years or older whose authority to bind that entity has passed the applicable verification process. A tenant administrator, designer, organization administrator, billing owner, payer, or person with a company email address is not a signer merely because of that status. The acceptance evidence identifies the exact Creator Party, signer, signer capacity and authority evidence where applicable, operator, immutable terms version and digest, generation, and server timestamp.

A valid organization acceptance belongs to the organization. Removing or offboarding its signer does not retroactively invalidate the agreement, but the former signer may not accept later changes or exercise signer-only party actions. A current authorized signer is required for reacceptance. Organization contracting must remain disabled and unadvertised until the party, signer, lifecycle, evidence, and verification boundaries are implemented and legally reviewed; the service may launch first for individuals aged 18 years or older acting in their own capacity.

Tenant roles authorize operational actions, not legal agency or copyright ownership. An authorized collaborator may submit content on behalf of the tenant's Creator Party, and the party remains responsible for owning or controlling all rights needed for every collaborator and third-party contribution. The platform's technical party, tenant, role, payer, and `ownerId` records do not adjudicate an external ownership dispute.

## Creator Content and ownership

For these terms, **Creator Content** means original game or world content that a creator owns or controls and deliberately chooses to host through the official FireMUD service. It may include the creator's original narrative, maps, rules, dialogue, artwork, music, sound, scripts, and similar game-world material.

Creator Content does not include FireMUD or other repository material, material the creator does not own or control, or player communications and other player-created content governed by separate terms. If a submission contains mixed or third-party material, the creator remains responsible for obtaining and maintaining every permission needed for the official service to perform the operations below. The grant covers only rights the creator owns or controls and does not expand or override a third party's terms.

The creator retains ownership of Creator Content. The hosting grant is limited to the operational rights expressly described below and does not transfer ownership or grant rights by implication.

Technical references elsewhere in FireMUD documentation to a tenant, game, service, domain, record, or `ownerId` as an owner describe administrative authority, storage custody, or a software ownership boundary. They do not transfer or determine copyright or other intellectual-property ownership.

Changing a tenant from an individual Creator Party to a company or between any two parties requires an explicit reviewed transfer. The source and target must provide the required authority, the target party must accept the current operator and terms through an authorized signer, and the service must record a lawful basis showing that the target owns or controls the rights needed for continued hosting. The service-party transfer does not itself assign copyright, resolve an ownership dispute, change tenant membership, or transfer the subscription, billing owner, payment instruments, marketplace rights, or historical acceptance evidence. Disputed, one-sided, ambiguous, or unavailable-source transfers are not ordinary self-service operations.

## Limited hosting grant

When incorporated into accepted hosted terms, the creator grants the identified hosted-service operator a non-exclusive, worldwide, royalty-free, limited licence to operate FireMUD using the Creator Content. The grant permits the operator, only as needed for that service operation, to:

- host and store the content;
- reproduce and copy it, including for ordinary service processing;
- cache and back it up;
- technically transform it by formatting, encoding, compressing, resizing, normalizing, or replicating it, but not by creatively reusing it;
- display it, including creator-directed in-service game listing and discovery display;
- transmit, deliver, and make it available to authorized service users;
- secure and inspect it; and
- moderate, quarantine, restrict, or remove it as required for service operation, safety, security, policy enforcement, support, or law.

The operator may use necessary infrastructure processors only to perform the same operations for the official service. Those processors receive no broader independent right to use the content, and the operator may not sublicense the grant beyond those necessary service processors.

Creator-directed in-service listing or discovery is operational display for the hosted service. External advertising, testimonials, endorsements, or promotional campaigns require separate creator permission; they are not included in this grant.

## Express exclusions

The hosting grant does not permit or authorize:

- transfer of ownership or a sale of Creator Content;
- marketplace participation, creator-to-player purchases, paid game subscriptions, tips, donations, platform fees, revenue sharing, payouts, settlement, or other marketplace rights;
- advertising or promotional use beyond creator-directed in-service discovery;
- AI or model training, model improvement, dataset creation, or similar use;
- resale or standalone exploitation of Creator Content;
- sublicensing except to necessary infrastructure processors for the same service operations; or
- any other use not required to operate FireMUD as the official hosted service.

If a creator separately requests a model-assisted inference feature, that feature may process relevant content only under separately disclosed feature and provider terms. Processing an inference request does not itself grant training or model-improvement rights. Any future training use requires separate explicit creator permission and approval.

## Duration, reachability, and deletion

The grant begins only when the Creator Party has accepted applicable hosted terms that identify the operator and has submitted or selected the Creator Content for official hosting. It continues while that content is hosted or remains reachable through the supported draft, published, active, or history lifecycle. It ends after the supported party/tenant deletion or closure lifecycle for the content has completed. Closing or offboarding an organization signer's personal account does not itself close the organization, delete its content, or end its grant.

A deletion request starts the supported deletion lifecycle; it is not an instant claim that active, published, or history-reachable content has been physically erased or is no longer reachable. The service must not promise instant physical deletion where the supported lifecycle has not completed.

After the supported deletion or account-closure lifecycle completes, a limited permission may continue only for isolated backups aging out under applicable retention, security or abuse evidence, or legal obligations or holds, and only for as long as needed for that purpose. Retained content must be minimized, access-restricted, unavailable for normal service use, and deleted under a finite purpose-specific schedule or when the applicable legal obligation or hold ends. This baseline invents no retention duration.

This baseline does not authorize continued content use to preserve an existing creator-player purchase or marketplace entitlement. Any creator-player paid-purchase or marketplace-entitlement continuity requires a separate marketplace and settlement decision and separate applicable terms. This restriction does not alter V1 hosting-plan or platform-subscription billing under [ADR 0143](design/architecture/decisions/adr-0143-stripe-v1-hosting-billing-and-deferred-creator-monetization.md).

## Changed terms, decline, and existing content

A change is material when it is reasonably likely to affect a creator's decision to accept or the creator's legal, economic, content, or exit position. This includes a different operator legal identity and substantive changes to creator-content rights, the hosting grant, price or payment obligations, renewal, suspension or termination, deletion or retention, dispute rights, governing law, or remedies. Formatting, typographical, support-address, and genuinely non-substantive clarification changes may be nonmaterial, but changing the contracting operator is always material. Materiality is conservatively classified and audited; reasonable uncertainty is resolved by treating the change as material and seeking affirmative reacceptance.

The operator must give reasonable advance notice of a material change. A 30-day floor, timing relative to renewal, early acceptance behavior, automatic-renewal choreography, and exact transition cutoff are candidate pre-launch defaults only, to be finalized with the complete subscription terms and New Zealand legal review; they are not operative commitments in this baseline. Notice identifies the current and proposed terms, operator, effective date, plain-language change summary, and available accept, decline, cancellation, refund, and content-lifecycle choices. For an individual party it should appear as a durable in-account notice and be sent to that person's verified contact when available. Organization-recipient and detailed evidence mechanics apply only if organization hosting is supported. The old terms remain operative and ordinary authoring may continue during the notice period, with a prominent warning about the effective-date freeze described below. Early acceptance and renewal handling must be specified in the complete accepted terms without deeming acceptance or applying the change retroactively.

Shorter notice is permitted only to the extent required by law or when an immediate safety or security necessity makes the normal period impracticable. Existing moderation, quarantine, suspension, and security powers may be exercised immediately on their existing basis. Urgency does not confer broader content, intellectual-property, price, monetization, transfer, promotion, or similar rights, and never deems acceptance. Where a new affirmative grant remains necessary, the service must freeze covered writes and offer the exit path instead.

An explicit decline during the notice period records nonacceptance for the proposed generation but does not end the previously accepted terms or freeze writes early. At the disclosed effective date, if the creator party has declined or has not reaccepted the material generation, it has no current acceptance for that generation and the service blocks every new official-hosted creator-content upload, authoring/edit/revision, asset/template/plugin write, publication or republication, and equivalent creator-intent content-bearing side effect. An exact request already committed under the previously accepted terms may replay its stored result under [ADR 0180](design/architecture/decisions/adr-0180-account-owned-hosted-terms-acceptance-gate.md); reaccepting current terms permits future covered operations only and does not retroactively authorize a stale attempt.

Removing an organization signer does not retroactively revoke the organization's valid acceptance, but the former signer loses signer-only authority immediately. If no current authorized signer reaccepts changed terms, the organization is a nonresponding Creator Party at the effective date; tenant administration, billing ownership, payment, or the historical signer cannot substitute.

The changed terms do not apply retroactively, and continued use or subscription payment does not infer acceptance or broaden the prior licence. Previously persisted Draft/history content may remain temporarily stored and readable. An unchanged already-published or active release may continue ordinary player delivery/runtime operation and technically necessary caching, replication, backup, restart, repair, security, and moderation only under the previously accepted terms. This continuity cannot create a new creator-authored version, make changed content newly reachable, expand the authorized audience class or purpose, or authorize any new/additional right. A future operator is not an automatic transferee.

Subject to ordinary authentication, authorization, safety, and billing-safe access rules, supported read/download and bounded lifecycle-reducing operations (including unpublish, retire, removal, deletion, account-closure requests, and whatever controlled export the product supports) remain available without accepting changed terms. They must be narrowly authorized, audited, idempotent, and content-nonexpanding; they cannot carry new Creator Content, increase reachability, or become authoring bypasses. Decline/nonresponse does not itself delete content, cancel a hosting subscription, decide refunds, or change billing/entitlement state. Whole-game portability remains deferred under [ADR 0125](design/architecture/decisions/adr-0125-defer-whole-game-portability-and-external-authoring-formats.md).

Renewal must not silently create a new rights period without current Creator Party acceptance. The exact renewal decision, paid-through freeze, early-exit, refund destination, service-credit election, and transition choreography are candidate pre-launch defaults to be finalized with the complete subscription terms and New Zealand legal review. The settled terms must preserve no retroactive rights expansion, no implied acceptance, reasonable notice, no charge for unavailable prepaid service, and a finite exit/retention path. Billing-owner acceptance or payment cannot substitute for Creator Party acceptance, and billing remains separate from content or signer authority. Abuse or security action, outstanding debt, legal holds, and provider reconciliation remain separate and do not create acceptance.

Before official launch, applicable terms and lifecycle policy must disclose a finite transition window reviewed under New Zealand law. The settled terms must preserve the finite exit/retention path; numeric duration, paid-through cutoff, and detailed transition choreography are candidate defaults rather than universal requirements in this baseline. At the applicable boundary, absent current acceptance, content must leave normal service use through the supported retirement/removal/deletion lifecycle. [ADR 0050](design/architecture/decisions/adr-0050-versioned-export-retention-and-erasure-policy.md) governs minimized restricted backups, finite purpose-specific retention, and legal holds; the service must not promise instant physical erasure. Marketplace, promotion, resale, AI/model training, ownership transfer, broader rights, and self-hosted community operation remain respectively excluded or separate lanes.

## Launch review

Before official hosted launch, the operator must identify the contracting operator and each supported Creator Party type in the applicable terms, implement individual creator-party identity, acceptance and terms-version evidence, keep unsupported party forms disabled, define processor disclosures and finite purpose-specific retention schedules, complete the ADR 0050 owner/export/reader/purpose/terminal-action/backup treatment for party/signer/acceptance evidence, and obtain New Zealand legal review of the complete hosted terms and related privacy, capacity, consumer, acceptable-use, moderation, deletion, transfer, and marketplace documents. Organization support additionally requires the party, signer, offboarding, changed-terms notice, transfer, and proof boundaries above before it is advertised. Numeric notice/refund/renewal details remain candidate defaults until that review. This policy baseline does not itself establish those legal or operational conclusions or select a numeric retention duration.
