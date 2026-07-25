# ADR 0032: Kubernetes-Native Secret Delivery Without Mandatory Vault

## Status

Accepted

## Implementation Status

Kubernetes Secret mounts, cert-manager examples, expected-binding preflight, and secret-compliance evidence exist, but the repository does not satisfy the complete target. Base manifests mount JWT signing material beyond Account, gRPC workloads commonly share one TLS Secret, and production Secret encryption-at-rest/audit proof plus automated rotation/convergence remain incomplete.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `SF-1.3` Authentication, authorization, service identity, and secret handling
- Affected capabilities: `PO-3.2`, `PO-2.1`, `PO-1.1`, `SF-1.5`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `SEC-01`

## Context

FireMUD must deliver database credentials, JWT signing keys, mTLS identities, operator credentials, object-store credentials, and recovery material without committing secrets to the repository or coupling every application to a secret vendor. The previous target described Kubernetes Secrets as one unified secret store and declared external systems out of scope. That is simple, but it conflates the workload delivery interface with the operator's possible upstream custody system and does not make the required Kubernetes hardening, per-workload identity, or multi-datacenter boundary precise.

Mandating Vault now would add storage, TLS, authentication, unseal/recovery, backup, high-availability, rotation, and incident-response obligations to every deployment, including hobby/self-hosted environments. Running a Vault container beside the same Compose stack while storing its recovery material in that stack would add complexity without a meaningful independent trust boundary.

## Decision

### One FireMUD Consumption Contract

- Kubernetes workloads consume sensitive values through fixed read-only mounted-file paths or bounded Kubernetes `secretKeyRef` interfaces. Private keys and certificate material use mounted files.
- The canonical JWT fallback interface is fixed and namespace-local: the `jwt-signing-keys` Secret exposes `current.key` as the active private key and may expose `pending.key` or `rollback.key` only for the explicitly recorded rotation phase; Account mounts it read-only at `/var/run/secrets/firemud/jwt` and reads the active file through `FIREMUD_AUTH_JWT_SECRET_PATH=/var/run/secrets/firemud/jwt/current.key`. The `jwt-jwks` ConfigMap exposes exactly `jwks.json`; Account mounts it read-only at `/var/run/secrets/firemud/jwks`, serves it through the JWKS endpoint, and uses `FIREMUD_AUTH_JWKS_PATH=/var/run/secrets/firemud/jwks/jwks.json`. No workload may invent alternate names or paths for these player-facing resources.
- Local development may use ignored `.env` values and generated credentials. When Docker mounts real private material, it uses read-only host files outside the repository with restrictive permissions.
- FireMUD application code, Compose, Helm, and Kubernetes manifests do not deploy or call Vault, cloud secret-manager, or provider-specific secret APIs.
- Operators may configure or feed an approved upstream source through deployment-specific infrastructure, but the upstream Secret-materialization controller must consume those inputs and materialize the canonical Kubernetes Secret and its freshness metadata. Operators must not directly create, replace, refresh, or delete canonical Secret bytes or freshness metadata as a parallel path. That upstream provisioning is transparent to FireMUD and is not a second product/runtime mode.
- Synchronization from an upstream provider must preserve the last valid materialized Kubernetes Secret during an upstream outage; it must not replace or delete healthy credentials merely because refresh is unavailable. Workloads may continue only while that retained secret remains within its configured validity/age policy. Expiry, invalid material, or inability to prove acceptable age makes the affected readiness gate fail closed while leaving the existing Secret intact for diagnosis and controlled recovery.
- Every shared or player-facing materialized Secret carries non-secret metadata for `materializedAt`, `expiresAt`, and the upstream `sourceGeneration` (for example as fixed `firemud.io/*` annotations). `materializedAt` and `expiresAt` describe the retained bytes that are mounted, with `expiresAt` no later than `materializedAt +` the class max-age policy or the material's cryptographic not-after time, whichever is earlier. Retaining a Secret during an upstream outage does not refresh either timestamp, and an invalid or missing metadata value fails closed.
- The required Secret annotations are `firemud.io/materialized-at`, `firemud.io/expires-at`, and `firemud.io/source-generation`. Timestamp values use RFC 3339 UTC with a `Z` suffix; `source-generation` is a non-empty opaque upstream version that must remain unchanged while the mounted bytes remain unchanged and must advance for a replacement. A readiness actor rejects missing, malformed, regressing, or mismatched metadata rather than treating the Secret as fresh.
- The upstream Secret-materialization controller is the sole writer of the Secret and its freshness metadata. The FireMUD workload-readiness actor (the controller/readiness gate evaluating the mounted Secret for that workload) is the enforcing authority: it verifies metadata age/expiry and material validity before reporting ready, and application liveness or an operator-supplied all-clear cannot override a failed freshness check.
- When freshness or validity fails, the readiness actor immediately removes the workload from new traffic and keeps the existing Secret untouched. Removal from readiness is not proof that already-open connections or in-process consumers stopped using stale material. The workload must stop new work, drain existing connections only within the credential class's bounded drain deadline, and then terminate or reload every affected in-process consumer. Hot reload is acceptable only when the process atomically installs and verifies the replacement generation and proves no new operation can use the old material; connections whose authenticated lifetime is bound to the expired credential are closed and re-established. If the workload cannot prove bounded drain and complete replacement, its controller terminates and replaces the pod. Liveness does not keep a pod serving with invalid material. Readiness may return only after the materialization controller writes a valid replacement with a new generation and metadata, the workload observes and verifies it, and the reload or replacement proof completes.

