package net.firedevops.firemud.common.account.authority;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;

/** Strict verifier for the closed Game Session issuer-authority projection schema. */
public final class IssuerAuthorityProjectionV1Codec {
  public static final String SCHEMA_VERSION = "game-session-auth-issuer-projection/v1";

  private static final String EVENT_ID_PREFIX = "account-issuer-authority-event-v1:";
  private static final BigInteger ZERO = BigInteger.ZERO;
  private static final BigInteger ONE = BigInteger.ONE;
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)");
  private static final Set<String> BASE_FIELDS =
      Set.of(
          "schemaVersion",
          "issuerId",
          "lastAppliedIssuerGeneration",
          "lastAppliedSourceOutboxSequence",
          "outboxStreamKey",
          "appliedAt",
          "appliedSourceEvidence");
  private static final Set<String> POSITIVE_FIELDS =
      Set.of("lastAppliedSourceEventId", "lastAppliedSourceEventDigest");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private IssuerAuthorityProjectionV1Codec() {}

  /** Verifies one projection supplied as JSON-compatible map data. */
  public static Projection verify(Map<String, ?> supplied) {
    if (supplied == null) {
      throw invalid("projection", "must be present");
    }
    return verifyFields(supplied);
  }

  /** Verifies one projection JSON object, rejecting duplicate properties and trailing content. */
  public static Projection verify(String exactJson) {
    if (exactJson == null) {
      throw invalid("projection JSON", "must be present");
    }
    final JsonNode tree;
    try {
      tree = JSON.readTree(exactJson);
    } catch (IOException exception) {
      throw new IllegalArgumentException("projection JSON is malformed", exception);
    }
    if (!(tree instanceof ObjectNode object)) {
      throw invalid("projection", "must be a JSON object");
    }
    Map<String, Object> fields =
        JSON.convertValue(object, new TypeReference<Map<String, Object>>() {});
    return verifyFields(fields);
  }

  private static Projection verifyFields(Map<String, ?> supplied) {
    String schema = requiredString(supplied, "schemaVersion");
    String issuer = requiredString(supplied, "issuerId");
    String generationText = requiredString(supplied, "lastAppliedIssuerGeneration");
    String sequenceText = requiredString(supplied, "lastAppliedSourceOutboxSequence");
    String stream = requiredString(supplied, "outboxStreamKey");
    String appliedAt = requiredString(supplied, "appliedAt");
    if (!SCHEMA_VERSION.equals(schema) || issuer.isBlank() || appliedAt.isBlank()) {
      throw invalid("projection", "identity, schema, or appliedAt is malformed");
    }

    BigInteger generation = positiveDecimal(generationText, "projection generation");
    BigInteger sequence = nonnegativeDecimal(sequenceText, "projection sequence");
    String expectedStream =
        IssuerGenerationAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "issuer/" + issuer;
    if (!expectedStream.equals(stream)) {
      throw invalid("projection.outboxStreamKey", "does not match the exact issuer stream");
    }

    Set<String> expectedFields = new HashSet<>(BASE_FIELDS);
    if (!sequence.equals(ZERO)) {
      expectedFields.addAll(POSITIVE_FIELDS);
    }
    if (!supplied.keySet().equals(expectedFields)) {
      throw invalid("projection", "must contain exactly the declared schema fields");
    }

    Object evidenceValue = supplied.get("appliedSourceEvidence");
    if (!(evidenceValue instanceof Map<?, ?> evidence)) {
      throw invalid("projection.appliedSourceEvidence", "must be an object");
    }

    IssuerGenerationAuthorityEvent latest = null;
    BigInteger sourceVersion = ONE;
    if (sequence.equals(ZERO)) {
      if (!generation.equals(ONE) || !evidence.isEmpty()) {
        throw invalid("projection", "zero checkpoint requires the original 1/1 baseline");
      }
    } else {
      if (evidence.size() != 1) {
        throw invalid("projection.appliedSourceEvidence", "must retain its current checkpoint");
      }
      Map.Entry<?, ?> entry = evidence.entrySet().iterator().next();
      if (!(entry.getKey() instanceof String evidenceSequence)
          || !(entry.getValue() instanceof String canonicalEvent)) {
        throw invalid("projection.appliedSourceEvidence", "must contain string key/value data");
      }
      BigInteger parsedEvidenceSequence =
          positiveDecimal(evidenceSequence, "projection evidence sequence");
      if (!sequence.equals(parsedEvidenceSequence) || !sequenceText.equals(evidenceSequence)) {
        throw invalid("projection.appliedSourceEvidence", "must identify the current checkpoint");
      }

      latest = verifiedProjectionEvent(canonicalEvent);
      if (!sameSource(latest, issuer, stream)
          || !sequence.equals(positiveDecimal(latest.outboxSequence(), "projection event sequence"))
          || !generation.equals(
              positiveDecimal(latest.issuerAuthGeneration(), "projection event generation"))
          || !requiredString(supplied, "lastAppliedSourceEventId").equals(latest.eventId())
          || !requiredString(supplied, "lastAppliedSourceEventDigest")
              .equals(latest.eventDigest())) {
        throw invalid("projection", "event does not match its exact checkpoint");
      }
      sourceVersion = positiveDecimal(latest.sourceVersion(), "projection event source version");
    }

    return new Projection(issuer, generation, sourceVersion, sequence, stream, latest, appliedAt);
  }

  private static IssuerGenerationAuthorityEvent verifiedProjectionEvent(String canonicalEvent) {
    final IssuerGenerationAuthorityEvent event;
    try {
      event = IssuerGenerationAuthorityEventV1Codec.verify(canonicalEvent);
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException("projection source event is malformed", malformed);
    }
    if (!event.canonicalJson().equals(canonicalEvent)) {
      throw invalid("projection source event", "must use its exact canonical JSON bytes");
    }
    UUID requestId;
    try {
      requestId = UUID.fromString(event.requestId());
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "projection source event requestId is malformed", malformed);
    }
    if (new UUID(0L, 0L).equals(requestId)
        || !requestId.toString().equals(event.requestId())
        || !(EVENT_ID_PREFIX + event.requestId()).equals(event.eventId())) {
      throw invalid("projection source event", "eventId does not bind its canonical requestId");
    }
    return event;
  }

  private static boolean sameSource(
      IssuerGenerationAuthorityEvent event, String issuerId, String streamKey) {
    return issuerId.equals(event.issuerId())
        && ("issuer/" + issuerId).equals(event.sourceScope())
        && streamKey.equals(event.outboxStreamKey());
  }

  private static String requiredString(Map<String, ?> fields, String name) {
    Object value = fields.get(name);
    if (!(value instanceof String text)) {
      throw invalid("projection." + name, "must be a required string");
    }
    return text;
  }

  private static BigInteger positiveDecimal(String value, String label) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw invalid(label, "must be a positive canonical decimal");
    }
    return new BigInteger(value);
  }

  private static BigInteger nonnegativeDecimal(String value, String label) {
    if (value == null || !NONNEGATIVE_DECIMAL.matcher(value).matches()) {
      throw invalid(label, "must be a canonical nonnegative decimal");
    }
    return new BigInteger(value);
  }

  private static IllegalArgumentException invalid(String field, String message) {
    return new IllegalArgumentException(field + " " + message);
  }

  /** Privately minted immutable projection evidence. */
  public static final class Projection {
    private final String issuerId;
    private final BigInteger generation;
    private final BigInteger sourceVersion;
    private final BigInteger sequence;
    private final String streamKey;
    private final Optional<IssuerGenerationAuthorityEvent> latestEvent;
    private final String appliedAt;

    private Projection(
        String issuerId,
        BigInteger generation,
        BigInteger sourceVersion,
        BigInteger sequence,
        String streamKey,
        IssuerGenerationAuthorityEvent latestEvent,
        String appliedAt) {
      this.issuerId = issuerId;
      this.generation = generation;
      this.sourceVersion = sourceVersion;
      this.sequence = sequence;
      this.streamKey = streamKey;
      this.latestEvent = Optional.ofNullable(latestEvent);
      this.appliedAt = appliedAt;
    }

    public String issuerId() {
      return issuerId;
    }

    public BigInteger generation() {
      return generation;
    }

    public BigInteger sourceVersion() {
      return sourceVersion;
    }

    public BigInteger sequence() {
      return sequence;
    }

    public String streamKey() {
      return streamKey;
    }

    public Optional<IssuerGenerationAuthorityEvent> latestEvent() {
      return latestEvent;
    }

    public String appliedAt() {
      return appliedAt;
    }
  }
}
