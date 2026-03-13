# Mud Client Protocol (MCP) Support

This document outlines how FireMUD incorporates the Mud Client Protocol (MCP) to enable richer client experiences over Telnet, such as auxiliary status panels, background notifications, and structured updates. The protocol reference can be found at <https://www.moo.mud.org/mcp/mcp2.html>.

## Goals

- Allow MCP-aware Telnet and MUD clients to exchange structured out-of-band messages with the server.
- Support richer client UI elements (status panes, maps, chat windows) without changing the primary text protocol.
- Maintain backward compatibility with traditional Telnet clients that do not understand MCP.

## MCP Basics

The MCP 2.1 specification defines a simple, 7-bit ASCII, line-based protocol for sending out-of-band messages on the same channel as normal Telnet traffic. Lines are delimited with `\r\n` and there is no fixed line-length limit in the spec. FireMUD does impose a transport limit at the TCP Proxy Service: each Telnet/MCP line must fit within `TCP_PROXY_MAX_LINE_BYTES` (default `4096`) as documented in the TCP Proxy Service design. MCP-aware clients should split large payloads across MCP multiline continuation lines (`#$#* ...`) rather than sending a single oversized line. Both connection endpoints are treated symmetrically, and the protocol itself maintains no state; higher-level packages define application behavior. Message names and keywords are case-insensitive, while authentication keys and data tags must preserve their exact case. Lines beginning with the `#$#` marker are interpreted as MCP messages, while other lines remain in-band for legacy clients. Each session uses an authentication key supplied by the client; although the key travels in cleartext, implementations must reject any message with an unexpected key to guard against spoofing.

## Overview

FireMUD negotiates MCP over the same line-based gameplay text stream that carries normal commands and responses. On the Telnet path, the TCP Proxy Service sanitizes and frames the Telnet transport, then forwards MCP control lines (`#$#...`) and payloads verbatim over the WebSocket bridge to the canonical gameplay route.

MCP package semantics are terminated by the backend session layer (Spring Cloud Gateway → Game Session Service): the edge is responsible for transport safety and MCP abuse budgets, while Game Session and downstream domain services decide which MCP packages exist and what they mean. Any client that can speak FireMUD’s line-based gameplay protocol may use MCP (including Telnet clients via the TCP Proxy and native WebSocket clients); clients that do not negotiate MCP remain on the plain-text channel.

## Protocol Handshake

MCP negotiation in FireMUD is role-specific:

- **Target state (canonical):** Game Session is the MCP server endpoint and sends the initial server greeting (`#$#mcp version: ...`); the client responds with its `#$#mcp authentication-key: ...` line.
- **Transitional compatibility mode (temporary):** a proxy-side greeting shim may emit the initial server greeting in environments where backend-owned greeting rollout is incomplete. This mode is compatibility-only and does not transfer package ownership to the proxy.

In both modes, FireMUD and MCP-capable clients agree on the highest overlapping version and use the client-supplied `authentication-key` for all subsequent MCP messages. If no overlap exists, MCP cannot be used and the connection must fall back to plain text or close.

Greeting ownership must be singular per connection. A connection may be in exactly one of these MCP greeting modes:

- `backend_greets` – Game Session emits the initial MCP greeting and the proxy forwards it transparently.
- `proxy_shim_greets` – the proxy shim emits the initial MCP greeting only because backend greeting is disabled for that environment/connection.

These modes are mutually exclusive. Implementations must not allow both the proxy shim and backend to emit server greetings on the same connection, and rollout flags must make the chosen mode explicit so clients never receive duplicate `#$#mcp version: ...` greetings.

If configuration drift would cause both greeting paths to fire, producers must fail closed on the duplicate-greeting path rather than sending two server greetings on one connection. This is a rollout/configuration bug, not a valid protocol variant:

- the duplicate greeting must be suppressed before it reaches the client whenever detection is possible,
- the owning component must emit a bounded misconfiguration signal such as `mcp_greeting_mode_conflict` and, if metrics are exposed for this condition, use a stable low-cardinality name such as `mcp.greeting.mode_conflict`,
- and operators should treat any client-visible duplicate greeting as an incident requiring rollback or feature-flag correction.

