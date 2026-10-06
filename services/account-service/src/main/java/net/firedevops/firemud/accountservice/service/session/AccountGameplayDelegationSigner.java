package net.firedevops.firemud.accountservice.service.session;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.accountservice.config.AccountJwtJwksApiBinding;
import net.firedevops.firemud.accountservice.config.AccountJwtSignerMaterializerTrustBinding;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.BoundTokenCandidate;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingRegistryCandidate;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingSigningIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.CallerIdentity;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.ActiveSigner;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CommittedSignerEvidence;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.CustodyMode;
import net.firedevops.firemud.accountservice.repository.AccountJwtSignerDesiredStateRepository.TrustFence;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.ExplicitRouteProfilePolicy;
import net.firedevops.firemud.common.security.AccountAsymmetricJwtVerifier.VerifiedClaims;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.AccountPublicJwksCache;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.PublicJwksSnapshot;
import net.firedevops.firemud.common.security.AccountPublicJwksCache.SourceIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationJwtProfileValidator;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Account-internal producer for one exact pending Game Session delegation candidate.
 *
 * <p>This class is intentionally not a Spring component and declares no RPC. It re-reads the
 * durable unbound issuance identity, requires Account's exact current COMMITTED signer owner
 * evidence, validates the active mounted key against Account's authenticated current public
 * projection, and seals that exact pending candidate for Account-owned recovery. It has no active
 * registry transition or caller-facing credential response.
 */
public final class AccountGameplayDelegationSigner {
  private static final Path PRIVATE_MOUNT_ROOT = Path.of("/var/run/secrets/firemud/jwt");
  private static final Path PRIVATE_ACTIVE_BUNDLE = Path.of("current.key");
  private static final Path PUBLIC_MOUNT_ROOT = Path.of("/var/run/secrets/firemud/jwks");
  private static final Path PUBLIC_JWKS = Path.of("jwks.json");
  private static final Duration PUBLIC_KEY_CACHE_AGE = Duration.ofSeconds(30);
  private static final ExplicitRouteProfilePolicy GSA_SIGNING_POLICY =
      new ExplicitRouteProfilePolicy(
          "account-gameplay-delegation-signing-self-check",
          GameSessionAccountDelegationProfile.PROFILE,
          GameSessionAccountDelegationProfile.TYPE,
          GameSessionAccountDelegationProfile.ISSUER,
          GameSessionAccountDelegationProfile.AUDIENCE,
          GameSessionAccountDelegationJwtProfileValidator.REQUIRED_CLAIMS,
          GameSessionAccountDelegationJwtProfileValidator.OPTIONAL_CLAIMS,
          GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS,
          5,
          GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES,
          GameSessionAccountDelegationJwtProfileValidator::validateClaims);

  private final AccountGameplayDelegationIssuanceRepository issuanceRepository;
  private final AccountJwtSignerDesiredStateRepository desiredStateRepository;
  private final AccountJwtSignerMaterializerTrustBinding materializerTrustBinding;
  private final AccountJwtJwksApiBinding apiBinding;
  private final AccountJwtJwksTrustedSource trustedJwksSource;
  private final AccountGameplayDelegationResponseEnvelopeService responseEnvelopeService;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;
  private final Path privateMountRoot;
  private final Path privateBundlePath;
  private final Path publicMountRoot;
  private final Path publicJwksPath;

  public AccountGameplayDelegationSigner(
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtJwksApiBinding apiBinding,
      AccountJwtJwksTrustedSource trustedJwksSource,
      AccountGameplayDelegationResponseEnvelopeService responseEnvelopeService,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this(
        issuanceRepository,
        desiredStateRepository,
        materializerTrustBinding,
        apiBinding,
        trustedJwksSource,
        responseEnvelopeService,
        transactionManager,
        clock,
        PRIVATE_MOUNT_ROOT,
        PRIVATE_ACTIVE_BUNDLE,
        PUBLIC_MOUNT_ROOT,
        PUBLIC_JWKS);
  }

  AccountGameplayDelegationSigner(
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountJwtSignerDesiredStateRepository desiredStateRepository,
      AccountJwtSignerMaterializerTrustBinding materializerTrustBinding,
      AccountJwtJwksApiBinding apiBinding,
      AccountJwtJwksTrustedSource trustedJwksSource,
      AccountGameplayDelegationResponseEnvelopeService responseEnvelopeService,
      PlatformTransactionManager transactionManager,
      Clock clock,
      Path privateMountRoot,
      Path privateBundlePath,
      Path publicMountRoot,
      Path publicJwksPath) {
    this.issuanceRepository =
        Objects.requireNonNull(issuanceRepository, "Account issuance repository is required");
    this.desiredStateRepository =
        Objects.requireNonNull(desiredStateRepository, "Account signer owner is required");
    this.materializerTrustBinding =
        Objects.requireNonNull(
            materializerTrustBinding, "Protected materializer trust is required");
    this.apiBinding =
        Objects.requireNonNull(apiBinding, "Protected Account JWKS API binding is required");
    this.trustedJwksSource =
        Objects.requireNonNull(trustedJwksSource, "Account authenticated JWKS source is required");
    this.responseEnvelopeService =
        Objects.requireNonNull(
            responseEnvelopeService, "Account response envelope owner is required");
    this.accountTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    this.privateMountRoot =
        Objects.requireNonNull(privateMountRoot, "Private mount root is required");
    this.privateBundlePath =
        Objects.requireNonNull(privateBundlePath, "Private bundle path is required");
    this.publicMountRoot = Objects.requireNonNull(publicMountRoot, "Public mount root is required");
    this.publicJwksPath = Objects.requireNonNull(publicJwksPath, "Public JWKS path is required");
  }

