# Account Service

The complete design for this service is documented in the central repository:
[📄 Account Service Design](../../design/architecture/microservices/account-service/README.md)

This README links to the service's canonical design. **Do not duplicate design details here.**

## Current gameplay connect-token availability

`/auth/connect-token` validates the authenticated bootstrap and connect scope, then returns `AUTH_UNAVAILABLE` before reading cached success, minting a token, or mutating replay/session state. The canonical gameplay connect-context producer is not qualified. See [Account Runtime and Data](../../design/architecture/microservices/account-service/runtime-and-data.md#implementation-status) and the [Player Access and Session tracker](../../design/project-management/implementation-tracking/player-access-and-session.md#canonical-first-party-receiving-preparation) for current implementation status and proof limits.
