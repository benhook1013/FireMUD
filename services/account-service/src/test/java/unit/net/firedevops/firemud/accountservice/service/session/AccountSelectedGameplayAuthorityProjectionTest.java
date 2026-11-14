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
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
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
            member(ACCOUNT, TENANT, "1", "7", "2"));
    assertThat(value.generation()).isEqualTo(new BigInteger("2"));
    assertThat(value.membershipVersion()).isEqualTo(new BigInteger("7"));
    assertThat(value.sourceVersion()).isEqualTo(new BigInteger("2"));
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
    assertThatThrownBy(
            () ->
                AccountSelectedGameplayAuthorityProjection.membership(
                    member(ACCOUNT, TENANT, "1", "7", "2"), 9L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void noSequenceGapEqualCheckpointConflictOrRegressionIsAdopted() {
    var first =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "7", "2"));
    first.requireSuccessorOf(null);
    first.requireSuccessorOf(first);
    var second =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "2", "8", "2"));
    second.requireSuccessorOf(first); // Non-revoking version advancement is independent.
    var gap =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "3", "9", "2"));
    assertThatThrownBy(() -> gap.requireSuccessorOf(first))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> second.requireSuccessorOf(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> first.requireSuccessorOf(second))
        .isInstanceOf(IllegalArgumentException.class);
    var conflict =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "8", "2"));
    assertThatThrownBy(() -> conflict.requireSuccessorOf(first))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void authorityCheckpointAdvancesTheHeadWithoutChangingMembershipFacts() {
    var prior =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "7", "2"));
    var checkpoint =
        AccountSelectedGameplayAuthorityProjection.membership(
            authorityCheckpoint(ACCOUNT, TENANT, "2", "7", "2", "3", "player"));

    checkpoint.requireSuccessorOf(prior);

    assertThat(checkpoint.sequence()).isEqualTo(new BigInteger("2"));
    assertThat(checkpoint.generation()).isEqualTo(prior.generation());
    assertThat(checkpoint.sourceVersion()).isEqualTo(prior.sourceVersion());
    assertThat(checkpoint.membershipVersion()).isEqualTo(prior.membershipVersion());

    var sequenceOneCheckpoint =
        AccountSelectedGameplayAuthorityProjection.membership(
            authorityCheckpoint(ACCOUNT, TENANT, "1", "7", "2", "3", "player"));
    assertThatThrownBy(() -> sequenceOneCheckpoint.requireSuccessorOf(null))
        .isInstanceOf(IllegalArgumentException.class);

    var changedVersion =
        AccountSelectedGameplayAuthorityProjection.membership(
            authorityCheckpoint(ACCOUNT, TENANT, "2", "8", "2", "3", "player"));
    assertThatThrownBy(() -> changedVersion.requireSuccessorOf(prior))
        .isInstanceOf(IllegalArgumentException.class);

    var changedRole =
        AccountSelectedGameplayAuthorityProjection.membership(
            authorityCheckpoint(ACCOUNT, TENANT, "2", "7", "2", "3", "designer"));
    assertThatThrownBy(() -> changedRole.requireSuccessorOf(prior))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void invalidatingMembershipTransitionCanBeCheckpointedAndFollowedByOrdinaryChange() {
    var joined =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "2", "1"));
    joined.requireSuccessorOf(null);
    var roleRemovalEvent = member(ACCOUNT, TENANT, "2", "3", "2", true);
    var roleRemoved = AccountSelectedGameplayAuthorityProjection.membership(roleRemovalEvent);
    roleRemoved.requireSuccessorOf(joined);

    var paidCheckpointEvent = authorityCheckpoint(ACCOUNT, TENANT, "3", "3", "2", "3", "player");
    var paidCheckpoint = AccountSelectedGameplayAuthorityProjection.membership(paidCheckpointEvent);
    paidCheckpoint.requireSuccessorOf(roleRemoved);

    var ordinaryChange =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "4", "4", "2"));
    ordinaryChange.requireSuccessorOf(paidCheckpoint);

    assertThat(roleRemovalEvent.callerBoundAuthorityInvalidated()).isTrue();
    assertThat(paidCheckpointEvent.callerBoundAuthorityInvalidated()).isFalse();
    assertThat(paidCheckpoint.generation()).isEqualTo(roleRemoved.generation());
    assertThat(paidCheckpoint.membershipVersion()).isEqualTo(roleRemoved.membershipVersion());
    assertThat(ordinaryChange.sequence()).isEqualTo(new BigInteger("4"));
    assertThat(ordinaryChange.membershipVersion()).isEqualTo(new BigInteger("4"));
    assertThat(ordinaryChange.generation()).isEqualTo(roleRemoved.generation());
    assertThat(ordinaryChange.generation()).isGreaterThan(joined.generation());
  }

  @Test
  void duplicateExtraAliasAndMissingEventFailClosed() {
    var value =
        AccountSelectedGameplayAuthorityProjection.membership(
            member(ACCOUNT, TENANT, "1", "7", "2"));
    String json = new String(value.canonicalBytes(), StandardCharsets.UTF_8);
    for (String malformed :
        List.of(
            json.replace(
                "\"sourceVersion\":\"2\"", "\"sourceVersion\":\"2\",\"sourceVersion\":\"2\""),
            json.replace("\"sourceVersion\":\"2\"", "\"generation\":\"2\""),
            json.replace("\"sourceVersion\":\"2\"", "\"sourceVersion\":\"2\",\"extra\":true"),
            json.replace("\"sourceVersion\":\"2\"", "\"sourceVersion\":2"))) {
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
    return member(account, tenant, sequence, version, generation, false);
  }

  private static MembershipAuthorityEventV1Codec.MembershipEvent member(
      UUID account,
      UUID tenant,
      String sequence,
      String version,
      String generation,
      boolean callerBoundAuthorityInvalidated) {
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
            Map.entry("callerBoundAuthorityInvalidated", callerBoundAuthorityInvalidated)));
  }

  private static MembershipAuthorityEventV1Codec.MembershipEvent authorityCheckpoint(
      UUID account,
      UUID tenant,
      String sequence,
      String version,
      String membershipGeneration,
      String tenantGeneration,
      String role) {
    String tenantStream = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX + "tenant/" + tenant;
    return MembershipAuthorityEventV1Codec.seal(
        Map.ofEntries(
            Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
            Map.entry("eventType", MembershipAuthorityEventV1Codec.AUTHORITY_CHECKPOINT_EVENT_TYPE),
            Map.entry("eventId", "member-checkpoint-event-" + sequence),
            Map.entry("requestId", "member-checkpoint-request-" + sequence),
            Map.entry(
                "outboxStreamKey",
                MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
                    + "membership/"
                    + account
                    + "/"
                    + tenant),
            Map.entry("outboxSequence", sequence),
            Map.entry("sourceScope", "membership/" + account + "/" + tenant),
            Map.entry("accountId", account.toString()),
            Map.entry("tenantId", tenant.toString()),
            Map.entry("membershipExists", true),
            Map.entry("membershipLifecycleState", "ACTIVE"),
            Map.entry("membershipVersion", Map.of(tenant.toString(), version)),
            Map.entry("membershipAuthorityGeneration", membershipGeneration),
            Map.entry(
                "authorityTuple",
                Map.of(
                    "issuerAuthGeneration",
                    "1",
                    "accountAuthorityGeneration",
                    "1",
                    "tenantAuthorityGeneration",
                    Map.of(tenant.toString(), tenantGeneration),
                    "membershipAuthorityGeneration",
                    Map.of(tenant.toString(), membershipGeneration),
                    "privateRealmGrantVersions",
                    List.of(),
                    "tenantBillingCutoff",
                    Map.of(
                        tenant.toString(),
                        Map.of(
                            "tenantAuthorityGeneration",
                            tenantGeneration,
                            "tenantBillingSequence",
                            "5",
                            "outboxStreamKey",
                            tenantStream,
                            "outboxSequence",
                            "4")))),
            Map.entry("issuanceFence", "1"),
            Map.entry("roles", List.of(role)),
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
    return TenantAuthorityEventV1Codec.seal(
        new TenantAuthorityEventV1Codec.AuthorityRequest(
            request.requestId(),
            request.requestDigest(),
            request.canonicalTenantId(),
            request.tenantCreationRequestId(),
            request.tenantCreationRequestDigest()),
        source,
        2L,
        2L,
        1L,
        new TenantAuthorityEventV1Codec.BillingReceipt(
            billing.canonicalTenantId(),
            billing.requestId(),
            billing.requestDigest(),
            billing.outboxStreamKey(),
            billing.tenantBillingSequence(),
            billing.eventId(),
            billing.eventDigest()));
  }
}
