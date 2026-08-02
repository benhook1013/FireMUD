# ADR 0045: Ordinary Login Factors and HTTPS Sensitive-Action Step-Up

## Status

Accepted

## Implementation Status

This decision is partially implemented. Account has live password and verified-email-code authentication foundations and HTTPS control-plane surfaces, but the full selected-factor policy, recent-reauthentication and independent-TOTP elevation windows, gameplay-to-HTTPS handoff completion, replay/idempotency proof, and registry-backed logout behavior are not yet fully converged. The current implementation must not be read as proof that every sensitive action already satisfies this target contract. This status does not change the human-reviewed decision metadata in the Decision Record below.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.3` Account security, recovery, and lifecycle
- Affected capabilities: `AA-2.1`, `EA-3.1`, `SF-1.3`, `PO-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `MS-AA-LOGIN-FACTORS`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `MS-AA-LOGIN-FACTORS`

## Context

FireMUD supports ordinary play over TCP/Telnet as well as account and control clients over HTTPS. Authentication must work for normal Telnet use without turning the gameplay protocol into an account-security, payment, or global-administration interface. Some actions may begin in gameplay but require stronger assurances, provider-hosted flows, or recent authentication before they can complete.

The design must also distinguish a new real-money charge from spending an existing, non-withdrawable premium balance. Requiring a factor for every gameplay command or premium-balance purchase would interrupt play without establishing an appropriate security boundary.

## Decision

### Ordinary login

Ordinary Telnet/gameplay login and ordinary HTTPS account/control login use the account-enabled primary-login mechanisms: `PASSWORD` and/or verified `EMAIL_OTP`. Each enabled mechanism is independently sufficient; when both are enabled, a valid password or a valid email OTP may authenticate the login. Both secrets are not required, this is not sequential multi-factor authentication, and the contract does not introduce a `BOTH` enum. TOTP is not an ordinary primary-login mechanism; it is reserved for the separate HTTPS sensitive-action elevation described below.

Gameplay `LOGIN` is ordinary authentication only. After it succeeds, FireMUD does not prompt for another password, verified email code, or TOTP during the active gameplay session, does not perform per-command reauthentication, and does not elevate the gameplay session into an account/control session. A reconnect `LOGIN` restores or rebinds gameplay only when the separate gameplay-session checks permit it; it is not an elevation mechanism.

### HTTPS-only sensitive actions

The following actions complete only on the HTTPS account/control plane:

- account security and identity changes, including email, password, and factor changes;
- new real-money charges and payment-card registration, replacement, or removal;
- account deletion;
- billing-ownership changes; and
- global administration.

The HTTPS client may be a web, native, or CLI client. A pure Telnet client cannot complete these actions.

Every listed HTTPS-sensitive action requires recent ordinary reauthentication using the account's enabled primary-login mechanism policy, including account deletion and global administration. If both `PASSWORD` and `EMAIL_OTP` are enabled, either one valid supplied secret satisfies this ordinary reauthentication; the request is not a sequential multi-factor exchange. Initial elevation to `platformAdmin`, or to `billingAdmin` authority that crosses tenant boundaries, additionally requires an independent TOTP. That TOTP step occurs once per bounded elevated window, not once per action, and never appears in gameplay. An allowlisted `platformAdmin_global` branch on a `tenant_regular` route is an explicit route-class exception: authorization selects the compatible global-role predicate rather than the tenant-role membership predicate. Account binds branch selection, live global-role and role-freshness evidence, every applicable issuer/account/target-tenant generation, and the `privileged_control` operator reference to one `account-auth-evidence-bundle/v1` at one linearization point. The branch, each predicate, and every redemption must carry the exact `{bundleVersion, sourceVersion, sourceFence, linearization}` reference; any mismatch, role revocation, generation advance, or stale operator reference rejects the action without tenant-role fallback. It requires this same ADR 0045 bounded elevated proof, with recent ordinary reauthentication plus independent TOTP elevation, the current control-plane token, account/issuer authority, explicit target tenant, live global role, and all target-tenant freshness checks still valid. Tenant-scoped `tenantAdmin` and `moderator` sensitive actions use the complete ordinary `tenant_regular` tenant-role contract: the exact token/account/issuer checks, explicit target tenant, live target-tenant membership and role, caller-bound membership-generation freshness, and target-tenant-generation freshness from that same bundle. The receiver selects the global exception only from conclusive fresh global-role evidence; otherwise it selects the tenant-role branch only from conclusive global-role absence, and unavailable or inconclusive evidence denies without fallback. These tenant-role actions do not acquire or claim the global `privileged_control` window merely to make the shared operator-delegation protocol work.

