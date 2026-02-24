# FireMUD Telnet Path Degraded Runbook

This runbook covers operational steps when the **Telnet path** (TCP Proxy Service and related components) is degraded or failing.

For the design of the Telnet and protocol-bridging path, see:

- `design/architecture/system-architecture-mud-client-protocol.md`
- `design/architecture/system-architecture-protocol-bridging.md`

## Symptoms

- Players using Telnet cannot connect or experience frequent disconnects.
- Metrics show elevated errors or latency on the TCP Proxy Service or protocol-bridging components.
- In some incidents, Web clients may remain healthy while Telnet is degraded; in others, WebSocket connectivity may degrade independently of Telnet. This runbook focuses on the Telnet path but includes a brief checklist for Web-only WebSocket issues so operators can quickly compare paths.

## Triage

1. **Confirm Scope**
   - Compare behavior between Telnet and Web client paths.
   - Check Gateway and Game Session metrics to ensure the core gameplay path is healthy.
2. **Inspect TCP Proxy Service**
   - Verify pod health and logs for network or protocol errors.
   - Ensure configuration for upstream endpoints (Gateway, Game Session) has not changed unexpectedly.
3. **Check TCP Proxy metrics**
   - Open the TCP Proxy Grafana dashboard and inspect:
     - `tcpproxy.connections.active` / `tcpproxy.connections.total` for unusual spikes or drops.
     - `tcpproxy.connections.limit.exceeded` for sustained non-zero values, which indicate global or per-IP caps are rejecting new connections.
     - `tcpproxy.telnet.discarded` for spikes that may reflect malformed Telnet sequences, buffer overflows, repeated malformed `SESSION` envelopes, or upstream backpressure that forces the proxy to close connections (for example `tcpproxy.telnet.discarded{reason="gateway_buffer_full"}` when the Telnet → Gateway input buffer reaches its configured ceiling).
     - `tcpproxy.websocket.reconnects` and `tcpproxy.websocket.reconnect.delay` for repeated Proxy → Gateway WebSocket bridge connection attempts and backoff delays (these correlate with Telnet disconnects because the proxy fail-closes when it cannot maintain the bridge).
     - `tcpproxy.tls.misconfig` and `tcpproxy.gateway.handshake.failures{reason=...}` for TLS/mTLS configuration issues.
     - If Telnet client IP preservation relies on PROXY protocol, verify that `tcpproxy.telnet.discarded{reason="proxy_protocol"}` is not elevated; sustained `proxy_protocol` discard reasons often indicate a misconfigured Telnet edge proxy (for example PROXY headers sent to the wrong listener or malformed headers).
4. **Compare Telnet vs WebSocket flows**
   - Pick a specific `{gameInstanceId, tenantId}` (or user) when available and:
     - Use Logging & Admin Service / Kibana to find the Telnet-side logs (from the TCP Proxy) and confirm that `LOGIN`/`LOOK` commands are received, with credentials redacted.
     - Find the corresponding WebSocket session in Spring Cloud Gateway logs and the downstream Game Session logs to verify whether the commands reach the backend and whether responses are emitted.
   - If WebSocket flows succeed while Telnet flows stall or drop, the problem is likely in the TCP Proxy, Gateway WebSocket route, or mTLS between them.

## Remediation

1. **Evaluate connection caps vs abusive clients**
   - If `tcpproxy.connections.limit.exceeded` is elevated and many IPs are affected:
     - Consider temporarily raising `TCP_PROXY_MAX_CONNECTIONS` and/or `TCP_PROXY_MAX_CONNECTIONS_PER_IP` for the affected environment and redeploying the proxy.
     - Watch the same metrics after the change to confirm the limits are no longer frequently hit.
   - If the metric is dominated by a small number of IPs:
     - Treat those IPs as abusive or misconfigured clients; prefer blocking or throttling them via firewall rules, ingress rules, or specific rate-limiter policies rather than raising global limits.

