package net.firedevops.firemud.common.gamedesign;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftAuthorizationFenceBinding;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.publication.RealmEntryPolicy;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Closed canonical carrier for complete published realm-policy evidence. */
public final class RealmPolicyPublishedEvidenceCodec {
  public static final String SCHEMA = "game-design-published-realm-policy-carrier/v1";

  /**
   * Bounds this complete framed carrier. Existing constituents retain their own limits, including
   * the 4 MiB publication operation and at most 128 policies of at most 4 KiB each; original
   * capture and terminal bytes remain opaque and exact. Larger assembled envelopes fail closed.
   */
  public static final int MAX_ENVELOPE_BYTES = 32 * 1024 * 1024;

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Base64.Encoder BASE64_ENCODER = Base64.getEncoder();
  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  private RealmPolicyPublishedEvidenceCodec() {}

  /** Encodes every field in a closed versioned frame without changing retained evidence bytes. */
  public static byte[] encode(RealmPolicyPublishedEvidence.PublishedSet evidence) {
    Objects.requireNonNull(evidence, "evidence");
    long binaryBytes =
        (long) evidence.operationBytes().length
            + evidence.captureBytes().length
            + evidence.terminalEvidenceBytes().length;
    if (binaryBytes > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("Published realm-policy carrier exceeds 32 MiB");
    }
    byte[] payload = canonicalPayload(evidence).getBytes(StandardCharsets.UTF_8);
    var out = new ByteArrayOutputStream();
    DraftAuthorizationFenceBinding.frame(out, SCHEMA);
    DraftAuthorizationFenceBinding.frame(out, payload);
    byte[] encoded = out.toByteArray();
    if (encoded.length > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException("Published realm-policy carrier exceeds 32 MiB");
    }
    return encoded;
  }

  /** Decodes only the complete canonical v1 carrier and revalidates the reconstructed record. */
  public static RealmPolicyPublishedEvidence.PublishedSet decode(byte[] encoded) {
    if (encoded == null || encoded.length == 0 || encoded.length > MAX_ENVELOPE_BYTES) {
      throw new IllegalArgumentException(
          "Complete bounded published realm-policy carrier required");
    }
    try {
      var reader = new DraftAuthorizationFenceBinding.FrameReader(encoded);
      reader.expect(SCHEMA);
      byte[] payload = reader.bytes();
      reader.requireEnd();
      String json = strictUtf8(payload);
      if (!Arrays.equals(payload, Rfc8785CanonicalJson.canonicalizeUtf8(json))) {
        throw new IllegalArgumentException("Noncanonical published realm-policy carrier JSON");
      }
      JsonNode root = JSON.readTree(json);
      RealmPolicySource.fields(
          root,
          "schema",
          "target",
          "versionNumber",
          "sourceCommitId",
          "sourceEpoch",
          "publishedReleaseBundleRef",
          "publishedReleaseBundleDigest",
          "publishWorkflowId",
          "manifestHash",
          "publicationVersionStateEpoch",
          "operationBytesBase64",
          "captureBytesBase64",
          "terminalEvidenceBytesBase64",
          "policyCount",
          "policySetDigest",
          "policies");
      if (!SCHEMA.equals(RealmPolicySource.text(root, "schema"))) {
        throw new IllegalArgumentException("Unsupported published realm-policy carrier schema");
      }
      DraftCommitBinding.TargetProof target = target(root.get("target"));
      int versionNumber = positiveInt(root, "versionNumber");
      UUID sourceCommitId = uuid(root, "sourceCommitId");
      String sourceEpoch = RealmPolicySource.text(root, "sourceEpoch");
      if (!sourceEpoch.matches("0|[1-9][0-9]*")) {
        throw new IllegalArgumentException("Canonical source epoch required");
      }
      String publishedReleaseBundleRef = RealmPolicySource.text(root, "publishedReleaseBundleRef");
      String publishedReleaseBundleDigest =
          RealmPolicySource.text(root, "publishedReleaseBundleDigest");
      String publishWorkflowId = RealmPolicySource.text(root, "publishWorkflowId");
      String manifestHash = RealmPolicySource.text(root, "manifestHash");
      long publicationVersionStateEpoch = positiveLong(root, "publicationVersionStateEpoch");
      byte[] operationBytes = base64(root, "operationBytesBase64");
      byte[] captureBytes = base64(root, "captureBytesBase64");
      byte[] terminalEvidenceBytes = base64(root, "terminalEvidenceBytesBase64");
      int policyCount = positiveInt(root, "policyCount");
      if (policyCount > RealmPolicySource.MAX_POLICIES) {
        throw new IllegalArgumentException("Published policy count exceeds the source limit");
      }
      String policySetDigest = RealmPolicySource.text(root, "policySetDigest");
      List<RealmPolicyPublishedEvidence.Policy> policies = policies(root.get("policies"));
      var evidence =
          new RealmPolicyPublishedEvidence.PublishedSet(
              target,
              versionNumber,
              sourceCommitId,
              sourceEpoch,
              publishedReleaseBundleRef,
              publishedReleaseBundleDigest,
              publishWorkflowId,
              manifestHash,
              publicationVersionStateEpoch,
              operationBytes,
              captureBytes,
              terminalEvidenceBytes,
              policyCount,
              policySetDigest,
              policies);
      if (!Arrays.equals(encoded, encode(evidence))) {
        throw new IllegalArgumentException("Noncanonical published realm-policy carrier frame");
      }
      return evidence;
    } catch (IOException malformed) {
      throw new IllegalArgumentException("Malformed published realm-policy carrier", malformed);
    } catch (RuntimeException malformed) {
      if (malformed instanceof IllegalArgumentException invalid) {
        throw invalid;
      }
      throw new IllegalArgumentException("Malformed published realm-policy carrier", malformed);
    }
  }