Every `privileged_control` proof is an Account-persisted, single-purpose record bound to the exact authenticated `{accountId, issuerId, tokenJti, audience, role, targetTenantId, actionClass, privilegedControlVersion, issuedAt, expiresAt}` tuple. `targetTenantId` is exactly the requested tenant for a tenant-scoped or cross-tenant action and is explicitly absent for an action class with no target tenant; no UI selection, default, global role, or account-wide value supplies it. Account persists and validates the exact authenticated `accountId`, alongside every existing field, against the current token, live role, exact request target, and route action class when the proof is issued and on every redemption/use. A changed account, role, audience, target tenant, or action class requires a new proof and cannot broaden or reuse the existing one.

### Bounded Security Policy

Account is the sole canonical resolver and publisher of the effective security policy. It resolves the versioned platform baseline and hard maxima first, then applies a versioned deployment policy and, where a tenant-scoped action applies, a versioned tenant policy; each narrower layer may only tighten the preceding result. For bounded ages, lifetimes, and clock skew, tightening is the field-wise minimum; for assurance or factor requirements, tightening means the stronger requirement. A missing, invalid, non-tightening, or contradictory override is not ignored. Account returns the resolved values with the contributing `platformPolicyVersion`, `deploymentPolicyVersion`, optional `tenantPolicyVersion`, and an opaque `effectivePolicyVersion`/digest. Callers must not merge policy inputs, fall back to defaults, or authorize from a cached or unversioned result.

Platform defaults are a 300-second recent-reauthentication age, a 600-second privileged-control window, a 300-second gameplay-to-HTTPS handoff lifetime, and at most 30 seconds of validation clock skew. Environment or tenant policy may tighten those values but cannot exceed the platform maxima without a new accepted decision. Age and expiry checks use Account-issued UTC timestamps and fail at the exact boundary after adding the allowed skew; future-dated evidence beyond the skew, missing policy version, an unavailable policy source, or failure to resolve the exact effective policy is invalid and fails the sensitive action closed. A policy-version advance invalidates unconsumed proofs issued under the prior effective policy. Token revocation, account-generation advance, role loss, TOTP reset, security lock, explicit handoff cancellation, or successful handoff consumption invalidates the affected proof immediately rather than waiting for expiry.

Focused proof must cover exact-boundary expiry, maximum allowed skew, future-dated evidence, tighter effective policy, token and generation revocation, role or TOTP loss, handoff replay, cancellation, and concurrent consume. Configuration validation rejects non-positive values and values above the platform maxima before the policy becomes active.

### Gameplay-to-HTTPS handoff

Telnet or gameplay may explicitly initiate a sensitive action. FireMUD may then return a short-lived HTTPS URL containing a high-entropy, unguessable, single-use opaque handoff handle. The URL contains no independently authorizing credential; the handle is only a server-side lookup key for state bound to the initiating account, gameplay session, exact action scope, action, and `requestId`. Payment handoffs additionally require and bind the exact product, amount, and currency. Non-commercial handoffs persist those commercial fields as explicitly absent, and that absence is part of the canonical digest and idempotency identity. Every handoff record and every lifecycle request/evidence carrier includes the required canonical `membershipVersion` map independently of `tenantId` and `authorityTuple.membershipAuthorityGeneration`: tenant-scoped actions carry exactly `{tenantId: version}`, while account-scoped actions carry `{}`. The map is part of the canonical handoff digest and `requestId` idempotency identity; it is never omitted, `null`, scalar, aggregated, or inferred from the target tenant or another Account read. Account-scoped actions persist `tenantId` as explicitly absent; tenant-scoped actions persist exactly one validated `tenantId`. For a JWT-backed gameplay origin, Account records the exact originating gameplay token `jti`, `tokenGeneration`, and `issuanceFence`. For Telnet/non-JWT origin, the canonical identity is the server-issued exact gameplay binding identity (`gameplayBindingId`) from the authenticated Game Session context together with that binding's Account-issued `issuanceFence`; JWT-only `jti` and `tokenGeneration` are explicitly absent and are never fabricated. In either branch Account also records the complete applicable authority tuple, `securityStateVersion`, `effectivePolicyVersion` and policy digest, and applicable `totpVersion` and `privilegedControlVersion`.

