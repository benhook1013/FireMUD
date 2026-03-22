# TCP Proxy Service Protocols

## Cross-Path Connectivity Contract

The following are canonical and active across Telnet and WebSocket paths:

- Telnet login-first without `SESSION` is supported (`LOGIN` then `PLAY`; `SESSION` optional).
- Proxy -> Gateway WebSocket hop is mTLS-authenticated in player-facing environments.
- Proxy bridge-availability circuit breaker uses deterministic open/half-open/closed admission behavior during sustained upstream unreachability.

## Recommended Telnet Client Flows

These flows describe how Telnet traffic is forwarded into the shared login/session pipeline; `LOGIN` / `LOGON` semantics and multi-client takeover behavior remain canonical in the [Authentication & Authorization](../../system-architecture-authentication.md) document.

- **Minimal / legacy client (no `SESSION`)**
  - Connect to the TCP Proxy Service.
  - Send a `LOGIN` command with the appropriate credentials and optional OTP where required.
  - Complete lobby selection with `PLAY <world> [character]` before gameplay commands.
  - Send gameplay commands (`LOOK`, `SAY`, movement, and so on) as normal.
  - The proxy forwards all lines verbatim to Spring Cloud Gateway; the Game Session Service creates or binds the gameplay session exactly as it does for native WebSocket clients.
- **Advanced client (attach/resume with `SESSION` + `LOGIN`)**
  - Obtain a `gameInstanceId` and `tenantId` from a first-party admission or session-management API owned by Game Session and/or the control plane. The specific endpoint shape is not part of the Telnet protocol contract.
  - Connect to the TCP Proxy Service.
  - Immediately send a `SESSION <gameInstanceId> <tenantId>` envelope as the first line on the connection.
  - Send a `LOGIN` command over the same connection.
  - Complete lobby selection with `PLAY <world> [character]`.
  - Continue with gameplay commands as normal.
  - Game Session evaluates the combination of `SESSION`, `LOGIN`, and `PLAY` against Redis-backed session state and the authentication rules described in the [Authentication & Authorization](../../system-architecture-authentication.md) and [Reconnection Strategy](../../system-architecture-reconnection.md) documents to decide whether to resume a prior session or start a fresh one.

## Advanced Multi-Connection Scenarios

Advanced Telnet tools may open more than one window or pane for the same account or `SESSION` envelope. The proxy forwards traffic for every TCP connection independently; visible behavior is governed by the Game Session Service’s one-session-per-character rules.

- **Two Telnet windows without `SESSION`**
  - Window A connects, issues `LOGIN`, and enters gameplay with `PLAY`.
  - Window B connects and issues `LOGIN` for the same character, then `PLAY`. Game Session treats this as a takeover: the old session is terminated, the new window becomes authoritative, and Window A is disconnected and stops receiving updates.
  - If Window A reconnects and logs in again, it in turn takes over from Window B. There is no concurrent split control even though the proxy forwards traffic from both TCP connections.
- **Two Telnet windows with the same `{gameInstanceId, tenantId}` envelope**
  - Both windows connect and send `SESSION <gameInstanceId> <tenantId>` followed by `LOGIN` and `PLAY`.
  - The proxy forwards both flows independently; Game Session binds socket-level control to whichever `LOGIN`/`PLAY` flow most recently succeeded for the character backing that session.
  - Clients should assume that only one window at a time has active control of the character and that reconnecting or logging in from another window moves control rather than creating a second simultaneous session.

## Data Flow

