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
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeKind;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerEvent;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;

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
      "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER;
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
      var verified = AccountAuthoritySourceEventV1Codec.verify(sourceEvent.orElseThrow());
      if (!(verified instanceof IssuerEvent event)) {
        throw new IllegalArgumentException("Issuer projection source event is not an issuer event");
      }
      if (!sourceEvent.orElseThrow().equals(event.canonicalJson())
          || !event.eventId().equals(event.requestId())
          || !issuerId.equals(event.issuerId())
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

  /** Adapts only the owner-proved original source; unsupported historical codecs deny. */
  public static IssuerGenerationProjection fromSource(
      net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository
              .CanonicalIssuerSourceSnapshot
          snapshot) {
    Objects.requireNonNull(snapshot);
    CurrentSourceEvidence source = snapshot.source();
    if (source.checkpoint().sequence() == 0L) return fromSource(source);
    if (source.scope().kind() != ScopeKind.ISSUER
        || !"ISSUER_SCOPE_INSERT".equals(source.initializationProvenance())
        || source.initializationTransactionId() <= 0L
        || source.issuanceFence() != null) {
      throw new IllegalArgumentException("Issuer source enrollment is unavailable");
    }
    var stored = snapshot.latestEvent().orElseThrow();
    var verified =
        AccountAuthoritySourceEventV1Codec.verify(
            new String(stored.payload(), java.nio.charset.StandardCharsets.UTF_8));
    if (!(verified instanceof IssuerEvent event)) {
      throw new IllegalArgumentException(
          "Issuer source checkpoint does not contain an issuer event");
    }
    if (!java.util.Arrays.equals(stored.payload(), event.canonicalJsonUtf8())
        || !stored.outboxStreamKey().equals(source.checkpoint().outboxStreamKey())
        || stored.outboxSequence() != source.checkpoint().sequence()
        || !event.outboxStreamKey().equals(source.checkpoint().outboxStreamKey())
        || !event.outboxSequence().equals(Long.toString(source.checkpoint().sequence()))
        || !event.issuerId().equals(source.scope().issuerId())
        || !event.issuerAuthGeneration().equals(Long.toString(source.generation()))
        || !event.sourceVersion().equals(Long.toString(source.sourceVersion()))
        || !source.checkpoint().sourceEventId().equals(Optional.of(stored.eventId()))
        || !source.checkpoint().sourceEventDigest().equals(Optional.of(stored.eventDigest()))
        || !stored.eventId().equals(event.eventId())
        || !stored.eventDigest().equals(event.eventDigest())
        || !stored.requestId().equals(event.requestId())
        || !stored.eventId().equals(stored.requestId())
        || !event.eventId().equals(event.requestId())) {
      throw new IllegalArgumentException(
          "Issuer projection event differs from the owned checkpoint");
    }
    return new IssuerGenerationProjection(
        source.scope().issuerId(), Long.toString(source.generation()),
        Long.toString(source.sourceVersion()), source.checkpoint().outboxStreamKey(),
        Long.toString(source.checkpoint().sequence()), source.checkpoint().sourceEventId(),
        source.checkpoint().sourceEventDigest(), Optional.of(event.canonicalJson()));
  }

  /** Original enrollment only; advanced values require their unchanged canonical event bytes. */
  public static IssuerGenerationProjection fromSource(CurrentSourceEvidence source) {
    Objects.requireNonNull(source);
    if (source.scope().kind() != ScopeKind.ISSUER
        || !"ISSUER_SCOPE_INSERT".equals(source.initializationProvenance())
        || source.initializationTransactionId() <= 0L
        || source.issuanceFence() != null
        || source.checkpoint().sequence() != 0L
        || source.checkpoint().sourceEventId().isPresent()
        || source.checkpoint().sourceEventDigest().isPresent()) {
      throw new IllegalArgumentException(
          "Current issuer source cannot prove the canonical issuer event contract");
    }
    return new IssuerGenerationProjection(
        source.scope().issuerId(),
        Long.toString(source.generation()),
        Long.toString(source.sourceVersion()),
        source.checkpoint().outboxStreamKey(),
        Long.toString(source.checkpoint().sequence()),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
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
    if (!GameSessionAccountDelegationProfile.ISSUER.equals(issuerId)) {
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
