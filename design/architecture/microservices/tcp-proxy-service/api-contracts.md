# TCP Proxy Service API Contracts

## Target Reconnect Contract

After actual edge loss, the Telnet transport carries the owner-defined `WORLDS` -> credential-bearing `LOGIN` -> authenticated `REALMS` -> conditional `JOIN` -> conditional `CHARS`/character creation when required -> `PLAY` sequence. The proxy may carry the owner-defined conditional `JOIN`, which may create missing or `INACTIVE` public membership only when `allowPublicJoin=true`, a fresh positive applicable entitlement result, and the canonical fresh authority/membership checks all pass; an unavailable or negative gate fails closed with no mutation. `PLAY` requires existing `ACTIVE` membership. The proxy neither decides nor mutates membership. [Authentication](../../system-architecture-authentication.md#direct-text-realms-to-join-scope-normative) owns credential, membership, entitlement, and target-admission policy; [Game Session](../game-session-service/protocols.md) owns command-front-door handling, command acceptance, and gameplay binding. This document records only the transport consequence. The proxy does not replay input, frames, bytes, or MCP traffic onto the new transport. Gateway owns the top-level close taxonomy, and a close reason never proves whether a command committed.

## Implementation Status

Current proxy routing and receiving-service behavior support an abbreviated returning-member `LOGIN` -> `PLAY` path when current membership and character gates pass. Current proxy routing still bootstraps a hidden default and does not enforce `WORLDS` as a prerequisite; the live Account adapter does not expose lifecycle state, so missing and `INACTIVE` membership cannot be distinguished by the current implementation. `NotifyDisconnect` remains a best-effort, at-least-once advisory signal as described below.

## Service Interactions

The proxy does not expose a public client API. Instead it emits a gRPC event for internal coordination:

- **NotifyDisconnect** – informs the Game Session Service when a Telnet client drops so the session may be suspended. This exists primarily to provide a fast, correlatable liveness hint keyed by `proxyConnectionId` when Telnet sockets close, even if the proxy’s WebSocket bridge teardown or downstream close detection is delayed or ambiguous during restarts.

These events let the Game Session Service resume suspended sessions and resume processing of any Redis-backed gameplay command queues it owns. They do not authorize replay of prior outbound transport bytes onto a new client socket. The TCP Proxy never replays Telnet input after a disconnect; connection-local buffers are cleared as soon as the TCP session closes. `NotifyDisconnect` is therefore a best-effort, at-least-once lifecycle signal keyed by `{proxyConnectionId, disconnectSequence}` rather than a request to re-run gameplay commands.

The proxy is an edge component, not the owner of resumable gameplay state or external close translation. One live Telnet socket owns one established inbound TCP Proxy -> Gateway WebSocket bridge. Under [ADR 0013](../../decisions/adr-0013-bounded-invisible-non-edge-restart-recovery.md), Gateway may retain that inbound bridge while replacing or rebinding its distinct outbound Gateway -> Game Session connection; successful bounded rebind is invisible to the Telnet client. If the TCP Proxy -> Gateway bridge itself drops after establishment, the proxy closes the live Telnet socket and never opens a replacement bridge behind it. The proxy preserves no authoritative gameplay state for Gateway's upstream recovery. If safe backend recovery cannot complete within the configured positive `firemud.gateway.backendUnavailableGraceMs` grace window, capped at 30,000 ms, Gateway closes with `backend_unavailable` and the proxy surfaces the corresponding explicit Telnet disconnect rather than keeping an indefinitely stalled TCP session. No input, frame, byte, or MCP replay occurs. Gateway owns the top-level close taxonomy, and a close reason never proves whether a command committed.

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

The proxy generates `proxyConnectionId` when the Telnet socket is accepted and uses one stable identifier for the lifetime of that TCP connection. Every initial WebSocket bridge-establishment handshake attempt while that Telnet socket is still alive re-sends the same `proxyConnectionId` as `X-Proxy-Connection-Id`; once a bridge has been established, losing it fails the Telnet session closed rather than starting another handshake behind the live socket.

The human-facing Telnet post-edge path is fresh public `WORLDS` before `LOGIN`, authenticated `REALMS` after `LOGIN`, and target-only conditional `JOIN` for public-production membership. With fresh public-join policy enabled (`allowPublicJoin=true`), missing or `INACTIVE` membership transitions to `ACTIVE` and returns the authoritative snapshot; an existing `ACTIVE` membership returns the exact current snapshot idempotently. With public joining disabled, `JOIN` returns `PUBLIC_PRODUCTION_ADMISSION_DENIED` without mutation. Before target `JOIN` or `PLAY`, the path requires a fresh positive entitlement result in addition to applicable membership, public-policy, and realm-grant evidence; entitlement failure fails closed and must not mutate membership. The path then performs conditional `CHARS`/allowed character creation when no valid selected character is already resolved, and then `PLAY`. The current returning-member compatibility behavior may use the abbreviated `LOGIN` -> `PLAY` path after the receiving service confirms current membership and character gates; it does not replace the target fresh-discovery sequence. Typed `SESSION` lines are no longer part of the Telnet contract.

The proxy may still forward `X-Proxy-Game-Instance-Id` and `X-Proxy-Tenant-Id` when those values come from server-owned defaults or future hidden MCP-carried smart-client metadata. Spring Cloud Gateway strips these from public ingress, emits `X-Firemud-Connection-Mode: trusted_tcp_proxy` on authenticated TCP Proxy bridge hops, and may forward canonical `X-Game-Instance-Id` / `X-Tenant-Id` headers only after authenticating the TCP Proxy identity. These headers remain advisory only and must never bypass `LOGIN` + `PLAY`.

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
- The `tcpproxy.disconnect.notify.transport_failure` and `tcpproxy.disconnect.notify.app_error` meters remain bounded-label metrics only. Correlation to specific `{proxyConnectionId, disconnectSequence}` values belongs in structured logs and traces rather than metric labels.

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
