# FireMUD System Architecture: Reconnection Strategy

FireMUD targets seamless gameplay recovery across network interruptions, client reconnects, and backend service restarts using a **layered reconnection model** and **Redis-backed session state**. This document owns the fresh-edge reconnect contract (decision key `SESSION-03`), including the protocol-visible distinction between a new transport and an invisible internal rebind. Per [ADR 0013](./decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md), ordinary non-edge restarts use bounded invisible recovery when the edge socket, healthy same-type replacement capacity, and required shared authority remain available. The ordinary functional target is 10 seconds; if continuation authority cannot be established safely, hidden recovery ends immediately, otherwise it stops after 30 seconds and falls back to `1013/backend_unavailable` rather than retaining an indefinitely stalled connection.

---

## Implementation Status

Proof and check selection follows [Validation and Runtime Proof](../developer-workflows/validation-and-runtime-proof.md); execution results remain in PR/CI records or implementation trackers, not this normative contract.

- **Session takeover and resume** – Game Session emits `gamesession.session.takeover` and `gamesession.session.resume` counters and rebinds Redis tick/command queues on reconnect/takeover. The target owner uniqueness key is `{tenantId, playableStateNamespaceId, characterId}` with atomic monotonic `bindingGeneration`; the current per-instance uniqueness key remains an implementation gap. Same-character target takeover fences further source input, preserves the identity of already-admitted source work, and switches the binding by CAS before target commands are admitted. Other source sessions follow the bounded drain in [ADR 0027](./decisions/adr-0027-single-realm-admission-target.md). See [Session Behavior](./system-architecture-session-behavior.md#namespace-scoped-controller-transfer-session-02) for the owner contract.
- **Replacement-state continuity** – [ADR 0122](./decisions/adr-0122-stable-playable-state-namespaces-for-runtime-replacement.md) adds a durable `playableStateNamespaceId` to the replacement/reconnect context without making `gameInstanceId` durable identity. Target reconnect and takeover use the exact controller key `{tenantId, playableStateNamespaceId, characterId}` with an atomic monotonic `bindingGeneration` CAS; server-derived `playableStateScope` remains binding context. They still require the namespace-bound active admission pointer and fresh active-instance authorization when replacing a runtime, reject stale/old-instance bindings, and preserve durable character state across replacement. Pointer validation is a replacement admission consequence, not a requirement for every gameplay command to reread the pointer. The pointer and binding generation provide the fence; replacement does not invent a second lease or identity or revoke every source session. [ADR 0123](./decisions/adr-0123-database-authoritative-temporal-coordinated-world-lifecycle.md) keeps lifecycle state/epoch authoritative in the owner database while Temporal coordinates cleanup; existing restart/reconnect proof does not establish these replacement guarantees.
- **Telnet and WebSocket parity** – The target shared gameplay authentication and reconnect flow is owned by [Authentication](./system-architecture-authentication.md#login-and-session-flow) and [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management). Direct text/Telnet starts with fresh `WORLDS` discovery before credential-bearing `LOGIN`, while first-party WebSocket starts with `POST /auth/player-bootstrap` and `/auth/bootstrap/*` discovery. Current first-party connect-token issuance and text `PLAY` use the fresh current Account fields `membershipExists` and `gameplayAdmissionAllowed` and admit only when both are true; an otherwise eligible public-production request with `membershipExists=false` fails closed with non-actionable `JOIN_REQUIRED`, while an existing response with `gameplayAdmissionAllowed=false` remains the established denial. The current response has no lifecycle state, so current admission is not classified as `ACTIVE` or `INACTIVE`. Missing or non-admitting membership for a non-public target remains `WORLD_ACCESS_DENIED`. Target-only `NON_PUBLIC_ENROLLMENT_REQUIRED` is not current behavior. Explicit `JOIN`/`Join & Play` and the required connect-token membership-authority/version rereads remain unimplemented/proof gaps. First-party WebSocket clients must obtain a fresh connect token before opening `/ws/game/**`.
- **Runtime authority checks** – `PLAY` now performs a first-pass runtime membership and tenant-entitlement check before fresh entry or resume/takeover. This closes the earlier gap where reconnect semantics relied only on Redis gameplay identity plus a fresh `LOGIN`.
- **Continuity configuration drift** – The target continuity policy caps `FIREMUD_AUTH_SESSION_EXPIRATION_MS` at five minutes independently of JWT lifetime. Current Game Session code still defaults this setting to one hour and does not enforce the target cap.
- **Remaining work** – Ordered `resume_transcript_entry` persistence is live, but convergence on the complete bounded semantic recent-context contract remains partial: namespace migration to `{tenantId, playableStateNamespaceId, characterId}`, strict `hardMax` accounting for complete envelopes, and oversize omission/marker behavior remain unproved. Redis is a best-effort hot cache. The target reconnect sequence renders retained context after authorization, obtains a fresh authoritative `LOOK`, and emits exactly one reconnect prompt only when both effective reconnect-prompt settings are enabled (zero reconnect prompts when either is disabled). Admin-driven forced session transfers remain planned future steps. FireMUD does not replay client input, transport bytes, or frames after edge loss; an in-flight command may be lost when it was not durably admitted, while admitted work continues under its existing identity. Gateway upstream rebind and focused downstream-worker restart proof exist, but the complete authenticated real-Game-Session replacement sequence, lifecycle classification, presence convergence, authority continuation, elapsed-time bounds, and stalled-input behavior still require implementation and proof before ADR 0013's availability target can be claimed as complete.

## Reconnection Layers

| Layer | Responsibility |
| --- | --- |
| **TCP Proxy Service** | Parses Telnet input and clears buffered commands; emits a best-effort disconnect notification to Game Session over an internal-only gRPC event sink |
| **Spring Cloud Gateway** | Stateless WebSocket router that owns the client-facing WebSocket close frame and close-code taxonomy (for example `1013/backend_unavailable` for sustained outages); translates classified upstream outcomes and resumes routing once clients reconnect |
| **Game Session Service** | Restores session from Redis; rebinds socket, region, and timers when the edge connection remains healthy; emits classified upstream session/protocol errors to Gateway and does not own the client-facing close frame |

Each layer handles fault tolerance independently. Session continuity, rebind handles, token refresh, and authority-revocation boundaries are canonical in [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management); this document retains the client-visible transport and close behavior.
Edge disconnects remain the canonical explicit recovery boundary. An ordinary non-edge restart qualifies for invisible recovery only while the edge socket, healthy same-type replacement capacity, and required shared authority remain available. Gateway retains the edge socket and rebinds its Game Session upstream within the bounded window; replaceable downstream workers recover behind the Game Session caller connection. Any in-flight gameplay command at the moment of a hard failure may still be lost, consistent with the at-most-once edge delivery semantics in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Missing shared authority, unsafe continuation authority or ownership ambiguity, terminal session policy, loss of the edge socket, or exhaustion of the 30-second window makes recovery explicit rather than invisible.
Spring Cloud Gateway should fail over cleanly at the fleet level: only sockets attached to the restarting Gateway instance disconnect; sockets on other healthy Gateway instances remain up, and new gameplay handshakes should continue against healthy instances. A socket terminated by its serving Gateway instance requires a fresh token where applicable, a new socket, `LOGIN`, and `PLAY`; the detailed client procedure is in [Client Reconnection Behaviour](#client-reconnection-behaviour). Under the [canonical close translation matrix](./system-architecture-gateway.md#canonical-close-translation-matrix), planned Gateway drain or restart is `1012/service_restart` and maps to Telnet `service_restart`, every valid authenticated upstream top-level class is preserved through Telnet, abrupt or unattributed Telnet bridge loss uses the observation-specific `backend_unavailable` fallback, and missing WebSocket close metadata remains the `internal_error` retry class.
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
> Spring Cloud Gateway restarts disconnect only WebSocket sessions attached to the restarting Gateway instance; those sessions must request a fresh connect token where applicable, reconnect their WebSocket, re-`LOGIN`, and re-`PLAY`, after which Game Session reloads state from Redis if available.
> Spring Cloud Gateway restarts affect only Telnet clients whose bridge is attached to the restarting Gateway instance. Planned drains map to Telnet `service_restart`; every other valid authenticated Gateway top-level close is preserved unchanged through the Telnet bridge. Only absent or invalid bridge attribution falls back to observation-specific `backend_unavailable`.

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

- **Credential-bearing text lobby flow** – Telnet and other non-WebSocket text clients open a fresh TCP connection, perform public `WORLDS` discovery, then send `LOGIN <email> <secret>` / `LOGON <email> <secret>` or the applicable email-code `LOGIN` / `LOGON` forms. They then perform authenticated `REALMS <world>`. For a public-production target, the Authentication-owned repair evaluates the applicable fresh entitlement before its target-only `JOIN` procedure; ordering and scope handling follow [Authentication](./system-architecture-authentication.md#login-and-session-flow) and [Direct-text REALMS-to-JOIN scope](./system-architecture-authentication.md#direct-text-realms-to-join-scope-normative). Current text reconnect instead stops with non-actionable `JOIN_REQUIRED` and performs no membership mutation because explicit `JOIN` is unavailable. Realm-scoped `CHARS` or character creation is conditional on no valid selected character already being resolved, followed by `PLAY`. This flow does not use HTTP bootstrap, browser discovery snapshots, or connect tokens; authenticated `REALMS` supplies the text-flow scope that Game Session retains for the current transport.
- **Target first-party WebSocket flow** – The client reruns HTTP `player-bootstrap` and discovery, must evaluate fresh selected-target catalog/pointer evidence and the applicable fresh Account entitlement before completing `Join & Play` when public-production membership is missing or `INACTIVE`, performs realm-scoped character selection or creation, requests a fresh HTTP connect token, opens `/ws/game/**`, sends bare `LOGIN` using the verified bootstrap/connect context, and completes `PLAY`. `Join & Play` therefore precedes connect-token issuance, socket open, bare `LOGIN`, and `PLAY`; the explicit join endpoint remains unimplemented in the current runtime. A returning player may skip `Join & Play` only under the [canonical first-party reconnect-shortcut gate](#canonical-first-party-reconnect-shortcut-gate). A denied policy returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`, preserves bootstrap authentication and membership state, creates no binding, and stops before character repair, token issuance, socket admission, bare `LOGIN`, or `PLAY`. A valid current character is additionally required before connect-token issuance or `PLAY`; otherwise the player proceeds through realm-scoped discovery or creation. Bare `LOGIN` never prompts for or replays credentials.

Text clients may use the `LOGIN` -> `PLAY` compatibility shortcut only after the fresh target-specific checks defined by [Authentication](./system-architecture-authentication.md#in-band-play-admission-boundary) pass. The shortcut may omit `JOIN` only for an existing `ACTIVE` member and may omit a separate `CHARS` round-trip only when one valid current character is resolved; it never reuses a first-party browser/WebSocket discovery snapshot. The text-flow scope is separate transport-local state from that browser snapshot, and a new transport obtains a new scope through `REALMS`. First-party WebSocket clients rerun bootstrap/discovery and obtain a fresh connect token before the new handshake. Gameplay commands are not admitted before `PLAY`, and Telnet attach hints remain advisory hidden MCP metadata only.

Private/playtest reconnect admission follows the [Authentication contract](./system-architecture-authentication.md#login-and-session-flow). Current non-public membership/admission denial from the available `membershipExists` and `gameplayAdmissionAllowed` fields remains `WORLD_ACCESS_DENIED`; current text `PLAY` also checks the current realm grant, while current connect-token grant validation remains incomplete. Target private/playtest reconnect requires the applicable fresh tenant runtime entitlement, existing caller-bound `ACTIVE` membership, and the exact current Account-owned realm grant; it does not consume the public-production enrollment predicate `allowPublicJoin`. The bounded last-known-good entitlement exception in [ADR 0028](./decisions/adr-0028-differentiated-entitlement-freshness.md#bounded-continuity-and-recovery) is limited to unchanged public-production binding continuity during an entitlement-only outage. Private/playtest reconnect therefore requires fresh entitlement and remains `ENTITLEMENT_UNAVAILABLE` when that evaluation is unavailable. Fresh entitlement also remains mandatory for new joins, membership creation, and `INACTIVE` restoration; a changed realm target or fresh gameplay binding remains strict. It also requires exact routing and one fresh atomic membership snapshot supplying `membershipLifecycleState`, `membershipAuthorityGeneration`, and independent `membershipVersion`; reachable invalid or stale pointer evidence is `ADMISSION_POINTER_UNAVAILABLE`, while an unreachable or timed-out Account or routing authority is `AUTH_UNAVAILABLE`. The target-only membership outcome is `NON_PUBLIC_ENROLLMENT_REQUIRED`, distinct from public `JOIN_REQUIRED`; target character discovery or allowed realm-local character creation follows only after `ACTIVE` membership and the current realm grant, with an invalid or missing grant returning `REALM_ACCESS_DENIED`.

A client must not infer current membership or target identity from a stale Redis binding, cached tenant/realm fields, or an old `connectScopeId` after `CONNECT_SCOPE_MISMATCH`. For direct text, Game Session may use only the scope retained from the current authenticated `REALMS` exchange; it must not recreate that scope or authority from a stale selector after transport loss or scope failure.

### Public membership repair during reconnect (normative)

Every reconnect path that would handle `JOIN_REQUIRED` or invoke public `JOIN`/`Join & Play` follows the [Authentication membership repair contract](./system-architecture-authentication.md#login-and-session-flow), including its [direct-text REALMS-to-JOIN scope](./system-architecture-authentication.md#direct-text-realms-to-join-scope-normative) procedure. This document retains only the transport consequence: fresh discovery precedes the authenticated text flow, and `CHARS` or character creation is conditional on no valid selected character being resolved. Current text reconnect stops with non-actionable `JOIN_REQUIRED` and performs no membership mutation; first-party browser/mobile bootstrap/connect-token surfaces remain partial and the complete flow is target-only.

Redis-backed session state allows resumable recovery when the gameplay binding is still logically valid, or a fresh login when it is not. The canonical continuity anchor, resume episode, physical TTL rules, token refresh, and transcript authority are defined in [Session Behavior](./system-architecture-session-behavior.md#session-and-identity-management). Reconnection retains only this transport consequence: Redis key presence alone never authorizes resume, and a new transport never replays prior bytes.

If prior resumable state is stale or partially missing, Game Session may fall through to fresh `PLAY` when current admission is valid; missing room or game-instance context is not itself a player-facing transport failure.

> 🧭 For full details on `LOGIN` behavior, argument formats, and session flow, see [Authentication & Authorization](./system-architecture-authentication.md#login-and-session-flow)

Gameplay command delivery is layered as defined by [ADR 0062](./decisions/adr-0062-layered-gameplay-command-delivery-semantics.md): Telnet and plain-text WebSocket clients remain fire-and-forget at the edge and do not attach identities, while trusted Game Session acceptance assigns or preserves a durable `commandId` and status before acknowledgement. Accepted ordinary commands are tracked but not automatically replayable; explicitly replayable classes require their own safe-intake contract. Internal retries preserve the accepted identity and owner guards, and reconnect never replays raw outbound frames. A close class never proves whether admitted work committed; a client or tool holding a known `commandId` reconciles it through the authoritative command-status surface.

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

**Target state:** the one-controller uniqueness key is `{tenantId, playableStateNamespaceId, characterId}`, and at most one active gameplay binding is supported for that key, using an atomic monotonic `bindingGeneration` compare-and-set. `playableStateScope` remains binding/routing context, not a second controller slot. For a same-character target takeover, Game Session fences further source input, retains the identity of work already admitted on that source, and switches the binding before admitting target commands. Other source sessions remain under the bounded drain in [ADR 0027](./decisions/adr-0027-single-realm-admission-target.md). During replacement, the namespace-bound admission pointer is moved with its expected-version compare-and-set, and a binding is admitted or resumed only after fresh authorization confirms that its `gameInstanceId` is the pointer's active fenced runtime for the resolved namespace; an old instance is not a second durable-state authority while it drains. The current per-instance uniqueness implementation remains a gap. This reuses the existing pointer and binding identity rather than adding a new lease or identity; see [Session Behavior](./system-architecture-session-behavior.md#namespace-scoped-controller-transfer-session-02) for the owner contract.

The controller identity is `{tenantId, playableStateNamespaceId, characterId}`; a binding additionally carries server-derived `playableStateScope` and active-runtime fence/value evidence. When a new client successfully issues `LOGIN` for an identity that is already bound to another connection (whether Telnet or WebSocket), Game Session treats this as a **takeover**:

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
| Spring Cloud Gateway restart | Only sockets attached to the restarting Gateway instance disconnect. Unaffected sockets on other healthy Gateway instances remain up and new handshakes continue. Connections bound to the specific Gateway instance being drained or crashing are visible failures. Planned drains emit `1012/service_restart` for affected WebSocket sessions and Telnet `service_restart`; graceful unplanned failures may emit `1011/internal_error`, while hard crashes may drop transport without a close frame and therefore use the `internal_error` retry fallback. Telnet preserves every valid authenticated upstream top-level class unchanged, and only absent or invalid bridge attribution uses the observation-specific `backend_unavailable` fallback. Exact translation and precedence remain owned by the [Gateway matrix](./system-architecture-gateway.md#canonical-close-translation-matrix). Affected clients obtain a fresh connect token where applicable before reopening `/ws/game/**`. |
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
    - `policy_violation_non_retriable` (for example sustained abuse or malformed protocol outcomes) – stop auto-retry or switch to very long backoff and surface corrective action to the user. The generic `1008/policy_violation` rule has one explicit exception: when the established protocol first emits `ERROR CONNECT_SCOPE_MISMATCH` and then closes `1008/policy_violation`, recovery is retriable; `CONNECT_SCOPE_MISMATCH` is not a close subreason. Discard the stale scope, rerun discovery/token issuance, and open a new socket; follow [Post-upgrade Game Session admission failures](#post-upgrade-game-session-admission-failures) for the flow. `edge_backpressure` remains diagnostic and non-retriable. Retry behavior normally keys only on the top-level `policy_violation` class; other subreasons are diagnostic and do not change lifecycle or retry semantics.
- **Scope of reconnection**
- Telnet clients reconnect by establishing a new TCP connection to the TCP Proxy Service and following the fresh-discovery and authenticated text procedure in [Authentication](./system-architecture-authentication.md#login-and-session-flow). Current text clients stop with non-actionable `JOIN_REQUIRED` and no membership mutation when an otherwise eligible public-production request reports `membershipExists=false` and target-only public repair would be required. An existing response with `gameplayAdmissionAllowed=false` remains the established denial. Direct text reconnect obtains a new transport-local scope from authenticated `REALMS`; it has no browser/WebSocket connect-token snapshot to reuse and must not select or attest a target from stale prior fields. `CHARS` or allowed character creation is conditional on no valid selected character being resolved. If smart-client attach hints return later, they should be re-sent as hidden MCP metadata on the new connection.
- Web clients reconnect by rerunning bootstrap discovery and, in the target when required, `Join & Play` plus realm-scoped `CHARS`/allowed character creation before obtaining a fresh connect token; they then open a new WebSocket to `/ws/game/**`, issue bare `LOGIN`, and issue `PLAY`. Current first-party browser/mobile bootstrap/connect-token surfaces are partial and the complete flow, including `Join & Play`, remains target-only. The target `Join & Play` and any required character repair therefore precede connect-token issuance and bare `LOGIN`. The token-backed WebSocket flow uses the fresh, short-lived discovery snapshot only for its selected edge target and discards it on expiry or scope mismatch. A valid snapshot is not a substitute for current `ACTIVE` membership or a valid character, and an invalid character must be repaired before token issuance rather than discovered after the socket opens. Web clients must not assume that any prior MCP or hidden smart-client attach metadata has survived, as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery).

### Canonical first-party reconnect-shortcut gate

A returning first-party browser/WebSocket player may skip `Join & Play` only when all of the following are established before connect-token issuance: fresh same-target entitlement, current authority, one fresh atomic Account membership snapshot confirming `membershipLifecycleState=ACTIVE` with independent `membershipAuthorityGeneration` and `membershipVersion`, the exact current Account-owned realm grant where applicable, an unexpired complete discovery snapshot, and one valid current character for the selected target. If the checks occur at `POST /auth/connect-token`, that endpoint must complete them before issuing the token; discovery state and cached authority are not substitutes. An unavailable exact realm grant fails closed for private/playtest admission.

For clarity, Telnet clients do not receive hidden recovery after their TCP socket or TCP Proxy-to-Gateway edge bridge is lost. Even if the proxy-wide admission circuit later returns to closed after such an edge outage, the affected Telnet client must reconnect on a fresh TCP socket and follow the applicable Authentication-owned `WORLDS`/credential-bearing `LOGIN`/authenticated `REALMS`/target-only conditional `JOIN`/conditional `CHARS` or character-creation/`PLAY` flow; current text stops with non-actionable `JOIN_REQUIRED` and no membership mutation only when an otherwise eligible public-production request reports `membershipExists=false` and explicit `JOIN` is required. An existing response with `gameplayAdmissionAllowed=false` remains the established denial. This does not prevent Gateway from retaining a still-established proxy bridge while it performs ADR 0013's bounded rebind of its separate Game Session upstream.

Clients must also treat pre-disconnect input and output as non-resumable transport state. FireMUD does not replay client commands, raw transport bytes, prior WebSocket frames, unsent Telnet output, or partially delivered prompts onto a newly opened connection. A command durably admitted before edge loss continues under its existing server-side command/effect identity; it is never recreated from client input. Instead, after fresh `LOGIN` and `PLAY`, reconnect restores player-visible context in three distinct steps:

- current implementation persists ordered `resume_transcript_entry` rows and can best-effort freshly render available retained context after a binding-checked eligible reconnect; the owner records the namespace, complete-entry bounds, oversize handling, logout suppression, structured metadata, and current proof gaps in [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#canonical-resume-context-model). In target behavior, explicit gameplay `LOGOUT` excludes the terminated binding from private context replay; post-logout suppression remains a current implementation gap under that owner;
- the context may repeat output the client already displayed or omit output that was not retained or available. It is not a delivery acknowledgement, exact missed-message list, command history, or transcript archive; Redis may cache it but is not authoritative, and physical deletion may complete asynchronously;
- then current reconnect flow obtains a fresh authoritative `LOOK` and applies the owner-defined prompt behavior. The local consequence is exactly one reconnect prompt when both effective reconnect-prompt settings are enabled, or zero when either is disabled; prompt restoration and duplicate-prevention proof remain current gaps recorded in [Input, Output and Presentation Implemented Status](./system-architecture-input-output-and-presentation.md#implemented-status). This reconstruction defines current state; retained context is narrative orientation only.

The hot reconnect semantic context is context restoration, not a transport delivery guarantee. It exists to help players understand what just happened around a disconnect; clients must not label it as missed messages or infer which bytes the old transport delivered. A complete Player Transcript Archive and Export, if later adopted, is a separate product with its own retention, privacy, access, and deletion contract.

Prompt/status output is a separate output class from transcript lines:

- prompts are coalesced UI/state summaries, not ordinary scrollback messages;
- prompts are not part of the reconnect screen buffer by default;
- reconnect should restore semantic context first, then obtain a fresh authoritative `LOOK`, then apply the owner-defined prompt behavior;
- first-party web and MCP-aware clients may consume prompt/status as structured data instead of rendering it into the main text transcript.

Service-local properties and effective persisted-override precedence are owned by [Settings Model](./system-architecture-settings-model.md); reconnect prompt and output behavior are owned by [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#canonical-resume-context-model). This document retains only the reconnect consequence: prompt lines are excluded from semantic recent-context restoration, and effective settings determine whether the fresh reconnect prompt is emitted.

### Canonical durable resume-context policy

The durable semantic recent-context, binding logout behavior, retention bounds, and Game Session persistence model are canonical in [Input, Output, and Presentation](./system-architecture-input-output-and-presentation.md#canonical-resume-context-model) and [Session Behavior](./system-architecture-session-behavior.md#gameplay-logout-and-resume-transcript). This document retains the client-visible consequence: after an eligible reconnect, a new transport receives bounded context freshly rendered from retained entries, then a fresh authoritative `LOOK` and the owner-defined prompt result. Raw client input, prior bytes, frames, and unsent transport output are never replayed.

### Abnormal WebSocket Transport Loss

Client implementations must not assume every disconnect includes a close frame and reason token. Planned drains and many graceful failures will carry canonical close codes, but abrupt process/node/network failures can terminate transport before the edge emits a close frame.

- If the connection drops without a usable close code/reason, classify it as `internal_error` for retry/backoff policy purposes.
- Do not treat missing close metadata as `logout` or `policy_violation`.

### Gameplay WebSocket route handshake policy (normative)

The canonical `/ws/game/**` route, carrier, trust marker, and connect-token validation rules are owned by [Gateway architecture](./system-architecture-gateway.md#gameplay-websocket-route). This section retains the client-visible handshake status/error matrix and its retry policy.

### HTTP Handshake Failures on `/ws/game/**`

When Web clients attempt to establish or re-establish WebSocket connections and receive HTTP errors instead of a successful upgrade, they must interpret those signals consistently with the close-code taxonomy:

This table is the canonical client-policy matrix for gameplay-route handshake failures; gateway and client documentation must reference this mapping rather than redefining retry semantics independently.

### Browser failed-upgrade recovery

Browser WebSocket APIs cannot observe failed HTTP upgrade responses reliably. Each failed-upgrade episode therefore allows exactly one fresh discovery/token retry: discard the failed discovery/connect-token bundle, rerun bootstrap discovery, obtain one fresh token, and retry once with bounded backoff. If that retry fails, the episode budget is exhausted; stop automatic refresh and reconnect and surface recovery guidance instead of fast-looping. A later user-directed recovery may begin a new episode.

Clients that can observe the failed HTTP upgrade must key on handshake error class first, then HTTP status as a secondary signal. First-party browsers follow the [browser failed-upgrade recovery rule](#browser-failed-upgrade-recovery) rather than guessing a row from this table; capable non-browser callers may use the detailed classes below.

| HTTP status | Handshake error class | Meaning on gameplay routes | Client policy |
| --- | --- | --- | --- |
| `429` | `POLICY_PRESSURE` | Edge rate or connection policy boundary reached | Classify as `policy_pressure_retriable`: retry with slower exponential backoff (start `5s`, cap `120s`, ±25% jitter) and strict retry caps; do not fast-loop. |
| `503` | `BACKEND_UNAVAILABLE` | Gateway currently considers gameplay backend unavailable | Treat as `1013/backend_unavailable`; use exponential backoff with jitter and retry caps. |
| `403` | `CONNECT_TOKEN_MISSING` | No connect-token carrier was supplied. For the current `first_party_web` protected-cookie handshake, this means no `Firemud-Connect-Token` cookie; a header-only `X-Firemud-Connect-Token` request on an unsupported or unregistered `non_first_party_public` route is `CONNECT_TOKEN_REJECTED`, not missing. A present but invalid value remains classified as expired, replayed, or rejected (including malformed, signature-invalid, missing-claim, or wrong-audience values). The dedicated header carrier is target-only/unavailable and is never a fallback. | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); if repeated after fresh-token refresh, surface login/session-recovery action to the user. |
| `403` | `CONNECT_TOKEN_EXPIRED` | Connect token expired before handshake validation completed | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter). |
| `403` | `CONNECT_TOKEN_REPLAYED` | Connect token `jti` was already used within the replay window | Acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); repeated replay failures should surface a session-recovery action rather than fast-looping. |
| `403` | `CONNECT_SCOPE_MISMATCH` | Before WebSocket upgrade, the verified connect-token target does not match the current request/admission scope | Discard the stale discovery/connect-token bundle, re-run bootstrap/discovery, complete `Join & Play` when the newly selected public target requires membership, perform required character discovery/creation, and only then acquire a fresh connect token from the newly returned scope; never reuse the old `connectScopeId` or infer a target from cached tenant/realm/game-instance values. |
| `403` | `CONNECT_REPLAY_PROTECTION_UNAVAILABLE` | Gateway cannot validate connect-token replay state and fail-closes | Retry with bounded exponential backoff (start `10s`, cap `60s`, ±25% jitter) and surface temporary edge-auth-unavailable context (not backend-outage messaging). |
| `403` | `CONNECT_TOKEN_REJECTED` | The supplied carrier/route is unsupported or unregistered, or a supported carrier contains malformed, signature-invalid, missing-claim, wrong-audience, or otherwise invalid token content | For an unsupported carrier/route, use the supported carrier/route and do not refresh the token. For invalid token content, acquire a fresh connect token and retry with bounded exponential backoff (start `2s`, cap `30s`, ±25% jitter); if repeated after fresh-token refresh, surface login/session-recovery action to the user. |
| `403` | `POLICY_DENY` | Handshake denied by gateway policy/trust boundary (for example internal-only listener, mTLS identity, security policy mismatch) | Treat as non-retriable until configuration/permissions change; surface as actionable operator/user error. |
| `426` | `PROTOCOL_MISMATCH` | Protocol upgrade requirement not met | Retry only after client transport/protocol correction (for example proper WebSocket upgrade/TLS endpoint). |
| Other `5xx` | `INTERNAL_ERROR` | Unexpected gateway/infra failure | Treat as `internal_error`; use exponential backoff with jitter. |

For gameplay routes, HTTP `401` is not part of the normal handshake taxonomy because gameplay authentication occurs after WebSocket establishment via `LOGIN`/`PLAY`. If `401` appears in practice, treat it as a misconfiguration signal and investigate gateway policy drift.

### Post-upgrade Game Session admission failures

The preceding table applies only before the server returns `101 Switching Protocols`. Once `/ws/game/**` has upgraded, a scope mismatch is a Game Session protocol outcome rather than an HTTP handshake response. If `PLAY` or the signed connect-context admission check finds that the requested target does not match the verified connect-token scope, Game Session emits `ERROR CONNECT_SCOPE_MISMATCH` on the established protocol and closes the socket with the existing `1008/policy_violation` close category. The client must discard the stale bootstrap/discovery state, rerun bootstrap discovery to obtain the current authoritative tenant/realm/game-instance scope, complete HTTP `Join & Play` if that newly discovered target requires membership, perform required realm-scoped character discovery or creation, obtain a fresh connect token from the newly returned `connectScopeId`, and open a new socket; it must not retry `PLAY` on the rejected socket, reuse the stale selector, infer the target from cached values, or treat the outcome as an HTTP `403`.

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

To avoid ambiguous half-open behavior for already-established Telnet sessions, the TCP Proxy bridge uses an explicit per-connection state machine. The exact WebSocket-to-Telnet mapping is owned by the [canonical close translation matrix](./system-architecture-gateway.md#canonical-close-translation-matrix); Reconnection retains only these local recovery consequences:

- `healthy` – upstream WebSocket bridge is established; gameplay traffic flows normally.
- `close_due_to_valid_upstream` – preserve every valid authenticated Gateway top-level close as the same Telnet token, including planned `service_restart`; bounded subreason context remains diagnostic only.
- `close_due_to_unattributed_loss` – absent or invalid authenticated bridge attribution closes the Telnet connection immediately with the observation-specific `backend_unavailable` fallback. The proxy does not keep the client TCP socket open while attempting a hidden gameplay-bridge reattach.
- `close_due_to_edge_backpressure` – if buffered lines exceed `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` while upstream is still reachable, close that Telnet connection with top-level `policy_violation` and emit `edge_backpressure` as diagnostic context only; clients retain the top-level policy-violation retry behavior.

This state machine is authoritative for established-session behavior and complements (but does not replace) the proxy-wide open/half-open/closed admission breaker used for new Telnet admissions.

### NotifyDisconnect Behavioral Contract (Summary)

The TCP Proxy Service emits `NotifyDisconnect` events to the Game Session Service over an internal-only gRPC stream whenever Telnet sockets close. Behaviourally, this stream follows a simple, canonical contract:

- **At-least-once, advisory delivery** – Transport for `NotifyDisconnect` is intentionally at-least-once: events may be delivered more than once or arrive late relative to the underlying TCP close. Game Session treats this stream as a best-effort hint, not as proof that a current gameplay binding should be removed. Current binding identity/generation and authoritative session transitions decide the outcome; Redis coordination state may be lost under its reset contract.
- **Idempotency key** – Every event carries an idempotency key derived from `{proxyConnectionId, disconnectSequence}`. Game Session persists the latest processed `disconnectSequence` per `proxyConnectionId` and must treat older or duplicate events for the same pair as no-ops so proxy-side retries remain simple and safe.
- **Idempotency record retention** – Game Session retains the latest processed sequence record per `proxyConnectionId` for at least `session_expiration_ms + TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` so delayed retries and reconnect races remain deduplicated. These records then expire via TTL-based cleanup; the retention horizon is a minimum, not a reason to shorten the record lifetime.
- **Allowed dedupe storage shapes** – These dedupe records may live in restart-persistent Redis for the bounded retry window only when total dedupe loss is harmless against current binding/generation checks. Any advisory capable of repeating a canonical side effect requires an owner-durable idempotency result instead. In-memory-only dedupe is not sufficient across ordinary Game Session restart.
- **Capacity and eviction guardrails** – Consumer implementations must size each bounded partition for the complete retention horizon and expected active cardinality (for example per tenant and per process shard). Records must not be evicted while unexpired; if a partition reaches capacity during a disconnect flood, implementations must emit explicit overload metrics/logs (for example `gamesession.notifydisconnect.dedupe.capacity_reached`) and apply `oldest-expiry-first` only among records that have already expired. Capacity pressure cannot shorten the required dedupe horizon.
- **Eviction scope** – When capacity guardrails are partitioned by shard or tenant, expiry-based eviction applies within the partition that reached its cap; implementations must not silently switch to an unbounded global pool or evict an unexpired record during floods. Canonical examples include a process-local shard partition or a tenant-scoped shard partition, but any chosen partition boundary must remain bounded, sized for the horizon, explicit, and operationally observable.
- **Operator interpretation** – Sustained `gamesession.notifydisconnect.dedupe.capacity_reached` during a regional outage should be treated as disconnect-flood pressure on the dedupe path, not as evidence that duplicate teardown is acceptable. Operators should scale or relieve the affected partition and confirm Redis/durable-store health before relaxing retention guarantees.
- **Runbook linkage** – During live incidents, use the Telnet degraded runbook for the operator workflow around `gamesession.notifydisconnect.dedupe.capacity_reached` and related bridge-failure signals; this section remains the canonical behavior contract, while the runbook covers triage order and mitigation steps.
- **Retry window** – The proxy retries failed `NotifyDisconnect` calls for a short, bounded window after Telnet socket close (see the TCP Proxy Service design’s **Service Interactions** section and the `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` configuration for exact timing). After this window, the proxy stops retrying and relies on Game Session’s own liveness detection and Redis timeouts to reconcile any missing hints.
- **Complement to edge delivery guarantees** – `NotifyDisconnect` complements, but does not change, the pre-acceptance at-most-once edge delivery model described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants). Late or duplicate hints must never tear down a healthy newer binding; consumers check the event identity plus current binding/generation rather than relying on Redis dedupe presence as authority.

Resume does not imply replay of prior outbound transport data. After reconnect, Game Session may emit fresh reconstruction output once the new binding is admitted, but it must not replay pre-disconnect text or MCP frames as if the old transport had continued. Any reconstruction output must be re-derived from current authoritative state, not copied from an old outbound queue.

This section is the canonical behaviour-level summary for `NotifyDisconnect`. The TCP Proxy Service design remains authoritative for message fields, configuration knobs, and implementation details, while [gRPC API Style & Versioning](./system-architecture-grpc.md#event-and-streaming-semantics) defines the general pattern for similar at-least-once event sinks elsewhere in the system.

---

## Related Documentation

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Game Session Service](./microservices/game-session-service/README.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