  /**
   * Signs and seals one Account-selected pending issuance by request ID. The result contains only
   * non-authorizing candidate and encrypted-envelope metadata, never a compact JWT.
   */
  CandidateOutcome signPendingCandidate(UUID requestId) {
    if (requestId == null) {
      throw unavailable();
    }

    byte[] transientCompactJwt = null;
    byte[] firstJwksBytes = null;
    byte[] finalJwksBytes = null;
    AtomicReference<byte[]> compactTokenCapture = new AtomicReference<>();
    try {
      InvocationIdentity initial =
          accountTransaction.execute(
              status -> {
                CurrentSigner signer = currentCommittedSigner();
                PendingSigningIdentity pending = requireCurrentPendingIdentity(requestId);
                requireUnexpired(pending);
                return new InvocationIdentity(signer, pending);
              });
      if (initial == null) {
        throw unavailable();
      }

      SourceIdentity sourcePin = trustedJwksSource.sourceIdentity();
      PublicJwksSnapshot initialSnapshot = trustedJwksSource.load();
      firstJwksBytes =
          requireExactTrustedSnapshot(initial.currentSigner(), sourcePin, initialSnapshot);
      byte[] initialJwksProjection = firstJwksBytes;
      AccountPublicJwksCache keyCache =
          new AccountPublicJwksCache(
              () -> {
                PublicJwksSnapshot observed = trustedJwksSource.load();
                byte[] observedBytes =
                    requireExactTrustedSnapshot(initial.currentSigner(), sourcePin, observed);
                try {
                  if (!Arrays.equals(initialJwksProjection, observedBytes)) {
                    throw new AccountPublicJwksCache.InvalidJwksException();
                  }
                  return observed;
                } finally {
                  wipe(observedBytes);
                }
              },
              sourcePin,
              clock,
              PUBLIC_KEY_CACHE_AGE);
      keyCache.keyFor(initial.currentSigner().expectedIdentity().kid());

      AccountMountedJwtSignerBundle.SignedDelegationDigest digest =
          AccountMountedJwtSignerBundle.signCommittedGameplayDelegationDigest(
              privateMountRoot,
              privateBundlePath,
              publicMountRoot,
              publicJwksPath,
              initial.currentSigner().expectedIdentity(),
              new AccountMountedJwtSignerBundle.DelegationSigningSpec(
                  initial.pending().identity(), initial.pending().authoritySnapshot()),
              (signed, exactCompactJwt) -> {
                requireSignedIdentity(initial, signed);
                byte[] transientCopy = exactCompactJwt.clone();
                if (!compactTokenCapture.compareAndSet(null, transientCopy)) {
                  wipe(transientCopy);
                  throw unavailable();
                }
              });
      transientCompactJwt = compactTokenCapture.getAndSet(null);
      if (transientCompactJwt == null
          || !sha256(transientCompactJwt).equals(digest.compactTokenSha256())) {
        throw unavailable();
      }
      String compactJwt = exactAscii(transientCompactJwt);

      VerifiedClaims verified =
          new AccountAsymmetricJwtVerifier(keyCache, clock).verify(compactJwt, GSA_SIGNING_POLICY);
      requireVerifiedClaims(initial, digest, verified);

      PublicJwksSnapshot finalSnapshot = trustedJwksSource.load();
      finalJwksBytes =
          requireExactTrustedSnapshot(initial.currentSigner(), sourcePin, finalSnapshot);
      if (!Arrays.equals(firstJwksBytes, finalJwksBytes)) {
        throw unavailable();
      }

      CandidateOutcome outcome =
          accountTransaction.execute(
              status ->
                  bindAndSealWithinTransaction(requestId, compactJwt, initial, digest, sourcePin));
      if (outcome == null) {
        throw unavailable();
      }
      return outcome;
    } catch (RuntimeException failure) {
      // Never preserve implementation, parser, API, or JWT-bearing causes across this boundary.
      throw unavailable();
    } finally {
      wipe(transientCompactJwt);
      wipe(compactTokenCapture.getAndSet(null));
      wipe(firstJwksBytes);
      wipe(finalJwksBytes);
    }
  }

  /**
   * Revalidates signer-owned candidate evidence inside the caller's writable Account transaction.
   * This method performs no signing, external JWKS/API request, mutation, or authorization effect.
   */
  void requireCurrentCommittedSigner(PendingCandidateVerificationProof proof) {
    if (proof == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw unavailable();
    }
    VerifiedPendingIdentity identity = proof.identity();
    long now = Math.floorDiv(clock.millis(), 1_000L);
    long latestIssuedAt = now > Long.MAX_VALUE - 5L ? Long.MAX_VALUE : now + 5L;
    if (now <= 0L
        || identity.notBeforeEpochSecond() > now
        || identity.issuedAtEpochSecond() > latestIssuedAt
        || identity.expiresAtEpochSecond() <= now) {
      throw unavailable();
    }

    CurrentSigner current = currentCommittedSigner();
    SourceIdentity originalSourceIdentity = proof.signerCorrespondence().publicJwksSourceIdentity();
    requireCurrentSourceIdentity(current, originalSourceIdentity);
    if (!signerCorrespondence(current, originalSourceIdentity)
        .equals(proof.signerCorrespondence())) {
      throw unavailable();
    }
  }

  /**
   * Revalidates the exact signer correspondence captured by durable COMMITTED evidence inside a
   * caller-owned writable Account transaction. It neither verifies a fabricated DTO nor signs,
   * fetches public response data, mutates the operation, or authorizes token use.
   */
  void requireCurrentCommittedSigner(CommittedCandidateVerificationData committed) {
    if (committed == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw unavailable();
    }
    AuthenticatedSignerCorrespondence original = requireCommittedCandidateIdentity(committed);

    CurrentSigner current = currentCommittedSigner();
    SourceIdentity sourceIdentity = original.publicJwksSourceIdentity();
    requireCurrentSourceIdentity(current, sourceIdentity);
    if (!signerCorrespondence(current, sourceIdentity).equals(original)) throw unavailable();
  }

  /**
   * Revalidates COMMITTED signer metadata while allowing its generation to be historical for
   * bounded response recovery. The current Account owner must still have one coherent COMMITTED
   * ACTIVE signer under the exact original protected trust/API/source identity. The historical
   * token key is checked separately against today's owner/readback-proved JWKS outside SQL.
   */
  void requireCurrentCommittedRecoverySigner(CommittedCandidateVerificationData committed) {
    if (committed == null
        || !TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw unavailable();
    }
    AuthenticatedSignerCorrespondence original = requireCommittedCandidateIdentity(committed);

    CurrentSigner current = currentCommittedSigner();
    requireCurrentSourceIdentity(current, original.publicJwksSourceIdentity());
    if (!current.binding().equals(original.binding())
        || !current.trust().equals(original.trustFence())
        || !current.api().bindingDigest().equals(original.apiBindingDigest())
        || !current.api().configRevision().equals(original.apiConfigRevision())) {
      throw unavailable();
    }
  }

  /** Confirms the original committed key has not been pruned or substituted in current JWKS. */
  void requireRetainedCommittedPublicKey(CommittedCandidateVerificationData committed) {
    if (committed == null || TransactionSynchronizationManager.isActualTransactionActive()) {
      throw unavailable();
    }
    AuthenticatedSignerCorrespondence original = requireCommittedCandidateIdentity(committed);
    Optional<String> currentFingerprint;
    try {
      if (!original.publicJwksSourceIdentity().equals(trustedJwksSource.sourceIdentity())) {
        throw unavailable();
      }
      PublicJwksSnapshot currentJwks = trustedJwksSource.load();
      currentFingerprint =
          AccountJwtJwksTrustedSource.publicKeyFingerprint(
              currentJwks, original.publicJwksSourceIdentity(), original.targetKid());
    } catch (RuntimeException unavailableSource) {
      throw unavailable();
    }
    if (currentFingerprint.isEmpty()
        || !original.targetPublicKeyFingerprint().equals(currentFingerprint.orElseThrow())) {
      throw unavailable();
    }
  }

