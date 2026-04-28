# TCP Proxy Service Configuration

## Minimal Production Configuration Checklist

For any shared or player-facing environment, operators should ensure at least:

- `GATEWAY_WS_URL` points at the Spring Cloud Gateway WebSocket mTLS listener (`wss://.../ws/game`), with `FIREMUD_GATEWAY_WS_*` variables configured so the proxy both authenticates the gateway and presents its own client certificate.
- `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP` are set to non-zero values sized for expected load and NAT patterns; the `0` defaults are reserved for local/dev and CI.
- In all shared and player-facing environments, Telnet is fronted by a Telnet edge proxy with PROXY protocol enabled into `TCP_PROXY_PROXY_PROTOCOL_PORT`; the PROXY-protocol listener remains internal-only and is never exposed directly as a public `LoadBalancer` port.
- Plaintext Telnet on `TCP_PROXY_PORT` is treated as a legacy channel governed by the Telnet hardening rules in the Security Architecture, and TLS Telnet plus the web client are preferred for general use.
- When `FIREMUD_AUTH_REQUIRE_2FA_FOR_PLAINTEXT_TCP` is enabled, logins over the raw plaintext Telnet port are permitted only for accounts that both have TOTP-based two-factor authentication enabled and explicitly opt in to allow plaintext Telnet login in account settings.
- Plaintext Telnet connections should trigger the canonical landing-menu warning recommending the TLS Telnet port or web client instead.

## TLS and Trust Surfaces

The TCP Proxy Service participates in three distinct TLS and trust boundaries:

| Surface | Direction | Purpose | Key configuration |
| --- | --- | --- | --- |
| Telnet plaintext or Telnet-over-TLS | Client <-> TCP Proxy Service | Player Telnet connections from legacy MUD clients. | `TCP_PROXY_PORT`, `TCP_PROXY_TLS_ENABLED`, `TCP_PROXY_TLS_PORT`, `TCP_PROXY_TLS_CERT`, `TCP_PROXY_TLS_KEY` |
| WebSocket mTLS bridge | TCP Proxy Service <-> Spring Cloud Gateway | Internal WebSocket hop that normalizes Telnet traffic into the same `/ws/game/**` route used by web clients. | `GATEWAY_WS_URL`, `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, `FIREMUD_GATEWAY_WS_CA_CERT_PATH` |
| Internal gRPC mTLS | Internal clients <-> TCP Proxy Service | Internal-only gRPC endpoints such as `Ping`. | `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH` |

Telnet-over-TLS and WebSocket mTLS may reuse the same certificate files in very small deployments, but they represent different trust surfaces and should be managed as separate concerns in production.
In production and other shared environments, operators should provision separate certificates and keys per surface and override defaults accordingly so a compromise in one trust surface does not automatically extend to the others.

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
| `TCP_PROXY_PORT` | TCP port the proxy listens on | `2323` |
| `TCP_PROXY_PROXY_PROTOCOL_PORT` | TCP port for the PROXY-protocol Telnet listener; internal-only and reachable only from the Telnet edge proxy | `2325` |
| `GATEWAY_WS_URL` | WebSocket URL for forwarding to the gateway; local Docker and test environments may use a plaintext `ws://` endpoint, but player-facing environments must set an explicit `wss://.../ws/game` target | *(none)* |
| `TCP_PROXY_TLS_ENABLED` | Enable Telnet-over-TLS termination | `false` |
| `TCP_PROXY_TLS_PORT` | TCP port for the Telnet-over-TLS listener | `2324` |
| `TCP_PROXY_TLS_CERT` | Path to the Telnet listener TLS certificate | *(empty)* |
| `TCP_PROXY_TLS_KEY` | Path to the Telnet listener TLS private key | *(empty)* |
| `TCP_PROXY_MAX_CONNECTIONS` | Maximum concurrent Telnet connections | `0` |
| `TCP_PROXY_MAX_CONNECTIONS_PER_IP` | Maximum concurrent Telnet connections per client IP | `0` |
| `TCP_PROXY_MAX_LINE_BYTES` | Maximum accepted Telnet/MCP line in bytes | `4096` |
| `TCP_PROXY_MAX_OVERSIZE_LINES` | Maximum oversized lines per connection before hard close | `10` |
| `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` | Maximum total retry time for failed `NotifyDisconnect` calls | `5000` |
| `TCP_PROXY_GATEWAY_RECONNECT_WINDOW_MS` | Initial-admission bridge-establishment window | `5000` |
| `TCP_PROXY_GATEWAY_CIRCUIT_OPEN_MS` | Circuit-breaker open threshold for upstream gameplay unreachability | `5000` |
| `TCP_PROXY_GATEWAY_CIRCUIT_HALF_OPEN_MAX_PROBES` | Maximum concurrent bridge probes while half-open | `3` |
| `TCP_PROXY_GATEWAY_CIRCUIT_RECOVERY_SUCCESS_COUNT` | Consecutive successful probes required to recover admission | `3` |
| `TCP_PROXY_GATEWAY_MAX_BUFFERED_LINES` | Maximum buffered Telnet lines waiting to be forwarded | `64` |
| `TCP_PROXY_MCP_NEGOTIATION_FAILURE_MAX` | Maximum MCP negotiation failures allowed per connection within the failure window | `5` |
| `TCP_PROXY_MCP_NEGOTIATION_FAILURE_WINDOW_MS` | MCP negotiation failure rolling window duration | `60000` |
| `TCP_PROXY_MCP_MAX_ACTIVE_CORDS` | Maximum concurrent MCP cords per connection | `16` |
| `TCP_PROXY_MCP_MAX_ACTIVE_DATA_TAGS` | Maximum concurrent MCP multiline `_data-tag` continuations per connection | `16` |
| `TCP_PROXY_MCP_MAX_CONTROL_LINES_PER_SEC` | Maximum MCP control-line processing rate per connection | `50` |
| `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` | Client certificate chain path for Proxy -> Gateway WebSocket mTLS | `certs/client.crt` |
| `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH` | Client private key path for Proxy -> Gateway WebSocket mTLS | `certs/client.key` |
| `FIREMUD_GATEWAY_WS_CA_CERT_PATH` | CA bundle path for verifying the gateway certificate | `certs/ca.crt` |
| `FIREMUD_GRPC_CERT_CHAIN_PATH` | Certificate chain path for the proxy’s internal gRPC server mTLS | `certs/client.crt` |
| `FIREMUD_GRPC_PRIVATE_KEY_PATH` | Private key path for the proxy’s internal gRPC server mTLS | `certs/client.key` |
| `FIREMUD_GRPC_CA_CERT_PATH` | CA bundle path for verifying gRPC peers | `certs/ca.crt` |
| `OTEL_ENDPOINT` | OpenTelemetry collector endpoint | `http://otel-collector:4317` |

