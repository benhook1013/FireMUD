#!/usr/bin/env python3
"""Generate pre-v1 Compose fixtures for Account's JWT signer lifecycle.

Compose smoke only, under the pre-v1 gate waiver (ADR 0185). Writes, under OUTPUT_DIR:

  kube/            TLS leaf, key and bearer token for dev-tools/smoke/pre-v1-kube-api-standin.py
  account-jwt-api/ Account's protected API binding (binding.json), serving CA pin and
                   validator inventory binding (validator-inventory.json)
  api-token/       Account's bearer token for that API
  materializer/    Materializer stand-in mTLS leaf (spiffe://firemud/ns/dev/sa/account-jwt-materializer)
  materializer-binding/ Account's protected materializer trust binding (binding.properties)
  readiness-binding/ Account's protected readiness trust binding (binding.properties); the
                   self-attested readiness driver presents its harness identity
  keyrings/        Account LOGIN request-digest and response-envelope keyrings
  identity.json    The fixed environment identity the stand-ins share

The init container copies these into root-owned volumes. Real cluster identity, Kubernetes RBAC
and a deployed materializer are owed before v1.
"""

import argparse
import base64
import hashlib
import json
import os
import secrets
import subprocess
import time
import uuid
from pathlib import Path

NAMESPACE = "dev"
ENVIRONMENT_ID = "compose"
CLUSTER_ID = "compose-local"
MATERIALIZER = "account-jwt-materializer"
API_HOST = "pre-v1-kube-api"
API_PORT = 6443


def canonical_json(value):
    # RFC 8785 matches this form for the string-only and string-list objects hashed here.
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()


def sha256_hex(data):
    return hashlib.sha256(data).hexdigest()


