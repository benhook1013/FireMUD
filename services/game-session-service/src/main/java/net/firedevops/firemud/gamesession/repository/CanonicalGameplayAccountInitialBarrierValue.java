package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Exact nine-field physical INITIAL OPEN value; it carries no later Account or issuer authority.
 */
final class CanonicalGameplayAccountInitialBarrierValue {
  static final String SCHEMA = "gs_account_admission_barrier/v1";
  static final String OPEN = "OPEN";
  static final Set<String> FIELDS =
      Set.of(
          "schema",
          "accountId",
          "tenantId",
          "playableStateNamespaceId",
          "tenantGameplayTag",
          "ownerOperationId",
          "ownerFenceEpoch",
          "incarnationDigest",
          "state");

  private final Map<String, String> fields;

  private CanonicalGameplayAccountInitialBarrierValue(Map<String, String> fields) {
    if (!FIELDS.equals(fields.keySet())) {
      throw new IllegalArgumentException("INITIAL barrier fields are not exact");
    }
    this.fields = Map.copyOf(fields);
  }

  static CanonicalGameplayAccountInitialBarrierValue open(
      UUID accountId,
      UUID tenantId,
      UUID namespaceId,
      String tenantGameplayTag,
      UUID initializerId,
      BigInteger ownerFenceEpoch,
      String incarnationDigest) {
    requireNonNil(accountId, "account id");
    requireNonNil(tenantId, "tenant id");
    requireNonNil(namespaceId, "namespace id");
    requireNonNil(initializerId, "initializer id");
    if (tenantGameplayTag == null
        || !tenantGameplayTag.matches("g[1-9][0-9]{0,9}-[0-9a-f]{32}")
        || ownerFenceEpoch == null
        || ownerFenceEpoch.signum() < 0
        || ownerFenceEpoch.toString().length() > 256
        || incarnationDigest == null
        || !incarnationDigest.matches("sha256:[0-9a-f]{64}")) {
      throw new IllegalArgumentException("INITIAL barrier target or incarnation is invalid");
    }
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("schema", SCHEMA);
    fields.put("accountId", accountId.toString());
    fields.put("tenantId", tenantId.toString());
    fields.put("playableStateNamespaceId", namespaceId.toString());
    fields.put("tenantGameplayTag", tenantGameplayTag);
    fields.put("ownerOperationId", initializerId.toString());
    fields.put("ownerFenceEpoch", ownerFenceEpoch.toString());
    fields.put("incarnationDigest", incarnationDigest);
    fields.put("state", OPEN);
    return new CanonicalGameplayAccountInitialBarrierValue(fields);
  }

  Map<String, String> fields() {
    return fields;
  }

  private static void requireNonNil(UUID value, String name) {
    Objects.requireNonNull(value, name);
    if (value.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException(name + " must not be nil");
    }
  }
}
