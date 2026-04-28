# Game Session Service

Design details are kept in the architecture docs:
[Game Session Service Design](../../design/architecture/microservices/game-session-service/README.md)

This README is only a stub. **Do not place design information here.**

## Account Service dependency

- The Game Session Service delegates credential verification to the Account Service `/auth/login` (or gRPC `Authenticate`) endpoint and stores the issued JWT/session data per the “Login and Session Flow” section of `design/architecture/system-architecture-authentication.md`.
- The login smoke test and local workflows expect either the lightweight Account Service stub or a local Account Service instance to be reachable; without it the `LOGIN <username> <password>` step cannot complete and later `PLAY` / gameplay commands cannot be admitted.

## Local development

- Run the service against the real local dependency stack using the canonical runtime configuration. For end-to-end proof, use the repo smoke scripts under `dev-tools/` rather than a special single-service profile or single-service shortcut.

## Environment variables

- No additional service-local development flags are required for the canonical login/session path. The maintained runtime and test coverage now targets the real Redis/Postgres/downstream-service topology.
