# ADR 0180: Account-Owned Hosted-Terms Acceptance Gate

## Status

Accepted

## Implementation Status

This decision is target state only. FireMUD currently has no hosted-creator-party registry, immutable hosted-terms catalog, individual or organization acceptance API/storage, signer-authority evidence, creator acceptance UI, Game Design hosted-content gate, durable party/evidence binding, creator-party transfer workflow, route-matrix coverage/proof for the gate, material-change reacceptance invalidation, or focused tests for these behaviors. The [Hosted Content Terms](../../../HOSTED_CONTENT_TERMS.md) file remains a pre-launch policy baseline and is not an operative contract. Until the organization path is implemented and legally reviewed, official hosting may enable only individuals aged 18 years or older acting in their own capacity and must reject organization acceptance rather than infer it.

## Decision Record

- Human review status: Completed
- Human review date: 2026-08-25
- Human review disposition: Accepted
- Review source: `HOSTED-TERMS-01`
- Decision date: 2026-08-24
- Decision key: `HOSTED-TERMS-01`
- Primary capability: `AA-1.3`
- Affected capabilities: `AA-1.5`, `AR-1.1`, `AR-1.4`, `AR-1.5`, `EA-3.2`, `PO-1.3`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: explicit human approval on 2026-08-24 and direct human refinement on 2026-08-25

## Context

Official hosted creator content needs a reliable boundary between a policy baseline and an accepted, versioned hosted-service agreement. A repository document cannot identify the contracting operator or creator party, prove which text an authorized person accepted, or establish that a later creator-intent write is still covered by the current terms. A login account, tenant role, payer, and technical owner field answer different questions and cannot be treated as the legal party or proof of authority. The Account and Game Design services therefore need one authority direction: Account owns creator-party identity, signer authority, the tenant's current creator-party association and transfer authority, and legal/compliance evidence, while Game Design owns creator content, its persistence and publication workflows, and only a local binding to Account's exact party/evidence/generation for each covered operation.

The gate applies only to FireMUD's official hosted deployments. A community self-hosted operation does not use this official-hosted gate. Hosting does not turn creator content into repository material and does not grant marketplace, promotion, resale, AI/model-training, or ownership-transfer rights. FireDevOps is a project brand, not a legal entity; Benjamin James Hook remains the current operator unless and until another operator is expressly identified in applicable terms. A future FireDevOps company is a separate operator and requires the existing material-change and affirmative-reacceptance process; formation or branding alone transfers no agreement, content right, billing, membership, or marketplace right.

## Decision

### Account owns the terms catalog and acceptance evidence

Account is the sole authority for the immutable published catalog of hosted-terms document versions, the exact identified operator legal party for each version, and append-only creator-party acceptance evidence. Game Design does not maintain a second catalog or infer acceptance from a local flag, tenant row, UI state, or copied terms text.

Each published terms version has an immutable version identifier and a SHA-256 digest of the exact document bytes. Account also maintains a monotonic material-acceptance generation. Any material change classified under [ADR 0181](adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md), including operator identity, creator rights, hosting grant, price/payment, renewal, suspension/termination, deletion/retention, dispute rights, governing law, or remedies, must advance that generation. Only a nonmaterial change may leave the generation unchanged. Materiality is an audited operator/legal classification; Game Design does not infer it from a text diff or from content mutations.

For the exact hosted-terms scope, Account evaluates the scope identity, current terms version and digest, material-acceptance generation, and operator legal party together. A future operator is not an automatic transferee of an old operator's acceptance. Operator cutover must not rely on acceptance of the old operator's terms and remains subject to the applicable legal and lifecycle decision.

### Acceptance is a creator-party-wide affirmative act

A hosted creator party is the counterparty that owns or controls the rights needed for Creator Content and grants the limited hosted permission. It is represented by one immutable Account-owned `creatorPartyId` and is exactly one of:

