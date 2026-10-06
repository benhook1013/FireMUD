package net.firedevops.firemud.accountservice.service.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AccountIdentitySource;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.AuthoritySourceVersions;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.BundleReference;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OperationIdentity;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OutboxCheckpoint;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.OwnerEvaluation;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle.TokenIdentity;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountGameplayCredentialRequestBindingFixture;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.BoundTokenCandidate;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingRegistryCandidate;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingSigningIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationPendingIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtJwksPublicationRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveJwksPromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.DesiredState;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.GenerationResult;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PrivatePromotionReceipt;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.PromotionOperationEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class AccountGameplayDelegationSignerTest {
  private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String ENVIRONMENT = "staging";
  private static final String CLUSTER = "cluster-a";
  private static final String NAMESPACE = "firemud";
  private static final String MATERIALIZER_REVISION = "materializer-r1";
  private static final String API_REVISION = "api-revision-1";
  private static final String MATERIALIZER_DIGEST = "b".repeat(64);
  private static final String API_DIGEST = "c".repeat(64);
  private static final String SERVING_CA_DIGEST = "e".repeat(64);
  private static final String CLUSTER_UID = "44444444-4444-4444-8444-444444444444";
  private static final String NAMESPACE_UID = "55555555-5555-4555-8555-555555555555";
  private static final String CONFIG_MAP_UID = "66666666-6666-4666-8666-666666666666";
  private static final String API_ORIGIN = "https://kubernetes.example.test:6443";
  private static final UUID ACCOUNT_ID = UUID.fromString("a6a2af33-2e50-4092-934b-43c5bce12f7b");
  private static final UUID ISSUANCE_OPERATION_ID =
      UUID.fromString("c5c31332-e560-41c8-a55a-97674e7a317c");
  private static final UUID REQUEST_ID = UUID.fromString("f14ca47a-2316-47b7-a38f-f9b693ff8afc");
  private static final UUID CALLER_CONTEXT_ID =
      UUID.fromString("e16fdce5-96eb-49e9-a1bd-3f429816cbf0");
  private static final UUID TOKEN_JTI = UUID.fromString("1a7c3d0c-ab15-4f86-b5af-9e29bc7d3543");
  private static final UUID GENERATION_OPERATION_ID =
      UUID.fromString("7af097ea-b1d1-42ea-9f24-46aeb211a779");
  private static final UUID PROMOTION_OPERATION_ID =
      UUID.fromString("8af097ea-b1d1-42ea-9f24-46aeb211a779");
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String GENERATION = "42";
  private static final String KID = "account-key-42";
  private static final String WORKLOAD = "spiffe://firemud/ns/firemud/sa/game-session-service";
  private static KeyPair signerPair;
  private static KeyPair mismatchedPair;

  @TempDir Path temporaryDirectory;

  @BeforeAll
  static void generateEphemeralRsaPairs() throws Exception {
    ensureEphemeralRsaPairs();
  }

  private static synchronized void ensureEphemeralRsaPairs() throws Exception {
    if (signerPair != null && mismatchedPair != null) return;
    KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(3072);
    signerPair = generator.generateKeyPair();
    mismatchedPair = generator.generateKeyPair();
  }

  @Test
  void remainsUnwiredAndFailsClosedWithoutProtectedSignerTrust() {
    AccountGameplayDelegationIssuanceRepository issuance =
        mock(AccountGameplayDelegationIssuanceRepository.class);
    AccountJwtSignerDesiredStateRepository desired =
        mock(AccountJwtSignerDesiredStateRepository.class);
    AccountJwtSignerMaterializerTrustBinding trust =
        new AccountJwtSignerMaterializerTrustBinding(false, "");
    AccountJwtJwksApiBinding apiBinding = new AccountJwtJwksApiBinding();
    AccountGameplayDelegationResponseEnvelopeService envelopeService =
        mock(AccountGameplayDelegationResponseEnvelopeService.class);
    InertTransactionManager transactionManager = new InertTransactionManager();
    AccountJwtJwksConfigMapClient configMapClient = mock(AccountJwtJwksConfigMapClient.class);
    AccountJwtJwksTrustedSource trustedJwksSource =
        new AccountJwtJwksTrustedSource(
            configMapClient,
            trust,
            desired,
            mock(AccountJwtJwksPublicationRepository.class),
            transactionManager);
    AccountGameplayDelegationSigner signer =
        new AccountGameplayDelegationSigner(
            issuance,
            desired,
            trust,
            apiBinding,
            trustedJwksSource,
            envelopeService,
            transactionManager,
            Clock.systemUTC());

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> signer.signPendingCandidate(UUID.randomUUID()));
    assertFalse(AccountGameplayDelegationSigner.class.isAnnotationPresent(Service.class));
    verifyNoInteractions(issuance, desired, envelopeService);
    verifyNoInteractions(configMapClient);
  }

  @Test
  void signsCanonicalPendingCandidateAndSealsOnlyMetadataWithLiveSourceOutsideTransactions()
      throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);

    // The owner/source collaborators are isolated mocks; this checks call placement and ordering,
    // not Spring proxy propagation or live API availability.
    AccountGameplayDelegationSigner.CandidateOutcome outcome =
        fixture.signer.signPendingCandidate(REQUEST_ID);
    assertNotNull(outcome);
    String transientJwt = fixture.transientJwt.get();
    AccountGameplayDelegationSigner.PendingCandidateVerificationProof proof =
        outcome.verificationProof();

    assertNotNull(proof);
    assertEquals(ISSUANCE_OPERATION_ID, outcome.operationId());
    assertEquals(REQUEST_ID, outcome.requestId());
    assertEquals(fixture.boundRecord.get().tokenHash(), outcome.tokenSha256());
    assertEquals(ISSUANCE_OPERATION_ID.toString(), fixture.boundRecord.get().operationId());
    assertEquals(REQUEST_ID.toString(), fixture.boundRecord.get().requestId());
    assertEquals(TOKEN_JTI.toString(), fixture.boundRecord.get().jti());
    assertEquals(ACCOUNT_ID.toString(), fixture.boundRecord.get().accountId());
    assertEquals("pending", fixture.boundRecord.get().state());
    assertEquals(GENERATION, outcome.signerGeneration());
    assertEquals(KID, outcome.signerKid());
    assertEquals(ISSUANCE_OPERATION_ID, proof.operationId());
    assertEquals(REQUEST_ID, proof.requestId());
    assertEquals(ACCOUNT_ID, proof.accountId());
    assertEquals(TOKEN_JTI, proof.tokenJti());
    assertEquals(
        fixture.evidenceBundle.canonicalSha256(),
        proof.evidenceBundleReference().canonicalSha256());
    assertEquals(outcome.tokenSha256(), proof.tokenSha256());
    assertEquals(fixture.evidenceReference, proof.evidenceBundleReference());
    assertEquals(fixture.authoritySnapshot, proof.authoritySnapshot());
    assertEquals(fixture.pendingIdentity.issuedAtEpochSecond(), proof.issuedAtEpochSecond());
    assertEquals(fixture.pendingIdentity.notBeforeEpochSecond(), proof.notBeforeEpochSecond());
    assertEquals(fixture.pendingIdentity.expiresAtEpochSecond(), proof.expiresAtEpochSecond());
    assertEquals(REQUEST_DIGEST, proof.identity().requestDigest());
    assertEquals(WORKLOAD, proof.identity().callerWorkload());
    assertEquals(CALLER_CONTEXT_ID, proof.identity().callerContextId());
    assertEquals(fixture.authoritySnapshot.issuanceFence(), proof.issuanceFence());
    assertEquals(GENERATION_OPERATION_ID, proof.signerOperationId());
    assertEquals(GENERATION, proof.signerGeneration());
    assertEquals(KID, proof.signerKid());
    assertEquals("COMMITTED", proof.signerCorrespondence().promotionStatus());
    assertEquals(PROMOTION_OPERATION_ID, proof.signerCorrespondence().promotionOperationId());
    assertEquals(GENERATION_OPERATION_ID, proof.signerCorrespondence().generationOperationId());
    assertEquals(fixture.sourceIdentity, proof.signerCorrespondence().publicJwksSourceIdentity());
    assertEquals("13", proof.signerCorrespondence().privatePromotionObservedResourceVersion());
    assertEquals("b".repeat(64), proof.signerCorrespondence().privatePromotionReceiptDigest());
    assertEquals("20", proof.signerCorrespondence().priorPublicResourceVersion());
    assertEquals("21", proof.signerCorrespondence().observedPublicResourceVersion());
    assertEquals("c".repeat(64), proof.signerCorrespondence().publicDataDigest());
    assertEquals("d".repeat(64), proof.signerCorrespondence().publicReceiptDigest());
    assertEquals(outcome.sealedCandidate(), proof.sealedCandidate());
    assertTrue(proof.canonicalRegistryRecordSha256().matches("[0-9a-f]{64}"));
    assertEquals(
        fixture.evidenceBundle.canonicalSha256(),
        outcome.sealedCandidate().authorityEvidenceBundleSha256());
    assertFalse(outcome.toString().contains(transientJwt));
    assertFalse(proof.toString().contains(transientJwt));
    assertFalse(proof.signerCorrespondence().toString().contains(transientJwt));
    assertProofHasNoPublicConstructionOrMutableAggregateFields();
    assertEquals(3, fixture.publicSourceLoadCount.get());
    assertTrue(fixture.sourceLoadsWereOutsideTransactions.get());
    verify(fixture.issuance, times(2)).readPendingSigningIdentity(REQUEST_ID);
    verify(fixture.issuance).bindSignedCandidate(REQUEST_ID, transientJwt, GENERATION);
    verify(fixture.issuance, times(2)).readPendingRegistryCandidate(REQUEST_ID);
    verify(fixture.envelopeService)
        .sealPendingCandidate(eq(REQUEST_ID), any(CallerIdentity.class), eq(transientJwt));
    verify(fixture.desiredState, times(3))
        .readCurrentCommittedSigner(
            eq(fixture.accountBinding), eq(fixture.trustFence), eq(API_DIGEST), eq(API_REVISION));

    var order = inOrder(fixture.issuance, fixture.envelopeService);
    order.verify(fixture.issuance, times(2)).readPendingSigningIdentity(REQUEST_ID);
    order.verify(fixture.issuance).bindSignedCandidate(REQUEST_ID, transientJwt, GENERATION);
    order.verify(fixture.issuance).readPendingRegistryCandidate(REQUEST_ID);
    order
        .verify(fixture.envelopeService)
        .sealPendingCandidate(eq(REQUEST_ID), any(CallerIdentity.class), eq(transientJwt));
    order.verify(fixture.issuance).readPendingRegistryCandidate(REQUEST_ID);
  }

  @Test
  void revalidatesPrivateProofOnlyInsideWritableTransactionAgainstCurrentOwner() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    AccountGameplayDelegationSigner.PendingCandidateVerificationProof proof =
        fixture.signer.signPendingCandidate(REQUEST_ID).verificationProof();

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> fixture.signer.requireCurrentCommittedSigner(proof));

    TransactionTemplate writable = new TransactionTemplate(fixture.transactionManager);
    int loadsBeforeOwnerCheck = fixture.publicSourceLoadCount.get();
    writable.execute(
        status -> {
          fixture.signer.requireCurrentCommittedSigner(proof);
          return null;
        });
    assertEquals(loadsBeforeOwnerCheck, fixture.publicSourceLoadCount.get());

    TransactionTemplate readOnly = new TransactionTemplate(fixture.transactionManager);
    readOnly.setReadOnly(true);
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            readOnly.execute(
                status -> {
                  fixture.signer.requireCurrentCommittedSigner(proof);
                  return null;
                }));
  }

  @Test
  void revalidatesRepositoryIssuedCommittedSnapshotOnlyInsideWritableTransaction()
      throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    var proof = fixture.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData committed = committedData(proof);

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> fixture.signer.requireCurrentCommittedSigner(committed));

    int loadsBeforeOwnerCheck = fixture.publicSourceLoadCount.get();
    new TransactionTemplate(fixture.transactionManager)
        .execute(
            status -> {
              fixture.signer.requireCurrentCommittedSigner(committed);
              return null;
            });
    assertEquals(loadsBeforeOwnerCheck, fixture.publicSourceLoadCount.get());

    TransactionTemplate readOnly = new TransactionTemplate(fixture.transactionManager);
    readOnly.setReadOnly(true);
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            readOnly.execute(
                status -> {
                  fixture.signer.requireCurrentCommittedSigner(committed);
                  return null;
                }));
  }

  @Test
  void committedSnapshotRecheckDeniesChangedSignerOrWithdrawnProtectedTrust() throws Exception {
    Fixture changedOwner = new Fixture(temporaryDirectory, false);
    var changedProof = changedOwner.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData changedSnapshot = committedData(changedProof);
    changedOwner.replaceCommittedEvidence(ownerEvidence(mismatchedPair, "43", "account-key-43"));
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            new TransactionTemplate(changedOwner.transactionManager)
                .execute(
                    status -> {
                      changedOwner.signer.requireCurrentCommittedSigner(changedSnapshot);
                      return null;
                    }));

    Fixture withdrawnTrust = new Fixture(temporaryDirectory, false);
    var trustProof = withdrawnTrust.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData trustSnapshot = committedData(trustProof);
    withdrawnTrust.withdrawMaterializerTrust();
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            new TransactionTemplate(withdrawnTrust.transactionManager)
                .execute(
                    status -> {
                      withdrawnTrust.signer.requireCurrentCommittedSigner(trustSnapshot);
                      return null;
                    }));
  }

  private static CommittedCandidateVerificationData committedData(
      AccountGameplayDelegationSigner.PendingCandidateVerificationProof proof) {
    var verifiedIdentity = proof.identity();
    AccountGameplayDelegationPendingIdentity identity =
        new AccountGameplayDelegationPendingIdentity(
            verifiedIdentity.operationId(),
            verifiedIdentity.requestId(),
            verifiedIdentity.accountId(),
            verifiedIdentity.callerWorkload(),
            verifiedIdentity.callerContextId(),
            AccountGameplayCredentialRequestBindingFixture.binding(),
            verifiedIdentity.requestDigest(),
            verifiedIdentity.tokenJti(),
            verifiedIdentity.issuedAtEpochSecond(),
            verifiedIdentity.notBeforeEpochSecond(),
            verifiedIdentity.expiresAtEpochSecond());
    CommittedCandidateVerificationData committed = mock(CommittedCandidateVerificationData.class);
    when(committed.identity()).thenReturn(identity);
    when(committed.authoritySnapshot()).thenReturn(proof.authoritySnapshot());
    when(committed.evidenceBundleReference()).thenReturn(proof.evidenceBundleReference());
    when(committed.tokenSha256()).thenReturn(proof.tokenSha256());
    when(committed.signerKid()).thenReturn(proof.signerKid());
    when(committed.signerGeneration()).thenReturn(proof.signerGeneration());
    when(committed.canonicalRegistryRecordSha256())
        .thenReturn(proof.canonicalRegistryRecordSha256());
    when(committed.commitProofSha256()).thenReturn("e".repeat(64));
    when(committed.signerCorrespondence()).thenReturn(proof.signerCorrespondence());
    when(committed.envelopeKeyId()).thenReturn("response-key-1");
    when(committed.envelopeSha256()).thenReturn("f".repeat(64));
    when(committed.envelopeBytesLength()).thenReturn(64);
    when(committed.responseRecoveryExpiryEpochMillis())
        .thenReturn(proof.sealedCandidate().responseRecoveryExpiryEpochMillis());
    return committed;
  }

  @Test
  void refusesVerificationProofAfterCurrentCommittedSignerChangesOrTrustIsWithdrawn()
      throws Exception {
    Fixture changedOwner = new Fixture(temporaryDirectory, false);
    var proof = changedOwner.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    changedOwner.replaceCommittedEvidence(ownerEvidence(mismatchedPair, "43", "account-key-43"));
    TransactionTemplate writable = new TransactionTemplate(changedOwner.transactionManager);
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            writable.execute(
                status -> {
                  changedOwner.signer.requireCurrentCommittedSigner(proof);
                  return null;
                }));

    Fixture withdrawnTrust = new Fixture(temporaryDirectory, false);
    var trustProof = withdrawnTrust.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    withdrawnTrust.withdrawMaterializerTrust();
    TransactionTemplate trustTransaction =
        new TransactionTemplate(withdrawnTrust.transactionManager);
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            trustTransaction.execute(
                status -> {
                  withdrawnTrust.signer.requireCurrentCommittedSigner(trustProof);
                  return null;
                }));
  }

  @Test
  void responseRecoveryAcceptsACommittedSignerAfterRotationOnlyWhileItsExactKeyIsRetained()
      throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    var originalProof = fixture.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData committed = committedData(originalProof);
    fixture.replaceCommittedEvidence(ownerEvidence(mismatchedPair, "43", "account-key-43"));
    fixture.sourceJwks.set(
        jwksJson(signerPair, KID, mismatchedPair, "account-key-43")
            .getBytes(StandardCharsets.UTF_8));
    Files.writeString(fixture.privateRoot.resolve("current.key"), "unavailable private material");

    new TransactionTemplate(fixture.transactionManager)
        .execute(
            status -> {
              fixture.signer.requireCurrentCommittedRecoverySigner(committed);
              return null;
            });
    fixture.signer.requireRetainedCommittedPublicKey(committed);

    assertTrue(fixture.sourceLoadsWereOutsideTransactions.get());
    verify(fixture.trustedJwksSource, times(4)).load();
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            new TransactionTemplate(fixture.transactionManager)
                .execute(
                    status -> {
                      fixture.signer.requireCurrentCommittedSigner(committed);
                      return null;
                    }));
  }

  @Test
  void responseRecoveryRejectsMissingSubstitutedExpiredOrUntrustedHistoricalKey() throws Exception {
    Fixture missingKey = new Fixture(temporaryDirectory, false);
    var missingProof = missingKey.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData missingCommitted = committedData(missingProof);
    missingKey.replaceCommittedEvidence(ownerEvidence(mismatchedPair, "43", "account-key-43"));
    missingKey.sourceJwks.set(
        jwksJson(mismatchedPair, "account-key-43").getBytes(StandardCharsets.UTF_8));
    new TransactionTemplate(missingKey.transactionManager)
        .execute(
            status -> {
              missingKey.signer.requireCurrentCommittedRecoverySigner(missingCommitted);
              return null;
            });
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> missingKey.signer.requireRetainedCommittedPublicKey(missingCommitted));

    Fixture substitutedKey = new Fixture(temporaryDirectory, false);
    var substitutedProof =
        substitutedKey.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData substitutedCommitted = committedData(substitutedProof);
    substitutedKey.replaceCommittedEvidence(ownerEvidence(mismatchedPair, "43", "account-key-43"));
    substitutedKey.sourceJwks.set(
        jwksJson(mismatchedPair, KID, mismatchedPair, "account-key-43")
            .getBytes(StandardCharsets.UTF_8));
    new TransactionTemplate(substitutedKey.transactionManager)
        .execute(
            status -> {
              substitutedKey.signer.requireCurrentCommittedRecoverySigner(substitutedCommitted);
              return null;
            });
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> substitutedKey.signer.requireRetainedCommittedPublicKey(substitutedCommitted));

    Fixture expired = new Fixture(temporaryDirectory, false);
    var expiredProof = expired.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData expiredCommitted = committedData(expiredProof);
    var oldIdentity = expiredProof.identity();
    when(expiredCommitted.identity())
        .thenReturn(
            new AccountGameplayDelegationPendingIdentity(
                oldIdentity.operationId(),
                oldIdentity.requestId(),
                oldIdentity.accountId(),
                oldIdentity.callerWorkload(),
                oldIdentity.callerContextId(),
                AccountGameplayCredentialRequestBindingFixture.binding(),
                oldIdentity.requestDigest(),
                oldIdentity.tokenJti(),
                oldIdentity.issuedAtEpochSecond(),
                oldIdentity.notBeforeEpochSecond(),
                NOW.getEpochSecond() - 1L));
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            new TransactionTemplate(expired.transactionManager)
                .execute(
                    status -> {
                      expired.signer.requireCurrentCommittedRecoverySigner(expiredCommitted);
                      return null;
                    }));

    Fixture withdrawnTrust = new Fixture(temporaryDirectory, false);
    var trustProof = withdrawnTrust.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    CommittedCandidateVerificationData trustCommitted = committedData(trustProof);
    withdrawnTrust.withdrawMaterializerTrust();
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            new TransactionTemplate(withdrawnTrust.transactionManager)
                .execute(
                    status -> {
                      withdrawnTrust.signer.requireCurrentCommittedRecoverySigner(trustCommitted);
                      return null;
                    }));
  }

  @Test
  void refusesVerificationProofWhenPromotionReceiptFenceNoLongerMatches() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    var proof = fixture.signer.signPendingCandidate(REQUEST_ID).verificationProof();
    fixture.mismatchPrivatePromotionObservation();

    TransactionTemplate writable = new TransactionTemplate(fixture.transactionManager);
    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () ->
            writable.execute(
                status -> {
                  fixture.signer.requireCurrentCommittedSigner(proof);
                  return null;
                }));
  }

  @Test
  void deniesWhenCommittedSignerEvidenceIsMissingBeforeReadingIssuanceOrJwks() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    fixture.returnMissingCommittedEvidence();

    AccountGameplayDelegationSigner.SigningUnavailableException failure =
        assertThrows(
            AccountGameplayDelegationSigner.SigningUnavailableException.class,
            () -> fixture.signer.signPendingCandidate(REQUEST_ID));

    assertEquals(
        "Account gameplay delegation candidate is unavailable or ambiguous", failure.getMessage());
    assertNull(failure.getCause());
    verifyNoInteractions(fixture.issuance, fixture.trustedJwksSource, fixture.envelopeService);
  }

  @Test
  void rejectsMismatchedLiveJwksBeforeBindingTheSignedCandidate() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    fixture.useSourceJwks(mismatchedPair);

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> fixture.signer.signPendingCandidate(REQUEST_ID));

    verify(fixture.issuance).readPendingSigningIdentity(REQUEST_ID);
    // The initial pin read is followed by the cache's independent source parse/refresh.
    assertEquals(2, fixture.publicSourceLoadCount.get());
    verify(fixture.issuance, never()).bindSignedCandidate(any(), anyString(), anyString());
    verifyNoInteractions(fixture.envelopeService);
    assertTrue(fixture.sourceLoadsWereOutsideTransactions.get());
  }

  @Test
  void rejectsPublicProjectionDriftAfterSigningAndBeforeCandidatePersistence() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    fixture.changeSourceJwksOnLoad(3, jwksJson(mismatchedPair).getBytes(StandardCharsets.UTF_8));

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> fixture.signer.signPendingCandidate(REQUEST_ID));

    assertEquals(3, fixture.publicSourceLoadCount.get());
    assertTrue(fixture.sourceLoadsWereOutsideTransactions.get());
    verify(fixture.issuance, never()).bindSignedCandidate(any(), anyString(), anyString());
    verifyNoInteractions(fixture.envelopeService);
  }

  private static void assertProofHasNoPublicConstructionOrMutableAggregateFields() {
    Class<?> proofType = AccountGameplayDelegationSigner.PendingCandidateVerificationProof.class;
    var constructors = proofType.getDeclaredConstructors();
    assertEquals(1, constructors.length);
    assertTrue(java.lang.reflect.Modifier.isPrivate(constructors[0].getModifiers()));
    assertNoMutableAggregateFields(proofType);
    assertNoMutableAggregateFields(AccountGameplayDelegationSigner.VerifiedPendingIdentity.class);
    assertNoMutableAggregateFields(
        AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence.class);
  }

  private static void assertNoMutableAggregateFields(Class<?> type) {
    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
      if (field.isSynthetic()) continue;
      assertFalse(field.getType().isArray(), type.getSimpleName() + " exposes array storage");
      assertFalse(
          Map.class.isAssignableFrom(field.getType()),
          type.getSimpleName() + " exposes mutable map storage");
    }
  }

  @Test
  void rejectsCommittedSignerChangeAcrossTheSigningGapBeforeBinding() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    fixture.changeCommittedSignerAfterFirstRead(mismatchedPair);

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> fixture.signer.signPendingCandidate(REQUEST_ID));

    verify(fixture.issuance, times(2)).readPendingSigningIdentity(REQUEST_ID);
    verify(fixture.issuance, never()).bindSignedCandidate(any(), anyString(), anyString());
    verifyNoInteractions(fixture.envelopeService);
    assertEquals(3, fixture.publicSourceLoadCount.get());
    assertTrue(fixture.sourceLoadsWereOutsideTransactions.get());
  }

  @Test
  void rejectsPendingIdentityDriftAcrossTheSigningGapBeforeBinding() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    fixture.changePendingIdentityAfterFirstRead();

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> fixture.signer.signPendingCandidate(REQUEST_ID));

    verify(fixture.issuance, times(2)).readPendingSigningIdentity(REQUEST_ID);
    verify(fixture.issuance, never()).bindSignedCandidate(any(), anyString(), anyString());
    verifyNoInteractions(fixture.envelopeService);
  }

  @Test
  void rejectsExpiredPendingIdentityBeforePublicSourceReadOrSigning() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, true);

    assertThrows(
        AccountGameplayDelegationSigner.SigningUnavailableException.class,
        () -> fixture.signer.signPendingCandidate(REQUEST_ID));

    verify(fixture.issuance).readPendingSigningIdentity(REQUEST_ID);
    verifyNoInteractions(fixture.trustedJwksSource, fixture.envelopeService);
    verify(fixture.issuance, never()).bindSignedCandidate(any(), anyString(), anyString());
  }

  @Test
  void quarantinesCandidateAndEnvelopeReadbackMismatchesWithoutLeakingTransientJwt()
      throws Exception {
    Fixture candidateMismatch = new Fixture(temporaryDirectory, false);
    candidateMismatch.returnMismatchedBoundCandidate();
    AccountGameplayDelegationSigner.SigningUnavailableException candidateFailure =
        assertThrows(
            AccountGameplayDelegationSigner.SigningUnavailableException.class,
            () -> candidateMismatch.signer.signPendingCandidate(REQUEST_ID));
    assertNotNull(candidateMismatch.transientJwt.get());
    assertFalse(candidateFailure.toString().contains(candidateMismatch.transientJwt.get()));
    assertNull(candidateFailure.getCause());
    verifyNoInteractions(candidateMismatch.envelopeService);

    Fixture envelopeMismatch = new Fixture(temporaryDirectory, false);
    envelopeMismatch.returnMismatchedEnvelopeReadback();
    AccountGameplayDelegationSigner.SigningUnavailableException envelopeFailure =
        assertThrows(
            AccountGameplayDelegationSigner.SigningUnavailableException.class,
            () -> envelopeMismatch.signer.signPendingCandidate(REQUEST_ID));
    assertNotNull(envelopeMismatch.transientJwt.get());
    assertFalse(envelopeFailure.toString().contains(envelopeMismatch.transientJwt.get()));
    assertNull(envelopeFailure.getCause());
    verify(envelopeMismatch.envelopeService)
        .sealPendingCandidate(
            eq(REQUEST_ID), any(CallerIdentity.class), eq(envelopeMismatch.transientJwt.get()));
  }

  @Test
  void rejectsSealedEnvelopeExpiryThatDiffersFromImmutablePendingIdentity() throws Exception {
    Fixture fixture = new Fixture(temporaryDirectory, false);
    fixture.returnMismatchedSealedExpiry();

    AccountGameplayDelegationSigner.SigningUnavailableException failure =
        assertThrows(
            AccountGameplayDelegationSigner.SigningUnavailableException.class,
            () -> fixture.signer.signPendingCandidate(REQUEST_ID));

    assertNull(failure.getCause());
    assertNotNull(fixture.transientJwt.get());
    assertFalse(failure.toString().contains(fixture.transientJwt.get()));
    verify(fixture.issuance)
        .bindSignedCandidate(REQUEST_ID, fixture.transientJwt.get(), GENERATION);
    verify(fixture.envelopeService)
        .sealPendingCandidate(
            eq(REQUEST_ID), any(CallerIdentity.class), eq(fixture.transientJwt.get()));
    verify(fixture.issuance, times(1)).readPendingRegistryCandidate(REQUEST_ID);
  }

  static final class Fixture {
    private final AccountGameplayDelegationIssuanceRepository issuance;
    private final AccountJwtSignerDesiredStateRepository desiredState;
    private final AccountJwtSignerMaterializerTrustBinding materializerTrust;
    private final AccountJwtJwksApiBinding apiBinding;
    private final AccountJwtJwksTrustedSource trustedJwksSource;
    private final AccountGameplayDelegationResponseEnvelopeService envelopeService;
    private CommittedSignerEvidence committedEvidence;
    private final AtomicReference<String> transientJwt = new AtomicReference<>();
    private final AtomicReference<GameSessionAccountDelegationRegistryRecord> boundRecord =
        new AtomicReference<>();
    private final AtomicReference<byte[]> sourceJwks = new AtomicReference<>();
    private final AtomicInteger publicSourceLoadCount = new AtomicInteger();
    private final AtomicBoolean sourceLoadsWereOutsideTransactions = new AtomicBoolean(true);
    private final AccountAuthoritySnapshot authoritySnapshot;
    private final AccountGameplayDelegationPendingIdentity pendingIdentity;
    private final PendingSigningIdentity pendingSigningIdentity;
    private final AccountAuthEvidenceBundle evidenceBundle;
    private final EvidenceBundleReference evidenceReference;
    private final SourceIdentity sourceIdentity;
    private final AccountJwtSignerDesiredStateRepository.Binding accountBinding;
    private final TrustFence trustFence;
    private final AccountMountedJwtSignerBundle.ExpectedIdentity mountedIdentity;
    private final Path privateRoot;
    private final Path publicRoot;
    private final PlatformTransactionManager transactionManager;
    private final AccountGameplayDelegationSigner signer;
    private int mutateSourceOnLoad = Integer.MAX_VALUE;
    private byte[] changedSourceBytes;
    private long sealedExpiryDeltaMillis;

    Fixture(Path temporaryDirectory, boolean expired) throws Exception {
      this(temporaryDirectory, null, null, null, null, CLOCK, expired);
    }

    Fixture(
        Path temporaryDirectory,
        PendingSigningIdentity suppliedPending,
        AccountGameplayDelegationIssuanceRepository suppliedIssuance,
        AccountGameplayDelegationResponseEnvelopeService suppliedEnvelopeService,
        PlatformTransactionManager suppliedTransactionManager,
        Clock suppliedClock)
        throws Exception {
      this(
          temporaryDirectory,
          suppliedPending,
          suppliedIssuance,
          suppliedEnvelopeService,
          suppliedTransactionManager,
          suppliedClock,
          false);
    }

    private Fixture(
        Path temporaryDirectory,
        PendingSigningIdentity suppliedPending,
        AccountGameplayDelegationIssuanceRepository suppliedIssuance,
        AccountGameplayDelegationResponseEnvelopeService suppliedEnvelopeService,
        PlatformTransactionManager suppliedTransactionManager,
        Clock suppliedClock,
        boolean expired)
        throws Exception {
      ensureEphemeralRsaPairs();
      issuance =
          suppliedIssuance == null
              ? mock(AccountGameplayDelegationIssuanceRepository.class)
              : suppliedIssuance;
      desiredState = mock(AccountJwtSignerDesiredStateRepository.class);
      materializerTrust = mock(AccountJwtSignerMaterializerTrustBinding.class);
      apiBinding = mock(AccountJwtJwksApiBinding.class);
      trustedJwksSource = mock(AccountJwtJwksTrustedSource.class);
      envelopeService =
          suppliedEnvelopeService == null
              ? mock(AccountGameplayDelegationResponseEnvelopeService.class)
              : suppliedEnvelopeService;
      transactionManager =
          suppliedTransactionManager == null
              ? new InertTransactionManager()
              : suppliedTransactionManager;
      Clock signingClock = suppliedClock == null ? CLOCK : suppliedClock;

      if (suppliedPending == null) {
        long issuedAt = expired ? NOW.getEpochSecond() - 300L : NOW.getEpochSecond();
        long expiresAt = expired ? NOW.getEpochSecond() - 1L : NOW.getEpochSecond() + 120L;
        pendingIdentity =
            new AccountGameplayDelegationPendingIdentity(
                ISSUANCE_OPERATION_ID,
                REQUEST_ID,
                ACCOUNT_ID,
                WORKLOAD,
                CALLER_CONTEXT_ID,
                AccountGameplayCredentialRequestBindingFixture.binding(),
                REQUEST_DIGEST,
                TOKEN_JTI,
                issuedAt,
                issuedAt,
                expiresAt);
        evidenceBundle = evidenceBundle(pendingIdentity);
        evidenceReference = evidenceReference(evidenceBundle);
        pendingSigningIdentity = pendingSigningIdentity(pendingIdentity, evidenceBundle);
        authoritySnapshot =
            new AccountAuthoritySnapshot(ACCOUNT_ID, 1L, 1L, 1L, 1L, 1L, 1L, Optional.empty());
      } else {
        pendingSigningIdentity = suppliedPending;
        pendingIdentity = suppliedPending.identity();
        evidenceBundle = suppliedPending.evidenceBundle();
        authoritySnapshot = suppliedPending.authoritySnapshot();
        evidenceReference = evidenceReference(evidenceBundle);
      }
      sourceJwks.set(jwksJson(signerPair).getBytes(StandardCharsets.UTF_8));
      sourceIdentity = sourceIdentity();
      accountBinding =
          new AccountJwtSignerDesiredStateRepository.Binding(
              ENVIRONMENT, CLUSTER, NAMESPACE, CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
      trustFence =
          new TrustFence(CLUSTER_UID, NAMESPACE_UID, MATERIALIZER_DIGEST, MATERIALIZER_REVISION);
      mountedIdentity =
          new AccountMountedJwtSignerBundle.ExpectedIdentity(
              ENVIRONMENT,
              CLUSTER,
              NAMESPACE,
              GENERATION_OPERATION_ID.toString(),
              GENERATION,
              KID,
              fingerprint(signerPair));
      privateRoot =
          Files.createDirectory(temporaryDirectory.resolve("private-" + UUID.randomUUID()));
      publicRoot = Files.createDirectory(temporaryDirectory.resolve("public-" + UUID.randomUUID()));
      Files.writeString(
          privateRoot.resolve("current.key"), privateBundleJson(mountedIdentity, signerPair));
      Files.writeString(publicRoot.resolve("jwks.json"), jwksJson(signerPair));

      configureProtectedBindings();
      configureTrustedJwksSource();
      configureCommittedSigner(ownerEvidence(signerPair, GENERATION, KID));
      if (suppliedIssuance == null) configureIssuanceRepository();
      if (suppliedEnvelopeService == null) configureEnvelopeService();
      signer =
          new AccountGameplayDelegationSigner(
              issuance,
              desiredState,
              materializerTrust,
              apiBinding,
              trustedJwksSource,
              envelopeService,
              transactionManager,
              signingClock,
              privateRoot,
              Path.of("current.key"),
              publicRoot,
              Path.of("jwks.json"));
    }

    AccountGameplayDelegationSigner signer() {
      return signer;
    }

    AccountGameplayDelegationIssuanceRepository issuanceRepository() {
      return issuance;
    }

    AccountGameplayDelegationResponseEnvelopeService responseEnvelopeService() {
      return envelopeService;
    }

    PlatformTransactionManager transactionManager() {
      return transactionManager;
    }

    UUID requestId() {
      return pendingIdentity.requestId();
    }

    UUID accountId() {
      return pendingIdentity.accountId();
    }

    AccountJwtSignerDesiredStateRepository desiredStateRepository() {
      return desiredState;
    }

    void withdrawTrust() {
      withdrawMaterializerTrust();
    }

    private void configureProtectedBindings() {
      AccountJwtSignerMaterializerTrustBinding.Binding materializer =
          mock(AccountJwtSignerMaterializerTrustBinding.Binding.class);
      when(materializer.environmentId()).thenReturn(ENVIRONMENT);
      when(materializer.clusterId()).thenReturn(CLUSTER);
      when(materializer.namespace()).thenReturn(NAMESPACE);
      when(materializer.expectedClusterIncarnationUid()).thenReturn(CLUSTER_UID);
      when(materializer.expectedNamespaceUid()).thenReturn(NAMESPACE_UID);
      when(materializer.bindingDigest()).thenReturn(MATERIALIZER_DIGEST);
      when(materializer.configRevision()).thenReturn(MATERIALIZER_REVISION);
      when(materializerTrust.current()).thenReturn(Optional.of(materializer));

      AccountJwtJwksApiBinding.ParsedBinding api =
          mock(AccountJwtJwksApiBinding.ParsedBinding.class);
      when(api.environmentId()).thenReturn(ENVIRONMENT);
      when(api.clusterId()).thenReturn(CLUSTER);
      when(api.namespace()).thenReturn(NAMESPACE);
      when(api.expectedClusterIncarnationUid()).thenReturn(CLUSTER_UID);
      when(api.expectedNamespaceUid()).thenReturn(NAMESPACE_UID);
      when(api.bindingDigest()).thenReturn(API_DIGEST);
      when(api.configRevision()).thenReturn(API_REVISION);
      when(api.apiServer()).thenReturn(java.net.URI.create(API_ORIGIN));
      when(api.servingCaSha256()).thenReturn(SERVING_CA_DIGEST);
      when(apiBinding.current()).thenReturn(api);
    }

    private void configureTrustedJwksSource() {
      when(trustedJwksSource.sourceIdentity())
          .thenAnswer(
              invocation -> {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                  sourceLoadsWereOutsideTransactions.set(false);
                }
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return sourceIdentity;
              });
      when(trustedJwksSource.load())
          .thenAnswer(
              invocation -> {
                if (TransactionSynchronizationManager.isActualTransactionActive()) {
                  sourceLoadsWereOutsideTransactions.set(false);
                }
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                int load = publicSourceLoadCount.incrementAndGet();
                byte[] bytes =
                    load >= mutateSourceOnLoad && changedSourceBytes != null
                        ? changedSourceBytes
                        : sourceJwks.get();
                return new PublicJwksSnapshot(sourceIdentity, bytes);
              });
    }

    private void configureCommittedSigner(CommittedSignerEvidence evidence) {
      committedEvidence = evidence;
      when(desiredState.readCurrentCommittedSigner(
              any(AccountJwtSignerDesiredStateRepository.Binding.class),
              any(TrustFence.class),
              eq(API_DIGEST),
              eq(API_REVISION)))
          .thenReturn(Optional.of(evidence));
    }

    private void replaceCommittedEvidence(CommittedSignerEvidence evidence) {
      committedEvidence = evidence;
      when(desiredState.readCurrentCommittedSigner(
              any(AccountJwtSignerDesiredStateRepository.Binding.class),
              any(TrustFence.class),
              eq(API_DIGEST),
              eq(API_REVISION)))
          .thenReturn(Optional.of(evidence));
    }

    private void mismatchPrivatePromotionObservation() {
      when(committedEvidence.promotion().privatePromotionObservedResourceVersion())
          .thenReturn(Optional.of("14"));
    }

    private void withdrawMaterializerTrust() {
      when(materializerTrust.current()).thenReturn(Optional.empty());
    }

    private void configureIssuanceRepository() {
      when(issuance.readPendingSigningIdentity(REQUEST_ID))
          .thenReturn(pendingSigningIdentity, pendingSigningIdentity);
      when(issuance.bindSignedCandidate(eq(REQUEST_ID), anyString(), eq(GENERATION)))
          .thenAnswer(
              invocation -> {
                String jwt = invocation.getArgument(1);
                transientJwt.set(jwt);
                GameSessionAccountDelegationRegistryRecord record =
                    GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
                        jwt,
                        GENERATION,
                        new IssuanceBinding(
                            ISSUANCE_OPERATION_ID.toString(),
                            REQUEST_ID.toString(),
                            REQUEST_DIGEST,
                            ACCOUNT_ID.toString()),
                        authoritySnapshot,
                        evidenceReference,
                        NOW.getEpochSecond(),
                        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
                boundRecord.set(record);
                return new BoundTokenCandidate(
                    REQUEST_ID, record.tokenHash(), record.kid(), record.signerGeneration());
              });
      when(issuance.readPendingRegistryCandidate(REQUEST_ID))
          .thenAnswer(
              invocation -> {
                GameSessionAccountDelegationRegistryRecord record = boundRecord.get();
                assertNotNull(record);
                return new PendingRegistryCandidate(
                    pendingIdentity,
                    record.tokenHash(),
                    record.kid(),
                    record.signerGeneration(),
                    record.expiresAtEpochSecond(),
                    record.toCanonicalJsonBytes(
                        GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES),
                    authoritySnapshot,
                    evidenceReference);
              });
    }

    private void configureEnvelopeService() {
      when(envelopeService.sealPendingCandidate(
              eq(REQUEST_ID), any(CallerIdentity.class), anyString()))
          .thenAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                CallerIdentity caller = invocation.getArgument(1);
                String jwt = invocation.getArgument(2);
                assertEquals(WORKLOAD, caller.workload());
                assertEquals(CALLER_CONTEXT_ID, caller.contextId());
                assertEquals(transientJwt.get(), jwt);
                return sealedObservation(
                    ISSUANCE_OPERATION_ID,
                    REQUEST_ID,
                    evidenceBundle,
                    Math.multiplyExact(pendingIdentity.expiresAtEpochSecond(), 1_000L)
                        + sealedExpiryDeltaMillis);
              });
    }

    private void returnMissingCommittedEvidence() {
      when(desiredState.readCurrentCommittedSigner(
              any(AccountJwtSignerDesiredStateRepository.Binding.class),
              any(TrustFence.class),
              eq(API_DIGEST),
              eq(API_REVISION)))
          .thenReturn(Optional.empty());
    }

    private void useSourceJwks(KeyPair pair) {
      sourceJwks.set(jwksJson(pair).getBytes(StandardCharsets.UTF_8));
    }

    private void changeSourceJwksOnLoad(int loadNumber, byte[] bytes) {
      mutateSourceOnLoad = loadNumber;
      changedSourceBytes = bytes.clone();
    }

    private void changeCommittedSignerAfterFirstRead(KeyPair changedKey) {
      CommittedSignerEvidence original = ownerEvidence(signerPair, GENERATION, KID);
      CommittedSignerEvidence changed = ownerEvidence(changedKey, "43", "account-key-43");
      when(desiredState.readCurrentCommittedSigner(
              any(AccountJwtSignerDesiredStateRepository.Binding.class),
              any(TrustFence.class),
              eq(API_DIGEST),
              eq(API_REVISION)))
          .thenReturn(Optional.of(original), Optional.of(changed));
    }

    private void changePendingIdentityAfterFirstRead() {
      AccountGameplayDelegationPendingIdentity changedIdentity =
          new AccountGameplayDelegationPendingIdentity(
              ISSUANCE_OPERATION_ID,
              REQUEST_ID,
              ACCOUNT_ID,
              WORKLOAD,
              CALLER_CONTEXT_ID,
              pendingIdentity.credentialRequestBinding(),
              REQUEST_DIGEST,
              UUID.fromString("2a7c3d0c-ab15-4f86-b5af-9e29bc7d3543"),
              pendingIdentity.issuedAtEpochSecond(),
              pendingIdentity.notBeforeEpochSecond(),
              pendingIdentity.expiresAtEpochSecond());
      PendingSigningIdentity changed = pendingSigningIdentity(changedIdentity, evidenceBundle);
      when(issuance.readPendingSigningIdentity(REQUEST_ID))
          .thenReturn(pendingSigningIdentity, changed);
    }

    private void returnMismatchedBoundCandidate() {
      when(issuance.bindSignedCandidate(eq(REQUEST_ID), anyString(), eq(GENERATION)))
          .thenAnswer(
              invocation -> {
                transientJwt.set(invocation.getArgument(1));
                return new BoundTokenCandidate(REQUEST_ID, "f".repeat(64), KID, GENERATION);
              });
    }

    private void returnMismatchedEnvelopeReadback() {
      when(envelopeService.sealPendingCandidate(
              eq(REQUEST_ID), any(CallerIdentity.class), anyString()))
          .thenAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                transientJwt.set(invocation.getArgument(2));
                return sealedObservation(
                    UUID.fromString("3a7c3d0c-ab15-4f86-b5af-9e29bc7d3543"),
                    REQUEST_ID,
                    evidenceBundle,
                    Math.multiplyExact(pendingIdentity.expiresAtEpochSecond(), 1_000L));
              });
    }

    private void returnMismatchedSealedExpiry() {
      sealedExpiryDeltaMillis = 1L;
    }
  }

  private static PendingSigningIdentity pendingSigningIdentity(
      AccountGameplayDelegationPendingIdentity identity, AccountAuthEvidenceBundle bundle) {
    PendingSigningIdentity pending = mock(PendingSigningIdentity.class);
    when(pending.identity()).thenReturn(identity);
    when(pending.authoritySnapshot())
        .thenReturn(
            new AccountAuthoritySnapshot(ACCOUNT_ID, 1L, 1L, 1L, 1L, 1L, 1L, Optional.empty()));
    when(pending.evidenceBundle()).thenReturn(bundle);
    return pending;
  }

  private static AccountAuthEvidenceBundle evidenceBundle(
      AccountGameplayDelegationPendingIdentity identity) {
    String issuerStream = "account:auth-authority:v1:issuer/firemud-account-service";
    String accountStream = "account:auth-authority:v1:account/" + ACCOUNT_ID;
    return AccountAuthEvidenceBundle.fromOwnerEvaluation(
        new OwnerEvaluation(
            new BundleReference("1", "11", "9", "3"),
            "b".repeat(64),
            "c".repeat(64),
            ACCOUNT_ID,
            new OperationIdentity(
                identity.operationId(),
                identity.requestId(),
                identity.requestDigest(),
                identity.callerWorkload(),
                identity.callerContextId(),
                identity.accountId()),
            new TokenIdentity(
                identity.tokenJti(),
                1L,
                identity.issuedAtEpochSecond(),
                identity.notBeforeEpochSecond(),
                identity.expiresAtEpochSecond()),
            GameSessionAccountDelegationProfile.authorityTuple(1L, 1L),
            1L,
            new AuthoritySourceVersions(1L, 1L, 1L),
            new AccountIdentitySource(
                42L, AccountIdentityProvenance.ACCOUNT_REPOSITORY_INSERT, 42L),
            List.of(
                new OutboxCheckpoint(accountStream, 0L, null, null),
                new OutboxCheckpoint(issuerStream, 0L, null, null))));
  }

  private static EvidenceBundleReference evidenceReference(AccountAuthEvidenceBundle bundle) {
    Map<?, ?> reference = (Map<?, ?>) bundle.fields().get("bundleRef");
    return new EvidenceBundleReference(
        (String) reference.get("bundleVersion"),
        (String) reference.get("sourceVersion"),
        (String) reference.get("sourceFence"),
        (String) reference.get("linearization"),
        bundle.canonicalSha256());
  }

  private static CommittedSignerEvidence ownerEvidence(
      KeyPair pair, String generation, String kid) {
    String publicFingerprint = fingerprint(pair);
    AccountJwtSignerDesiredStateRepository.Binding binding =
        new AccountJwtSignerDesiredStateRepository.Binding(
            ENVIRONMENT, CLUSTER, NAMESPACE, CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
    TrustFence trust =
        new TrustFence(CLUSTER_UID, NAMESPACE_UID, MATERIALIZER_DIGEST, MATERIALIZER_REVISION);
    UUID generationOperation =
        generation.equals(GENERATION)
            ? GENERATION_OPERATION_ID
            : UUID.fromString("9af097ea-b1d1-42ea-9f24-46aeb211a779");
    UUID promotionOperation =
        generation.equals(GENERATION)
            ? PROMOTION_OPERATION_ID
            : UUID.fromString("aaf097ea-b1d1-42ea-9f24-46aeb211a779");
    ActiveSigner active = new ActiveSigner(generation, kid);
    DesiredState desired =
        new DesiredState(
            binding,
            generation.equals(GENERATION) ? 7L : 8L,
            Optional.of(active),
            Optional.of(active),
            Optional.of(generationOperation),
            Optional.empty(),
            Optional.empty());

    GenerationResult generationResult = mock(GenerationResult.class);
    when(generationResult.operationId()).thenReturn(generationOperation);
    when(generationResult.binding()).thenReturn(binding);
    when(generationResult.trustFence()).thenReturn(trust);
    when(generationResult.operationDigest()).thenReturn("2".repeat(64));
    when(generationResult.generationRequestDigest()).thenReturn("3".repeat(64));
    when(generationResult.desiredStateVersion()).thenReturn(6L);
    when(generationResult.privateSecretName())
        .thenReturn(AccountJwtSignerDesiredStateRepository.PRIVATE_SECRET_NAME);
    when(generationResult.secretUid()).thenReturn("66666666-6666-4666-8666-666666666666");
    when(generationResult.expectedPriorResourceVersion()).thenReturn("12");
    when(generationResult.observedResourceVersion()).thenReturn("13");
    when(generationResult.targetGeneration()).thenReturn(generation);
    when(generationResult.targetKid()).thenReturn(kid);
    when(generationResult.targetAlgorithm()).thenReturn("RS256");
    when(generationResult.publicKeyFingerprint()).thenReturn(publicFingerprint);
    when(generationResult.receiptDigest()).thenReturn("4".repeat(64));

    PromotionOperationEvidence promotion = mock(PromotionOperationEvidence.class);
    when(promotion.operationId()).thenReturn(promotionOperation);
    when(promotion.generationOperationId()).thenReturn(generationOperation);
    when(promotion.binding()).thenReturn(binding);
    when(promotion.trustFence()).thenReturn(trust);
    when(promotion.targetGeneration()).thenReturn(generation);
    when(promotion.targetKid()).thenReturn(kid);
    when(promotion.targetAlgorithm()).thenReturn("RS256");
    when(promotion.targetPublicKeyFingerprint()).thenReturn(publicFingerprint);
    when(promotion.requestDigest()).thenReturn("5".repeat(64));
    when(promotion.expectedRecordVersion()).thenReturn(6L);
    when(promotion.expectedPreviousActive()).thenReturn(Optional.empty());
    when(promotion.expectedPreviousPublicKeyFingerprint()).thenReturn(Optional.empty());
    when(promotion.expectedPublishedActive()).thenReturn(Optional.of(active));
    when(promotion.generationOperationDigest()).thenReturn("2".repeat(64));
    when(promotion.generationReceiptDigest()).thenReturn("4".repeat(64));
    when(promotion.secretUid()).thenReturn("66666666-6666-4666-8666-666666666666");
    when(promotion.expectedPrivateResourceVersion()).thenReturn("12");
    when(promotion.apiBindingDigest()).thenReturn(API_DIGEST);
    when(promotion.apiConfigRevision()).thenReturn(API_REVISION);
    when(promotion.publicConfigMapUid()).thenReturn(CONFIG_MAP_UID);
    when(promotion.expectedPublicResourceVersion()).thenReturn("20");
    when(promotion.prepublicationIntentDigest()).thenReturn("6".repeat(64));
    when(promotion.prepublicationReceiptDigest()).thenReturn("7".repeat(64));
    when(promotion.mountedObservationDigest()).thenReturn("8".repeat(64));
    when(promotion.readinessPlanDigest()).thenReturn("9".repeat(64));
    when(promotion.readinessEvidenceDigest()).thenReturn("a".repeat(64));
    when(promotion.status()).thenReturn("COMMITTED");
    when(promotion.privatePromotionDispatched()).thenReturn(true);
    when(promotion.privatePromotionObservedResourceVersion()).thenReturn(Optional.of("13"));
    when(promotion.privatePromotionReceiptDigest()).thenReturn(Optional.of("b".repeat(64)));
    when(promotion.activeJwksObservedResourceVersion()).thenReturn(Optional.of("21"));
    when(promotion.activeJwksPublicDataDigest()).thenReturn(Optional.of("c".repeat(64)));
    when(promotion.activeJwksReceiptDigest()).thenReturn(Optional.of("d".repeat(64)));

    PrivatePromotionReceipt privateReceipt =
        new PrivatePromotionReceipt(promotionOperation, generationOperation, "13", "b".repeat(64));
    ActiveJwksPromotionReceipt publicReceipt =
        new ActiveJwksPromotionReceipt(
            promotionOperation, CONFIG_MAP_UID, "20", "21", "c".repeat(64), "d".repeat(64));
    return new CommittedSignerEvidence(
        promotion, generationResult, desired, privateReceipt, publicReceipt);
  }

  private static SealedCandidateObservation sealedObservation(
      UUID operationId,
      UUID requestId,
      AccountAuthEvidenceBundle bundle,
      long responseRecoveryExpiryEpochMillis) {
    return new SealedCandidateObservation(
        operationId,
        requestId,
        bundle.canonicalSha256(),
        1L,
        responseRecoveryExpiryEpochMillis,
        "9".repeat(64),
        512);
  }

  private static SourceIdentity sourceIdentity() {
    return new SourceIdentity(
        ENVIRONMENT,
        CLUSTER,
        CLUSTER_UID,
        NAMESPACE,
        NAMESPACE_UID,
        CONFIG_MAP_UID,
        API_REVISION,
        API_ORIGIN,
        SERVING_CA_DIGEST);
  }

  private static String privateBundleJson(
      AccountMountedJwtSignerBundle.ExpectedIdentity identity, KeyPair pair) {
    return "{"
        + "\"version\":1,"
        + "\"environmentId\":\""
        + identity.environmentId()
        + "\","
        + "\"clusterId\":\""
        + identity.clusterId()
        + "\","
        + "\"namespace\":\""
        + identity.namespace()
        + "\","
        + "\"operationId\":\""
        + identity.operationId()
        + "\","
        + "\"generation\":\""
        + identity.generation()
        + "\","
        + "\"kid\":\""
        + identity.kid()
        + "\","
        + "\"algorithm\":\"RS256\","
        + "\"privateKeyPkcs8\":\""
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(((RSAPrivateCrtKey) pair.getPrivate()).getEncoded())
        + "\",\"publicKeyFingerprint\":\""
        + identity.publicKeyFingerprint()
        + "\"}";
  }

  private static String jwksJson(KeyPair pair) {
    return jwksJson(pair, KID);
  }

  private static String jwksJson(
      KeyPair firstPair, String firstKid, KeyPair secondPair, String secondKid) {
    return "{\"keys\":["
        + jwkJson(firstPair, firstKid)
        + ","
        + jwkJson(secondPair, secondKid)
        + "]}";
  }

  private static String jwksJson(KeyPair pair, String kid) {
    return "{\"keys\":[" + jwkJson(pair, kid) + "]}";
  }

  private static String jwkJson(KeyPair pair, String kid) {
    RSAPublicKey key = (RSAPublicKey) pair.getPublic();
    return "{"
        + "\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\","
        + "\"kid\":\""
        + kid
        + "\",\"key_ops\":[\"verify\"],"
        + "\"n\":\""
        + base64Url(unsigned(key.getModulus()))
        + "\","
        + "\"e\":\""
        + base64Url(unsigned(key.getPublicExponent()))
        + "\"}";
  }

  private static String fingerprint(KeyPair pair) {
    RSAPublicKey key = (RSAPublicKey) pair.getPublic();
    String material =
        "{\"e\":\""
            + base64Url(unsigned(key.getPublicExponent()))
            + "\",\"kty\":\"RSA\",\"n\":\""
            + base64Url(unsigned(key.getModulus()))
            + "\"}";
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(material.getBytes(StandardCharsets.US_ASCII)));
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    }
  }

  private static byte[] unsigned(BigInteger value) {
    byte[] encoded = value.toByteArray();
    return encoded.length > 1 && encoded[0] == 0
        ? Arrays.copyOfRange(encoded, 1, encoded.length)
        : encoded;
  }

  private static String base64Url(byte[] value) {
    try {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    } finally {
      Arrays.fill(value, (byte) 0);
    }
  }

  private static final class InertTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) {
      return false;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