- TCP connections are accepted on a dedicated port and proxied to Spring Cloud Gateway using a lightweight WebSocket bridge.
- For each Telnet socket, the proxy may read and validate an optional initial `SESSION <gameInstanceId> <tenantId>` envelope before opening the bridge so those values can be included in the authenticated Proxy -> Gateway handshake headers.
- Example: the client connects, sends `SESSION <gameInstanceId> <tenantId>`, the proxy captures that context and opens the Proxy -> Gateway bridge with `X-Proxy-Game-Instance-Id`, `X-Proxy-Tenant-Id`, and `X-Proxy-Connection-Id` as applicable, then forwards the first gameplay line such as `LOGIN ...` over that bridge.
- Incoming bytes are queued and forwarded to the gateway in order.
- If the proxy cannot establish the WebSocket bridge because gameplay upstream is unavailable, it fail-closes the Telnet socket with `backend_unavailable`.
- User-facing failure messaging may use a clear bounded message such as `Gateway link unavailable; please reconnect.` so clients are not left with a silent close.
- If bridge establishment fails because trust or policy checks fail, the proxy fail-closes with `policy_violation`, not `backend_unavailable`.
- Example: the client sends `SESSION <gameInstanceId> <tenantId>`, the proxy attempts the authenticated bridge handshake, and Gateway rejects it due to certificate validation or policy deny. Because gameplay admission never succeeded, the Telnet connection closes as `policy_violation`, not `backend_unavailable`.
- If the WebSocket bridge drops after the Telnet connection is established, the proxy closes the Telnet socket immediately according to the established-session bridge state machine; it does not keep the client TCP socket open while attempting a hidden gameplay-bridge reattach.
- During sustained Gateway gameplay unreachability, proxy admission uses a bridge-availability circuit-breaker model so new Telnet sockets are rejected quickly with `backend_unavailable`. Admission resumes only after `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful probe bridge establishments.
- If upstream backpressure causes the Telnet -> Gateway buffered-line ceiling to be exceeded while upstream is still reachable, the proxy closes the Telnet connection with `policy_violation`, emits `edge_backpressure` context in structured logs and metrics, and increments `tcpproxy.telnet.discarded{reason="gateway_buffer_full"}`. If upstream is already unreachable, `backend_unavailable` takes precedence.
- If the upstream bridge closes cleanly with `1000/logout` on the authenticated internal bridge, the proxy preserves the Telnet-side disconnect category as `logout` and carries through the bounded subreason. Planned Gateway drain remains the canonical `1000/logout;subreason=gateway_restart` case and should be logged as `bridge_shutdown_class=planned_drain`.
- All gameplay commands, including `LOGIN` and `PLAY`, are forwarded verbatim over the WebSocket bridge so Spring Cloud Gateway and Game Session see the same protocol lines as native WebSocket clients.

## Bridge State Machine (Established Telnet Sessions)

For already-established Telnet sessions, the proxy uses an explicit per-connection bridge state machine:

- `healthy` – upstream bridge established and forwarding.
- `close_due_to_clean_logout` – if the proxy receives `1000/logout` on the authenticated internal bridge, close the Telnet session as `logout` and preserve the bounded subreason.
- `close_due_to_unreachable` – if the established upstream gameplay WebSocket cannot be maintained for any other reason, close immediately with `backend_unavailable` and treat the loss as `bridge_shutdown_class=unattributed_failure` unless a bounded clean logout class was delivered.
- `close_due_to_edge_backpressure` – if queued lines exceed `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` while upstream is reachable, close with `policy_violation` and record `edge_backpressure` context.

This state machine is distinct from the proxy-wide open/half-open/closed admission breaker and defines deterministic behavior for active Telnet sockets during upstream loss. Hidden bridge reattachment behind an already-open client TCP socket is not part of the design.

## Telnet Session Envelope and Event Metrics

This section is the canonical reference for the TCP Proxy Service’s Telnet `SESSION` envelope semantics and related event and metric behavior. Other documents intentionally summarize the behavior at a higher level and should link back here rather than redefining the protocol.

The `SESSION` envelope is an optional optimization used by first-party and other advanced Telnet clients to attach to an existing session before `LOGIN`. Normal Telnet clients never need to send `SESSION`; they simply issue `LOGIN`.

Canonical form:

```text
SESSION <gameInstanceId> <tenantId>
```

Both `gameInstanceId` and `tenantId` are opaque internal identifiers across the system and must be supplied in the canonical server-issued string form for the active deployment contract. Clients must not invent aliases or alternate local encodings in the `SESSION` envelope.

The token is case-insensitive on the wire: the proxy trims and upper-cases the `SESSION` prefix before parsing, then applies canonical identifier validation to the arguments. For compatibility, the parser still accepts the historical colon-separated form by splitting on the first colon or whitespace; the whitespace-separated form above remains the canonical documented shape for new clients.

### Where `gameInstanceId` and `tenantId` Come From

- Cross-service tests and advanced clients typically obtain `{gameInstanceId, tenantId}` from a first-party admission or session-management API, then send `SESSION <gameInstanceId> <tenantId>` when attaching to that instance.
- Simpler Telnet clients do not send `SESSION`; they connect, issue `LOGIN`, and rely on Game Session to derive session and tenant context from the login flow.

### Envelope and Command Handling Rules

- The proxy attempts to capture at most one `SESSION` envelope while `sessionContext` is unset and before the first non-`SESSION` line is forwarded upstream. The first forwarded non-`SESSION` line permanently ends the attach-hint phase for that connection.
- Telnet option negotiation bytes are handled by the Telnet pipeline and are not treated as lines for the purposes of the envelope capture window. MCP control and negotiation lines (`#$#...`) are application-level control traffic, but they are still forwarded upstream. Because the bridge handshake can only happen once, forwarding an MCP control line also ends the attach-hint phase.
- The proxy establishes its Proxy -> Gateway WebSocket bridge the first time it must forward any non-`SESSION` text upstream. If a valid `SESSION` envelope is captured before the bridge is established, the proxy includes the corresponding `X-Proxy-Game-Instance-Id` / `X-Proxy-Tenant-Id` hints in the WebSocket handshake; otherwise the bridge is established without those optional hints and later `SESSION` lines must not retroactively change the connection context.
- During the envelope capture window, lines beginning with `SESSION` are consumed by the TCP Proxy Service and never forwarded upstream. After the attach-hint phase ends, any lines beginning with `SESSION` are forwarded verbatim as normal gameplay text and have no special meaning at the proxy layer.
- Without any `SESSION` envelope, all lines, including `LOGIN`, are forwarded verbatim to the gateway; the proxy does not drop or delay gameplay commands.
- With a valid `SESSION` envelope, the connection is bound to that `{gameInstanceId, tenantId}` pair for its lifetime and those identifiers are propagated via headers and metrics. The envelope window closes immediately after a valid capture; any subsequent `SESSION` lines are forwarded as normal text and do not rebind the connection.
- Malformed `SESSION` lines are logged and ignored; they do not block the Telnet connection. Clients that choose to use `SESSION` may resend a corrected envelope as long as the envelope window is still open, or they may proceed with `LOGIN` only. Each malformed envelope increments a per-connection counter, and once the number exceeds `TCP_PROXY_MAX_MALFORMED_ENVELOPES`, the proxy closes the connection as abusive and emits the corresponding metrics.
- Oversized-line and repeated malformed-envelope paths may emit short bounded client-visible warnings such as `Line too long; command not processed.` or `Repeated malformed SESSION envelope; connection closing.` before hard-closing the connection.
- A future diagnostics mode may surface explicit warnings or errors for malformed or repeated `SESSION` envelopes, but until that mode ships the one-shot binding and silent-ignore behavior is canonical.

