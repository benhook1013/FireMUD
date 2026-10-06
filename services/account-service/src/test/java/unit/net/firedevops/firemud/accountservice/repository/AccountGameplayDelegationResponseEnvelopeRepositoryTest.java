package unit.net.firedevops.firemud.accountservice.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.SourceCheckpoint;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingIntent;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner.ActiveCommittedIssuanceObservation;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.Binding;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeKeyring;
import net.firedevops.firemud.common.account.authority.AccountAuthoritySourceEventV1Codec.AccountSecurityCutoff;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

class AccountGameplayDelegationResponseEnvelopeRepositoryTest {
  private static final UUID ACCOUNT_ID = UUID.fromString("4cae05e8-7a6b-4b14-9d44-665e3eec450b");
  private static final UUID OPERATION_ID = UUID.fromString("5414e55d-0393-4561-ac3d-cb916a08d3f0");
  private static final UUID REQUEST_ID = UUID.fromString("1ee95a1e-83f2-4a63-a7ba-6288e246ac76");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("91d13625-0e03-4e46-b82e-18f69f091436");
  private static final UUID JTI = UUID.fromString("2921ba03-bf74-49ac-b24b-a9255f5de308");
  private static final String WORKLOAD = "spiffe://firemud/ns/test/sa/game-session-service";
  private static final Instant NOW = Instant.ofEpochSecond(Instant.now().getEpochSecond());
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @AfterEach
  void clearTransactionContext() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactRetryReturnsExistingSealedMetadataWithoutResealingOrReturningJwt() throws Exception {
    Fixture fixture = fixture(NOW);
    // The owner bundle and encrypted response are mocked components. This proves only local
    // binding/CAS/readback orchestration; it is not a source, signer, or successful-issuance proof.
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM account_gameplay_delegation_issuance_operations")) {
                return fixture.operationRow();
              }
              if (sql.contains("FROM accounts")) {
                assertThat(sql).contains("FOR SHARE").doesNotContain("FOR UPDATE");
                return fixture.accountRow();
              }
              if (sql.contains("FROM account_gameplay_delegation_response_envelopes")) {
                return fixture.envelopeReads().getAndIncrement() == 0
                    ? null
                    : fixture.envelopeRow();
              }
              throw new AssertionError("Unexpected SQL in component fixture");
            });
    when(fixture.dsl().execute(anyString(), any(Object[].class))).thenReturn(1);
    when(fixture.bundles().captureAndPersist(REQUEST_ID)).thenReturn(fixture.bundle());
    when(fixture.bundles().readStoredNonAuthorizingValue(OPERATION_ID))
        .thenReturn(fixture.bundle());
    when(fixture.crypto().encrypt(any(Binding.class), any(byte[].class), any(Instant.class)))
        .thenReturn(fixture.sealed());

    SealedCandidateObservation first =
        inWritableOwnerTransaction(
            () ->
                fixture
                    .repository()
                    .sealPendingCandidate(REQUEST_ID, fixture.caller(), fixture.compactJwt()));
    SealedCandidateObservation retry =
        inWritableOwnerTransaction(
            () ->
                fixture
                    .repository()
                    .sealPendingCandidate(REQUEST_ID, fixture.caller(), fixture.compactJwt()));

    assertThat(retry).isEqualTo(first);
    assertThat(first.operationId()).isEqualTo(OPERATION_ID);
    assertThat(first.requestId()).isEqualTo(REQUEST_ID);
    assertThat(first.responseRecoveryExpiryEpochMillis())
        .isEqualTo((NOW.getEpochSecond() + 120L) * 1_000L);
    assertThat(first.toString()).doesNotContain(fixture.compactJwt());
    verify(fixture.crypto(), times(1))
        .encrypt(any(Binding.class), any(byte[].class), any(Instant.class));
    verify(fixture.dsl(), times(1)).execute(anyString(), any(Object[].class));

    ArgumentCaptor<Binding> binding = ArgumentCaptor.forClass(Binding.class);
    verify(fixture.crypto()).encrypt(binding.capture(), any(byte[].class), any(Instant.class));
    assertThat(binding.getValue().operation())
        .isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
    assertThat(binding.getValue().requestId()).isEqualTo(REQUEST_ID.toString());
    assertThat(binding.getValue().callerWorkload()).isEqualTo(WORKLOAD);
    assertThat(binding.getValue().bindingId()).isEqualTo(OPERATION_ID.toString());
    assertThat(binding.getValue().lineageId()).isEmpty();
    assertThat(binding.getValue().replacementId()).isEmpty();
    assertThat(binding.getValue().leaseId()).isEmpty();
    assertThat(binding.getValue().issuanceFence()).isEqualTo(11L);
    assertThat(binding.getValue().authorityEvidenceBundleCanonicalBytes())
        .isEqualTo(fixture.bundle().canonicalBytes());
    assertThat(binding.getValue().membershipVersionMapCanonicalBytes())
        .isEqualTo("{}".getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void changedCallerOrCompactCandidateDeniesBeforeSourceReadOrEncryption() throws Exception {
    Fixture fixture = fixture(NOW);
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenReturn(fixture.operationRow());
    CallerIdentity wrongCaller =
        new CallerIdentity("spiffe://firemud/ns/other/sa/game-session-service", CALLER_CONTEXT_ID);
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        fixture
                            .repository()
                            .sealPendingCandidate(REQUEST_ID, wrongCaller, fixture.compactJwt())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException.class);

    CallerIdentity wrongContext =
        new CallerIdentity(WORKLOAD, UUID.fromString("c46b58cf-4204-468b-a499-2de0eb8ce5fc"));
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        fixture
                            .repository()
                            .sealPendingCandidate(REQUEST_ID, wrongContext, fixture.compactJwt())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException.class);

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        fixture
                            .repository()
                            .sealPendingCandidate(
                                REQUEST_ID, fixture.caller(), fixture.compactJwt() + "A")))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException.class);
    verifyNoInteractions(fixture.sourceEvidence(), fixture.bundles(), fixture.crypto());
    verify(fixture.dsl(), never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void committedRecoveryReturnsOnlyExactHashMatchedJwtAfterActiveOwnerEvidence() throws Exception {
    Fixture fixture = fixture(NOW);
    AccountGameplayDelegationIssuanceRepository issuance =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    CommittedCandidateVerificationData committed = committedCandidate(fixture);
    ActiveCommittedIssuanceObservation active = activeObservation(fixture);
    when(fixture.operationRow().get("status", String.class)).thenReturn("COMMITTED");
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM account_gameplay_delegation_issuance_operations")) {
                return fixture.operationRow();
              }
              if (sql.contains("FROM accounts")) return fixture.accountRow();
              if (sql.contains("FROM account_gameplay_delegation_response_envelopes")) {
                return fixture.envelopeRow();
              }
              throw new AssertionError("Unexpected recovery SQL in component fixture");
            });
    when(fixture
            .sourceEvidence()
            .readCurrentIssuerAccountSources(
                GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(fixture.snapshot());
    when(fixture.bundles().readStoredNonAuthorizingValue(OPERATION_ID))
        .thenReturn(fixture.bundle());
    when(issuance.readCurrentCommittedCandidate(REQUEST_ID)).thenReturn(committed);
    when(fixture
            .crypto()
            .decrypt(any(EncryptedResponseEnvelope.class), any(Binding.class), any(Instant.class)))
        .thenReturn(fixture.compactJwt().getBytes(StandardCharsets.US_ASCII));
    AccountGameplayDelegationResponseEnvelopeRepository repository =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            fixture.dsl(), fixture.sourceEvidence(), fixture.bundles(), issuance, fixture.crypto());

    var recovered =
        inWritableOwnerTransaction(
            () ->
                repository.recoverCommittedResponse(
                    REQUEST_ID, ACCOUNT_ID, fixture.caller(), active));

    assertThat(recovered.compactJwtBytes())
        .containsExactly(fixture.compactJwt().getBytes(StandardCharsets.US_ASCII));
    assertThat(recovered.accountId()).isEqualTo(ACCOUNT_ID);
    assertThat(recovered.tokenJti()).isEqualTo(JTI);
    assertThat(recovered.tokenSha256()).isEqualTo(fixture.candidate().tokenHash());
    assertThat(recovered.profile()).isEqualTo(GameSessionAccountDelegationProfile.PROFILE);
    assertThat(recovered.expiresAtEpochSecond()).isEqualTo(fixture.intent().expiresAtEpochSecond());
    assertThat(recovered.authEvidenceBundle()).isSameAs(fixture.bundle());
    assertThat(recovered.toString()).doesNotContain(fixture.compactJwt());
    verify(issuance).readCurrentCommittedCandidate(REQUEST_ID);
    verify(fixture.crypto())
        .decrypt(any(EncryptedResponseEnvelope.class), any(Binding.class), any(Instant.class));
  }

  @Test
  void committedRecoveryPreflightRejectsEveryCallerBindingMismatchBeforeOwnerReads()
      throws Exception {
    Fixture fixture = fixture(NOW);
    when(fixture.operationRow().get("status", String.class)).thenReturn("COMMITTED");
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenReturn(fixture.operationRow());
    AccountGameplayDelegationResponseEnvelopeRepository repository =
        new AccountGameplayDelegationResponseEnvelopeRepository(
            fixture.dsl(), fixture.sourceEvidence(), fixture.bundles(), fixture.crypto());

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        repository.preflightCommittedRecovery(
                            UUID.fromString("58102ea4-9245-4ddc-a68c-3ff41dcf96af"),
                            ACCOUNT_ID,
                            fixture.caller())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.OperationUnavailableException.class)
        .hasNoCause();
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        repository.preflightCommittedRecovery(
                            REQUEST_ID,
                            UUID.fromString("674cb504-8f4c-4d06-86c6-7b72f79cae74"),
                            fixture.caller())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException.class)
        .hasNoCause();
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        repository.preflightCommittedRecovery(
                            REQUEST_ID,
                            ACCOUNT_ID,
                            new CallerIdentity(
                                "spiffe://firemud/ns/other/sa/game-session-service",
                                CALLER_CONTEXT_ID))))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException.class)
        .hasNoCause();
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        repository.preflightCommittedRecovery(
                            REQUEST_ID,
                            ACCOUNT_ID,
                            new CallerIdentity(
                                WORKLOAD,
                                UUID.fromString("e46c59e8-4dd8-49a7-9d26-2db49f0d0ecb")))))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.IdempotencyConflictException.class)
        .hasNoCause();

    verifyNoInteractions(fixture.sourceEvidence(), fixture.bundles(), fixture.crypto());
  }

  @Test
  void committedRecoveryPreflightRejectsAnExpiredOriginalDeadlineBeforeOwnerReads()
      throws Exception {
    Fixture fixture = fixture(NOW.minusSeconds(121L));
    when(fixture.operationRow().get("status", String.class)).thenReturn("COMMITTED");
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenReturn(fixture.operationRow());

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        fixture
                            .repository()
                            .preflightCommittedRecovery(REQUEST_ID, ACCOUNT_ID, fixture.caller())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.ResponseRecoveryExpiredException
                .class)
        .hasNoCause();

    verifyNoInteractions(fixture.sourceEvidence(), fixture.bundles(), fixture.crypto());
  }

  @Test
  void committedRecoveryRejectsDecryptedDigestDriftAndMissingEnvelopeKey() throws Exception {
    Fixture digestFixture = fixture(NOW);
    RecoveryHarness digestHarness = recoveryHarness(digestFixture);
    byte[] alteredJwt = (digestFixture.compactJwt() + "A").getBytes(StandardCharsets.US_ASCII);
    when(digestFixture
            .crypto()
            .decrypt(any(EncryptedResponseEnvelope.class), any(Binding.class), any(Instant.class)))
        .thenReturn(alteredJwt);
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        digestHarness
                            .repository()
                            .recoverCommittedResponse(
                                REQUEST_ID,
                                ACCOUNT_ID,
                                digestFixture.caller(),
                                digestHarness.active())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.StorageUnavailableException.class)
        .hasNoCause();

    Fixture missingKeyFixture = fixture(NOW);
    RecoveryHarness missingKeyHarness = recoveryHarness(missingKeyFixture);
    AccountResponseEnvelopeKeyring.KeyUnavailableException missingKey =
        mock(AccountResponseEnvelopeKeyring.KeyUnavailableException.class);
    when(missingKeyFixture
            .crypto()
            .decrypt(any(EncryptedResponseEnvelope.class), any(Binding.class), any(Instant.class)))
        .thenThrow(missingKey);
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        missingKeyHarness
                            .repository()
                            .recoverCommittedResponse(
                                REQUEST_ID,
                                ACCOUNT_ID,
                                missingKeyFixture.caller(),
                                missingKeyHarness.active())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.StorageUnavailableException.class)
        .hasNoCause();
  }

  @Test
  void activeHashAndEnvelopeDigestDriftFailBeforeDecryption() throws Exception {
    Fixture fixture = fixture(NOW);
    RecoveryHarness harness = recoveryHarness(fixture);
    when(harness.active().tokenSha256()).thenReturn("f".repeat(64));

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        harness
                            .repository()
                            .recoverCommittedResponse(
                                REQUEST_ID, ACCOUNT_ID, fixture.caller(), harness.active())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.OwnerEvidenceUnavailableException
                .class)
        .hasNoCause();
    verify(fixture.crypto(), never())
        .decrypt(any(EncryptedResponseEnvelope.class), any(Binding.class), any(Instant.class));

    Fixture envelopeFixture = fixture(NOW);
    RecoveryHarness envelopeHarness = recoveryHarness(envelopeFixture);
    when(envelopeFixture.envelopeRow().get("envelope_sha256", String.class))
        .thenReturn("f".repeat(64));
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        envelopeHarness
                            .repository()
                            .recoverCommittedResponse(
                                REQUEST_ID,
                                ACCOUNT_ID,
                                envelopeFixture.caller(),
                                envelopeHarness.active())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.StorageUnavailableException.class)
        .hasNoCause();
    verify(envelopeFixture.crypto(), never())
        .decrypt(any(EncryptedResponseEnvelope.class), any(Binding.class), any(Instant.class));
  }

  @Test
  void expiryAndMissingOwnerBundleDenyWithoutCreatingOrExposingEnvelope() throws Exception {
    Fixture expired = fixture(NOW.minusSeconds(121L));
    when(expired.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenReturn(expired.operationRow());
    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        expired
                            .repository()
                            .sealPendingCandidate(
                                REQUEST_ID, expired.caller(), expired.compactJwt())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.ResponseRecoveryExpiredException
                .class);
    verifyNoInteractions(expired.sourceEvidence(), expired.bundles(), expired.crypto());
    verify(expired.dsl(), never()).execute(anyString(), any(Object[].class));

    Fixture missing = fixture(NOW);
    when(missing.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenReturn(missing.operationRow(), missing.operationRow(), missing.accountRow());
    when(missing
            .sourceEvidence()
            .readCurrentIssuerAccountSources(
                GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(missing.snapshot());
    when(missing.bundles().captureAndPersist(REQUEST_ID))
        .thenThrow(new AccountAuthEvidenceBundleRepository.OwnerEvidenceUnavailableException());

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        missing
                            .repository()
                            .sealPendingCandidate(
                                REQUEST_ID, missing.caller(), missing.compactJwt())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.OwnerEvidenceUnavailableException
                .class);
    verify(missing.crypto(), never())
        .encrypt(any(Binding.class), any(byte[].class), any(Instant.class));
    verify(missing.dsl(), never()).execute(anyString(), any(Object[].class));
  }

  private static RecoveryHarness recoveryHarness(Fixture fixture) throws Exception {
    AccountGameplayDelegationIssuanceRepository issuance =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    CommittedCandidateVerificationData committed = committedCandidate(fixture);
    ActiveCommittedIssuanceObservation active = activeObservation(fixture);
    when(fixture.operationRow().get("status", String.class)).thenReturn("COMMITTED");
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM account_gameplay_delegation_issuance_operations")) {
                return fixture.operationRow();
              }
              if (sql.contains("FROM accounts")) return fixture.accountRow();
              if (sql.contains("FROM account_gameplay_delegation_response_envelopes")) {
                return fixture.envelopeRow();
              }
              throw new AssertionError("Unexpected recovery SQL in component fixture");
            });
    when(fixture
            .sourceEvidence()
            .readCurrentIssuerAccountSources(
                GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(fixture.snapshot());
    when(fixture.bundles().readStoredNonAuthorizingValue(OPERATION_ID))
        .thenReturn(fixture.bundle());
    when(issuance.readCurrentCommittedCandidate(REQUEST_ID)).thenReturn(committed);
    when(fixture
            .crypto()
            .decrypt(any(EncryptedResponseEnvelope.class), any(Binding.class), any(Instant.class)))
        .thenReturn(fixture.compactJwt().getBytes(StandardCharsets.US_ASCII));
    return new RecoveryHarness(
        new AccountGameplayDelegationResponseEnvelopeRepository(
            fixture.dsl(), fixture.sourceEvidence(), fixture.bundles(), issuance, fixture.crypto()),
        active);
  }

  private static CommittedCandidateVerificationData committedCandidate(Fixture fixture)
      throws Exception {
    CommittedCandidateVerificationData committed = mock(CommittedCandidateVerificationData.class);
    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            OPERATION_ID,
            REQUEST_ID,
            ACCOUNT_ID,
            WORKLOAD,
            CALLER_CONTEXT_ID,
            fixture.intent().credentialRequestBinding(),
            fixture.digest(),
            JTI,
            fixture.intent().issuedAtEpochSecond(),
            fixture.intent().notBeforeEpochSecond(),
            fixture.intent().expiresAtEpochSecond());
    long expiresAtMillis = Math.multiplyExact(fixture.intent().expiresAtEpochSecond(), 1_000L);
    when(committed.identity()).thenReturn(identity);
    when(committed.authoritySnapshot()).thenReturn(fixture.intent().authoritySnapshot());
    when(committed.evidenceBundleReference())
        .thenReturn(fixture.candidate().evidenceBundleReference());
    when(committed.tokenSha256()).thenReturn(fixture.candidate().tokenHash());
    when(committed.signerKid()).thenReturn(fixture.candidate().kid());
    when(committed.signerGeneration()).thenReturn(fixture.candidate().signerGeneration());
    when(committed.canonicalRegistryRecordSha256())
        .thenReturn(
            sha256(
                fixture
                    .candidate()
                    .toCanonicalJsonBytes(
                        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES)));
    when(committed.commitProofSha256()).thenReturn("c".repeat(64));
    when(committed.envelopeKeyId()).thenReturn("response-key-1");
    String envelopeSha256 = fixture.envelopeRow().get("envelope_sha256", String.class);
    when(committed.envelopeSha256()).thenReturn(envelopeSha256);
    when(committed.envelopeBytesLength()).thenReturn(fixture.sealed().bytes().length);
    when(committed.responseRecoveryExpiryEpochMillis()).thenReturn(expiresAtMillis);
    when(committed.registryAbsoluteExpiryMillis()).thenReturn(expiresAtMillis + 30_000L);
    when(committed.registrationLocalAofCount()).thenReturn(1L);
    when(committed.registrationReplicaAofCount()).thenReturn(1L);
    when(committed.registrationOutcome())
        .thenReturn(AccountGameplayDelegationRedisClient.PendingRegistrationOutcome.CREATED);
    return committed;
  }

  private static ActiveCommittedIssuanceObservation activeObservation(Fixture fixture) {
    ActiveCommittedIssuanceObservation active = mock(ActiveCommittedIssuanceObservation.class);
    when(active.operationId()).thenReturn(OPERATION_ID);
    when(active.requestId()).thenReturn(REQUEST_ID);
    when(active.accountId()).thenReturn(ACCOUNT_ID);
    when(active.tokenSha256()).thenReturn(fixture.candidate().tokenHash());
    when(active.activeRecordSha256()).thenReturn("d".repeat(64));
    when(active.commitProofSha256()).thenReturn("c".repeat(64));
    when(active.registryVersion()).thenReturn(2L);
    when(active.absoluteExpiryMillis())
        .thenReturn(Math.multiplyExact(fixture.intent().expiresAtEpochSecond(), 1_000L) + 30_000L);
    when(active.outcome()).thenReturn(ActiveRegistrationOutcome.ACTIVATED);
    when(active.observedAtEpochMillis()).thenReturn(NOW.toEpochMilli());
    return active;
  }

  @Test
  void sourceVersionOrFenceChangeDeniesBeforeBundleRead() throws Exception {
    Fixture fixture = fixture(NOW);
    IssuerAccountSourceSnapshot changed = sourceSnapshot(12L);
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenReturn(fixture.operationRow(), fixture.operationRow());
    when(fixture
            .sourceEvidence()
            .readCurrentIssuerAccountSources(
                GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(changed);

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        fixture
                            .repository()
                            .sealPendingCandidate(
                                REQUEST_ID, fixture.caller(), fixture.compactJwt())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.AuthorityChangedException.class);
    verifyNoInteractions(fixture.bundles(), fixture.crypto());
  }

  @Test
  void bundleReadbackMustMatchTheSameOwnerEvaluationBytes() throws Exception {
    Fixture fixture = fixture(NOW);
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenReturn(fixture.operationRow(), fixture.operationRow());
    when(fixture
            .sourceEvidence()
            .readCurrentIssuerAccountSources(
                GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(fixture.snapshot());
    when(fixture.bundles().captureAndPersist(REQUEST_ID)).thenReturn(fixture.bundle());
    when(fixture.bundles().readStoredNonAuthorizingValue(OPERATION_ID))
        .thenReturn(bundle(fixture.digest(), "3".repeat(64)));

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        fixture
                            .repository()
                            .sealPendingCandidate(
                                REQUEST_ID, fixture.caller(), fixture.compactJwt())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.OwnerEvidenceUnavailableException
                .class);
    verify(fixture.crypto(), never())
        .encrypt(any(Binding.class), any(byte[].class), any(Instant.class));
    verify(fixture.dsl(), never()).execute(anyString(), any(Object[].class));
  }

  @Test
  void tokenGenerationRequiresCanonicalOneStringAndRejectsOtherWireValues() throws Exception {
    AccountAuthEvidenceBundle bundle = fixture(NOW).bundle();
    Object tokenGeneration =
        ((Map<?, ?>) bundle.fields().get("tokenIdentity")).get("tokenGeneration");

    assertThat(tokenGeneration).isInstanceOf(String.class).isEqualTo("1");
    assertThat(AccountAuthEvidenceBundle.parseCanonical(bundle.canonicalBytes())).isEqualTo(bundle);

    for (Object invalidTokenGeneration : List.of(1L, "01", "2")) {
      assertThatThrownBy(
              () ->
                  AccountAuthEvidenceBundle.parseCanonical(
                      bundleBytesWithTokenGeneration(bundle, invalidTokenGeneration)))
          .isInstanceOf(AccountAuthEvidenceBundle.InvalidBundleException.class);
    }
  }

  @Test
  void mismatchedStoredEnvelopeExpiryIsNotReplayed() throws Exception {
    Fixture fixture = fixture(NOW);
    Record mismatchedExpiry =
        envelopeRow(
            fixture.candidate(),
            fixture.bundle(),
            fixture.sealed().bytes(),
            NOW.getEpochSecond() + 119L);
    when(fixture.dsl().fetchOne(anyString(), any(Object[].class)))
        .thenAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("FROM account_gameplay_delegation_issuance_operations")) {
                return fixture.operationRow();
              }
              if (sql.contains("FROM accounts")) return fixture.accountRow();
              if (sql.contains("FROM account_gameplay_delegation_response_envelopes")) {
                return mismatchedExpiry;
              }
              throw new AssertionError("Unexpected SQL in component fixture");
            });
    when(fixture
            .sourceEvidence()
            .readCurrentIssuerAccountSources(
                GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(fixture.snapshot());
    when(fixture.bundles().captureAndPersist(REQUEST_ID)).thenReturn(fixture.bundle());
    when(fixture.bundles().readStoredNonAuthorizingValue(OPERATION_ID))
        .thenReturn(fixture.bundle());

    assertThatThrownBy(
            () ->
                inWritableOwnerTransaction(
                    () ->
                        fixture
                            .repository()
                            .sealPendingCandidate(
                                REQUEST_ID, fixture.caller(), fixture.compactJwt())))
        .isInstanceOf(
            AccountGameplayDelegationResponseEnvelopeRepository.StorageUnavailableException.class);
    verify(fixture.crypto(), never())
        .encrypt(any(Binding.class), any(byte[].class), any(Instant.class));
    verify(fixture.dsl(), never()).execute(anyString(), any(Object[].class));
  }

  private static Fixture fixture(Instant now) throws Exception {
    DSLContext dsl = mock(DSLContext.class);
    AccountAuthoritySourceEvidenceRepository sourceEvidence =
        mock(AccountAuthoritySourceEvidenceRepository.class);
    AccountAuthEvidenceBundleRepository bundles = mock(AccountAuthEvidenceBundleRepository.class);
    AccountResponseEnvelopeCryptography crypto = mock(AccountResponseEnvelopeCryptography.class);
    IssuerAccountSourceSnapshot snapshot = sourceSnapshot(11L);
    long issuedAtEpochSecond = now.getEpochSecond();
    PendingIntent intent =
        new PendingIntent(
            OPERATION_ID,
            REQUEST_ID,
            WORKLOAD,
            CALLER_CONTEXT_ID,
            JTI,
            issuedAtEpochSecond,
            issuedAtEpochSecond,
            issuedAtEpochSecond + 120L,
            new AccountAuthoritySnapshot(
                ACCOUNT_ID, 7L, 7L, 11L, 11L, 11L, 11L, Optional.of(accountCutoff(11L))),
            AccountGameplayCredentialRequestBindingFixture.binding());
    String digest =
        net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository
            .requestDigest(intent);
    String compactJwt = compactCandidate(intent);
    AccountAuthEvidenceBundle bundle = bundle(digest, "2".repeat(64));
    GameSessionAccountDelegationRegistryRecord candidate =
        GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
            compactJwt,
            "2",
            new IssuanceBinding(
                OPERATION_ID.toString(), REQUEST_ID.toString(), digest, ACCOUNT_ID.toString()),
            intent.authoritySnapshot(),
            new EvidenceBundleReference("1", "11", "9", "12345678", bundle.canonicalSha256()),
            intent.issuedAtEpochSecond(),
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    byte[] candidateBytes =
        candidate.toCanonicalJsonBytes(
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    byte[] sealedBytes = new byte[] {1, 2, 3, 4, 5};
    EncryptedResponseEnvelope sealed = new EncryptedResponseEnvelope(sealedBytes);
    Record operationRow = operationRow(intent, digest, candidate, candidateBytes);
    Record accountRow = mock(Record.class);
    when(accountRow.get("id", Long.class)).thenReturn(42L);
    when(accountRow.get("id")).thenReturn(42L);
    when(accountRow.get("account_uuid", UUID.class)).thenReturn(ACCOUNT_ID);
    when(accountRow.get("account_uuid_provenance", String.class))
        .thenReturn(AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name());
    when(accountRow.get("account_uuid_source_numeric_id", Long.class)).thenReturn(42L);
    when(accountRow.get("account_uuid_source_numeric_id")).thenReturn(42L);
    when(sourceEvidence.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, ACCOUNT_ID))
        .thenReturn(snapshot);
    Record envelopeRow = envelopeRow(candidate, bundle, sealedBytes, intent.expiresAtEpochSecond());
    return new Fixture(
        dsl,
        sourceEvidence,
        bundles,
        crypto,
        snapshot,
        intent,
        digest,
        compactJwt,
        candidate,
        bundle,
        sealed,
        operationRow,
        accountRow,
        envelopeRow,
        new AtomicInteger(),
        new AccountGameplayDelegationResponseEnvelopeRepository(
            dsl, sourceEvidence, bundles, crypto));
  }

  private static Record operationRow(
      PendingIntent intent,
      String digest,
      GameSessionAccountDelegationRegistryRecord candidate,
      byte[] candidateBytes) {
    Record row = mock(Record.class);
    when(row.get("operation_id", UUID.class)).thenReturn(OPERATION_ID);
    when(row.get("request_id", UUID.class)).thenReturn(REQUEST_ID);
    when(row.get("account_uuid", UUID.class)).thenReturn(ACCOUNT_ID);
    when(row.get("caller_workload", String.class)).thenReturn(WORKLOAD);
    when(row.get("caller_context_id", UUID.class)).thenReturn(CALLER_CONTEXT_ID);
    when(row.get("request_digest", String.class)).thenReturn(digest);
    when(row.get("token_jti", UUID.class)).thenReturn(JTI);
    when(row.get("status", String.class)).thenReturn("PENDING");
    when(row.get("request_digest_version")).thenReturn((short) 2);
    when(row.get("credential_request_digest_version"))
        .thenReturn((short) intent.credentialRequestBinding().digestSchemaVersion());
    when(row.get("credential_digest_key_id", String.class))
        .thenReturn(intent.credentialRequestBinding().digestKeyId());
    when(row.get("credential_request_digest", String.class))
        .thenReturn(intent.credentialRequestBinding().credentialRequestDigest());
    when(row.get("token_generation")).thenReturn(1L);
    when(row.get("issued_at_epoch_second")).thenReturn(intent.issuedAtEpochSecond());
    when(row.get("not_before_epoch_second")).thenReturn(intent.notBeforeEpochSecond());
    when(row.get("expires_at_epoch_second")).thenReturn(intent.expiresAtEpochSecond());
    when(row.get("authority_issuer_generation")).thenReturn(7L);
    when(row.get("authority_issuer_source_version")).thenReturn(7L);
    when(row.get("authority_account_generation")).thenReturn(11L);
    when(row.get("authority_account_source_version")).thenReturn(11L);
    when(row.get("issuance_fence")).thenReturn(11L);
    when(row.get("issuance_fence_source_version")).thenReturn(11L);
    when(row.get("token_hash", String.class)).thenReturn(candidate.tokenHash());
    when(row.get("signer_kid", String.class)).thenReturn(candidate.kid());
    when(row.get("signer_generation", String.class)).thenReturn(candidate.signerGeneration());
    when(row.get("pending_registry_candidate_bytes", byte[].class)).thenReturn(candidateBytes);
    when(row.get("authority_tuple_canonical_bytes", byte[].class))
        .thenReturn(
            canonicalJson(
                GameSessionAccountDelegationProfile.authorityTuple(
                    7L, 11L, Optional.of(accountCutoff(11L)))));
    when(row.get("membership_version_canonical_bytes", byte[].class))
        .thenReturn("{}".getBytes(StandardCharsets.US_ASCII));
    return row;
  }

  private static Record envelopeRow(
      GameSessionAccountDelegationRegistryRecord candidate,
      AccountAuthEvidenceBundle bundle,
      byte[] envelope,
      long expiryEpochSecond)
      throws Exception {
    Record row = mock(Record.class);
    when(row.get("operation_id", UUID.class)).thenReturn(OPERATION_ID);
    when(row.get("request_id", UUID.class)).thenReturn(REQUEST_ID);
    when(row.get("token_hash", String.class)).thenReturn(candidate.tokenHash());
    when(row.get("signer_kid", String.class)).thenReturn(candidate.kid());
    when(row.get("signer_generation", String.class)).thenReturn(candidate.signerGeneration());
    when(row.get("authority_evidence_bundle_sha256", String.class))
        .thenReturn(bundle.canonicalSha256());
    when(row.get("issuance_fence", Long.class)).thenReturn(11L);
    when(row.get("issuance_fence")).thenReturn(11L);
    when(row.get("response_recovery_expiry_epoch_ms", Long.class))
        .thenReturn(Math.multiplyExact(expiryEpochSecond, 1_000L));
    when(row.get("response_recovery_expiry_epoch_ms"))
        .thenReturn(Math.multiplyExact(expiryEpochSecond, 1_000L));
    when(row.get("envelope_sha256", String.class)).thenReturn(sha256(envelope));
    when(row.get("envelope_bytes", byte[].class)).thenReturn(envelope);
    return row;
  }

  private static AccountAuthEvidenceBundle bundle(String digest, String evaluationIdentity) {
    String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
    return AccountAuthEvidenceBundle.fromOwnerEvaluation(
        new OwnerEvaluation(
            new BundleReference("1", "11", "9", "12345678"),
            "1".repeat(64),
            evaluationIdentity,
            ACCOUNT_ID,
            new OperationIdentity(
                OPERATION_ID, REQUEST_ID, digest, WORKLOAD, CALLER_CONTEXT_ID, ACCOUNT_ID),
            new TokenIdentity(
                JTI, 1L, NOW.getEpochSecond(), NOW.getEpochSecond(), NOW.getEpochSecond() + 120L),
            GameSessionAccountDelegationProfile.authorityTuple(
                7L, 11L, Optional.of(accountCutoff(11L))),
            11L,
            new AuthoritySourceVersions(7L, 11L, 11L),
            new AccountIdentitySource(
                42L, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, 42L),
            List.of(
                new OutboxCheckpoint(
                    accountStream, 10L, "fixture-account-event", "sha256:" + "a".repeat(64)),
                new OutboxCheckpoint(
                    issuerStream, 6L, "fixture-issuer-event", "sha256:" + "b".repeat(64)))));
  }

  private static byte[] bundleBytesWithTokenGeneration(
      AccountAuthEvidenceBundle bundle, Object tokenGeneration) {
    Map<String, Object> fields = new LinkedHashMap<>(bundle.fields());
    Map<String, Object> tokenIdentity = new LinkedHashMap<>();
    ((Map<?, ?>) fields.get("tokenIdentity"))
        .forEach((field, value) -> tokenIdentity.put((String) field, value));
    tokenIdentity.put("tokenGeneration", tokenGeneration);
    fields.put("tokenIdentity", tokenIdentity);
    return canonicalJson(fields);
  }

  private static IssuerAccountSourceSnapshot sourceSnapshot(long accountGeneration) {
    long accountSourceVersion = accountGeneration;
    long issuerGeneration = 7L;
    long accountFence = accountGeneration;
    IssuanceFence issuanceFence = new IssuanceFence(ACCOUNT_ID, accountFence, accountSourceVersion);
    CurrentSourceEvidence issuer =
        new CurrentSourceEvidence(
            AuthorityScope.issuer(GameSessionAccountDelegationProfile.ISSUER),
            issuerGeneration,
            issuerGeneration,
            null,
            new SourceCheckpoint(
                "account:auth-authority:v1:issuer/firemud-account-service",
                issuerGeneration - 1L,
                Optional.of("fixture-issuer-event"),
                Optional.of("b".repeat(64))),
            Optional.empty(),
            "ISSUER_SCOPE_INSERT",
            null,
            null,
            1L,
            null);
    CurrentSourceEvidence account =
        new CurrentSourceEvidence(
            AuthorityScope.account(ACCOUNT_ID),
            accountGeneration,
            accountSourceVersion,
            issuanceFence,
            new SourceCheckpoint(
                "account:auth-authority:v1:account/" + ACCOUNT_ID,
                accountGeneration - 1L,
                Optional.of("fixture-account-event"),
                Optional.of("a".repeat(64))),
            Optional.of(sourceAccountCutoff(accountGeneration)),
            "ACCOUNT_REPOSITORY_INSERT",
            42L,
            AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT.name(),
            2L,
            2L);
    return new IssuerAccountSourceSnapshot(issuer, account, issuanceFence);
  }

  private static GameSessionAccountDelegationProfile.AccountSecurityCutoff accountCutoff(
      long accountGeneration) {
    return new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
        Long.toString(accountGeneration),
        "account:auth-authority:v1:account/" + ACCOUNT_ID,
        Long.toString(accountGeneration - 1L));
  }

  private static AccountSecurityCutoff sourceAccountCutoff(long accountGeneration) {
    return new AccountSecurityCutoff(
        Long.toString(accountGeneration),
        "account:auth-authority:v1:account/" + ACCOUNT_ID,
        Long.toString(accountGeneration - 1L));
  }

  private static String compactCandidate(PendingIntent intent) throws Exception {
    Map<String, Object> header = Map.of("alg", "RS256", "kid", "delegation-kid", "typ", "JWT");
    Map<String, Object> claims =
        Map.ofEntries(
            Map.entry("iss", GameSessionAccountDelegationProfile.ISSUER),
            Map.entry("sub", ACCOUNT_ID.toString()),
            Map.entry("accountId", ACCOUNT_ID.toString()),
            Map.entry("jti", JTI.toString()),
            Map.entry("aud", GameSessionAccountDelegationProfile.AUDIENCE),
            Map.entry("iat", intent.issuedAtEpochSecond()),
            Map.entry("nbf", intent.notBeforeEpochSecond()),
            Map.entry("exp", intent.expiresAtEpochSecond()),
            Map.entry("tokenGeneration", "1"),
            Map.entry(
                "authorityTuple",
                GameSessionAccountDelegationProfile.authorityTuple(
                    intent.authoritySnapshot().issuerGeneration(),
                    intent.authoritySnapshot().accountGeneration(),
                    intent.authoritySnapshot().accountSecurityCutoff())),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", Long.toString(intent.authoritySnapshot().issuanceFence())));
    Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
    String head = encoder.encodeToString(canonicalJson(header));
    String payload = encoder.encodeToString(canonicalJson(claims));
    return head + "." + payload + "." + encoder.encodeToString(new byte[] {1, 2, 3});
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static String sha256(byte[] bytes) throws Exception {
    return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private static <T> T inWritableOwnerTransaction(java.util.function.Supplier<T> operation) {
    boolean priorActive = TransactionSynchronizationManager.isActualTransactionActive();
    boolean priorReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    try {
      return operation.get();
    } finally {
      TransactionSynchronizationManager.setCurrentTransactionReadOnly(priorReadOnly);
      TransactionSynchronizationManager.setActualTransactionActive(priorActive);
    }
  }

  private record RecoveryHarness(
      AccountGameplayDelegationResponseEnvelopeRepository repository,
      ActiveCommittedIssuanceObservation active) {}

  private record Fixture(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountAuthEvidenceBundleRepository bundles,
      AccountResponseEnvelopeCryptography crypto,
      IssuerAccountSourceSnapshot snapshot,
      PendingIntent intent,
      String digest,
      String compactJwt,
      GameSessionAccountDelegationRegistryRecord candidate,
      AccountAuthEvidenceBundle bundle,
      EncryptedResponseEnvelope sealed,
      Record operationRow,
      Record accountRow,
      Record envelopeRow,
      AtomicInteger envelopeReads,
      AccountGameplayDelegationResponseEnvelopeRepository repository) {
    private CallerIdentity caller() {
      return new CallerIdentity(WORKLOAD, CALLER_CONTEXT_ID);
    }
  }
}
