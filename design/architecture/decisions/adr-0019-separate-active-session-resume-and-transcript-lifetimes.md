# ADR 0019: Separate Active Session, Resume, and Transcript Lifetimes

## Status

Accepted

## Implementation Status

The decision is accepted; implementation and proof remain partial. Durable bounded semantic reconnect-context storage and Redis caching exist, but immutable continuity/resume anchors, deadline enforcement in `PLAY`, token-refresh independence, repeated-episode behavior, and explicit-logout replay suppression remain incomplete or unproved. The target `FIREMUD_AUTH_SESSION_EXPIRATION_MS` default is `300000` ms with an inclusive valid range of `1..300000` ms; current Game Session code still defaults it to one hour (`3600000` ms) and does not enforce that range. Acceptance records the target decision, not completion; the obligations below define the remaining proof.

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `AA-2.2` Reconnect, resume eligibility, and cross-device continuity
- Affected capabilities: `GR-1.4`, `AR-2.1`, `EA-3.4`, `SF-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SESSION-08`
- Human review status: Completed
- Human review date: 2026-07-18
- Human review disposition: Revised
- Review source: `SESSION-08`

## Context

FireMUD must distinguish token validity, continuously active gameplay, continuity after transport loss, Redis cleanup, and retained presentation context. These boundaries have different security, player-experience, and storage purposes. The live implementation currently relies primarily on a refreshable Redis TTL, does not enforce the configured disconnected-resume window in `PLAY`, and lacks immutable continuity timestamps.

The previously reconciled target separated the policies but described `gameplaySessionExpiresAt`, derived from JWT lifetime plus a safety margin, as an absolute gameplay-binding ceiling. Read literally, the one-hour JWT default plus five-minute margin could force a healthy long-running player through periodic gameplay-session recreation even though private player-delegation tokens are designed to rotate. Internal credential lifetime must not indirectly dictate uninterrupted player-session duration.

## Decision

JWT validity, active gameplay authorization, continuity-binding eligibility, disconnected-resume eligibility, physical storage, and semantic reconnect-context retention are separate authorities.

Active-session authority is the authoritative gameplay binding plus current account, membership, entitlement, revocation, fencing, and lease state. It is not an inference from an expiring Redis key, reconnect cache, or retained semantic context. The continuity binding and its Redis representation are a bounded recovery projection for a disconnected episode: cache refresh can never extend active-session or resume authority, and cache expiry or loss can only make the binding non-resumable; it cannot create, extend, or revoke an otherwise authoritative connected session.

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

where `FIREMUD_AUTH_SESSION_EXPIRATION_MS` is the independent logical active-session/continuity horizon, has target default `300000` ms, and must be validated at startup/preflight as a finite integer in the inclusive range `1..300000` ms. Zero, negative, non-integral, non-finite, and above-maximum values are configuration errors; the live one-hour Game Session default is implementation drift, not a valid target override. The 300,000 millisecond cap is part of this contract. It is not derived from JWT lifetime or changed by private player-delegation token rotation. Passing this anchor does not itself kick a continuously connected, currently authorized player. It means that after the next transport loss the old binding cannot be resumed.

Each connected-to-disconnected transition starts one immutable disconnection episode. At that transition:

`resumeDeadline = min(continuityBindingExpiresAt, disconnectAt + effective resume-window-ms)`

- Resume requires the current time to be before both limits and requires current, fail-closed identity, membership, revocation, uniqueness, lease, and gameplay-scope checks. Entitlement freshness is governed by ADR 0028: an eligible positive last-known-good entitlement may supply the entitlement input only for the exact same still-resumable binding and only when the recovery is non-expanding; fresh entitlement remains required for new commitments, fresh admission, changed realm/target bindings, or any other operation covered by ADR 0028's strict class.
- `disconnectAt` and `resumeDeadline` are immutable within that episode. Failed reconnect attempts, token rotation, takeover attempts, Redis TTL refresh, and semantic reconnect-context retention cannot move them.
- A successful resume consumes the current disconnection episode and returns the binding to connected state. A later genuine transport loss starts a new episode with a new `disconnectAt` and `resumeDeadline`, still capped by the binding's original immutable `continuityBindingExpiresAt`.
- After either limit, the old binding is non-resumable even if data remains. A successful current `LOGIN` and `PLAY` may perform fresh admission and create a new binding; that is not continuation of the expired binding.

ADR 0028 is authoritative when this decision's resume rules and entitlement-freshness policy intersect. That precedence changes only the entitlement input permitted for bounded same-binding continuity; it does not relax the current identity, membership, revocation, uniqueness, lease, or gameplay-scope checks required to resume.

### Storage, Semantic Reconnect Context, and Logout

