package net.firedevops.firemud.gamesession.service;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult.NonSecretEvidence;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed nonsecret live-context codec. Decoding is never authentication or installation authority.
 */
public final class InitialGameplayLoginContextState {
  public static final int MAX_BYTES = 32 * 1024;
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private static final Set<String> BASE_FIELDS =
      Set.of("schemaVersion", "state", "requestId", "callerContextId", "expectedAttemptFence");
  private static final Set<String> EVIDENCE_FIELDS =
      Set.of(
          "accountId",
          "requestId",
          "callerContextId",
          "tokenJti",
          "tokenSha256",
          "tokenProfile",
          "issuedAtEpochSecond",
          "notBeforeEpochSecond",
          "expiresAtEpochSecond",
          "tokenGeneration",
          "issuanceFence",
          "authorityTupleCanonicalJson",
          "outboxCheckpointsCanonicalJson",
          "outboxSourceEventEvidenceCanonicalJson");

  public enum State {
    PENDING,
    INSTALLED,
    CLOSED
  }

  private final State state;
  private final InitialGameplayLoginAttemptCoordinates coordinates;
  private final NonSecretEvidence evidence;

  private InitialGameplayLoginContextState(
      State state, InitialGameplayLoginAttemptCoordinates coordinates, NonSecretEvidence evidence) {
    this.state = Objects.requireNonNull(state);
    this.coordinates = Objects.requireNonNull(coordinates);
    if ((state == State.INSTALLED) != (evidence != null)) throw invalid();
    if (evidence != null) validateEvidence(coordinates, evidence);
    this.evidence = evidence;
  }

  /**
   * Encodes the exact desired initial value; Redis create-only authority remains in its owner
   * script.
   */
  public static InitialGameplayLoginContextState pending(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    return new InitialGameplayLoginContextState(State.PENDING, coordinates, null);
  }

  public static InitialGameplayLoginContextState installed(
      InitialGameplayLoginAttemptCoordinates coordinates,
      CanonicalGameplayLoginResult verifiedResult) {
    return new InitialGameplayLoginContextState(
        State.INSTALLED,
        coordinates,
        Objects.requireNonNull(verifiedResult, "Verified Account result required").evidence());
  }

  public static InitialGameplayLoginContextState closed(
      InitialGameplayLoginAttemptCoordinates coordinates) {
    return new InitialGameplayLoginContextState(State.CLOSED, coordinates, null);
  }

  public State state() {
    return state;
  }

  public InitialGameplayLoginAttemptCoordinates coordinates() {
    return coordinates;
  }

  public NonSecretEvidence evidence() {
    return evidence;
  }

