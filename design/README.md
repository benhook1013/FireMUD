# Design Documentation

This directory contains FireMUD's product, architecture, operations, developer-workflow, and project-management documentation.

If you are orienting yourself to the platform, use this directory as the main documentation entry point rather than the repository root README.

Suggested reading order:

1. [**product/README.md**](./product/README.md) for product requirements, capabilities, and observable user behavior.
2. [**architecture/system-architecture-overview.md**](./architecture/system-architecture-overview.md) for technical contracts and the platform model.
3. [**architecture/service-responsibility-matrix.md**](./architecture/service-responsibility-matrix.md) for service ownership boundaries.
4. [**project-management/implementation-tracking/README.md**](./project-management/implementation-tracking/README.md) for domain capability status, active gaps, and remaining implementation decisions.

- [**architecture/**](./architecture/) – Infrastructure, microservice designs, and system overviews.
- [**operations/**](./operations/README.md) – Operator-facing deployment, recovery, incident, credential, and compliance procedures.
- [**developer-workflows/**](./developer-workflows/) – Contributor procedures for validation, review, testing, and playtesting.
- [**project-management/**](./project-management/) – Implementation status, reconciliation, reusable review material, and project history.
- [**user-guides/**](./user-guides/) – Documentation for game creators and integration testing.

Additional generated documentation lives in:

- [**grpc-docs/**](./grpc-docs/) – API references generated from our protobuf definitions.
- [**observability/**](./observability/) – Default dashboards, alert references, and observability assets.

Refer to the README files within each subdirectory for more details. For a high-level walkthrough of common player, creator, and operator workflows see [**User Journeys**](product/user-journeys/overview.md).
