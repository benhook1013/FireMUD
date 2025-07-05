# Kubernetes Manifests

This directory contains Kubernetes manifests and Helm chart placeholders for deploying the FireMUD services.

The `base/` folder provides minimal deployment files that can be applied to a development cluster:

```bash
kubectl apply -f base/account-service.yaml
kubectl apply -f base/gateway.yaml
```

Customize these manifests with proper image repositories and resource limits before running in production.
