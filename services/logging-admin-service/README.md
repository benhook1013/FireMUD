# Logging Admin Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/logging-admin/v1](../../protos/logging-admin/v1)

## Running Locally

```bash
./gradlew :logging-admin-service:bootRun
```

To run the entire stack:

```bash
docker compose up --build
```

