"""Independent capacity-admission/1 corpus consumer. Encoding proof, not runtime authority."""
import base64
import copy
import hashlib
import json
import re
import unicodedata
import unittest
from pathlib import Path

MAX = 9223372036854775807


def decimal(value):
    if not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]{0,18}", value):
        raise ValueError("canonical int64 decimal string required")
    result = int(value)
    if result > MAX:
        raise ValueError("int64 overflow")
    return result


def segment(value):
    if len(value) > 393216:
        raise ValueError("segment bound")
    return str(len(value)).encode("ascii") + b":" + value


def value(kind, payload, present=True):
    return segment(kind.encode("ascii")) + (b"1" if present else b"0") + segment(payload)


def string(text):
    encoded = unicodedata.normalize("NFC", text).encode("utf-8", "strict")
    if len(encoded) > 360448:
        raise ValueError("string bound")
    return value("string", encoded)


def number(text):
    return value("number", str(decimal(text)).encode("ascii"))


def obj(fields):
    return value("object", segment(str(len(fields)).encode("ascii")) + b"".join(
        segment(key.encode("ascii")) + item for key, item in fields))


def shape(values):
    return obj(list(zip(("activeSessions", "concurrentGameInstances", "storageBytes"),
                        (number(v) for v in values))))


def reject_duplicates(pairs):
    result = {}
    for key, item in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = item
    return result