- an individual aged 18 years or older acting in their own capacity, bound to their authenticated Account identity; a sole trader or trading name remains that natural person rather than becoming a separate legal entity; or
- an identified legal entity capable of contracting, with its exact legal name, jurisdiction, registration identifier where applicable, legal-identity version, and verified organizational contact recorded under the legally reviewed minimization and verification policy.

A brand, project name, informal team, or unincorporated group is not accepted as a legal entity merely because it has a display name or tenant. Joint, partnership, trust, minor, guardian, or other creator-party forms remain unsupported until their capacity and authority model is separately reviewed. For the official-hosted product, an individual creator or organization signer must be aged 18 years or older and otherwise have legal capacity to enter or accept the applicable hosted terms. This is a product eligibility rule, not a claim that New Zealand law universally defines adulthood or contractual capacity at 18. Official-hosted creator roles for minors remain disabled until a separate guardian, child-safety, privacy, and content-rights decision is accepted; community self-hosting is unaffected.

Acceptance is party-wide for one exact creator party, operator legal party, and hosted-terms scope; it is not tenant-specific. One party may rely on the same current acceptance across its linked tenants, while each tenant retains its own exact `creatorPartyId` binding. The acceptance record immutably records at least:

- the creator-party ID, party type, exact legal identity version, and legally required identity fields;
- the accepting signer account ID, derived from authenticated Account context;
- for an organization, the signer's stated capacity and the current Account-owned signer-authority evidence ID and generation;
- the immutable terms version identifier and SHA-256 document digest;
- the immutable hosted-terms scope identifier;
- the exact operator legal identity;
- the material-acceptance generation;
- the server-recorded acceptance timestamp; and
- a stable acceptance evidence ID.

Acceptance requires an explicit affirmative Account-owned action after the creator party, applicable operator, and terms version have been shown to the signer. Caller-supplied party, account, authority, or timestamp is not authority. An individual accepts only for their own bound party. An organization accepts only through an authenticated signer aged 18 years or older whose distinct, current authority record has passed the legally approved verification boundary; `tenantAdmin`, `designer`, organization membership/administration, billing ownership, payment, and possession of a company email address do not imply that authority.

A valid organization acceptance binds the organization as Creator Party; the signer does not become the Creator Party merely by signing. Later signer removal or account offboarding does not retroactively invalidate that acceptance, but it immediately prevents the former signer from accepting a new generation or exercising signer-only party actions. Reacceptance requires a currently authorized signer. The exact initial and renewal verification method may be manual or automated, but it must be legally reviewed, auditable, fail closed when authority is missing, expired, revoked, stale, ambiguous, or unavailable, and be implemented before organization contracting is advertised.

Account may expose a bounded opaque evaluation or evidence reference for a downstream operation. The reference must resolve to the immutable evidence above and its currentness checks; this ADR does not mandate a distributed transaction mechanism.

### Official hosted creator writes fail closed without current evidence

On an official hosted deployment, the acceptance gate applies before the first persisted creator content, including a Draft, and thereafter to every new creator-intent content-bearing mutation or publish operation. The covered class includes authoring and revision writes, assets, templates, plugins, publication, and equivalent future content-bearing writes; it is not limited to today's endpoint list.

The authoritative Game Design service boundary calls or consumes Account authority immediately before accepting the operation. Each committed covered creator-intent operation records the tenant ID, exact creator-party ID, acceptance evidence ID, and material-acceptance generation used for that operation. The acting account must still have the applicable current tenant role, but that operational role neither substitutes for party acceptance nor makes the actor a legal signer. A missing, stale, mismatched, ambiguous, or unavailable party, acceptance, or tenant-authority result fails closed before the new side effect. The evidence is a compliance binding, not a second terms or party catalog in Game Design.

