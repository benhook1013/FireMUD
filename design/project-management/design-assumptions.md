# FireMUD Design Assumptions

This document outlines high-level design and technology assumptions for the FireMUD platform. These are not strict requirements but represent current architectural choices. Alternative approaches may still be considered where appropriate.

## Backend

### Core Technologies

- **Language**: Java 21+
- **Framework**: Spring Boot 3.x
- **Architecture**: Microservices
- **Boilerplate Reduction**: Lombok
- **DTO Mapping**: MapStruct
- **Build Integration**: Each service declares Lombok and MapStruct dependencies with annotation processors enabled.

### Deployment & Networking

- **Containerization**: Docker
- **Orchestration**: Kubernetes
- **Service Discovery**:
  - **Local Development**: Docker internal DNS-based discovery
  - **Production**: Kubernetes DNS-based discovery
- **API Gateway**: Spring Cloud Gateway
- **TCP Proxy Service** bridges Telnet clients to the Gateway
- **Inter-Service Communication**: gRPC secured with mTLS
- **Real-Time Networking**: WebSocket/TCP

### Data & Session Management

- **Database**: PostgreSQL
- **Database Access**: Spring Data JPA
- **Caching**: Redis for transient session and gameplay state
- **Redis Consistency**: Lua scripts enforce atomic updates with `WAIT` for replica acknowledgment
- **Game Session Service** orchestrates ticks and runtime flags using Redis
- **Single Session** per character with layered reconnection (Proxy → Gateway → Session)
- **Multi-Tenancy**: `tenantId` column on all tables with isolation enforced in each service

### Operations & Support

- **Monitoring & Logging**: Fluent Bit, Elasticsearch, Kibana, Grafana, Prometheus, OpenTelemetry, Alertmanager (see [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md))
- **CI/CD**: [GitHub Actions](../architecture/system-architecture-cicd.md)
- **Certificate Management**: TLS and mTLS certificates issued by **cert-manager** and stored as Kubernetes Secrets
- **Cluster Backups**: **Velero** backs up Kubernetes manifests only. PostgreSQL volumes are dumped via a CronJob. See [Backup & Disaster Recovery](../architecture/system-architecture-backup-recovery.md) for the backup schedule.
- **Payment Gateway**: Stripe (with custom subscription integration)

## Frontend

- **Language**: JavaScript
- **Framework**: React
- **Styling**: Material-UI

## Platform Interfaces

- **Web-based MUD Client**: Browser-based interface for players.
- **Web-based MUD Game Editor**: Browser-based editor for designing game content.

## Testing

- **Unit Testing**: JUnit, Mockito
- **Integration Testing**: Spring Test
- **Load Testing**: Gatling (module `dev-tools/load-testing`)
