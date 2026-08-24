# ADR 0148: Social Relationship Authority and Entity-Owned Value

## Status

Accepted

## Implementation Status

This decision is not implemented. Social relationship ownership, typed membership subjects, Entity-owned transferable value, and cross-service proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MS-SOCIAL-RELATIONSHIP-AUTHORITY`
- Decision date: 2026-07-20
- Decision key: `MS-SOCIAL-RELATIONSHIP-AUTHORITY`
- Primary capability: `EA-2.2`
- Affected capabilities: `EA-2.1`, `AA-1.3`, `PO-1.2`, `SF-2.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of relationship scope and lifecycle, group membership identity, operator authority, presence and profile boundaries, guild value ownership, mail attachments, and gameplay-specific mail rules

## Context

Social & Groups is the natural owner for friend and block relationships, guild and group metadata, membership and roles, alliances, social audience resolution, and social message or mail history. Creating a separate social-graph service would add another distributed boundary without evidence that this authority needs an independent availability, scale, or security envelope.

The existing contracts nevertheless blur several independent authorities. The supposedly account-global friend table and APIs still require `tenantId`, so they do not represent a genuinely global account relationship. Guild membership is universally account-based even though some games need membership to identify a character within one playable-state namespace. Account profile visibility and Game Session presence are also inputs to the Social projection, not state that Social may originate.

The largest ownership conflict is value. The current Social schema and REST surface let Social create `itemName` and `quantity` rows in a guild-storage table. That is a second item and inventory authority alongside Entity Management. Mail currently has no attachment contract, but adding attachment value directly to the Social mail record would create the same conflict.

## Decision

### One Social Relationship and Group Owner

Retain one Social & Groups service. It owns:

- friend and block relationship records and their explicit lifecycle;
- guild and group definitions, membership subject types, membership and role policy, and alliances;
- social audience resolution and the social ACLs used for group communication and shared resources;
- messaging and mail envelopes, delivery state, and history where the product promises durability; and
- its side of moderation integration under [ADR 0146](./adr-0146-owner-local-moderation-enforcement.md).

Social does not become an account identity, gameplay presence, transport, item, currency, or container authority merely because those facts are needed to compute a social view.

### Relationship Scope and Lifecycle

An account-global relationship is a genuinely tenant-free account-pair record. Friendship uses an explicit `REQUESTED`, `ACCEPTED`, `REJECTED`, and `REMOVED` lifecycle with requester and transition identity; one account cannot unilaterally create an accepted friendship. Blocking is an explicit directional relationship state or record and takes precedence over friendship visibility and interaction while active.

Tenant-local relationships are separate tenant-qualified records. They are not projections of the global record with a synthetic tenant and cannot accidentally grant cross-tenant visibility. A product surface may deliberately show an accepted global friendship inside a game, but the global and tenant-local authorities remain distinguishable.

Ordinary users control requests, acceptance, rejection, removal, and blocking. Tenant operators receive only explicitly documented moderation or support actions. They may restrict interaction, hide abusive content, or repair demonstrably corrupt state when policy permits, but they cannot fabricate friendship or acceptance on a user's behalf.

### Typed Group Membership Subjects

Every guild or group definition declares one membership-subject type:

- `ACCOUNT`, identified by `accountId`; or
- `CHARACTER`, identified by `{tenantId, playableStateNamespaceId, characterId}`.

Membership operations, uniqueness constraints, roles, ownership transfer, audience resolution, and ACL evaluation use that declared type consistently. `gameInstanceId` is not a durable character membership scope because live instance replacement can preserve the same playable-state namespace. A group cannot silently mix account and character members; a future mixed-subject group requires an explicit contract rather than ambiguous identifiers.

### Input Authorities Remain Separate

Account owns account identity, account status, and profile-visibility policy. Game Session owns raw current and recent gameplay presence and connected transports. Social may read those facts and apply its own accepted-relationship and block state to produce a bounded player-facing social projection, but it neither rewrites the source facts nor treats a cached projection as their authority.

### Entity Owns Containers and Transferable Value

Entity Management owns real guild containers, items, inventory containment, currency, and mail attachments. Social may own the guild ACL and a typed binding from a guild to an Entity-owned container. It may authorize a caller against the social policy and ask Entity to perform a container operation, but it cannot mint or persist independent `itemName + quantity` value rows.

Attaching an item or currency to mail uses an owner-controlled, idempotent transfer or escrow protocol. Entity Management atomically reserves or transfers item ownership and containment; as the currency owner, it does the equivalent for currency. Social records only stable attachment or escrow references and mail lifecycle state. Sending, claiming, returning, expiring, retrying, or deleting mail must use the owner-controlled idempotency boundary and must not duplicate, lose, or recreate value after an ambiguous handoff.

Ordinary account or social mail enters Social directly. A game may define world-specific delivery requirements, costs, interception, or other gameplay effects; those rules enter Game Logic under [ADR 0147](./adr-0147-explicit-communication-classes-and-owner-delivery.md), then hand an authorized semantic result to the applicable Social and value owners. Tenant-authored gameplay rules do not gain access to ordinary private platform mail.

## Consequences

