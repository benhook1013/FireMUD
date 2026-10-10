package net.firedevops.firemud.accountservice.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionOriginalAckReceipt;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;

/** Test-only, fabricated storage carriers; neither method creates authority or admission proof. */
final class AccountGameplayAdmissionOriginalAckReceiptTestFixtures {
  private AccountGameplayAdmissionOriginalAckReceiptTestFixtures() {}

  /** Deliberately fabricated expired storage carrier, not issuer or current authority evidence. */
  static AccountGameplayAdmissionLeaseEvidence fixture() {
    String account = "11111111-1111-4111-8111-111111111111";
    String tenant = "22222222-2222-4222-8222-222222222222";
    String other = "33333333-3333-4333-8333-333333333333";
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "test");
    value.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    value.put("requestId", other);
    value.put("leaseId", tenant);
    value.put("leaseFence", "1");
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, other);
    scope.put("accountId", account);
    scope.put("tenantId", tenant);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "1");
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(tenant, "1"),
            "membershipAuthorityGeneration",
            Map.of(tenant, "1"),
            "privateRealmGrantVersions",
            List.of()));
    value.put("issuanceFence", "1");
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(tenant, "1"),
            "membershipAuthorityGeneration",
            "1"));
    value.put(
        "outboxCheckpoints",
        List.of(
                "account/" + account,
                "issuer/firemud-account-service",
                "membership/" + account + "/" + tenant,
                "tenant/" + tenant)
            .stream()
            .map(
                suffix ->
                    Map.of(
                        "outboxStreamKey",
                        "account:auth-authority:v1:" + suffix,
                        "outboxSequence",
                        suffix.startsWith("account/") || suffix.startsWith("issuer/") ? "0" : "1"))
            .toList());
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", account);
    for (String key : List.of("operationId", "issuanceRequestId", "tokenJti"))
      token.put(key, other);
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    for (String key : List.of("tokenGeneration", "issuanceFence", "tokenIdentityFence"))
      token.put(key, "1");
    token.put("issuedAt", "999");
    token.put("notBefore", "999");
    token.put("expiresAt", "1300");
    value.put("tokenIdentityEvidence", token);
    value.put("evaluatedAt", "1000000");
    value.put("expiresAt", "1015000");
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(value);
  }

  /** Builds only a non-authorizing storage DTO with the receipt values used by these tests. */
  static AccountGameplayAdmissionOriginalAckReceipt receipt(
      AccountGameplayAdmissionLeaseEvidence evidence, UUID decision) {
    var operation =
        new AccountGameplayAdmissionLeaseOperation(evidence, State.COMMITTED, decision, null);
    return new AccountGameplayAdmissionOriginalAckReceipt(
        operation,
        (short) 1,
        UUID.fromString((String) evidence.carrier().get("requestId")),
        UUID.fromString("11111111-1111-4111-8111-111111111111"),
        UUID.fromString((String) evidence.carrier().get("leaseId")),
        1L,
        evidence.sha256(),
        decision,
        1_015_000L,
        "123456",
        1_014_999L,
        "123457");
  }
}
