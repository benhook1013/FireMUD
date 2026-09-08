## Summary

- make the legacy manual Velero path an explicitly isolated, manifest-only drill that cannot create workloads, restore volumes, target arbitrary namespaces, or imply player-facing recovery proof
- bind external-credential validation to the canonical environment expected-bindings manifest and immutable recovery-record provenance, while failing closed on unavailable or contradictory evidence
- distinguish current read-only incident diagnosis from target owner-directed reset/replay/remediation, and carry complete tick-effect and runtime-scope evidence through the incident runbooks

## Scope

This stacked corpus parcel contains the first correction passes for Unit 6D (environment, deployment, asset, backup, and delivery authority) and Unit 7C (incident and operational proof surfaces). It is based on PR #2661 so shared identity, replay, and tracker corrections are reviewed once. The restore workflow remains explicitly unavailable until a protected restore-cluster authentication binding is checked in; this PR does not claim that manifest restoration is database recovery, traffic-open proof, or a production recovery controller.

## Review progress

- Unit 6D Luna pass 1: 9 normalized useful findings, corrected in this parcel
- Unit 7C Luna pass 1: 6 normalized useful findings, corrected in this parcel
- focused independent recovery integration review: unsafe target selection, workload activation, credential-provenance, and unsupported nested `restoreSource` schema findings corrected
- further unit passes wait for the first CodeRabbit CLI correction cycle, then continue independently to their planned semantic terminal

## Validation

- `bash dev-tools/tests/architecture-doc-contracts.sh`
- `./gradlew linkCheck lintMarkdown`
- `bash -n` and ShellCheck for the changed restore helpers and contract tests
- actionlint for `.github/workflows/manual-backup-restore.yml`
- `bash dev-tools/tests/restore-cluster-contract.sh`
- `bash dev-tools/tests/validate-external-credentials-contract.sh`
- `git diff --check`

Stacked on #2661; review this PR against `codex/corpus-review-5a`.
