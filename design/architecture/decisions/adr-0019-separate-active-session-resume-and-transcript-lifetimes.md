# ADR 0019: Separate Active Session, Resume, and Transcript Lifetimes

## Status

Accepted

## Implementation Status

The decision is accepted; implementation and proof remain partial. Durable bounded transcript storage and Redis caching exist, but immutable continuity/resume anchors, deadline enforcement in `PLAY`, token-refresh independence, repeated-episode behavior, and explicit-logout replay suppression remain incomplete or unproved. Current Game Session code still defaults `FIREMUD_AUTH_SESSION_EXPIRATION_MS` to one hour and does not enforce the five-minute continuity cap. Acceptance records the target decision, not completion; the obligations below define the remaining proof.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `AA-2.2` Reconnect, resume eligibility, and cross-device continuity
- Affected capabilities: `GR-1.4`, `AR-2.1`, `EA-3.4`, `SF-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SESSION-08`

## Context

FireMUD must distinguish token validity, continuously active gameplay, continuity after transport loss, Redis cleanup, and retained presentation context. These boundaries have different security, player-experience, and storage purposes. The live implementation currently relies primarily on a refreshable Redis TTL, does not enforce the configured disconnected-resume window in `PLAY`, and lacks immutable continuity timestamps.

The previously reconciled target separated the policies but described `gameplaySessionExpiresAt`, derived from JWT lifetime plus a safety margin, as an absolute gameplay-binding ceiling. Read literally, the one-hour JWT default plus five-minute margin could force a healthy long-running player through periodic gameplay-session recreation even though private player-delegation tokens are designed to rotate. Internal credential lifetime must not indirectly dictate uninterrupted player-session duration.

## Decision

JWT validity, active gameplay authorization, continuity-binding eligibility, disconnected-resume eligibility, physical storage, and transcript retention are separate authorities.

### Active Gameplay

- A continuously connected gameplay session may remain active while edge liveness is healthy and current account, membership, entitlement, revocation, fencing, and backend-token checks succeed.
- Receiver-specific private player-delegation JWTs rotate on their bounded cadence. Each token remains valid only through its own `exp`; rotation neither revives an expired token nor forces a healthy player through fresh `PLAY` merely because the previous token aged out.
- Game Session schedules refresh before `exp` and retries a transient refresh failure with bounded backoff while the current receiver token remains valid. A refresh failure does not move `continuityBindingExpiresAt`, `disconnectAt`, or `resumeDeadline`, and it does not grant a grace period beyond the current token's `exp`.
- If no replacement receiver token is installed before `exp`, Game Session fails closed for backend-authenticated actions, transitions the active binding to token-expired/disconnected state, and closes the gameplay socket. The player-visible outcome is `AUTH_TOKEN_EXPIRED` when the token simply expires or refresh remains unavailable, and `AUTH_SESSION_REVOKED` when Account rejects refresh because authority was revoked or blocked; neither outcome is resolved by retrying the expired token.
- Recovery after receiver-token expiry or refresh failure is explicit: the client obtains a fresh bootstrap/connect token where the transport requires it, reconnects, sends fresh `LOGIN`, and completes fresh `PLAY`. If the old binding still has a valid continuity/disconnection episode and current authority checks pass, that fresh admission may consume the episode as a resume; otherwise it creates a new binding. A receiver-token refresh is never a client-visible reauthentication substitute and never permits token-only reentry.
- Account or tenant authority revocation and loss of required authority remain immediate terminal conditions for the old binding. Fresh `LOGIN`/`PLAY` cannot consume that binding's episode as a resume; only a later independent fresh admission after authority is restored may create a new binding. This decision does not create an immortal authorization grant.
- If FireMUD later requires a maximum continuously active player-session lifetime, it must be an explicit security/product policy rather than an accidental consequence of private player-delegation token configuration.

### Continuity and Resume

On successful gameplay admission, Game Session records an immutable continuity anchor:

`continuityBindingExpiresAt = admissionAt + min(FIREMUD_AUTH_SESSION_EXPIRATION_MS, 300000 ms)`

where `FIREMUD_AUTH_SESSION_EXPIRATION_MS` is the independent logical active-session/continuity horizon and the 300,000 millisecond cap is part of this contract. It is not derived from JWT lifetime or changed by private player-delegation token rotation. Passing this anchor does not itself kick a continuously connected, currently authorized player. It means that after the next transport loss the old binding cannot be resumed.

Each connected-to-disconnected transition starts one immutable disconnection episode. At that transition:

`resumeDeadline = min(continuityBindingExpiresAt, disconnectAt + effective resume-window-ms)`

