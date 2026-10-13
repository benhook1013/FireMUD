package net.firedevops.firemud.accountservice.dto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.repository.AccountTenantEntitlementOutboxRepository;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closed canonical event encoding for Account's independently sequenced tenant authority stream.
 */
public final class TenantAuthorityEventV1Codec {
  public static final String SCHEMA_VERSION = "account-tenant-authority-event/v1";
  public static final String EVENT_TYPE = "TENANT_AUTHORITY_CHANGED";

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final String STREAM_PREFIX = "account:auth-authority:v1:tenant/";
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Set<String> EVENT_FIELDS =
      Set.of(
          "schemaVersion",
          "eventType",
          "eventId",
          "requestId",
          "requestDigest",
          "tenantId",
          "tenantAuthorityGeneration",
          "tenantAuthoritySourceVersion",
          "outboxStreamKey",
          "outboxSequence",
          "tenantBillingReceipt",
          "sourceEvidence",
          "eventDigest");
  private static final Set<String> BILLING_FIELDS =
      Set.of("outboxStreamKey", "tenantBillingSequence", "eventId", "eventDigest");
  private static final Set<String> SOURCE_FIELDS =
      Set.of(
          "schemaVersion",
          "targetNamespace",
          "creationRequestId",
          "operationId",
          "requestDigest",
          "canonicalTenantId",
          "sourceGameRowId",
          "sourceGameTenantKey",
          "provenanceKind",
          "evidenceDigest");
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private TenantAuthorityEventV1Codec() {}

  public static String streamKey(UUID tenantId) {
    requireUuid(tenantId, "tenant UUID");
    return STREAM_PREFIX + tenantId;
  }

