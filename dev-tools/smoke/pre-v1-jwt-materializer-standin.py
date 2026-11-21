#!/usr/bin/env python3
"""Pre-v1 stand-in for Account's interim mounted-key JWT signer materializer.

Compose smoke only, under the pre-v1 gate waiver (ADR 0185). It polls Account's
AccountJwtSignerMaterializationService over mTLS as spiffe://firemud/ns/dev/sa/account-jwt-materializer
and keeps a directory standing in for the private Kubernetes Secret that Account mounts at
/var/run/secrets/firemud/jwt. The "Secret" uid and resourceVersion live in that directory's
state file. A deployed materializer writing a real Secret is owed before v1.

Requires grpcio, grpcio-tools and cryptography. Stubs are generated from --protos at start.
"""

import argparse
import base64
import hashlib
import importlib
import json
import os
import subprocess
import sys
import tempfile
import time
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

SCHEMA_VERSION = 1
PROTO = "account/v1/jwt_signer_materialization.proto"
PHASE_OBSERVE = 1
PHASE_GENERATE = 2


def b64url(data):
    return base64.urlsafe_b64encode(data).decode("ascii").rstrip("=")


def unsigned(value):
    return value.to_bytes((value.bit_length() + 7) // 8 or 1, "big")


def load_stubs(protos, out_dir):
    subprocess.run(
        [sys.executable, "-m", "grpc_tools.protoc", f"-I{protos}", f"--python_out={out_dir}",
         f"--grpc_python_out={out_dir}", os.path.join(protos, PROTO)],
        check=True,
    )
    sys.path.insert(0, out_dir)
    return (
        importlib.import_module("account.v1.jwt_signer_materialization_pb2"),
        importlib.import_module("account.v1.jwt_signer_materialization_pb2_grpc"),
    )


class SecretDir:
    """Directory standing in for the private Secret; one key file per slot."""

    def __init__(self, path):
        self.path = path
        self.state_file = os.path.join(path, ".standin-state.json")
        if os.path.exists(self.state_file):
            with open(self.state_file, encoding="utf-8") as handle:
                self.state = json.load(handle)
        else:
            self.state = {"uid": str(uuid.uuid4()), "resourceVersion": 1}
            self._save()

    @property
    def uid(self):
        return self.state["uid"]

    @property
    def resource_version(self):
        return str(self.state["resourceVersion"])

    def _save(self):
        fd, tmp = tempfile.mkstemp(dir=self.path)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(self.state, handle)
        os.replace(tmp, self.state_file)

    def write_slots(self, slots):
        for name, data in slots.items():
            target = os.path.join(self.path, f"{name}.key")
            if data is None:
                if os.path.exists(target):
                    os.unlink(target)
                continue
            fd, tmp = tempfile.mkstemp(dir=self.path)
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
            os.chmod(tmp, 0o444)
            os.replace(tmp, target)
        self.state["resourceVersion"] += 1
        self._save()

    def read_slot(self, name):
        target = os.path.join(self.path, f"{name}.key")
        if not os.path.exists(target):
            return None
        with open(target, "rb") as handle:
            return handle.read()

    def slots(self):
        return sorted(
            name[: -len(".key")] for name in os.listdir(self.path) if name.endswith(".key")
        )


def generate_bundle(request):
    key = rsa.generate_private_key(public_exponent=65537, key_size=3072)
    numbers = key.public_key().public_numbers()
    n, e = b64url(unsigned(numbers.n)), b64url(unsigned(numbers.e))
    fingerprint = hashlib.sha256(
        ('{"e":"' + e + '","kty":"RSA","n":"' + n + '"}').encode("ascii")
    ).hexdigest()
    pkcs8 = key.private_bytes(
        serialization.Encoding.DER,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    )
    bundle = {
        "version": 1,
        "environmentId": request.environment_id,
        "clusterId": request.cluster_id,
        "namespace": request.namespace,
        "operationId": request.operation_id,
        "generation": request.target_generation,
        "kid": request.target_kid,
        "algorithm": "RS256",
        "privateKeyPkcs8": b64url(pkcs8),
        "publicKeyFingerprint": fingerprint,
    }
    # RFC 8785 canonical form: sorted keys, no whitespace, ASCII-only content here.
    jwk = json.dumps(
        {
            "alg": "RS256",
            "e": e,
            "key_ops": ["verify"],
            "kid": request.target_kid,
            "kty": "RSA",
            "n": n,
            "use": "sig",
        },
        sort_keys=True,
        separators=(",", ":"),
    )
    return json.dumps(bundle).encode("utf-8"), jwk


PROMOTED_MARKER = "/tmp/firemud-signer-promoted"
CALL_TIMEOUT_SECONDS = 30.0


class TimedStub:
    """Bounds every Account call so a restarted Account cannot leave the stand-in waiting forever."""

    def __init__(self, stub, timeout):
        self._stub = stub
        self._timeout = timeout

    def __getattr__(self, name):
        method = getattr(self._stub, name)
        return lambda request: method(request, timeout=self._timeout)


def reconcile(pb, stub, secret):
    generation = stub.GetCurrentGenerationRequest(
        pb.GetCurrentGenerationRequestRequest(schema_version=SCHEMA_VERSION)
    )
    if generation.phase == PHASE_OBSERVE:
        stub.RecordSecretObservation(
            pb.RecordSecretObservationRequest(
                schema_version=SCHEMA_VERSION,
                operation_id=generation.operation_id,
                operation_digest=generation.operation_digest,
                secret_uid=secret.uid,
                observed_resource_version=secret.resource_version,
            )
        )
        return True
    if generation.phase == PHASE_GENERATE:
        prior = secret.resource_version
        bundle, jwk = generate_bundle(generation)
        secret.write_slots({"pending": bundle})
        stub.RecordGenerationResult(
            pb.RecordGenerationResultRequest(
                schema_version=SCHEMA_VERSION,
                operation_id=generation.operation_id,
                generation_request_digest=generation.generation_request_digest,
                secret_uid=secret.uid,
                expected_prior_resource_version=prior,
                observed_resource_version=secret.resource_version,
                public_jwk_json=jwk,
            )
        )
        return True

    promotion = stub.GetCurrentPromotionRequest(
        pb.GetCurrentPromotionRequestRequest(schema_version=SCHEMA_VERSION)
    )
    if not promotion.promotion_operation_id:
        return False
    prior = secret.resource_version
    pending, current = secret.read_slot("pending"), secret.read_slot("current")
    if pending is None:
        return False
    secret.write_slots({"current": pending, "previous": current, "pending": None})
    previous = None
    if promotion.expected_previous_kid:
        previous = pb.PromotionPublicKeyIdentity(
            generation=promotion.expected_previous_generation,
            kid=promotion.expected_previous_kid,
            public_key_fingerprint=promotion.expected_previous_public_key_fingerprint,
        )
    stub.RecordPrivatePromotionResult(
        pb.RecordPrivatePromotionResultRequest(
            schema_version=SCHEMA_VERSION,
            promotion_operation_id=promotion.promotion_operation_id,
            promotion_request_digest=promotion.promotion_request_digest,
            generation_operation_id=promotion.generation_operation_id,
            generation_operation_digest=promotion.generation_operation_digest,
            secret_uid=secret.uid,
            expected_prior_resource_version=prior,
            observed_resource_version=secret.resource_version,
            current=pb.PromotionPublicKeyIdentity(
                generation=promotion.target_generation,
                kid=promotion.target_kid,
                public_key_fingerprint=promotion.target_public_key_fingerprint,
            ),
            previous=previous,
            resulting_slots=secret.slots(),
        )
    )
    # The container healthcheck reads this marker; Game Session starts only once a signer is live.
    with open(PROMOTED_MARKER, "w", encoding="utf-8") as marker:
        marker.write(promotion.target_kid)
    return True


def main():
    import grpc

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--account", default="account-service:6565")
    parser.add_argument("--protos", required=True)
    parser.add_argument("--cert", required=True)
    parser.add_argument("--key", required=True)
    parser.add_argument("--ca", required=True)
    parser.add_argument("--server-name", default="account-service")
    parser.add_argument("--secret-dir", required=True)
    parser.add_argument("--interval", type=float, default=5.0)
    args = parser.parse_args()

    pb, pb_grpc = load_stubs(args.protos, tempfile.mkdtemp())
    with open(args.ca, "rb") as ca, open(args.key, "rb") as key, open(args.cert, "rb") as cert:
        credentials = grpc.ssl_channel_credentials(ca.read(), key.read(), cert.read())
    channel = grpc.secure_channel(
        args.account, credentials, options=[("grpc.ssl_target_name_override", args.server_name)]
    )
    stub = TimedStub(pb_grpc.AccountJwtSignerMaterializationServiceStub(channel), CALL_TIMEOUT_SECONDS)
    secret = SecretDir(args.secret_dir)

    enrolled = False
    while True:
        try:
            if not enrolled:
                stub.EnrollBootstrap(pb.EnrollBootstrapRequest(schema_version=SCHEMA_VERSION))
                enrolled = True
            if reconcile(pb, stub, secret):
                time.sleep(0.5)
                continue
        except grpc.RpcError as failure:
            print(f"materializer stand-in: {failure.code().name}: {failure.details()}", file=sys.stderr, flush=True)
        time.sleep(args.interval)


if __name__ == "__main__":
    main()
