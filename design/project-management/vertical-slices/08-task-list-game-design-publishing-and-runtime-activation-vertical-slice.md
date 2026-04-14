# `08` Game Design Publishing and Runtime Activation

Goal: translate FireMUD's already-rich design-time versioning, asset publication, and launch-control-plane architecture into one explicit slice family so publish, attestation, activation preflight, and patch/plugin rollout do not keep growing as scattered notes across Game Design, World Management, and runtime docs. Status: in progress.

## Implementation Notes

This domain is heavily designed already, but still under-sliced relative to its importance:

- Game Design owns version lifecycle state, publish orchestration, release manifests, and immutable release attestation.
- publish gating already has a clear target-state contract around draft digests, participant selection, and `published_release_bundle`.
- asset lifecycle rules, manifest integrity, tombstoning, repair, and purge safety are documented in detail.
- launch resolution and activation preflight are already specified around normalized template references, resolved launch descriptors, and attested release validation.
- script-only patch and plugin publication are already intentionally separate from runtime pinning and activation, but that separation is not yet represented as dedicated slice planning.

The problem is not missing architecture. The problem is that the architecture still lacks a coherent vertical-slice family, which makes implementation planning look thinner than the real target-state contract.

That is no longer just planning work: the family is now active in code.

- `08.1` now has a live immutable release-bundle attestation seam in `game-design-service`;
- the canonical publish-attempt / participant-observation framework is also now live;
- script-patch publish already uses that same framework with real Automation & Scripting plus Game Design control-plane digests;
- full-version publish still fails closed until the missing required domain digest participants exist, which keeps the family honest instead of pretending the gate is complete.

## Why This Slice Exists

Without a dedicated family here, implementation pressure will keep leaking into unrelated slices:

- publish safety gets buried under generic runtime hardening;
- asset lifecycle gets treated like generic storage plumbing;
- activation preflight gets spread across Game Session, World Management, and Game Design docs without one bounded delivery plan;
- script patch and plugin publication risk being treated as "scripting runtime work" instead of design-time control-plane publication and rollout visibility.

This family is the canonical home for that work.

## Target State

- full-version publish uses explicit digest-gated participant selection and ends with one immutable release attestation row.
- published assets and derived artifacts move through one canonical Game Design-owned export/manifest lifecycle with fail-closed integrity and purge rules.
- instance launch resolves one immutable launch descriptor before any runtime rows are created, and activation preflight validates the attested release rather than reconstructing state ad hoc.
- script-only patch publication and plugin publication remain distinct from runtime pinning/activation, with clear readiness and rollout visibility.

## Child Slices

- [08.1-task-list-publish-digest-gating-and-release-attestation-vertical-slice.md](./08.1-task-list-publish-digest-gating-and-release-attestation-vertical-slice.md)
- [08.2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice.md](./08.2-task-list-published-asset-manifest-and-purge-lifecycle-vertical-slice.md)
- [08.3-task-list-launch-descriptor-and-activation-preflight-vertical-slice.md](./08.3-task-list-launch-descriptor-and-activation-preflight-vertical-slice.md)
- [08.4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice.md](./08.4-task-list-script-patch-and-plugin-publication-boundaries-vertical-slice.md)

## Validation

- [ ] `./gradlew linkCheck lintMarkdown`
