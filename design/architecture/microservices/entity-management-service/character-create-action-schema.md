# CHARACTER_CREATE/1 Action Schema

This document owns the fixed action-family schema for `firemud.entity.create-character` version `1`. It defines the immutable typed mutation preimage used by Entity Management and its producer for `PLAYER_CREATED` character creation.

## Implementation Status

The fixed ordered `CHARACTER_CREATE/1` map below is approved. The four offline typed-preimage vectors remain unchanged, and the shared resource now includes the required negative vectors. A passive common-platform-core action model, strict ordered raw JSON encoder/decoder and digest path, and bounded authored-source projection parser are present with focused production-decoder tests. Current local proof and its limits are recorded in the [gameplay implementation tracker](../../../project-management/implementation-tracking/gameplay-rules-entities-and-effects.md#validation-and-proof). This remains local-only receiving support: it does not provide an authenticated producer, owner protections, Entity terminal composition, or route activation. The legacy scalar-only `CreateCharacter` RPC remains denied with `FAILED_PRECONDITION` before allocation because it cannot carry the required owner proof and operation binding; see the [Entity character-creation owner-protection contract](./api-contracts.md#character-creation-owner-protection). This schema does not activate a route or claim live creation support.

The current owner carriers and protection contracts establish only the initial public-production boundary. Private/playtest creation remains unsupported and denied until the genuine catalog, pointer, lifecycle, and protection carriers exist. Conditional tuple slots define exact presence for a complete future owner proof; their presence in this schema does not enable that boundary.

## Canonical Preimage

The family uses ADR 0047's existing `mutationDigest/v1` byte grammar and exactly its seven outer fields. This schema adds no digest grammar, JSON hashing, or object-map sorting. Each preimage object has exactly its declared members in the order shown. Required members are present and non-null. A declared absent member retains its preimage position and uses the exact `ABSENT` value from [ADR 0047](../../decisions/adr-0047-logging-admin-as-external-operator-write-ingress.md). Raw JSON omits only a member explicitly declared absent by this schema; its decoder restores that declared preimage position rather than dropping it. `null`, an empty string, zero, or a caller-supplied replacement object is not an absent value. Unknown, missing required, duplicated, reordered, or NFC-colliding keys are rejected before a digest is accepted.

| Position | Field | Type and exact value |
| --- | --- | --- |
| 1 | `actionFamilySchemaId` | String `firemud.entity.create-character` |
| 2 | `actionFamilySchemaVersion` | String `1` |
| 3 | `scope` | Object `(targetNamespace, tenantId, playableStateNamespaceId, playableStateScope)` |
| 4 | `target` | Object `(accountId, realmId, worldSlug, realmSlug, gameInstanceId, versionId)` |
| 5 | `expectedVersion` | Object `(catalogRevision, pointerVersion, activeLifecycleEpoch, playtestLifecycleId, playtestStateGeneration, evaluatedAt, connectScopeExpiresAt)` |
| 6 | `mutation` | Object `(purpose, entryPolicy, catalog, pointer, authoredSource, canonicalCreationInput)` |
| 7 | `auditReason` | Exact `ABSENT` value |

The action's typed members have no array, integer, decimal, or floating-point arms. The only boolean members are `catalog.visible` and `catalog.publicProduction`. Arrays occur only inside retained source documents carried as `ExactUtf8` strings and are validated by the bounded source parser below.

## Scope, Target, and Snapshot

`scope.targetNamespace` is the validated fixed workload namespace. `scope.tenantId` and `scope.playableStateNamespaceId` are canonical non-nil UUID strings. `scope.playableStateScope` is exactly `PLAYABLE_STATE_SCOPE_SHARED` or `PLAYABLE_STATE_SCOPE_ISOLATED`, derived from the catalog's exact `SHARED` or `ISOLATED` value. That mapping is checked at every boundary; callers cannot choose or derive the scope.

`target.accountId` is the authenticated Account UUID, not a management owner or a legacy row mapping. `realmId`, `gameInstanceId`, and `versionId` are canonical non-nil UUID strings; `worldSlug` and `realmSlug` are exact canonical selectors. Legacy Game Session fields such as `gameSessionTenantId`, `gameInstanceId`, and `runtimeVersionId` remain owner-private provenance and cannot substitute for these canonical identifiers.

`expectedVersion` has this exact order and value contract:

| Member | Type and presence |
| --- | --- |
| `catalogRevision` | Positive canonical decimal string from the actual catalog owner row |
| `pointerVersion` | Positive canonical decimal string from the actual pointer owner row |
| `activeLifecycleEpoch` | Positive canonical decimal string from the actual World lifecycle owner row |
| `playtestLifecycleId` | Canonical non-nil UUID for a private/playtest target; exact `ABSENT` for public production |
| `playtestStateGeneration` | Positive canonical decimal string for a private/playtest target; exact `ABSENT` for public production |
| `evaluatedAt` | Exact discovery-owner timestamp in canonical UTC `Instant.toString()` form |
| `connectScopeExpiresAt` | Exact discovery-owner timestamp in canonical UTC `Instant.toString()` form |

Every included timestamp, including `pointer.updatedAt`, must end in `Z` and preserve the owner's full `Instant` precision. Parsing and reformatting must reproduce the exact source string. No caller clock, refresh, rounding, or timestamp substitution is permitted. `connectScopeId` is not part of this preimage; it is separately bound with the creation request and authority in the durable intent.

All counters are canonical decimal strings, never JSON numbers, rounded values, or synthetic defaults. Owner `long` counters are at most 19 digits and within the positive signed-long range. Public-production targets require exact `ABSENT` in both playtest positions. A target requiring private/playtest identity is denied until those actual owner values and their protections are available; missing private data cannot be inferred, and optional slots do not imply support. A mode, counter, selector, or snapshot mismatch is denied before digest acceptance.

## Mutation Members

`mutation` has this exact order:

| Member | Type and exact value |
| --- | --- |
| `purpose` | String `CHARACTER_CREATE` |
| `entryPolicy` | String `PLAYER_CREATED` |
| `catalog` | Complete typed catalog snapshot below |
| `pointer` | Complete typed admission pointer below |
| `authoredSource` | Complete public authored projection below |
| `canonicalCreationInput` | Present string exactly `{}` |

This family supports `PLAYER_CREATED` only. `AUTO_PROVISIONED` is not an arm of this schema; any future family must retain the sixth mutation position with ADR 0140's exact typed `ABSENT` value and its own published vectors. The descriptor validates `{}` as its `EMPTY_OBJECT` input. Missing, `null`, whitespace JSON, or present-empty input is invalid.

### Catalog Snapshot

`catalog` has exactly this order:

`(tenantId, tenantSlug, worldSlug, worldDisplayName, realmId, realmSlug, realmDisplayName, visible, publicProduction, stateScope, playableStateNamespaceId, characterCreationPolicy, catalogRevision)`

`tenantId`, `realmId`, and `playableStateNamespaceId` are canonical non-nil UUID strings. `tenantSlug`, `worldSlug`, and `realmSlug` are canonical slug strings. `worldDisplayName` and `realmDisplayName` are `ExactUtf8` values. `visible` and `publicProduction` are booleans, and `visible` must be `true`. `stateScope` is exactly `SHARED` or `ISOLATED`; it must map to the scope enum above. `characterCreationPolicy` is exactly `PLAYER_CREATED`. `catalogRevision` is the same positive canonical decimal string as `expectedVersion.catalogRevision`.

All catalog identifiers and selectors must equal the corresponding scope, target, and source values. The catalog is the exact revision-bound source of policy, namespace, visibility, and published metadata. A current mutable catalog lookup cannot silently upgrade the creation intent.

### Admission Pointer

`pointer` has exactly this order:

`(tenantId, realmId, worldSlug, realmSlug, playtestLifecycleId, playtestStateGeneration, admissionState, admissibleGameInstanceId, pointerVersion, catalogRevision, updatedAt)`

Pointer identifiers, selectors, and counters must equal the corresponding scope, target, `expectedVersion`, and catalog values. For this family, `admissionState` is exactly `OPEN`, and `admissibleGameInstanceId` is the exact canonical UUID in `target.gameInstanceId`; `CLOSED`, missing, or ambiguous state is denied. Both playtest fields follow the same presence rule as `expectedVersion`. `updatedAt` is the exact owner value in canonical UTC `Instant.toString()` form. A positive legacy row identifier does not prove `OPEN`.

This is the canonical Multi-Tenancy pointer shape, not a narrower synthetic `GameplayAdmissionPointerSnapshot` constructor. The public-production target is assembled only after the exact catalog, committed pointer-owner proof, and launch association agree. Missing adapter fields are implementation gaps, not authority to fabricate values. Request IDs, hold IDs/fences, reservation/claim IDs, audit row IDs, `ownerProofDigest`, terminal timestamps, and pointer-snapshot proof digests are authenticated owner evidence bound separately to the intent; they do not replace digest-bound catalog or pointer content. See [Multi-Tenancy](../../system-architecture-multi-tenancy.md).

### Authored Public Source

`authoredSource` preserves the full Game Design-owned public authored initial actor-core projection, not a private capture or selected-commit payload. The approved source mapping was checked against the preserved `AuthoredInitialActorCoreProjection` implementation at source commit `1dcb173efe9670557e4db182ae5df10c04d95e79`; its bounded receiving implementation is now present in common-platform-core. This local-only passive parser does not authenticate a producer or establish publication or activation. The complete preimage projection has exactly this order:

`(schema, bindingId, bindingDigest, canonicalTenantId, canonicalVersionId, worldSourceOperationId, worldSourceEvidenceDigest, worldSlug, publicationWorkflowId, selectedCommitId, selectedCommitDigest, policy, core, descriptor, disposition, safeFeedback)`

| Member | Type and exact source |
| --- | --- |
| `schema` | String `game-design-authored-initial-actor-core-projection/v1` |
| `bindingId` | Canonical non-nil UUID from the retained Game Design binding |
| `bindingDigest` | `sha256:` plus 64 lowercase hexadecimal characters; metadata, not producer authentication |
| `canonicalTenantId`, `canonicalVersionId` | Canonical non-nil UUIDs equal to the target tenant and version |
| `worldSourceOperationId` | Canonical non-nil UUID of the retained published source operation |
| `worldSourceEvidenceDigest` | Exact `sha256:`-prefixed source-content digest |
| `worldSlug` | Exact target selector |
| `publicationWorkflowId` | `ExactUtf8` of the retained public workflow identity |
| `selectedCommitId`, `selectedCommitDigest` | Canonical non-nil UUID and exact `sha256:`-prefixed digest from the selected immutable commit |
| `policy` | Object `(commitId, revisionId, logicalRevisionId, canonicalPolicyJson, contentDigest)` |
| `core` | Object `(canonicalJson)` |
| `descriptor` | Object `(canonicalJson, digest)` |
| `disposition`, `safeFeedback` | Each object `(key, definitionJson, definitionDigest, sourceCommitId, sourceCommitDigest, revisionId, revisionOrder)` |

`worldSourceOperationId` and `publicationWorkflowId` are immutable published-source provenance and are included in this family. They are not the current creation request or operation identity. The latter and all current owner-protection identities remain separately bound and excluded from the digest.

`policy.commitId` and `policy.revisionId` preserve their original source UUIDs and are not aliases for `selectedCommitId`. `logicalRevisionId` and `canonicalPolicyJson` are `ExactUtf8`; the latter is the complete retained RFC 8785 schema-three policy with exactly `(schemaVersion=3, worldSlug, worldDisplayName, realmSlug, realmDisplayName, visible, publicProduction, stateScope, entryPolicy=PLAYER_CREATED, creationDescriptor, initialActorCore)`. `contentDigest` is its unchanged `sha256:`-prefixed digest. The closed source parser decodes and cross-checks the complete selectors, metadata, flags, scope, and descriptor against this operation; an aggregate policy digest is not a substitute.

`core.canonicalJson` is `ExactUtf8` of exactly `{displayName, dispositionKey}`. It is authored initial core, not live presence, occupancy, guessed RPG defaults, or component-initialization proof.

`descriptor.canonicalJson` is `ExactUtf8` of the complete existing schema-one descriptor with exactly `{schemaVersion:1, descriptorId:UUID, descriptorRevisionId:UUID, inputKind:EMPTY_OBJECT, dependencies:[]}`. `descriptor.digest` is its unchanged `sha256:`-prefixed content digest. The parser must read descriptor identity and revision from this source and match the published policy; they cannot be independently supplied.

Each rule `key` is a canonical ASCII identifier. `definitionJson` is `ExactUtf8` of the complete original canonical definition. `definitionDigest` and `sourceCommitDigest` are `sha256:`-prefixed strings; `sourceCommitId` and `revisionId` are canonical non-nil UUIDs. `revisionOrder` is the unchanged nonnegative canonical decimal string, bounded by the 1 MiB original public projection; it is not narrowed to a legacy numeric ID. The disposition definition contains `(dispositionKey, ordered unique deniedAdmissionTags, safeFeedbackKey)`. The feedback definition contains `(feedbackKey, outcomeEvent, audience, messageKey, messageTemplate, ordered arguments(key, kind), visibility, replayPolicy)`. Retain source array order, original source commit/revision/order, and full definitions. Require exactly one disposition and its mandatory referenced feedback; do not relabel source commits as the later selected commit.

The complete existing public projection bytes and digest are retained separately and reconstructed byte-for-byte from these fields for source verification. Owner-private publication, capture, terminal, and selected-commit frames and target row keys do not cross this boundary. Qualified mTLS reads plus exact owner readback authenticate the producer; a binding, projection, or content digest alone authenticates nothing.

## ExactUtf8

`ExactUtf8` is an ordinary `mutationDigest/v1` object with exactly the ordered members `(encoding, bytes)`: `encoding` is the string `utf8-base64url-unpadded`, and `bytes` is a present ASCII base64url string containing the exact valid UTF-8 source bytes. Decode within the declared source bound, then re-encode and require exact equality, including zero unused bits and no padding. This wrapper does not change the digest grammar and is not authentication.

Use `ExactUtf8` for every retained source field not guaranteed ASCII, including display names, workflow and logical revision text, complete policy/core/descriptor canonical JSON, and rule definition JSON. Required nonblank fields must remain nonblank after decoding. Validate source JSON with its owning bounded closed parser and verify its exact canonical bytes and digest; never NFC-rewrite retained source. RFC 8785 does not normalize Unicode, while ordinary `mutationDigest/v1` strings normalize NFC, so this reversible ASCII framing preserves the published bytes without restamping the source.

## Bounds and Parsing

Enforce these family-local bounds while reading the raw action and before allocation, normalization, parsing, or hashing:

| Boundary | Limit |
| --- | --- |
| Raw action transport | 2 MiB |
| Action nesting depth | 16 |
| Members per action object | 16 |
| Encoded raw JSON string token | 1.5 MiB |
| Framing/count lexeme | 10 ASCII digits |
| Ordinary canonical identity/string token | 1024 bytes |
| Normalized single string | 1.5 MiB |
| One typed segment | 2 MiB |
| Complete preimage | 2 MiB |

Owner `long` counters are at most 19 decimal digits and must fit the positive signed-long range. `revisionOrder` remains a nonnegative decimal string and is bounded by the original 1 MiB public projection. The complete retained authored public projection must be at most 1 MiB before reconstruction or owner parsing. This source bound does not imply that every projection within 1 MiB fits the 2 MiB action or preimage limits.

Bound decoded `ExactUtf8` bytes before allocating the decoded value: policy JSON at 4096 bytes, descriptor JSON at 4096 bytes, complete public source at 1 MiB, workflow identity at 256 Java characters and 1024 UTF-8 bytes, and logical revision at 128 Java characters and 512 UTF-8 bytes. Display names retain the existing limit of 128 code points, no controls, and already stripped form; slugs are at most 64 ASCII characters; rule keys are at most 128 ASCII characters. Check base64 overhead incrementally and reject over-limit input rather than accepting and truncating it.

For every `ExactUtf8.bytes` value, the unescaped ASCII base64url payload must also fit the unpadded base64 length of that field's decoded-byte limit; the raw JSON string token independently remains within 1.5 MiB. For example, a 4096-byte policy or descriptor allows at most 5462 base64url payload characters, a 1024-byte workflow identity at most 1366, and a 512-byte logical revision at most 683. A decoded 1 MiB bound allows at most 1398102 payload characters, but the full action and preimage bounds still apply. These are separate encoded-token, unescaped encoded-payload, and decoded-byte checks; an escaped oversized token cannot evade a decoded bound or allocate an oversized intermediate buffer. Core and rule definition JSON remain within their owning complete-projection bound and the independently enforced action/preimage bounds.

Embedded source parsing has explicit raw limits rather than relying on parser defaults: depth 16, object members 16, array elements 262144, and raw token/source bytes no greater than the owning source bound. Use closed schema fields, enforce key uniqueness, and preserve array order. These receiving bounds do not rewrite source or declare broader producer activation complete. An otherwise valid retained source over any receiving bound remains retained and this creation mutation is denied as unsupported or over-limit; no silent repair is permitted.

These limits apply only to this action family. Existing `StartSession` APIs, bytes, vectors, and rejection bounds remain identical.

## Contradictions and Separate Operation Binding

Before computing or accepting a digest, reject contradictory duplicate identifiers, selectors, counters, snapshot values, policy values, or descriptor values. Cross-check the complete scope, target, catalog, pointer, expected-version, policy, descriptor, and authored-source values; do not resolve a contradiction by preferring one copy or by treating a digest as authority. The required shared vectors must include these denials.

The digest excludes credentials, JWT/reference values, authenticated workload evidence, current creation request/operation IDs, owner-protection IDs/fences, and owner authority/claim evidence. Those values are separately bound to the same durable intent and revalidated by each owner protection. The digest-bound discovery timestamp members are the exact `evaluatedAt` and `connectScopeExpiresAt` values declared in `expectedVersion`; they do not replace separately bound `connectScopeId`, authority evidence, or protection facts. Entity's terminal outcome and replay guard remain bound to the exact request, digest, and intent. See the [Entity owner-protection contract](./api-contracts.md#character-creation-owner-protection) and [ADR 0140](../../decisions/adr-0140-realm-authored-controllable-actor-entry.md).

## Shared Vectors And Remaining Proof

The [shared offline vector resource](../../../../services/common-platform-core/src/test/resources/operator/character-create-v1-vectors.json) contains four literal complete preimages and SHA-256 digests: public `PLAYER_CREATED`, decomposed Unicode retained through `ExactUtf8`, signed-long maximum counters with rule revision orders beyond signed-long range, and a changed Account UUID. The [focused vector test](../../../../services/common-platform-core/src/test/java/net/firedevops/firemud/common/operator/CharacterCreateActionSchemaVectorsTest.java) checks the unchanged existing value grammar against those literals. Its source identities and evidence are run-owned fixtures, not authenticated owner material. Its `ABSENT` string markers describe fixture presence only and are not raw-action values. These small vectors remain within StartSession's existing generic encoder limits and establish no larger-limit family support.

Production support still requires Game Session to produce these preimages from qualified owner reads and Entity to compose them with the terminal and owner-protection checks; neither flow is implemented by this passive receiver. The shared resource now contains the required negative vectors, and focused codec tests exercise the production decoder against them. Cross-language consumer execution also remains pending. The typed-preimage test does not substitute for those production and owner proofs.