Clients should not be expected to recover from duplicate server greetings beyond falling back to plain-text behavior or disconnecting cleanly; the server side owns preventing this condition.

On the Telnet path, `SESSION` remains an attach hint only until the proxy forwards the first non-`SESSION` line upstream. The proxy may therefore delay opening the Proxy → Gateway gameplay WebSocket until it has consumed the optional initial `SESSION` envelope, but it must open the bridge before forwarding the first non-`SESSION` line. Because MCP control lines are forwarded upstream, they also end the period during which `SESSION` can affect the Proxy → Gateway WebSocket handshake. Clients that want to use both `SESSION` and MCP must therefore send `SESSION` first, then start MCP negotiation (and then proceed to `LOGIN`) so the proxy can include any captured session hints in the initial handshake. After the first forwarded MCP or gameplay line, later `SESSION` lines are no longer attach hints and are treated according to the TCP Proxy Service contract.

Examples:

- `SESSION` first, then `LOGIN`: `SESSION <gameInstanceId> <tenantId>` followed by `LOGIN ...` causes the proxy to open the bridge with the captured `SESSION` hints before forwarding the `LOGIN` line.
- `SESSION` first, then MCP: `SESSION <gameInstanceId> <tenantId>` followed by `#$#mcp ...` causes the proxy to include the `SESSION` hints in the initial Proxy → Gateway handshake.
- MCP first, then `SESSION`: `#$#mcp ...` followed later by `SESSION <gameInstanceId> <tenantId>` does **not** update the already-established bridge handshake; the later `SESSION` line is no longer an attach hint.
- Negative example: `#$#mcp version: 2.1 to: 2.1`, then `SESSION <gameInstanceId> <tenantId>`, then `LOGIN ...` means the bridge was already opened by the initial MCP line; the later `SESSION` text is forwarded as ordinary input and must not affect admission headers.

Each endpoint then advertises its capabilities using `mcp-negotiate-can package: <name> min-version: <x> max-version: <y>` messages and finishes with `mcp-negotiate-end`. FireMUD uses version 2.0 of the `mcp-negotiate` package, so the package must be advertised explicitly and `mcp-negotiate-end` terminates negotiation. A package is considered active only after both sides have sent `mcp-negotiate-can` for it and both have sent `mcp-negotiate-end`. Implementations may defer using a package until receipt of the other side’s `mcp-negotiate-end`. Unknown packages are ignored so legacy clients remain unaffected.

No other MCP traffic should be sent until both sides have exchanged their initial `mcp` lines and completed negotiation. Every subsequent message must include the agreed `authentication-key`.

Example handshake:

```text
#$#mcp version: 2.1 to: 2.1
#$#mcp authentication-key: 18972163558 version: 2.1 to: 2.1
#$#mcp-negotiate-can package: mcp-negotiate min-version: 1.0 max-version: 2.0
#$#mcp-negotiate-can package: mcp-cord min-version: 1.0 max-version: 1.0
#$#mcp-negotiate-end
```

## Message Format

MCP treats any line starting with `#$#` as an out-of-band message. Other lines remain in-band Telnet traffic. Lines beginning with `#$#` or `#$"` must be quoted with the prefix `#$"` to preserve their literal content. Each message contains a case-insensitive name, the session’s authentication key, and case-insensitive keywords. Authentication keys and data tags are case-sensitive. Simple values that include spaces, quotes, backslashes, colons, or asterisks must be enclosed in double quotes, escaping `\"` and `\\` as needed. Multiline values append `*` to the keyword and include an `_data-tag` argument whose value is echoed on subsequent `* datatag keyword:` lines and closed with `#: datatag`. Continuation lines may be interleaved with other traffic but must arrive in order for a given data tag. MCP relies on the underlying session for ordering and reliability. Malformed or unrecognized messages are silently discarded, allowing traditional Telnet clients to coexist with MCP-aware tooling.

Example multiline message:

```text
#$#firemud-status-panel 18972163558 title: "Status" body*: "" _data-tag: status1
#$#* status1 body: HP: 20/30
#$#* status1 body: Mana: 10/15
#$#* status1 body: Room: Hall of Echoes
#$#: status1
```

