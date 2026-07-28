# ADR-0045: Ordinary Login Factors and HTTPS Sensitive-Action Step-Up

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

Ordinary Telnet/gameplay login and ordinary HTTPS account/control login use the factors selected for the account: `PASSWORD`, verified `EMAIL_OTP`, or both.

Gameplay `LOGIN` is ordinary authentication only. After it succeeds, FireMUD does not prompt for another password, verified email code, or TOTP during the active gameplay session, does not perform per-command reauthentication, and does not elevate the gameplay session into an account/control session. A reconnect `LOGIN` restores or rebinds gameplay only when the separate gameplay-session checks permit it; it is not an elevation mechanism.

### HTTPS-only sensitive actions

The following actions complete only on the HTTPS account/control plane:

- account security and identity changes, including email, password, and factor changes;
- new real-money charges and payment-card registration, replacement, or removal;
- account deletion;
- billing-ownership changes; and
- global administration.

The HTTPS client may be a web, native, or CLI client. A pure Telnet client cannot complete these actions.

Every listed HTTPS-sensitive action requires recent ordinary reauthentication using the account's selected ordinary factor policy, including account deletion and global administration. Initial elevation to `platformAdmin`, or to `billingAdmin` authority that crosses tenant boundaries, additionally requires an independent TOTP. That TOTP step occurs once per bounded elevated window, not once per action, and never appears in gameplay. Tenant-scoped `tenantAdmin` and `moderator` operator actions continue to require current tenant membership and role at the action boundary; they do not acquire or claim the global `privileged_control` window merely to make the shared operator-delegation protocol work.

### Gameplay-to-HTTPS handoff

Telnet or gameplay may explicitly initiate a sensitive action. FireMUD may then return a short-lived HTTPS URL containing a high-entropy, unguessable, single-use opaque handoff handle. The URL contains no independently authorizing credential; the handle is only a server-side lookup key for state bound to the initiating account, gameplay session, tenant, action, product, amount, currency, and `requestId`.

The HTTPS client authenticates the user over HTTPS before FireMUD discloses the bound action, tenant, product, amount, or currency details, and then performs any required recent reauthentication, elevation, or payment-provider flow. FireMUD verifies the authenticated account, gameplay-session, tenant, and `requestId` bindings, completes the action asynchronously only after receiving and verifying the authenticated result or provider result, and atomically consumes the handoff when the action completes. Any later reuse, alteration, or opening of an expired handoff is rejected; retries and duplicate callbacks may converge through the bound `requestId` idempotency record but cannot reopen the consumed handoff. The gameplay session may observe the eventual success, failure, expiry, or cancellation, but it cannot approve the action itself.

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

- ordinary Telnet and HTTPS login honor the account-selected `PASSWORD` and/or verified `EMAIL_OTP` policy;
- an active gameplay session cannot solicit another ordinary factor, TOTP, or elevated control-plane authority;
- every listed sensitive route, including account deletion and global administration, rejects absent or stale ordinary reauthentication and keeps its mutations HTTPS-only;
- `platformAdmin` and cross-tenant `billingAdmin` elevation require independent TOTP before a bounded elevated window is issued;
- a handoff handle is high-entropy, unguessable, server-side, opaque, non-authorizing, short-lived, single-use, bound to every declared field, atomically consumed on completion, and rejected on reuse or parameter substitution;
- completion occurs only after a verified authenticated or provider result and is idempotent under retries and duplicate callbacks; and
- premium-balance spending proves explicit confirmation, idempotency, and cap enforcement without introducing per-purchase factor prompts.

## Reversibility and Revisit Triggers

The ordinary factor set and elevated-factor implementation can evolve without moving sensitive authority into gameplay. Revisit this decision if FireMUD introduces withdrawable or cash-redeemable balances, direct player-to-player transfer of cash-equivalent value, a materially different administrative threat model, new ordinary authentication factors such as passkeys, or a requirement that sensitive actions be completable by a pure Telnet client.