### Security Considerations for `{gameInstanceId, tenantId}`

The proxy treats `gameInstanceId` and `tenantId` from the `SESSION` envelope as client-provided claims, not trusted facts:

- `tenantId` is validated against the authenticated account’s allowed tenants during `LOGIN` and subsequent session binding.
- Session ownership is checked so a client cannot bind to or resume another user’s game instance.
- Any mismatch between the envelope’s `{gameInstanceId, tenantId}` and the account’s known sessions or tenants is treated as a cross-tenant hijack attempt and rejected during admission with a canonical error.
- `SESSION` carries only canonical server-issued identifiers for the deployment. Any higher-level gameplay aliasing or default-instance selection happens after authentication inside Game Session and must not be encoded as a special edge-level `SESSION` value.
- If a `SESSION` hint resolves to an instance that is not admissible for the authenticated account, Game Session rejects the enter-game attempt with a canonical admission error.
- This case belongs to the canonical `PLAY` admission error set in [Authentication & Authorization](../../system-architecture-authentication.md#play-returns-canonical-stable-error-codes-so-clients-can-recover-deterministically). TCP Proxy must not invent a Telnet-only error name for stale or inadmissible `SESSION` hints.
- PROXY protocol trust follows the same principle: on the internal-only PROXY listener, malformed or truncated PROXY headers are a hard failure and the proxy must not silently fall back to the TCP peer IP. The canonical discard signal is `tcpproxy.telnet.discarded{reason="proxy_protocol"}`.
- PROXY parsing must never be enabled on the public Telnet listener. Accepting PROXY headers directly from the Internet would allow client-IP spoofing.

Metrics give observability into each Telnet connection while keeping Prometheus label cardinality bounded:

- `tcpproxy.connection.events{type="connect"|"disconnect"}`
- `tcpproxy.connection.duration`
- `grpc_app_error_total{code="<code>"}`

Detailed identifiers such as `gameInstanceId` and client IP are captured in structured logs and tracing context, not Prometheus label values.

To avoid label blow-up in multi-tenant clusters, `tenantId` is intentionally omitted from all proxy metrics by default. For very small, single-tenant or single-admin deployments, operators may temporarily enable per-tenant metrics in custom dashboards by adding `tenantId` as an opt-in label on a subset of meters and keeping those series in short-retention or dedicated Prometheus storage. Even in that mode, `tenantId` labels should be treated as a diagnostic tool rather than a permanent core monitoring surface.

## Telnet Command Handling

The proxy sanitizes incoming bytes and allows only a safe subset of Telnet protocol commands. Mud Client Protocol (MCP) 2.1 negotiation and messages are carried over the line-based text channel and are not affected by the low-level Telnet command whitelist.

Allowed Telnet commands:

| Command / Option | Byte | Purpose |
| --- | --- | --- |
| `SE` | `240` | Terminates subnegotiation blocks. |
| `NOP` | `241` | Ignored; kept for compatibility. |
| `GA` | `249` | May be sent by some legacy clients; ignored by the server. |
| `WILL` | `251` | Negotiation: client proposes enabling an option. |
| `WONT` | `252` | Negotiation: client refuses to enable an option. |
| `DO` | `253` | Negotiation: client requests that the server enable an option. |
| `DONT` | `254` | Negotiation: client requests that the server disable an option. |

Options outside this subset are silently discarded by the sanitization layer. For Telnet subnegotiation (`IAC SB ... IAC SE`), the proxy consumes the entire subnegotiation block up to the matching `SE` and does not surface any of its bytes as gameplay text when the option is unsupported.
Malformed or truncated subnegotiations may increment diagnostic counters, but they must not leak partial control bytes into the line-oriented gameplay stream.

Hard abuse signals include:

- line-length floods or repeated lines exceeding `TCP_PROXY_MAX_LINE_BYTES`;
- repeated malformed `SESSION` envelopes that drive the per-connection counter past `TCP_PROXY_MAX_MALFORMED_ENVELOPES`; and
- excessive connection churn from the same IP that collides with global connection-limit policy.

Diagnostic-only signals include isolated malformed `SESSION` envelopes and unknown or malformed MCP control lines.

### Compatibility Notes

- Classic MUD clients that rely only on standard text I/O and basic Telnet negotiation are expected to work without special configuration.
- Clients that depend on advanced Telnet options should treat those features as best-effort.
- MCP-aware clients should assume that MCP negotiation and messages are the primary extensibility mechanism.

## MCP Resource Limits and Abuse Budgets

The TCP Proxy Service enforces MCP-specific budgets in addition to generic Telnet connection and line limits:

- Each connection has a bounded number of active cords and concurrent `_data-tag` continuations.
- MCP control-line volume is subject to a per-connection MCP control-line rate budget.
- MCP line size still participates in the generic `TCP_PROXY_MAX_LINE_BYTES` and `TCP_PROXY_MAX_OVERSIZE_LINES` limits, but MCP parsing failures do not count toward the `TCP_PROXY_MAX_MALFORMED_ENVELOPES` budget.

Metrics and diagnostics for these budgets integrate with the existing observability surface:

- `tcpproxy.telnet.discarded{reason="mcp_budget"}`
- `tcpproxy.mcp.negotiation_failures`
- `tcpproxy.mcp.control_lines`
- `tcpproxy.mcp.discarded`
- `tcpproxy.mcp.active_cords`

Once MCP negotiation failures exceed `TCP_PROXY_MCP_NEGOTIATION_FAILURE_MAX` within `TCP_PROXY_MCP_NEGOTIATION_FAILURE_WINDOW_MS`, the connection closes with `policy_violation`.

Normal Telnet abuse detection remains focused on Telnet control bytes, malformed `SESSION` envelopes, connection churn, and generic line-size limits. MCP parsing errors and unknown MCP packages are diagnostic-only and must not, on their own, cause connections to be hard-closed.
