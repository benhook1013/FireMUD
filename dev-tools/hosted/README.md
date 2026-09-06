# Hosted Environment Tooling

This directory contains tooling for FireMUD's hosted Kubernetes environments.

## Folder Map

- `shared/`
  - helpers used by both hosted lanes
  - kubeconfig setup, namespace deletion, shared smoke, shared image wait, shared rollout diagnostics, and shared pull-secret/TLS setup

- `preview/`
  - PR-preview-only helpers
  - capacity allocation and bounded priority reclaim, PR-head freshness checks, preview namespace pruning, preview NodePort allocation, and preview-specific value rendering/summary output

- `dev-demo/`
  - fixed `develop` environment helpers
  - dev-demo-specific namespace labels, value rendering, and summary output

## Rollout Failure Contract

When a hosted deploy fails after cluster access is available, start with `shared/show-rollout-diagnostics.sh`.

It is the canonical first-look diagnostic for both PR preview and dev-demo and prints:

- the namespace labels/annotations that identify the current target
- blocked workload and pod readiness reasons
- service and target-port detail
- safe selected ConfigMap values
- secret and TLS certificate summaries
- recent events, unavailable workload describes, and current plus previous logs for problematic pods

Use it before ad hoc live inspection so preview debugging stays deterministic and comparable across runs.
