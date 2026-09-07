# TCP Proxy Service Configuration

## Implementation Status

The target contract requires explicit `TCP_PROXY_TELNET_MODE` selection and startup rejection of incompatible listener and TLS settings in shared and player-facing environments. `EDGE_PROXY` additionally requires an authenticated, cryptographically protected edge-to-PROXY-listener channel and retained deployment evidence for the exact edge and listener identities. Runtime configuration does not contain the deployment topology needed to determine whether both public ingress modes are externally exposed; public-listener exposure exclusivity is therefore a deployment-preflight responsibility under [PREFLIGHT-TELNET-001](../../system-architecture-deploy-preflight-policy.md#preflight-telnet-001). Current code still exposes independent port and `TCP_PROXY_TLS_ENABLED` settings without that cross-setting validator or protected-edge-channel readiness proof, so operators can currently construct an invalid or unauthenticated mixed mode. Those gaps are not supported target configurations and require implementation and focused startup proof before player-facing readiness.

## Minimal Production Configuration Checklist

The security policy for these settings is owned by [Security](../../system-architecture-security.md#telnet-command-handling-and-controls); this section retains the TCP Proxy startup and listener consequences.

For any shared or player-facing environment, operators should ensure at least:

- `GATEWAY_WS_URL` points at the Spring Cloud Gateway WebSocket mTLS listener (`wss://.../ws/game`), with `FIREMUD_GATEWAY_WS_*` variables configured so TCP Proxy authenticates the Gateway server and presents its own dedicated WebSocket client certificate.
- The `certs/client.*` values shown below for Proxy -> Gateway and internal gRPC are local/dev convenience defaults only. Shared and player-facing startup/admission must load the effective private-key identities and fail closed when their public-key fingerprints are equal, including when the paths differ through symlinks or aliases.
- `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP` are set to non-zero values sized for expected load and NAT patterns; the `0` defaults are reserved for local/dev and CI.
- Public player-facing Telnet must select exactly one TLS mode per endpoint: edge termination with internal PROXY forwarding, or direct TLS termination at TCP Proxy. These modes must not be combined.
- Shared and player-facing deployments set `TCP_PROXY_TELNET_MODE` explicitly to `EDGE_PROXY` or `DIRECT_TLS`; an unset mode is allowed only for local development and automated tests.
- In `EDGE_PROXY` mode, the edge forwards Telnet with PROXY protocol into `TCP_PROXY_PROXY_PROTOCOL_PORT` only through the authenticated, cryptographically protected channel required by [Security](../../system-architecture-security.md#telnet-command-handling-and-controls). The listener remains internal-only, `TCP_PROXY_TLS_ENABLED` must be `false`, and recovered client addresses become trusted only after channel identity validation succeeds.
- In `DIRECT_TLS` mode, the public listener uses `TCP_PROXY_TLS_ENABLED=true` and does not accept a PROXY header; raw and PROXY-protocol listeners remain local, test-only, or explicitly private.
- Startup rejects an unknown mode, missing required listener or certificate settings, `EDGE_PROXY` with TCP Proxy TLS enabled, or `DIRECT_TLS` without TCP Proxy TLS enabled. It cannot determine simultaneous public exposure from runtime configuration; deployment preflight must prove that only the selected public mode is externally exposed. The canonical preflight evidence entrypoint is [`dev-tools/deploy/preflight.py`](../../../../dev-tools/deploy/preflight.py), which does not yet emit this target-state-only result.
- Genuinely client-facing plaintext Telnet connections should trigger the canonical landing-menu warning recommending Telnet-over-TLS or the web client instead. The trusted internal plaintext hop after edge TLS termination is not itself a plaintext client connection and does not trigger that warning.

## TLS and Trust Surfaces

[Security](../../system-architecture-security.md#tls-termination--internal-encryption) owns the cross-service trust policy. The table below records the TCP Proxy-local listener identities and delivery variables.

The TCP Proxy Service participates in three distinct TLS and trust boundaries:

| Surface | Direction | Identity and purpose | Key configuration |
| --- | --- | --- | --- |
| Telnet edge termination plus internal PROXY | Public TLS edge -> internal TCP Proxy | Edge terminates client TLS and forwards Telnet with trusted client-IP metadata over an authenticated, cryptographically protected channel; TCP Proxy application TLS remains disabled on the PROXY listener when protection terminates in an attested sidecar or service-mesh boundary. | `TCP_PROXY_PROXY_PROTOCOL_PORT`, edge TLS configuration, and the deployment-owned listener identity, trust roots, permitted edge identity, and readiness evidence |
| Direct Telnet-over-TLS | Client <-> TCP Proxy Service | **Telnet server-TLS identity:** TCP Proxy presents the server certificate configured by `TCP_PROXY_TLS_CERT`/`TCP_PROXY_TLS_KEY` to Telnet clients. This is client-facing server TLS, not the WebSocket client mTLS identity or the internal gRPC server identity. | `TCP_PROXY_PORT`, `TCP_PROXY_TLS_ENABLED`, `TCP_PROXY_TLS_CERT`, `TCP_PROXY_TLS_KEY` |
| WebSocket mTLS bridge | TCP Proxy Service -> Spring Cloud Gateway | **Gateway WebSocket client mTLS identity:** TCP Proxy presents its dedicated client certificate to Gateway and validates Gateway with the configured CA. This is not the identity used by TCP Proxy's gRPC server. | `GATEWAY_WS_URL`, `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, `FIREMUD_GATEWAY_WS_CA_CERT_PATH` |
| Internal gRPC mTLS | Internal clients -> TCP Proxy Service | **Internal gRPC server mTLS identity:** TCP Proxy presents its gRPC server certificate to internal callers and validates their client identity. This is not the Gateway WebSocket client identity or the Telnet server-TLS identity. | `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` |

Plaintext local and throwaway test profiles may omit these identities and may reuse generated `certs/client.*` material. Those defaults are local/dev-only convenience values; shared and player-facing deployments use separate private identities, and reused certificate files are not promotion evidence.

## Redis Role Guidance

The TCP Proxy Service does not depend on Redis for correctness and currently uses no Redis keys.

- It must never use Coordination Redis.
- If future optional cache or throttle keys are introduced, they must live in Cache/Rate-Limit Redis under dedicated `tcpproxy:*` prefixes.
- Any such Redis usage must remain non-authoritative and safe to lose without affecting gameplay correctness.

## Environment Variables

The proxy uses minimal configuration. It follows the scheme in [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md), though it does not use PostgreSQL directly.

The full variable list is the canonical source of defaults and behavior for `TCP_PROXY_*`:

| Variable | Purpose | Default |
| --- | --- | --- |
| `TCP_PROXY_TELNET_MODE` | Select exactly one player-facing Telnet ingress mode: `EDGE_PROXY` or `DIRECT_TLS`; required in shared and player-facing environments, unset only in local/dev and tests | *(none)* |
| `TCP_PROXY_PORT` | TCP port the proxy listens on; this is the public TLS listener in `DIRECT_TLS` mode and must remain unbound or private in `EDGE_PROXY` mode | `2323` |
| `TCP_PROXY_PROXY_PROTOCOL_PORT` | TCP port for the edge-termination mode's PROXY-protocol Telnet listener; internal-only and reachable only from the Telnet edge proxy | `2325` |
| `GATEWAY_WS_URL` | WebSocket URL for forwarding to the gateway; local Docker and test environments may use a plaintext `ws://` endpoint, but player-facing environments must set an explicit `wss://.../ws/game` target | *(none)* |
| `TCP_PROXY_DEFAULT_WORLD_SLUG` | Explicit local/bootstrap world slug forwarded as server-owned default advisory bridge metadata when configured instead of waiting for first-party connect-token admission; it cannot alter authentication, canonical routing, or gameplay-admission authority | *(empty)* |
| `TCP_PROXY_DEFAULT_REALM_SLUG` | Explicit local/bootstrap realm slug paired with the server-owned default advisory world metadata; it cannot alter authentication, canonical routing, or gameplay-admission authority | *(empty)* |
| `TCP_PROXY_DEFAULT_GAME_INSTANCE_ID` | Explicit local/bootstrap game-instance id forwarded as server-owned default advisory bridge metadata; TCP Proxy does not resolve or authorize it | *(empty)* |
| `TCP_PROXY_DEFAULT_TENANT_ID` | Explicit local/bootstrap tenant id forwarded as server-owned default advisory bridge metadata; TCP Proxy does not resolve or authorize it | *(empty)* |
| `TCP_PROXY_DEFAULT_POINTER_VERSION` | Explicit local/bootstrap admission-pointer freshness token paired with the server-owned default advisory world/realm metadata; it cannot alter authentication, canonical routing, or gameplay-admission authority | *(empty)* |
| `TCP_PROXY_TLS_ENABLED` | Enable direct TCP Proxy Telnet-over-TLS termination; required with `DIRECT_TLS` and rejected with `EDGE_PROXY` | `false` |
| `TCP_PROXY_TLS_CERT` | Path to the Telnet listener TLS certificate | *(empty)* |
| `TCP_PROXY_TLS_KEY` | Path to the Telnet listener TLS private key | *(empty)* |
| `TCP_PROXY_MAX_CONNECTIONS` | Maximum concurrent Telnet connections | `0` |
| `TCP_PROXY_MAX_CONNECTIONS_PER_IP` | Maximum concurrent Telnet connections per client IP | `0` |
| `TCP_PROXY_MAX_LINE_BYTES` | Maximum accepted Telnet/opaque input line in bytes | `4096` |
| `TCP_PROXY_MAX_OVERSIZE_LINES` | Maximum oversized lines per connection before hard close | `10` |
| `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` | Maximum total retry time for failed `NotifyDisconnect` calls | `5000` |
| `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` | Initial-admission bridge-establishment window | `5000` |
| `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` | Circuit-breaker open threshold for upstream gameplay unreachability | `5000` |
| `TCP_PROXY_GATEWAY_CIRCUIT_HALF_OPEN_MAX_PROBES` | Maximum concurrent bridge probes while half-open | `3` |
| `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` | Consecutive successful probes required to recover admission | `3` |
| `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` | Maximum buffered Telnet lines waiting to be forwarded | `64` |
| `TCP_PROXY_MCP_ENABLED` | Deprecated implementation-drift marker/greeting flag; target startup accepts `true` only in explicit local/development/test profiles and rejects it in shared, player-facing, and prod-like profiles; the default remains `false`, disabled, and unadvertised; not a supported semantic-extension toggle | `false` |
| `TCP_PROXY_MCP_NEGOTIATION_FAILURE_MAX` | Dormant target-only MCP budget; not consumed by the current runtime | `5` |
| `TCP_PROXY_MCP_NEGOTIATION_FAILURE_WINDOW_MS` | Dormant target-only MCP budget window; not consumed by the current runtime | `60000` |
| `TCP_PROXY_MCP_MAX_ACTIVE_CORDS` | Dormant target-only MCP cord budget; not consumed by the current runtime | `16` |
| `TCP_PROXY_MCP_MAX_ACTIVE_DATA_TAGS` | Dormant target-only MCP `_data-tag` budget; not consumed by the current runtime | `16` |
| `TCP_PROXY_MCP_MAX_CONTROL_LINES_PER_SEC` | Dormant target-only MCP control-line budget; not consumed by the current runtime | `50` |
| `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` | Client certificate chain path for Proxy -> Gateway WebSocket mTLS | `certs/client.crt` (local/dev only) |
| `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH` | Client private key path for Proxy -> Gateway WebSocket mTLS | `certs/client.key` (local/dev only) |
| `FIREMUD_GATEWAY_WS_CA_CERT_PATH` | CA bundle path for verifying the gateway certificate | `certs/ca.crt` |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Certificate chain path for the proxy’s internal gRPC server mTLS | `certs/client.crt` (local/dev only) |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Private key path for the proxy’s internal gRPC server mTLS | `certs/client.key` (local/dev only) |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle path for verifying gRPC peers | `certs/ca.crt` |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint | `http://otel-collector:4317` |

When any `TCP_PROXY_DEFAULT_*` bootstrap variables are used together, local/bootstrap profiles must supply one coherent server-owned advisory bridge-metadata bundle: `worldSlug`, `realmSlug`, explicit `tenantId`, explicit `gameInstanceId`, and `pointerVersion`. These direct environment values are local/bootstrap inputs only: TCP Proxy neither resolves nor authorizes tenant or game-instance identity. Shared and player-facing deployments must obtain matching canonical gameplay-admission evidence and fail closed when that evidence is absent, malformed, stale, or inconsistent; forwarded metadata remains advisory and cannot replace or alter canonical authentication, routing, or gameplay-admission authority. Do not configure only the runtime ids while omitting the visible world/realm identity or pointer freshness, because that would recreate a partial routing shortcut prohibited by the canonical [realm-catalog and admission-pointer contract](../../system-architecture-multi-tenancy.md#realm-catalog-and-admission-pointer-contract).

Hosted preview and dev-demo Helm deployments select direct Telnet TLS with a dedicated cert-manager Secret named `<release>-telnet-tls`; it is distinct from the HTTP Ingress and gRPC TLS Secrets and is mounted read-only at `/telnet-tls`. The public NodePort remains the selected TCP Proxy listener, now carrying TLS.

When all three routing defaults are configured coherently, TCP Proxy forwards them as `X-World-Slug`, `X-Realm-Slug`, and positive `X-Pointer-Version` only on the authenticated Proxy -> Gateway WebSocket hop. Gateway rejects a partial or malformed bundle, removes connect-token carriers for that trusted transport, and forwards the validated advisory bundle to Game Session; the headers never bypass direct credential `LOGIN` / `PLAY` or become gameplay-admission authority. The complete trusted-hop header contract is [TCP Proxy API Contracts](./api-contracts.md#correlation-and-header-contract).

## WebSocket mTLS to Spring Cloud Gateway

The trust and identity requirements are defined by [Security](../../system-architecture-security.md#tls-termination--internal-encryption) and the [environment and secrets catalog](../../infrastructure/environment-and-secrets-catalog.md#tls--certificates); the TCP Proxy-local wiring is:

In production, the TCP Proxy Service connects to Spring Cloud Gateway over `wss://` using mutual TLS by dialing a dedicated internal-only Gateway WebSocket mTLS listener.

- Client certificate and key are loaded from `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` and `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`.
- The Gateway’s certificate is validated against `FIREMUD_GATEWAY_WS_CA_CERT_PATH`, with hostname verification enabled using the host from `GATEWAY_WS_URL`.
- Certificate changes are picked up via the shared `TlsCertificateWatcher` so WebSocket clients can reload credentials without restarts.

The WebSocket client certificate must include the `clientAuth` extended key usage. This is intentionally decoupled from the proxy’s internal gRPC server certificate profile, which must include `serverAuth`.

TLS handshake failures are fail-closed. The proxy does not fall back to plaintext.

Shared and player-facing startup must validate every effective private key for an enabled TLS surface: the Telnet server identity (`TCP_PROXY_TLS_KEY`) in `DIRECT_TLS` mode, the Gateway WebSocket client identity, and the internal gRPC server identity. The same validation must run before player-facing admission and after certificate reload. If any applicable identity converges with another or cannot be verified, player admission is disabled and the proxy fails closed. The separate certificate chains must also satisfy their respective `clientAuth` and `serverAuth` profiles.

When overriding `GATEWAY_WS_URL` in a `wss://` configuration, the host portion of the URL is used for both SNI and hostname verification. If you point `GATEWAY_WS_URL` at an IP address or a hostname that is not present in the Gateway certificate SANs, the TLS handshake fails with `reason="cert_validation"` and no insecure fallback occurs. In cluster-internal deployments, prefer the Kubernetes DNS name for the Gateway service.

The local environment consequence is:

- Proxy -> Gateway gameplay traffic uses mTLS in all shared and player-facing environments.
- Shared and player-facing environments must use `wss://` to the internal-only Gateway mTLS listener; they must not serve player-facing traffic over `ws://`.
- Player-facing environments must fail startup or admission if Proxy -> Gateway mTLS identity verification is unavailable.
- Gateway trust uses exactly one profile from [ADR 0169](../../decisions/adr-0169-exclusive-environment-bound-tcp-proxy-trust.md). Steady-state player-facing deployments require an exact environment-specific URI SAN identity; DNS migration and fingerprint break-glass profiles are explicit and expiring, while insecure CIDR trust is development/test-only.

## Connection Limits and Abuse Protection

The cross-service abuse ownership split is defined in [Security](../../system-architecture-security.md#brute-force-defense-and-abuse-handling); these are the TCP Proxy-local hard ceilings.

Because the TCP Proxy Service sits in the network DMZ, it enforces hard resource ceilings even though richer abuse policies live in Spring Cloud Gateway and Game Session.

- A global concurrent connection cap prevents the proxy from exhausting sockets or file descriptors.
- A per-client-IP cap guards against a single address consuming the entire connection budget, but this cap is only as accurate as the observed client IP.
- In NAT-heavy environments it is acceptable to keep the per-IP cap relatively high or unset, but only when Spring Cloud Gateway rate limiting and Game Session per-IP/per-session quotas are enabled and monitored; do not run with both proxy per-IP limits and higher-layer quotas effectively disabled.
- Read idle timeouts and maximum connection lifetimes close connections that send no data or linger indefinitely.
- Maximum line-length constraints reject oversized lines without forwarding partial input.

The proxy’s connection caps, idle timeouts, and buffer depth limits are hard ceilings at the network edge. Gateway rate limiting and Game Session quotas remain higher-layer controls.

## Tuning TCP Proxy for Different Environments

The connection limits exposed via `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP` are intended to be tuned per environment.

The listed `TCP_PROXY_MCP_*` budgets are dormant target-only settings and are not consumed by the current runtime. Marker-looking lines are treated as opaque generic input and forwarded subject only to the proxy's live generic line-size, connection, idle, buffer, and per-IP connection limits; no MCP-specific budget/rate-limit enforcement or `reason="mcp_budget"` discard is live. The deprecated `TCP_PROXY_MCP_ENABLED` flag can trigger the implementation-drift greeting when enabled, but the **target** configuration/startup contract accepts `true` only in explicit local/development/test profiles and rejects it in shared, player-facing, and prod-like profiles; it remains `false`, disabled, and unadvertised by default. Focused profile-startup proof is required for this boundary; the current runtime does not yet prove the profile rejection.

The initial Proxy -> Gateway WebSocket bridge retry budget and input buffer depth (`TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` and `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES`) should be sized to match expected gateway availability characteristics and typical player command rates.

- `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` is historical naming only. Its canonical meaning is the initial-admission bridge-establishment window, not a recovery window for already-established Telnet sessions.
- `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` should usually stay in a bounded range such as `32` to `128`, balanced against memory use and the requirement that gameplay commands are not dropped silently while a connection remains open.
- If the buffer fills while upstream is reachable, the proxy closes the Telnet connection with `policy_violation`, emits `edge_backpressure` context, and increments `tcpproxy.telnet.discarded{reason="gateway_buffer_full"}` so buffer-driven disconnects appear explicitly in dashboards. If upstream is already unreachable, close with `backend_unavailable` instead.
- When tuning these values, watch `tcpproxy.websocket.reconnects`, `tcpproxy.websocket.reconnect.delay`, and the `gateway_buffer_full` breakdown of `tcpproxy.telnet.discarded`.

Bridge-availability circuit-breaker settings should also be tuned explicitly:

- `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` controls how long upstream gameplay unreachability must persist before the breaker opens.
- `TCP_PROXY_GATEWAY_CIRCUIT_HALF_OPEN_MAX_PROBES` controls how many concurrent bridge probe attempts are allowed while half-open.
- `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` controls how many consecutive successful half-open bridge establishments are required before returning to closed admission.
- Keep `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` aligned with `firemud.gateway.backendUnavailableGraceMs`.
- Keep `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` aligned with `firemud.gateway.backendUnavailableRecoverySuccessCount`.

Startup must fail fast when `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS <= 0`, `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS <= 0`, or `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT <= 0`.

### Recommended Dev Defaults

- `TCP_PROXY_MAX_CONNECTIONS=50`
- `TCP_PROXY_MAX_CONNECTIONS_PER_IP=10`
- `ws://` targets for `GATEWAY_WS_URL` are acceptable in local or Docker Compose setups

### Minimum Viable Prod Hardening

- Size `TCP_PROXY_MAX_CONNECTIONS` to expected concurrent players plus safety margin.
- Set `TCP_PROXY_MAX_CONNECTIONS_PER_IP=3` to `5` so individual IPs cannot exhaust the connection pool while still allowing multiple windows per player.
- In NAT-heavy or carrier-grade-NAT environments, prefer a higher per-IP cap or even no hard per-IP cap only when Gateway and Game Session quotas remain enabled and monitored.
- Require `wss://` with mutual TLS for `GATEWAY_WS_URL`.

### Heavier Deployments

- Scale the proxy horizontally and keep per-pod limits moderate rather than pushing a single instance to extreme totals.
- Treat sustained increases in `tcpproxy.connections.limit.exceeded` and `tcpproxy.telnet.discarded` as signals to add capacity or block or throttle specific abusive IP ranges at the network or gateway layer.
