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
        Alertmgr[Alertmanager]
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
    InternalServices -- logs --> FluentBit
    InternalServices -- metrics --> Prom
    InternalServices -- traces --> OTel
    FluentBit --> ES
    Prom --> Alertmgr
    Prom --> Grafana
    OTel --> Jaeger
    ES --> Kibana
```

All internal communication from the **Game Session Service** to downstream microservices uses **gRPC** for high performance and strict schema enforcement. All services persist data in PostgreSQL, cache transient state in Redis, and send structured logs to Elasticsearch.

## 🧩 Core Services Shown

The diagram covers every microservice currently in the repository:

- **TCP Proxy Service** – Bridges Telnet clients into the WebSocket-based backend.
- **Spring Cloud Gateway** – Routes HTTP and WebSocket traffic to internal services.
- **Game Session Service** – Orchestrates sessions, ticks, and runtime configuration.
- **Account Service** – Handles accounts, authentication, and subscriptions.
- **World Management Service** – Stores rooms, regions, and world maps.
- **Entity Management Service** – Manages players, NPCs, items, and inventory data.
- **Game Logic Service** – Resolves commands and core gameplay mechanics.
- **Game Design Service** – Provides authoring tools for game data and feature flags.
- **Automation & Scripting Service** – Executes AI behaviors and custom scripts.
- **Social & Groups Service** – Manages chat, guilds, and social networking.
- **Logging & Admin Service** – Centralizes logging, metrics, and admin tools.

## 🔍 Observability Components

The diagram also illustrates the monitoring stack shared by every service:

- **Fluent Bit** – Collects structured logs from each container.
- **Elasticsearch** – Stores logs for search and troubleshooting.
- **Prometheus** – Scrapes metrics and forwards alerts to **Alertmanager**.
- **Grafana** – Visualizes dashboards based on Prometheus data.
- **OpenTelemetry Collector** – Aggregates distributed traces.
- **Jaeger** – Provides a UI for end‑to‑end trace analysis.
- **Kibana** – Queries and visualizes Elasticsearch logs.

See [Logging & Monitoring](./system-architecture-logging-monitoring.md) for deployment details.

## 📚 Related Documentation

- [System Context Diagram](./system-context-diagram.md)
- [Microservices Overview](./microservices/README.md)
- [Service Responsibility Matrix](./service-responsibility-matrix.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
