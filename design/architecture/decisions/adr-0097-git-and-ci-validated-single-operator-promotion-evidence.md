# ADR 0097: Git and CI-Validated Single-Operator Promotion Evidence

## Status

Accepted

## Implementation Status

Git-reviewed, CI-validated promotion evidence is target state. Current enforcement still has gaps around absent-attestation failure, complete field/schema validation, staging commit existence, and release-manifest cross-reference checks. Non-official production promotions require the applicable overlay, staging, and promotion-attestation evidence; official releases additionally require the release digest manifest and its cross-references. See the [CI/CD promotion contract](../system-architecture-cicd.md#promotion--rollback-model) and [promotion attestation contract](../system-architecture-promotion-attestation.md#artifact-format).

## Canonical Design

- [CI/CD promotion and rollback model](../system-architecture-cicd.md#promotion--rollback-model)
- [Promotion attestation contract](../system-architecture-promotion-attestation.md#artifact-format)
- [Canonical deployment evidence lifecycle](../system-architecture-cicd.md#canonical-deployment-evidence-lifecycle)

## Decision Record

- Decision date: 2026-07-20
- Decision key: `PROMO-01`
- Primary capability: `PO-3.1` deployment and promotion authority
- Affected capabilities: `PO-4.4`, `SF-1.1`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of single-operator trust, exact staged artifact lineage, evidence production, rollback lineage, enforcement gaps, and signed-provenance alternatives
- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `PROMO-01`

## Context

Production promotion must prove that the service images selected by the production overlay are the exact bytes that were deployed and validated in staging. Rebuilding from the same source commit or release tag is not equivalent: a changed toolchain, dependency, input, or compromised build step can produce different bytes.

FireMUD currently has a single-admin/operator trust model. The same administrative trust domain controls the repository, CI configuration, production promotion, and the credentials that could sign external provenance. Requiring detached signatures, a transparency service, or a complete SLSA-style supply-chain system now would add operational machinery without creating meaningful separation from that authority.

The existing in-repository promotion contract is directionally correct but is not completely enforced. In particular, the overlay validation script skips promotion preflight when a production overlay changes without an attestation. The current checker also does not completely validate required attestation fields and schema version, staging commit existence in Git history, or the release digest manifest and all of its cross-references. The target trust model therefore needs to be distinguished from current implementation and proof.

## Decision

For the current single-admin/operator phase, Git-reviewed, CI-validated in-repository evidence is the production promotion trust root. The production overlay, staging deployment record, production promotion attestation, and release digest manifest form one machine-verifiable evidence chain. A production overlay change without the required attestation fails validation; absence of evidence is not a reason to skip the gate.

Promotion tooling machine-generates the candidate attestation and related evidence from observed staging state. It records the applied staging overlay commit, exact running service image digests, successful deployment and smoke evidence, required compliance references, production overlay target, rollback classification, and release manifest reference. The operator reviews and approves that generated evidence and the resulting Git change. Routine digest and lineage fields are not manually reconstructed from memory or copied from intended configuration when observed state is available.

Every promoted service digest must be byte-identical to the digest recorded by the successful staging deployment and its live-state observation. Promotion may retag or copy an already-identified artifact without changing its bytes, but it must not rebuild it. Any rebuilt artifact requires a new staging deployment, observation, and promotion evidence chain.

CI validates the evidence schema and required fields, confirms that the referenced staging commit exists in Git history, resolves the corresponding successful staging deployment record, verifies its live-state and required compliance evidence, compares every staged, attested, manifested, and production-overlay digest, and verifies all cross-references. Official releases additionally require the release digest manifest to bind the release or deployment reference, source commit, promotion attestation, staging deployment record, and exact digest set.

Rollback restores a previously proved digest set by referencing its original promotion attestation and evidence lineage. It does not fabricate a new staging history for artifacts that were already promoted. Any new compatibility, recovery-readiness, or current-environment checks required before rollback remain new execution evidence and do not replace the original artifact-lineage proof.

This trust root protects against accidental rebuilds, digest drift, missing staging proof, malformed or inconsistent promotion records, and undocumented promotion. Git history also provides an auditable record of reviewed changes. It does not protect against an administrator who is malicious or compromised, a compromised repository authority, or CI operating under the same compromised authority. Documentation and operational claims must state that boundary rather than treating an unsigned in-repository record as independent cryptographic provenance.

Detached signatures, an external transparency log, and SLSA-style provenance are deferred. Introduce an independent signed provenance layer before the trust model includes independent approvers, untrusted build or promotion actors, externally supplied production artifacts, regulatory separation of duties, or multiple administrative trust domains. The canonical in-repository promotion index may continue to reference that stronger evidence when introduced.

## Consequences

- Production uses the exact artifacts exercised in staging, and a rebuild cannot silently enter the promotion path under the same source or release identity.
- Machine-generated evidence reduces routine operator transcription work while preserving explicit human review of what will be promoted.
- Missing or inconsistent promotion evidence fails closed, so a tooling or evidence outage can delay a production change.
- Rollback retains an auditable connection to the original artifact proof without claiming that historical staging was repeated.
- The repository remains the deterministic promotion index and audit record for the current operating model.
- This model does not add protection from compromise of the shared administrator, repository, and CI trust domain.
- CI and promotion tooling require further work before the documented contract is fully enforced.

## Alternatives Considered

### Require Detached Signatures, External Transparency, or SLSA Provenance Now

This is the strongest alternative. It can provide cryptographic artifact identity, tamper-evident publication, builder provenance, and independently verifiable approval when signing and verification authorities are genuinely separated. It is rejected as a current prerequisite because the single administrator controls the repository, CI, production environment, and likely signing credentials. Under that model, signatures mostly add key custody, rotation, outage, verification, and release-process overhead without materially resisting compromise of the controlling authority. It becomes appropriate when an upgrade trigger creates an independent or untrusted trust boundary.

### Trust Source Commits or Release Tags and Rebuild for Production

Rejected because source identity does not prove byte identity. Rebuilding can change dependencies, toolchains, build inputs, or output and bypasses the exact artifact that staging tested.

### Let the Operator Author Promotion Evidence Manually

Rejected as the normal path because manually transcribed digests and evidence references are error-prone and can describe intended rather than observed staging state. The operator remains the reviewer and approver, while tooling produces deterministic fields from authoritative observations.

## Implementation and Proof Obligations

Provide a canonical evidence producer that reads the successful staging deployment record and observed live-state evidence, emits schema-valid promotion evidence, and presents the exact candidate digest set and cross-references for operator review. Generated evidence must remain reviewable as ordinary repository content and must not silently select a different production target.

Production overlay validation must fail when the required attestation is absent, duplicated, malformed, or unrelated to the overlay change. It must validate the complete field and schema contract, confirm the staging overlay commit in Git history, validate the staging deployment and live-state records, compare exact service digest maps, validate compliance and recovery references, and validate the release digest manifest and all bidirectional references required for an official release.

Proof must cover a missing attestation, multiple attestations, schema mismatch, omitted required field, nonexistent staging commit, missing or failed staging deployment, intended-versus-observed digest drift, incomplete digest maps, rebuilt bytes under the same source identity, mismatched production overlay, invalid release manifest, broken evidence reference, rollback to an original attestation, and machine-generated evidence reviewed without manual digest reconstruction.

Current enforcement is incomplete. A production overlay PR without an attestation currently causes static promotion preflight to be skipped rather than failed. The current promotion checker does not fully enforce the attestation field/schema contract, staging commit existence in Git history, or release-manifest presence, schema, digest equality, and cross-reference validation. This decision records the target contract and does not claim those gaps are closed.

## Reversibility and Revisit Triggers

Evidence schemas, generators, and repository paths may evolve while preserving exact staged-byte lineage and fail-closed validation. Add independent signed provenance before adopting independent approvers, untrusted build or promotion actors, externally supplied production artifacts, regulatory separation of duties, or multiple administrative trust domains. Revisit the in-repository trust root if repository availability or evidence volume makes deterministic promotion review impractical, but do not weaken exact digest and no-rebuild guarantees.
