# FireMUD Design Assumptions

This document outlines high-level design and technology assumptions for the FireMUD platform. These are not strict requirements but represent current architectural choices. Alternative approaches may still be considered where appropriate.

## Backend

### Core Technologies

- **Language**: Java 21+
- **Framework**: Spring Boot 3.x
- **Architecture**: Microservices (see [System Architecture Overview](../architecture/system-architecture-overview.md))
- **Boilerplate Reduction**: Lombok
- **DTO Mapping**: MapStruct
- **Build Integration**: Each service declares Lombok and MapStruct dependencies with annotation processors enabled.
- **Data-Driven Rules**: Game definitions and rules can be edited via tooling without redeploying code. See [Game Design Service](../architecture/microservices/game-design-service/README.md).

### Deployment & Networking

- **Containerization**: Docker
- **Orchestration**: Kubernetes
- **Service Discovery**:
  - **Local Development**: Docker internal DNS-based discovery
  - **Production**: Kubernetes DNS-based discovery
- **API Gateway**: Spring Cloud Gateway
- **TCP Proxy Service** bridges Telnet clients to the Gateway via WebSocket
- **Inter-Service Communication**: gRPC secured with mTLS and instrumented with logging, metrics, and tracing interceptors (see [gRPC Architecture](../architecture/system-architecture-grpc.md))
- **Real-Time Networking**: WebSocket/TCP

### Data & Session Management

- **Database**: PostgreSQL
- **Database Access**: Spring Data JPA
- **Caching**: Redis for transient session and gameplay state
- **Redis Coordination Semantics**: Lua scripts provide atomic, shard-local updates on a single primary. Replication remains asynchronous; the platform assumes that some recent coordination writes may be lost or rolled back around failover and relies on idempotent tick replays plus PostgreSQL as the source of truth to repair or reapply state (see [Redis Architecture](../architecture/system-architecture-redis.md) and [Tick System](../architecture/system-architecture-ticks.md#crash-recovery-and-replay)).
- **Game Session Service** orchestrates ticks using Redis and loads runtime feature flags from PostgreSQL (see [Tick System](../architecture/system-architecture-ticks.md) and [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md))
- **Feature Flags** are defined in the Game Design Service and toggled at runtime via the Logging & Admin Service.
- **Single Session** per character with layered reconnection (Proxy → Gateway → Session) (see [Reconnection Strategy](../architecture/system-architecture-reconnection.md))
- **Multi-Tenancy**: `tenantId` column on all tables with isolation enforced in each service (see [Multi-Tenancy Architecture](../architecture/system-architecture-multi-tenancy.md))

### Operations & Support

- **Monitoring & Logging**: Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager with Micrometer instrumentation (see [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md))
- **CI/CD**: [GitHub Actions](../architecture/system-architecture-cicd.md)
- **Certificate Management**: cert-manager issues TLS and mTLS certificates stored as Kubernetes Secrets with hot reload via shared watchers (see [Security Architecture](../architecture/system-architecture-security.md))
- **Cluster Backups**: **Velero** backs up Kubernetes manifests only. PostgreSQL volumes are dumped via a CronJob. See [Backup & Disaster Recovery](../architecture/system-architecture-backup-recovery.md) for the backup schedule.
- **Payment Gateway**: Stripe (with custom subscription integration)

## Frontend

- **Language**: TypeScript
- **Framework**: React
- **Styling**: Material-UI

## Platform Interfaces

- **Web-based MUD Client**: Browser-based interface for players. See [web-client README](../../web-client/README.md).
- **Web-based MUD Game Editor**: Browser-based editor for designing game content, built on the Game Design Service UI.

## Testing

- **Unit Testing**: JUnit, Mockito
- **Integration Testing**: Spring Test
- **Load Testing**: Gatling (module `dev-tools/load-testing`)