- Resume requires the current time to be before both limits and requires fresh identity, membership, entitlement, revocation, and uniqueness checks.
- `disconnectAt` and `resumeDeadline` are immutable within that episode. Failed reconnect attempts, token rotation, takeover attempts, Redis TTL refresh, and transcript retention cannot move them.
- A successful resume consumes the current disconnection episode and returns the binding to connected state. A later genuine transport loss starts a new episode with a new `disconnectAt` and `resumeDeadline`, still capped by the binding's original immutable `continuityBindingExpiresAt`.
- After either limit, the old binding is non-resumable even if data remains. A successful current `LOGIN` and `PLAY` may perform fresh admission and create a new binding; that is not continuation of the expired binding.

### Storage, Transcript, and Logout

- Redis TTL is physical cleanup metadata. Every gameplay-binding TTL refresh is capped at the remaining `continuityBindingExpiresAt` lifetime, for example `min(requestedTtlMs, max(0, continuityBindingExpiresAt - now))`, and must never create a sliding deadline. Key presence never grants resume authority, and early key loss makes the binding non-resumable rather than reconstructing authority from other projections.
- Resume transcript retention is an independent bounded presentation policy. Transcript existence cannot prove identity or extend active or resume authority.
- Explicit gameplay `LOGOUT` immediately terminates continuity/resume authority and makes the binding's private transcript non-replayable. Physical deletion of transcript rows or cache entries may complete asynchronously, but replay must honor the authoritative non-replayable state immediately. After a fresh non-logout `LOGIN` and `PLAY`, retained transcript context may replay subject to current identity, authorization, and gameplay scope; the terminated binding's logged-out context must not replay.

## Consequences

- Long uninterrupted play is not coupled to the one-hour private player-delegation token default.
- Short disconnected-resume windows still bound unattended continuity risk, and stale Redis or transcript data cannot revive a binding.
- Fresh admission provides a player-friendly fallback after continuity expiry without pretending that old transient state resumed.
- The runtime must persist and evaluate additional logical timestamps independently of Redis expiration.
- Operations and tests must distinguish active-token refresh, continuity expiry, resume expiry, fresh-entry fallback, logout, and transcript cleanup.
- The independent continuity horizon is bounded separately from JWT configuration and no longer changes uninterrupted active-session duration.

## Alternatives Considered

### Treat the Derived Anchor as an Active-Session Cutoff

This gives a simple hard cap but couples player-session duration to private player-delegation token policy and can interrupt healthy long-running play.

### Use One TTL for Tokens, Bindings, Resume, and Transcript

This is simpler to implement but makes physical Redis behavior security-sensitive and couples credential security, continuity, and storage costs.

### Use a Sliding Resume Binding

Refreshing the logical anchor on activity or token rotation improves continuity but can make automation or steady activity preserve bindings indefinitely and weakens cleanup and stale-binding rejection.

### Disable Resume

Fresh admission after every disconnect is simpler and more conservative but materially degrades continuity and loses bounded in-flight presentation state.

## Implementation and Proof Obligations

- Persist immutable `continuityBindingExpiresAt` plus one immutable `disconnectAt`/`resumeDeadline` pair per disconnection episode, consume that episode on successful resume, and enforce the current pair in `PLAY` admission.
- Prove token refresh and healthy uninterrupted play independently of the continuity anchor.
- Prove bounded retry while the receiver token remains valid, the `AUTH_TOKEN_EXPIRED` outcome when no replacement exists by `exp`, the `AUTH_SESSION_REVOKED` outcome for rejected authority, socket termination, and fresh `LOGIN`/`PLAY` reconnect or resume behavior.
- Prove boundary behavior immediately before, at, and after both continuity and resume deadlines.
- Prove repeated failed reconnects cannot extend one episode, successful resume closes it, and a later disconnect creates a new bounded episode without moving `continuityBindingExpiresAt`.
- Prove Redis saves, TTL refresh capped at the remaining `continuityBindingExpiresAt`, restart, failover, and stale-key recovery cannot move or bypass logical deadlines.
- Prove current subject, membership, entitlement, revocation, and uniqueness checks on every resume.
- Prove stale bindings fall through to fresh admission only after full current authorization and receive a new identity and anchor.
- Prove transcript bounds independently and prove explicit logout immediately prevents later private replay from the terminated binding, without requiring physical transcript deletion to complete synchronously.

## Reversibility and Revisit Triggers

The independent policy boundaries can gain explicit active-session maximum or idle-session policies later without weakening resume rules. Revisit when security requires periodic player reauthentication during uninterrupted play, when measured storage pressure makes the continuity horizon unsuitable, or when product/security policy changes the current rule for replay after fresh non-logout admission. Explicit logout remains non-replayable unless a future decision changes that rule.

## Required Documentation Alignment

- `design/architecture/system-architecture-reconnection.md`
- `design/architecture/system-architecture-session-behavior.md`
- `design/architecture/system-architecture-redis.md`
- `design/architecture/system-architecture-input-output-and-presentation.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
