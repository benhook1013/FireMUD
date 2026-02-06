# TCP Proxy Grafana & Alertmanager Snippets (Reference)

These snippets provide example PromQL expressions and Alertmanager rules for the TCP Proxy metrics. They are intended as **reference templates** for operators customizing dashboards and alerts; adjust thresholds and labels per environment.

## Example Grafana Panels (PromQL)

- **Active Telnet connections**

  ```promql
  max_over_time(tcpproxy_connections_active[5m])
  ```

- **Connection limit exceeded rate**

  ```promql
  rate(tcpproxy_connections_limit_exceeded[5m])
  ```

- **Discarded Telnet input (negotiation / malformed / overflow)**

  ```promql
  rate(tcpproxy_telnet_discarded[5m])
  ```

- **WebSocket reconnect rate to Spring Cloud Gateway**

  ```promql
  rate(tcpproxy_websocket_reconnects[5m])
  ```

## Example Alertmanager Rules (YAML)

These can be added to an Alertmanager rule file and imported into the existing ruleset.

```yaml
groups:
  - name: tcp-proxy.rules
    rules:
      - alert: TcpProxyConnectionLimitsExceeded
        expr: rate(tcpproxy_connections_limit_exceeded[5m]) > 0
        for: 10m
        labels:
          severity: P2
          service: tcp-proxy-service
          owner: platform
          runbook: design/architecture/system-architecture-telnet-degraded-runbook.md#triage
        annotations:
          summary: "TCP Proxy connection limits exceeded"
          description: |
            TCP Proxy has rejected Telnet connections due to global or per-IP caps
            for at least 10 minutes.
            Check TCP_PROXY_MAX_CONNECTIONS and TCP_PROXY_MAX_CONNECTIONS_PER_IP,
            and inspect tcpproxy.connections.* panels to distinguish normal load
            from abusive clients.

      - alert: TcpProxyTelnetDiscardSpike
        expr: rate(tcpproxy_telnet_discarded[5m]) > 5
        for: 5m
        labels:
          severity: P2
          service: tcp-proxy-service
          owner: platform
          runbook: design/architecture/system-architecture-telnet-degraded-runbook.md#triage
        annotations:
          summary: "Spike in discarded Telnet input"
          description: |
            TCP Proxy is discarding Telnet input at an elevated rate (>5/s over 5m).
            This often indicates malformed Telnet negotiation sequences, repeated
            malformed SESSION envelopes, or misbehaving clients.
            Inspect logs for offending IPs and consider blocking abusive sources
            or tightening rate limits.

      - alert: TcpProxyGatewayReconnectsHigh
        expr: rate(tcpproxy_websocket_reconnects[5m]) > 1
        for: 5m
        labels:
          severity: P1
          service: tcp-proxy-service
          owner: platform
          runbook: design/architecture/system-architecture-telnet-degraded-runbook.md#triage
        annotations:
          summary: "TCP Proxy frequently reconnecting to Gateway"
          description: |
            The Telnet WebSocket bridge is reconnecting to Spring Cloud Gateway more
            than once per second on average over 5 minutes.
            Verify GATEWAY_WS_URL, TLS/mTLS configuration, and Gateway health.

      - alert: TcpProxyNotifyDisconnectFailures
        expr: rate(tcpproxy_disconnect_notify_failure[5m]) > 0
        for: 10m
        labels:
          severity: P1
          service: tcp-proxy-service
          owner: platform
          runbook: design/architecture/system-architecture-telnet-degraded-runbook.md#stalled-backend--partial-disconnect-symptoms
        annotations:
          summary: "TCP Proxy NotifyDisconnect failures observed"
          description: |
            The TCP Proxy is seeing sustained failures when calling the Game Session
            Service NotifyDisconnect event sink. Inspect grpc.app_error{code=...}
            and Game Session logs to distinguish transient transport issues from
            contract or authorization errors.

      - alert: TcpProxyGrpcAppErrorSpike
        expr: sum by (code) (rate(grpc_app_error{service="tcp-proxy-service"}[5m])) > 1
        for: 10m
        labels:
          severity: P1
          service: tcp-proxy-service
          owner: platform
          runbook: design/architecture/system-architecture-telnet-degraded-runbook.md#stalled-backend--partial-disconnect-symptoms
        annotations:
          summary: "Spike in gRPC app errors on TCP Proxy paths"
          description: |
            Application-level gRPC errors are elevated for one or more codes on
            TCP Proxy related RPCs (such as NotifyDisconnect). Use the TCP Proxy
            dashboard grpc_app_error panel and correlated Game Session logs to
            identify misconfigurations or schema/contract issues.
```

These expressions assume that Micrometer has exported the TCP Proxy meters using the default naming conventions (e.g., `tcpproxy.connections.active` → `tcpproxy_connections_active`, `tcpproxy.disconnect.notify.failure` → `tcpproxy_disconnect_notify_failure`, `grpc.app_error` → `grpc_app_error`). Adjust names if your Prometheus setup uses different naming rules or additional labels.
