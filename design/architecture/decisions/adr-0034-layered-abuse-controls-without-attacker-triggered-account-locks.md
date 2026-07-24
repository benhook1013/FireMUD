# ADR 0034: Layered Abuse Controls Without Attacker-Triggered Account Locks

## Status

Accepted

## Implementation Status

Gateway and TCP Proxy transport controls plus focused gameplay and credential-path foundations exist, but the accepted abuse-control boundary is not complete. Trusted source propagation into every credential-bearing Account call, one Account-owned graduated throttling policy, fail-closed behavior for player-facing Cache/Rate-Limit Redis outages, and migration of Game Session from the current per-command Redis increment to the bounded in-process token bucket remain unimplemented or unproved. Multi-replica, shared-NAT, reset/eviction, and stable retry proof therefore remains incomplete.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `SF-1.3` Authentication, authorization, service identity, and secret handling
- Affected capabilities: `AA-1.3`, `AA-2.1`, `PO-2.4`, `PO-1.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SEC-05`

## Context

FireMUD needs to resist connection floods, credential stuffing, account takeover, spam, and pathological gameplay commands without making one service understand every abuse domain. The existing design assigns transport pressure to Gateway and TCP Proxy, credential abuse to Account, and post-authentication gameplay abuse to Game Session. That authority split is sound, but several important behaviors were unspecified or contradictory.

A simple failed-password threshold that durably locks an account lets an attacker deny access to any known username. Game Session was also described as applying login-attempt throttling even though Account is the credential authority. Account's authentication RPC does not currently carry trusted source context, so its documented per-IP policy is not enforceable consistently across Telnet, WebSocket, and HTTP paths. Finally, the current Game Session limiter performs a synchronous Redis increment for every command even though ordinary command-rate limiting is an abuse heuristic rather than correctness authority.

## Decision

### Layered Ownership

- Spring Cloud Gateway owns anonymous HTTP request, WebSocket establishment, and canonical client-IP pressure limits. TCP Proxy owns raw connection, per-socket, input-size, idle, buffer, protocol, and observed-client-IP limits.
- Edge components never decide credential correctness or mutate durable account security state.
- Account Service is the sole credential-abuse authority across password, verified-email code, REST, gRPC, Telnet, and WebSocket-derived login paths. Game Session forwards the attempt and consumes the canonical outcome; it does not maintain an independent failed-login policy.
- Game Session owns authenticated gameplay command budgets and content-aware gameplay abuse decisions. Moderation consequences remain with their established domain owners.

### Credential Abuse Policy

- Ordinary failed attempts feed graduated, bounded throttles using a combination of canonical source, normalized account candidate, and coarse global pressure. No one bucket is treated as proof of compromise.
- A failed-attempt threshold may delay or temporarily reject further attempts, but it must not transition an account to durable `security_locked`. This prevents an attacker from locking a victim by repeatedly submitting bad credentials.
- Durable `security_locked` is reserved for a verified or high-confidence compromise signal, an explicit account-security policy, or an audited operator action. Entering that state advances the account authority generation and follows the established recovery lifecycle.
- Unknown account, wrong secret, ineligible login mode, and ordinary candidate throttling use non-enumerating public behavior. `AUTH_ACCOUNT_LOCKED` is exposed only after sufficient identity proof or through the recovery path; it is not an account-existence oracle for arbitrary failed attempts.
- Account credential paths return stable bounded outcomes including invalid credentials, retry-later with bounded retry metadata, durable security lock, and abuse-control unavailable.

### Trusted Source Context and Availability

- Every credential-bearing call to Account carries server-derived source context, including canonical client address and connection/transport class where relevant. Gateway canonicalizes public HTTP/WebSocket addresses. It accepts a TCP Proxy promoted address only on the dedicated internal listener after authenticating the exact TCP Proxy workload identity, matching the configured certificate identity allowlist, validating the expected promoted-header schema/version, and verifying that the listener and network source are permitted to supply PROXY-derived context. Public listeners strip all promoted-address and proxy-context headers before classification. A missing, malformed, wrong-version, wrong-listener, or unauthenticated proxy context is rejected rather than trusted or silently downgraded to an internal source.
- Source identifiers stored in rate-limit keys are normalized or hashed and never become metric labels. Security audit access and retention govern any raw address retained in logs.
- Distributed credential counters use Cache/Rate-Limit Redis with bounded TTLs; they are not durable account authority and do not use Coordination Redis.
- If shared credential-abuse enforcement is unavailable in a player-facing environment, new credential-bearing authentication fails closed with a retryable canonical outcome. Existing authenticated sessions continue under their existing authority and revocation contracts.
- Per-IP limits are deliberately not the sole policy because carrier-grade NAT and shared networks can place many legitimate players behind one address. Operators tune edge buckets within platform hard bounds, while Account combines source and account-candidate signals.

