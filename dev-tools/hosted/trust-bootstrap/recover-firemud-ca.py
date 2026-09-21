#!/usr/bin/env python3
"""Validate or restore a FireMUD CA from a protected recovery Environment.

The bundle is supplied through an environment secret, never through argv or a
repository file.  ``pack`` writes the bundle only to stdout so an operator can
pipe it directly to ``gh secret set`` without leaving a plaintext copy.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path


CA_NAME = "firemud-grpc-ca"
RECOVERY_USER = "system:serviceaccount:firemud-system:firemud-preview-ca-recovery"
BOUNDARY_POLICIES = (
    "firemud-trust-bootstrap-certificaterequest",
    "firemud-trust-bootstrap-certificaterequest-subresources",
    "firemud-trust-bootstrap-certificate",
    "firemud-trust-bootstrap-certificate-status",
    "firemud-trust-bootstrap-ca-issuers",
    "firemud-trust-ca-secret-boundary",
    "firemud-trust-runtime-namespace-boundary",
    "firemud-trust-runtime-binding-boundary",
)


class RecoveryError(Exception):
    pass


def run(*args: str, input_bytes: bytes | None = None) -> bytes:
    result = subprocess.run(args, input=input_bytes, capture_output=True, check=False)
    if result.returncode:
        # kubectl and OpenSSL can echo submitted material in diagnostics.  Do
        # not forward stderr from a recovery operation to an Actions log.
        raise RecoveryError(f"{args[0]} {args[1] if len(args) > 1 else ''} failed")
    return result.stdout


def decode_bundle(raw: str) -> tuple[bytes, bytes, str]:
    try:
        payload = json.loads(raw)
        if set(payload) != {"version", "certificate", "privateKey", "fingerprintSha256"}:
            raise ValueError("unexpected bundle fields")
        if payload["version"] != 1:
            raise ValueError("unsupported bundle version")
        cert = base64.b64decode(payload["certificate"], validate=True)
        key = base64.b64decode(payload["privateKey"], validate=True)
        fingerprint = payload["fingerprintSha256"]
        if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
            raise ValueError("invalid fingerprint")
        return cert, key, fingerprint
    except (TypeError, KeyError, ValueError, json.JSONDecodeError) as exc:
        raise RecoveryError("recovery bundle is missing or malformed") from exc


def validate_material(cert: bytes, key: bytes, fingerprint: str) -> None:
    if not cert.startswith(b"-----BEGIN CERTIFICATE-----\n") or not key.startswith(
        b"-----BEGIN PRIVATE KEY-----\n"
    ):
        raise RecoveryError("CA must use PEM certificate and unencrypted PKCS8 private key")
    cert_der = run("openssl", "x509", "-inform", "PEM", "-outform", "DER", input_bytes=cert)
    if hashlib.sha256(cert_der).hexdigest() != fingerprint:
        raise RecoveryError("CA certificate fingerprint does not match the recovery bundle")
    text = run("openssl", "x509", "-noout", "-text", input_bytes=cert).decode("utf-8")
    if "Signature Algorithm: sha256WithRSAEncryption" not in text:
        raise RecoveryError("CA certificate must use an RSA SHA-256 signature")
    if not re.search(r"X509v3 Basic Constraints: critical\s+CA:TRUE, pathlen:0", text):
        raise RecoveryError("CA requires critical CA:TRUE with pathlen:0")
    if not re.search(r"X509v3 Key Usage: critical\s+.*Certificate Sign", text):
        raise RecoveryError("CA requires critical keyCertSign usage")
    cert_public = run("openssl", "x509", "-pubkey", "-noout", input_bytes=cert)
    key_public = run("openssl", "pkey", "-pubout", input_bytes=key)
    if cert_public != key_public:
        raise RecoveryError("CA certificate and private key do not match")
    public_text = run("openssl", "pkey", "-pubin", "-text", "-noout", input_bytes=key_public)
    if b"Public-Key: (4096 bit)" not in public_text:
        raise RecoveryError("CA private key must be RSA 4096")
    dates = run("openssl", "x509", "-noout", "-dates", input_bytes=cert).decode("ascii")
    try:
        fields = dict(line.split("=", 1) for line in dates.splitlines())
        not_before = datetime.strptime(fields["notBefore"], "%b %d %H:%M:%S %Y %Z").replace(
            tzinfo=timezone.utc
        )
        not_after = datetime.strptime(fields["notAfter"], "%b %d %H:%M:%S %Y %Z").replace(
            tzinfo=timezone.utc
        )
    except (KeyError, ValueError) as exc:
        raise RecoveryError("CA certificate validity could not be parsed") from exc
    if (not_after - not_before).total_seconds() != 730 * 24 * 60 * 60:
        raise RecoveryError("CA certificate must have an exact 730-day validity")
    run("openssl", "x509", "-checkend", "86400", "-noout", input_bytes=cert)
    # Only the public certificate is materialized for OpenSSL's two-file
    # self-signature verification. The private key stays in memory.
    with tempfile.NamedTemporaryFile(prefix="firemud-ca-cert-") as cert_file:
        cert_file.write(cert)
        cert_file.flush()
        run("openssl", "verify", "-CAfile", cert_file.name, cert_file.name)


def kubectl_json(*args: str) -> dict:
    try:
        return json.loads(run("kubectl", *args, "-o", "json"))
    except json.JSONDecodeError as exc:
        raise RecoveryError("Kubernetes returned malformed JSON") from exc


def require_live_boundary(expected_context: str) -> None:
    if run("kubectl", "config", "current-context").decode().strip() != expected_context:
        raise RecoveryError("current Kubernetes context does not match the explicitly approved context")
    identity = kubectl_json("auth", "whoami").get("status", {}).get("userInfo", {})
    if identity.get("username") != RECOVERY_USER:
        raise RecoveryError("recovery requires the dedicated scoped ServiceAccount")
    for name in BOUNDARY_POLICIES:
        policy = kubectl_json("get", "validatingadmissionpolicy", name)
        binding = kubectl_json("get", "validatingadmissionpolicybinding", name)
        if policy.get("spec", {}).get("failurePolicy") != "Fail":
            raise RecoveryError(f"admission policy {name} is not fail-closed")
        if binding.get("spec", {}).get("validationActions") != ["Deny"]:
            raise RecoveryError(f"admission binding {name} is not denying")
        if binding.get("spec", {}).get("policyName") != name:
            raise RecoveryError(f"admission binding {name} targets another policy")
    # A still-present legacy ServiceAccount can retain a previously minted
    # token even if one binding was narrowed.  Rotation requires its removal.
    if run(
        "kubectl", "-n", "kube-system", "get", "serviceaccount", "preview-deployer",
        "--ignore-not-found", "-o", "name",
    ).strip():
        raise RecoveryError("legacy preview-deployer ServiceAccount has not been revoked")
    if run(
        "kubectl", "get", "clusterrolebinding", "preview-deployer",
        "--ignore-not-found", "-o", "name",
    ).strip():
        raise RecoveryError("legacy preview-deployer ClusterRoleBinding remains installed")


def desired_secret(namespace: str, cert: bytes, key: bytes) -> dict:
    cert_key = "ca.crt" if namespace == "firemud-system" else "tls.crt"
    private_key = "ca.key" if namespace == "firemud-system" else "tls.key"
    return {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"name": CA_NAME, "namespace": namespace},
        "type": "Opaque" if namespace == "firemud-system" else "kubernetes.io/tls",
        "data": {
            cert_key: base64.b64encode(cert).decode("ascii"),
            private_key: base64.b64encode(key).decode("ascii"),
        },
    }


def restore(cert: bytes, key: bytes, context: str, issuer_namespace: str) -> None:
    require_live_boundary(context)
    for namespace in ("firemud-system", issuer_namespace):
        desired = desired_secret(namespace, cert, key)
        run(
            "kubectl", "apply", "--server-side", "--field-manager=firemud-ca-recovery", "-f", "-",
            input_bytes=json.dumps(desired, separators=(",", ":")).encode(),
        )
        observed = kubectl_json("-n", namespace, "get", "secret", CA_NAME)
        if observed.get("type") != desired["type"] or observed.get("data") != desired["data"]:
            raise RecoveryError(f"CA Secret readback failed in {namespace}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("pack", "verify", "restore"))
    parser.add_argument("--cert-file", type=Path)
    parser.add_argument("--key-file", type=Path)
    parser.add_argument("--expected-fingerprint", required=True)
    parser.add_argument("--context")
    parser.add_argument("--issuer-namespace", default="cert-manager")
    args = parser.parse_args()
    if not re.fullmatch(r"[0-9a-f]{64}", args.expected_fingerprint):
        parser.error("--expected-fingerprint must be lowercase SHA-256 hex")
    if not re.fullmatch(r"[a-z0-9]([-a-z0-9]*[a-z0-9])?", args.issuer_namespace):
        parser.error("--issuer-namespace must be a Kubernetes namespace name")
    try:
        if args.mode == "pack":
            if not args.cert_file or not args.key_file:
                parser.error("pack requires --cert-file and --key-file")
            cert, key = args.cert_file.read_bytes(), args.key_file.read_bytes()
            fingerprint = args.expected_fingerprint
        else:
            if args.cert_file or args.key_file:
                parser.error("verify/restore read only FIREMUD_CA_RECOVERY_BUNDLE")
            cert, key, fingerprint = decode_bundle(os.environ.get("FIREMUD_CA_RECOVERY_BUNDLE", ""))
            if fingerprint != args.expected_fingerprint:
                raise RecoveryError("bundle fingerprint differs from the expected trust anchor")
        validate_material(cert, key, fingerprint)
        if args.mode == "pack":
            if sys.stdout.isatty():
                raise RecoveryError("refusing to print the private recovery bundle to a terminal; pipe it directly to the protected secret store")
            print(json.dumps({
                "version": 1,
                "certificate": base64.b64encode(cert).decode("ascii"),
                "privateKey": base64.b64encode(key).decode("ascii"),
                "fingerprintSha256": fingerprint,
            }, separators=(",", ":")))
        elif args.mode == "restore":
            if not args.context:
                parser.error("restore requires --context")
            restore(cert, key, args.context, args.issuer_namespace)
            print("CA recovery readback passed for both fixed Secrets")
        else:
            print("CA recovery bundle and trust anchor verified")
        return 0
    except (OSError, RecoveryError) as exc:
        print(f"CA recovery refused: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
