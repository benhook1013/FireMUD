#!/usr/bin/env python3
"""Provision the five fixed, scoped trust-bootstrap kubeconfigs.

This is an operator-only helper.  Without ``--apply --confirm`` or
``--finalize --confirm --confirm-external-deletion`` it prints a non-secret
plan and performs no Kubernetes or GitHub operation.  Applying requires an
explicitly selected ``kubectl`` context authenticated as a non-ServiceAccount
member of ``system:masters``.  Staging and publishing the new credentials is
safe while the legacy credential remains present; the separate finalization
mode performs the guarded legacy revocation only after a complete proof.

The apply path creates one fresh ``kubernetes.io/service-account-token`` Secret
per fixed ServiceAccount, waits for the controller to populate its token, and
builds a private kubeconfig from the selected context's API server and CA.  It
then verifies the generated identity and a small RBAC boundary before piping
the kubeconfig directly to ``gh secret set``.  Token and kubeconfig bytes are
never put in argv, logs, or command diagnostics.

This helper does not install a CA, create an issuer, or modify any workflow or
manifest.  Only finalization revokes the exact legacy ClusterRoleBinding,
ServiceAccount, annotated token Secrets, and repository ``PREVIEW_KUBECONFIG``
secret.  A failed run before GitHub publication removes only the exact new
token Secrets created by this run.  If GitHub publication has begun, newly
created Secrets are retained so an already-published kubeconfig cannot be
invalidated automatically; the caller must inspect the reported failure
before retrying.
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path

CONTROL_NAMESPACE = "firemud-system"
LEGACY_NAMESPACE = "kube-system"
LEGACY_SERVICE_ACCOUNT = "preview-deployer"
LEGACY_CLUSTER_ROLE_BINDING = "preview-deployer"
TOKEN_SECRET_TYPE = "kubernetes.io/service-account-token"
PROVISIONER = "firemud-scoped-kubeconfig-provisioner"
REPOSITORY = "benhook1013/FireMUD"
REPOSITORY_LEGACY_SECRET = "PREVIEW_KUBECONFIG"
DEVELOP_BRANCH = "develop"
PROOF_NAMESPACE_RE = re.compile(r"^pr-([1-9][0-9]{0,50})$")


@dataclass(frozen=True)
class Credential:
    service_account: str
    environment: str
    github_secret: str
    # (verb, resource, scope, expected answer) probes only check existing
    # authorization; they never create or mutate an object. Scope is None for
    # cluster-scoped checks, or "proof"/"control" for a namespace check.
    rbac_probes: tuple[tuple[str, str, str | None, str], ...]


CREDENTIALS = (
    Credential(
        "firemud-preview-namespace-manager",
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_PREVIEW_NAMESPACE_MANAGER_KUBECONFIG",
        (
            ("get", "namespaces", None, "yes"),
            ("get", "secrets", "proof", "no"),
        ),
    ),
    Credential(
        "firemud-preview-runtime",
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_PREVIEW_RUNTIME_KUBECONFIG",
        (
            ("get", "secrets", "proof", "yes"),
            ("get", "namespaces", None, "no"),
        ),
    ),
    Credential(
        "firemud-standalone-certificate-writer",
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_STANDALONE_CERTIFICATE_KUBECONFIG",
        (
            ("get", "certificates.cert-manager.io", "proof", "yes"),
            ("create", "secrets", "proof", "no"),
        ),
    ),
    Credential(
        "firemud-hosted-identity-requester",
        "trusted-hosted-cluster",
        "TRUSTED_HOSTED_IDENTITY_REQUESTER_KUBECONFIG",
        (
            ("get", "hostedenvironmentidentities.platform.firemud.dev", "control", "yes"),
            ("get", "namespaces", None, "no"),
        ),
    ),
    Credential(
        "firemud-preview-ca-recovery",
        "trusted-preview-ca-recovery",
        "TRUSTED_PREVIEW_CA_RECOVERY_KUBECONFIG",
        (
            ("get", "validatingadmissionpolicies", None, "yes"),
            ("get", "namespaces", None, "no"),
        ),
    ),
)

_CREDENTIALS_BY_NAME = {item.service_account: item for item in CREDENTIALS}
_CONTEXT_RE = re.compile(r"^[^\x00-\x1f\x7f]+$")
_KUBERNETES_NAME_RE = re.compile(r"^[a-z0-9]([-a-z0-9]*[a-z0-9])?$")


class ProvisioningError(RuntimeError):
    """An expected refusal or non-secret command failure."""


def _command_description(command: list[str]) -> str:
    # Never include command input or a kubeconfig path in diagnostics.
    return command[0] if command else "command"


def run_command(
    command: list[str], *, input_bytes: bytes | None = None
) -> bytes:
    """Run a command while withholding stdout/stderr from operator logs."""

    result = subprocess.run(
        command,
        input=input_bytes,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise ProvisioningError(f"{_command_description(command)} failed")
    return result.stdout


def kubectl_command(context: str, *args: str) -> list[str]:
    return ["kubectl", "--context", context, *args]


def kubectl_json(context: str, *args: str) -> dict:
    output = run_command(kubectl_command(context, *args, "-o", "json"))
    try:
        value = json.loads(output)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProvisioningError("kubectl returned malformed JSON") from exc
    if not isinstance(value, dict):
        raise ProvisioningError("kubectl returned an unexpected JSON object")
    return value


def kubectl_text(context: str, *args: str) -> str:
    try:
        return run_command(kubectl_command(context, *args)).decode("utf-8").strip()
    except UnicodeDecodeError as exc:
        raise ProvisioningError("kubectl returned non-UTF-8 text") from exc


def select_credentials(names: Iterable[str] | None) -> tuple[Credential, ...]:
    if names is None:
        return CREDENTIALS
    selected = []
    for name in names:
        credential = _CREDENTIALS_BY_NAME.get(name)
        if credential is None:
            raise ProvisioningError(f"unknown fixed ServiceAccount: {name}")
        if credential not in selected:
            selected.append(credential)
    if not selected:
        raise ProvisioningError("at least one fixed ServiceAccount is required")
    return tuple(selected)


def token_secret_name(service_account: str) -> str:
    if service_account not in _CREDENTIALS_BY_NAME:
        raise ProvisioningError("token Secret requested for an unapproved ServiceAccount")
    # A fresh generated suffix prevents an old token Secret from being
    # overwritten if an operator runs a later, intentional rotation.
    return f"firemud-{service_account}-token-{secrets.token_hex(8)}"


def service_account_token_secret(name: str, credential: Credential) -> dict:
    if credential.service_account not in _CREDENTIALS_BY_NAME:
        raise ProvisioningError("token Secret requested for an unapproved ServiceAccount")
    if not _KUBERNETES_NAME_RE.fullmatch(name):
        raise ProvisioningError("generated token Secret name is not a Kubernetes name")
    return {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {
            "name": name,
            "namespace": CONTROL_NAMESPACE,
            "annotations": {
                "kubernetes.io/service-account.name": credential.service_account,
                "firemud.dev/provisioned-by": PROVISIONER,
            },
            "labels": {
                "app.kubernetes.io/name": "firemud-trust-bootstrap",
                "app.kubernetes.io/component": "scoped-kubeconfig-token",
            },
        },
        "type": TOKEN_SECRET_TYPE,
    }


def build_kubeconfig(
    cluster_name: str,
    server: str,
    ca_data: str,
    credential: Credential,
    token: str,
) -> bytes:
    """Return a JSON kubeconfig (valid YAML) containing only the new token."""

    if not token or any(character.isspace() for character in token):
        raise ProvisioningError("service-account token is empty or malformed")
    if not server.startswith("https://") or not ca_data:
        raise ProvisioningError("selected context must provide an HTTPS server and embedded CA")
    kubeconfig_context = credential.service_account
    kubeconfig = {
        "apiVersion": "v1",
        "kind": "Config",
        "clusters": [{"name": cluster_name, "cluster": {
            "server": server,
            "certificate-authority-data": ca_data,
        }}],
        "contexts": [{"name": kubeconfig_context, "context": {
            "cluster": cluster_name,
            "namespace": CONTROL_NAMESPACE,
            "user": credential.service_account,
        }}],
        "current-context": kubeconfig_context,
        "users": [{"name": credential.service_account, "user": {"token": token}}],
    }
    return json.dumps(kubeconfig, separators=(",", ":")).encode("utf-8")


def require_operator(context: str) -> None:
    selected_context = kubectl_text(context, "config", "current-context")
    if selected_context != context:
        raise ProvisioningError("selected kubectl context is not the current context")
    identity = kubectl_json(context, "auth", "whoami")
    user_info = identity.get("status", {}).get("userInfo", {})
    if not isinstance(user_info, dict):
        raise ProvisioningError("selected kubectl context did not return an operator identity")
    username = user_info.get("username", "")
    groups = user_info.get("groups", [])
    if not isinstance(username, str) or username.startswith("system:serviceaccount:"):
        raise ProvisioningError("selected kubectl context must not be a ServiceAccount")
    if not isinstance(groups, list) or "system:masters" not in groups:
        raise ProvisioningError("selected kubectl context caller is not a system:masters operator")


def gh_json(*args: str) -> dict:
    output = run_command(["gh", *args])
    try:
        value = json.loads(output)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProvisioningError("gh returned malformed JSON") from exc
    if not isinstance(value, dict):
        raise ProvisioningError("gh returned an unexpected JSON object")
    return value


def environment_secret_names(environment: str) -> set[str]:
    output = run_command(
        [
            "gh",
            "secret",
            "list",
            "--repo",
            REPOSITORY,
            "--env",
            environment,
            "--json",
            "name",
            "--limit",
            "100",
        ]
    )
    try:
        values = json.loads(output)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProvisioningError("gh returned malformed environment secret JSON") from exc
    if not isinstance(values, list):
        raise ProvisioningError("gh returned an unexpected environment secret list")
    names = set()
    for value in values:
        if isinstance(value, dict) and isinstance(value.get("name"), str):
            names.add(value["name"])
    return names


def verify_environment_policies() -> None:
    environments = {credential.environment for credential in CREDENTIALS}
    for environment in sorted(environments):
        details = gh_json(
            "api",
            f"repos/{REPOSITORY}/environments/{environment}",
        )
        branch_policy = details.get("deployment_branch_policy")
        if branch_policy != {
            "protected_branches": False,
            "custom_branch_policies": True,
        }:
            raise ProvisioningError(
                f"GitHub Environment {environment} does not use an exact custom branch policy"
            )
        policies = gh_json(
            "api",
            f"repos/{REPOSITORY}/environments/{environment}/deployment-branch-policies?per_page=100",
        ).get("branch_policies")
        if not isinstance(policies, list) or len(policies) != 1:
            raise ProvisioningError(
                f"GitHub Environment {environment} must have exactly one branch policy"
            )
        policy = policies[0]
        if policy.get("name") != DEVELOP_BRANCH or policy.get("type") != "branch":
            raise ProvisioningError(
                f"GitHub Environment {environment} branch policy is not exactly develop"
            )


def verify_environment_secrets(credentials: Iterable[Credential]) -> None:
    expected: dict[str, set[str]] = {}
    for credential in credentials:
        expected.setdefault(credential.environment, set()).add(credential.github_secret)
    for environment, names in expected.items():
        missing = names - environment_secret_names(environment)
        if missing:
            raise ProvisioningError(
                f"GitHub Environment {environment} is missing a fixed kubeconfig secret"
            )


def require_proof_namespace(context: str, namespace: str) -> None:
    match = PROOF_NAMESPACE_RE.fullmatch(namespace)
    if not match:
        raise ProvisioningError("--proof-namespace must be a canonical pr-N namespace")
    namespace_object = kubectl_json(context, "get", "namespace", namespace)
    metadata = namespace_object.get("metadata", {})
    labels = metadata.get("labels", {})
    if (
        metadata.get("name") != namespace
        or labels.get("firemud.dev/preview") != "true"
        or labels.get("firemud.dev/pr-number") != match.group(1)
    ):
        raise ProvisioningError("proof namespace is not the canonical existing preview namespace")

    for role_name in ("firemud-preview-runtime", "firemud-standalone-certificate-writer"):
        binding = kubectl_json(
            context,
            "-n",
            namespace,
            "get",
            "rolebinding",
            role_name,
        )
        binding_metadata = binding.get("metadata", {})
        expected_subject = {
            "kind": "ServiceAccount",
            "name": role_name,
            "namespace": CONTROL_NAMESPACE,
        }
        if (
            binding.get("apiVersion") != "rbac.authorization.k8s.io/v1"
            or binding.get("kind") != "RoleBinding"
            or binding_metadata.get("name") != role_name
            or binding_metadata.get("namespace") != namespace
            or binding.get("roleRef")
            != {
                "apiGroup": "rbac.authorization.k8s.io",
                "kind": "ClusterRole",
                "name": role_name,
            }
            or binding.get("subjects") != [expected_subject]
        ):
            raise ProvisioningError(
                f"proof namespace RoleBinding {role_name} is not the canonical fixed binding"
            )


def read_selected_cluster(context: str) -> tuple[str, str, str]:
    config = kubectl_json(context, "config", "view", "--raw", "--minify")
    clusters = config.get("clusters")
    if not isinstance(clusters, list) or len(clusters) != 1:
        raise ProvisioningError("selected context did not resolve to exactly one cluster")
    cluster_entry = clusters[0]
    cluster_name = cluster_entry.get("name")
    cluster = cluster_entry.get("cluster", {})
    server = cluster.get("server")
    ca_data = cluster.get("certificate-authority-data")
    if not isinstance(cluster_name, str) or not cluster_name:
        raise ProvisioningError("selected context has no cluster name")
    if not isinstance(server, str) or not server.startswith("https://"):
        raise ProvisioningError("selected context must use an HTTPS Kubernetes API server")
    if not isinstance(ca_data, str) or not ca_data:
        # Refuse a local CA path: the resulting secret must be portable and
        # must never depend on an operator workstation filesystem.
        raise ProvisioningError("selected context must embed certificate-authority-data")
    try:
        base64.b64decode(ca_data, validate=True)
    except (ValueError, base64.binascii.Error) as exc:
        raise ProvisioningError("selected context contains malformed CA data") from exc
    return cluster_name, server, ca_data


def read_service_account(context: str, credential: Credential) -> None:
    service_account = kubectl_json(
        context,
        "-n",
        CONTROL_NAMESPACE,
        "get",
        "serviceaccount",
        credential.service_account,
    )
    metadata = service_account.get("metadata", {})
    if metadata.get("name") != credential.service_account or metadata.get("namespace") != CONTROL_NAMESPACE:
        raise ProvisioningError(f"fixed ServiceAccount {credential.service_account} has unexpected identity")


def ensure_secret_absent(context: str, name: str) -> None:
    existing = kubectl_text(
        context,
        "-n",
        CONTROL_NAMESPACE,
        "get",
        "secret",
        name,
        "--ignore-not-found",
        "-o",
        "name",
    )
    if existing:
        raise ProvisioningError("generated token Secret name unexpectedly already exists")


def create_token_secret(context: str, name: str, credential: Credential) -> None:
    document = json.dumps(
        service_account_token_secret(name, credential), separators=(",", ":")
    ).encode("utf-8")
    # create (rather than apply) ensures an existing object cannot be
    # overwritten, even if a generated name collision is ever observed.
    run_command(kubectl_command(context, "create", "-f", "-"), input_bytes=document)


def read_token_secret(context: str, name: str, credential: Credential) -> tuple[str, str]:
    secret = kubectl_json(
        context,
        "-n",
        CONTROL_NAMESPACE,
        "get",
        "secret",
        name,
    )
    metadata = secret.get("metadata", {})
    annotations = metadata.get("annotations", {})
    if (
        metadata.get("name") != name
        or metadata.get("namespace") != CONTROL_NAMESPACE
        or annotations.get("kubernetes.io/service-account.name") != credential.service_account
        or secret.get("type") != TOKEN_SECRET_TYPE
    ):
        raise ProvisioningError("created token Secret readback did not match the fixed ServiceAccount")
    data = secret.get("data", {})
    if (
        not isinstance(data, dict)
        or not data.get("token")
        or not data.get("ca.crt")
        or not data.get("namespace")
    ):
        raise ProvisioningError("service-account token Secret was not populated by Kubernetes")
    try:
        token = base64.b64decode(data["token"], validate=True).decode("ascii")
        ca_data = data["ca.crt"]
        base64.b64decode(ca_data, validate=True)
        token_namespace = base64.b64decode(data["namespace"], validate=True).decode("utf-8")
    except (KeyError, TypeError, UnicodeDecodeError, ValueError, base64.binascii.Error) as exc:
        raise ProvisioningError("service-account token Secret contains malformed data") from exc
    if token_namespace != CONTROL_NAMESPACE:
        raise ProvisioningError("service-account token Secret targets an unexpected namespace")
    if not token or any(character.isspace() for character in token):
        raise ProvisioningError("service-account token Secret contains an invalid token")
    return token, ca_data


def wait_for_token(
    context: str, name: str, credential: Credential, timeout_seconds: float
) -> tuple[str, str]:
    deadline = time.monotonic() + timeout_seconds
    while True:
        try:
            return read_token_secret(context, name, credential)
        except ProvisioningError as exc:
            if "was not populated" not in str(exc) or time.monotonic() >= deadline:
                raise
            time.sleep(1)


def verify_generated_kubeconfig(kubeconfig: bytes, credential: Credential, proof_namespace: str) -> None:
    with tempfile.NamedTemporaryFile(prefix="firemud-scoped-", suffix=".kubeconfig") as file:
        file.write(kubeconfig)
        file.flush()
        Path(file.name).chmod(0o600)
        command = ["kubectl", "--kubeconfig", file.name, "auth", "whoami", "-o", "json"]
        identity_raw = run_command(command)
        try:
            identity = json.loads(identity_raw)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ProvisioningError("generated kubeconfig returned malformed identity JSON") from exc
        username = identity.get("status", {}).get("userInfo", {}).get("username")
        expected_username = f"system:serviceaccount:{CONTROL_NAMESPACE}:{credential.service_account}"
        if username != expected_username:
            raise ProvisioningError("generated kubeconfig authenticated as an unexpected identity")
        for verb, resource, scope, expected in credential.rbac_probes:
            args = ["auth", "can-i", verb, resource]
            namespace = {
                "proof": proof_namespace,
                "control": CONTROL_NAMESPACE,
            }.get(scope)
            if scope not in (None, "proof", "control"):
                raise ProvisioningError("generated kubeconfig contains an unknown RBAC probe scope")
            if namespace:
                args.extend(["-n", namespace])
            answer = run_command(["kubectl", "--kubeconfig", file.name, *args]).decode("utf-8").strip()
            if answer != expected:
                raise ProvisioningError(
                    f"RBAC probe for {credential.service_account} returned an unexpected answer"
                )


def publish_kubeconfig(credential: Credential, kubeconfig: bytes) -> None:
    # The secret value is stdin only; gh output and diagnostics remain hidden.
    run_command(
        [
            "gh",
            "secret",
            "set",
            credential.github_secret,
            "--repo",
            REPOSITORY,
            "--env",
            credential.environment,
        ],
        input_bytes=kubeconfig,
    )


def cleanup_created_secrets(context: str, names: list[str]) -> bool:
    cleanup_failed = False
    for name in names:
        try:
            run_command(
                kubectl_command(
                    context,
                    "-n",
                    CONTROL_NAMESPACE,
                    "delete",
                    "secret",
                    name,
                    "--ignore-not-found",
                    "--wait=true",
                )
            )
        except ProvisioningError:
            cleanup_failed = True
    return not cleanup_failed


def repository_secret_names() -> set[str]:
    output = run_command(
        [
            "gh",
            "secret",
            "list",
            "--repo",
            REPOSITORY,
            "--json",
            "name",
            "--limit",
            "100",
        ]
    )
    try:
        values = json.loads(output)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProvisioningError("gh returned malformed repository secret JSON") from exc
    if not isinstance(values, list):
        raise ProvisioningError("gh returned an unexpected repository secret list")
    return {
        value["name"]
        for value in values
        if isinstance(value, dict) and isinstance(value.get("name"), str)
    }


def legacy_token_secret_refs(context: str) -> list[tuple[str, str]]:
    # The legacy ServiceAccount is namespace-scoped. Restrict discovery to its
    # namespace and, while it exists, require the exact UID annotation so an
    # unrelated ServiceAccount with the same name elsewhere cannot be removed.
    service_account_output = run_command(
        kubectl_command(
            context,
            "-n",
            LEGACY_NAMESPACE,
            "get",
            "serviceaccount",
            LEGACY_SERVICE_ACCOUNT,
            "--ignore-not-found",
            "-o",
            "json",
        )
    )
    legacy_uid = None
    if service_account_output.strip():
        try:
            service_account = json.loads(service_account_output)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ProvisioningError("legacy ServiceAccount readback returned malformed JSON") from exc
        legacy_uid = service_account.get("metadata", {}).get("uid")
        if not isinstance(legacy_uid, str) or not legacy_uid:
            raise ProvisioningError("legacy ServiceAccount has no usable UID")

    secrets_object = kubectl_json(context, "-n", LEGACY_NAMESPACE, "get", "secrets")
    refs = []
    for item in secrets_object.get("items", []):
        if not isinstance(item, dict):
            continue
        metadata = item.get("metadata", {})
        annotations = metadata.get("annotations", {})
        if (
            item.get("type") == TOKEN_SECRET_TYPE
            and annotations.get("kubernetes.io/service-account.name") == LEGACY_SERVICE_ACCOUNT
            and isinstance(metadata.get("name"), str)
            and (
                legacy_uid is None
                or annotations.get("kubernetes.io/service-account.uid") == legacy_uid
            )
        ):
            refs.append((LEGACY_NAMESPACE, metadata["name"]))
    return sorted(set(refs))


def provisioner_token_secret_refs(
    context: str, service_accounts: Iterable[str]
) -> dict[str, list[str]]:
    selected = set(service_accounts)
    secrets_object = kubectl_json(context, "-n", CONTROL_NAMESPACE, "get", "secrets")
    refs = {service_account: [] for service_account in selected}
    for item in secrets_object.get("items", []):
        if not isinstance(item, dict) or item.get("type") != TOKEN_SECRET_TYPE:
            continue
        metadata = item.get("metadata", {})
        annotations = metadata.get("annotations", {})
        service_account = annotations.get("kubernetes.io/service-account.name")
        name = metadata.get("name")
        if (
            service_account in selected
            and annotations.get("firemud.dev/provisioned-by") == PROVISIONER
            and isinstance(name, str)
        ):
            refs[service_account].append(name)
    for names in refs.values():
        names.sort()
    return refs


def rotate_provisioner_tokens(context: str, retained: dict[str, str]) -> None:
    refs = provisioner_token_secret_refs(context, retained)
    for service_account, retained_name in retained.items():
        if retained_name not in refs[service_account]:
            raise ProvisioningError("newly published provisioner token Secret is missing")
        for name in refs[service_account]:
            if name == retained_name:
                continue
            run_command(
                kubectl_command(
                    context,
                    "-n",
                    CONTROL_NAMESPACE,
                    "delete",
                    "secret",
                    name,
                    "--ignore-not-found",
                    "--wait=true",
                )
            )
    remaining = provisioner_token_secret_refs(context, retained)
    for service_account, retained_name in retained.items():
        if remaining[service_account] != [retained_name]:
            raise ProvisioningError(
                f"provisioner token rotation left unexpected token Secrets for {service_account}"
            )


def verify_legacy_no_privileges(context: str, proof_namespace: str) -> None:
    identity = f"system:serviceaccount:{LEGACY_NAMESPACE}:{LEGACY_SERVICE_ACCOUNT}"
    probes = (
        ("get", "secrets", proof_namespace),
        ("create", "certificaterequests.cert-manager.io", proof_namespace),
        ("update", "issuers.cert-manager.io", proof_namespace),
        ("create", "clusterissuers.cert-manager.io", None),
    )
    for verb, resource, namespace in probes:
        command = kubectl_command(
            context,
            "auth",
            "can-i",
            verb,
            resource,
            f"--as={identity}",
        )
        if namespace:
            command.extend(["-n", namespace])
        try:
            answer = run_command(command).decode("utf-8").strip()
        except UnicodeDecodeError as exc:
            raise ProvisioningError("legacy identity RBAC probe returned non-UTF-8 text") from exc
        if answer != "no":
            raise ProvisioningError("legacy preview-deployer identity still has a forbidden privilege")


def finalize_legacy(context: str, proof_namespace: str) -> None:
    # The old binding and ServiceAccount are exact names. Token Secret names
    # are discovered only by their exact ServiceAccount annotation and token
    # type, never by a broad name pattern.
    token_refs = legacy_token_secret_refs(context)
    run_command(
        kubectl_command(
            context,
            "delete",
            "clusterrolebinding",
            LEGACY_CLUSTER_ROLE_BINDING,
            "--ignore-not-found",
            "--wait=true",
        )
    )
    run_command(
        kubectl_command(
            context,
            "-n",
            LEGACY_NAMESPACE,
            "delete",
            "serviceaccount",
            LEGACY_SERVICE_ACCOUNT,
            "--ignore-not-found",
            "--wait=true",
        )
    )
    for namespace, name in token_refs:
        run_command(
            kubectl_command(
                context,
                "-n",
                namespace,
                "delete",
                "secret",
                name,
                "--ignore-not-found",
                "--wait=true",
            )
        )
    remaining_binding = kubectl_text(
        context,
        "get",
        "clusterrolebinding",
        LEGACY_CLUSTER_ROLE_BINDING,
        "--ignore-not-found",
        "-o",
        "name",
    )
    remaining_service_account = kubectl_text(
        context,
        "-n",
        LEGACY_NAMESPACE,
        "get",
        "serviceaccount",
        LEGACY_SERVICE_ACCOUNT,
        "--ignore-not-found",
        "-o",
        "name",
    )
    if remaining_binding or remaining_service_account:
        raise ProvisioningError("legacy ClusterRoleBinding or ServiceAccount remains after revocation")
    if legacy_token_secret_refs(context):
        raise ProvisioningError("legacy ServiceAccount token Secrets remain after revocation")

    if REPOSITORY_LEGACY_SECRET in repository_secret_names():
        run_command(
            [
                "gh",
                "api",
                "--method",
                "DELETE",
                f"repos/{REPOSITORY}/actions/secrets/{REPOSITORY_LEGACY_SECRET}",
            ]
        )
    if REPOSITORY_LEGACY_SECRET in repository_secret_names():
        raise ProvisioningError("repository PREVIEW_KUBECONFIG secret remains after finalization")
    verify_legacy_no_privileges(context, proof_namespace)


def print_plan(credentials: tuple[Credential, ...]) -> None:
    print(
        "No cluster or GitHub mutation performed; use --apply --confirm "
        "or --finalize --confirm --confirm-external-deletion to execute."
    )
    for credential in credentials:
        print(f"{credential.service_account}: {credential.environment}/{credential.github_secret}")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True, help="explicit current kubectl admin context")
    parser.add_argument(
        "--proof-namespace",
        help="existing canonical pr-N namespace used for positive and negative RBAC probes",
    )
    parser.add_argument(
        "--credential",
        action="append",
        choices=tuple(_CREDENTIALS_BY_NAME),
        help="fixed ServiceAccount to provision (repeat; default: all five)",
    )
    parser.add_argument("--apply", action="store_true", help="perform guarded staging and publication")
    parser.add_argument(
        "--finalize",
        action="store_true",
        help="stage and publish all five credentials, then revoke the legacy credential",
    )
    parser.add_argument(
        "--confirm",
        action="store_true",
        help="confirm that live staging/publication is intended",
    )
    parser.add_argument(
        "--confirm-external-deletion",
        action="store_true",
        help="second confirmation for deleting legacy cluster and repository credentials",
    )
    parser.add_argument(
        "--token-timeout-seconds",
        type=float,
        default=120.0,
        help="maximum wait for each token Secret to populate (default: 120)",
    )
    args = parser.parse_args(argv)
    if not _CONTEXT_RE.fullmatch(args.context) or args.context.startswith("-"):
        parser.error("--context must be a non-empty context name without control characters")
    if args.token_timeout_seconds <= 0:
        parser.error("--token-timeout-seconds must be positive")
    if args.apply and args.finalize:
        parser.error("--apply and --finalize are mutually exclusive")
    if args.confirm and not (args.apply or args.finalize):
        parser.error("--confirm requires --apply or --finalize")
    if args.confirm_external_deletion and not args.finalize:
        parser.error("--confirm-external-deletion requires --finalize")
    if (args.apply or args.finalize) and not args.proof_namespace:
        parser.error("--proof-namespace is required for live staging or finalization")
    if args.finalize and args.credential:
        parser.error("--finalize always provisions all five fixed ServiceAccounts")
    return args


def apply(
    credentials: tuple[Credential, ...],
    context: str,
    proof_namespace: str,
    timeout_seconds: float,
    *,
    announce: bool = True,
) -> int:
    if shutil.which("kubectl") is None or shutil.which("gh") is None:
        raise ProvisioningError("kubectl and gh are required for live provisioning")
    require_operator(context)
    require_proof_namespace(context, proof_namespace)
    # Check both protected environments before creating any token Secret or
    # publishing any kubeconfig. The target repository is explicit in every
    # gh operation, so a different checkout cannot redirect publication.
    verify_environment_policies()
    cluster_name, server, ca_data = read_selected_cluster(context)

    created_secrets: list[str] = []
    published = False
    prepared: list[tuple[Credential, bytes]] = []
    retained_token_secrets: dict[str, str] = {}
    try:
        for credential in credentials:
            read_service_account(context, credential)
            secret_name = token_secret_name(credential.service_account)
            ensure_secret_absent(context, secret_name)
            create_token_secret(context, secret_name, credential)
            created_secrets.append(secret_name)
            retained_token_secrets[credential.service_account] = secret_name
            token, token_ca_data = wait_for_token(
                context, secret_name, credential, timeout_seconds
            )
            # Prefer the CA material populated alongside this token.  The
            # selected admin context is used only as the endpoint source and
            # as a final consistency check.
            if token_ca_data != ca_data:
                raise ProvisioningError("token Secret CA does not match the selected cluster context")
            kubeconfig = build_kubeconfig(
                cluster_name, server, token_ca_data, credential, token
            )
            verify_generated_kubeconfig(kubeconfig, credential, proof_namespace)
            prepared.append((credential, kubeconfig))

        for credential, kubeconfig in prepared:
            publish_kubeconfig(credential, kubeconfig)
            published = True
        verify_environment_secrets(credentials)
        # Rotate only after every selected kubeconfig has been published and
        # verified. On partial publication failure this path is not reached,
        # so the newly created tokens remain available for recovery/inspection.
        rotate_provisioner_tokens(context, retained_token_secrets)

    except ProvisioningError:
        if created_secrets and not published:
            cleanup_created_secrets(context, created_secrets)
        raise

    if announce:
        print(
            "Scoped kubeconfigs staged and published; legacy credential remains until "
            "explicit finalization."
        )
    return 0


def finalize(
    context: str,
    proof_namespace: str,
    timeout_seconds: float,
) -> int:
    # Re-run the complete stage and proof in this guarded invocation. This
    # makes finalization independent of an unverifiable local claim about a
    # previous process and guarantees all five kubeconfigs passed RBAC probes.
    apply(
        CREDENTIALS,
        context,
        proof_namespace,
        timeout_seconds,
        announce=False,
    )
    verify_environment_secrets(CREDENTIALS)
    finalize_legacy(context, proof_namespace)
    verify_environment_secrets(CREDENTIALS)
    print("Scoped kubeconfigs verified; legacy credential and repository secret finalized.")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])
    try:
        credentials = select_credentials(args.credential)
        if not args.apply and not args.finalize:
            print_plan(credentials)
            return 0
        if args.finalize:
            if not args.confirm or not args.confirm_external_deletion:
                raise ProvisioningError(
                    "finalization requires --confirm --confirm-external-deletion"
                )
            return finalize(args.context, args.proof_namespace, args.token_timeout_seconds)
        if not args.confirm:
            raise ProvisioningError("live staging requires --apply --confirm")
        return apply(
            credentials,
            args.context,
            args.proof_namespace,
            args.token_timeout_seconds,
        )
    except ProvisioningError as exc:
        print(f"scoped kubeconfig provisioning refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
