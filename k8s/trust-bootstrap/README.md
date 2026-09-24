# Preview trust bootstrap

This is a prerequisite for the private Gateway–TCP Proxy bridge in #2713. It must be safe on `develop` with `previewStack.certificateIdentity.mode: standalone`: installing the issuance boundary does not activate the hosted identity controller or require public Telnet changes. The canonical hosted identity contract remains in [deployment environments](../../design/architecture/infrastructure/deployment-environments.md#hosted-identity-lifecycle); this page is the preview operator sequence and evidence checklist. It is not a production-CA procedure or a production-readiness claim.

## Required order

1. Apply the fixed `k8s/hosted-identity-controller/namespace.yaml` prerequisite, then install the scoped deployment, standalone certificate-writer, recovery, and admission resources from `k8s/trust-bootstrap` in this reviewed default-branch revision. Keep the existing controller inactive. Do not install `firemud-ca-issuer` or either `firemud-grpc-ca` Secret yet.
2. Replace the legacy `kube-system/preview-deployer` kubeconfig in trusted jobs with the five scoped credentials. For the initial proof namespace, run `bind-runtime-roles.sh` once with the explicit `system:masters` context because the proof namespace's exact runtime RoleBindings do not exist yet. After the manager credential has been provisioned and verified, run the same helper under the namespace-manager identity for later namespace bindings. Run `provision-scoped-kubeconfigs.py --apply --confirm` under an explicit `system:masters` context to create, authenticate, RBAC-check, and publish fresh kubeconfigs as `TRUSTED_HOSTED_PREVIEW_NAMESPACE_MANAGER_KUBECONFIG`, `TRUSTED_HOSTED_PREVIEW_RUNTIME_KUBECONFIG`, `TRUSTED_HOSTED_STANDALONE_CERTIFICATE_KUBECONFIG`, `TRUSTED_HOSTED_IDENTITY_REQUESTER_KUBECONFIG`, and recovery-only `TRUSTED_PREVIEW_CA_RECOVERY_KUBECONFIG`. This staging operation leaves the legacy credential intact so a publication failure cannot remove the only working deployment path. After the reviewed workflow split is on `develop`, run its separately confirmed `--finalize` operation: it rotates prior provisioner tokens, deletes only the exact legacy ClusterRoleBinding, ServiceAccount and token Secrets, removes the repository-scoped `PREVIEW_KUBECONFIG`, and proves the old identity cannot read a Secret, create a CertificateRequest, or change an Issuer. A manifest change or secret-name check alone is not rotation proof.
3. With the live issuer still absent, prove the `Deny` bindings are active and the cluster's actual RBAC/admission response rejects direct `CertificateRequest` creation and approval/status mutation, malformed or unauthorized `Certificate` creation, and issuer aliases/rewrites. Include omitted `issuerRef.group` and kind variants. Prove that the fixed standalone writer's canonical Certificate shape is admitted, while a changed SAN, usage, issuer, namespace, or name is denied. Record commands, caller identity, target namespace, response, and cleanup without logging a CSR private key or Secret data. Use server-side dry-run for negative cases where possible.
4. Prepare the separate recovery-only GitHub Environment `trusted-preview-ca-recovery`. It is restricted to `develop` and is never selected by a PR-controlled or routine deployment job. Provision its dedicated recovery kubeconfig, `FIREMUD_PREVIEW_CA_RECOVERY_BUNDLE` secret, and non-secret fingerprint/context variables. Test the recovery script's bundle validation, exact two-Secret reconstruction, refusal on a missing admission binding or surviving legacy credential, and the trusted `verify` workflow. Before the canonical CA exists, use disposable test material for this procedure; no test key may be installed as `firemud-grpc-ca` in the shared cluster.
5. Only after steps 1–4 have live evidence, generate a new preview-only RSA-4096 CA in a private, ephemeral operator directory. Use an unencrypted PKCS8 private key, critical `BasicConstraints CA:TRUE,pathlen:0`, critical `KeyUsage keyCertSign`, SHA-256, and 730-day validity. Do not use a cert-manager self-signed issuer to create the root. Compute the lowercase SHA-256 fingerprint of the DER certificate. Pipe `recover-firemud-ca.py pack` directly to `gh secret set FIREMUD_PREVIEW_CA_RECOVERY_BUNDLE -e trusted-preview-ca-recovery` without printing or retaining the bundle; set the fingerprint as a protected Environment variable. The GitHub Environment secret is the encrypted out-of-cluster recovery copy. Verify that copy through the trusted workflow before removing the ephemeral operator files. Do not put the key, bundle, or kubeconfig in a PR, Actions artifact, log, issue, chat, user PC, or password manager.
6. Run the reviewed recovery workflow's `restore` operation on `develop`. It accepts only the dedicated recovery ServiceAccount, the explicitly selected Kubernetes context, all eight live fail-closed admission bindings, and a revoked legacy ServiceAccount/ClusterRoleBinding. It writes only `firemud-system/firemud-grpc-ca` (`Opaque`, `ca.crt`/`ca.key`) and `cert-manager/firemud-grpc-ca` (`kubernetes.io/tls`, `tls.crt`/`tls.key`), then compares exact bytes through private readback. The copy is never printed. Install the fixed `firemud-ca-issuer` only after this recovery and readback pass.
7. With the controller still inactive, request the fixed standalone internal Gateway and TCP Proxy Certificates through the scoped writer, observe `Ready`, key-complete output Secrets, and the served certificate identities/chain. Repeat unauthorized direct CertificateRequest and Certificate tests after the issuer becomes Ready; an admission-only dry-run before issuance is not proof that unauthorized signing cannot occur. Retain only non-secret evidence. Controller bootstrap and the #2713 private-bridge rollout are later gates.

