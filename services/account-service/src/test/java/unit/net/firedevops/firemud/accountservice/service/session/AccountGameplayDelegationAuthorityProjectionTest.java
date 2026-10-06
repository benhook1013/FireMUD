package unit.net.firedevops.firemud.accountservice.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationAuthorityProjectionTest {
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
    assertThatThrownBy(() -> fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant))
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
    assertThatThrownBy(() -> fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void selectedReachableMissingProjectionNeverUsesSqlAsRedisProof() {
    SelectedOwnerFixture fixture = new SelectedOwnerFixture();
    when(fixture.redis.readSelectedAuthorityProjections(ACCOUNT_ID, fixture.tenant))
        .thenReturn(new byte[][] {fixture.values[0], fixture.values[1], fixture.values[2], null});
    assertThatThrownBy(() -> fixture.projection.observeSelectedCurrent(ACCOUNT_ID, fixture.tenant))
        .isInstanceOf(AccountGameplayDelegationAuthorityProjection.StaleProjectionException.class);
  }

  @Test
  void historicalActiveMembershipRemainsValidAfterProvedClosedAccountSecurityChange() {
    SelectedOwnerFixture fixture =
        new SelectedOwnerFixture(
            net.firedevops.firemud.accountservice.service.session
                .AccountSelectedGameplayAuthorityProjectionTest.TENANT,
            1L,
            2L);
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
                    "account:tenant-entitlement:v1:tenant/" + fixture.tenant,
                    "outboxSequence",
                    "1")));
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
            () -> contradictory.projection.observeSelectedCurrent(ACCOUNT_ID, contradictory.tenant))
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
    final net.firedevops.firemud.accountservice.repository.AccountMembershipPairAuthorityRepository
        pairs =
            mock(
                net.firedevops.firemud.accountservice.repository
                    .AccountMembershipPairAuthorityRepository.class);
    final net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository outbox =
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
      this.tenant = tenant;
      var source = snapshot(issuerGeneration, accountGeneration);
      when(sources.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
          .thenReturn(source);
      var composite =
          new net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository
              .CompositeSnapshot(
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
      var tenantEvent =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.tenant(tenant);
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
                  .AccountMembershipPairAuthorityRepository.TenantProvenanceKind.FRESH_GAME_DESIGN,
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
      when(outbox.findEvent(event.outboxStreamKey(), 1L))
          .thenReturn(
              Optional.of(
                  new net.firedevops.firemud.accountservice.repository
                      .AccountAuthorityOutboxRepository.Event(
                      event.outboxStreamKey(),
                      event.requestId(),
                      1L,
                      event.eventId(),
                      event.eventDigest(),
                      event.canonicalJsonUtf8())));
      values =
          net.firedevops.firemud.accountservice.service.session
              .AccountSelectedGameplayAuthorityProjectionTest.selected(
              canonicalProjectionPairForFixture(source), ACCOUNT_ID, tenant);
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

    private static byte[][] canonicalProjectionPairForFixture(IssuerAccountSourceSnapshot source) {
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
    assertThat(decodeFields(issuer)).doesNotContainKeys("schema", "scope", "digest", "checkpoint");
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
        new AccountGameplayDelegationAuthorityProjection(sources, transactionManager(null), redis);
    assertThatThrownBy(() -> projection.publishCurrent(ACCOUNT_ID))
        .isInstanceOf(AccountGameplayDelegationAuthorityProjection.StaleProjectionException.class);
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
        .isInstanceOf(AccountGameplayDelegationAuthorityProjection.StaleProjectionException.class);
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
        new AccountGameplayDelegationAuthorityProjection(sourceEvidence, transactionManager, redis);

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
                      Map.entry("eventType", AccountSecurityStateAuthorityEventV1Codec.EVENT_TYPE),
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