  private AuthenticatedSignerCorrespondence requireCommittedCandidateIdentity(
      CommittedCandidateVerificationData committed) {
    var identity = committed.identity();
    long now = Math.floorDiv(clock.millis(), 1_000L);
    long latestIssuedAt = now > Long.MAX_VALUE - 5L ? Long.MAX_VALUE : now + 5L;
    long expectedEnvelopeExpiry;
    try {
      expectedEnvelopeExpiry = Math.multiplyExact(identity.expiresAtEpochSecond(), 1_000L);
    } catch (ArithmeticException invalidExpiry) {
      throw unavailable();
    }
    AuthenticatedSignerCorrespondence original = committed.signerCorrespondence();
    if (now <= 0L
        || identity.notBeforeEpochSecond() > now
        || identity.issuedAtEpochSecond() > latestIssuedAt
        || identity.expiresAtEpochSecond() <= now
        || !identity.accountId().equals(committed.authoritySnapshot().accountId())
        || !committed.evidenceBundleReference().canonicalSha256().matches("[0-9a-f]{64}")
        || !committed.tokenSha256().matches("[0-9a-f]{64}")
        || !committed.canonicalRegistryRecordSha256().matches("[0-9a-f]{64}")
        || !committed.commitProofSha256().matches("[0-9a-f]{64}")
        || !committed.envelopeSha256().matches("[0-9a-f]{64}")
        || committed.envelopeBytesLength() < 1
        || committed.envelopeBytesLength() > 65_610
        || committed.envelopeKeyId() == null
        || !committed.envelopeKeyId().matches("[A-Za-z0-9_-]{1,32}")
        || expectedEnvelopeExpiry != committed.responseRecoveryExpiryEpochMillis()
        || !committed.signerKid().equals(original.targetKid())
        || !committed.signerGeneration().equals(original.targetGeneration())
        || !"COMMITTED".equals(original.promotionStatus())
        || !original.privatePromotionDispatched()
        || !original.promotionOperationId().equals(original.privatePromotionOperationId())
        || !original.promotionOperationId().equals(original.publicPromotionOperationId())
        || !original
            .generationOperationId()
            .equals(original.privatePromotionGenerationOperationId())
        || !"RS256".equals(original.targetAlgorithm())
        || !original.targetPublicKeyFingerprint().matches("[0-9a-f]{64}")
        || original
            .durableActive()
            .filter(
                active ->
                    active.generation().equals(committed.signerGeneration())
                        && active.kid().equals(committed.signerKid()))
            .isEmpty()
        || original
            .publishedActive()
            .filter(
                active ->
                    active.generation().equals(committed.signerGeneration())
                        && active.kid().equals(committed.signerKid()))
            .isEmpty()) {
      throw unavailable();
    }
    return original;
  }

  private static void requireCurrentSourceIdentity(
      CurrentSigner currentSigner, SourceIdentity sourceIdentity) {
    if (sourceIdentity == null
        || !currentSigner.binding().environmentId().equals(sourceIdentity.environmentId())
        || !currentSigner.binding().clusterId().equals(sourceIdentity.clusterId())
        || !currentSigner.binding().namespace().equals(sourceIdentity.namespace())
        || !currentSigner
            .trust()
            .expectedClusterIncarnationUid()
            .equals(sourceIdentity.clusterIncarnationUid())
        || !currentSigner.trust().expectedNamespaceUid().equals(sourceIdentity.namespaceUid())
        || !currentSigner.api().configRevision().equals(sourceIdentity.bindingRevision())
        || !currentSigner.api().apiServer().toString().equals(sourceIdentity.apiServerOrigin())
        || !currentSigner.api().servingCaSha256().equals(sourceIdentity.servingCaSha256())
        || !currentSigner
            .evidence()
            .promotion()
            .publicConfigMapUid()
            .equals(sourceIdentity.configMapUid())
        || !currentSigner
            .evidence()
            .publicReceipt()
            .configMapUid()
            .equals(sourceIdentity.configMapUid())) {
      throw unavailable();
    }
  }

  private CandidateOutcome bindAndSealWithinTransaction(
      UUID requestId,
      String compactJwt,
      InvocationIdentity initial,
      AccountMountedJwtSignerBundle.SignedDelegationDigest digest,
      SourceIdentity sourceIdentity) {
    CurrentSigner currentBeforePersist = currentCommittedSigner();
    PendingSigningIdentity pendingBeforePersist = requireCurrentPendingIdentity(requestId);
    requireUnexpired(pendingBeforePersist);
    requireSameCommittedSigner(initial.currentSigner(), currentBeforePersist);
    requireSamePendingIdentity(initial.pending(), pendingBeforePersist);

    BoundTokenCandidate bound =
        issuanceRepository.bindSignedCandidate(requestId, compactJwt, digest.signerGeneration());
    requireBoundCandidate(requestId, digest, bound);

    PendingRegistryCandidate persisted = issuanceRepository.readPendingRegistryCandidate(requestId);
    requirePersistedCandidate(initial, digest, bound, persisted);

    SealedCandidateObservation sealed =
        responseEnvelopeService.sealPendingCandidate(
            requestId,
            new CallerIdentity(
                pendingBeforePersist.identity().callerWorkload(),
                pendingBeforePersist.identity().callerContextId()),
            compactJwt);
    requireSealedCandidate(requestId, pendingBeforePersist, sealed);

    PendingRegistryCandidate finalReadback =
        issuanceRepository.readPendingRegistryCandidate(requestId);
    requirePersistedCandidate(initial, digest, bound, finalReadback);
    CurrentSigner currentAfterPersist = currentCommittedSigner();
    requireSameCommittedSigner(initial.currentSigner(), currentAfterPersist);

    PendingCandidateVerificationProof verificationProof =
        createVerificationProof(
            pendingBeforePersist,
            digest,
            finalReadback,
            sealed,
            currentAfterPersist,
            sourceIdentity);

    return new CandidateOutcome(
        pendingBeforePersist.identity().operationId(),
        requestId,
        digest.compactTokenSha256(),
        digest.signerGeneration(),
        digest.kid(),
        sealed,
        verificationProof);
  }

