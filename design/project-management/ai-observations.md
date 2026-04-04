# AI Observations

Append-only notes for recurring friction, surprising behavior, environment issues, inefficient patterns, and code smells discovered during AI work.

Entry format:

- `YYYY-MM-DD`: short title
  - Context: where it appeared
  - Observation: what was surprising or wasteful
  - Expected pattern: what should happen instead

- `2026-04-05`: Preview gRPC mTLS used a client-only cert for server identity
  - Context: hosted preview `pr-2205` stalled with only `game-logic-service` and `game-session-service` unready after the gRPC service-port fix was already live.
  - Observation: preview secret generation reused `client.crt` as the mounted server certificate even though it had `CN=firemud-client` and no service-DNS SANs, so readiness paths that call in-cluster gRPC services could fail silently on hostname verification.
  - Expected pattern: local and preview certificate generation should emit a shared mTLS certificate valid for both client and server use, including localhost and in-cluster service DNS names.
