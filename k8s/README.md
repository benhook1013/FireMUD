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
kubectl apply -f base/spring-cloud-gateway.yaml
```

After the services are running, apply the default network policies found in
`network-policies/` to restrict traffic to internal pods only:

```bash
kubectl apply -f network-policies/internal-services.yaml
```

Customize these manifests with proper image repositories and resource limits before running in production.
All Spring Boot services are configured to run with the `prod` profile by default via the `SPRING_PROFILES_ACTIVE` environment variable.

Services follow the port scheme described in the infrastructure docs: most
containers listen on `8080`, the TCP proxy exposes `2323` for Telnet clients,
and the Spring Cloud Gateway is published on port `80`.
