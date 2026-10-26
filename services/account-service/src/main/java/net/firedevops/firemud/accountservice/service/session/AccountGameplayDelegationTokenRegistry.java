package net.firedevops.firemud.accountservice.service.session;

import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.PendingRegistryCandidate;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayIssuanceRepository.Genesis;
import net.firedevops.firemud.common.security.AccountJwtExactValues;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired Account Coordination Redis boundary for non-authorizing Game Session delegation records.
 *
 * <p>This registry writes the exact PENDING projection read from Account's durable issuance
 * repository and exposes a separate package-scoped COMMITTED-to-ACTIVE transition for the committed
 * owner. Both use the pinned Account Coordination client and exact readback/WAITAOF. It is not a
 * token validator, Authenticate/HMAC path, signer-promotion, readiness, or response-recovery API;
 * active owner metadata does not itself authorize player access or expose a credential.
 */
public final class AccountGameplayDelegationTokenRegistry {
  /** Only a repository-produced durable intent permits original-token disposal. */
  public AccountGameplayDelegationRedisClient.InitialLoginDisposalReceipt disposeInitialLogin(
      net.firedevops.firemud.accountservice.repository.AccountInitialGameplayLoginDisposalRepository
              .Intent
          intent) {
    requireNoAccountTransaction("Disposal intent must commit before registry mutation");
    return coordinationRedis.disposeInitialLogin(intent);
  }

  private static final long MAX_CLEANUP_MARGIN_MILLIS = 300_000L;

  private final AccountGameplayDelegationIssuanceRepository issuanceRepository;
  private final TransactionTemplate pendingCandidateReadTransaction;
  private final AccountGameplayDelegationRedisClient coordinationRedis;
  private final Clock clock;
  private final long maxEncodedBytes;
  private final long cleanupMarginMillis;

  public AccountGameplayDelegationTokenRegistry(
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      PlatformTransactionManager transactionManager,
      AccountGameplayDelegationRedisClient coordinationRedis,
      Clock clock,
      long maxEncodedBytes,
      long cleanupMarginMillis) {
    this.issuanceRepository =
        Objects.requireNonNull(issuanceRepository, "Account issuance repository is required");
    this.pendingCandidateReadTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.coordinationRedis =
        Objects.requireNonNull(coordinationRedis, "Account Coordination Redis client is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    if (maxEncodedBytes <= 0L
        || maxEncodedBytes > GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES) {
      throw new IllegalArgumentException("Registry byte bound exceeds the finite profile ceiling");
    }
    if (cleanupMarginMillis < 0L || cleanupMarginMillis > MAX_CLEANUP_MARGIN_MILLIS) {
      throw new IllegalArgumentException("Registry cleanup margin is outside its finite bound");
    }
    this.maxEncodedBytes = maxEncodedBytes;
    this.cleanupMarginMillis = cleanupMarginMillis;
  }

  /**
   * CAS-writes the exact pending projection loaded from committed Account SQL state and reads it
   * back byte-for-byte. The signed JWT is never accepted here, logged, returned, or stored.
   */
  public AccountGameplayDelegationRedisClient.PendingRegistrationReceipt registerPending(
      UUID requestId) {
    Objects.requireNonNull(requestId, "Request ID is required");
    requireNoAccountTransaction(
        "Pending registry work must not join an ambient Account transaction");
    PendingRegistryCandidate candidate =
        pendingCandidateReadTransaction.execute(
            status -> issuanceRepository.readPendingRegistryCandidate(requestId));
    requireNoAccountTransaction("Pending candidate SQL transaction must close before Redis work");
    if (candidate == null) {
      throw new IllegalStateException("Durable pending registry candidate is unavailable");
    }
    var identity = candidate.identity();
    byte[] recordBytes = candidate.canonicalRecordBytes();
    if (recordBytes.length == 0 || recordBytes.length > maxEncodedBytes) {
      throw new IllegalArgumentException("Pending registry record exceeds its finite byte bound");
    }
    GameSessionAccountDelegationRegistryRecord record =
        GameSessionAccountDelegationRegistryRecord.decode(recordBytes, maxEncodedBytes);
    if (!"pending".equals(record.state())
        || !requestId.toString().equals(record.requestId())
        || !candidate.tokenHash().equals(record.tokenHash())
        || !candidate.kid().equals(record.kid())
        || !candidate.signerGeneration().equals(record.signerGeneration())
        || !identity.operationId().toString().equals(record.operationId())
        || !identity.requestId().toString().equals(record.requestId())
        || !identity.requestDigest().equals(record.requestDigest())
        || !identity.accountId().toString().equals(record.accountId())
        || !identity.tokenJti().toString().equals(record.jti())
        || identity.issuedAtEpochSecond() != record.issuedAtEpochSecond()
        || identity.notBeforeEpochSecond() != record.notBeforeEpochSecond()
        || identity.expiresAtEpochSecond() != record.expiresAtEpochSecond()
        || candidate.expiresAtEpochSecond() != identity.expiresAtEpochSecond()
        || !candidate.authoritySnapshot().accountId().equals(identity.accountId())
        || record.issuanceFence() != candidate.authoritySnapshot().issuanceFence()
        || !candidate.evidenceBundleReference().equals(record.evidenceBundleReference())
        || !authorityTupleMatches(record.fields(), candidate)
        || !authoritySourceVersionsMatch(record.fields(), candidate)) {
      throw new IllegalStateException(
          "Durable pending registry candidate does not match its record");
    }

    long nowMillis = clock.millis();
    if (nowMillis < 0L) throw new IllegalStateException("Clock returned a negative instant");
    long expiryMillis;
    try {
      expiryMillis = Math.multiplyExact(candidate.expiresAtEpochSecond(), 1000L);
      expiryMillis = Math.addExact(expiryMillis, cleanupMarginMillis);
      if (Math.subtractExact(expiryMillis, nowMillis) <= 0L) {
        throw new IllegalArgumentException("Pending token is expired");
      }
    } catch (ArithmeticException ex) {
      throw new IllegalArgumentException("Pending registry TTL cannot be represented");
    }

    AccountGameplayDelegationRedisClient.PendingRegistrationReceipt receipt =
        coordinationRedis.registerPending(candidate.tokenHash(), recordBytes, expiryMillis);
    requireExactReceipt(receipt, candidate, recordBytes, expiryMillis);
    return receipt;
  }

