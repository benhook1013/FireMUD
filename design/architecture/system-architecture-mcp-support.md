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

When a client connects, the server begins with an `mcp` version line. The client selects a mutually supported version and responds with an authentication key. Both sides then advertise supported packages using `mcp-negotiate-can` messages and finish with `mcp-negotiate-end`. Unknown packages are ignored so legacy clients remain unaffected.

## 📨 Message Format

MCP treats any line starting with `#$#` as an out-of-band message. Other lines remain in-band Telnet traffic. Lines beginning with `#$#` or `#$"` must be quoted with the prefix `#$"` to preserve their literal content. Each message contains a name, the session’s authentication key, and keyword/value pairs. Values may be simple or multiline; simple values containing spaces require quoting.

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
