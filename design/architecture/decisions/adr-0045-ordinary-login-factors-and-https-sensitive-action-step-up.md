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

Every listed HTTPS-sensitive action requires recent ordinary reauthentication using the account's enabled primary-login mechanism policy, including account deletion and global administration. If both `PASSWORD` and `EMAIL_OTP` are enabled, either one valid supplied secret satisfies this ordinary reauthentication; the request is not a sequential multi-factor exchange. Initial elevation to `platformAdmin`, or to `billingAdmin` authority that crosses tenant boundaries, additionally requires an independent TOTP. That TOTP step occurs once per bounded elevated window, not once per action, and never appears in gameplay. Tenant-scoped `tenantAdmin` and `moderator` operator actions continue to require current tenant membership and role at the action boundary; they do not acquire or claim the global `privileged_control` window merely to make the shared operator-delegation protocol work.

### Bounded Security Policy

Account is the sole canonical resolver and publisher of the effective security policy. It resolves the versioned platform baseline and hard maxima first, then applies a versioned deployment policy and, where a tenant-scoped action applies, a versioned tenant policy; each narrower layer may only tighten the preceding result. For bounded ages, lifetimes, and clock skew, tightening is the field-wise minimum; for assurance or factor requirements, tightening means the stronger requirement. A missing, invalid, non-tightening, or contradictory override is not ignored. Account returns the resolved values with the contributing `platformPolicyVersion`, `deploymentPolicyVersion`, optional `tenantPolicyVersion`, and an opaque `effectivePolicyVersion`/digest. Callers must not merge policy inputs, fall back to defaults, or authorize from a cached or unversioned result.

Platform defaults are a 300-second recent-reauthentication age, a 600-second privileged-control window, a 300-second gameplay-to-HTTPS handoff lifetime, and at most 30 seconds of validation clock skew. Environment or tenant policy may tighten those values but cannot exceed the platform maxima without a new accepted decision. Age and expiry checks use Account-issued UTC timestamps and fail at the exact boundary after adding the allowed skew; future-dated evidence beyond the skew, missing policy version, an unavailable policy source, or failure to resolve the exact effective policy is invalid and fails the sensitive action closed. A policy-version advance invalidates unconsumed proofs issued under the prior effective policy. Token revocation, account-generation advance, role loss, TOTP reset, security lock, explicit handoff cancellation, or successful handoff consumption invalidates the affected proof immediately rather than waiting for expiry.

Focused proof must cover exact-boundary expiry, maximum allowed skew, future-dated evidence, tighter effective policy, token and generation revocation, role or TOTP loss, handoff replay, cancellation, and concurrent consume. Configuration validation rejects non-positive values and values above the platform maxima before the policy becomes active.

### Gameplay-to-HTTPS handoff

Telnet or gameplay may explicitly initiate a sensitive action. FireMUD may then return a short-lived HTTPS URL containing a high-entropy, unguessable, single-use opaque handoff handle. The URL contains no independently authorizing credential; the handle is only a server-side lookup key for state bound to the initiating account, gameplay session, exact action scope, action, and `requestId`. Payment handoffs additionally require and bind the exact product, amount, and currency. Non-commercial handoffs persist those commercial fields as explicitly absent, and that absence is part of the canonical digest and idempotency identity. Account-scoped actions persist `tenantId` as explicitly absent; tenant-scoped actions persist exactly one validated `tenantId`. At creation, Account also records the exact originating gameplay token `jti`, `tokenGeneration`, `issuanceFence`, complete applicable authority tuple, `securityStateVersion`, `effectivePolicyVersion` and policy digest, and applicable `totpVersion` and `privilegedControlVersion`; an account or gameplay-session identity alone is not a substitute.

The HTTPS client authenticates the user over HTTPS before FireMUD discloses the bound action and, for payment handoffs, the product, amount, and currency details; it discloses the exact tenant only when the action is tenant-scoped. Before disclosure, before accepting recent reauthentication or step-up, before accepting provider-backed completion, and in the transaction that atomically consumes the handoff, Account revalidates the exact current gameplay token `jti` and every recorded token, authority, security, policy, TOTP, and elevation version. Any missing, rotated, revoked, stale, or mismatched value rejects the handoff immediately. A policy advance therefore invalidates every unconsumed handoff issued under the prior policy, even if its ordinary lifetime has not elapsed. Callers must not satisfy these checks from account or gameplay-session identity, cached claims, or an unversioned result. FireMUD then performs any required recent reauthentication, elevation, or payment-provider flow while preserving those bindings. It verifies the authenticated account and gameplay-session bindings, verifies the exact `tenantId` for tenant-scoped actions, requires `tenantId` to remain absent for account-scoped actions, and requires non-commercial handoffs to keep product, amount, and currency absent, together with the `requestId` binding. Payment handoffs complete asynchronously only after Account receives and verifies the corresponding provider webhook and all current-binding checks succeed; an authenticated HTTPS result or generic provider result alone is not completion authority. It atomically consumes the handoff only after the same checks succeed. Any later reuse, alteration, policy or authority advance, or opening of an expired handoff is rejected; retries and duplicate callbacks may converge through the bound `requestId` idempotency record but cannot reopen the consumed handoff. The gameplay session may observe the eventual success, failure, expiry, or cancellation, but it cannot approve the action itself.

### Existing premium balances

Spending an existing non-withdrawable premium balance remains a gameplay action. It uses an explicit purchase confirmation, an idempotent request identity, and applicable spending caps, but does not trigger general reauthentication or HTTPS step-up. Any future withdrawal, cash redemption, or cash-equivalent transfer capability is outside this decision.

## Consequences

- Normal Telnet play remains usable without browser-style authentication prompts.
- Account secrets, TOTP elevation, payment credentials, and provider challenges remain outside the gameplay protocol.
- Sensitive gameplay-originated actions can preserve game context while completing through a separately authenticated HTTPS client.
- Users need access to an HTTPS-capable web, native, or CLI client for sensitive actions; Telnet alone is intentionally insufficient.
- The server must persist and validate handoff intent, expiry, single-use state, bindings, verified completion, and idempotent outcomes.
- Premium-balance purchases need clear confirmation, replay protection, caps, auditability, and failure recovery even though they do not normally require reauthentication.

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
- `platformAdmin` and cross-tenant `billingAdmin` elevation require independent TOTP before a bounded elevated window is issued;
- a handoff handle is high-entropy, unguessable, server-side, opaque, non-authorizing, short-lived, single-use, bound to every declared field plus the originating gameplay token `jti`, token generation and issuance fence, complete applicable authority tuple, security and policy versions, and applicable TOTP/elevation versions; every binding is revalidated at disclosure, step-up, provider completion, and atomic consume, so an authority or policy advance invalidates the unconsumed handoff; account-scoped handoffs prove `tenantId` is absent in state, disclosure, and completion binding, while tenant-scoped handoffs prove the exact `tenantId` is present and consistent across all of them;
- payment or provider-backed action completion occurs only after Account verifies the corresponding provider webhook and is idempotent under retries and duplicate callbacks; and
- premium-balance spending proves explicit confirmation, idempotency, and cap enforcement without introducing per-purchase factor prompts.

## Reversibility and Revisit Triggers

The ordinary factor set and elevated-factor implementation can evolve without moving sensitive authority into gameplay. Revisit this decision if FireMUD introduces withdrawable or cash-redeemable balances, direct player-to-player transfer of cash-equivalent value, a materially different administrative threat model, new ordinary authentication factors such as passkeys, or a requirement that sensitive actions be completable by a pure Telnet client.
