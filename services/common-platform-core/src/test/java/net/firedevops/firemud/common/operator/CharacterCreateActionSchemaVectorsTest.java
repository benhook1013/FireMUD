package net.firedevops.firemud.common.operator;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Positive typed preimage vectors for CHARACTER_CREATE/1.
 *
 * <p>This test frames the approved seven outer fields with the existing generic value grammar and
 * locks the four published positive literals. Shared negative raw-action cases are exercised by
 * {@link CharacterCreateOperatorActionCodecTest}; neither test verifies source ownership or claims
 * creation support.
 */
class CharacterCreateActionSchemaVectorsTest {
  private static final String VECTOR_RESOURCE = "/operator/character-create-v1-vectors.json";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> OUTER_FIELDS =
      List.of(
          "actionFamilySchemaId",
          "actionFamilySchemaVersion",
          "scope",
          "target",
          "expectedVersion",
          "mutation",
          "auditReason");

  @Test
  void matchesLiteralSourceOnlyPreimageAndDigestVectors() throws IOException {
    JsonNode root = fixture();
    assertThat(root.path("vectors").size()).isEqualTo(4);
    Map<String, String> digestsByName = new HashMap<>();

    for (JsonNode vector : root.path("vectors")) {
      String name = text(vector, "name");
      byte[] preimage = canonicalPreimage(root, vector);
      assertThat(preimage.length)
          .as("generic preimage bound for %s", name)
          .isLessThanOrEqualTo(MutationDigestV1.MAX_PREIMAGE_BYTES);
      String digest = MutationDigestV1.sha256Hex(preimage);

      assertThat(HexFormat.of().formatHex(preimage))
          .as("complete preimage for %s", name)
          .isEqualTo(text(vector, "preimageHex"));
      assertThat(digest).as("SHA-256 for %s", name).isEqualTo(text(vector, "digest"));
      digestsByName.put(name, digest);
    }

    assertThat(digestsByName)
        .containsOnlyKeys(
            "base-public-player-created",
            "unicode-decomposed-exact-utf8",
            "large-counters-and-revision-order",
            "changed-account-id");
    assertThat(digestsByName.get("changed-account-id"))
        .isNotEqualTo(digestsByName.get("base-public-player-created"));
  }

  @Test
  void exactUtf8RetainsDecomposedSourceBytes() throws IOException {
    JsonNode vector = findVector("unicode-decomposed-exact-utf8");
    JsonNode mutation = vector.path("mutation");
    JsonNode catalog = mutation.path("catalog");
    JsonNode authored = mutation.path("authoredSource");
    JsonNode policy = authored.path("policy");
    JsonNode core = authored.path("core");

    String workflow = text(authored, "publicationWorkflowId");
    String worldDisplayName = text(catalog, "worldDisplayName");
    String policyJson = text(policy, "canonicalPolicyJson");
    String coreJson = text(core, "canonicalJson");
    for (String retainedSource : List.of(workflow, worldDisplayName, policyJson, coreJson)) {
      assertThat(Normalizer.isNormalized(retainedSource, Normalizer.Form.NFC)).isFalse();
      byte[] originalUtf8 = retainedSource.getBytes(StandardCharsets.UTF_8);
      byte[] decodedUtf8 = Base64.getUrlDecoder().decode(exactUtf8Payload(retainedSource));
      assertThat(decodedUtf8).containsExactly(originalUtf8);
      assertThat(new String(decodedUtf8, StandardCharsets.UTF_8)).isEqualTo(retainedSource);
    }
    assertThat(text(authored, "worldSourceOperationId"))
        .isEqualTo("a8000000-0000-4000-8000-000000000002");
    assertThat(workflow).contains("e\u0301");
  }

  @Test
  void changedImmutableAccountIdHasItsOwnDifferentGolden() throws IOException {
    JsonNode base = findVector("base-public-player-created");
    JsonNode changed = findVector("changed-account-id");

    assertThat(text(base.path("target"), "accountId"))
        .isNotEqualTo(text(changed.path("target"), "accountId"));
    assertThat(MutationDigestV1.sha256Hex(canonicalPreimage(fixture(), base)))
        .isNotEqualTo(MutationDigestV1.sha256Hex(canonicalPreimage(fixture(), changed)));
  }