  /**
   * Registers the exact immutable Account-owned ORIGINAL-NEW pending projection. Its lineage row
   * was already committed with the signed token hash and record bytes; this method only repeats
   * that record under the same fixed expiry and requires the real pinned-Redis WAITAOF receipt.
   */
  public AccountGameplayDelegationRedisClient.PendingRegistrationReceipt registerOriginalNewPending(
      Genesis genesis) {
    Objects.requireNonNull(genesis, "Account-owned ORIGINAL-NEW genesis is required");
    requireNoAccountTransaction(
        "ORIGINAL-NEW pending registry work must not join an Account transaction");
    byte[] recordBytes = genesis.pendingRegistryRecordBytes();
    if (recordBytes == null || recordBytes.length == 0 || recordBytes.length > maxEncodedBytes) {
      throw new IllegalStateException("Durable ORIGINAL-NEW registry record is unavailable");
    }
    try {
      GameSessionAccountDelegationRegistryRecord record =
          GameSessionAccountDelegationRegistryRecord.decode(recordBytes, maxEncodedBytes);
      if (!"pending".equals(record.state())
          || record.registryVersion() != 1L
          || !genesis.tokenHash().equals(record.tokenHash())
          || !genesis.signerKid().equals(record.kid())
          || !genesis.signerGeneration().equals(record.signerGeneration())
          || !genesis.lineageId().toString().equals(record.operationId())
          || !genesis.requestId().toString().equals(record.requestId())
          || !genesis.requestDigest().equals(record.requestDigest())
          || !genesis.accountId().toString().equals(record.accountId())
          || !genesis.tokenJti().toString().equals(record.jti())
          || genesis.issuedAtEpochSecond() != record.issuedAtEpochSecond()
          || genesis.notBeforeEpochSecond() != record.notBeforeEpochSecond()
          || genesis.expiresAtEpochSecond() != record.expiresAtEpochSecond()
          || !Long.toString(genesis.tokenGeneration())
              .equals(record.fields().get("tokenGeneration"))
          || genesis.issuanceFence() != record.issuanceFence()) {
        throw new IllegalStateException(
            "Persisted ORIGINAL-NEW registry record differs from the frozen lineage");
      }

      long nowMillis = clock.millis();
      if (nowMillis <= 0L) throw new IllegalStateException("Clock returned an invalid instant");
      long absoluteExpiryMillis;
      try {
        absoluteExpiryMillis = Math.multiplyExact(genesis.expiresAtEpochSecond(), 1_000L);
        absoluteExpiryMillis = Math.addExact(absoluteExpiryMillis, cleanupMarginMillis);
        if (absoluteExpiryMillis <= nowMillis) {
          throw new IllegalStateException("Original-new registry deadline has elapsed");
        }
      } catch (ArithmeticException malformed) {
        throw new IllegalStateException("Original-new registry deadline is unavailable");
      }

      AccountGameplayDelegationRedisClient.PendingRegistrationReceipt receipt =
          coordinationRedis.registerPending(genesis.tokenHash(), recordBytes, absoluteExpiryMillis);
      if (receipt == null
          || !receipt
              .tokenKey()
              .equals(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + genesis.tokenHash())
          || !AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY.equals(
              receipt.aclIdentity())
          || !receipt.canonicalRecordSha256().equals(sha256(recordBytes))
          || !receipt.operationId().equals(genesis.lineageId().toString())
          || !receipt.requestId().equals(genesis.requestId().toString())
          || !receipt.requestDigest().equals(genesis.requestDigest())
          || !receipt.accountId().equals(genesis.accountId().toString())
          || !receipt.tokenJti().equals(genesis.tokenJti().toString())
          || !receipt.tokenHash().equals(genesis.tokenHash())
          || !receipt.kid().equals(genesis.signerKid())
          || !receipt.signerGeneration().equals(genesis.signerGeneration())
          || receipt.issuedAtEpochSecond() != genesis.issuedAtEpochSecond()
          || receipt.notBeforeEpochSecond() != genesis.notBeforeEpochSecond()
          || receipt.expiresAtEpochSecond() != genesis.expiresAtEpochSecond()
          || receipt.issuanceFence() != genesis.issuanceFence()
          || receipt.registryVersion() != 1L
          || receipt.absoluteExpiryMillis() != absoluteExpiryMillis
          || receipt.localAofCount() < 1L
          || receipt.replicaAofCount() < 1L) {
        throw new IllegalStateException("ORIGINAL-NEW pending registry receipt is inconsistent");
      }
      return receipt;
    } finally {
      Arrays.fill(recordBytes, (byte) 0);
    }
  }

