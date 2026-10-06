package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AccountIdentitySource;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AuthoritySourceVersions;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.BundleReference;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OperationIdentity;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OutboxCheckpoint;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OwnerEvaluation;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.TokenIdentity;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthEvidenceBundleRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.AuthorityScope;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityGenerationRepository.IssuanceFence;
import net.firedevops.firemud.accountservice.repository.AccountAuthorityOutboxRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CanonicalIssuerSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingSigningIdentity;
import net.firedevops.firemud.accountservice.service.AccountGenerationProjection;
import net.firedevops.firemud.accountservice.service.IssuerGenerationProjection;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.IssuerPreimage;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationIssuanceRepositoryTest {
  @Test
  void committedProjectionProofRequiresCanonicalAccountBytesNotRecipientFenceSchema()
      throws Exception {
    var account =
        new CurrentSourceEvidence(
            AuthorityScope.account(ACCOUNT_ID),
            1L,
            1L,
            new IssuanceFence(ACCOUNT_ID, 1L, 1L),
            new SourceCheckpoint(
                "account:auth-authority:v1:account/" + ACCOUNT_ID,
                0L,
                Optional.empty(),
                Optional.empty()),
            Optional.empty(),
            "ACCOUNT_REPOSITORY_INSERT",
            42L,
            "ACCOUNT_REPOSITORY_INSERT",
            2L,
            2L);
    String issuerStream =
        "account:auth-authority:v1:issuer/" + GameSessionAccountDelegationProfile.ISSUER;
    String issuerRequestId = "be305426-4ea8-4f73-a571-4ef89b7ae864";
    var issuerEvent =
        AccountAuthoritySourceEventV1Codec.sealIssuer(
            new IssuerPreimage(
                issuerRequestId,
                issuerRequestId,
                issuerStream,
                "6",
                GameSessionAccountDelegationProfile.ISSUER,
                "7",
                "7",
                "SIGNER_COMPROMISE"));
    var issuer =
        new CurrentSourceEvidence(
            AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER),
            7L,
            7L,
            null,
            new SourceCheckpoint(
                issuerStream,
                6L,
                Optional.of(issuerEvent.eventId()),
                Optional.of(issuerEvent.eventDigest())),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            7L,
            null);
    var storedIssuerEvent =
        new AccountAuthorityOutboxRepository.Event(
            issuerStream,
            issuerRequestId,
            6L,
            issuerEvent.eventId(),
            issuerEvent.eventDigest(),
            issuerEvent.canonicalJsonUtf8());
    var issuerProjection =
        IssuerGenerationProjection.fromSource(
            new CanonicalIssuerSourceSnapshot(issuer, Optional.of(storedIssuerEvent)));
    var accountProjection =
        new AccountGenerationProjection(
            ACCOUNT_ID.toString(),
            "1",
            "1",
            account.checkpoint().outboxStreamKey(),
            "0",
            Optional.empty());
    var source =
        new IssuerAccountSourceSnapshot(
            issuer, account, account.issuanceFence(), accountProjection, issuerProjection);
    var pair =
        net.firedevops.firemud.accountservice.service.session
            .AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(source);
    var issuer =
        net.firedevops.firemud.accountservice.service.session
            .AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            pair[0], "issuer", GameSessionAccountDelegationProfile.ISSUER);
    var canonicalAccount =
        net.firedevops.firemud.accountservice.service.session
            .AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            pair[1], "account", ACCOUNT_ID.toString());
    Method method =
        AccountGameplayDelegationIssuanceRepository.class.getDeclaredMethod(
            "requireExactProjectionObservation",
            net.firedevops.firemud.accountservice.service.session
                .AccountGameplayDelegationAuthorityProjection.ProjectionObservation.class,
            IssuerAccountSourceSnapshot.class);
    method.setAccessible(true);
    method.invoke(
        null,
        new net.firedevops.firemud.accountservice.service.session
            .AccountGameplayDelegationAuthorityProjection.ProjectionObservation(
            issuer.digest(), canonicalAccount.digest(), 7L, 1L),
        source);
    assertThatThrownBy(
            () ->
                method.invoke(
                    null,
                    new net.firedevops.firemud.accountservice.service.session
                        .AccountGameplayDelegationAuthorityProjection.ProjectionObservation(
                        issuer.digest(), "b".repeat(64), 7L, 1L),
                    source))
        .hasCauseInstanceOf(
            AccountGameplayDelegationIssuanceRepository.StaleAuthorityException.class);
  }

  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID OPERATION_ID = UUID.fromString("5414e55d-0393-4561-ac3d-cb916a08d3f0");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final UUID JTI = UUID.fromString("2921ba03-bf74-49ac-b24b-a9255f5de308");
  private static final Instant NOW = Instant.parse("2027-01-15T12:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void requestDigestBindsTheExactCallerContextAndImmutableTokenIdentity() {
    PendingIntent intent = intent(authoritySnapshot(11L));
    PendingIntent changedCaller =
        new PendingIntent(
            OPERATION_ID,
            REQUEST_ID,
            "spiffe://firemud/ns/test/sa/game-session-service",
            UUID.fromString("c46b58cf-4204-468b-a499-2de0eb8ce5fc"),
            JTI,
            NOW.getEpochSecond(),
            NOW.getEpochSecond(),
            NOW.plusSeconds(120).getEpochSecond(),
            intent.authoritySnapshot(),
            AccountGameplayCredentialRequestBindingFixture.binding());

    assertThat(AccountGameplayDelegationIssuanceRepository.requestDigest(intent))
        .isEqualTo(AccountGameplayDelegationIssuanceRepository.requestDigest(intent))
        .isNotEqualTo(AccountGameplayDelegationIssuanceRepository.requestDigest(changedCaller));
    assertThat(intent.toString()).doesNotContain(ACCOUNT_ID.toString(), JTI.toString());
  }

  @Test
  void currentCommittedOwnerReadRequiresWritableAccountTransactionBeforeStorageAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(dsl, sources);

    assertThatThrownBy(() -> repository.readCurrentCommittedCandidate(REQUEST_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account transaction");

    verifyNoInteractions(dsl, sources);
  }

  @Test
  void currentGameplayLoginReplayRequiresItsOwnerWritableTransaction() throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(dsl, sources);
    Method method =
        AccountGameplayDelegationIssuanceRepository.class.getMethod(
            "readCurrentGameplayLoginReplay", UUID.class);
    Transactional transaction = method.getAnnotation(Transactional.class);

    assertThat(transaction).isNotNull();
    assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(transaction.readOnly()).isFalse();
    assertThatThrownBy(() -> repository.readCurrentGameplayLoginReplay(REQUEST_ID))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account transaction");
    verifyNoInteractions(dsl, sources);
  }

  @Test
  void currentGameplayLoginReplayRejectsCommittedRowWithoutCanonicalCommitProof() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    IssuerAccountSourceSnapshot current = sources();
    PendingIntent intent = intent(authoritySnapshot(11L));
    Record committedWithoutProof =
        operationRecord(intent, AccountGameplayDelegationIssuanceRepository.requestDigest(intent));
    when(committedWithoutProof.get("status", String.class)).thenReturn("COMMITTED");
    when(committedWithoutProof.get("token_hash", String.class)).thenReturn("a".repeat(64));
    when(dsl.fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(committedWithoutProof, committedWithoutProof);
    when(sources.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(current);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(
            dsl, sources, Clock.fixed(NOW, ZoneOffset.UTC));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> repository.readCurrentGameplayLoginReplay(REQUEST_ID))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.StorageUnavailableException.class);

    verify(sources)
        .readCurrentIssuerAccountSources(GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID);
  }

  @Test
  void committedVerificationDataCanOnlyComeFromTheMandatoryOwnerRead() throws Exception {
    Method readMethod =
        AccountGameplayDelegationIssuanceRepository.class.getMethod(
            "readCurrentCommittedCandidate", UUID.class);
    Transactional transaction = readMethod.getAnnotation(Transactional.class);

    assertThat(transaction).isNotNull();
    assertThat(transaction.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(
            Arrays.stream(
                    AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData
                        .class
                        .getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())))
        .isTrue();
  }

  @Test
  void requestDigestCanonicallyBindsTheApplicableAccountSecurityCutoff() throws Exception {
    PendingIntent intent = intent(authoritySnapshot(11L));
    Map<String, Object> tuple =
        GameSessionAccountDelegationProfile.authorityTuple(
            intent.authoritySnapshot().issuerGeneration(),
            intent.authoritySnapshot().accountGeneration(),
            intent.authoritySnapshot().accountSecurityCutoff());
    assertThat(tuple).containsKey("accountSecurityCutoff");

    Map<String, Object> preimage = digestPreimage(intent, tuple);
    assertThat(preimage.get("issuanceFence"))
        .isEqualTo(Long.toString(intent.authoritySnapshot().issuanceFence()));
    assertThat(preimage.get("iat")).isInstanceOf(Number.class);
    assertThat(((Map<?, ?>) preimage.get("authorityTuple")).get("issuerAuthGeneration"))
        .isEqualTo(Long.toString(intent.authoritySnapshot().issuerGeneration()));
    Map<String, Object> tupleWithoutCutoff =
        new LinkedHashMap<>(
            GameSessionAccountDelegationProfile.authorityTuple(
                intent.authoritySnapshot().issuerGeneration(), 1L));
    tupleWithoutCutoff.put(
        "accountAuthorityGeneration", intent.authoritySnapshot().accountGeneration());
    Map<String, Object> cutoffOmitted = digestPreimage(intent, tupleWithoutCutoff);

    assertThat(AccountGameplayDelegationIssuanceRepository.requestDigest(intent))
        .isEqualTo(digest(preimage))
        .isNotEqualTo(digest(cutoffOmitted));
  }

  @Test
  void staleOrTenantScopedAuthorityCannotCreateDurableIntent() {
    DSLContext dsl = mock(DSLContext.class);
    when(dsl.fetchOne(
            "SELECT account_uuid FROM accounts WHERE account_uuid = ? FOR UPDATE", ACCOUNT_ID))
        .thenReturn(mock(Record.class));
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    when(sources.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(sources());
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(
            dsl, sources, Clock.fixed(NOW, ZoneOffset.UTC));
    AccountAuthoritySnapshot stale = authoritySnapshot(10L);
    PendingIntent intent = intent(stale);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> repository.beginPending(intent))
        .isInstanceOf(AccountGameplayDelegationIssuanceRepository.StaleAuthorityException.class);
    verify(dsl, org.mockito.Mockito.never())
        .execute(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class));
  }

  @Test
  void requiresAnOwnerWritableTransactionBeforeAnySqlAccess() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(
            dsl, sources, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> repository.beginPending(intent(authoritySnapshot(11L))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account transaction");
    verifyNoInteractions(dsl, sources);
  }

  @Test
  void pendingCandidateReadJoinsSigningTransactionBeforeResponseSealing() throws Exception {
    Method readMethod =
        AccountGameplayDelegationIssuanceRepository.class.getMethod(
            "readPendingRegistryCandidate", UUID.class);
    Transactional transaction = readMethod.getAnnotation(Transactional.class);

    assertThat(transaction).isNotNull();
    assertThat(transaction.propagation()).isEqualTo(Propagation.REQUIRED);
  }

  @Test
  void commitRequiresTheCallerWritableTransactionAndRetryReadUsesItsOwnShortTransaction()
      throws Exception {
    Method commitMethod =
        AccountGameplayDelegationIssuanceRepository.class.getMethod(
            "commitPendingCandidate",
            UUID.class,
            net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner
                .PendingCandidateVerificationProof.class,
            net.firedevops.firemud.accountservice.service.session
                .AccountGameplayDelegationRedisClient.PendingRegistrationReceipt.class,
            net.firedevops.firemud.accountservice.service.session
                .AccountGameplayDelegationAuthorityProjection.ProjectionObservation.class,
            net.firedevops.firemud.accountservice.service.session
                .AccountGameplayDelegationIssuanceCommitService.CommitProof.class);
    Method readMethod =
        AccountGameplayDelegationIssuanceRepository.class.getMethod(
            "readCommittedProof", UUID.class);
    Transactional commitTransaction = commitMethod.getAnnotation(Transactional.class);
    Transactional readTransaction = readMethod.getAnnotation(Transactional.class);

    assertThat(commitTransaction).isNotNull();
    assertThat(commitTransaction.propagation()).isEqualTo(Propagation.MANDATORY);
    assertThat(readTransaction).isNotNull();
    assertThat(readTransaction.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    assertThat(readTransaction.readOnly()).isTrue();
  }

  @Test
  void commitCannotStartWithoutWritableAccountTransactionOrTouchSql() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(
            dsl, sources, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> repository.commitPendingCandidate(REQUEST_ID, null, null, null, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("writable Account transaction");
    verifyNoInteractions(dsl, sources);
  }

  @Test
  void pendingSigningReadReturnsPersistedFullBundleAfterOwnerAndOperationLocks() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountAuthEvidenceBundleRepository bundles = mock(AccountAuthEvidenceBundleRepository.class);
    IssuerAccountSourceSnapshot current = sources();
    AccountAuthoritySnapshot authority = authoritySnapshot(11L);
    PendingIntent intent = intent(authority);
    String digest = AccountGameplayDelegationIssuanceRepository.requestDigest(intent);
    Record preliminary = operationRecord(intent, digest);
    Record locked = operationRecord(intent, digest);
    when(dsl.fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(preliminary, locked);
    when(sources.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(current);
    AccountAuthEvidenceBundle bundle = evidenceBundle(intent, current);
    assertSigningBundleMatchesOwnerSnapshot(bundle, intent, authority, current);
    when(bundles.captureAndPersist(REQUEST_ID)).thenReturn(bundle);
    when(bundles.readStoredNonAuthorizingValue(OPERATION_ID)).thenReturn(bundle);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(
            dsl, sources, bundles, Clock.fixed(NOW, ZoneOffset.UTC));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    PendingSigningIdentity result = repository.readPendingSigningIdentity(REQUEST_ID);

    assertThat(result.identity().operationId()).isEqualTo(OPERATION_ID);
    assertThat(result.identity().requestId()).isEqualTo(REQUEST_ID);
    assertThat(result.identity().callerContextId()).isEqualTo(CALLER_CONTEXT_ID);
    assertThat(result.identity().tokenJti()).isEqualTo(JTI);
    assertThat(result.authoritySnapshot()).isEqualTo(authority);
    assertThat(result.evidenceBundle()).isEqualTo(bundle);
    assertThat(result.toString()).doesNotContain(REQUEST_ID.toString(), JTI.toString());
    var order = inOrder(dsl, sources, bundles);
    order
        .verify(dsl)
        .fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class));
    order
        .verify(sources)
        .readCurrentIssuerAccountSources(GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID);
    order
        .verify(dsl)
        .fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class));
    order.verify(bundles).captureAndPersist(REQUEST_ID);
    order.verify(bundles).readStoredNonAuthorizingValue(OPERATION_ID);
  }

  @Test
  void pendingSigningReadRejectsBundleForDifferentCallerAndDoesNotIssueIdentity() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountAuthEvidenceBundleRepository bundles = mock(AccountAuthEvidenceBundleRepository.class);
    IssuerAccountSourceSnapshot current = sources();
    PendingIntent intent = intent(authoritySnapshot(11L));
    String digest = AccountGameplayDelegationIssuanceRepository.requestDigest(intent);
    Record preliminary = operationRecord(intent, digest);
    Record locked = operationRecord(intent, digest);
    when(dsl.fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(preliminary, locked);
    when(sources.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(current);
    UUID changedContext = UUID.fromString("c46b58cf-4204-468b-a499-2de0eb8ce5fc");
    AccountAuthEvidenceBundle wrongCaller = evidenceBundle(intent, current, changedContext);
    when(bundles.captureAndPersist(REQUEST_ID)).thenReturn(wrongCaller);
    when(bundles.readStoredNonAuthorizingValue(OPERATION_ID)).thenReturn(wrongCaller);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(
            dsl, sources, bundles, Clock.fixed(NOW, ZoneOffset.UTC));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> repository.readPendingSigningIdentity(REQUEST_ID))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.PendingCandidateUnavailableException.class);
    verify(bundles).readStoredNonAuthorizingValue(OPERATION_ID);
  }

  @Test
  void pendingSigningReadRejectsBundleWithStaleIssuanceFence() {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sources =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountAuthEvidenceBundleRepository bundles = mock(AccountAuthEvidenceBundleRepository.class);
    IssuerAccountSourceSnapshot current = sources();
    AccountAuthoritySnapshot authority = authoritySnapshot(11L);
    PendingIntent intent = intent(authority);
    String digest = AccountGameplayDelegationIssuanceRepository.requestDigest(intent);
    Record preliminary = operationRecord(intent, digest);
    Record locked = operationRecord(intent, digest);
    when(dsl.fetchOne(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(Object[].class)))
        .thenReturn(preliminary, locked);
    when(sources.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(current);
    long staleFence = Math.addExact(current.issuanceFence().value(), 1L);
    AccountAuthEvidenceBundle staleFenceBundle =
        evidenceBundle(intent, current, CALLER_CONTEXT_ID, staleFence);
    when(bundles.captureAndPersist(REQUEST_ID)).thenReturn(staleFenceBundle);
    when(bundles.readStoredNonAuthorizingValue(OPERATION_ID)).thenReturn(staleFenceBundle);
    AccountGameplayDelegationIssuanceRepository repository =
        new AccountGameplayDelegationIssuanceRepository(
            dsl, sources, bundles, Clock.fixed(NOW, ZoneOffset.UTC));
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

    assertThatThrownBy(() -> repository.readPendingSigningIdentity(REQUEST_ID))
        .isInstanceOf(
            AccountGameplayDelegationIssuanceRepository.PendingCandidateUnavailableException.class);
    verify(bundles).readStoredNonAuthorizingValue(OPERATION_ID);
  }

  private static PendingIntent intent(AccountAuthoritySnapshot authority) {
    return new PendingIntent(
        OPERATION_ID,
        REQUEST_ID,
        "spiffe://firemud/ns/test/sa/game-session-service",
        CALLER_CONTEXT_ID,
        JTI,
        NOW.getEpochSecond(),
        NOW.getEpochSecond(),
        NOW.plusSeconds(120).getEpochSecond(),
        authority,
        AccountGameplayCredentialRequestBindingFixture.binding());
  }

  private static AccountAuthoritySnapshot authoritySnapshot(long accountGeneration) {
    String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    return new AccountAuthoritySnapshot(
        ACCOUNT_ID,
        7L,
        7L,
        accountGeneration,
        accountGeneration,
        accountGeneration,
        accountGeneration,
        Optional.of(
            new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                Long.toString(accountGeneration),
                accountStream,
                Long.toString(accountGeneration - 1L))));
  }

  private static IssuerAccountSourceSnapshot sources() {
    String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
    String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    IssuanceFence fence = new IssuanceFence(ACCOUNT_ID, 11L, 11L);
    CurrentSourceEvidence issuer =
        new CurrentSourceEvidence(
            AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER),
            7L,
            7L,
            null,
            checkpoint(issuerStream, 6L),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            1L,
            null);
    CurrentSourceEvidence account =
        new CurrentSourceEvidence(
            AuthorityScope.account(ACCOUNT_ID),
            11L,
            11L,
            fence,
            checkpoint(accountStream, 10L),
            Optional.of(new AccountSecurityCutoff("11", accountStream, "10")),
            "ACCOUNT_REPOSITORY_INSERT",
            42L,
            "ACCOUNT_REPOSITORY_INSERT",
            2L,
            2L);
    return new IssuerAccountSourceSnapshot(issuer, account, fence);
  }

  private static SourceCheckpoint checkpoint(String streamKey, long sequence) {
    return new SourceCheckpoint(
        streamKey,
        sequence,
        Optional.of("source-event-" + sequence),
        Optional.of("sha256:" + "a".repeat(64)));
  }

  private static Map<String, Object> digestPreimage(
      PendingIntent intent, Map<String, Object> authorityTuple) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("schema", "account-game-session-delegation-issuance/v2");
    value.put("operationId", intent.operationId().toString());
    value.put("requestId", intent.requestId().toString());
    value.put("accountId", intent.authoritySnapshot().accountId().toString());
    value.put("callerWorkload", intent.callerWorkload());
    value.put("callerContextId", intent.callerContextId().toString());
    value.put(
        "credentialRequestBinding",
        Map.of(
            "digestSchemaVersion", intent.credentialRequestBinding().digestSchemaVersion(),
            "digestKeyId", intent.credentialRequestBinding().digestKeyId(),
            "credentialRequestDigest",
            intent.credentialRequestBinding().credentialRequestDigest()));
    value.put("jti", intent.tokenJti().toString());
    value.put("iat", intent.issuedAtEpochSecond());
    value.put("nbf", intent.notBeforeEpochSecond());
    value.put("exp", intent.expiresAtEpochSecond());
    value.put("authorityTuple", authorityTuple);
    value.put("membershipVersion", Map.of());
    value.put("issuanceFence", Long.toString(intent.authoritySnapshot().issuanceFence()));
    value.put("authoritySourceVersions", intent.authoritySnapshot().sourceVersions());
    return value;
  }

  private static Record operationRecord(PendingIntent intent, String digest) {
    Record row = mock(Record.class);
    when(row.get("operation_id", UUID.class)).thenReturn(intent.operationId());
    when(row.get("request_id", UUID.class)).thenReturn(intent.requestId());
    when(row.get("account_uuid", UUID.class)).thenReturn(ACCOUNT_ID);
    when(row.get("caller_workload", String.class)).thenReturn(intent.callerWorkload());
    when(row.get("caller_context_id", UUID.class)).thenReturn(intent.callerContextId());
    when(row.get("request_digest_version", Short.class)).thenReturn((short) 2);
    when(row.get("request_digest", String.class)).thenReturn(digest);
    when(row.get("credential_request_digest_version", Short.class))
        .thenReturn((short) intent.credentialRequestBinding().digestSchemaVersion());
    when(row.get("credential_digest_key_id", String.class))
        .thenReturn(intent.credentialRequestBinding().digestKeyId());
    when(row.get("credential_request_digest", String.class))
        .thenReturn(intent.credentialRequestBinding().credentialRequestDigest());
    when(row.get("token_jti", UUID.class)).thenReturn(intent.tokenJti());
    when(row.get("token_generation", Long.class)).thenReturn(1L);
    when(row.get("issued_at_epoch_second", Long.class)).thenReturn(intent.issuedAtEpochSecond());
    when(row.get("not_before_epoch_second", Long.class)).thenReturn(intent.notBeforeEpochSecond());
    when(row.get("expires_at_epoch_second", Long.class)).thenReturn(intent.expiresAtEpochSecond());
    when(row.get("authority_issuer_generation", Long.class)).thenReturn(7L);
    when(row.get("authority_issuer_source_version", Long.class)).thenReturn(7L);
    when(row.get("authority_account_generation", Long.class)).thenReturn(11L);
    when(row.get("authority_account_source_version", Long.class)).thenReturn(11L);
    when(row.get("issuance_fence", Long.class)).thenReturn(11L);
    when(row.get("issuance_fence_source_version", Long.class)).thenReturn(11L);
    when(row.get("authority_tuple_canonical_bytes", byte[].class))
        .thenReturn(
            canonicalJson(
                GameSessionAccountDelegationProfile.authorityTuple(
                    7L, 11L, intent.authoritySnapshot().accountSecurityCutoff())));
    when(row.get("membership_version_canonical_bytes", byte[].class))
        .thenReturn("{}".getBytes(StandardCharsets.US_ASCII));
    when(row.get("authority_source_versions_canonical_bytes", byte[].class))
        .thenReturn(canonicalJson(intent.authoritySnapshot().sourceVersions()));
    when(row.get("status", String.class)).thenReturn("PENDING");
    return row;
  }

  private static AccountAuthEvidenceBundle evidenceBundle(
      PendingIntent intent, IssuerAccountSourceSnapshot current) {
    return evidenceBundle(intent, current, CALLER_CONTEXT_ID);
  }

  private static AccountAuthEvidenceBundle evidenceBundle(
      PendingIntent intent, IssuerAccountSourceSnapshot current, UUID callerContextId) {
    return evidenceBundle(intent, current, callerContextId, current.issuanceFence().value());
  }

  private static AccountAuthEvidenceBundle evidenceBundle(
      PendingIntent intent,
      IssuerAccountSourceSnapshot current,
      UUID callerContextId,
      long issuanceFence) {
    String issuerStream = current.issuer().checkpoint().outboxStreamKey();
    String accountStream = current.account().checkpoint().outboxStreamKey();
    Optional<GameSessionAccountDelegationProfile.AccountSecurityCutoff> currentCutoff =
        current
            .account()
            .accountSecurityCutoff()
            .map(
                cutoff ->
                    new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                        cutoff.accountAuthorityGeneration(),
                        cutoff.outboxStreamKey(),
                        cutoff.outboxSequence()));
    return AccountAuthEvidenceBundle.fromOwnerEvaluation(
        new OwnerEvaluation(
            new BundleReference("1", "11", "9", "3"),
            "b".repeat(64),
            "c".repeat(64),
            ACCOUNT_ID,
            new OperationIdentity(
                OPERATION_ID,
                REQUEST_ID,
                AccountGameplayDelegationIssuanceRepository.requestDigest(intent),
                intent.callerWorkload(),
                callerContextId,
                ACCOUNT_ID),
            new TokenIdentity(
                JTI,
                1L,
                intent.issuedAtEpochSecond(),
                intent.notBeforeEpochSecond(),
                intent.expiresAtEpochSecond()),
            GameSessionAccountDelegationProfile.authorityTuple(
                current.issuer().generation(), current.account().generation(), currentCutoff),
            issuanceFence,
            new AuthoritySourceVersions(
                current.issuer().sourceVersion(),
                current.account().sourceVersion(),
                current.issuanceFence().sourceVersion()),
            new AccountIdentitySource(
                current.account().accountSourceNumericId(),
                AccountIdentityProvenance.valueOf(current.account().accountUuidProvenance()),
                current.account().accountSourceNumericId()),
            java.util.List.of(
                new OutboxCheckpoint(
                    accountStream,
                    current.account().checkpoint().sequence(),
                    current.account().checkpoint().sourceEventId().orElseThrow(),
                    current.account().checkpoint().sourceEventDigest().orElseThrow()),
                new OutboxCheckpoint(
                    issuerStream,
                    current.issuer().checkpoint().sequence(),
                    current.issuer().checkpoint().sourceEventId().orElseThrow(),
                    current.issuer().checkpoint().sourceEventDigest().orElseThrow()))));
  }

  private static void assertSigningBundleMatchesOwnerSnapshot(
      AccountAuthEvidenceBundle bundle,
      PendingIntent intent,
      AccountAuthoritySnapshot authority,
      IssuerAccountSourceSnapshot current) {
    Map<String, Object> fields = bundle.fields();
    assertThat(fields.get("schema"))
        .as("bundle schema")
        .isEqualTo(AccountAuthEvidenceBundle.SCHEMA);
    assertThat(fields.get("issuer"))
        .as("bundle issuer")
        .isEqualTo(GameSessionAccountDelegationProfile.ISSUER);
    assertThat(fields.get("profile"))
        .as("bundle profile")
        .isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
    assertThat(fields.get("audience"))
        .as("bundle audience")
        .isEqualTo(GameSessionAccountDelegationProfile.AUDIENCE);

    assertCanonicalBundleSubtree(
        fields.get("scope"),
        Map.of(
            "kind", "account",
            "accountId", intent.authoritySnapshot().accountId().toString(),
            "tenantIds", java.util.List.of()),
        "scope");
    assertCanonicalBundleSubtree(
        fields.get("operation"),
        Map.of(
            "operationId", intent.operationId().toString(),
            "requestId", intent.requestId().toString(),
            "requestDigest", AccountGameplayDelegationIssuanceRepository.requestDigest(intent),
            "callerWorkload", intent.callerWorkload(),
            "callerContextId", intent.callerContextId().toString(),
            "accountId", intent.authoritySnapshot().accountId().toString()),
        "operation");
    assertCanonicalBundleSubtree(
        fields.get("tokenIdentity"),
        Map.of(
            "jti", intent.tokenJti().toString(),
            "tokenGeneration", "1",
            "iat", intent.issuedAtEpochSecond(),
            "nbf", intent.notBeforeEpochSecond(),
            "exp", intent.expiresAtEpochSecond()),
        "token identity");
    assertCanonicalBundleSubtree(
        fields.get("authorityTuple"),
        GameSessionAccountDelegationProfile.authorityTuple(
            current.issuer().generation(),
            current.account().generation(),
            current
                .account()
                .accountSecurityCutoff()
                .map(
                    cutoff ->
                        new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                            cutoff.accountAuthorityGeneration(),
                            cutoff.outboxStreamKey(),
                            cutoff.outboxSequence()))),
        "authority tuple and Account cutoff");
    assertCanonicalBundleSubtree(fields.get("membershipVersion"), Map.of(), "membership version");
    assertThat(fields.get("issuanceFence"))
        .as("persisted bundle issuance fence is its canonical decimal string")
        .isEqualTo(Long.toString(authority.issuanceFence()));
    assertCanonicalBundleSubtree(
        fields.get("authoritySourceVersions"),
        Map.of(
            "issuerSourceVersion", Long.toString(authority.issuerSourceVersion()),
            "accountSourceVersion", Long.toString(authority.accountSourceVersion()),
            "issuanceFenceSourceVersion", Long.toString(authority.issuanceFenceSourceVersion())),
        "authority source versions");
    assertCanonicalBundleSubtree(
        fields.get("accountIdentitySource"),
        Map.of(
            "sourceRowId", Long.toString(current.account().accountSourceNumericId()),
            "provenance", current.account().accountUuidProvenance(),
            "sourceNumericId", Long.toString(current.account().accountSourceNumericId())),
        "Account identity provenance");
    assertCanonicalBundleSubtree(
        fields.get("outboxCheckpoints"),
        java.util.List.of(
            checkpointFields(current.account().checkpoint()),
            checkpointFields(current.issuer().checkpoint())),
        "exact owner outbox checkpoints");
  }

  private static Map<String, Object> checkpointFields(SourceCheckpoint checkpoint) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("outboxStreamKey", checkpoint.outboxStreamKey());
    value.put("outboxSequence", Long.toString(checkpoint.sequence()));
    if (checkpoint.sequence() > 0L) {
      value.put("sourceEventId", checkpoint.sourceEventId().orElseThrow());
      value.put("sourceEventDigest", checkpoint.sourceEventDigest().orElseThrow());
    }
    return Map.copyOf(value);
  }

  private static void assertCanonicalBundleSubtree(Object actual, Object expected, String label) {
    assertThat(Arrays.equals(canonicalJson(actual), canonicalJson(expected)))
        .as("persisted bundle %s matches exact owner readback", label)
        .isTrue();
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String digest(Map<String, Object> preimage) throws Exception {
    byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(preimage));
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
  }
}
