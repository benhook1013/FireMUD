# 🔁 FireMUD System Architecture: Reconnection Strategy

This document outlines the **multi-layer reconnection strategy** used in FireMUD to preserve gameplay continuity across network interruptions, client restarts, or backend service failures. Each layer has distinct responsibilities and fallback behavior to ensure minimal player disruption.

---

## 🧩 Overview of Layered Strategy

| Layer                  | Reconnection Role                                                          |
|------------------------|----------------------------------------------------------------------------|
| **TCP Proxy Service**  | Manages raw Telnet input and socket reconnection                           |
| **Spring Cloud Gateway** | Reconnects WebSocket to backend Game Session Service, maintains auth context |
| **Game Session Service** | Restores gameplay session, player state, active world and tick participation |

Each layer provides scoped fault tolerance. Combined, they ensure players can recover seamlessly across brief disconnects or server restarts.

---

## 🛰️ TCP Proxy Service Reconnection

### Proxy Behavior

- Handles raw Telnet input, which arrives one character at a time.
- Maintains a **temporary input buffer** per active socket to assemble characters into full commands (delimited by `\n` or `\r\n`).
- Once a **full command is received**, it is **immediately forwarded** to the Spring Cloud Gateway over WebSocket.
- The proxy does **not retain complete commands**, and **buffered partial input is discarded** if the connection drops.

### Proxy Edge Cases

- **Short input interruptions** may be tolerated if the socket remains open.
- **Any disconnection clears the input buffer**, as the user will need to reconnect and reauthenticate, making old input irrelevant.
- **Restart or crash** of the proxy results in loss of all in-progress input — no attempt is made to restore partially typed commands.

> 🔎 The TCP Proxy intentionally avoids buffering across sessions. Once a Telnet client disconnects, their input buffer is cleared, as the gameplay session requires re-login anyway.

---

## 🌐 Spring Cloud Gateway Recovery

### Gateway Behavior

- Stateless WebSocket endpoint that auto-reconnects on client retry
- Re-authenticates JWT on reconnect and routes to the correct backend
- Maintains no gameplay context; simply passes traffic

### Gateway Edge Cases

- **Pod restart**: Clients re-establish WebSocket with no user-visible effect
- **Invalid or expired token**: Auth flow is re-triggered on reconnect

---

## 🎮 Game Session Recovery Logic

### Game Session Behavior

- Reconstructs session context from Redis:
  - `session:{playerId}` stores socket binding, selected character, current world
  - Tick region state, timers, and in-flight actions preserved
- Re-binds the WebSocket to the recovered session
- Resumes participation in tick execution and queued command flow

### Game Session Edge Cases

- **Crash mid-tick**: Recovery uses Redis-staged data for replay/resume
- **Simultaneous reconnects**: Deduplicated and conflict-resolved via Redis lock state
- **Manual client reconnect**: Treated identically to network interruption

---

## 🔄 Resume vs Reload Behavior

| Condition                        | Outcome                       |
|----------------------------------|-------------------------------|
| Brief client disconnect          | **Resume**: Session recovered via Redis |
| Gateway or Proxy restart         | **Resume**: Transparent reconnection |
| Game Session crash or restart    | **Resume**: Tick and session recovery from Redis |
| Redis unavailable/corrupted      | **Full Reload**: Player must log in again |
| Player device/browser switch     | **Full Reload**: New socket/session required |

---

## 💾 How State Is Preserved Across Layers

| State Type                  | Storage Layer | Purpose                                               |
|-----------------------------|----------------|-------------------------------------------------------|
| Socket/session bindings     | Redis          | Rebinds clients to live game context                  |
| In-progress Telnet input    | None (cleared) | Not preserved across reconnects; command must be retyped |
| Tick region participation   | Redis          | Ensures proper action scheduling after resume         |
| Cooldowns and timers        | Redis          | Maintains consistent temporal effects across downtime |

---

## ✅ Design Considerations and Goals

- Ensure minimal disruption from network instability
- Prevent duplicate execution during reconnection
- Decouple infrastructure from gameplay state (stateless layers)
- Prioritize fast, resilient reconnection over preserving input from disconnected sessions

> 🔗 See also:  
> [Tick System and Runtime Design](./system-architecture-ticks.md)  
> [Redis Architecture](./system-architecture-redis.md)  
> [System Architecture Overview](./system-architecture-overview.md)