  private static PendingCandidateVerificationProof createVerificationProof(
      PendingSigningIdentity pending,
      AccountMountedJwtSignerBundle.SignedDelegationDigest digest,
      PendingRegistryCandidate persisted,
      SealedCandidateObservation sealed,
      CurrentSigner currentSigner,
      SourceIdentity sourceIdentity) {
    byte[] canonicalRecord = persisted.canonicalRecordBytes();
    final String recordSha256;
    try {
      recordSha256 = sha256(canonicalRecord);
    } finally {
      wipe(canonicalRecord);
    }
    VerifiedPendingIdentity identity =
        new VerifiedPendingIdentity(
            persisted.identity().operationId(),
            persisted.identity().requestId(),
            persisted.identity().accountId(),
            persisted.identity().callerWorkload(),
            persisted.identity().callerContextId(),
            persisted.identity().requestDigest(),
            persisted.identity().tokenJti(),
            persisted.identity().issuedAtEpochSecond(),
            persisted.identity().notBeforeEpochSecond(),
            persisted.identity().expiresAtEpochSecond());
    if (!identity.requestId().equals(digest.requestId())
        || !identity.operationId().equals(digest.issuanceOperationId())
        || !identity.tokenJti().equals(digest.jti())
        || digest.issuedAtEpochSecond() != identity.issuedAtEpochSecond()
        || digest.notBeforeEpochSecond() != identity.notBeforeEpochSecond()
        || digest.expiresAtEpochSecond() != identity.expiresAtEpochSecond()
        || persisted.authoritySnapshot() == null
        || !persisted
            .evidenceBundleReference()
            .canonicalSha256()
            .equals(pending.evidenceBundle().canonicalSha256())
        || !persisted.tokenHash().equals(digest.compactTokenSha256())
        || !persisted.kid().equals(digest.kid())
        || !persisted.signerGeneration().equals(digest.signerGeneration())) {
      throw unavailable();
    }
    AuthenticatedSignerCorrespondence correspondence =
        signerCorrespondence(currentSigner, sourceIdentity);
    if (!persisted.authoritySnapshot().equals(pending.authoritySnapshot())
        || !correspondence
            .generationOperationId()
            .equals(UUID.fromString(currentSigner.expectedIdentity().operationId()))
        || !correspondence.targetGeneration().equals(digest.signerGeneration())
        || !correspondence.targetKid().equals(digest.kid())
        || !correspondence
            .targetPublicKeyFingerprint()
            .equals(currentSigner.expectedIdentity().publicKeyFingerprint())) {
      throw unavailable();
    }
    return new PendingCandidateVerificationProof(
        identity,
        persisted.authoritySnapshot(),
        persisted.evidenceBundleReference(),
        persisted.tokenHash(),
        recordSha256,
        correspondence,
        sealed);
  }

  private static AuthenticatedSignerCorrespondence signerCorrespondence(
      CurrentSigner currentSigner, SourceIdentity sourceIdentity) {
    CommittedSignerEvidence evidence = currentSigner.evidence();
    var promotion = evidence.promotion();
    var generation = evidence.generationResult();
    var desiredState = evidence.desiredState();
    var privateReceipt = evidence.privateReceipt();
    var publicReceipt = evidence.publicReceipt();
    if (sourceIdentity == null
        || !promotion.publicConfigMapUid().equals(sourceIdentity.configMapUid())
        || !promotion.apiBindingDigest().equals(currentSigner.api().bindingDigest())
        || !promotion.apiConfigRevision().equals(currentSigner.api().configRevision())
        || !publicReceipt.configMapUid().equals(sourceIdentity.configMapUid())
        || !privateReceipt.promotionOperationId().equals(promotion.operationId())
        || !privateReceipt.generationOperationId().equals(generation.operationId())
        || !publicReceipt.promotionOperationId().equals(promotion.operationId())
        || !generation.operationId().equals(promotion.generationOperationId())
        || !promotion.privatePromotionDispatched()
        || promotion
            .privatePromotionObservedResourceVersion()
            .filter(privateReceipt.observedResourceVersion()::equals)
            .isEmpty()
        || promotion
            .privatePromotionReceiptDigest()
            .filter(privateReceipt.receiptDigest()::equals)
            .isEmpty()
        || promotion
            .activeJwksObservedResourceVersion()
            .filter(publicReceipt.observedResourceVersion()::equals)
            .isEmpty()
        || promotion
            .activeJwksPublicDataDigest()
            .filter(publicReceipt.publicDataDigest()::equals)
            .isEmpty()
        || promotion
            .activeJwksReceiptDigest()
            .filter(publicReceipt.receiptDigest()::equals)
            .isEmpty()) {
      throw unavailable();
    }
    return new AuthenticatedSignerCorrespondence(
        currentSigner.binding(),
        currentSigner.trust(),
        currentSigner.api().bindingDigest(),
        currentSigner.api().configRevision(),
        sourceIdentity,
        desiredState.recordVersion(),
        desiredState.durableActive(),
        desiredState.publishedActive(),
        promotion.operationId(),
        promotion.requestDigest(),
        promotion.expectedRecordVersion(),
        promotion.expectedPreviousActive(),
        promotion.expectedPreviousPublicKeyFingerprint(),
        promotion.expectedPublishedActive(),
        promotion.status(),
        promotion.generationOperationId(),
        promotion.generationOperationDigest(),
        promotion.generationReceiptDigest(),
        generation.desiredStateVersion(),
        generation.privateSecretName(),
        generation.secretUid(),
        generation.expectedPriorResourceVersion(),
        generation.observedResourceVersion(),
        generation.targetGeneration(),
        generation.targetKid(),
        generation.targetAlgorithm(),
        generation.publicKeyFingerprint(),
        privateReceipt.observedResourceVersion(),
        privateReceipt.receiptDigest(),
        promotion.apiBindingDigest(),
        promotion.apiConfigRevision(),
        promotion.publicConfigMapUid(),
        promotion.expectedPublicResourceVersion(),
        publicReceipt.priorResourceVersion(),
        publicReceipt.observedResourceVersion(),
        publicReceipt.publicDataDigest(),
        publicReceipt.receiptDigest(),
        promotion.prepublicationIntentDigest(),
        promotion.prepublicationReceiptDigest(),
        promotion.mountedObservationDigest(),
        promotion.readinessPlanDigest(),
        promotion.readinessEvidenceDigest(),
        promotion.privatePromotionDispatched(),
        privateReceipt.promotionOperationId(),
        privateReceipt.generationOperationId(),
        publicReceipt.promotionOperationId(),
        promotion.privatePromotionObservedResourceVersion(),
        promotion.privatePromotionReceiptDigest(),
        promotion.activeJwksObservedResourceVersion(),
        promotion.activeJwksPublicDataDigest(),
        promotion.activeJwksReceiptDigest());
  }

