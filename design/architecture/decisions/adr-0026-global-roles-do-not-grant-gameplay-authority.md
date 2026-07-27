# ADR 0026: Global Roles Do Not Grant Gameplay Authority

## Status

Accepted

## Implementation Status

The accepted separation of global control-plane roles from gameplay authority is target state. Current global-role presence classification still has drift, and the regression test proving a normally joined global-role account remains an ordinary player is missing. No runtime completion is claimed by this ADR.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.2` Tenant membership, invitations, and player roles
- Affected capabilities: `AA-2.1`, `PO-1.1`, `PO-1.2`, `EA-3.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `AUTH-07`

## Context

FireMUD has global `platformAdmin`, `support`, and `billingAdmin` roles for cross-tenant control-plane work. Those roles express platform operating responsibility, not tenant consent, player identity, or in-world authority. Existing target-state admission rules already prevent global roles from bypassing caller-bound gameplay membership, but current runtime presence classification can still translate a global `platformAdmin` role into an in-game `ADMIN` actor after the account joins normally. That leaks control-plane privilege into gameplay and weakens tenant autonomy.

Support impersonation or hidden live observation could make some investigations more convenient, but neither has a current product requirement. Both would create a privileged path into private player state, conversations, and tenant experiences that would require substantial privacy, audit, notification, and abuse controls.

## Decision

### Separate Global Operations From Gameplay

- Global roles alone never grant gameplay discovery, admission, realm switching, character access, or `PLAY`. The caller must use the same explicit membership and admission flow as any other account.
- A staff account may explicitly join a public game under the ordinary open-enrollment policy. That creates an ordinary Account-owned player membership and is not a privileged operator session.
- Global roles are ignored when deriving in-game presence classification, command authority, actor capabilities, and `PlayerExecutionContext`. They must not be copied or translated into gameplay roles.
- In-game moderator, administrator, game-master, or equivalent capabilities require an explicit tenant-scoped gameplay grant owned by the tenant authorization model. A joined staff account without such a grant appears and acts as an ordinary `PLAYER`.
- Break-glass platform operations remain separate, audited control-plane actions. They may remediate platform state but must not silently create a player actor, gameplay session, or tenant-scoped gameplay grant.

### No Staff Impersonation Or Hidden Observation

- Support and operator workflows use redacted support-safe reads, logs, dashboards, reports, moderation records, and explicit control-plane operations.
- Player impersonation, live session attachment, and hidden observation are positively excluded from the current target. Implementations must not add dormant hooks or generic bypasses for those modes.
- A future concrete support requirement may reopen this decision, but it requires a new human-reviewed privacy, tenant-consent, audit, notification, and capability design rather than reuse of global roles.

## Consequences

- Tenant gameplay authority remains explicit and cannot be inherited accidentally from platform employment or operating access.
- Global staff can reproduce ordinary player behavior by joining a public game normally, but they receive no special in-world access unless the tenant explicitly grants it.
- Support investigations cannot inspect private live gameplay by impersonating a player or attaching invisibly. They depend on purpose-built diagnostics and control-plane evidence.
- Current global-role-to-presence elevation and its tests are implementation drift that must be removed.
- Operational emergency actions remain possible, but their APIs and audit records remain visibly separate from player action processing.

## Alternatives Considered

### Global Role Gameplay Bypass

Allowing `platformAdmin` or `support` to enter any game would simplify ad hoc support, but it grants cross-tenant access to private gameplay without tenant consent and makes ordinary player execution indistinguishable from platform intervention.

### Audited, Time-Bounded Impersonation

A narrowly issued impersonation session could reduce support friction, but it still exposes private state and conversations and creates a high-value credential and evidence-integrity problem. There is no current product requirement that justifies this mechanism.

### Read-Only Live Observation

Observation avoids mutation but still grants access to private live state and communications. It also requires visibility, consent, audit, and data-minimization rules, so it is not a harmless intermediate feature.

## Implementation and Proof Obligations

- Remove global-role inputs from gameplay presence and capability classification. Prove a normally joined `platformAdmin`, `support`, or `billingAdmin` account is a `PLAYER` without an explicit tenant-scoped gameplay grant.
- Prove global roles cannot bypass membership, admission, realm switching, character ownership, or gameplay actor binding.
- Prove tenant-scoped gameplay elevation works without granting unrelated global or cross-tenant authority.
- Keep support-safe reads and break-glass control-plane mutations separately classified, authorized, minimized, and audited.
- Search for and remove any hidden player impersonation, observation, or global-role gameplay bypass path rather than preserving speculative compatibility.

## Required Documentation Alignment

- [Authentication architecture](../system-architecture-authentication.md)
- [Authorization route matrix](../system-architecture-authz-route-matrix.md)
- [Logging Admin runtime and data](../microservices/logging-admin-service/runtime-and-data.md)
- [Player access and session tracker](../../project-management/implementation-tracking/player-access-and-session.md)
- [Player experience, commands, and communication tracker](../../project-management/implementation-tracking/player-experience-commands-and-communication.md)

## Reversibility and Revisit Triggers

Removing accidental elevation is straightforward before v1. Adding a later impersonation or observation product would be expensive because it requires explicit privacy, audit, tenant-consent, and client-presentation contracts. Revisit only for a demonstrated support or safety workflow that purpose-built diagnostics and control-plane actions cannot satisfy.
