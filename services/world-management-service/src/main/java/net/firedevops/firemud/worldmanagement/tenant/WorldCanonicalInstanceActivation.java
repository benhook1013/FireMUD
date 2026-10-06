package net.firedevops.firemud.worldmanagement.tenant;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Immutable operation identity and result for the default-denied canonical World activation cut.
 */
public final class WorldCanonicalInstanceActivation {
  private static final String REQUEST_SCHEMA = "world-canonical-instance-activation-request/v1";
  private static final String RESULT_SCHEMA = "world-canonical-instance-activation-result/v1";
  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "schema",
          "activationRequestId",
          "requestDigest",
          "requestBytesBase64",
          "preparingEvidenceBytesBase64",
          "outcome",
          "terminalCode",
          "lifecycleEvidenceBytesBase64");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();

  private WorldCanonicalInstanceActivation() {}

  /**
   * Stable operation identity. The nested World lifecycle read UUID is correlation metadata and is
   * excluded from {@link #canonicalRequestBytes()}, while the original complete proof remains in
   * {@link #preparingEvidenceBytes()} and the immutable operation result.
   */
  public record Request(
      UUID activationRequestId, WorldCanonicalInstanceLifecycleEvidence preparing) {
    public Request {
      requireNonNil(activationRequestId, "activationRequestId");
      Objects.requireNonNull(preparing, "preparing");
      if (!"PREPARING".equals(preparing.lifecycleStatus())) {
        throw new IllegalArgumentException(
            "Canonical activation requires exact PREPARING evidence");
      }
    }

    public UUID canonicalGameInstanceId() {
      return preparing.request().canonicalGameInstanceId();
    }

    public long expectedLifecycleEpoch() {
      return preparing.lifecycleEpoch();
    }

    public long expectedRowVersion() {
      return preparing.rowVersion();
    }

    /** Canonical operation bytes omit only the transport lifecycle-read UUID. */
    public byte[] canonicalRequestBytes() {
      try {
        JsonNode evidence = JSON.readTree(preparing.canonicalBytes());
        JsonNode lifecycleRequest = evidence.path("request");
        if (!lifecycleRequest.isObject()) {
          throw new IllegalArgumentException("Canonical PREPARING proof has no closed request");
        }
        ((tools.jackson.databind.node.ObjectNode) lifecycleRequest).remove("readRequestId");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("schema", REQUEST_SCHEMA);
        request.put("activationRequestId", activationRequestId.toString());
        request.put("preparingEvidence", evidence);
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(request));
      } catch (IOException invalid) {
        throw new IllegalArgumentException("Canonical activation request is invalid", invalid);
      }
    }

    public byte[] preparingEvidenceBytes() {
      return preparing.canonicalBytes();
    }

    public String requestDigest() {
      return digest(canonicalRequestBytes());
    }
  }

  public enum Outcome {
    COMMITTED,
    ABORTED
  }

  /** Immutable success or terminal stale-precondition outcome; ABORTED never grants admission. */
  public record Result(
      Request request,
      Outcome outcome,
      String terminalCode,
      WorldCanonicalInstanceLifecycleEvidence lifecycleEvidence) {
    public Result {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(outcome, "outcome");
      Objects.requireNonNull(lifecycleEvidence, "lifecycleEvidence");
      if (!request.preparing().request().equals(lifecycleEvidence.request())) {
        throw new IllegalArgumentException(
            "Canonical activation result differs from its original lifecycle-read request");
      }
      if (outcome == Outcome.COMMITTED) {
        if (terminalCode != null
            || !"ACTIVE".equals(lifecycleEvidence.lifecycleStatus())
            || lifecycleEvidence.lifecycleEpoch()
                != Math.addExact(request.expectedLifecycleEpoch(), 1L)
            || lifecycleEvidence.rowVersion() != Math.addExact(request.expectedRowVersion(), 1L)) {
          throw new IllegalArgumentException(
              "Committed activation result is not the exact next ACTIVE state");
        }
      } else if (terminalCode == null || terminalCode.isBlank()) {
        throw new IllegalArgumentException("Aborted activation result requires a terminal code");
      }
    }

    public byte[] canonicalBytes() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("schema", RESULT_SCHEMA);
      result.put("activationRequestId", request.activationRequestId().toString());
      result.put("requestDigest", request.requestDigest());
      result.put(
          "requestBytesBase64",
          Base64.getEncoder().encodeToString(request.canonicalRequestBytes()));
      result.put(
          "preparingEvidenceBytesBase64",
          Base64.getEncoder().encodeToString(request.preparingEvidenceBytes()));
      result.put("outcome", outcome.name());
      result.put("terminalCode", terminalCode);
      result.put(
          "lifecycleEvidenceBytesBase64",
          Base64.getEncoder().encodeToString(lifecycleEvidence.canonicalBytes()));
      try {
        return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(result));
      } catch (IOException impossible) {
        throw new IllegalStateException(
            "Canonical activation result cannot be encoded", impossible);
      }
    }

    public static Result fromStored(byte[] stored) {
      Objects.requireNonNull(stored, "stored");
      try {
        String json =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(stored))
                .toString();
        JsonNode root = JSON.readTree(json);
        requireFields(root, RESULT_FIELDS, "Canonical World activation result");
        if (!RESULT_SCHEMA.equals(text(root, "schema"))) {
          throw new IllegalArgumentException("Unsupported canonical activation result schema");
        }
        UUID activationId = UUID.fromString(text(root, "activationRequestId"));
        WorldCanonicalInstanceLifecycleEvidence preparing =
            WorldCanonicalInstanceLifecycleEvidence.fromStored(
                decodeBase64(root, "preparingEvidenceBytesBase64"));
        Request request = new Request(activationId, preparing);
        if (!request.requestDigest().equals(text(root, "requestDigest"))
            || !Arrays.equals(
                request.canonicalRequestBytes(), decodeBase64(root, "requestBytesBase64"))) {
          throw new IllegalArgumentException("Stored activation request digest or bytes differ");
        }
        Outcome outcome = Outcome.valueOf(text(root, "outcome"));
        JsonNode terminal = root.get("terminalCode");
        if (terminal != null && !terminal.isNull() && !terminal.isTextual()) {
          throw new IllegalArgumentException("Stored activation terminalCode must be text or null");
        }
        String terminalCode = terminal == null || terminal.isNull() ? null : terminal.textValue();
        Result result =
            new Result(
                request,
                outcome,
                terminalCode,
                WorldCanonicalInstanceLifecycleEvidence.fromStored(
                    decodeBase64(root, "lifecycleEvidenceBytesBase64")));
        if (!Arrays.equals(stored, result.canonicalBytes())) {
          throw new IllegalArgumentException("Stored canonical activation result is not canonical");
        }
        return result;
      } catch (IOException invalid) {
        throw new IllegalArgumentException(
            "Stored canonical activation result is invalid", invalid);
      }
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static byte[] decodeBase64(JsonNode node, String field) {
    try {
      return Base64.getDecoder().decode(text(node, field));
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("Stored activation " + field + " is invalid", invalid);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Stored activation " + field + " must be text");
    }
    return value.textValue();
  }

  private static void requireFields(JsonNode node, Set<String> fields, String label) {
    if (node == null || !node.isObject() || node.size() != fields.size()) {
      throw new IllegalArgumentException(label + " has an invalid closed object shape");
    }
    for (String name : node.propertyNames()) {
      if (!fields.contains(name)) {
        throw new IllegalArgumentException(label + " has an unknown field");
      }
    }
  }

  private static void requireNonNil(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (new UUID(0L, 0L).equals(value)) {
      throw new IllegalArgumentException(label + " must be non-nil");
    }
  }
}