  private byte[] requireExactTrustedSnapshot(
      CurrentSigner currentSigner, SourceIdentity sourceIdentity, PublicJwksSnapshot snapshot) {
    if (sourceIdentity == null
        || snapshot == null
        || !sourceIdentity.equals(snapshot.sourceIdentity())
        || !currentSigner.binding().environmentId().equals(sourceIdentity.environmentId())
        || !currentSigner.binding().clusterId().equals(sourceIdentity.clusterId())
        || !currentSigner.binding().namespace().equals(sourceIdentity.namespace())
        || !currentSigner
            .trust()
            .expectedClusterIncarnationUid()
            .equals(sourceIdentity.clusterIncarnationUid())
        || !currentSigner.trust().expectedNamespaceUid().equals(sourceIdentity.namespaceUid())
        || !currentSigner
            .evidence()
            .promotion()
            .publicConfigMapUid()
            .equals(sourceIdentity.configMapUid())
        || !currentSigner
            .evidence()
            .publicReceipt()
            .configMapUid()
            .equals(sourceIdentity.configMapUid())
        || !currentSigner.api().configRevision().equals(sourceIdentity.bindingRevision())
        || !currentSigner.api().servingCaSha256().equals(sourceIdentity.servingCaSha256())
        || !currentSigner.api().apiServer().toString().equals(sourceIdentity.apiServerOrigin())) {
      throw unavailable();
    }
    byte[] jwksBytes = snapshot.jwksBytes();
    if (jwksBytes == null
        || jwksBytes.length == 0
        || jwksBytes.length > AccountPublicJwksCache.MAX_JWKS_BYTES) {
      wipe(jwksBytes);
      throw unavailable();
    }
    return jwksBytes;
  }

  private PendingSigningIdentity requireCurrentPendingIdentity(UUID requestId) {
    PendingSigningIdentity pending = issuanceRepository.readPendingSigningIdentity(requestId);
    if (pending == null
        || pending.identity() == null
        || pending.authoritySnapshot() == null
        || pending.evidenceBundle() == null
        || !requestId.equals(pending.identity().requestId())
        || !pending.identity().accountId().equals(pending.authoritySnapshot().accountId())
        || !GameSessionAccountDelegationProfile.PROFILE.equals(
            pending.evidenceBundle().fields().get("profile"))
        || !GameSessionAccountDelegationProfile.AUDIENCE.equals(
            pending.evidenceBundle().fields().get("audience"))) {
      throw unavailable();
    }
    return pending;
  }

  private CurrentSigner currentCommittedSigner() {
    AccountJwtSignerMaterializerTrustBinding.Binding materializer =
        materializerTrustBinding
            .current()
            .orElseThrow(AccountGameplayDelegationSigner::unavailable);
    AccountJwtJwksApiBinding.ParsedBinding api = apiBinding.current();
    if (!materializer.environmentId().equals(api.environmentId())
        || !materializer.clusterId().equals(api.clusterId())
        || !materializer.namespace().equals(api.namespace())
        || !materializer.expectedClusterIncarnationUid().equals(api.expectedClusterIncarnationUid())
        || !materializer.expectedNamespaceUid().equals(api.expectedNamespaceUid())) {
      throw unavailable();
    }

    AccountJwtSignerDesiredStateRepository.Binding binding =
        new AccountJwtSignerDesiredStateRepository.Binding(
            materializer.environmentId(),
            materializer.clusterId(),
            materializer.namespace(),
            CustodyMode.INTERIM_ACCOUNT_ONLY_MOUNTED_FALLBACK);
    TrustFence trust =
        new TrustFence(
            materializer.expectedClusterIncarnationUid(),
            materializer.expectedNamespaceUid(),
            materializer.bindingDigest(),
            materializer.configRevision());
    CommittedSignerEvidence evidence =
        desiredStateRepository
            .readCurrentCommittedSigner(binding, trust, api.bindingDigest(), api.configRevision())
            .orElseThrow(AccountGameplayDelegationSigner::unavailable);
    return verifiedCurrentSigner(binding, trust, api, evidence);
  }

  private static CurrentSigner verifiedCurrentSigner(
      AccountJwtSignerDesiredStateRepository.Binding binding,
      TrustFence trust,
      AccountJwtJwksApiBinding.ParsedBinding api,
      CommittedSignerEvidence evidence) {
    var promotion = evidence.promotion();
    var generation = evidence.generationResult();
    ActiveSigner target = new ActiveSigner(generation.targetGeneration(), generation.targetKid());
    if (!"COMMITTED".equals(promotion.status())
        || !binding.equals(promotion.binding())
        || !binding.equals(generation.binding())
        || !trust.equals(promotion.trustFence())
        || !trust.equals(generation.trustFence())
        || !promotion.generationOperationId().equals(generation.operationId())
        || !promotion.targetGeneration().equals(generation.targetGeneration())
        || !promotion.targetKid().equals(generation.targetKid())
        || !promotion.targetAlgorithm().equals("RS256")
        || !generation.targetAlgorithm().equals("RS256")
        || !promotion.targetPublicKeyFingerprint().equals(generation.publicKeyFingerprint())
        || evidence.desiredState().durableActive().filter(target::equals).isEmpty()
        || evidence.desiredState().publishedActive().filter(target::equals).isEmpty()
        || evidence.desiredState().preparedOperationId().isPresent()
        || !evidence.privateReceipt().promotionOperationId().equals(promotion.operationId())
        || !evidence.publicReceipt().promotionOperationId().equals(promotion.operationId())) {
      throw unavailable();
    }
    AccountMountedJwtSignerBundle.ExpectedIdentity expected =
        new AccountMountedJwtSignerBundle.ExpectedIdentity(
            binding.environmentId(),
            binding.clusterId(),
            binding.namespace(),
            generation.operationId().toString(),
            generation.targetGeneration(),
            generation.targetKid(),
            generation.publicKeyFingerprint());
    return new CurrentSigner(binding, trust, api, evidence, expected);
  }

  private static void requireSameCommittedSigner(CurrentSigner expected, CurrentSigner observed) {
    if (!expected.equals(observed)) {
      throw unavailable();
    }
  }

  private void requireUnexpired(PendingSigningIdentity pending) {
    long now = Math.floorDiv(clock.millis(), 1000L);
    long latestIssuedAt = now > Long.MAX_VALUE - 5L ? Long.MAX_VALUE : now + 5L;
    if (now <= 0L
        || pending.identity().notBeforeEpochSecond() > now
        || pending.identity().issuedAtEpochSecond() > latestIssuedAt
        || pending.identity().expiresAtEpochSecond() <= now) {
      throw unavailable();
    }
  }

