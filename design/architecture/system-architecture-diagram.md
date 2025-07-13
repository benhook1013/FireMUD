# 📈 FireMUD System Architecture: Diagram

```mermaid
flowchart TD
    subgraph Clients
        MUD[MUD Client]
        Web[Web Client]
    end

    subgraph DMZ
        TCPProxy[TCP Proxy Service]
        Gateway[Spring Cloud Gateway]
    end

    subgraph InternalServices["Internal Services"]
        Session[Game Session Service]
        Account[Account Service]
        World[World Management Service]
        Entity[Entity Management Service]
        Logic[Game Logic Service]
        Design[Game Design Service]
        Script[Automation & Scripting Service]
        Social[Social & Groups Service]
        Logging[Logging & Admin Service]
    end

    subgraph Datastores
        DB[(PostgreSQL)]
        Cache[(Redis)]
        ES[(Elasticsearch)]
    end

    subgraph Observability
        FluentBit[Fluent Bit]
        Prom[Prometheus]
        OTel[OTel Collector]
        Kibana[Kibana]
        Grafana[Grafana]
        Jaeger[Jaeger]
    end

    MUD -- TCP --> TCPProxy
    Web -- wss/HTTP --> Gateway
    TCPProxy -- wss --> Gateway
    Gateway -- wss --> Session

    Session -- gRPC --> Account
    Session -- gRPC --> World
    Session -- gRPC --> Entity
    Session -- gRPC --> Logic
    Session -- gRPC --> Design
    Session -- gRPC --> Script
    Session -- gRPC --> Social
    Session -- gRPC --> Logging

    InternalServices --> Datastores
    InternalServices --> Observability
```

All internal communication from the **Game Session Service** to downstream microservices uses **gRPC** for high performance and strict schema enforcement. All services persist data in PostgreSQL, cache transient state in Redis, and send structured logs to Elasticsearch.

## 📚 Related Documentation

- [System Context Diagram](./system-context-diagram.md)
- [Microservices Overview](./microservices/README.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
