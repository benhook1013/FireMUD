# 🔗 Design Document for Account Service

The design for this service is located here:

[📄 Central Architecture: Account Service Design](../../../design/architecture/microservices/account-service/README.md)

This stub exists to make the design easy to find from the service source tree.

## Monetization Design

The Account Service also manages billing records for purchases and subscriptions. Payment processing is handled through **Stripe** as outlined in the [Core Requirements](../../../design/project-management/core-requirements.md#2.8-moderation-administration--monetization). Planned entities include `payment_transaction` and `subscription` tables with Flyway migrations. gRPC endpoints and REST controllers will expose operations for creating payment intents and managing subscriptions.
The proto definitions live in [`payment_service.proto`](../../../protos/account/v1/payment_service.proto).

## Email & Notification Design

This service sends verification and password reset emails using a configured SMTP provider. Notifications for suspicious logins or account events are queued for asynchronous delivery via a gRPC `NotificationService`. Sample templates live under `resources/templates/` and environment variables specify SMTP credentials.

## REST & gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /accounts` – create a new account and profile.

Example request:

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

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`account_service.proto`](../../../protos/account/v1/account_service.proto).
- `CreateAccount(CreateAccountRequest) returns (CreateAccountResponse)` – registers a new user.

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