  private static void requireSignedIdentity(
      InvocationIdentity initial, AccountMountedJwtSignerBundle.SignedDelegationDigest digest) {
    PendingSigningIdentity pending = initial.pending();
    AccountMountedJwtSignerBundle.ExpectedIdentity signer =
        initial.currentSigner().expectedIdentity();
    if (!digest.issuanceOperationId().equals(pending.identity().operationId())
        || !digest.requestId().equals(pending.identity().requestId())
        || !digest.jti().equals(pending.identity().tokenJti())
        || !digest.signerOperationId().equals(signer.operationId())
        || !digest.signerGeneration().equals(signer.generation())
        || !digest.kid().equals(signer.kid())
        || !digest.publicKeyFingerprint().equals(signer.publicKeyFingerprint())
        || digest.issuedAtEpochSecond() != pending.identity().issuedAtEpochSecond()
        || digest.notBeforeEpochSecond() != pending.identity().notBeforeEpochSecond()
        || digest.expiresAtEpochSecond() != pending.identity().expiresAtEpochSecond()) {
      throw unavailable();
    }
  }

  private static void requireVerifiedClaims(
      InvocationIdentity initial,
      AccountMountedJwtSignerBundle.SignedDelegationDigest digest,
      VerifiedClaims verified) {
    PendingSigningIdentity pending = initial.pending();
    Map<String, Object> claims = verified.claims();
    if (!GameSessionAccountDelegationProfile.PROFILE.equals(verified.profile())
        || !GameSessionAccountDelegationProfile.TYPE.equals(verified.tokenType())
        || !initial.currentSigner().expectedIdentity().kid().equals(verified.keyId())
        || !pending.identity().accountId().toString().equals(claims.get("sub"))
        || !pending.identity().accountId().toString().equals(claims.get("accountId"))
        || !pending.identity().tokenJti().toString().equals(claims.get("jti"))
        || decimalCounterClaim(claims, "tokenGeneration") != 1L
        || numberClaim(claims, "iat") != pending.identity().issuedAtEpochSecond()
        || numberClaim(claims, "nbf") != pending.identity().notBeforeEpochSecond()
        || numberClaim(claims, "exp") != pending.identity().expiresAtEpochSecond()
        || decimalCounterClaim(claims, "issuanceFence")
            != pending.authoritySnapshot().issuanceFence()
        || !(claims.get("membershipVersion") instanceof Map<?, ?> membershipVersion)
        || !membershipVersion.isEmpty()
        || claims.containsKey("globalRoles")
        || claims.containsKey("scopedRoles")
        || !digest.jti().equals(pending.identity().tokenJti())
        || !digest.compactTokenSha256().matches("[0-9a-f]{64}")) {
      throw unavailable();
    }
  }

  private static long numberClaim(Map<String, Object> claims, String field) {
    Object value = claims.get(field);
    if (!(value instanceof Number number)) {
      throw unavailable();
    }
    try {
      return new java.math.BigDecimal(number.toString()).longValueExact();
    } catch (ArithmeticException | NumberFormatException malformed) {
      throw unavailable();
    }
  }

