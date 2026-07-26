# Player Experience Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for player-centric SLO alerts. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

## Player Experience SLO Alerts

These example rules enforce the target-state player-centric SLOs defined in the Logging & Monitoring architecture doc. Thresholds and severities may be tuned per environment, but the underlying metric shapes should remain consistent once the producers and approved `scope` label are implemented.

```yaml
- alert: LoginSuccessRatioLowGateway
  expr: (
    sum by (scope) (rate(login_requests_total{service="spring-cloud-gateway", outcome="success"}[15m]))
      /
    sum by (scope) (rate(login_requests_total{service="spring-cloud-gateway"}[15m]))
  ) < 0.995
  for: 15m
  labels:
    service: spring-cloud-gateway
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Login success ratio below SLO
    description: Gateway login success ratio has fallen below 99.5% over the last 15 minutes.

- alert: LoginSuccessRatioLowTcpProxy
  expr: (
    sum by (scope) (rate(login_requests_total{service="tcp-proxy-service", outcome="success"}[15m]))
      /
    sum by (scope) (rate(login_requests_total{service="tcp-proxy-service"}[15m]))
  ) < 0.995
  for: 15m
  labels:
    service: tcp-proxy-service
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Login success ratio below SLO (TCP Proxy)
    description: TCP Proxy login success ratio has fallen below 99.5% over the last 15 minutes.

- alert: CommandLatencyP99HighGateway
  expr: histogram_quantile(
          0.99,
          sum by (service, scope, command, le) (
            rate(command_end_to_end_latency_ms_bucket{service="spring-cloud-gateway", command=~"move|look|combat"}[5m])
          )
        ) > 250
  for: 10m
  labels:
    service: spring-cloud-gateway
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Command p99 latency above SLO
    description: Gateway command end-to-end p99 latency has exceeded 250ms for at least one bounded core command. Preserve the `command` label so single-command regressions are not hidden by healthy higher-volume commands.

- alert: CommandLatencyP99HighTcpProxy
  expr: histogram_quantile(
          0.99,
          sum by (service, scope, command, le) (
            rate(command_end_to_end_latency_ms_bucket{service="tcp-proxy-service", command=~"move|look|combat"}[5m])
          )
        ) > 250
  for: 10m
  labels:
    service: tcp-proxy-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Command p99 latency above SLO (TCP Proxy)
    description: TCP Proxy command end-to-end p99 latency has exceeded 250ms for at least one bounded core command. Preserve the `command` label so single-command regressions are not hidden by healthy higher-volume commands.

- alert: ChatDeliveryLatencyP99High
  expr: histogram_quantile(
          0.99,
          sum by (scope, channel_type, le) (rate(chat_delivery_latency_ms_bucket[5m]))
        ) > 1000
  for: 10m
  labels:
    service: social-groups-service
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#chat-delivery-latency-above-slo
  annotations:
    summary: Chat delivery latency above SLO
    description: Chat delivery p99 latency has exceeded 1s over the last 5 minutes for active regions.

- alert: EntryPathAvailabilityLowGateway
  expr: (
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome="success"}[5m]))
      /
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway"}[5m]))
  ) < 0.995
  for: 10m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Gateway entry-path availability degraded
    description: One or more approved scopes have acute connection failures on a gateway-owned entry path. Use the short-window view for incident response and the 1-day view for compliance.

- alert: EntryPathAvailabilityLowGatewayCompliance
  expr: (
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome="success"}[1d]))
      /
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P2
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Gateway entry-path availability below 1-day SLO
    description: One or more approved scopes have sustained connection failures on a gateway-owned entry path over the compliance window. Inspect entrypath_connection_attempts_total and follow the player experience runbook.

- alert: EntryPathAvailabilityLowTcpProxy
  expr: (
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome="success"}[5m]))
      /
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service"}[5m]))
  ) < 0.995
  for: 10m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: TCP Proxy entry-path availability degraded
    description: One or more approved scopes have acute connection failures on TCP Proxy entry paths. Use the short-window view for incident response and the 1-day view for compliance.

- alert: EntryPathAvailabilityLowTcpProxyCompliance
  expr: (
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome="success"}[1d]))
      /
    sum by (scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P2
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: TCP Proxy entry-path availability below 1-day SLO
    description: One or more approved scopes have sustained connection failures on TCP Proxy entry paths over the compliance window. Inspect entrypath_connection_attempts_total and follow the player experience runbook.

- alert: PlayerFlowCanaryLoginFailed
  expr: max_over_time(playerflow_canary_success{flow="login"}[2m]) == 0
  for: 2m
  labels:
    service: spring-cloud-gateway
    component: playerflow-canary
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Synthetic login canary failing
    description: The independent player-flow login canary is failing on at least one monitored public path; treat this as player-impacting even when live traffic is sparse.

- alert: PlayerFlowCanaryCommandFailed
  expr: max_over_time(playerflow_canary_success{flow="command"}[2m]) == 0
  for: 2m
  labels:
    service: game-session-service
    component: playerflow-canary
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Synthetic command canary failing
    description: The independent player-flow representative command canary is failing after gameplay admission on at least one monitored public path.

- alert: PlayerFlowCanaryLatencyHigh
  expr: max_over_time(playerflow_canary_latency_ms{flow="command"}[5m]) > 1000
  for: 5m
  labels:
    service: game-session-service
    component: playerflow-canary
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Synthetic command canary latency high
    description: The independent player-flow representative command canary has exceeded 1000ms for at least one monitored public path.

- alert: WebSocketEntryPathBlackboxUnavailable
  expr: max_over_time(entrypath_blackbox_probe_success{path="websocket"}[2m]) == 0
  for: 2m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: WebSocket entry path unreachable from external probe
    description: Independent blackbox probes cannot reach the public WebSocket gameplay path; this catches LB, DNS, TLS, and ingress failures before traffic reaches Gateway.

- alert: TelnetEntryPathBlackboxUnavailable
  expr: max_over_time(entrypath_blackbox_probe_success{path="telnet"}[2m]) == 0
  for: 2m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Telnet entry path unreachable from external probe
    description: Independent blackbox probes cannot reach the public Telnet gameplay path; this catches LB, DNS, TLS, and ingress failures before traffic reaches TCP Proxy.
```
