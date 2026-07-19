# ADR-0045: Ordinary Login Factors and HTTPS Sensitive-Action Step-Up

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.3` Account security, recovery, and lifecycle
- Affected capabilities: `AA-2.1`, `EA-3.1`, `SF-1.3`, `PO-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `MS-AA-LOGIN-FACTORS`

## Context

FireMUD supports ordinary play over TCP/Telnet as well as account and control clients over HTTPS. Authentication must work for normal Telnet use without turning the gameplay protocol into an account-security, payment, or global-administration interface. Some actions may begin in gameplay but require stronger assurances, provider-hosted flows, or recent authentication before they can complete.

The design must also distinguish a new real-money charge from spending an existing, non-withdrawable premium balance. Requiring a factor for every gameplay command or premium-balance purchase would interrupt play without establishing an appropriate security boundary.

## Decision

### Ordinary login

Ordinary Telnet/gameplay login and ordinary HTTPS account/control login use the factors selected for the account: `PASSWORD`, verified `EMAIL_OTP`, or both.

FireMUD does not prompt for a password or TOTP during gameplay and does not perform per-command reauthentication. An authenticated gameplay session does not thereby become an elevated account/control session.

### HTTPS-only sensitive actions

The following actions complete only on the HTTPS account/control plane:

- account security and identity changes, including email, password, and factor changes;
- new real-money charges and payment-card registration, replacement, or removal;
- account deletion;
- billing-ownership changes; and
- global administration.

The HTTPS client may be a web, native, or CLI client. A pure Telnet client cannot complete these actions.

Sensitive personal and billing changes require recent ordinary reauthentication using the account's selected ordinary factor policy. Initial elevation to `platformAdmin`, or to `billingAdmin` authority that crosses tenant boundaries, additionally requires an independent TOTP. That TOTP step occurs once per bounded elevated window, not once per action, and never appears in gameplay.

### Gameplay-to-HTTPS handoff

Telnet or gameplay may explicitly initiate a sensitive action. FireMUD may then return a short-lived, single-use, opaque HTTPS URL. The URL contains no secret and grants no authority by itself; its opaque handle resolves to server-side state bound to the initiating account, session, tenant, action, product, amount, currency, and `requestId`.

The HTTPS client authenticates the user and performs any required recent reauthentication, elevation, or payment-provider flow. FireMUD completes the action asynchronously only after receiving and verifying the authenticated result or provider result. The gameplay session may observe the eventual success, failure, expiry, or cancellation, but it cannot approve the action itself. Reusing, altering, or opening an expired handoff cannot change or complete the bound intent.

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
- gameplay commands cannot solicit password or TOTP input or acquire elevated control-plane authority;
- sensitive routes reject absent or stale ordinary reauthentication and keep their mutations HTTPS-only;
- `platformAdmin` and cross-tenant `billingAdmin` elevation require independent TOTP before a bounded elevated window is issued;
- a handoff is opaque, non-authorizing, short-lived, single-use, bound to every declared field, and safe against replay or parameter substitution;
- completion occurs only after a verified authenticated or provider result and is idempotent under retries and duplicate callbacks; and
- premium-balance spending proves explicit confirmation, idempotency, and cap enforcement without introducing per-purchase factor prompts.

## Reversibility and Revisit Triggers

The ordinary factor set and elevated-factor implementation can evolve without moving sensitive authority into gameplay. Revisit this decision if FireMUD introduces withdrawable or cash-redeemable balances, direct player-to-player transfer of cash-equivalent value, a materially different administrative threat model, new ordinary authentication factors such as passkeys, or a requirement that sensitive actions be completable by a pure Telnet client.
