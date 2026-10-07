package net.firedevops.firemud.accountservice.dto;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.RecoveredCredential;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import tools.jackson.databind.json.JsonMapper;

/** Exact recovered gameplay LOGIN credential and Account metadata; never a gameplay binding. */
public final class InitialGameplayLoginResult {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final RecoveredCredential credential;
  private final UUID requestId;
  private final UUID callerContextId;
  private final String tokenGeneration;
  private final String issuanceFence;
  private final long issuedAtEpochSecond;
  private final long notBeforeEpochSecond;
  private final byte[] authorityTupleCanonicalJson;
  private final byte[] outboxCheckpointsCanonicalJson;
  private final byte[] outboxSourceEventEvidenceCanonicalJson;

  private InitialGameplayLoginResult(
      RecoveredCredential credential,
      UUID requestId,
      UUID callerContextId,
      String tokenGeneration,
      String issuanceFence,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      byte[] authorityTupleCanonicalJson,
      byte[] outboxCheckpointsCanonicalJson,
      byte[] outboxSourceEventEvidenceCanonicalJson) {
    this.credential = credential;
    this.requestId = requestId;
    this.callerContextId = callerContextId;
    this.tokenGeneration = tokenGeneration;
    this.issuanceFence = issuanceFence;
    this.issuedAtEpochSecond = issuedAtEpochSecond;
    this.notBeforeEpochSecond = notBeforeEpochSecond;
    this.authorityTupleCanonicalJson = authorityTupleCanonicalJson.clone();
    this.outboxCheckpointsCanonicalJson = outboxCheckpointsCanonicalJson.clone();
    this.outboxSourceEventEvidenceCanonicalJson = outboxSourceEventEvidenceCanonicalJson.clone();
  }

  /** Wraps only a credential privately created by the verified Account response-recovery owner. */
  public static InitialGameplayLoginResult fromRecoveredCredential(RecoveredCredential credential) {
    Objects.requireNonNull(credential, "Verified recovered credential is required");
    AccountAuthEvidenceBundle bundle =
        Objects.requireNonNull(
            credential.authEvidenceBundle(), "Verified Account auth-evidence bundle is required");
    Map<String, Object> fields = bundle.fields();
    Map<?, ?> operation = object(fields.get("operation"), "operation");
    Map<?, ?> token = object(fields.get("tokenIdentity"), "tokenIdentity");
    Map<?, ?> authorityTuple = object(fields.get("authorityTuple"), "authorityTuple");

    UUID requestId = v4Uuid(operation.get("requestId"), "requestId");
    UUID callerContextId = v4Uuid(operation.get("callerContextId"), "callerContextId");
    UUID accountId = uuid(operation.get("accountId"), "operation.accountId");
    UUID tokenJti = v4Uuid(token.get("jti"), "tokenIdentity.jti");
    String tokenGeneration = positiveDecimal(token.get("tokenGeneration"), "tokenGeneration");
    String issuanceFence = positiveDecimal(fields.get("issuanceFence"), "issuanceFence");
    long issuedAt = positiveInteger(token.get("iat"), "iat");
    long notBefore = positiveInteger(token.get("nbf"), "nbf");
    long expiresAt = positiveInteger(token.get("exp"), "exp");

    if (!accountId.equals(credential.accountId())
        || !tokenJti.equals(credential.tokenJti())
        || !GameSessionAccountDelegationProfile.PROFILE.equals(credential.profile())
        || expiresAt != credential.expiresAtEpochSecond()
        || credential.tokenSha256() == null
        || !SHA256.matcher(credential.tokenSha256()).matches()
        || notBefore > issuedAt
        || issuedAt >= expiresAt) {
      throw invalidAggregate();
    }

    List<?> checkpoints = list(fields.get("outboxCheckpoints"), "outboxCheckpoints");
    List<Map<String, Object>> checkpointPairs = new ArrayList<>(checkpoints.size());
    List<Map<?, ?>> sourceEvents = new ArrayList<>(checkpoints.size());
    for (Object value : checkpoints) {
      Map<?, ?> checkpoint = object(value, "outbox checkpoint");
      String streamKey = text(checkpoint.get("outboxStreamKey"), "outboxStreamKey");
      String sequence = nonnegativeDecimal(checkpoint.get("outboxSequence"), "outboxSequence");
      Map<String, Object> pair = new LinkedHashMap<>();
      pair.put("outboxStreamKey", streamKey);
      pair.put("outboxSequence", sequence);
      checkpointPairs.add(Map.copyOf(pair));
      if (!"0".equals(sequence)) {
        sourceEvents.add(checkpoint);
      }
    }

    return new InitialGameplayLoginResult(
        credential,
        requestId,
        callerContextId,
        tokenGeneration,
        issuanceFence,
        issuedAt,
        notBefore,
        canonicalJson(authorityTuple),
        canonicalJson(checkpointPairs),
        canonicalJson(sourceEvents));
  }

