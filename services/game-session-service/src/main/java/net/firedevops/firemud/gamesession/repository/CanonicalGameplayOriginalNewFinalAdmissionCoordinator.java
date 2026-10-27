package net.firedevops.firemud.gamesession.repository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalFinalAdmissionConfirmation;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Manual, unregistered composer for the already-installed original-NEW final-admission boundary.
 *
 * <p>It consumes only the original SQL candidate and native acknowledgements. Each owner retains
 * its own authority; this class sequences their currentness checks and exact retries without
 * creating credentials, leases, barriers, or cleanup receipts of its own.
 */
final class CanonicalGameplayOriginalNewFinalAdmissionCoordinator {
  private static final long MAX_SESSION_DURATION_MILLIS =
      CanonicalGameplayOriginalNewAuthenticatedSession.MAX_CANONICAL_SESSION_DURATION_MILLIS;

  private final CanonicalGameplayBindingInventoryRepository inventory;
  private final AccountGameplayAdmissionLeaseClient accountClient;
  private final CanonicalGameplayAccountInitialBarrierRedisOwner accountBarrier;
  private final CanonicalGameplayIssuerAdmissionBarrierRedisOwner issuerBarrier;
  private final CanonicalGameplayRegionBindingBridgeOwner regionBridge;
  private final CanonicalGameplayOriginalNewBoundCredentialVerifier credentialVerifier;
  private final CanonicalGameplayOriginalNewFinalAdmissionRedisOwner finalRedis;
  private final Clock clock;

