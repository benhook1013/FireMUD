# FireMUD System Architecture: Reconnection Strategy

FireMUD targets seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts using a **layered reconnection model** and **Redis-backed session state**. The canonical recovery contract remains explicit and protocol-visible at the edge so third-party MUD clients can recover reliably, while first-party clients may automate that same recovery path to reduce user-visible friction. Per [ADR 0013](./decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md), ordinary non-edge restarts use bounded invisible recovery when the edge socket, healthy same-type replacement capacity, and required shared authority remain available. The ordinary functional target is 10 seconds; if continuation authority cannot be established safely, hidden recovery ends immediately, otherwise it stops after 30 seconds and falls back to `1013/backend_unavailable` rather than retaining an indefinitely stalled connection.

---

## Implementation Status

- **Session takeover and resume** – Game Session emits `gamesession.session.takeover` and `gamesession.session.resume` counters and rebinds Redis tick/command queues on reconnect/takeover. The active uniqueness key is `{tenantId, gameInstanceId, characterId}`.
- **Telnet and WebSocket parity** – The target shared gameplay authentication and reconnect flow is owned by [Authentication](./system-architecture-authentication.md#login-and-session-flow) and [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management). Direct text/Telnet starts with fresh `WORLDS` discovery before credential-bearing `LOGIN`, while first-party WebSocket starts with `POST /auth/player-bootstrap` and `/auth/bootstrap/*` discovery. Current first-party connect-token issuance and text `PLAY` require existing `ACTIVE` membership and fail closed with `JOIN_REQUIRED` for eligible missing or `INACTIVE` public-production membership. Explicit `JOIN`/`Join & Play` and the required connect-token membership-authority/version rereads remain unimplemented/proof gaps. First-party WebSocket clients must obtain a fresh connect token before opening `/ws/game/**`.
- **Runtime authority checks** – `PLAY` now performs a first-pass runtime membership and tenant-entitlement check before fresh entry or resume/takeover. This closes the earlier gap where reconnect semantics relied only on Redis gameplay identity plus a fresh `LOGIN`.
- **Continuity configuration drift** – The target continuity policy caps `FIREMUD_AUTH_SESSION_EXPIRATION_MS` at five minutes independently of JWT lifetime. Current Game Session code still defaults this setting to one hour and does not enforce the target cap.
- **Remaining work** – Durable bounded resume-transcript persistence is live through ordered `resume_transcript_entry` rows, with Redis as a best-effort hot cache and fresh authoritative redraw after replay. Admin-driven forced session transfers remain planned future steps. FireMUD does not attempt to replay or reconstruct ambiguously delivered gameplay commands after failure; an in-flight command may be lost under the at-most-once edge contract. Gateway upstream rebind and focused downstream-worker restart proof exist, but the complete authenticated real-Game-Session replacement sequence, lifecycle classification, presence convergence, authority continuation, elapsed-time bounds, and stalled-input behavior still require implementation and proof before ADR 0013's availability target can be claimed as complete.

## Reconnection Layers

| Layer | Responsibility |
| --- | --- |
| **TCP Proxy Service** | Parses Telnet input and clears buffered commands; emits a best-effort disconnect notification to Game Session over an internal-only gRPC event sink |
| **Spring Cloud Gateway** | Stateless WebSocket router that owns the client-facing WebSocket close frame and close-code taxonomy (for example `1013/backend_unavailable` for sustained outages); translates classified upstream outcomes and resumes routing once clients reconnect |
| **Game Session Service** | Restores session from Redis; rebinds socket, region, and timers when the edge connection remains healthy; emits classified upstream session/protocol errors to Gateway and does not own the client-facing close frame |

Each layer handles fault tolerance independently. Session continuity, rebind handles, token refresh, and authority-revocation boundaries are canonical in [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management); this document retains the client-visible transport and close behavior.
Edge disconnects remain the canonical explicit recovery boundary. An ordinary non-edge restart qualifies for invisible recovery only while the edge socket, healthy same-type replacement capacity, and required shared authority remain available. Gateway retains the edge socket and rebinds its Game Session upstream within the bounded window; replaceable downstream workers recover behind the Game Session caller connection. Any in-flight gameplay command at the moment of a hard failure may still be lost, consistent with the at-most-once edge delivery semantics in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Missing shared authority, unsafe continuation authority or ownership ambiguity, terminal session policy, loss of the edge socket, or exhaustion of the 30-second window makes recovery explicit rather than invisible.
Spring Cloud Gateway should fail over cleanly at the fleet level: unaffected sockets on other healthy Gateway instances remain up, and new gameplay handshakes should continue against healthy instances. A socket terminated by its serving Gateway instance requires a fresh token where applicable, a new socket, `LOGIN`, and `PLAY`; the detailed client procedure is in [Client Reconnection Behaviour](#client-reconnection-behaviour). Restart classification remains local transport behavior: planned Gateway drain is `logout` with `gateway_restart`, while abrupt or unattributed Telnet bridge loss is `backend_unavailable` and missing WebSocket close metadata is `internal_error` retry class.
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

Clients must send a `LOGIN` command and complete `PLAY` after an actual client-facing edge disconnect or an authentication invalidation that terminates the active binding. The continuity, episode, refresh, and revocation predicates are canonical in [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management); this section records the client-visible boundary:

- TCP loss (Telnet clients)
- WebSocket loss (Web clients)
- Receiver-token expiry or refresh failure uses the owner-defined resume or fresh-admission path.
- Account or tenant authority revocation terminates the old binding; a later independent fresh admission is possible only if authority is restored.

A retained-edge upstream rebind, including a qualifying hidden Game Session restart, is not a client disconnect and does not repeat `LOGIN`, `JOIN`, or `PLAY`; the new-session rules still apply after an actual edge disconnect or newly opened socket.

Reconnection uses two distinct client flows:

- **Credential-bearing text lobby flow** – Telnet and other non-WebSocket text clients open a fresh TCP connection, perform public `WORLDS` discovery, then send `LOGIN <email> <secret>` / `LOGON <email> <secret>` or the applicable email-code `LOGIN` / `LOGON` forms. They then perform authenticated `REALMS <world>`, selected-target-policy-gated `JOIN`/`JOIN_REQUIRED` handling, and realm-scoped `CHARS` or character creation when a valid selected character is not already resolved, followed by `PLAY`. This flow does not use HTTP bootstrap, browser discovery snapshots, or connect tokens.
  - **First-party WebSocket flow** – The client reruns HTTP `player-bootstrap` and discovery, must evaluate fresh selected-target catalog/pointer evidence and the Account-owned `allowPublicJoin` entitlement before completing `Join & Play` when public-production membership is missing or `INACTIVE`, performs realm-scoped character selection or creation, requests a fresh HTTP connect token, opens `/ws/game/**`, sends bare `LOGIN` using the verified bootstrap/connect context, and completes `PLAY`. `Join & Play` therefore precedes connect-token issuance, socket open, bare `LOGIN`, and `PLAY`. A denied policy returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`, preserves bootstrap authentication and membership state, creates no binding, and stops before character repair, token issuance, socket admission, bare `LOGIN`, or `PLAY`. A returning player may skip `Join & Play` after fresh Account evidence confirms `membershipLifecycleState=ACTIVE`, the exact current `membershipAuthorityGeneration`, and independently current `membershipVersion` for the unexpired discovery snapshot. A valid current character is additionally required before connect-token issuance or `PLAY`; otherwise the player proceeds through realm-scoped discovery or creation. Bare `LOGIN` never prompts for or replays credentials.

Text clients may use the `LOGIN` -> `PLAY` compatibility shortcut only after fresh transport-local routing, membership, character, entitlement, and grant checks defined by [Authentication](./system-architecture-authentication.md#in-band-play-admission-boundary) pass for the same target. The shortcut may omit `JOIN` only for an existing `ACTIVE` member and may omit a separate `CHARS` round-trip only when one valid current character is resolved; it never reuses a first-party discovery snapshot. First-party WebSocket clients rerun bootstrap/discovery and obtain a fresh connect token before the new handshake. Gameplay commands are not admitted before `PLAY`, and Telnet attach hints remain advisory hidden MCP metadata only.

Private/playtest reconnects have a distinct membership outcome. Missing or non-`ACTIVE` membership returns `NON_PUBLIC_ENROLLMENT_REQUIRED`, never public `JOIN` or `JOIN_REQUIRED`, and never character discovery as membership repair. Character discovery or allowed realm-local character creation follows only after existing membership is confirmed `ACTIVE` and the current realm grant is valid; an invalid or missing grant returns `REALM_ACCESS_DENIED`.

A client must not infer current membership or target identity from a stale Redis binding, cached tenant/realm fields, or an old `connectScopeId` after `CONNECT_SCOPE_MISMATCH`.

### Public membership repair during reconnect (normative)

Every reconnect path that would handle `JOIN_REQUIRED` or invoke public `JOIN`/`Join & Play` follows the [Authentication membership repair contract](./system-architecture-authentication.md#login-and-session-flow). For credential-bearing text clients, fresh `WORLDS` discovery precedes `LOGIN`, then authenticated target discovery precedes the conditional in-band `JOIN`; a denied policy preserves authentication and membership, creates no binding, and stops before `CHARS`, character creation, or `PLAY`. For first-party WebSocket clients, HTTP `Join & Play` must complete before character repair, connect-token issuance, socket admission, bare `LOGIN`, and `PLAY`; a denied policy returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`, preserves bootstrap authentication and membership state, and stops before those later steps. Only a permitted repair continues after Account returns `ACTIVE`.

Redis-backed session state allows resumable recovery when the gameplay binding is still logically valid, or a fresh login when it is not. The canonical continuity anchor, resume episode, physical TTL rules, token refresh, and transcript authority are defined in [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management). Reconnection retains only this transport consequence: Redis key presence alone never authorizes resume, and a new transport never replays prior bytes.

If prior resumable state is stale or partially missing, Game Session may fall through to fresh `PLAY` when current admission is valid; missing room or game-instance context is not itself a player-facing transport failure.

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

The general continuity anchor, disconnection episode, resume deadline, and authority/lifetime predicates are canonical in [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management). This section defines only the retained-edge behavior for a qualifying non-edge restart. Invisible recovery keeps the client-facing Gateway WebSocket or TCP Proxy socket open, so the player does not repeat `LOGIN` or `PLAY`; it does not promise zero interruption, raw transport replay, or exactly-once command completion.

- **Qualifying conditions** – one non-edge instance is lost or deliberately replaced while the edge socket, healthy same-type replacement capacity, and required shared authority remain available.
- **Retained-edge rebind** – Gateway rebinds the stable edge transport to the replacement Game Session instance only after Game Session authorizes the durable protected single-use rebind handle carried in the gameplay binding. Retaining the socket alone is insufficient. This is not a new public admission: the original connect token is not required for the internal rebind, and a successful rebind requires no new `LOGIN` or `PLAY`. Game Session still applies the canonical session continuity, authority, and fencing checks.
- **Timing** – ordinary recovery targets no more than 10 seconds. If the canonical continuation checks cannot be established safely, hidden recovery ends immediately; otherwise Gateway stops hidden recovery after 30 seconds of continuous upstream unavailability and closes with `1013/backend_unavailable`. Retry-attempt counts alone are not an elapsed-time bound.
- **Input during a detected stall** – input accepted into the bounded stall buffer remains FIFO and is delivered once after successful rebind. If the buffer cannot accept further input, Gateway closes or fails explicitly using the bounded taxonomy; it never silently discards input while leaving the client apparently healthy.
- **Ambiguous in-flight work** – a command that may already have crossed the failed hop is not blindly replayed. It may be lost under the current at-most-once edge contract; durable command/effect recovery continues only from recorded internal execution identity.
- **Lifecycle classification** – the internal Gateway-to-Game-Session hop distinguishes rebindable backend lifecycle or transport loss from terminal session outcomes such as logout, takeover, policy rejection, revocation, and loss of current authorization. That internal classification does not add a public close category.
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
- Telnet clients reconnect by establishing a new TCP connection to the TCP Proxy Service, issuing `LOGIN`, performing fresh authoritative `WORLDS`/`REALMS`/`CHARS` discovery as needed, taking the conditional `JOIN`/character-creation path, and then issuing `PLAY` before gameplay commands. Direct text reconnect has no browser connect-token snapshot to reuse and must not select or attest a target from stale prior fields. If smart-client attach hints return later, they should be re-sent as hidden MCP metadata on the new connection.
- Web clients reconnect by rerunning bootstrap discovery and, when required, `Join & Play` plus realm-scoped `CHARS`/allowed character creation before obtaining a fresh connect token; they then open a new WebSocket to `/ws/game/**`, issue bare `LOGIN`, and issue `PLAY`. `Join & Play` and any required character repair therefore precede connect-token issuance and bare `LOGIN`. The token-backed WebSocket flow uses the fresh, short-lived discovery snapshot only for its selected edge target and discards it on expiry or scope mismatch. A valid snapshot is not a substitute for current `ACTIVE` membership or a valid character, and an invalid character must be repaired before token issuance rather than discovered after the socket opens. Web clients must not assume that any prior MCP or hidden smart-client attach metadata has survived, as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery).

For clarity, Telnet clients do not receive hidden recovery after their TCP socket or TCP Proxy-to-Gateway edge bridge is lost. Even if the proxy-wide admission circuit later returns to closed after such an edge outage, the affected Telnet client must reconnect on a fresh TCP socket and repeat the applicable `LOGIN`/conditional `JOIN`/character-gate/`PLAY` flow. This does not prevent Gateway from retaining a still-established proxy bridge while it performs ADR 0013's bounded rebind of its separate Game Session upstream.

Clients must also treat pre-disconnect output as non-resumable transport state. FireMUD does not replay raw transport bytes, prior WebSocket frames, or unsent Telnet output onto a newly opened connection. Instead, reconnect restores player-visible context in two distinct ways:

- a bounded durable resume transcript keyed by the admitted tenant/game, game-instance, and character identity is freshly rendered from its retained narrative/system entries after successful `LOGIN` + `PLAY` for an eligible resume or fresh non-logout admission; Redis may cache that context but is not authoritative. Explicit gameplay `LOGOUT` immediately makes the binding's private transcript non-replayable; physical deletion may complete asynchronously, and later `LOGIN` + `PLAY` does not replay that terminated binding's context;
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

The durable transcript, binding logout behavior, retention bounds, and Game Session persistence model are canonical in [Session Behavior](./system-architecture-session-behavior.md#gameplay-logout-and-resume-transcript). This document retains the client-visible consequence: a new transport receives a bounded transcript freshly rendered from retained structured entries, plus fresh state reconstruction, after the owner permits an eligible resume or fresh non-logout admission. Raw prior bytes and unsent transport output are never replayed, and `LOGOUT` remains excluded from transcript replay.

### Abnormal WebSocket Transport Loss

Client implementations must not assume every disconnect includes a close frame and reason token. Planned drains and many graceful failures will carry canonical close codes, but abrupt process/node/network failures can terminate transport before the edge emits a close frame.

- If the connection drops without a usable close code/reason, classify it as `internal_error` for retry/backoff policy purposes.
- Do not treat missing close metadata as `logout` or `policy_violation`.

### Gameplay WebSocket route handshake policy (normative)

The canonical `/ws/game/**` route, carrier, trust marker, and connect-token validation rules are owned by [Gateway architecture](./system-architecture-gateway.md#gameplay-websocket-route). This section retains the client-visible handshake status/error matrix and its retry policy.

### HTTP Handshake Failures on `/ws/game/**`

When Web clients attempt to establish or re-establish WebSocket connections and receive HTTP errors instead of a successful upgrade, they must interpret those signals consistently with the close-code taxonomy:

This table is the canonical client-policy matrix for gameplay-route handshake failures; gateway and client documentation must reference this mapping rather than redefining retry semantics independently.
Client behavior must key on handshake error class first, then HTTP status as a secondary signal.

| HTTP status | Handshake error class | Meaning on gameplay routes | Client policy |
| --- | --- | --- | --- |
| `429` | `POLICY_PRESSURE` | Edge rate or connection policy boundary reached | Classify as `policy_pressure_retriable`: retry with slower exponential backoff (start `5s`, cap `120s`, ±25% jitter) and strict retry caps; do not fast-loop. |
| `503` | `BACKEND_UNAVAILABLE` | Gateway currently considers gameplay backend unavailable | Treat as `1013/backend_unavailable`; use exponential backoff with jitter and retry caps. |
| `403` | `CONNECT_TOKEN_MISSING` | The current first-party protected-cookie gameplay handshake contained no `Firemud-Connect-Token` cookie. This class is only for cookie absence; a present but invalid value remains classified as expired, replayed, or rejected (including malformed, signature-invalid, missing-claim, or wrong-audience values). The dedicated `X-Firemud-Connect-Token` header carrier is target-only/unavailable and is never a fallback. | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); if repeated after fresh-token refresh, surface login/session-recovery action to the user. |
| `403` | `CONNECT_TOKEN_EXPIRED` | Connect token expired before handshake validation completed | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter). |
| `403` | `CONNECT_TOKEN_REPLAYED` | Connect token `jti` was already used within the replay window | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); repeated replay failures should surface a session-recovery action rather than fast-looping. |
| `403` | `CONNECT_SCOPE_MISMATCH` | Before WebSocket upgrade, the verified connect-token target does not match the current request/admission scope | Discard the stale discovery/connect-token bundle, re-run bootstrap/discovery, and acquire a fresh connect token from the newly returned scope; never reuse the old `connectScopeId` or infer a target from cached tenant/realm/game-instance values. |
| `403` | `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` | Gateway cannot validate connect-token replay state and fail-closes | Retry with bounded exponential backoff (start `10s`, cap `60s`, ±25% jitter) and surface temporary edge-auth-unavailable context (not backend-outage messaging). |
| `403` | `CONNECT_TOKEN_REJECTED` | Connect token is malformed, signature-invalid, missing required claims, wrong-audience, or otherwise rejected outside the narrower classes above | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); if repeated after fresh-token refresh, surface login/session-recovery action to the user. |
| `403` | `POLICY_DENY` | Handshake denied by gateway policy/trust boundary (for example internal-only listener, mTLS identity, security policy mismatch) | Treat as non-retriable until configuration/permissions change; surface as actionable operator/user error. |
| `426` | `PROTOCOL_MISMATCH` | Protocol upgrade requirement not met | Retry only after client transport/protocol correction (for example proper WebSocket upgrade/TLS endpoint). |
| Other `5xx` | `INTERNAL_ERROR` | Unexpected gateway/infra failure | Treat as `internal_error`; use exponential backoff with jitter. |

For gameplay routes, HTTP `401` is not part of the normal handshake taxonomy because gameplay authentication occurs after WebSocket establishment via `LOGIN`/`PLAY`. If `401` appears in practice, treat it as a misconfiguration signal and investigate gateway policy drift.

### Post-upgrade Game Session admission failures

The preceding table applies only before the server returns `101 Switching Protocols`. Once `/ws/game/**` has upgraded, a scope mismatch is a Game Session protocol outcome rather than an HTTP handshake response. If `PLAY` or the signed connect-context admission check finds that the requested target does not match the verified connect-token scope, Game Session emits `ERROR CONNECT_SCOPE_MISMATCH` on the established protocol and closes the socket with the existing `1008/policy_violation` close category. The client must discard the stale bootstrap/discovery state, rerun bootstrap discovery to obtain the current authoritative tenant/realm/game-instance scope, complete HTTP `Join & Play` if that newly discovered target requires membership, obtain a fresh connect token from the newly returned `connectScopeId`, and open a new socket; it must not retry `PLAY` on the rejected socket, reuse the stale selector, infer the target from cached values, or treat the outcome as an HTTP `403`.

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
