package net.firedevops.firemud.accountservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.PrivateRealmGrantVersion;
import net.firedevops.firemud.common.account.authority.TenantGenerationAuthorityEventV1Codec;
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
    var snapshot = snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", completeTuple(), "1", STREAM_KEY);

    assertThat(snapshot.membershipExists()).isFalse();
    assertThat(snapshot.membershipLifecycleState()).isEqualTo("MISSING");
    assertThat(snapshot.roles()).isEmpty();
    assertThat(snapshot.gameplayAdmissionAllowed()).isFalse();
    assertThat(snapshot.membershipVersion()).containsOnly(Map.entry(TENANT_ID, "1"));
    assertThat(snapshot.outboxCheckpoints())
        .containsExactly(
            new OutboxCheckpointEntry("account:auth-authority:v1:account/" + ACCOUNT_ID, "0"),
            new OutboxCheckpointEntry(
                "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER, "0"),
            new OutboxCheckpointEntry(STREAM_KEY, "0"),
            new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + TENANT_ID, "0"));
    assertThat(snapshot.outboxSourceEvidence()).isEmpty();
    assertThat(snapshot.authorityTuple().membershipAuthorityGeneration())
        .containsEntry(TENANT_ID, "1");
    assertThatThrownBy(() -> snapshot.membershipVersion().put(TENANT_ID, "2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void retainsAnAdvancedAccountIssuanceFenceWithoutInventingUpstreamEvents() {
    var snapshot = snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", completeTuple(), "7", STREAM_KEY);

    assertThat(snapshot.issuanceFence()).isEqualTo("7");
    assertThat(snapshot.outboxCheckpoints())
        .allMatch(checkpoint -> checkpoint.outboxSequence().equals("0"));
    assertThat(snapshot.outboxSourceEvidence()).isEmpty();
  }

  @Test
  void neverJoinedMembershipMayKeepZeroMembershipCheckpointWithAdvancedUpstreamSources() {
    String issuerStream =
        "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER;
    String tenantStream = "account:auth-authority:v1:tenant/" + TENANT_ID;
    var issuerEvent =
        IssuerGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                IssuerGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                IssuerGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                "issuer-event-1",
                "requestId",
                "issuer-request-1",
                "issuerId",
                AccountServiceImpl.ACCOUNT_JWT_ISSUER,
                "sourceScope",
                "issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER,
                "outboxStreamKey",
                issuerStream,
                "outboxSequence",
                "1",
                "issuerAuthGeneration",
                "2",
                "sourceVersion",
                "2"));
    UUID tenantRequest = UUID.fromString("018f8f0a-3c8d-7b35-ad26-7b0c9d8e6f4a");
    var tenantEvent =
        TenantGenerationAuthorityEventV1Codec.seal(
            Map.of(
                "schemaVersion",
                TenantGenerationAuthorityEventV1Codec.SCHEMA_VERSION,
                "eventType",
                TenantGenerationAuthorityEventV1Codec.EVENT_TYPE,
                "eventId",
                TenantGenerationAuthorityEventV1Codec.EVENT_ID_PREFIX + tenantRequest,
                "requestId",
                tenantRequest.toString(),
                "tenantId",
                TENANT_ID,
                "sourceScope",
                "tenant/" + TENANT_ID,
                "outboxStreamKey",
                tenantStream,
                "outboxSequence",
                "1",
                "tenantAuthorityGeneration",
                "2",
                "sourceVersion",
                "2"));
    AuthorityTuple currentTuple =
        new AuthorityTuple(
            "2",
            "1",
            Map.of(TENANT_ID, "2"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    List<OutboxCheckpointEntry> checkpoints =
        List.of(
            new OutboxCheckpointEntry("account:auth-authority:v1:account/" + ACCOUNT_ID, "0"),
            new OutboxCheckpointEntry(issuerStream, "1"),
            new OutboxCheckpointEntry(STREAM_KEY, "0"),
            new OutboxCheckpointEntry(tenantStream, "1"));
    List<OutboxSourceEvidence> evidence =
        List.of(
            new OutboxSourceEvidence(
                issuerStream,
                "1",
                issuerEvent.eventId(),
                issuerEvent.eventDigest(),
                issuerEvent.canonicalJson()),
            new OutboxSourceEvidence(
                tenantStream,
                "1",
                tenantEvent.eventId(),
                tenantEvent.eventDigest(),
                tenantEvent.canonicalJson()));

    var snapshot =
        new AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot(
            ACCOUNT_ID,
            TENANT_ID,
            Map.of(TENANT_ID, "1"),
            "1",
            currentTuple,
            "7",
            Instant.parse("2026-09-27T00:00:00Z"),
            STREAM_KEY,
            checkpoints,
            evidence);

    assertThat(snapshot.membershipExists()).isFalse();
    assertThat(snapshot.gameplayAdmissionAllowed()).isFalse();
    assertThat(snapshot.outboxCheckpoints()).isEqualTo(checkpoints);
    assertThat(snapshot.outboxSourceEvidence()).isEqualTo(evidence);
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
                    "1",
                    STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "0", "1", completeTuple(), "1", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "0", completeTuple(), "1", STREAM_KEY))
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
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", wrongTenant, "1", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                snapshot(
                    ACCOUNT_ID, TENANT_ID, "1", "1", wrongMembershipGeneration, "1", STREAM_KEY))
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

    assertThatThrownBy(() -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", zeroIssuer, "1", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", nonCanonicalAccount, "1", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", zeroTenant, "1", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", extraGrant, "1", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", presentCutoff, "1", STREAM_KEY))
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
                    "1",
                    STREAM_KEY + "/unexpected"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUpstreamGenerationThatCannotProveSequenceZero() {
    AuthorityTuple changedIssuerGeneration =
        new AuthorityTuple(
            "2",
            "1",
            Map.of(TENANT_ID, "1"),
            Map.of(TENANT_ID, "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());

    assertThatThrownBy(
            () ->
                snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", changedIssuerGeneration, "1", STREAM_KEY))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsSequenceZeroSnapshotWithMissingCheckpointOrSourceEvent() {
    var valid = snapshot(ACCOUNT_ID, TENANT_ID, "1", "1", completeTuple(), "1", STREAM_KEY);
    var withoutTenantCheckpoint =
        valid.outboxCheckpoints().subList(0, valid.outboxCheckpoints().size() - 1);
    var positiveSourceEvidence =
        List.of(
            new OutboxSourceEvidence(
                STREAM_KEY,
                "1",
                "04ef66b4-c0ad-3d5b-b3b2-0e8510e72002",
                positiveSourceEvent().eventDigest(),
                positiveSourceEvent().canonicalJson()));

    assertThatThrownBy(
            () ->
                new AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot(
                    valid.accountId(),
                    valid.tenantId(),
                    valid.membershipVersion(),
                    valid.membershipAuthorityGeneration(),
                    valid.authorityTuple(),
                    valid.issuanceFence(),
                    valid.evaluatedAt(),
                    valid.outboxStreamKey(),
                    withoutTenantCheckpoint,
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new AccountMembershipAuthorityEventProducer.NeverJoinedMembershipSnapshot(
                    valid.accountId(),
                    valid.tenantId(),
                    valid.membershipVersion(),
                    valid.membershipAuthorityGeneration(),
                    valid.authorityTuple(),
                    valid.issuanceFence(),
                    valid.evaluatedAt(),
                    valid.outboxStreamKey(),
                    valid.outboxCheckpoints(),
                    positiveSourceEvidence))
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
        Map.of(tenantId, membershipVersion),
        membershipAuthorityGeneration,
        authorityTuple,
        issuanceFence,
        Instant.parse("2026-09-27T00:00:00Z"),
        streamKey,
        List.of(
            new OutboxCheckpointEntry("account:auth-authority:v1:account/" + accountId, "0"),
            new OutboxCheckpointEntry(
                "account:auth-authority:v1:issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER, "0"),
            new OutboxCheckpointEntry(streamKey, "0"),
            new OutboxCheckpointEntry("account:auth-authority:v1:tenant/" + tenantId, "0")),
        List.of());
  }

  private static AuthorityTuple completeTuple() {
    return new AuthorityTuple(
        "1",
        "1",
        Map.of(TENANT_ID, "1"),
        Map.of(TENANT_ID, "1"),
        List.of(),
        Optional.empty(),
        Optional.empty());
  }

  private static MembershipAuthorityEventV1Codec.MembershipEvent positiveSourceEvent() {
    return MembershipAuthorityEventV1Codec.seal(
        Map.ofEntries(
            Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
            Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
            Map.entry("eventId", "04ef66b4-c0ad-3d5b-b3b2-0e8510e72002"),
            Map.entry("requestId", "join-request-1"),
            Map.entry("outboxStreamKey", STREAM_KEY),
            Map.entry("outboxSequence", "1"),
            Map.entry("sourceScope", "membership/" + ACCOUNT_ID + "/" + TENANT_ID),
            Map.entry("accountId", ACCOUNT_ID),
            Map.entry("tenantId", TENANT_ID),
            Map.entry("membershipExists", true),
            Map.entry("membershipLifecycleState", "ACTIVE"),
            Map.entry("membershipVersion", Map.of(TENANT_ID, "2")),
            Map.entry("membershipAuthorityGeneration", "1"),
            Map.entry(
                "authorityTuple",
                Map.of(
                    "issuerAuthGeneration", "1",
                    "accountAuthorityGeneration", "1",
                    "tenantAuthorityGeneration", Map.of(TENANT_ID, "1"),
                    "membershipAuthorityGeneration", Map.of(TENANT_ID, "1"),
                    "privateRealmGrantVersions", List.of())),
            Map.entry("issuanceFence", "1"),
            Map.entry("roles", List.of("player")),
            Map.entry("gameplayAdmissionAllowed", true),
            Map.entry("callerBoundAuthorityInvalidated", false)));
  }
}
