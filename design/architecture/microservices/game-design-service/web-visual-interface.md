# Web-Based Visual Design Interface

This document describes the planned **visual editing front end** for the Game Design Service. It will allow creators to build worlds, scripts and game assets entirely from a browser.

## Overview

- **React + Material UI** provide the core component library.
- Editors communicate with the Game Design Service through existing REST and gRPC APIs.
- The scripting editor uses the same component-based DSL described in the [World Editing & Customization Tools](world-editing-tools.md) document.

## Implementation Outline

1. A React single-page application runs under the `web-client` module and is served via the Gateway.
2. Drag-and-drop editors render rooms, NPCs and items on a canvas. Changes are persisted via `SaveRevision` gRPC calls.
3. The visual scripting editor represents nodes and connections in JSON which maps directly to the Automation & Scripting Service DSL.
4. Authentication relies on the Account Service JWT flow. Requests include the `tenantId` to isolate data per project.

## Related Design

- [Game Design Service Architecture](README.md)
- [Asset Storage Setup](../../../../services/game-design-service/design/asset-storage.md)
- [World Editing & Customization Tools](world-editing-tools.md)
