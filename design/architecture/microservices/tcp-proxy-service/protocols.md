# TCP Proxy Service Protocols

## Implementation Status

The explicit `JOIN` step is target behavior. Connect-token issuance now requires existing public-production membership and text `PLAY` has the same bounded admission outcomes: `JOIN_REQUIRED` is returned only when fresh authority confirms a publicly visible target whose public-production join policy allows entry and the caller's membership is absent. `PUBLIC_PRODUCTION_ADMISSION_DENIED` remains the policy-denial outcome, while unavailable, stale, malformed, or ambiguous authority uses the applicable unavailable or invalid-authority outcome. Private/playtest admission requires existing caller-bound membership, the current membership authority generation, and the applicable realm grant; a grant never replaces membership. The target flow below must not be read as proof that explicit join is complete across all clients.
The Telnet credential form is `LOGIN <email> [secret]`. `LOGIN <email>` starts the email challenge, while `LOGIN <email> <secret>` supplies the secret immediately.

## Cross-Path Connectivity Contract

The following are canonical contracts across Telnet and WebSocket paths:

- Target Telnet login-first without typed attach hints is `LOGIN` -> conditional `JOIN` -> `PLAY`: a first-time public-production account joins after login, while a returning member skips `JOIN`. In the current runtime, explicit `JOIN` is unavailable; missing public-production membership causes `PLAY` to return `JOIN_REQUIRED` only when fresh authority confirms that the public target is visible and joinable, without creating membership.
- Proxy -> Gateway WebSocket hop is mTLS-authenticated in player-facing environments.
- Proxy bridge-availability circuit breaker uses deterministic open/half-open/closed admission behavior during sustained upstream unreachability.

## Recommended Telnet Client Flows

These flows describe how Telnet traffic is forwarded into the shared login/session pipeline; `LOGIN` / `LOGON` semantics and multi-client takeover behavior remain canonical in the [Authentication & Authorization](../../system-architecture-authentication.md) document. The target first public-production flow is `LOGIN` -> conditional `JOIN` -> `PLAY`; returning members skip `JOIN`. Text `PLAY` and connect-token issuance return `JOIN_REQUIRED` only for a publicly visible, joinable target confirmed by fresh authority when public-production membership is absent; policy denial remains `PUBLIC_PRODUCTION_ADMISSION_DENIED`, and unavailable or invalid authority remains the applicable unavailable/invalid-authority outcome. Explicit `JOIN` remains unimplemented, and the missing connect-token membership-authority-generation reread is a separate gap.

- **Target canonical player flow**
  - Connect to the TCP Proxy Service.
  - Optionally browse public worlds with `WORLDS`.
  - Use `LOGIN <email> [secret]`: `LOGIN <email>` starts the email challenge, while `LOGIN <email> <secret>` authenticates immediately. Complete the applicable secret or verified-email-code response before continuing; `JOIN` is accepted only after authentication succeeds.
  - For a first-time public-production account, send `JOIN <world>` to create the durable player membership. A returning member skips `JOIN`.
  - After membership, use `CHARS <world> [realm]` or the character-creation flow to select or create the required character, then enter gameplay with `PLAY <world> [realm] [character]`; use `REALMS <world>` when the target is ambiguous.
  - Send gameplay commands (`LOOK`, `SAY`, movement, and so on) as normal.
  - The proxy forwards all lines verbatim to Spring Cloud Gateway; the Game Session Service creates or binds the gameplay session exactly as it does for native WebSocket clients.
- **Future smart-client flow**
  - If advanced attach hints return, they should travel through hidden MCP metadata rather than a typed `SESSION` gameplay line.
  - Those hints remain advisory only and must not replace the normal human-facing `WORLDS` -> `LOGIN` -> `JOIN` -> `PLAY` flow; returning members skip `JOIN`.

## Advanced Multi-Connection Scenarios

Advanced Telnet tools may open more than one window or pane for the same account. The proxy forwards traffic for every TCP connection independently; visible behavior is governed by the Game Session Service’s one-session-per-character rules.

- **Two Telnet windows**
  - Window A connects, issues `LOGIN`, takes the conditional `JOIN` step if it is first public-production entry, and enters gameplay with `PLAY`.
  - Window B connects and issues `LOGIN` for the same character, takes `JOIN` only if it is not already a returning member, then issues `PLAY`. Game Session treats this as a takeover: the old session is terminated, the new window becomes authoritative, and Window A is disconnected and stops receiving updates.
  - If Window A reconnects and logs in again, it in turn takes over from Window B. There is no concurrent split control even though the proxy forwards traffic from both TCP connections.

