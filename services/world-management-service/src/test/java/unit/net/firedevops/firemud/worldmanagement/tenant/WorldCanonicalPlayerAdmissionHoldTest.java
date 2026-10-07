package unit.net.firedevops.firemud.worldmanagement.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.worldmanagement.tenant.WorldCanonicalPlayerAdmissionHold;
import org.junit.jupiter.api.Test;

class WorldCanonicalPlayerAdmissionHoldTest {
  static final String ID = "11111111-1111-4111-8111-111111111111";
  static final String ACCOUNT = "22222222-2222-4222-8222-222222222222";

  @Test
  void originalLeaseDefinesRetryIdentityWithoutRemintingOrRenewal() {
    var lease = lease();
    var original = new WorldCanonicalPlayerAdmissionHold.Request(lease, 7L, 8L);
    var exact =
        new WorldCanonicalPlayerAdmissionHold.Request(
            AccountGameplayAdmissionLeaseEvidence.parseCanonical(lease.canonicalJson()), 7L, 8L);
    assertThat(original.sameBinding(exact)).isTrue();
    assertThat(original.sameBinding(new WorldCanonicalPlayerAdmissionHold.Request(lease, 8L, 8L)))
        .isFalse();
    var changed = carrier();
    changed.put("leaseFence", "2");
    assertThat(
            original.sameBinding(
                new WorldCanonicalPlayerAdmissionHold.Request(
                    AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed), 7L, 8L)))
        .isFalse();
  }

  @Test
  void donorDelegationBoundsRemainExactWithoutWideningTheOriginalCarrier() {
    var carrier = carrier();
    var tuple = new LinkedHashMap<>((Map<String, Object>) carrier.get("authorityTuple"));
    tuple.put("issuerAuthGeneration", "9007199254740993");
    carrier.put("authorityTuple", tuple);
    var exact = AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
    assertThat(exact.canonicalJson()).contains("9007199254740993");
    tuple.put("issuerAuthGeneration", "9223372036854775808");
    assertThatThrownBy(() -> AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void worldLifecycleEpochRemainsSeparateFromLeaseRegionEpoch() {
    var request = new WorldCanonicalPlayerAdmissionHold.Request(lease(), 7L, 8L);
    var evidence = evidence();
    var hold =
        new WorldCanonicalPlayerAdmissionHold(
            UUID.randomUUID(), UUID.randomUUID(), request, evidence);
    assertThat(hold.worldEvidence().lifecycleEpoch()).isEqualTo(7L);
    assertThat(hold.diagnosticExpiresAtMillis())
        .isEqualTo(Long.parseLong((String) request.lease().carrier().get("expiresAt")));
    when(evidence.lifecycleEpoch()).thenReturn(9L);
    assertThatThrownBy(() -> request.requireExactActiveWorld(evidence))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact expected ACTIVE");
  }

  @Test
  void inactiveMembershipAndIsolatedScopeAreNotRepairable() {
    var changed = carrier();
    changed.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "INACTIVE",
            "membershipVersion",
            Map.of(ID, "1"),
            "membershipAuthorityGeneration",
            "1"));
    var inactiveLease = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> new WorldCanonicalPlayerAdmissionHold.Request(inactiveLease, 7L, 8L))
        .isInstanceOf(IllegalArgumentException.class);
    changed = carrier();
    var scope = new LinkedHashMap<>(scope(changed));
    scope.put("playableStateScope", "ISOLATED");
    changed.put("bindingScope", scope);
    var isolated = AccountGameplayAdmissionLeaseEvidence.fromCarrier(changed);
    assertThatThrownBy(() -> new WorldCanonicalPlayerAdmissionHold.Request(isolated, 7L, 8L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void substitutedWorldTenantOrNonActiveTupleIsDenied() {
    var request = new WorldCanonicalPlayerAdmissionHold.Request(lease(), 7L, 8L);
    var evidence = evidence();
    when(evidence.request().canonicalTenantId()).thenReturn(UUID.fromString(ACCOUNT));
    var substituted = evidence;
    assertThatThrownBy(() -> request.requireExactActiveWorld(substituted))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("canonical World scope");
    evidence = evidence();
    when(evidence.lifecycleStatus()).thenReturn("TERMINATING");
    var inactive = evidence;
    assertThatThrownBy(() -> request.requireExactActiveWorld(inactive))
        .isInstanceOf(IllegalArgumentException.class);
  }

  static WorldCanonicalInstanceLifecycleEvidence evidence() {
    var selector = mock(WorldCanonicalInstanceLifecycleEvidence.Request.class);
    when(selector.targetNamespace()).thenReturn("firemud");
    when(selector.canonicalTenantId()).thenReturn(UUID.fromString(ID));
    when(selector.worldSlug()).thenReturn("world");
    when(selector.canonicalGameInstanceId()).thenReturn(UUID.fromString(ID));
    when(selector.playableStateNamespaceId()).thenReturn(UUID.fromString(ID));
    when(selector.playableStateScope()).thenReturn("SHARED");
    when(selector.publicProduction()).thenReturn(true);
    var evidence = mock(WorldCanonicalInstanceLifecycleEvidence.class);
    when(evidence.request()).thenReturn(selector);
    when(evidence.lifecycleStatus()).thenReturn("ACTIVE");
    when(evidence.lifecycleEpoch()).thenReturn(7L);
    when(evidence.rowVersion()).thenReturn(8L);
    return evidence;
  }

  static AccountGameplayAdmissionLeaseEvidence lease() {
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier());
  }

  /** Synthetic lease shape only: no Account authentication, sources, or terminal proof. */
  static Map<String, Object> carrier() {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    value.put("schemaVersion", "1");
    value.put("mode", "PUBLIC_PRODUCTION");
    value.put("targetNamespace", "firemud");
    value.put("callerWorkload", "spiffe://firemud/ns/firemud/sa/game-session-service");
    value.put("requestId", ID);
    value.put("leaseId", ACCOUNT);
    value.put("leaseFence", "1");
    value.put("leaseKind", "NEW_BINDING");
    Map<String, Object> scope = new LinkedHashMap<>();
    for (String key :
        List.of(
            "tenantId",
            "realmId",
            "playableStateNamespaceId",
            "gameInstanceId",
            "characterId",
            "sessionId",
            "regionId")) scope.put(key, ID);
    scope.put("accountId", ACCOUNT);
    scope.put("worldSlug", "world");
    scope.put("realmSlug", "realm");
    scope.put("playableStateScope", "SHARED");
    for (String key :
        List.of("bindingGeneration", "catalogRevision", "pointerVersion", "regionEpoch"))
      scope.put(key, "9007199254740993");
    value.put("bindingScope", scope);
    value.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration",
            "1",
            "accountAuthorityGeneration",
            "1",
            "tenantAuthorityGeneration",
            Map.of(ID, "1"),
            "membershipAuthorityGeneration",
            Map.of(ID, "1"),
            "privateRealmGrantVersions",
            List.of()));
    value.put("issuanceFence", "1");
    value.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState",
            "ACTIVE",
            "membershipVersion",
            Map.of(ID, "1"),
            "membershipAuthorityGeneration",
            "1"));
    value.put(
        "outboxCheckpoints",
        List.of(
            checkpoint("account/" + ACCOUNT, "0"),
                checkpoint("issuer/firemud-account-service", "0"),
            checkpoint("membership/" + ACCOUNT + "/" + ID, "1"), checkpoint("tenant/" + ID, "1")));
    long now = System.currentTimeMillis();
    value.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", ACCOUNT),
            Map.entry("operationId", ID),
            Map.entry("issuanceRequestId", ID),
            Map.entry("tokenJti", ID),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("tokenProfile", "game-session-account-delegation"),
            Map.entry("issuedAt", Long.toString(now / 1000)),
            Map.entry("notBefore", Long.toString(now / 1000)),
            Map.entry("expiresAt", Long.toString(now / 1000 + 300))));
    value.put("evaluatedAt", Long.toString(now));
    value.put("expiresAt", Long.toString(now + 15000));
    return value;
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> scope(Map<String, Object> carrier) {
    return (Map<String, Object>) carrier.get("bindingScope");
  }

  private static Map<String, Object> checkpoint(String suffix, String sequence) {
    return Map.of(
        "outboxStreamKey", "account:auth-authority:v1:" + suffix, "outboxSequence", sequence);
  }
}
