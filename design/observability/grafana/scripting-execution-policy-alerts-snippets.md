# Scripting Execution Policy Alertmanager Snippets

This file contains reference PromQL expressions and Alertmanager rules that cover the scripting execution-policy contract surfaced through `automation_script_*` and `script_*` meters.

## Scripting Execution Policy Health

```yaml
- alert: ScriptTenantBudgetDeniedSpike
  expr: |
    sum by (tenantId, tier) (rate(automation_script_tenant_budget_denied_total[5m])) > 0.5
  for: 10m
  labels:
    service: automation-scripting-service
    component: automation-budgets
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-observability-incident-runbook.md#scripting-tenant-budget-pressure
  annotations:
    summary: Script tenant budget denials are sustained
    description: Tenant/script priority-tier budget denies are sustained; script execution admission is throttling non-dry-run work items.

- alert: ScriptWorkItemQuotaDenialRate
  expr: |
    sum(rate(script_quota_denied_total[5m]))
      /
    (sum(rate(script_quota_allowed_total[5m])) + sum(rate(script_quota_denied_total[5m])) + 1e-9)
      > 0.20
  for: 5m
  labels:
    service: automation-scripting-service
    component: automation-budgets
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-quotas-and-operations.md#quota-denials
  annotations:
    summary: Script per-handler quota denials above 20% of attempts
    description: The handler admission quota deny ratio is high; inspect handler load, misrouted dry-runs, and trigger bursts before gameplay impact rises.

- alert: ScriptDryRunCapacitySaturated
  expr: |
    sum by (tenantId, scope) (rate(automation_script_test_capacity_denied_total[5m])) > 0
  for: 10m
  labels:
    service: automation-scripting-service
    component: dry-run-capacity
    severity: P2
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-quotas-and-operations.md#dry-run-capacity-controls
  annotations:
    summary: Automation dry-run execution capacity is saturated
    description: Tenant dry-run capacity reservation is denied; test traffic is being back-pressured by configured dry-run tenant or cluster concurrency limits.

- alert: ScriptWorkItemBudgetOutcomeCritical
  expr: |
    sum by (tenantId, eventType, priorityTag, sourceKind, sourceService, dryRun) (
      rate(automation_script_work_item_outcomes_total{stage="ADMISSION", outcome="tenant_budget_exceeded"}[10m])
    ) > 0
  for: 10m
  labels:
    service: automation-scripting-service
    component: work-item-admission
    severity: P2
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-control-plane-operations.md#work-item-admission-denials
  annotations:
    summary: Work-item tenant budget admission denials are occurring
    description: Script work items are being rejected at admission because tenant budget limits were exceeded for the source/event profile.

- alert: ScriptWorkItemDryRunCapacityOutcomeCritical
  expr: |
    sum by (tenantId, eventType, priorityTag, sourceKind, sourceService) (
      rate(automation_script_work_item_outcomes_total{stage="ADMISSION", outcome="dry_run_capacity_exhausted"}[10m])
    ) > 0
  for: 10m
  labels:
    service: automation-scripting-service
    component: dry-run-capacity
    severity: P2
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-quotas-and-operations.md#dry-run-capacity-controls
  annotations:
    summary: Dry-run execution capacity is saturated
    description: Dry-run script work item evaluations are being denied by dry-run capacity reservation; reduce concurrent dry-runs or increase runbook-defined limits.

- alert: ScriptWorkItemOutcomeFailingBurst
  expr: |
    sum by (service, outcome, eventType) (
      rate(automation_script_work_item_outcomes_total{stage=~"DSL_EVAL|TICK_HANDOFF|SCRIPT_EVAL"}[5m])
    ) > 0.05
  for: 10m
  labels:
    service: automation-scripting-service
    component: execution-health
    severity: P1
    owner: gameplay
    runbook: design/architecture/system-architecture-scripting-runtime-execution.md#work-item-processing-failures
  annotations:
    summary: Script work-item failures are elevated
    description: Elevated non-success outcomes in script work-item processing indicate execution or DSL/handoff issues that should be investigated with `script_event_audit` and runtime logs.
```
