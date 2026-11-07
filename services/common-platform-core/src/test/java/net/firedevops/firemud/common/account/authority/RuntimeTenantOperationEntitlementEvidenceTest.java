package net.firedevops.firemud.common.account.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Content proof only; controlled values are not authenticated Account source evidence. */
class RuntimeTenantOperationEntitlementEvidenceTest {
  private static final String TENANT = "44444444-4444-4444-8444-444444444444";
  private static final String STREAM = "account:auth-authority:v1:tenant/" + TENANT;
  private static final String DIGEST = "sha256:" + "a".repeat(64);

  @Test
  void roundTripPreservesTenantOnlyShapeIndependentCountersAndImmutableValue() {
    var value = RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(fields());
    assertThat(RuntimeTenantOperationEntitlementEvidence.parseCanonical(value.canonicalBytes()))
        .isEqualTo(value);
    assertThat(value.fields())
        .containsEntry("tenantAuthorityGeneration", "5")
        .containsEntry("entitlementVersion", "7")
        .containsEntry("tenantBillingSequence", "11");
    assertThat(tuple(value.fields()))
        .containsOnlyKeys("tenantAuthorityGeneration", "tenantBillingCutoff");
    value.fields().put("tenantId", UUID.randomUUID().toString());
    byte[] copy = value.canonicalBytes();
    copy[0] = 0;
    assertThat(value.fields()).containsEntry("tenantId", TENANT);
  }

  @Test
  void playerIssuerAccountClaimsAndUnknownVariantsCannotEnterTenantOperation() {
    for (String field :
        List.of("callerAccountId", "verifiedConnectScopeId", "issuer", "membershipVersion")) {
      var fields = fields();
      fields.put(field, "injected");
      reject(fields);
    }
    for (String field :
        List.of(
            "issuerAuthGeneration",
            "accountAuthorityGeneration",
            "membershipAuthorityGeneration",
            "privateRealmGrantVersions",
            "accountSecurityCutoff")) {
      var fields = fields();
      var tuple = new LinkedHashMap<>(tuple(fields));
      tuple.put(field, "1");
      fields.put("authorityTuple", tuple);
      reject(fields);
    }
    var fields = fields();
    fields.put("variant", "PLAYER_ADMISSION");
    reject(fields);
  }

  @Test
  void cutoffAndExactPositiveCheckpointCannotBeReplacedByFlatBillingSequence() {
    var fields = fields();
    fields.put("tenantBillingSequence", "12");
    reject(fields);
    fields = fields();
    fields.put("tenantAuthorityGeneration", "6");
    reject(fields);
    fields = fields();
    fields.put("outboxCheckpoints", List.of(checkpoint("0")));
    reject(fields);
    fields = fields();
    fields.put("outboxCheckpoints", List.of(checkpoint("3"), checkpoint("3")));
    reject(fields);
    fields = fields();
    fields.put(
        "outboxCheckpoints",
        List.of(
            Map.of(
                "outboxStreamKey",
                TenantAuthorityEventV1Codec.tenantBillingStreamKey(UUID.fromString(TENANT)),
                "outboxSequence",
                "3",
                "sourceEventId",
                UUID.randomUUID().toString(),
                "sourceEventDigest",
                DIGEST)));
    reject(fields);
  }

  @Test
  void statusesHaveExplicitPresenceAndProvisioningCannotGrantAdmission() {
    var fields = fields();
    fields.remove("subscriptionStatus");
    reject(fields);
    fields = fields();
    fields.put("subscriptionStatus", null);
    fields.put("provisioningStatus", "pending");
    reject(fields);
    for (String flag :
        List.of(
            "gameplayAvailable",
            "allowPublicJoin",
            "allowNewGameplayBindings",
            "allowNewInstanceStarts")) {
      fields.put(flag, false);
    }
    assertThat(RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(fields).fields())
        .containsEntry("provisioningStatus", "pending")
        .containsEntry("subscriptionStatus", null);
    fields.put("provisioningStatus", null);
    fields.put("sourceKind", "NON_PAID_DEMO");
    assertThat(RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(fields).fields())
        .containsEntry("subscriptionStatus", null);
  }

