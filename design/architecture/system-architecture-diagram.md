# 📈 FireMUD System Architecture: Diagram

```mermaid
flowchart TD
    MUD[MUD Client] -- TCP --> TCPProxy[TCP Proxy Service]
    Web[Web Client] -- wss/HTTP --> Gateway[Spring Cloud Gateway]
    TCPProxy -- wss --> Gateway
    Gateway -- wss --> Session[Game Session Service]

    Session -- gRPC --> Account[Account Service]
    Session -- gRPC --> World[World Management Service]
    Session -- gRPC --> Entity[Entity Management Service]
    Session -- gRPC --> Logic[Game Logic Service]
    Session -- gRPC --> Design[Game Design Service]
    Session -- gRPC --> Script[Automation & Scripting Service]
    Session -- gRPC --> Social[Social & Groups Service]
    Session -- gRPC --> Logging[Logging & Admin Service]
```

All internal communication from the **Game Session Service** to downstream
microservices uses **gRPC** for high performance and schema enforcement.

## 📚 Related Documentation

- [System Context Diagram](./system-context-diagram.md)
- [Gateway Architecture](./system-architecture-gateway.md)
