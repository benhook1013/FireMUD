# Social Groups Service

Refer to [design/README.md](design/README.md) for architecture details.

- **Proto definitions**: [../../protos/social-groups/v1](../../protos/social-groups/v1)

## Running Locally

```bash
./gradlew :social-groups-service:bootRun
```

To run the entire stack:

```bash
./gradlew devUp
```

## Environment Variables

This service uses the standard `FIREMUD_` prefixed variables for PostgreSQL and
Redis connectivity. See the
[Environment Variables & Secrets Management](../../design/architecture/infrastructure/environment-and-secrets.md)
doc for defaults.
