# ADR-0046: Bounded Friend Presence With Private-by-Failure Redaction

## Status

Accepted

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

The view contains only mutual, accepted friends. Blocks override friendship and suppress presence. Results are paginated so no Account or Game Session request exceeds 100 account IDs.

`FRIENDS_ONLY` is the default profile policy. `PUBLIC` may broaden disclosure on an otherwise authorized social surface, but never permits global presence enumeration. `PRIVATE` exposes no presence fact: no online state, last-seen value, location, character, activity, disconnect information, or policy label. Unknown, missing, malformed, or unavailable policy is treated as `PRIVATE` with the same complete redaction.

World or realm names for a non-public realm are disclosed only when the viewer can independently see that realm. Friendship does not grant realm discovery.

`HIDDEN_STAFF` is removed. Staff concealment and game-authored invisibility are not account-profile presence policies.

Gameplay `WHO` remains a current-game-instance view. It is separate from Account profile privacy and from game-authored invisibility and perception mechanics, which must be applied by their owning gameplay policy before `WHO` renders its instance-local result. FireMUD has no current hidden-staff or observer presence mode.

Player-facing friend presence does not expose disconnect disposition. Internal operational and recovery consumers may retain that fact within their authorized service boundary.

## Consequences

- Account remains the authority for player-selected profile privacy, Game Session remains the raw-presence owner, and Social & Groups owns relationship-aware projection.
- A policy outage degrades presence to no disclosed fact instead of fabricating public or offline state.
- Mutual acceptance, block checks, realm visibility, and policy redaction must occur before results leave the Social projection boundary.
- `PUBLIC` supports future authorized social surfaces without creating a platform-wide online-player directory.
- Pagination adds client and service continuation handling but preserves bounded Account and Game Session bulk reads.
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

## Implementation and Proof Obligations

Focused contract and integration proof must demonstrate that:

- only mutual accepted friends appear and either party's block suppresses the result;
- `FRIENDS_ONLY` is the persisted default, `HIDDEN_STAFF` is absent, and `PUBLIC` cannot enumerate arbitrary accounts;
- `PRIVATE` and every missing, malformed, timeout, or unavailable policy result disclose no presence fact or policy label;
- private world and realm labels are absent unless independent viewer visibility is proven;
- player responses never include disconnect disposition;
- Account and Game Session reads remain bounded to 100 IDs per page and continuation does not duplicate or skip entries; and
- `WHO` remains instance-local while applicable game-authored invisibility and perception policy is enforced independently before rendering.

## Reversibility and Revisit Triggers

The projection fields and audiences can be narrowed without changing raw-presence ownership. Revisit this decision if FireMUD introduces an explicitly consented public player directory, non-friend presence audiences, organization-managed privacy, relationship graphs too large for bounded paginated reads, or a dedicated presence service that can assume raw-presence authority without duplicating Game Session state.
