# ADR 0185: Account-Owned Global-Role Administration and Bootstrap

## Status

Accepted

## Implementation Status

The approved authority boundary is target state only. Global-role grant/revoke production, the protected initial-administrator bootstrap, its Account source/event/readback implementation, and Gateway → Logging & Admin ingress are not implemented or proved. The V55 explicit-empty source is fresh-Account birth evidence only; it does not authorize mutation or retained-account enrollment. Existing activation gates remain closed.

## Decision Record

- Human review status: Completed
- Human review date: 2026-10-04
- Human review disposition: Accepted
- Review source: `AUTH-GLOBAL-ROLE-WRITER-01`
- Decision owner: FireMUD human product and architecture owner
- Consultation: Ben explicitly selected “Approve this boundary (Recommended)” on 2026-10-04, relayed by the Overseer through inbox `0f5ec31e-db81-4b31-ab9c-7e94b257cd62`

## Context

Global roles authorize only the control-plane route classes declared by Authentication and the Authorization Route Matrix. Account's V55 birth source records an explicit empty role set for eligible fresh Accounts, but retained role history, mutation authority, and authenticated recipient evidence are not supplied by that source. A present-day legacy scalar, first registrant, token claim, tenant identifier, or ordinary automated workload cannot safely establish the first global administrator or authorize subsequent role changes.

## Decision

Account is the sole authority for granting and revoking global roles and for the current role state used in authorization. Ordinary grant and revoke requests enter through the Gateway to Logging & Admin as human operator actions. The human operator must have a live `platformAdmin` role and current `privileged_control` step-up evidence. Account issues the currentness authorization used at the authoritative owner commit and revalidates it there; the committed operation has durable audit evidence. Logging & Admin is the external ingress and audit/forwarding boundary, not a second role authority.

The first `platformAdmin` is established only through a separate, one-time protected deployment bootstrap. It is not assigned by public registration, first-account status, a legacy scalar, an asserted or signed token role, or ordinary automation. The bootstrap is a distinct operational authority from ordinary grants and revocations.

Global-role actions are account-global actions with an explicit global-account target scope. They cannot use the existing non-moderation `tenant_scope` branch or treat a tenant ID, tenant role, or target-tenant generation as a substitute. Unattended automation cannot grant or revoke global roles.

This decision selects the authority and ingress boundary only. It does not select an HTTP route, protobuf method, action-family schema, mutation identity or digest grammar, exact bootstrap artifact or recovery protocol, durable event schema, source-counter composition, or deployment activation procedure. Those details require canonical owner contracts and focused proof before a route or mutation becomes available. Until then, global-role mutation is target-only and fail closed.

## Consequences

- Authentication owns the normative role-administration policy and its authorization consequences.
- Account owns role persistence, the mutation/currentness decision at commit, durable audit evidence, and authoritative readback. Role-source version, Account authority generation, issuance fence, and event-stream checkpoint remain distinct evidence and must not be inferred from each other.
- Logging & Admin owns only the human external ingress and its local durable intent/audit and forwarding consequences under ADRs 0047 and 0048.
- The Authorization Route Matrix records target-only denial and an explicit global-account action scope. It does not create a runtime route or enable global-role administration.
- The protected first-administrator bootstrap, source/event contract, authenticated capture/readback, retry/recovery behavior, migration/enrollment policy for retained Accounts, and end-to-end proof remain implementation prerequisites.

## Alternatives Considered

### Allow the first registrant or an existing scalar to become administrator

Rejected because public registration and historical role fields do not prove an authorized administrative designation or a trustworthy retained grant history.

### Permit tenant-scoped or ordinary automated role assignment

Rejected because tenant authority does not imply platform-global authority, and the current automation authorization path is tenant-scoped without a human operator or global-role step-up.

### Enable mutations before owner-currentness and audit are committed

Rejected because an ingress decision or preflight read cannot remain authoritative through Account's mutation commit or provide durable attribution.

## Reversibility and Revisit Triggers

The route and storage details remain intentionally unselected and can be specified under this accepted authority boundary. Revisit this decision only if the human owner changes who may administer global roles, the initial-administrator trust boundary, or the required human-only step-up policy.
