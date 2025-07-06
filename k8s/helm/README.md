# Helm Charts

This directory contains placeholder Helm charts for deploying FireMUD services.
Only the **Game Session Service** chart is currently provided as an example.

The files `values-local.yaml` and `values-dev.yaml` demonstrate how runtime
settings such as Redis connection info, tick interval, and feature flags can be
overridden per environment.

Install the example chart with:

```bash
helm install game-session ./game-session-service \
  -f values-local.yaml
```

Use the Terraform module in `../terraform` to create a test cluster if desired.
