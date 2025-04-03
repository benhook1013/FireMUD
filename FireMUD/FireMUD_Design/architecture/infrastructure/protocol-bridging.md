# 🔌 Protocol Bridging: WebSocket and Telnet (TCP)

This document describes how FireMUD supports **both modern and traditional MUD clients** by bridging two distinct communication protocols: **WebSocket** and **raw TCP (Telnet)**. Both are routed into a unified backend session service for shared logic and scalability.

---

## 🎯 Overview

FireMUD enables real-time interaction through two types of client connections:

| Client Type           | Protocol       | Entry Point                     |
|-----------------------|----------------|----------------------------------|
| Web-based clients     | WebSocket      | Spring Cloud Gateway (`/ws/**`) |
| Traditional MUD clients | TCP (Telnet) | TCP Gateway Service (custom)    |

Despite their differences, both protocols are normalized into the same internal architecture using a **WebSocket-based session layer**.

---

## 🔷 WebSocket Flow (Modern Clients)

- Used by browser-based MUD clients or modern tools
- Connections are initiated using the WebSocket protocol
- Routed through **Spring Cloud Gateway**, which supports WebSocket proxying
- Forwarded to `game-session-service`, which maintains the gameplay session

### ✅ Benefits:
- Leverages Spring Cloud Gateway’s routing, auth, logging, and rate limiting
- Ideal for web UIs, admin tools, or companion clients

---

## 🔶 Telnet / Raw TCP Flow (Legacy Clients)

- Used by traditional MUD clients (e.g., MUDlet, TinTin++, GMud)
- Clients connect using raw TCP (typically Telnet-compatible)
- Handled by a dedicated **TCP Gateway Service** (not Spring-based)
- This service:
  - Accepts and parses Telnet line-based input
  - Normalizes the connection
  - **Creates a WebSocket connection to the backend** on behalf of the TCP client
  - Proxies I/O between the TCP client and the backend game session

### ✅ Benefits:
- Maintains full compatibility with legacy tools and the wider MUD ecosystem
- Allows reuse of the same backend infrastructure and logic
- Makes legacy clients first-class citizens in the platform

---

## 🧱 Shared Backend Logic

The `game-session-service` is the central component responsible for:

- Maintaining game session state per client connection
- Handling command parsing and game world interaction
- Sending and receiving text streams in a line-based protocol format
- Managing disconnects, reconnections, and session cleanup

> Whether a client is connected via WebSocket directly or tunneled through the TCP gateway, the backend **treats all sessions the same**.

---

## 🤝 Integration with Other Systems

Both connection types interface with:

- **Player Management** — for identity, login, and session tracking
- **Game Management** — for assigning or creating new game instances
- **World Management** — for game world data, rooms, NPCs, etc.

---

## 📚 Related Docs

- [Infrastructure Overview](./infrastructure-overview.md)
- [Gateway Architecture](./gateway-architecture.md)
- [Deployment Environments](./deployment-environments.md)