  /**
   * Activates one exact committed ORIGINAL-NEW projection after Account's authenticated GS read.
   */
  public AccountGameplayDelegationRedisClient.OriginalNewActivationReadback
      activateOriginalNewCommittedPending(Genesis genesis) {
    Objects.requireNonNull(genesis, "Committed Account ORIGINAL-NEW source required");
    requireNoAccountTransaction("ORIGINAL-NEW activation must not hold Account SQL locks");
    long absoluteExpiryMillis = originalNewAbsoluteExpiry(genesis);
    long nowMillis = clock.millis();
    if (nowMillis <= 0L || absoluteExpiryMillis <= nowMillis) throw unavailable();
    return coordinationRedis.activateOriginalNewCommittedPending(genesis, absoluteExpiryMillis);
  }

  /** Requires the exact already-active ORIGINAL-NEW projection and its original absolute expiry. */
  public AccountGameplayDelegationRedisClient.ActiveRegistryReadback
      requireExactOriginalNewActiveRecord(Genesis genesis) {
    Objects.requireNonNull(genesis, "Committed Account ORIGINAL-NEW source required");
    requireNoAccountTransaction("ORIGINAL-NEW registry reads must not hold Account SQL locks");
    if (clock.millis() <= 0L) throw unavailable();
    return coordinationRedis.requireExactOriginalNewActiveRecord(
        genesis, originalNewAbsoluteExpiry(genesis));
  }

  private long originalNewAbsoluteExpiry(Genesis genesis) {
    try {
      long tokenExpiryMillis = Math.multiplyExact(genesis.expiresAtEpochSecond(), 1_000L);
      long absoluteExpiryMillis = Math.addExact(tokenExpiryMillis, cleanupMarginMillis);
      if (absoluteExpiryMillis <= 0L) throw unavailable();
      return absoluteExpiryMillis;
    } catch (ArithmeticException malformed) {
      throw unavailable();
    }
  }

