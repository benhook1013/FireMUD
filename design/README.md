# Design Documentation

This directory contains all architecture and project-management documentation for the FireMUD Game Platform.

If you are orienting yourself to the platform, use this directory as the main documentation entry point rather than the repository root README.

Suggested reading order:

1. [**architecture/system-architecture-overview.md**](./architecture/system-architecture-overview.md) for the canonical platform model.
2. [**architecture/service-responsibility-matrix.md**](./architecture/service-responsibility-matrix.md) for service ownership boundaries.
3. [**architecture/user-journeys.md**](./architecture/user-journeys.md) for player, creator, and operator flows.
4. [**project-management/implementation-tracking/README.md**](./project-management/implementation-tracking/README.md) for domain capability status, active gaps, and remaining implementation decisions.

- [**architecture/**](./architecture/) – Infrastructure, microservice designs, and system overviews.
- [**developer-workflows/**](./developer-workflows/) – Hands-on walkthroughs and smoke tests for key platform capabilities.
- [**project-management/**](./project-management/) – Requirements, domain implementation tracking, and AI rule sets.
- [**user-guides/**](./user-guides/) – Documentation for game creators and integration testing.

Additional generated documentation lives in:

- [**grpc-docs/**](./grpc-docs/) – API references generated from our protobuf definitions.
- [**observability/**](./observability/) – Default Grafana and Kibana dashboard templates.

Refer to the README files within each subdirectory for more details. For a high-level walkthrough of common creator and player workflows see [**User Journeys**](architecture/user-journeys.md).
