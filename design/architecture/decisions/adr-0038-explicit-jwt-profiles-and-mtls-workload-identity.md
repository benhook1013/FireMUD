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
- Player-facing and shared environments require asymmetric Account signing and JWKS validation. Their startup and readiness gates fail before accepting traffic if shared-HMAC Account signing or validation material is configured, if asymmetric signing/JWKS configuration is absent, or if both modes are enabled. HMAC is allowed only when the environment is explicitly classified as local/dev, or when an ephemeral CI profile separately opts in to HMAC for that run; it is never inferred from missing asymmetric configuration. Account is the only application workload with the Account JWT private key.

### mTLS Identity And Certificate Lifecycle

- The authenticated workload identity is sourced only from the peer certificate validated by the receiving mTLS listener. In shared and player-facing Kubernetes environments, cert-manager issues a distinct certificate and private key for each workload or explicitly classified bridge/operator client from the FireMUD cluster CA. The canonical identity is the normalized URI SAN. DNS SAN matching is forbidden as a fallback in player-facing environments. Outside player-facing environments, a DNS-SAN bridge exception may exist only as a separately approved, migration-only, narrowly scoped, time-bounded exception with an explicit expiry and proof that URI-SAN issuance is incomplete. Common service-family labels, certificate subjects, forwarded identity headers, and JWT subjects are not identities.
- One shared, versioned parser and normalizer owns the URI-SAN grammar. A normalized URI SAN is the canonical ASCII form `scheme://authority/path`: the URI must be absolute with a non-empty scheme and authority, and must have no userinfo, query, fragment, opaque component, or parser warning. The scheme is lowercase. Authority is either an ASCII reg-name host or a bracketed IPv6 literal; userinfo, IPv6 zone identifiers, and percent encoding anywhere in authority are rejected. Reg-name hosts are lowercase. IPv6 literals use lowercase RFC 5952 compressed spelling inside brackets. An optional port is canonical decimal `1..65535` with no leading zeroes; default ports are not removed. The canonical path is `/` when the raw path is empty, remains case-sensitive and absolute, and preserves a trailing slash. Literal ASCII unreserved path bytes and percent-encoded unreserved bytes are accepted spellings, with hex digit case ignored, except that a path segment whose raw or decoded form is exactly `.` or `..` is rejected; no dot-segment resolution or collapse is performed. Every percent escape must encode one ASCII unreserved byte (`ALPHA`, `DIGIT`, `-`, `.`, `_`, or `~`) and is decoded to that literal byte. Every other escape is rejected, including escapes for all reserved delimiters, `%` itself, control bytes, and non-ASCII bytes. Non-ASCII input, invalid UTF-8, malformed URI syntax, and any form with more than one possible parse are rejected. Certificate extraction, route allowlists, renewal overlap, audit identity, and trusted forwarded mTLS context all use this normalized value and never compare raw SAN spellings.
- Canonical vectors are deterministic: `spiffe://firemud.example/service` normalizes to itself; `spiffe://FIREMUD.EXAMPLE/service` normalizes to `spiffe://firemud.example/service`; and each delimiter escape below is rejected, with both hex-case spellings rejected where the escape contains alphabetic hex digits:
  - `/`: `%2F` or `%2f`
  - `:`: `%3A` or `%3a`
  - `@`: `%40`
  - `?`: `%3F` or `%3f`
  - `#`: `%23`