  private static String canonicalPayload(RealmPolicyPublishedEvidence.PublishedSet evidence) {
    var target = evidence.target();
    var targetJson =
        map(
            "canonicalTenantId", target.canonicalTenantId().toString(),
            "canonicalVersionId", target.canonicalVersionId().toString(),
            "gameDesignVersionRowId", Long.toString(target.gameDesignVersionRowId()),
            "gameDesignVersionTenantKey", target.gameDesignVersionTenantKey(),
            "sourceGameRowId", Long.toString(target.sourceGameRowId()),
            "sourceGameTenantKey", target.sourceGameTenantKey(),
            "sourceProvenanceKind", target.sourceProvenanceKind());
    var policies =
        evidence.policies().stream()
            .map(
                policy ->
                    map(
                        "policyId", policy.policyId().toString(),
                        "source",
                            map(
                                "commitId", policy.source().commitId().toString(),
                                "revisionId", policy.source().revisionId().toString(),
                                "logicalRevisionId", policy.source().logicalRevisionId(),
                                "canonicalPolicyJson", policy.source().policy().canonicalJson()),
                        "policyDigest", policy.policyDigest()))
            .toList();
    return RealmPolicySource.canonical(
        map(
            "schema", SCHEMA,
            "target", targetJson,
            "versionNumber", Integer.toString(evidence.versionNumber()),
            "sourceCommitId", evidence.sourceCommitId().toString(),
            "sourceEpoch", evidence.sourceEpoch(),
            "publishedReleaseBundleRef", evidence.publishedReleaseBundleRef(),
            "publishedReleaseBundleDigest", evidence.publishedReleaseBundleDigest(),
            "publishWorkflowId", evidence.publishWorkflowId(),
            "manifestHash", evidence.manifestHash(),
            "publicationVersionStateEpoch", Long.toString(evidence.publicationVersionStateEpoch()),
            "operationBytesBase64", BASE64_ENCODER.encodeToString(evidence.operationBytes()),
            "captureBytesBase64", BASE64_ENCODER.encodeToString(evidence.captureBytes()),
            "terminalEvidenceBytesBase64",
                BASE64_ENCODER.encodeToString(evidence.terminalEvidenceBytes()),
            "policyCount", Integer.toString(evidence.policyCount()),
            "policySetDigest", evidence.policySetDigest(),
            "policies", policies));
  }

