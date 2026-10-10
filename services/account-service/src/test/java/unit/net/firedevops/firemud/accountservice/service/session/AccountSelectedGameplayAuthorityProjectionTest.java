package net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementEventV1Codec;
import net.firedevops.firemud.accountservice.dto.DemoTenantEntitlementRequest;
import net.firedevops.firemud.accountservice.dto.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.tenant.FreshTenantCreationEvidence;
import net.firedevops.firemud.common.tenant.GameTenantCreationDigest;
import org.junit.jupiter.api.Test;

/** Pure encoding fixtures are not owner/currentness or installation proof. */
public class AccountSelectedGameplayAuthorityProjectionTest {
  public static final UUID TENANT = UUID.fromString("6271a559-6f2d-4f82-bb1e-9d4c13884e51");
  public static final UUID ACCOUNT = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");

  @Test
  void independentMembershipVersionAndGenerationSurviveExactEventRoundTrip() {
    var value =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "7", "2"), 9L);
    assertThat(value.generation()).isEqualTo(new BigInteger("2"));
    assertThat(value.membershipVersion()).isEqualTo(new BigInteger("7"));
    assertThat(value.sourceVersion()).isEqualTo(new BigInteger("9"));
    assertThat(
            AccountSelectedGameplayAuthorityProjection.decode(
                    value.canonicalBytes(), ACCOUNT, TENANT)
                .canonicalBytes())
        .containsExactly(value.canonicalBytes());
    assertThatThrownBy(
            () ->
                AccountSelectedGameplayAuthorityProjection.decode(
                    value.canonicalBytes(), UUID.randomUUID(), TENANT))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noSequenceGapEqualCheckpointConflictOrRegressionIsAdopted() {
    var first =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "7", "2"), 9L);
    first.requireSuccessorOf(null);
    first.requireSuccessorOf(first);
    var second =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "2", "8", "2"), 9L);
    second.requireSuccessorOf(first); // Non-revoking version advancement is independent.
    var gap =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "3", "9", "2"), 9L);
    assertThatThrownBy(() -> gap.requireSuccessorOf(first))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> second.requireSuccessorOf(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> first.requireSuccessorOf(second))
        .isInstanceOf(IllegalArgumentException.class);
    var conflict =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "8", "2"), 9L);
    assertThatThrownBy(() -> conflict.requireSuccessorOf(first))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void duplicateExtraAliasAndMissingEventFailClosed() {
    var value =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "7", "2"), 9L);
    String json = new String(value.canonicalBytes(), StandardCharsets.UTF_8);
    for (String malformed :
        List.of(
            json.replace(
                "\"sourceVersion\":\"9\"", "\"sourceVersion\":\"9\",\"sourceVersion\":\"9\""),
            json.replace("\"sourceVersion\":\"9\"", "\"generation\":\"9\""),
            json.replace("\"sourceVersion\":\"9\"", "\"sourceVersion\":\"9\",\"extra\":true"),
            json.replace("\"sourceVersion\":\"9\"", "\"sourceVersion\":9"))) {
      assertThatThrownBy(
              () ->
                  AccountSelectedGameplayAuthorityProjection.decode(
                      malformed.getBytes(StandardCharsets.UTF_8), ACCOUNT, TENANT))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void tenantProjectionUsesAuthorityStreamNotLinkedBillingStream() {
    var event = tenant(TENANT);
    var value = AccountSelectedGameplayAuthorityProjection.tenant(event);
    assertThat(new String(value.canonicalBytes(), StandardCharsets.UTF_8))
        .contains("account:auth-authority:v1:tenant/" + TENANT)
        .contains("account:tenant-entitlement:v1:tenant/" + TENANT);
    assertThat(value.key()).isEqualTo("session:auth:generation:tenant:" + TENANT);
    assertThat(
            AccountSelectedGameplayAuthorityProjection.decode(value.canonicalBytes(), null, TENANT)
                .canonicalBytes())
        .containsExactly(value.canonicalBytes());
  }

  public static byte[][] selected(byte[][] initial, UUID account, UUID tenant) {
    return new byte[][] {
      initial[0],
      initial[1],
      AccountSelectedGameplayAuthorityProjection.tenant(tenant(tenant)).canonicalBytes(),
      AccountSelectedGameplayAuthorityProjection.membership(
              member(account, tenant, "1", "2", "1"), 1L)
          .canonicalBytes()
    };
  }

  public static byte[][] selected(
      byte[][] initial,
      TenantAuthorityEventV1Codec.Event tenant,
      MembershipAuthorityEventV1Codec.MembershipEvent member,
      long memberSourceVersion) {
    return new byte[][] {
      initial[0],
      initial[1],
      AccountSelectedGameplayAuthorityProjection.tenant(tenant).canonicalBytes(),
      AccountSelectedGameplayAuthorityProjection.membership(member, memberSourceVersion)
          .canonicalBytes()
    };
  }

  public static byte[] membershipBytes(
      UUID account, UUID tenant, String sequence, String version, String generation) {
    return AccountSelectedGameplayAuthorityProjection.membership(
            member(account, tenant, sequence, version, generation), 1L)
        .canonicalBytes();
  }

  public static MembershipAuthorityEventV1Codec.MembershipEvent member(
      UUID account, UUID tenant, String sequence, String version, String generation) {
    return MembershipAuthorityEventV1Codec.seal(
        Map.ofEntries(
            Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
            Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
            Map.entry("eventId", "member-event-" + sequence),
            Map.entry("requestId", "member-request-" + sequence),
            Map.entry(
                "outboxStreamKey",
                "account:auth-authority:v1:membership/" + account + "/" + tenant),
            Map.entry("outboxSequence", sequence),
            Map.entry("sourceScope", "membership/" + account + "/" + tenant),
            Map.entry("accountId", account.toString()),
            Map.entry("tenantId", tenant.toString()),
            Map.entry("membershipExists", true),
            Map.entry("membershipLifecycleState", "ACTIVE"),
            Map.entry("membershipVersion", Map.of(tenant.toString(), version)),
            Map.entry("membershipAuthorityGeneration", generation),
            Map.entry(
                "authorityTuple",
                Map.of(
                    "issuerAuthGeneration",
                    "1",
                    "accountAuthorityGeneration",
                    "1",
                    "tenantAuthorityGeneration",
                    Map.of(tenant.toString(), "2"),
                    "membershipAuthorityGeneration",
                    Map.of(tenant.toString(), generation),
                    "privateRealmGrantVersions",
                    List.of())),
            Map.entry("issuanceFence", "1"),
            Map.entry("roles", List.of("player")),
            Map.entry("gameplayAdmissionAllowed", true),
            Map.entry("callerBoundAuthorityInvalidated", false)));
  }

  public static TenantAuthorityEventV1Codec.Event tenant(UUID tenant) {
    UUID creation = UUID.fromString("72a18e29-57c0-48be-a9e7-bd7b681d7720");
    UUID operation = UUID.fromString("a750fb1f-3e5d-40e6-b51e-160ef64d24ec");
    String digest = "sha256:" + "a".repeat(64);
    var source =
        new FreshTenantCreationEvidence(
            1,
            "account-service",
            creation,
            operation,
            digest,
            tenant,
            91L,
            "demo-source",
            "NEW_GAME_ROW",
            GameTenantCreationDigest.evidenceDigest(
                "account-service",
                creation,
                operation,
                digest,
                tenant,
                91L,
                "demo-source",
                "NEW_GAME_ROW"));
    var request =
        new DemoTenantEntitlementRequest(
            UUID.fromString("15aad6f4-3c5b-4cd9-b1b9-654e6d9bc51a"),
            tenant,
            creation,
            digest,
            null,
            null,
            null,
            true,
            true,
            true,
            true,
            new DemoTenantEntitlementRequest.Quotas(1L, 1L, 1L));
    var billing = DemoTenantEntitlementEventV1Codec.seal(request, source, 1L, 2L, 2L, 1L);
    return TenantAuthorityEventV1Codec.seal(request, source, 2L, 2L, 1L, billing);
  }
}
