# Hosted Environment Tooling

This directory contains tooling for FireMUD's hosted Kubernetes environments.

## Folder Map

- `shared/`
  - helpers used by both hosted lanes
  - kubeconfig setup, namespace deletion, shared smoke, shared image wait, shared runtime rollout wait, rollout diagnostics, and shared pull-secret/TLS setup
  - `wait-for-hosted-runtime-rollouts.sh` owns the canonical 15-deployment rollout inventory; callers provide the namespace and per-deployment timeout

- `preview/`
  - PR-preview-only helpers
  - capacity allocation and bounded priority reclaim, PR-head freshness checks, preview namespace pruning, preview NodePort allocation, and preview-specific value rendering/summary output
  - [Preview eligibility](preview/preview-eligibility.py) requires valid GitHub label metadata for deploy and retain operations; destroy remains eligible for cleanup when metadata is malformed. Same-repository pull requests targeting `main` or `develop` remain ordinarily eligible, while another same-repository base requires the maintainer-controlled `preview:priority` label in addition to current mergeability and exact source-tuple validation
  - priority reclaim applies only when the preview pool is full, selects the oldest ordinary allocation after rechecking both target and victim labels at the deletion boundary, and never reclaims another currently priority-labelled allocation

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

## Public Helpers

- [shared/show-rollout-diagnostics.sh](shared/show-rollout-diagnostics.sh) – first-look diagnostics for a hosted namespace when a rollout is blocked.
- [shared/wait-for-hosted-runtime-rollouts.sh](shared/wait-for-hosted-runtime-rollouts.sh) – bounded readiness wait for the canonical hosted runtime deployment inventory.
