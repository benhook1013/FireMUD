# 🔐 Environment Variables & Secrets Management

This document explains how configuration values and sensitive secrets are supplied to FireMUD services in both development and production.

---

## 🧪 Local Development

- Environment variables are loaded from a `.env` file when running `./gradlew devUp`.
- The sample file `.env.sample` lists the variables described below with default credentials for PostgreSQL and Redis. Copy this file to `.env` and adjust values as needed; `.env` is git‑ignored so real credentials remain local.
- Docker Compose passes these variables to each container so Spring Boot can connect to the databases.
- Secrets such as JWT signing keys are not required in development; random keys are generated on startup.

## ☁️ Production

- Kubernetes `ConfigMap` objects store non‑secret configuration values like host names or feature flags.
- Sensitive values (database passwords, JWT keys, TLS certificates) are stored in Kubernetes `Secret` objects. TLS certificates are issued by **cert-manager**, while JWT signing keys are added manually; automated rotation via cert-manager is planned. (TODO: Not yet implemented)
- The manifests in `k8s/base/` demonstrate loading these via `envFrom` so that services receive the same variables as in development.
- TLS certificates are provisioned by **cert-manager** and rotated automatically. Other secrets, such as database passwords and JWT keys, are stored in standard Kubernetes `Secret` objects and must be rotated manually. Automated secret rotation is planned. (TODO: Not yet implemented)
- Services reload TLS certificates for gRPC client channels and JWT secrets when these Secrets update using the `TlsCertificateWatcher` and `JwtSecretWatcher` utilities from the shared library. A `GrpcServerTlsReloader` exists for server certificates but is not yet wired into the services, so server-side hot reload is planned. (TODO: Not yet implemented)
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
provisioned by **cert-manager** and mounted from Kubernetes Secrets. A sample
`Certificate` manifest is provided at `k8s/base/firemud-grpc-certificate.yaml`.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Filesystem path to the certificate chain for this service | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Filesystem path to the private key matching the certificate chain | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | Filesystem path to the CA bundle used to verify peer services | `certs/ca.crt` |

During local development these values are generated automatically, so the
variables may be omitted.

Docker Compose mounts `dev-tools/certs` into each service container at `/app/certs`
so the default paths above resolve correctly.

In Kubernetes deployments the certificates are mounted at `/tls`, and the
environment variables point to that directory (for example,
`FIREMUD_GRPC_CERT_CHAIN_PATH=/tls/client.crt`). Services watch these files for
changes so new certificates are loaded without restarts via `TlsCertificateWatcher`. Certificate reload for
gRPC servers will use `GrpcServerTlsReloader` but this integration is still pending. (TODO: Not yet implemented)
See [System Architecture: Security](../system-architecture-security.md#key-and-certificate-rotation)
for details on the hot reload mechanism.

> **Note**: Certificate files should be loaded from the filesystem rather than
> packaged inside the application. Avoid `classpath:` URIs so that TLS materials
> can be mounted securely via volumes or Secrets.

### Authentication

JWT tokens secure internal service calls. Production keys are provided via
environment variables while development instances generate random secrets.
When `FIREMUD_AUTH_JWT_SECRET_PATH` is set, the service watches the file for
changes using `JwtSecretWatcher` so keys can be rotated without restarts. Certificate and secret watching
is described in [System Architecture: Security](../system-architecture-security.md#key-and-certificate-rotation).

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
These overrides are consumed by the `ServiceEndpointsProperties` class so gRPC
clients can dynamically point to different hosts. Spring Cloud Gateway currently
loads routes from static YAML files and does not read these variables. Gateway
support for `FIREMUD_SERVICES_` overrides is planned. (TODO: Not yet implemented)

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
| `FLUENT_ELASTICSEARCH_HOST` | Hostname of the log storage backend | `elasticsearch` |
| `FLUENT_ELASTICSEARCH_PORT` | Port for the log storage backend | `9200` |

Service design documents reference this table for the OpenTelemetry endpoint configuration.

### Backup & Restore Variables

Operational scripts and CronJobs rely on the following variables when uploading or restoring database dumps.

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `PG_DUMP_BUCKET` | Object storage bucket for pg_dump files | *(none)* |
| `PG_DUMP_ENDPOINT` | Optional S3-compatible endpoint URL | *(none)* |
| `FIREMUD_K8S_NAMESPACE` | Target namespace for restore scripts | `firemud` |

See [Backup & Disaster Recovery](../system-architecture-backup-recovery.md) for schedules and retention policies.

### Additional Notes

Service-specific settings such as SMTP credentials for the Account Service or `GAME_TICK_DURATION_MS` for the Game Session Service are documented in each service's design README. See the "Environment Variables" sections in
[Account Service Design](../microservices/account-service/README.md#environment-variables) and
[Game Session Service Design](../microservices/game-session-service/README.md#environment-variables)
for concrete examples. This document covers only shared configuration keys.

Operational scripts like `dev-tools/restore-cluster.sh` use an optional
`FIREMUD_K8S_NAMESPACE` variable to target the Kubernetes namespace. It defaults
to `firemud` when unset.

## 📚 Related Documentation

- [Deployment Environments](./deployment-environments.md)
- [System Architecture: Security](../system-architecture-security.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Operational Runbooks](../system-architecture-runbooks.md)
