# Environment Binding Manifests

This directory stores the canonical expected-binding manifests used by deployment preflight and restore validation.

The owner contract is [ADR 0152: phased environment-bound deployment preflight and expected bindings](../../architecture/decisions/adr-0152-phased-environment-bound-deployment-preflight-and-expected-bindings.md). The manifest declares expected intent; content-digested preflight evidence and live observation establish proof for the specific deployment or recovery event.

## Implementation Status

The files in this directory are the intended source-of-truth location, and preflight evidence must prove it consumed the matching manifest via `expectedBindingsRef` before any first player-facing deployment or traffic-open decision. The current tooling now validates that link for hobby traffic-open evidence, for production traffic-open evidence, and for staging deployment records referenced by production promotion attestation, but richer live-state evidence still needs to keep converging around the same manifest contract.

- `<environment>/expected-bindings.yaml` is the single source of truth for the environment-isolation contract for that player-facing environment.
- These manifests cover both internal state/trust bindings (PostgreSQL, Redis, JWT/JWKS, certificate issuer, registry pull credentials) and external bindings (backup storage, asset storage, outbound communications, operator credentials).
- `backupStorage.enabled` is required as a boolean. Enabled backup storage declares its bucket and `bindingRef` or `fingerprint` (plus a non-default endpoint when applicable); disabled backup storage omits those binding fields. Production must enable backup storage, while staging and hobby/self-hosted may disable it. Disabled backup storage is ignored for external-binding uniqueness, without disabling checks for other applicable external integrations.
- `assetStorage` and `outboundComms` are optional sections. If present, each requires an explicit boolean `enabled` selector. Enabled asset storage requires `bucket`, `endpoint`, and `bindingRef` or `fingerprint`; enabled outbound communications requires `smtpHost` or a non-empty `webhookTargets` mapping. Disabled sections must omit all target and credential fields, may be omitted entirely, and are excluded from external-binding uniqueness checks. Asset-store and outbound credential principals remain environment-exclusive even when a non-sensitive target is conditionally shareable.
- `internalBindings.jwt.custodyMode` is the authoritative closed selector for JWT custody. The checked-in player-facing Kustomize manifests explicitly use `LEGACY_SECRET_DIAGNOSTIC`, which classifies the current legacy Secret-backed signing plus fixed public `jwt-jwks` Secret diagnostic wiring only and cannot satisfy player-facing custody/readiness. Hosted preview Helm remains on a separate ConfigMap-backed diagnostic path. Interim and target selectors remain fail-closed until their mode-specific proofs are implemented. `signingKeysRef` and `jwksRef` do not select a custody mode.
- `serviceDiscovery` also lives here so player-facing `FIREMUD_SERVICES_*` overrides are explicit, reviewable, and validated against the intended environment boundary.
- Internal bindings are interpreted relative to the target environment boundary. The same cluster-local literal may appear in multiple manifests when each environment owns its own cluster-local resource with that name.

Player-facing environments must keep this manifest current before first deployment and whenever a binding changes.

The same environment-bound manifest is consumed by deployment and recovery workflows. Binding declarations follow the shareability matrix: internal state/trust, credential principals, and operator identities are environment-exclusive, while only approved non-sensitive endpoints/targets may be conditionally shared. Every participating manifest must carry the same `shared: true` declaration and non-empty `sharedRationale`; `shared: true` cannot override an exclusive classification. Optional integrations are required only when enabled for the target environment. A manifest digest, event identity, and observed target binding must remain traceable in the owner evidence rather than being treated as proof by declaration alone.

Illustrative player-facing exception:

```yaml
environment: staging
serviceDiscovery:
  mode: explicit-overrides
  allowedOverrides:
    FIREMUD_SERVICES_WORLD_MANAGEMENT_SERVICE: world-management-service-canary.firemud.svc.cluster.local:6565
```

Use this exception path only when the override still resolves inside the target environment boundary. Cross-environment override targets remain non-compliant.
