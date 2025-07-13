# 📈 FireMUD System Architecture: Diagram

```plaintext
     +------------+      TCP (Telnet)         +-------------------+
     | MUD Client | <-----------------------> | TCP Proxy Service |
     +------------+                           +---------+---------+
                                                        |
                                                        | WebSocket (wss)
                                                        |
                                                        v
     +------------+      WebSocket/HTTP     +----------------------+
     | Web Client | <----------------------> | Spring Cloud Gateway |
     +------------+                          +----------+-----------+
                                                        |
                                                        | WebSocket (wss)
                                                        |
                                                        v
                                         +----------------------------+
                                         | Game Session Service       |
                                         +--------------+-------------+
                                                        |
            +--------------------------+----------------+---------------+----------------------------+
            |                          |                |               |                            |
            v                          v                |               v                            v
  +-------------------+      +-------------------+      |      +-------------------+      +--------------------+
  | Account Service   |      | World Management  |      |      | Entity Management |      | Game Logic Service |
  | (Auth)            |      | (Maps/Rooms)      |      |      | (Players, NPCs,   |      | (Rules, Commands)  |
  |                   |      |                   |      |      | Items)            |      |                    |
  +-------------------+      +-------------------+      |      +-------------------+      +--------------------+
                                                        |                                               
            +--------------------------+----------------+---------------+----------------------------+
            |                          |                                |                            |
            v                          v                                v                            v           
  +-------------------+        +-------------------+           +-------------------+      +--------------------+
  | Game Design       |        | Automation &      |           | Social & Groups   |      | Logging & Admin    |
  | Service           |        | Scripting Service |           | Service           |      | Service            |
  | (Templates,       |        |                   |           |                   |      |                    |
  | Backups)          |        +-------------------+           +-------------------+      +--------------------+
  +-------------------+ 
```

## 📚 Related Documentation

- [System Context Diagram](./system-context-diagram.md)
- [Gateway Architecture](./system-architecture-gateway.md)
