package net.firedevops.firemud.common.gamesession;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
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
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewFinalAdmissionRequest;
import net.firedevops.firemud.gamesession.v1.ReadOriginalNewFinalAdmissionResponse;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Closed, bounded Account-to-Game-Session codec for the actual ORIGINAL_NEW final readback. */
public final class CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec {
  public static final int MAX_REQUEST_WIRE_BYTES = 4096;
  public static final int MAX_RESPONSE_WIRE_BYTES = 4096;
  private static final int SCHEMA_VERSION = 1;
  public static final int SESSION_SCHEMA_VERSION = 2;
  private static final int MAX_JSON_BYTES = 3072;
  private static final Pattern UUID_TEXT =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final JsonFactory JSON_FACTORY =
      JsonFactory.builder()
          .streamReadConstraints(
              StreamReadConstraints.builder()
                  .maxNestingDepth(8)
                  .maxStringLength(MAX_JSON_BYTES)
                  .maxNumberLength(8)
                  .build())
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .build();
  private static final JsonMapper JSON =
      JsonMapper.builder(JSON_FACTORY)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> SELECTOR_FIELDS =
      Set.of(
          "schemaVersion",
          "requestId",
          "accountId",
          "tenantId",
          "sessionId",
          "bindingDecisionId",
          "originalLeaseId",
          "originalLeaseFence",
          "originalLeaseEvidenceSha256",
          "tokenSha256",
          "tokenGeneration",
          "issuanceFence",
          "bindingScopeSha256",
          "membershipBaselineSha256",
          "checkpointsSha256",
          "rebindHandleSha256");
  private static final Set<String> RESULT_FIELDS =
      Set.of(
          "schemaVersion",
          "admissionState",
          "requestId",
          "accountId",
          "tenantId",
          "sessionId",
          "bindingDecisionId",
          "originalLeaseId",
          "originalLeaseFence",
          "originalLeaseEvidenceSha256",
          "tokenSha256",
          "tokenGeneration",
          "issuanceFence",
          "bindingScopeSha256",
          "membershipBaselineSha256",
          "checkpointsSha256",
          "rebindHandleSha256",
          "admissionAtMillis",
          "continuityExpiresAtMillis",
          "canonicalSessionDurationMillis",
          "canonicalSessionSha256");

  private CanonicalGameplayOriginalNewFinalAdmissionReadGrpcCodec() {}

  /** Account's expected original anchors. Raw token, raw handle, lineage, and JTI are excluded. */
  public record Selector(
      UUID requestId,
      UUID accountId,
      UUID tenantId,
      UUID sessionId,
      UUID bindingDecisionId,
      UUID originalLeaseId,
      long originalLeaseFence,
      String originalLeaseEvidenceSha256,
      String tokenSha256,
      long tokenGeneration,
      long issuanceFence,
      String bindingScopeSha256,
      String membershipBaselineSha256,
      String checkpointsSha256,
      String rebindHandleSha256) {
    public Selector {
      requireUuid(requestId);
      requireUuid(accountId);
      requireUuid(tenantId);
      requireUuid(sessionId);
      requireUuid(bindingDecisionId);
      requireUuid(originalLeaseId);
      requirePositive(originalLeaseFence, "originalLeaseFence");
      requireDigest(originalLeaseEvidenceSha256);
      requireDigest(tokenSha256);
      if (tokenGeneration != 1L) throw invalid();
      requirePositive(issuanceFence, "issuanceFence");
      requireDigest(bindingScopeSha256);
      requireDigest(membershipBaselineSha256);
      requireDigest(checkpointsSha256);
      requireDigest(rebindHandleSha256);
    }
  }

