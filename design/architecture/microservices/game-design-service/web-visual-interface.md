# Web-Based Visual Design Interface

This document describes the **visual editing front end** for the Game Design Service. It allows creators to build worlds, scripts and game assets entirely from a browser.

## Overview

- **React + Material UI** provide the core component library.
- Editors communicate with the Game Design Service through its externally allowlisted REST APIs via Spring Cloud Gateway. Internal gRPC remains service-to-service rather than a private browser transport.
- The scripting editor uses the same component-based DSL described in the [World Editing & Customization Tools](world-editing-tools.md) document.

## Implementation Outline

1. The React single-page application is part of the independently released `web-client` artifact and is served by the stateless first-party frontend boundary defined in [ADR 0144](../../decisions/adr-0144-stateless-first-party-frontend-application-boundary.md), not by Gateway or Game Design Service.
2. Drag-and-drop editors render rooms, NPCs and items on a canvas. Changes are persisted through the authenticated, Gateway-routed Game Design API; Game Design remains the domain authority.
3. The visual scripting editor represents nodes and connections in JSON which maps directly to the Automation & Scripting Service DSL.
4. Authentication uses the Account Service through Gateway. The short-lived control-plane Browser JWT is held only in browser memory and sent as `Authorization: Bearer <token>` on protected API calls; it is never written to browser persistent storage or retained by the static host. Explicit logout invokes Account revocation, and sensitive account, billing, or privileged actions use the canonical browser reauthentication and step-up flow. Requests include the `tenantId`, and Game Design authoritatively enforces tenant isolation.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Asset Storage Setup](asset-storage.md)
- [World Editing & Customization Tools](world-editing-tools.md)
