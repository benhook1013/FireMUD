package net.firedevops.firemud.accountservice.service.session;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Explicitly unwired Account owner for the separate COMMITTED-to-ACTIVE initial LOGIN stage.
 *
 * <p>The activation operation surrounds one pinned Redis CAS/readback with independent fresh
 * Account/source/envelope and current signer-owner transactions. Its returned metadata is not a
 * credential, player-admission decision, response-recovery proof, or globally atomic cross-store
 * snapshot. Recovery may use an older committed signing generation only when its exact original
 * public-key fingerprint remains in the current trusted JWKS; current signer/trust and the exact
 * original committed issuance are reread on both sides of the Redis postcondition.
 */
public final class AccountGameplayDelegationCommittedIssuanceOwner {
  private final AccountGameplayDelegationIssuanceRepository issuanceRepository;
  private final AccountGameplayDelegationSigner signer;
  private final AccountGameplayDelegationTokenRegistry tokenRegistry;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;

  public AccountGameplayDelegationCommittedIssuanceOwner(
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry tokenRegistry,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.issuanceRepository =
        Objects.requireNonNull(issuanceRepository, "Account issuance repository is required");
    this.signer = Objects.requireNonNull(signer, "Account signer is required");
    this.tokenRegistry =
        Objects.requireNonNull(tokenRegistry, "Account token registry is required");
    this.accountTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.clock = Objects.requireNonNull(clock, "Clock is required");
  }

  /** Performs one fresh informational check of an already persisted COMMITTED request. */
  public CurrentCommittedIssuanceObservation inspectCurrentCommittedIssuance(UUID requestId) {
    if (requestId == null || requestId.version() != 4 || requestId.variant() != 2) {
      throw unavailable();
    }
    // The method owns its complete bounded transaction; it must never inherit unknown caller work.
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    try {
      CheckedObservation current = readCurrentOwnerEvidence(requestId);
      if (current == null) throw unavailable();
      long observedAt = clock.millis();
      if (current.checkedAtMillis() <= 0L
          || Math.floorDiv(current.checkedAtMillis(), 1_000L)
              >= current.evidence().identity().expiresAtEpochSecond()
          || observedAt <= 0L
          || Math.floorDiv(observedAt, 1_000L)
              >= current.evidence().identity().expiresAtEpochSecond()) {
        throw unavailable();
      }
      return new CurrentCommittedIssuanceObservation(current.evidence(), current.checkedAtMillis());
    } catch (OwnerUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      // Storage, signer, parsing, and protected-owner details are deliberately not surfaced.
      throw unavailable();
    }
  }

