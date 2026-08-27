# Player Experience Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for player-centric SLO alerts. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

## Player Experience SLO Alerts

These example rules are calibration and degradation signals for the target-state player-centric SLO families defined in the Logging & Monitoring architecture doc. They are not universal SLO gates or availability claims: until a deployment profile promotes a measured objective, keep these alerts at non-urgent severity and treat no-data or low-volume windows as `unknown`. A promoted objective must replace the calibration rule with profile-specific minimum-sample handling and short/long error-budget burn evaluation.

The `profile` label uses ADR 0159's canonical monitoring-profile enum, `independent-required` or `independent-omitted`, across canary metrics, retained evidence, and profile-aware rules. Do not serialize the prose abbreviations `required` or `omitted` as profile values.

The `PlayerFlowCanary*` rules below, together with the P0 `WebSocketEntryPathBlackboxUnavailable` and `TelnetEntryPathBlackboxUnavailable` rules and the P1 `WebSocketEntryPathBlackboxMetricsAbsent` and `TelnetEntryPathBlackboxMetricsAbsent` unknown/degraded evidence rules, are target-state reference fixtures only. They remain pending deployment-owned applicability/expected-series gating for the advertised capability and complete exposed path set, and are not currently installed by the shared PrometheusRule or any profile overlay. When eventually installed, they must remain local diagnostic mirrors of—not replacements for—the authoritative canary/external monitor and pager. They must not be installed for omitted capabilities or non-exposed paths.

