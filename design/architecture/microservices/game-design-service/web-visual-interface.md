# Web-Based Visual Design Interface

This document describes the **visual editing front end** for the Game Design Service. It allows creators to build worlds, scripts, and game assets from a browser while preserving the independently released static frontend boundary in [FRONT-01](../../decisions/adr-0144-stateless-first-party-frontend-application-boundary.md).

## Overview

- **React + Material UI** provide the core component library.
- Editors use the existing browser-facing Account/Gateway HTTP APIs and the allowlisted Game Design API contracts. The browser does not call internal gRPC directly; Game Design remains authoritative for design state, publication, assets, and templates.
- The scripting editor uses the same component-based DSL described in the [World Editing & Customization Tools](world-editing-tools.md) document.

## Implementation Outline

1. The React single-page application is built under `web-client` into an immutable, version-identified artifact and served by the first-party unprivileged static host. Gateway remains the `/auth/**`, `/api/**`, and `/ws/game/**` ingress and does not serve frontend files or SPA fallback.
2. Drag-and-drop editors render rooms, NPCs and items on a canvas. Gateway-backed Game Design APIs persist changes through the owner’s `SaveRevision` gRPC contract.
3. The visual scripting editor represents nodes and connections in JSON which maps directly to the Automation & Scripting Service DSL.
4. Authentication uses the Account-owned browser token contracts through Gateway. Short-lived browser bearer tokens remain memory-only; they are not persisted in browser storage, URLs, logs, or frontend runtime configuration. Requests use authenticated caller/tenant context, and the frontend does not become an authorization, domain, or data authority.

The editor consumes the static frontend boundary and its browser-only presentation state; the host's file, cache, security, and health mechanics remain canonical in [Frontend Architecture](../../system-architecture-frontend.md#canonical-first-party-frontend-boundary-front-01). The editor's current implementation and browser proof remain partial; this document records the local presentation consequence, not implementation evidence.

## Related Documentation

- [Game Design Service Architecture](README.md)
- [Asset Storage Setup](asset-storage.md)
- [World Editing & Customization Tools](world-editing-tools.md)