  /**
   * Requires the exact ACTIVE Redis projection for an already-current COMMITTED Account owner read.
   * This read grants no authority by itself and performs no registry activation.
   */
  public AccountGameplayDelegationRedisClient.ActiveRegistryReadback requireExactActiveRecord(
      CommittedCandidateVerificationData committedCandidate) {
    Objects.requireNonNull(committedCandidate, "Current COMMITTED Account evidence is required");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Active registry reads must not run under Account SQL locks");
    }
    AccountGameplayDelegationRedisClient.ActiveRegistryReadback active =
        coordinationRedis.readActiveRecord(committedCandidate.tokenSha256());
    var identity = committedCandidate.identity();
    if (!committedCandidate.tokenSha256().equals(active.tokenHash())
        || !identity.accountId().toString().equals(active.accountId())
        || !identity.tokenJti().toString().equals(active.jti())
        || identity.expiresAtEpochSecond() != active.tokenExpiryEpochSecond()
        || committedCandidate.registryAbsoluteExpiryMillis() != active.absoluteExpiryMillis()
        || !committedCandidate
            .canonicalRegistryRecordSha256()
            .equals(active.canonicalPendingRecordSha256())) {
      throw new IllegalStateException(
          "ACTIVE registry state differs from the exact current COMMITTED Account issuance");
    }
    return active;
  }

  /**
   * Performs the committed owner's exact Redis transition without holding Account SQL locks. The
   * caller must obtain this repository-issued value in its own bounded transaction and revalidate
   * current Account/signer ownership after this network operation.
   */
  AccountGameplayDelegationRedisClient.ActiveRegistrationReceipt activateCommittedCandidate(
      CommittedCandidateVerificationData committedCandidate) {
    Objects.requireNonNull(
        committedCandidate, "Repository-produced COMMITTED candidate evidence is required");
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Account transaction must not span Coordination Redis work");
    }
    var identity = committedCandidate.identity();
    long nowMillis = clock.millis();
    if (nowMillis <= 0L
        || Math.floorDiv(nowMillis, 1_000L) >= identity.expiresAtEpochSecond()
        || committedCandidate.registryAbsoluteExpiryMillis() <= nowMillis) {
      throw new IllegalStateException("Committed Account issuance is expired or unavailable");
    }
    return coordinationRedis.activateCommittedCandidate(committedCandidate);
  }

  private static void requireExactReceipt(
      AccountGameplayDelegationRedisClient.PendingRegistrationReceipt receipt,
      PendingRegistryCandidate candidate,
      byte[] canonicalRecordBytes,
      long absoluteExpiryMillis) {
    var identity = candidate.identity();
    if (receipt == null
        || !receipt
            .tokenKey()
            .equals(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + candidate.tokenHash())
        || !AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY.equals(receipt.aclIdentity())
        || !receipt.canonicalRecordSha256().equals(sha256(canonicalRecordBytes))
        || !receipt.operationId().equals(identity.operationId().toString())
        || !receipt.requestId().equals(identity.requestId().toString())
        || !receipt.requestDigest().equals(identity.requestDigest())
        || !receipt.accountId().equals(identity.accountId().toString())
        || !receipt.tokenJti().equals(identity.tokenJti().toString())
        || !receipt.tokenHash().equals(candidate.tokenHash())
        || !receipt.kid().equals(candidate.kid())
        || !receipt.signerGeneration().equals(candidate.signerGeneration())
        || receipt.issuedAtEpochSecond() != identity.issuedAtEpochSecond()
        || receipt.notBeforeEpochSecond() != identity.notBeforeEpochSecond()
        || receipt.expiresAtEpochSecond() != identity.expiresAtEpochSecond()
        || receipt.issuanceFence() != candidate.authoritySnapshot().issuanceFence()
        || receipt.registryVersion() != 1L
        || !receipt.evidenceBundleReference().equals(candidate.evidenceBundleReference())
        || receipt.absoluteExpiryMillis() != absoluteExpiryMillis
        || receipt.localAofCount() < 1L
        || receipt.replicaAofCount() < 1L) {
      throw new IllegalStateException("Account pending registration receipt is inconsistent");
    }
  }

  private static void requireNoAccountTransaction(String message) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(message);
    }
  }

  private static boolean authorityTupleMatches(
      Map<String, Object> fields, PendingRegistryCandidate candidate) {
    Object value = fields.get("authorityTuple");
    if (!(value instanceof Map<?, ?> tuple)) return false;
    Map<String, Object> expected =
        GameSessionAccountDelegationProfile.authorityTuple(
            candidate.authoritySnapshot().issuerGeneration(),
            candidate.authoritySnapshot().accountGeneration(),
            candidate.authoritySnapshot().accountSecurityCutoff());
    return expected.equals(tuple)
        && fields.get("membershipVersion") instanceof Map<?, ?> membershipVersion
        && membershipVersion.isEmpty();
  }

  private static boolean authoritySourceVersionsMatch(
      Map<String, Object> fields, PendingRegistryCandidate candidate) {
    Object value = fields.get("authoritySourceVersions");
    if (!(value instanceof Map<?, ?> versions)) return false;
    return positive(versions.get("issuerSourceVersion"))
            == candidate.authoritySnapshot().issuerSourceVersion()
        && positive(versions.get("accountSourceVersion"))
            == candidate.authoritySnapshot().accountSourceVersion()
        && positive(versions.get("issuanceFenceSourceVersion"))
            == candidate.authoritySnapshot().issuanceFenceSourceVersion();
  }

  private static long positive(Object value) {
    try {
      return AccountJwtExactValues.positiveDecimalCounter(value).longValueExact();
    } catch (IllegalArgumentException | ArithmeticException ex) {
      return -1L;
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original-new Account token registry is unavailable");
  }
}
