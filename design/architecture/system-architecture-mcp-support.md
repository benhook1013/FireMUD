# MCP Support for AI-Assisted Game Creation

This document outlines how FireMUD will incorporate the Mud Client Protocol (MCP) to enable richer tooling for AI-assisted game creation. The protocol reference can be found at [modelcontextprotocol](https://github.com/modelcontextprotocol).

## 🎯 Goals

- Allow editors and automated tools to communicate with the server using structured MCP messages. (TODO: Not yet implemented)
- Enable scripted workflows where the AI can create rooms, items, and NPCs via standardized commands. (TODO: Not yet implemented)
- Maintain backward compatibility with traditional telnet clients that do not understand MCP.

## Overview

The TCP Proxy Service will negotiate MCP with connecting clients. (TODO: Not yet implemented)
When MCP is enabled, JSON payloads are exchanged inside MCP packages. (TODO: Not yet implemented)
The Game Design Service will expose commands for creating and updating content. (TODO: Not yet implemented)
These commands will be wrapped in MCP messages so external tools, including AI assistants, can drive world creation. (TODO: Not yet implemented)

## Example Workflow

1. Client connects and upgrades to MCP. (TODO: Not yet implemented)
2. The editor sends a `firemud-create-room` package with room details. (TODO: Not yet implemented)
3. The Game Design Service validates the request and persists the new room. (TODO: Not yet implemented)
4. A confirmation package containing the new room ID is returned. (TODO: Not yet implemented)

This workflow is planned for future development. (TODO: Not yet implemented)

Future enhancements will include bulk import and transaction support for batch creation. (TODO: Not yet implemented)

## 📚 Related Documentation

- [Game Design Service](./microservices/game-design-service/README.md)
- [TCP Proxy Service](./microservices/tcp-proxy-service/README.md)
- [Modding Framework](./microservices/game-design-service/modding-framework.md)
- [User Journeys – Extensibility & External Tools](./user-journeys.md#21-extensibility--external-tools)