- Redis TTL is physical cleanup metadata. Every gameplay-binding refresh must atomically preserve the earliest of the existing physical expiry, the requested refresh deadline, and `continuityBindingExpiresAt`; a plain `PEXPIREAT` or relative `PEXPIRE` is insufficient because it can extend an already earlier cleanup deadline. The compare-and-set/server-side update must not recreate a missing or expired binding. The effective physical deadline may therefore be earlier, but no retry, concurrent update, or failover replay may move it later, set it after `continuityBindingExpiresAt`, or create a sliding deadline. Key presence never grants resume authority, and early key loss makes the binding non-resumable rather than reconstructing authority from other projections.
- Semantic reconnect-context retention is an independent bounded presentation policy. Context existence cannot prove identity or extend active or resume authority.
- [ADR 0134](./adr-0134-bounded-durable-semantic-reconnect-context.md) clarifies the settings vocabulary without changing this ADR's lifetime or authority ownership: `firemud.reconnection.policy.*` governs resume eligibility, while `firemud.reconnection.buffer.*` governs only bounded semantic reconnect-context retention/resource controls. Its hard ceiling is absolute over the complete scope-bound persisted envelope; message/line floors and the soft ceiling are subordinate best-effort preferences, and retention never grants resume or replay authority. Current oversized-single-entry and namespace/schema-envelope enforcement remain implementation/proof gaps.
- Explicit gameplay `LOGOUT` immediately terminates continuity/resume authority and must durably commit a binding-scoped replay-revocation marker, including a monotonic termination fence, in Game Session's authoritative durable session/reconnect-context store before acknowledging logout. Replay/restore must check that marker before using any Redis binding or reconnect-context cache, including after Redis loss or restart; marker retention must cover the maximum reconnect-context horizon. A missing or ambiguous marker for a binding claiming logout fails closed. Physical deletion of reconnect-context rows or cache entries may complete asynchronously. After a fresh non-logout `LOGIN` and `PLAY`, retained semantic context may replay only when it belongs to a different, currently authorized continuity episode; the terminated binding's logged-out context must never replay, even when its rows or cache entries remain and even when the new admission uses the same account, character, or realm.
- The logout transition is one authoritative compare-and-set/transaction: binding state moves out of `connected`, the termination fence advances, and the replay-revocation marker is committed together before success is acknowledged. In-flight authorization, refresh, resume, and replay operations carry the expected binding/fence identity and lose the race when logout advances it; an unavailable or ambiguous authoritative store fails logout and all dependent operations closed rather than allowing a post-logout action.

## Consequences

- Long uninterrupted play is not coupled to the one-hour private player-delegation token default.
- Short disconnected-resume windows still bound unattended continuity risk, and stale Redis or semantic reconnect-context data cannot revive a binding.
- Fresh admission provides a player-friendly fallback after continuity expiry without pretending that old transient state resumed.
- The runtime must persist and evaluate additional logical timestamps independently of Redis expiration.
- Operations and tests must distinguish active-token refresh, continuity expiry, resume expiry, fresh-entry fallback, logout, and semantic reconnect-context cleanup.
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
- Prove Redis saves, `PEXPIREAT` or an atomic equivalent capped at `continuityBindingExpiresAt`, restart, failover, and stale-key recovery cannot move or bypass logical deadlines.
- Prove current subject, membership, revocation, uniqueness, lease, and gameplay-scope checks on every resume, and prove that entitlement input follows ADR 0028: eligible positive last-known-good state is accepted only for exact same-binding non-expanding continuity, while fresh entitlement is required for new commitments, fresh admission, changed bindings, and unsafe or expired continuity.
- Prove stale bindings fall through to fresh admission only after full current authorization and receive a new identity and anchor.
- Prove semantic reconnect-context bounds independently and prove the durable explicit-logout replay-revocation marker survives Redis loss/restart and is checked before later private replay from the terminated binding, without requiring physical reconnect-context deletion to complete synchronously. Prove that audit/diagnostic evidence distinguishes active-binding authority, continuity-cache state, and termination fences without treating context contents as authority.
- Prove concurrent `LOGOUT` versus `PLAY`, token refresh, resume, and replay: once the termination fence wins, no in-flight operation can authorize or recreate the binding, and authoritative-store loss fails closed.

## Reversibility and Revisit Triggers

The independent policy boundaries can gain explicit active-session maximum or idle-session policies later without weakening resume rules. Revisit when security requires periodic player reauthentication during uninterrupted play, when measured storage pressure makes the continuity horizon unsuitable, or when product/security policy changes the current rule for replay after fresh non-logout admission. Explicit logout remains non-replayable unless a future decision changes that rule.

## Required Documentation Alignment

- [Reconnection](../system-architecture-reconnection.md)
- [Session behavior](../system-architecture-session-behavior.md)
- [Redis](../system-architecture-redis.md)
- [Input, output, and presentation](../system-architecture-input-output-and-presentation.md)
- [Environment and secrets catalog](../infrastructure/environment-and-secrets-catalog.md)