  private static DraftCommitBinding.TargetProof target(JsonNode node) {
    RealmPolicySource.fields(
        node,
        "canonicalTenantId",
        "canonicalVersionId",
        "gameDesignVersionRowId",
        "gameDesignVersionTenantKey",
        "sourceGameRowId",
        "sourceGameTenantKey",
        "sourceProvenanceKind");
    return new DraftCommitBinding.TargetProof(
        uuid(node, "canonicalTenantId"),
        uuid(node, "canonicalVersionId"),
        positiveLong(node, "gameDesignVersionRowId"),
        RealmPolicySource.text(node, "gameDesignVersionTenantKey"),
        positiveLong(node, "sourceGameRowId"),
        RealmPolicySource.text(node, "sourceGameTenantKey"),
        RealmPolicySource.text(node, "sourceProvenanceKind"));
  }

  private static List<RealmPolicyPublishedEvidence.Policy> policies(JsonNode node) {
    if (node == null
        || !node.isArray()
        || node.size() < 1
        || node.size() > RealmPolicySource.MAX_POLICIES) {
      throw new IllegalArgumentException("Complete bounded published policy list required");
    }
    var result = new ArrayList<RealmPolicyPublishedEvidence.Policy>(node.size());
    for (JsonNode policyNode : node) {
      RealmPolicySource.fields(policyNode, "policyId", "source", "policyDigest");
      JsonNode sourceNode = policyNode.get("source");
      RealmPolicySource.fields(
          sourceNode, "commitId", "revisionId", "logicalRevisionId", "canonicalPolicyJson");
      RealmEntryPolicy policy =
          RealmEntryPolicy.parseCanonical(
              RealmPolicySource.text(sourceNode, "canonicalPolicyJson"), JSON);
      result.add(
          new RealmPolicyPublishedEvidence.Policy(
              uuid(policyNode, "policyId"),
              new RealmPolicySource.Policy(
                  uuid(sourceNode, "commitId"),
                  uuid(sourceNode, "revisionId"),
                  RealmPolicySource.text(sourceNode, "logicalRevisionId"),
                  policy),
              RealmPolicySource.text(policyNode, "policyDigest")));
    }
    return List.copyOf(result);
  }

  private static byte[] base64(JsonNode object, String field) {
    String encoded = RealmPolicySource.text(object, field);
    final byte[] decoded;
    try {
      decoded = BASE64_DECODER.decode(encoded);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("Canonical base64 required: " + field, malformed);
    }
    if (!BASE64_ENCODER.encodeToString(decoded).equals(encoded)) {
      throw new IllegalArgumentException("Canonical base64 required: " + field);
    }
    return decoded;
  }

  private static UUID uuid(JsonNode object, String field) {
    String encoded = RealmPolicySource.text(object, field);
    try {
      UUID parsed = UUID.fromString(encoded);
      if (!parsed.toString().equals(encoded) || parsed.equals(new UUID(0L, 0L))) {
        throw new IllegalArgumentException("Canonical non-nil UUID required: " + field);
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw new IllegalArgumentException("Canonical non-nil UUID required: " + field, malformed);
    }
  }

  private static int positiveInt(JsonNode object, String field) {
    String value = RealmPolicySource.text(object, field);
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException("Canonical positive counter required: " + field);
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed <= 0 || !Integer.toString(parsed).equals(value)) {
        throw new IllegalArgumentException("Canonical positive counter required: " + field);
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw new IllegalArgumentException(
          "Canonical positive counter required: " + field, malformed);
    }
  }

  private static long positiveLong(JsonNode object, String field) {
    String value = RealmPolicySource.text(object, field);
    if (!value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException("Canonical positive counter required: " + field);
    }
    try {
      long parsed = Long.parseLong(value);
      if (parsed <= 0 || !Long.toString(parsed).equals(value)) {
        throw new IllegalArgumentException("Canonical positive counter required: " + field);
      }
      return parsed;
    } catch (NumberFormatException malformed) {
      throw new IllegalArgumentException(
          "Canonical positive counter required: " + field, malformed);
    }
  }

  private static String strictUtf8(byte[] bytes) throws CharacterCodingException {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  private static Map<String, Object> map(Object... entries) {
    if (entries.length % 2 != 0) {
      throw new IllegalArgumentException("Even carrier key/value entries required");
    }
    var result = new LinkedHashMap<String, Object>();
    for (int i = 0; i < entries.length; i += 2) {
      result.put((String) entries[i], entries[i + 1]);
    }
    return result;
  }
}
