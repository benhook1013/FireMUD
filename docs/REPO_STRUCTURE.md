# Repository Structure

This repository uses a hierarchical Gradle layout. Each microservice and the shared `common-library` are standalone modules under the root project.

```
root
├── common-library
├── account-service
├── ...
├── spring-cloud-gateway
├── tcp-proxy-service
├── protos/
└── docker-compose.yml
```

Proto definitions live under `protos/` organized by service and version as described in the gRPC design document. Database migration scripts for each service reside in `src/main/resources/db/migration/`.
