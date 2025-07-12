# Repository Structure

This repository uses a hierarchical Gradle layout. All microservices and the shared `common-library` now live under a top-level `services/` folder to keep the root tidy.

```text
root
├── services/
│   ├── common-library
│   ├── account-service
│   ├── ...
│   ├── spring-cloud-gateway
│   └── tcp-proxy-service
├── protos/
└── docker-compose.yml
```

Proto definitions live under `protos/` organized by service and version as described in the gRPC design document. Database migration scripts for each service reside in `src/main/resources/db/migration/`.

## 📚 Related Documentation

- [System Architecture Overview](./system-architecture-overview.md)
- [Microservices Overview](./microservices/README.md)