- FireMUD keeps one cohesive service for relationships, groups, social audience policy, and social message history.
- Account-global friendships become usable across tenants without a fake tenant scope, while tenant-local relationships remain isolated.
- Games can choose account-based guilds or character-based guilds without making a transient game instance part of durable membership identity.
- Social views require bounded reads from Account and Game Session, but those dependencies do not transfer source authority.
- Guild storage and attached mail value require cross-owner transfer contracts instead of a simple Social-local row write.
- The value flow is more complex, but it prevents two services from independently claiming to own or mint the same item or currency.
- Operators can moderate abuse without impersonating users in consensual relationship transitions.

## Alternatives Considered

### Add a Separate Social-Graph Service

Move friend, block, group, and alliance records into a new graph-focused service while Social & Groups handles communication and mail. This could become useful if relationship traversal develops distinct storage or scaling needs, but it creates another authority handoff and operational component without current evidence. One Social & Groups owner is retained.

### Make Every Relationship and Membership Tenant-Scoped

Require a tenant for all friendship and group records. This simplifies schema conventions but cannot express the accepted product behavior of account relationships that follow a player across games. It also encourages platform relationships to be copied among tenants. Genuine account-global records and distinct tenant-local records are clearer.

### Make Every Guild Membership Account-Based

Treat guilds as account communities and let each game choose which character represents the account at runtime. This is appropriate for some products but cannot model games where characters in separate playable-state namespaces must have independent affiliations. The group declares its subject type instead.

### Let Social Own Guild Inventory and Mail Attachments

Persist convenient item names, quantities, currency amounts, and attachment state beside guild or mail records. This avoids a cross-service handoff but creates a second value ledger with no authoritative Entity containment or currency transfer. It permits duplication after retries and cannot prove that the sender owned the value. It is rejected.

### Route Every Mail Operation Through Game Logic

Treat all mail as an in-world action so game rules can always participate. This exposes private platform mail to gameplay availability and potentially tenant-authored code. Only deliberately world-specific mail semantics enter Game Logic; ordinary social mail remains independent.

## Implementation and Proof Obligations

The current implementation is materially partial and drifted. Account friend records and all friend APIs still carry `tenantId`; the add operation does not prove bilateral request and acceptance; guild rows and membership APIs are account-only; and authorization primarily proves tenant or caller-account matching rather than complete owner/member role policy. Social also persists `guild_storage_items(item_name, quantity)` directly. The current mail schema has envelopes but no owner-controlled attachment or escrow contract.

Implementation must converge the schemas and contracts directly because FireMUD is pre-v1. It must distinguish tenant-free account pairs from tenant-qualified relationships, encode legal lifecycle transitions and blocking precedence, bind each group to one typed membership subject, enforce role and ownership policy, replace Social-owned value rows with Entity container bindings, and add owner-controlled attachment escrow only when attached value is implemented.

Proof must cover bilateral friendship transitions; directional blocks; duplicate, crossed, reordered, and retried requests; removal and re-request; global versus tenant-local isolation; operator inability to fabricate consent; account- and character-member uniqueness; namespace isolation; ownership and role transitions; audience resolution; source-authority outage and privacy-default behavior; and cross-tenant authorization.

Value proof must cover caller authorization plus Entity ownership, concurrent deposits and withdrawals, retries after every ambiguous handoff, sender or recipient disconnect, mail cancellation, claim, return, expiry, deletion, and owner restart without duplication or loss. World-specific mail tests must prove Game Logic participation only for that declared class and prove ordinary private mail is unavailable to tenant-authored scripts.

Select validation and runtime evidence according to [`validation and runtime proof`](../../developer-workflows/validation-and-runtime-proof.md); record actual execution results in PR/CI evidence or implementation-tracking documents, not in this ADR.

## Reversibility and Revisit Triggers

Relationship schemas, group types, ACL representation, projection caches, and transfer protocol details may evolve while preserving their named owner boundaries. A separate social-graph service should be reconsidered only if measured graph scale, query shape, deployment independence, or a distinct security boundary cannot be served by the existing Social & Groups service. Mixed-subject groups require a deliberate product and authorization decision. Social must not acquire independent item, currency, or attachment value authority as a shortcut.

## Required Documentation Alignment

- [`design/architecture/microservices/social-groups-service/README.md`](../microservices/social-groups-service/README.md)
- [`design/architecture/microservices/social-groups-service/runtime-and-data.md`](../microservices/social-groups-service/runtime-and-data.md)
- [`design/architecture/microservices/social-groups-service/api-contracts.md`](../microservices/social-groups-service/api-contracts.md)
- [`design/architecture/microservices/entity-management-service/README.md`](../microservices/entity-management-service/README.md)
- [`design/architecture/microservices/entity-management-service/runtime-and-data.md`](../microservices/entity-management-service/runtime-and-data.md)
- [`design/architecture/microservices/account-service/README.md`](../microservices/account-service/README.md)
- [`design/architecture/microservices/game-session-service/README.md`](../microservices/game-session-service/README.md)
- [`design/architecture/microservices/game-session-service/runtime-and-data.md`](../microservices/game-session-service/runtime-and-data.md)
