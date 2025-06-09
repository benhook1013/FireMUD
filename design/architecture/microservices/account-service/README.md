# Account Service

## Overview
Manages user accounts and authentication for the platform. Stores profile data and controls session creation and validation.

## Architecture / Design Notes
- Stateless authentication using JWT tokens.
- Session information is cached in Redis for fast lookups and reconnections.

## Key Features
- Account registration and login.
- Profile management and email notifications.
- Banning and subscription tracking.

## Dependencies
- **External:** PostgreSQL for account data, Redis for session caching.

## Future Enhancements
- OAuth2 support for social logins.
- Self-service account recovery tools.
