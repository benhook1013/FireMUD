# ADR 0038: Explicit JWT Profiles and mTLS Workload Identity

## Status

Accepted

## Implementation Status

Explicit JWT profiles and mTLS workload identity remain target state and are not fully implemented or proved. Current shared-runtime tracking records shared-HMAC signing, incomplete Account-JWKS and certificate-derived caller convergence, and missing receiver-specific profile and per-method authorization proof; those gaps remain implementation debt rather than accepted completion.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `SF-1.3` Authentication, authorization, service identity, and secret handling
- Affected capabilities: `PO-2.2`, `AA-1.3`, `AA-2.1`, `AA-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `JWT-04`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `JWT-04`

## Context

The previous JWT catalog used a generic Service JWT with an example `aud=internal` for both workload callers and private player-bearing delegation. That conflates two authorities: mTLS proves which service connected, while a delegated token proves which account authority the service is carrying. A broad internal audience also permits unnecessary lateral replay between consumers.

The target gameplay-domain delegation boundary uses concrete mTLS identity, exact per-method caller policy, and typed `PlayerExecutionContext`. The current implementation has not fully converged on that boundary, as recorded above; the token catalog must define the target without adding JWT work to ordinary gameplay commands.

## Decision

- Account issues exact registry-backed revocable profiles:
  - `control-ui` for first-party admin/creator browser control-plane sessions;
  - `player-bootstrap` for first-party gameplay discovery and connect-token bootstrap; and
  - narrowly named private player-delegation profiles only for actual control-plane consumers, beginning with `game-session-account-delegation` and exact audience `account-service` for the Game Session to Account/control use case. Each declares its exact receiving service or purpose audience.
- A private player-delegation token is not workload identity. A receiving endpoint requires both an approved concrete mTLS caller and the exact delegated-token audience/profile. Do not create profiles for consumers that have no demonstrated call path.
- Generic Service JWT and `aud=internal` are not canonical workload authentication. Workload-only calls use certificate-derived mTLS identity and exact per-method caller policy.
- Delegated-token receiving endpoints make the registered token profile/type, exact Account issuer, exact audience, concrete mTLS caller identity, and method-level policy mandatory predicates. A matching audience without the registered profile/type, issuer, caller certificate, or method allowlist is insufficient. Workload-only methods declare token profile/type/issuer/audience as `none` and still require the concrete mTLS identity and exact method policy; they do not require a JWT issuer. Private delegation methods require all five delegated-token predicates.
- Gameplay-domain calls use mTLS plus typed `PlayerExecutionContext`; raw gameplay clients never receive or carry gameplay-command JWTs, and ordinary commands perform no token validation.
- `gameplay-connect` remains a short-lived Account-signed, Gateway-only, single-use admission artifact. Gateway signed connect context remains a separate Gateway key family and assertion. Neither is an issued-token-registry auth session.
- Player-facing and shared environments require asymmetric Account signing and JWKS validation. HMAC is allowed only for local/dev and explicitly ephemeral CI. Account is the only application workload with the Account JWT private key.

## Consequences

- Tokens cannot move laterally merely because multiple services accept a generic internal audience.
- Workload and delegated-player authority are independently visible and testable.
- Profile, route-matrix, negative-cross-profile, and release-readiness testing increases.
- A Game Session binding may need more than one private delegation token if multiple real control-plane receivers require distinct audiences; implementations should mint only those actually used.
- Exact audience comparison is local and negligible. Ordinary gameplay has no added JWT, Redis, or signature-validation cost.

## Alternatives Considered

### Keep One Generic Internal Service JWT

This minimizes configuration and token count, but expands every accepting service into one bearer-token trust zone and duplicates mTLS as workload identity.

### Eliminate Every Private Player-Delegation Token

mTLS plus typed context is sufficient for gameplay-domain calls, but existing Account/control-plane refresh and account-authority flows still require generation-bound delegated player authority. Eliminate a private profile only after its actual consumer contract no longer needs bearer delegation.

### Put JWTs On Gameplay Clients Or Commands

This exposes internal authority to raw clients and creates a per-command validation dependency without improving the accepted workload-delegation boundary.

## Implementation and Proof Obligations

- Replace generic `internal` and locally minted workload JWT paths directly in this pre-v1 system.
- Define exact profile/audience constants and route-matrix acceptance for every issuer and consumer.
- Prove every cross-profile, wrong-type, wrong-issuer, wrong-audience, wrong-certificate-caller, and unallowlisted-method combination fails before authorization.
- Prove workload-only calls use mTLS caller policy without JWTs, every receiver applies an exact per-method policy, and gameplay commands retain no JWT hot path.
- Separate Account JWT, gameplay-connect, and Gateway connect-context key families and deployment mounts.
- Prove asymmetric JWKS startup/readiness in player-facing environments and rejection of HMAC-only configuration.
- Align secondary Account, service, frontend, deployment, and tracker documentation during implementation without treating current HMAC/generic-token code as target behavior.

## Reversibility and Revisit Triggers

Profiles are explicit contract identifiers and can be added for demonstrated consumers. Revisit if an external identity provider becomes token authority, workload identity moves away from mTLS, or measured token proliferation justifies a separately reviewed bounded multi-audience delegation profile.