The HTTPS client authenticates the user over HTTPS before FireMUD discloses the bound action and, for payment handoffs, the product, amount, and currency details; it discloses the exact tenant only when the action is tenant-scoped. At handoff issuance, before disclosure, before accepting recent reauthentication or step-up, before accepting provider-backed completion, in the transaction that atomically consumes the handoff, during cancellation, and for every retry/replay/idempotency lookup, Account independently revalidates the JWT branch's exact current gameplay token `jti`, `tokenGeneration`, and `issuanceFence`, plus the complete recorded `authorityTuple`, or the Telnet/non-JWT branch's exact current server-issued `gameplayBindingId` and Account-issued `issuanceFence`, together with the recorded security, policy, TOTP, and elevation versions. At each of those lifecycle points it also compares the complete persisted and presented `membershipVersion` map, including the exact key set and the `{}` representation when no membership applies, against the current Account authority snapshot; it must not derive that result from `tenantId`, a role, `membershipAuthorityGeneration`, cached claims, or another authority field. Any missing, rotated, revoked, stale, separately assembled, or mismatched value rejects the handoff immediately. A policy advance therefore invalidates every unconsumed handoff issued under the prior policy, even if its ordinary lifetime has not elapsed. Callers must not satisfy these checks from account or gameplay-session identity alone, cached claims, or an unversioned result. FireMUD then performs any required recent reauthentication, elevation, or payment-provider flow while preserving those bindings. It verifies the authenticated account and gameplay-session bindings, the same server-issued binding identity and issuance fence across the handoff lifecycle, the exact `tenantId` for tenant-scoped actions, requires `tenantId` to remain absent for account-scoped actions, and requires the same independent `membershipVersion` map to remain exact across every lifecycle point. It also requires non-commercial handoffs to keep product, amount, and currency absent, together with the `requestId` binding. Payment handoffs complete asynchronously only after Account receives and verifies the corresponding provider webhook and all current-binding checks succeed; an authenticated HTTPS result or generic provider result alone is not completion authority. It atomically consumes the handoff only after the same checks succeed. Any later reuse, alteration, replay, cancellation, policy or authority advance, or opening of an expired handoff is rejected; retries and duplicate callbacks may converge through the bound `requestId` idempotency record only when the exact map and all other bindings match, but cannot reopen the consumed handoff. The gameplay session may observe the eventual success, failure, expiry, or cancellation, but it cannot approve the action itself.

Handoff cancellation has one canonical lifecycle: `PENDING` is the only unconsumed state, while `CONSUMED`, `EXPIRED`, and `CANCELED` are terminal states. Account cancellation atomically compare-and-sets only `PENDING` to `CANCELED` after independently revalidating the same bindings, exact `membershipVersion` map or `{}`, and digest; cancellation of `CONSUMED`, `EXPIRED`, or `CANCELED` is rejected without mutation. A race with consume or expiry therefore resolves through that single state transition and cannot cancel a completed, expired, or already-canceled handoff.

### Existing premium balances

