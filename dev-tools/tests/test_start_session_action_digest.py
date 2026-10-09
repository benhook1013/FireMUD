#!/usr/bin/env python3
"""Standard-library conformance checks for StartSession mutationDigest/v1 vectors."""

import hashlib
import json
import re
import unicodedata
import unittest
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = (
    ROOT
    / "services/common-platform-core/src/test/resources/operator/"
    / "start-session-mutation-digest-v1-vectors.json"
)
SCHEMA_ID = "firemud.game-session.start-session"
SCHEMA_VERSION = "1"
SHARED_WHITESPACE_CODEPOINTS = frozenset(
    [*range(0x0009, 0x000E), 0x0020, 0x0085, 0x00A0, 0x1680]
    + list(range(0x2000, 0x200B))
    + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000]
)
TOP_LEVEL_ORDER = [
    "actionFamilySchemaId",
    "actionFamilySchemaVersion",
    "scope",
    "target",
    "expectedVersion",
    "mutation",
    "auditReason",
]
UUID_PATTERN = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\Z")
NAMESPACE_PATTERN = re.compile(r"[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\Z")
NUMBER_SOURCE = re.compile(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?\Z")
NUMBER_CANONICAL = re.compile(r"0|-?(?:[1-9][0-9]*(?:\.[0-9]*[1-9])?|0\.[0-9]*[1-9])\Z")


@dataclass(frozen=True)
class RawNumber:
    lexeme: str


@dataclass(frozen=True)
class RawObject:
    members: list


def reject_constant(_value):
    raise ValueError("non-finite numeric value")


def parse_json(raw):
    if len(raw) > 16 * 1_024:
        raise ValueError("raw JSON exceeds its byte limit")
    source = raw.decode("utf-8", "strict")
    return json.loads(
        source,
        parse_int=RawNumber,
        parse_float=RawNumber,
        parse_constant=reject_constant,
        object_pairs_hook=lambda pairs: RawObject(pairs),
    )


def scalar_string(value, byte_limit=4_096):
    if not isinstance(value, str):
        raise TypeError("string value required")
    if any(0xD800 <= ord(character) <= 0xDFFF for character in value):
        raise ValueError("malformed Unicode")
    normalized = unicodedata.normalize("NFC", value)
    encoded = normalized.encode("utf-8", "strict")
    if len(encoded) > byte_limit:
        raise ValueError("normalized string exceeds its byte limit")
    return normalized


def segment(payload):
    if len(payload) > 8_192:
        raise ValueError("segment exceeds its byte limit")
    return str(len(payload)).encode("ascii") + b":" + payload


def decimal_value(raw):
    lexeme = raw.lexeme
    encoded = lexeme.encode("ascii", "strict")
    if len(encoded) > 128 or not NUMBER_SOURCE.fullmatch(lexeme):
        raise ValueError("numeric source is invalid or too long")
    fraction = lexeme.partition(".")[2]
    if len(fraction) > 128:
        raise ValueError("numeric scale exceeds its limit")
    negative = lexeme.startswith("-")
    unsigned = lexeme[1:] if negative else lexeme
    integer, separator, fraction = unsigned.partition(".")
    fraction = fraction.rstrip("0") if separator else ""
    nonzero = any(digit != "0" for digit in integer + fraction)
    if not nonzero:
        canonical = "0"
    elif fraction:
        canonical = ("-" if negative else "") + integer + "." + fraction
    else:
        canonical = ("-" if negative else "") + integer
    canonical_bytes = canonical.encode("ascii")
    if len(canonical_bytes) > 128 or not NUMBER_CANONICAL.fullmatch(canonical):
        raise ValueError("canonical number exceeds its limit")
    return canonical_bytes


def encoded_value(value, depth=0):
    if depth > 8:
        raise ValueError("composite nesting is too deep")
    if value is ABSENT:
        return segment(b"absent") + b"0" + segment(b"")
    if value is None:
        return segment(b"null") + b"1" + segment(b"")
    if isinstance(value, str):
        payload = scalar_string(value).encode("utf-8")
        return segment(b"string") + b"1" + segment(payload)
    if isinstance(value, RawNumber):
        return segment(b"number") + b"1" + segment(decimal_value(value))
    if isinstance(value, bool):
        return segment(b"boolean") + b"1" + segment(b"true" if value else b"false")
    if isinstance(value, RawObject):
        if len(value.members) > 16:
            raise ValueError("object exceeds its member limit")
        normalized_members = []
        seen = set()
        for raw_key, member_value in value.members:
            key = scalar_string(raw_key).encode("utf-8")
            if key in seen:
                raise ValueError("duplicate or normalized-colliding object key")
            seen.add(key)
            normalized_members.append((key, member_value))
        payload = segment(str(len(normalized_members)).encode("ascii"))
        for key, member_value in normalized_members:
            payload += segment(key) + encoded_value(member_value, depth + 1)
        return segment(b"object") + b"1" + segment(payload)
    if isinstance(value, list):
        if len(value) > 64:
            raise ValueError("array exceeds its element limit")
        payload = segment(str(len(value)).encode("ascii"))
        for item in value:
            payload += encoded_value(item, depth + 1)
        return segment(b"array") + b"1" + segment(payload)
    raise ValueError("unsupported typed JSON value")


ABSENT = object()


def ordered_members(value, declared_order, optional=()):
    if not isinstance(value, RawObject):
        raise TypeError("object value required")
    if len(value.members) > 16:
        raise ValueError("object exceeds its member limit")
    members = {}
    actual_order = []
    for raw_key, member_value in value.members:
        key = scalar_string(raw_key)
        if key in members:
            raise ValueError("duplicate or normalized-colliding object key")
        members[key] = member_value
        actual_order.append(key)
    if any(key not in declared_order for key in actual_order):
        raise ValueError("unknown object member")
    expected_order = [key for key in declared_order if key in members or key not in optional]
    if actual_order != expected_order:
        raise ValueError("missing or out-of-order object member")
    return members


def canonical_uuid(value, label):
    normalized = scalar_string(value)
    if not UUID_PATTERN.fullmatch(normalized) or normalized == "00000000-0000-0000-0000-000000000000":
        raise ValueError(f"{label} is not a canonical non-nil UUID")
    return normalized


def action_preimage(raw):
    root = parse_json(raw)
    members = ordered_members(root, TOP_LEVEL_ORDER, optional={"expectedVersion"})
    if members.get("actionFamilySchemaId") != SCHEMA_ID:
        raise ValueError("wrong schema id")
    if members.get("actionFamilySchemaVersion") != SCHEMA_VERSION:
        raise ValueError("wrong schema version")
    if "expectedVersion" in members:
        raise ValueError("expectedVersion must be absent")

    scope = ordered_members(members["scope"], ["tenantId", "targetNamespace"])
    tenant_id = canonical_uuid(scope["tenantId"], "tenantId")
    namespace = scalar_string(scope["targetNamespace"])
    if len(namespace) > 63 or not NAMESPACE_PATTERN.fullmatch(namespace):
        raise ValueError("invalid workload namespace")

    target = ordered_members(members["target"], ["gameTemplateId", "ownerAccountId"])
    template_id = scalar_string(target["gameTemplateId"])
    if not re.fullmatch(r"[1-9][0-9]*", template_id):
        raise ValueError("gameTemplateId must be a positive canonical decimal string")
    if len(template_id) > 19 or (
        len(template_id) == 19 and template_id > "9223372036854775807"
    ):
        raise ValueError("gameTemplateId exceeds the current owner long range")
    owner_id = canonical_uuid(target["ownerAccountId"], "ownerAccountId")

    mutation = ordered_members(members["mutation"], ["clientIp"], optional={"clientIp"})
    if "clientIp" not in mutation:
        client_ip = ABSENT
    else:
        client_ip = mutation["clientIp"]
        if not isinstance(client_ip, str):
            raise ValueError("clientIp must be an absent or string value")
        client_ip = scalar_string(client_ip, 128)

    audit_reason = scalar_string(members["auditReason"], 1_000)
    if not audit_reason or all(
        ord(character) in SHARED_WHITESPACE_CODEPOINTS for character in audit_reason
    ):
        raise ValueError("auditReason must not be blank")

    values = [
        ("actionFamilySchemaId", SCHEMA_ID),
        ("actionFamilySchemaVersion", SCHEMA_VERSION),
        (
            "scope",
            RawObject(
                [
                    ("tenantId", tenant_id),
                    ("targetNamespace", namespace),
                ]
            ),
        ),
        (
            "target",
            RawObject(
                [
                    ("gameTemplateId", template_id),
                    ("ownerAccountId", owner_id),
                ]
            ),
        ),
        ("expectedVersion", ABSENT),
        ("mutation", RawObject([("clientIp", client_ip)])),
        ("auditReason", audit_reason),
    ]
    result = segment(b"mutationDigest/v1")
    for name, typed_value in values:
        result += segment(name.encode("utf-8")) + encoded_value(typed_value)
    if len(result) > 32 * 1_024:
        raise ValueError("preimage exceeds its byte limit")
    return result


def raw_vector_bytes(vector):
    if "rawHex" in vector:
        return bytes.fromhex(vector["rawHex"])
    return vector["input"].encode("utf-8", "strict")


class StartSessionMutationDigestVectorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_action_vectors(self):
        results = {}
        for vector in self.fixture["vectors"]:
            with self.subTest(vector=vector["name"]):
                raw = raw_vector_bytes(vector)
                if not vector["accepted"]:
                    with self.assertRaises((TypeError, ValueError, UnicodeError, json.JSONDecodeError)):
                        action_preimage(raw)
                    continue
                preimage = action_preimage(raw)
                digest = hashlib.sha256(preimage).hexdigest()
                self.assertEqual(preimage.hex(), vector["preimageHex"])
                self.assertEqual(digest, vector["digest"])
                results[vector["name"]] = digest

        self.assertNotEqual(results["base"], results["changed-target"])
        self.assertNotEqual(results["base"], results["changed-scope"])
        self.assertNotEqual(results["base"], results["changed-audit-reason"])
        self.assertNotEqual(results["client-ip-absent"], results["client-ip-empty"])
        self.assertEqual(results["unicode-composed"], results["unicode-decomposed"])

    def test_audit_reason_uses_explicit_shared_whitespace_set(self):
        base = json.loads(self.fixture["vectors"][0]["input"])
        for codepoint in SHARED_WHITESPACE_CODEPOINTS:
            with self.subTest(codepoint=f"U+{codepoint:04X}"):
                base["auditReason"] = chr(codepoint)
                with self.assertRaises(ValueError):
                    action_preimage(json.dumps(base, ensure_ascii=True).encode("utf-8"))

                base["auditReason"] = chr(codepoint) + "x"
                action_preimage(json.dumps(base, ensure_ascii=True).encode("utf-8"))

        for codepoint in (0x001C, 0x200B):
            with self.subTest(outside_codepoint=f"U+{codepoint:04X}"):
                base["auditReason"] = chr(codepoint)
                action_preimage(json.dumps(base, ensure_ascii=True).encode("utf-8"))

    def test_generic_value_grammar_vectors(self):
        encodings = {}
        for vector in self.fixture["grammarVectors"]:
            with self.subTest(vector=vector["name"]):
                if vector["kind"] == "numberLexeme":
                    if not vector["accepted"]:
                        with self.assertRaises((ValueError, UnicodeError)):
                            encoded_value(RawNumber(vector["rawLexeme"]))
                        continue
                    canonical = encoded_value(RawNumber(vector["rawLexeme"]))
                    self.assertEqual(canonical.hex(), vector["canonicalValueHex"])
                    encodings[vector["name"]] = canonical.hex()
                    continue
                if vector["kind"] == "reject":
                    with self.assertRaises((ValueError, UnicodeError, json.JSONDecodeError)):
                        encoded_value(parse_json(vector["rawValue"].encode("utf-8")))
                    continue
                if vector["kind"] == "absent":
                    canonical = encoded_value(ABSENT)
                else:
                    canonical = encoded_value(parse_json(vector["rawValue"].encode("utf-8")))
                self.assertEqual(canonical.hex(), vector["canonicalValueHex"])
                encodings[vector["name"]] = canonical.hex()

        self.assertNotEqual(encodings["absent"], encodings["null"])
        self.assertNotEqual(encodings["number-one"], encodings["string-one"])
        self.assertEqual(encodings["number-signed-zero"], encodings["number-zero"])
        self.assertEqual(encodings["unicode-key-composed"], encodings["unicode-key-decomposed"])


if __name__ == "__main__":
    unittest.main()