  private static long decimalCounterClaim(Map<String, Object> claims, String field) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(claims.get(field)).longValueExact();
    } catch (IllegalArgumentException | ArithmeticException malformed) {
      throw unavailable();
    }
  }

  private static void requireSamePendingIdentity(
      PendingSigningIdentity expected, PendingSigningIdentity observed) {
    if (!expected.identity().equals(observed.identity())
        || !expected.authoritySnapshot().equals(observed.authoritySnapshot())
        || !expected
            .evidenceBundle()
            .canonicalSha256()
            .equals(observed.evidenceBundle().canonicalSha256())
        || !Arrays.equals(
            expected.evidenceBundle().canonicalBytes(),
            observed.evidenceBundle().canonicalBytes())) {
      throw unavailable();
    }
  }

  private static void requireBoundCandidate(
      UUID requestId,
      AccountMountedJwtSignerBundle.SignedDelegationDigest digest,
      BoundTokenCandidate bound) {
    if (bound == null
        || !bound.requestId().equals(requestId)
        || !bound.tokenHash().equals(digest.compactTokenSha256())
        || !bound.signerGeneration().equals(digest.signerGeneration())
        || !bound.kid().equals(digest.kid())) {
      throw unavailable();
    }
  }

  private static void requirePersistedCandidate(
      InvocationIdentity initial,
      AccountMountedJwtSignerBundle.SignedDelegationDigest digest,
      BoundTokenCandidate bound,
      PendingRegistryCandidate persisted) {
    if (persisted == null
        || !persisted.identity().equals(initial.pending().identity())
        || !persisted.authoritySnapshot().equals(initial.pending().authoritySnapshot())
        || !persisted.tokenHash().equals(digest.compactTokenSha256())
        || !persisted.kid().equals(digest.kid())
        || !persisted.signerGeneration().equals(digest.signerGeneration())
        || !persisted.tokenHash().equals(bound.tokenHash())
        || !persisted.kid().equals(bound.kid())
        || !persisted.signerGeneration().equals(bound.signerGeneration())
        || persisted.expiresAtEpochSecond() != digest.expiresAtEpochSecond()
        || !matchesEvidenceBundleReference(
            initial.pending(), persisted.evidenceBundleReference())) {
      throw unavailable();
    }
  }

  private static boolean matchesEvidenceBundleReference(
      PendingSigningIdentity pending, EvidenceBundleReference actual) {
    Object referenceValue = pending.evidenceBundle().fields().get("bundleRef");
    if (!(referenceValue instanceof Map<?, ?> reference)) {
      return false;
    }
    return Objects.equals(reference.get("bundleVersion"), actual.bundleVersion())
        && Objects.equals(reference.get("sourceVersion"), actual.sourceVersion())
        && Objects.equals(reference.get("sourceFence"), actual.sourceFence())
        && Objects.equals(reference.get("linearization"), actual.linearization())
        && pending.evidenceBundle().canonicalSha256().equals(actual.canonicalSha256());
  }

  private static void requireSealedCandidate(
      UUID requestId, PendingSigningIdentity pending, SealedCandidateObservation sealed) {
    final long immutableExpiryMillis;
    try {
      immutableExpiryMillis = Math.multiplyExact(pending.identity().expiresAtEpochSecond(), 1_000L);
    } catch (ArithmeticException invalidExpiry) {
      throw unavailable();
    }
    if (sealed == null
        || !sealed.requestId().equals(requestId)
        || !sealed.operationId().equals(pending.identity().operationId())
        || !sealed
            .authorityEvidenceBundleSha256()
            .equals(pending.evidenceBundle().canonicalSha256())
        || sealed.issuanceFence() != pending.authoritySnapshot().issuanceFence()
        || sealed.responseRecoveryExpiryEpochMillis() != immutableExpiryMillis) {
      throw unavailable();
    }
  }

  private static String exactAscii(byte[] compactJwt) {
    if (compactJwt == null
        || compactJwt.length == 0
        || compactJwt.length > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES) {
      throw unavailable();
    }
    String value = new String(compactJwt, StandardCharsets.US_ASCII);
    if (!Arrays.equals(compactJwt, value.getBytes(StandardCharsets.US_ASCII))) {
      throw unavailable();
    }
    return value;
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw AccountGameplayDelegationSigner.unavailable();
    }
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) {
      Arrays.fill(bytes, (byte) 0);
    }
  }

  private static SigningUnavailableException unavailable() {
    return new SigningUnavailableException();
  }

  private record CurrentSigner(
      AccountJwtSignerDesiredStateRepository.Binding binding,
      TrustFence trust,
      AccountJwtJwksApiBinding.ParsedBinding api,
      CommittedSignerEvidence evidence,
      AccountMountedJwtSignerBundle.ExpectedIdentity expectedIdentity) {}

  private record InvocationIdentity(CurrentSigner currentSigner, PendingSigningIdentity pending) {}

  /**
   * Signer-produced evidence that one exact pending candidate passed local cryptographic and
   * profile validation, was durably bound, sealed, and read back under unchanged owner fences. This
   * is neither an issuance receipt nor authority to transition the registry to COMMITTED.
   */
  public static final class PendingCandidateVerificationProof {
    private final VerifiedPendingIdentity identity;
    private final AccountAuthoritySnapshot authoritySnapshot;
    private final EvidenceBundleReference evidenceBundleReference;
    private final String tokenSha256;
    private final String canonicalRegistryRecordSha256;
    private final AuthenticatedSignerCorrespondence signerCorrespondence;
    private final SealedCandidateObservation sealedCandidate;

    private PendingCandidateVerificationProof(
        VerifiedPendingIdentity identity,
        AccountAuthoritySnapshot authoritySnapshot,
        EvidenceBundleReference evidenceBundleReference,
        String tokenSha256,
        String canonicalRegistryRecordSha256,
        AuthenticatedSignerCorrespondence signerCorrespondence,
        SealedCandidateObservation sealedCandidate) {
      this.identity = Objects.requireNonNull(identity);
      this.authoritySnapshot = Objects.requireNonNull(authoritySnapshot);
      this.evidenceBundleReference = Objects.requireNonNull(evidenceBundleReference);
      this.tokenSha256 = Objects.requireNonNull(tokenSha256);
      this.canonicalRegistryRecordSha256 = Objects.requireNonNull(canonicalRegistryRecordSha256);
      this.signerCorrespondence = Objects.requireNonNull(signerCorrespondence);
      this.sealedCandidate = Objects.requireNonNull(sealedCandidate);
      final long immutableExpiryMillis;
      try {
        immutableExpiryMillis = Math.multiplyExact(identity.expiresAtEpochSecond(), 1_000L);
      } catch (ArithmeticException invalidExpiry) {
        throw unavailable();
      }
      if (!identity.operationId().equals(sealedCandidate.operationId())
          || !identity.requestId().equals(sealedCandidate.requestId())
          || !identity.accountId().equals(authoritySnapshot.accountId())
          || !evidenceBundleReference
              .canonicalSha256()
              .equals(sealedCandidate.authorityEvidenceBundleSha256())
          || authoritySnapshot.issuanceFence() != sealedCandidate.issuanceFence()
          || immutableExpiryMillis != sealedCandidate.responseRecoveryExpiryEpochMillis()
          || !"COMMITTED".equals(signerCorrespondence.promotionStatus())
          || !signerCorrespondence.privatePromotionDispatched()
          || !signerCorrespondence
              .promotionOperationId()
              .equals(signerCorrespondence.privatePromotionOperationId())
          || !signerCorrespondence
              .promotionOperationId()
              .equals(signerCorrespondence.publicPromotionOperationId())
          || !signerCorrespondence
              .generationOperationId()
              .equals(signerCorrespondence.privatePromotionGenerationOperationId())
          || signerCorrespondence
              .durableActive()
              .filter(
                  active ->
                      active.generation().equals(signerCorrespondence.targetGeneration())
                          && active.kid().equals(signerCorrespondence.targetKid()))
              .isEmpty()
          || signerCorrespondence
              .publishedActive()
              .filter(
                  active ->
                      active.generation().equals(signerCorrespondence.targetGeneration())
                          && active.kid().equals(signerCorrespondence.targetKid()))
              .isEmpty()
          || signerCorrespondence
              .promotionPrivateObservedResourceVersion()
              .filter(signerCorrespondence.privatePromotionObservedResourceVersion()::equals)
              .isEmpty()
          || signerCorrespondence
              .promotionPrivateReceiptDigest()
              .filter(signerCorrespondence.privatePromotionReceiptDigest()::equals)
              .isEmpty()
          || signerCorrespondence
              .activeJwksObservedResourceVersion()
              .filter(signerCorrespondence.observedPublicResourceVersion()::equals)
              .isEmpty()
          || signerCorrespondence
              .activeJwksPublicDataDigest()
              .filter(signerCorrespondence.publicDataDigest()::equals)
              .isEmpty()
          || signerCorrespondence
              .activeJwksReceiptDigest()
              .filter(signerCorrespondence.publicReceiptDigest()::equals)
              .isEmpty()
          || !signerCorrespondence
              .publicConfigMapUid()
              .equals(signerCorrespondence.publicJwksSourceIdentity().configMapUid())
          || !tokenSha256.matches("[0-9a-f]{64}")
          || !canonicalRegistryRecordSha256.matches("[0-9a-f]{64}")
          || !evidenceBundleReference.canonicalSha256().matches("[0-9a-f]{64}")) {
        throw unavailable();
      }
    }

    public VerifiedPendingIdentity identity() {
      return identity;
    }

    public UUID operationId() {
      return identity.operationId();
    }

    public UUID requestId() {
      return identity.requestId();
    }

    public UUID accountId() {
      return identity.accountId();
    }

    public UUID tokenJti() {
      return identity.tokenJti();
    }

    public long issuedAtEpochSecond() {
      return identity.issuedAtEpochSecond();
    }

    public long notBeforeEpochSecond() {
      return identity.notBeforeEpochSecond();
    }

    public long expiresAtEpochSecond() {
      return identity.expiresAtEpochSecond();
    }

    public long issuanceFence() {
      return authoritySnapshot.issuanceFence();
    }

    public AccountAuthoritySnapshot authoritySnapshot() {
      return authoritySnapshot;
    }

    public UUID signerOperationId() {
      return signerCorrespondence.generationOperationId();
    }

    public String signerKid() {
      return signerCorrespondence.targetKid();
    }

    public String signerGeneration() {
      return signerCorrespondence.targetGeneration();
    }

    public EvidenceBundleReference evidenceBundleReference() {
      return evidenceBundleReference;
    }

    public String tokenSha256() {
      return tokenSha256;
    }

    public String canonicalRegistryRecordSha256() {
      return canonicalRegistryRecordSha256;
    }

    public AuthenticatedSignerCorrespondence signerCorrespondence() {
      return signerCorrespondence;
    }

    public SealedCandidateObservation sealedCandidate() {
      return sealedCandidate;
    }

    @Override
    public String toString() {
      return "PendingCandidateVerificationProof[redacted]";
    }
  }

  /** Exact immutable pending identity read back from Account's issuance owner. */
  public record VerifiedPendingIdentity(
      UUID operationId,
      UUID requestId,
      UUID accountId,
      String callerWorkload,
      UUID callerContextId,
      String requestDigest,
      UUID tokenJti,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond) {
    public VerifiedPendingIdentity {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(callerWorkload);
      Objects.requireNonNull(callerContextId);
      Objects.requireNonNull(requestDigest);
      Objects.requireNonNull(tokenJti);
    }

    @Override
    public String toString() {
      return "VerifiedPendingIdentity[redacted]";
    }
  }

  /** Public, byte-free projection of the exact authenticated committed signer source evidence. */
  public record AuthenticatedSignerCorrespondence(
      AccountJwtSignerDesiredStateRepository.Binding binding,
      TrustFence trustFence,
      String apiBindingDigest,
      String apiConfigRevision,
      SourceIdentity publicJwksSourceIdentity,
      long desiredStateRecordVersion,
      java.util.Optional<ActiveSigner> durableActive,
      java.util.Optional<ActiveSigner> publishedActive,
      UUID promotionOperationId,
      String promotionRequestDigest,
      long promotionExpectedRecordVersion,
      java.util.Optional<ActiveSigner> expectedPreviousActive,
      java.util.Optional<String> expectedPreviousPublicKeyFingerprint,
      java.util.Optional<ActiveSigner> expectedPublishedActive,
      String promotionStatus,
      UUID generationOperationId,
      String generationOperationDigest,
      String generationReceiptDigest,
      long generationDesiredStateVersion,
      String privateSecretName,
      String secretUid,
      String expectedPriorSecretResourceVersion,
      String observedSecretResourceVersion,
      String targetGeneration,
      String targetKid,
      String targetAlgorithm,
      String targetPublicKeyFingerprint,
      String privatePromotionObservedResourceVersion,
      String privatePromotionReceiptDigest,
      String promotionApiBindingDigest,
      String promotionApiConfigRevision,
      String publicConfigMapUid,
      String expectedPublicResourceVersion,
      String priorPublicResourceVersion,
      String observedPublicResourceVersion,
      String publicDataDigest,
      String publicReceiptDigest,
      String prepublicationIntentDigest,
      String prepublicationReceiptDigest,
      String mountedObservationDigest,
      String readinessPlanDigest,
      String readinessEvidenceDigest,
      boolean privatePromotionDispatched,
      UUID privatePromotionOperationId,
      UUID privatePromotionGenerationOperationId,
      UUID publicPromotionOperationId,
      java.util.Optional<String> promotionPrivateObservedResourceVersion,
      java.util.Optional<String> promotionPrivateReceiptDigest,
      java.util.Optional<String> activeJwksObservedResourceVersion,
      java.util.Optional<String> activeJwksPublicDataDigest,
      java.util.Optional<String> activeJwksReceiptDigest) {
    public AuthenticatedSignerCorrespondence {
      Objects.requireNonNull(binding);
      Objects.requireNonNull(trustFence);
      Objects.requireNonNull(apiBindingDigest);
      Objects.requireNonNull(apiConfigRevision);
      Objects.requireNonNull(publicJwksSourceIdentity);
      Objects.requireNonNull(durableActive);
      Objects.requireNonNull(publishedActive);
      Objects.requireNonNull(promotionOperationId);
      Objects.requireNonNull(expectedPreviousActive);
      Objects.requireNonNull(expectedPreviousPublicKeyFingerprint);
      Objects.requireNonNull(expectedPublishedActive);
      Objects.requireNonNull(generationOperationId);
      Objects.requireNonNull(privatePromotionOperationId);
      Objects.requireNonNull(privatePromotionGenerationOperationId);
      Objects.requireNonNull(publicPromotionOperationId);
      Objects.requireNonNull(promotionPrivateObservedResourceVersion);
      Objects.requireNonNull(promotionPrivateReceiptDigest);
      Objects.requireNonNull(activeJwksObservedResourceVersion);
      Objects.requireNonNull(activeJwksPublicDataDigest);
      Objects.requireNonNull(activeJwksReceiptDigest);
    }

    @Override
    public String toString() {
      return "AuthenticatedSignerCorrespondence[redacted]";
    }
  }

  /** Non-authorizing metadata returned after the candidate and sealed envelope commit. */
  record CandidateOutcome(
      UUID operationId,
      UUID requestId,
      String tokenSha256,
      String signerGeneration,
      String signerKid,
      SealedCandidateObservation sealedCandidate,
      PendingCandidateVerificationProof verificationProof) {
    CandidateOutcome {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(tokenSha256);
      Objects.requireNonNull(signerGeneration);
      Objects.requireNonNull(signerKid);
      Objects.requireNonNull(sealedCandidate);
      Objects.requireNonNull(verificationProof);
      if (!operationId.equals(verificationProof.operationId())
          || !requestId.equals(verificationProof.requestId())
          || !tokenSha256.equals(verificationProof.tokenSha256())
          || !signerGeneration.equals(verificationProof.signerGeneration())
          || !signerKid.equals(verificationProof.signerKid())
          || !sealedCandidate.equals(verificationProof.sealedCandidate())) {
        throw unavailable();
      }
    }

    @Override
    public String toString() {
      return "CandidateOutcome[redacted]";
    }
  }

  static final class SigningUnavailableException extends IllegalStateException {
    private SigningUnavailableException() {
      super("Account gameplay delegation candidate is unavailable or ambiguous");
    }
  }
}
