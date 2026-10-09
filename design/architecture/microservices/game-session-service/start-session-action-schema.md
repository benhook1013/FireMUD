# StartSession Operator Action Schema

## Implementation status

The shared StartSession action, mutation-digest codec, pre-authorization reservation tuple, post-authorization execution tuple, and Account authority-evidence bundle are present as common-platform-core value and codec contracts with focused Java tests and shared Java/Python digest vectors. Logging & Admin also implements durable StartSession pre-authorization reservation persistence and the read-only `ReadCurrentClaimEvidence` transport for Account claim-evidence handoff. Production reservation construction is write-denied and passive/read-only; mutation-capable construction is isolated to test-only fixtures. These implementations do not establish current Account authority. Account issuance and redemption are not wired; Game Session and Game Design runtime registration and owner execution integration are not implemented. External routing and end-to-end launch proof remain unproved. No external route or runtime activation is enabled by this boundary.

## Normative contract

This document defines the StartSession action-family schema `firemud.game-session.start-session`, version `1`, owned by `game-session-service`. It defines the digest input shape for the `StartSession` operator action. It does not make the external operator route routable or establish an authorization decision.

The request is a JSON object whose declared member order is `actionFamilySchemaId`, `actionFamilySchemaVersion`, `scope`, `target`, `expectedVersion`, `mutation`, and `auditReason`. `expectedVersion` is absent for this creation action and must not appear in the JSON request, including as `null`. Its absence remains an explicit seventh field in the canonical digest preimage. All other top-level members are required.

| Member | Required shape and constraint |
| --- | --- |
| `actionFamilySchemaId` | String exactly `firemud.game-session.start-session`. |
| `actionFamilySchemaVersion` | String exactly `1`. |
| `scope` | Object with exactly `tenantId`, then `targetNamespace`. |
| `scope.tenantId` | Canonical lowercase, hyphenated, non-nil UUID. |
| `scope.targetNamespace` | Kubernetes workload namespace label: 1–63 lowercase ASCII letters or digits, with internal hyphens allowed and no leading or trailing hyphen. |
| `target` | Object with exactly `gameTemplateId`, then `ownerAccountId`. |
| `target.gameTemplateId` | JSON string matching `[1-9][0-9]*`, within the current owner's signed `long` range. JSON numbers and strings with a sign, leading zero, decimal point, or exponent are rejected. |
| `target.ownerAccountId` | Canonical lowercase, hyphenated, non-nil Account UUID. Private legacy identifier mappings remain the receiver's responsibility. |
| `expectedVersion` | Explicitly absent for StartSession creation. Any supplied value, including `null`, is rejected. |
| `mutation` | Object with the optional member `clientIp`. |
| `mutation.clientIp` | Absent or a string whose NFC-normalized UTF-8 encoding is at most 128 bytes. Empty string is distinct from absence. `null` and all other types are rejected. No address parsing or ownership claim is implied. |
| `auditReason` | Required string, nonblank after NFC normalization, at most 1,000 normalized UTF-8 bytes. |

For the `auditReason` nonblank constraint, the shared blank codepoint set is exactly U+0009–U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F, U+205F, and U+3000. Implementations must use this explicit set rather than a language-provided whitespace predicate. Other codepoints, including U+200B ZERO WIDTH SPACE, do not make an audit reason blank.

JSON object members must appear in the declared order. Duplicate raw keys, keys colliding after NFC normalization, unknown or missing members, wrong JSON types, malformed Unicode, invalid UTF-8, and trailing tokens are rejected before a digest is returned. Object and array values use the recursive typed grammar from [ADR 0047, Bounded Owner Delegation](../../decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md#bounded-owner-delegation); no generic JSON canonicalization is a substitute.

The schema uses these shared parser and encoding bounds:

| Limit | Maximum |
| --- | ---: |
| Raw UTF-8 JSON input | 16 KiB |
| Composite nesting depth | 8 |
| Members in one object | 16 |
| Elements in one array | 64 |
| NFC-normalized string or object-key bytes | 4,096 |
| Raw or canonical numeric bytes | 128 |
| Numeric scale | 128 |
| Canonical segment payload | 8,192 bytes |
| Complete `mutationDigest/v1` preimage | 32 KiB |

## Digest inputs and exclusions

The SHA-256 preimage follows ADR 0047's exact `mutationDigest/v1` framing. The schema pair and these typed fields are included in the ADR-defined order: `scope`, `target`, absent `expectedVersion`, `mutation`, and `auditReason`. The `mutation` object always declares one member, `clientIp`; if it is omitted from JSON, the digest still includes that member in its declared position with type `absent` and presence `0`. An empty string is encoded as a present `string` and therefore has a different digest.

The target owner is fixed to `game-session-service` for this schema and is carried separately in the ADR 0048 execution tuple; it is not a digest field. `controlPlaneRequestId`, any separately tagged action-family request identity, reservation owner and claim fence, transport credentials, opaque authorization references, actor assertions, and Account authority evidence are outside these seven grammar fields and are compared at their respective boundaries under [ADR 0047](../../decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md#bounded-owner-delegation) and [ADR 0048](../../decisions/adr-0048-durable-idempotent-operator-write-execution.md#one-correlated-execution).

The action contains no runtime version or patch input. It does not assert current ownership or authority. Owner-domain checks, source selection and execution remain separate runtime prerequisites outside this shared-contract slice.

The shared Java implementation exposes `StartSessionOperatorActionCodec.decode(byte[])`, `canonicalPreimage(...)`, and `mutationDigest(...)`, plus the immutable typed action accessors. Shared Java and Python conformance vectors are stored in [the common vector fixture](../../../../services/common-platform-core/src/test/resources/operator/start-session-mutation-digest-v1-vectors.json).
