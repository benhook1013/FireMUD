# TCP Proxy Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/tcp-proxy/v1](../../protos/tcp-proxy/v1)

## Running Locally

```bash
./gradlew :tcp-proxy-service:bootRun
```

To run the entire stack:

```bash
docker compose up --build
```