2. **Check WebSocket bridge and TLS configuration**
   - If `tcpproxy.websocket.reconnects` and `tcpproxy.gateway.handshake.failures{reason="cert_validation"}` increase:
     - Confirm `GATEWAY_WS_URL` points to a hostname that matches the Gateway certificate SANs.
     - Verify `FIREMUD_GATEWAY_WS_CLIENT_CERT_CHAIN_PATH`, `FIREMUD_GATEWAY_WS_CLIENT_PRIVATE_KEY_PATH`, and `FIREMUD_GATEWAY_WS_CA_CERT_PATH` are valid and mounted in the proxy deployment.
     - If needed, roll back recent TLS or gateway changes and reapply them with correct hostnames and certificate bundles.
   - If Telnet client IP-related behaviour looks incorrect (for example, per-IP limits clearly not matching real client IPs, or logs showing node/LoadBalancer IPs as the client address), validate the PROXY protocol deployment:
     - Confirm that the public Telnet `LoadBalancer` fronts a dedicated Telnet edge proxy (for example HAProxy) and that it forwards to the TCP Proxy Service using PROXY protocol on the internal-only listener/port configured by `TCP_PROXY_PROXY_PROTOCOL_PORT`.
     - Ensure the raw Telnet listener (`TCP_PROXY_PORT`) is not PROXY-enabled and is not exposed directly on the Internet in production; accepting PROXY headers from public clients allows client-IP spoofing.
     - When PROXY protocol is not enabled (or source IP is not preserved), treat `TCP_PROXY_MAX_CONNECTIONS_PER_IP` as a best-effort heuristic and rely primarily on global `TCP_PROXY_MAX_CONNECTIONS` and higher-layer rate limits, as described in the TCP Proxy design and Deployment Environments docs.

3. **Run Telnet smoke tests**
   - Use the Telnet smoke script described in the TCP Proxy README (or the `dev-echo-loop.sh` flow) to:
     - Connect to the proxy with `telnet` or a test client.
     - Send `SESSION` + `LOGIN` + `LOOK` and confirm that responses match the WebSocket path for the same account.
     - Capture the raw transcript and include it in incident notes.

4. **Escalation and mitigation**
   - If Telnet is degraded but WebSocket is healthy and the root cause is not immediately fixable:
     - Communicate to players that Telnet may be unreliable and recommend the Web client as a temporary workaround.
     - Track the incident and any config changes in the Logging & Admin Service / runbook history so future investigations can correlate behavioral changes with deployment events.

## Buffer, Slow-Client, and WebSocket-Specific Considerations

The TCP Proxy Service and WebSocket path enforce backpressure rather than silently dropping gameplay lines; understanding how this shows up in metrics and user reports helps distinguish client issues from server-side regressions:

- **TCP Proxy output buffer and slow Telnet clients**
  - When a Telnet client cannot keep up with outbound traffic (for example due to a very slow terminal or network), the proxy’s per-socket output buffer can fill. In this case the proxy **closes the Telnet connection with a clear message** instead of silently discarding lines mid-stream. Expect to see:
    - Elevated `tcpproxy.telnet.discarded` with reasons related to buffer or rate ceilings.
    - Short-lived connections from the same IP repeatedly hitting the same limits.
  - If this pattern affects many well-behaved players simultaneously, treat it as a configuration or gameplay-output problem (for example an excessively verbose broadcast) and consider:
    - Temporarily relaxing per-socket output limits in the affected environment while you reduce output volume or fix the regression.
    - Coordinating with game designers to avoid bursty, unbounded broadcast patterns that overwhelm slow clients.
  - If the pattern is confined to a handful of IPs, treat it as client-side slowness or abuse and prefer blocking or rate limiting those sources rather than relaxing global limits.