  public byte[] compactJwtBytes() {
    return credential.compactJwtBytes();
  }

  public UUID accountId() {
    return credential.accountId();
  }

  public UUID tokenJti() {
    return credential.tokenJti();
  }

  public String tokenSha256() {
    return credential.tokenSha256();
  }

  public String profile() {
    return credential.profile();
  }

  public long issuedAtEpochSecond() {
    return issuedAtEpochSecond;
  }

  public long notBeforeEpochSecond() {
    return notBeforeEpochSecond;
  }

  public long expiresAtEpochSecond() {
    return credential.expiresAtEpochSecond();
  }

  public String tokenGeneration() {
    return tokenGeneration;
  }

  public String issuanceFence() {
    return issuanceFence;
  }

  /** Immutable original operation identity, projected from the stored Account evidence bundle. */
  public UUID requestId() {
    return requestId;
  }

  /** Immutable original caller context, projected from the stored Account evidence bundle. */
  public UUID callerContextId() {
    return callerContextId;
  }

  public byte[] authorityTupleCanonicalJson() {
    return authorityTupleCanonicalJson.clone();
  }

  /** Ordered stream/sequence pairs include explicit zero baselines and omit event payloads. */
  public byte[] outboxCheckpointsCanonicalJson() {
    return outboxCheckpointsCanonicalJson.clone();
  }

  /** Ordered positive source events only; zero baselines are not event evidence. */
  public byte[] outboxSourceEventEvidenceCanonicalJson() {
    return outboxSourceEventEvidenceCanonicalJson.clone();
  }

  @Override
  public String toString() {
    return "InitialGameplayLoginResult[redacted, non-admitting]";
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException failure) {
      throw invalidAggregate();
    }
  }

  private static Map<?, ?> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> map)) throw invalidAggregate();
    return map;
  }

  private static List<?> list(Object value, String field) {
    if (!(value instanceof List<?> list)) throw invalidAggregate();
    return list;
  }

  private static UUID uuid(Object value, String field) {
    String text = text(value, field);
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text)
          || parsed.equals(new UUID(0L, 0L))
          || parsed.variant() != 2) {
        throw invalidAggregate();
      }
      return parsed;
    } catch (IllegalArgumentException failure) {
      throw invalidAggregate();
    }
  }

  private static UUID v4Uuid(Object value, String field) {
    UUID parsed = uuid(value, field);
    if (parsed.version() != 4) throw invalidAggregate();
    return parsed;
  }

  private static String text(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) throw invalidAggregate();
    return text;
  }

  private static String positiveDecimal(Object value, String field) {
    String text = text(value, field);
    if (!POSITIVE_DECIMAL.matcher(text).matches()) throw invalidAggregate();
    return text;
  }

  private static String nonnegativeDecimal(Object value, String field) {
    String text = text(value, field);
    if (!"0".equals(text) && !POSITIVE_DECIMAL.matcher(text).matches()) {
      throw invalidAggregate();
    }
    return text;
  }

  private static long positiveInteger(Object value, String field) {
    if (!(value instanceof Number number)) throw invalidAggregate();
    try {
      long parsed = new BigDecimal(number.toString()).longValueExact();
      if (parsed <= 0L) throw invalidAggregate();
      return parsed;
    } catch (ArithmeticException | NumberFormatException failure) {
      throw invalidAggregate();
    }
  }

  private static IllegalArgumentException invalidAggregate() {
    return new IllegalArgumentException("Recovered gameplay LOGIN metadata is inconsistent");
  }
}