def evidence(encoded, variant="TENANT_OPERATION"):
    raw = base64.b64decode(encoded, validate=True)
    if base64.b64encode(raw).decode("ascii") != encoded:
        raise ValueError("canonical padded base64 required")
    if not 0 < len(raw) <= 262144:
        raise ValueError("evidence bound")
    parsed = json.loads(raw.decode("utf-8", "strict"), object_pairs_hook=reject_duplicates)
    canonical = json.dumps(parsed, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if raw != canonical or parsed["schema"] != "account-auth-evidence-bundle/v1" or parsed["variant"] != variant:
        raise ValueError("exact canonical variant required")
    tenant = parsed["tenantId"]
    cutoff = parsed["authorityTuple"]["tenantBillingCutoff"][tenant]
    points = parsed["outboxCheckpoints"]
    matching = [point for point in points if point["outboxStreamKey"] == cutoff["outboxStreamKey"]]
    if (variant == "TENANT_OPERATION" and len(points) != 1) or len(matching) != 1 or any(
            cutoff[k] != matching[0][k] for k in ("outboxStreamKey", "outboxSequence")):
        raise ValueError("cutoff checkpoint mismatch")
    return encoded


def preimage(request, candidate):
    context = request["ownerContext"]
    if request["ownerService"] == "game-session-service":
        variant = "PLAYER_ADMISSION"
        owner_fields = [(key, string(context[key])) for key in
                        ("accountId", "tenantId", "playableStateNamespaceId", "gameInstanceId",
                         "characterId", "sessionId", "realmId", "requestId", "playableStateScope")]
    elif request["ownerService"] == "world-management-service":
        variant = "TENANT_OPERATION"
        owner_fields = [(key, string(context[key])) for key in
                        ("tenantId", "operationId", "lifecycleId", "gameInstanceId", "ownerId")]
        owner_fields.append(("ownerFence", number(context["ownerFence"])))
    else:
        raise ValueError("known owner service required")
    fields = [
        ("actionFamilySchemaId", string("capacity-admission")),
        ("actionFamilySchemaVersion", string("1")),
        ("scope", obj([(key, string(request[key])) for key in ("tenantId", "requestId", "admissionId")])),
        ("target", obj([("ownerService", string(request["ownerService"])),
                        ("targetNamespace", string(request["targetNamespace"])),
                        ("ownerContext", obj(owner_fields))])),
        ("expectedVersion", obj([("admissionAttemptId", string(request["admissionAttemptId"])),
                                 ("capacityRearmFence", number(request["capacityRearmFence"]))])),
        ("mutation", obj([("operation", string(candidate.get("operation", request["operation"]))),
                          ("priorShape", shape(candidate.get("priorShape", request["priorShape"]))),
                          ("requestedShape", shape(candidate.get("requestedShape", request["requestedShape"]))),
                          ("accountEvidence", string(evidence(request["canonicalAccountEvidenceBase64"], variant))),
                          ("capacityDelta", value("absent", b"", False) if candidate["capacityDelta"] is None
                           else number(candidate["capacityDelta"]))])),
        ("auditReason", string("capacity-admission"))]
    result = segment(b"mutationDigest/v1") + b"".join(segment(key.encode("ascii")) + item for key, item in fields)
    if len(result) > 524288:
        raise ValueError("preimage bound")
    return result


class CapacityAdmissionVectors(unittest.TestCase):
    def test_shared_golden_preimages_and_digests(self):
        corpus = json.loads(Path(__file__).with_name("capacity-admission-v1-vectors.json").read_text())
        digests = []
        for candidate in corpus["vectors"]:
            with self.subTest(candidate["name"]):
                encoded = preimage(corpus["request"], candidate)
                self.assertEqual(encoded.hex(), candidate["preimageHex"])
                digest = hashlib.sha256(encoded).hexdigest()
                self.assertEqual(digest, candidate["sha256"])
                digests.append(digest)
        self.assertNotEqual(digests[0], digests[1])

    def test_player_golden_preimage_and_each_player_identity(self):
        corpus = json.loads(Path(__file__).with_name("capacity-admission-v1-vectors.json").read_text())
        request, candidate = corpus["playerRequest"], corpus["playerVector"]
        context_ids = [item for key, item in request["ownerContext"].items() if key.endswith("Id")]
        self.assertEqual(len(context_ids), len(set(context_ids)))
        encoded = preimage(request, candidate)
        self.assertEqual(encoded.hex(), candidate["preimageHex"])
        self.assertEqual(hashlib.sha256(encoded).hexdigest(), candidate["sha256"])
        for key in ("characterId", "sessionId"):
            with self.subTest(key):
                changed = copy.deepcopy(request)
                changed["ownerContext"][key] = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                self.assertNotEqual(hashlib.sha256(preimage(changed, candidate)).hexdigest(), candidate["sha256"])
        swapped = copy.deepcopy(request)
        context = swapped["ownerContext"]
        context["characterId"], context["sessionId"] = context["sessionId"], context["characterId"]
        self.assertNotEqual(hashlib.sha256(preimage(swapped, candidate)).hexdigest(), candidate["sha256"])

    def test_rejects_nonzero_unused_base64_pad_bits(self):
        corpus = json.loads(Path(__file__).with_name("capacity-admission-v1-vectors.json").read_text())
        parsed = json.loads(base64.b64decode(corpus["request"]["canonicalAccountEvidenceBase64"]))
        alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for padding in (1, 2):
            # Valid JSON evidence with each padding width isolates the base64 spelling check.
            for width in range(3):
                parsed["padBitTest"] = "x" * width
                raw = json.dumps(parsed, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
                canonical = base64.b64encode(raw).decode("ascii")
                if canonical.endswith("=" * padding) and not canonical.endswith("=" * (padding + 1)):
                    break
            self.assertEqual(evidence(canonical), canonical)
            index = len(canonical) - padding - 1
            alternate = canonical[:index] + alphabet[alphabet.index(canonical[index]) + 1] + canonical[index + 1:]
            self.assertEqual(base64.b64decode(alternate, validate=True), raw)
            with self.subTest(padding=padding), self.assertRaisesRegex(ValueError, "canonical padded base64"):
                evidence(alternate)

    def test_rejected_decimal_candidates(self):
        corpus = json.loads(Path(__file__).with_name("capacity-admission-v1-vectors.json").read_text())
        for candidate in corpus["rejected"]:
            with self.subTest(candidate["name"]), self.assertRaises(ValueError):
                decimal(candidate["capacityDelta"])

    def test_rejected_evidence_candidates(self):
        corpus = json.loads(Path(__file__).with_name("capacity-admission-v1-vectors.json").read_text())
        for candidate in corpus["rejectedEvidence"]:
            with self.subTest(candidate["name"]), self.assertRaises(ValueError):
                evidence(candidate["canonicalAccountEvidenceBase64"])
        # Shape/absence and usage expectations are separate from this encoding corpus:
        # CapacityAdmissionDigestTest and AccountTenantCapacityOwnerTest invoke their real owners.


if __name__ == "__main__":
    unittest.main()
