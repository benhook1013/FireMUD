# 📊 FireMUD System Context Diagram

```plaintext
                      +-------------+                          +---------------+
                      | Web Client  |                          | Telnet Client |
                      +-------------+                          +---------------+
                            |                                        |
                            | HTTP/WebSocket                         | TCP
                            v                                        v
                +-----------------------+                  +---------------------+
                | Spring Cloud Gateway  | <--------------- |  TCP Proxy Service  |
                |         (DMZ)         |  HTTP/WebSocket  |        (DMZ)        |
                +-----------+-----------+                  +---------------------+
                            |
                            | gRPC/WebSocket
                            v
                +----------------------------------------------+
                |               Internal Services              |
                | - Game Session Service                       |
                | - Account Service                            |
                | - Entity Management Service                  |
                | - Game Logic Service                         |
                | - World Management Service                   |
                | - Automation & Scripting Service             |
                | - Social & Groups Service                    |
                | - Logging & Admin Service                    |
                | - Game Design Service                        |
                +-----------+----------------------------------+
                            |
                            | DB/Cache/Logs
                            v
                +----------------------------------------------+
                |               Datastore Layer               |
                |  PostgreSQL (per service)                   |
                |  Redis (sessions, ticks)                    |
                |  Elasticsearch (logs)                       |
                +----------------------------------------------+
```

## 📚 Related Documentation

- [System Architecture Diagram](./system-architecture-diagram.md)
- [Gateway Architecture](./system-architecture-gateway.md)
