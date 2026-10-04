package net.firedevops.firemud.accountservice.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.CompositeSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.ScopeState;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.TenantProvenanceKind;
import net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance;
import net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRoleSnapshotRepository.RoleSnapshot;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxCheckpointEntry;
import net.firedevops.firemud.accountservice.service.AccountMembershipAuthorityEventProducer.OutboxSourceEvidence;
import net.firedevops.firemud.accountservice.service.impl.AccountServiceImpl;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec.AuthorityTuple;
import org.junit.jupiter.api.Test;

class AccountMembershipCaptureSourcesTest {
  private static final String STREAM_PREFIX = MembershipAuthorityEventV1Codec.EVENT_STREAM_PREFIX;

  @Test
  void retainsExactLockedScopeVersionsRoleHeaderEventAndFenceImmutably() {
    CaptureFixture fixture = activeFixture();
    AccountMembershipCaptureSources sources = fixture.sources();

    assertThat(sources.authoritySnapshot()).isSameAs(fixture.authoritySnapshot());
    assertThat(sources.authoritySnapshot().issuer().sourceVersion()).isEqualTo(1L);
    assertThat(sources.authoritySnapshot().account().sourceVersion()).isEqualTo(1L);
    assertThat(sources.authoritySnapshot().tenants().getFirst().sourceVersion()).isEqualTo(1L);
    assertThat(sources.authoritySnapshot().memberships().getFirst().sourceVersion()).isEqualTo(1L);
    assertThat(sources.authoritySnapshot().issuanceFence().value()).isEqualTo(1L);
    assertThat(sources.authoritySnapshot().issuanceFence().sourceVersion()).isEqualTo(1L);
    assertThat(sources.membershipSnapshot().authorityTuple().membershipAuthorityGeneration())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(sources.membershipSnapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "2"));
    assertThat(sources.membershipSnapshot().sourceEvent().canonicalJson())
        .isEqualTo(fixture.event().canonicalJson());
    assertThat(sources.roleSource()).contains(fixture.roleSource());
    assertThat(sources.roleSource().orElseThrow().snapshotVersion()).isEqualTo(2L);

    assertThatThrownBy(() -> sources.membershipSnapshot().outboxCheckpoints().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> sources.membershipSnapshot().outboxSourceEvidence().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> sources.roleSource().orElseThrow().roles().clear())
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> sources.authoritySnapshot().memberships().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void retainsOnlyAuthenticatedNonadmittingSequenceZeroAbsence() {
    CaptureFixture fixture = missingFixture();
    AccountMembershipCaptureSources sources = fixture.sources();

    assertThat(sources.membershipSnapshot().membershipExists()).isFalse();
    assertThat(sources.membershipSnapshot().gameplayAdmissionAllowed()).isFalse();
    assertThat(sources.membershipSnapshot().membershipBaseline().membershipLifecycleState())
        .isEqualTo("MISSING");
    assertThat(sources.membershipSnapshot().membershipBaseline().membershipVersion())
        .isEqualTo(Map.of(fixture.tenantUuid().toString(), "1"));
    assertThat(sources.membershipSnapshot().outboxCheckpoints())
        .anySatisfy(
            checkpoint -> {
              assertThat(checkpoint.outboxStreamKey())
                  .isEqualTo(membershipStream(fixture.accountUuid(), fixture.tenantUuid()));
              assertThat(checkpoint.outboxSequence()).isEqualTo("0");
            });
    assertThat(sources.membershipSnapshot().outboxSourceEvidence())
        .noneMatch(
            source ->
                source
                    .outboxStreamKey()
                    .equals(membershipStream(fixture.accountUuid(), fixture.tenantUuid())));
    assertThat(sources.membershipSnapshot().sourceEvent()).isNull();
    assertThat(sources.roleSource()).isEmpty();
  }