- Dot-segment vectors are rejected before any normalization: raw `/./` and `/../`, percent-encoded unreserved spellings `/%2E/`, `/%2e/`, `/%2E%2E/`, and `/%2e%2e/`, plus mixed raw/escaped spellings such as `/.%2E/`, `/%2E./`, `/.%2e/`, and `/%2e./`. All raw, hex-case, and mixed combinations that decode to a segment exactly equal to `.` or `..` are rejected, including at the beginning or end of the path; they are never resolved or collapsed.
- Required positive vectors also cover an empty raw path normalizing to `/`, accepted non-dot unreserved path escapes decoding to literals, bracketed RFC 5952 IPv6, and the boundary ports `1` and `65535`. Required negative vectors cover userinfo, zone identifiers, unbracketed IPv6, ports `0`, `65536`, or with leading zeroes, percent encoding in authority, every reserved-delimiter escape, escaped `%`, non-ASCII escapes, malformed escapes, query, fragment, opaque input, dot segments, and multiple URI SAN entries. Parser errors and unsupported forms are rejection outcomes, never best-effort normalization.
- The stable workload principal is the normalized URI SAN. A certificate instance is a specific approved leaf certificate identified by its leaf fingerprint or serial. The principal is usable only when the presented certificate instance is approved for that principal, receiver, and method; exact normalized URI-SAN allowlisting remains mandatory and is never replaced by certificate-instance approval alone.
- The receiver terminates mTLS only at the declared internal listener and validates the complete chain, `clientAuth` EKU, validity interval, and exact URI SAN against the route's allowlist before dispatch. A trusted internal termination component may forward identity-derived metadata only through the existing authenticated context contract: the TCP Proxy -> Gateway hop uses the certificate-authenticated `X-Proxy-*` contract, while Gateway -> Game Session uses the separately signed connect context. An unauthenticated or externally supplied header can never become mTLS identity, and Internet-facing TLS termination does not satisfy an internal workload-authentication predicate.
- Identity matching is exact and method-scoped: exactly one URI SAN must be present, and duplicate URI SAN entries or multiple distinct URI SANs are ambiguous even when one would match. The concrete normalized URI SAN must be allowlisted for that receiver and RPC/route, and one identity allowed for one method is not inherited by another. The receiver fails closed on missing, duplicated, malformed, expired, wrong-EKU, unknown, or ambiguous certificate identity before JWT/profile or business authorization.
- cert-manager renewal replaces the leaf certificate before expiry and permits bounded overlap only while that renewal is in progress. Receivers reload the trust and leaf material through the documented watcher/reloader path, accept both the old and new approved certificate instances for the same normalized principal only during the configured overlap, and then remove the old instance from the approved set. Rotation never broadens a method allowlist or changes the identity spelling.
- A suspected compromise or revoked certificate immediately removes only the affected certificate instance, identified by its leaf fingerprint or serial, from the approved set and denies calls using it; an approved replacement certificate instance with the same normalized URI SAN may remain usable subject to the exact allowlist and other certificate checks. A failed chain validation or lost trust root denies the presented certificate as applicable and audits the affected calls. The service or bridge must receive a newly issued certificate and pass peer/readiness proof before traffic resumes. CA/issuer rotation is a separate incident workflow; it does not silently fall back to a broad trust bundle, DNS-only matching, or plaintext in a player-facing environment.

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
- Prove certificate-derived URI-SAN principal extraction, exact method matching, approved certificate-instance tracking, trusted-termination boundaries, renewal-only bounded overlap, old-instance removal, and instance-scoped compromise/revocation fail-closed behavior.
- Prove that no DNS-SAN fallback is reachable in player-facing environments and that any separately approved bridge exception is bounded by its identity/method allowlist, expiry, migration evidence, and explicit removal proof.
- Prove workload-only calls use mTLS caller policy without JWTs, every receiver applies an exact per-method policy, and gameplay commands retain no JWT hot path.
- Separate Account JWT, gameplay-connect, and Gateway connect-context key families and deployment mounts.
- Prove asymmetric JWKS startup/readiness in player-facing and shared environments; reject HMAC-only, mixed HMAC/asymmetric, and missing-asymmetric configurations before traffic. Prove that local/dev and ephemeral CI accept HMAC only under their explicit environment/profile opt-in.
- Align secondary Account, service, frontend, deployment, and tracker documentation during implementation without treating current HMAC/generic-token code as target behavior.

## Reversibility and Revisit Triggers

Profiles are explicit contract identifiers and can be added for demonstrated consumers. Revisit if an external identity provider becomes token authority, workload identity moves away from mTLS, or measured token proliferation justifies a separately reviewed bounded multi-audience delegation profile.
