# ADR-0046: Bounded Friend Presence With Private-by-Failure Redaction

## Status

Accepted

## Implementation Status

This decision is partially implemented. Social & Groups has friend relationship and presence surfaces, but the complete target contract remains in follow-through: snapshot-bound continuation, current relationship and policy revalidation, bounded chunked bulk reads, atomic no-partial-page failure behavior, and private-by-failure redaction are not all fully implemented and proven in the current runtime. This status does not change the human-reviewed decision metadata in the Decision Record below.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `EA-2.3` Presence, discovery, and social visibility
- Affected capabilities: `AA-1.3`, `EA-2.1`, `EA-2.2`, `AA-2.1`, `SF-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `MS-SOCIAL-PRESENCE-PRIVACY`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `MS-SOCIAL-PRESENCE-PRIVACY`

## Context

FireMUD needs cross-game friend presence without turning live sessions into a globally enumerable directory or leaking activity when policy data is unavailable. The design must also keep account-profile privacy distinct from in-world visibility mechanics and the instance-local `WHO` command.

## Decision

Game Session owns raw live and recent gameplay presence. Social & Groups projects the bounded friend-presence view, using current Account Service profile policy when deciding which fields may be returned.

The view contains only mutual, accepted friends. Blocks override friendship and suppress presence. Whenever pagination can return a continuation, including when all backend reads fit in one chunk, the initial request establishes an opaque, short-lived snapshot that fixes the candidate friend subject set and deterministic friend-ordinal/account-ID order. The platform caps snapshot creation at 10,000 subjects, page size at 100 subjects, and candidates scanned per continuation at 1,000; tenant settings may narrow any cap. A request that exceeds an effective snapshot or page-size cap is rejected with no entries or continuation token. Continuation is bound to that snapshot, the authenticated viewer, filters, and page size, so no Account or Game Session request exceeds 100 account IDs and pagination cannot duplicate or skip subjects. Every continuation request must authoritatively re-read mutual accepted-friend status, both-direction block state, and the current profile policy for every candidate before disclosure. A newly blocked or non-mutual subject is omitted; a stricter or `PRIVATE` policy is redacted; a less-restrictive policy may disclose only when current relationship, block, and policy authorization permits it. It must never disclose from stale current-state data. Remaining eligible subjects retain the snapshot's deterministic friend-ordinal/account-ID order and cursor semantics.

Account authorization and relationship/block-state revalidation are request-fatal operations: failure while establishing caller authority or the accepted-friend snapshot rejects the request, and failure during continuation revalidation rejects that page with no entries or continuation token. A successful revalidation that finds a subject no longer mutual or blocked omits that subject. Profile-policy reads are a separate operation: a policy transport/chunk failure after authorization and relationship checks applies uniform private-by-failure redaction to the whole page, while a missing, malformed, unknown, legacy, denied, or `PRIVATE` policy value redacts only that subject. These rules apply by operation purpose even when backend calls are batched, and request-fatal authorization/integrity failures take precedence over policy redaction. Raw-presence transport or chunk-shape failures and snapshot-integrity failures reject the whole page with no partial entries or continuation token.

The cursor carries the candidate snapshot identity/proof, a `lastScanned` `(friendOrdinal, accountId)` pair, and a separate `lastEmitted` pair, with explicit start-of-snapshot sentinels for the first page. It is not an offset into a revalidated or filtered list. Each continuation walks the original snapshot strictly after `lastScanned` in its immutable friend-ordinal/account-ID order, re-reads current relationship, block, and policy state for each candidate, and advances `lastScanned` after every candidate examined. A candidate that remains eligible is serialized, including its redacted shape when policy requires it, and advances `lastEmitted`; a candidate that is no longer mutual or is blocked is consumed, omitted, and advances only `lastScanned`. Only currently eligible subjects are emitted. A less-restrictive current policy may disclose only under current authorization. Revalidation therefore cannot renumber, reorder, duplicate, or skip an emitted subject, and omitted candidates are not rescanned on the next continuation. If the effective scan cap is reached before the page is filled or the original snapshot is exhausted, the service returns all entries accumulated during that scan plus a continuation advanced through `lastScanned`; entries are empty only when no subject was emitted during that scan, and the cap response is not fatal. A page with no emitted subject returns no continuation only after the original snapshot is exhausted, rather than repeating an unchanged scan position or hiding later candidates. Fatal expiry, snapshot-integrity, authorization, relationship, or raw-presence transport/chunk-shape failures return no entries or continuation token. The snapshot, viewer, filters, page size, and expiry remain bound to every cursor. Snapshot expiry retains the `page-expired` error and returns no entries or continuation token.

`FRIENDS_ONLY` is the default profile policy. `PUBLIC` is a disclosure policy, not authorization: Social derives the viewer identity from authenticated caller context and authorizes the viewer for the surface and each subject before evaluating the subject's policy. The friend-list surface may return only subjects in that viewer's mutual accepted-friend snapshot; any single-subject surface must use an exact subject reference and the same viewer/subject authorization check. `PUBLIC` never permits anonymous, arbitrary-account, wildcard, search, count, presence-sorted, or global presence enumeration. `PRIVATE` exposes no presence fact: no online state, last-seen value, location, character, activity, disconnect information, or policy label. Unknown, missing, malformed, or legacy (including `HIDDEN_STAFF`) per-subject policy is treated as `PRIVATE` with the same complete redaction; unavailable policy follows the post-snapshot chunk/transport failure rule above.

World or realm names for a non-public realm are disclosed only when the viewer can independently see that realm. Friendship does not grant realm discovery.

`HIDDEN_STAFF` is removed from the player-facing policy vocabulary. It and all other legacy or unknown values fail closed as `PRIVATE`; staff concealment and game-authored invisibility are not account-profile presence policies.

Gameplay `WHO` remains a current-game-instance view. It is separate from Account profile privacy and from game-authored invisibility and perception mechanics, which must be applied by their owning gameplay policy before `WHO` renders its instance-local result. FireMUD has no current hidden-staff or observer presence mode.

Player-facing friend presence does not expose disconnect disposition. Internal operational and recovery consumers may retain that fact within their authorized service boundary.

## Consequences

- Account remains the authority for player-selected profile privacy, Game Session remains the raw-presence owner, and Social & Groups owns relationship-aware projection.
- A policy outage degrades presence to no disclosed fact instead of fabricating public or offline state.
- Mutual acceptance, block checks, realm visibility, and policy redaction must occur before results leave the Social projection boundary.
- `PUBLIC` supports future authorized social surfaces without creating a platform-wide online-player directory.
- Pagination adds client and service continuation handling but preserves bounded Account and Game Session bulk reads. An opaque snapshot is created whenever pagination can return a continuation, even when one backend chunk is sufficient. Snapshot subjects are capped at 10,000, page size at 100, and candidates scanned per continuation at 1,000; tenant settings may narrow those caps. Snapshot creation or page requests above their effective caps are rejected with no page data or continuation token. When a continuation reaches its effective candidate scan cap before filling the page or establishing exhaustion, it returns all entries accumulated during the scan plus a continuation advanced through `lastScanned`; this non-fatal cap response preserves progress, and the page is empty only when no subject was emitted. Snapshot exhaustion returns accumulated entries without a continuation, including an empty page only when no subject was emitted. Snapshot expiry, request-level Account authorization or accepted-friend-snapshot failure, raw-presence transport or chunk-shape failure, and snapshot-integrity failure return no partial page or continuation token. Ordinary per-subject policy denial remains redacted in place, while any policy chunk or transport failure uniformly redacts the entire page to avoid chunk-outcome side channels.
- Continuations re-read current relationship, block, and profile-policy state for every candidate. A newly blocked or non-mutual subject is omitted, a stricter or `PRIVATE` policy is redacted, and a less-restrictive policy may disclose only under current authorization.
- Instance-local visibility and concealment cannot be implemented by reusing cross-game profile policy.

## Alternatives Considered

### Enforce Account profile privacy inside Game Session

Rejected because it would couple the raw gameplay-presence owner to social relationships and profile projection. Game Session supplies bounded raw facts to authorized internal consumers; Social applies the friend and privacy rules.

### Maintain a Social-owned global presence registry

Rejected because it would duplicate live-presence authority and increase stale or globally enumerable state.

### Treat unavailable policy as offline or return partial private metadata

Rejected because both choices disclose or fabricate a presence fact when disclosure authority is unavailable.

### Apply profile privacy directly to `WHO`

Rejected because `WHO` is an in-world instance view whose invisibility and perception rules belong to gameplay and operator policy, not the cross-game account profile.

## Implementation and Future Proof Obligations

Future focused contract and integration proof must demonstrate that:

- only mutual accepted friends appear and either party's block suppresses the result;
- the viewer is derived from authenticated caller context, every subject passes the applicable viewer/subject authorization check, `FRIENDS_ONLY` is the persisted default, `HIDDEN_STAFF` is absent from the player-facing vocabulary, and `PUBLIC` cannot enumerate arbitrary accounts;
- `PRIVATE`, denied, and every missing, malformed, unknown, or legacy per-subject policy result disclose no presence fact or policy label; a policy chunk or transport failure, including timeout or unavailability, applies uniform private-by-failure redaction to the entire page;
- private world and realm labels are absent unless independent viewer visibility is proven;
- player responses never include disconnect disposition;
- continuation pages authoritatively re-read mutual accepted friendship, both-direction block state, and current profile policy for every candidate; a newly blocked or non-mutual subject is omitted without reordering the remaining snapshot subjects, a stricter or `PRIVATE` policy is redacted, and a less-restrictive policy may disclose only under current authorization; and
- pageable requests create an opaque snapshot whenever a continuation can be returned, including when one backend chunk is sufficient; snapshot creation is capped at 10,000 subjects, page size at 100, and candidates scanned per continuation at 1,000, with lower tenant settings allowed; exceeding a snapshot or page-size cap produces no entries or continuation token, while reaching the scan cap before page fill or exhaustion returns accumulated entries plus a continuation advanced through `lastScanned`, with an empty page only when no subject was emitted; snapshot exhaustion returns accumulated entries without a continuation, including an empty page only when no subject was emitted; snapshot-bound Account and Game Session reads remain bounded to 100 IDs per chunk, expiry is terminal with the `page-expired` error, request-level Account authorization or accepted-friend-snapshot failure and any raw-presence, chunk-shape, or snapshot-integrity failure produce no partial page or continuation token, and any policy chunk or transport failure produces uniform private-by-failure redaction for the entire page; and
- `WHO` remains instance-local while applicable game-authored invisibility and perception policy is enforced independently before rendering.

## Canonical Design Links

- [System Architecture Overview](../system-architecture-overview.md)
- [Account Service API Contracts](../microservices/account-service/api-contracts.md)
- [Game Session Service API Contracts](../microservices/game-session-service/api-contracts.md)
- [Social & Groups Service API Contracts](../microservices/social-groups-service/api-contracts.md)

## Reversibility and Revisit Triggers

The projection fields and audiences can be narrowed without changing raw-presence ownership. Revisit this decision if FireMUD introduces an explicitly consented public player directory, non-friend presence audiences, organization-managed privacy, relationship graphs too large for bounded paginated reads, or a dedicated presence service that can assume raw-presence authority without duplicating Game Session state.
