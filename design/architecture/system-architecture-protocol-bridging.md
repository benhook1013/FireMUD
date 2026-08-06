# Protocol Bridging: WebSocket and Telnet (TCP)

This document describes how FireMUD supports **both modern and traditional MUD clients** by bridging two distinct communication protocols: **WebSocket** and **TCP/Telnet**. Both are routed into a unified backend session service for shared logic and scalability.

This design is the canonical specification for protocol-specific gameplay carriage through the edge: it defines ordering and delivery guarantees, backpressure and slow-client behaviour, Telnet and WebSocket reconnection and buffering rules, and the Telnet disconnect reason taxonomy. [Gateway architecture](./system-architecture-gateway.md) owns route lifecycle, gameplay sharding, lease-aware selection, and canonical close translation; [Authentication](./system-architecture-authentication.md) owns `LOGIN`, `JOIN`, and `PLAY` admission semantics. Service-specific designs retain their transport configuration and apply those owning contracts. **Target direct-text flow:** fresh public `WORLDS` discovery -> credential-bearing Account email `LOGIN <email> <secret>` (or the supported email-code challenge) -> authenticated `REALMS` discovery -> conditional explicit in-band `JOIN` -> conditional realm-scoped `CHARS`/character creation -> `PLAY`; `LOGIN` establishes only socket/account authentication and `PLAY` performs gameplay binding. Only that public-production join may create missing or restore `INACTIVE` durable player membership. A direct-text client may omit `JOIN` only for an already `ACTIVE` member and may omit a separate character-discovery round-trip only when fresh transport-local resolution supplies one valid current character; it never consumes the first-party WebSocket discovery snapshot shortcut. First-party token-backed WebSocket clients may use the selected-character shortcut only with an unexpired complete discovery snapshot, current `ACTIVE` membership, and a valid current character. Browser and other first-party WebSocket clients use HTTP `Join & Play` before connect-token issuance, then complete bare in-band `LOGIN` -> `PLAY` without an in-band `JOIN`. Grant-backed private/playtest entry requires existing `membershipLifecycleState=ACTIVE` membership and the current grant, skips `JOIN`, and never auto-creates membership.

## Implementation Status

The target flow and delivery contracts below remain normative. Explicit `JOIN`/`Join & Play` and the required exact `membershipAuthorityGeneration`, `membershipLifecycleState`, and `membershipVersion` reread at connect-token issuance remain unimplemented/proof gaps in the current runtime. Current connect-token issuance and text `PLAY` require existing `membershipLifecycleState=ACTIVE` membership and fail closed with non-actionable `JOIN_REQUIRED` for eligible missing or `INACTIVE` public-production membership. The obsolete implicit membership-writer surface has been removed. Current non-public missing or non-`ACTIVE` membership remains `WORLD_ACCESS_DENIED`; target-only `NON_PUBLIC_ENROLLMENT_REQUIRED` is defined below. Current private/playtest text `PLAY` also requires the current realm grant, while connect-token grant validation remains implementation drift. Returning active members may proceed through the current existing-membership path, but that behavior does not prove the target generation/version reread at connect-token issuance; the target requires both values from one fresh membership snapshot. Grant-backed private/playtest flows remain available only when their own current membership, grant, entitlement, routing, and membership-generation/version checks pass. This implementation drift does not relax the at-most-once delivery, authentication, admission, reconnect, or close-taxonomy requirements.

---

## Bridging Overview

FireMUD enables real-time interaction through two types of client connections:

| Client Type | Protocol | Entry Point |
| --- | --- | --- |
| Web-based clients | WebSocket | Spring Cloud Gateway (`/ws/game/**`) |
| Traditional MUD clients | TCP/Telnet | TCP Proxy Service (custom) |

Despite their differences, both protocols are normalized into the same internal architecture using a **WebSocket-based session layer**.

---

## WebSocket Client Flow (Modern Clients)

- Used by browser-based MUD clients or modern tools.
- Connections are initiated using the WebSocket protocol.
- Routed through the [Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md), which supports WebSocket proxying.
- Canonical player-facing endpoint is `/ws/game/**` (token-enforced for non-proxy clients; trusted TCP Proxy bridge is authenticated by mTLS identity).
- Forwarded to the [Game Session Service](./microservices/game-session-service/README.md), which maintains the gameplay session.
- Game Session restart and edge reconnect behavior follow [Reconnection Strategy](./system-architecture-reconnection.md) and [Session Behavior](./system-architecture-session-behavior.md). The local protocol consequence is that a socket terminated by its serving Gateway instance opens a fresh `/ws/game/**` connection, while unaffected sockets on healthy Gateway instances remain up.

### WebSocket Flow Benefits

- Leverages Spring Cloud Gateway’s routing, header enforcement and forwarding, logging, and rate limiting while leaving `LOGIN`/`JOIN`/`PLAY` authority to [Authentication](./system-architecture-authentication.md).
- Ideal for web UIs, admin tools, or companion clients.

### Gameplay WebSocket route contract (normative)

