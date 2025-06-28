# 🔁 FireMUD System Architecture: Reconnection Strategy

This document outlines FireMUD’s **layered reconnection strategy**, enabling seamless recovery from network interruptions, client restarts, or backend service failures.

---

## 🧩 Reconnection Layers

| Layer                    | Role                                                                 |
|--------------------------|----------------------------------------------------------------------|
| **TCP Proxy**            | Manages raw Telnet input; clears buffer on disconnect                |
| **Spring Cloud Gateway** | Stateless WebSocket passthrough to Game Session                      |
| **Game Session**         | Restores gameplay session, character state, and tick participation   |

Each layer provides scoped fault tolerance. **Internal service restarts (e.g., Proxy, Gateway, Game Session)** can often be recovered from seamlessly.  
However, **clients must always re-authenticate with a `LOGIN` command** after disconnecting.

> 🔐 Session continuity is always evaluated by the Game Session Service using Redis — clients never retain or resend tokens.

---

## 🛰️ TCP Proxy Behavior

- Assembles raw Telnet input into commands; forwards complete commands over WebSocket.
- Input buffer is **cleared on disconnect or crash** — partial input is discarded.
- **No gameplay state** is maintained here.  
  A reconnect **requires a fresh connection and re-authentication by the client.**

---

## 🌐 Gateway Behavior

- Fully **stateless WebSocket router**; re-establishes backend connections automatically.
- Maintains **no gameplay or authentication state**.
- **Transparent failover** on restart — has no impact on gameplay if the client remains connected.

---

## 🎮 Game Session Recovery

- Uses Redis to restore:
  - `session:{playerId}` socket binding
  - Tick region participation and timers
  - Queued actions and cooldowns
- If a reconnecting client sends a valid `LOGIN` for an active character, the session may be resumed automatically.
- Redis locks deduplicate overlapping reconnect attempts.

> 🔐 Clients never transmit or store JWTs. Session reconstruction is fully server-driven.

---

## 👥 Multi-Client and Takeover Semantics

- An account can be logged in from multiple clients, each controlling a **different character**.
- Logging into the same character from another client will:
  - **Terminate** the old session
  - **Transfer control** to the new connection
  - **Rebind** Redis session state
- This takeover behaves identically to a reconnect — no gameplay data is lost.

---

## 🔄 Resume vs Reload Behavior

| Condition                                | Outcome                                            |
|------------------------------------------|----------------------------------------------------|
| Brief client disconnect                  | **Resume**: If same account+character logs in, session can be recovered via Redis |
| Gateway or Proxy restart                 | **Resume**: Transparent reconnection               |
| Game Session crash or restart            | **Resume**: Tick and session recovery from Redis   |
| Manual LOGIN with same account + character | **Resume**: Session may be resumed if Redis state is available |
| Redis unavailable/corrupted              | **Full Reload**: Player must log in again          |
| Player device/browser switch             | **Full Reload**: New socket/session required       |
| Character re-login from another client   | **Takeover**: Old session terminated, new one becomes authoritative |

> 🧠 Even brief client disconnects **require explicit re-authentication** — only infrastructure restarts are fully transparent.

---

## 🧠 Design Goals

- **All client reconnects require a new `LOGIN`**
- Transparent recovery from **stateless service restarts**
- All session state externalized to **Redis**
- Gameplay continuity preserved wherever possible — but input buffers are not
- Tick state, timers, and action queues are resilient and recoverable

> 🔗 See also:  
> [Tick System and Runtime Design](./system-architecture-ticks.md)  
> [Redis Architecture](./system-architecture-redis.md)  
> [System Architecture Overview](./system-architecture-overview.md)
