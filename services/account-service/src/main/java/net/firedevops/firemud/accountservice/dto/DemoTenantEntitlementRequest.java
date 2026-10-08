package net.firedevops.firemud.accountservice.dto;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import tools.jackson.databind.json.JsonMapper;

/** Explicit Account-owned non-paid demo entitlement mutation from a protected local fixture. */
public record DemoTenantEntitlementRequest(
    UUID requestId,
    UUID canonicalTenantId,
    UUID tenantCreationRequestId,
    String tenantCreationRequestDigest,
    Long expectedEntitlementVersion,
    Long expectedTenantAuthorityGeneration,
    Long expectedTenantAuthoritySourceVersion,
    boolean gameplayAvailable,
    boolean allowPublicJoin,
    boolean allowNewGameplayBindings,
    boolean allowNewInstanceStarts,
    Quotas quotas) {
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  public DemoTenantEntitlementRequest {
    requireNonNil(requestId, "Demo entitlement request ID");
    requireNonNil(canonicalTenantId, "Canonical tenant UUID");
    requireNonNil(tenantCreationRequestId, "Fresh tenant creation request ID");
    if (tenantCreationRequestDigest == null
        || !DIGEST.matcher(tenantCreationRequestDigest).matches()) {
      throw new IllegalArgumentException("Fresh tenant creation request digest is invalid");
    }
    boolean creating = expectedEntitlementVersion == null;
    if (creating != (expectedTenantAuthorityGeneration == null)
        || creating != (expectedTenantAuthoritySourceVersion == null)) {
      throw new IllegalArgumentException(
          "Demo entitlement create or update fence fields must be supplied together");
    }
    if (!creating
        && (expectedEntitlementVersion <= 0
            || expectedTenantAuthorityGeneration <= 0
            || expectedTenantAuthoritySourceVersion <= 0)) {
      throw new IllegalArgumentException("Expected demo entitlement fences must be positive");
    }
    Objects.requireNonNull(quotas, "Explicit demo entitlement quotas are required");
  }

  /** Stable normalized request digest; the request ID remains the operation identity. */
  public String requestDigest() {
    Map<String, Object> normalized = new LinkedHashMap<>();
    normalized.put("schemaVersion", "account-demo-entitlement-request/v1");
    normalized.put("canonicalTenantId", canonicalTenantId.toString());
    normalized.put("tenantCreationRequestId", tenantCreationRequestId.toString());
    normalized.put("tenantCreationRequestDigest", tenantCreationRequestDigest);
    normalized.put("expectedEntitlementVersion", decimal(expectedEntitlementVersion));
    normalized.put("expectedTenantAuthorityGeneration", decimal(expectedTenantAuthorityGeneration));
    normalized.put(
        "expectedTenantAuthoritySourceVersion", decimal(expectedTenantAuthoritySourceVersion));
    normalized.put("entitlementKind", "NON_PAID_DEMO");
    normalized.put("subscriptionStatus", null);
    normalized.put("paid", false);
    normalized.put("status", "ACTIVE");
    normalized.put("gameplayAvailable", gameplayAvailable);
    normalized.put("allowPublicJoin", allowPublicJoin);
    normalized.put("allowNewGameplayBindings", allowNewGameplayBindings);
    normalized.put("allowNewInstanceStarts", allowNewInstanceStarts);
    normalized.put("quotas", quotaFields(quotas));
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(normalized));
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (IOException | NoSuchAlgorithmException | RuntimeException exception) {
      throw new IllegalStateException(
          "Demo entitlement request digest could not be computed", exception);
    }
  }

  private static void requireNonNil(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
  }

  private static String decimal(Long value) {
    return value == null ? null : Long.toString(value);
  }

  private static Map<String, Object> quotaFields(Quotas quotas) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("maxActiveSessions", Long.toString(quotas.maxActiveSessions()));
    fields.put("maxConcurrentGameInstances", Long.toString(quotas.maxConcurrentGameInstances()));
    fields.put("maxStorageBytes", Long.toString(quotas.maxStorageBytes()));
    return fields;
  }

  /** Quotas are explicit demo values and never copied from a paid plan or subscription. */
  public record Quotas(
      long maxActiveSessions, long maxConcurrentGameInstances, long maxStorageBytes) {
    public Quotas {
      if (maxActiveSessions < 0 || maxConcurrentGameInstances < 0 || maxStorageBytes < 0) {
        throw new IllegalArgumentException("Demo entitlement quotas cannot be negative");
      }
    }

    public Map<String, Object> asMap() {
      Map<String, Object> values = new LinkedHashMap<>();
      values.put("maxActiveSessions", maxActiveSessions);
      values.put("maxConcurrentGameInstances", maxConcurrentGameInstances);
      values.put("maxStorageBytes", maxStorageBytes);
      return values;
    }
  }
}
