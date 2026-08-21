# Mud Client Protocol (MCP) Support

This document outlines how FireMUD incorporates the Mud Client Protocol (MCP) to enable richer client experiences over Telnet, such as auxiliary status panels, background notifications, and structured updates. The protocol reference can be found at <https://mudstandards.org/mud/mcp2/>.

## Goals

- Allow MCP-aware Telnet and MUD clients to exchange structured out-of-band messages with the server.
- Support richer client UI elements (status panes, maps, chat windows) without changing the primary text protocol.
- Allow traditional Telnet clients that do not understand MCP to continue using the plain text protocol.

## MCP Basics

The MCP 2.1 specification defines a simple, 7-bit ASCII, line-based protocol for sending out-of-band messages on the same channel as normal Telnet traffic. Lines are delimited with `\r\n` and there is no fixed line-length limit in the spec. FireMUD does impose a transport limit at the TCP Proxy Service: each Telnet/MCP line must fit within `TCP_PROXY_MAX_LINE_BYTES` (default `4096`) as documented in the TCP Proxy Service design. MCP-aware clients should split large payloads across MCP multiline continuation lines (`#$#* ...`) rather than sending a single oversized line. Both connection endpoints are treated symmetrically, and the protocol itself maintains no state; higher-level packages define application behavior. Message names and keywords are case-insensitive, while authentication keys and data tags must preserve their exact case. Lines beginning with the `#$#` marker are interpreted as MCP messages, while other lines remain in-band for legacy clients. Each session uses an authentication key supplied by the client; although the key travels in cleartext, implementations must reject any message with an unexpected key to guard against spoofing.

## Overview

FireMUD negotiates MCP over the same line-based gameplay text stream that carries normal commands and responses. On the Telnet path, the TCP Proxy Service sanitizes and frames the Telnet transport, then forwards MCP control lines (`#$#...`) and payloads verbatim over the WebSocket bridge to the canonical gameplay route.

MCP package semantics are terminated by the backend session layer (Spring Cloud Gateway → Game Session Service): the edge is responsible for transport safety and MCP abuse budgets, while Game Session and downstream domain services decide which MCP packages exist and what they mean. Any client that can speak FireMUD’s line-based gameplay protocol may use MCP (including Telnet clients via the TCP Proxy and native WebSocket clients); clients that do not negotiate MCP remain on the plain-text channel.

## Protocol Handshake

MCP negotiation in FireMUD is role-specific:

- **Canonical mode:** Game Session is the MCP server endpoint and sends the initial server greeting (`#$#mcp version: ...`); the client responds with its `#$#mcp authentication-key: ...` line.

In both modes, FireMUD and MCP-capable clients agree on the highest overlapping version and use the client-supplied `authentication-key` for all subsequent MCP messages. If no overlap exists, MCP cannot be used and the connection must fall back to plain text or close.

Greeting ownership must be singular per connection. Game Session emits the initial MCP greeting and the proxy forwards it transparently. Clients should treat any duplicate server greeting as a server bug and fall back to plain text or disconnect cleanly; the platform owns preventing duplicate greetings.

On the Telnet path, MCP is the planned channel for any future hidden smart-client metadata. Those hints remain advisory transport metadata only: they must never become visible player commands and must never bypass `LOGIN` + `PLAY`.

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
- MCP line size still participates in the generic `TCP_PROXY_MAX_LINE_BYTES` and `TCP_PROXY_MAX_OVERSIZE_LINES` limits.

The exact counter and timer names for these budgets live in the TCP Proxy Service design’s **Metrics Summary** and **Connection Limits and Abuse Protection** sections; this document describes only their high-level intent. Thresholds for MCP budgets are configured through explicit TCP Proxy knobs (`TCP_PROXY_MCP_MAX_ACTIVE_CORDS`, `TCP_PROXY_MCP_MAX_ACTIVE_DATA_TAGS`, `TCP_PROXY_MCP_MAX_CONTROL_LINES_PER_SEC`, and the negotiation-failure knobs above). Operators should treat them as guardrails that rarely need adjustment in day-to-day operations. As with other safety controls, operators should treat sustained increases in MCP-related discard reasons as a signal to either adjust client behaviour (for example cord usage or update frequency) or tighten limits for obviously abusive sources after consulting the TCP Proxy design. Gameplay text lines that Game Session treats as commands are not dropped silently while a connection remains open; hitting non-MCP safety limits results in the TCP Proxy or gateway closing the connection with a clear reason as described in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).

### Implementation Status and Client Expectations

MCP support is being rolled out incrementally:

- The underlying line-based Telnet transport and control-line parsing (`#$#...`) are implemented in the TCP Proxy Service.
- The `mcp-negotiate` handshake and the `mcp-cord` package are supported for basic cord creation and message routing.
- Higher-level FireMUD-specific MCP packages (for example status panels, map feeds, or structured notifications) are introduced gradually and may evolve as the platform matures.

Client authors should treat MCP integration as **backwards-compatible but evolving**:

- Always fall back gracefully to plain Telnet behaviour if MCP negotiation fails or a package is not advertised by the server.
- Do not assume that every documented package is available in all environments; rely on the negotiated package list rather than hard-coding expectations.
- Avoid making gameplay-critical flows depend solely on MCP; the plain text protocol remains the canonical channel for commands and responses, and MCP is used to augment the experience with structured data.

## Reconnection & Session Recovery

MCP state is **strictly per TCP connection** and does not survive reconnects on its own:

