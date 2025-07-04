# Account Service

## Overview

Manages user accounts and authentication for the platform. Stores profile data and controls session creation and validation.

## Architecture / Design Notes

- Stateless authentication using JWT tokens.
- Session information is stored in Redis as transient data for quick reconnections.

## Key Features

- Account registration and login.
- Profile management and email notifications.
- Banning and subscription tracking.

## Dependencies

- **External:** PostgreSQL for account data, Redis for transient session data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## 📚 Related Documentation

- [Authentication & Authorization](../system-architecture-authentication.md)
- [System Architecture Overview](../system-architecture-overview.md)

## Future Enhancements

- OAuth2 support for social logins.
- Self-service account recovery tools.