  CanonicalGameplayOriginalNewFinalAdmissionCoordinator(
      CanonicalGameplayBindingInventoryRepository inventory,
      AccountGameplayAdmissionLeaseClient accountClient,
      CanonicalGameplayAccountInitialBarrierRedisOwner accountBarrier,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner issuerBarrier,
      CanonicalGameplayRegionBindingBridgeOwner regionBridge,
      CanonicalGameplayOriginalNewBoundCredentialVerifier credentialVerifier,
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner finalRedis,
      Clock clock) {
    this.inventory = Objects.requireNonNull(inventory, "Game Session inventory is required");
    this.accountClient = Objects.requireNonNull(accountClient, "Account lease client is required");
    this.accountBarrier =
        Objects.requireNonNull(accountBarrier, "Account INITIAL owner is required");
    this.issuerBarrier =
        Objects.requireNonNull(issuerBarrier, "Issuer admission owner is required");
    this.regionBridge = Objects.requireNonNull(regionBridge, "Region bridge owner is required");
    this.credentialVerifier =
        Objects.requireNonNull(credentialVerifier, "Bound credential verifier is required");
    this.finalRedis = Objects.requireNonNull(finalRedis, "Final admission Redis owner is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
  }

  /**
   * Completes one already-installed original NEW candidate and returns only a redacted internal
   * receipt. Uncertainty preserves the owners' retryable evidence and never invents cleanup.
   */
  FinalAdmissionResult completeOriginalNew(
      CanonicalGameplayAdmissionDecision originalDecision,
      String originalLoginJwt,
      CanonicalGameplayRegionLeaseOwner.Installation privateRegionInstallation,
      long canonicalSessionDurationMillis,
      long absoluteDeadlineMillis) {
    requireNoAmbientSql();
    Objects.requireNonNull(originalDecision, "Original Account decision is required");
    Objects.requireNonNull(
        privateRegionInstallation, "This owner's private region grant is required");
    if (originalLoginJwt == null || originalLoginJwt.isBlank()) {
      throw new IllegalArgumentException("Original LOGIN credential is required");
    }
    if (canonicalSessionDurationMillis <= 0
        || canonicalSessionDurationMillis > MAX_SESSION_DURATION_MILLIS) {
      throw new IllegalArgumentException("Canonical session duration is outside its bound");
    }
    requireDeadline(absoluteDeadlineMillis);

    byte[] originalLeaseBytes = null;
    try {
      String exactLeaseJson = originalDecision.leaseEvidenceJson();
      originalLeaseBytes = exactLeaseJson.getBytes(StandardCharsets.UTF_8);
      if (!Arrays.equals(
          originalLeaseBytes,
          originalDecision.leaseEvidence().canonicalJson().getBytes(StandardCharsets.UTF_8))) {
        throw unavailable();
      }

      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source =
          inventory.readOriginalNewFinalAdmissionSource(originalDecision);
      requireOriginalSource(source, originalDecision);
      Currentness beforeEffects = currentness(source, privateRegionInstallation);
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource currentSource =
          readSameSource(source, originalDecision, false);
      source = currentSource;

      OriginalFinalAdmissionConfirmation recoveredConfirmation = null;
      if (source.transitionStatus()
          == CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED) {
        // Account confirmation must recover a committed-but-unconfirmed result before issue can
        // return the unchanged ACTIVE credential and handle.
        requireDeadline(absoluteDeadlineMillis);
        recoveredConfirmation =
            accountClient.confirmOriginalNewGameplayAdmission(originalLoginJwt, originalLeaseBytes);
        requireConfirmation(source, recoveredConfirmation, null, originalDecision);
        Currentness afterRecovery = currentness(source, privateRegionInstallation);
        requireSameCurrentness(beforeEffects, afterRecovery);
        source = readSameSource(source, originalDecision, false);
        beforeEffects = afterRecovery;
      }

      try (OriginalNewCredential credential =
          accountClient.issueOriginalNewBoundGameplayCredential(
              originalLoginJwt, originalLeaseBytes)) {
        requireCredentialLease(source, originalDecision, credential);
        try (CanonicalGameplayOriginalNewAuthenticatedSession.Template template =
            CanonicalGameplayOriginalNewAuthenticatedSession.template(
                source.candidate(),
                credential,
                credentialVerifier,
                canonicalSessionDurationMillis)) {
          CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
              afterCredential = readSameSource(source, originalDecision, false);
          Currentness beforeFinalCas = currentness(afterCredential, privateRegionInstallation);
          requireSameCurrentness(beforeEffects, beforeFinalCas);
          source = afterCredential;
          requireDeadline(absoluteDeadlineMillis);

          try (CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback finalReadback =
              finalRedis.finalizeOriginalNewOnce(
                  source,
                  template,
                  requireAccountOpen(beforeFinalCas.accountOpen, source.candidate()),
                  beforeFinalCas.issuerOpen,
                  absoluteDeadlineMillis)) {
            requireFinalReadback(source, finalReadback, credential, canonicalSessionDurationMillis);
            Currentness afterFinalCas = currentness(source, privateRegionInstallation);
            requireSameCurrentness(beforeFinalCas, afterFinalCas);

            boolean committedByThisAttempt =
                source.transitionStatus()
                    == CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL;
            if (committedByThisAttempt) {
              inventory.commitOriginalNewFinalAdmission(finalReadback);
              CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
                  committedSource = readSameSource(source, originalDecision, true);
              if (committedSource.transitionStatus()
                  != CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED) {
                throw unavailable();
              }
              source = committedSource;
            }

            CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionReceipt
                finalReceipt =
                    source
                        .finalReceipt()
                        .orElseThrow(
                            CanonicalGameplayOriginalNewFinalAdmissionCoordinator::unavailable);
            requireReadbackMatchesReceipt(finalReadback, finalReceipt);
            Currentness beforeConfirmation = currentness(source, privateRegionInstallation);
            requireSameCurrentness(afterFinalCas, beforeConfirmation, committedByThisAttempt);

            requireDeadline(absoluteDeadlineMillis);
            OriginalFinalAdmissionConfirmation confirmation =
                accountClient.confirmOriginalNewGameplayAdmission(
                    originalLoginJwt, originalLeaseBytes);
            requireConfirmation(source, confirmation, credential, originalDecision);

            CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
                afterConfirmation = readSameSource(source, originalDecision, false);
            Currentness afterConfirmationCurrentness =
                currentness(afterConfirmation, privateRegionInstallation);
            requireSameCurrentness(beforeConfirmation, afterConfirmationCurrentness);
            source = afterConfirmation;
            if (recoveredConfirmation != null
                && !sameConfirmation(recoveredConfirmation, confirmation)) {
              throw unavailable();
            }
            return new FinalAdmissionResult(
                source.candidate().bindingDecisionId(),
                finalReadback.admissionAtMillis(),
                finalReadback.continuityExpiresAtMillis(),
                finalReadback.canonicalSessionDurationMillis(),
                finalReadback.finalSessionSha256());
          }
        }
      }
    } catch (RuntimeException uncertain) {
      throw unavailable(uncertain);
    } finally {
      wipe(originalLeaseBytes);
    }
  }

  private Currentness currentness(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayRegionLeaseOwner.Installation privateRegionInstallation) {
    requireNoAmbientSql();
    CanonicalGameplayOriginalNewCandidate candidate = source.candidate();
    CanonicalGameplayBindingIdentity identity = candidate.bindingIdentity();
    CanonicalGameplayAccountInitialOpenProof proof =
        accountBarrier.verifyCurrentOpenForTarget(
            identity.accountId(), identity.tenantId(), identity.playableStateNamespaceId());
    if (!(proof instanceof CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen)
        || !accountOpen.accountId().equals(identity.accountId())
        || !accountOpen.tenantId().equals(identity.tenantId())
        || !accountOpen.playableStateNamespaceId().equals(identity.playableStateNamespaceId())
        || !"OPEN".equals(accountOpen.exactFields().get("state"))) {
      throw unavailable();
    }
    requireCurrentOpenWindow(accountOpen);

    CanonicalGameplayIssuerBindingLocalAcknowledgement issuerAcknowledgement =
        source.issuerAcknowledgement();
    requireNativeIssuerAcknowledgement(source, issuerAcknowledgement);
    CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen =
        issuerBarrier.initializeAndRead(issuerAcknowledgement.operationId());
    CanonicalGameplayIssuerAdmissionBarrierValue issuerValue = issuerOpen.value();
    byte[] expectedPayload = issuerValue.exactBytes();
    byte[] observedPayload = issuerOpen.exactPhysicalPayload();
    try {
      if (issuerValue.state() != CanonicalGameplayIssuerAdmissionBarrierValue.State.OPEN
          || issuerValue.initializer() == null
          || !identity.issuerId().equals(issuerValue.issuerId())
          || !identity.tenantId().equals(issuerValue.tenantId())
          || !identity.playableStateNamespaceId().equals(issuerValue.playableStateNamespaceId())
          || !Arrays.equals(expectedPayload, observedPayload)) {
        throw unavailable();
      }
    } finally {
      wipe(expectedPayload);
      wipe(observedPayload);
    }

    CanonicalGameplayRegionLeaseOwner.OriginalNewFinalBindingCurrentProof regionCurrent =
        regionBridge.revalidateOriginalNewFinalBinding(privateRegionInstallation, source);
    return new Currentness(accountOpen, issuerOpen, regionCurrent);
  }

  private CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource
      readSameSource(
          CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource expected,
          CanonicalGameplayAdmissionDecision originalDecision,
          boolean allowProvisionalToCommitted) {
    requireNoAmbientSql();
    CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource current =
        inventory.readOriginalNewFinalAdmissionSource(originalDecision);
    requireSameSource(expected, current, allowProvisionalToCommitted);
    return current;
  }

  private static void requireOriginalSource(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayAdmissionDecision originalDecision) {
    CanonicalGameplayOriginalNewCandidate candidate = source.candidate();
    byte[] candidateBytes = candidate.canonicalBytes();
    try {
      if (source.candidateInstallationProvenance()
              != CanonicalGameplayBindingInventoryRepository
                  .OriginalNewCandidateInstallationProvenance.HISTORICAL_READBACK_CONFIRMED
          || !candidate.matchesOriginalDecision(originalDecision)
          || !candidate.bindingDecisionId().equals(originalDecision.bindingDecisionId())
          || !candidate.exactlyMatches(candidateBytes)
          || !source
              .candidateDigest()
              .equals(CanonicalGameplayIssuerMemberV2Codec.sha256(candidateBytes))
          || !source.originalCandidateReadbackDigest().matches("sha256:[0-9a-f]{64}")
          || candidate.leaseEvidence().leaseKind()
              != net.firedevops.firemud.common.account.admission
                  .AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
          || !"PUBLIC_PRODUCTION".equals(candidate.leaseEvidence().carrier().get("mode"))) {
        throw unavailable();
      }
      requireNativeIssuerAcknowledgement(source, source.issuerAcknowledgement());
      if ((source.transitionStatus() == CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED)
          != source.finalReceipt().isPresent()) {
        throw unavailable();
      }
    } finally {
      wipe(candidateBytes);
    }
  }

  private static void requireNativeIssuerAcknowledgement(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayIssuerBindingLocalAcknowledgement acknowledgement) {
    var evidence = acknowledgement.evidence();
    CanonicalGameplayOriginalNewCandidate candidate = source.candidate();
    if (!acknowledgement.operationId().equals(evidence.operationId())
        || !evidence.transitionId().equals(candidate.transition().transitionId())
        || !evidence.issuerId().equals(candidate.bindingIdentity().issuerId())
        || !Arrays.equals(evidence.bindingRef(), candidate.bindingIdentity().bindingRef().bytes())
        || !evidence.bindingGeneration().equals(candidate.transition().bindingGeneration())
        || !evidence.reservation().reservationId().equals(candidate.reservation().reservationId())
        || !evidence
            .reservation()
            .reservationFence()
            .equals(candidate.reservation().reservationFence())
        || !"RESERVED".equals(evidence.reservation().lifecycle())
        || acknowledgement.digest() == null
        || !acknowledgement.digest().matches("sha256:[0-9a-f]{64}")) {
      throw unavailable();
    }
  }

  private static void requireSameSource(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource expected,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource current,
      boolean allowProvisionalToCommitted) {
    CanonicalGameplayBindingTransitionSnapshot.Status expectedStatus = expected.transitionStatus();
    CanonicalGameplayBindingTransitionSnapshot.Status currentStatus = current.transitionStatus();
    boolean statusMatches =
        expectedStatus == currentStatus
            || (allowProvisionalToCommitted
                && expectedStatus == CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
                && currentStatus == CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED);
    if (!statusMatches
        || !expected.candidate().equals(current.candidate())
        || !expected.candidateDigest().equals(current.candidateDigest())
        || !expected
            .originalCandidateReadbackDigest()
            .equals(current.originalCandidateReadbackDigest())
        || !sameRegionAcknowledgement(
            expected.regionAcknowledgement(),
            current.regionAcknowledgement(),
            allowProvisionalToCommitted)
        || !Arrays.equals(
            expected.issuerAcknowledgement().exactBytes(),
            current.issuerAcknowledgement().exactBytes())
        || (expected.finalReceipt().isPresent()
            && !expected.finalReceipt().equals(current.finalReceipt()))) {
      throw unavailable();
    }
    if (expectedStatus == CanonicalGameplayBindingTransitionSnapshot.Status.COMMITTED
        && !expected.finalReceipt().equals(current.finalReceipt())) {
      throw unavailable();
    }
  }

  static boolean sameRegionAcknowledgement(
      CanonicalGameplayBindingRegionBridgeObligation left,
      CanonicalGameplayBindingRegionBridgeObligation right,
      boolean allowCommitRevisionAdvance) {
    if (!allowCommitRevisionAdvance || left.inventoryRevision().equals(right.inventoryRevision())) {
      return left.equals(right);
    }
    // The inventory clock is global: another binding may advance it before this commit.
    if (right.inventoryRevision().compareTo(left.inventoryRevision()) <= 0) {
      return false;
    }
    var normalized =
        new CanonicalGameplayBindingRegionBridgeObligation(
            left.transitionId(),
            left.bindingRef(),
            left.bindingGeneration(),
            left.accountId(),
            left.tenantId(),
            left.gameInstanceId(),
            left.runtimeGameInstanceId(),
            left.sessionId(),
            left.regionId(),
            left.regionEpoch(),
            left.status(),
            right.inventoryRevision(),
            left.bridgeOperationId(),
            left.bridgePreimageState(),
            left.runtimeEntityId(),
            left.entityProjectionGeneration(),
            left.entityProjectionFence(),
            left.entityWorkloadNamespace(),
            left.actorOrigin(),
            left.entityReferenceDigest(),
            left.worldSourceDigest(),
            left.canonicalRegionId(),
            left.worldLifecycleEpoch(),
            left.worldLifecycleRowVersion(),
            left.worldCaptureId(),
            left.worldGraphSha256(),
            left.worldPreparationInputDigest(),
            left.executorFence(),
            left.leaseTokenCorrelation(),
            left.redisReadbackDigest(),
            left.acknowledgedAt());
    return normalized.equals(right);
  }

  private CanonicalGameplayAccountInitialOpenProof.CurrentOpen requireAccountOpen(
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayOriginalNewCandidate candidate) {
    if (!accountOpen.accountId().equals(candidate.bindingIdentity().accountId())
        || !accountOpen.tenantId().equals(candidate.bindingIdentity().tenantId())
        || !accountOpen
            .playableStateNamespaceId()
            .equals(candidate.bindingIdentity().playableStateNamespaceId())) {
      throw unavailable();
    }
    requireCurrentOpenWindow(accountOpen);
    return accountOpen;
  }

  private static void requireCredentialLease(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayAdmissionDecision originalDecision,
      OriginalNewCredential credential) {
    if (!credential.originalLeaseId().equals(originalDecision.leaseId())
        || credential.originalLeaseFence() != originalDecision.leaseFence().longValueExact()
        || !source.candidate().leaseEvidence().hasSameIdentity(originalDecision.leaseEvidence())) {
      throw unavailable();
    }
  }

  private static void requireFinalReadback(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback readback,
      OriginalNewCredential credential,
      long canonicalSessionDurationMillis) {
    var session = readback.session();
    if (!source.candidate().equals(session.originalCandidate())
        || !source.candidate().equals(readback.source().candidate())
        || !source.candidateDigest().equals(readback.source().candidateDigest())
        || !source
            .originalCandidateReadbackDigest()
            .equals(readback.source().originalCandidateReadbackDigest())
        || !credential.tokenSha256().equals(session.authTokenHash())
        || !Long.toString(credential.issuanceFence()).equals(session.issuanceFence())
        || !"1".equals(session.tokenGeneration())
        || readback.canonicalSessionDurationMillis() != canonicalSessionDurationMillis
        || session.canonicalSessionDurationMillis() != canonicalSessionDurationMillis
        || readback.admissionAtMillis() <= 0
        || readback.continuityExpiresAtMillis()
            != readback.admissionAtMillis() + canonicalSessionDurationMillis) {
      throw unavailable();
    }
  }

  private static void requireReadbackMatchesReceipt(
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback readback,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionReceipt receipt) {
    if (!receipt.finalSessionSha256().equals(readback.finalSessionSha256())
        || receipt.admissionAtMillis() != readback.admissionAtMillis()
        || receipt.continuityExpiresAtMillis() != readback.continuityExpiresAtMillis()
        || receipt.sessionDurationMillis() != readback.canonicalSessionDurationMillis()) {
      throw unavailable();
    }
  }

  private static void requireConfirmation(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      OriginalFinalAdmissionConfirmation confirmation,
      OriginalNewCredential credential,
      CanonicalGameplayAdmissionDecision originalDecision) {
    CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionReceipt receipt =
        source
            .finalReceipt()
            .orElseThrow(CanonicalGameplayOriginalNewFinalAdmissionCoordinator::unavailable);
    // Account's confirmation digest binds its final-read and ACTIVE-registry evidence in
    // Account's own domain; it is not the digest of the physical Game Session payload.
    if (!confirmation.originalLeaseId().equals(originalDecision.leaseId())
        || confirmation.originalLeaseFence() != originalDecision.leaseFence().longValueExact()
        || confirmation.admissionAtMillis() != receipt.admissionAtMillis()
        || confirmation.continuityExpiresAtMillis() != receipt.continuityExpiresAtMillis()
        || (credential != null && !confirmation.lineageId().equals(credential.lineageId()))) {
      throw unavailable();
    }
  }

  private static boolean sameConfirmation(
      OriginalFinalAdmissionConfirmation left, OriginalFinalAdmissionConfirmation right) {
    return left.lineageId().equals(right.lineageId())
        && left.originalLeaseId().equals(right.originalLeaseId())
        && left.originalLeaseFence() == right.originalLeaseFence()
        && left.admissionAtMillis() == right.admissionAtMillis()
        && left.continuityExpiresAtMillis() == right.continuityExpiresAtMillis()
        && left.confirmationReadbackSha256().equals(right.confirmationReadbackSha256());
  }

  private static void requireSameCurrentness(Currentness expected, Currentness current) {
    requireSameCurrentness(expected, current, false);
  }

  private static void requireSameCurrentness(
      Currentness expected, Currentness current, boolean allowCommitRevisionAdvance) {
    if (!sameAccountOpen(expected.accountOpen, current.accountOpen)
        || !Arrays.equals(
            expected.issuerOpen.exactPhysicalPayload(), current.issuerOpen.exactPhysicalPayload())
        || (!allowCommitRevisionAdvance
            && !expected.regionCurrent.sameRetainedBindingEvidenceAs(current.regionCurrent))) {
      throw unavailable();
    }
  }

  private static boolean sameAccountOpen(
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen left,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen right) {
    return left.accountId().equals(right.accountId())
        && left.tenantId().equals(right.tenantId())
        && left.playableStateNamespaceId().equals(right.playableStateNamespaceId())
        && left.key().equals(right.key())
        && left.initializerId().equals(right.initializerId())
        && left.originalRequestId().equals(right.originalRequestId())
        && left.originalCallerContextId().equals(right.originalCallerContextId())
        && left.originalAttemptFence() == right.originalAttemptFence()
        && left.sourceControlEpoch().equals(right.sourceControlEpoch())
        && left.accountLoginEvidenceDigest().equals(right.accountLoginEvidenceDigest())
        && left.sourceControlDigest().equals(right.sourceControlDigest())
        && left.incarnationDigest().equals(right.incarnationDigest())
        && left.exactFields().equals(right.exactFields());
  }

  private void requireCurrentOpenWindow(
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen) {
    long now = clock.millis();
    if (now < accountOpen.issuedAtEpochMillis() || now >= accountOpen.expiresAtEpochMillis()) {
      throw unavailable();
    }
  }

  private void requireDeadline(long absoluteDeadlineMillis) {
    if (absoluteDeadlineMillis <= 0 || clock.millis() >= absoluteDeadlineMillis) {
      throw unavailable();
    }
  }

  private static void requireNoAmbientSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("Original NEW final admission requires no ambient SQL scope");
    }
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) Arrays.fill(bytes, (byte) 0);
  }

  private static IllegalStateException unavailable() {
    return unavailable(null);
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException(
        "Original NEW final admission could not be safely confirmed; retry the exact original operation",
        cause);
  }

  private static final class Currentness {
    private final CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen;
    private final CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen;
    private final CanonicalGameplayRegionLeaseOwner.OriginalNewFinalBindingCurrentProof
        regionCurrent;

    private Currentness(
        CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
        CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen,
        CanonicalGameplayRegionLeaseOwner.OriginalNewFinalBindingCurrentProof regionCurrent) {
      this.accountOpen = accountOpen;
      this.issuerOpen = issuerOpen;
      this.regionCurrent = regionCurrent;
    }
  }

  /** Minimal owner-internal result; credential bytes and the opaque handle never escape here. */
  static final class FinalAdmissionResult {
    private final UUID transitionId;
    private final long admissionAtMillis;
    private final long continuityExpiresAtMillis;
    private final long canonicalSessionDurationMillis;
    private final String finalSessionSha256;

    private FinalAdmissionResult(
        UUID transitionId,
        long admissionAtMillis,
        long continuityExpiresAtMillis,
        long canonicalSessionDurationMillis,
        String finalSessionSha256) {
      this.transitionId = Objects.requireNonNull(transitionId, "transitionId");
      this.admissionAtMillis = admissionAtMillis;
      this.continuityExpiresAtMillis = continuityExpiresAtMillis;
      this.canonicalSessionDurationMillis = canonicalSessionDurationMillis;
      this.finalSessionSha256 = Objects.requireNonNull(finalSessionSha256, "finalSessionSha256");
    }

    UUID transitionId() {
      return transitionId;
    }

    long admissionAtMillis() {
      return admissionAtMillis;
    }

    long continuityExpiresAtMillis() {
      return continuityExpiresAtMillis;
    }

    long canonicalSessionDurationMillis() {
      return canonicalSessionDurationMillis;
    }

    String finalSessionSha256() {
      return finalSessionSha256;
    }

    @Override
    public String toString() {
      return "CanonicalGameplayOriginalNewFinalAdmissionCoordinator.FinalAdmissionResult[confirmed,redacted]";
    }
  }
}
