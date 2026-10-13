package net.firedevops.firemud.common.world;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Closed, canonical World evidence for the original authored start-location selector. */
public record WorldDraftStartLocationEvidence(
    String targetNamespace,
    UUID operationId,
    UUID requestId,
    UUID commitId,
    UUID authorizationFenceId,
    String accountBindingDigest,
    String bindingDigest,
    RoomTemplateRef startLocation,
    String graphDigest,
    String receiptDigest) {
  public static final String SCHEMA = "world-draft-start-location-receipt/v1";

  private static final Pattern SHA256 = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Set<String> FIELDS =
      Set.of(
          "schema",
          "targetNamespace",
          "operationId",
          "requestId",
          "commitId",
          "authorizationFenceId",
          "accountBindingDigest",
          "bindingDigest",
          "startLocation",
          "graphDigest",
          "receiptDigest");
  private static final Set<String> ROOM_TEMPLATE_REF_FIELDS =
      Set.of("tenantId", "versionId", "roomTemplateId");
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  public WorldDraftStartLocationEvidence {
    if (!GrpcPeerIdentity.isValidNamespace(targetNamespace)) {
      throw new IllegalArgumentException("World workload namespace is invalid");
    }
    requireCanonicalNonNilUuid(operationId, "operationId");
    requireCanonicalNonNilUuid(requestId, "requestId");
    requireCanonicalNonNilUuid(commitId, "commitId");
    requireCanonicalNonNilUuid(authorizationFenceId, "authorizationFenceId");
    requireDigest(accountBindingDigest, "accountBindingDigest");
    requireDigest(bindingDigest, "bindingDigest");
    Objects.requireNonNull(startLocation, "startLocation");
    requireDigest(graphDigest, "graphDigest");
    requireDigest(receiptDigest, "receiptDigest");
    if (!receiptDigest.equals(
        computeReceiptDigest(
            targetNamespace,
            operationId,
            requestId,
            commitId,
            authorizationFenceId,
            accountBindingDigest,
            bindingDigest,
            startLocation,
            graphDigest))) {
      throw new IllegalArgumentException("World start-location receipt digest is invalid");
    }
  }

  /** Creates the one canonical representation from the complete original receipt fields. */
  public static WorldDraftStartLocationEvidence create(
      String targetNamespace,
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID authorizationFenceId,
      String accountBindingDigest,
      String bindingDigest,
      RoomTemplateRef startLocation,
      String graphDigest) {
    return new WorldDraftStartLocationEvidence(
        targetNamespace,
        operationId,
        requestId,
        commitId,
        authorizationFenceId,
        accountBindingDigest,
        bindingDigest,
        startLocation,
        graphDigest,
        computeReceiptDigest(
            targetNamespace,
            operationId,
            requestId,
            commitId,
            authorizationFenceId,
            accountBindingDigest,
            bindingDigest,
            startLocation,
            graphDigest));
  }

  /**
   * Decodes a complete canonical receipt and verifies its closed fields and digest. This does not
   * authenticate the producer or prove the receipt's Account-bound meaning.
   */
  public static WorldDraftStartLocationEvidence fromStored(byte[] stored) {
    Objects.requireNonNull(stored, "stored");
    try {
      String json =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(stored))
              .toString();
      JsonNode value = JSON.readTree(json);
      requireFields(value, FIELDS, "World start-location receipt");
      if (!SCHEMA.equals(text(value, "schema"))) {
        throw new IllegalArgumentException("Unsupported World start-location receipt schema");
      }
      JsonNode selector = value.get("startLocation");
      requireFields(selector, ROOM_TEMPLATE_REF_FIELDS, "World start-location selector");
      var evidence =
          new WorldDraftStartLocationEvidence(
              text(value, "targetNamespace"),
              parseCanonicalNonNilUuid(text(value, "operationId"), "operationId"),
              parseCanonicalNonNilUuid(text(value, "requestId"), "requestId"),
              parseCanonicalNonNilUuid(text(value, "commitId"), "commitId"),
              parseCanonicalNonNilUuid(text(value, "authorizationFenceId"), "authorizationFenceId"),
              text(value, "accountBindingDigest"),
              text(value, "bindingDigest"),
              new RoomTemplateRef(
                  parseCanonicalNonNilUuid(text(selector, "tenantId"), "selector tenantId"),
                  parseCanonicalNonNilUuid(text(selector, "versionId"), "selector versionId"),
                  parseCanonicalNonNilUuid(
                      text(selector, "roomTemplateId"), "selector roomTemplateId")),
              text(value, "graphDigest"),
              text(value, "receiptDigest"));
      if (!Arrays.equals(stored, evidence.canonicalBytes())) {
        throw new IllegalArgumentException("World start-location receipt is not canonical");
      }
      return evidence;
    } catch (IOException | tools.jackson.core.JacksonException invalid) {
      throw new IllegalArgumentException("World start-location receipt is invalid", invalid);
    }
  }

  /** Returns the exact canonical receipt bytes retained by World. */
  public byte[] canonicalBytes() {
    Map<String, Object> selector = new LinkedHashMap<>();
    selector.put("tenantId", startLocation.tenantId().toString());
    selector.put("versionId", startLocation.versionId().toString());
    selector.put("roomTemplateId", startLocation.roomTemplateId().toString());
    Map<String, Object> json = new LinkedHashMap<>();
    json.put("schema", SCHEMA);
    json.put("targetNamespace", targetNamespace);
    json.put("operationId", operationId.toString());
    json.put("requestId", requestId.toString());
    json.put("commitId", commitId.toString());
    json.put("authorizationFenceId", authorizationFenceId.toString());
    json.put("accountBindingDigest", accountBindingDigest);
    json.put("bindingDigest", bindingDigest);
    json.put("startLocation", selector);
    json.put("graphDigest", graphDigest);
    json.put("receiptDigest", receiptDigest);
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(json));
    } catch (IOException impossible) {
      throw new IllegalStateException("World start-location receipt cannot be encoded", impossible);
    }
  }

  private static String computeReceiptDigest(
      String namespace,
      UUID operationId,
      UUID requestId,
      UUID commitId,
      UUID fenceId,
      String accountBindingDigest,
      String bindingDigest,
      RoomTemplateRef selector,
      String graphDigest) {
    Objects.requireNonNull(namespace, "targetNamespace");
    Objects.requireNonNull(operationId, "operationId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(commitId, "commitId");
    Objects.requireNonNull(fenceId, "authorizationFenceId");
    Objects.requireNonNull(accountBindingDigest, "accountBindingDigest");
    Objects.requireNonNull(bindingDigest, "bindingDigest");
    Objects.requireNonNull(selector, "startLocation");
    Objects.requireNonNull(graphDigest, "graphDigest");
    ByteArrayOutputStream framed = new ByteArrayOutputStream();
    for (String segment :
        new String[] {
          SCHEMA,
          namespace,
          operationId.toString(),
          requestId.toString(),
          commitId.toString(),
          fenceId.toString(),
          accountBindingDigest,
          bindingDigest,
          selector.tenantId().toString(),
          selector.versionId().toString(),
          selector.roomTemplateId().toString(),
          graphDigest
        }) {
      byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
      framed.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      framed.writeBytes(bytes);
    }
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(framed.toByteArray()));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void requireCanonicalNonNilUuid(UUID value, String label) {
    Objects.requireNonNull(value, label);
    if (NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
    }
  }

  private static void requireDigest(String value, String label) {
    if (value == null || !SHA256.matcher(value).matches()) {
      throw new IllegalArgumentException(label + " must be a canonical SHA-256 digest");
    }
  }

  private static UUID parseCanonicalNonNilUuid(String value, String label) {
    try {
      UUID parsed = UUID.fromString(value);
      if (NIL_UUID.equals(parsed) || !parsed.toString().equals(value)) {
        throw new IllegalArgumentException(label + " must be a canonical non-nil UUID");
      }
      return parsed;
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException(label + " must be a canonical non-nil UUID", invalid);
    }
  }

  private static void requireFields(JsonNode value, Set<String> expected, String label) {
    if (value == null || !value.isObject()) {
      throw new IllegalArgumentException(label + " must be an object");
    }
    if (value.size() != expected.size()
        || value.properties().stream().anyMatch(entry -> !expected.contains(entry.getKey()))) {
      throw new IllegalArgumentException(label + " has missing or unsupported fields");
    }
  }

  private static String text(JsonNode value, String field) {
    JsonNode child = value.get(field);
    if (child == null || !child.isTextual()) {
      throw new IllegalArgumentException(
          "World start-location receipt field " + field + " is invalid");
    }
    return child.textValue();
  }
}
