# 🔗 Design Document for Account Service

The design for this service is located here:

[📄 Central Architecture: Account Service Design](../../../design/architecture/microservices/account-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## Monetization Design

The Account Service also manages billing records for purchases and subscriptions. Payment processing is handled through **Stripe** as outlined in the [Core Requirements](../../../design/project-management/core-requirements.md#2.8-moderation-administration--monetization). Planned entities include `payment_transaction` and `subscription` tables with Flyway migrations. gRPC endpoints and REST controllers will expose operations for creating payment intents and managing subscriptions.
The proto definitions live in [`payment_service.proto`](../../../protos/account/v1/payment_service.proto).

## Email & Notification Design

This service sends verification and password reset emails using a configured SMTP provider. Notifications for suspicious logins or account events are queued for asynchronous delivery via a gRPC `NotificationService`. Sample templates live under `resources/templates/` and environment variables specify SMTP credentials. The gRPC API is defined in [`notification_service.proto`](../../../protos/account/v1/notification_service.proto).

## Session Management

Authentication returns a JWT token which is stored in Redis for quick reconnects. Keys follow `session:{tenantId}:{token}` and expire after the duration configured by `session-expiration-ms` in `AuthProperties`.

## Two-Factor Authentication

Admin and moderator accounts can enable a TOTP secret for additional protection.
If a `two_factor_secret` is present on the account row, the `/auth/login` endpoint
expects an `otp` field. Codes are validated using the Base32 secret as outlined
in the [Security Architecture](../../../design/architecture/system-architecture-security.md).

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /accounts` – create a new account and profile.
- `POST /auth/login` – authenticate and return a JWT token.
- `GET /.well-known/jwks.json` – JWKS for verifying issued JWT tokens.

Example account creation request:

```bash
curl -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"username":"demo","email":"demo@example.com","password":"secret"}'
```

Example response:

```json
{
  "id": 123,
  "tenantId": 1,
  "username": "demo",
  "email": "demo@example.com"
}
```

Example login request:

`otp` is only required when two-factor authentication is enabled for the account.

```bash
curl -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"username":"demo","password":"secret","otp":"123456"}'
```

Example login response:

```json
{
  "status": "SUCCESS",
  "data": {
    "authToken": "<token>"
  }
}
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`account_service.proto`](../../../protos/account/v1/account_service.proto).
- `CreateAccount(CreateAccountRequest) returns (CreateAccountResponse)` – registers a new user.
- `SendNotification(SendNotificationRequest) returns (SendNotificationResponse)` – deliver account notifications asynchronously.

Call the gRPC method with:

```bash
grpcurl -plaintext localhost:6565 account.v1.AccountService/Ping
```

Create an account via gRPC:

```bash
grpcurl -plaintext -d '{"tenant_id":1,"username":"demo","email":"demo@example.com","password":"secret"}' \
  localhost:6565 account.v1.AccountService/CreateAccount
```

Expected response:

```json
{
  "accountId": "123"
}
```

## Metrics & Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. Service methods expose
`account.*`, `payment.*`, `notification.*`, and `session.*` timers via
`@Timed` annotations. OpenTelemetry spans are exported to the collector service
so traces can be viewed in Jaeger. No additional configuration is required when
running via `./gradlew bootRun` as the default properties target
`http://otel-collector:4317`.