  /** Metadata from the actual finalized session. It never includes the token or rebind handle. */
  public record FinalResult(
      Selector actualAnchors,
      long admissionAtMillis,
      long continuityExpiresAtMillis,
      long canonicalSessionDurationMillis,
      String canonicalSessionSha256) {
    public FinalResult {
      Objects.requireNonNull(actualAnchors, "actualAnchors");
      requirePositive(admissionAtMillis, "admissionAtMillis");
      requirePositive(continuityExpiresAtMillis, "continuityExpiresAtMillis");
      requirePositive(canonicalSessionDurationMillis, "canonicalSessionDurationMillis");
      if (canonicalSessionDurationMillis > 300_000L) throw invalid();
      long exactExpiry;
      try {
        exactExpiry = Math.addExact(admissionAtMillis, canonicalSessionDurationMillis);
      } catch (ArithmeticException overflow) {
        throw invalid();
      }
      if (continuityExpiresAtMillis != exactExpiry) throw invalid();
      requireDigest(canonicalSessionSha256);
    }
  }

  public static ReadOriginalNewFinalAdmissionRequest toRequest(Selector selector) {
    Objects.requireNonNull(selector, "selector");
    byte[] expected = selectorJson(selector);
    try {
      return ReadOriginalNewFinalAdmissionRequest.newBuilder()
          .addSchemaVersion(SCHEMA_VERSION)
          .addExpectedOriginalAdmissionJson(ByteString.copyFrom(expected))
          .build();
    } finally {
      Arrays.fill(expected, (byte) 0);
    }
  }

  public static Selector parseRequest(ReadOriginalNewFinalAdmissionRequest request) {
    Objects.requireNonNull(request, "request");
    if (request.getSerializedSize() > MAX_REQUEST_WIRE_BYTES
        || !request.getUnknownFields().asMap().isEmpty()
        || request.getSchemaVersionCount() != 1
        || request.getSchemaVersion(0) != SCHEMA_VERSION
        || request.getExpectedOriginalAdmissionJsonCount() != 1) {
      throw invalid();
    }
    byte[] encoded = request.getExpectedOriginalAdmissionJson(0).toByteArray();
    try {
      Map<String, String> fields = parseTextObject(encoded, SELECTOR_FIELDS);
      Selector selector = selector(fields);
      if (!Arrays.equals(encoded, selectorJson(selector))) throw invalid();
      return selector;
    } finally {
      Arrays.fill(encoded, (byte) 0);
    }
  }

