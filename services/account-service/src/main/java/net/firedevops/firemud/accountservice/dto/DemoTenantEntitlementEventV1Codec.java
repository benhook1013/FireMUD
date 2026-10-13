package net.firedevops.firemud.accountservice.dto;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

/** Closed canonical Account event encoding for explicit non-paid demo entitlements. */
public final class DemoTenantEntitlementEventV1Codec {
  public static final String SCHEMA_VERSION = "account-tenant-entitlement-event/v1";
  public static final String EVENT_TYPE = "TENANT_ENTITLEMENT_CHANGED";

  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Set<String> EVENT_FIELDS =
      Set.of(
          "schemaVersion",
          "eventType",
          "eventId",
          "requestId",
          "requestDigest",
          "outboxStreamKey",
          "tenantId",
          "tenantBillingSequence",
          "tenantAuthorityGeneration",
          "tenantAuthoritySourceVersion",
          "entitlementVersion",
          "entitlementKind",
          "status",
          "subscriptionStatus",
          "paid",
          "gameplayAvailable",
          "allowPublicJoin",
          "allowNewGameplayBindings",
          "allowNewInstanceStarts",
          "quotas",
          "sourceEvidence",
          "eventDigest");
  private static final Set<String> QUOTA_FIELDS =
      Set.of("maxActiveSessions", "maxConcurrentGameInstances", "maxStorageBytes");
  private static final java.util.regex.Pattern DIGEST =
      java.util.regex.Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final Pattern NONNEGATIVE_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]{0,18})");
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

  private DemoTenantEntitlementEventV1Codec() {}

  public static Event seal(
      DemoTenantEntitlementRequest request,
      FreshTenantCreationEvidence source,
      long entitlementVersion,
      long tenantAuthorityGeneration,
      long tenantAuthoritySourceVersion,
      long tenantBillingSequence) {
    if (request == null || source == null) {
      throw new IllegalArgumentException(
          "Demo entitlement request and source evidence are required");
    }
    if (!request.canonicalTenantId().equals(source.canonicalTenantId())
        || !request.tenantCreationRequestId().equals(source.creationRequestId())
        || !request.tenantCreationRequestDigest().equals(source.requestDigest())) {
      throw new IllegalArgumentException(
          "Demo entitlement event source differs from exact request");
    }
    UUID eventId = eventId(request.requestId());
    String streamKey =
        AccountTenantEntitlementOutboxRepository.streamKey(request.canonicalTenantId());
    Map<String, Object> preimage =
        eventFields(
            request,
            source,
            entitlementVersion,
            tenantAuthorityGeneration,
            tenantAuthoritySourceVersion,
            tenantBillingSequence,
            eventId,
            streamKey);
    String digest = digest(canonical(preimage));
    Map<String, Object> wire = new LinkedHashMap<>(preimage);
    wire.put("eventDigest", digest);
    byte[] payload = canonical(wire);
    return new Event(
        request.requestId(),
        request.requestDigest(),
        request.canonicalTenantId(),
        source,
        entitlementVersion,
        tenantAuthorityGeneration,
        tenantAuthoritySourceVersion,
        tenantBillingSequence,
        request.gameplayAvailable(),
        request.allowPublicJoin(),
        request.allowNewGameplayBindings(),
        request.allowNewInstanceStarts(),
        request.quotas(),
        eventId,
        streamKey,
        digest,
        payload);
  }

  /** Parses and verifies the exact canonical event payload stored in Account's outbox. */
  public static Event verify(byte[] payload) {
    if (payload == null || payload.length == 0 || payload.length > 32 * 1024) {
      throw new IllegalArgumentException("Demo entitlement event payload is absent or oversized");
    }
    final Map<String, Object> wire;
    try {
      wire = JSON.readValue(payload, new TypeReference<>() {});
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("Demo entitlement event payload is malformed", exception);
    }
    if (!wire.keySet().equals(EVENT_FIELDS)) {
      throw new IllegalArgumentException("Demo entitlement event fields are incomplete or unknown");
    }
    String claimedDigest = text(wire.get("eventDigest"), "eventDigest");
    Map<String, Object> preimage = new LinkedHashMap<>(wire);
    preimage.remove("eventDigest");
    byte[] canonicalPreimage = canonical(preimage);
    if (!claimedDigest.equals(digest(canonicalPreimage))) {
      throw new IllegalArgumentException(
          "Demo entitlement event digest does not match its payload");
    }
    if (!java.util.Arrays.equals(payload, canonical(wire))) {
      throw new IllegalArgumentException("Demo entitlement event payload is not canonical JSON");
    }

    UUID tenantId = uuid(wire.get("tenantId"), "tenantId");
    UUID requestId = uuid(wire.get("requestId"), "requestId");
    UUID actualEventId = uuid(wire.get("eventId"), "eventId");
    String streamKey = text(wire.get("outboxStreamKey"), "outboxStreamKey");
    if (!SCHEMA_VERSION.equals(wire.get("schemaVersion"))
        || !EVENT_TYPE.equals(wire.get("eventType"))
        || !"NON_PAID_DEMO".equals(wire.get("entitlementKind"))
        || !"ACTIVE".equals(wire.get("status"))
        || wire.get("subscriptionStatus") != null
        || !Boolean.FALSE.equals(wire.get("paid"))
        || !DIGEST.matcher(text(wire.get("requestDigest"), "requestDigest")).matches()
        || !eventId(requestId).equals(actualEventId)
        || !AccountTenantEntitlementOutboxRepository.streamKey(tenantId).equals(streamKey)) {
      throw new IllegalArgumentException(
          "Demo entitlement event identity or non-paid state is invalid");
    }

    Map<String, Object> sourceMap = object(wire.get("sourceEvidence"), "sourceEvidence");
    if (!sourceMap.keySet().equals(SOURCE_FIELDS)) {
      throw new IllegalArgumentException("Demo entitlement source evidence fields are incomplete");
    }
    FreshTenantCreationEvidence source =
        new FreshTenantCreationEvidence(
            integer(sourceMap.get("schemaVersion"), "source schemaVersion"),
            text(sourceMap.get("targetNamespace"), "source targetNamespace"),
            uuid(sourceMap.get("creationRequestId"), "source creationRequestId"),
            uuid(sourceMap.get("operationId"), "source operationId"),
            text(sourceMap.get("requestDigest"), "source requestDigest"),
            uuid(sourceMap.get("canonicalTenantId"), "source canonicalTenantId"),
            positiveDecimal(sourceMap.get("sourceGameRowId"), "source sourceGameRowId"),
            text(sourceMap.get("sourceGameTenantKey"), "source sourceGameTenantKey"),
            text(sourceMap.get("provenanceKind"), "source provenanceKind"),
            text(sourceMap.get("evidenceDigest"), "source evidenceDigest"));
    if (!tenantId.equals(source.canonicalTenantId())) {
      throw new IllegalArgumentException("Demo entitlement event source targets another tenant");
    }

    Map<String, Object> quotaMap = object(wire.get("quotas"), "quotas");
    if (!quotaMap.keySet().equals(QUOTA_FIELDS)) {
      throw new IllegalArgumentException("Demo entitlement event quota fields are incomplete");
    }
    DemoTenantEntitlementRequest.Quotas quotas =
        new DemoTenantEntitlementRequest.Quotas(
            nonnegativeDecimal(quotaMap.get("maxActiveSessions"), "maxActiveSessions"),
            nonnegativeDecimal(
                quotaMap.get("maxConcurrentGameInstances"), "maxConcurrentGameInstances"),
            nonnegativeDecimal(quotaMap.get("maxStorageBytes"), "maxStorageBytes"));

    return new Event(
        requestId,
        text(wire.get("requestDigest"), "requestDigest"),
        tenantId,
        source,
        positiveDecimal(wire.get("entitlementVersion"), "entitlementVersion"),
        positiveDecimal(wire.get("tenantAuthorityGeneration"), "tenantAuthorityGeneration"),
        positiveDecimal(wire.get("tenantAuthoritySourceVersion"), "tenantAuthoritySourceVersion"),
        positiveDecimal(wire.get("tenantBillingSequence"), "tenantBillingSequence"),
        bool(wire.get("gameplayAvailable"), "gameplayAvailable"),
        bool(wire.get("allowPublicJoin"), "allowPublicJoin"),
        bool(wire.get("allowNewGameplayBindings"), "allowNewGameplayBindings"),
        bool(wire.get("allowNewInstanceStarts"), "allowNewInstanceStarts"),
        quotas,
        actualEventId,
        streamKey,
        claimedDigest,
        payload);
  }

  public static UUID eventId(UUID requestId) {
    if (requestId == null || NIL_UUID.equals(requestId)) {
      throw new IllegalArgumentException("Demo entitlement request ID is required");
    }
    return UUID.nameUUIDFromBytes(
        (SCHEMA_VERSION + ":" + requestId).getBytes(StandardCharsets.UTF_8));
  }

  private static Map<String, Object> eventFields(
      DemoTenantEntitlementRequest request,
      FreshTenantCreationEvidence source,
      long entitlementVersion,
      long tenantAuthorityGeneration,
      long tenantAuthoritySourceVersion,
      long tenantBillingSequence,
      UUID eventId,
      String streamKey) {
    if (entitlementVersion <= 0
        || tenantAuthorityGeneration <= 0
        || tenantAuthoritySourceVersion <= 0
        || tenantBillingSequence <= 0) {
      throw new IllegalArgumentException("Demo entitlement event versions must be positive");
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", SCHEMA_VERSION);
    fields.put("eventType", EVENT_TYPE);
    fields.put("eventId", eventId.toString());
    fields.put("requestId", request.requestId().toString());
    fields.put("requestDigest", request.requestDigest());
    fields.put("outboxStreamKey", streamKey);
    fields.put("tenantId", request.canonicalTenantId().toString());
    fields.put("tenantBillingSequence", Long.toString(tenantBillingSequence));
    fields.put("tenantAuthorityGeneration", Long.toString(tenantAuthorityGeneration));
    fields.put("tenantAuthoritySourceVersion", Long.toString(tenantAuthoritySourceVersion));
    fields.put("entitlementVersion", Long.toString(entitlementVersion));
    fields.put("entitlementKind", "NON_PAID_DEMO");
    fields.put("status", "ACTIVE");
    fields.put("subscriptionStatus", null);
    fields.put("paid", false);
    fields.put("gameplayAvailable", request.gameplayAvailable());
    fields.put("allowPublicJoin", request.allowPublicJoin());
    fields.put("allowNewGameplayBindings", request.allowNewGameplayBindings());
    fields.put("allowNewInstanceStarts", request.allowNewInstanceStarts());
    fields.put("quotas", quotaFields(request.quotas()));
    fields.put("sourceEvidence", sourceFields(source));
    return fields;
  }

  private static Map<String, Object> sourceFields(FreshTenantCreationEvidence source) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schemaVersion", source.schemaVersion());
    fields.put("targetNamespace", source.targetNamespace());
    fields.put("creationRequestId", source.creationRequestId().toString());
    fields.put("operationId", source.operationId().toString());
    fields.put("requestDigest", source.requestDigest());
    fields.put("canonicalTenantId", source.canonicalTenantId().toString());
    fields.put("sourceGameRowId", Long.toString(source.sourceGameRowId()));
    fields.put("sourceGameTenantKey", source.sourceGameTenantKey());
    fields.put("provenanceKind", source.provenanceKind());
    fields.put("evidenceDigest", source.evidenceDigest());
    return fields;
  }

  private static byte[] canonical(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException(
          "Demo entitlement event could not be canonicalized", exception);
    }
  }

  private static String digest(byte[] value) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> raw)) {
      throw new IllegalArgumentException("Demo entitlement event " + field + " must be an object");
    }
    for (Object key : raw.keySet()) {
      if (!(key instanceof String)) {
        throw new IllegalArgumentException("Demo entitlement event " + field + " has invalid keys");
      }
    }
    return (Map<String, Object>) raw;
  }

  private static String text(Object value, String field) {
    if (!(value instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException("Demo entitlement event " + field + " is invalid");
    }
    return text;
  }

  private static UUID uuid(Object value, String field) {
    String text = text(value, field);
    UUID uuid;
    try {
      uuid = UUID.fromString(text);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "Demo entitlement event " + field + " is not a UUID", exception);
    }
    if (NIL_UUID.equals(uuid) || !uuid.toString().equals(text)) {
      throw new IllegalArgumentException("Demo entitlement event " + field + " is not canonical");
    }
    return uuid;
  }

  private static int integer(Object value, String field) {
    if (!(value instanceof Number number)) {
      throw new IllegalArgumentException(
          "Demo entitlement event " + field + " must be a JSON integer");
    }
    String encoded = number.toString();
    if (!POSITIVE_DECIMAL.matcher(encoded).matches()) {
      throw new IllegalArgumentException(
          "Demo entitlement event " + field + " must be a positive JSON integer");
    }
    try {
      return Integer.parseInt(encoded);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Demo entitlement event " + field + " is too large", exception);
    }
  }

  private static long positiveDecimal(Object value, String field) {
    String encoded = decimalText(value, field, POSITIVE_DECIMAL, "positive");
    try {
      return Long.parseLong(encoded);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Demo entitlement event " + field + " is outside the supported owner range", exception);
    }
  }

  private static long nonnegativeDecimal(Object value, String field) {
    String encoded = decimalText(value, field, NONNEGATIVE_DECIMAL, "non-negative");
    try {
      return Long.parseLong(encoded);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          "Demo entitlement event " + field + " is outside the supported owner range", exception);
    }
  }

  private static String decimalText(
      Object value, String field, Pattern grammar, String description) {
    if (!(value instanceof String encoded) || !grammar.matcher(encoded).matches()) {
      throw new IllegalArgumentException(
          "Demo entitlement event "
              + field
              + " must be a canonical "
              + description
              + " decimal string");
    }
    return encoded;
  }

  private static Map<String, Object> quotaFields(DemoTenantEntitlementRequest.Quotas quotas) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("maxActiveSessions", Long.toString(quotas.maxActiveSessions()));
    fields.put("maxConcurrentGameInstances", Long.toString(quotas.maxConcurrentGameInstances()));
    fields.put("maxStorageBytes", Long.toString(quotas.maxStorageBytes()));
    return fields;
  }

  private static boolean bool(Object value, String field) {
    if (!(value instanceof Boolean result)) {
      throw new IllegalArgumentException("Demo entitlement event " + field + " is not boolean");
    }
    return result;
  }

  /** Exact closed event evidence stored in the Account entitlement outbox. */
  public record Event(
      UUID requestId,
      String requestDigest,
      UUID canonicalTenantId,
      FreshTenantCreationEvidence sourceEvidence,
      long entitlementVersion,
      long tenantAuthorityGeneration,
      long tenantAuthoritySourceVersion,
      long tenantBillingSequence,
      boolean gameplayAvailable,
      boolean allowPublicJoin,
      boolean allowNewGameplayBindings,
      boolean allowNewInstanceStarts,
      DemoTenantEntitlementRequest.Quotas quotas,
      UUID eventId,
      String outboxStreamKey,
      String eventDigest,
      byte[] payload) {
    public Event {
      payload = payload.clone();
    }

    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }
}