- **WebSocket path checks when Web clients are degraded**
  - When Web clients (browser or other WebSocket-based tools) experience frequent disconnects or stalled gameplay while Telnet remains healthy:
    - Verify that the `/ws/game/**` route is still present and correctly configured in Spring Cloud Gateway (route predicates, filters, and target Service).
    - Check Gateway metrics for elevated connection churn or WebSocket send failures on the gameplay route, and correlate with Game Session metrics to ensure backend pods are healthy. In particular, distinguish:
      - **Slow-client / policy enforcement** at the gateway (for example `gateway.websocket.slow_client_closes` or close-reason metrics dominated by `1008` / `policy_violation`), which typically point to individual misbehaving or very slow Web clients.
      - **`backend_unavailable` conditions** (for example close-reason metrics dominated by `1013` / `backend_unavailable` and matching Game Session health issues), which indicate that Web clients are being dropped because gameplay backends are unavailable rather than because of client behaviour.
    - Confirm that any load balancer or CDN in front of the gateway is not terminating idle WebSocket connections more aggressively than the gateway’s own WebSocket idle timeout and ping/pong interval described in [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts).
  - WebSocket send failures and slow-client issues should result in the connection being closed rather than frames being silently dropped. If you observe symptoms that look like partial updates or missing messages without disconnects, treat this as a bug in the gateway or Game Session implementation and open an incident referencing this runbook and [Protocol Bridging](./system-architecture-protocol-bridging.md#backpressure--slow-clients).

- **Telnet vs Web-only incidents**
  - If Telnet is degraded while Web remains healthy, this runbook plus the TCP Proxy design should be your primary reference.
  - If Web is degraded while Telnet remains healthy, start with the WebSocket checklist above and Spring Cloud Gateway documentation; treat the Telnet path as a known-good control when forming hypotheses and testing fixes.

When inspecting Telnet-side disconnects, prefer reasoning in terms of the standard Telnet disconnect reasons defined in [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-disconnect-reasons) rather than ad-hoc message strings. In particular:

- Treat `policy_violation` closes as non-retriable or very low-rate retriable; they usually indicate client behaviour that must change (scripts, bots, abusive traffic) rather than platform outages.
- Treat `backend_unavailable` closes as indicators of core gameplay outages or edge-to-gateway bridge failures, comparable to WebSocket `1013` (`backend_unavailable`) from the gateway, and prioritise checking Game Session, Redis, and Gateway health (including whether the Telnet → Gateway buffer is filling, as indicated by `tcpproxy.telnet.discarded{reason="gateway_buffer_full"}`) before tuning Telnet limits.
- Treat `idle_timeout` and `logout` as expected lifecycle noise rather than indicators of incidents unless volumes spike unexpectedly.

### Web-Only WebSocket Degradation Playbook

When Web clients are degraded and Telnet remains healthy, use this focused checklist in addition to the general guidance above:

1. **Scope and comparison**
   - Confirm that Telnet sessions via the TCP Proxy can still log in, issue `LOOK`/`SAY`, and receive responses.
   - Capture a small set of affected Web client sessions (for example by `sessionId` or account) and compare their behaviour with Telnet for the same accounts.
2. **Gateway `/ws/game/**` health**
   - Inspect Gateway metrics for the gameplay route: connection churn, WebSocket handshake failures, send-timeout or close reasons, and any route-level errors.
   - Verify that recent configuration changes to routes, filters, or mTLS listeners (for example the internal-only WebSocket listener used by `GATEWAY_WS_URL`) have been rolled out as expected.
3. **Idle timeout and heartbeat alignment**
   - Check that the gateway’s configured WebSocket idle timeout and ping interval match the expectations in [Gateway Architecture](./system-architecture-gateway.md#websocket-liveness-and-idle-timeouts).
   - Confirm that upstream load balancers, CDNs, or service meshes are not closing idle connections earlier than the gateway’s own timeouts.
4. **Game Session impact**
   - Compare Game Session metrics (per-session queues, command throughput, error rates) for WebSocket-only sessions versus Telnet-bridged sessions.
   - If WebSocket sessions are consistently being closed due to backpressure or timeouts while Telnet remains within limits, treat this as a configuration or capacity issue on the WebSocket path and adjust limits or scale out as needed.
5. **Mitigation**
   - If Web-only issues cannot be resolved quickly, communicate a temporary recommendation for affected players to use Telnet where appropriate, and record any gateway/config changes made during mitigation so they can be correlated with behaviour changes in future incidents.

### Stalled Backend and Partial-Disconnect Symptoms

Some failures present as **“connection alive, commands accepted, but no responses”** from the player’s perspective. When Telnet remains connected but multiple players report that commands like `LOOK` or `SAY` stop producing output while metrics show Game Session or Redis under stress:

- Treat this pattern as a likely **backend or tick-runtime degradation**, not just a Telnet problem.
- Correlate with the tick and Redis runbooks – in particular [Tick Failures & Operations](./system-architecture-tick-failures-and-operations.md) and [Redis Operations](./system-architecture-redis-operations.md) – to determine whether regions are marked degraded, timers are over budget, or Redis latency is elevated.
- Avoid compensating at the Telnet layer (for example by greatly increasing buffers) when the root cause is stalled ticks or overloaded Redis; prefer relieving backend pressure or reducing gameplay output volume.
