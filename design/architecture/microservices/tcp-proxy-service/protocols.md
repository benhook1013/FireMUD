# TCP Proxy Service Protocols

## Implementation Status

The admission rules are owned by [Authentication](../../system-architecture-authentication.md#login-and-session-flow). Current proxy-routed admission is fail-closed: connect-token issuance and text `PLAY` proceed only when the fresh current Account response reports `membershipExists=true` and `gameplayAdmissionAllowed=true`. That response exposes `membershipExists`, `gameplayAdmissionAllowed`, `membershipVersion`, and `evaluatedAt`, but no lifecycle-state field: missing membership is represented by `membershipExists=false`; the current response cannot expose or distinguish `INACTIVE` from any other existing non-`ACTIVE` lifecycle, so the only available admission signal for an existing non-admitting case is `gameplayAdmissionAllowed=false`. The target reconnect/admission evidence is the authoritative fresh atomic Account membership snapshot, which carries `membershipLifecycleState`, `membershipAuthorityGeneration`, and independent `membershipVersion`. Eligible missing or `INACTIVE` public-production membership returns `JOIN_REQUIRED`; missing or non-`ACTIVE` membership for a non-public target remains `WORLD_ACCESS_DENIED`. For private/playtest targets, current text `PLAY` also requires the current realm grant, while connect-token issuance does not yet validate that grant. Every target admission uses the applicable fresh tenant runtime entitlement; private/playtest additionally requires existing caller-bound `ACTIVE` membership plus the exact current Account-owned realm grant and does not consume the public-production enrollment predicate `allowPublicJoin`. Reachable invalid or stale pointer evidence is `ADMISSION_POINTER_UNAVAILABLE`; an unreachable or timed-out Account or routing authority is `AUTH_UNAVAILABLE`. Target-only `NON_PUBLIC_ENROLLMENT_REQUIRED` and `REALM_ACCESS_DENIED` are not current behavior. The target flow below must not be read as proof that explicit join is complete across all clients.
The Telnet credential form is `LOGIN <email> [secret]`. `LOGIN <email>` starts the email challenge, while `LOGIN <email> <secret>` supplies the secret immediately.
The current text `PLAY` path checks the fresh Account response's `membershipExists` and `gameplayAdmissionAllowed` fields plus the realm grant where applicable; the current connect-token path uses the same membership fields but the private/playtest grant check remains an implementation and proof gap. These local gaps do not change the Authentication-owned admission outcomes.
The discovery-snapshot shortcut is restricted to the first-party token-backed WebSocket path. Direct text/Telnet clients must follow the Authentication-owned fresh `WORLDS`, credential-bearing `LOGIN`, authenticated `REALMS`, and conditional `JOIN` procedure; `CHARS` / character creation is conditional when no valid selected character exists, followed by `PLAY`. The current proxy still bootstraps hidden default routing and does not enforce `WORLDS` as a prerequisite.

## Cross-Path Connectivity Contract

The following are canonical contracts across Telnet and WebSocket paths:

- Target Telnet discovery-first without typed attach hints follows fresh `WORLDS` discovery -> credential-bearing `LOGIN` -> authenticated `REALMS` -> the Authentication-owned conditional `JOIN` when required -> conditional `CHARS`/character creation when no valid selected character exists -> `PLAY`. In the current runtime, explicit `JOIN` is unavailable, hidden default routing is bootstrapped, and missing public-production membership does not create membership. Direct text does not use a first-party WebSocket discovery snapshot.
- Proxy -> Gateway WebSocket hop is mTLS-authenticated in player-facing environments under [Security](../../system-architecture-security.md#tls-termination--internal-encryption).
- Proxy bridge-availability circuit breaker uses deterministic open/half-open/closed admission behavior during sustained upstream unreachability.

## Recommended Telnet Client Flows

These flows describe how Telnet traffic is forwarded into the shared login/session pipeline. `LOGIN` / `LOGON` / `JOIN` / `PLAY` semantics are canonical in [Authentication](../../system-architecture-authentication.md#login-and-session-flow), while multi-client continuity is canonical in [Session Behavior](../../system-architecture-session-behavior.md#multi-client-behavior-and-session-takeover). Explicit `JOIN` is target-only: current Telnet clients cannot perform `JOIN` and fail closed with non-actionable `JOIN_REQUIRED` when membership is absent or inactive. The missing connect-token membership-authority-generation reread is a separate gap.

- **Target canonical player flow**
  - Connect to the TCP Proxy Service.
  - Perform fresh public-world discovery with `WORLDS`. This direct text flow does not consume a first-party WebSocket discovery snapshot.
  - Use `LOGIN <email> [secret]`: `LOGIN <email>` starts the email challenge, while `LOGIN <email> <secret>` authenticates immediately. Complete the applicable secret or verified-email-code response before continuing, then perform authenticated `REALMS` discovery as needed.
  - For a public-production target, follow [Authentication](../../system-architecture-authentication.md#direct-text-realms-to-join-scope-normative) for the target-only conditional `JOIN`; current text reconnect stops with non-actionable `JOIN_REQUIRED` and performs no membership mutation. Private/playtest targets do not use public `JOIN`; after the common runtime-entitlement gate they require existing `ACTIVE` membership plus the exact current realm grant rather than `allowPublicJoin`.
  - If no valid selected character exists after the conditional membership step, use `CHARS <world> [realm]` or the character-creation flow to select or create it; then enter gameplay with `PLAY <world> [realm] [character]`.
  - Send gameplay commands (`LOOK`, `SAY`, movement, and so on) as normal.
  - The proxy forwards all lines verbatim to Spring Cloud Gateway; the Game Session Service creates or binds the gameplay session exactly as it does for native WebSocket clients.
- **Future smart-client flow**
  - If advanced attach hints return, they should travel through hidden MCP metadata rather than a typed `SESSION` gameplay line.
  - Those hints remain advisory only and must not replace the normal human-facing `WORLDS` -> `LOGIN` -> `REALMS` -> Authentication-owned conditional `JOIN` -> conditional `CHARS`/character creation -> `PLAY` flow; public-production members skip `JOIN` only with current `ACTIVE` membership, while private/playtest targets require existing `ACTIVE` membership plus the exact current realm grant and never use `allowPublicJoin`.

## Advanced Multi-Connection Scenarios

Advanced Telnet tools may open more than one window or pane for the same account. The proxy forwards traffic for every TCP connection independently; visible takeover behavior follows [Session Behavior](../../system-architecture-session-behavior.md#multi-client-behavior-and-session-takeover).

- **Two Telnet windows**
  - Window A connects, performs fresh `WORLDS` discovery, issues `LOGIN`, performs authenticated `REALMS` discovery, follows the target-only conditional `JOIN` step if it is first public-production entry, conditionally completes `CHARS`/character creation when no valid selected character exists, and enters gameplay with `PLAY`.
  - Window B connects and repeats `WORLDS`, `LOGIN`, and authenticated `REALMS` discovery for the same character, follows `JOIN` only if required by the Authentication flow, conditionally completes `CHARS`/character creation when no valid selected character exists, then issues `PLAY`. Game Session applies the canonical takeover and the old window is disconnected.
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
- All gameplay commands, including `LOGIN`, `JOIN` when required, and `PLAY`, are forwarded verbatim over the WebSocket bridge so Spring Cloud Gateway and Game Session see the same protocol lines as native WebSocket clients. Admission semantics remain owned by [Authentication](../../system-architecture-authentication.md#login-and-session-flow).

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

- normal Telnet players perform fresh `WORLDS` discovery, then use `LOGIN`, fresh authenticated `REALMS` discovery as needed, and the Authentication-owned target-only `JOIN` procedure when public-production membership is missing or `INACTIVE`; `CHARS`/character creation is conditional before `PLAY` when no valid selected character exists. Public-production members skip `JOIN` only after current `ACTIVE` membership, while private/playtest targets require existing `ACTIVE` membership and the exact current grant and never use `allowPublicJoin`;
- the proxy bootstraps hidden default gameplay instance and tenant metadata for the connection;
- current proxy behavior forwards `WORLDS` but also bootstraps hidden default routing and does not enforce the discovery command, so the fresh-discovery requirement remains a target gap;
- typed attach hints do not exist on the player-facing wire contract;
- future MCP-carried attach hints must remain advisory and must never bypass `WORLDS` + `LOGIN` + `REALMS` + the Authentication-owned `JOIN` procedure + conditional `CHARS`/character creation + `PLAY`; returning members still skip `JOIN`.
- PROXY protocol trust follows [Security](../../system-architecture-security.md#telnet-command-handling-and-controls): on the internal-only PROXY listener, the edge-to-TCP Proxy channel must be authenticated and cryptographically protected before the recovered address can drive per-IP connection caps, rate limits, abuse controls, or admission decisions. Malformed or truncated PROXY headers remain a hard failure, and the canonical discard signal is `tcpproxy.telnet.discarded{reason="proxy_protocol"}`.
- PROXY parsing must never be enabled on the public Telnet listener. Accepting PROXY headers directly from the Internet would allow client-IP spoofing.
- Public Telnet must use either edge TLS termination with the authenticated internal PROXY listener or direct TCP Proxy TLS without a PROXY header; the two modes are mutually exclusive. Both modes continue through the same Proxy -> Gateway WebSocket bridge and Authentication-owned `WORLDS` -> `LOGIN` -> `REALMS` -> conditional `JOIN` -> conditional `CHARS`/character creation -> `PLAY` protocol, with returning members skipping `JOIN`.

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