Account authority and the Game Design commit are one fail-closed boundary: for each covered mutation, the exact Account party, acceptance-evidence, and material-acceptance-generation authorization must remain valid through the Game Design commit linearization point. A preflight read alone is insufficient. The implementation must use a commit-bound Account authorization/fence or equivalent compare-and-set validation, without this ADR selecting a transaction, lease, fence, or other mechanism. If authority is stale, mismatched, unavailable, or uncertain at that boundary, Game Design aborts its local transaction; no creator mutation or staged artifact becomes authoritative or externally reachable. Any staged bytes are cleanup/quarantine state, not success.

Gateway and UI checks are convenience checks only. They may improve feedback but cannot authorize a mutation or replace the Game Design-to-Account boundary. Self-hosted community operation remains outside this official-hosted gate.

### Collaborators act for the creator party without becoming signers

An authorized `designer` or `tenantAdmin` may submit content for a tenant only on behalf of its current creator party. The party represents that it owns or controls every permission needed for the content, including collaborator and third-party contributions. A tenant or organization role does not transfer copyright, make the collaborator a contracting party, or let the platform infer that the party owns content. The platform's technical party binding and evidence do not adjudicate an external ownership dispute.

Requiring every collaborator to accept the party's hosted terms is not the legal authority model. Game Design instead proves the actor's current tenant permission and the tenant creator party's current Account acceptance as separate conjuncts. A collaborator who lacks authority to contribute content remains a rights problem for the party and may be subject to removal, dispute, or moderation handling under the applicable terms.

### Creator-party transfer is explicit and separate

Changing a tenant from a personal creator party to a company or between any two parties is a distinct, audited creator-party transfer. It requires current source-party authority where available, exact target-party identification, a currently authorized target signer, target-party acceptance of the current operator and terms, and a recorded lawful basis showing that the target owns or controls the rights needed for continued hosting. Historical party, signer, acceptance, and content-operation evidence remains immutable.

The transfer changes FireMUD's contracting/content-control binding only after its guarded workflow completes. It does not itself assign copyright, decide an ownership dispute, change tenant membership, transfer billing ownership or payment instruments, or make the target party an automatic successor to any separate marketplace agreement. Billing-owner transfer remains a separate Account subscription operation. An ambiguous, disputed, one-sided, or unavailable-source case fails closed for ordinary self-service and requires manual/legal handling; no tenant role, display-name change, or billing transfer may stand in for it.

### Replay is not a new grant

A previously committed exact request may replay its durably stored result under the acceptance evidence captured for that committed operation. Replay is not a new acceptance grant and does not require the current evidence to be treated as though it authorized the old side effect. If the original attempt is uncertain or not durably known to be committed, the service must first reconcile the original stable operation identity and obtain durable proof of no commit; it must not blindly redispatch or create a second mutation while the outcome is indeterminate. Only a permitted fresh attempt after that proof performs a fresh commit-bound currentness check before any side effect.

The same request identity with a changed normalized payload returns the target application outcome `IDEMPOTENCY_CONFLICT` before mutation. Implementations must preserve the operation identity, normalized-payload equality, committed result, and captured acceptance evidence needed to distinguish a safe exact replay from an uncertain new attempt.

### Existing lifecycle and legal boundaries remain separate

Existing read-only delivery, repair, retention, decline/withdrawal consequences, and operator-transfer/cutover handling remain governed by the previously accepted terms/lifecycle design or by explicit deferred legal/lifecycle decisions. Behavior of already-hosted content after changed terms is now defined by the supplemental [ADR 0181](./adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md), which does not supersede this acceptance gate. This ADR does not otherwise invent deletion, continuity, or marketplace behavior.

Acceptance evidence is personal/compliance data governed by [ADR 0050](./adr-0050-versioned-export-retention-and-erasure-policy.md). Before launch, the owning design must declare its owner, export treatment, allowed readers, purpose, terminal action, backup treatment, and a finite purpose-specific retention schedule. No numeric duration is selected here. Legal holds are bounded to their lawful lifetime, and exact retention plus New Zealand legal review remain launch gates.