## Data Flow

- TCP connections are accepted on a dedicated port and proxied to Spring Cloud Gateway using a lightweight WebSocket bridge.
- For each Telnet socket, the proxy bootstraps the hidden default gameplay instance and tenant metadata needed to open the bridge and includes that context in the authenticated Proxy -> Gateway handshake headers.
- Incoming bytes are queued and forwarded to the gateway in order.
- If the proxy cannot establish the WebSocket bridge because gameplay upstream is unavailable, it fail-closes the Telnet socket with `backend_unavailable`.
- User-facing failure messaging may use a clear bounded message such as `Gateway link unavailable; please reconnect.` so clients are not left with a silent close.
- If bridge establishment fails because trust or policy checks fail, the proxy fail-closes with `policy_violation`, not `backend_unavailable`.
- Example: the proxy attempts the authenticated bridge handshake with its hidden bootstrap context and Gateway rejects it due to certificate validation or policy deny. Because gameplay admission never succeeded, the Telnet connection closes as `policy_violation`, not `backend_unavailable`.
- If the WebSocket bridge drops after the Telnet connection is established, the proxy closes the Telnet socket immediately according to the established-session bridge state machine; it does not keep the client TCP socket open while attempting a hidden gameplay-bridge reattach.
- During sustained Gateway gameplay unreachability, proxy admission uses a bridge-availability circuit-breaker model so new Telnet sockets are rejected quickly with `backend_unavailable`. Admission resumes only after `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` consecutive successful probe bridge establishments.
- If upstream backpressure causes the Telnet -> Gateway buffered-line ceiling to be exceeded while upstream is still reachable, the proxy closes the Telnet connection with `policy_violation`, emits `edge_backpressure` context in structured logs and metrics, and increments `tcpproxy.telnet.discarded{reason="gateway_buffer_full"}`. If upstream is already unreachable, `backend_unavailable` takes precedence.
- If the upstream bridge closes cleanly with `1000/logout` on the authenticated internal bridge, the proxy preserves the Telnet-side disconnect category as `logout` and carries through the bounded subreason. Planned Gateway drain remains the canonical `1000/logout;subreason=gateway_restart` case and should be logged as `bridge_shutdown_class=planned_drain`.
- All gameplay commands, including `LOGIN`, `JOIN` when first-time membership is required, and `PLAY`, are forwarded verbatim over the WebSocket bridge so Spring Cloud Gateway and Game Session see the same protocol lines as native WebSocket clients.

## Bridge State Machine (Established Telnet Sessions)

For already-established Telnet sessions, the proxy uses an explicit per-connection bridge state machine:

- `healthy` – upstream bridge established and forwarding.
- `close_due_to_clean_logout` – if the proxy receives `1000/logout` on the authenticated internal bridge, close the Telnet session as `logout` and preserve the bounded subreason.
- `close_due_to_unreachable` – if the established upstream gameplay WebSocket cannot be maintained for any other reason, close immediately with `backend_unavailable` and treat the loss as `bridge_shutdown_class=unattributed_failure` unless a bounded clean logout class was delivered.
- `close_due_to_edge_backpressure` – if queued lines exceed `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` while upstream is reachable, close with `policy_violation` and record `edge_backpressure` context.

This state machine is distinct from the proxy-wide open/half-open/closed admission breaker and defines deterministic behavior for active Telnet sockets during upstream loss. Hidden bridge reattachment behind an already-open client TCP socket is not part of the design.

## Telnet Disconnect Line Format

When the proxy can write a final player-visible disconnect line before closing the socket, it uses this exact line format:

```text
DISCONNECT <reason-token> <human-message>\n
```

`<reason-token>` is one non-whitespace token from the unified disconnect taxonomy (`logout`, `idle_timeout`, `policy_violation`, `internal_error`, or `backend_unavailable`). When bounded subreason context is available, it is appended to the reason token as `;subreason=<value>`, for example `logout;subreason=gateway_restart` or `policy_violation;subreason=edge_backpressure`. `<human-message>` is advisory display text for people and must not be parsed for retry policy.

Examples:

```text
DISCONNECT backend_unavailable Gateway link dropped; please reconnect\n
DISCONNECT logout;subreason=takeover Gameplay session ended; please reconnect\n
DISCONNECT policy_violation;subreason=edge_backpressure Gameplay connection closed due to policy violation\n
```