  public static Event seal(
      DemoTenantEntitlementRequest request,
      FreshTenantCreationEvidence source,
      long tenantAuthorityGeneration,
      long tenantAuthoritySourceVersion,
      long outboxSequence,
      DemoTenantEntitlementEventV1Codec.Event billingEvent) {
    if (request == null || source == null || billingEvent == null) {
      throw new IllegalArgumentException(
          "Tenant authority request, source, and committed billing event are required");
    }
    if (!request.canonicalTenantId().equals(source.canonicalTenantId())
        || !request.tenantCreationRequestId().equals(source.creationRequestId())
        || !request.tenantCreationRequestDigest().equals(source.requestDigest())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())
        || !request.requestId().equals(billingEvent.requestId())
        || !request.requestDigest().equals(billingEvent.requestDigest())
        || !request.canonicalTenantId().equals(billingEvent.canonicalTenantId())) {
      throw new IllegalArgumentException(
          "Tenant authority event differs from its exact source or billing request");
    }
    requirePositive(tenantAuthorityGeneration, "tenant authority generation");
    requirePositive(tenantAuthoritySourceVersion, "tenant authority source version");
    requirePositive(outboxSequence, "authority outbox sequence");
    UUID eventId = eventId(request.requestId());
    String streamKey = streamKey(request.canonicalTenantId());
    Map<String, Object> preimage =
        fields(
            request.requestId(),
            request.requestDigest(),
            request.canonicalTenantId(),
            tenantAuthorityGeneration,
            tenantAuthoritySourceVersion,
            streamKey,
            outboxSequence,
            eventId,
            billingEvent,
            source);
    String digest = digest(canonical(preimage));
    Map<String, Object> wire = new LinkedHashMap<>(preimage);
    wire.put("eventDigest", digest);
    byte[] payload = canonical(wire);
    return new Event(
        request.requestId(),
        request.requestDigest(),
        request.canonicalTenantId(),
        tenantAuthorityGeneration,
        tenantAuthoritySourceVersion,
        streamKey,
        outboxSequence,
        eventId,
        billingEvent.outboxStreamKey(),
        billingEvent.tenantBillingSequence(),
        billingEvent.eventId(),
        billingEvent.eventDigest(),
        source,
        digest,
        payload);
  }

  /** Parses and verifies the exact canonical Account authority event bytes. */
  public static Event verify(byte[] payload) {
    if (payload == null || payload.length == 0 || payload.length > 32 * 1024) {
      throw new IllegalArgumentException("Tenant authority event payload is absent or oversized");
    }
    final Map<String, Object> wire;
    try {
      wire = JSON.readValue(payload, new TypeReference<>() {});
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Tenant authority event payload is malformed", exception);
    }
    if (!wire.keySet().equals(EVENT_FIELDS)) {
      throw new IllegalArgumentException("Tenant authority event fields are incomplete or unknown");
    }
    String claimedDigest = text(wire.get("eventDigest"), "eventDigest");
    Map<String, Object> preimage = new LinkedHashMap<>(wire);
    preimage.remove("eventDigest");
    if (!claimedDigest.equals(digest(canonical(preimage)))
        || !Arrays.equals(payload, canonical(wire))) {
      throw new IllegalArgumentException("Tenant authority event digest or canonical bytes differ");
    }
    String schemaVersion = text(wire.get("schemaVersion"), "schemaVersion");
    String eventType = text(wire.get("eventType"), "eventType");
    UUID tenantId = uuid(wire.get("tenantId"), "tenantId");
    UUID requestId = uuid(wire.get("requestId"), "requestId");
    UUID eventId = uuid(wire.get("eventId"), "eventId");
    String requestDigest = digestText(wire.get("requestDigest"), "requestDigest");
    String streamKey = text(wire.get("outboxStreamKey"), "outboxStreamKey");
    long authorityGeneration =
        positiveDecimal(wire.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration");
    long sourceVersion =
        positiveDecimal(wire.get("tenantAuthoritySourceVersion"), "tenantAuthoritySourceVersion");
    long sequence = positiveDecimal(wire.get("outboxSequence"), "outboxSequence");
    if (!SCHEMA_VERSION.equals(schemaVersion)
        || !EVENT_TYPE.equals(eventType)
        || !streamKey(tenantId).equals(streamKey)
        || !eventId(requestId).equals(eventId)) {
      throw new IllegalArgumentException("Tenant authority event scope or identity is invalid");
    }

    Map<String, Object> billing = object(wire.get("tenantBillingReceipt"), "tenantBillingReceipt");
    if (!billing.keySet().equals(BILLING_FIELDS)) {
      throw new IllegalArgumentException("Tenant billing receipt fields are incomplete");
    }
    String billingStream = text(billing.get("outboxStreamKey"), "billing outboxStreamKey");
    long billingSequence =
        positiveDecimal(billing.get("tenantBillingSequence"), "tenantBillingSequence");
    UUID billingEventId = uuid(billing.get("eventId"), "billing eventId");
    String billingDigest = digestText(billing.get("eventDigest"), "billing eventDigest");
    if (!AccountTenantEntitlementOutboxRepository.streamKey(tenantId).equals(billingStream)) {
      throw new IllegalArgumentException("Tenant billing receipt has another tenant scope");
    }
    if (!DemoTenantEntitlementEventV1Codec.eventId(requestId).equals(billingEventId)) {
      throw new IllegalArgumentException("Tenant billing receipt has another request identity");
    }

    Map<String, Object> sourceMap = object(wire.get("sourceEvidence"), "sourceEvidence");
    if (!sourceMap.keySet().equals(SOURCE_FIELDS)) {
      throw new IllegalArgumentException("Tenant source evidence fields are incomplete");
    }
    FreshTenantCreationEvidence source =
        new FreshTenantCreationEvidence(
            integer(sourceMap.get("schemaVersion"), "source schemaVersion"),
            text(sourceMap.get("targetNamespace"), "source targetNamespace"),
            uuid(sourceMap.get("creationRequestId"), "source creationRequestId"),
            uuid(sourceMap.get("operationId"), "source operationId"),
            digestText(sourceMap.get("requestDigest"), "source requestDigest"),
            uuid(sourceMap.get("canonicalTenantId"), "source canonicalTenantId"),
            positiveDecimal(sourceMap.get("sourceGameRowId"), "source sourceGameRowId"),
            text(sourceMap.get("sourceGameTenantKey"), "source sourceGameTenantKey"),
            text(sourceMap.get("provenanceKind"), "source provenanceKind"),
            digestText(sourceMap.get("evidenceDigest"), "source evidenceDigest"));
    if (!tenantId.equals(source.canonicalTenantId())
        || !"NEW_GAME_ROW".equals(source.provenanceKind())) {
      throw new IllegalArgumentException("Tenant authority source evidence targets another tenant");
    }
    return new Event(
        requestId,
        requestDigest,
        tenantId,
        authorityGeneration,
        sourceVersion,
        streamKey,
        sequence,
        eventId,
        billingStream,
        billingSequence,
        billingEventId,
        billingDigest,
        source,
        claimedDigest,
        payload);
  }

  public static UUID eventId(UUID requestId) {
    requireUuid(requestId, "request ID");
    return UUID.nameUUIDFromBytes(
        (SCHEMA_VERSION + ":" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> fields(
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      long authorityGeneration,
      long sourceVersion,
      String streamKey,
      long sequence,
      UUID eventId,
      DemoTenantEntitlementEventV1Codec.Event billingEvent,
      FreshTenantCreationEvidence source) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", SCHEMA_VERSION);
    values.put("eventType", EVENT_TYPE);
    values.put("eventId", eventId.toString());
    values.put("requestId", requestId.toString());
    values.put("requestDigest", requestDigest);
    values.put("tenantId", tenantId.toString());
    values.put("tenantAuthorityGeneration", Long.toString(authorityGeneration));
    values.put("tenantAuthoritySourceVersion", Long.toString(sourceVersion));
    values.put("outboxStreamKey", streamKey);
    values.put("outboxSequence", Long.toString(sequence));
    Map<String, Object> billing = new LinkedHashMap<>();
    billing.put("outboxStreamKey", billingEvent.outboxStreamKey());
    billing.put("tenantBillingSequence", Long.toString(billingEvent.tenantBillingSequence()));
    billing.put("eventId", billingEvent.eventId().toString());
    billing.put("eventDigest", billingEvent.eventDigest());
    values.put("tenantBillingReceipt", billing);
    values.put("sourceEvidence", sourceFields(source));
    return values;
  }

  private static Map<String, Object> sourceFields(FreshTenantCreationEvidence source) {
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("schemaVersion", source.schemaVersion());
    values.put("targetNamespace", source.targetNamespace());
    values.put("creationRequestId", source.creationRequestId().toString());
    values.put("operationId", source.operationId().toString());
    values.put("requestDigest", source.requestDigest());
    values.put("canonicalTenantId", source.canonicalTenantId().toString());
    values.put("sourceGameRowId", Long.toString(source.sourceGameRowId()));
    values.put("sourceGameTenantKey", source.sourceGameTenantKey());
    values.put("provenanceKind", source.provenanceKind());
    values.put("evidenceDigest", source.evidenceDigest());
    return values;
  }

  private static byte[] canonical(Map<String, Object> value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Tenant authority event could not be canonicalized", exception);
    }
  }

  private static String digest(byte[] bytes) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("Tenant authority event " + field + " is not an object");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    raw.forEach(
        (key, fieldValue) -> {
          if (!(key instanceof String textKey)) {
            throw new IllegalArgumentException(
                "Tenant authority event " + field + " has a bad key");
          }
          result.put(textKey, fieldValue);
        });
    return result;
  }

  private static String text(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank() || !text.equals(text.strip())) {
      throw new IllegalArgumentException("Tenant authority event " + field + " is invalid");
    }
    return text;
  }

  private static String digestText(Object value, String field) {
    String text = text(value, field);
    if (!DIGEST.matcher(text).matches()) {
      throw new IllegalArgumentException("Tenant authority event " + field + " is invalid");
    }
    return text;
  }

  private static UUID uuid(Object value, String field) {
    try {
      String text = text(value, field);
      UUID uuid = UUID.fromString(text);
      requireUuid(uuid, field);
      if (!uuid.toString().equals(text)) {
        throw new IllegalArgumentException("Tenant authority event " + field + " is not canonical");
      }
      return uuid;
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException(
          "Tenant authority event " + field + " is invalid", exception);
    }
  }

  private static int integer(Object value, String field) {
    if (!(value instanceof Integer) || ((Integer) value) <= 0) {
      throw new IllegalArgumentException("Tenant authority event " + field + " is invalid");
    }
    return (Integer) value;
  }

  private static long positiveDecimal(Object value, String field) {
    String text = text(value, field);
    if (!POSITIVE_DECIMAL.matcher(text).matches()) {
      throw new IllegalArgumentException("Tenant authority event " + field + " is invalid");
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Tenant authority event " + field + " is out of range", exception);
    }
  }

  private static void requirePositive(long value, String field) {
    if (value <= 0L) {
      throw new IllegalArgumentException("Tenant authority event " + field + " must be positive");
    }
  }

  private static void requireUuid(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException("Tenant authority event " + field + " is required");
    }
  }

  /** Exact authority event plus its independent tenant-billing and fresh-source linkage. */
  public record Event(
      UUID requestId,
      String requestDigest,
      UUID tenantId,
      long tenantAuthorityGeneration,
      long tenantAuthoritySourceVersion,
      String outboxStreamKey,
      long outboxSequence,
      UUID eventId,
      String tenantBillingStreamKey,
      long tenantBillingSequence,
      UUID tenantBillingEventId,
      String tenantBillingEventDigest,
      FreshTenantCreationEvidence sourceEvidence,
      String eventDigest,
      byte[] payload) {
    public Event {
      payload = payload == null ? null : payload.clone();
      requireUuid(requestId, "request ID");
      requireUuid(tenantId, "tenant UUID");
      requireUuid(eventId, "event ID");
      requireUuid(tenantBillingEventId, "billing event ID");
      if (requestDigest == null
          || !DIGEST.matcher(requestDigest).matches()
          || eventDigest == null
          || !DIGEST.matcher(eventDigest).matches()
          || tenantBillingEventDigest == null
          || !DIGEST.matcher(tenantBillingEventDigest).matches()
          || !streamKey(tenantId).equals(outboxStreamKey)
          || !AccountTenantEntitlementOutboxRepository.streamKey(tenantId)
              .equals(tenantBillingStreamKey)
          || tenantAuthorityGeneration <= 0L
          || tenantAuthoritySourceVersion <= 0L
          || outboxSequence <= 0L
          || tenantBillingSequence <= 0L
          || sourceEvidence == null
          || !tenantId.equals(sourceEvidence.canonicalTenantId())
          || payload == null
          || payload.length == 0) {
        throw new IllegalArgumentException("Tenant authority event evidence is incomplete");
      }
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }
}