```yaml
- alert: LoginSuccessRatioLowGateway
  expr: (
    sum by (scope) (rate(login_requests_total{service="spring-cloud-gateway", outcome="success"}[15m]))
      /
    sum by (scope) (rate(login_requests_total{service="spring-cloud-gateway", outcome=~"success|server_failure"}[15m]))
  ) < 0.995
  for: 15m
  labels:
    service: spring-cloud-gateway
    severity: P2
    slo_state: calibration
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Login success calibration below starting point
    description: Gateway login success ratio is below the 99.5% calibration starting point over the last 15 minutes. This is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy.

- alert: LoginSuccessRatioLowTcpProxy
  expr: (
    sum by (scope) (rate(login_requests_total{service="tcp-proxy-service", outcome="success"}[15m]))
      /
    sum by (scope) (rate(login_requests_total{service="tcp-proxy-service", outcome=~"success|server_failure"}[15m]))
  ) < 0.995
  for: 15m
  labels:
    service: tcp-proxy-service
    severity: P2
    slo_state: calibration
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Login success calibration below starting point (TCP Proxy)
    description: TCP Proxy login success ratio is below the 99.5% calibration starting point over the last 15 minutes. This is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy.

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
    severity: P2
    slo_state: calibration
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Command p99 calibration above starting point
    description: Gateway command end-to-end p99 latency has exceeded the 250ms calibration starting point for at least one bounded core command. This is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy. Preserve the `command` label so single-command regressions are not hidden by healthy higher-volume commands.

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
    severity: P2
    slo_state: calibration
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Command p99 calibration above starting point (TCP Proxy)
    description: TCP Proxy command end-to-end p99 latency has exceeded the 250ms calibration starting point for at least one bounded core command. This is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy. Preserve the `command` label so single-command regressions are not hidden by healthy higher-volume commands.

- alert: ChatDeliveryLatencyP99High
  expr: histogram_quantile(
          0.99,
          sum by (service, scope, completion_boundary, channel_type, le) (rate(chat_delivery_latency_ms_bucket{completion_boundary="recipient_dispatch"}[5m]))
        ) > 1000
  for: 10m
  labels:
    severity: P2
    slo_state: calibration
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#chat-delivery-latency-above-slo
  annotations:
    summary: Chat delivery p99 calibration above starting point
    description: Chat delivery p99 latency has exceeded the 1s calibration starting point over the last 5 minutes for one emitting service at the canonical recipient-dispatch completion boundary. This is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy; diagnostic completion boundaries must not be combined with or satisfy this SLI.

- alert: EntryPathAvailabilityLowGateway
  expr: (
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome="success"}[5m]))
      /
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome=~"success|server_failure"}[5m]))
  ) < 0.995
  for: 10m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P2
    slo_state: calibration
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Gateway entry-path calibration degraded
    description: One or more approved scopes have acute connection failures on a gateway-owned entry path. This calibration signal is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy; use the short-window view for diagnosis and the 1-day view for calibration.

- alert: EntryPathAvailabilityLowGatewayCompliance
  expr: (
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome="success"}[1d]))
      /
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="spring-cloud-gateway", outcome=~"success|server_failure"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P2
    slo_state: calibration
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Gateway entry-path calibration below starting point
    description: One or more approved scopes have sustained connection failures on a gateway-owned entry path over the calibration window. This is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy. Inspect entrypath_connection_attempts_total and follow the player experience runbook.

- alert: EntryPathAvailabilityLowTcpProxy
  expr: (
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome="success"}[5m]))
      /
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome=~"success|server_failure"}[5m]))
  ) < 0.995
  for: 10m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P2
    slo_state: calibration
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: TCP Proxy entry-path calibration degraded
    description: One or more approved scopes have acute connection failures on TCP Proxy entry paths. This calibration signal is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy; use the short-window view for diagnosis and the 1-day view for calibration.

- alert: EntryPathAvailabilityLowTcpProxyCompliance
  expr: (
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome="success"}[1d]))
      /
    sum by (service, scope, path) (increase(entrypath_connection_attempts_total{service="tcp-proxy-service", outcome=~"success|server_failure"}[1d]))
  ) < 0.999
  for: 30m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P2
    slo_state: calibration
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: TCP Proxy entry-path calibration below starting point
    description: One or more approved scopes have sustained connection failures on TCP Proxy entry paths over the calibration window. This is non-enforcing until a profile promotes a measured objective with minimum-sample and multi-window policy. Inspect entrypath_connection_attempts_total and follow the player experience runbook.

- alert: PlayerFlowCanaryLoginFailed
  expr: >-
    playerflow_canary_success{flow="login"} == 0
    and on (flow, path, target, profile)
    (
      time() - playerflow_canary_last_run_timestamp_seconds{flow="login"} >= 0
      and on (flow, path, target, profile)
      (
        time() - playerflow_canary_last_run_timestamp_seconds{flow="login"}
        <= on (profile) group_left()
          playerflow_canary_freshness_budget_seconds
      )
    )
  for: 2m
  labels:
    component: playerflow-canary
    path: '{{ $labels.path }}'
    target: '{{ $labels.target }}'
    profile: '{{ $labels.profile }}'
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#login-success-ratio-below-slo
  annotations:
    summary: Synthetic login canary failure requires confirmation
    description: A fresh independent player-flow login canary is failing on at least one monitored public path. Treat this as actionable player-flow degradation even when live traffic is sparse; promote to P0 only after the deployment-owned policy confirms a sustained complete-journey failure, not from one sample or identity.

- alert: PlayerFlowCanaryCommandFailed
  expr: >-
    playerflow_canary_success{flow="command"} == 0
    and on (flow, path, target, profile)
    (
      time() - playerflow_canary_last_run_timestamp_seconds{flow="command"} >= 0
      and on (flow, path, target, profile)
      (
        time() - playerflow_canary_last_run_timestamp_seconds{flow="command"}
        <= on (profile) group_left()
          playerflow_canary_freshness_budget_seconds
      )
    )
  for: 2m
  labels:
    component: playerflow-canary
    path: '{{ $labels.path }}'
    target: '{{ $labels.target }}'
    profile: '{{ $labels.profile }}'
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Synthetic command canary failing
    description: The independent player-flow representative command canary is failing after gameplay admission on at least one monitored public path.

- alert: PlayerFlowCanaryLatencyHigh
  expr: >-
    playerflow_canary_latency_ms{flow="command"} > 1000
    and on (flow, path, target, profile)
    (
      time() - playerflow_canary_last_run_timestamp_seconds{flow="command"} >= 0
      and on (flow, path, target, profile)
      (
        time() - playerflow_canary_last_run_timestamp_seconds{flow="command"}
        <= on (profile) group_left()
          playerflow_canary_freshness_budget_seconds
      )
    )
  for: 2m
  labels:
    component: playerflow-canary
    path: '{{ $labels.path }}'
    target: '{{ $labels.target }}'
    profile: '{{ $labels.profile }}'
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#command-latency-above-slo
  annotations:
    summary: Synthetic command canary latency high
    description: The independent player-flow representative command canary has exceeded 1000ms for at least one monitored public path.

- alert: PlayerFlowCanaryFreshnessBudgetMissing
  expr: >-
    count by (profile) (
      playerflow_canary_success
      or playerflow_canary_latency_ms
      or playerflow_canary_last_run_timestamp_seconds
    )
    unless on (profile)
    count by (profile) (playerflow_canary_freshness_budget_seconds)
  for: 2m
  labels:
    service: prometheus
    component: playerflow-canary
    profile: '{{ $labels.profile }}'
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#prometheus-down-or-stale
  annotations:
    summary: Player-flow canary freshness budget series missing
    description: Canary result series are present without a matching freshness budget for this profile, so the canary failure and latency alerts cannot evaluate. Treat player-flow health as unknown until the budget mirror is restored.

- alert: PlayerFlowCanaryEvidenceStale
  expr: >-
    (
      playerflow_canary_success
      unless on (flow, path, target, profile)
      playerflow_canary_last_run_timestamp_seconds
    )
    or
    time() - playerflow_canary_last_run_timestamp_seconds < 0
    or on (flow, path, target, profile)
    time() - playerflow_canary_last_run_timestamp_seconds
    > on (profile) group_left()
      playerflow_canary_freshness_budget_seconds
  for: 1m
  labels:
    service: prometheus
    component: playerflow-canary
    flow: '{{ $labels.flow }}'
    path: '{{ $labels.path }}'
    target: '{{ $labels.target }}'
    profile: '{{ $labels.profile }}'
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#incident-types
  annotations:
    summary: Synthetic player-flow canary evidence stale
    description: The advertised player-flow canary run evidence is missing its matching last-run timestamp, future-dated, or older than the profile-derived freshness budget; treat player-flow health as unknown or degraded until a fresh run is retained.

- alert: WebSocketEntryPathBlackboxUnavailable
  # An absent non-exposed path is not_applicable; missing evidence for an
  # exposed path is unknown/degraded rather than not_applicable.
  # A two-minute rule hold confirms continuous failure before paging.
  expr: entrypath_blackbox_probe_success{path="websocket"} == 0
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

- alert: WebSocketEntryPathBlackboxMetricsAbsent
  # Missing required-path evidence is unknown/degraded, not an observed outage.
  # Use the same two-minute hold as the zero-value path alert.
  expr: absent(entrypath_blackbox_probe_success{path="websocket"})
  for: 2m
  labels:
    service: spring-cloud-gateway
    component: entrypath
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: WebSocket entry-path blackbox evidence is absent
    description: The required external WebSocket probe mirror has no series, so entry-path availability is unknown or degraded rather than an observed outage; restore the external monitor or Prometheus mirror.

- alert: TelnetEntryPathBlackboxUnavailable
  # An absent non-exposed path is not_applicable; missing evidence for an
  # exposed path is unknown/degraded rather than not_applicable.
  # A two-minute rule hold confirms continuous failure before paging.
  expr: entrypath_blackbox_probe_success{path="telnet"} == 0
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

- alert: TelnetEntryPathBlackboxMetricsAbsent
  # Missing required-path evidence is unknown/degraded, not an observed outage.
  # Use the same two-minute hold as the zero-value path alert.
  expr: absent(entrypath_blackbox_probe_success{path="telnet"})
  for: 2m
  labels:
    service: tcp-proxy-service
    component: entrypath
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-player-experience-incident-runbook.md#telnet-and-websocket-path-availability-below-slo
  annotations:
    summary: Telnet entry-path blackbox evidence is absent
    description: The required external Telnet probe mirror has no series, so entry-path availability is unknown or degraded rather than an observed outage; restore the external monitor or Prometheus mirror.
```