Spending an existing non-withdrawable premium balance remains a gameplay action. The balance is account-scoped and is not tenant authority or a tenant entitlement. A spend that creates a tenant gameplay effect must carry an explicit owning-boundary `tenantConsumptionBinding` naming the exact `{accountId, tenantId, currency, gameplayEffect, amount, requestId, requestDigest}`; `requestDigest` must be computed before reservation over the canonical binding plus every debit and gameplay-effect input, including `currency`, and must be unchanged through reservation, effect, debit, retry, and callback. The owning domain boundary records or consumes that binding, validates current tenant authorization and all applicable authority-generation/membership-version predicates at consumption time, and rejects any missing, stale, mismatched, or cross-tenant binding. The Account balance debit and owning-boundary effect converge under that exact idempotency identity; retries replay only the stored lifecycle result and cannot debit twice or apply the effect to another tenant. Explicit purchase confirmation, applicable spending caps, and audit remain mandatory, but the flow does not trigger general reauthentication or HTTPS step-up. Any future withdrawal, cash redemption, or cash-equivalent transfer capability is outside this decision.

### Durable Premium-Spend Protocol

The gameplay `requestId` and `requestDigest` in `tenantConsumptionBinding` are the one durable operation identity for a premium spend; `requestDigest` is computed before reservation over the canonical `{accountId, tenantId, currency, gameplayEffect, amount, requestId}` tuple and every other input that affects the debit or gameplay effect. Retries and callbacks must not create a second debit or effect identity or alter any covered input. A direct non-idempotent `SpendCurrency` call is not sufficient. Game Session/Game Logic admits the confirmed command and owns the gameplay effect/outbox, while Account Service remains the sole authority for the account-scoped balance, reservation, debit ledger, and Account-side operation result. The gameplay owner validates the current tenant binding before asking Account to reserve funds; Account does not become a second tenant-authority store.

The operation is durably driven through the following single lifecycle: `RESERVE_PENDING` -> `RESERVED` -> `EFFECT_PENDING` -> `EFFECT_COMMITTED` -> `DEBIT_COMMITTED`, with terminal `REJECTED`, `RELEASED`, or `FAILED` outcomes and bounded `PENDING`/indeterminate state when evidence is incomplete. Account atomically records `{accountId, tenantId, currency, amount, gameplayEffect, requestId, requestDigest}` and creates one bounded reservation only after authoritative balance and cap checks and after verifying that the precomputed digest covers currency and every debit/effect input. The gameplay owner applies the effect once under the same identity and durably acknowledges it; Account commits the debit for that exact operation only after that acknowledgement. If the gameplay owner durably proves that no effect committed, Account releases the reservation as the same operation's idempotent compensation. Account never writes gameplay state, and gameplay never writes or reconstructs the balance.

Crash, timeout, partial success, retry, and duplicate callback recovery uses the durable operation row, exact binding/digest, and a bounded claim/deadline. Same-identity/same-digest reserve, effect, commit, release, and callback attempts replay the stored lifecycle result; any changed field is `IDEMPOTENCY_CONFLICT` and cannot mutate either side. A timeout or missing callback is reconciled by reading Account's operation result and the gameplay effect marker and retrying only the missing transition with the original identity. A possible committed debit or effect remains `PENDING`/indeterminate until proven; it is never released, double-applied, or reported absent merely because a response was lost. A reversal after a committed effect is a separately explicit, idempotent product operation linked to the original request, not an uncorrelated refund.

## Consequences

- Normal Telnet play remains usable without browser-style authentication prompts.
- Account secrets, TOTP elevation, payment credentials, and provider challenges remain outside the gameplay protocol.
- Sensitive gameplay-originated actions can preserve game context while completing through a separately authenticated HTTPS client.
- Users need access to an HTTPS-capable web, native, or CLI client for sensitive actions; Telnet alone is intentionally insufficient.
- The server must persist and validate handoff intent, expiry, single-use state, bindings, verified completion, and idempotent outcomes.
- Premium-balance purchases need clear confirmation, replay protection, caps, auditability, and the durable Account/gameplay reservation, commit, compensation, and bounded-recovery protocol above even though they do not normally require reauthentication.

## Alternatives Considered

### Collect sensitive factors and payment details over Telnet

Rejected because it expands the gameplay protocol into an account-security and payment interface, complicates provider flows, and creates unexpected in-game secret prompts.