The proxy writes this line best-effort and then closes the TCP connection. If the transport drops before the line is received, clients must treat the event as abnormal transport loss and use the reconnection policy in [Reconnection Strategy](../../system-architecture-reconnection.md).

## Hidden Attach Metadata

Typed `SESSION` gameplay lines are no longer part of the Telnet contract. If advanced smart clients need attach hints in the future, those hints should travel through hidden MCP metadata rather than through visible player input.

Current rules:

- normal Telnet players use `WORLDS` (optional), `LOGIN`, `JOIN` when first-time public-production membership is required, and `PLAY`; returning members skip `JOIN`;
- the proxy bootstraps hidden default gameplay instance and tenant metadata for the connection;
- typed attach hints do not exist on the player-facing wire contract;
- future MCP-carried attach hints must remain advisory and must never bypass `LOGIN` + `JOIN` + `PLAY`; returning members still skip `JOIN`.
- PROXY protocol trust follows the same principle: on the internal-only PROXY listener, the edge-to-TCP Proxy channel must be authenticated and cryptographically protected before the recovered address can drive per-IP connection caps, rate limits, abuse controls, or admission decisions. Without that protection, the recovered address is advisory only. Malformed or truncated PROXY headers are a hard failure and the proxy must not silently fall back to the TCP peer IP. The canonical discard signal is `tcpproxy.telnet.discarded{reason="proxy_protocol"}`.
- PROXY parsing must never be enabled on the public Telnet listener. Accepting PROXY headers directly from the Internet would allow client-IP spoofing.
- Public Telnet must use either edge TLS termination with this authenticated, cryptographically protected internal PROXY listener or direct TCP Proxy TLS without a PROXY header; the two modes are mutually exclusive. Both modes continue through the same Proxy -> Gateway WebSocket mTLS bridge and `LOGIN` -> `JOIN` -> `PLAY` protocol for first-time membership, with returning members skipping `JOIN`.

Metrics give observability into each Telnet connection while keeping Prometheus label cardinality bounded:

- `tcpproxy.connection.events{type="connect"|"disconnect"}`
- `tcpproxy.connection.duration`
- `grpc_app_error_total{code="<code>"}`

Detailed identifiers such as `gameInstanceId` and client IP are captured in structured logs and tracing context, not Prometheus label values.

`tenantId` is intentionally omitted from canonical proxy metrics. Even in small deployments, tenant-specific diagnosis should come from logs, traces, and control-plane reads or from an explicitly separate non-canonical diagnostic export; operators should not mutate the shared Prometheus metric contract by adding raw `tenantId` labels to the standard proxy meters.

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

- line-length floods or repeated lines exceeding `TCP_PROXY_MAX_LINE_BYTES`; and
- excessive connection churn from the same IP that collides with global connection-limit policy.

Diagnostic-only signals include unknown or malformed MCP control lines.

### Compatibility Notes

- Classic MUD clients that rely only on standard text I/O and basic Telnet negotiation are expected to work without special configuration.
- Clients that depend on advanced Telnet options should treat those features as best-effort.
- MCP-aware clients should assume that MCP negotiation and messages are the primary extensibility mechanism, including any future hidden smart-client attach metadata.

## MCP Resource Limits and Abuse Budgets

The TCP Proxy Service enforces MCP-specific budgets in addition to generic Telnet connection and line limits:

- Each connection has a bounded number of active cords and concurrent `_data-tag` continuations.
- MCP control-line volume is subject to a per-connection MCP control-line rate budget.
- MCP line size still participates in the generic `TCP_PROXY_MAX_LINE_BYTES` and `TCP_PROXY_MAX_OVERSIZE_LINES` limits.

Metrics and diagnostics for these budgets integrate with the existing observability surface:

- `tcpproxy.telnet.discarded{reason="mcp_budget"}`
- `tcpproxy.mcp.negotiation_failures`
- `tcpproxy.mcp.control_lines`
- `tcpproxy.mcp.discarded`
- `tcpproxy.mcp.active_cords`

Once MCP negotiation failures exceed `TCP_PROXY_MCP_NEGOTIATION_FAILURE_MAX` within `TCP_PROXY_MCP_NEGOTIATION_FAILURE_WINDOW_MS`, the connection closes with `policy_violation`.

Normal Telnet abuse detection remains focused on Telnet control bytes, connection churn, and generic line-size limits. MCP parsing errors and unknown MCP packages are diagnostic-only and must not, on their own, cause connections to be hard-closed.
