# FireMUD System Architecture: Reconnection Strategy

FireMUD targets seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts using a **layered reconnection model** and **Redis-backed session state**. The canonical recovery contract remains explicit and protocol-visible at the edge so third-party MUD clients can recover reliably, while first-party clients may automate that same recovery path to reduce user-visible friction. Per [ADR 0013](./decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md), ordinary non-edge restarts use bounded invisible recovery when the edge socket, healthy same-type replacement capacity, and required shared authority remain available. The ordinary functional target is 10 seconds; if continuation authority cannot be established safely, hidden recovery ends immediately, otherwise it stops after 30 seconds and falls back to `1013/backend_unavailable` rather than retaining an indefinitely stalled connection.

---

## Implemented Status

- **Session takeover and resume** – Game Session emits `gamesession.session.takeover` and `gamesession.session.resume` counters and rebinds Redis tick/command queues on reconnect/takeover. The active uniqueness key is `{tenantId, gameInstanceId, characterId}`.
- **Telnet and WebSocket parity** – The current implemented shared gameplay authentication and lobby-binding flow after an actual edge reconnect is `LOGIN` then `PLAY` for both transports. The current connect-token/`PLAY` admission path may create membership implicitly where the existing admission contract permits it; an explicit first-time `JOIN` command remains target work and is not implemented status. First-party WebSocket clients must obtain a fresh connect token before opening `/ws/game/**`.
- **Runtime authority checks** – `PLAY` now performs a first-pass runtime membership and tenant-entitlement check before fresh entry or resume/takeover. This closes the earlier gap where reconnect semantics relied only on Redis gameplay identity plus a fresh `LOGIN`.
- **Continuity configuration drift** – The target continuity policy caps `FIREMUD_AUTH_SESSION_EXPIRATION_MS` at five minutes independently of JWT lifetime. Current Game Session code still defaults this setting to one hour and does not enforce the target cap.
- **Remaining work** – Durable bounded resume-transcript persistence is live through ordered `resume_transcript_entry` rows, with Redis as a best-effort hot cache and fresh authoritative redraw after replay. Admin-driven forced session transfers remain planned future steps. FireMUD does not attempt to replay or reconstruct ambiguously delivered gameplay commands after failure; an in-flight command may be lost under the at-most-once edge contract. Gateway upstream rebind and focused downstream-worker restart proof exist, but the complete authenticated real-Game-Session replacement sequence, lifecycle classification, presence convergence, authority continuation, elapsed-time bounds, and stalled-input behavior still require implementation and proof before ADR 0013's availability target can be claimed as complete.

## Reconnection Layers

| Layer | Responsibility |
| --- | --- |
| **TCP Proxy Service** | Parses Telnet input and clears buffered commands; emits a best-effort disconnect notification to Game Session over an internal-only gRPC event sink |
| **Spring Cloud Gateway** | Stateless WebSocket router; enforces the close-code taxonomy (for example `1013/backend_unavailable` for sustained outages) and resumes routing once clients reconnect |
| **Game Session Service** | Restores session from Redis; rebinds socket, region, and timers when the edge connection remains healthy |

Each layer handles fault tolerance independently.
Reauthentication is required after loss of the client-facing edge transport (the client-to-Gateway WebSocket or Telnet socket/serving TCP Proxy edge path), or when the active binding's receiver token expires/refresh fails or Account authority is revoked. A retained-edge upstream rebind, including a qualifying hidden Game Session restart, preserves the existing session and does not repeat `LOGIN` or `PLAY`.
Edge disconnects remain the canonical explicit recovery boundary. An ordinary non-edge restart qualifies for invisible recovery only while the edge socket, healthy same-type replacement capacity, and required shared authority remain available. Gateway retains the edge socket and rebinds its Game Session upstream within the bounded window; replaceable downstream workers recover behind the Game Session caller connection. Any in-flight gameplay command at the moment of a hard failure may still be lost, consistent with the at-most-once edge delivery semantics in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Missing shared authority, unsafe continuation authority or ownership ambiguity, terminal session policy, loss of the edge socket, or exhaustion of the 30-second window makes recovery explicit rather than invisible.
Spring Cloud Gateway should fail over cleanly at the fleet level: unaffected sockets on other healthy Gateway instances remain up, and new gameplay handshakes should continue against healthy instances. Clients whose live WebSocket or Telnet bridge was terminated on the specific Gateway process that restarted or crashed still see a visible retryable edge failure because that edge-owned socket was lost. This is an accepted current platform limitation, not a hidden bug: FireMUD intentionally prioritizes non-edge restart invisibility and fast reconnect over explicit cross-Gateway live socket handoff. Future work could revisit true edge connection handoff, but it is not a current design goal. Those affected Web clients must request a fresh connect token, open a new WebSocket, and issue `LOGIN` again. Once reconnected, the gateway resumes routing and Game Session uses Redis-backed session state to decide whether to resume or start fresh after the client re-selects gameplay scope with `PLAY`. Restart classification remains canonical: planned Gateway drain is surfaced by the TCP Proxy as `logout` with `gateway_restart` context when the bridge-drain signal is delivered, while abrupt or unattributed loss of the specific Gateway bridge/socket serving a client is surfaced immediately as `backend_unavailable` on Telnet and `internal_error` retry class when WebSocket close metadata is missing.
TCP Proxy restarts drop Telnet clients, who must reconnect manually.
If the Gateway link is unavailable (for example during gateway deploys or outages), the TCP Proxy applies the bridge-availability contract from Gateway/Protocol Bridging, closes with `backend_unavailable` when gameplay admission is unavailable, and clients reconnect using backoff, then reauthenticate with `LOGIN` and re-enter gameplay scope via `PLAY`.

