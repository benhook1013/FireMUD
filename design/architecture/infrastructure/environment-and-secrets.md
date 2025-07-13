# 🔐 Environment Variables & Secrets Management

This document explains how configuration values and sensitive secrets are supplied to FireMUD services in both development and production.

---

## 🧪 Local Development

- Environment variables are loaded from a `.env` file when running `./gradlew devUp`.
- The sample file `.env.sample` contains default credentials for PostgreSQL and Redis.
- Docker Compose passes these variables to each container so Spring Boot can connect to the databases.
- Secrets such as JWT signing keys are not required in development; random keys are generated on startup.

## ☁️ Production

- Kubernetes `ConfigMap` objects store non‑secret configuration values like host names or feature flags.
- Sensitive values (database passwords, JWT keys, TLS certificates) are stored in Kubernetes `Secret` objects.
- The manifests in `k8s/base/` demonstrate loading these via `envFrom` so that services receive the same variables as in development.
- Secrets are provisioned by **cert-manager** and rotated automatically. The
  sample `firemud-secret` manifest in `k8s/base/` contains placeholder values
  only for local demos; production deployments must supply real credentials.
- **Kubernetes Secrets** is the chosen mechanism for storing all sensitive
  credentials. External secret stores like Vault are not planned at this
  stage.

## 🔄 Variable Prefixes

Shared libraries support overriding default settings with environment variables using the `FIREMUD_` prefix. For example:

```bash
FIREMUD_POSTGRES_HOST=postgres
FIREMUD_POSTGRES_PORT=5432
FIREMUD_REDIS_HOST=redis
FIREMUD_REDIS_PORT=6379
```

Each service merges these variables with its own `application.yml` profile.

### Common Application Settings

The following variable is used by all Spring Boot services to select the
appropriate configuration profile. Typically only `dev` and `prod` are used.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`dev` or `prod`) | `dev` |

### PostgreSQL Credentials

Services connect to the shared PostgreSQL database using the following variables.
These values are typically provided via Kubernetes Secrets in production.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_POSTGRES_HOST` | Database host | `postgres` |
| `FIREMUD_POSTGRES_PORT` | Database port | `5432` |
| `FIREMUD_POSTGRES_DB` | Database name | `firemud` |
| `FIREMUD_POSTGRES_USER` | Username | `firemud` |
| `FIREMUD_POSTGRES_PASSWORD` | Password | `firemud` |

### Redis Connection

Redis stores transient queues and caches. Services that depend on Redis read
these variables.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_REDIS_HOST` | Redis host | `redis` |
| `FIREMUD_REDIS_PORT` | Redis port | `6379` |

### gRPC TLS Certificates

Mutual TLS protects all gRPC calls between services. Certificates are normally
provisioned by **cert-manager** and mounted from Kubernetes Secrets.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Filesystem path to the certificate chain for this service | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Filesystem path to the private key matching the certificate chain | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | Filesystem path to the CA bundle used to verify peer services | `certs/ca.crt` |

During local development these values are generated automatically, so the
variables may be omitted.

Docker Compose mounts `dev-tools/certs` into each service container at `/app/certs`
so the default paths above resolve correctly.

> **Note**: Certificate files should be loaded from the filesystem rather than
> packaged inside the application. Avoid `classpath:` URIs so that TLS materials
> can be mounted securely via volumes or Secrets.

### Authentication

JWT tokens secure internal service calls. Production keys are provided via
environment variables while development instances generate random secrets.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_AUTH_JWT_SECRET` | HMAC signing key for JWTs | *(none)* |
| `FIREMUD_AUTH_JWT_SECRET_PATH` | Path to a file containing the JWT secret; enables hot reload | *(none)* |
| `FIREMUD_AUTH_JWT_EXPIRATION_MS` | Lifetime of issued JWTs in milliseconds | `3600000` |
| `FIREMUD_AUTH_SESSION_EXPIRATION_MS` | Server-side session TTL in milliseconds | `3600000` |

### Service Discovery

The shared configuration library resolves other services using environment
variables prefixed with `FIREMUD_SERVICES_`. Each variable holds a `host:port`
pair for a target service. When undefined, Kubernetes DNS is used instead.

Each variable is suffixed with `_SERVICE` to match the Spring configuration
keys. Examples:

```bash
FIREMUD_SERVICES_GAME_LOGIC_SERVICE=game-logic-service:6565
FIREMUD_SERVICES_LOGGING_ADMIN_SERVICE=logging-admin-service:6565
```

### Observability

All services export OpenTelemetry spans. The collector endpoint can be
overridden with the `OTEL_ENDPOINT` environment variable (mapped to the
Spring property `otel.endpoint`):

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `OTEL_ENDPOINT` | gRPC endpoint for the OpenTelemetry collector | `http://otel-collector:4317` |

Service design documents reference this table for the OpenTelemetry endpoint configuration.

### Additional Notes

Service-specific settings such as SMTP credentials for the Account Service or
`GAME_TICK_DURATION_MS` for the Game Session Service are documented in each
service's design file. This document covers only shared configuration keys.

## 📚 Related Documentation

- [Deployment Environments](./deployment-environments.md)
- [System Architecture: Security](../system-architecture-security.md)
