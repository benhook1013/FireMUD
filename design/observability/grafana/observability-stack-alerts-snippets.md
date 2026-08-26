# Observability Stack Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rule snippets for the observability stack itself. These complement the TCP Proxy-specific rules in `tcp-proxy-alerts-snippets.md` and are intended to be imported or adapted into environment-specific rulesets.

## Observability Stack Health

Example alerts for the observability stack itself:

`ObservabilityDeadmanHeartbeatStale` and `ObservabilityDeadmanHeartbeatMissing` are installed only through `k8s/overlays/monitoring/independent-required-prometheus-published`. Before installing that overlay, the authoritative external monitor must publish `observability_deadman_stale{profile="independent-required"}` after applying the profile's stale threshold and evaluation window. The smoke runner's heartbeat timestamp is only a diagnostic mirror and does not synthesize this stale decision. A published stale value pages immediately; an absent mirror is held for one minute so provisioning gaps and transient scrape loss are distinguishable.

```yaml
- alert: ObservabilityDeadmanHeartbeatStale
  expr: observability_deadman_stale{profile="independent-required"} == 1
  for: 0m
  labels:
    service: external-monitoring
    component: deadman
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#deadman-freshness-contract
  annotations:
    summary: Independent observability deadman heartbeat stale
    description: The profile-aware external deadman mirror reports stale; verify the authoritative external monitor and paging path immediately.

- alert: ObservabilityDeadmanHeartbeatMissing
  expr: absent(observability_deadman_stale{profile="independent-required"})
  for: 1m
  labels:
    service: external-monitoring
    component: deadman
    severity: P0
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#deadman-freshness-contract
  annotations:
    summary: Independent observability deadman heartbeat missing
    description: The required-profile external deadman mirror is absent for one minute; confirm the authoritative external monitor and paging path.

- alert: AlertmanagerServiceUnavailable
  expr: up{job="alertmanager"} == 0
  for: 5m
  labels:
    service: alertmanager
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#alertmanager-down-or-not-routing
  annotations:
    summary: Alertmanager service unavailable
    description: Alertmanager is unreachable from Prometheus, so notifications and alert-state visibility are impaired even if rule evaluation continues.

- alert: AlertmanagerNotificationsFailing
  expr: rate(alertmanager_notifications_failed_total[5m]) > 0
  for: 10m
  labels:
    service: alertmanager
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#alertmanager-down-or-not-routing
  annotations:
    summary: Alertmanager notifications are failing
    description: Alertmanager is evaluating alerts but cannot deliver notifications reliably.

- alert: AlertmanagerConfigReloadFailed
  expr: alertmanager_config_last_reload_successful == 0
  for: 5m
  labels:
    service: alertmanager
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#alertmanager-down-or-not-routing
  annotations:
    summary: Alertmanager configuration reload failed
    description: Alertmanager is running with stale or invalid routing configuration.

- alert: PrometheusRuleEvaluationsFailing
  expr: increase(prometheus_rule_evaluation_failures_total[5m]) > 0
  for: 10m
  labels:
    service: prometheus
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#prometheus-down-or-stale
  annotations:
    summary: Prometheus rule evaluations are failing
    description: Prometheus cannot evaluate one or more rules; alerting and fallback recordings may be stale.

- alert: OTelCollectorExportFailures
  expr: rate(otelcol_exporter_send_failed_spans[5m]) > 0
  for: 10m
  labels:
    service: otel-collector
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger--opentelemetry-collector-down
  annotations:
    summary: OpenTelemetry Collector is failing to export spans
    description: Distributed tracing data is being dropped before it reaches Jaeger or the configured backend.

- alert: OTelCollectorUnavailable
  expr: up{job="otel-collector"} == 0
  for: 5m
  labels:
    service: otel-collector
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger--opentelemetry-collector-down
  annotations:
    summary: OpenTelemetry Collector unavailable
    description: The collector is unreachable, so new traces cannot be received even before downstream export or storage is considered.

- alert: PrometheusServiceDiscoveryFailures
  expr: increase(prometheus_sd_refresh_failures_total[5m]) > 0
  for: 10m
  labels:
    service: prometheus
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#prometheus-down-or-stale
  annotations:
    summary: Prometheus service discovery or scrape refresh is failing
    description: Prometheus cannot refresh one or more scrape target pools, so metrics may go stale without the server being fully down.

- alert: ElasticsearchClusterHealthRed
  expr: elasticsearch_cluster_health_status{color="red"} == 1
  for: 10m
  labels:
    service: elasticsearch
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#indexed-log-query-path-down-or-ingest-stalled
  annotations:
    summary: Elasticsearch cluster health is red
    description: Elasticsearch cluster health is red, which can break log ingest, search, and Kibana-backed incident triage.

- alert: ElasticsearchIndexingFailuresHigh
  expr: rate(elasticsearch_indices_indexing_index_failed_total[5m]) > 0
  for: 10m
  labels:
    service: elasticsearch
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#indexed-log-query-path-down-or-ingest-stalled
  annotations:
    summary: Elasticsearch indexing failures detected
    description: Elasticsearch is failing to index a non-zero stream of documents, so recent logs may be missing or incomplete.

- alert: JaegerQueryUnavailable
  expr: up{job="jaeger-query"} == 0
  for: 10m
  labels:
    service: jaeger
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger--opentelemetry-collector-down
  annotations:
    summary: Jaeger query service unavailable
    description: Jaeger query is unavailable, so operators cannot search or inspect traces even if spans are still being ingested.

- alert: JaegerStorageFailuresHigh
  expr: increase(jaeger_collector_spans_dropped_total[5m]) > 0
  for: 10m
  labels:
    service: jaeger
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#jaeger--opentelemetry-collector-down
  annotations:
    summary: Jaeger is dropping spans
    description: Jaeger storage or collector paths are dropping spans, so trace data is incomplete even when services still export successfully.

- alert: FluentBitOutputErrorsHigh
  expr: rate(fluentbit_output_errors_total[5m]) > 0
  for: 10m
  labels:
    service: fluent-bit
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#indexed-log-query-path-down-or-ingest-stalled
  annotations:
    summary: Fluent Bit output errors detected
    description: Log shipping is failing or backpressured; Kibana may lose recent log visibility.

- alert: GrafanaDatasourceUnavailable
  expr: grafana_datasource_up == 0
  for: 10m
  labels:
    service: grafana
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#grafana-down
  annotations:
    summary: Grafana datasource unavailable
    description: Grafana cannot query one or more configured datasources, so dashboards may render incomplete or misleading incident views.

- alert: GrafanaServiceUnavailable
  expr: up{job="grafana"} == 0
  for: 10m
  labels:
    service: grafana
    severity: P1
    owner: platform
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#grafana-down
  annotations:
    summary: Grafana service unavailable
    description: Grafana itself is unreachable, so dashboard-based triage is unavailable even if Prometheus and other backends are healthy.
```

Environment overlays may replace metric expressions for Elasticsearch, Grafana, or Jaeger service-health checks based on the exporters they deploy, but they should preserve the alert names, ownership, and runbook routing.

## Observability Smoke Test (Non-Production)

In non-production environments, it is often useful to verify alert routing end-to-end without triggering real P0/P1 alerts. A dedicated, test-only rule can be used for this purpose:

```yaml
- alert: ObservabilitySmokeTestAlert
  expr: observability_smoke_test_metric > 0
  for: 1m
  labels:
    service: observability-smoke-test
    severity: P2
    alert_class: test
    owner: platform
    runbook: design/architecture/system-architecture-testing.md#observability-tests
  annotations:
    summary: Observability smoke test alert
    description: This test-only alert is triggered by CI or a synthetic probe to verify Alertmanager routing. It must not be enabled in production.
```

CI jobs or manual probes should temporarily set `observability_smoke_test_metric` above zero in a non-production environment to confirm that Alertmanager receives and routes this alert with the expected labels, without paging on-call engineers.
