# Environment Binding Manifests

This directory stores the canonical expected-binding manifests used by deployment preflight and restore validation.

- `<environment>/expected-bindings.yaml` is the single source of truth for the environment-isolation contract for that player-facing environment.
- These manifests cover both internal state/trust bindings (PostgreSQL, Redis, JWT/JWKS, certificate issuer, registry pull credentials) and external bindings (backup storage, asset storage, outbound communications, operator credentials).
- `serviceDiscovery` also lives here so player-facing `FIREMUD_SERVICES_*` overrides are explicit, reviewable, and validated against the intended environment boundary.

Player-facing environments must keep this manifest current before first deployment and whenever a binding changes.
