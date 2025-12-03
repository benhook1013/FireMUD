# Mud Client Protocol (MCP) Support

This document outlines how FireMUD incorporates the Mud Client Protocol (MCP) to enable richer client experiences over Telnet, such as auxiliary status panels, background notifications, and structured updates. The protocol reference can be found at <https://www.moo.mud.org/mcp/mcp2.html>.

## Goals

- Allow MCP-aware Telnet and MUD clients to exchange structured out-of-band messages with the server.
- Support richer client UI elements (status panes, maps, chat windows) without changing the primary text protocol.
- Maintain backward compatibility with traditional Telnet clients that do not understand MCP.

## MCP Basics

The MCP 2.1 specification defines a simple, 7-bit ASCII, line-based protocol for sending out-of-band messages on the same channel as normal Telnet traffic. Lines are delimited with `\r\n` and there is no fixed line-length limit. Both connection endpoints are treated symmetrically, and the protocol itself maintains no state; higher-level packages define application behavior. Message names and keywords are case-insensitive, while authentication keys and data tags must preserve their exact case. Lines beginning with the `#$#` marker are interpreted as MCP messages, while other lines remain in-band for legacy clients. Each session uses an authentication key supplied by the client; although the key travels in cleartext, implementations must reject any message with an unexpected key to guard against spoofing.

## Overview

The TCP Proxy Service negotiates MCP with connecting clients and falls back to plain Telnet when unsupported.
When MCP is enabled, structured messages (optionally containing JSON payloads) are exchanged inside MCP packages over the same Telnet connection.
On the server side, the TCP Proxy interprets these messages and maps them to game session operations and events, reusing the same domain logic that drives the text protocol.
From the client’s perspective, all interaction still happens over a single MCP-aware Telnet session, but clients can render additional UI elements based on the structured data they receive.

## Protocol Handshake

When a client connects, the server begins with an `mcp` version line advertising a minimum and maximum supported protocol version. The client replies with its own `mcp` line that includes the session’s `authentication-key` along with its version range. Both sides pick the highest overlapping version and tag all subsequent messages with the agreed key; if no overlap exists, MCP cannot be used and the connection must fall back to plain Telnet or close. The authentication key may be any unquoted string but should be hard to guess, as the server will discard any message whose key does not match the one negotiated at startup.

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

## Example Workflow

1. Client connects and negotiates MCP support with the TCP Proxy Service.
2. The client opens a dedicated cord for a status panel using `mcp-cord-open`.
3. The server periodically sends `firemud-status-panel` updates on that cord with structured information about health, mana, and location.
4. The client renders the status panel and updates it when new MCP messages arrive, while normal gameplay continues over the text stream.

## Related Documentation

- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [User Journeys – Extensibility & External Tools](./user-journeys.md#21-extensibility--external-tools)