## Optional Packages

FireMUD supports the `mcp-cord` package (version 1.0) to multiplex additional channels over the same connection. The package defines:

- `mcp-cord-open _id: <token> _type: <name>` to create a new cord identified by a unique `_id` and a descriptive `_type`.
- `mcp-cord _id: <token> _message: <name> ...` to send messages scoped to that cord, with additional arguments defined by the message.
- `mcp-cord-closed _id: <token>` to signal cord termination. Either side may send this, and it is safe to receive more than once.

These primitives let stateful conversations—such as dedicated chat tabs, map views, or status panels—be tied to specific client windows. Additional packages can be negotiated as needed.

### Interaction with abuse heuristics

MCP control lines (`#$#...`) and their payloads are treated as application‑level text on top of the sanitized Telnet transport. Abuse detection at the TCP Proxy layer operates on Telnet control bytes, envelope handling, connection churn, and similar signals; unknown MCP packages or malformed MCP messages are not treated as abuse by default. Implementations may log or surface MCP parsing issues for diagnostics, but they must not close connections purely because a client sends an unrecognised MCP package.

Repeated MCP negotiation failures are handled separately from unknown-package tolerance. If a connection exceeds a bounded negotiation-failure budget (defaults: `TCP_PROXY_MCP_NEGOTIATION_FAILURE_MAX=5` within `TCP_PROXY_MCP_NEGOTIATION_FAILURE_WINDOW_MS=60000`), the proxy may close it as `policy_violation` to protect the edge from tight failure loops.

### MCP resource limits & abuse budgets

To keep MCP traffic from overwhelming the Telnet edge while still being friendly to well-behaved tools, the TCP Proxy Service enforces a set of **MCP-specific budgets** on top of the generic Telnet limits described in the TCP Proxy design:

- Each connection has a bounded number of **active cords** and **concurrent `_data-tag` continuations**; once these limits are exceeded, new MCP control lines are discarded and counted in `tcpproxy.telnet.discarded` with a low-cardinality `reason` label (for example `reason="mcp_budget"`), but the connection may remain open as long as other safety limits are respected.
- MCP message volume is subject to a per-connection **MCP control-line rate** budget. When a client sends MCP control lines significantly faster than expected (for example due to a misbehaving script), excess lines are dropped rather than forwarded, again contributing to `tcpproxy.telnet.discarded` rather than being treated as immediate hard-close abuse.
- MCP line size still participates in the generic `TCP_PROXY_MAX_LINE_BYTES` and `TCP_PROXY_MAX_OVERSIZE_LINES` limits, but **MCP parsing failures do not count towards the `TCP_PROXY_MAX_MALFORMED_ENVELOPES` budget**, which is reserved for Telnet `SESSION` envelope errors as described in the TCP Proxy Service design’s **Telnet Session Envelope & Event Metrics** section.

For envelope handling, MCP control lines are out-of-band with respect to gameplay semantics, but they are still forwarded upstream and therefore end the attach-hint phase for the TCP Proxy’s `SESSION` envelope. The proxy may continue to tolerate later literal `SESSION` text as ordinary forwarded input, but those later lines must not retroactively change the WebSocket handshake headers or rebind the connection. Clients that need `SESSION` hints applied must send `SESSION` before any MCP control line or other forwarded text.

The exact counter and timer names for these budgets live in the TCP Proxy Service design’s **Metrics Summary** and **Connection Limits and Abuse Protection** sections; this document describes only their high-level intent. Thresholds for MCP budgets are configured through explicit TCP Proxy knobs (`TCP_PROXY_MCP_MAX_ACTIVE_CORDS`, `TCP_PROXY_MCP_MAX_ACTIVE_DATA_TAGS`, `TCP_PROXY_MCP_MAX_CONTROL_LINES_PER_SEC`, and the negotiation-failure knobs above). Operators should treat them as guardrails that rarely need adjustment in day-to-day operations. As with other safety controls, operators should treat sustained increases in MCP-related discard reasons as a signal to either adjust client behaviour (for example cord usage or update frequency) or tighten limits for obviously abusive sources after consulting the TCP Proxy design. Gameplay text lines that Game Session treats as commands are not dropped silently while a connection remains open; hitting non-MCP safety limits results in the TCP Proxy or gateway closing the connection with a clear reason as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).