def b64url_raw(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def run(*args):
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def leaf(ca_dir, out_dir, name, sans, client):
    out_dir.mkdir(parents=True, exist_ok=True)
    key, csr, crt, ext = (out_dir / n for n in ("tls.key", "tls.csr", "tls.crt", "ext.cnf"))
    usage = "clientAuth" if client else "serverAuth"
    ext.write_text(
        "basicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\n"
        f"extendedKeyUsage={usage}\nsubjectAltName={','.join(sans)}\n"
    )
    run("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(key))
    run("openssl", "req", "-new", "-key", str(key), "-subj", f"/CN={name}", "-out", str(csr))
    run(
        "openssl", "x509", "-req", "-in", str(csr), "-CA", str(ca_dir / "ca.crt"),
        "-CAkey", str(ca_dir / "ca.key"), "-CAcreateserial", "-out", str(crt),
        "-days", "30", "-sha256", "-extfile", str(ext),
    )
    csr.unlink()
    ext.unlink()
    return crt, key


def spki_sha256(cert):
    der = subprocess.run(
        ["openssl", "x509", "-in", str(cert), "-pubkey", "-noout"], check=True, capture_output=True
    ).stdout
    spki = subprocess.run(
        ["openssl", "pkey", "-pubin", "-outform", "DER"], input=der, check=True, capture_output=True
    ).stdout
    return sha256_hex(spki)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("compose_ca_dir", type=Path, help="directory holding ca.crt and ca.key")
    parser.add_argument("--validator-leaf", type=Path, required=True,
                        help="Game Session mTLS leaf certificate (receiver Pod pin)")
    parser.add_argument("--validator-ip", required=True, help="fixed Game Session container IP")
    parser.add_argument("--account-leaf", type=Path, required=True,
                        help="Account mTLS leaf certificate (Account validator Pod pin)")
    args = parser.parse_args()
    out = args.output_dir
    out.mkdir(parents=True, exist_ok=True)

    identity = {
        "environmentId": ENVIRONMENT_ID,
        "clusterId": CLUSTER_ID,
        "namespace": NAMESPACE,
        "expectedClusterIncarnationUid": str(uuid.uuid4()),
        "expectedNamespaceUid": str(uuid.uuid4()),
        "configMapUid": str(uuid.uuid4()),
        "apiServerUrl": f"https://{API_HOST}:{API_PORT}",
        "apiUsername": f"system:serviceaccount:{NAMESPACE}:account-service",
    }

    kube = out / "kube"
    leaf(args.compose_ca_dir, kube, API_HOST, [f"DNS:{API_HOST}"], client=False)
    token = base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("=")
    (kube / "token").write_text(token)
    api_token = out / "api-token"
    api_token.mkdir(exist_ok=True)
    (api_token / "token").write_text(token)

    api = out / "account-jwt-api"
    api.mkdir(exist_ok=True)
    ca_bytes = (args.compose_ca_dir / "ca.crt").read_bytes()
    (api / "serving-ca.pem").write_bytes(ca_bytes)
    api_binding = {
        "version": "account-jwt-jwks-api-binding/v1",
        "enabled": "true",
        "configRevision": "pre-v1-compose-1",
        "environmentId": ENVIRONMENT_ID,
        "clusterId": CLUSTER_ID,
        "apiServerUrl": identity["apiServerUrl"],
        "tlsServerName": API_HOST,
        "servingCaPath": "/etc/firemud/account-jwt-api/serving-ca.pem",
        "servingCaSha256": sha256_hex(ca_bytes),
        "bearerTokenPath": "/var/run/secrets/firemud/account-jwt-api-token/token",
        "namespace": NAMESPACE,
        "expectedClusterIncarnationUid": identity["expectedClusterIncarnationUid"],
        "expectedNamespaceUid": identity["expectedNamespaceUid"],
        "expectedApiUsername": identity["apiUsername"],
    }
    api_binding["bindingDigest"] = sha256_hex(canonical_json(api_binding))
    (api / "binding.json").write_text(json.dumps(api_binding, indent=2) + "\n")

    materializer = out / "materializer"
    peer_uri = f"spiffe://firemud/ns/{NAMESPACE}/sa/{MATERIALIZER}"
    crt, _ = leaf(args.compose_ca_dir, materializer, MATERIALIZER, [f"URI:{peer_uri}"], client=True)
    (materializer / "ca.crt").write_bytes(ca_bytes)
    trust = {
        "digestVersion": "account-jwt-signer-materializer-binding/v1",
        "configRevision": "pre-v1-compose-1",
        "environmentId": ENVIRONMENT_ID,
        "clusterId": CLUSTER_ID,
        "namespace": NAMESPACE,
        "expectedClusterIncarnationUid": identity["expectedClusterIncarnationUid"],
        "expectedNamespaceUid": identity["expectedNamespaceUid"],
        "expectedPeerUri": peer_uri,
        "peerSpkiSha256Pins": [spki_sha256(crt)],
    }
    digest = sha256_hex(canonical_json(trust))
    trust_file = {k: v for k, v in trust.items() if k != "digestVersion"}
    trust_file["peerSpkiSha256Pins"] = ",".join(trust["peerSpkiSha256Pins"])
    trust_file["enabled"] = "true"
    trust_file["bindingDigest"] = digest
    binding_dir = out / "materializer-binding"
    binding_dir.mkdir(exist_ok=True)
    # AccountJwtSignerMaterializerTrustBinding reads one key=value field per line.
    (binding_dir / "binding.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in trust_file.items())
    )

    harness_uri = f"spiffe://firemud/ns/{NAMESPACE}/sa/account-jwt-readiness-harness"
    readiness = {
        "digestVersion": "account-jwt-readiness-trust-binding/v1",
        "configRevision": "pre-v1-compose-1",
        "environmentId": ENVIRONMENT_ID,
        "clusterId": CLUSTER_ID,
        "namespace": NAMESPACE,
        "expectedClusterIncarnationUid": identity["expectedClusterIncarnationUid"],
        "expectedNamespaceUid": identity["expectedNamespaceUid"],
        "expectedPeerUri": harness_uri,
        "peerSpkiSha256Pins": [spki_sha256(crt)],
        "validatorId": "account-service",
        "validatorInstanceId": "compose-account-service",
        "validUntilEpochSecond": int(time.time()) + 30 * 24 * 3600,
    }
    readiness_file = {k: v for k, v in readiness.items() if k != "digestVersion"}
    readiness_file["peerSpkiSha256Pins"] = ",".join(readiness["peerSpkiSha256Pins"])
    readiness_file["enabled"] = "true"
    readiness_file["bindingDigest"] = sha256_hex(canonical_json(readiness))
    readiness_dir = out / "readiness-binding"
    readiness_dir.mkdir(exist_ok=True)
    (readiness_dir / "binding.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in readiness_file.items())
    )
    identity["readinessHarnessUri"] = harness_uri
    identity["readinessHarnessSpkiSha256"] = readiness["peerSpkiSha256Pins"][0]

    inventory = {
        "version": "account-jwt-validator-inventory-binding/v2",
        "enabled": "true",
        "configRevision": "pre-v1-compose-1",
        "environmentId": ENVIRONMENT_ID,
        "clusterId": CLUSTER_ID,
        "namespace": NAMESPACE,
        "expectedClusterIncarnationUid": identity["expectedClusterIncarnationUid"],
        "expectedNamespaceUid": identity["expectedNamespaceUid"],
        "apiBindingRevision": api_binding["configRevision"],
        "apiBindingDigest": api_binding["bindingDigest"],
        # Promotion needs the complete production matrix: Account itself validates every
        # production profile and Game Session the representative one. Readiness is
        # self-attested under the waiver, so the Account Pod IP is a placeholder.
        "validators": [
            {
                "validatorId": "account-service",
                "deploymentName": "account-service",
                "deploymentUid": str(uuid.uuid4()),
                "selector": {"app": "account-service"},
                "replicas": 1,
                "containerName": "account-service",
                "image": "firemud/account-service@sha256:" + "0" * 63 + "2",
                "jwksUri": "https://account-service/.well-known/jwks.json",
                "maxCacheAgeSeconds": 60,
                "profiles": [
                    {"tokenProfile": "control-ui", "audience": "control-ui"},
                    {"tokenProfile": "player-bootstrap", "audience": "player-bootstrap"},
                    {"tokenProfile": "game-session-account-delegation", "audience": "account-service"},
                ],
                "receiverServiceUri": f"spiffe://firemud/ns/{NAMESPACE}/sa/account-service",
                "receiverPort": 6565,
                "receiverPods": [
                    {
                        "podUid": str(uuid.uuid4()),
                        "podIp": "172.30.0.21",
                        "leafSpkiSha256": spki_sha256(args.account_leaf),
                    }
                ],
            },
            {
                "validatorId": "game-session-service",
                "deploymentName": "game-session-service",
                "deploymentUid": str(uuid.uuid4()),
                "selector": {"app": "game-session-service"},
                "replicas": 1,
                "containerName": "game-session-service",
                # Compose builds have no registry digest; the stand-in API reports this one.
                "image": "firemud/game-session-service@sha256:" + "0" * 63 + "1",
                "jwksUri": "https://account-service/.well-known/jwks.json",
                "maxCacheAgeSeconds": 60,
                "profiles": [
                    {"tokenProfile": "game-session-account-delegation", "audience": "account-service"}
                ],
                "receiverServiceUri": f"spiffe://firemud/ns/{NAMESPACE}/sa/game-session-service",
                "receiverPort": 6565,
                "receiverPods": [
                    {
                        "podUid": str(uuid.uuid4()),
                        "podIp": args.validator_ip,
                        "leafSpkiSha256": spki_sha256(args.validator_leaf),
                    }
                ],
            }
        ],
    }
    inventory["bindingDigest"] = sha256_hex(canonical_json(inventory))
    # AccountJwtValidatorInventoryBinding reads one fixed path beside the API binding.
    (api / "validator-inventory.json").write_text(json.dumps(inventory, indent=2) + "\n")

    keyrings = out / "keyrings"
    for name in ("request-digest", "response-envelope"):
        (keyrings / name).mkdir(parents=True, exist_ok=True)
    key = lambda: b64url_raw(secrets.token_bytes(32))
    retained_until = (int(time.time()) + 30 * 24 * 3600) * 1000
    (keyrings / "request-digest" / "keyring").write_text(
        "firemud-account-credential-request-digest-keyring-v1\n"
        f"active compose-1 {retained_until} {key()}\n"
    )
    (keyrings / "response-envelope" / "keyring").write_text(
        "firemud-account-response-envelope-keyring-v1\n" f"active compose-1 {key()}\n"
    )

    # Pre-v1 waiver: the World runtime installation inventory the run-owned fixture freezes its
    # launch owners against (PreV1ComposeRuntimeInstallationSource reads it read-only).
    world = out / "world-runtime-installation"
    world.mkdir(parents=True, exist_ok=True)
    world_inventory = {
        "schema": "firemud-runtime-installation-inventory/v1",
        "artifactId": "8d6a7f0e-2f0c-4f5e-9a51-0c7d3c2e9b11",
        "installationId": "1f3b9c4a-7e2d-4b8a-8f61-5d2e0a9c7b33",
        "environment": "local-dev",
        "generation": "pre-v1-compose-installation-v1",
        "clusterUid": "compose",
        "namespaceUid": "compose-dev",
        "namespace": "dev",
        "sourceCommit": "0" * 40,
        "renderedManifestDigest": "sha256:" + sha256_hex(b"docker-compose.yml"),
        "declarationsDigest": "sha256:" + sha256_hex(b"pre-v1-compose-declarations"),
        "executor": {
            "identity": "pre-v1-compose-fixtures/v1",
            "versionDigest": "sha256:" + sha256_hex(b"pre-v1-signer-fixtures.py"),
        },
        "inputBindings": {
            "migrations": [],
            "configurations": [],
            "mountedResources": [],
            "secretContracts": [],
            "externalBindings": [],
        },
        "members": [
            {
                "workloadApiVersion": "v1",
                "workloadKind": "Pod",
                "workloadNamespace": "dev",
                "workloadName": "world-management-service",
                "containerKind": "APPLICATION",
                "containerName": "world-management-service",
                "image": "firemud/world-management-service@sha256:" + sha256_hex(b"compose-world"),
                "ownerId": "world-management-service",
                "ownership": "RUNTIME_OWNER",
                "catalogSources": [
                    {
                        "sourceId": "compose-world-sql",
                        "contract": "firemud/world/catalog/v1",
                        "epoch": "1",
                        "digest": "sha256:" + sha256_hex(b"compose-world-sql"),
                    }
                ],
                "families": [
                    {
                        "familyId": "compose-world-state",
                        "catalogSourceId": "compose-world-sql",
                        "replacementClass": "S3",
                        "cleanupContract": {
                            field: f"firemud/world/{field}/v1"
                            for field in ("operation", "requestIdentity", "acknowledgement", "retention")
                        },
                    }
                ],
            }
        ],
    }
    inventory_path = world / "inventory.json"
    if inventory_path.exists():
        inventory_path.unlink()
    inventory_path.write_text(json.dumps(world_inventory, separators=(",", ":")))
    os.chmod(inventory_path, 0o444)

    (out / "identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    os.chmod(out, 0o755)


if __name__ == "__main__":
    main()
