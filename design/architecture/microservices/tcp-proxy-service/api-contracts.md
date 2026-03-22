# TCP Proxy Service API Contracts

## Service Interactions

The proxy does not expose a public client API. Instead it emits a gRPC event for internal coordination:

- **NotifyDisconnect** – informs the Game Session Service when a Telnet client drops so the session may be suspended. This exists primarily to provide a fast, correlatable liveness hint keyed by `proxyConnectionId` when Telnet sockets close, even if the proxy’s WebSocket bridge teardown or downstream close detection is delayed or ambiguous during restarts.

These events let the Game Session Service resume suspended sessions and resume processing of any Redis-backed gameplay command queues it owns. They do not authorize replay of prior outbound transport bytes onto a new client socket. The TCP Proxy never replays Telnet input after a disconnect; connection-local buffers are cleared as soon as the TCP session closes. `NotifyDisconnect` is therefore a best-effort, at-least-once lifecycle signal keyed by `{proxyConnectionId, disconnectSequence}` rather than a request to re-run gameplay commands.

When a `NotifyDisconnect` call fails with a transport-level error, the proxy retries it with a short, bounded exponential backoff window:

- `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS` bounds the total retry window after Telnet socket close.
- Within that window, the proxy applies exponential backoff between attempts and gives up permanently once the total elapsed time since Telnet socket close exceeds the configured window.
- After the window elapses, the proxy relies entirely on Game Session’s own liveness detection and Redis-backed timeouts.

When the gRPC transport returns `OK` but the `NotifyDisconnectResponse.error` field is populated, the proxy treats most codes as permanent contract-level failures and does not retry. The exception is `RESOURCE_EXHAUSTED`, which is treated as temporary app-level overload and may be retried within `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS`.

The proxy must log all app errors, increment bounded app-error metrics, and avoid inventing arbitrary high-cardinality codes. More detailed context belongs in logs and traces rather than in the canonical error-code surface.
`RESOURCE_EXHAUSTED` is the only retryable application-level code. Codes such as `INVALID_ARGUMENT`, `NOT_FOUND`, `PERMISSION_DENIED`, and `FAILED_PRECONDITION` remain permanent outcomes for that event.

### `NotifyDisconnect` Error Codes

| Code | Meaning | Retry behaviour |
| --- | --- | --- |
| `OK` | Event accepted and processed successfully. The Game Session implementation may set this explicitly, and the TCP Proxy normalizes responses that omit an `error` field to `OK` so callers always see a concrete code. | Never retried. |
| `INVALID_ARGUMENT` | Request was structurally or semantically invalid. | Permanent contract failure; do not retry. |
| `NOT_FOUND` | Target session or game instance was not found or has already been cleaned up. | Permanent contract failure; do not retry. |
| `PERMISSION_DENIED` | Caller identity is authenticated but not authorized for the target event sink/session scope. | Permanent contract failure; do not retry. |
| `FAILED_PRECONDITION` | Event shape is valid but the target session state cannot accept it right now. | Permanent contract failure for that event; do not retry. |
| `RESOURCE_EXHAUSTED` | Consumer-side capacity guardrail hit while processing disconnect hints. | Temporary overload; retry only within `TCP_PROXY_NOTIFY_DISCONNECT_MAX_RETRY_MS`. |

Additional codes may be introduced later, but they must remain few in number and be added here when introduced.

### Correlation and Header Contract

The proxy generates `proxyConnectionId` when the Telnet socket is accepted and uses one stable identifier for the lifetime of that TCP connection. Every WebSocket bridge handshake attempt initiated while that same Telnet socket is still alive re-sends the same `proxyConnectionId` as `X-Proxy-Connection-Id`.

The canonical `SESSION` envelope contract lives in [`protocols.md`](./protocols.md#telnet-session-envelope-and-event-metrics). In particular:

- the proxy captures at most one `SESSION` envelope before the first forwarded non-`SESSION` line;
- Telnet negotiation and MCP control traffic participate in that attach-hint window as documented there;
- malformed `SESSION` lines are advisory and budgeted, not immediate transport failures; and
- `SESSION` values are client-provided claims, not trusted facts.

When a valid `SESSION <gameInstanceId> <tenantId>` envelope is captured, the proxy also forwards `X-Proxy-Game-Instance-Id` and `X-Proxy-Tenant-Id`. Spring Cloud Gateway strips these from public ingress, emits `X-Firemud-Connection-Mode: trusted_tcp_proxy` on authenticated TCP Proxy bridge hops, and may forward canonical `X-Game-Instance-Id` / `X-Tenant-Id` headers only after authenticating the TCP Proxy identity.

Client-IP ownership follows the same pattern: the proxy recovers the real client IP via PROXY protocol on the internal-only listener, sets `X-Proxy-Client-IP` on the authenticated internal bridge, and Gateway canonicalizes that into `X-Client-IP` only after authenticating the proxy identity. Public ingress must never be allowed to set these proxy-owned headers directly.

Game Session must treat `NotifyDisconnect` as strictly advisory and idempotent:

- If a `NotifyDisconnect` arrives after the session has already been rebound to a new socket, the event is ignored for state changes.
- Missing events are tolerated because disconnects are also detected at the Gateway and Game Session layers.
- Duplicate events for the same `{proxyConnectionId, disconnectSequence}` and older `disconnectSequence` values for a given `proxyConnectionId` are handled without side effects.

## Failure Modes and Expectations

- Loss or delay of `NotifyDisconnect` events must never leave players stuck logged in or unable to resume; Game Session is responsible for independently detecting liveness via WebSocket/TCP close and Redis-backed timeouts.
- Retries and at-least-once delivery should only increase duplication of advisory events, not change observable gameplay behavior.
- In practice, losing events at this layer should only slow down session cleanup or metrics accuracy slightly, not introduce new correctness states.

## Logging and Correlation

For operators and developers, `NotifyDisconnect` is designed to be easy to correlate end-to-end:

- The TCP Proxy logs Telnet socket lifecycle events with a stable `proxyConnectionId` field, along with connection-level tags such as `tenantId`, `gameInstanceId` when known, and client IP or `X-Proxy-Client-IP`.
- Spring Cloud Gateway propagates `X-Proxy-Connection-Id` only on authenticated TCP Proxy -> Gateway hops and strips it from public ingress, then produces a canonical `X-Client-IP` header.
- Game Session logs login, resume, takeover, and disconnect-processing events using the same `proxyConnectionId` and `{gameInstanceId, tenantId}` values captured during session binding.
- The `tcpproxy.disconnect.notify.transport_failure` and `tcpproxy.disconnect.notify.app_error` meters reference `{proxyConnectionId, disconnectSequence}` so failures and retries can be tied back to specific Telnet connections and Game Session events.

Their definitions live in [`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

## REST and gRPC Endpoints

### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check.
- `NotifyDisconnect(NotifyDisconnectRequest) returns (NotifyDisconnectResponse)` – implemented by the Game Session Service as an internal-only event sink that the TCP Proxy Service calls when a Telnet client disconnects; it is not exposed as a TCP Proxy inbound RPC.

All RPC definitions live in [`tcp_proxy_service.proto`](../../../../protos/tcp-proxy/v1/tcp_proxy_service.proto).

```bash
grpcurl -cacert "$FIREMUD_GRPC_CA_CERT_PATH" \
  -cert "$FIREMUD_GRPC_CERT_CHAIN_PATH" \
  -key "$FIREMUD_GRPC_PRIVATE_KEY_PATH" \
  localhost:6565 tcp_proxy.v1.TcpProxyService/Ping
```
