# AI Observations

## 2026-04-05

- The WSL environment was missing `unzip`, which forced archive inspection to fall back to `jar`. Common CLI tooling should be installed once rather than repeatedly replacing it with Python or ad hoc fallbacks.
- Spring gRPC `1.0.2` server TLS is bound under `spring.grpc.server.*`, while the repo still has older top-level `grpc.*` config blocks. That split is easy to edit incorrectly, so transport migrations should use explicit path checks or a dedicated config guard.
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
- 2026-04-05: Preview tcp-proxy readiness was blocked because preview wired GATEWAY_WS_URL to the public preview hostname. Tcp-proxy readiness depends on probing gateway readiness derived from GATEWAY_WS_URL, so preview must use the internal spring-cloud-gateway service URL rather than ingress.
