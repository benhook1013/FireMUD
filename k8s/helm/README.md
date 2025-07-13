# Helm Charts

This directory contains placeholder Helm charts for deploying FireMUD services.
Charts are provided for the **Account Service** and **Game Session Service** as examples.

The files `values-local.yaml` and `values-dev.yaml` demonstrate how runtime
settings such as Redis connection info, tick interval, and feature flags can be
overridden per environment.

Install the example chart with:

```bash
helm install game-session ./game-session-service \
  -f values-local.yaml
```

Use the Terraform module in `../terraform` to create a test cluster if desired.

To deploy the Account Service:

```bash
helm install account-service ./account-service \
  -f values-local.yaml
```

To deploy all services at once you can use the umbrella chart:

```bash
helm install firemud ./firemud \
  -f values-local.yaml
```

The umbrella chart includes a Helm hook job that wipes the Redis Append-Only File on each deployment.
Set `redis.releaseName` in your values file to match the Redis Helm release name
(for example `firemud-redis` in production) so the hook can mount the correct
PersistentVolumeClaim.
