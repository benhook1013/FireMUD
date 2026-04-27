# Environment Binding Manifests

This directory stores the canonical expected-binding manifests used by deployment preflight and restore validation.

Implementation note: the files in this directory are the intended source-of-truth location, and preflight evidence must prove it consumed the matching manifest via `expectedBindingsRef` before any first player-facing deployment or traffic-open decision. The current tooling now validates that link for hobby traffic-open evidence and for staging deployment records referenced by production promotion attestation, but richer live-state evidence still needs to keep converging around the same manifest contract.

- `<environment>/expected-bindings.yaml` is the single source of truth for the environment-isolation contract for that player-facing environment.
- These manifests cover both internal state/trust bindings (PostgreSQL, Redis, JWT/JWKS, certificate issuer, registry pull credentials) and external bindings (backup storage, asset storage, outbound communications, operator credentials).
- `serviceDiscovery` also lives here so player-facing `FIREMUD_SERVICES_*` overrides are explicit, reviewable, and validated against the intended environment boundary.
- Internal bindings are interpreted relative to the target environment boundary. The same cluster-local literal may appear in multiple manifests when each environment owns its own cluster-local resource with that name.

Player-facing environments must keep this manifest current before first deployment and whenever a binding changes.

Illustrative player-facing exception:

```yaml
environment: staging
serviceDiscovery:
  mode: explicit-overrides
  allowedOverrides:
    FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE: world-management-service-canary.firemud.svc.cluster.local:6565
```

Use this exception path only when the override still resolves inside the target environment boundary. Cross-environment override targets remain non-compliant.
