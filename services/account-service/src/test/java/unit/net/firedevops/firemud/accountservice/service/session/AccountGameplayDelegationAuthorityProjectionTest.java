package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.AccountSecurityStateAuthorityEventV1Codec;
import net.firedevops.firemud.common.account.authority.TenantAuthorityEventV1Codec;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationAuthorityProjectionTest {
  @Test
  void canonicalProjectionPairMatchesDonorSequenceZeroGoldenVector() {
    UUID accountId = UUID.fromString("b4ec84c2-50c8-4cec-8d92-a8603d1c53f0");
    IssuerAccountSourceSnapshot source = sequenceZeroSnapshot(accountId);

    byte[][] actual = AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);

    assertThat(new String(actual[0], StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"schemaVersion\":\"account-auth-issuer-generation-projection/v1\","
                + "\"issuerId\":\""
                + GameSessionAccountDelegationProfile.ISSUER
                + "\",\"issuerAuthGeneration\":\"1\",\"sourceVersion\":\"1\","
                + "\"outboxStreamKey\":\"account:auth-authority:v1:issuer/"
                + GameSessionAccountDelegationProfile.ISSUER
                + "\",\"lastAppliedSourceOutboxSequence\":\"0\"}");
    assertThat(new String(actual[1], StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"schemaVersion\":\"account-auth-account-generation-projection/v1\","
                + "\"accountId\":\""
                + accountId
                + "\",\"accountAuthorityGeneration\":\"1\",\"sourceVersion\":\"1\","
                + "\"outboxStreamKey\":\"account:auth-authority:v1:account/"
                + accountId
                + "\",\"outboxSequence\":\"0\"}");
  }

  @Test
  void canonicalProjectionPairRejectsMissingAccountProjection() {
    UUID accountId = UUID.fromString("b4ec84c2-50c8-4cec-8d92-a8603d1c53f0");
    IssuerAccountSourceSnapshot valid = sequenceZeroSnapshot(accountId);
    IssuerAccountSourceSnapshot missingAccountProjection =
        new IssuerAccountSourceSnapshot(
            valid.issuer(),
            valid.account(),
            valid.issuanceFence(),
            null,
            valid.canonicalIssuerProjection());

    assertThatThrownBy(
            () ->
                AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(
                    missingAccountProjection))
        .isInstanceOf(
            AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
  }

  private static IssuerAccountSourceSnapshot sequenceZeroSnapshot(UUID accountId) {
    String issuerId = GameSessionAccountDelegationProfile.ISSUER;
    CurrentSourceEvidence issuer =
        new CurrentSourceEvidence(
            AuthorityScope.issuer(issuerId),
            1L,
            1L,
            null,
            new SourceCheckpoint(
                "account:auth-authority:v1:issuer/" + issuerId,
                0L,
                Optional.empty(),
                Optional.empty()),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            10L,
            null);
    var fence = new IssuanceFence(accountId, 1L, 1L);
    CurrentSourceEvidence account =
        new CurrentSourceEvidence(
            AuthorityScope.account(accountId),
            1L,
            1L,
            fence,
            new SourceCheckpoint(
                "account:auth-authority:v1:account/" + accountId,
                0L,
                Optional.empty(),
                Optional.empty()),
            Optional.empty(),
            "ACCOUNT_REPOSITORY_INSERT",
            42L,
            AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name(),
            20L,
            20L);
    return new IssuerAccountSourceSnapshot(issuer, account, fence);
  }

  @Nested
  class DonorOwnerReadRegressions {
    private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void selectedOwnerObservationUsesActualCompositePairReceiptAndIndependentVersion() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.readSelectedAuthorityProjections(ACCOUNT_ID, fixture.tenant))
          .thenReturn(fixture.values);
      var observation = fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant);
      assertThat(observation.admitting()).isFalse();
      assertThat(observation.membershipVersion()).isEqualTo(2L);
      assertThat(observation.membershipAuthorityGeneration()).isEqualTo(1L);
      assertThat(java.util.Arrays.deepEquals(observation.canonicalProjections(), fixture.values))
          .isTrue();
      org.mockito.Mockito.verify(fixture.generations, org.mockito.Mockito.times(2))
          .readCompositeSnapshot(
              GameSessionAccountDelegationProfile.ISSUER,
              ACCOUNT_ID,
              List.of(fixture.tenant),
              List.of(fixture.tenant));
      org.mockito.Mockito.verify(fixture.tenantSources, org.mockito.Mockito.times(2))
          .readCurrentByTenant(fixture.tenant);
    }

    @Test
    void selectedIndependentVersionMismatchDeniesBeforeRedisEvenWhenGenerationMatches() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.member.getMembershipVersion()).thenReturn(3L);
      assertThatThrownBy(
              () -> fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verifyNoInteractions(fixture.redis);
    }

    @Test
    void selectedSourceChangedAfterExactRedisReadbackNeverReturnsObservation() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.readSelectedAuthorityProjections(ACCOUNT_ID, fixture.tenant))
          .thenAnswer(
              invocation -> {
                when(fixture.member.getMembershipVersion()).thenReturn(3L);
                return fixture.values;
              });
      assertThatThrownBy(
              () -> fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verify(fixture.memberships, org.mockito.Mockito.times(2))
          .findFreshMembershipForUpdate(ACCOUNT_ID, fixture.tenant);
    }

    @Test
    void selectedPublicationRejectsMissingMembershipPrefixBeforeMembershipCas() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.publishAuthorityProjections(
              eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
          .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              new byte[][] {null, null}, new byte[][] {fixture.values[2], fixture.values[3]});
      when(fixture.outbox.findEventPage(
              "account:auth-authority:v1:membership/" + ACCOUNT_ID + "/" + fixture.tenant, 0L, 1L))
          .thenReturn(List.of());

      assertThatThrownBy(
              () -> fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.times(1))
          .publishSelectedAuthorityEventPage(
              any(UUID.class),
              any(UUID.class),
              org.mockito.ArgumentMatchers.anyBoolean(),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              any(List.class));
    }

    @Test
    void firstSelectedPublicationReplaysRetainedPrefixThroughCapturedHeadAboveOne() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.publishAuthorityProjections(
              eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
          .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);

      var eventOne =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.member(
              ACCOUNT_ID, fixture.tenant, "1", "2", "1");
      var eventTwo =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.member(
              ACCOUNT_ID, fixture.tenant, "2", "3", "1");
      String stream = eventOne.outboxStreamKey();
      var rowOne =
          new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
              .Event(
              stream,
              eventOne.requestId(),
              1L,
              eventOne.eventId(),
              eventOne.eventDigest(),
              eventOne.canonicalJsonUtf8());
      var rowTwo =
          new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
              .Event(
              stream,
              eventTwo.requestId(),
              2L,
              eventTwo.eventId(),
              eventTwo.eventDigest(),
              eventTwo.canonicalJsonUtf8());
      when(fixture.member.getMembershipVersion()).thenReturn(3L);
      when(fixture.outbox.readCheckpoint(stream))
          .thenReturn(
              Optional.of(
                  new net.firedevops.firemud.accountservice.repository
                      .AccountAuthorityOutboxRepository.Checkpoint(
                      stream, 2L, eventTwo.eventId(), eventTwo.eventDigest())));
      when(fixture.outbox.findEvent(stream, 2L)).thenReturn(Optional.of(rowTwo));
      when(fixture.outbox.findEventPage(stream, 0L, 2L)).thenReturn(List.of(rowOne, rowTwo));
      var tenantEvent =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.tenant(fixture.tenant);
      var provenance =
          new net.firedevops.firemud.accountservice.repository
              .AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance(
              null,
              net.firedevops.firemud.accountservice.repository
                  .AccountMembershipPairAuthorityRepository.TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvent.sourceEvidence().operationId(),
              tenantEvent.sourceEvidence().evidenceDigest());
      when(fixture.pairs.readForUpdate(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              Optional.of(
                  new net.firedevops.firemud.accountservice.repository
                      .AccountMembershipPairAuthorityRepository.PairAuthority(
                      ACCOUNT_ID,
                      fixture.tenant,
                      provenance,
                      true,
                      3L,
                      1L,
                      2L,
                      eventTwo.eventId(),
                      eventTwo.eventDigest(),
                      false)));

      byte[] currentMembership =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.membershipBytes(
              ACCOUNT_ID, fixture.tenant, "2", "3", "1");
      when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              new byte[][] {null, null}, new byte[][] {fixture.values[2], currentMembership});

      fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant);

      org.mockito.ArgumentCaptor<byte[]> prior = org.mockito.ArgumentCaptor.forClass(byte[].class);
      @SuppressWarnings("unchecked")
      org.mockito.ArgumentCaptor<List<byte[]>> candidates =
          (org.mockito.ArgumentCaptor<List<byte[]>>)
              (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(List.class);
      org.mockito.Mockito.verify(fixture.redis)
          .publishSelectedAuthorityEventPage(
              eq(ACCOUNT_ID), eq(fixture.tenant), eq(false), prior.capture(), candidates.capture());
      assertThat(prior.getValue()).isNull();
      assertThat(candidates.getValue())
          .containsExactly(
              net.firedevops.firemud.accountservice.service.session
                  .AccountSelectedGameplayAuthorityProjectionTest.membershipBytes(
                  ACCOUNT_ID, fixture.tenant, "1", "2", "1"),
              currentMembership);
    }

    @Test
    void selectedPublicationRejectsInterpublicationGapInRetainedPages() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.publishAuthorityProjections(
              eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
          .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
          .thenReturn(new byte[][] {null, null});

      var eventOne =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.member(
              ACCOUNT_ID, fixture.tenant, "1", "2", "1");
      var eventThree =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.member(
              ACCOUNT_ID, fixture.tenant, "3", "4", "1");
      String stream = eventOne.outboxStreamKey();
      var rowOne =
          new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
              .Event(
              stream,
              eventOne.requestId(),
              1L,
              eventOne.eventId(),
              eventOne.eventDigest(),
              eventOne.canonicalJsonUtf8());
      var rowThree =
          new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
              .Event(
              stream,
              eventThree.requestId(),
              3L,
              eventThree.eventId(),
              eventThree.eventDigest(),
              eventThree.canonicalJsonUtf8());
      when(fixture.member.getMembershipVersion()).thenReturn(4L);
      when(fixture.outbox.readCheckpoint(stream))
          .thenReturn(
              Optional.of(
                  new net.firedevops.firemud.accountservice.repository
                      .AccountAuthorityOutboxRepository.Checkpoint(
                      stream, 3L, eventThree.eventId(), eventThree.eventDigest())));
      when(fixture.outbox.findEvent(stream, 3L)).thenReturn(Optional.of(rowThree));
      when(fixture.outbox.findEventPage(stream, 0L, 3L)).thenReturn(List.of(rowOne, rowThree));
      var tenantEvent =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.tenant(fixture.tenant);
      var provenance =
          new net.firedevops.firemud.accountservice.repository
              .AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance(
              null,
              net.firedevops.firemud.accountservice.repository
                  .AccountMembershipPairAuthorityRepository.TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvent.sourceEvidence().operationId(),
              tenantEvent.sourceEvidence().evidenceDigest());
      when(fixture.pairs.readForUpdate(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              Optional.of(
                  new net.firedevops.firemud.accountservice.repository
                      .AccountMembershipPairAuthorityRepository.PairAuthority(
                      ACCOUNT_ID,
                      fixture.tenant,
                      provenance,
                      true,
                      4L,
                      1L,
                      3L,
                      eventThree.eventId(),
                      eventThree.eventDigest(),
                      false)));

      assertThatThrownBy(
              () -> fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.times(1))
          .publishSelectedAuthorityEventPage(
              eq(ACCOUNT_ID),
              eq(fixture.tenant),
              eq(true),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              any(List.class));
      org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.never())
          .publishSelectedAuthorityEventPage(
              eq(ACCOUNT_ID),
              eq(fixture.tenant),
              eq(false),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              any(List.class));
    }

    @Test
    void exactCachedSelectedHeadsSkipRetainedPrefixReadsAndCas() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.publishAuthorityProjections(
              eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
          .thenReturn(
              AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);
      when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              new byte[][] {fixture.values[2], fixture.values[3]},
              new byte[][] {fixture.values[2], fixture.values[3]});

      var result = fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant);

      assertThat(result.publicationOutcome())
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.EXACT_RETRY);
      org.mockito.Mockito.verify(fixture.outbox, org.mockito.Mockito.never())
          .findEventPage(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyLong());
      org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.never())
          .publishSelectedAuthorityEventPage(
              any(UUID.class),
              any(UUID.class),
              org.mockito.ArgumentMatchers.anyBoolean(),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              any(List.class));
    }

    @Test
    void selectedPublicationRejectsCachedHeadThatDiffersFromRetainedEvent() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.publishAuthorityProjections(
              eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
          .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      byte[] conflictingHead =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.membershipBytes(
              ACCOUNT_ID, fixture.tenant, "1", "3", "1");
      when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
          .thenReturn(new byte[][] {fixture.values[2], conflictingHead});

      assertThatThrownBy(
              () -> fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verify(fixture.outbox, org.mockito.Mockito.never())
          .findEventPage(
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.anyLong(),
              org.mockito.ArgumentMatchers.anyLong());
      org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.never())
          .publishSelectedAuthorityEventPage(
              any(UUID.class),
              any(UUID.class),
              org.mockito.ArgumentMatchers.anyBoolean(),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              any(List.class));
    }

    @Test
    void selectedPublicationContinuesBoundedPagesAcross128RetainedEvents() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.publishAuthorityProjections(
              eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
          .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      String stream = "account:auth-authority:v1:membership/" + ACCOUNT_ID + "/" + fixture.tenant;
      java.util.List<
              net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                  .Event>
          firstPage = new java.util.ArrayList<>();
      java.util.List<
              net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                  .Event>
          secondPage = new java.util.ArrayList<>();
      net.firedevops.firemud.common.account.authority.MembershipAuthorityEventV1Codec
              .MembershipEvent
          lastEvent = null;
      for (long sequence = 1L; sequence <= 130L; sequence++) {
        var event =
            net.firedevops.firemud.accountservice.service.session
                .AccountSelectedGameplayAuthorityProjectionTest.member(
                ACCOUNT_ID,
                fixture.tenant,
                Long.toString(sequence),
                Long.toString(sequence + 1L),
                "1");
        var row =
            new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                .Event(
                stream,
                event.requestId(),
                sequence,
                event.eventId(),
                event.eventDigest(),
                event.canonicalJsonUtf8());
        if (sequence <= 128L) firstPage.add(row);
        else secondPage.add(row);
        lastEvent = event;
      }
      when(fixture.member.getMembershipVersion()).thenReturn(131L);
      when(fixture.outbox.readCheckpoint(stream))
          .thenReturn(
              Optional.of(
                  new net.firedevops.firemud.accountservice.repository
                      .AccountAuthorityOutboxRepository.Checkpoint(
                      stream, 130L, lastEvent.eventId(), lastEvent.eventDigest())));
      var lastRow = secondPage.getLast();
      when(fixture.outbox.findEvent(stream, 130L)).thenReturn(Optional.of(lastRow));
      when(fixture.outbox.findEventPage(stream, 0L, 130L)).thenReturn(firstPage);
      when(fixture.outbox.findEventPage(stream, 128L, 130L)).thenReturn(secondPage);
      var tenantEvent =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.tenant(fixture.tenant);
      var provenance =
          new net.firedevops.firemud.accountservice.repository
              .AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance(
              null,
              net.firedevops.firemud.accountservice.repository
                  .AccountMembershipPairAuthorityRepository.TenantProvenanceKind.FRESH_GAME_DESIGN,
              tenantEvent.sourceEvidence().operationId(),
              tenantEvent.sourceEvidence().evidenceDigest());
      when(fixture.pairs.readForUpdate(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              Optional.of(
                  new net.firedevops.firemud.accountservice.repository
                      .AccountMembershipPairAuthorityRepository.PairAuthority(
                      ACCOUNT_ID,
                      fixture.tenant,
                      provenance,
                      true,
                      131L,
                      1L,
                      130L,
                      lastEvent.eventId(),
                      lastEvent.eventDigest(),
                      false)));
      byte[] currentMembership =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.membershipBytes(
              ACCOUNT_ID, fixture.tenant, "130", "131", "1");
      when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              new byte[][] {null, null}, new byte[][] {fixture.values[2], currentMembership});

      var result = fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant);

      assertThat(result.publicationOutcome())
          .isEqualTo(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      org.mockito.Mockito.verify(fixture.outbox).findEventPage(stream, 0L, 130L);
      org.mockito.Mockito.verify(fixture.outbox).findEventPage(stream, 128L, 130L);
      @SuppressWarnings("unchecked")
      org.mockito.ArgumentCaptor<List<byte[]>> pageCaptor =
          (org.mockito.ArgumentCaptor<List<byte[]>>)
              (org.mockito.ArgumentCaptor<?>) org.mockito.ArgumentCaptor.forClass(List.class);
      org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.times(2))
          .publishSelectedAuthorityEventPage(
              eq(ACCOUNT_ID),
              eq(fixture.tenant),
              eq(false),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              pageCaptor.capture());
      assertThat(pageCaptor.getAllValues().stream().map(List::size).toList())
          .containsExactly(128, 2);
    }

    @Test
    void selectedPublicationRejectsTamperedOrOutOfOrderRetainedRows() {
      for (boolean outOfOrder : List.of(false, true)) {
        SelectedOwnerFixture fixture = new SelectedOwnerFixture();
        when(fixture.redis.publishAuthorityProjections(
                eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
            .thenReturn(
                AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
        when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
            .thenReturn(new byte[][] {null, null});
        var event =
            net.firedevops.firemud.accountservice.service.session
                .AccountSelectedGameplayAuthorityProjectionTest.member(
                ACCOUNT_ID, fixture.tenant, "1", "2", "1");
        var row =
            new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                .Event(
                event.outboxStreamKey(),
                event.requestId(),
                outOfOrder ? 2L : 1L,
                event.eventId(),
                outOfOrder ? event.eventDigest() : event.eventDigest() + "-tampered",
                event.canonicalJsonUtf8());
        when(fixture.outbox.findEventPage(event.outboxStreamKey(), 0L, 1L))
            .thenReturn(List.of(row));

        assertThatThrownBy(
                () -> fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant))
            .isInstanceOf(
                AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
        org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.times(1))
            .publishSelectedAuthorityEventPage(
                any(UUID.class),
                any(UUID.class),
                org.mockito.ArgumentMatchers.anyBoolean(),
                org.mockito.ArgumentMatchers.nullable(byte[].class),
                any(List.class));
      }
    }

    @Test
    void selectedPublicationRereadsOwnerCurrentnessAfterCasReplay() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.publishAuthorityProjections(
              eq(ACCOUNT_ID), any(byte[].class), any(byte[].class)))
          .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      when(fixture.redis.readSelectedAuthorityHeads(ACCOUNT_ID, fixture.tenant))
          .thenReturn(
              new byte[][] {null, null}, new byte[][] {fixture.values[2], fixture.values[3]});
      when(fixture.redis.publishSelectedAuthorityEventPage(
              any(UUID.class),
              any(UUID.class),
              org.mockito.ArgumentMatchers.anyBoolean(),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              any(List.class)))
          .thenAnswer(
              invocation -> {
                @SuppressWarnings("unchecked")
                List<byte[]> candidates = invocation.getArgument(4);
                String candidate = new String(candidates.getLast(), StandardCharsets.UTF_8);
                if (candidate.contains("\"accountId\"")) {
                  when(fixture.member.getMembershipVersion()).thenReturn(3L);
                }
                return AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED;
              });

      assertThatThrownBy(
              () -> fixture.projection.publishSelectedCurrent(ACCOUNT_ID, fixture.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verify(fixture.redis, org.mockito.Mockito.times(2))
          .publishSelectedAuthorityEventPage(
              any(UUID.class),
              any(UUID.class),
              org.mockito.ArgumentMatchers.anyBoolean(),
              org.mockito.ArgumentMatchers.nullable(byte[].class),
              any(List.class));
    }

    @Test
    void selectedReachableMissingProjectionNeverUsesSqlAsRedisProof() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture();
      when(fixture.redis.readSelectedAuthorityProjections(ACCOUNT_ID, fixture.tenant))
          .thenReturn(new byte[][] {fixture.values[0], fixture.values[1], fixture.values[2], null});
      assertThatThrownBy(
              () -> fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.StaleProjectionException.class);
    }

    @Test
    void historicalActiveMembershipRemainsValidAfterProvedClosedAccountSecurityChange() {
      UUID tenant =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.TENANT;
      var tenantEvent = tenantEventWithDistinctBillingAndAuthorityCoordinates(tenant);
      SelectedOwnerFixture fixture = new SelectedOwnerFixture(tenant, 1L, 2L, tenantEvent);
      when(fixture.redis.readSelectedAuthorityProjections(ACCOUNT_ID, fixture.tenant))
          .thenReturn(fixture.values);
      var observation = fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant);
      Map<String, Object> current = decodeFields(observation.canonicalCurrentAuthorityTuple());
      assertThat(current.get("issuerAuthGeneration")).isEqualTo("1");
      assertThat(current.get("accountAuthorityGeneration")).isEqualTo("2");
      assertThat(current.get("accountSecurityCutoff"))
          .isEqualTo(
              Map.of(
                  "accountAuthorityGeneration",
                  "2",
                  "outboxStreamKey",
                  "account:auth-authority:v1:account/" + ACCOUNT_ID,
                  "outboxSequence",
                  "1"));
      assertThat(current.get("tenantBillingCutoff"))
          .isEqualTo(
              Map.of(
                  fixture.tenant.toString(),
                  Map.of(
                      "tenantAuthorityGeneration",
                      "2",
                      "tenantBillingSequence",
                      "1",
                      "outboxStreamKey",
                      tenantEvent.outboxStreamKey(),
                      "outboxSequence",
                      Long.toString(tenantEvent.outboxSequence()))));
      assertThat(tenantEvent.outboxStreamKey()).isNotEqualTo(tenantEvent.tenantBillingStreamKey());
      assertThat(tenantEvent.outboxSequence()).isNotEqualTo(tenantEvent.tenantBillingSequence());
      assertThat(observation.issuanceFence()).isEqualTo(2L);
      assertThat(observation.membershipVersion()).isEqualTo(2L);
      assertThat(observation.membershipAuthorityGeneration()).isEqualTo(1L);
      byte[] unchangedMember =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.membershipBytes(
              ACCOUNT_ID, fixture.tenant, "1", "2", "1");
      assertThat(observation.canonicalProjections()[3]).containsExactly(unchangedMember);
      assertThat(new String(unchangedMember, java.nio.charset.StandardCharsets.UTF_8))
          .contains("\\\"accountAuthorityGeneration\\\":\\\"1\\\"");
      assertThat(observation.admitting()).isFalse();
    }

    @Test
    void identicalUuidInIndependentAccountAndTenantDomainsIsNotRejected() {
      SelectedOwnerFixture fixture = new SelectedOwnerFixture(ACCOUNT_ID, 1L, 1L);
      when(fixture.redis.readSelectedAuthorityProjections(ACCOUNT_ID, fixture.tenant))
          .thenReturn(fixture.values);
      assertThat(fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant).admitting())
          .isFalse();
    }

    @Test
    void currentContradictoryCompositeOrUnsupportedClosedAccountSourceStillDenies() {
      SelectedOwnerFixture contradictory = new SelectedOwnerFixture();
      when(contradictory.sources.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
          .thenReturn(
              snapshot(1L, 2L)); // The actual composite still proves 1, not this claimed head.
      assertThatThrownBy(
              () ->
                  contradictory.projection.observeSelectedCurrent(ACCOUNT_ID, contradictory.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verifyNoInteractions(contradictory.redis);
      SelectedOwnerFixture unsupported =
          new SelectedOwnerFixture(
              net.firedevops.firemud.accountservice.service.session
                  .AccountSelectedGameplayAuthorityProjectionTest.TENANT,
              1L,
              2L);
      var advanced = snapshot(1L, 2L);
      when(unsupported.sources.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
          .thenReturn(
              new IssuerAccountSourceSnapshot(
                  advanced.issuer(), advanced.account(), advanced.issuanceFence()));
      assertThatThrownBy(
              () -> unsupported.projection.observeSelectedCurrent(ACCOUNT_ID, unsupported.tenant))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      org.mockito.Mockito.verifyNoInteractions(unsupported.redis);
    }

    private static TenantAuthorityEventV1Codec.Event
        tenantEventWithDistinctBillingAndAuthorityCoordinates(UUID tenant) {
      var original =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.tenant(tenant);
      return TenantAuthorityEventV1Codec.seal(
          new TenantAuthorityEventV1Codec.AuthorityRequest(
              original.requestId(),
              original.requestDigest(),
              tenant,
              original.sourceEvidence().creationRequestId(),
              original.sourceEvidence().requestDigest()),
          original.sourceEvidence(),
          original.tenantAuthorityGeneration(),
          original.tenantAuthoritySourceVersion(),
          original.outboxSequence() + 3L,
          new TenantAuthorityEventV1Codec.BillingReceipt(
              tenant,
              original.requestId(),
              original.requestDigest(),
              original.tenantBillingStreamKey(),
              original.tenantBillingSequence(),
              original.tenantBillingEventId(),
              original.tenantBillingEventDigest()));
    }

    private static final class SelectedOwnerFixture {
      final UUID tenant;
      final AccountAuthoritySourceEvidenceRepository sources =
          mock(AccountAuthoritySourceEvidenceRepository.class);
      final net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
          generations =
              mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountAuthorityGenerationRepository.class);
      final net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository
          memberships =
              mock(
                  net.firedevops.firemud.accountservice.repository.AccountTenantMembershipRepository
                      .class);
      final net.firedevops.firemud.accountservice.repository
              .AccountMembershipPairAuthorityRepository
          pairs =
              mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountMembershipPairAuthorityRepository.class);
      final net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
          outbox =
              mock(
                  net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                      .class);
      final net.firedevops.firemud.accountservice.repository.AccountTenantAuthorityEventRepository
          tenantSources =
              mock(
                  net.firedevops.firemud.accountservice.repository
                      .AccountTenantAuthorityEventRepository.class);
      final net.firedevops.firemud.accountservice.entity.AccountTenantMembership member =
          mock(net.firedevops.firemud.accountservice.entity.AccountTenantMembership.class);
      final AccountGameplayDelegationRedisClient redis =
          mock(AccountGameplayDelegationRedisClient.class);
      final AccountGameplayDelegationAuthorityProjection projection;
      final byte[][] values;

      SelectedOwnerFixture() {
        this(
            net.firedevops.firemud.accountservice.service.session
                .AccountSelectedGameplayAuthorityProjectionTest.TENANT,
            1L,
            1L);
      }

      SelectedOwnerFixture(UUID tenant, long issuerGeneration, long accountGeneration) {
        this(
            tenant,
            issuerGeneration,
            accountGeneration,
            net.firedevops.firemud.accountservice.service.session
                .AccountSelectedGameplayAuthorityProjectionTest.tenant(tenant));
      }

      SelectedOwnerFixture(
          UUID tenant,
          long issuerGeneration,
          long accountGeneration,
          TenantAuthorityEventV1Codec.Event tenantEvent) {
        this.tenant = tenant;
        var source = snapshot(issuerGeneration, accountGeneration);
        when(sources.readCurrentIssuerAccountSources(
                GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
            .thenReturn(source);
        var composite =
            new net.firedevops.firemud.accountservice.repository
                .AccountAuthorityGenerationRepository.CompositeSnapshot(
                new net.firedevops.firemud.accountservice.repository
                    .AccountAuthorityGenerationRepository.ScopeState(
                    source.issuer().scope(), issuerGeneration, issuerGeneration, null),
                new net.firedevops.firemud.accountservice.repository
                    .AccountAuthorityGenerationRepository.ScopeState(
                    source.account().scope(),
                    accountGeneration,
                    accountGeneration,
                    source.issuanceFence()),
                List.of(
                    new net.firedevops.firemud.accountservice.repository
                        .AccountAuthorityGenerationRepository.ScopeState(
                        AuthorityScope.tenant(tenant), 2L, 2L, null)),
                List.of(
                    new net.firedevops.firemud.accountservice.repository
                        .AccountAuthorityGenerationRepository.ScopeState(
                        AuthorityScope.membership(ACCOUNT_ID, tenant), 1L, 1L, null)),
                source.issuanceFence());
        when(generations.readCompositeSnapshot(
                GameSessionAccountDelegationProfile.ISSUER,
                ACCOUNT_ID,
                List.of(tenant),
                List.of(tenant)))
            .thenReturn(composite);
        when(tenantSources.readCurrentByTenant(tenant)).thenReturn(tenantEvent);
        var event =
            net.firedevops.firemud.accountservice.service.session
                .AccountSelectedGameplayAuthorityProjectionTest.member(
                ACCOUNT_ID, tenant, "1", "2", "1");
        var provenance =
            new net.firedevops.firemud.accountservice.repository
                .AccountMembershipPairAuthorityRepository.VerifiedTenantProvenance(
                null,
                net.firedevops.firemud.accountservice.repository
                    .AccountMembershipPairAuthorityRepository.TenantProvenanceKind
                    .FRESH_GAME_DESIGN,
                tenantEvent.sourceEvidence().operationId(),
                tenantEvent.sourceEvidence().evidenceDigest());
        var pair =
            new net.firedevops.firemud.accountservice.repository
                .AccountMembershipPairAuthorityRepository.PairAuthority(
                ACCOUNT_ID,
                tenant,
                provenance,
                true,
                2L,
                1L,
                1L,
                event.eventId(),
                event.eventDigest(),
                false);
        when(pairs.readForUpdate(ACCOUNT_ID, tenant)).thenReturn(Optional.of(pair));
        when(memberships.findFreshMembershipForUpdate(ACCOUNT_ID, tenant))
            .thenReturn(Optional.of(member));
        when(member.getMembershipVersion()).thenReturn(2L);
        when(member.getMembershipAuthorityGeneration()).thenReturn(1L);
        when(member.getLifecycleState()).thenReturn("ACTIVE");
        when(member.isGameplayAdmissionAllowed()).thenReturn(true);
        when(outbox.readCheckpoint(event.outboxStreamKey()))
            .thenReturn(
                Optional.of(
                    new net.firedevops.firemud.accountservice.repository
                        .AccountAuthorityOutboxRepository.Checkpoint(
                        event.outboxStreamKey(), 1L, event.eventId(), event.eventDigest())));
        var memberRow =
            new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                .Event(
                event.outboxStreamKey(),
                event.requestId(),
                1L,
                event.eventId(),
                event.eventDigest(),
                event.canonicalJsonUtf8());
        when(outbox.findEvent(event.outboxStreamKey(), 1L)).thenReturn(Optional.of(memberRow));
        when(outbox.findEventPage(event.outboxStreamKey(), 0L, 1L)).thenReturn(List.of(memberRow));
        when(outbox.readCheckpoint(tenantEvent.outboxStreamKey()))
            .thenReturn(
                Optional.of(
                    new net.firedevops.firemud.accountservice.repository
                        .AccountAuthorityOutboxRepository.Checkpoint(
                        tenantEvent.outboxStreamKey(),
                        tenantEvent.outboxSequence(),
                        tenantEvent.eventId().toString(),
                        tenantEvent.eventDigest())));
        var tenantRow =
            new net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository
                .Event(
                tenantEvent.outboxStreamKey(),
                tenantEvent.requestId().toString(),
                tenantEvent.outboxSequence(),
                tenantEvent.eventId().toString(),
                tenantEvent.eventDigest(),
                tenantEvent.payload());
        when(outbox.findEvent(tenantEvent.outboxStreamKey(), tenantEvent.outboxSequence()))
            .thenReturn(Optional.of(tenantRow));
        when(outbox.findEventPage(tenantEvent.outboxStreamKey(), 0L, tenantEvent.outboxSequence()))
            .thenReturn(List.of(tenantRow));
        values =
            net.firedevops.firemud.accountservice.service.session
                .AccountSelectedGameplayAuthorityProjectionTest.selected(
                canonicalProjectionPairForFixture(source), tenantEvent, event, 1L);
        projection =
            new AccountGameplayDelegationAuthorityProjection(
                sources,
                transactionManager(null),
                redis,
                generations,
                memberships,
                pairs,
                outbox,
                tenantSources);
      }

      private static byte[][] canonicalProjectionPairForFixture(
          IssuerAccountSourceSnapshot source) {
        return AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
      }
    }

    @Test
    void canonicalIssuerBaselineRejectsGenericAliasesAndAdvancedLocalSource() throws Exception {
      byte[] issuer =
          AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot(1, 1))[0];
      var canonical =
          net.firedevops.firemud.accountservice.service.IssuerGenerationProjection.parse(
              new String(issuer, java.nio.charset.StandardCharsets.UTF_8));
      assertThat(canonical.lastAppliedSourceOutboxSequence()).isEqualTo("0");
      assertThat(decodeFields(issuer))
          .doesNotContainKeys("schema", "scope", "digest", "checkpoint");
      Map<String, Object> altered = new LinkedHashMap<>(decodeFields(issuer));
      altered.put("schema", "account-auth-authority-projection/v1");
      assertThatThrownBy(
              () ->
                  AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
                      JSON.writeValueAsBytes(altered),
                      "issuer",
                      GameSessionAccountDelegationProfile.ISSUER))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      assertThatThrownBy(
              () ->
                  AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(
                      snapshot(2, 1)))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
    }

    @Test
    void freshAccountUsesOnlyCanonicalClosedAccountFields() {
      byte[][] pair =
          AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot(1, 1));
      Map<String, Object> fields = decodeFields(pair[1]);
      assertThat(fields.get("schemaVersion")).isEqualTo(AccountGenerationProjection.SCHEMA_VERSION);
      assertThat(fields.get("outboxSequence")).isEqualTo("0");
      assertThat(fields).doesNotContainKeys("sourceEvent", "issuanceFence", "digest", "generation");
      assertThat(
              AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
                      pair[1], "account", ACCOUNT_ID.toString())
                  .generation())
          .isEqualTo(1L);
    }

    @Test
    void advancedAccountRetainsCompleteClosedSourceEventAndExactBytes() {
      var source = snapshot(1, Long.MAX_VALUE);
      byte[][] pair = AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
      assertThat(new String(pair[1], java.nio.charset.StandardCharsets.UTF_8))
          .isEqualTo(source.canonicalAccountProjection().toJson());
      var decoded =
          AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
              pair[1], "account", ACCOUNT_ID.toString());
      assertThat(decoded.generationValue()).isEqualTo(BigInteger.valueOf(Long.MAX_VALUE));
      assertThat(decodeFields(pair[1]).get("sourceEvent"))
          .isEqualTo(source.canonicalAccountProjection().sourceEvent().orElseThrow());
    }

    @Test
    void rejectsLegacyAccountSchemaUnknownFieldsWrongIdentityAndMissingEvent() throws Exception {
      byte[] account =
          AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(snapshot(1, 11))[1];
      for (String field : List.of("issuanceFence", "digest", "generation")) {
        Map<String, Object> altered = new LinkedHashMap<>(decodeFields(account));
        altered.put(field, "1");
        assertThatThrownBy(
                () ->
                    AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
                        JSON.writeValueAsString(altered)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "account",
                        ACCOUNT_ID.toString()))
            .isInstanceOf(
                AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      }
      Map<String, Object> missing = new LinkedHashMap<>(decodeFields(account));
      missing.remove("sourceEvent");
      assertThatThrownBy(
              () ->
                  AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
                      JSON.writeValueAsString(missing)
                          .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      "account",
                      ACCOUNT_ID.toString()))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
      assertThatThrownBy(
              () ->
                  AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
                      account, "account", UUID.randomUUID().toString()))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
    }

    @Test
    void advancedSnapshotWithoutExactSourcePayloadFailsClosed() {
      var complete = snapshot(1, 11);
      var missing =
          new IssuerAccountSourceSnapshot(
              complete.issuer(), complete.account(), complete.issuanceFence());
      assertThatThrownBy(
              () -> AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(missing))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.ProjectionUnavailableException.class);
    }

    @Test
    void canonicalAccountDecodePreservesArbitraryPrecisionCounters() throws Exception {
      var base = snapshot(1, 11).canonicalAccountProjection();
      Map<String, Object> event =
          new LinkedHashMap<>(
              JSON.readValue(
                  base.sourceEvent().orElseThrow(),
                  new tools.jackson.core.type.TypeReference<Map<String, Object>>() {}));
      String generation = "92233720368547758081234567890";
      String sequence = "92233720368547758081234567889";
      event.remove("eventDigest");
      event.put("accountAuthorityGeneration", generation);
      event.put("sourceVersion", generation);
      event.put("outboxSequence", sequence);
      event.put(
          "accountSecurityCutoff",
          Map.of(
              "accountAuthorityGeneration",
              generation,
              "outboxStreamKey",
              base.outboxStreamKey(),
              "outboxSequence",
              sequence));
      var projection =
          new AccountGenerationProjection(
              ACCOUNT_ID.toString(),
              generation,
              generation,
              base.outboxStreamKey(),
              sequence,
              Optional.of(AccountSecurityStateAuthorityEventV1Codec.seal(event).canonicalJson()));
      var decoded =
          AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
              projection.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
              "account",
              ACCOUNT_ID.toString());
      assertThat(decoded.generationValue()).isEqualTo(new BigInteger(generation));
    }

    @Test
    void rejectsPublicationWhenOwnerSourceAdvancesAcrossRedisWrite() {
      var sources = mock(AccountAuthoritySourceEvidenceRepository.class);
      var redis = mock(AccountGameplayDelegationRedisClient.class);
      when(sources.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
          .thenReturn(snapshot(1, 11), snapshot(1, 12));
      when(redis.publishAuthorityProjections(any(UUID.class), any(byte[].class), any(byte[].class)))
          .thenReturn(AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED);
      var projection =
          new AccountGameplayDelegationAuthorityProjection(
              sources, transactionManager(null), redis);
      assertThatThrownBy(() -> projection.publishCurrent(ACCOUNT_ID))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.StaleProjectionException.class);
    }

    @Test
    void rejectsProjectionObservationWhenOwnerSourceAdvancesAcrossRedisRead() throws Exception {
      AccountAuthoritySourceEvidenceRepository sourceEvidence =
          mock(AccountAuthoritySourceEvidenceRepository.class);
      AccountGameplayDelegationRedisClient redis = mock(AccountGameplayDelegationRedisClient.class);
      when(sourceEvidence.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
          .thenReturn(snapshot(1L, 11L), snapshot(1L, 12L));
      when(redis.readAuthorityProjections(ACCOUNT_ID))
          .thenReturn(
              AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(
                  snapshot(1L, 11L)));
      AccountGameplayDelegationAuthorityProjection projection =
          new AccountGameplayDelegationAuthorityProjection(
              sourceEvidence, transactionManager(null), redis);

      assertThatThrownBy(() -> projection.observeCurrent(ACCOUNT_ID))
          .isInstanceOf(
              AccountGameplayDelegationAuthorityProjection.StaleProjectionException.class);
    }

    @Test
    void publishesOnlyAfterOwnerSnapshotTransactionHasCompleted() {
      AccountAuthoritySourceEvidenceRepository sourceEvidence =
          mock(AccountAuthoritySourceEvidenceRepository.class);
      AccountGameplayDelegationRedisClient redis = mock(AccountGameplayDelegationRedisClient.class);
      AtomicBoolean transactionActive = new AtomicBoolean();
      PlatformTransactionManager transactionManager = transactionManager(transactionActive);
      when(sourceEvidence.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
          .thenReturn(snapshot(1L, 11L));
      when(redis.publishAuthorityProjections(any(UUID.class), any(byte[].class), any(byte[].class)))
          .thenAnswer(
              invocation -> {
                assertThat(transactionActive.get()).isFalse();
                return AccountGameplayDelegationRedisClient.ProjectionPublicationOutcome.PUBLISHED;
              });
      AccountGameplayDelegationAuthorityProjection projection =
          new AccountGameplayDelegationAuthorityProjection(
              sourceEvidence, transactionManager, redis);

      assertThat(projection.publishCurrent(ACCOUNT_ID).accountGeneration()).isEqualTo(11L);
      assertThat(transactionActive.get()).isFalse();
    }

    static IssuerAccountSourceSnapshot snapshot(long issuerGeneration, long accountGeneration) {
      String issuerStream =
          "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER;
      String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
      IssuanceFence fence = new IssuanceFence(ACCOUNT_ID, accountGeneration, accountGeneration);
      CurrentSourceEvidence issuer =
          new CurrentSourceEvidence(
              AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER),
              issuerGeneration,
              issuerGeneration,
              null,
              checkpoint(issuerStream, issuerGeneration - 1L),
              Optional.empty(),
              "ISSUER_SCOPE_INSERT",
              null,
              null,
              5L,
              null);
      Optional<AccountSecurityCutoff> cutoff =
          accountGeneration == 1L
              ? Optional.empty()
              : Optional.of(
                  new AccountSecurityCutoff(
                      Long.toString(accountGeneration),
                      accountStream,
                      Long.toString(accountGeneration - 1L)));
      CurrentSourceEvidence account =
          new CurrentSourceEvidence(
              AuthorityScope.account(ACCOUNT_ID),
              accountGeneration,
              accountGeneration,
              fence,
              checkpoint(accountStream, accountGeneration - 1L),
              cutoff,
              "ACCOUNT_REPOSITORY_INSERT",
              42L,
              "ACCOUNT_REPOSITORY_INSERT",
              6L,
              6L);
      String event = null;
      if (accountGeneration > 1) {
        String generation = Long.toString(accountGeneration);
        String sequence = Long.toString(accountGeneration - 1);
        String request = "11111111-1111-4111-8111-111111111111";
        event =
            AccountSecurityStateAuthorityEventV1Codec.seal(
                    Map.ofEntries(
                        Map.entry(
                            "schemaVersion",
                            AccountSecurityStateAuthorityEventV1Codec.SCHEMA_VERSION),
                        Map.entry(
                            "eventType", AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
                        Map.entry(
                            "eventId",
                            AccountSecurityStateAuthorityEventV1Codec.EVENT_ID_PREFIX + request),
                        Map.entry("requestId", request),
                        Map.entry("accountId", ACCOUNT_ID.toString()),
                        Map.entry("sourceScope", "account/" + ACCOUNT_ID),
                        Map.entry("outboxStreamKey", accountStream),
                        Map.entry("outboxSequence", sequence),
                        Map.entry("accountAuthorityGeneration", generation),
                        Map.entry("sourceVersion", generation),
                        Map.entry(
                            "accountSecurityCutoff",
                            Map.of(
                                "accountAuthorityGeneration",
                                generation,
                                "outboxStreamKey",
                                accountStream,
                                "outboxSequence",
                                sequence)),
                        Map.entry("mutationKinds", List.of("EMAIL_LOGIN_ELIGIBILITY_CHANGED")),
                        Map.entry(
                            "accountState",
                            Map.of(
                                "emailVerified",
                                true,
                                "loginAuthModes",
                                List.of("EMAIL_OTP", "PASSWORD"),
                                "globalRoles",
                                List.of(),
                                "lifecycleState",
                                "ACTIVE"))))
                .canonicalJson();
      }
      return new IssuerAccountSourceSnapshot(
          issuer,
          account,
          fence,
          new AccountGenerationProjection(
              ACCOUNT_ID.toString(),
              Long.toString(accountGeneration),
              Long.toString(accountGeneration),
              accountStream,
              Long.toString(accountGeneration - 1),
              Optional.ofNullable(event)));
    }

    private static SourceCheckpoint checkpoint(String stream, long sequence) {
      return sequence == 0L
          ? new SourceCheckpoint(stream, 0L, Optional.empty(), Optional.empty())
          : new SourceCheckpoint(
              stream,
              sequence,
              Optional.of("event-" + sequence),
              Optional.of("sha256:" + "a".repeat(64)));
    }

    private static Map<String, Object> decodeFields(byte[] value) {
      try {
        return JSON.readValue(value, new tools.jackson.core.type.TypeReference<>() {});
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    }

    private static byte[] canonical(Object value) throws Exception {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    }

    private static String digestWithoutDigest(Map<String, Object> fields) throws Exception {
      Map<String, Object> preimage = new LinkedHashMap<>(fields);
      preimage.remove("digest");
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(preimage)));
    }

    private static Map<String, Object> mutableMap(Object value) {
      if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException();
      Map<String, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : source.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException();
        result.put(key, entry.getValue());
      }
      return result;
    }

    private static PlatformTransactionManager transactionManager(AtomicBoolean active) {
      PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
      TransactionStatus status = mock(TransactionStatus.class);
      when(manager.getTransaction(any(TransactionDefinition.class)))
          .thenAnswer(
              invocation -> {
                if (active != null) active.set(true);
                return status;
              });
      doAnswer(
              invocation -> {
                if (active != null) active.set(false);
                return null;
              })
          .when(manager)
          .commit(status);
      doAnswer(
              invocation -> {
                if (active != null) active.set(false);
                return null;
              })
          .when(manager)
          .rollback(status);
      return manager;
    }
  }
}