The hosted-content permission remains narrow: it does not broaden content rights, repository licensing, commerce, marketplace, promotion, resale, AI/model-training, or copyright/ownership-transfer rights. The billing owner may differ from the creator party and gains no content, tenant, or signer authority from paying. [HOSTED_CONTENT_TERMS.md](../../../HOSTED_CONTENT_TERMS.md) remains a policy baseline to be incorporated into complete accepted hosted terms, not a contract merely because it is present in the repository.

## Consequences

- Account has one global authority for creator parties, signer authority, terms versions, operator identity, acceptance currentness, and append-only evidence.
- A creator party accepts once for the exact operator and hosted-terms scope, while every Game Design mutation remains tenant-scoped and records the party and evidence used.
- Individual-only launch remains permitted, but organization contracting fails closed and must not be advertised until party, signer, transfer, lifecycle, route, and focused proof are implemented and legally reviewed.
- Tenant roles, organization administration, billing ownership, content rights, and legal signing authority remain separate.
- Material terms or operator changes can require reacceptance without relying on Game Design's local copies or on a tenant-specific acceptance flag.
- Official hosted content writes fail closed when Account authority is unavailable or evidence is not current; self-hosted operation is unaffected by this gate.
- Exact request replay remains safe without turning a historical committed result into a new grant.
- Changed-term decline, prior-terms continuity, lifecycle-reducing access, finite transition, and independent billing consequences are defined separately by [ADR 0181](./adr-0181-changed-hosted-terms-decline-and-existing-content-continuity.md).
- The acceptance evidence is compliance data with explicit ADR 0050 treatment and launch-specific retention/legal obligations.
- The current policy, Account contracts, Game Design gate, route inventory, and focused proof must be implemented before official hosted launch can rely on this decision.

## Alternatives Considered

### Let Game Design own a copied terms catalog

Rejected because a second catalog can diverge from Account's operator identity, digest, generation, and acceptance evidence. Game Design consumes Account authority and records only its local evidence binding.

### Store acceptance per tenant or trust the tenant owner field

Rejected because the legal acceptance is by an identified creator party for one operator and hosted-terms scope. Tenant identity, display metadata, and caller-supplied owner fields do not establish party identity, content rights, or signer authority.

### Treat a tenant administrator, payer, or company email as an organization signer

Rejected because operational permission, payment authority, email-domain possession, and legal authority to bind an entity are different facts. Organization acceptance requires a distinct current signer-authority record and evidence.

### Represent an organization as a shared or synthetic login account

Rejected because authentication belongs to accountable human accounts. A first-class creator party with named signer evidence preserves actor attribution, signer changes, and party continuity without sharing credentials or pretending a company is a human.

### Require every collaborator to accept as the creator party

Rejected because collaborators act under tenant permission while the identified creator party supplies the rights representation and hosting grant. Individual collaborator acceptance would still not establish that each contributor owns the whole tenant or can bind the organization.

### Infer personal-to-company transfer from membership or billing

Rejected because changing administrators or payers neither assigns content rights nor substitutes a new contracting party. Party transfer requires its own source/target authority, current acceptance, rights-basis evidence, and immutable history.

### Check acceptance only at publication or in Gateway/UI

Rejected because Drafts and other persisted creator-intent writes can create hosted content before publication, and edge checks can be bypassed or become stale. Game Design is the authoritative mutation boundary; Gateway/UI checks are convenience only.

### Treat terms text presence or a caller timestamp as acceptance

Rejected because repository presence, copied text, caller identity, and caller timestamps do not prove affirmative acceptance of an identified operator's immutable document version.

### Require a distributed transaction in this ADR

Rejected as unnecessarily prescriptive. The required outcome is an Account-authorized, evidence-bound Game Design operation with safe replay and fail-closed uncertainty; a bounded opaque evaluation/reference or another implementation may satisfy that contract.

