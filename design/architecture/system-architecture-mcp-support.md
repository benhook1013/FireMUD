# MCP Support for AI-Assisted Game Creation

This document outlines how FireMUD incorporates the Mud Client Protocol (MCP) to enable richer tooling for AI-assisted game creation. The protocol reference can be found at <https://www.moo.mud.org/mcp/mcp2.html>.

## 🎯 Goals

- Allow editors and automated tools to communicate with the server using structured MCP messages.
- Enable scripted workflows where the AI can create rooms, items, and NPCs via standardized commands.
- Maintain backward compatibility with traditional Telnet clients that do not understand MCP.

## Overview

The TCP Proxy Service negotiates MCP with connecting clients and falls back to plain Telnet when unsupported.
When MCP is enabled, JSON payloads are exchanged inside MCP packages.
The Game Design Service exposes REST and gRPC commands for creating and updating content.
These commands are wrapped in MCP messages so external tools, including AI assistants, drive world creation.

## 🔌 Protocol Handshake

When a client connects, the server begins with an `mcp` version line advertising a minimum and maximum supported protocol version. The client replies with its own `mcp` line that includes the session’s `authentication-key` along with its version range. Both sides pick the highest overlapping version and tag all subsequent messages with the agreed key. After the version exchange, each side advertises optional packages with `mcp-negotiate-can` and may confirm a version for a package using `mcp-negotiate-set` before concluding with `mcp-negotiate-end`. Unknown packages are ignored so legacy clients remain unaffected.

Example handshake:

```text
#$#mcp version: 2.1 to: 2.1
#$#mcp authentication-key: 18972163558 version: 2.1 to: 2.1
#$#mcp-negotiate-can package: mcp-cord min-version: 1.0 max-version: 1.0
#$#mcp-negotiate-end
```

## 📨 Message Format

MCP treats any line starting with `#$#` as an out-of-band message. Other lines remain in-band Telnet traffic. Lines beginning with `#$#` or `#$"` must be quoted with the prefix `#$"` to preserve their literal content. Each message contains a case-insensitive name, the session’s case-sensitive authentication key, and keyword/value pairs. Values may be simple or multiline; simple values containing spaces or special characters require quoting, while multiline values span additional lines prefixed with `* datatag` and terminate with `: datatag`.

## 📦 Optional Packages

FireMUD supports the `mcp-cord` package to multiplex additional channels over the same connection. Cords allow stateful conversations—such as room editing sessions—to be tied to specific client windows. Additional packages can be negotiated as needed.

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
