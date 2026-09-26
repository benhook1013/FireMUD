package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.PrivateRealmGrantVersion;
import org.junit.jupiter.api.Test;

class NeverJoinedMembershipSnapshotTest {
  private static final String ACCOUNT_ID = "4c4b57d8-e3a2-48fe-9977-e7df0fdce901";
  private static final String TENANT_ID = "57c58f36-c5ea-4aa8-8ef7-91a45e407f01";
  private static final String STREAM_KEY =
      MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX
          + "membership/"
          + ACCOUNT_ID
          + "/"
          + TENANT_ID;

  @Test
  void constructsEventFreeNonAdmittingSnapshotWithCompleteTuple() {
    var snapshot = snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", completeTuple(), "9", STREAM_KEY);

    assertThat(snapshot.membershipExists()).isFalse();
    assertThat(snapshot.gameplayAdmissionAllowed()).isFalse();
    assertThat(snapshot.outboxSequence()).isZero();
    assertThat(snapshot.authorityTuple().membershipAuthorityGeneration())
        .containsEntry(TENANT_ID, "1");
  }

  @Test
  void rejectsNonCanonicalIdsAndNonPositiveCounters() {
    assertThatThrownBy(
            () ->
                snapshot(
                    ACCOUNT_ID.toUpperCase(),
                    TENANT_ID,
                    "1",
                    "1",
                    completeTuple(),
                    "9",
                    STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "0", "1", completeTuple(), "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "0", completeTuple(), "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", completeTuple(), "0", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsTupleWithWrongTenantOrMembershipGeneration() {
    AuthorityTuple wrongTenant =
        new AuthorityTuple(
            "4",
            "6",
            Map.of(ACCOUNT_ID, "8"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    AuthorityTuple wrongMembershipGeneration =
        new AuthorityTuple(
            "4",
            "6",
            Map.of(TENANT_ID, "8"),
            Map.of(TENANT_ID, "2"),
            List.of(),
            Optional.empty(),
            Optional.empty());

    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", wrongTenant, "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                snapshot(
                    ACCOUNT_ID, TENANT_ID, "1", "1", wrongMembershipGeneration, "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsInvalidTupleCountersAndSequenceZeroAuthorityData() {
    AuthorityTuple zeroIssuer =
        new AuthorityTuple(
            "0",
            "6",
            Map.of(TENANT_ID, "8"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    AuthorityTuple nonCanonicalAccount =
        new AuthorityTuple(
            "4",
            "01",
            Map.of(TENANT_ID, "8"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    AuthorityTuple zeroTenant =
        new AuthorityTuple(
            "4",
            "6",
            Map.of(TENANT_ID, "0"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    AuthorityTuple extraGrant =
        new AuthorityTuple(
            "4",
            "6",
            Map.of(TENANT_ID, "8"),
            Map.of(TENANT_ID, "1"),
            List.of(new PrivateRealmGrantVersion(TENANT_ID, "world", "realm", "lifecycle", "1")),
            Optional.empty(),
            Optional.empty());
    AuthorityTuple presentCutoff =
        new AuthorityTuple(
            "4",
            "6",
            Map.of(TENANT_ID, "8"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.of(new AccountSecurityCutoff("6", "account-stream", "1")),
            Optional.empty());

    assertThatThrownBy(() -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", zeroIssuer, "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", nonCanonicalAccount, "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", zeroTenant, "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", extraGrant, "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", presentCutoff, "9", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNonCanonicalMembershipStreamKey() {
    assertThatThrownBy(
            () ->
                snapshot(
                    ACCOUNT_ID,
                    TENANT_ID,
                    "1",
                    "1",
                    completeTuple(),
                    "9",
                    STREAM_KEY + "/unexpected"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot snapshot(
      String accountId,
      String tenantId,
      String membershipVersion,
      String membershipAuthorityGeneration,
      AuthorityTuple authorityTuple,
      String issuanceFence,
      String streamKey) {
    return new AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot(
        accountId,
        tenantId,
        membershipVersion,
        membershipAuthorityGeneration,
        authorityTuple,
        issuanceFence,
        Instant.parse("2026-09-27T00:00:00Z"),
        streamKey);
  }

  private static AuthorityTuple completeTuple() {
    return new AuthorityTuple(
        "4",
        "6",
        Map.of(TENANT_ID, "8"),
        Map.of(TENANT_ID, "1"),
        List.of(),
        Optional.empty(),
        Optional.empty());
  }
}
