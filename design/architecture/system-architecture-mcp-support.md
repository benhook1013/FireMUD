# MCP Support for AI-Assisted Game Creation

This document outlines how FireMUD incorporates the Mud Client Protocol (MCP) to enable richer tooling for AI-assisted game creation. The protocol reference can be found at <https://www.moo.mud.org/mcp/mcp2.html>.

## 🎯 Goals

- Allow editors and automated tools to communicate with the server using structured MCP messages.
- Enable scripted workflows where the AI can create rooms, items, and NPCs via standardized commands.
- Maintain backward compatibility with traditional Telnet clients that do not understand MCP.

## 📘 MCP Basics

The MCP 2.1 specification defines a simple, 7-bit ASCII, line-based protocol for sending out-of-band messages on the same channel as normal Telnet traffic. Lines are delimited with `\r\n` and there is no fixed line-length limit. Both connection endpoints are treated symmetrically, and the protocol itself maintains no state; higher-level packages define application behavior. Message names and keywords are case-insensitive, while authentication keys and data tags must preserve their exact case. Lines beginning with the `#$#` marker are interpreted as MCP messages, while other lines remain in-band for legacy clients.

## Overview

The TCP Proxy Service negotiates MCP with connecting clients and falls back to plain Telnet when unsupported.
When MCP is enabled, JSON payloads are exchanged inside MCP packages.
The Game Design Service exposes REST and gRPC commands for creating and updating content.
These commands are wrapped in MCP messages so external tools, including AI assistants, drive world creation.

## 🔌 Protocol Handshake

When a client connects, the server begins with an `mcp` version line advertising a minimum and maximum supported protocol version. The client replies with its own `mcp` line that includes the session’s `authentication-key` along with its version range. Both sides pick the highest overlapping version and tag all subsequent messages with the agreed key.

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

## 📨 Message Format

MCP treats any line starting with `#$#` as an out-of-band message. Other lines remain in-band Telnet traffic. Lines beginning with `#$#` or `#$"` must be quoted with the prefix `#$"` to preserve their literal content. Each message contains a case-insensitive name, the session’s authentication key, and case-insensitive keywords. Authentication keys and data tags are case-sensitive. Values may be simple or multiline; simple values containing spaces or special characters require quoting, while multiline values append `*` to the keyword and include an `_data-tag` argument whose value is echoed on subsequent `* datatag keyword:` lines and closed with `#: datatag`. MCP relies on the underlying session for ordering and reliability. Malformed or unrecognized messages are silently discarded, allowing traditional Telnet clients to coexist with MCP-aware tooling.

Example multiline message:

```text
#$#firemud-create-room 18972163558 name: "Hall" description*: "" _data-tag: desc1
#$#* desc1 description: First line
#$#* desc1 description: Second line
#$#: desc1
```

## 📦 Optional Packages

FireMUD supports the `mcp-cord` package (version 1.0) to multiplex additional channels over the same connection. The package defines:

- `mcp-cord-open _id: <token> _type: <name>` to create a new cord identified by a unique `_id` and a descriptive `_type`.
- `mcp-cord _id: <token> _message: <name> ...` to send messages scoped to that cord, with additional arguments defined by the message.
- `mcp-cord-closed _id: <token>` to signal cord termination. Either side may send this, and it is safe to receive more than once.

These primitives let stateful conversations—such as room editing sessions—be tied to specific client windows. Additional packages can be negotiated as needed.

## Example Workflow

1. Client connects and upgrades to MCP.
2. The editor sends a `firemud-create-room` package with room details.
3. The Game Design Service validates the request and persists the new room.
4. A confirmation package containing the new room ID is returned.

Bulk import and transaction support for batch creation expand this workflow and are tracked separately.

## 📚 Related Documentation

- [Game Design Service](./microservices/game-design-service/README.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Modding Framework](./microservices/game-design-service/modding-framework.md)
- [User Journeys – Extensibility & External Tools](./user-journeys.md#21-extensibility--external-tools)