## Pre-CA handoff evidence (non-secret)

Before step 5, run the following from the explicitly approved Kubernetes context after the staged credentials have been finalized. It is a read-only boundary gate: it prints only caller, policy, binding, resource-name, and activation-mode metadata; it must not read Secret data, install resources, or repair a failed prerequisite. Save its output with the bootstrap record. Any failed assertion blocks CA generation and installation.

```bash
set -euo pipefail

trusted_context="${FIREMUD_HOSTED_IDENTITY_TRUSTED_CONTEXT:?set the approved Kubernetes context}"
current_context="$(kubectl config current-context)"
[[ "$current_context" == "$trusted_context" ]] || {
  echo "current Kubernetes context is not the explicitly approved context" >&2
  exit 1
}
operator_identity="$(kubectl auth whoami -o jsonpath='{.status.userInfo.username}')"
operator_groups="$(kubectl auth whoami -o jsonpath='{range .status.userInfo.groups[*]}{.}{"\n"}{end}')"
grep -Fx system:masters <<<"$operator_groups" >/dev/null || {
  echo "current Kubernetes operator is not a system:masters member" >&2
  exit 1
}
printf 'pre-ca-caller=%s context=%s\n' "$operator_identity" "$current_context"

admission_policies=(
  firemud-trust-bootstrap-certificaterequest
  firemud-trust-bootstrap-certificaterequest-subresources
  firemud-trust-bootstrap-certificate
  firemud-trust-bootstrap-certificate-status
  firemud-trust-bootstrap-ca-issuers
  firemud-trust-ca-secret-boundary
  firemud-trust-runtime-namespace-boundary
  firemud-trust-runtime-binding-boundary
)
for policy in "${admission_policies[@]}"; do
  failure_policy="$(kubectl get validatingadmissionpolicy "$policy" -o jsonpath='{.spec.failurePolicy}')"
  bound_policy="$(kubectl get validatingadmissionpolicybinding "$policy" -o jsonpath='{.spec.policyName}')"
  validation_actions="$(kubectl get validatingadmissionpolicybinding "$policy" -o jsonpath='{.spec.validationActions[*]}')"
  [[ "$failure_policy" == Fail && "$bound_policy" == "$policy" && "$validation_actions" == Deny ]] || {
    echo "trust-bootstrap admission boundary is not exactly Fail/Deny for ${policy}" >&2
    exit 1
  }
  printf 'admission=%s failurePolicy=%s policyName=%s validationActions=%s\n' \
    "$policy" "$failure_policy" "$bound_policy" "$validation_actions"
done

controller_resource="$(kubectl -n firemud-system get deployment firemud-hosted-identity-controller \
  --ignore-not-found -o name)"
if [[ -n "$controller_resource" ]]; then
  controller_mode="$(kubectl -n firemud-system get deployment firemud-hosted-identity-controller \
    -o jsonpath='{.spec.template.spec.containers[?(@.name=="controller")].env[?(@.name=="FIREMUD_HOSTED_IDENTITY_ACTIVATION_MODE")].value}')"
  [[ "$controller_mode" == paused ]] || {
    echo "hosted identity controller is active or missing its pause marker; keep it paused before CA installation" >&2
    exit 1
  }
else
  controller_mode=absent
fi
printf 'controllerActivation=%s\n' "${controller_mode:-absent}"

for resource in \
  "clusterissuer firemud-ca-issuer" \
  "-n firemud-system secret firemud-grpc-ca" \
  "-n cert-manager secret firemud-grpc-ca" \
  "-n kube-system serviceaccount preview-deployer" \
  "clusterrolebinding preview-deployer"; do
  resource_names=""
  if ! resource_names="$(kubectl get $resource --ignore-not-found -o name)"; then
    echo "pre-CA handoff could not verify resource absence: ${resource}" >&2
    exit 1
  fi
  if [[ -n "$resource_names" ]]; then
    echo "pre-CA handoff found a forbidden existing resource: ${resource}" >&2
    exit 1
  fi
done
printf 'pre-ca-resources=issuer-and-fixed-secrets-absent legacy-credential=revoked\n'
printf 'pre-ca-handoff=pass\n'
```

