package net.firedevops.firemud.accountservice.service;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.security.ControlUiJwtProfileValidator;

/** Closed Account-owned current issuer projection; not recipient or token authorization. */
public record IssuerGenerationProjection(
    String issuerId,
    String issuerAuthGeneration,
    String sourceVersion,
    String outboxStreamKey,
    String lastAppliedSourceOutboxSequence,
    Optional<String> lastAppliedSourceEventId,
    Optional<String> lastAppliedSourceEventDigest,
    Optional<String> sourceEvent) {
  public static final String SCHEMA_VERSION = "account-auth-issuer-generation-projection/v1";
  public static final String KEY_PREFIX = "session:auth:generation:issuer:";
  private static final String STREAM =
      "account:auth-authority:v1:issuer/" + ControlUiJwtProfileValidator.ISSUER;
  private static final Set<String> BASE_FIELDS =
      Set.of(
          "schemaVersion",
          "issuerId",
          "issuerAuthGeneration",
          "sourceVersion",
          "outboxStreamKey",
          "lastAppliedSourceOutboxSequence");
  private static final Set<String> EVENT_FIELDS =
      Set.of(
          "schemaVersion",
          "issuerId",
          "issuerAuthGeneration",
          "sourceVersion",
          "outboxStreamKey",
          "lastAppliedSourceOutboxSequence",
          "lastAppliedSourceEventId",
          "lastAppliedSourceEventDigest",
          "sourceEvent");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public IssuerGenerationProjection {
    keyForIssuer(issuerId);
    positive(issuerAuthGeneration);
    positive(sourceVersion);
    if (lastAppliedSourceOutboxSequence == null
        || !lastAppliedSourceOutboxSequence.matches("0|[1-9][0-9]*")
        || !STREAM.equals(outboxStreamKey)) {
      throw new IllegalArgumentException("Issuer projection stream/checkpoint is not canonical");
    }
    lastAppliedSourceEventId = Objects.requireNonNull(lastAppliedSourceEventId);
    lastAppliedSourceEventDigest = Objects.requireNonNull(lastAppliedSourceEventDigest);
    sourceEvent = Objects.requireNonNull(sourceEvent);
    if (new BigInteger(lastAppliedSourceOutboxSequence).signum() == 0) {
      if (!"1".equals(issuerAuthGeneration)
          || !"1".equals(sourceVersion)
          || lastAppliedSourceEventId.isPresent()
          || lastAppliedSourceEventDigest.isPresent()
          || sourceEvent.isPresent()) {
        throw new IllegalArgumentException(
            "Issuer sequence zero requires original 1/1 and no event");
      }
    } else {
      if (new BigInteger(issuerAuthGeneration).compareTo(BigInteger.ONE) <= 0
          || new BigInteger(sourceVersion).compareTo(BigInteger.ONE) <= 0
          || lastAppliedSourceEventId.isEmpty()
          || lastAppliedSourceEventDigest.isEmpty()
          || sourceEvent.isEmpty()) {
        throw new IllegalArgumentException(
            "Advanced issuer projection requires complete event evidence");
      }
      var event = IssuerGenerationAuthorityEventV1Codec.verify(sourceEvent.orElseThrow());
      if (!sourceEvent.orElseThrow().equals(event.canonicalJson())
          || !issuerId.equals(event.issuerId())
          || !("issuer/" + issuerId).equals(event.sourceScope())
          || !issuerAuthGeneration.equals(event.issuerAuthGeneration())
          || !sourceVersion.equals(event.sourceVersion())
          || !outboxStreamKey.equals(event.outboxStreamKey())
          || !lastAppliedSourceOutboxSequence.equals(event.outboxSequence())
          || !lastAppliedSourceEventId.orElseThrow().equals(event.eventId())
          || !lastAppliedSourceEventDigest.orElseThrow().equals(event.eventDigest())) {
        throw new IllegalArgumentException(
            "Issuer projection differs from its exact canonical event");
      }
    }
  }

  public static IssuerGenerationProjection fromSource(IssuerAuthoritySnapshot source) {
    Objects.requireNonNull(source);
    return new IssuerGenerationProjection(
        source.issuerId(),
        Long.toString(source.issuerAuthGeneration()),
        Long.toString(source.sourceVersion()),
        source.outboxStreamKey(),
        Long.toString(source.outboxSequence()),
        source.latestEvent().map(event -> event.eventId()),
        source.latestEvent().map(event -> event.eventDigest()),
        source.latestEvent().map(event -> event.canonicalJson()));
  }

  public static IssuerGenerationProjection parse(String json) {
    Objects.requireNonNull(json);
    final JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (IOException failure) {
      throw new IllegalArgumentException("Issuer projection JSON is malformed", failure);
    }
    if (!(root instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Issuer projection must be an object");
    }
    Set<String> fields = new HashSet<>();
    object.fieldNames().forEachRemaining(fields::add);
    if (!fields.equals(BASE_FIELDS) && !fields.equals(EVENT_FIELDS)) {
      throw new IllegalArgumentException("Issuer projection has missing or unknown fields");
    }
    if (!SCHEMA_VERSION.equals(text(object, "schemaVersion"))) {
      throw new IllegalArgumentException("Issuer projection schema is unsupported");
    }
    boolean event = fields.equals(EVENT_FIELDS);
    var projection =
        new IssuerGenerationProjection(
            text(object, "issuerId"),
            text(object, "issuerAuthGeneration"),
            text(object, "sourceVersion"),
            text(object, "outboxStreamKey"),
            text(object, "lastAppliedSourceOutboxSequence"),
            event ? Optional.of(text(object, "lastAppliedSourceEventId")) : Optional.empty(),
            event ? Optional.of(text(object, "lastAppliedSourceEventDigest")) : Optional.empty(),
            event ? Optional.of(text(object, "sourceEvent")) : Optional.empty());
    if (!projection.toJson().equals(json)) {
      throw new IllegalArgumentException("Issuer projection JSON is not canonical");
    }
    return projection;
  }

  public String toJson() {
    ObjectNode object = JSON.createObjectNode();
    object.put("schemaVersion", SCHEMA_VERSION);
    object.put("issuerId", issuerId);
    object.put("issuerAuthGeneration", issuerAuthGeneration);
    object.put("sourceVersion", sourceVersion);
    object.put("outboxStreamKey", outboxStreamKey);
    object.put("lastAppliedSourceOutboxSequence", lastAppliedSourceOutboxSequence);
    lastAppliedSourceEventId.ifPresent(value -> object.put("lastAppliedSourceEventId", value));
    lastAppliedSourceEventDigest.ifPresent(
        value -> object.put("lastAppliedSourceEventDigest", value));
    sourceEvent.ifPresent(value -> object.put("sourceEvent", value));
    try {
      return JSON.writeValueAsString(object);
    } catch (IOException failure) {
      throw new IllegalStateException("Issuer projection cannot be serialized", failure);
    }
  }

  public String key() {
    return keyForIssuer(issuerId);
  }

  public BigInteger generationValue() {
    return new BigInteger(issuerAuthGeneration);
  }

  public BigInteger sourceVersionValue() {
    return new BigInteger(sourceVersion);
  }

  public BigInteger outboxSequenceValue() {
    return new BigInteger(lastAppliedSourceOutboxSequence);
  }

  public static String keyForIssuer(String issuerId) {
    if (!ControlUiJwtProfileValidator.ISSUER.equals(issuerId)) {
      throw new IllegalArgumentException(
          "Issuer projection must bind the configured Account issuer");
    }
    return KEY_PREFIX + issuerId;
  }

  private static String text(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException("Issuer projection field must be a string: " + field);
    }
    return value.textValue();
  }

  private static void positive(String value) {
    if (value == null || !value.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          "Issuer projection counter must be a positive canonical decimal");
    }
  }
}
