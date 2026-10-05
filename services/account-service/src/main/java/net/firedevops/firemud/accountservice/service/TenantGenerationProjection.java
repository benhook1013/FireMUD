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
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec.TenantGenerationAuthorityEvent;

/** Closed current-snapshot projection of one durable Account tenant-generation source. */
public record TenantGenerationProjection(
    String tenantId,
    String tenantAuthorityGeneration,
    String sourceVersion,
    String outboxStreamKey,
    String outboxSequence,
    Optional<String> sourceEvent) {
  public static final String SCHEMA_VERSION = "account-auth-tenant-generation-projection/v1";
  public static final String KEY_PREFIX = "session:auth:generation:tenant:";
  private static final String STREAM_PREFIX = "account:auth-authority:v1:tenant/";
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]*");
  private static final Pattern NON_NEGATIVE_DECIMAL = Pattern.compile("0|[1-9][0-9]*");
  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final Set<String> REQUIRED_FIELDS =
      Set.of(
          "schemaVersion",
          "tenantId",
          "tenantAuthorityGeneration",
          "sourceVersion",
          "outboxStreamKey",
          "outboxSequence");
  private static final Set<String> ALL_FIELDS =
      Set.of(
          "schemaVersion",
          "tenantId",
          "tenantAuthorityGeneration",
          "sourceVersion",
          "outboxStreamKey",
          "outboxSequence",
          "sourceEvent");

  public TenantGenerationProjection {
    requireCanonicalTenantId(tenantId);
    requirePositiveDecimal(tenantAuthorityGeneration, "tenantAuthorityGeneration");
    requirePositiveDecimal(sourceVersion, "sourceVersion");
    requireNonNegativeDecimal(outboxSequence, "outboxSequence");
    if (!streamKeyForTenant(tenantId).equals(outboxStreamKey)) {
      throw new IllegalArgumentException(
          "Tenant generation projection stream key is not canonical");
    }
    sourceEvent = Objects.requireNonNull(sourceEvent, "sourceEvent optional is required");

    BigInteger generation = new BigInteger(tenantAuthorityGeneration);
    BigInteger version = new BigInteger(sourceVersion);
    BigInteger sequence = new BigInteger(outboxSequence);
    if (sequence.signum() == 0) {
      if (!BigInteger.ONE.equals(generation)
          || !BigInteger.ONE.equals(version)
          || sourceEvent.isPresent()) {
        throw new IllegalArgumentException(
            "Tenant sequence zero requires the original 1/1 source baseline and no event");
      }
    } else {
      if (!generation.equals(version)
          || !generation.equals(sequence.add(BigInteger.ONE))
          || sourceEvent.isEmpty()) {
        throw new IllegalArgumentException(
            "Positive tenant projection requires generation=sourceVersion=sequence+1 "
                + "and its exact event");
      }
      validateSourceEvent(
          sourceEvent.orElseThrow(),
          tenantId,
          tenantAuthorityGeneration,
          sourceVersion,
          outboxStreamKey,
          outboxSequence);
    }
  }

  /** Builds projection evidence only from Account's owner-local current durable readback. */
  public static TenantGenerationProjection fromSource(
      AccountTenantAuthorityEventProducer.TenantAuthoritySnapshot source) {
    Objects.requireNonNull(source, "Tenant source snapshot is required");
    String tenantText = source.tenantId().toString();
    String generation = Long.toString(source.tenantAuthorityGeneration());
    String version = Long.toString(source.sourceVersion());
    String sequence = Long.toString(source.outboxSequence());
    Optional<String> event =
        source
            .latestEvent()
            .map(
                evidence -> {
                  TenantGenerationAuthorityEvent verified =
                      TenantGenerationAuthorityEventV1Codec.verify(evidence.canonicalJson());
                  if (!verified.eventDigest().equals(evidence.eventDigest())
                      || !verified.canonicalJson().equals(evidence.canonicalJson())
                      || !java.util.Arrays.equals(
                          verified.canonicalJsonUtf8(), evidence.canonicalJsonUtf8())) {
                    throw new IllegalArgumentException(
                        "Tenant source event differs from its closed codec readback");
                  }
                  return verified.canonicalJson();
                });
    return new TenantGenerationProjection(
        tenantText, generation, version, source.outboxStreamKey(), sequence, event);
  }

  /**
   * Parses the closed wire shape and rejects noncanonical JSON and all alternate representations.
   */
  public static TenantGenerationProjection parse(String json) {
    Objects.requireNonNull(json, "projection JSON is required");
    final JsonNode root;
    try {
      root = JSON.readTree(json);
    } catch (IOException exception) {
      throw new IllegalArgumentException(
          "Tenant generation projection JSON is malformed", exception);
    }
    if (!(root instanceof ObjectNode object)) {
      throw new IllegalArgumentException("Tenant generation projection must be a JSON object");
    }
    Set<String> fields = new HashSet<>();
    object.fieldNames().forEachRemaining(fields::add);
    if (!fields.contains("sourceEvent") && !fields.equals(REQUIRED_FIELDS)) {
      throw new IllegalArgumentException(
          "Tenant generation projection has unknown or missing fields");
    }
    if (fields.contains("sourceEvent") && !fields.equals(ALL_FIELDS)) {
      throw new IllegalArgumentException(
          "Tenant generation projection has unknown or missing fields");
    }
    if (!SCHEMA_VERSION.equals(requireText(object, "schemaVersion"))) {
      throw new IllegalArgumentException(
          "Tenant generation projection schemaVersion is unsupported");
    }
    Optional<String> event =
        fields.contains("sourceEvent")
            ? Optional.of(requireText(object, "sourceEvent"))
            : Optional.empty();
    TenantGenerationProjection projection =
        new TenantGenerationProjection(
            requireText(object, "tenantId"),
            requireText(object, "tenantAuthorityGeneration"),
            requireText(object, "sourceVersion"),
            requireText(object, "outboxStreamKey"),
            requireText(object, "outboxSequence"),
            event);
    if (!projection.toJson().equals(json)) {
      throw new IllegalArgumentException("Tenant generation projection JSON is not canonical");
    }
    return projection;
  }

  public String key() {
    return KEY_PREFIX + tenantId;
  }

  public String toJson() {
    ObjectNode object = JSON.createObjectNode();
    object.put("schemaVersion", SCHEMA_VERSION);
    object.put("tenantId", tenantId);
    object.put("tenantAuthorityGeneration", tenantAuthorityGeneration);
    object.put("sourceVersion", sourceVersion);
    object.put("outboxStreamKey", outboxStreamKey);
    object.put("outboxSequence", outboxSequence);
    sourceEvent.ifPresent(value -> object.put("sourceEvent", value));
    try {
      return JSON.writeValueAsString(object);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Tenant generation projection cannot be serialized", exception);
    }
  }

  public BigInteger generationValue() {
    return new BigInteger(tenantAuthorityGeneration);
  }

  public BigInteger sourceVersionValue() {
    return new BigInteger(sourceVersion);
  }

  public BigInteger outboxSequenceValue() {
    return new BigInteger(outboxSequence);
  }

  static String keyForTenant(UUID tenantId) {
    Objects.requireNonNull(tenantId, "Tenant UUID is required");
    if (new UUID(0L, 0L).equals(tenantId)) {
      throw new IllegalArgumentException("A canonical non-nil tenant UUID is required");
    }
    return KEY_PREFIX + tenantId;
  }

  private static String streamKeyForTenant(String tenantId) {
    return STREAM_PREFIX + tenantId;
  }

  private static void validateSourceEvent(
      String eventJson,
      String tenantId,
      String generation,
      String sourceVersion,
      String streamKey,
      String sequence) {
    TenantGenerationAuthorityEvent event = TenantGenerationAuthorityEventV1Codec.verify(eventJson);
    if (!event.canonicalJson().equals(eventJson)) {
      throw new IllegalArgumentException("Tenant source event JSON is not canonical");
    }
    if (!tenantId.equals(event.tenantId())
        || !("tenant/" + tenantId).equals(event.sourceScope())
        || !generation.equals(event.tenantAuthorityGeneration())
        || !sourceVersion.equals(event.sourceVersion())
        || !streamKey.equals(event.outboxStreamKey())
        || !sequence.equals(event.outboxSequence())) {
      throw new IllegalArgumentException(
          "Tenant source event does not match its current projection checkpoint");
    }
  }

  private static String requireText(ObjectNode object, String field) {
    JsonNode value = object.get(field);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(
          "Tenant generation projection " + field + " must be a string");
    }
    return value.textValue();
  }

  private static void requireCanonicalTenantId(String value) {
    Objects.requireNonNull(value, "tenantId is required");
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || new UUID(0L, 0L).equals(parsed)) {
        throw new IllegalArgumentException(
            "Tenant generation projection tenantId is not canonical");
      }
    } catch (IllegalArgumentException malformed) {
      throw new IllegalArgumentException(
          "Tenant generation projection tenantId must be a lowercase canonical non-nil UUID",
          malformed);
    }
  }

  private static void requirePositiveDecimal(String value, String field) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a positive canonical decimal string");
    }
  }

  private static void requireNonNegativeDecimal(String value, String field) {
    if (value == null || !NON_NEGATIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(
          field + " must be a non-negative canonical decimal string");
    }
  }
}