  @Test
  void malformedUtf8DuplicatesUnknownSchemaOverflowAndNoncanonicalBytesFailClosed() {
    byte[] canonical =
        RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(fields()).canonicalBytes();
    String text = new String(canonical, StandardCharsets.UTF_8);
    rejectBytes((" " + text).getBytes(StandardCharsets.UTF_8));
    rejectBytes(
        text.replaceFirst("\\{", "{\"schema\":\"account-auth-evidence-bundle/v1\",")
            .getBytes(StandardCharsets.UTF_8));
    rejectBytes(new byte[] {(byte) 0xc3, (byte) 0x28});
    rejectBytes(new byte[RuntimeTenantOperationEntitlementEvidence.MAX_CANONICAL_BYTES + 1]);
    for (String decimal : List.of("01", "-1", "9223372036854775808")) {
      var fields = fields();
      fields.put("entitlementVersion", decimal);
      reject(fields);
    }
    var fields = fields();
    fields.put("schema", "account-auth-evidence-bundle/v2");
    reject(fields);
    fields = fields();
    fields.put("tenantId", "44444444444444448444444444444444");
    reject(fields);
  }

  @Test
  void absentNullOrUnavailableAuthorityCannotProduceUsableEvidence() {
    var absent = fields();
    absent.remove("authorityAvailability");
    reject(absent);
    var missing = fields();
    missing.put("authorityAvailability", null);
    reject(missing);
    var unavailable = fields();
    unavailable.put("authorityAvailability", "UNAVAILABLE");
    reject(unavailable);
  }

  private static Map<String, Object> fields() {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", RuntimeTenantOperationEntitlementEvidence.SCHEMA);
    fields.put("authorityAvailability", "AVAILABLE");
    fields.put("variant", "TENANT_OPERATION");
    fields.put("bundleVersion", "1");
    fields.put("tenantId", TENANT);
    fields.put("requestId", "11111111-1111-4111-8111-111111111111");
    fields.put("callerWorkload", "spiffe://firemud/ns/prod/sa/world-management-service");
    fields.put("sourceKind", "PAID_HOSTING");
    fields.put("subscriptionStatus", "active");
    fields.put("provisioningStatus", null);
    fields.put("gameplayAvailable", true);
    fields.put("allowPublicJoin", true);
    fields.put("allowNewGameplayBindings", true);
    fields.put("allowNewInstanceStarts", true);
    fields.put(
        "quotas",
        Map.of(
            "maxActiveSessions",
            "100",
            "maxConcurrentGameInstances",
            "2",
            "maxStorageBytes",
            "1048576"));
    fields.put("entitlementVersion", "7");
    fields.put("tenantBillingSequence", "11");
    fields.put("tenantAuthorityGeneration", "5");
    fields.put("evaluatedAt", "2026-11-02T12:00:00Z");
    fields.put("snapshotIdentity", DIGEST);
    fields.put("evaluationIdentity", "sha256:" + "b".repeat(64));
    fields.put(
        "authorityTuple",
        Map.of(
            "tenantAuthorityGeneration",
            Map.of(TENANT, "5"),
            "tenantBillingCutoff",
            Map.of(
                TENANT,
                Map.of(
                    "tenantAuthorityGeneration",
                    "5",
                    "tenantBillingSequence",
                    "11",
                    "outboxStreamKey",
                    STREAM,
                    "outboxSequence",
                    "3"))));
    fields.put("outboxCheckpoints", List.of(checkpoint("3")));
    return fields;
  }

  private static Map<String, Object> checkpoint(String sequence) {
    return Map.of(
        "outboxStreamKey",
        STREAM,
        "outboxSequence",
        sequence,
        "sourceEventId",
        "22222222-2222-4222-8222-222222222222",
        "sourceEventDigest",
        DIGEST);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> tuple(Map<String, Object> fields) {
    return (Map<String, Object>) fields.get("authorityTuple");
  }

  private static void reject(Map<String, Object> fields) {
    assertThatThrownBy(() -> RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(fields))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static void rejectBytes(byte[] bytes) {
    assertThatThrownBy(() -> RuntimeTenantOperationEntitlementEvidence.parseCanonical(bytes))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