- When a Telnet client disconnects and later reconnects (whether due to client-side network loss, TCP Proxy restart, Gateway outages, or other infrastructure events), the TCP connection and its associated MCP negotiation are gone. Redis-backed session state (account/player bindings and tick queues) lives in Game Session; durable actor gameplay state, including cooldowns, continues independently. Neither restores MCP negotiation or cords.
- After any reconnect, MCP-aware clients must:
  - Re-run the `mcp` version negotiation and agree on a fresh `authentication-key`.
  - Re-advertise and activate packages with `mcp-negotiate-can` / `mcp-negotiate-end`.
  - Re-open any required cords (for example status panels) using `mcp-cord-open`.
- Hidden MCP metadata is likewise per TCP connection. Advanced clients that rely on future MCP-carried attach hints must resend that metadata on reconnect if they want those hints to apply again, even when the underlying gameplay session resumes from Redis.
- The TCP Proxy Service never attempts to “reattach” a new TCP socket to prior MCP negotiation state; each TCP connection is treated as a fresh transport, even when it leads to a resumed gameplay session in Game Session.

From the gameplay perspective, reconnection and resume behavior follow the fresh-admission rules in [Reconnection Strategy](./system-architecture-reconnection.md), with separate transport sequences:

- **Direct-text/Telnet clients:** after a new connection, complete fresh public `WORLDS` discovery, credential-bearing `LOGIN`, and authenticated `REALMS`. Unauthenticated/public `WORLDS` exposes only the browseable public-production target surface; private/playtest targets require authenticated/grant-backed discovery and must not be presented as public targets. For a public-production target, `JOIN` is conditional on fresh public-join policy (`allowPublicJoin=true`). When membership is missing or `INACTIVE`, it atomically creates or restores `ACTIVE` and returns the authoritative membership snapshot; an existing `ACTIVE` membership returns the exact current snapshot idempotently. When public joining is disabled, `JOIN` returns `PUBLIC_PRODUCTION_ADMISSION_DENIED` without mutating membership. Private/playtest targets do not use `JOIN`; they require existing `ACTIVE` membership and the current realm grant. Continue with realm-scoped `CHARS` or allowed character creation when no valid character is already selected, then `PLAY` and a fresh `LOOK`. Current text runtime may stop with `JOIN_REQUIRED` only for an otherwise eligible public-production target with missing membership; the live adapter does not distinguish `INACTIVE` from other non-admitting outcomes, and it must not create membership implicitly.
- **First-party `/ws/game/**` clients:** complete the first-party connect-token bootstrap and owner-defined discovery. First-party `Join & Play` membership repair is limited to a public-production target whose current policy permits public joining and whose membership is missing or `INACTIVE`; an existing `ACTIVE` public-production member uses normal fresh connect-token admission—open a new `/ws/game/**` socket, then issue bare `LOGIN` followed by `PLAY`—even when public joining is disabled. Private/playtest targets do not use `Join & Play`; they require existing `ACTIVE` membership and the current realm grant. Complete any required character prerequisites; clients must not replay credential-bearing text `LOGIN` after bootstrap has been re-established. After successful first-party `LOGIN` + `PLAY`, the client receives the fresh authoritative `LOOK` required by [Reconnection Strategy](./system-architecture-reconnection.md#canonical-durable-resume-context-policy), whether or not a reconnect buffer exists; this target integration and proof remain incomplete.

Game Session uses Redis-backed state to decide whether the selected world/character can resume an existing gameplay session or must start a new one. MCP negotiation, package activation, cords, and hidden attach metadata are orthogonal transport features: they may be repeated around either sequence but never replace or bypass membership, entitlement, realm-grant, character, or gameplay-admission checks, the core text protocol, Redis session state, or canonical authorization.

For MCP, a new TCP connection repeats negotiation and resends any MCP-carried attach metadata. Close classification and Telnet translation remain owned by [Gateway Architecture](./system-architecture-gateway.md#canonical-close-translation-matrix) and [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons); fresh transport and session recovery remain owned by [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour).

MCP-aware clients should also follow the general reconnection backoff guidance from [Reconnection Strategy](./system-architecture-reconnection.md#client-reconnection-behaviour): use exponential backoff with jitter when reconnecting after failures (including MCP negotiation failures), respect non‑retriable conditions such as clear policy violations, and avoid tight reconnect loops that could overload the TCP Proxy or Gateway during incidents.

Prompt/status handling should evolve toward MCP or other structured client data rather than treating prompts as ordinary transcript lines:

- classic Telnet clients may still render prompts as text;
- first-party web clients will often suppress textual prompts in the main scrollback and instead bind the same state to dedicated UI widgets;
- semantic reconnect-context restoration should therefore exclude prompt lines. After retained context and a fresh `LOOK`, emit exactly one reconnect prompt only when both effective `firemud.presentation.prompt.enabled` and `firemud.presentation.prompt.emit-after-reconnect-restore` settings are enabled; emit zero reconnect prompts when either is disabled. A structured MCP status update may carry the equivalent state only after both sides have sent `mcp-negotiate-can` for the specific status package (for example, `firemud-status-panel`) and both have sent `mcp-negotiate-end` on that connection; base MCP negotiation alone is insufficient. If that package is inactive, emit no structured MCP status.

## Example Workflow

1. Client connects and negotiates MCP support with FireMUD over the gameplay text stream (via the TCP Proxy Service on the Telnet path).
2. The client opens a dedicated cord for a status panel using `mcp-cord-open`.
3. The server periodically sends `firemud-status-panel` updates on that cord with structured information about health, mana, and location.
4. The client renders the status panel and updates it when new MCP messages arrive, while normal gameplay continues over the text stream.

## Related Documentation

- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [User Journeys – Extensibility & External Tools](../product/user-journeys/creators.md#8-extensibility--external-tools)