  public static ReadOriginalNewFinalAdmissionResponse toResponse(
      Selector selector, FinalResult result) {
    Objects.requireNonNull(selector, "selector");
    Objects.requireNonNull(result, "result");
    if (!selector.equals(result.actualAnchors())) throw invalid();
    byte[] expected = selectorJson(selector);
    byte[] actual = resultJson(result);
    try {
      ReadOriginalNewFinalAdmissionResponse response =
          ReadOriginalNewFinalAdmissionResponse.newBuilder()
              .addSchemaVersion(SCHEMA_VERSION)
              .addExpectedOriginalAdmissionJson(ByteString.copyFrom(expected))
              .addActualFinalAdmissionJson(ByteString.copyFrom(actual))
              .build();
      if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES) throw invalid();
      return response;
    } finally {
      Arrays.fill(expected, (byte) 0);
      Arrays.fill(actual, (byte) 0);
    }
  }

  /** Parses a closed response and exact-matches the persisted authentication anchors. */
  public static FinalResult fromResponse(
      Selector expected, ReadOriginalNewFinalAdmissionResponse response) {
    Objects.requireNonNull(expected, "expected");
    Objects.requireNonNull(response, "response");
    if (response.getSerializedSize() > MAX_RESPONSE_WIRE_BYTES
        || !response.getUnknownFields().asMap().isEmpty()
        || response.getSchemaVersionCount() != 1
        || response.getSchemaVersion(0) != SCHEMA_VERSION
        || response.getExpectedOriginalAdmissionJsonCount() != 1
        || response.getActualFinalAdmissionJsonCount() != 1) {
      throw invalid();
    }
    byte[] expectedPayload = response.getExpectedOriginalAdmissionJson(0).toByteArray();
    byte[] actualPayload = response.getActualFinalAdmissionJson(0).toByteArray();
    byte[] canonicalExpected = selectorJson(expected);
    try {
      if (!Arrays.equals(expectedPayload, canonicalExpected)) throw invalid();
      Map<String, String> fields = parseTextObject(actualPayload, RESULT_FIELDS);
      if (!Integer.toString(SESSION_SCHEMA_VERSION).equals(fields.get("schemaVersion"))
          || !"COMPLETE".equals(fields.get("admissionState"))) throw invalid();
      Selector actual = selector(fields);
      if (!expected.equals(actual)) throw invalid();
      return new FinalResult(
          actual,
          positive(fields.get("admissionAtMillis"), "admissionAtMillis"),
          positive(fields.get("continuityExpiresAtMillis"), "continuityExpiresAtMillis"),
          positive(fields.get("canonicalSessionDurationMillis"), "canonicalSessionDurationMillis"),
          digest(fields.get("canonicalSessionSha256")));
    } finally {
      Arrays.fill(expectedPayload, (byte) 0);
      Arrays.fill(actualPayload, (byte) 0);
      Arrays.fill(canonicalExpected, (byte) 0);
    }
  }

  /** SHA-256 of the exact canonical UTF-8 JSON bytes used for an independently sourced payload. */
  public static String canonicalJsonSha256(Object value) {
    Objects.requireNonNull(value, "canonical JSON value required");
    byte[] encoded = null;
    try {
      encoded = canonicalJson(value);
      return sha256(encoded);
    } finally {
      if (encoded != null) Arrays.fill(encoded, (byte) 0);
    }
  }

  private static byte[] selectorJson(Selector selector) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", Integer.toString(SCHEMA_VERSION));
    fields.put("requestId", selector.requestId().toString());
    fields.put("accountId", selector.accountId().toString());
    fields.put("tenantId", selector.tenantId().toString());
    fields.put("sessionId", selector.sessionId().toString());
    fields.put("bindingDecisionId", selector.bindingDecisionId().toString());
    fields.put("originalLeaseId", selector.originalLeaseId().toString());
    fields.put("originalLeaseFence", Long.toString(selector.originalLeaseFence()));
    fields.put("originalLeaseEvidenceSha256", selector.originalLeaseEvidenceSha256());
    fields.put("tokenSha256", selector.tokenSha256());
    fields.put("tokenGeneration", Long.toString(selector.tokenGeneration()));
    fields.put("issuanceFence", Long.toString(selector.issuanceFence()));
    fields.put("bindingScopeSha256", selector.bindingScopeSha256());
    fields.put("membershipBaselineSha256", selector.membershipBaselineSha256());
    fields.put("checkpointsSha256", selector.checkpointsSha256());
    fields.put("rebindHandleSha256", selector.rebindHandleSha256());
    return canonicalJson(fields);
  }

  private static byte[] resultJson(FinalResult result) {
    Selector selector = result.actualAnchors();
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", Integer.toString(SESSION_SCHEMA_VERSION));
    fields.put("admissionState", "COMPLETE");
    fields.put("requestId", selector.requestId().toString());
    fields.put("accountId", selector.accountId().toString());
    fields.put("tenantId", selector.tenantId().toString());
    fields.put("sessionId", selector.sessionId().toString());
    fields.put("bindingDecisionId", selector.bindingDecisionId().toString());
    fields.put("originalLeaseId", selector.originalLeaseId().toString());
    fields.put("originalLeaseFence", Long.toString(selector.originalLeaseFence()));
    fields.put("originalLeaseEvidenceSha256", selector.originalLeaseEvidenceSha256());
    fields.put("tokenSha256", selector.tokenSha256());
    fields.put("tokenGeneration", Long.toString(selector.tokenGeneration()));
    fields.put("issuanceFence", Long.toString(selector.issuanceFence()));
    fields.put("bindingScopeSha256", selector.bindingScopeSha256());
    fields.put("membershipBaselineSha256", selector.membershipBaselineSha256());
    fields.put("checkpointsSha256", selector.checkpointsSha256());
    fields.put("rebindHandleSha256", selector.rebindHandleSha256());
    fields.put("admissionAtMillis", Long.toString(result.admissionAtMillis()));
    fields.put("continuityExpiresAtMillis", Long.toString(result.continuityExpiresAtMillis()));
    fields.put(
        "canonicalSessionDurationMillis", Long.toString(result.canonicalSessionDurationMillis()));
    fields.put("canonicalSessionSha256", result.canonicalSessionSha256());
    return canonicalJson(fields);
  }

  private static Selector selector(Map<String, String> fields) {
    return new Selector(
        uuid(fields.get("requestId")),
        uuid(fields.get("accountId")),
        uuid(fields.get("tenantId")),
        uuid(fields.get("sessionId")),
        uuid(fields.get("bindingDecisionId")),
        uuid(fields.get("originalLeaseId")),
        positive(fields.get("originalLeaseFence"), "originalLeaseFence"),
        digest(fields.get("originalLeaseEvidenceSha256")),
        digest(fields.get("tokenSha256")),
        positive(fields.get("tokenGeneration"), "tokenGeneration"),
        positive(fields.get("issuanceFence"), "issuanceFence"),
        digest(fields.get("bindingScopeSha256")),
        digest(fields.get("membershipBaselineSha256")),
        digest(fields.get("checkpointsSha256")),
        digest(fields.get("rebindHandleSha256")));
  }

  private static Map<String, String> parseTextObject(byte[] encoded, Set<String> expectedFields) {
    if (encoded == null || encoded.length == 0 || encoded.length > MAX_JSON_BYTES) throw invalid();
    try {
      String json = strictUtf8(encoded);
      Map<?, ?> parsed = JSON.readValue(json, Map.class);
      if (parsed == null || !parsed.keySet().equals(expectedFields)) throw invalid();
      Map<String, String> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : parsed.entrySet()) {
        if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof String value))
          throw invalid();
        result.put(key, value);
      }
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(json);
      try {
        if (!Arrays.equals(canonical, encoded)) throw invalid();
      } finally {
        Arrays.fill(canonical, (byte) 0);
      }
      return result;
    } catch (IllegalArgumentException malformed) {
      throw malformed;
    } catch (IOException | RuntimeException malformed) {
      throw invalid();
    }
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException malformed) {
      throw invalid();
    }
  }

  private static String strictUtf8(byte[] encoded) {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(encoded))
          .toString();
    } catch (CharacterCodingException malformed) {
      throw invalid();
    }
  }

  private static UUID uuid(String encoded) {
    if (encoded == null || !UUID_TEXT.matcher(encoded).matches()) throw invalid();
    try {
      UUID value = UUID.fromString(encoded);
      requireUuid(value);
      if (!value.toString().equals(encoded)) throw invalid();
      return value;
    } catch (IllegalArgumentException malformed) {
      throw invalid();
    }
  }

  private static void requireUuid(UUID value) {
    Objects.requireNonNull(value, "UUID required");
    if (value.version() != 4 || value.variant() != 2) throw invalid();
  }

  private static long positive(String encoded, String name) {
    if (encoded == null || !POSITIVE_DECIMAL.matcher(encoded).matches()) throw invalid();
    try {
      return Long.parseLong(encoded);
    } catch (NumberFormatException malformed) {
      throw invalid();
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0L) throw invalid();
  }

  private static String digest(String value) {
    requireDigest(value);
    return value;
  }

  private static void requireDigest(String value) {
    if (value == null || !SHA256.matcher(value).matches()) throw invalid();
  }

  private static String sha256(byte[] encoded) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Original NEW final-admission readback is malformed");
  }
}
