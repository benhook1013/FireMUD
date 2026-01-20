# FireMUD Telnet Path Degraded Runbook

This runbook covers operational steps when the **Telnet path** (TCP Proxy Service and related components) is degraded or failing.

For the design of the Telnet and protocol-bridging path, see:

- `design/architecture/system-architecture-mud-client-protocol.md`
- `design/architecture/system-architecture-protocol-bridging.md`

## Symptoms

- Players using Telnet cannot connect or experience frequent disconnects.
- Metrics show elevated errors or latency on the TCP Proxy Service or protocol-bridging components.

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
     - `tcpproxy.telnet.discarded` for spikes that may reflect malformed Telnet sequences, buffer overflows, or repeated malformed `SESSION` envelopes.
     - `tcpproxy.websocket.reconnects` and `tcpproxy.websocket.reconnect.delay` for repeated reconnection attempts to Spring Cloud Gateway.
     - `tcpproxy.tls.misconfig` and `tcpproxy.gateway.handshake.failures{reason=...}` for TLS/mTLS configuration issues.
     - If Telnet client IP preservation relies on PROXY protocol, verify that `tcpproxy.telnet.discarded{reason="proxy_protocol"}` is not elevated; sustained `proxy_protocol` discard reasons often indicate a misconfigured Telnet edge proxy (for example PROXY headers sent to the wrong listener or malformed headers).
4. **Compare Telnet vs WebSocket flows**
   - Pick a specific `{sessionId, tenantId}` (or user) and:
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
     - Verify `FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, and `FIREMUD_GRPC_CA_CERT_PATH` are valid and mounted in the proxy deployment.
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
