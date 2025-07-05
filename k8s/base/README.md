# Baseline Kubernetes Manifests

This directory contains minimal deployment files for running the core FireMUD services in a Kubernetes cluster. The manifests are intended as starting points and should be customized with image repositories, resource limits, and environment variables.

Apply all manifests with:

```bash
kubectl apply -f account-service.yaml
kubectl apply -f gateway.yaml
```

These files expose the services internally using `ClusterIP` (except the gateway which is a `LoadBalancer`). See the [Deployment Environments](../../design/architecture/infrastructure/deployment-environments.md) document for production considerations.