The resource loop intentionally reads only object names (`-o name`); it is not a CA or credential readback. This gate does not prove CA validity, cert-manager issuance, served identity, or consumer convergence. Those remain the private recovery, issuer, and later hosted-controller proof obligations below.

## Recovery copy and rotation

`FIREMUD_PREVIEW_CA_RECOVERY_BUNDLE` is a versioned JSON envelope with base64 PEM certificate and private key plus the public SHA-256 fingerprint. It is stored only as a GitHub Environment secret; the expected fingerprint is a separate Environment variable and must also match the deployed controller trust anchor. `preview-ca-recovery.yml` checks out only `develop`, selects only the recovery Environment, and accepts `verify` or `restore`. The script refuses malformed, mismatched, expired, non-RSA-4096, non-CA, or non-self-signed material. A fresh cluster first installs the same reviewed admission/RBAC boundary and reissues a scoped recovery kubeconfig; it must not reuse a token from the lost cluster. The recovery copy is independently usable without the original operator machine or password manager.

The preview root is valid for 730 days. Schedule expiry alerts at 180, 90, and 30 days and an operator-led replacement before expiry. Reissue every dependent leaf, update the protected recovery bundle and fingerprint, prove the new copy's verify/restore path, and only then change the controller trust anchor. Do not rotate one Secret copy independently. A separate future production environment must use a separate CA, protected GitHub Environment, scoped kubeconfig, recovery bundle, fingerprint, and live proof; reusing this procedure is not evidence that production is ready.

Scoped deployment kubeconfigs use dedicated ServiceAccount token Secrets labeled and annotated by the provisioning helper. Rotate them by staging a new verified token and Environment secret first; only after publication and RBAC proof does the helper remove older helper-owned tokens for that identity. Never overwrite or hand-edit a kubeconfig value, reuse the removed `preview-deployer` token, or delete a token before its replacement is verified.

## Current state

The recovery Environment and its `develop` branch policy were created on 2026-09-21. No recovery bundle, fingerprint, or recovery kubeconfig has been populated yet. No FireMUD internal CA has been generated or installed; the controller remains inactive. This note must be updated with the actual live proof before the prerequisite is called operationally complete.

## Hosted playable diagnostic after activation

Once the protected bootstrap above and controller activation have their own live proof, and Gate 1's exact base/head/merge/image Namespace annotations are present in the deployed trusted workflow, an eligible public preview can run the trusted default-branch `verify-runtime` job in `hosted-identity-request.yml`. The job waits for controller identity and runtime rollouts, runs the public Telnet `LOGIN → PLAY → LOOK` probe, then runs the first-party WSS flow with a fresh one-use connect token. Both probes receive the same trusted demo account, password, world, realm, and optional character mapping; ambient transport-specific overrides cannot split parity evidence. The WSS probe compares its structured LOOK room ID with Telnet, closes and reconnects with a second fresh token, checks that the consumed cookie is rejected as a replay, and checks final LOGOUT/close. It does not use candidate-controlled scripts after the runtime kubeconfig enters scope.

The trusted job revalidates the open PR's exact base, head, and merge binding, requested/deployed Namespace annotations, and runtime Namespace UID around diagnostics. It fails closed if the current parent stack has not yet absorbed Gate 1's annotation contract. Its bounded `hosted-playable-diagnostic-pr-N-<merge SHA>` artifact records the validated source tuple, Namespace UID and allocation, controller observed generation and five projection revisions, running application image tags and runtime digests, and the two transport outcomes. Raw Kubernetes objects, connect tokens, credentials, CA keys, and kubeconfigs are not artifacts. A passing artifact is a diagnostic for this exact deployed preview, not production readiness, browser acceptance under [ADR 0178](../../design/architecture/decisions/adr-0178-disposable-transport-complete-pr-preview-proof.md), or proof of CNI/local-node egress policy.

Before treating this as live evidence, the operator must separately retain non-secret records of the pre-CA admission and credential checks, recovery-copy verification, issuer readiness, controller activation order, served Gateway/TCP Proxy and gRPC certificate identities, projection rotation/convergence, and the [controller egress allow/deny matrix](../hosted-identity-controller/README.md#live-egress-evidence-before-activation). A denied target alone is insufficient to establish egress policy when the target might be unreachable; use a permitted positive control and record the CNI/local-node handling. This diagnostic covers reconnect after transport close, token-replay rejection, and final logout; an active simultaneous-controller takeover and post-logout replay-suppression proof remain separate player-session obligations, not implied by this artifact. Do not run a live active takeover against the shared demo character: it changes binding and presence state. That proof requires an explicitly isolated disposable character and two concurrent fresh-token sockets, and must not claim the still-unimplemented namespace-scoped atomic takeover contract. If any protected value, issuer, or controller is absent, stop before activation and report that precise gate. Local tests and rendered manifests are not substitutes for these live observations.