## WebSocket mTLS to Spring Cloud Gateway

In production, the TCP Proxy Service connects to Spring Cloud Gateway over `wss://` using mutual TLS by dialing a dedicated internal-only Gateway WebSocket mTLS listener.

- Client certificate and key are loaded from `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH` and `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`.
- The Gateway’s certificate is validated against `FIREMUD_GATEWAY_WS_CA_CERT_PATH`, with hostname verification enabled using the host from `GATEWAY_WS_URL`.
- Certificate changes are picked up via the shared `TlsCertificateWatcher` so WebSocket clients can reload credentials without restarts.

The WebSocket client certificate must include the `clientAuth` extended key usage. This is intentionally decoupled from the proxy’s internal gRPC server certificate profile, which must include `serverAuth`.

TLS handshake failures are fail-closed. The proxy does not fall back to plaintext.

When overriding `GATEWAY_WS_URL` in a `wss://` configuration, the host portion of the URL is used for both SNI and hostname verification. If you point `GATEWAY_WS_URL` at an IP address or a hostname that is not present in the Gateway certificate SANs, the TLS handshake fails with `reason="cert_validation"` and no insecure fallback occurs. In cluster-internal deployments, prefer the Kubernetes DNS name for the Gateway service.

Environment policy is normative:

- Proxy -> Gateway gameplay traffic uses mTLS in all shared and player-facing environments.
- Shared and player-facing environments must use `wss://` to the internal-only Gateway mTLS listener; they must not serve player-facing traffic over `ws://`.
- Player-facing environments must fail startup or admission if Proxy -> Gateway mTLS identity verification is unavailable.

## Connection Limits and Abuse Protection

Because the TCP Proxy Service sits in the network DMZ, it enforces hard resource ceilings even though richer abuse policies live in Spring Cloud Gateway and Game Session.

- A global concurrent connection cap prevents the proxy from exhausting sockets or file descriptors.
- A per-client-IP cap guards against a single address consuming the entire connection budget, but this cap is only as accurate as the observed client IP.
- In NAT-heavy environments it is acceptable to keep the per-IP cap relatively high or unset, but only when Spring Cloud Gateway rate limiting and Game Session per-IP/per-session quotas are enabled and monitored; do not run with both proxy per-IP limits and higher-layer quotas effectively disabled.
- Read idle timeouts and maximum connection lifetimes close connections that send no data or linger indefinitely.
- Maximum line-length constraints reject oversized lines without forwarding partial input.

The proxy’s connection caps, idle timeouts, and buffer depth limits are hard ceilings at the network edge. Gateway rate limiting and Game Session quotas remain higher-layer policy controls.

## Tuning TCP Proxy for Different Environments

The connection limits exposed via `TCP_PROXY_MAX_CONNECTIONS` and `TCP_PROXY_MAX_CONNECTIONS_PER_IP` are intended to be tuned per environment.

MCP-specific budgets are intentionally softer than the hard-close Telnet safety limits: exceeding `TCP_PROXY_MCP_*` limits discards MCP control lines as `reason="mcp_budget"` while generally keeping the underlying Telnet connection open, unless the separate MCP negotiation-failure threshold is crossed.

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
