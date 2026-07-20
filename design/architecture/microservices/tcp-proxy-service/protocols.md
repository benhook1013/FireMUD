# TCP Proxy Service Protocols

## Cross-Path Connectivity Contract

The following are canonical and active across Telnet and WebSocket paths:

- Telnet login-first without typed attach hints is the canonical flow (`LOGIN` then `PLAY`).
- Proxy -> Gateway WebSocket hop is mTLS-authenticated in player-facing environments.
- Proxy bridge-availability circuit breaker uses deterministic open/half-open/closed admission behavior during sustained upstream unreachability.

## Recommended Telnet Client Flows

These flows describe how Telnet traffic is forwarded into the shared login/session pipeline; `LOGIN` / `LOGON` semantics and multi-client takeover behavior remain canonical in the [Authentication & Authorization](../../system-architecture-authentication.md) document.

- **Canonical player flow**
  - Connect to the TCP Proxy Service.
  - Optionally browse public worlds with `WORLDS`.
  - Send `LOGIN <email-or-username>` to start the neutral email challenge, or `LOGIN <email-or-username> <secret>` to authenticate immediately.
  - Enter gameplay with `PLAY <world> [realm] [character]`; use `REALMS <world>` or `CHARS <world> [realm]` only if the target is ambiguous and more selection help is needed.
  - Send gameplay commands (`LOOK`, `SAY`, movement, and so on) as normal.
  - The proxy forwards all lines verbatim to Spring Cloud Gateway; the Game Session Service creates or binds the gameplay session exactly as it does for native WebSocket clients.
- **Future smart-client flow**
  - If advanced attach hints return after a classic-client adapter is selected and proven, they may travel through that extension rather than a typed `SESSION` gameplay line.
  - Those hints remain advisory only and must not replace the normal human-facing `WORLDS` -> `LOGIN` -> `PLAY` flow.

## Advanced Multi-Connection Scenarios

Advanced Telnet tools may open more than one window or pane for the same account. The proxy forwards traffic for every TCP connection independently; visible behavior is governed by the Game Session Service’s one-session-per-character rules.

- **Two Telnet windows**
  - Window A connects, issues `LOGIN`, and enters gameplay with `PLAY`.
  - Window B connects and issues `LOGIN` for the same character, then `PLAY`. Game Session treats this as a takeover: the old session is terminated, the new window becomes authoritative, and Window A is disconnected and stops receiving updates.
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
- All gameplay commands, including `LOGIN` and `PLAY`, are forwarded verbatim over the WebSocket bridge so Spring Cloud Gateway and Game Session see the same protocol lines as native WebSocket clients.

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

Typed `SESSION` gameplay lines are no longer part of the Telnet contract. If advanced smart clients need attach hints in the future, those hints may travel through a selected and proven classic-client extension rather than through visible player input.

Current rules:

- normal Telnet players use `WORLDS` (optional), `LOGIN`, and `PLAY`;
- the proxy bootstraps hidden default gameplay instance and tenant metadata for the connection;
- typed attach hints do not exist on the player-facing wire contract;
- future extension-carried attach hints must remain advisory to Game Session and must never bypass `LOGIN` + `PLAY`;
- PROXY protocol trust follows the same principle: on the internal-only PROXY listener, malformed or truncated PROXY headers are a hard failure and the proxy must not silently fall back to the TCP peer IP. The canonical discard signal is `tcpproxy.telnet.discarded{reason="proxy_protocol"}`.
- PROXY parsing must never be enabled on the public Telnet listener. Accepting PROXY headers directly from the Internet would allow client-IP spoofing.

Metrics give observability into each Telnet connection while keeping Prometheus label cardinality bounded:

- `tcpproxy.connection.events{type="connect"|"disconnect"}`
- `tcpproxy.connection.duration`
- `grpc_app_error_total{code="<code>"}`

Detailed identifiers such as `gameInstanceId` and client IP are captured in structured logs and tracing context, not Prometheus label values.

`tenantId` is intentionally omitted from canonical proxy metrics. Even in small deployments, tenant-specific diagnosis should come from logs, traces, and control-plane reads or from an explicitly separate non-canonical diagnostic export; operators should not mutate the shared Prometheus metric contract by adding raw `tenantId` labels to the standard proxy meters.

## Telnet Command Handling

The proxy sanitizes incoming bytes and allows only a safe subset of Telnet protocol commands. Plain text is the only supported universal gameplay contract. MCP, GMCP, and other classic-client extensions remain experimental, disabled, and unadvertised under [ADR 0145](../../decisions/adr-0145-plain-text-gameplay-and-deferred-classic-client-extensions.md).

TCP Proxy may recognize an opaque reserved marker solely to enforce a per-connection marker-line rate limit, then forwards allowed lines unchanged. This recognition is not negotiation or semantic parsing. If MCP is later selected, Game Session alone owns the greeting, negotiation, authentication-key correlation, packages, cords, and multiline data tags; TCP Proxy must not maintain duplicate state.

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

The proxy does not classify unknown packages, malformed extension messages, cord state, or data-tag state. Those are Game Session semantic concerns only after an adapter has been selected and implemented.

### Compatibility Notes

- Classic MUD clients that rely only on standard text I/O and basic Telnet negotiation are expected to work without special configuration.
- Clients that depend on advanced Telnet options should treat those features as best-effort.
- No client should currently assume MCP 2.1, GMCP, `mcp-negotiate`, `mcp-cord`, or any FireMUD extension package is supported.

## Experimental Extension Marker Safety Boundary

TCP Proxy's extension-related safety boundary is intentionally semantic-free:

- every line participates in the generic `TCP_PROXY_MAX_LINE_BYTES` and `TCP_PROXY_MAX_OVERSIZE_LINES` limits;
- lines matching a configured reserved extension marker may participate in a per-connection opaque marker-line rate budget;
- gameplay never depends on an optional extension line being accepted;
- the proxy does not track negotiation failures, active packages, cords, authentication keys, or multiline tags.

Current MCP-looking recognition and greeting code is not compliant negotiation and must remain disabled and unadvertised. The proxy does not make package or protocol compatibility claims from marker forwarding or generic rate protection.

If research later selects MCP, exact package/version advertisement and semantic resource budgets belong to Game Session and require current-client end-to-end proof. Operational limits are safety controls, not blanket MCP compatibility promises.