  @Test
  void rejectsRequestScopeTupleSourceVersionCheckpointAndFenceMixes() {
    CaptureFixture fixture = activeFixture();
    CompositeSnapshot original = fixture.authoritySnapshot();

    assertThatThrownBy(
            () ->
                new AccountMembershipCaptureSources(
                    UUID.randomUUID(),
                    fixture.tenantUuid(),
                    original,
                    fixture.membershipSnapshot(),
                    Optional.of(fixture.roleSource())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact canonical request");

    ScopeState mixedTenant =
        new ScopeState(
            original.tenants().getFirst().scope(),
            original.tenants().getFirst().generation() + 1L,
            original.tenants().getFirst().sourceVersion() + 1L,
            null);
    CompositeSnapshot mixedTuple =
        new CompositeSnapshot(
            original.issuer(),
            original.account(),
            List.of(mixedTenant),
            original.memberships(),
            original.issuanceFence());
    assertThatThrownBy(
            () ->
                new AccountMembershipCaptureSources(
                    fixture.accountUuid(),
                    fixture.tenantUuid(),
                    mixedTuple,
                    fixture.membershipSnapshot(),
                    Optional.of(fixture.roleSource())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority generations or fence");

    ScopeState mixedIssuerVersion =
        new ScopeState(
            original.issuer().scope(),
            original.issuer().generation(),
            original.issuer().sourceVersion() + 1L,
            null);
    CompositeSnapshot mixedCheckpoint =
        new CompositeSnapshot(
            mixedIssuerVersion,
            original.account(),
            original.tenants(),
            original.memberships(),
            original.issuanceFence());
    assertThatThrownBy(
            () ->
                new AccountMembershipCaptureSources(
                    fixture.accountUuid(),
                    fixture.tenantUuid(),
                    mixedCheckpoint,
                    fixture.membershipSnapshot(),
                    Optional.of(fixture.roleSource())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checkpoints differ");

    IssuanceFence changedFence = new IssuanceFence(fixture.accountUuid(), 2L, 2L);
    CompositeSnapshot mixedFence =
        new CompositeSnapshot(
            original.issuer(),
            new ScopeState(original.account().scope(), 1L, 1L, changedFence),
            original.tenants(),
            List.of(
                new ScopeState(original.memberships().getFirst().scope(), 1L, 1L, changedFence)),
            changedFence);
    assertThatThrownBy(
            () ->
                new AccountMembershipCaptureSources(
                    fixture.accountUuid(),
                    fixture.tenantUuid(),
                    mixedFence,
                    fixture.membershipSnapshot(),
                    Optional.of(fixture.roleSource())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("authority generations or fence");
  }

  @Test
  void rejectsMismatchedRoleHeaderAndMembershipEventCheckpoint() {
    CaptureFixture fixture = activeFixture();
    RoleSnapshot mixedRoleHeader =
        new RoleSnapshot(
            fixture.roleSource().accountId(),
            fixture.roleSource().tenantId(),
            fixture.roleSource().membershipId(),
            fixture.roleSource().snapshotVersion() + 1L,
            fixture.roleSource().roles(),
            fixture.roleSource().accountUuid(),
            fixture.roleSource().tenantUuid(),
            fixture.roleSource().tenantProvenance());
    assertThatThrownBy(
            () ->
                new AccountMembershipCaptureSources(
                    fixture.accountUuid(),
                    fixture.tenantUuid(),
                    fixture.authoritySnapshot(),
                    fixture.membershipSnapshot(),
                    Optional.of(mixedRoleHeader)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("role header and identity");

    List<OutboxCheckpointEntry> mixedCheckpoints =
        new ArrayList<>(
            fixture.membershipSnapshot().outboxCheckpoints().stream()
                .map(
                    checkpoint ->
                        checkpoint
                                .outboxStreamKey()
                                .equals(
                                    membershipStream(fixture.accountUuid(), fixture.tenantUuid()))
                            ? new OutboxCheckpointEntry(
                                membershipStream(fixture.accountUuid(), fixture.tenantUuid()), "2")
                            : checkpoint)
                .toList());
    assertThatThrownBy(
            () ->
                new RuntimeMembershipSnapshotDto(
                    fixture.accountUuid().toString(),
                    fixture.tenantUuid().toString(),
                    fixture.accountUuid().toString(),
                    fixture.tenantUuid().toString(),
                    true,
                    true,
                    new RuntimeMembershipSnapshotDto.MembershipBaseline(
                        "ACTIVE", Map.of(fixture.tenantUuid().toString(), "2"), "1"),
                    fixture.roleSource().roles(),
                    fixture.membershipSnapshot().authorityTuple(),
                    "1",
                    Instant.EPOCH,
                    mixedCheckpoints,
                    fixture.membershipSnapshot().outboxSourceEvidence(),
                    fixture.event()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private CaptureFixture missingFixture() {
    UUID accountUuid = UUID.fromString("1c69f3f4-b0f2-4a3a-8eb7-f4f9025746d1");
    UUID tenantUuid = UUID.fromString("8f3b4a30-d5f2-4ddc-a7f3-bb4c729e0461");
    IssuanceFence fence = new IssuanceFence(accountUuid, 1L, 1L);
    CompositeSnapshot authoritySnapshot =
        new CompositeSnapshot(
            new ScopeState(
                AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER), 1L, 1L, null),
            new ScopeState(AuthorityScope.account(accountUuid), 1L, 1L, fence),
            List.of(new ScopeState(AuthorityScope.tenant(tenantUuid), 1L, 1L, null)),
            List.of(
                new ScopeState(AuthorityScope.membership(accountUuid, tenantUuid), 1L, 1L, fence)),
            fence);
    AuthorityTuple authorityTuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(tenantUuid.toString(), "1"),
            Map.of(tenantUuid.toString(), "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    List<OutboxCheckpointEntry> checkpoints =
        new ArrayList<>(
            List.of(
                new OutboxCheckpointEntry(accountStream(accountUuid), "0"),
                new OutboxCheckpointEntry(issuerStream(), "0"),
                new OutboxCheckpointEntry(membershipStream(accountUuid, tenantUuid), "0"),
                new OutboxCheckpointEntry(tenantStream(tenantUuid), "0")));
    RuntimeMembershipSnapshotDto membershipSnapshot =
        new RuntimeMembershipSnapshotDto(
            accountUuid.toString(),
            tenantUuid.toString(),
            accountUuid.toString(),
            tenantUuid.toString(),
            false,
            false,
            new RuntimeMembershipSnapshotDto.MembershipBaseline(
                "MISSING", Map.of(tenantUuid.toString(), "1"), "1"),
            List.of(),
            authorityTuple,
            "1",
            Instant.EPOCH,
            checkpoints,
            List.of(),
            null);
    checkpoints.clear();
    return new CaptureFixture(
        accountUuid, tenantUuid, authoritySnapshot, membershipSnapshot, null, null);
  }

  private CaptureFixture activeFixture() {
    UUID accountUuid = UUID.fromString("76e4fb60-0e16-4b1f-90e5-3ccf465007e1");
    UUID tenantUuid = UUID.fromString("ab4ee2cd-d93c-4a87-b975-c832e197d7c1");
    IssuanceFence fence = new IssuanceFence(accountUuid, 1L, 1L);
    ScopeState issuer =
        new ScopeState(AuthorityScope.issuer(AccountServiceImpl.ACCOUNT_JWT_ISSUER), 1L, 1L, null);
    ScopeState account = new ScopeState(AuthorityScope.account(accountUuid), 1L, 1L, fence);
    ScopeState tenant = new ScopeState(AuthorityScope.tenant(tenantUuid), 1L, 1L, null);
    ScopeState membership =
        new ScopeState(AuthorityScope.membership(accountUuid, tenantUuid), 1L, 1L, fence);
    CompositeSnapshot authoritySnapshot =
        new CompositeSnapshot(issuer, account, List.of(tenant), List.of(membership), fence);
    AuthorityTuple authorityTuple =
        new AuthorityTuple(
            "1",
            "1",
            Map.of(tenantUuid.toString(), "1"),
            Map.of(tenantUuid.toString(), "1"),
            List.of(),
            Optional.empty(),
            Optional.empty());
    String membershipStream = membershipStream(accountUuid, tenantUuid);
    var event =
        MembershipAuthorityEventV1Codec.seal(
            Map.ofEntries(
                Map.entry("schemaVersion", MembershipAuthorityEventV1Codec.SCHEMA_VERSION),
                Map.entry("eventType", MembershipAuthorityEventV1Codec.EVENT_TYPE),
                Map.entry("eventId", "test-membership-event-1"),
                Map.entry("requestId", "test-membership-request-1"),
                Map.entry("outboxStreamKey", membershipStream),
                Map.entry("outboxSequence", "1"),
                Map.entry("sourceScope", "membership/" + accountUuid + "/" + tenantUuid),
                Map.entry("accountId", accountUuid.toString()),
                Map.entry("tenantId", tenantUuid.toString()),
                Map.entry("membershipExists", true),
                Map.entry("membershipLifecycleState", "ACTIVE"),
                Map.entry("membershipVersion", Map.of(tenantUuid.toString(), "2")),
                Map.entry("membershipAuthorityGeneration", "1"),
                Map.entry(
                    "authorityTuple",
                    Map.of(
                        "issuerAuthGeneration", "1",
                        "accountAuthorityGeneration", "1",
                        "tenantAuthorityGeneration", Map.of(tenantUuid.toString(), "1"),
                        "membershipAuthorityGeneration", Map.of(tenantUuid.toString(), "1"),
                        "privateRealmGrantVersions", List.of())),
                Map.entry("issuanceFence", "1"),
                Map.entry("roles", List.of("player")),
                Map.entry("gameplayAdmissionAllowed", true),
                Map.entry("callerBoundAuthorityInvalidated", false)));
    List<OutboxCheckpointEntry> checkpoints =
        new ArrayList<>(
            List.of(
                new OutboxCheckpointEntry(accountStream(accountUuid), "0"),
                new OutboxCheckpointEntry(issuerStream(), "0"),
                new OutboxCheckpointEntry(membershipStream, "1"),
                new OutboxCheckpointEntry(tenantStream(tenantUuid), "0")));
    List<OutboxSourceEvidence> sourceEvidence =
        new ArrayList<>(
            List.of(
                new OutboxSourceEvidence(
                    membershipStream,
                    "1",
                    event.eventId(),
                    event.eventDigest(),
                    event.canonicalJson())));
    RuntimeMembershipSnapshotDto membershipSnapshot =
        new RuntimeMembershipSnapshotDto(
            accountUuid.toString(),
            tenantUuid.toString(),
            accountUuid.toString(),
            tenantUuid.toString(),
            true,
            true,
            new RuntimeMembershipSnapshotDto.MembershipBaseline(
                "ACTIVE", Map.of(tenantUuid.toString(), "2"), "1"),
            List.of("player"),
            authorityTuple,
            "1",
            Instant.EPOCH,
            checkpoints,
            sourceEvidence,
            event);
    checkpoints.clear();
    sourceEvidence.clear();

    VerifiedTenantProvenance provenance =
        new VerifiedTenantProvenance(
            81L,
            TenantProvenanceKind.APPROVED_RETAINED,
            UUID.fromString("f1d82a71-d966-43da-a5cc-e9ac1ea4d8cb"),
            "sha256:" + "a".repeat(64));
    RoleSnapshot roleSource =
        new RoleSnapshot(41L, 81L, 33L, 2L, List.of("player"), accountUuid, tenantUuid, provenance);
    return new CaptureFixture(
        accountUuid, tenantUuid, authoritySnapshot, membershipSnapshot, event, roleSource);
  }

  private static String issuerStream() {
    return STREAM_PREFIX + "issuer/" + AccountServiceImpl.ACCOUNT_JWT_ISSUER;
  }

  private static String accountStream(UUID accountUuid) {
    return STREAM_PREFIX + "account/" + accountUuid;
  }

  private static String tenantStream(UUID tenantUuid) {
    return STREAM_PREFIX + "tenant/" + tenantUuid;
  }

  private static String membershipStream(UUID accountUuid, UUID tenantUuid) {
    return STREAM_PREFIX + "membership/" + accountUuid + "/" + tenantUuid;
  }

  private record CaptureFixture(
      UUID accountUuid,
      UUID tenantUuid,
      CompositeSnapshot authoritySnapshot,
      RuntimeMembershipSnapshotDto membershipSnapshot,
      MembershipAuthorityEventV1Codec.MembershipEvent event,
      RoleSnapshot roleSource) {
    AccountMembershipCaptureSources sources() {
      return new AccountMembershipCaptureSources(
          accountUuid,
          tenantUuid,
          authoritySnapshot,
          membershipSnapshot,
          Optional.ofNullable(roleSource));
    }
  }
}
