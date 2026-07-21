# ADR 0049: Optional Provider-Specific External Identity Linking

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `ACCOUNT-01`
- Primary capability: `AA-1.3` Authentication, recovery, security policy, and account data rights
- Affected capabilities: `EA-3.1`, `AA-1.1`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `ACCOUNT-01`

## Context

Player documentation names Google, Discord, and Steam, but the current implementation is not federated authentication. It accepts caller-supplied provider names and external IDs and stores them in a tenant-scoped table without provider callback verification, login, unlinking, collision, recovery, or outage behavior. Advertising that scaffold as social login would create account-takeover risk and an unsupported product promise.

FireMUD still benefits from convenient web sign-in. The boundary must preserve one global Account-owned identity, an independent recovery path, and normal tenant isolation while allowing each provider integration to arrive only when it is complete.

## Decision

Google, Discord, and Steam remain planned optional first-party integrations, not baseline launch or simultaneous-availability promises. Each provider may be advertised only after its provider-specific callback, collision, outage, unlink, recovery, deletion, and end-to-end authentication proof passes.

Initial provider delivery supports linking to an existing global FireMUD account and subsequent HTTPS sign-in. Provider-first account creation is deferred until separately approved.

Each link is global and unique by canonical `{provider, issuer, subject}`. It has no `tenantId`. Linking:

- requires an authenticated global account and recent ordinary reauthentication;
- completes only from a server-verified provider authorization response;
- never trusts a client-supplied external ID, email, username, or display name as proof of provider control;
- never automatically merges or links accounts because email addresses match; and
- fails closed into Account recovery or support when the provider subject is already linked elsewhere.

Every account retains a verified-email recovery path and at least one enabled Account-owned ordinary login mode. Provider access alone is not recovery authority. Unlinking requires recent reauthentication, cannot remove the last usable login or recovery path, emits security audit, and advances the Account auth generation so existing sessions are re-evaluated.

A provider outage makes new login or linking through that provider retriable. It does not invalidate already-issued FireMUD sessions, and password or verified-email-code fallback remains available.

Tenant roles cannot inspect or administer linked identities. Linking never creates tenant membership or alters characters, purchases, subscriptions, entitlements, Stripe customers, or payment instruments. Account security lock, global deletion, and compromise response remain Account-owned and remove or disable provider access through the normal global lifecycle.

Telnet continues to use password or verified-email-code login. External provider authorization is HTTPS-only; any later gameplay initiation uses the bounded HTTPS handoff rather than carrying provider credentials through Telnet.

## Consequences

- FireMUD retains a clear direction for convenient provider login without promising three incomplete integrations.
- Provider-first signup and automatic email-based account merging are unavailable.
- Each provider needs separate configuration, legal review, callback verification, failure behavior, and proof.
- Losing one provider does not eliminate Account-owned recovery or terminate healthy existing sessions.
- The current tenant-scoped, caller-asserted external-link surface is implementation drift and must not be exposed as supported authentication.

## Alternatives Considered

### Promise Google, Discord, and Steam Together

Rejected because the integrations have distinct verification and operating contracts and none is currently complete.

### Password and Email Only Permanently

Rejected as the permanent product boundary because provider login can materially improve browser onboarding once implemented safely. Password and verified-email code remain the baseline and fallback.

### Generic Configurable OAuth Provider

Rejected as the public contract because it obscures provider-specific issuer, subject, callback, lifecycle, and support requirements. Shared internal machinery may still reduce duplication.

## Implementation and Proof Obligations

Before any provider is advertised, implementation and focused proof must cover verified provider authorization, canonical issuer/subject normalization, global uniqueness, replay and CSRF protection, no email auto-merge, collisions, unlink safeguards, Account-owned fallback recovery, outage behavior, security-generation advancement, global deletion, tenant isolation, and end-to-end web login. The caller-asserted external-ID API and tenant-scoped schema must be removed or replaced rather than promoted.

## Reversibility and Revisit Triggers

Providers can be added or withdrawn independently without changing global account identity. Revisit provider-first signup if conversion evidence justifies its additional collision and recovery policy, or revisit Account-owned fallback only if an external identity platform becomes the explicitly accepted lifecycle and recovery authority.
