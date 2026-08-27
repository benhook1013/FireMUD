# Scripting Execution Policy Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rules that cover the scripting execution-policy contract surfaced through `automation_script_*` and `script_*` meters.

[Normative Table 4](../../architecture/system-architecture-scripting-normative-contract-tables.md#table-4-metrics-label-matrix) is the sole owner of scripting metric labels and increment units. Some counter names below may be current for their families, but every expression is a target template until its producer exposes the canonical Table 4 constant `service="automation-scripting-service"` label and finite labels. Keep the canonical `service` selector; do not bridge producer drift with compatibility recording rules.

## Scripting Execution Policy Health

```yaml
- alert: ScriptTenantBudgetDeniedSpike
  expr: |
    sum by (service, scope, tier) (rate(automation_script_tenant_budget_denied_total{service="automation-scripting-service"}[5m])) > 0.5
  for: 10m
  labels:
    service: automation-scripting-service
    component: automation-budgets
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-operations-cookbook.md#operational-cookbook-quotas-budgets-and-metrics
  annotations:
    summary: Script tenant budget denials are sustained
    description: Tenant/script priority-tier budget denies are sustained; script execution admission is throttling non-dry-run work items.

- alert: ScriptWorkItemQuotaDenialRate
  expr: |
    sum by (service, scope, script_kind) (rate(script_quota_denied_total{service="automation-scripting-service"}[5m]))
      /
    (
      sum by (service, scope, script_kind) (rate(script_quota_denied_total{service="automation-scripting-service"}[5m]))
        +
      (
        sum by (service, scope, script_kind) (rate(script_quota_allowed_total{service="automation-scripting-service"}[5m]))
          or on (service, scope, script_kind)
        (
          0 * sum by (service, scope, script_kind) (rate(script_quota_denied_total{service="automation-scripting-service"}[5m]))
        )
      )
      + 1e-9
    )
      > 0.20
  for: 5m
  labels:
    service: automation-scripting-service
    component: automation-budgets
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-operations-cookbook.md#operational-cookbook-quotas-budgets-and-metrics
  annotations:
    summary: Script admission quota denials above 20% of attempts
    description: The bounded script-class admission quota deny ratio is high; inspect service/scope/script-kind load, misrouted dry-runs, and trigger bursts before gameplay impact rises.

- alert: ScriptDryRunCapacitySaturated
  expr: |
    sum by (service, scope) (rate(automation_script_test_capacity_denied_total{service="automation-scripting-service"}[5m])) > 0
  for: 10m
  labels:
    service: automation-scripting-service
    component: dry-run-capacity
    severity: P2
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-quotas-and-operations.md#dry-run-budgets--limits
  annotations:
    summary: Automation dry-run execution capacity is saturated
    description: Tenant dry-run capacity reservation is denied; test traffic is being back-pressured by configured dry-run tenant or cluster concurrency limits.

- alert: ScriptWorkItemBudgetOutcomeCritical
  expr: |
    sum by (service, scope, script_kind, event_class, plugin_origin, priority) (
      rate(automation_script_triggers_total{service="automation-scripting-service", outcome="tenant_budget_exceeded"}[10m])
    ) > 0
  for: 10m
  labels:
    service: automation-scripting-service
    component: work-item-admission
    severity: P2
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-observability-contract.md#metrics-consequences-table-4-owned-schema
  annotations:
    summary: Work-item tenant budget admission denials are occurring
    description: Live script handlers are being denied after durable work-item claim but before executor acceptance because tenant budget limits were exceeded for the bounded priority and source dimensions.

- alert: ScriptWorkItemOutcomeFailingBurst
  expr: |
    sum by (service, stage, outcome, priority, source_class) (
      rate(automation_script_work_item_outcomes_total{
        service="automation-scripting-service",
        stage=~"DSL_EVAL|TICK_HANDOFF",
        outcome=~"sandbox_error|validation_error|infrastructure_error"
      }[5m])
    ) > 0.05
  for: 10m
  labels:
    service: automation-scripting-service
    component: execution-health
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-runtime-execution.md#failure-modes-and-error-handling
  annotations:
    summary: Script work-item failures are elevated
    description: Elevated `sandbox_error`, `validation_error`, or `infrastructure_error` outcomes during DSL evaluation or tick handoff indicate execution or DSL/handoff issues that should be investigated with `script_event_audit` and runtime logs.
```