- `/ws/game/**` is the only gameplay WebSocket route. The canonical carrier, replay, handshake, and Gateway-to-Game-Session context rules are owned by [Gateway architecture](./system-architecture-gateway.md#gameplay-websocket-route) and [Authentication](./system-architecture-authentication.md#websocket-connect-token-contract-ws-game).
- The supported `first_party_web` variant requires the protected `Firemud-Connect-Token` cookie; an absent cookie is `CONNECT_TOKEN_MISSING`, while a present but invalid value uses the applicable expired, replayed, or rejected class. A target-only `non_first_party_public` variant may use `X-Firemud-Connect-Token` only after its dedicated route registration and issuance, replay, signed-context, response, and carrier proof; the header is not a fallback for the cookie route and is rejected as `CONNECT_TOKEN_REJECTED` until then. These handshake outcomes return HTTP `403`. TCP Proxy bridge traffic is admitted only through the authenticated internal mTLS and header-trust branch and consumes no gameplay connect token.
- All gameplay sessions require in-band `LOGIN` and `PLAY` before gameplay commands. `LOGIN` establishes socket/account authentication and `PLAY` performs gameplay admission and binding under [Authentication](./system-architecture-authentication.md#login-and-session-flow). The current implementation caveat remains: explicit join is unimplemented, current text `PLAY` and connect-token issuance require existing `ACTIVE` membership, and eligible missing or `INACTIVE` public-production membership returns non-actionable `JOIN_REQUIRED` without creating membership.

For browser onboarding and reconnect, the realm-scoped character gate remains in the sequence: first-time public entry, or a returning flow whose selected character is missing, invalid, or no longer visible for the resolved realm, must complete current `CHARS`/allowed character creation after valid `ACTIVE` membership and before `POST /auth/connect-token` and WebSocket `PLAY`. `CHARS` and character creation require the applicable membership/grant and entitlement checks but do not require an existing character; a character becomes mandatory only before connect-token issuance and `PLAY`. The returning shortcut is valid only when the current `ACTIVE` membership, current grant where applicable, unexpired complete discovery snapshot, and valid current character all match the target. A connect token carries a short-lived discovery snapshot for edge admission; it does not bypass `CHARS` or turn stale character evidence into gameplay authority.

Every target connect-token issuance and `LOGIN` -> `PLAY` path uses the canonical admission checks in [Authentication](./system-architecture-authentication.md#in-band-play-admission-boundary). This document retains only the transport consequence: the bridge forwards the protocol in order and does not create or restore membership.

### Public membership repair gate (normative)

For public-production repair, clients follow the canonical [Authentication membership and admission flow](./system-architecture-authentication.md#login-and-session-flow). The local bridge consequence is transport-specific: credential-bearing text clients perform public `WORLDS`, credential-bearing `LOGIN`, and authenticated `REALMS` discovery before taking the conditional in-band `JOIN`; `CHARS` or character creation follows only after membership is `ACTIVE`. A denied repair preserves that authenticated session and stops before character discovery, creation, or `PLAY`. First-party WebSocket clients complete HTTP `Join & Play` before connect-token issuance, socket open, bare `LOGIN`, or `PLAY`; a denied repair preserves bootstrap authentication and membership state, creates no binding, and stops before character repair, token issuance, socket admission, and gameplay login. The wire outcomes remain `ENTITLEMENT_UNAVAILABLE`, `PUBLIC_PRODUCTION_ADMISSION_DENIED`, `ADMISSION_POINTER_UNAVAILABLE`, `REALM_UNAVAILABLE`, `CONNECT_SCOPE_MISMATCH`, or `JOIN_REQUIRED` as applicable; existing `ACTIVE` members do not use repair.

### Canonical `PLAY` Error Inventory

Game Session translates admission outcomes into the shared `ERROR <CODE> <message>` form on the established gameplay protocol. This inventory owns the canonical `PLAY` precedence; it is not an inventory of every character or protocol-stage error. All listed admission outcomes preserve the authenticated pre-`PLAY` session and create no gameplay binding.

The precedence and error rows below are the normative target inventory. Current runtime mapping is narrower: eligible public missing or `INACTIVE` membership returns non-actionable `JOIN_REQUIRED` while explicit `JOIN` is unimplemented, and current non-public missing or non-`ACTIVE` membership remains `WORLD_ACCESS_DENIED`; the target-only `NON_PUBLIC_ENROLLMENT_REQUIRED` row must not be reported as current behavior.

Admission precedence is deterministic and evaluated in stages: first classify current routing/pointer/catalog evidence (`ADMISSION_POINTER_UNAVAILABLE` for incomplete or unavailable evidence, `REALM_UNAVAILABLE` for a complete `CLOSED` pointer); then evaluate verified connect scope (`CONNECT_SCOPE_MISMATCH`); then evaluate fresh Account entitlement and other current authority (`ENTITLEMENT_UNAVAILABLE` for an entitlement outage, `TENANT_BILLING_BLOCKED` or `PUBLIC_PRODUCTION_ADMISSION_DENIED` for authoritative denials, and `AUTH_UNAVAILABLE` for a non-entitlement authority outage); only then classify public missing/`INACTIVE` membership as `JOIN_REQUIRED`, and only when the Account-owned `allowPublicJoin` value is true. `WORLD_ACCESS_DENIED` is reserved for a reachable authoritative world/tenant denial for a reason other than missing or `INACTIVE` public-production membership, so it is mutually exclusive with `JOIN_REQUIRED`; public missing/`INACTIVE` membership uses `JOIN_REQUIRED` after the earlier gates pass. Non-public enrollment and grant behavior remains distinct after those shared gates: missing or non-`ACTIVE` membership is `NON_PUBLIC_ENROLLMENT_REQUIRED`, and a missing or invalid grant is `REALM_ACCESS_DENIED`. A routing failure masks scope, entitlement, authority, membership, grant, and world-denial results; a scope mismatch masks all later results; and an entitlement outage or authoritative denial masks both public `JOIN_REQUIRED` and non-public enrollment/grant results. When both non-public membership and grant predicates fail after the shared gates pass, membership remains the first non-public outcome and returns `NON_PUBLIC_ENROLLMENT_REQUIRED`; neither non-public outcome becomes public `JOIN_REQUIRED`.

- `ADMISSION_POINTER_UNAVAILABLE` - Catalog/pointer evidence is missing, malformed, ambiguous, stale, unreachable, or timed out. Preserve authentication, create no binding, rerun discovery or reconciliation, and retry only with fresh routing evidence. This is never `AUTH_UNAVAILABLE`.
- `REALM_UNAVAILABLE` - The resolved realm pointer is complete and `CLOSED`. Preserve authentication, create no binding, and wait for a fresh authoritative availability result rather than fast-looping a retry.
- `CONNECT_SCOPE_MISMATCH` - For token-backed `/ws/game/**` admission, the verified connect scope no longer matches the server-resolved target. Preserve authentication, discard the entire discovery snapshot and all derived connect-token metadata including `catalogRevision`, then rediscover, request a fresh token, and use a new socket before retrying `PLAY`.
- `ENTITLEMENT_UNAVAILABLE` - Fresh entitlement authority cannot be established for `PLAY` or the join/admission decision. Preserve authentication, create no gameplay binding or new membership, and retry with bounded backoff; stale or last-known-good entitlement may supply input only for the explicitly eligible, exact same-binding, non-expanding continuation/resume contract in [ADR 0028](./decisions/adr-0028-differentiated-entitlement-freshness.md). It never authorizes `JoinPublicProductionMembership`, any membership creation, or restoration of `INACTIVE` membership; explicit join is a strict new commitment and requires fresh entitlement. This is distinct from `AUTH_UNAVAILABLE`, which covers unavailable membership, grant, registry, or other required non-routing, non-entitlement authority.
- `TENANT_BILLING_BLOCKED` - Account has authoritatively denied the tenant for billing. Preserve authentication and the lobby/session, create no binding, and keep billing-safe operations available unless separately denied; do not use this code when entitlement authority is unavailable.
- `PUBLIC_PRODUCTION_ADMISSION_DENIED` - Current Account entitlement, including `allowPublicJoin=false`, or other authoritative public-production admission rules deny first entry for a nonbilling reason. Preserve authentication and membership state, create no binding, do not create or restore membership, present a nonbilling admission denial, and do not present billing recovery or retry automatically until the policy/target changes.
- `WORLD_ACCESS_DENIED` - Reachable authoritative world/tenant policy denies gameplay for the resolved target for a reason other than missing or `INACTIVE` public-production membership. Preserve authentication, create no binding, and do not use this outcome when the canonical public membership predicate is false; that case is `JOIN_REQUIRED` after the earlier precedence gates.
- `JOIN_REQUIRED` - The current public-production membership predicate is false because durable membership is missing or `INACTIVE`, the fresh Account entitlement snapshot carries `allowPublicJoin=true`, and fresh selected-target catalog/pointer evidence identifies the visible public-production target. Preserve authentication, present explicit `JOIN` or `Join & Play` as the restorable onboarding action, and block character creation, connect-token issuance, and `PLAY` retry until it succeeds. `JOIN` creates missing membership or restores `INACTIVE`; private/playtest membership or grant failure is not a public join fallback. For missing or `INACTIVE` public membership, pointer/routing failure returns `ADMISSION_POINTER_UNAVAILABLE` or `REALM_UNAVAILABLE`, scope failure returns `CONNECT_SCOPE_MISMATCH`, entitlement outage returns `ENTITLEMENT_UNAVAILABLE`, authoritative billing denial returns `TENANT_BILLING_BLOCKED`, and authoritative nonbilling denial, including `allowPublicJoin=false`, returns `PUBLIC_PRODUCTION_ADMISSION_DENIED`; stale or last-known-good entitlement cannot produce `JOIN_REQUIRED` or authorize join. Only when those earlier checks pass with fresh Account entitlement and fresh selected-target evidence is the result `JOIN_REQUIRED`.
- `NON_PUBLIC_ENROLLMENT_REQUIRED` - A private, playtest, or other non-public target has missing or non-`ACTIVE` caller membership. Preserve authentication, create no binding, present the stable non-public enrollment/membership requirement, and do not offer public `JOIN` or automatically retry. This is distinct from `REALM_ACCESS_DENIED` for a missing/revoked/expired grant and from `AUTH_UNAVAILABLE` for an authority outage.
- `REALM_ACCESS_DENIED` - A private, playtest, or other non-public target lacks the current Account-owned realm-access grant. Preserve authentication, create no binding, present grant/access guidance, and do not treat the result as a membership-enrollment or authority-outage condition.
- `AUTH_UNAVAILABLE` - A required current non-routing, non-entitlement dependency, including membership authority or its generation, grant authority, or the token registry, is unreachable or times out. Preserve authentication, create no gameplay binding, and retry the affected admission with bounded backoff; unavailable authority cannot be interpreted as missing/`INACTIVE` membership and cached authority cannot authorize it. Reachable stale, regressed, malformed, revoked, or mismatched evidence is the applicable denial or revocation outcome, not `AUTH_UNAVAILABLE`. Routing/pointer failures use `ADMISSION_POINTER_UNAVAILABLE`, and entitlement authority failures use `ENTITLEMENT_UNAVAILABLE` instead.

---

## Telnet / TCP Client Flow (Legacy Clients)

- Used by traditional MUD clients (e.g., MUDlet, TinTin++, GMud).
- Clients connect using TCP/Telnet and are handled by a dedicated **TCP Proxy Service**.
- For local development and explicitly private compatibility networks, the TCP Proxy Service listens on port `2323` by default. This plaintext/default port is not a public player-facing contract; public deployments must select one of the TLS modes below. The port and the Spring Cloud Gateway WebSocket URL can be adjusted with the `TCP_PROXY_PORT` and `GATEWAY_WS_URL` environment variables described in the [TCP Proxy Service configuration](./microservices/tcp-proxy-service/configuration.md#environment-variables). `GATEWAY_WS_URL` should always be set explicitly by deployment config; local Compose smoke also sets it explicitly to the canonical in-stack target. See [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md) for general configuration guidance.
- Direct text/Telnet clients must perform fresh public `WORLDS` discovery before credential-bearing `LOGIN`, then fresh authenticated `REALMS` / `CHARS` discovery as needed. `REALMS`, membership, character selection, and `PLAY` follow the canonical [Authentication](./system-architecture-authentication.md#login-and-session-flow) contract; unusable realm-pointer evidence remains `ADMISSION_POINTER_UNAVAILABLE` and a complete `CLOSED` realm remains `REALM_UNAVAILABLE`.
- Direct text/Telnet reconnect is a fresh transport and discovery path described in [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour). The proxy forwards the new `WORLDS` -> credential-bearing `LOGIN` -> authenticated `REALMS` -> conditional `JOIN` -> `CHARS`/character-gate -> `PLAY` sequence without reusing a browser snapshot or stale token. Current proxy routing still bootstraps a hidden default and does not enforce `WORLDS` as a prerequisite; explicit-join and membership-reread gaps remain those recorded in [Authentication](./system-architecture-authentication.md#implementation-status).

Target private/playtest reconnect does not use public `JOIN`; missing or non-`ACTIVE` membership is `NON_PUBLIC_ENROLLMENT_REQUIRED`, and a missing or invalid grant is `REALM_ACCESS_DENIED`. Current private/playtest missing or non-`ACTIVE` membership remains `WORLD_ACCESS_DENIED`; current text `PLAY` requires the current realm grant, while connect-token grant validation remains implementation drift.

A retained-edge upstream rebind is not a reconnect from the client's perspective. Gateway and the proxy may rebind the established edge to a replacement Game Session owner without repeating `LOGIN`, `JOIN`, or `PLAY`; this exception does not change the new-session rules above for a newly opened socket or a client-facing edge disconnect.

### Telnet `JOIN` Resolution Contract

`JOIN <world>` is a Telnet selector, not an authority-bearing tenant or route input. The text adapter keeps selectors local and forwards the authenticated `JOIN` operation to Game Session; Account-owned membership mutation and the `{connectScopeId, requestId}` binding are defined in [Authentication](./system-architecture-authentication.md#login-and-session-flow). Private/playtest realms do not use this operation and require existing membership plus their current grant.

### Public Telnet TLS Modes

The cross-service TLS and trust policy is owned by [Security](./system-architecture-security.md#plaintext-telnet-policy). This section retains the protocol-bridging mode selection and client-visible consequences.

Public player-facing Telnet has exactly one TLS termination mode per endpoint. These modes are mutually exclusive:

- **Edge termination plus internal PROXY mode** – The public Telnet edge terminates client TLS and forwards plaintext Telnet plus a PROXY header to the internal-only `TCP_PROXY_PROXY_PROTOCOL_PORT` over the authenticated, cryptographically protected channel required by Security. `TCP_PROXY_TLS_ENABLED=false`; raw and PROXY-protocol ports are not public.
- **Direct TCP Proxy TLS mode** – The client connects directly to a TCP Proxy TLS listener with `TCP_PROXY_TLS_ENABLED=true`. TCP Proxy terminates client TLS, no preceding edge terminates TLS, and the listener does not accept a PROXY header; client IP is the TCP peer address.

Do not configure both modes on one public path, and do not treat an edge-terminated plaintext hop as a public plaintext exception. The protocol-bridging checklist is:

- Select one mode and record the public listener, certificate owner, and internal target in deployment evidence.
- Prove the selected TLS handshake and plaintext rejection at the public endpoint.
- In both modes, prove Proxy -> Gateway uses the internal `wss://` mTLS listener and that the bridged Telnet client reaches the Authentication-owned `WORLDS -> LOGIN -> authenticated REALMS -> conditional JOIN -> realm-scoped CHARS/character creation -> PLAY -> LOOK` flow. Existing members may use the current abbreviated `LOGIN -> PLAY -> LOOK` compatibility path only after the receiving service confirms the current membership and character gates.

The proxy establishes the Proxy → Gateway gameplay WebSocket lazily for each Telnet connection:

- The bridge is opened before the first forwarded gameplay or MCP line.
- The proxy may include server-owned advisory bootstrap metadata on the authenticated Proxy → Gateway handshake when local defaults or future hidden MCP-carried smart-client hints are available.
- Those hints must never bypass fresh `WORLDS` discovery, `LOGIN`, authenticated `REALMS`/`CHARS` discovery, conditional `JOIN`, the realm-scoped character gate, or `PLAY`, and must never retroactively alter an already-established gameplay binding.

Planned Gateway drain example:

- Gateway closes the authenticated internal bridge with `1000/logout;subreason=gateway_restart`.
- TCP Proxy classifies that machine-parseable bridge close as `bridge_shutdown_class=planned_drain`.
- The Telnet client receives a final `logout` disconnect with `subreason=gateway_restart` rather than `backend_unavailable`.

Clean upstream logout example:

- Game Session or Gateway closes the authenticated internal bridge with `1000/logout` and a supported bounded subreason such as `takeover`, `user_logout`, `admin_termination`, or `none`.
- TCP Proxy preserves that clean upstream session-end signal as the Telnet-side `logout` category with the same bounded subreason instead of translating it into `backend_unavailable`.

Unattributed bridge-loss example affecting one established Telnet bridge:

- The authenticated internal bridge drops without a machine-parseable planned-drain close (for example abrupt transport reset or crash).
- TCP Proxy classifies the loss as `bridge_shutdown_class=unattributed_failure`.
- For that already-established Telnet session, the proxy closes the client connection immediately with `backend_unavailable`; it does not hold the TCP socket open for hidden bridge recovery. Other Telnet sessions whose bridges terminate on healthy Gateway instances should remain unaffected.

MCP negotiation and cord state are also scoped to a single TCP connection. When a Telnet client establishes a new TCP connection after an actual edge disconnect (including Gateway outages, TCP Proxy restarts, or client-side network loss), it must re-run MCP negotiation and re-open any required cords. Redis-backed gameplay session state (account/player identity and tick queues) is distinct from MCP metadata; durable actor gameplay state, including cooldowns, continues independently of the transport session. See [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery) for details.

---

## Ordering & Delivery Invariants

The combined TCP Proxy → Spring Cloud Gateway → Game Session path preserves a clear set of ordering and delivery guarantees for **gameplay command streams from clients into Game Session**:

- **Per-connection FIFO where delivered** – For a given Telnet/TCP or WebSocket connection, gameplay commands and text lines are forwarded to the Game Session Service in the same order they were accepted by the edge (TCP Proxy for Telnet, Gateway for WebSocket). No component in this client → Game Session command path intentionally reorders gameplay messages or generates duplicates.
- **At-most-once delivery with bounded loss (gameplay commands)** – The edge protocol path for gameplay commands is **at most once**: once a command on a given connection is dropped by any edge layer (for example due to buffer ceilings or abuse limits), it is not retried or replayed by that layer. “Bounded” here means that potential loss is limited to the commands still resident in that layer’s per-connection buffers at the time of failure; there is no implicit replay across disconnects. Higher-level retries and replay semantics live entirely in Game Session and domain services; see [Transactions & Idempotency](./system-architecture-transactions.md) for the canonical idempotency model.
- **No replay of prior outbound stream across reconnects** – The edge does not preserve or replay previously-sent server text, WebSocket frames, or MCP messages onto a new client transport after reconnect. After resume, Game Session may send fresh post-`PLAY` state or summaries, but it must not treat the new transport as a continuation of the prior byte stream.
- **At-least-once delivery (edge event sinks)** – Internal gRPC event sinks associated with the edge (for example the TCP Proxy’s `NotifyDisconnect` stream into Game Session) are intentionally **at-least-once** and must be consumed idempotently with respect to their idempotency keys, as described in [gRPC API Style & Versioning](./system-architecture-grpc.md#event-and-streaming-semantics). These streams are advisory hints that complement, but do not change, the at‑most‑once guarantees for client gameplay commands.
- **Explicit drop conditions (edge layers)** – Commands or lines may be dropped under clearly defined conditions, including:
  - TCP Proxy upstream-bridge failures: if the TCP Proxy cannot establish the Proxy → Gateway WebSocket bridge within its bounded retry budget because upstream gameplay is unavailable, it closes the Telnet connection with `backend_unavailable`. If handshake trust checks fail (for example mTLS certificate validation or policy deny), it closes with `policy_violation` instead. For established sessions where the bridge drops, the proxy closes the Telnet connection immediately according to the canonical disconnect taxonomy: clean authenticated `1000/logout` closes preserve `logout` with the corresponding bounded subreason, while unattributed established-session bridge loss maps to `backend_unavailable`. If upstream backpressure causes the proxy’s Telnet → Gateway input buffer ceiling to be exceeded while upstream remains reachable, it closes the connection with `policy_violation` and records `edge_backpressure` context in logs/metrics rather than silently discarding gameplay commands.
  - MCP-specific budgets (for example control-line rate and `_data-tag` continuation limits) that discard excess MCP control lines while keeping the connection open, as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md);
  - Abuse and safety limits in the TCP Proxy Service (oversize lines, malformed Telnet or MCP traffic) where the proxy either discards input or closes the connection;
  - Client-side disconnects or network loss (TCP/WebSocket) where the edge cannot reliably determine whether the last few bytes were delivered to the client.
- **No implicit replay on reconnect** – Neither Spring Cloud Gateway nor the TCP Proxy Service replays gameplay commands or MCP messages across reconnects. After an actual client-facing edge disconnect, or after authentication invalidation has terminated the active binding, direct text/Telnet clients must resend the applicable credential-bearing `LOGIN` flow, perform fresh discovery, resolve a new Account-bound `connectScopeId`, validate the current catalog/pointer pair, and establish fresh non-entitlement authority. `AUTH_UNAVAILABLE` preserves authentication and is retried before the client evaluates missing/`INACTIVE` membership, takes the conditional `JOIN` repair, or repairs a character. Once authority is available, public-production membership repair requires fresh selected-target evidence identifying the target plus fresh Account entitlement carrying `allowPublicJoin=true`; a denied policy/target returns `PUBLIC_PRODUCTION_ADMISSION_DENIED` and stops before character repair or `PLAY`. If the character is invalid, it must rerun character discovery/creation. This mode has no reusable connect-token reconnect snapshot: stale prior snapshot fields and token-backed `CONNECT_SCOPE_MISMATCH` state do not apply, but fresh `connectScopeId` resolution still does. Token-backed `/ws/game/**` clients must first pass the same separate entitlement and selected-target gates before completing HTTP `Join & Play` for missing or `INACTIVE` public membership, then obtain a fresh connect token, reconnect, and complete bare `LOGIN` and `PLAY`; a shortcut requires the same current membership and character checks and an unexpired complete discovery snapshot. Only that token-backed mode uses the reconnect snapshot bundle `{connectScopeId, tenantId, worldSlug, realmSlug, gameInstanceId, pointerVersion, catalogRevision, evaluatedAt, connectScopeExpiresAt}`; `CONNECT_SCOPE_MISMATCH` discards it and every derived connect-token field before fresh discovery/token issuance. Both client classes must re-run MCP negotiation for that new connection if they use MCP. A retained-edge Game Session upstream rebind is not a client reconnect and is exempt from repeating `LOGIN`, `JOIN`, and `PLAY`. Clients then rely on Game Session and Redis to resume or start fresh according to [Reconnection Strategy](./system-architecture-reconnection.md). Game Session and downstream domain services may use internal effect identifiers and transactional idempotency to protect tick processing and side effects, but these mechanisms are not exposed directly in the Telnet and WebSocket text protocol.

Edge behaviour distinguishes between **gameplay command lines** and **MCP/control lines**:

- Gameplay text commands that Game Session treats as input are never silently discarded while the connection remains open. When a gameplay line would exceed a non-MCP input or output safety limit, the TCP Proxy Service or gateway closes the connection with a clear reason rather than dropping the command in place.
- MCP control lines may be discarded under the MCP-specific budgets above while the connection stays open. When this happens, gameplay continues but MCP behaviour for that connection is effectively degraded or disabled as described in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery). Sustained symptoms that look like “partial gameplay output” or “missing gameplay commands without disconnects” should be treated as a bug in the edge or Game Session implementation rather than expected backpressure behaviour.

When any layer drops input due to its own limits or backpressure protections, it should close the connection with a clear, human-readable message or (for WebSocket clients) send an explicit error/close reason before terminating the session. Hard transport failures (for example process crash, node/network reset) may terminate sessions before any close frame or final line is emitted; clients must handle this abnormal transport-loss case per [Reconnection Strategy](./system-architecture-reconnection.md#abnormal-websocket-transport-loss). Edge components still do **not** silently discard gameplay commands while keeping a connection that appears healthy to the client.

Domain services treat incoming commands as **idempotent with respect to their effect identifiers** so that retries at the Game Session layer (for example tick replays) can safely handle duplicates even though the edge path is at-most-once. See [Transactions & Idempotency](./system-architecture-transactions.md) and [Redis Architecture](./system-architecture-redis.md) for the underlying invariants.

### Gameplay Command Idempotency (Client View)

External clients (WebSocket and Telnet) treat gameplay commands as **fire-and-forget** with respect to the edge:

- Clients do not attach idempotency keys, effect identifiers, or per-command sequence numbers to text commands as part of the Telnet or WebSocket protocol described in this document.
- When a command fails due to network loss, disconnect, or `backend_unavailable` conditions, clients surface the failure to the user and may choose to reissue the command as a new gameplay action, but there is no protocol-level replay contract.
- Idempotency and replay safety for ambiguous situations inside the tick system are handled entirely by Game Session and domain services using internal effect IDs and transactional safeguards as described in [Transactions & Idempotency](./system-architecture-transactions.md).

Architecture and service designs must not assume that external clients participate in any idempotency or sequence-key protocol beyond these fire-and-forget semantics.

### Telnet Disconnect Reasons

Telnet clients receive final disconnect messages from the TCP Proxy Service when connections close due to policy, slow-client behaviour, backend outages, or internal errors. To keep behaviour aligned with WebSocket close codes from [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts), the TCP Proxy Service standardises a small set of Telnet disconnect reason categories:

- `logout` – explicit, clean shutdown (user-initiated logout, takeover completion, admin-initiated session end, or planned edge drain); maps to WebSocket `1000` with reason `logout` and the corresponding bounded `subreason` defined in Gateway Architecture. When the authenticated upstream gameplay bridge closes cleanly with `1000/logout`, the TCP Proxy must preserve the Telnet-side category as `logout` and carry through the bounded subreason (`user_logout`, `takeover`, `gateway_restart`, `admin_termination`, or `none`) rather than translating that clean shutdown into `backend_unavailable`.
- `idle_timeout` – idle-connection timeout where no traffic has been observed within the configured idle window; maps to WebSocket `1001` with reason `idle_timeout`.
- `policy_violation` – client behaviour that violates platform policies (for example sustained command-rate abuse, malformed envelopes, repeated MCP negotiation failures, intentionally abusive traffic, or edge trust/policy handshake failures such as proxy mTLS certificate validation mismatch); maps to WebSocket `1008` with reason `policy_violation`.
- `backend_unavailable` – gameplay backend services (Game Session or critical dependencies) are unavailable or overloaded beyond well-defined grace windows on the WebSocket and Telnet paths. On the WebSocket side, Spring Cloud Gateway emits `1013/backend_unavailable` when ADR 0013's bounded upstream-recovery timer expires or the stalled-input buffer cannot accept more input, as described in [Gateway Architecture](./system-architecture-gateway.md#backend-unavailable-grace-window). On the Telnet side, the TCP Proxy emits `backend_unavailable` when its bridge-availability state determines gameplay admission is unavailable (including sustained inability to establish or maintain Proxy → Gateway gameplay connectivity) and does not keep ambiguous half-open gameplay sessions. Edge buffer-pressure closes map to `policy_violation` with `edge_backpressure` context only when the gameplay upstream is reachable; buffer exhaustion caused by a Game Session stall maps to `backend_unavailable`.
- `internal_error` – unexpected server-side failures not attributable to client behaviour and not clearly backend unavailable; maps to WebSocket `1011` with reason `internal_error`.

The authoritative cross-layer translation table and precedence rules for WebSocket and Telnet close outcomes live in [Gateway Architecture](./system-architecture-gateway.md#canonical-close-translation-matrix). This document defines the Telnet taxonomy and must remain consistent with that table.

The exact Telnet disconnect line format is defined in the TCP Proxy Service design as `DISCONNECT <reason-token> <human-message>\n`. Every player-visible disconnect must include one of these reason tokens so that:

- Client authors can treat `policy_violation` as non-retriable (or much longer backoff) and the others as retriable with the backoff rules in [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour), except when wire-visible disconnect metadata explicitly indicates edge backpressure (for example WebSocket `1008/policy_violation;subreason=edge_backpressure` or Telnet `policy_violation;subreason=edge_backpressure`), which should follow retriable backend-pressure policy. If this metadata is absent, default to non-retriable `policy_violation`.
- Operators can aggregate disconnect metrics by reason category in a way that lines up with WebSocket close-code dashboards.

Telnet disconnect messages and structured logs should preserve the same bounded subreason context used by the WebSocket side (`user_logout`, `takeover`, `gateway_restart`, `admin_termination`, `edge_backpressure`, `none`) so deploy drains and edge pressure can be distinguished from true outages without introducing a separate Telnet-only taxonomy. For Telnet disconnects caused by edge backpressure, `subreason=edge_backpressure` is mandatory on the wire so clients can apply the retriable policy deterministically.

Concrete clean-logout example:

- A Telnet client is already in gameplay.
- A second client successfully takes over the same `{tenantId, gameInstanceId, characterId}` binding, or an admin ends that session cleanly.
- Game Session closes the authenticated upstream gameplay bridge with `1000/logout;subreason=takeover` or `1000/logout;subreason=admin_termination`.
- TCP Proxy preserves that as Telnet `logout;subreason=takeover` or `logout;subreason=admin_termination`; it does not translate the event into `backend_unavailable`.

Any additional Telnet-specific reasons introduced in the TCP Proxy implementation must be documented here and mapped to one of the WebSocket categories above (or a new, explicitly added category) to keep the taxonomy unified.

### Cross-Client Takeover Examples

The underlying authentication and gameplay services enforce a **single active gameplay binding per `{tenantId, gameInstanceId, characterId}`**, as described in [Authentication & Authorization](./system-architecture-authentication.md#multi-client-behavior-and-session-takeover) and [Reconnection Strategy](./system-architecture-reconnection.md#resume-vs-reload-scenarios). From the networking and protocol edge, this manifests as follows:

- **Telnet → Web takeover**
  - A Telnet client connects via the TCP Proxy, issues `LOGIN`, takes the conditional `JOIN` step if it is first public-production entry, completes realm-scoped `CHARS`/character creation as needed, and enters gameplay with `PLAY` for a character.
  - Later, a Web client completes HTTP `Join & Play` if public-production membership is missing or `INACTIVE`, connects via WebSocket to `/ws/game/**`, and successfully issues `LOGIN` then `PLAY` for the same character.
  - Game Session treats the Web client as the new active binding, terminates or demotes the Telnet connection according to takeover rules, and closes the Telnet path with a `logout` Telnet reason (mapped to WebSocket `1000/logout` in the taxonomy above). No ordering guarantees are provided between the last Telnet commands and the first WebSocket commands; only per-connection FIFO holds on each individual connection.
  - The Telnet client must treat this disconnect as a normal session takeover outcome, apply its reconnection/backoff rules if it wishes to reconnect, and not assume that any new Telnet connection can “resume” alongside the active WebSocket binding.
- **Web → Telnet takeover**
  - A Web client completes HTTP `Join & Play` if public-production membership is missing or `INACTIVE`, connects via `/ws/game/**`, issues `LOGIN`, and enters gameplay with `PLAY` for a character.
  - A Telnet client later connects through the TCP Proxy and logs in as the same character.
  - Game Session treats the Telnet client as the new active binding; the WebSocket session is closed with `1000/logout` and the Telnet connection becomes authoritative. Again, cross-connection ordering is not defined: only per-connection FIFO is guaranteed, and clients must not attempt to sequence commands across the old and new transports.
- **Concurrent Telnet + Web connections**
  - When clients deliberately keep both a Telnet connection and a WebSocket connection open for the same character (for example a scripting tool plus a web UI), only one binding at a time is gameplay-active. The “losing” connection is closed or demoted by Game Session, surfaced at the edge via the standard disconnect categories, and must not be relied on for ongoing gameplay commands.

The networking layer does not implement its own multi-client arbitration or attempt to keep connections in sync; it simply reflects Game Session’s takeover decisions via the Telnet and WebSocket disconnect taxonomy. Tools and clients must design their UX around the single-active-binding model rather than expecting concurrent, ordered control over a character from multiple transports.

---

## Backpressure & Slow Clients

Backpressure and slow-client handling are split across layers so that the platform remains robust without silently corrupting gameplay streams. Responsibilities and observability are intentionally divided between the TCP Proxy, Spring Cloud Gateway, and Game Session:

- **Telnet/TCP clients (TCP Proxy Service)**
  - The TCP Proxy Service enforces a strict per-socket output buffer limit. When a Telnet client cannot keep up with outbound traffic and the proxy’s output buffer fills, the proxy closes the Telnet connection with a clear message rather than silently dropping gameplay lines in the middle of a session. Relevant events are surfaced via metrics such as `tcpproxy.telnet.discarded` and per-connection counters; see the TCP Proxy Service design’s **Connection Limits and Abuse Protection** section.
  - Input-side backpressure is governed by per-connection and per-IP line-rate budgets, as well as MCP-specific limits. Excess input beyond these budgets may be dropped (for example MCP control lines over budget) or cause the proxy to close the connection for sustained abuse. The Telnet degraded runbook documents how operators should interpret these metrics and when to adjust limits vs block abusive sources.
- **WebSocket clients (Gateway / WebSocket container)**
  - Spring Cloud Gateway (or its underlying WebSocket container) is responsible for **network-level slow-client detection** on `/ws/game/**`. When a WebSocket client is slow to read and outbound send buffers for that connection fill or repeatedly time out, the gateway closes the WebSocket connection rather than silently discarding frames.
  - Gateway-side slow-client closures should use the standard close codes from [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts) and must map to `1008/policy_violation` (with a bounded subreason such as `edge_backpressure`) rather than `1013/backend_unavailable`. Gateway metrics such as `gateway.websocket.slow_client_closes` and route-level close-reason counters allow operators to distinguish network-level backpressure from backend outages.
  - Spring Cloud Gateway’s Redis-backed rate limiting focuses on **connection establishment and HTTP requests**, not individual WebSocket frames, as described in [Gateway Architecture](./system-architecture-gateway.md#rate-limiting--abuse-protection). Once a WebSocket is established to `/ws/game/**`, ongoing gameplay messages traverse the connection without additional gateway-level frame-by-frame throttling.
- **WebSocket clients (Game Session Service)**
  - Game Session provides **domain-level backpressure**. For gameplay WebSocket sessions, it applies a per-session outbound queue limit and send-timeout budget on its side of the connection. If either is exceeded (for example because a client has stopped reading or a downstream hop between Game Session and the gateway is persistently slow), Game Session closes the session with an explicit close reason instead of allowing the queue to grow without bound or dropping frames while pretending the connection is healthy.
  - For inbound overload (for example, a misbehaving client sending commands far beyond expected rates), Game Session either rejects excess commands with visible error messages or terminates the session after sustained abuse; it does not accept and then silently discard gameplay input. Domain-level backpressure and abuse closures are surfaced via metrics such as `gamesession.connection.closed{reason="backpressure"|"rate_limit"}` and command‑level error counters.
- **Client expectations**
- When WebSocket connections close due to slow-client behavior, abuse, network issues, or backend unavailability, only first-party token-backed WebSocket clients may use the reconnect shortcut, and only while the complete discovery snapshot is present and unexpired and current `ACTIVE` membership plus a valid current character are confirmed. Otherwise they must complete HTTP `Join & Play` before any fresh connect-token issuance, socket reconnect, bare `LOGIN`, or `PLAY` when public-production membership is missing or `INACTIVE`, or rerun character discovery/creation when the character is invalid before those later steps. Credential-bearing text clients authenticate with `LOGIN` first, perform fresh discovery, and take the conditional in-band `JOIN` step under the same checks. Game Session’s Redis-backed state determines whether gameplay resumes or starts fresh, per [Reconnection Strategy](./system-architecture-reconnection.md).

This model favors **clear closures over silent drops** when a client cannot keep up and provides enough metrics at each layer for operators to identify whether the TCP Proxy, Gateway, or Game Session is enforcing backpressure in a given incident.

Telnet TLS termination, PROXY trust, and client-address promotion remain governed by [Public Telnet TLS Modes](#public-telnet-tls-modes); backpressure handling does not create a second transport trust policy.

### Global Load Shedding Strategy

During severe load or partial outages, each layer in the TCP Proxy → Gateway → Game Session path participates in protecting the platform, but responsibilities are ordered so that core gameplay services are preserved and client signals remain clear:

- **Core gameplay first (Game Session and Redis)**
  - Game Session and its Redis dependencies expose health and saturation metrics (for example queue depth, tick latency, and error rates). When these cross defined thresholds, Game Session prioritises preserving existing sessions and regions while rejecting new logins or high-cost commands, surfacing clear error responses rather than allowing unbounded growth in queues or CPU usage.
  - Region-level degradation and command throttling are considered core policy decisions and are described in more detail in the tick and Redis architecture docs; edge components treat these errors as backend-level signals rather than attempting to work around them.
- **Gateway next (connection creation and route-level limits)**
  - Spring Cloud Gateway protects the core by tightening rate limits on new HTTP and WebSocket connections and, when necessary, preferring to fail new handshake attempts with HTTP 429/503 (as described in [Gateway Architecture](./system-architecture-gateway.md#rate-limiting--abuse-protection)) over tearing down large numbers of existing gameplay sessions. When core gameplay backends remain unavailable beyond `firemud.gateway.backendUnavailableGraceMs`, Gateway then closes existing gameplay WebSocket sessions with `1013/backend_unavailable` and rejects further `/ws/game/**` handshakes with HTTP 503, per the grace-window semantics in [Gateway Architecture](./system-architecture-gateway.md#backend-unavailable-grace-window) and the reconnection rules in [Reconnection Strategy](./system-architecture-reconnection.md#backend-unavailable-scenarios).
- **TCP Proxy as outer edge (DMZ safety rails)**
  - The TCP Proxy Service remains the first line of defence against obvious floods and abusive Telnet patterns via `TCP_PROXY_MAX_CONNECTIONS`, `TCP_PROXY_MAX_CONNECTIONS_PER_IP`, idle timeouts, and buffer depth limits, backed by metrics such as `tcpproxy.connections.limit.exceeded` and `tcpproxy.telnet.discarded`.
  - In healthy but busy conditions, these limits are tuned so that normal player behaviour is primarily shaped by Gateway and Game Session policies rather than frequent proxy disconnects. Under clear Telnet-specific abuse (for example, a small set of IPs consuming most connections), operators first adjust proxy-side caps or block misbehaving sources rather than relaxing gateway or Game Session limits.

Operators should interpret spikes in each layer’s metrics in this order when diagnosing load incidents: check Game Session and Redis saturation first, then Gateway rate-limit and backend unavailable signals, and finally TCP Proxy connection limits. This layered strategy ensures that both WebSocket and Telnet entry points shed load in a way that keeps behaviour predictable for players and preserves the integrity of core gameplay services.

### Protocol handling and security

- Accepts and parses line-based input from TCP/Telnet clients; Telnet option negotiation is minimal and optional so compatible plain TCP clients with ANSI color codes work without additional configuration.
- Sanitizes incoming data and allows only a safe subset of **Telnet protocol commands** as outlined in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls).
- Runs alongside Spring Cloud Gateway in the network **DMZ** so no client ever reaches internal services directly. See [Security Architecture](./system-architecture-security.md#network-security--boundary-design).
- In `DIRECT_TLS` mode, Telnet-over-TLS uses `TCP_PROXY_TLS_ENABLED=true` with certificates provided via `TCP_PROXY_TLS_CERT` and `TCP_PROXY_TLS_KEY`; in `EDGE_PROXY` mode, `TCP_PROXY_TLS_ENABLED=false` because the edge terminates client TLS. Plaintext Telnet is limited to local, automated-test, and explicitly private-network compatibility; player-facing deployments require one of these TLS modes or the web client as defined in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls).
- Telnet-over-TLS certificates (client ↔ proxy) are independent from the Proxy → Gateway WebSocket mutual TLS certificates (proxy ↔ Spring Cloud Gateway). Every player-facing deployment, including hobby/self-hosted, must use dedicated separately managed certificate and key material for these trust surfaces; reusing certificate files is permitted only for local development, automated tests, or throwaway environments and never qualifies as player-facing evidence.

### Bridging to the backend

- Normalizes the connection by proxying Telnet traffic through a WebSocket tunnel.
- Creates a WebSocket connection to Spring Cloud Gateway on behalf of the TCP client. In production this hop uses `wss://` with mutual TLS as described in [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway); the detailed mTLS contract (required listener, SAN/hostname expectations, and certificate paths) lives in the TCP Proxy Service design’s **WebSocket mTLS to Spring Cloud Gateway** section, which should be treated as canonical for certificate wiring details.
- Forwards client identity to the backend using a gateway canonicalization model. The TCP Proxy Service supplies `X-Proxy-Client-IP` only when the PROXY address came through an authenticated, cryptographically protected edge channel; otherwise it uses the direct peer address for security controls and keeps any PROXY address advisory only. Spring Cloud Gateway sets the canonical `X-Client-IP` header after authenticating the TCP Proxy identity. Spring Cloud Gateway strips any `X-Client-IP`, `X-Game-Instance-Id`, `X-Tenant-Id`, and `X-Proxy-*` headers arriving directly from public clients, and only promotes proxy-supplied inputs when the connection is known to have traversed the TCP Proxy → Gateway path; this trust is enforced by the mTLS identity on the TCP Proxy → Gateway hop. Downstream services treat `X-Client-IP` as authoritative only because the gateway produced it.
- Proxies I/O between the TCP client and Spring Cloud Gateway.

### Buffering, reconnection, and observability

- Buffers active input while the client remains connected and discards it if the TCP connection drops; the proxy never replays Telnet commands after a disconnect.
- Telnet clients keep a sticky connection to the TCP Proxy Service; reconnection and session recovery are handled as described in [Reconnection Strategy](./system-architecture-reconnection.md).
- Disconnect handling is **layered**: the proxy cleans up Telnet sessions; Spring Cloud Gateway retains the client or proxy-facing gameplay WebSocket while performing ADR 0013's bounded rebind of a rebindable Game Session upstream, then closes with `1013/backend_unavailable` if recovery exhausts its window; and Game Session restores continuation authority from shared state. A retained-edge upstream rebind is not an actual edge disconnect and does not require `LOGIN`, `JOIN`, or `PLAY`. After an actual edge disconnect, only first-party token-backed WebSocket clients may use a shortcut while the complete discovery snapshot is present and unexpired and current `ACTIVE` membership plus a valid current character are confirmed; otherwise they complete HTTP `Join & Play` when public-production membership is missing or `INACTIVE`, rerun character discovery/creation when needed, use a fresh `/ws/game/**` connect token, re-`LOGIN`, and re-`PLAY`; credential-bearing text clients perform fresh discovery and use the conditional in-band `JOIN`. The Telnet path does not support hidden reattachment after its distinct Proxy → Gateway gameplay WebSocket is lost: the proxy closes that Telnet connection rather than silently opening a fresh edge bridge behind the same client TCP socket.
- The proxy defines a `NotifyDisconnect` gRPC event so the Game Session Service can react quickly when Telnet clients drop. This stream is best-effort and **at-least-once**, and Game Session treats it as an idempotent, advisory hint rather than a source of truth for session liveness. Consumers key handling off `{proxyConnectionId, disconnectSequence}` so late or duplicate events are safe to ignore. The behaviour-level contract for this stream is summarised in the **NotifyDisconnect Behavioral Contract** section of [Reconnection Strategy](./system-architecture-reconnection.md#notifydisconnect-behavioral-contract-summary), while the TCP Proxy Service design’s **Service Interactions** section remains canonical for message fields, retry windows, and envelope context.
- Metrics are exported at `/actuator/prometheus` and tracing data is sent to the collector configured by `OTEL_ENDPOINT`. See [Logging & Monitoring](./system-architecture-logging-monitoring.md).
- Environment-specific tuning guidance for the TCP Proxy Service (connection caps, envelope budgets, and production hardening) is documented in the TCP Proxy Service design under **Tuning TCP Proxy for Different Environments**.

### Outbound Recovery Boundary

Resume affects gameplay identity and session binding, not transport continuity:

- Neither the TCP Proxy nor Gateway replays prior outbound text or MCP traffic onto a newly reconnected client transport.
- After a successful reconnect and `PLAY`, Game Session may emit a bounded per-player transcript window followed by fresh state reconstruction output derived from current authoritative state (for example room description, prompt, status snapshot, or newly generated MCP state) for the new transport.
- Allowed reconstruction output must be re-derived from current state at resume time; it must not be a byte-for-byte replay of previously queued outbound payloads from the old transport.
- If previously delivered content and newly derived reconstruction happen to look similar to a human reader, that is acceptable only because the content was regenerated from current state, not because the transport backlog was replayed.
- Prompt/status output remains a special output class rather than ordinary transcript text. Prompt lines should be coalesced after bursts of gameplay output and regenerated fresh for the new transport rather than copied into the reconnect transcript buffer. Current operator-default prompt behavior is surfaced in Game Session through `firemud.presentation.prompt.enabled`, `firemud.presentation.prompt.emit-after-reconnect-restore`, and `firemud.presentation.prompt.coalesce-window-ms`.
- MCP cords and negotiation remain per TCP connection as defined in [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md#reconnection--session-recovery); resumed gameplay state must not assume that prior MCP channels still exist.

### WebSocket Bridge Configuration

The TCP Proxy Service acts as the bridge and speaks directly to Spring Cloud Gateway through the WebSocket route that also serves modern clients. The TCP Proxy Service uses the
`GATEWAY_WS_URL` environment variable so the proxy always connects to the `/ws/game/**` predicate shown in the
[Gateway Architecture](./system-architecture-gateway.md) document (`Path=/api/session/**,/ws/game/**`). This keeps the Telnet flow and the web client flow aligned:
they both traverse the same filters, metrics, and downstream `game-session-service` backend.

In production, set `GATEWAY_WS_URL` to the Gateway’s internal-only WebSocket mTLS listener (for example `wss://spring-cloud-gateway-mtls:8443/ws/game`) so the proxy–gateway hop uses mutual TLS and the gateway can authenticate the TCP Proxy identity before promoting any `X-Proxy-*` inputs.

`GATEWAY_WS_URL` is the **authoritative endpoint** for the TCP Proxy → Gateway WebSocket bridge; it is configured independently of the `FIREMUD_SERVICES_*` service-discovery overrides that other services and the gateway use for gRPC and HTTP routing. Changing `FIREMUD_SERVICES_SPRING_CLOUD_GATEWAY_SERVICE` or related overrides does **not** automatically update the Telnet bridge; operators must keep `GATEWAY_WS_URL` aligned with the Gateway’s internal WebSocket mTLS listener via their deployment configuration.

Set `GATEWAY_WS_URL` explicitly in every shared and player-facing environment; regardless of the value, the URL must point to a gateway route
whose path contains `/ws/game/**` (or the configured alias) so Telnet and WebSocket clients hit the identical entry point. When using `wss://`, the host portion of
`GATEWAY_WS_URL` must match a name present in the Gateway certificate’s SANs; pointing it at a bare IP or an unrelated hostname causes TLS validation to fail on the TCP
Proxy side and increments `tcpproxy.gateway.handshake.failures{reason="cert_validation"}`. For mTLS certificate loading and watcher details, see the TCP Proxy Service design’s WebSocket mTLS section.

Player-facing and local-development environments must bridge to the gameplay entry point so the gateway’s standard filters, metrics, and downstream routing apply consistently for Telnet and native WebSocket clients.

### TCP Flow Benefits

- Maintains full compatibility with legacy tools and the wider MUD ecosystem.
- Allows reuse of the same backend infrastructure and logic.
- Makes legacy clients first-class citizens in the platform.

---

## Unified Backend Session Logic

The [Game Session Service](./microservices/game-session-service/README.md) is the central component responsible for:

- Maintaining game session state per client connection.
- Interpreting and completing **system commands** (for example `LOGIN`, `LOGON`, and `PING`) and routing **gameplay commands** into the tick/command pipeline.
- Queuing gameplay commands, enforcing tick/region admission rules, and invoking the Game Logic Service to parse and resolve gameplay commands deterministically.
- Sending and receiving text streams in a line-based protocol format for both WebSocket and Telnet-bridged clients.
- Persisting session state in Redis to enable reconnect recovery.
- Manages disconnects, reconnections, and session cleanup.

> Whether a client is connected via WebSocket directly or tunneled through the TCP Proxy Service, the backend **treats all sessions the same**.

Where Game Session uses a stable session front-end surface that forwards work to an internal region or lease owner, that forwarding hop must preserve the same connection-level invariants that matter to clients: per-connection FIFO, bounded backpressure, and explicit failure propagation when forwarding cannot continue. Internal lease movement must not silently weaken the client-visible transport guarantees defined above.

When lease/epoch fencing fails after a command has been accepted at the session front-end, Game Session must not leave the outcome ambiguous. The implementation must choose one of two canonical outcomes:

- reject the command visibly before gameplay side effects occur, or
- retry internally behind idempotency/effect guards so at-most-once observable gameplay semantics are preserved.

If neither outcome can be guaranteed because ownership is ambiguous or forwarding continuity is lost, the session must fail visibly using the existing structured command-failure or reconnect path rather than silently dropping or partially applying the command.

---

## Recommended Telnet deployment modes

The exact Telnet configuration varies by environment, but recommended defaults are:

| Environment type | Public Telnet transport | Telnet edge proxy | Plaintext Telnet login policy |
| --- | --- | --- | --- |
| Local dev / CI | Plaintext to `TCP_PROXY_PORT` | Optional; often omitted | Allowed for protocol iteration; do not represent it as an account-factor-protected path. |
| Hobby / self‑hosted (single operator) | Telnet-over-TLS through either edge termination plus restricted internal PROXY forwarding or direct TCP Proxy TLS; plaintext only on an explicitly private network | Required only when edge termination mode is selected | Public plaintext TCP/Telnet does not qualify as a supported player-facing deployment. |
| Player-facing staging / production | Telnet-over-TLS through either edge termination plus restricted internal PROXY forwarding or direct TCP Proxy TLS | Required only when edge termination mode is selected | Select exactly one public TLS mode per endpoint; do not expose public plaintext TCP/Telnet or PROXY-protocol listeners. |

These recommendations complement the detailed Telnet controls in [Security Architecture](./system-architecture-security.md#telnet-command-handling-and-controls) and the authentication flows in [Authentication & Authorization](./system-architecture-authentication.md). When in doubt, treat the Security Architecture and TCP Proxy Service design as canonical sources for Telnet hardening and update the bridge configuration here to match.

---

## Related Documentation

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Environment Variables & Secrets Management](./infrastructure/environment-and-secrets.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Infrastructure Overview](./infrastructure/README.md)