---

## Layer Behavior Breakdown

### TCP Proxy Service (Telnet Clients)

- Accepts raw TCP input and assembles it into commands.
- Buffers input **during connection**, but **clears on disconnect**; no gameplay state is preserved across reconnects.
- Emits a `NotifyDisconnect` event to the Game Session Service over an internal-only gRPC event sink as a best-effort, at-least-once hint when Telnet sockets close. Game Session must treat this stream as idempotent and advisory only, never as the sole source of truth for session liveness; it keys consumption by `{proxyConnectionId, disconnectSequence}` so late or duplicate events are safe to ignore.
- Runtime options such as the listening port and Spring Cloud Gateway WebSocket URL are configured via `TCP_PROXY_PORT` and `GATEWAY_WS_URL` (see the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md#environment-variables)).

For the **concrete `NotifyDisconnect` message shape and transport behaviour** – including retry windows, transport vs application-level failures, and the exact event fields – treat the TCP Proxy Service design’s **Service Interactions** section as canonical. This document is canonical for the **cross-service, behaviour-level contract**; see the **NotifyDisconnect Behavioral Contract (Summary)** section below for how this stream fits into the overall reconnection and liveness model.

### Spring Cloud Gateway (Web Clients)

- WebSocket router with no authoritative or durable gameplay/auth/session state; it may hold only bounded connection-local recovery state while an edge socket remains open
- Resumes routing once Web clients reconnect their WebSocket connections after a blip or restart
- Holds no authoritative gameplay, auth, or resumable session state

> TCP Proxy restarts drop Telnet connections.
> Spring Cloud Gateway restarts temporarily disconnect Web clients; clients must request a fresh connect token, reconnect their WebSocket, re-`LOGIN`, and re-`PLAY`, after which Game Session reloads state from Redis if available.
> Spring Cloud Gateway restarts disconnect Telnet clients under the canonical two-path rule: planned drains map to `logout` with `gateway_restart` context when the deterministic bridge-drain signal is received, while abrupt or unattributed bridge loss is surfaced immediately as `backend_unavailable`.

### Game Session Service

- Uses Redis to store session state such as command queues, tick participation, and retry coordination. Reconnect logic restores these details and reattaches the player to the actor's durable gameplay state.
- On reconnect, rebinds:
  - Socket connection
  - Tick region context
  - Timers and in-flight actions

> 🔗 Full structure of Redis session keys is documented in [Session Keys and Gameplay Binding](./system-architecture-redis.md#session-keys-and-gameplay-binding).
> See also the [Game Session Service design](./microservices/game-session-service/README.md#redis-keys) for how session state is stored for reconnect recovery.

---

## When Reauthentication Is Required

Clients must send a `LOGIN` command and complete `PLAY` **after an actual client-facing edge disconnect or an authentication invalidation that terminates the active binding**, such as:

- TCP loss (Telnet clients)
- WebSocket loss (Web clients)
- Receiver-token expiry or refresh failure: fresh `LOGIN`/`PLAY` may consume the existing episode as a resume when `continuityBindingExpiresAt`, `resumeDeadline`, and current Account authority remain valid.
- Account or tenant authority revocation: the old binding is terminal and non-resumable; fresh `LOGIN`/`PLAY` cannot consume its episode. A later independent fresh admission is possible only if authority is restored.

A retained-edge upstream rebind, including a qualifying hidden Game Session restart, is not a client disconnect and does not repeat `LOGIN` or `PLAY`.

After `LOGIN` succeeds, clients must re-establish gameplay scope by selecting a world, optional realm, and character via the lobby commands (`WORLDS`, `REALMS <world>`, `CHARS <world> [realm]`, and `PLAY <world> [realm] [character]`) as defined in [Tenant Selection for Gameplay](./system-architecture-authentication.md#tenant-selection-for-gameplay-lobby-selection). This `LOGIN` → `PLAY` sequence is mandatory for both Telnet and WebSocket reconnect flows in this multi-tenant platform; first-party WebSocket reconnects must also acquire a fresh connect token before the `/ws/game/**` handshake. Gameplay commands are not admitted before `PLAY` except in explicitly documented dev/test bypass modes. If Telnet smart-client attach hints return later, they should ride hidden MCP metadata on the new TCP connection and remain advisory only.

Redis-backed session state allows resumable recovery when the gameplay binding is still logically valid, or a fresh login when it is not. Logical binding expiry and physical Redis deletion are separate:

- **Continuity-binding expiry** – On successful gameplay admission at `admissionAt`, Game Session sets the immutable `continuityBindingExpiresAt` anchor:

  `continuityBindingExpiresAt = admissionAt + session_expiration_ms`

  Passing this anchor does not itself end a continuously connected, currently authorized gameplay session. It means the old binding cannot resume after a later transport loss. A resume, takeover, reconnect, or backend-token rotation may update socket and token metadata, but never moves this anchor. For a binding disconnected or suspended at `disconnectAt`, the resume deadline is:

  `resumeDeadline = min(continuityBindingExpiresAt, disconnectAt + effective resume-window-ms)`

  Each connected-to-disconnected transition creates one immutable disconnection episode. Reconnect attempts cannot move that episode's `disconnectAt` or `resumeDeadline`. A successful resume consumes the episode and returns the binding to connected state; a later genuine transport loss creates a new episode and deadline, still capped by the original `continuityBindingExpiresAt`. Resume requires the current time to be before both limits and still requires current identity, membership, entitlement, and revocation checks. A genuinely fresh `PLAY` admission creates a new binding and anchor; it does not extend the old binding or its current resume window.
- **Physical Redis deletion** – The Redis key TTL is storage cleanup for the binding. Expiration processing, failover, or AOF replay can leave a key present after logical expiry, while cleanup can remove it earlier. Every gameplay-binding TTL refresh is capped at the remaining `continuityBindingExpiresAt` lifetime, for example `min(requestedTtlMs, max(0, continuityBindingExpiresAt - now))`. Key presence is never permission to resume, and refreshing the physical TTL must never rewrite `continuityBindingExpiresAt`.

Target state treats `session_expiration_ms` as the independent gameplay-continuity policy supplied by `FIREMUD_AUTH_SESSION_EXPIRATION_MS` and caps its effective value at `300000` ms (five minutes), regardless of JWT lifetime or issued-token cleanup margin. The current Game Session default remains `3600000` ms (one hour) and does not enforce that target cap. This setting is not a JWT validity period or an uninterrupted-play cutoff: every server-side JWT remains valid only through its own `exp`, while healthy active sessions rotate backend tokens. Rotation cannot extend the continuity anchor or resume deadline. Transcript retention under `firemud.reconnection.buffer.*` is independent and never extends resume eligibility.

Resume is authorized from current identity and current membership/entitlement authority, not from the previous backend token alone. After a fresh successful `LOGIN`, Game Session must rebind any resumed gameplay session to a fresh backend token and reject resume if current membership authority for the tenant has been removed.

If the prior resumable gameplay state is stale or partially missing, Game Session should prefer invisible fresh entry whenever current `PLAY` admission is still valid. Missing room or game-instance resume context is not a player-facing failure by itself; the normal outcome is a successful fresh `PLAY` that rebinds the session to canonical entry state.

> 🧭 For full details on `LOGIN` behavior, argument formats, and session flow, see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow)

Gameplay command idempotency for reconnects is intentionally simple from the client’s perspective: Telnet and WebSocket clients treat commands as fire-and-forget. They do not attach idempotency keys or effect identifiers to individual commands in the text protocol; idempotency is handled internally by Game Session and domain services as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#gameplay-command-idempotency-client-view) and [Transactions & Idempotency](./system-architecture-transactions.md).

### Example Recovery Outcomes

Player-visible recovery output should stay helpful and protocol-shaped rather than surfacing backend jargon.

Successful reconnect after a normal disconnect:

```text
OK LOGIN Logged in as demo@example.com
OK PLAY Entered world: demo
<fresh LOOK or prompt output follows>
```

Stale or expired resumable state where a fresh `PLAY` can still be admitted should not force the player to type the same command twice. The normal outcome is still a successful fresh entry:

```text
OK LOGIN Logged in as demo@example.com
OK PLAY Entered world: demo
<fresh LOOK or prompt output follows>
```

Only failures that genuinely require player or client action should surface as errors, for example access revocation, missing entitlements, or backend unavailability.

Reconstructed session state after resume or fresh-entry fallback is intentionally bounded:

- current authenticated gameplay binding;
- current room/view state via fresh `LOOK`-style redraw or rerun;
- current tick/region participation and timers that still exist in shared authoritative state.

What is intentionally not replayed:

- pre-disconnect transport bytes or frames;
- partially delivered prompt/output text;
- volatile in-memory command buffering that did not survive the disconnect or restart.

---

## Multi-Client and Session Takeover

Gameplay resumes cleanly when a session is resumed — whether due to reconnect or takeover.

> 🔄 For full takeover behavior, including forced logins from a different client and Redis socket rebinding, see [Authentication & Authorization](./system-architecture-authentication.md#multi-client-behavior-and-session-takeover).

At most one active gameplay binding is supported per uniqueness key `{tenantId, gameInstanceId, characterId}` at any point in time.

The active gameplay identity is `characterId`. When a new client successfully issues `LOGIN` for an identity that is already bound to another connection (whether Telnet or WebSocket), Game Session treats this as a **takeover**:

- The previous connection is disconnected or demoted according to the takeover rules in the authentication design.
- No ordering guarantees are provided between the last few commands on the old connection and the first commands on the new one; only **per-connection FIFO** is maintained as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
- Clients and tools must not assume that keeping multiple concurrent connections (for example Telnet + Web) to the same character will preserve any cross-connection ordering; instead, they should rely on the single active binding and takeover semantics.

---

## Resume vs Reload Scenarios

| Event | Result |
| --- | --- |
| Actual client-edge disconnect (TCP/WebSocket) | Requires new `LOGIN` and `PLAY`; may resume via Redis |
| Receiver-token expiry or refresh failure | Game Session terminates the active binding, records the disconnection episode, and requires fresh `LOGIN` and `PLAY`; re-admission may consume that episode as a resume while continuity and current Account authority remain valid |
| Account or tenant authority revocation | Game Session terminates the active binding and makes the old episode non-resumable; fresh `LOGIN` and `PLAY` cannot consume it, and only later independent fresh admission after authority restoration can create a new binding |
| TCP Proxy Service restart | Telnet clients disconnected; new `LOGIN` and `PLAY` required |
| Spring Cloud Gateway restart | Fleet-level behavior should stay largely invisible: unaffected sockets on other healthy Gateway instances remain up and new handshakes continue. Connections bound to the specific Gateway instance being drained or crashing are visible failures. Planned drains should emit `1000/logout` (`subreason=gateway_restart`) for affected WebSocket sessions. Graceful unplanned failures may emit `1011/internal_error`, while hard crashes may drop transport without a close frame; clients treat missing close metadata as the `internal_error` retry class and must fetch a fresh connect token before reopening `/ws/game/**`. Telnet clients follow the same scope split: unaffected sessions bridged through other healthy Gateway instances continue normally, while planned drains on the serving bridge are surfaced by the proxy as `logout` (with `gateway_restart` context when available) and abrupt loss of the serving bridge is surfaced immediately as `backend_unavailable`. |
| Lease move / gameplay shard handoff | Ordinary in-cluster lease rebalancing remains internal to the Game Session layer and is not a distinct edge-visible event in the current contract; session front-end pods may continue serving the socket while forwarding region-owned work to the new lease owner. Forwarded requests use lease/epoch fencing. If fencing fails before gameplay side effects begin, the command is rejected visibly; if safe retry is possible, it is retried only behind effect/idempotency guards after ownership refresh; if ownership remains ambiguous, the session falls back to the existing structured command-failure or reconnect behavior rather than silently dropping or partially applying the command. If a future design introduces client-visible handoff semantics, the signal and reconnection/backoff policy must be defined explicitly in the Gateway and Protocol Bridging contracts. |
| Gateway ↔ Game Session link degraded (short window) | When the edge socket remains healthy and replacement Game Session capacity plus shared authority remain available, Gateway retains the client socket, buffers accepted input within its bound, and rebinds the upstream. Rebindable upstream lifecycle or transport loss is not itself a client disconnect. Game Session may instead return bounded explicit per-command errors while reachable. |
| Gateway ↔ Game Session link degraded (`unreachable` sustained) | Hidden recovery terminates immediately if continuation authority cannot be established safely; otherwise it stops after 30 seconds. Gateway closes affected WebSocket sessions with `1013` (`backend_unavailable`) and clients reconnect with backoff. Telnet clients receive the same result when Gateway closes the still-established proxy bridge or the TCP Proxy cannot establish or maintain that edge bridge. |
| Game Session Service restart | An ordinary qualifying single-instance restart has a 10-second functional target and is invisible while the edge socket, healthy replacement capacity, and shared authority remain available. Gateway retains the edge socket, reuses its stable transport identity, and the replacement continues current server-side session authority without another `LOGIN` or `PLAY`. Full real-service proof remains incomplete. |
| Manual re-`LOGIN` from same character | Treated as reconnect only when the binding is present, its logical continuity and episode deadlines remain valid, and current identity, membership, entitlement, revocation, and uniqueness checks all pass; otherwise admission is fresh |
| Gameplay binding logically expired or Redis key physically missing | Treated as non-resumable; fresh login and gameplay admission start a new binding |
| New client logs in as same character | Old session terminated; new one resumes control |

---

## Design Principles

- Redis stores:
  - Socket bindings and session metadata
  - Queued commands and tick state
- Reconstructible timer/retry scheduling metadata; authoritative actor cooldown state remains outside session state
- Gateway governs bounded upstream transport rebind while Game Session governs session continuation authority, gameplay deduplication, and shared-state restoration
- Non-edge gameplay services should remain replaceable/stateless workers against shared state; if restarting one of them visibly disconnects clients inside ADR 0013's qualifying conditions and recovery window, the system should treat that as an implementation or proof gap
- The canonical recovery path after an actual client transport disconnect remains explicit and protocol-visible for all clients
- Third-party MUD clients must be able to recover cleanly using the documented reconnect flow alone
- First-party clients may automate reconnect, reauthentication, and gameplay re-entry, but they must not depend on a private recovery model unavailable to other clients

### Bounded Non-Edge Restart Recovery

Invisible recovery means the client-facing Gateway WebSocket or TCP Proxy socket remains open and the player does not repeat `LOGIN` or `PLAY`. It does not promise zero interruption, raw transport replay, or exactly-once command completion.

- **Qualifying conditions** – one non-edge instance is lost or deliberately replaced while the edge socket, healthy same-type replacement capacity, and required shared authority remain available.
- **Timing** – ordinary recovery targets no more than 10 seconds. If continuation authority cannot be established safely, hidden recovery ends immediately; otherwise Gateway stops hidden recovery after 30 seconds of continuous upstream unavailability and closes with `1013/backend_unavailable`. Retry-attempt counts alone are not an elapsed-time bound.
- **Input during a detected stall** – input accepted into the bounded stall buffer remains FIFO and is delivered once after successful rebind. If the buffer cannot accept further input, Gateway closes or fails explicitly using the bounded taxonomy; it never silently discards input while leaving the client apparently healthy.
- **Ambiguous in-flight work** – a command that may already have crossed the failed hop is not blindly replayed. It may be lost under the current at-most-once edge contract; durable command/effect recovery continues only from recorded internal execution identity.
- **Lifecycle classification** – the internal Gateway-to-Game-Session hop distinguishes rebindable backend lifecycle or transport loss from terminal session outcomes such as logout, takeover, policy rejection, revocation, and loss of current authorization. That internal classification does not add a public close category.
- **Continuation authority** – replacement Game Session instances use current server-side session authority and the stable edge transport identity. They do not require the original first-party connect token to remain valid as though the internal rebind were a new public admission, but current tenant/game scope, membership, entitlement, revocation, authorization, and fencing still apply.
- **Presence and liveness** – loss of only the internal Game Session hop is not proof that the player transport was lost. Disconnect events, presence removal, and gameplay-binding teardown follow authoritative edge liveness and replacement registration so a successful rebind does not publish a false disconnect.

These timing thresholds are initial functional acceptance criteria rather than a published percentile availability SLO. FireMUD must not claim the complete target as implemented until a real authenticated Gateway and Game Session replacement test retains the same client socket, continues after prior `LOGIN` and `PLAY`, and proves subsequent gameplay, authority, presence, and transcript behavior without repeating admission.

## Client Reconnection Behaviour

FireMUD treats actual edge reconnection as an explicit **client-visible recovery flow**: after an actual client-facing edge disconnect, or after an authentication invalidation has terminated the active binding, clients open a fresh transport (TCP or WebSocket), issue a new `LOGIN`, and complete `PLAY` before gameplay commands. This documented flow is the canonical interoperability contract for third-party MUD clients. A retained-edge upstream rebind is not this flow and does not require another `LOGIN` or `PLAY`. First-party WebSocket clients may automate the explicit sequence for a smoother UX, but they must still follow the same underlying connect-token, `LOGIN`, and `PLAY` rules. To avoid thundering herds and to keep reconnect storms predictable during incidents, automated or first-party clients should follow a consistent reconnection policy:

- **Backoff and jitter**
  - Start with an initial delay of `1–2s` after the first failed reconnect attempt.
  - Use exponential backoff (for example, doubling the delay on each subsequent failure) up to a maximum backoff of `30–60s`.
  - Apply jitter of ±25% to each delay to avoid synchronized reconnect bursts from many clients.
- **Retry caps**
  - Cap reconnect attempts to a reasonable rate per client (for example, no more than ~6 attempts in the first minute and ~60 attempts per hour).
  - Client policy handling is split into explicit classes:
    - `policy_pressure_retriable` (for example HTTP `429`) – retry with slower backoff and strict caps.
    - `policy_violation_non_retriable` (for example sustained abuse/malformed protocol outcomes) – stop auto-retry or switch to very long backoff and surface corrective action to the user.
    - `policy_violation_edge_backpressure_retriable` – if wire-visible disconnect metadata explicitly indicates edge backpressure (for example WebSocket close `1008/policy_violation` with `subreason=edge_backpressure`, or the Telnet disconnect token `policy_violation;subreason=edge_backpressure`), treat as retriable with backend-unavailable backoff. If that metadata is absent, keep the default non-retriable policy for `policy_violation`.
- **Scope of reconnection**
  - Telnet clients reconnect by establishing a new TCP connection to the TCP Proxy Service, issuing `LOGIN`, and then issuing `PLAY` before gameplay commands. If smart-client attach hints return later, they should be re-sent as hidden MCP metadata on the new connection.
- Web clients reconnect by first obtaining a fresh connect token, opening a new WebSocket to `/ws/game/**`, issuing `LOGIN`, and then issuing `PLAY`; they must not assume that any prior MCP or hidden smart-client attach metadata has survived, as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery).

For clarity, Telnet clients do not receive hidden recovery after their TCP socket or TCP Proxy-to-Gateway edge bridge is lost. Even if the proxy-wide admission circuit later returns to closed after such an edge outage, the affected Telnet client must reconnect on a fresh TCP socket and repeat `LOGIN`/`PLAY`. This does not prevent Gateway from retaining a still-established proxy bridge while it performs ADR 0013's bounded rebind of its separate Game Session upstream.

Clients must also treat pre-disconnect output as non-resumable transport state. FireMUD does not replay raw transport bytes, prior WebSocket frames, or unsent Telnet output onto a newly opened connection. Instead, reconnect restores player-visible context in two distinct ways:

- a bounded durable resume transcript keyed by the admitted tenant/game, game-instance, and character identity replays its retained narrative/system entries after successful `LOGIN` + `PLAY` for an eligible resume or fresh non-logout admission; Redis may cache that context but is not authoritative. Explicit gameplay `LOGOUT` immediately makes the binding's private transcript non-replayable; physical deletion may complete asynchronously, and later `LOGIN` + `PLAY` does not replay that terminated binding's context;
- new screen-buffer entries retain structured player-output metadata alongside rendered protocol text when the output came from the structured `PlayerOutput` path, so first-party clients can receive typed replay entries while classic clients continue to receive text;
- then FireMUD emits fresh state-derived reconstruction output such as `LOOK` and prompt/status information.

The hot reconnect screen buffer is context restoration, not a transport delivery guarantee. It exists to help players understand what just happened around a disconnect; it does not promise exact delivery of every missed output line.

Prompt/status output is a separate output class from transcript lines:

- prompts are coalesced UI/state summaries, not ordinary scrollback messages;
- prompts are not part of the reconnect screen buffer by default;
- reconnect should restore transcript context first, then emit a fresh `LOOK`, then emit one fresh current prompt;
- first-party web and MCP-aware clients may consume prompt/status as structured data instead of rendering it into the main text transcript.

In the current Game Session settings surface, prompt enablement and restore/coalescing defaults are exposed through:

- `firemud.presentation.prompt.enabled`
- `firemud.presentation.prompt.emit-after-reconnect-restore`
- `firemud.presentation.prompt.coalesce-window-ms`

The current reconnect and durable-context settings are exposed through:

- `firemud.reconnection.policy.resume-window-ms`
- `firemud.reconnection.policy.stale-resume-falls-through-to-fresh-entry`
- `firemud.reconnection.buffer.ttl-ms`
- `firemud.reconnection.buffer.min-messages`
- `firemud.reconnection.buffer.min-lines`
- `firemud.reconnection.buffer.soft-max-bytes`
- `firemud.reconnection.buffer.hard-max-bytes`

Service-local typed properties provide operator defaults, and Game Design persists optional tenant/game overrides that Game Session merges into the effective reconnection policy. Because the soft and hard byte ceilings form one effective invariant while those operator defaults remain service-local, a tenant override that changes either ceiling must persist both values; a game-instance override may set one ceiling only when the tenant layer supplies the complete pair. Tenant put and delete operations validate existing game-instance reconnection overrides against the prospective tenant layer before changing it. Operator caps and presets remain future work. Prompt exclusion from reconnect transcript replay remains a canonical reconnect/output rule rather than a separately surfaced toggle.

### Canonical durable resume-transcript policy

Every admitted `{tenantId, gameInstanceId, characterId}` scope retains one durable, bounded resume transcript. This is a short rolling player context, not an archive and not a player-selected setting. The effective policy resolves from platform defaults with an optional tenant/game override:

- soft and hard retained-byte ceilings, with message and line floors for the soft ceiling;
- optional expiry after configured replayable transcript activity inactivity, or `never`.

New entries evict complete oldest retained entries when a byte bound is exceeded. The soft ceiling preserves the configured message and line floors where possible, then the hard ceiling bounds every multi-entry window. Byte accounting uses the deterministic canonical structured-entry envelope defined in [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#resume-transcript-bounds), including structured replay metadata and rendered compatibility text exactly once. If a single complete entry exceeds the hard byte bound, FireMUD retains that complete entry as the valid current window and later appends evict older entries first. Inactivity expiry removes the whole context. After `LOGIN` + `PLAY` for an eligible resume or fresh non-logout admission, FireMUD replays complete retained structured entries in ordering-token order before sending fresh state reconstruction. Explicit gameplay `LOGOUT` immediately makes the binding's private transcript non-replayable; physical deletion may complete asynchronously. A later `LOGIN` + `PLAY` is fresh admission and does not replay that terminated binding's context. A persistent RPG may use no inactivity expiry while still retaining only its configured recent screen window.

Game Session implements this bounded durable model with ordered `resume_transcript_entry` rows. Redis is a best-effort hot cache only: a cache reset does not discard retained reconnect context. Command-input history is a separate current feature, while complete player transcript archive/export remains future work. Neither alters the reconnect context contract; see [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#separate-history-features).

### Abnormal WebSocket Transport Loss

Client implementations must not assume every disconnect includes a close frame and reason token. Planned drains and many graceful failures will carry canonical close codes, but abrupt process/node/network failures can terminate transport before the edge emits a close frame.

- If the connection drops without a usable close code/reason, classify it as `internal_error` for retry/backoff policy purposes.
- Do not treat missing close metadata as `logout` or `policy_violation`.

### Gameplay WebSocket route handshake policy (normative)

`/ws/game/**` is the only gameplay route. Non-proxy clients with missing, invalid, expired, replayed, scope-mismatched, or replay-protection-unavailable connect-token state are rejected with HTTP `403` and a bounded `CONNECT_*` handshake class. Trust-boundary denials (for example internal-listener/mTLS policy mismatch) are rejected with HTTP `403` and handshake class `POLICY_DENY`. Successful first-party gameplay handshakes emit `X-Firemud-Connection-Mode: first_party_web`, while TCP Proxy bridge requests are admitted without a connect token only when proxy mTLS identity and trust checks pass and emit `X-Firemud-Connection-Mode: trusted_tcp_proxy`, so Game Session can distinguish both paths using positive markers rather than header absence.

### HTTP Handshake Failures on `/ws/game/**`

When Web clients attempt to establish or re-establish WebSocket connections and receive HTTP errors instead of a successful upgrade, they must interpret those signals consistently with the close-code taxonomy:

This table is the canonical client-policy matrix for gameplay-route handshake failures; gateway and client documentation must reference this mapping rather than redefining retry semantics independently.
Client behavior must key on handshake error class first, then HTTP status as a secondary signal.

| HTTP status | Handshake error class | Meaning on gameplay routes | Client policy |
| --- | --- | --- | --- |
| `429` | `POLICY_PRESSURE` | Edge rate or connection policy boundary reached | Classify as `policy_pressure_retriable`: retry with slower exponential backoff (start `5s`, cap `120s`, ±25% jitter) and strict retry caps; do not fast-loop. |
| `503` | `BACKEND_UNAVAILABLE` | Gateway currently considers gameplay backend unavailable | Treat as `1013/backend_unavailable`; use exponential backoff with jitter and retry caps. |
| `403` | `CONNECT_TOKEN_MISSING` | First-party gameplay handshake did not include a valid connect-token carrier (`Firemud-Connect-Token` cookie for browsers or `X-Firemud-Connect-Token` header for non-browser clients) | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); if repeated after fresh-token refresh, surface login/session-recovery action to the user. |
| `403` | `CONNECT_TOKEN_EXPIRED` | Connect token expired before handshake validation completed | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter). |
| `403` | `CONNECT_TOKEN_REPLAYED` | Connect token `jti` was already used within the replay window | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); repeated replay failures should surface a session-recovery action rather than fast-looping. |
| `403` | `CONNECT_SCOPE_MISMATCH` | Handshake headers or later Game Session admission context do not match the verified connect-token scope | Re-run bootstrap/discovery and acquire a fresh connect token for the current selected scope; do not silently fall back to caller-guessed tenant or game-instance values. |
| `403` | `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` | Gateway cannot validate connect-token replay state and fail-closes | Retry with bounded exponential backoff (start `10s`, cap `60s`, ±25% jitter) and surface temporary edge-auth-unavailable context (not backend-outage messaging). |
| `403` | `CONNECT_TOKEN_REJECTED` | Connect token is malformed, signature-invalid, missing required claims, wrong-audience, or otherwise rejected outside the narrower classes above | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); if repeated after fresh-token refresh, surface login/session-recovery action to the user. |
| `403` | `POLICY_DENY` | Handshake denied by gateway policy/trust boundary (for example internal-only listener, mTLS identity, security policy mismatch) | Treat as non-retriable until configuration/permissions change; surface as actionable operator/user error. |
| `426` | `PROTOCOL_MISMATCH` | Protocol upgrade requirement not met | Retry only after client transport/protocol correction (for example proper WebSocket upgrade/TLS endpoint). |
| Other `5xx` | `INTERNAL_ERROR` | Unexpected gateway/infra failure | Treat as `internal_error`; use exponential backoff with jitter. |

For gameplay routes, HTTP `401` is not part of the normal handshake taxonomy because gameplay authentication occurs after WebSocket establishment via `LOGIN`/`PLAY`. If `401` appears in practice, treat it as a misconfiguration signal and investigate gateway policy drift.

First-party clients and tools should implement a unified “edge error → backoff policy” table that maps WebSocket close codes (plus abnormal no-close transport loss) and handshake error classes (`POLICY_PRESSURE`, `BACKEND_UNAVAILABLE`, the `CONNECT_*` classes above, `POLICY_DENY`, `PROTOCOL_MISMATCH`, `INTERNAL_ERROR`) to concrete backoff behaviour so reconnect storms remain predictable during incidents. This policy table must be derivable from wire-visible signals alone; operator-only metrics are supplemental for diagnostics, not required for client retry decisions.

For clarity: HTTP `429` is not a hard “never retry” signal in FireMUD. It is a controlled `policy_pressure_retriable` class intended to protect the edge under pressure while still allowing eventual recovery for legitimate clients.

Clients that do not implement these backoff rules will still function, but first‑party tools and reference clients should treat this behaviour as the normative baseline so that production incidents do not amplify reconnect load.

## Failure Modes & Reconciliation Rules

`NotifyDisconnect` events and edge behaviour around disconnects are intentionally **advisory**, with Redis and gameplay activity as the source of truth:

- **Authoritative liveness** – Game Session treats Redis session entries, tick activity, and its own heartbeats as authoritative for session liveness. `NotifyDisconnect` is a best-effort, at-least-once hint from the TCP Proxy Service, not the sole indicator of whether a client is still connected.
- **Idempotent disconnect handling** – Game Session keys disconnect handling by `{proxyConnectionId, disconnectSequence}` and treats duplicate events for the same pair as no-ops. Late events that arrive after a new socket has been bound or after Redis has expired the session are also safe to ignore; they must not forcefully tear down a new, healthy binding.
- **Single close transition** – For a given gameplay binding, Game Session must implement one authoritative close/suspend transition keyed by the current bound connection identity. Upstream WebSocket close detection and `NotifyDisconnect` are separate signals that race into that same transition; neither path may independently perform duplicate teardown, suspend, or metric side effects once the binding has already advanced state.
- **Missing hints** – Absence of a `NotifyDisconnect` event is never interpreted as a guarantee that the client is still connected. Game Session relies on its own timeouts and region/tick-level activity to clean up stale sessions when disconnect hints are missing (for example due to gRPC transport failures).
- **Edge ordering vs domain idempotency** – The TCP Proxy → Gateway → Game Session path provides per-connection FIFO where delivered and at-most-once delivery, as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Game Session and downstream domain services implement durable idempotency guards (for example effect IDs and transaction rows) so retries and replays within the tick system remain safe even when edge hints are late or duplicated. See [gRPC API Style & Versioning](./system-architecture-grpc.md) and [Transactions & Idempotency](./system-architecture-transactions.md) for the underlying RPC and effect semantics.

### Backend-Unavailable Scenarios

Some failures leave edge connections technically alive while core gameplay services are degraded. To keep behaviour predictable:

- **`degraded_but_reachable` state**
  - When Game Session is reachable and can still return a bounded explicit gameplay/protocol response for each affected command over the existing upstream WebSocket, Spring Cloud Gateway keeps existing sessions open and clients receive explicit per-command failures.
  - Telnet clients follow an equivalent outcome through the proxy bridge: while the bridge remains healthy and backend responses remain explicit and bounded, connections stay open and command failures remain explicit backend responses.
  - This state must not be used for “connected but blackholed” behavior. If the platform cannot produce a bounded explicit response for gameplay on an established session, the edge must treat the route as `unreachable` for that session rather than leaving the client connected without progress.
- **`unreachable` state**
  - When Gateway cannot establish or maintain the upstream gameplay WebSocket, it enters `unreachable` and starts the `firemud.gateway.backendUnavailableGraceMs` timer.
  - During this state, new gameplay-route handshakes (`/ws/game/**`) are rejected with HTTP `503`. Existing sessions retain their edge socket during bounded rebind. Input accepted into the bounded stall buffer remains FIFO and is delivered once after rebind; buffer exhaustion closes explicitly with `1013/backend_unavailable` rather than silently dropping commands.
- **Sustained backend unavailability**
  - `firemud.gateway.backendUnavailableGraceMs` implements ADR 0013's elapsed-time cutoff and must not exceed 30,000 ms. When `unreachable` remains continuous through the configured cutoff, Spring Cloud Gateway closes affected gameplay WebSocket sessions with `1013/backend_unavailable`, signalling clients to apply reconnection/backoff rules.
  - Returning from `unreachable` to normal routing requires hysteresis (consecutive successful upstream connects/forwards) as defined in [Gateway Architecture](./system-architecture-gateway.md#backend-unavailable-grace-window), so one brief success does not flap clients.
  - Proxy and gateway outage windows must be configured in lockstep using explicit keys: `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` equals `firemud.gateway.backendUnavailableGraceMs`, and `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` equals `firemud.gateway.backendUnavailableRecoverySuccessCount`.
  - Edge services must fail fast on invalid local values (`<= 0`), Gateway must also reject `firemud.gateway.backendUnavailableGraceMs > 30000`, and deployment preflight must fail when any lockstep values diverge.
  - The TCP Proxy Service enforces equivalent admission outcomes with a bridge-availability circuit breaker: while the breaker is open (continuous gateway gameplay unreachability), new Telnet sockets are rejected quickly with `backend_unavailable` and existing affected sockets are closed with `backend_unavailable` rather than being held in ambiguous half-open states. Recovery from half-open to closed requires `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful bridge probes (default `3`) so proxy admission hysteresis stays aligned with gateway recovery criteria.
  - Operators should treat elevated counts of `1013` (`backend_unavailable`) and proxy‑side “backend unavailable” disconnects as indicators of core gameplay outages rather than client misuse, and use the metrics referenced in [Protocol Bridging](./system-architecture-protocol-bridging.md#backpressure--slow-clients) and the Telnet degraded runbook to distinguish these from slow‑client backpressure events.

### Telnet Bridge State Machine (Established Sessions)

To avoid ambiguous half-open behavior for already-established Telnet sessions, the TCP Proxy bridge uses an explicit per-connection state machine:

- `healthy` – upstream WebSocket bridge is established; gameplay traffic flows normally.
- `close_due_to_clean_logout` – if the proxy receives `1000/logout` on the authenticated bridge path, it closes the Telnet session as `logout` and preserves the bounded subreason. `subreason=gateway_restart` is the planned-drain case within this branch.
- `close_due_to_unreachable` – if the established upstream gameplay WebSocket cannot be maintained for any other reason, the proxy closes that Telnet connection immediately with `backend_unavailable`. The proxy does not keep the client TCP socket open while attempting a hidden gameplay-bridge reattach.
- `close_due_to_edge_backpressure` – if buffered lines exceed `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` while upstream is still reachable, close that Telnet connection with `policy_violation` and emit `edge_backpressure` context in structured logs/metrics.

This state machine is authoritative for established-session behavior and complements (but does not replace) the proxy-wide open/half-open/closed admission breaker used for new Telnet admissions.

### NotifyDisconnect Behavioral Contract (Summary)

The TCP Proxy Service emits `NotifyDisconnect` events to the Game Session Service over an internal-only gRPC stream whenever Telnet sockets close. Behaviourally, this stream follows a simple, canonical contract:

- **At-least-once, advisory delivery** – Transport for `NotifyDisconnect` is intentionally at-least-once: events may be delivered more than once or arrive late relative to the underlying TCP close. Game Session treats this stream as a best-effort hint about Telnet liveness, not as the source of truth; Redis session state and gameplay activity remain authoritative.
- **Idempotency key** – Every event carries an idempotency key derived from `{proxyConnectionId, disconnectSequence}`. Game Session persists the latest processed `disconnectSequence` per `proxyConnectionId` and must treat older or duplicate events for the same pair as no-ops so proxy-side retries remain simple and safe.
- **Idempotency record retention** – Game Session retains the latest processed sequence record per `proxyConnectionId` for at least `session_expiration_ms + TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` so delayed retries and reconnect races remain deduplicated. These records then expire via TTL-based cleanup.
- **Allowed dedupe storage shapes** – These dedupe records may live in Redis or another durable store that survives ordinary Game Session process restarts for at least the required retention window. In-memory-only dedupe is not sufficient for the canonical contract.
- **Capacity and eviction guardrails** – Consumer implementations must enforce a bounded maximum cardinality for active dedupe records (for example per tenant and per process shard). If the cap is hit during disconnect floods, eviction policy is deterministic `oldest-expiry-first`, and implementations must emit explicit overload metrics/logs (for example `gamesession.notifydisconnect.dedupe.capacity_reached`) so operators can distinguish flood pressure from logic bugs.
- **Eviction scope** – When capacity guardrails are partitioned by shard or tenant, `oldest-expiry-first` applies within the partition that hit its cap first; implementations must not silently switch to an unbounded global pool during floods. Canonical examples include a process-local shard partition or a tenant-scoped shard partition, but any chosen partition boundary must remain bounded, explicit, and operationally observable.
- **Operator interpretation** – Sustained `gamesession.notifydisconnect.dedupe.capacity_reached` during a regional outage should be treated as disconnect-flood pressure on the dedupe path, not as evidence that duplicate teardown is acceptable. Operators should scale or relieve the affected partition and confirm Redis/durable-store health before relaxing retention guarantees.
- **Runbook linkage** – During live incidents, use the Telnet degraded runbook for the operator workflow around `gamesession.notifydisconnect.dedupe.capacity_reached` and related bridge-failure signals; this section remains the canonical behavior contract, while the runbook covers triage order and mitigation steps.
- **Retry window** – The proxy retries failed `NotifyDisconnect` calls for a short, bounded window after Telnet socket close (see the TCP Proxy Service design’s **Service Interactions** section and the `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` configuration for exact timing). After this window, the proxy stops retrying and relies on Game Session’s own liveness detection and Redis timeouts to reconcile any missing hints.
- **Complement to edge delivery guarantees** – `NotifyDisconnect` complements, but does not change, the at-most-once edge delivery model for gameplay commands described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Late or duplicate disconnect hints must never cause healthy, newly bound sessions to be torn down; consumers always key decisions off the idempotency key and current Redis state.

Resume does not imply replay of prior outbound transport data. After reconnect, Game Session may emit fresh reconstruction output once the new binding is admitted, but it must not replay pre-disconnect text or MCP frames as if the old transport had continued. Any reconstruction output must be re-derived from current authoritative state, not copied from an old outbound queue.

This section is the canonical behaviour-level summary for `NotifyDisconnect`. The TCP Proxy Service design remains authoritative for message fields, configuration knobs, and implementation details, while [gRPC API Style & Versioning](./system-architecture-grpc.md#event-and-streaming-semantics) defines the general pattern for similar at-least-once event sinks elsewhere in the system.

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