### Implementation Status and Client Expectations

MCP support is being rolled out incrementally:

- The underlying line-based Telnet transport and control-line parsing (`#$#...`) are implemented in the TCP Proxy Service.
- Some environments temporarily enable a proxy-side MCP “server greeting” (`#$#mcp version: ...`) as a compatibility shim during rollout. The target-state design is that the backend session layer is the MCP endpoint and emits the canonical server greeting and package advertisements; the proxy remains a transport bridge and does not define package semantics.
- Transitional proxy-side greeting shims must be explicitly feature-flagged and tracked with rollout metrics. The shim is removed once all player-facing environments run backend-owned MCP greeting and negotiation.
- The `mcp-negotiate` handshake and the `mcp-cord` package are supported for basic cord creation and message routing.
- Higher-level FireMUD-specific MCP packages (for example status panels, map feeds, or structured notifications) are introduced gradually and may evolve as the platform matures.

Client authors should treat MCP integration as **backwards-compatible but evolving**:

- Always fall back gracefully to plain Telnet behaviour if MCP negotiation fails or a package is not advertised by the server.
- Do not assume that every documented package is available in all environments; rely on the negotiated package list rather than hard-coding expectations.
- Avoid making gameplay-critical flows depend solely on MCP; the plain text protocol remains the canonical channel for commands and responses, and MCP is used to augment the experience with structured data.

## Reconnection & Session Recovery

MCP state is **strictly per TCP connection** and does not survive reconnects on its own:

- When a Telnet client disconnects and later reconnects (whether due to client-side network loss, TCP Proxy restart, Gateway outages, or other infrastructure events), the TCP connection and its associated MCP negotiation are gone. Redis-backed gameplay session state (account/player bindings, tick queues, cooldowns) lives in the Game Session Service and determines whether gameplay resumes or starts fresh, but it does **not** restore MCP negotiation or cords.
- After any reconnect, MCP-aware clients must:
  - Re-run the `mcp` version negotiation and agree on a fresh `authentication-key`.
  - Re-advertise and activate packages with `mcp-negotiate-can` / `mcp-negotiate-end`.
  - Re-open any required cords (for example status panels) using `mcp-cord-open`.
- Telnet `SESSION` envelopes are likewise per TCP connection. Advanced clients that rely on `SESSION` for session/tenant hints must resend the envelope on reconnect if they want those hints to apply again, even when the underlying gameplay session resumes from Redis.
- The TCP Proxy Service never attempts to “reattach” a new TCP socket to a previous `SESSION` or MCP negotiation; each TCP connection is treated as a fresh transport, even when it leads to a resumed gameplay session in Game Session.

From the gameplay perspective, reconnection and resume behavior follow the rules in [Reconnection Strategy](./system-architecture-reconnection.md): clients always send a fresh `LOGIN` after any disconnect and then re-establish gameplay scope via the lobby commands (`WORLDS` / `CHARS` / `PLAY`). Game Session uses Redis-backed state to decide whether the selected world/character can resume an existing gameplay session or must start a new one. MCP and `SESSION` provide additional structure and hints on top of that flow but never replace the core text protocol, Redis session state, or the canonical authorization/entitlement checks as the source of truth.

MCP-aware clients should also follow the general reconnection backoff guidance from [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour): use exponential backoff with jitter when reconnecting after failures (including MCP negotiation failures), respect non‑retriable conditions such as clear policy violations, and avoid tight reconnect loops that could overload the TCP Proxy or Gateway during incidents.

## Example Workflow

1. Client connects and negotiates MCP support with FireMUD over the gameplay text stream (via the TCP Proxy Service on the Telnet path).
2. The client opens a dedicated cord for a status panel using `mcp-cord-open`.
3. The server periodically sends `firemud-status-panel` updates on that cord with structured information about health, mana, and location.
4. The client renders the status panel and updates it when new MCP messages arrive, while normal gameplay continues over the text stream.

## Related Documentation

- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [User Journeys – Extensibility & External Tools](./user-journeys-creators.md#8-extensibility--external-tools)
