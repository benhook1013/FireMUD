# Kubernetes Network Policies

This folder documents the baseline `NetworkPolicy` set for the FireMUD cluster. The canonical manifests now live under `k8s/base/` so the base Kustomization and overlays consume one source of truth.

Apply the policies after the base service deployments if you are applying files directly rather than via `kubectl apply -k`:

```bash
kubectl apply -f k8s/base/internal-services-network-policy.yaml
kubectl apply -f k8s/base/internal-services-egress-network-policy.yaml
```

The gateway and TCP proxy remain accessible to external clients, while all other services accept connections only from within the cluster.

Helm deployments name the PostgreSQL release `firemud-postgresql` and expose
the Redis roles with `app` labels `redis-coord` and `redis-cache`. The policies
reference those labels directly. Update the selectors if your release naming or
labels differ.
