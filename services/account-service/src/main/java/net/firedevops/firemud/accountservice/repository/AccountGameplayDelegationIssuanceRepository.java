package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection.ProjectionObservation;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationIssuanceCommitService;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.PendingRegistrationOutcome;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile.AccountSecurityCutoff;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account-owned immutable issuance intent, candidate binding, and non-authorizing commit evidence.
 *
 * <p>This is deliberately not an Authenticate issuer, signer-promotion authority, response recovery
 * store, active-token validator, or registry activation path. It records a bounded pre-sign intent,
 * separately binds the candidate hash and canonical pending projection, and can persist COMMITTED
 * only with signer-owned verification, pinned Redis, current projection, full bundle, and sealed
 * envelope evidence. Every mutation locks the existing Account authority rows in the caller's
 * Account transaction; no remote operation is performed while those locks are held.
 */
@Repository
public class AccountGameplayDelegationIssuanceRepository {
  private static final String TABLE = "account_gameplay_delegation_issuance_operations";
  private static final int REQUEST_DIGEST_VERSION = 2;
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern POSITIVE_DECIMAL = Pattern.compile("[1-9][0-9]{0,18}");
  private static final int MAX_INTENT_BYTES = 16 * 1024;
  private static final int MAX_COMMIT_PROOF_BYTES =
      AccountGameplayDelegationIssuanceCommitService.MAX_COMMIT_PROOF_BYTES;
  private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.US_ASCII);
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private final DSLContext dsl;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
  private final AccountAuthEvidenceBundleRepository evidenceBundles;
  private final Clock clock;
  private final AccountGameplayTokenIdentityFenceRepository tokenIdentityFences;

  @Autowired
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep required collaborator preconditions fail-fast; this non-final Spring repository is proxied, no partially initialized instance escapes, and it declares no finalizer.")
  public AccountGameplayDelegationIssuanceRepository(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountAuthEvidenceBundleRepository evidenceBundles) {
    this(dsl, sourceEvidence, evidenceBundles, Clock.systemUTC());
  }

  /** Compatibility for non-signing repositories/fixtures that only use the pending boundary. */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep required collaborator preconditions fail-fast; this non-final Spring repository is proxied, no partially initialized instance escapes, and it declares no finalizer.")
  public AccountGameplayDelegationIssuanceRepository(
      DSLContext dsl, AccountAuthoritySourceEvidenceRepository sourceEvidence) {
    this(dsl, sourceEvidence, defaultEvidenceBundles(dsl), Clock.systemUTC());
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep required collaborator preconditions fail-fast; this non-final Spring repository is proxied, no partially initialized instance escapes, and it declares no finalizer.")
  public AccountGameplayDelegationIssuanceRepository(
      DSLContext dsl, AccountAuthoritySourceEvidenceRepository sourceEvidence, Clock clock) {
    this(dsl, sourceEvidence, defaultEvidenceBundles(dsl), clock);
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep required collaborator preconditions fail-fast; this non-final Spring repository is proxied, no partially initialized instance escapes, and it declares no finalizer.")
  public AccountGameplayDelegationIssuanceRepository(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountAuthEvidenceBundleRepository evidenceBundles,
      Clock clock) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.sourceEvidence =
        Objects.requireNonNull(sourceEvidence, "Account authority source evidence is required");
    this.evidenceBundles = evidenceBundles;
    this.clock = Objects.requireNonNull(clock, "Clock is required");
    this.tokenIdentityFences = new AccountGameplayTokenIdentityFenceRepository(dsl);
  }

  /** Persists an immutable pre-sign intent or returns its exact still-current replay. */
  @Transactional(propagation = Propagation.MANDATORY)
  public PendingOperation beginPending(PendingIntent intent) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(intent, "Account gameplay delegation intent is required");
    tokenIdentityFences.lockAccountForUpdate(intent.authoritySnapshot().accountId());
    lockRequestId(intent.requestId());
    long now = Math.floorDiv(clock.millis(), 1000L);
    long latestIssueTime;
    try {
      latestIssueTime = Math.addExact(now, 5L);
    } catch (ArithmeticException ex) {
      latestIssueTime = Long.MAX_VALUE;
    }
    if (now <= 0L
        || intent.expiresAtEpochSecond() <= now
        || intent.issuedAtEpochSecond() > latestIssueTime) {
      throw new IllegalArgumentException("Delegation issuance intent is expired");
    }
    IssuerAccountSourceSnapshot current = readAndCompareAuthority(intent);
    String digest = requestDigest(intent);
    Record prior = selectByRequestId(intent.requestId(), false);
    if (prior != null) {
      requireExactIntent(prior, intent, digest, current);
      Record locked = selectByRequestId(intent.requestId(), true);
      if (locked == null) throw new StorageUnavailableException();
      requireExactIntent(locked, intent, digest, current);
      return decodeOperation(locked);
    }

    insertIntent(intent, digest, current);
    Record readback = selectByRequestId(intent.requestId(), true);
    if (readback == null) throw new StorageUnavailableException();
    requireExactIntent(readback, intent, digest, current);
    return decodeOperation(readback);
  }

  /**
   * Reads the exact current PENDING or COMMITTED gameplay-login request under its request-id
   * serialization lock. A COMMITTED replay additionally requires the canonical persisted commit
   * proof. This metadata-only owner boundary rechecks current source authority and original expiry;
   * it never reconstructs the request identity or returns a credential.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public java.util.Optional<GameplayLoginReplayReadback> readCurrentGameplayLoginReplay(
      UUID requestId) {
    requireWritableAccountTransaction();
    requireRequestId(requestId);

    Record preliminary = selectByRequestId(requestId, false);
    if (preliminary == null) return java.util.Optional.empty();
    String preliminaryStatus = preliminary.get("status", String.class);
    if (!isReplayableGameplayLoginStatus(preliminaryStatus)) {
      throw new IdempotencyConflictException();
    }
    UUID accountId = preliminary.get("account_uuid", UUID.class);
    UUID operationId = preliminary.get("operation_id", UUID.class);
    if (accountId == null || operationId == null) throw new StorageUnavailableException();

    tokenIdentityFences.lockAccountForUpdate(accountId);
    lockRequestId(requestId);
    IssuerAccountSourceSnapshot current = readCurrentAuthority(accountId);
    Record locked = selectByRequestId(requestId, true);
    String lockedStatus = locked == null ? null : locked.get("status", String.class);
    if (locked == null
        || !accountId.equals(locked.get("account_uuid", UUID.class))
        || !operationId.equals(locked.get("operation_id", UUID.class))
        || !isReplayableGameplayLoginStatus(lockedStatus)
        || ("COMMITTED".equals(preliminaryStatus) && !"COMMITTED".equals(lockedStatus))) {
      throw new IdempotencyConflictException();
    }
    AccountGameplayDelegationPendingIdentity identity = identity(locked);
    requireStoredAuthority(locked, current);
    AccountAuthoritySnapshot currentAuthority = authoritySnapshot(current);
    PendingIntent exactIntent =
        new PendingIntent(
            identity.operationId(),
            identity.requestId(),
            identity.callerWorkload(),
            identity.callerContextId(),
            identity.tokenJti(),
            identity.issuedAtEpochSecond(),
            identity.notBeforeEpochSecond(),
            identity.expiresAtEpochSecond(),
            currentAuthority,
            identity.credentialRequestBinding());
    if (!requestId.equals(identity.requestId())
        || !identity.requestDigest().equals(requestDigest(exactIntent))) {
      throw new IdempotencyConflictException();
    }
    if (identity.expiresAtEpochSecond() <= Math.floorDiv(clock.millis(), 1000L)) {
      throw new PendingCandidateUnavailableException();
    }
    PendingOperation operation = decodeOperation(locked);
    CommittedIssuanceReadback committedProof = null;
    GameplayLoginReplayState replayState = GameplayLoginReplayState.PENDING;
    if ("COMMITTED".equals(lockedStatus)) {
      if (!operation.candidateBound()) throw new StorageUnavailableException();
      committedProof =
          decodeCommittedReadback(
              locked,
              locked.get("commit_proof_sha256", String.class),
              locked.get("commit_proof_canonical_bytes", byte[].class));
      replayState = GameplayLoginReplayState.COMMITTED;
    }
    return java.util.Optional.of(
        new GameplayLoginReplayReadback(identity, operation, replayState, committedProof, false));
  }

  /** Creates a single Account-owned request identity from the current locked authority snapshot. */
  @Transactional(propagation = Propagation.MANDATORY)
  public GameplayLoginReplayReadback beginPendingForAccount(
      UUID requestId,
      UUID accountId,
      String callerWorkload,
      UUID callerContextId,
      AccountGameplayCredentialRequestBinding credentialRequestBinding) {
    requireWritableAccountTransaction();
    requireRequestId(requestId);
    Objects.requireNonNull(accountId, "Account UUID is required");
    if (accountId.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("A non-nil Account UUID is required");
    }
    requireRequestId(callerContextId);
    Objects.requireNonNull(callerWorkload, "Caller workload is required");
    Objects.requireNonNull(
        credentialRequestBinding, "Keyed credential request binding is required");
    tokenIdentityFences.lockAccountForUpdate(accountId);
    lockRequestId(requestId);

    java.util.Optional<GameplayLoginReplayReadback> existing =
        readCurrentGameplayLoginReplay(requestId);
    if (existing.isPresent()) {
      GameplayLoginReplayReadback replay = existing.orElseThrow();
      AccountGameplayDelegationPendingIdentity identity = replay.identity();
      if (!accountId.equals(identity.accountId())
          || !callerWorkload.equals(identity.callerWorkload())
          || !callerContextId.equals(identity.callerContextId())
          || !credentialRequestBinding.equals(identity.credentialRequestBinding())) {
        throw new IdempotencyConflictException();
      }
      return replay;
    }

    IssuerAccountSourceSnapshot current = readCurrentAuthority(accountId);
    long issuedAt = Math.floorDiv(clock.millis(), 1000L);
    long expiresAt;
    try {
      expiresAt =
          Math.addExact(issuedAt, GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS);
    } catch (ArithmeticException ex) {
      throw new StorageUnavailableException();
    }
    PendingIntent intent =
        new PendingIntent(
            UUID.randomUUID(),
            requestId,
            callerWorkload,
            callerContextId,
            UUID.randomUUID(),
            issuedAt,
            issuedAt,
            expiresAt,
            authoritySnapshot(current),
            credentialRequestBinding);
    PendingOperation operation = beginPending(intent);
    Record readback = selectByRequestId(requestId, true);
    if (readback == null) throw new StorageUnavailableException();
    AccountGameplayDelegationPendingIdentity identity = identity(readback);
    requireExactIntent(readback, intent, requestDigest(intent), current);
    if (!operation.equals(decodeOperation(readback))) throw new StorageUnavailableException();
    return new GameplayLoginReplayReadback(
        identity, operation, GameplayLoginReplayState.PENDING, null, true);
  }

  /**
   * Reads the exact current owner identity and persisted full evidence bundle before signing or
   * resuming a previously signed candidate. Account and authority rows are locked before the
   * immutable operation row; the canonical bundle owner re-evaluates and returns its persisted
   * bytes in this same Account transaction.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public PendingSigningIdentity readPendingSigningIdentity(UUID requestId) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(requestId, "Request ID is required");
    if (evidenceBundles == null) throw new PendingCandidateUnavailableException();

    Record preliminary = selectByRequestId(requestId, false);
    if (preliminary == null) throw new PendingCandidateUnavailableException();
    UUID preliminaryAccountId = preliminary.get("account_uuid", UUID.class);
    UUID preliminaryOperationId = preliminary.get("operation_id", UUID.class);
    if (preliminaryAccountId == null || preliminaryOperationId == null) {
      throw new PendingCandidateUnavailableException();
    }

    IssuerAccountSourceSnapshot current = readCurrentAuthority(preliminaryAccountId);
    Record operation = selectByRequestId(requestId, true);
    if (operation == null
        || !preliminaryAccountId.equals(operation.get("account_uuid", UUID.class))
        || !preliminaryOperationId.equals(operation.get("operation_id", UUID.class))
        || !"PENDING".equals(operation.get("status", String.class))
        || !Objects.equals(operation.get("token_generation", Long.class), 1L)
        || !hasConsistentCandidateBinding(operation)) {
      throw new PendingCandidateUnavailableException();
    }

    AccountGameplayDelegationPendingIdentity identity = identity(operation);
    AccountAuthoritySnapshot authority = authoritySnapshot(current);
    if (!requestId.equals(identity.requestId())
        || !preliminaryAccountId.equals(identity.accountId())) {
      throw new PendingCandidateUnavailableException();
    }
    requireStoredAuthority(operation, current);
    PendingIntent exactIntent =
        new PendingIntent(
            identity.operationId(),
            identity.requestId(),
            identity.callerWorkload(),
            identity.callerContextId(),
            identity.tokenJti(),
            identity.issuedAtEpochSecond(),
            identity.notBeforeEpochSecond(),
            identity.expiresAtEpochSecond(),
            authority,
            identity.credentialRequestBinding());
    if (!identity.requestDigest().equals(requestDigest(exactIntent))) {
      throw new IdempotencyConflictException();
    }
    long now = Math.floorDiv(clock.millis(), 1000L);
    if (now <= 0L || identity.expiresAtEpochSecond() <= now) {
      throw new PendingCandidateUnavailableException();
    }

    AccountAuthEvidenceBundle captured = evidenceBundles.captureAndPersist(requestId);
    AccountAuthEvidenceBundle stored =
        evidenceBundles.readStoredNonAuthorizingValue(identity.operationId());
    if (!MessageDigest.isEqual(captured.canonicalBytes(), stored.canonicalBytes())) {
      throw new PendingCandidateUnavailableException();
    }
    requireExactSigningBundle(stored, identity, authority, current);
    Optional<PendingRegistryCandidate> persistedCandidate =
        operation.get("token_hash", String.class) == null
            ? Optional.empty()
            : Optional.of(decodePendingRegistryCandidate(operation, current, stored));
    return new PendingSigningIdentity(identity, authority, stored, persistedCandidate);
  }

  private static boolean hasConsistentCandidateBinding(Record operation) {
    String tokenHash = operation.get("token_hash", String.class);
    String kid = operation.get("signer_kid", String.class);
    String generation = operation.get("signer_generation", String.class);
    byte[] candidateBytes = operation.get("pending_registry_candidate_bytes", byte[].class);
    boolean any = tokenHash != null || kid != null || generation != null || candidateBytes != null;
    boolean all = tokenHash != null && kid != null && generation != null && candidateBytes != null;
    return !any || all;
  }

  /**
   * Binds the signed compact-token identity once. The raw JWT is transient and never stored; this
   * method cannot assert signature validity, promotion, or active-token authority.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public BoundTokenCandidate bindSignedCandidate(
      UUID requestId, String compactJwt, String signerGeneration) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(requestId, "Request ID is required");
    Objects.requireNonNull(compactJwt, "Compact JWT is required");
    requirePositiveDecimal(signerGeneration, "signerGeneration");
    Record prior = selectByRequestId(requestId, false);
    if (prior == null) throw new StorageUnavailableException();
    UUID accountId = prior.get("account_uuid", UUID.class);
    IssuerAccountSourceSnapshot current = readCurrentAuthority(accountId);
    Record operation = selectByRequestId(requestId, true);
    if (operation == null || !accountId.equals(operation.get("account_uuid", UUID.class))) {
      throw new StorageUnavailableException();
    }
    requireStoredAuthority(operation, current);

    AccountGameplayDelegationPendingIdentity identity = identity(operation);
    AccountAuthoritySnapshot accountSnapshot = authoritySnapshot(current);
    AccountAuthEvidenceBundle evidenceBundle =
        requireCurrentEvidenceBundle(requestId, identity, accountSnapshot, current);
    GameSessionAccountDelegationRegistryRecord candidate =
        GameSessionAccountDelegationRegistryRecord.fromAccountSignedCompactJwt(
            compactJwt,
            signerGeneration,
            new IssuanceBinding(
                identity.operationId().toString(),
                identity.requestId().toString(),
                identity.requestDigest(),
                identity.accountId().toString()),
            accountSnapshot,
            evidenceBundleReference(evidenceBundle),
            Math.floorDiv(clock.millis(), 1000L),
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    Map<String, Object> candidateFields = candidate.fields();
    if (!identity.operationId().toString().equals(candidate.operationId())
        || !identity.requestId().toString().equals(candidate.requestId())
        || !identity.requestDigest().equals(candidate.requestDigest())
        || !identity.accountId().toString().equals(candidate.accountId())
        || !identity.tokenJti().toString().equals(candidate.jti())
        || identity.issuedAtEpochSecond() != candidate.issuedAtEpochSecond()
        || identity.notBeforeEpochSecond() != candidate.notBeforeEpochSecond()
        || identity.expiresAtEpochSecond() != candidate.expiresAtEpochSecond()
        || candidate.issuanceFence() != authoritySnapshot(current).issuanceFence()
        || !Arrays.equals(
            canonicalJson(candidateFields.get("authorityTuple")),
            canonicalJson(authorityTuple(authoritySnapshot(current))))
        || !Arrays.equals(canonicalJson(candidateFields.get("membershipVersion")), EMPTY_OBJECT)
        || !Arrays.equals(
            canonicalJson(candidateFields.get("authoritySourceVersions")),
            canonicalJson(sourceVersionsMap(current)))) {
      throw new IdempotencyConflictException();
    }
    byte[] candidateBytes =
        candidate.toCanonicalJsonBytes(
            GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    String hash = candidate.tokenHash();
    String kid = candidate.kid();
    String existingHash = operation.get("token_hash", String.class);
    byte[] existingBytes = operation.get("pending_registry_candidate_bytes", byte[].class);
    String existingSigner = operation.get("signer_generation", String.class);
    String existingKid = operation.get("signer_kid", String.class);
    if (existingHash != null
        || existingBytes != null
        || existingSigner != null
        || existingKid != null) {
      if (!hash.equals(existingHash)
          || !Arrays.equals(candidateBytes, existingBytes)
          || !signerGeneration.equals(existingSigner)
          || !kid.equals(existingKid)) {
        throw new IdempotencyConflictException();
      }
      return new BoundTokenCandidate(identity.requestId(), hash, kid, signerGeneration);
    }

    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET token_hash = ?, signer_kid = ?, signer_generation = ?, "
                + "pending_registry_candidate_bytes = ? WHERE request_id = ? "
                + "AND status = 'PENDING' AND token_hash IS NULL AND signer_kid IS NULL "
                + "AND signer_generation IS NULL AND pending_registry_candidate_bytes IS NULL",
            hash,
            kid,
            signerGeneration,
            candidateBytes,
            requestId);
    if (changed != 1) throw new StorageUnavailableException();
    Record readback = selectByRequestId(requestId, true);
    if (readback == null
        || !hash.equals(readback.get("token_hash", String.class))
        || !kid.equals(readback.get("signer_kid", String.class))
        || !signerGeneration.equals(readback.get("signer_generation", String.class))
        || !Arrays.equals(
            candidateBytes, readback.get("pending_registry_candidate_bytes", byte[].class))) {
      throw new StorageUnavailableException();
    }
    requireStoredAuthority(readback, current);
    return new BoundTokenCandidate(identity.requestId(), hash, kid, signerGeneration);
  }

  /** Reads a canonical pending projection only after an exact current-authority SQL recheck. */
  @Transactional(propagation = Propagation.REQUIRED)
  public PendingRegistryCandidate readPendingRegistryCandidate(UUID requestId) {
    Objects.requireNonNull(requestId, "Request ID is required");
    Record prior = selectByRequestId(requestId, false);
    if (prior == null) throw new StorageUnavailableException();
    UUID accountId = prior.get("account_uuid", UUID.class);
    IssuerAccountSourceSnapshot current = readCurrentAuthority(accountId);
    Record row = selectByRequestId(requestId, true);
    if (row == null
        || !accountId.equals(row.get("account_uuid", UUID.class))
        || !"PENDING".equals(row.get("status", String.class))) {
      throw new StorageUnavailableException();
    }
    requireStoredAuthority(row, current);
    AccountAuthEvidenceBundle evidenceBundle =
        requireCurrentEvidenceBundle(requestId, identity(row), authoritySnapshot(current), current);
    return decodePendingRegistryCandidate(row, current, evidenceBundle);
  }

  private PendingRegistryCandidate decodePendingRegistryCandidate(
      Record row, IssuerAccountSourceSnapshot current, AccountAuthEvidenceBundle evidenceBundle) {
    byte[] bytes = row.get("pending_registry_candidate_bytes", byte[].class);
    String hash = row.get("token_hash", String.class);
    String kid = row.get("signer_kid", String.class);
    String signer = row.get("signer_generation", String.class);
    if (bytes == null || hash == null || kid == null || signer == null) {
      throw new PendingCandidateUnavailableException();
    }
    GameSessionAccountDelegationRegistryRecord record =
        GameSessionAccountDelegationRegistryRecord.decode(
            bytes, GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    AccountGameplayDelegationPendingIdentity identity = identity(row);
    if (!record.tokenHash().equals(hash)
        || !record.kid().equals(kid)
        || !record.signerGeneration().equals(signer)
        || !"pending".equals(record.state())
        || !record.operationId().equals(identity.operationId().toString())
        || !record.requestId().equals(identity.requestId().toString())
        || !record.requestDigest().equals(identity.requestDigest())
        || !record.accountId().equals(identity.accountId().toString())
        || !record.jti().equals(identity.tokenJti().toString())
        || record.issuanceFence() != authoritySnapshot(current).issuanceFence()
        || !Arrays.equals(
            canonicalJson(record.fields().get("authorityTuple")),
            canonicalJson(authorityTuple(authoritySnapshot(current))))
        || !Arrays.equals(canonicalJson(record.fields().get("membershipVersion")), EMPTY_OBJECT)
        || !Arrays.equals(
            canonicalJson(record.fields().get("authoritySourceVersions")),
            canonicalJson(sourceVersionsMap(current)))
        || !record.evidenceBundleReference().equals(evidenceBundleReference(evidenceBundle))
        || !Long.valueOf(identity.issuedAtEpochSecond())
            .equals(requireJsonInteger(record.fields().get("iat")))
        || !Long.valueOf(identity.notBeforeEpochSecond())
            .equals(requireJsonInteger(record.fields().get("nbf")))
        || !Long.valueOf(identity.expiresAtEpochSecond())
            .equals(requireJsonInteger(record.fields().get("exp")))) {
      throw new StorageUnavailableException();
    }
    return new PendingRegistryCandidate(
        identity,
        hash,
        kid,
        signer,
        record.expiresAtEpochSecond(),
        bytes,
        authoritySnapshot(current),
        evidenceBundleReference(evidenceBundle));
  }

  /**
   * Reads and validates the complete immutable COMMITTED owner evidence under the same Account,
   * authority, and operation lock order used by issuance. The result is non-authorizing metadata;
   * it deliberately contains neither the JWT, registry bytes, response ciphertext, nor proof bytes.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CommittedCandidateVerificationData readCurrentCommittedCandidate(UUID requestId) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(requestId, "Request ID is required");
    if (evidenceBundles == null) throw new StorageUnavailableException();

    Record preliminary = selectByRequestId(requestId, false);
    if (preliminary == null) throw new StorageUnavailableException();
    UUID accountId = preliminary.get("account_uuid", UUID.class);
    UUID operationId = preliminary.get("operation_id", UUID.class);
    if (accountId == null || operationId == null) throw new StorageUnavailableException();

    IssuerAccountSourceSnapshot current = readCurrentAuthority(accountId);
    Record row = selectByRequestId(requestId, true);
    if (row == null
        || !accountId.equals(row.get("account_uuid", UUID.class))
        || !operationId.equals(row.get("operation_id", UUID.class))
        || !requestId.equals(row.get("request_id", UUID.class))
        || !"COMMITTED".equals(row.get("status", String.class))
        || !Objects.equals(row.get("token_generation", Long.class), 1L)
        || row.get("token_hash", String.class) == null
        || row.get("signer_kid", String.class) == null
        || row.get("signer_generation", String.class) == null
        || row.get("pending_registry_candidate_bytes", byte[].class) == null) {
      throw new StorageUnavailableException();
    }
    AccountGameplayDelegationPendingIdentity identity = identity(row);
    AccountAuthoritySnapshot authority = authoritySnapshot(current);
    requireStoredAuthority(row, current);
    PendingIntent exactIntent =
        new PendingIntent(
            identity.operationId(),
            identity.requestId(),
            identity.callerWorkload(),
            identity.callerContextId(),
            identity.tokenJti(),
            identity.issuedAtEpochSecond(),
            identity.notBeforeEpochSecond(),
            identity.expiresAtEpochSecond(),
            authority,
            identity.credentialRequestBinding());
    if (!requestDigest(exactIntent).equals(identity.requestDigest())) {
      throw new IdempotencyConflictException();
    }
    requireUnexpired(identity);

    AccountAuthEvidenceBundle bundle;
    try {
      bundle = evidenceBundles.readStoredNonAuthorizingValue(operationId);
    } catch (RuntimeException failure) {
      throw new StorageUnavailableException();
    }
    requireExactSigningBundle(bundle, identity, authority, current);
    PendingRegistryCandidate candidate = decodePendingRegistryCandidate(row, current, bundle);

    CommittedIssuanceReadback readback = decodeCommittedReadback(row, null, null);
    String proofSha256 = row.get("commit_proof_sha256", String.class);
    byte[] proofBytes = row.get("commit_proof_canonical_bytes", byte[].class);
    Map<String, Object> proof = parseCommitProof(proofBytes);
    requireCommittedProofIdentity(proof, identity, readback, candidate, bundle, current);
    tokenIdentityFences.requireActiveForUpdate(tokenIdentity(row));
    AuthenticatedSignerCorrespondence correspondence =
        requireCommittedSignerProof(proof, candidate);
    EnvelopeIdentity envelope =
        requireCommittedEnvelopeProof(proof, identity, candidate, authority, bundle);
    Map<?, ?> persistedRegistry = requireMap(proof.get("registry"));
    long registryExpiryMillis =
        positiveCanonicalLong(persistedRegistry.get("absoluteExpiryMillis"));
    long registrationLocalAofCount = positiveCanonicalLong(persistedRegistry.get("localAofCount"));
    long registrationReplicaAofCount =
        positiveCanonicalLong(persistedRegistry.get("replicaAofCount"));
    PendingRegistrationOutcome registrationOutcome =
        switch (Objects.toString(persistedRegistry.get("outcome"), "")) {
          case "CREATED" -> PendingRegistrationOutcome.CREATED;
          case "EXACT_RETRY" -> PendingRegistrationOutcome.EXACT_RETRY;
          default -> throw new StorageUnavailableException();
        };

    return new CommittedCandidateVerificationData(
        identity,
        authority,
        evidenceBundleReference(bundle),
        candidate.tokenHash(),
        candidate.kid(),
        candidate.signerGeneration(),
        sha256(candidate.canonicalRecordBytes()),
        proofSha256,
        correspondence,
        envelope.keyId(),
        envelope.sha256(),
        envelope.length(),
        envelope.expiryMillis(),
        registryExpiryMillis,
        registrationLocalAofCount,
        registrationReplicaAofCount,
        registrationOutcome);
  }

  /**
   * Resolves an untrusted signed-token JTI only to the request key of a committed operation, then
   * runs the same full current-authority/commit-proof read used by the issuance owner. The JTI is a
   * lookup hint, never authentication or permission by itself.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public java.util.Optional<CommittedCandidateVerificationData> readCurrentCommittedCandidateByJti(
      UUID tokenJti) {
    requireWritableAccountTransaction();
    if (tokenJti == null || tokenJti.equals(new UUID(0L, 0L))) {
      throw new IllegalArgumentException("Canonical non-nil token JTI is required");
    }
    Record requestRow;
    try {
      requestRow =
          dsl.fetchOne(
              "SELECT request_id FROM account_gameplay_delegation_issuance_operations "
                  + "WHERE token_jti = ? AND status = 'COMMITTED' AND token_generation = 1",
              tokenJti);
    } catch (org.jooq.exception.TooManyRowsException ambiguous) {
      throw new StorageUnavailableException();
    }
    if (requestRow == null) return java.util.Optional.empty();
    UUID requestId = requestRow.get("request_id", UUID.class);
    if (requestId == null || requestId.equals(new UUID(0L, 0L))) {
      throw new StorageUnavailableException();
    }
    CommittedCandidateVerificationData current = readCurrentCommittedCandidate(requestId);
    if (current == null
        || !requestId.equals(current.identity().requestId())
        || !tokenJti.equals(current.identity().tokenJti())) {
      throw new StorageUnavailableException();
    }
    return java.util.Optional.of(current);
  }

  private void requireUnexpired(AccountGameplayDelegationPendingIdentity identity) {
    long now = Math.floorDiv(clock.millis(), 1_000L);
    long latestIssuedAt = now > Long.MAX_VALUE - 5L ? Long.MAX_VALUE : now + 5L;
    if (now <= 0L
        || identity.notBeforeEpochSecond() > now
        || identity.issuedAtEpochSecond() > latestIssuedAt
        || identity.expiresAtEpochSecond() <= now) {
      throw new StorageUnavailableException();
    }
  }

  private static void requireCommittedProofIdentity(
      Map<String, Object> proof,
      AccountGameplayDelegationPendingIdentity identity,
      CommittedIssuanceReadback readback,
      PendingRegistryCandidate candidate,
      AccountAuthEvidenceBundle bundle,
      IssuerAccountSourceSnapshot current) {
    Map<?, ?> proofIdentity = requireMap(proof.get("identity"));
    requireExactFields(
        proofIdentity,
        Set.of(
            "operationId",
            "requestId",
            "accountId",
            "requestDigest",
            "callerWorkload",
            "callerContextId",
            "tokenJti",
            "tokenGeneration",
            "issuedAtEpochSecond",
            "notBeforeEpochSecond",
            "expiresAtEpochSecond",
            "tokenSha256"));
    if (!AccountGameplayDelegationIssuanceCommitService.COMMIT_PROOF_SCHEMA.equals(
            proof.get("schema"))
        || !identity.operationId().toString().equals(proofIdentity.get("operationId"))
        || !identity.requestId().toString().equals(proofIdentity.get("requestId"))
        || !identity.accountId().toString().equals(proofIdentity.get("accountId"))
        || !identity.requestDigest().equals(proofIdentity.get("requestDigest"))
        || !identity.callerWorkload().equals(proofIdentity.get("callerWorkload"))
        || !identity.callerContextId().toString().equals(proofIdentity.get("callerContextId"))
        || !identity.tokenJti().toString().equals(proofIdentity.get("tokenJti"))
        || !"1".equals(proofIdentity.get("tokenGeneration"))
        || !Long.toString(identity.issuedAtEpochSecond())
            .equals(proofIdentity.get("issuedAtEpochSecond"))
        || !Long.toString(identity.notBeforeEpochSecond())
            .equals(proofIdentity.get("notBeforeEpochSecond"))
        || !Long.toString(identity.expiresAtEpochSecond())
            .equals(proofIdentity.get("expiresAtEpochSecond"))
        || !candidate.tokenHash().equals(proofIdentity.get("tokenSha256"))
        || !candidate.tokenHash().equals(proof.get("tokenSha256"))
        || !sha256(candidate.canonicalRecordBytes()).equals(proof.get("registryRecordSha256"))
        || !readback.operationId().equals(identity.operationId())
        || !readback.requestId().equals(identity.requestId())
        || !readback.accountId().equals(identity.accountId())
        || !readback.requestDigest().equals(identity.requestDigest())
        || !readback.tokenHash().equals(candidate.tokenHash())
        || !readback.signerKid().equals(candidate.kid())
        || !readback.signerGeneration().equals(candidate.signerGeneration())) {
      throw new StorageUnavailableException();
    }

    Map<?, ?> registry = requireMap(proof.get("registry"));
    requireExactFields(
        registry,
        Set.of(
            "state",
            "registryVersion",
            "tokenKey",
            "tokenHash",
            "kid",
            "signerGeneration",
            "operationId",
            "requestId",
            "requestDigest",
            "absoluteExpiryMillis",
            "localAofCount",
            "replicaAofCount",
            "outcome",
            "canonicalRecordSha256",
            "evidenceBundleSha256",
            "aclIdentity"));
    long tokenExpiryMillis;
    long registryExpiryMillis;
    try {
      tokenExpiryMillis = Math.multiplyExact(identity.expiresAtEpochSecond(), 1_000L);
      registryExpiryMillis = positiveCanonicalLong(registry.get("absoluteExpiryMillis"));
    } catch (RuntimeException invalidExpiry) {
      throw new StorageUnavailableException();
    }
    long expiryMargin;
    try {
      expiryMargin = Math.subtractExact(registryExpiryMillis, tokenExpiryMillis);
    } catch (ArithmeticException invalidMargin) {
      throw new StorageUnavailableException();
    }
    Object outcome = registry.get("outcome");
    if (!"pending".equals(registry.get("state"))
        || positiveCanonicalLong(registry.get("registryVersion")) != 1L
        || !AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX
            .concat(candidate.tokenHash())
            .equals(registry.get("tokenKey"))
        || !candidate.tokenHash().equals(registry.get("tokenHash"))
        || !candidate.kid().equals(registry.get("kid"))
        || !candidate.signerGeneration().equals(registry.get("signerGeneration"))
        || !identity.operationId().toString().equals(registry.get("operationId"))
        || !identity.requestId().toString().equals(registry.get("requestId"))
        || !identity.requestDigest().equals(registry.get("requestDigest"))
        || expiryMargin < 0L
        || expiryMargin > 300_000L
        || positiveCanonicalLong(registry.get("localAofCount")) < 1L
        || positiveCanonicalLong(registry.get("replicaAofCount")) < 1L
        || !("CREATED".equals(outcome) || "EXACT_RETRY".equals(outcome))
        || !sha256(candidate.canonicalRecordBytes()).equals(registry.get("canonicalRecordSha256"))
        || !bundle.canonicalSha256().equals(registry.get("evidenceBundleSha256"))
        || !AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY.equals(
            registry.get("aclIdentity"))) {
      throw new StorageUnavailableException();
    }

    Map<?, ?> bundleReference = requireMap(proof.get("bundle"));
    if (!sameCanonicalMap(bundleReference, evidenceBundleReference(bundle).toMap())) {
      throw new StorageUnavailableException();
    }

    Map<?, ?> authority = requireMap(proof.get("authority"));
    requireExactFields(
        authority,
        Set.of(
            "issuerGeneration",
            "issuerSourceVersion",
            "accountGeneration",
            "accountSourceVersion",
            "issuanceFence",
            "issuanceFenceSourceVersion",
            "authorityTuple",
            "authoritySourceVersions",
            "issuerProjectionGeneration",
            "accountProjectionGeneration",
            "issuerProjectionSha256",
            "accountProjectionSha256"));
    byte[][] projectionPair =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(current);
    AccountAuthoritySnapshot snapshot = authoritySnapshot(current);
    if (!Long.toString(snapshot.issuerGeneration()).equals(authority.get("issuerGeneration"))
        || !Long.toString(snapshot.issuerSourceVersion())
            .equals(authority.get("issuerSourceVersion"))
        || !Long.toString(snapshot.accountGeneration()).equals(authority.get("accountGeneration"))
        || !Long.toString(snapshot.accountSourceVersion())
            .equals(authority.get("accountSourceVersion"))
        || !Long.toString(snapshot.issuanceFence()).equals(authority.get("issuanceFence"))
        || !Long.toString(snapshot.issuanceFenceSourceVersion())
            .equals(authority.get("issuanceFenceSourceVersion"))
        || !sameCanonicalMap(authority.get("authorityTuple"), authorityTuple(snapshot))
        || !sameCanonicalMap(authority.get("authoritySourceVersions"), snapshot.sourceVersions())
        || !Long.toString(snapshot.issuerGeneration())
            .equals(authority.get("issuerProjectionGeneration"))
        || !Long.toString(snapshot.accountGeneration())
            .equals(authority.get("accountProjectionGeneration"))
        || !sha256(projectionPair[0]).equals(authority.get("issuerProjectionSha256"))
        || !sha256(projectionPair[1]).equals(authority.get("accountProjectionSha256"))) {
      throw new StaleAuthorityException();
    }
  }

  private static AuthenticatedSignerCorrespondence requireCommittedSignerProof(
      Map<String, Object> proof, PendingRegistryCandidate candidate) {
    Map<?, ?> signer = requireMap(proof.get("signer"));
    requireExactFields(
        signer,
        Set.of(
            "promotionStatus",
            "promotionOperationId",
            "generationOperationId",
            "targetKid",
            "targetGeneration",
            "correspondenceSha256",
            "correspondence"));
    Map<?, ?> correspondenceMap = requireMap(signer.get("correspondence"));
    Set<String> correspondenceFields =
        Arrays.stream(AuthenticatedSignerCorrespondence.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    requireExactFields(correspondenceMap, correspondenceFields);
    if (!"COMMITTED".equals(signer.get("promotionStatus"))
        || !candidate.kid().equals(signer.get("targetKid"))
        || !candidate.signerGeneration().equals(signer.get("targetGeneration"))
        || !sha256(canonicalProofJson(correspondenceMap))
            .equals(signer.get("correspondenceSha256"))) {
      throw new StorageUnavailableException();
    }
    try {
      byte[] bytes = JSON.writeValueAsBytes(correspondenceMap);
      AuthenticatedSignerCorrespondence correspondence =
          JSON.readValue(bytes, AuthenticatedSignerCorrespondence.class);
      if (!correspondence
              .promotionOperationId()
              .toString()
              .equals(signer.get("promotionOperationId"))
          || !correspondence
              .generationOperationId()
              .toString()
              .equals(signer.get("generationOperationId"))
          || !"COMMITTED".equals(correspondence.promotionStatus())
          || !correspondence.privatePromotionDispatched()
          || !correspondence
              .promotionOperationId()
              .equals(correspondence.privatePromotionOperationId())
          || !correspondence
              .promotionOperationId()
              .equals(correspondence.publicPromotionOperationId())
          || !correspondence
              .generationOperationId()
              .equals(correspondence.privatePromotionGenerationOperationId())
          || correspondence
              .durableActive()
              .filter(
                  active ->
                      active.generation().equals(candidate.signerGeneration())
                          && active.kid().equals(candidate.kid()))
              .isEmpty()
          || correspondence
              .publishedActive()
              .filter(
                  active ->
                      active.generation().equals(candidate.signerGeneration())
                          && active.kid().equals(candidate.kid()))
              .isEmpty()
          || correspondence
              .promotionPrivateObservedResourceVersion()
              .filter(correspondence.privatePromotionObservedResourceVersion()::equals)
              .isEmpty()
          || correspondence
              .promotionPrivateReceiptDigest()
              .filter(correspondence.privatePromotionReceiptDigest()::equals)
              .isEmpty()
          || correspondence
              .activeJwksObservedResourceVersion()
              .filter(correspondence.observedPublicResourceVersion()::equals)
              .isEmpty()
          || correspondence
              .activeJwksPublicDataDigest()
              .filter(correspondence.publicDataDigest()::equals)
              .isEmpty()
          || correspondence
              .activeJwksReceiptDigest()
              .filter(correspondence.publicReceiptDigest()::equals)
              .isEmpty()
          || !correspondence
              .publicConfigMapUid()
              .equals(correspondence.publicJwksSourceIdentity().configMapUid())) {
        throw new StorageUnavailableException();
      }
      return correspondence;
    } catch (RuntimeException failure) {
      throw new StorageUnavailableException();
    }
  }

  private EnvelopeIdentity requireCommittedEnvelopeProof(
      Map<String, Object> proof,
      AccountGameplayDelegationPendingIdentity identity,
      PendingRegistryCandidate candidate,
      AccountAuthoritySnapshot authority,
      AccountAuthEvidenceBundle bundle) {
    Map<?, ?> envelopeProof = requireMap(proof.get("envelope"));
    requireExactFields(
        envelopeProof,
        Set.of(
            "operationId",
            "requestId",
            "tokenHash",
            "kid",
            "signerGeneration",
            "authorityEvidenceBundleSha256",
            "issuanceFence",
            "responseRecoveryExpiryEpochMillis",
            "envelopeSha256",
            "envelopeBytesLength"));
    long expectedExpiryMillis;
    try {
      expectedExpiryMillis = Math.multiplyExact(identity.expiresAtEpochSecond(), 1_000L);
    } catch (ArithmeticException failure) {
      throw new StorageUnavailableException();
    }
    Record envelope =
        dsl.fetchOne(
            "SELECT operation_id, request_id, token_hash, signer_kid, signer_generation, "
                + "authority_evidence_bundle_sha256, issuance_fence, "
                + "response_recovery_expiry_epoch_ms, envelope_sha256, envelope_bytes "
                + "FROM account_gameplay_delegation_response_envelopes "
                + "WHERE operation_id = ? FOR SHARE",
            identity.operationId());
    if (envelope == null) throw new StorageUnavailableException();
    byte[] bytes = envelope.get("envelope_bytes", byte[].class);
    String digest = envelope.get("envelope_sha256", String.class);
    String tokenHash = envelope.get("token_hash", String.class);
    String keyId = envelope.get("signer_kid", String.class);
    String signerGeneration = envelope.get("signer_generation", String.class);
    long expiry = positive(envelope.get("response_recovery_expiry_epoch_ms", Long.class), "expiry");
    if (bytes == null
        || bytes.length == 0
        || bytes.length > 65_610
        || !SHA256.matcher(Objects.toString(digest, "")).matches()
        || !digest.equals(sha256(bytes))
        || !identity.operationId().equals(envelope.get("operation_id", UUID.class))
        || !identity.requestId().equals(envelope.get("request_id", UUID.class))
        || !candidate.tokenHash().equals(tokenHash)
        || !candidate.kid().equals(keyId)
        || !candidate.signerGeneration().equals(signerGeneration)
        || !bundle
            .canonicalSha256()
            .equals(envelope.get("authority_evidence_bundle_sha256", String.class))
        || authority.issuanceFence()
            != positive(envelope.get("issuance_fence", Long.class), "issuance fence")
        || expiry != expectedExpiryMillis
        || !identity.operationId().toString().equals(envelopeProof.get("operationId"))
        || !identity.requestId().toString().equals(envelopeProof.get("requestId"))
        || !candidate.tokenHash().equals(envelopeProof.get("tokenHash"))
        || !candidate.kid().equals(envelopeProof.get("kid"))
        || !candidate.signerGeneration().equals(envelopeProof.get("signerGeneration"))
        || !bundle.canonicalSha256().equals(envelopeProof.get("authorityEvidenceBundleSha256"))
        || !Long.toString(authority.issuanceFence()).equals(envelopeProof.get("issuanceFence"))
        || !Long.toString(expiry).equals(envelopeProof.get("responseRecoveryExpiryEpochMillis"))
        || !digest.equals(envelopeProof.get("envelopeSha256"))
        || !Integer.toString(bytes.length).equals(envelopeProof.get("envelopeBytesLength"))) {
      throw new StorageUnavailableException();
    }
    EnvelopeHeader header = parseEnvelopeHeader(bytes, expiry);
    return new EnvelopeIdentity(digest, bytes.length, expiry, header.keyId());
  }

  private static long positiveCanonicalLong(Object value) {
    if (!(value instanceof String text) || !POSITIVE_DECIMAL.matcher(text).matches()) {
      throw new StorageUnavailableException();
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException invalid) {
      throw new StorageUnavailableException();
    }
  }

  private static EnvelopeHeader parseEnvelopeHeader(byte[] bytes, long expectedExpiryMillis) {
    final byte[] magic = {'F', 'R', 'E', 'A'};
    final int minimumLength = 4 + 1 + 1 + 1 + Long.BYTES + 12 + 16 + 1;
    if (bytes.length < minimumLength
        || bytes.length > 65_610
        || bytes[0] != magic[0]
        || bytes[1] != magic[1]
        || bytes[2] != magic[2]
        || bytes[3] != magic[3]
        || bytes[4] != 1) {
      throw new StorageUnavailableException();
    }
    int keyLength = Byte.toUnsignedInt(bytes[5]);
    if (keyLength < 1
        || keyLength > 32
        || bytes.length < 6 + keyLength + Long.BYTES + 12 + 16 + 1) {
      throw new StorageUnavailableException();
    }
    String keyId = new String(bytes, 6, keyLength, StandardCharsets.US_ASCII);
    if (!keyId.matches("[A-Za-z0-9_-]{1,32}")) throw new StorageUnavailableException();
    long expiry = ByteBuffer.wrap(bytes, 6 + keyLength, Long.BYTES).getLong();
    if (expiry <= 0L || expiry != expectedExpiryMillis) throw new StorageUnavailableException();
    return new EnvelopeHeader(keyId);
  }

  private static boolean sameCanonicalMap(Object actual, Object expected) {
    try {
      return Arrays.equals(canonicalJson(actual), canonicalJson(expected));
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private static void requireExactFields(Map<?, ?> value, Set<String> fields) {
    if (!value.keySet().equals(fields)) throw new StorageUnavailableException();
  }

  /**
   * Persists signer-verified, exact pinned-Redis registration and source-projection evidence as one
   * immutable non-authorizing PENDING-to-COMMITTED transition. Network work is supplied only as
   * private-constructor owner receipts and is complete before this Account transaction begins.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public CommittedIssuanceReadback commitPendingCandidate(
      UUID requestId,
      AccountGameplayDelegationSigner.PendingCandidateVerificationProof signerProof,
      AccountGameplayDelegationRedisClient.PendingRegistrationReceipt redisReceipt,
      ProjectionObservation projectionObservation,
      AccountGameplayDelegationIssuanceCommitService.CommitProof commitProof) {
    requireWritableAccountTransaction();
    Objects.requireNonNull(requestId, "Request ID is required");
    Objects.requireNonNull(signerProof, "Signer-owned candidate proof is required");
    Objects.requireNonNull(redisReceipt, "Pinned pending registration receipt is required");
    Objects.requireNonNull(projectionObservation, "Current Account projection is required");
    Objects.requireNonNull(commitProof, "Canonical Account commit proof is required");

    PendingRegistryCandidate candidate = readPendingRegistryCandidate(requestId);
    AccountGameplayDelegationPendingIdentity identity = candidate.identity();
    if (!identity.operationId().equals(signerProof.operationId())
        || !requestId.equals(signerProof.requestId())
        || !identity.accountId().equals(signerProof.accountId())
        || !identity.requestDigest().equals(signerProof.identity().requestDigest())
        || !identity.callerWorkload().equals(signerProof.identity().callerWorkload())
        || !identity.callerContextId().equals(signerProof.identity().callerContextId())
        || !identity.tokenJti().equals(signerProof.tokenJti())
        || identity.issuedAtEpochSecond() != signerProof.issuedAtEpochSecond()
        || identity.notBeforeEpochSecond() != signerProof.notBeforeEpochSecond()
        || identity.expiresAtEpochSecond() != signerProof.expiresAtEpochSecond()
        || !candidate.tokenHash().equals(signerProof.tokenSha256())
        || !candidate.kid().equals(signerProof.signerKid())
        || !candidate.signerGeneration().equals(signerProof.signerGeneration())
        || candidate.authoritySnapshot().issuanceFence() != signerProof.issuanceFence()
        || !candidate.authoritySnapshot().equals(signerProof.authoritySnapshot())
        || !candidate.evidenceBundleReference().equals(signerProof.evidenceBundleReference())
        || !sha256(candidate.canonicalRecordBytes())
            .equals(signerProof.canonicalRegistryRecordSha256())
        || !commitProof.operationId().equals(identity.operationId())
        || !commitProof.requestId().equals(requestId)
        || !commitProof.accountId().equals(identity.accountId())
        || !commitProof.requestDigest().equals(identity.requestDigest())
        || !commitProof.tokenSha256().equals(candidate.tokenHash())) {
      throw new IdempotencyConflictException();
    }

    IssuerAccountSourceSnapshot current = readCurrentAuthority(identity.accountId());
    AccountAuthoritySnapshot currentAuthority = authoritySnapshot(current);
    if (!currentAuthority.equals(candidate.authoritySnapshot())
        || !currentAuthority.equals(signerProof.authoritySnapshot())) {
      throw new StaleAuthorityException();
    }
    requireExactProjectionObservation(projectionObservation, current);
    requireExactRedisReceipt(redisReceipt, candidate);
    requireExactSealedEnvelope(signerProof.sealedCandidate(), candidate);
    requireCanonicalCommitProof(commitProof, signerProof, redisReceipt, projectionObservation);

    int changed =
        dsl.execute(
            "UPDATE "
                + TABLE
                + " SET status = 'COMMITTED', commit_proof_version = 1, commit_proof_sha256 = ?, "
                + "commit_proof_canonical_bytes = ?, committed_at = CURRENT_TIMESTAMP "
                + "WHERE request_id = ? AND status = 'PENDING' AND token_hash = ? "
                + "AND signer_kid = ? AND signer_generation = ? "
                + "AND pending_registry_candidate_bytes = ?",
            commitProof.sha256(),
            commitProof.canonicalBytes(),
            requestId,
            candidate.tokenHash(),
            candidate.kid(),
            candidate.signerGeneration(),
            candidate.canonicalRecordBytes());
    if (changed != 1) throw new StaleAuthorityException();

    Record readback = selectByRequestId(requestId, true);
    CommittedIssuanceReadback exact =
        decodeCommittedReadback(readback, commitProof.sha256(), commitProof.canonicalBytes());
    requireStoredAuthority(readback, current);
    tokenIdentityFences.registerCommittedIssuance(tokenIdentity(readback));
    return exact;
  }

  /**
   * Reads only the immutable non-authorizing COMMITTED result. A prior exact commit replay never
   * invokes the signer, extends Redis TTL, or returns/decrypts the encrypted response envelope.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public java.util.Optional<CommittedIssuanceReadback> readCommittedProof(UUID requestId) {
    Objects.requireNonNull(requestId, "Request ID is required");
    Record row = selectByRequestId(requestId, false);
    if (row == null) throw new StorageUnavailableException();
    String status = row.get("status", String.class);
    if ("PENDING".equals(status)) return java.util.Optional.empty();
    if (!"COMMITTED".equals(status)) throw new StorageUnavailableException();
    String proofSha = row.get("commit_proof_sha256", String.class);
    byte[] proofBytes = row.get("commit_proof_canonical_bytes", byte[].class);
    CommittedIssuanceReadback exact = decodeCommittedReadback(row, proofSha, proofBytes);
    tokenIdentityFences.requireActiveReadback(tokenIdentity(row));
    return java.util.Optional.of(exact);
  }

  private static void requireExactProjectionObservation(
      ProjectionObservation observation, IssuerAccountSourceSnapshot current) {
    byte[][] expected =
        AccountGameplayDelegationAuthorityProjection.canonicalProjectionPair(current);
    var issuer =
        AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            expected[0], "issuer", GameSessionAccountDelegationProfile.ISSUER);
    var account =
        AccountGameplayDelegationAuthorityProjection.ProjectionValue.decode(
            expected[1], "account", current.account().scope().accountId().toString());
    if (!issuer.digest().equals(observation.issuerDigest())
        || !account.digest().equals(observation.accountDigest())
        || issuer.generation() != observation.issuerGeneration()
        || !account
            .generationValue()
            .equals(java.math.BigInteger.valueOf(observation.accountGeneration()))
        || !current.issuanceFence().equals(current.account().issuanceFence())) {
      throw new StaleAuthorityException();
    }
  }

  private static void requireExactRedisReceipt(
      AccountGameplayDelegationRedisClient.PendingRegistrationReceipt receipt,
      PendingRegistryCandidate candidate) {
    AccountGameplayDelegationPendingIdentity identity = candidate.identity();
    long tokenExpiry;
    try {
      tokenExpiry = Math.multiplyExact(identity.expiresAtEpochSecond(), 1_000L);
      long margin = Math.subtractExact(receipt.absoluteExpiryMillis(), tokenExpiry);
      if (margin < 0L || margin > 300_000L) throw new StorageUnavailableException();
    } catch (ArithmeticException ex) {
      throw new StorageUnavailableException();
    }
    if (!receipt
            .tokenKey()
            .equals(AccountGameplayDelegationRedisClient.TOKEN_KEY_PREFIX + candidate.tokenHash())
        || !AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY.equals(receipt.aclIdentity())
        || !receipt.canonicalRecordSha256().equals(sha256(candidate.canonicalRecordBytes()))
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
        || receipt.localAofCount() < 1L
        || receipt.replicaAofCount() < 1L) {
      throw new StorageUnavailableException();
    }
  }

  private void requireExactSealedEnvelope(
      AccountGameplayDelegationResponseEnvelopeRepository.SealedCandidateObservation sealed,
      PendingRegistryCandidate candidate) {
    Record envelope =
        dsl.fetchOne(
            "SELECT operation_id, request_id, token_hash, signer_kid, signer_generation, "
                + "authority_evidence_bundle_sha256, issuance_fence, "
                + "response_recovery_expiry_epoch_ms, envelope_sha256, "
                + "octet_length(envelope_bytes) AS envelope_bytes_length "
                + "FROM account_gameplay_delegation_response_envelopes "
                + "WHERE operation_id = ? FOR SHARE",
            candidate.identity().operationId());
    if (envelope == null
        || !Objects.equals(envelope.get("operation_id", UUID.class), sealed.operationId())
        || !Objects.equals(envelope.get("request_id", UUID.class), sealed.requestId())
        || !Objects.equals(envelope.get("token_hash", String.class), candidate.tokenHash())
        || !Objects.equals(envelope.get("signer_kid", String.class), candidate.kid())
        || !Objects.equals(
            envelope.get("signer_generation", String.class), candidate.signerGeneration())
        || !Objects.equals(
            envelope.get("authority_evidence_bundle_sha256", String.class),
            sealed.authorityEvidenceBundleSha256())
        || !Objects.equals(envelope.get("issuance_fence", Long.class), sealed.issuanceFence())
        || !Objects.equals(
            envelope.get("response_recovery_expiry_epoch_ms", Long.class),
            sealed.responseRecoveryExpiryEpochMillis())
        || !Objects.equals(envelope.get("envelope_sha256", String.class), sealed.envelopeSha256())
        || !Objects.equals(
            envelope.get("envelope_bytes_length", Integer.class), sealed.envelopeBytesLength())) {
      throw new StorageUnavailableException();
    }
  }

  private static void requireCanonicalCommitProof(
      AccountGameplayDelegationIssuanceCommitService.CommitProof commitProof,
      AccountGameplayDelegationSigner.PendingCandidateVerificationProof signerProof,
      AccountGameplayDelegationRedisClient.PendingRegistrationReceipt redisReceipt,
      ProjectionObservation projectionObservation) {
    byte[] bytes = commitProof.canonicalBytes();
    if (bytes.length == 0
        || bytes.length > MAX_COMMIT_PROOF_BYTES
        || !sha256(bytes).equals(commitProof.sha256())
        || !commitProof.operationId().equals(signerProof.operationId())
        || !commitProof.requestId().equals(signerProof.requestId())
        || !commitProof.accountId().equals(signerProof.accountId())
        || !commitProof.requestDigest().equals(signerProof.identity().requestDigest())
        || !commitProof.tokenSha256().equals(redisReceipt.tokenHash())) {
      throw new StorageUnavailableException();
    }
    Map<String, Object> proof = parseCommitProof(bytes);
    Object identityValue = proof.get("identity");
    Object registryValue = proof.get("registry");
    Object bundleValue = proof.get("bundle");
    Object authorityValue = proof.get("authority");
    Object signerValue = proof.get("signer");
    Object envelopeValue = proof.get("envelope");
    var identity = requireMap(identityValue);
    var registry = requireMap(registryValue);
    var bundle = requireMap(bundleValue);
    var authority = requireMap(authorityValue);
    var signer = requireMap(signerValue);
    var envelope = requireMap(envelopeValue);
    var correspondence = requireMap(signer.get("correspondence"));
    var pending = signerProof.identity();
    var snapshot = signerProof.authoritySnapshot();
    var bundleReference = signerProof.evidenceBundleReference();
    var sealed = signerProof.sealedCandidate();
    long expectedExpiryMillis;
    try {
      expectedExpiryMillis = Math.multiplyExact(pending.expiresAtEpochSecond(), 1_000L);
    } catch (ArithmeticException ex) {
      throw new StorageUnavailableException();
    }
    if (!identity
            .keySet()
            .equals(
                Set.of(
                    "operationId",
                    "requestId",
                    "accountId",
                    "requestDigest",
                    "callerWorkload",
                    "callerContextId",
                    "tokenJti",
                    "tokenGeneration",
                    "issuedAtEpochSecond",
                    "notBeforeEpochSecond",
                    "expiresAtEpochSecond",
                    "tokenSha256"))
        || !registry
            .keySet()
            .equals(
                Set.of(
                    "state",
                    "registryVersion",
                    "tokenKey",
                    "tokenHash",
                    "kid",
                    "signerGeneration",
                    "operationId",
                    "requestId",
                    "requestDigest",
                    "absoluteExpiryMillis",
                    "localAofCount",
                    "replicaAofCount",
                    "outcome",
                    "canonicalRecordSha256",
                    "evidenceBundleSha256",
                    "aclIdentity"))
        || !bundle
            .keySet()
            .equals(
                Set.of(
                    "bundleVersion",
                    "sourceVersion",
                    "sourceFence",
                    "linearization",
                    "canonicalSha256"))
        || !authority
            .keySet()
            .equals(
                Set.of(
                    "issuerGeneration",
                    "issuerSourceVersion",
                    "accountGeneration",
                    "accountSourceVersion",
                    "issuanceFence",
                    "issuanceFenceSourceVersion",
                    "authorityTuple",
                    "authoritySourceVersions",
                    "issuerProjectionGeneration",
                    "accountProjectionGeneration",
                    "issuerProjectionSha256",
                    "accountProjectionSha256"))
        || !signer
            .keySet()
            .equals(
                Set.of(
                    "promotionStatus",
                    "promotionOperationId",
                    "generationOperationId",
                    "targetKid",
                    "targetGeneration",
                    "correspondenceSha256",
                    "correspondence"))
        || !envelope
            .keySet()
            .equals(
                Set.of(
                    "operationId",
                    "requestId",
                    "tokenHash",
                    "kid",
                    "signerGeneration",
                    "authorityEvidenceBundleSha256",
                    "issuanceFence",
                    "responseRecoveryExpiryEpochMillis",
                    "envelopeSha256",
                    "envelopeBytesLength"))
        || !AccountGameplayDelegationIssuanceCommitService.COMMIT_PROOF_SCHEMA.equals(
            proof.get("schema"))
        || !pending.operationId().toString().equals(identity.get("operationId"))
        || !pending.requestId().toString().equals(identity.get("requestId"))
        || !pending.accountId().toString().equals(identity.get("accountId"))
        || !pending.requestDigest().equals(identity.get("requestDigest"))
        || !pending.callerWorkload().equals(identity.get("callerWorkload"))
        || !pending.callerContextId().toString().equals(identity.get("callerContextId"))
        || !pending.tokenJti().toString().equals(identity.get("tokenJti"))
        || !"1".equals(identity.get("tokenGeneration"))
        || !Long.toString(pending.issuedAtEpochSecond()).equals(identity.get("issuedAtEpochSecond"))
        || !Long.toString(pending.notBeforeEpochSecond())
            .equals(identity.get("notBeforeEpochSecond"))
        || !Long.toString(pending.expiresAtEpochSecond())
            .equals(identity.get("expiresAtEpochSecond"))
        || !signerProof.tokenSha256().equals(identity.get("tokenSha256"))
        || !signerProof.tokenSha256().equals(proof.get("tokenSha256"))
        || !redisReceipt.canonicalRecordSha256().equals(proof.get("registryRecordSha256"))
        || !"pending".equals(registry.get("state"))
        || !"1".equals(registry.get("registryVersion"))
        || !redisReceipt.tokenKey().equals(registry.get("tokenKey"))
        || !redisReceipt.tokenHash().equals(registry.get("tokenHash"))
        || !redisReceipt.kid().equals(registry.get("kid"))
        || !redisReceipt.signerGeneration().equals(registry.get("signerGeneration"))
        || !redisReceipt.operationId().equals(registry.get("operationId"))
        || !redisReceipt.requestId().equals(registry.get("requestId"))
        || !redisReceipt.requestDigest().equals(registry.get("requestDigest"))
        || !Long.toString(redisReceipt.absoluteExpiryMillis())
            .equals(registry.get("absoluteExpiryMillis"))
        || !Long.toString(redisReceipt.localAofCount()).equals(registry.get("localAofCount"))
        || !Long.toString(redisReceipt.replicaAofCount()).equals(registry.get("replicaAofCount"))
        || !redisReceipt.outcome().name().equals(registry.get("outcome"))
        || !redisReceipt.canonicalRecordSha256().equals(registry.get("canonicalRecordSha256"))
        || !bundleReference.canonicalSha256().equals(registry.get("evidenceBundleSha256"))
        || !AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY.equals(
            registry.get("aclIdentity"))
        || !bundleReference.toMap().equals(bundle)
        || !Long.toString(snapshot.issuerGeneration()).equals(authority.get("issuerGeneration"))
        || !Long.toString(snapshot.issuerSourceVersion())
            .equals(authority.get("issuerSourceVersion"))
        || !Long.toString(snapshot.accountGeneration()).equals(authority.get("accountGeneration"))
        || !Long.toString(snapshot.accountSourceVersion())
            .equals(authority.get("accountSourceVersion"))
        || !Long.toString(snapshot.issuanceFence()).equals(authority.get("issuanceFence"))
        || !Long.toString(snapshot.issuanceFenceSourceVersion())
            .equals(authority.get("issuanceFenceSourceVersion"))
        || !Arrays.equals(
            canonicalJson(authorityTuple(snapshot)), canonicalJson(authority.get("authorityTuple")))
        || !Arrays.equals(
            canonicalJson(snapshot.sourceVersions()),
            canonicalJson(authority.get("authoritySourceVersions")))
        || !Long.toString(projectionObservation.issuerGeneration())
            .equals(authority.get("issuerProjectionGeneration"))
        || !Long.toString(projectionObservation.accountGeneration())
            .equals(authority.get("accountProjectionGeneration"))
        || !projectionObservation.issuerDigest().equals(authority.get("issuerProjectionSha256"))
        || !projectionObservation.accountDigest().equals(authority.get("accountProjectionSha256"))
        || !"COMMITTED".equals(signer.get("promotionStatus"))
        || !signerProof
            .signerCorrespondence()
            .promotionOperationId()
            .toString()
            .equals(signer.get("promotionOperationId"))
        || !signerProof
            .signerCorrespondence()
            .generationOperationId()
            .toString()
            .equals(signer.get("generationOperationId"))
        || !signerProof.signerKid().equals(signer.get("targetKid"))
        || !signerProof.signerGeneration().equals(signer.get("targetGeneration"))
        || !SHA256.matcher(Objects.toString(signer.get("correspondenceSha256"), "")).matches()
        || !sha256(canonicalJson(correspondence)).equals(signer.get("correspondenceSha256"))
        || !pending.operationId().toString().equals(envelope.get("operationId"))
        || !pending.requestId().toString().equals(envelope.get("requestId"))
        || !signerProof.tokenSha256().equals(envelope.get("tokenHash"))
        || !signerProof.signerKid().equals(envelope.get("kid"))
        || !signerProof.signerGeneration().equals(envelope.get("signerGeneration"))
        || !bundleReference.canonicalSha256().equals(envelope.get("authorityEvidenceBundleSha256"))
        || !Long.toString(signerProof.issuanceFence()).equals(envelope.get("issuanceFence"))
        || !Long.toString(sealed.responseRecoveryExpiryEpochMillis())
            .equals(envelope.get("responseRecoveryExpiryEpochMillis"))
        || !sealed.envelopeSha256().equals(envelope.get("envelopeSha256"))
        || !Integer.toString(sealed.envelopeBytesLength())
            .equals(envelope.get("envelopeBytesLength"))
        || redisReceipt.absoluteExpiryMillis() < expectedExpiryMillis
        || redisReceipt.absoluteExpiryMillis() - expectedExpiryMillis > 300_000L
        || redisReceipt.localAofCount() < 1L
        || redisReceipt.replicaAofCount() < 1L
        || !redisReceipt.outcome().name().equals(registry.get("outcome"))
        || !redisReceipt.canonicalRecordSha256().equals(registry.get("canonicalRecordSha256"))) {
      throw new StorageUnavailableException();
    }
  }

  private static Map<?, ?> requireMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw new StorageUnavailableException();
    return map;
  }

  private CommittedIssuanceReadback decodeCommittedReadback(
      Record row, String expectedSha256, byte[] expectedBytes) {
    Object storedVersion = row == null ? null : row.get("commit_proof_version");
    if (row == null
        || !"COMMITTED".equals(row.get("status", String.class))
        || !(storedVersion instanceof Number version)
        || version.intValue() != 1
        || row.get("committed_at") == null) {
      throw new StorageUnavailableException();
    }
    String storedSha = row.get("commit_proof_sha256", String.class);
    byte[] storedBytes = row.get("commit_proof_canonical_bytes", byte[].class);
    if (storedSha == null
        || !SHA256.matcher(storedSha).matches()
        || storedBytes == null
        || storedBytes.length == 0
        || storedBytes.length > MAX_COMMIT_PROOF_BYTES
        || !storedSha.equals(sha256(storedBytes))
        || (expectedSha256 != null && !expectedSha256.equals(storedSha))
        || (expectedBytes != null && !Arrays.equals(expectedBytes, storedBytes))) {
      throw new StorageUnavailableException();
    }
    Map<String, Object> proof = parseCommitProof(storedBytes);
    AccountGameplayDelegationPendingIdentity identity = identity(row);
    Object proofIdentity = proof.get("identity");
    if (!(proofIdentity instanceof Map<?, ?> fields)
        || !fields
            .keySet()
            .equals(
                Set.of(
                    "operationId",
                    "requestId",
                    "accountId",
                    "requestDigest",
                    "callerWorkload",
                    "callerContextId",
                    "tokenJti",
                    "tokenGeneration",
                    "issuedAtEpochSecond",
                    "notBeforeEpochSecond",
                    "expiresAtEpochSecond",
                    "tokenSha256"))
        || !AccountGameplayDelegationIssuanceCommitService.COMMIT_PROOF_SCHEMA.equals(
            proof.get("schema"))
        || !identity.operationId().toString().equals(fields.get("operationId"))
        || !identity.requestId().toString().equals(fields.get("requestId"))
        || !identity.accountId().toString().equals(fields.get("accountId"))
        || !identity.requestDigest().equals(fields.get("requestDigest"))
        || !identity.tokenJti().toString().equals(fields.get("tokenJti"))
        || !"1".equals(fields.get("tokenGeneration"))
        || !Long.toString(identity.issuedAtEpochSecond()).equals(fields.get("issuedAtEpochSecond"))
        || !Long.toString(identity.notBeforeEpochSecond())
            .equals(fields.get("notBeforeEpochSecond"))
        || !Long.toString(identity.expiresAtEpochSecond())
            .equals(fields.get("expiresAtEpochSecond"))
        || !identity.callerWorkload().equals(fields.get("callerWorkload"))
        || !identity.callerContextId().toString().equals(fields.get("callerContextId"))
        || !Objects.equals(row.get("token_hash", String.class), fields.get("tokenSha256"))
        || !identity.tokenJti().toString().equals(row.get("token_jti", UUID.class).toString())
        || !Objects.equals(row.get("token_hash", String.class), proof.get("tokenSha256"))) {
      throw new StorageUnavailableException();
    }
    return new CommittedIssuanceReadback(
        identity.operationId(),
        identity.requestId(),
        identity.accountId(),
        identity.requestDigest(),
        row.get("token_hash", String.class),
        row.get("signer_kid", String.class),
        row.get("signer_generation", String.class),
        storedSha);
  }

  private static Map<String, Object> parseCommitProof(byte[] bytes) {
    try {
      Map<String, Object> parsed = JSON.readValue(bytes, new TypeReference<>() {});
      if (!parsed
              .keySet()
              .equals(
                  Set.of(
                      "schema",
                      "identity",
                      "tokenSha256",
                      "registryRecordSha256",
                      "registry",
                      "bundle",
                      "authority",
                      "signer",
                      "envelope"))
          || !Arrays.equals(bytes, canonicalProofJson(parsed))) {
        throw new StorageUnavailableException();
      }
      return parsed;
    } catch (RuntimeException ex) {
      if (ex instanceof StorageUnavailableException unavailable) throw unavailable;
      throw new StorageUnavailableException();
    }
  }

  private static byte[] canonicalProofJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (IOException ex) {
      throw new StorageUnavailableException();
    }
  }

  /** Versioned request digest over the immutable pre-sign operation and locked authority tuple. */
  public static String requestDigest(PendingIntent intent) {
    Objects.requireNonNull(intent, "Intent is required");
    Map<String, Object> value =
        Map.ofEntries(
            Map.entry("schema", "account-game-session-delegation-issuance/v2"),
            Map.entry("operationId", intent.operationId().toString()),
            Map.entry("requestId", intent.requestId().toString()),
            Map.entry("accountId", intent.authoritySnapshot().accountId().toString()),
            Map.entry("callerWorkload", intent.callerWorkload()),
            Map.entry("callerContextId", intent.callerContextId().toString()),
            Map.entry(
                "credentialRequestBinding",
                Map.of(
                    "digestSchemaVersion",
                    intent.credentialRequestBinding().digestSchemaVersion(),
                    "digestKeyId",
                    intent.credentialRequestBinding().digestKeyId(),
                    "credentialRequestDigest",
                    intent.credentialRequestBinding().credentialRequestDigest())),
            Map.entry("jti", intent.tokenJti().toString()),
            Map.entry("iat", intent.issuedAtEpochSecond()),
            Map.entry("nbf", intent.notBeforeEpochSecond()),
            Map.entry("exp", intent.expiresAtEpochSecond()),
            Map.entry("authorityTuple", authorityTuple(intent.authoritySnapshot())),
            Map.entry("membershipVersion", Map.of()),
            Map.entry("issuanceFence", Long.toString(intent.authoritySnapshot().issuanceFence())),
            Map.entry("authoritySourceVersions", intent.authoritySnapshot().sourceVersions()));
    return sha256(canonicalJson(value));
  }

  private void insertIntent(
      PendingIntent intent, String digest, IssuerAccountSourceSnapshot current) {
    AccountAuthoritySnapshot authority = authoritySnapshot(current);
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + TABLE
                + " (operation_id, request_id, account_uuid, caller_workload, caller_context_id, "
                + "request_digest_version, request_digest, credential_request_digest_version, "
                + "credential_digest_key_id, credential_request_digest, token_jti, token_generation, "
                + "issued_at_epoch_second, not_before_epoch_second, expires_at_epoch_second, "
                + "authority_issuer_generation, authority_issuer_source_version, "
                + "authority_account_generation, authority_account_source_version, issuance_fence, "
                + "issuance_fence_source_version, authority_tuple_canonical_bytes, "
                + "membership_version_canonical_bytes, authority_source_versions_canonical_bytes) "
                + "VALUES (?, ?, ?, ?, ?, 2, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (request_id) DO NOTHING",
            intent.operationId(),
            intent.requestId(),
            authority.accountId(),
            intent.callerWorkload(),
            intent.callerContextId(),
            digest,
            intent.credentialRequestBinding().digestSchemaVersion(),
            intent.credentialRequestBinding().digestKeyId(),
            intent.credentialRequestBinding().credentialRequestDigest(),
            intent.tokenJti(),
            intent.issuedAtEpochSecond(),
            intent.notBeforeEpochSecond(),
            intent.expiresAtEpochSecond(),
            authority.issuerGeneration(),
            authority.issuerSourceVersion(),
            authority.accountGeneration(),
            authority.accountSourceVersion(),
            authority.issuanceFence(),
            authority.issuanceFenceSourceVersion(),
            canonicalJson(authorityTuple(authority)),
            EMPTY_OBJECT,
            canonicalJson(authority.sourceVersions()));
    if (inserted < 0 || inserted > 1) throw new StorageUnavailableException();
  }

  private Record selectByRequestId(UUID requestId, boolean lock) {
    return dsl.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE request_id = ?" + (lock ? " FOR UPDATE" : ""),
        requestId);
  }

  private void lockRequestId(UUID requestId) {
    dsl.fetch(
        "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
        "account-gameplay-delegation-request/" + requestId);
  }

  private static void requireRequestId(UUID value) {
    Objects.requireNonNull(value, "Non-nil high-entropy UUID is required");
    if (value.equals(new UUID(0L, 0L)) || value.version() != 4 || value.variant() != 2) {
      throw new IllegalArgumentException("Non-nil high-entropy UUID is required");
    }
  }

  private static boolean isReplayableGameplayLoginStatus(String status) {
    return "PENDING".equals(status) || "COMMITTED".equals(status);
  }

  private IssuerAccountSourceSnapshot readAndCompareAuthority(PendingIntent intent) {
    IssuerAccountSourceSnapshot current =
        readCurrentAuthority(intent.authoritySnapshot().accountId());
    AccountAuthoritySnapshot expected = intent.authoritySnapshot();
    if (!authoritySnapshot(current).equals(expected)) throw new StaleAuthorityException();
    return current;
  }

  private IssuerAccountSourceSnapshot readCurrentAuthority(UUID accountId) {
    tokenIdentityFences.lockAccountForUpdate(accountId);
    IssuerAccountSourceSnapshot snapshot =
        sourceEvidence.readCurrentIssuerAccountSources(
            GameSessionAccountDelegationProfile.ISSUER, accountId);
    if (snapshot.issuer().scope().kind() != AccountAuthorityGenerationRepository.ScopeKind.ISSUER
        || !GameSessionAccountDelegationProfile.ISSUER.equals(snapshot.issuer().scope().issuerId())
        || snapshot.issuer().scope().accountId() != null
        || snapshot.issuer().scope().tenantId() != null
        || snapshot.account().scope().kind()
            != AccountAuthorityGenerationRepository.ScopeKind.ACCOUNT
        || !accountId.equals(snapshot.account().scope().accountId())
        || snapshot.account().scope().issuerId() != null
        || snapshot.account().scope().tenantId() != null
        || !accountId.equals(snapshot.issuanceFence().accountId())
        || !Objects.equals(snapshot.account().issuanceFence(), snapshot.issuanceFence())) {
      throw new IllegalStateException("Exact non-tenant Account authority snapshot is required");
    }
    return snapshot;
  }

  private static TokenIdentity tokenIdentity(Record row) {
    return new TokenIdentity(
        row.get("account_uuid", UUID.class), row.get("operation_id", UUID.class),
        row.get("request_id", UUID.class), row.get("token_hash", String.class),
        row.get("token_jti", UUID.class), row.get("not_before_epoch_second", Long.class),
        row.get("token_generation", Long.class), row.get("issuance_fence", Long.class));
  }

  private static AccountAuthoritySnapshot authoritySnapshot(IssuerAccountSourceSnapshot snapshot) {
    AccountSecurityCutoff cutoff =
        snapshot
            .account()
            .accountSecurityCutoff()
            .map(
                value ->
                    new AccountSecurityCutoff(
                        value.accountAuthorityGeneration(),
                        value.outboxStreamKey(),
                        value.outboxSequence()))
            .orElse(null);
    return new AccountAuthoritySnapshot(
        snapshot.account().scope().accountId(),
        snapshot.issuer().generation(),
        snapshot.issuer().sourceVersion(),
        snapshot.account().generation(),
        snapshot.account().sourceVersion(),
        snapshot.issuanceFence().value(),
        snapshot.issuanceFence().sourceVersion(),
        java.util.Optional.ofNullable(cutoff));
  }

  private static Map<String, Object> authorityTuple(AccountAuthoritySnapshot snapshot) {
    return GameSessionAccountDelegationProfile.authorityTuple(
        snapshot.issuerGeneration(),
        snapshot.accountGeneration(),
        snapshot.accountSecurityCutoff());
  }

  private static Map<String, Object> sourceVersionsMap(IssuerAccountSourceSnapshot snapshot) {
    return authoritySnapshot(snapshot).sourceVersions();
  }

  private static void requireExactIntent(
      Record row, PendingIntent intent, String digest, IssuerAccountSourceSnapshot current) {
    AccountGameplayDelegationPendingIdentity identity = identity(row);
    if (!identity.operationId().equals(intent.operationId())
        || !identity.requestId().equals(intent.requestId())
        || !identity.accountId().equals(intent.authoritySnapshot().accountId())
        || !identity.callerWorkload().equals(intent.callerWorkload())
        || !identity.callerContextId().equals(intent.callerContextId())
        || !identity.credentialRequestBinding().equals(intent.credentialRequestBinding())
        || !identity.tokenJti().equals(intent.tokenJti())
        || identity.issuedAtEpochSecond() != intent.issuedAtEpochSecond()
        || identity.notBeforeEpochSecond() != intent.notBeforeEpochSecond()
        || identity.expiresAtEpochSecond() != intent.expiresAtEpochSecond()
        || !identity.requestDigest().equals(digest)) {
      throw new IdempotencyConflictException();
    }
    requireStoredAuthority(row, current);
    if (!"PENDING".equals(row.get("status", String.class))) {
      throw new StorageUnavailableException();
    }
  }

  private static void requireStoredAuthority(Record row, IssuerAccountSourceSnapshot current) {
    AccountAuthoritySnapshot authority = authoritySnapshot(current);
    if (!Objects.equals(
            row.get("authority_issuer_generation", Long.class), authority.issuerGeneration())
        || !Objects.equals(
            row.get("authority_issuer_source_version", Long.class), authority.issuerSourceVersion())
        || !Objects.equals(
            row.get("authority_account_generation", Long.class), authority.accountGeneration())
        || !Objects.equals(
            row.get("authority_account_source_version", Long.class),
            authority.accountSourceVersion())
        || !Objects.equals(row.get("issuance_fence", Long.class), authority.issuanceFence())
        || !Objects.equals(
            row.get("issuance_fence_source_version", Long.class),
            authority.issuanceFenceSourceVersion())
        || !Arrays.equals(
            row.get("authority_tuple_canonical_bytes", byte[].class),
            canonicalJson(authorityTuple(authority)))
        || !Arrays.equals(row.get("membership_version_canonical_bytes", byte[].class), EMPTY_OBJECT)
        || !Arrays.equals(
            row.get("authority_source_versions_canonical_bytes", byte[].class),
            canonicalJson(authority.sourceVersions()))) {
      throw new StaleAuthorityException();
    }
  }

  private static void requireExactSigningBundle(
      AccountAuthEvidenceBundle bundle,
      AccountGameplayDelegationPendingIdentity identity,
      AccountAuthoritySnapshot authority,
      IssuerAccountSourceSnapshot current) {
    try {
      Map<String, Object> fields = bundle.fields();
      if (!AccountAuthEvidenceBundle.SCHEMA.equals(fields.get("schema"))
          || !GameSessionAccountDelegationProfile.ISSUER.equals(fields.get("issuer"))
          || !GameSessionAccountDelegationProfile.PROFILE.equals(fields.get("profile"))
          || !GameSessionAccountDelegationProfile.AUDIENCE.equals(fields.get("audience"))) {
        throw new PendingCandidateUnavailableException();
      }
      requireCanonicalSubtree(
          fields.get("scope"),
          Map.of(
              "kind",
              "account",
              "accountId",
              identity.accountId().toString(),
              "tenantIds",
              java.util.List.of()));
      requireCanonicalSubtree(
          fields.get("operation"),
          Map.of(
              "operationId", identity.operationId().toString(),
              "requestId", identity.requestId().toString(),
              "requestDigest", identity.requestDigest(),
              "callerWorkload", identity.callerWorkload(),
              "callerContextId", identity.callerContextId().toString(),
              "accountId", identity.accountId().toString()));
      requireCanonicalSubtree(
          fields.get("tokenIdentity"),
          Map.of(
              "jti", identity.tokenJti().toString(),
              "tokenGeneration", "1",
              "iat", identity.issuedAtEpochSecond(),
              "nbf", identity.notBeforeEpochSecond(),
              "exp", identity.expiresAtEpochSecond()));
      requireCanonicalSubtree(fields.get("authorityTuple"), authorityTuple(authority));
      requireCanonicalSubtree(fields.get("membershipVersion"), Map.of());
      Object issuanceFence = fields.get("issuanceFence");
      if (!(issuanceFence instanceof String fence)
          || !Long.toString(authority.issuanceFence()).equals(fence)) {
        throw new PendingCandidateUnavailableException();
      }
      requireCanonicalSubtree(fields.get("authoritySourceVersions"), sourceVersionStrings(current));
      requireCanonicalSubtree(fields.get("accountIdentitySource"), accountIdentitySource(current));
      requireCanonicalSubtree(fields.get("outboxCheckpoints"), outboxCheckpoints(current));
    } catch (RuntimeException ex) {
      if (ex instanceof PendingCandidateUnavailableException unavailable) throw unavailable;
      throw new PendingCandidateUnavailableException();
    }
  }

  private AccountAuthEvidenceBundle requireCurrentEvidenceBundle(
      UUID requestId,
      AccountGameplayDelegationPendingIdentity identity,
      AccountAuthoritySnapshot authority,
      IssuerAccountSourceSnapshot current) {
    if (evidenceBundles == null) throw new PendingCandidateUnavailableException();
    AccountAuthEvidenceBundle captured;
    AccountAuthEvidenceBundle stored;
    try {
      captured = evidenceBundles.captureAndPersist(requestId);
      stored = evidenceBundles.readStoredNonAuthorizingValue(identity.operationId());
    } catch (RuntimeException ex) {
      throw new PendingCandidateUnavailableException();
    }
    if (!MessageDigest.isEqual(captured.canonicalBytes(), stored.canonicalBytes())) {
      throw new PendingCandidateUnavailableException();
    }
    requireExactSigningBundle(stored, identity, authority, current);
    return stored;
  }

  private static EvidenceBundleReference evidenceBundleReference(AccountAuthEvidenceBundle bundle) {
    Object value = bundle.fields().get("bundleRef");
    if (!(value instanceof Map<?, ?> reference)) {
      throw new PendingCandidateUnavailableException();
    }
    Object bundleVersion = reference.get("bundleVersion");
    Object sourceVersion = reference.get("sourceVersion");
    Object sourceFence = reference.get("sourceFence");
    Object linearization = reference.get("linearization");
    if (!(bundleVersion instanceof String version)
        || !(sourceVersion instanceof String source)
        || !(sourceFence instanceof String fence)
        || !(linearization instanceof String xid)) {
      throw new PendingCandidateUnavailableException();
    }
    try {
      return new EvidenceBundleReference(version, source, fence, xid, bundle.canonicalSha256());
    } catch (RuntimeException ex) {
      throw new PendingCandidateUnavailableException();
    }
  }

  private static AccountAuthEvidenceBundleRepository defaultEvidenceBundles(DSLContext dsl) {
    AccountAuthorityGenerationRepository generations =
        new AccountAuthorityGenerationRepository(dsl);
    AccountAuthorityOutboxRepository outbox = new AccountAuthorityOutboxRepository(dsl);
    return new AccountAuthEvidenceBundleRepository(dsl, generations, outbox);
  }

  private static void requireCanonicalSubtree(Object actual, Object expected) {
    if (!Arrays.equals(canonicalJson(actual), canonicalJson(expected))) {
      throw new PendingCandidateUnavailableException();
    }
  }

  private static Map<String, Object> sourceVersionStrings(IssuerAccountSourceSnapshot current) {
    AccountAuthoritySnapshot authority = authoritySnapshot(current);
    return Map.of(
        "issuerSourceVersion", Long.toString(authority.issuerSourceVersion()),
        "accountSourceVersion", Long.toString(authority.accountSourceVersion()),
        "issuanceFenceSourceVersion", Long.toString(authority.issuanceFenceSourceVersion()));
  }

  private static Map<String, Object> accountIdentitySource(IssuerAccountSourceSnapshot current) {
    var account = current.account();
    if (account.accountSourceNumericId() == null || account.accountUuidProvenance() == null) {
      throw new PendingCandidateUnavailableException();
    }
    String sourceId = Long.toString(account.accountSourceNumericId());
    return Map.of(
        "sourceRowId", sourceId,
        "provenance", account.accountUuidProvenance(),
        "sourceNumericId", sourceId);
  }

  private static java.util.List<Map<String, Object>> outboxCheckpoints(
      IssuerAccountSourceSnapshot current) {
    java.util.List<Map<String, Object>> result = new java.util.ArrayList<>(2);
    for (var source : java.util.List.of(current.account(), current.issuer())) {
      var checkpoint = source.checkpoint();
      Map<String, Object> value = new java.util.LinkedHashMap<>();
      value.put("outboxStreamKey", checkpoint.outboxStreamKey());
      value.put("outboxSequence", Long.toString(checkpoint.sequence()));
      if (checkpoint.sequence() > 0L) {
        value.put("sourceEventId", checkpoint.sourceEventId().orElseThrow());
        value.put("sourceEventDigest", checkpoint.sourceEventDigest().orElseThrow());
      }
      result.add(Map.copyOf(value));
    }
    return java.util.List.copyOf(result);
  }

  private static AccountGameplayDelegationPendingIdentity identity(Record row) {
    try {
      if (row.get("request_digest_version", Short.class) == null
          || row.get("request_digest_version", Short.class).intValue() != REQUEST_DIGEST_VERSION) {
        throw new StorageUnavailableException();
      }
      return new AccountGameplayDelegationPendingIdentity(
          row.get("operation_id", UUID.class),
          row.get("request_id", UUID.class),
          row.get("account_uuid", UUID.class),
          row.get("caller_workload", String.class),
          row.get("caller_context_id", UUID.class),
          new AccountGameplayCredentialRequestBinding(
              row.get("credential_request_digest_version", Short.class).intValue(),
              row.get("credential_digest_key_id", String.class),
              row.get("credential_request_digest", String.class)),
          row.get("request_digest", String.class),
          row.get("token_jti", UUID.class),
          positive(row.get("issued_at_epoch_second", Long.class), "issued-at"),
          positive(row.get("not_before_epoch_second", Long.class), "not-before"),
          positive(row.get("expires_at_epoch_second", Long.class), "expiry"));
    } catch (RuntimeException ex) {
      throw new StorageUnavailableException();
    }
  }

  private static PendingOperation decodeOperation(Record row) {
    AccountGameplayDelegationPendingIdentity identity = identity(row);
    return new PendingOperation(
        identity.operationId(),
        identity.requestId(),
        identity.requestDigest(),
        identity.tokenJti(),
        row.get("token_hash", String.class) != null);
  }

  private static byte[] canonicalJson(Object value) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (canonical.length == 0 || canonical.length > MAX_INTENT_BYTES) {
        throw new IllegalArgumentException("Account issuance metadata exceeds its finite bound");
      }
      return canonical;
    } catch (IOException ex) {
      throw new StorageUnavailableException();
    }
  }

  private static String sha256(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is unavailable");
    }
  }

  private static long positive(Long value, String field) {
    if (value == null || value <= 0L)
      throw new IllegalArgumentException(field + " must be positive");
    return value;
  }

  private static Long requireJsonInteger(Object value) {
    if (!(value instanceof Number number)) throw new StorageUnavailableException();
    String encoded = number.toString();
    if (!POSITIVE_DECIMAL.matcher(encoded).matches()) throw new StorageUnavailableException();
    try {
      return Long.parseLong(encoded);
    } catch (NumberFormatException ex) {
      throw new StorageUnavailableException();
    }
  }

  private static void requirePositiveDecimal(String value, String field) {
    if (value == null || !POSITIVE_DECIMAL.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must be a canonical positive decimal");
    }
    try {
      Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new IllegalArgumentException(field + " exceeds its supported range");
    }
  }

  private static void requireWritableAccountTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account gameplay delegation issuance requires a writable Account transaction");
    }
  }

  public record PendingIntent(
      UUID operationId,
      UUID requestId,
      String callerWorkload,
      UUID callerContextId,
      UUID tokenJti,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond,
      AccountAuthoritySnapshot authoritySnapshot,
      AccountGameplayCredentialRequestBinding credentialRequestBinding) {
    public PendingIntent {
      requireUuid(operationId, "operationId");
      requireUuid(requestId, "requestId");
      requireUuid(callerContextId, "callerContextId");
      requireUuid(tokenJti, "token jti");
      if (callerWorkload == null
          || callerWorkload.length() > 256
          || !callerWorkload.matches(
              "^spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$")) {
        throw new IllegalArgumentException("Exact Game Session workload identity is required");
      }
      Objects.requireNonNull(authoritySnapshot, "Expected Account authority snapshot is required");
      Objects.requireNonNull(
          credentialRequestBinding, "Keyed credential request binding is required");
      if (issuedAtEpochSecond <= 0L
          || notBeforeEpochSecond <= 0L
          || expiresAtEpochSecond <= issuedAtEpochSecond
          || notBeforeEpochSecond > issuedAtEpochSecond
          || expiresAtEpochSecond - issuedAtEpochSecond
              > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) {
        throw new IllegalArgumentException("Delegation token time bounds are invalid");
      }
      try {
        if (Math.subtractExact(expiresAtEpochSecond, issuedAtEpochSecond) <= 0L
            || Math.subtractExact(expiresAtEpochSecond, issuedAtEpochSecond)
                > GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS) {
          throw new IllegalArgumentException("Delegation token time bounds are invalid");
        }
      } catch (ArithmeticException ex) {
        throw new IllegalArgumentException("Delegation token time bounds are invalid");
      }
    }

    private static void requireUuid(UUID value, String field) {
      Objects.requireNonNull(value, field + " is required");
      if (value.equals(new UUID(0L, 0L)) || value.version() != 4 || value.variant() != 2) {
        throw new IllegalArgumentException(field + " must be a non-zero high-entropy UUID");
      }
    }

    @Override
    public String toString() {
      return "PendingIntent[redacted]";
    }
  }

  public record PendingOperation(
      UUID operationId,
      UUID requestId,
      String requestDigest,
      UUID tokenJti,
      boolean candidateBound) {
    @Override
    public String toString() {
      return "PendingOperation[redacted]";
    }
  }

  public enum GameplayLoginReplayState {
    PENDING,
    COMMITTED
  }

  /** Exact current request replay readback; no token or envelope bytes are included. */
  public static final class GameplayLoginReplayReadback {
    private final AccountGameplayDelegationPendingIdentity identity;
    private final PendingOperation operation;
    private final GameplayLoginReplayState state;
    private final boolean created;

    private GameplayLoginReplayReadback(
        AccountGameplayDelegationPendingIdentity identity,
        PendingOperation operation,
        GameplayLoginReplayState state,
        CommittedIssuanceReadback committedProof,
        boolean created) {
      this.identity = Objects.requireNonNull(identity);
      this.operation = Objects.requireNonNull(operation);
      this.state = Objects.requireNonNull(state);
      this.created = created;
      if (!identity.operationId().equals(operation.operationId())
          || !identity.requestId().equals(operation.requestId())
          || !identity.tokenJti().equals(operation.tokenJti())) {
        throw new StorageUnavailableException();
      }
      if (state == GameplayLoginReplayState.COMMITTED) {
        if (created
            || !operation.candidateBound()
            || committedProof == null
            || !identity.operationId().equals(committedProof.operationId())
            || !identity.requestId().equals(committedProof.requestId())
            || !identity.accountId().equals(committedProof.accountId())
            || !identity.requestDigest().equals(committedProof.requestDigest())) {
          throw new StorageUnavailableException();
        }
      } else if (committedProof != null) {
        throw new StorageUnavailableException();
      }
    }

    public AccountGameplayDelegationPendingIdentity identity() {
      return identity;
    }

    public PendingOperation operation() {
      return operation;
    }

    public GameplayLoginReplayState state() {
      return state;
    }

    public boolean created() {
      return created;
    }

    @Override
    public String toString() {
      return "GameplayLoginReplayReadback[non-authorizing,redacted]";
    }
  }

  /** Exact durable COMMITTED metadata only; this is not token validation or issuance authority. */
  public record CommittedIssuanceReadback(
      UUID operationId,
      UUID requestId,
      UUID accountId,
      String requestDigest,
      String tokenHash,
      String signerKid,
      String signerGeneration,
      String proofSha256) {
    public CommittedIssuanceReadback {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(accountId);
      if (requestDigest == null
          || !SHA256.matcher(requestDigest).matches()
          || tokenHash == null
          || !SHA256.matcher(tokenHash).matches()
          || signerKid == null
          || !signerKid.matches("[A-Za-z0-9_-]{1,64}")
          || proofSha256 == null
          || !SHA256.matcher(proofSha256).matches()) {
        throw new IllegalArgumentException("Account committed issuance readback is malformed");
      }
      requirePositiveDecimal(signerGeneration, "signerGeneration");
    }

    @Override
    public String toString() {
      return "CommittedIssuanceReadback[non-authorizing]";
    }
  }

  public record BoundTokenCandidate(
      UUID requestId, String tokenHash, String kid, String signerGeneration) {
    public BoundTokenCandidate {
      Objects.requireNonNull(requestId, "Request ID is required");
      if (tokenHash == null || !SHA256.matcher(tokenHash).matches()) {
        throw new IllegalArgumentException("Token hash must be lowercase SHA-256 hex");
      }
      if (kid == null || !kid.matches("[A-Za-z0-9_-]{1,64}")) {
        throw new IllegalArgumentException("Signer kid is malformed");
      }
      requirePositiveDecimal(signerGeneration, "signerGeneration");
    }

    @Override
    public String toString() {
      return "BoundTokenCandidate[redacted]";
    }
  }

  public record PendingRegistryCandidate(
      AccountGameplayDelegationPendingIdentity identity,
      String tokenHash,
      String kid,
      String signerGeneration,
      long expiresAtEpochSecond,
      byte[] canonicalRecordBytes,
      AccountAuthoritySnapshot authoritySnapshot,
      EvidenceBundleReference evidenceBundleReference) {
    public PendingRegistryCandidate {
      canonicalRecordBytes = canonicalRecordBytes.clone();
      Objects.requireNonNull(identity);
      Objects.requireNonNull(authoritySnapshot);
      Objects.requireNonNull(evidenceBundleReference);
    }

    @Override
    public byte[] canonicalRecordBytes() {
      return canonicalRecordBytes.clone();
    }

    @Override
    public String toString() {
      return "PendingRegistryCandidate[redacted]";
    }
  }

  /**
   * Repository-issued exact pre-sign identity plus the persisted full Account evidence value.
   * Construction is private so callers cannot turn a supplied DTO into a signing authorization.
   */
  public static final class PendingSigningIdentity {
    private final AccountGameplayDelegationPendingIdentity identity;
    private final AccountAuthoritySnapshot authoritySnapshot;
    private final AccountAuthEvidenceBundle evidenceBundle;
    private final Optional<PendingRegistryCandidate> persistedCandidate;

    private PendingSigningIdentity(
        AccountGameplayDelegationPendingIdentity identity,
        AccountAuthoritySnapshot authoritySnapshot,
        AccountAuthEvidenceBundle evidenceBundle,
        Optional<PendingRegistryCandidate> persistedCandidate) {
      this.identity = Objects.requireNonNull(identity);
      this.authoritySnapshot = Objects.requireNonNull(authoritySnapshot);
      this.evidenceBundle = Objects.requireNonNull(evidenceBundle);
      this.persistedCandidate = Objects.requireNonNull(persistedCandidate);
      if (!identity.accountId().equals(authoritySnapshot.accountId())) {
        throw new PendingCandidateUnavailableException();
      }
      Map<String, Object> fields = evidenceBundle.fields();
      requireCanonicalSubtree(
          fields.get("operation"),
          Map.of(
              "operationId", identity.operationId().toString(),
              "requestId", identity.requestId().toString(),
              "requestDigest", identity.requestDigest(),
              "callerWorkload", identity.callerWorkload(),
              "callerContextId", identity.callerContextId().toString(),
              "accountId", identity.accountId().toString()));
      requireCanonicalSubtree(
          fields.get("tokenIdentity"),
          Map.of(
              "jti", identity.tokenJti().toString(),
              "tokenGeneration", "1",
              "iat", identity.issuedAtEpochSecond(),
              "nbf", identity.notBeforeEpochSecond(),
              "exp", identity.expiresAtEpochSecond()));
      requireCanonicalSubtree(fields.get("authorityTuple"), authorityTuple(authoritySnapshot));
      requireCanonicalSubtree(fields.get("membershipVersion"), Map.of());
      Object issuanceFence = fields.get("issuanceFence");
      if (!(issuanceFence instanceof String fence)
          || !Long.toString(authoritySnapshot.issuanceFence()).equals(fence)) {
        throw new PendingCandidateUnavailableException();
      }
      requireCanonicalSubtree(
          fields.get("authoritySourceVersions"),
          Map.of(
              "issuerSourceVersion", Long.toString(authoritySnapshot.issuerSourceVersion()),
              "accountSourceVersion", Long.toString(authoritySnapshot.accountSourceVersion()),
              "issuanceFenceSourceVersion",
                  Long.toString(authoritySnapshot.issuanceFenceSourceVersion())));
    }

    public AccountGameplayDelegationPendingIdentity identity() {
      return identity;
    }

    public AccountAuthoritySnapshot authoritySnapshot() {
      return authoritySnapshot;
    }

    public AccountAuthEvidenceBundle evidenceBundle() {
      return evidenceBundle;
    }

    /** Exact persisted PENDING candidate, when a prior signing attempt bound it successfully. */
    public Optional<PendingRegistryCandidate> persistedCandidate() {
      return persistedCandidate;
    }

    @Override
    public String toString() {
      return "PendingSigningIdentity[redacted]";
    }
  }

  /**
   * Repository-issued, non-authorizing snapshot of the exact current durable COMMITTED evidence.
   * The type contains no candidate/JWT bytes, commit-proof bytes, registry bytes, or ciphertext.
   */
  public static final class CommittedCandidateVerificationData {
    private final AccountGameplayDelegationPendingIdentity identity;
    private final AccountAuthoritySnapshot authoritySnapshot;
    private final EvidenceBundleReference evidenceBundleReference;
    private final String tokenSha256;
    private final String signerKid;
    private final String signerGeneration;
    private final String canonicalRegistryRecordSha256;
    private final String commitProofSha256;
    private final AuthenticatedSignerCorrespondence signerCorrespondence;
    private final String envelopeKeyId;
    private final String envelopeSha256;
    private final int envelopeBytesLength;
    private final long responseRecoveryExpiryEpochMillis;
    private final long registryAbsoluteExpiryMillis;
    private final long registrationLocalAofCount;
    private final long registrationReplicaAofCount;
    private final AccountGameplayDelegationRedisClient.PendingRegistrationOutcome
        registrationOutcome;

    private CommittedCandidateVerificationData(
        AccountGameplayDelegationPendingIdentity identity,
        AccountAuthoritySnapshot authoritySnapshot,
        EvidenceBundleReference evidenceBundleReference,
        String tokenSha256,
        String signerKid,
        String signerGeneration,
        String canonicalRegistryRecordSha256,
        String commitProofSha256,
        AuthenticatedSignerCorrespondence signerCorrespondence,
        String envelopeKeyId,
        String envelopeSha256,
        int envelopeBytesLength,
        long responseRecoveryExpiryEpochMillis,
        long registryAbsoluteExpiryMillis,
        long registrationLocalAofCount,
        long registrationReplicaAofCount,
        AccountGameplayDelegationRedisClient.PendingRegistrationOutcome registrationOutcome) {
      this.identity = Objects.requireNonNull(identity);
      this.authoritySnapshot = Objects.requireNonNull(authoritySnapshot);
      this.evidenceBundleReference = Objects.requireNonNull(evidenceBundleReference);
      this.tokenSha256 = requireSha256(tokenSha256);
      this.signerKid = Objects.requireNonNull(signerKid);
      this.signerGeneration = Objects.requireNonNull(signerGeneration);
      this.canonicalRegistryRecordSha256 = requireSha256(canonicalRegistryRecordSha256);
      this.commitProofSha256 = requireSha256(commitProofSha256);
      this.signerCorrespondence = Objects.requireNonNull(signerCorrespondence);
      this.envelopeKeyId = Objects.requireNonNull(envelopeKeyId);
      this.envelopeSha256 = requireSha256(envelopeSha256);
      this.envelopeBytesLength = envelopeBytesLength;
      this.responseRecoveryExpiryEpochMillis = responseRecoveryExpiryEpochMillis;
      this.registryAbsoluteExpiryMillis = registryAbsoluteExpiryMillis;
      this.registrationLocalAofCount = registrationLocalAofCount;
      this.registrationReplicaAofCount = registrationReplicaAofCount;
      this.registrationOutcome = Objects.requireNonNull(registrationOutcome);
      if (!identity.accountId().equals(authoritySnapshot.accountId())
          || signerKid.isBlank()
          || signerKid.length() > 64
          || !signerKid.matches("[A-Za-z0-9_-]+")
          || envelopeKeyId.isBlank()
          || envelopeKeyId.length() > 32
          || !envelopeKeyId.matches("[A-Za-z0-9_-]+")
          || envelopeBytesLength < 1
          || envelopeBytesLength > 65_610
          || responseRecoveryExpiryEpochMillis <= 0L
          || registryAbsoluteExpiryMillis <= 0L
          || registrationLocalAofCount < 1L
          || registrationReplicaAofCount < 1L) {
        throw new StorageUnavailableException();
      }
      requirePositiveDecimal(signerGeneration, "signer generation");
    }

    private static String requireSha256(String value) {
      if (value == null || !SHA256.matcher(value).matches()) {
        throw new StorageUnavailableException();
      }
      return value;
    }

    public AccountGameplayDelegationPendingIdentity identity() {
      return identity;
    }

    public AccountAuthoritySnapshot authoritySnapshot() {
      return authoritySnapshot;
    }

    public EvidenceBundleReference evidenceBundleReference() {
      return evidenceBundleReference;
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

    public String canonicalRegistryRecordSha256() {
      return canonicalRegistryRecordSha256;
    }

    public String commitProofSha256() {
      return commitProofSha256;
    }

    public AuthenticatedSignerCorrespondence signerCorrespondence() {
      return signerCorrespondence;
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

    public long responseRecoveryExpiryEpochMillis() {
      return responseRecoveryExpiryEpochMillis;
    }

    /** Exact absolute deadline persisted in the immutable COMMITTED proof. */
    public long registryAbsoluteExpiryMillis() {
      return registryAbsoluteExpiryMillis;
    }

    /** Historical WAITAOF counts validated from the immutable COMMITTED proof. */
    public long registrationLocalAofCount() {
      return registrationLocalAofCount;
    }

    public long registrationReplicaAofCount() {
      return registrationReplicaAofCount;
    }

    public AccountGameplayDelegationRedisClient.PendingRegistrationOutcome registrationOutcome() {
      return registrationOutcome;
    }

    @Override
    public String toString() {
      return "CommittedCandidateVerificationData[redacted]";
    }
  }

  private record EnvelopeHeader(String keyId) {}

  private record EnvelopeIdentity(String sha256, int length, long expiryMillis, String keyId) {}

  public static class StorageUnavailableException extends IllegalStateException {
    public StorageUnavailableException() {
      super("Account gameplay delegation issuance storage is unavailable or ambiguous");
    }
  }

  public static final class StaleAuthorityException extends IllegalStateException {
    public StaleAuthorityException() {
      super("Account gameplay delegation authority changed");
    }
  }

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException() {
      super("Account gameplay delegation request identity conflicts");
    }
  }

  public static final class PendingCandidateUnavailableException extends IllegalStateException {
    public PendingCandidateUnavailableException() {
      super("Account gameplay delegation pending candidate is unavailable");
    }
  }
}
