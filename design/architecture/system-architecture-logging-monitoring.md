# 📊 FireMUD Logging & Monitoring Overview

This document consolidates the platform's observability architecture. It replaces duplicated descriptions found in other docs.

---

## 🔍 Logging Pipeline

- **Fluent Bit** sidecars collect service logs.
- Logs are stored in **Elasticsearch** and explored through **Kibana** dashboards.
- The **Logging & Admin Service** exposes moderation tools and log queries.

## 📈 Metrics & Tracing

- **Prometheus** scrapes metrics from all services and triggers alerts via **Alertmanager**.
- **Grafana** dashboards visualize performance data.
- **OpenTelemetry** spans provide distributed tracing across ticks and requests.

## 🩺 Health Checks

- Spring Boot `/actuator/health` endpoints feed Kubernetes readiness and liveness probes.
- See [Deployment Environments](./infrastructure/deployment-environments.md#🩺-kubernetes-health-monitoring) for probe behavior.

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Redis Architecture](./system-architecture-redis.md#📈-observability-and-reliability)
- [Logging & Admin Service](./microservices/logging-admin-service/README.md)
- [Infrastructure Overview](./infrastructure/README.md)