  @Test
  void largeCountersAndRuleRevisionOrdersRemainCanonicalDecimalStrings() throws IOException {
    JsonNode vector = findVector("large-counters-and-revision-order");
    JsonNode expectedVersion = vector.path("expectedVersion");
    JsonNode disposition = vector.path("mutation").path("authoredSource").path("disposition");
    JsonNode safeFeedback = vector.path("mutation").path("authoredSource").path("safeFeedback");
    String maxSignedLong = "9223372036854775807";

    for (String field : List.of("catalogRevision", "pointerVersion", "activeLifecycleEpoch")) {
      assertThat(expectedVersion.path(field).isTextual()).as(field).isTrue();
      assertThat(text(expectedVersion, field)).as(field).isEqualTo(maxSignedLong);
      assertThat(new BigInteger(text(expectedVersion, field)))
          .as(field)
          .isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));
    }
    for (JsonNode rule : List.of(disposition, safeFeedback)) {
      String revisionOrder = text(rule, "revisionOrder");
      assertThat(revisionOrder).matches("0|[1-9][0-9]*");
      assertThat(new BigInteger(revisionOrder)).isGreaterThan(BigInteger.valueOf(Long.MAX_VALUE));
    }
  }

  private static byte[] canonicalPreimage(JsonNode root, JsonNode vector) {
    JsonNode scope = vector.path("scope");
    JsonNode target = vector.path("target");
    JsonNode expectedVersion = vector.path("expectedVersion");
    JsonNode mutation = vector.path("mutation");
    List<MutationDigestV1.Member> fields =
        List.of(
            member(
                "actionFamilySchemaId",
                MutationDigestV1.string(text(root, "actionFamilySchemaId"))),
            member(
                "actionFamilySchemaVersion",
                MutationDigestV1.string(text(root, "actionFamilySchemaVersion"))),
            member(
                "scope",
                object(
                    member(
                        "targetNamespace", MutationDigestV1.string(text(scope, "targetNamespace"))),
                    member("tenantId", MutationDigestV1.string(text(scope, "tenantId"))),
                    member(
                        "playableStateNamespaceId",
                        MutationDigestV1.string(text(scope, "playableStateNamespaceId"))),
                    member(
                        "playableStateScope",
                        MutationDigestV1.string(text(scope, "playableStateScope"))))),
            member(
                "target",
                object(
                    member("accountId", MutationDigestV1.string(text(target, "accountId"))),
                    member("realmId", MutationDigestV1.string(text(target, "realmId"))),
                    member("worldSlug", MutationDigestV1.string(text(target, "worldSlug"))),
                    member("realmSlug", MutationDigestV1.string(text(target, "realmSlug"))),
                    member(
                        "gameInstanceId", MutationDigestV1.string(text(target, "gameInstanceId"))),
                    member("versionId", MutationDigestV1.string(text(target, "versionId"))))),
            member(
                "expectedVersion",
                object(
                    member(
                        "catalogRevision",
                        MutationDigestV1.string(text(expectedVersion, "catalogRevision"))),
                    member(
                        "pointerVersion",
                        MutationDigestV1.string(text(expectedVersion, "pointerVersion"))),
                    member(
                        "activeLifecycleEpoch",
                        MutationDigestV1.string(text(expectedVersion, "activeLifecycleEpoch"))),
                    member(
                        "playtestLifecycleId", absentSlot(expectedVersion, "playtestLifecycleId")),
                    member(
                        "playtestStateGeneration",
                        absentSlot(expectedVersion, "playtestStateGeneration")),
                    member(
                        "evaluatedAt",
                        MutationDigestV1.string(text(expectedVersion, "evaluatedAt"))),
                    member(
                        "connectScopeExpiresAt",
                        MutationDigestV1.string(text(expectedVersion, "connectScopeExpiresAt"))))),
            member(
                "mutation",
                object(
                    member("purpose", MutationDigestV1.string(text(mutation, "purpose"))),
                    member("entryPolicy", MutationDigestV1.string(text(mutation, "entryPolicy"))),
                    member("catalog", catalog(mutation.path("catalog"))),
                    member("pointer", pointer(mutation.path("pointer"))),
                    member("authoredSource", authoredSource(mutation.path("authoredSource"))),
                    member(
                        "canonicalCreationInput",
                        MutationDigestV1.string(text(mutation, "canonicalCreationInput"))))),
            member("auditReason", absentSlot(vector, "auditReason")));

    assertThat(fields.stream().map(MutationDigestV1.Member::key))
        .containsExactlyElementsOf(OUTER_FIELDS);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    writeSegment(output, "mutationDigest/v1".getBytes(StandardCharsets.US_ASCII));
    for (MutationDigestV1.Member field : fields) {
      writeSegment(output, field.key().getBytes(StandardCharsets.US_ASCII));
      output.writeBytes(MutationDigestV1.encodedValue(field.value()));
    }
    return output.toByteArray();
  }

  private static MutationDigestV1.Value catalog(JsonNode node) {
    return object(
        member("tenantId", MutationDigestV1.string(text(node, "tenantId"))),
        member("tenantSlug", MutationDigestV1.string(text(node, "tenantSlug"))),
        member("worldSlug", MutationDigestV1.string(text(node, "worldSlug"))),
        member("worldDisplayName", exactUtf8(text(node, "worldDisplayName"))),
        member("realmId", MutationDigestV1.string(text(node, "realmId"))),
        member("realmSlug", MutationDigestV1.string(text(node, "realmSlug"))),
        member("realmDisplayName", exactUtf8(text(node, "realmDisplayName"))),
        member("visible", MutationDigestV1.bool(bool(node, "visible"))),
        member("publicProduction", MutationDigestV1.bool(bool(node, "publicProduction"))),
        member("stateScope", MutationDigestV1.string(text(node, "stateScope"))),
        member(
            "playableStateNamespaceId",
            MutationDigestV1.string(text(node, "playableStateNamespaceId"))),
        member(
            "characterCreationPolicy",
            MutationDigestV1.string(text(node, "characterCreationPolicy"))),
        member("catalogRevision", MutationDigestV1.string(text(node, "catalogRevision"))));
  }

  private static MutationDigestV1.Value pointer(JsonNode node) {
    return object(
        member("tenantId", MutationDigestV1.string(text(node, "tenantId"))),
        member("realmId", MutationDigestV1.string(text(node, "realmId"))),
        member("worldSlug", MutationDigestV1.string(text(node, "worldSlug"))),
        member("realmSlug", MutationDigestV1.string(text(node, "realmSlug"))),
        member("playtestLifecycleId", absentSlot(node, "playtestLifecycleId")),
        member("playtestStateGeneration", absentSlot(node, "playtestStateGeneration")),
        member("admissionState", MutationDigestV1.string(text(node, "admissionState"))),
        member(
            "admissibleGameInstanceId",
            MutationDigestV1.string(text(node, "admissibleGameInstanceId"))),
        member("pointerVersion", MutationDigestV1.string(text(node, "pointerVersion"))),
        member("catalogRevision", MutationDigestV1.string(text(node, "catalogRevision"))),
        member("updatedAt", MutationDigestV1.string(text(node, "updatedAt"))));
  }

  private static MutationDigestV1.Value authoredSource(JsonNode node) {
    JsonNode policy = node.path("policy");
    JsonNode core = node.path("core");
    JsonNode descriptor = node.path("descriptor");
    return object(
        member("schema", MutationDigestV1.string(text(node, "schema"))),
        member("bindingId", MutationDigestV1.string(text(node, "bindingId"))),
        member("bindingDigest", MutationDigestV1.string(text(node, "bindingDigest"))),
        member("canonicalTenantId", MutationDigestV1.string(text(node, "canonicalTenantId"))),
        member("canonicalVersionId", MutationDigestV1.string(text(node, "canonicalVersionId"))),
        member(
            "worldSourceOperationId",
            MutationDigestV1.string(text(node, "worldSourceOperationId"))),
        member(
            "worldSourceEvidenceDigest",
            MutationDigestV1.string(text(node, "worldSourceEvidenceDigest"))),
        member("worldSlug", MutationDigestV1.string(text(node, "worldSlug"))),
        member("publicationWorkflowId", exactUtf8(text(node, "publicationWorkflowId"))),
        member("selectedCommitId", MutationDigestV1.string(text(node, "selectedCommitId"))),
        member("selectedCommitDigest", MutationDigestV1.string(text(node, "selectedCommitDigest"))),
        member(
            "policy",
            object(
                member("commitId", MutationDigestV1.string(text(policy, "commitId"))),
                member("revisionId", MutationDigestV1.string(text(policy, "revisionId"))),
                member("logicalRevisionId", exactUtf8(text(policy, "logicalRevisionId"))),
                member("canonicalPolicyJson", exactUtf8(text(policy, "canonicalPolicyJson"))),
                member("contentDigest", MutationDigestV1.string(text(policy, "contentDigest"))))),
        member("core", object(member("canonicalJson", exactUtf8(text(core, "canonicalJson"))))),
        member(
            "descriptor",
            object(
                member("canonicalJson", exactUtf8(text(descriptor, "canonicalJson"))),
                member("digest", MutationDigestV1.string(text(descriptor, "digest"))))),
        member("disposition", rule(node.path("disposition"))),
        member("safeFeedback", rule(node.path("safeFeedback"))));
  }

  private static MutationDigestV1.Value rule(JsonNode node) {
    return object(
        member("key", MutationDigestV1.string(text(node, "key"))),
        member("definitionJson", exactUtf8(text(node, "definitionJson"))),
        member("definitionDigest", MutationDigestV1.string(text(node, "definitionDigest"))),
        member("sourceCommitId", MutationDigestV1.string(text(node, "sourceCommitId"))),
        member("sourceCommitDigest", MutationDigestV1.string(text(node, "sourceCommitDigest"))),
        member("revisionId", MutationDigestV1.string(text(node, "revisionId"))),
        member("revisionOrder", MutationDigestV1.string(text(node, "revisionOrder"))));
  }

  private static MutationDigestV1.Value exactUtf8(String sourceText) {
    return object(
        member("encoding", MutationDigestV1.string("utf8-base64url-unpadded")),
        member("bytes", MutationDigestV1.string(exactUtf8Payload(sourceText))));
  }

  private static String exactUtf8Payload(String sourceText) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(sourceText.getBytes(StandardCharsets.UTF_8));
  }

  private static MutationDigestV1.Value absentSlot(JsonNode node, String field) {
    assertThat(text(node, field)).as("declared ABSENT slot %s", field).isEqualTo("ABSENT");
    return MutationDigestV1.absent();
  }

  private static MutationDigestV1.Member member(String name, MutationDigestV1.Value value) {
    return MutationDigestV1.member(name, value);
  }

  private static MutationDigestV1.Value object(MutationDigestV1.Member... members) {
    return MutationDigestV1.object(List.of(members));
  }

  private static void writeSegment(ByteArrayOutputStream output, byte[] bytes) {
    assertThat(bytes.length).isLessThanOrEqualTo(MutationDigestV1.MAX_SEGMENT_BYTES);
    output.writeBytes(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
    output.write(':');
    output.writeBytes(bytes);
  }

  private static JsonNode fixture() throws IOException {
    try (InputStream stream =
        CharacterCreateActionSchemaVectorsTest.class.getResourceAsStream(VECTOR_RESOURCE)) {
      if (stream == null) {
        throw new IOException("missing source-only CHARACTER_CREATE/1 vector resource");
      }
      return JSON.readTree(stream);
    }
  }

  private static JsonNode findVector(String name) throws IOException {
    for (JsonNode vector : fixture().path("vectors")) {
      if (name.equals(vector.path("name").asText())) {
        return vector;
      }
    }
    throw new IOException("missing vector: " + name);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new AssertionError("required textual fixture field missing: " + field);
    }
    return value.textValue();
  }

  private static boolean bool(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isBoolean()) {
      throw new AssertionError("required boolean fixture field missing: " + field);
    }
    return value.booleanValue();
  }
}
