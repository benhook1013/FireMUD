package net.firedevops.firemud.common.account.authority;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Controlled transport fixture only, never genuine Account source or currentness proof. */
public final class TenantOperationEntitlementTransportFixture {
  private TenantOperationEntitlementTransportFixture() {}

  public static RuntimeTenantOperationEntitlementGrpcCodec.Request request(
      RuntimeTenantOperationEntitlementGrpcCodec.SourceKind source) {
    return new RuntimeTenantOperationEntitlementGrpcCodec.Request(
        "firemud",
        UUID.fromString("44444444-4444-4444-8444-444444444444"),
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        source);
  }

  public static RuntimeTenantOperationEntitlementEvidence evidence(
      RuntimeTenantOperationEntitlementGrpcCodec.Request request) {
    String tenant = request.tenantId().toString();
    String stream = TenantAuthorityEventV1Codec.streamKey(request.tenantId());
    String digest = "sha256:" + "a".repeat(64);
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("schema", RuntimeTenantOperationEntitlementEvidence.SCHEMA);
    fields.put("variant", "TENANT_OPERATION");
    fields.put("bundleVersion", "1");
    fields.put("authorityAvailability", "AVAILABLE");
    fields.put("tenantId", tenant);
    fields.put("requestId", request.requestId().toString());
    fields.put("callerWorkload", request.expectedWorldWorkload());
    fields.put("sourceKind", request.sourceKind().name());
    fields.put(
        "subscriptionStatus",
        request.sourceKind() == RuntimeTenantOperationEntitlementGrpcCodec.SourceKind.PAID_HOSTING
            ? "active"
            : null);
    fields.put("provisioningStatus", null);
    for (String flag :
        List.of(
            "gameplayAvailable",
            "allowPublicJoin",
            "allowNewGameplayBindings",
            "allowNewInstanceStarts")) {
      fields.put(flag, true);
    }
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
    fields.put("snapshotIdentity", digest);
    fields.put("evaluationIdentity", "sha256:" + "b".repeat(64));
    fields.put(
        "authorityTuple",
        Map.of(
            "tenantAuthorityGeneration",
            Map.of(tenant, "5"),
            "tenantBillingCutoff",
            Map.of(
                tenant,
                Map.of(
                    "tenantAuthorityGeneration",
                    "5",
                    "tenantBillingSequence",
                    "11",
                    "outboxStreamKey",
                    stream,
                    "outboxSequence",
                    "3"))));
    fields.put(
        "outboxCheckpoints",
        List.of(
            Map.of(
                "outboxStreamKey",
                stream,
                "outboxSequence",
                "3",
                "sourceEventId",
                "22222222-2222-4222-8222-222222222222",
                "sourceEventDigest",
                digest)));
    return RuntimeTenantOperationEntitlementEvidence.fromOwnerEvaluation(fields);
  }
}