### Classification And Least Privilege

- Secret values never appear in Git, images, ConfigMaps, rendered Helm values, logs, traces, or compliance evidence. Evidence records contain bounded identifiers, ages, digests, and outcomes only.
- Account Service is the only application workload that receives the Account JWT private signing bundle.
- JWKS is public verification configuration, not a private secret. It is delivered through one fixed-name ConfigMap whose `jwks.json` contents are mutable only through the Account Service service account's name-scoped `get`, `update`, and `patch` authority using resource-version compare-and-set, or an equivalent integrity-controlled public artifact, and remains generation-coupled to signing-key rotation. The Account workload and readiness validators reject a public set whose key generation, active `kid`, or resource identity does not match authenticated Account transition evidence; no other service account may write it, and no actor may list, create, or delete the pre-created resource.
- cert-manager issues a distinct leaf private key and certificate for each workload identity into a dedicated Secret. Workloads may share a CA trust bundle but not one leaf private identity.
- Operator client certificates use a separate issuer/profile and dedicated Secret and are not mounted into normal application workloads.
- Re-creatable leaf certificates and routine credentials are reissued after loss. Irreplaceable recovery material, including backup-decryption keys or an intentionally retained offline CA root, has encrypted out-of-cluster custody and never relies on the live cluster as its sole copy.

### Environment Baseline

- Staging and production require verified Kubernetes Secret encryption at rest, minimal service-account RBAC, namespace isolation, Kubernetes API audit logging, expected-binding preflight, and Tier A age/rotation evidence.
- Hobby/self-hosted player-facing Kubernetes deployments use the same Secret names, mount boundaries, least-privilege checks, and credential-age preflight. Operators should enable control-plane encryption and audit controls where their Kubernetes distribution supports them; unsupported infrastructure limitations remain visible in readiness evidence rather than silently claiming production equivalence.
- Local and ephemeral environments may use generated throwaway material but cannot supply production-promotion secret evidence.

### External And Multi-Datacenter Boundary

- Vault is neither required nor bundled. FireMUD does not promise a built-in external secret-manager adapter.
- Independent deployments or future regions use independent credential authorities by default.
- A future active-active multi-datacenter control plane that requires shared signing or secret authority must make a separate architecture decision among non-exportable KMS/HSM signing, a managed secret service, Vault, or another system.
- FireMUD does not build cross-cluster secret replication. If operator scripts begin recreating centralized distribution, dynamic credential issuance, or shared-authority audit, that is a trigger to adopt dedicated infrastructure rather than grow a bespoke secret manager.