  /**
   * Resolves an untrusted verified-token JTI to the current committed Account issuance and then
   * requires the exact ACTIVE Redis projection after releasing every SQL lock. This is a read-only
   * caller-currentness check; it never activates, repairs, or extends the registry record.
   */
  public CommittedCandidateVerificationData requireCurrentActiveCommittedCandidateByJti(
      UUID tokenJti) {
    if (tokenJti == null || tokenJti.version() != 4 || tokenJti.variant() != 2) {
      throw unavailable();
    }
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    try {
      CommittedCandidateVerificationData evidence =
          accountTransaction.execute(
              status -> {
                CommittedCandidateVerificationData current =
                    issuanceRepository
                        .readCurrentCommittedCandidateByJti(tokenJti)
                        .orElseThrow(AccountGameplayDelegationCommittedIssuanceOwner::unavailable);
                signer.requireCurrentCommittedSigner(current);
                return current;
              });
      if (evidence == null
          || !tokenJti.equals(evidence.identity().tokenJti())
          || Math.floorDiv(clock.millis(), 1_000L) >= evidence.identity().expiresAtEpochSecond()) {
        throw unavailable();
      }
      tokenRegistry.requireExactActiveRecord(evidence);
      if (Math.floorDiv(clock.millis(), 1_000L) >= evidence.identity().expiresAtEpochSecond()) {
        throw unavailable();
      }
      return evidence;
    } catch (OwnerUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  /**
   * Advances one exact committed issuance to ACTIVE, then independently rereads the Account owner
   * evidence. This is a separate Redis/SQL transition, not a global cross-store transaction or a
   * credential-return authorization; Authenticate and response recovery remain unwired.
   */
  public ActiveCommittedIssuanceObservation activateCommittedIssuance(UUID requestId) {
    requireRequestId(requestId);
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    try {
      CheckedObservation before = readCurrentRecoveryOwnerEvidence(requestId);
      if (before == null) throw unavailable();
      requireUnexpired(before, clock.millis());
      AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt active =
          tokenRegistry.activateCommittedCandidate(before.evidence());
      requireExactActiveReceipt(active, before.evidence());

      CheckedObservation after = readCurrentRecoveryOwnerEvidence(requestId);
      if (after == null || !sameCommittedOwnerEvidence(before.evidence(), after.evidence())) {
        throw unavailable();
      }
      long observedAt = clock.millis();
      requireUnexpired(before, observedAt);
      requireUnexpired(after, observedAt);
      if (active.absoluteExpiryMillis() != after.evidence().registryAbsoluteExpiryMillis()) {
        throw unavailable();
      }
      return new ActiveCommittedIssuanceObservation(active, observedAt);
    } catch (OwnerUnavailableException failure) {
      throw failure;
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private CheckedObservation readCurrentOwnerEvidence(UUID requestId) {
    return accountTransaction.execute(
        status -> {
          CommittedCandidateVerificationData evidence =
              issuanceRepository.readCurrentCommittedCandidate(requestId);
          signer.requireCurrentCommittedSigner(evidence);
          return new CheckedObservation(evidence, clock.millis());
        });
  }

  /**
   * Reads current Account signer/trust and the immutable issuance inside SQL, then checks the
   * historical key against the trusted live JWKS only after the SQL transaction has ended.
   */
  private CheckedObservation readCurrentRecoveryOwnerEvidence(UUID requestId) {
    CheckedObservation checked =
        accountTransaction.execute(
            status -> {
              CommittedCandidateVerificationData evidence =
                  issuanceRepository.readCurrentCommittedCandidate(requestId);
              signer.requireCurrentCommittedRecoverySigner(evidence);
              return new CheckedObservation(evidence, clock.millis());
            });
    if (checked == null) return null;
    signer.requireRetainedCommittedPublicKey(checked.evidence());
    return checked;
  }

  private void requireUnexpired(CheckedObservation checked, long observedAtMillis) {
    long expiry = checked.evidence().identity().expiresAtEpochSecond();
    if (checked.checkedAtMillis() <= 0L
        || Math.floorDiv(checked.checkedAtMillis(), 1_000L) >= expiry
        || observedAtMillis <= 0L
        || Math.floorDiv(observedAtMillis, 1_000L) >= expiry) {
      throw unavailable();
    }
  }

  private static void requireRequestId(UUID requestId) {
    if (requestId == null || requestId.version() != 4 || requestId.variant() != 2) {
      throw unavailable();
    }
  }

  private static void requireExactActiveReceipt(
      AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt receipt,
      CommittedCandidateVerificationData evidence) {
    var identity = evidence.identity();
    if (receipt == null
        || !receipt
            .tokenKey()
            .equals(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + evidence.tokenSha256())
        || !receipt.tokenHash().equals(evidence.tokenSha256())
        || !receipt.operationId().equals(identity.operationId().toString())
        || !receipt.requestId().equals(identity.requestId().toString())
        || !receipt.accountId().equals(identity.accountId().toString())
        || !receipt.commitProofSha256().equals(evidence.commitProofSha256())
        || receipt.registryVersion() != 2L
        || receipt.absoluteExpiryMillis() != evidence.registryAbsoluteExpiryMillis()
        || receipt.localAofCount() < 1L
        || receipt.replicaAofCount() < 1L) {
      throw unavailable();
    }
  }

  private static boolean sameCommittedOwnerEvidence(
      CommittedCandidateVerificationData left, CommittedCandidateVerificationData right) {
    return left.identity().equals(right.identity())
        && left.authoritySnapshot().equals(right.authoritySnapshot())
        && left.evidenceBundleReference().equals(right.evidenceBundleReference())
        && left.tokenSha256().equals(right.tokenSha256())
        && left.signerKid().equals(right.signerKid())
        && left.signerGeneration().equals(right.signerGeneration())
        && left.canonicalRegistryRecordSha256().equals(right.canonicalRegistryRecordSha256())
        && left.commitProofSha256().equals(right.commitProofSha256())
        && left.signerCorrespondence().equals(right.signerCorrespondence())
        && left.envelopeKeyId().equals(right.envelopeKeyId())
        && left.envelopeSha256().equals(right.envelopeSha256())
        && left.envelopeBytesLength() == right.envelopeBytesLength()
        && left.responseRecoveryExpiryEpochMillis() == right.responseRecoveryExpiryEpochMillis()
        && left.registryAbsoluteExpiryMillis() == right.registryAbsoluteExpiryMillis()
        && left.registrationLocalAofCount() == right.registrationLocalAofCount()
        && left.registrationReplicaAofCount() == right.registrationReplicaAofCount()
        && left.registrationOutcome() == right.registrationOutcome();
  }

  private static OwnerUnavailableException unavailable() {
    return new OwnerUnavailableException();
  }

  private record CheckedObservation(
      CommittedCandidateVerificationData evidence, long checkedAtMillis) {}

  /** Exact active-store receipt plus before/after owner reread timestamps; never a credential. */
  public static final class ActiveCommittedIssuanceObservation {
    private final UUID operationId;
    private final UUID requestId;
    private final UUID accountId;
    private final String tokenSha256;
    private final String activeRecordSha256;
    private final String commitProofSha256;
    private final long registryVersion;
    private final long absoluteExpiryMillis;
    private final AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome outcome;
    private final long observedAtEpochMillis;

    private ActiveCommittedIssuanceObservation(
        AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt receipt,
        long observedAtEpochMillis) {
      this.operationId = UUID.fromString(receipt.operationId());
      this.requestId = UUID.fromString(receipt.requestId());
      this.accountId = UUID.fromString(receipt.accountId());
      this.tokenSha256 = receipt.tokenHash();
      this.activeRecordSha256 = receipt.canonicalActiveRecordSha256();
      this.commitProofSha256 = receipt.commitProofSha256();
      this.registryVersion = receipt.registryVersion();
      this.absoluteExpiryMillis = receipt.absoluteExpiryMillis();
      this.outcome = receipt.outcome();
      this.observedAtEpochMillis = observedAtEpochMillis;
    }

    public UUID operationId() {
      return operationId;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID accountId() {
      return accountId;
    }

    public String tokenSha256() {
      return tokenSha256;
    }

    public String activeRecordSha256() {
      return activeRecordSha256;
    }

    public String commitProofSha256() {
      return commitProofSha256;
    }

    public long registryVersion() {
      return registryVersion;
    }

    public long absoluteExpiryMillis() {
      return absoluteExpiryMillis;
    }

    public AccountGameplayDelegationRedisClient.ActiveRegistrationOutcome outcome() {
      return outcome;
    }

    public long observedAtEpochMillis() {
      return observedAtEpochMillis;
    }

    @Override
    public String toString() {
      return "ActiveCommittedIssuanceObservation[non-authorizing,redacted]";
    }
  }

  /** Immutable informational result; no candidate, ciphertext, or proof bytes are exposed. */
  public static final class CurrentCommittedIssuanceObservation {
    private final UUID operationId;
    private final UUID requestId;
    private final UUID accountId;
    private final UUID tokenJti;
    private final String requestDigest;
    private final String tokenSha256;
    private final String signerKid;
    private final String signerGeneration;
    private final String commitProofSha256;
    private final AccountAuthoritySnapshot authoritySnapshot;
    private final EvidenceBundleReference evidenceBundleReference;
    private final String canonicalRegistryRecordSha256;
    private final String envelopeKeyId;
    private final String envelopeSha256;
    private final int envelopeBytesLength;
    private final long issuedAtEpochSecond;
    private final long notBeforeEpochSecond;
    private final long expiresAtEpochSecond;
    private final long responseRecoveryExpiryEpochMillis;
    private final long observedAtEpochMillis;

    private CurrentCommittedIssuanceObservation(
        CommittedCandidateVerificationData evidence, long observedAtEpochMillis) {
      var identity = evidence.identity();
      this.operationId = identity.operationId();
      this.requestId = identity.requestId();
      this.accountId = identity.accountId();
      this.tokenJti = identity.tokenJti();
      this.requestDigest = identity.requestDigest();
      this.tokenSha256 = evidence.tokenSha256();
      this.signerKid = evidence.signerKid();
      this.signerGeneration = evidence.signerGeneration();
      this.commitProofSha256 = evidence.commitProofSha256();
      this.authoritySnapshot = evidence.authoritySnapshot();
      this.evidenceBundleReference = evidence.evidenceBundleReference();
      this.canonicalRegistryRecordSha256 = evidence.canonicalRegistryRecordSha256();
      this.envelopeKeyId = evidence.envelopeKeyId();
      this.envelopeSha256 = evidence.envelopeSha256();
      this.envelopeBytesLength = evidence.envelopeBytesLength();
      this.issuedAtEpochSecond = identity.issuedAtEpochSecond();
      this.notBeforeEpochSecond = identity.notBeforeEpochSecond();
      this.expiresAtEpochSecond = identity.expiresAtEpochSecond();
      this.responseRecoveryExpiryEpochMillis = evidence.responseRecoveryExpiryEpochMillis();
      this.observedAtEpochMillis = observedAtEpochMillis;
    }

    public UUID operationId() {
      return operationId;
    }

    public UUID requestId() {
      return requestId;
    }

    public UUID accountId() {
      return accountId;
    }

    public UUID tokenJti() {
      return tokenJti;
    }

    public String requestDigest() {
      return requestDigest;
    }

    public String tokenSha256() {
      return tokenSha256;
    }

    public String signerKid() {
      return signerKid;
    }

    public String signerGeneration() {
      return signerGeneration;
    }

    public String commitProofSha256() {
      return commitProofSha256;
    }

    public AccountAuthoritySnapshot authoritySnapshot() {
      return authoritySnapshot;
    }

    public EvidenceBundleReference evidenceBundleReference() {
      return evidenceBundleReference;
    }

    public String canonicalRegistryRecordSha256() {
      return canonicalRegistryRecordSha256;
    }

    public String envelopeKeyId() {
      return envelopeKeyId;
    }

    public String envelopeSha256() {
      return envelopeSha256;
    }

    public int envelopeBytesLength() {
      return envelopeBytesLength;
    }

    public long issuedAtEpochSecond() {
      return issuedAtEpochSecond;
    }

    public long notBeforeEpochSecond() {
      return notBeforeEpochSecond;
    }

    public long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    public long responseRecoveryExpiryEpochMillis() {
      return responseRecoveryExpiryEpochMillis;
    }

    public long observedAtEpochMillis() {
      return observedAtEpochMillis;
    }

    @Override
    public String toString() {
      return "CurrentCommittedIssuanceObservation[non-authorizing,redacted]";
    }
  }

  public static final class OwnerUnavailableException extends IllegalStateException {
    public OwnerUnavailableException() {
      super("Current committed Account gameplay delegation evidence is unavailable");
    }
  }
}
