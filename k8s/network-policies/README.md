# Kubernetes Network Policies

This folder contains baseline `NetworkPolicy` manifests for the FireMUD cluster. These policies restrict **ingress** traffic so only other pods inside the namespace can connect to the internal microservices.

Apply the policies after the base service deployments:

```bash
kubectl apply -f network-policies/internal-services.yaml
```

The gateway and TCP proxy remain accessible to external clients, while all other services accept connections only from within the cluster.

Helm deployments name the PostgreSQL and Redis releases `firemud-postgresql` and
`firemud-redis`. The policies reference these release names via the
`app.kubernetes.io/instance` label. Update the values if your release names
differ.
