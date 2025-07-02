# FireMUD Design Assumptions

This document outlines high-level design and technology assumptions for the FireMUD platform. These are not strict requirements but represent current architectural choices. Alternative approaches may still be considered where appropriate.

## Backend

- **Language**: Java
- **Architecture**: Microservices
- **Framework**: Java Spring Framework
- **Database**: PostgreSQL
- **Caching**: Redis
- **Database Access**: Spring Data JPA
- **Service Discovery**:
  - **Local Development**: Docker internal DNS-based discovery
  - **Production**: Kubernetes DNS-based discovery
- **API Gateway**: Spring Cloud Gateway
- **Real-Time Networking**: WebSocket/TCP
- **Containerization**: Docker
- **Orchestration**: Kubernetes
- **Monitoring & Logging**: Prometheus, Grafana, Elasticsearch, Alertmanager
- **CI/CD**: [GitHub Actions](../architecture/system-architecture-cicd.md)
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
- **Load Testing**: Gatling
