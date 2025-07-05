# Kubernetes Manifests

This directory contains Kubernetes manifests and Helm chart placeholders for deploying the FireMUD services.

The `base/` folder provides minimal deployment files that can be applied to a development cluster:

```bash
kubectl apply -f base/account-service.yaml
kubectl apply -f base/automation-scripting-service.yaml
kubectl apply -f base/entity-management-service.yaml
kubectl apply -f base/game-design-service.yaml
kubectl apply -f base/game-logic-service.yaml
kubectl apply -f base/game-session-service.yaml
kubectl apply -f base/logging-admin-service.yaml
kubectl apply -f base/social-groups-service.yaml
kubectl apply -f base/tcp-proxy-service.yaml
kubectl apply -f base/world-management-service.yaml
kubectl apply -f base/gateway.yaml
```

Customize these manifests with proper image repositories and resource limits before running in production.