  public byte[] encode() {
    try {
      Map<String, Object> fields = new LinkedHashMap<>();
      fields.put("schemaVersion", 1);
      fields.put("state", state.name());
      fields.put("requestId", coordinates.requestId().toString());
      fields.put("callerContextId", coordinates.callerContextId().toString());
      fields.put("expectedAttemptFence", Long.toString(coordinates.expectedAttemptFence()));
      if (evidence != null) fields.put("accountEvidence", evidenceMap(evidence));
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(fields));
      if (bytes.length > MAX_BYTES) throw invalid();
      return bytes;
    } catch (IOException | RuntimeException failure) {
      throw invalid();
    }
  }

  public static InitialGameplayLoginContextState decode(byte[] bytes) {
    try {
      if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw invalid();
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes))
              .toString();
      Map<?, ?> fields = object(JSON.readValue(text, Object.class));
      State state = State.valueOf(text(fields, "state"));
      Set<String> allowed = new java.util.HashSet<>(BASE_FIELDS);
      if (state == State.INSTALLED) allowed.add("accountEvidence");
      if (!fields.keySet().equals(allowed) || integer(fields.get("schemaVersion")) != 1)
        throw invalid();
      var coordinates =
          new InitialGameplayLoginAttemptCoordinates(
              uuid(text(fields, "requestId"), true),
              uuid(text(fields, "callerContextId"), true),
              positive(text(fields, "expectedAttemptFence")));
      NonSecretEvidence evidence =
          state == State.INSTALLED ? readEvidence(object(fields.get("accountEvidence"))) : null;
      var result = new InitialGameplayLoginContextState(state, coordinates, evidence);
      if (!Arrays.equals(bytes, result.encode())) throw invalid();
      return result;
    } catch (CharacterCodingException | RuntimeException failure) {
      throw invalid();
    }
  }

  private static Map<String, Object> evidenceMap(NonSecretEvidence evidence) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("accountId", evidence.accountId().toString());
    fields.put("requestId", evidence.requestId().toString());
    fields.put("callerContextId", evidence.callerContextId().toString());
    fields.put("tokenJti", evidence.tokenJti().toString());
    fields.put("tokenSha256", evidence.tokenSha256());
    fields.put("tokenProfile", evidence.tokenProfile());
    fields.put("issuedAtEpochSecond", evidence.issuedAtEpochSecond());
    fields.put("notBeforeEpochSecond", evidence.notBeforeEpochSecond());
    fields.put("expiresAtEpochSecond", evidence.expiresAtEpochSecond());
    fields.put("tokenGeneration", evidence.tokenGeneration());
    fields.put("issuanceFence", evidence.issuanceFence());
    fields.put(
        "authorityTupleCanonicalJson", evidence.authorityTupleCanonicalJson().toStringUtf8());
    fields.put(
        "outboxCheckpointsCanonicalJson", evidence.outboxCheckpointsCanonicalJson().toStringUtf8());
    fields.put(
        "outboxSourceEventEvidenceCanonicalJson",
        evidence.outboxSourceEventEvidenceCanonicalJson().toStringUtf8());
    return fields;
  }

  private static NonSecretEvidence readEvidence(Map<?, ?> fields) {
    if (!fields.keySet().equals(EVIDENCE_FIELDS)) throw invalid();
    return new NonSecretEvidence(
        uuid(text(fields, "accountId"), false),
        uuid(text(fields, "requestId"), true),
        uuid(text(fields, "callerContextId"), true),
        uuid(text(fields, "tokenJti"), true),
        text(fields, "tokenSha256"),
        text(fields, "tokenProfile"),
        integer(fields.get("issuedAtEpochSecond")),
        integer(fields.get("notBeforeEpochSecond")),
        integer(fields.get("expiresAtEpochSecond")),
        text(fields, "tokenGeneration"),
        text(fields, "issuanceFence"),
        ByteString.copyFromUtf8(text(fields, "authorityTupleCanonicalJson")),
        ByteString.copyFromUtf8(text(fields, "outboxCheckpointsCanonicalJson")),
        ByteString.copyFromUtf8(text(fields, "outboxSourceEventEvidenceCanonicalJson")));
  }

  private static void validateEvidence(
      InitialGameplayLoginAttemptCoordinates coordinates, NonSecretEvidence evidence) {
    try {
      if (!coordinates.requestId().equals(evidence.requestId())
          || !coordinates.callerContextId().equals(evidence.callerContextId())
          || !GameSessionAccountDelegationProfile.PROFILE.equals(evidence.tokenProfile())
          || evidence.tokenSha256() == null
          || !evidence.tokenSha256().matches("[0-9a-f]{64}")
          || evidence.issuedAtEpochSecond() <= 0
          || evidence.notBeforeEpochSecond() <= 0
          || evidence.expiresAtEpochSecond() <= evidence.issuedAtEpochSecond()
          || evidence.expiresAtEpochSecond() - evidence.issuedAtEpochSecond()
              > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) throw invalid();
      uuid(evidence.accountId().toString(), false);
      uuid(evidence.tokenJti().toString(), true);
      positive(evidence.tokenGeneration());
      positive(evidence.issuanceFence());
      Object tuple =
          projection(
              evidence.authorityTupleCanonicalJson(),
              true,
              GameSessionAccountDelegationProfile.MAX_AUTHORITY_TUPLE_BYTES);
      // Reuse the existing profile's structural validator; this confers no JWT authentication.
      Map<String, Object> shape = new LinkedHashMap<>();
      shape.put("iss", GameSessionAccountDelegationProfile.ISSUER);
      shape.put("aud", GameSessionAccountDelegationProfile.AUDIENCE);
      shape.put("sub", evidence.accountId().toString());
      shape.put("accountId", evidence.accountId().toString());
      shape.put("jti", evidence.tokenJti().toString());
      shape.put("iat", evidence.issuedAtEpochSecond());
      shape.put("nbf", evidence.notBeforeEpochSecond());
      shape.put("exp", evidence.expiresAtEpochSecond());
      shape.put("tokenGeneration", evidence.tokenGeneration());
      shape.put("issuanceFence", evidence.issuanceFence());
      shape.put("membershipVersion", Map.of());
      shape.put("authorityTuple", tuple);
      GameSessionAccountDelegationJwtProfileValidator.validateInitialClaims(shape);
      Object checkpoints = projection(evidence.outboxCheckpointsCanonicalJson(), false, 8 * 1024);
      Object events =
          projection(evidence.outboxSourceEventEvidenceCanonicalJson(), false, 8 * 1024);
      CanonicalGameplayLoginResult.requireCheckpointEvidence(
          evidence.accountId(), object(tuple), checkpoints, events);
    } catch (Exception failure) {
      throw invalid();
    }
  }

  private static Object projection(ByteString bytes, boolean map, int bound) throws Exception {
    if (bytes == null || bytes.isEmpty() || bytes.size() > bound) throw invalid();
    String text =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(bytes.asReadOnlyByteBuffer())
            .toString();
    Object parsed = JSON.readValue(text, Object.class);
    if (map ? !(parsed instanceof Map<?, ?>) : !(parsed instanceof java.util.List<?>))
      throw invalid();
    if (!Arrays.equals(bytes.toByteArray(), Rfc8785CanonicalJson.canonicalizeUtf8(text)))
      throw invalid();
    return parsed;
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw invalid();
    return map;
  }

  private static String text(Map<?, ?> fields, String key) {
    if (!(fields.get(key) instanceof String value)) throw invalid();
    return value;
  }

  private static UUID uuid(String text, boolean v4) {
    UUID value = UUID.fromString(text);
    if (!value.toString().equals(text) || value.variant() != 2 || v4 && value.version() != 4)
      throw invalid();
    return value;
  }

  private static long positive(String text) {
    if (text == null || !text.matches("[1-9][0-9]{0,18}")) throw invalid();
    long value = Long.parseLong(text);
    if (value <= 0) throw invalid();
    return value;
  }

  private static long integer(Object value) {
    if (!(value instanceof Number number)) throw invalid();
    return new BigDecimal(number.toString()).longValueExact();
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Initial LOGIN context is invalid");
  }

  @Override
  public String toString() {
    return "InitialGameplayLoginContextState[nonsecret,non-admitting]";
  }
}