## Consequences

- FireMUD has one testable workload contract rather than separate Vault and non-Vault application modes.
- Hobby/self-hosted deployment does not inherit Vault's permanent operational burden.
- Production security depends on correctly configured Kubernetes encryption, RBAC, audit, workload isolation, rotation, and recovery custody; a base64 Secret object alone is not sufficient.
- Per-workload certificates make concrete mTLS caller allowlists possible but increase cert-manager resources, Secret objects, rotation proof, and Helm/Kustomize complexity.
- An operator can adopt an external manager later without application changes as long as it materializes the same contract.
- Truly shared active-active multi-datacenter authority remains deliberately unimplemented and cannot be inferred from provider-neutral file mounts.

## Alternatives Considered

### Mandatory Vault In Every Deployment

Vault centralizes audit, dynamic credentials, and multi-cluster distribution, but requires its own secure storage, TLS, identity bootstrap, unseal/recovery custody, high availability, backup, monitoring, and incident response. That cost is not justified by the current single-cluster-per-deployment topology.

### Kubernetes Secrets As Both Mandatory Origin And Delivery

This is simpler to describe but unnecessarily prevents operators from using managed custody and makes future migration sound like an application mode change. FireMUD needs to standardize consumption, not dictate every operator's upstream provisioning tool.

### Application-Native Provider Integrations

Teaching each service to call Vault or cloud APIs creates provider coupling, startup/outage dependencies, duplicated authentication logic, and multiple runtime paths. Materialization behind Kubernetes avoids those costs.

### Bundled Compose Vault

A companion Vault container in the same trust and recovery domain provides little protection when its unseal or root material is delivered through the same Compose environment. It adds failure modes without establishing independent custody.

## Implementation and Proof Obligations

- Remove Account JWT private material from every non-Account workload and prove validators consume only public JWKS.
- Replace shared gRPC leaf material with cert-manager-issued per-workload certificates and prove exact workload identity plus method caller allowlists.
- Prove secret-expiry handling stops new work, drains or terminates existing connections within the class-specific deadline, replaces every in-process consumer, and cannot regain readiness while any affected process still uses invalid material.
- Classify JWKS as public integrity-controlled configuration and keep its rotation generation consistent with the Account signing bundle.
- Add staging/production evidence for Secret encryption at rest, service-account RBAC, namespace isolation, API auditing, mount identity, and absence of sensitive Helm/ConfigMap values.
- Preserve and extend expected-binding and Tier A compliance checks without recording secret values.
- Document and prove encrypted out-of-cluster custody or reissuance for every required recovery credential class.
- Keep Docker/local real-secret mounts outside the repository and read-only; do not add Vault to the default Compose topology.
- Prove an upstream provisioning outage does not erase already-materialized secrets from healthy workloads.

## Required Documentation Alignment

- [Security](../system-architecture-security.md)
- [gRPC](../system-architecture-grpc.md)
- [Environment and secrets overview](../infrastructure/environment-and-secrets-overview.md)
- [Environment and secrets catalog](../infrastructure/environment-and-secrets-catalog.md)
- [Operator credentials runbook](../system-architecture-operator-credentials-runbook.md)
- [Platform operations and delivery tracker](../../project-management/implementation-tracking/platform-operations-and-delivery.md)

## Reversibility and Revisit Triggers

The mounted-file and Kubernetes Secret contract is provider-neutral. Revisit the upstream authority only if FireMUD adopts an active-active shared control plane across datacenters, requires non-exportable signing, dynamic database credentials, centralized multi-cluster revocation/audit, or regulatory controls the hardened Kubernetes baseline cannot satisfy. Any adoption should remain behind the existing workload contract where technically possible.