### Gameplay Fast Path

- Normal per-session command-rate enforcement uses an in-process token bucket owned by the current session front end. It performs no network or datastore operation per command solely for rate limiting.
- A replacement session front end reads and consumes only the remaining portion of one externalized, bounded cumulative handoff budget for the active gameplay binding; each replacement consumes from that same budget rather than resetting it. A process move cannot grant an unbounded fresh allowance. Handoff-budget bookkeeping may use one shared operation per replacement, but ordinary commands remain on the local bucket and perform no per-command network or datastore work.
- Coarser shared account, source, tenant, or reconnect-abuse windows may use Cache/Rate-Limit Redis outside the per-command fast path. They are defense in depth, reset-tolerant, and must not determine gameplay ordering or whether an already accepted command happened.
- Limit outcomes use stable classes and bounded retry guidance. Tenant/game tuning follows the accepted settings model and cannot exceed platform hard bounds or operator caps.

## Consequences

- The service that understands the abused resource owns the policy without duplicating credential rules across transports.
- A known username cannot be trivially denied service through repeated bad passwords.
- New login availability depends on shared abuse enforcement in player-facing environments, while an outage does not eject already authenticated players.
- Ordinary gameplay avoids a synchronous Redis increment and rate-limit-store availability dependency on every command.
- A process handoff can temporarily lose fine-grained token-bucket history, but conservative initialization and optional coarse shared backstops bound that weakness. Exact global command-rate consistency is deliberately not promised.
- Trusted source propagation, generic public failures, recovery, tuning, privacy, and multi-replica proof add implementation and release-test obligations.

## Alternatives Considered

### Durable Account Lock After A Fixed Failure Threshold

This is simple and strongly slows guessing against one account, but lets attackers lock victims by username. Graduated temporary throttles provide protection without turning credential failures into a denial-of-service primitive.

### Centralize All Abuse Policy At Gateway

Gateway sees connection pressure but does not understand credential validity, account lifecycle, gameplay command cost, or moderation semantics. Giving it those decisions would duplicate domain policy and expand the edge trust boundary.

### Centralize All Limits At Account

Account can evaluate credentials but should not receive every gameplay command or understand tick, region, and content-aware costs. That would put a security service on the routine gameplay hot path.

### Use A Distributed Redis Counter For Every Gameplay Command

This preserves a more consistent rate window across process movement, but adds a network operation and shared-store dependency to every command. Exact global consistency is unnecessary for a bounded abuse heuristic; local enforcement plus coarse shared backstops is the accepted tradeoff.

## Implementation and Proof Obligations

- Extend every Account credential contract with trusted server-derived source context and prove spoofed public headers, unlisted workload identities, wrong listeners, malformed header versions, and untrusted PROXY sources cannot influence it.
- Implement one Account-owned password and email-code abuse policy shared by REST and gRPC paths, with bounded TTL counters on Cache/Rate-Limit Redis.
- Prove unknown-account, wrong-secret, throttled-candidate, and locked-account behavior does not provide a practical public enumeration oracle.
- Prove ordinary failed attempts cannot enter durable `security_locked`; prove a real security-lock transition audits, revokes, and enters recovery correctly.
- Fail new player-facing credential attempts closed when shared abuse enforcement is unavailable while leaving existing authenticated gameplay unaffected.
- Replace Game Session's per-command Redis rate-limit increment with a bounded in-process token bucket and prove its initial, refill, exhaustion, retry, handoff, cumulative replacement-budget, and conservative-reset behavior.
- Prove edge canonicalization occurs before rate-limit key derivation, including the authenticated TCP Proxy path and untrusted forwarded-header rejection.
- Exercise shared-NAT behavior, multi-replica attempts, credential-counter reset/eviction, reconnect churn, and stable retry classifications.

## Required Documentation Alignment

- `design/architecture/system-architecture-security.md`
- `design/architecture/system-architecture-gateway.md`
- `design/architecture/microservices/account-service/runtime-and-data.md`
- `design/architecture/microservices/account-service/api-contracts.md`
- `design/architecture/microservices/game-session-service/runtime-and-data.md`
- `design/project-management/implementation-tracking/player-access-and-session.md`

## Reversibility and Revisit Triggers

Bucket thresholds and algorithms are tunable within the accepted settings and cap model. Revisit the authority split only if FireMUD adopts a dedicated identity provider, a specialized edge-abuse service, or measured cross-session attacks that cannot be contained by Account-owned credential policy plus local and coarse shared gameplay limits. A proposal to restore a datastore operation to every gameplay command must include measured abuse benefit, latency, capacity, and outage evidence.