## Implementation and Proof Obligations

Before official hosted launch, implement and prove:

- Account's immutable terms-version catalog, SHA-256 digests, exact operator legal identity, monotonic material-acceptance generation, explicit affirmative acceptance action, and append-only evidence record;
- Account-owned individual creator-party records, age-18-or-older/capacity gating, approved minimization/verification, party-wide operator/scope semantics, individual self-acceptance, and server-recorded evidence with no caller authority for those facts;
- the Game Design gate before the first persisted Draft or other creator content and before every creator-intent authoring, revision, asset, template, plugin, and publication mutation;
- durable tenant/creator-party/evidence-ID/generation binding for each committed Game Design mutation, separately conjoined with the acting account's current tenant authorization;
- exact-request replay under captured evidence, changed normalized payload conflict, and current-gate enforcement when the original commit is not durably known;
- material operator/terms changes that advance generation and require reacceptance, including operator cutover without old-operator acceptance;
- unsupported party types and creator-party transfer operations remaining disabled and unadvertised, with missing, stale, mismatched, or ambiguous party/authority evidence rejected;
- route-matrix inventory and focused proof for all covered mutation classes, with no assumption that the current endpoint list is exhaustive; and
- ADR 0050 ownership, export, reader, purpose, terminal-action, backup, finite-retention, legal-hold, and New Zealand legal-review launch gates for acceptance evidence.

Before organization hosting is advertised or enabled, additionally implement and prove:

- Account-owned legal-entity creator-party records, approved identity minimization/verification, signer-authority generations, revocation/offboarding, and verified organization acceptance;
- persistence of valid organization acceptance after signer removal, rejection of tenant-role, organization-administrator, billing-owner, or former-signer substitutes, and a current-authorized-signer requirement for later acceptance; and
- explicit creator-party transfer with source and target authority, target current acceptance, rights-basis evidence, immutable history, separate billing-owner treatment, and fail-closed disputed or ambiguous cases.

Current proof is absent for all of these gates. Existing policy wording, creator APIs, asset storage, publication workflows, or generic account authentication must not be reported as implementation of this decision.

## Reversibility and Revisit Triggers

Terms document wording, API names, evidence-reference shape, and storage representation may evolve while preserving Account creator-party and signer authority, immutable version/digest identity, generation currentness, explicit affirmative acceptance, Game Design fail-closed enforcement, exact replay semantics, explicit party transfer, and narrow hosted rights. Revisit this decision for a joint, partnership, trust, minor/guardian, or other creator-party form; a different operator-transfer model; organization-owned billing wallets; a supported self-hosted commercial lane; or any broadened content/commerce/model-use right. Each requires explicit legal and architecture review.

## Required Documentation Alignment

- [Account Service API Contracts](../microservices/account-service/api-contracts.md)
- [Account Service Runtime and Data](../microservices/account-service/runtime-and-data.md)
- [Game Design Service API Contracts](../microservices/game-design-service/api-contracts.md)
- [Game Design Service](../microservices/game-design-service/README.md)
- [Asset Storage Setup](../microservices/game-design-service/asset-storage.md)
- [Product Requirements](../../product/requirements.md)
- [Creator User Journey](../../product/user-journeys/creators.md)
- [Hosted Content Terms baseline](../../../HOSTED_CONTENT_TERMS.md)
- [Player Access and Session tracker](../../project-management/implementation-tracking/player-access-and-session.md)
- [Game Authoring, Publishing, and Activation tracker](../../project-management/implementation-tracking/game-authoring-publishing-and-activation.md)
- [Architecture authority map](../README.md#contract-authority-map)
- [Authorization route matrix](../system-architecture-authz-route-matrix.md)
- [Service responsibility matrix](../service-responsibility-matrix.md)
- [ADR 0050](./adr-0050-versioned-export-retention-and-erasure-policy.md)
