# Kubernetes Network Policies

This folder contains baseline `NetworkPolicy` manifests for the FireMUD cluster. These policies restrict **ingress** traffic so only other pods inside the namespace can connect to the internal microservices. An additional egress policy limits outbound traffic from those services to the database, Redis, and other internal pods.

Apply the policies after the base service deployments:

```bash
kubectl apply -f network-policies/internal-services.yaml
kubectl apply -f network-policies/internal-services-egress.yaml
```

The gateway and TCP proxy remain accessible to external clients, while all other services accept connections only from within the cluster.

Helm deployments name the PostgreSQL release `firemud-postgresql` and expose
the Redis roles with `app` labels `redis-coord` and `redis-cache`. The policies
reference those labels directly. Update the selectors if your release naming or
labels differ.
