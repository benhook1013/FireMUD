# ADR 0147: Phased Environment-Bound Deployment Preflight and Expected Bindings

## Status

Accepted

## Decision Record

- Decision date: 2026-07-20
- Decision keys: `PREFLIGHT-01`, `PREFLIGHT-02`
- Primary capabilities: `PO-3.1` packaging, CI/CD, deployment, and infrastructure topology; `PO-3.2` environment, configuration, secret, certificate, and service-discovery delivery
- Affected capabilities: `PO-3.4`, `PO-4.4`, `SF-1.1`, `SF-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of preflight authority, phased enforcement, validator modularity, environment identity, expected-binding evidence, controlled sharing, waiver trust, and single-operator operations

## Context

FireMUD needs one understandable deployment-safety contract across CI, operator apply, promotion, recovery, and traffic opening. A static repository check can reject malformed or obviously unsafe intended configuration, but it cannot prove which cluster an operator selected, which resources exist there, what bindings the rendered workloads use, or what actually became live. Conversely, independent scripts with unrelated policy names and report shapes would make the result depend on which deployment path happened to run.

The environment expected-binding manifest is valuable because it gives deployment and recovery one portable, reviewable declaration of intended state, trust, integration, and operator bindings. The declaration is not proof by itself. A path reference alone does not bind a check to the manifest bytes, and a manifest authored for `production` does not prove that the active Kubernetes context or observed resources belong to production.

Some bindings may legitimately use shared infrastructure, while production state and trust authorities must remain isolated. A universal `shared: true` escape hatch is therefore too broad. Requiring fields for disabled integrations is also misleading: placeholder SMTP, asset-store, webhook, or other bindings would create apparent evidence for capabilities that are not enabled.

FireMUD currently operates within one administrative trust domain. The preflight system should make safe operation practical for one operator through automated playbooks and machine-produced evidence. It does not need a distributed policy platform or independent cryptographic attestation layer for the current operating model.

## Decision

### One Versioned Policy Contract and CLI Facade

FireMUD has one versioned preflight policy catalogue. Each check has a stable policy ID, declared applicability, enforcement category, expected inputs, and result semantics. CI, operator deployment, promotion, recovery, and traffic-open tooling use the same versioned report contract.

One canonical CLI facade selects the applicable catalogue and orchestrates modular validators. Individual validators may be implemented, tested, and evolved separately, but they do not invent parallel policy IDs, applicability rules, waiver semantics, or incompatible evidence formats. Reports identify the policy-catalogue version and the concrete deployment event they evaluate.

### Three Ordered Evidence Phases

Preflight is evaluated in three distinct phases:

1. **Static CI** validates repository inputs, policy and manifest schemas, digest pinning, declared applicability, permitted sharing, deterministic rendered configuration where available, and other checks that do not require target-environment access.
2. **Live cluster pre-apply** binds the run to the selected environment and cluster identity, resolves the exact candidate manifests, and compares the declared expected bindings with rendered and live observed resources before apply.
3. **Post-apply promotion or traffic-open** verifies the actual deployed state and the event-specific evidence required before a deployment becomes promotable or player-facing.

Evidence from an earlier phase may be referenced by a later phase, but a successful static result never authorizes a live apply, promotion, or traffic opening. Each later phase must evaluate its own applicable checks and bind its report to the same deployment event, policy catalogue, candidate inputs, environment identity, and expected-binding content.

### Explicit Enforcement Categories

Every policy check belongs to one of these categories:

- **Advisory** checks report risk or approaching thresholds but do not block the current transition.
- **Apply-blocking** checks must pass before the candidate manifests are applied to the target environment unless a valid event-bound waiver expressly covers that policy ID.
- **Non-waivable promotion or traffic-open** checks must pass before the protected player-facing or promotion transition. A waiver may permit isolated investigation, repair, or a quarantined drill, but it cannot authorize the protected transition.

Waivers are never implied by a report, prior incident, environment, or operator role. An accepted waiver is bound to one deployment event and target environment, names the exact policy IDs and phase, includes an authorized approver, ticket, rationale, issue and expiration timestamps, and is validated as part of the report. It expires with that event and cannot carry forward. A malformed, expired, mismatched, or unauthorized waiver fails closed.

### Expected Bindings Declare Intent; Observation Establishes Proof

`design/operations/environments/<environment>/expected-bindings.yaml` remains the portable canonical declaration of intended environment bindings for deployment and recovery. Each preflight report records a content digest of the exact manifest bytes it consumed, not only the path.

Static validation checks the declaration and candidate render. Live pre-apply validation additionally proves the selected environment and cluster identity and compares the declaration with rendered and observed bindings. The comparison covers applicable internal state and trust resources, certificates and issuers, registry identities, external storage and communications, operator credentials, and exceptional service-discovery overrides. Post-apply validation records what was actually deployed and verifies the applicable promotion or traffic-open contract against that observed state.

Literal names alone are insufficient environment proof. Repeated cluster-local names are acceptable only when live observation proves that they resolve inside distinct environment boundaries with the intended cluster, namespace, resource, credential, and trust ownership. External and globally addressed resources are compared using the stable provider or platform binding identity available for that binding type.

### Sharing Is Binding-Type Policy, Not a Generic Escape Hatch

The policy catalogue defines an explicit shareability matrix by binding type. It identifies which binding classes are:

- environment-exclusive;
- conditionally shareable with required isolation evidence; or
- ordinarily shareable non-secret infrastructure.

Production state and trust bindings are never shareable with another environment. This includes production PostgreSQL state and credential bindings, Coordination and Cache Redis authorities, JWT signing and JWKS trust, certificate issuers and private workload identities, registry pull credentials where they confer production access, backup and asset credential principals, and production operator-control identities.

`shared: true` is accepted only for a binding class the matrix permits to be shared. Every participating environment manifest must make the same declaration, include the required rationale, and satisfy any binding-type-specific isolation proof. The flag cannot convert an environment-exclusive binding into a shared one.

### Conditional Integrations Are Validated Only When Enabled

Asset storage, outbound email, webhook targets, non-default object storage, and similar optional integrations are required in expected bindings only when the corresponding capability is enabled for that environment. The manifest declares enablement explicitly or references the canonical enablement input. Enabling an integration without its required binding fails preflight; disabling it does not require a placeholder target or credential.

### Portable Manifest Over Pure Provider Derivation

FireMUD retains the expected-binding manifest instead of deriving the entire contract only from provider-specific infrastructure. Provider and Kubernetes adapters may generate candidate declarations and gather observed identity, but deployment and recovery continue to consume one portable reviewed contract. This keeps hobby and self-hosted deployments viable, makes intended sharing and disabled integrations explicit, and avoids making one cloud provider's resource model the architecture authority.

### Evidence Production Is Automated for One Operator

Canonical deployment playbooks invoke the CLI phases, gather rendered and live observations, bind reports to the event and expected-binding digest, and generate reviewable evidence. The operator reviews and authorizes the resulting transition rather than manually transcribing routine binding or status fields.

For the current single-operator phase, this Git-reviewed, machine-validated evidence is intended to prevent mistakes, drift, missing checks, and undocumented exceptions. It is not independent proof against compromise of the operator, repository, CI, or cluster authority within that same trust domain. Stronger signed or independently witnessed evidence requires a separate trust-boundary trigger.

## Consequences

- CI and operators share one policy vocabulary and evidence shape without placing every validator in one monolithic implementation.
- Static CI remains fast and useful but cannot be mistaken for environment authorization.
- Applying, promoting, and opening traffic have distinct evidence boundaries, so a healthy render cannot stand in for live environment or post-apply proof.
- The expected-binding manifest remains portable and reviewable while its content digest and live comparisons make it stronger than a path-level declaration.
- Explicit environment and cluster identity reduces the risk of running a correctly shaped production command against the wrong boundary.
- A binding-type shareability matrix prevents `shared: true` from becoming a blanket safety bypass.
- Conditional integrations avoid placeholder evidence and unnecessary hobby/self-hosted configuration.
- Modular validators and generated evidence reduce single-operator effort, but implementing reliable live observation requires Kubernetes and provider-specific adapters.
- Non-waivable promotion and traffic-open checks may delay a player-facing transition when evidence or validation tooling is unavailable.

## Alternatives Considered

### One Undifferentiated Preflight Pass

Rejected because a single `pass` would conceal whether the run evaluated only repository state, inspected the intended target before apply, or verified the actual deployed state. It would encourage static evidence to authorize live transitions it cannot prove.

### Independent CI, Operator, and Environment Scripts

Rejected because duplicated policy names, applicability, waiver rules, and report formats drift and require operators to interpret conflicting results manually.

### Treat Expected Bindings as Sufficient Proof

Rejected because an operator-authored declaration cannot prove the selected cluster, live resource identity, external credential principal, or post-apply state. The manifest declares intent; observation supplies proof.

### Derive All Bindings from Provider-Specific Infrastructure

Rejected as the sole authority because it couples deployment and recovery to provider implementations, weakens portability, and makes sharing intent and optional integrations harder to review consistently. Provider discovery remains an input to live validation.

### Permit Arbitrary Sharing with `shared: true`

Rejected because a generic flag could legitimize production state or trust reuse. Shareability is decided by binding type, with production state and trust authorities always environment-exclusive.

### Require Independent Signed Attestation Now

Rejected for the current single-operator trust domain because the same authority would control the deployment, evidence, repository, and signing credentials. Generated, Git-reviewed evidence is the present trust root; independent signing becomes useful when an actual independent or untrusted actor is introduced.

## Implementation and Proof Obligations

Define the versioned policy catalogue, stable policy IDs, enforcement categories, phase applicability, report schema, validator interface, and canonical CLI facade. Reports must bind the deployment event, target environment, environment and cluster identity, policy-catalogue version, candidate input identity, expected-binding content digest, phase, results, and validated waiver identity where applicable.

Implement modular static, live pre-apply, and post-apply validators. Live validation must compare declared, rendered, and observed bindings using binding-type-appropriate identity rather than raw names alone. Post-apply evidence must compare the actual deployment with the reviewed candidate and enforce the applicable promotion or traffic-open gates.

Define and validate the binding-type shareability matrix, including unconditional rejection of shared production state and trust bindings. Define enablement inputs for conditional integrations and reject enabled integrations without their complete binding evidence.

Automated playbooks must generate evidence for ordinary staging, production, and hobby/self-hosted operation without requiring manual transcription. Proof must cover a static pass followed by live failure; wrong cluster or environment identity; manifest changes after static validation; rendered, observed, and declared binding mismatches; repeated cluster-local names in separate valid boundaries; prohibited production sharing; permitted sharing with missing, mismatched, and valid rationales; disabled and newly enabled optional integrations; malformed, expired, cross-event, unauthorized, and prohibited waivers; post-apply drift; promotion or traffic-open attempted with only earlier-phase evidence; and successful single-operator generation and review of the complete evidence chain.

Current static and operator tooling is partial. Existing reports and expected-binding checks do not yet prove every live binding or target-boundary identity, do not content-digest-bind the expected manifest throughout all phases, and do not implement the complete shareability matrix, phase taxonomy, or waiver validation described here. Existing static success must not be treated as proof that these obligations are complete.

## Reversibility and Revisit Triggers

Policy IDs, report versions, validator modules, provider adapters, and the shareability matrix may evolve through explicit versioning while preserving phase separation and event-bound evidence. Revisit the single-operator trust model before accepting evidence from independent operators, untrusted build or deployment actors, multiple administrative trust domains, externally supplied production artifacts, or a regulatory separation-of-duties requirement. Those triggers require independently verifiable provenance without removing the portable expected-binding contract.

## Required Documentation Alignment

- `design/architecture/system-architecture-deploy-preflight-policy.md`
- `design/architecture/system-architecture-deployment-runbook.md`
- `design/architecture/system-architecture-cicd.md`
- `design/architecture/infrastructure/deployment-environments.md`
- `design/architecture/infrastructure/environment-and-secrets-overview.md`
- `design/architecture/infrastructure/environment-and-secrets-catalog.md`
- `design/operations/environments/README.md`
