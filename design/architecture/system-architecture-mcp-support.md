# MCP Support for AI-Assisted Game Creation

This document outlines how FireMUD will incorporate the Mud Client Protocol (MCP) to enable richer tooling for AI-assisted game creation.

## 🎯 Goals

- Allow editors and automated tools to communicate with the server using structured MCP messages.
- Enable scripted workflows where the AI can create rooms, items, and NPCs via standardized commands.
- Maintain backward compatibility with traditional telnet clients that do not understand MCP.

## Overview

The TCP Proxy Service will negotiate MCP with connecting clients. When enabled, JSON payloads are exchanged inside MCP packages. The Game Design Service exposes commands for creating and updating content. These commands are wrapped in MCP messages so external tools, including AI assistants, can drive world creation.

## Example Workflow

1. Client connects and upgrades to MCP.
2. The editor sends a `firemud-create-room` package with room details.
3. The Game Design Service validates the request and persists the new room.
4. A confirmation package containing the new room ID is returned.

Future enhancements will include bulk import and transaction support for batch creation.

## 📚 Related Documentation

- [Game Design Service](./microservices/game-design-service/README.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [Modding Framework](./microservices/game-design-service/modding-framework.md)
- [User Journeys – Extensibility & External Tools](./user-journeys.md#21-extensibility--external-tools)