### Require HTTPS approval for every premium-balance spend

Rejected for existing non-withdrawable balances because it imposes disproportionate friction on ordinary gameplay. Confirmation, idempotency, caps, and audit controls address this case without general step-up.

### Require TOTP for every sensitive or administrative action

Rejected because repeated prompts do not materially improve assurance within a short, bounded elevated session. Independent TOTP is required when entering the relevant elevated window.

### Put all gameplay-originated sensitive actions exclusively in a separate portal

Rejected as the only mechanism because an explicit, bound handoff preserves the player's intended action and exact commercial context without granting authority through the URL.

## Implementation and Proof Obligations

Focused contract and integration proof must demonstrate that:

- ordinary Telnet and HTTPS login honor the account-enabled `PASSWORD` and/or verified `EMAIL_OTP` primary-login policy, with either one valid secret sufficient when both are enabled and no sequential-MFA or `BOTH` interpretation;
- Account alone resolves the effective security policy using the explicit platform, deployment, and applicable tenant precedence/tightening rules, returns the contributing versions plus `effectivePolicyVersion`, and fails sensitive actions closed when any required policy input or resolution is unavailable, invalid, contradictory, stale, or unversioned;
- an active gameplay session cannot solicit another ordinary factor, TOTP, or elevated control-plane authority;
- every listed sensitive route, including account deletion and global administration, rejects absent or stale ordinary reauthentication and keeps its mutations HTTPS-only;
- `platformAdmin`, including an allowlisted `tenant_regular` `platformAdmin_global` branch, and cross-tenant `billingAdmin` elevation require recent ordinary reauthentication plus independent TOTP before a bounded elevated window is issued;
- every `privileged_control` proof persists and revalidates the exact authenticated `accountId`, token `jti`, issuer, audience, role, target tenant presence/value, action class, version, and bounded timestamps at issuance and every redemption; focused proof rejects redemption of a proof under another account, role, audience, target tenant, or action class, including cross-account, cross-role, and cross-tenant reuse;
- a handoff handle is high-entropy, unguessable, server-side, opaque, non-authorizing, short-lived, single-use, bound to every declared field plus either the originating gameplay token `jti`/`tokenGeneration`/`issuanceFence` or, for Telnet/non-JWT, the server-issued exact `gameplayBindingId` and Account-issued `issuanceFence`, complete applicable authority tuple, exact canonical `membershipVersion` map or `{}`, security and policy versions, and applicable TOTP/elevation versions; JWT-only fields are explicitly absent on the Telnet branch. Account independently carries and revalidates that complete map at issuance, disclosure, reauthentication/step-up, provider completion, atomic consume, cancellation, and every replay/idempotency retry; it never infers the map from `tenantId`, a scalar membership version, or another authority field. The selected identity, map, and issuance fence are revalidated at every lifecycle point, so an authority, membership, or policy advance invalidates the unconsumed handoff; account-scoped handoffs prove `tenantId` is absent and `membershipVersion` is `{}` in state, disclosure, and completion binding, while tenant-scoped handoffs prove the exact one-key map and exact `tenantId` remain consistent across all of them;
- payment or provider-backed action completion occurs only after Account verifies the corresponding provider webhook and is idempotent under retries and duplicate callbacks; and
- premium-balance spending proves the exact reservation/effect/debit lifecycle, crash and timeout recovery, partial-success reconciliation, compensation, duplicate-callback handling, explicit owning-boundary tenant consumption binding including `currency`, current tenant authorization, and a pre-reservation `requestDigest` covering currency and every debit/effect input, without introducing per-purchase factor prompts or treating an account-scoped balance as tenant authority.

## Reversibility and Revisit Triggers

The ordinary factor set and elevated-factor implementation can evolve without moving sensitive authority into gameplay. Revisit this decision if FireMUD introduces withdrawable or cash-redeemable balances, direct player-to-player transfer of cash-equivalent value, a materially different administrative threat model, new ordinary authentication factors such as passkeys, or a requirement that sensitive actions be completable by a pure Telnet client.
