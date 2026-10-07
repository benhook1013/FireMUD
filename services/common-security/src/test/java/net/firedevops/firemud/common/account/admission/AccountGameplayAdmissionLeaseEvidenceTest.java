package net.firedevops.firemud.common.account.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class AccountGameplayAdmissionLeaseEvidenceTest {
  private static final String ACCOUNT = "11111111-1111-4111-8111-111111111111";
  private static final String TENANT = "22222222-2222-4222-8222-222222222222";
  private static final String OTHER = "33333333-3333-4333-8333-333333333333";
  private static final String HIGH = "9007199254740993";
  private static final String PREFIX = "account:auth-authority:v1:";

  @Test
  void retainsExactCanonicalRetryIdentityAndCountersAboveDoublePrecision() {
    var value = evidence();
    var parsed = AccountGameplayAdmissionLeaseEvidence.parseCanonical(value.canonicalJson());
    assertThat(parsed).isEqualTo(value);
    assertThat(value.hasSameIdentity(parsed)).isTrue();
    assertThat(value.sha256()).matches("[0-9a-f]{64}");
    assertThat(value.leaseFence()).isEqualTo(new BigInteger(HIGH));
    assertThat(value.canonicalJson())
        .contains("\"membershipVersion\":{\"" + TENANT + "\":\"" + HIGH + "\"}");
    assertThat(value.toString()).doesNotContain(ACCOUNT, value.sha256());
  }

  @Test
  void lifecycleShapeCarriesInactiveWithoutGrantingAuthority() {
    Map<String, Object> carrier = fixture();
    object(carrier, "membershipBaseline").put("membershipLifecycleState", "INACTIVE");
    assertThat(AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier).sha256())
        .isNotEqualTo(evidence().sha256());
  }

  @Test
  void everyCompleteCarrierContributesToDigest() {
    List<Consumer<Map<String, Object>>> changes =
        List.of(
            c ->
                object(object(c, "membershipBaseline"), "membershipVersion")
                    .put(TENANT, "9007199254740994"),
            c -> object(c, "authorityTuple").put("issuerAuthGeneration", "2"),
            c -> object(c, "bindingScope").put("worldSlug", "another-world"),
            c -> object(c, "tokenIdentityEvidence").put("tokenSHA256", "b".repeat(64)),
            c -> c.put("expiresAt", "1014000"),
            c -> checkpoints(c).get(0).put("outboxSequence", "1"));
    for (Consumer<Map<String, Object>> change : changes) {
      var carrier = fixture();
      change.accept(carrier);
      var changed = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
      assertThat(changed.sha256()).isNotEqualTo(evidence().sha256());
      assertThat(changed.hasSameIdentity(evidence())).isFalse();
    }
  }

  @Test
  void rejectsBaselineMissingExtraScalarWrongTenantAndMismatchedGeneration() {
    rejects(c -> c.remove("membershipBaseline"));
    rejects(c -> object(c, "membershipBaseline").put("extra", "1"));
    rejects(c -> object(c, "membershipBaseline").remove("membershipAuthorityGeneration"));
    rejects(c -> object(c, "membershipBaseline").put("membershipVersion", HIGH));
    rejects(c -> object(c, "membershipBaseline").put("membershipVersion", Map.of(OTHER, HIGH)));
    rejects(c -> object(c, "membershipBaseline").put("membershipVersion", Map.of(TENANT, "01")));
    rejects(c -> object(c, "membershipBaseline").put("membershipVersion", Map.of(TENANT, 1L)));
    rejects(c -> object(c, "membershipBaseline").put("membershipAuthorityGeneration", "2"));
    rejects(
        c ->
            object(c, "membershipBaseline")
                .put("membershipVersion", Map.of(TENANT, "9223372036854775808")));
  }

  @Test
  void rejectsDuplicateUnorderedMissingExtraAndMalformedCheckpoints() {
    rejects(c -> checkpoints(c).add(new LinkedHashMap<>(checkpoints(c).get(0))));
    rejects(c -> java.util.Collections.swap(checkpoints(c), 0, 1));
    rejects(c -> checkpoints(c).remove(0));
    rejects(c -> checkpoints(c).get(0).put("outboxSequence", "00"));
    rejects(c -> checkpoints(c).get(2).put("outboxSequence", "0"));
    rejects(c -> checkpoints(c).get(0).put("eventId", OTHER));
  }

  @Test
  void rejectsWrongModeScopePeerTokenAndResumeEpisode() {
    rejects(c -> c.put("mode", "PRIVATE"));
    rejects(c -> c.put("leaseKind", "REFRESH"));
    rejects(c -> c.put("resumeEpisodeId", OTHER));
    rejects(c -> c.put("leaseKind", "RESUME"));
    rejects(c -> object(c, "bindingScope").put("playableStateScope", "PUBLIC_PRODUCTION"));
    rejects(c -> object(c, "bindingScope").put("regionId", "region-1"));
    rejects(c -> c.put("targetNamespace", "other"));
    rejects(c -> c.put("callerWorkload", "spiffe://firemud/ns/test/sa/account-service"));
    rejects(c -> object(c, "tokenIdentityEvidence").put("accountId", OTHER));
    rejects(c -> object(c, "tokenIdentityEvidence").put("issuanceFence", "2"));
    rejects(c -> object(c, "tokenIdentityEvidence").put("tokenBytes", "secret"));
    rejects(c -> object(c, "tokenIdentityEvidence").put("tokenProfile", "gameplay-connect"));
    var resume = fixture();
    resume.put("leaseKind", "RESUME");
    resume.put("resumeEpisodeId", OTHER);
    resume.put("expectedOldBindingGeneration", "7");
    assertThat(AccountGameplayAdmissionLeaseEvidence.fromCarrier(resume).leaseKind())
        .isEqualTo(AccountGameplayAdmissionLeaseEvidence.LeaseKind.RESUME);
  }

  @Test
  void retainsExplicitPriorGenerationWithoutDerivingHistory() {
    var initial = fixture();
    var prior = fixture();
    prior.put("expectedOldBindingGeneration", "7");
    var priorValue = AccountGameplayAdmissionLeaseEvidence.fromCarrier(prior);
    assertThat(priorValue.sha256())
        .isNotEqualTo(AccountGameplayAdmissionLeaseEvidence.fromCarrier(initial).sha256());
    assertThat(AccountGameplayAdmissionLeaseEvidence.parseCanonical(priorValue.canonicalJson()))
        .isEqualTo(priorValue);
    rejects(c -> c.put("expectedOldBindingGeneration", "0"));
    rejects(c -> c.put("expectedOldBindingGeneration", null));
    rejects(
        c -> {
          c.put("leaseKind", "RESUME");
          c.put("resumeEpisodeId", OTHER);
        });
  }

  @Test
  void rejectsExpiredLongerThanSlaOrExtendingTokenAndNoncanonicalParsing() {
    rejects(c -> c.put("expiresAt", "1000000"));
    rejects(c -> c.put("expiresAt", "1015001"));
    rejects(c -> object(c, "tokenIdentityEvidence").put("expiresAt", "1014"));
    rejects(c -> object(c, "tokenIdentityEvidence").put("notBefore", "1001"));
    rejects(c -> c.put("leaseFence", "+1"));
    rejects(c -> c.put("schemaVersion", "2"));
    String json = evidence().canonicalJson();
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.parseCanonical(" " + json))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.parseCanonical(json + "{}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.parseCanonical(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseEvidence.parseCanonical(
                    json.replace(
                        "\"schemaVersion\":\"1\"",
                        "\"schemaVersion\":\"1\",\"schemaVersion\":\"1\"")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cutoffReferencesMustBeCoveredByCompleteCheckpointCoordinates() {
    var carrier = fixture();
    object(carrier, "authorityTuple").put("accountAuthorityGeneration", "2");
    object(carrier, "authorityTuple")
        .put(
            "accountSecurityCutoff",
            Map.of(
                "accountAuthorityGeneration",
                "2",
                "outboxStreamKey",
                PREFIX + "account/" + ACCOUNT,
                "outboxSequence",
                "1"));
    checkpoints(carrier).get(0).put("outboxSequence", "1");
    assertThat(AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier)).isNotNull();
    checkpoints(carrier).get(0).put("outboxSequence", "2");
    var retained = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
    assertThat(object(retained.carrier(), "authorityTuple").get("accountSecurityCutoff"))
        .isEqualTo(object(carrier, "authorityTuple").get("accountSecurityCutoff"));
    checkpoints(carrier).get(0).put("outboxSequence", "0");
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier))
        .isInstanceOf(IllegalArgumentException.class);
    var billing = fixture();
    object(billing, "authorityTuple")
        .put(
            "tenantBillingCutoff",
            Map.of(
                TENANT,
                Map.of(
                    "tenantAuthorityGeneration",
                    HIGH,
                    "tenantBillingSequence",
                    "7",
                    "outboxStreamKey",
                    PREFIX + "tenant/" + TENANT,
                    "outboxSequence",
                    HIGH)));
    assertThat(AccountGameplayAdmissionLeaseEvidence.fromCarrier(billing)).isNotNull();
    checkpoints(billing).get(3).put("outboxSequence", "9007199254740994");
    assertThat(AccountGameplayAdmissionLeaseEvidence.fromCarrier(billing)).isNotNull();
    checkpoints(billing).get(3).put("outboxSequence", "2");
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.fromCarrier(billing))
        .isInstanceOf(IllegalArgumentException.class);
    object(billing, "authorityTuple")
        .put(
            "tenantBillingCutoff",
            Map.of(
                TENANT,
                Map.of(
                    "tenantAuthorityGeneration",
                    HIGH,
                    "tenantBillingSequence",
                    "7",
                    "outboxStreamKey",
                    PREFIX + "account/" + ACCOUNT,
                    "outboxSequence",
                    "1")));
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.fromCarrier(billing))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsOversizedDeepCyclicAndNullCarriersBeforeBuildingUnboundedEvidence() {
    rejects(
        c ->
            c.put(
                "large", "a".repeat(AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES + 1)));
    rejects(c -> c.put("large", "界".repeat(22000)));
    rejects(
        c -> {
          Map<String, Object> nested = Map.of("leaf", "value");
          for (int index = 0; index < 10; index++) {
            nested = Map.of("nested", nested);
          }
          c.put("nested", nested);
        });
    rejects(c -> c.put("cycle", c));
    rejects(c -> c.put("nullable", null));
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.fromCarrier(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseEvidence.parseCanonical(
                    " ".repeat(AccountGameplayAdmissionLeaseEvidence.MAX_EVIDENCE_BYTES + 1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                AccountGameplayAdmissionLeaseEvidence.parseCanonical(
                    "[".repeat(10) + "\"value\"" + "]".repeat(10)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void freezesNestedMapsAndCheckpointListsAgainstCallerMutation() {
    var carrier = fixture();
    var value = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
    String original = value.canonicalJson();
    object(object(carrier, "membershipBaseline"), "membershipVersion").put(TENANT, "2");
    checkpoints(carrier).clear();
    assertThat(value.canonicalJson()).isEqualTo(original);
    assertThatThrownBy(
            () -> object(value.carrier(), "authorityTuple").put("issuerAuthGeneration", "2"))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> checkpoints(value.carrier()).clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  private static AccountGameplayAdmissionLeaseEvidence evidence() {
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(fixture());
  }

  private static void rejects(Consumer<Map<String, Object>> change) {
    var value = fixture();
    change.accept(value);
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.fromCarrier(value))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> value, String key) {
    return (Map<String, Object>) value.get(key);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> checkpoints(Map<String, Object> value) {
    return (List<Map<String, Object>>) value.get("outboxCheckpoints");
  }

  private static Map<String, Object> fixture() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "test");
    value.put("callerWorkload", "spiffe://firemud/ns/test/sa/game-session-service");
    value.put("requestId", OTHER);
    value.put("leaseId", TENANT);
    value.put("leaseFence", HIGH);
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, OTHER);
    scope.put("accountId", ACCOUNT);
    scope.put("tenantId", TENANT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, HIGH);
    value.put("bindingScope", scope);
    Map<String, Object> tuple = new LinkedHashMap<>();
    tuple.put("issuerAuthGeneration", "1");
    tuple.put("accountAuthorityGeneration", "1");
    tuple.put("tenantAuthorityGeneration", Map.of(TENANT, HIGH));
    tuple.put("membershipAuthorityGeneration", Map.of(TENANT, HIGH));
    tuple.put("privateRealmGrantVersions", List.of());
    value.put("authorityTuple", tuple);
    value.put("issuanceFence", HIGH);
    value.put(
        "membershipBaseline",
        new LinkedHashMap<>(
            Map.of(
                "membershipLifecycleState",
                "ACTIVE",
                "membershipVersion",
                new LinkedHashMap<>(Map.of(TENANT, HIGH)),
                "membershipAuthorityGeneration",
                HIGH)));
    List<Map<String, Object>> checkpoints = new ArrayList<>();
    for (String suffix :
        List.of(
            "account/" + ACCOUNT,
            "issuer/firemud-account-service",
            "membership/" + ACCOUNT + "/" + TENANT,
            "tenant/" + TENANT)) {
      checkpoints.add(
          new LinkedHashMap<>(
              Map.of(
                  "outboxStreamKey",
                  PREFIX + suffix,
                  "outboxSequence",
                  suffix.startsWith("account/") || suffix.startsWith("issuer/") ? "0" : HIGH)));
    }
    value.put("outboxCheckpoints", checkpoints);
    Map<String, Object> token = new LinkedHashMap<>();
    token.put("accountId", ACCOUNT);
    for (String key : List.of("operationId", "issuanceRequestId", "tokenJti"))
      token.put(key, OTHER);
    token.put("tokenSHA256", "a".repeat(64));
    token.put("tokenProfile", "game-session-account-delegation");
    for (String key : List.of("tokenGeneration", "issuanceFence", "tokenIdentityFence"))
      token.put(key, HIGH);
    token.put("issuedAt", "999");
    token.put("notBefore", "999");
    token.put("expiresAt", "1300");
    value.put("tokenIdentityEvidence", token);
    value.put("evaluatedAt", "1000000");
    value.put("expiresAt", "1015000");
    return value;
  }
}
