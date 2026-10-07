package net.firedevops.firemud.accountservice.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.accountservice.dto.AccountAuthEvidenceBundle;
import net.firedevops.firemud.accountservice.entity.AccountIdentityProvenance;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.CurrentSourceEvidence;
import net.firedevops.firemud.accountservice.repository.AccountAuthoritySourceEvidenceRepository.IssuerAccountSourceSnapshot;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationCommittedIssuanceOwner.ActiveCommittedIssuanceObservation;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.Binding;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores and recovers one immutable encrypted compact-token candidate for an Account operation.
 *
 * <p>Sealing is candidate custody only. Recovery compares caller values to immutable owner rows but
 * does not authenticate them; the actual Game Session boundary must provide its authenticated
 * workload identity and enforce that its original socket context remains open and current. Recovery
 * requires current Account owner evidence and the exact result of the separate registry activation
 * owner before it returns the JWT in a redacted typed result.
 */
@Repository
public class AccountGameplayDelegationResponseEnvelopeRepository {
  private static final String OPERATION_TABLE = "account_gameplay_delegation_issuance_operations";
  private static final String BUNDLE_TABLE = "account_gameplay_delegation_auth_evidence_bundles";
  private static final String RESPONSE_TABLE = "account_gameplay_delegation_response_envelopes";
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern V4_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern GAME_SESSION_WORKLOAD =
      Pattern.compile(
          "^spiffe://firemud/ns/[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?/sa/game-session-service$");
  private static final byte[] EMPTY_OBJECT = "{}".getBytes(StandardCharsets.US_ASCII);
  private static final JsonMapper JSON =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private final DSLContext dsl;
  private final AccountAuthoritySourceEvidenceRepository sourceEvidence;
  private final AccountAuthEvidenceBundleRepository evidenceBundles;
  private final AccountGameplayDelegationIssuanceRepository issuanceRepository;
  private final AccountResponseEnvelopeCryptography responseCryptography;
  private final Clock clock;

  @Autowired
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep fail-fast collaborator preconditions in the delegated constructor; this non-final Spring repository acquires no external resources and does not escape partially initialized.")
  public AccountGameplayDelegationResponseEnvelopeRepository(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountAuthEvidenceBundleRepository evidenceBundles,
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountResponseEnvelopeCryptography responseCryptography) {
    this(
        dsl,
        sourceEvidence,
        evidenceBundles,
        issuanceRepository,
        responseCryptography,
        Clock.systemUTC());
  }

  /** Compatibility constructor for the existing candidate-sealing component fixtures. */
  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep fail-fast collaborator preconditions in the delegated constructor; constructing the lightweight owner adds no external resource acquisition or partially initialized escape.")
  public AccountGameplayDelegationResponseEnvelopeRepository(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountAuthEvidenceBundleRepository evidenceBundles,
      AccountResponseEnvelopeCryptography responseCryptography) {
    this(
        dsl,
        sourceEvidence,
        evidenceBundles,
        new AccountGameplayDelegationIssuanceRepository(dsl, sourceEvidence, evidenceBundles),
        responseCryptography,
        Clock.systemUTC());
  }

  @SuppressFBWarnings(
      value = "CT_CONSTRUCTOR_THROW",
      justification =
          "Keep required collaborator and clock null checks fail-fast; this non-final Spring repository acquires no external resources and does not escape partially initialized.")
  AccountGameplayDelegationResponseEnvelopeRepository(
      DSLContext dsl,
      AccountAuthoritySourceEvidenceRepository sourceEvidence,
      AccountAuthEvidenceBundleRepository evidenceBundles,
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      AccountResponseEnvelopeCryptography responseCryptography,
      Clock clock) {
    this.dsl = Objects.requireNonNull(dsl, "DSLContext is required");
    this.sourceEvidence =
        Objects.requireNonNull(sourceEvidence, "Account authority source evidence is required");
    this.evidenceBundles =
        Objects.requireNonNull(evidenceBundles, "Account auth-evidence bundle owner is required");
    this.issuanceRepository =
        Objects.requireNonNull(issuanceRepository, "Account durable issuance owner is required");
    this.responseCryptography =
        Objects.requireNonNull(
            responseCryptography, "Account response-envelope crypto is required");
    this.clock = Objects.requireNonNull(clock, "Clock is required");
  }

  /**
   * Encrypts and durably read-backs an exact candidate once, or returns its existing sealed
   * metadata for a matching retry. Caller values are comparison inputs from the upstream
   * authenticated boundary; this repository does not authenticate them.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public SealedCandidateObservation sealPendingCandidate(
      UUID requestId, CallerIdentity caller, String exactCompactJwt) {
    requireWritableAccountTransaction();
    requireV4(requestId, "request ID");
    Objects.requireNonNull(caller, "Caller identity comparison is required");
    validateCompactJwt(exactCompactJwt);

    Record preliminary = selectOperation(requestId, false);
    if (preliminary == null) throw new OperationUnavailableException();
    UUID accountId = uuid(preliminary, "account_uuid");
    UUID operationId = uuid(preliminary, "operation_id");
    requireV4(operationId, "operation ID");
    PendingIdentity identity = identity(preliminary);
    if (!requestId.equals(identity.requestId())) throw new OperationUnavailableException();
    requireCallerMatch(identity, caller);
    CandidateIdentity candidate = candidate(preliminary, identity);
    String exactTokenHash = sha256(exactCompactJwt.getBytes(StandardCharsets.US_ASCII));
    if (!MessageDigest.isEqual(
        exactTokenHash.getBytes(StandardCharsets.US_ASCII),
        candidate.tokenHash().getBytes(StandardCharsets.US_ASCII))) {
      throw new IdempotencyConflictException();
    }
    long immutableExpiryMillis = expiryEpochMillis(identity.expiresAtEpochSecond());
    if (clock.millis() >= immutableExpiryMillis) throw new ResponseRecoveryExpiredException();

    final IssuerAccountSourceSnapshot current;
    try {
      // Locks Account and authority before the operation row and verifies exact retained source
      // checkpoints, including the applicable Account security cutoff.
      current =
          sourceEvidence.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, accountId);
    } catch (RuntimeException ex) {
      throw new AuthorityChangedException();
    }
    Record operation = selectOperation(requestId, true);
    if (operation == null
        || !operationId.equals(uuid(operation, "operation_id"))
        || !accountId.equals(uuid(operation, "account_uuid"))) {
      throw new OperationUnavailableException();
    }
    identity = identity(operation);
    requireCallerMatch(identity, caller);
    candidate = candidate(operation, identity);
    if (!exactTokenHash.equals(candidate.tokenHash())) throw new IdempotencyConflictException();
    requireCurrentAuthority(operation, current, accountId);

    AccountAuthEvidenceBundle evaluatedBundle;
    AccountAuthEvidenceBundle bundle;
    try {
      // This owner API performs the current locked source/history evaluation. A previously stored
      // value alone is not enough for this new seal attempt.
      evaluatedBundle = evidenceBundles.captureAndPersist(requestId);
      bundle = evidenceBundles.readStoredNonAuthorizingValue(operationId);
    } catch (RuntimeException ex) {
      // A missing or malformed owner bundle is not replaced by operation-row fields or caller data.
      throw new OwnerEvidenceUnavailableException();
    }
    if (!MessageDigest.isEqual(evaluatedBundle.canonicalBytes(), bundle.canonicalBytes())) {
      throw new OwnerEvidenceUnavailableException();
    }
    requireExactBundle(bundle, identity, operation, current, accountId);
    requireCandidateMatchesBoundRows(candidate.record(), identity, operation, current);

    Record existing = selectEnvelope(operationId);
    if (existing != null) {
      return exactReadback(existing, identity, candidate, bundle, immutableExpiryMillis);
    }

    Binding binding = binding(identity, operation, bundle);
    Instant recoveryExpiry;
    try {
      recoveryExpiry = Instant.ofEpochMilli(immutableExpiryMillis);
    } catch (RuntimeException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
    EncryptedResponseEnvelope sealed =
        responseCryptography.encrypt(
            binding, exactCompactJwt.getBytes(StandardCharsets.US_ASCII), recoveryExpiry);
    byte[] envelopeBytes = sealed.bytes();
    String envelopeHash = sha256(envelopeBytes);
    String bundleHash = bundle.canonicalSha256();
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + RESPONSE_TABLE
                + " (operation_id, request_id, token_hash, signer_kid, signer_generation, "
                + "authority_evidence_bundle_sha256, issuance_fence, "
                + "response_recovery_expiry_epoch_ms, envelope_sha256, envelope_bytes) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            identity.operationId(),
            identity.requestId(),
            candidate.tokenHash(),
            candidate.kid(),
            candidate.signerGeneration(),
            bundleHash,
            identity.issuanceFence(),
            immutableExpiryMillis,
            envelopeHash,
            envelopeBytes);
    if (inserted != 1) throw new StorageUnavailableException();
    Record readback = selectEnvelope(operationId);
    if (readback == null) throw new StorageUnavailableException();
    SealedCandidateObservation observation =
        exactReadback(readback, identity, candidate, bundle, immutableExpiryMillis);
    if (!MessageDigest.isEqual(envelopeBytes, bytes(readback, "envelope_bytes"))) {
      throw new StorageUnavailableException();
    }
    return observation;
  }

  /**
   * Checks only immutable request/caller identity and the stored response horizon before registry
   * activation. This deliberately does not authenticate the supplied identity or inspect/decrypt
   * the response envelope; the actual Game Session boundary must supply its authenticated mTLS
   * workload and ensure the original caller context is still open and current.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RecoveryPreflight preflightCommittedRecovery(
      UUID requestId, UUID expectedAccountId, CallerIdentity caller) {
    requireWritableAccountTransaction();
    requireRecoveryRequest(requestId, expectedAccountId, caller);
    Record operation = selectOperation(requestId, false);
    if (operation == null) throw new OperationUnavailableException();
    CommittedRecoveryIdentity identity = committedRecoveryIdentity(operation);
    requireRecoveryCaller(identity, requestId, expectedAccountId, caller);
    long expiryMillis = expiryEpochMillis(identity.expiresAtEpochSecond());
    long nowMillis = clock.millis();
    if (nowMillis <= 0L || nowMillis >= expiryMillis) {
      throw new ResponseRecoveryExpiredException();
    }
    return new RecoveryPreflight(
        identity.operationId(),
        identity.requestId(),
        identity.accountId(),
        identity.callerWorkload(),
        identity.callerContextId(),
        identity.requestDigest(),
        identity.tokenJti(),
        identity.expiresAtEpochSecond(),
        expiryMillis);
  }

  /**
   * Reads current durable COMMITTED evidence and decrypts its exact original compact JWT only after
   * the caller has completed the COMMITTED-to-ACTIVE registry postcondition. The SQL owner
   * transaction contains no network operation; plaintext is returned only in the redacted typed
   * credential result and the local byte buffer is wiped before exit.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public RecoveredCredential recoverCommittedResponse(
      UUID requestId,
      UUID expectedAccountId,
      CallerIdentity caller,
      ActiveCommittedIssuanceObservation active) {
    requireWritableAccountTransaction();
    requireRecoveryRequest(requestId, expectedAccountId, caller);
    Objects.requireNonNull(active, "Completed Account activation evidence is required");

    Record preliminary = selectOperation(requestId, false);
    if (preliminary == null) throw new OperationUnavailableException();
    CommittedRecoveryIdentity preliminaryIdentity = committedRecoveryIdentity(preliminary);
    requireRecoveryCaller(preliminaryIdentity, requestId, expectedAccountId, caller);
    long immutableExpiryMillis = expiryEpochMillis(preliminaryIdentity.expiresAtEpochSecond());
    if (clock.millis() <= 0L || clock.millis() >= immutableExpiryMillis) {
      throw new ResponseRecoveryExpiredException();
    }

    final CommittedCandidateVerificationData committed;
    try {
      committed = issuanceRepository.readCurrentCommittedCandidate(requestId);
    } catch (RuntimeException failure) {
      if (clock.millis() >= immutableExpiryMillis) {
        throw new ResponseRecoveryExpiredException();
      }
      throw new OwnerEvidenceUnavailableException();
    }

    Record operation = selectOperation(requestId, true);
    if (operation == null) throw new OperationUnavailableException();
    CommittedRecoveryIdentity identity = committedRecoveryIdentity(operation);
    requireRecoveryCaller(identity, requestId, expectedAccountId, caller);
    if (!preliminaryIdentity.equals(identity)
        || !committedMatches(committed, identity)
        || !activeMatches(active, committed)) {
      throw new OwnerEvidenceUnavailableException();
    }
    if (clock.millis() <= 0L || clock.millis() >= immutableExpiryMillis) {
      throw new ResponseRecoveryExpiredException();
    }

    final IssuerAccountSourceSnapshot current;
    try {
      current =
          sourceEvidence.readCurrentIssuerAccountSources(
              GameSessionAccountDelegationProfile.ISSUER, identity.accountId());
    } catch (RuntimeException failure) {
      throw new AuthorityChangedException();
    }
    requireCurrentAuthority(operation, current, identity.accountId());
    CandidateIdentity candidate = candidate(operation, identity.pendingIdentity());
    requireCandidateMatchesBoundRows(
        candidate.record(), identity.pendingIdentity(), operation, current);

    final AccountAuthEvidenceBundle bundle;
    try {
      bundle = evidenceBundles.readStoredNonAuthorizingValue(identity.operationId());
    } catch (RuntimeException failure) {
      throw new OwnerEvidenceUnavailableException();
    }
    if (!committed.evidenceBundleReference().canonicalSha256().equals(bundle.canonicalSha256())
        || !candidate.record().evidenceBundleReference().equals(committed.evidenceBundleReference())
        || !committed.tokenSha256().equals(candidate.tokenHash())
        || !committed.signerKid().equals(candidate.kid())
        || !committed.signerGeneration().equals(candidate.signerGeneration())
        || !committed
            .canonicalRegistryRecordSha256()
            .equals(sha256(bytes(operation, "pending_registry_candidate_bytes")))
        || !committed.authoritySnapshot().equals(authoritySnapshot(current))) {
      throw new OwnerEvidenceUnavailableException();
    }
    requireExactBundle(
        bundle, identity.pendingIdentity(), operation, current, identity.accountId());

    Record envelope = selectEnvelopeForRecovery(identity.operationId());
    if (envelope == null) throw new StorageUnavailableException();
    byte[] envelopeBytes = bytes(envelope, "envelope_bytes");
    String envelopeHash = text(envelope, "envelope_sha256");
    if (!exactCommittedEnvelope(
        envelope,
        envelopeBytes,
        envelopeHash,
        identity,
        candidate,
        committed,
        bundle,
        immutableExpiryMillis)) {
      throw new StorageUnavailableException();
    }

    byte[] plaintext = null;
    try {
      plaintext =
          responseCryptography.decrypt(
              new EncryptedResponseEnvelope(envelopeBytes),
              binding(identity.pendingIdentity(), operation, bundle),
              Instant.ofEpochMilli(immutableExpiryMillis));
      validateRecoveredCompactJwt(plaintext, committed.tokenSha256());
      return new RecoveredCredential(
          plaintext,
          identity.accountId(),
          identity.tokenJti(),
          committed.tokenSha256(),
          GameSessionAccountDelegationProfile.PROFILE,
          identity.expiresAtEpochSecond(),
          bundle);
    } catch (AccountResponseEnvelopeCryptography.ResponseRecoveryExpiredException expired) {
      throw new ResponseRecoveryExpiredException();
    } catch (RuntimeException failure) {
      if (failure instanceof ResponseRecoveryExpiredException expired) throw expired;
      throw new StorageUnavailableException();
    } finally {
      if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
      Arrays.fill(envelopeBytes, (byte) 0);
    }
  }

  private Record selectEnvelopeForRecovery(UUID operationId) {
    return dsl.fetchOne(
        "SELECT operation_id, request_id, token_hash, signer_kid, signer_generation, "
            + "authority_evidence_bundle_sha256, issuance_fence, "
            + "response_recovery_expiry_epoch_ms, envelope_sha256, envelope_bytes "
            + "FROM "
            + RESPONSE_TABLE
            + " WHERE operation_id = ? FOR SHARE",
        operationId);
  }

  private boolean exactCommittedEnvelope(
      Record envelope,
      byte[] envelopeBytes,
      String envelopeHash,
      CommittedRecoveryIdentity identity,
      CandidateIdentity candidate,
      CommittedCandidateVerificationData committed,
      AccountAuthEvidenceBundle bundle,
      long immutableExpiryMillis) {
    return identity.operationId().equals(uuid(envelope, "operation_id"))
        && identity.requestId().equals(uuid(envelope, "request_id"))
        && candidate.tokenHash().equals(text(envelope, "token_hash"))
        && candidate.kid().equals(text(envelope, "signer_kid"))
        && candidate.signerGeneration().equals(text(envelope, "signer_generation"))
        && bundle.canonicalSha256().equals(text(envelope, "authority_evidence_bundle_sha256"))
        && identity.issuanceFence() == positive(envelope, "issuance_fence")
        && immutableExpiryMillis == positive(envelope, "response_recovery_expiry_epoch_ms")
        && immutableExpiryMillis == committed.responseRecoveryExpiryEpochMillis()
        && committed.envelopeSha256().equals(envelopeHash)
        && committed.envelopeBytesLength() == envelopeBytes.length
        && envelopeBytes.length > 0
        && envelopeBytes.length <= 65_610
        && SHA256.matcher(envelopeHash).matches()
        && MessageDigest.isEqual(
            sha256(envelopeBytes).getBytes(StandardCharsets.US_ASCII),
            envelopeHash.getBytes(StandardCharsets.US_ASCII));
  }

  private static boolean committedMatches(
      CommittedCandidateVerificationData committed, CommittedRecoveryIdentity identity) {
    var committedIdentity = committed.identity();
    return committedIdentity.operationId().equals(identity.operationId())
        && committedIdentity.requestId().equals(identity.requestId())
        && committedIdentity.accountId().equals(identity.accountId())
        && committedIdentity.callerWorkload().equals(identity.callerWorkload())
        && committedIdentity.callerContextId().equals(identity.callerContextId())
        && committedIdentity.requestDigest().equals(identity.requestDigest())
        && committedIdentity.tokenJti().equals(identity.tokenJti())
        && committedIdentity.issuedAtEpochSecond() == identity.issuedAtEpochSecond()
        && committedIdentity.notBeforeEpochSecond() == identity.notBeforeEpochSecond()
        && committedIdentity.expiresAtEpochSecond() == identity.expiresAtEpochSecond()
        && committed.tokenSha256().equals(identity.tokenHash())
        && committed.authoritySnapshot().accountId().equals(identity.accountId())
        && committed.authoritySnapshot().issuanceFence() == identity.issuanceFence()
        && committed.responseRecoveryExpiryEpochMillis()
            == expiryEpochMillis(identity.expiresAtEpochSecond());
  }

  private static boolean activeMatches(
      ActiveCommittedIssuanceObservation active, CommittedCandidateVerificationData committed) {
    return active.operationId().equals(committed.identity().operationId())
        && active.requestId().equals(committed.identity().requestId())
        && active.accountId().equals(committed.identity().accountId())
        && active.tokenSha256().equals(committed.tokenSha256())
        && active.commitProofSha256().equals(committed.commitProofSha256())
        && active.registryVersion() == 2L
        && active.absoluteExpiryMillis() == committed.registryAbsoluteExpiryMillis()
        && active.observedAtEpochMillis() > 0L
        && active.observedAtEpochMillis() < committed.responseRecoveryExpiryEpochMillis()
        && active.activeRecordSha256() != null
        && SHA256.matcher(active.activeRecordSha256()).matches();
  }

  private static void requireV4ForRecovery(UUID value, String field) {
    requireV4(value, field);
    if (value.variant() != 2) throw new OperationUnavailableException();
  }

  private CommittedRecoveryIdentity committedRecoveryIdentity(Record operation) {
    if (!"COMMITTED".equals(text(operation, "status"))
        || number(operation, "request_digest_version") != 2L
        || number(operation, "token_generation") != 1L) {
      throw new OperationUnavailableException();
    }
    String callerWorkload = text(operation, "caller_workload");
    if (!GAME_SESSION_WORKLOAD.matcher(callerWorkload).matches()) {
      throw new OperationUnavailableException();
    }
    String requestDigest = text(operation, "request_digest");
    if (!SHA256.matcher(requestDigest).matches()) throw new OperationUnavailableException();
    UUID operationId = uuid(operation, "operation_id");
    UUID requestId = uuid(operation, "request_id");
    UUID accountId = uuid(operation, "account_uuid");
    UUID callerContextId = uuid(operation, "caller_context_id");
    UUID tokenJti = uuid(operation, "token_jti");
    requireV4ForRecovery(operationId, "operation ID");
    requireV4ForRecovery(requestId, "request ID");
    requireV4ForRecovery(accountId, "Account ID");
    requireV4ForRecovery(callerContextId, "caller context ID");
    requireV4ForRecovery(tokenJti, "token ID");
    String tokenHash = text(operation, "token_hash");
    if (!SHA256.matcher(tokenHash).matches()) throw new CandidateUnavailableException();
    return new CommittedRecoveryIdentity(
        operationId,
        requestId,
        accountId,
        callerWorkload,
        callerContextId,
        credentialRequestBinding(operation),
        requestDigest,
        tokenJti,
        positive(operation, "issued_at_epoch_second"),
        positive(operation, "not_before_epoch_second"),
        positive(operation, "expires_at_epoch_second"),
        tokenHash,
        positive(operation, "issuance_fence"));
  }

  private static void requireRecoveryCaller(
      CommittedRecoveryIdentity identity,
      UUID requestId,
      UUID expectedAccountId,
      CallerIdentity caller) {
    if (!identity.requestId().equals(requestId)) throw new OperationUnavailableException();
    if (!identity.accountId().equals(expectedAccountId)
        || !identity.callerWorkload().equals(caller.workload())
        || !identity.callerContextId().equals(caller.contextId())) {
      throw new IdempotencyConflictException();
    }
  }

  private static void requireRecoveryRequest(
      UUID requestId, UUID expectedAccountId, CallerIdentity caller) {
    requireV4ForRecovery(requestId, "request ID");
    requireV4ForRecovery(expectedAccountId, "Account ID");
    Objects.requireNonNull(caller, "Authenticated Game Session identity comparison is required");
  }

  private static void validateRecoveredCompactJwt(byte[] bytes, String expectedSha256) {
    if (bytes == null
        || bytes.length == 0
        || bytes.length > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES) {
      throw new StorageUnavailableException();
    }
    for (byte value : bytes) {
      int octet = Byte.toUnsignedInt(value);
      if (octet < 0x21 || octet > 0x7e) throw new StorageUnavailableException();
    }
    if (!MessageDigest.isEqual(
        sha256(bytes).getBytes(StandardCharsets.US_ASCII),
        expectedSha256.getBytes(StandardCharsets.US_ASCII))) {
      throw new StorageUnavailableException();
    }
  }

  private Record selectOperation(UUID requestId, boolean lock) {
    return dsl.fetchOne(
        "SELECT * FROM " + OPERATION_TABLE + " WHERE request_id = ?" + (lock ? " FOR UPDATE" : ""),
        requestId);
  }

  private Record selectEnvelope(UUID operationId) {
    return dsl.fetchOne(
        "SELECT operation_id, request_id, token_hash, signer_kid, signer_generation, "
            + "authority_evidence_bundle_sha256, issuance_fence, "
            + "response_recovery_expiry_epoch_ms, envelope_sha256, envelope_bytes "
            + "FROM "
            + RESPONSE_TABLE
            + " WHERE operation_id = ?",
        operationId);
  }

  private SealedCandidateObservation exactReadback(
      Record row,
      PendingIdentity identity,
      CandidateIdentity candidate,
      AccountAuthEvidenceBundle bundle,
      long expiryMillis) {
    byte[] envelope = bytes(row, "envelope_bytes");
    String storedEnvelopeHash = text(row, "envelope_sha256");
    if (!identity.operationId().equals(uuid(row, "operation_id"))
        || !identity.requestId().equals(uuid(row, "request_id"))
        || !candidate.tokenHash().equals(text(row, "token_hash"))
        || !candidate.kid().equals(text(row, "signer_kid"))
        || !candidate.signerGeneration().equals(text(row, "signer_generation"))
        || !bundle.canonicalSha256().equals(text(row, "authority_evidence_bundle_sha256"))
        || identity.issuanceFence() != positive(row, "issuance_fence")
        || expiryMillis != positive(row, "response_recovery_expiry_epoch_ms")
        || envelope.length == 0
        || envelope.length > 65_610
        || !SHA256.matcher(storedEnvelopeHash).matches()
        || !MessageDigest.isEqual(
            sha256(envelope).getBytes(StandardCharsets.US_ASCII),
            storedEnvelopeHash.getBytes(StandardCharsets.US_ASCII))) {
      throw new StorageUnavailableException();
    }
    return new SealedCandidateObservation(
        identity.operationId(),
        identity.requestId(),
        bundle.canonicalSha256(),
        identity.issuanceFence(),
        expiryMillis,
        storedEnvelopeHash,
        envelope.length);
  }

  private static Binding binding(
      PendingIdentity identity, Record operation, AccountAuthEvidenceBundle bundle) {
    return new Binding(
        GameSessionAccountDelegationProfile.PROFILE,
        identity.requestId().toString(),
        HexFormat.of().parseHex(identity.requestDigest()),
        identity.callerWorkload(),
        identity.operationId().toString(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        identity.issuanceFence(),
        bytes(operation, "authority_tuple_canonical_bytes"),
        bytes(operation, "membership_version_canonical_bytes"),
        bundle.canonicalBytes());
  }

  private static PendingIdentity identity(Record operation) {
    if (!"PENDING".equals(text(operation, "status"))
        || number(operation, "request_digest_version") != 2L
        || number(operation, "token_generation") != 1L) {
      throw new OperationUnavailableException();
    }
    String caller = text(operation, "caller_workload");
    if (!GAME_SESSION_WORKLOAD.matcher(caller).matches()) {
      throw new OperationUnavailableException();
    }
    String digest = text(operation, "request_digest");
    if (!SHA256.matcher(digest).matches()) throw new OperationUnavailableException();
    return new PendingIdentity(
        uuid(operation, "operation_id"),
        uuid(operation, "request_id"),
        uuid(operation, "account_uuid"),
        caller,
        uuid(operation, "caller_context_id"),
        credentialRequestBinding(operation),
        digest,
        uuid(operation, "token_jti"),
        positive(operation, "issued_at_epoch_second"),
        positive(operation, "not_before_epoch_second"),
        positive(operation, "expires_at_epoch_second"),
        positive(operation, "issuance_fence"));
  }

  private static AccountGameplayCredentialRequestBinding credentialRequestBinding(
      Record operation) {
    try {
      return new AccountGameplayCredentialRequestBinding(
          Math.toIntExact(number(operation, "credential_request_digest_version")),
          text(operation, "credential_digest_key_id"),
          text(operation, "credential_request_digest"));
    } catch (RuntimeException ex) {
      throw new OperationUnavailableException();
    }
  }

  private static CandidateIdentity candidate(Record operation, PendingIdentity identity) {
    String tokenHash = text(operation, "token_hash");
    String kid = text(operation, "signer_kid");
    String signerGeneration = text(operation, "signer_generation");
    byte[] candidateBytes = bytes(operation, "pending_registry_candidate_bytes");
    if (!SHA256.matcher(tokenHash).matches()
        || !kid.matches("[A-Za-z0-9_-]{1,64}")
        || !signerGeneration.matches("[1-9][0-9]{0,18}")
        || candidateBytes.length == 0
        || candidateBytes.length > GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES) {
      throw new CandidateUnavailableException();
    }
    GameSessionAccountDelegationRegistryRecord record;
    try {
      record =
          GameSessionAccountDelegationRegistryRecord.decode(
              candidateBytes, GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
    } catch (RuntimeException ex) {
      throw new CandidateUnavailableException();
    }
    return new CandidateIdentity(tokenHash, kid, signerGeneration, record);
  }

  private static void requireCallerMatch(PendingIdentity identity, CallerIdentity caller) {
    if (!identity.callerWorkload().equals(caller.workload())
        || !identity.callerContextId().equals(caller.contextId())) {
      throw new IdempotencyConflictException();
    }
  }

  private static void requireCurrentAuthority(
      Record operation, IssuerAccountSourceSnapshot snapshot, UUID accountId) {
    CurrentSourceEvidence issuer = snapshot.issuer();
    CurrentSourceEvidence account = snapshot.account();
    if (issuer.scope().kind() != AccountAuthorityGenerationRepository.ScopeKind.ISSUER
        || !GameSessionAccountDelegationProfile.ISSUER.equals(issuer.scope().issuerId())
        || issuer.scope().accountId() != null
        || issuer.scope().tenantId() != null
        || account.scope().kind() != AccountAuthorityGenerationRepository.ScopeKind.ACCOUNT
        || !accountId.equals(account.scope().accountId())
        || account.scope().issuerId() != null
        || account.scope().tenantId() != null
        || !Objects.equals(account.issuanceFence(), snapshot.issuanceFence())
        || !accountId.equals(snapshot.issuanceFence().accountId())
        || !Objects.equals(issuer.generation(), longValue(operation, "authority_issuer_generation"))
        || !Objects.equals(
            issuer.sourceVersion(), longValue(operation, "authority_issuer_source_version"))
        || !Objects.equals(
            account.generation(), longValue(operation, "authority_account_generation"))
        || !Objects.equals(
            account.sourceVersion(), longValue(operation, "authority_account_source_version"))
        || !Objects.equals(snapshot.issuanceFence().value(), longValue(operation, "issuance_fence"))
        || !Objects.equals(
            snapshot.issuanceFence().sourceVersion(),
            longValue(operation, "issuance_fence_source_version"))) {
      throw new AuthorityChangedException();
    }
    Map<String, Object> currentTuple =
        GameSessionAccountDelegationProfile.authorityTuple(
            issuer.generation(),
            account.generation(),
            account
                .accountSecurityCutoff()
                .map(
                    cutoff ->
                        new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                            cutoff.accountAuthorityGeneration(),
                            cutoff.outboxStreamKey(),
                            cutoff.outboxSequence())));
    if (!Arrays.equals(
            bytes(operation, "authority_tuple_canonical_bytes"), canonicalJson(currentTuple))
        || !Arrays.equals(bytes(operation, "membership_version_canonical_bytes"), EMPTY_OBJECT)) {
      throw new AuthorityChangedException();
    }
  }

  private void requireExactBundle(
      AccountAuthEvidenceBundle bundle,
      PendingIdentity identity,
      Record operation,
      IssuerAccountSourceSnapshot current,
      UUID accountId) {
    Map<String, Object> root = bundle.fields();
    Map<String, Object> scope = object(root, "scope");
    Map<String, Object> operationFields = object(root, "operation");
    Map<String, Object> token = object(root, "tokenIdentity");
    Map<String, Object> authorityTuple = object(root, "authorityTuple");
    Map<String, Object> sourceVersions = object(root, "authoritySourceVersions");
    if (!"account".equals(scope.get("kind"))
        || !accountId.toString().equals(scope.get("accountId"))
        || !List.of().equals(scope.get("tenantIds"))
        || !identity.operationId().toString().equals(operationFields.get("operationId"))
        || !identity.requestId().toString().equals(operationFields.get("requestId"))
        || !identity.requestDigest().equals(operationFields.get("requestDigest"))
        || !identity.callerWorkload().equals(operationFields.get("callerWorkload"))
        || !identity.callerContextId().toString().equals(operationFields.get("callerContextId"))
        || !accountId.toString().equals(operationFields.get("accountId"))
        || !identity.tokenJti().toString().equals(token.get("jti"))
        || !Long.valueOf(1L).equals(numberFromString(token.get("tokenGeneration")))
        || numberValue(token.get("iat")) != identity.issuedAtEpochSecond()
        || numberValue(token.get("nbf")) != identity.notBeforeEpochSecond()
        || numberValue(token.get("exp")) != identity.expiresAtEpochSecond()
        || !Long.toString(identity.issuanceFence()).equals(root.get("issuanceFence"))
        || !Map.of().equals(root.get("membershipVersion"))
        || !Long.valueOf(current.issuer().generation())
            .equals(numberFromString(authorityTuple.get("issuerAuthGeneration")))
        || !Long.valueOf(current.account().generation())
            .equals(numberFromString(authorityTuple.get("accountAuthorityGeneration")))
        || !Arrays.equals(
            canonicalJson(authorityTuple), bytes(operation, "authority_tuple_canonical_bytes"))
        || !Arrays.equals(
            canonicalJson(root.get("membershipVersion")),
            bytes(operation, "membership_version_canonical_bytes"))
        || !Long.toString(current.issuer().sourceVersion())
            .equals(sourceVersions.get("issuerSourceVersion"))
        || !Long.toString(current.account().sourceVersion())
            .equals(sourceVersions.get("accountSourceVersion"))
        || !Long.toString(current.issuanceFence().sourceVersion())
            .equals(sourceVersions.get("issuanceFenceSourceVersion"))
        || positive(operation, "authority_issuer_source_version")
            != current.issuer().sourceVersion()
        || positive(operation, "authority_account_source_version")
            != current.account().sourceVersion()
        || positive(operation, "issuance_fence_source_version")
            != current.issuanceFence().sourceVersion()) {
      throw new OwnerEvidenceUnavailableException();
    }
    Record accountRow =
        dsl.fetchOne(
            "SELECT id, account_uuid, account_uuid_provenance, account_uuid_source_numeric_id "
                + "FROM accounts WHERE account_uuid = ? FOR SHARE",
            accountId);
    if (accountRow == null
        || !accountId.equals(uuid(accountRow, "account_uuid"))
        || !Long.toString(positive(accountRow, "id"))
            .equals(object(root, "accountIdentitySource").get("sourceRowId"))
        || !Long.toString(positive(accountRow, "account_uuid_source_numeric_id"))
            .equals(object(root, "accountIdentitySource").get("sourceNumericId"))) {
      throw new OwnerEvidenceUnavailableException();
    }
    AccountIdentityProvenance provenance;
    try {
      provenance =
          AccountIdentityProvenance.fromStorageValue(text(accountRow, "account_uuid_provenance"));
    } catch (RuntimeException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
    if (!AccountIdentityProvenance.isAccepted(provenance)
        || !provenance.name().equals(object(root, "accountIdentitySource").get("provenance"))) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static void requireCandidateMatchesBoundRows(
      GameSessionAccountDelegationRegistryRecord record,
      PendingIdentity identity,
      Record operation,
      IssuerAccountSourceSnapshot current) {
    AccountAuthoritySnapshot currentAuthority = authoritySnapshot(current);
    Map<String, Object> fields = record.fields();
    if (!record.tokenHash().equals(text(operation, "token_hash"))
        || !record.kid().equals(text(operation, "signer_kid"))
        || !record.signerGeneration().equals(text(operation, "signer_generation"))
        || !"pending".equals(record.state())
        || !identity.operationId().toString().equals(record.operationId())
        || !identity.requestId().toString().equals(record.requestId())
        || !identity.requestDigest().equals(record.requestDigest())
        || !identity.accountId().toString().equals(record.accountId())
        || !identity.tokenJti().toString().equals(record.jti())
        || identity.issuedAtEpochSecond() != record.issuedAtEpochSecond()
        || identity.notBeforeEpochSecond() != record.notBeforeEpochSecond()
        || identity.expiresAtEpochSecond() != record.expiresAtEpochSecond()
        || identity.issuanceFence() != record.issuanceFence()
        || !Arrays.equals(
            canonicalJson(fields.get("authorityTuple")),
            bytes(operation, "authority_tuple_canonical_bytes"))
        || !Arrays.equals(
            canonicalJson(fields.get("membershipVersion")),
            bytes(operation, "membership_version_canonical_bytes"))
        || !Arrays.equals(
            canonicalJson(fields.get("authoritySourceVersions")),
            canonicalJson(currentAuthority.sourceVersions()))) {
      throw new CandidateUnavailableException();
    }
  }

  private static AccountAuthoritySnapshot authoritySnapshot(IssuerAccountSourceSnapshot sources) {
    Optional<GameSessionAccountDelegationProfile.AccountSecurityCutoff> cutoff =
        sources
            .account()
            .accountSecurityCutoff()
            .map(
                value ->
                    new GameSessionAccountDelegationProfile.AccountSecurityCutoff(
                        value.accountAuthorityGeneration(),
                        value.outboxStreamKey(),
                        value.outboxSequence()));
    return new AccountAuthoritySnapshot(
        sources.account().scope().accountId(),
        sources.issuer().generation(),
        sources.issuer().sourceVersion(),
        sources.account().generation(),
        sources.account().sourceVersion(),
        sources.issuanceFence().value(),
        sources.issuanceFence().sourceVersion(),
        cutoff);
  }

  private static long expiryEpochMillis(long expEpochSecond) {
    try {
      long millis = Math.multiplyExact(expEpochSecond, 1_000L);
      if (millis <= 0L) throw new ArithmeticException();
      return millis;
    } catch (ArithmeticException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static void validateCompactJwt(String value) {
    if (value == null
        || value.isEmpty()
        || value.length() > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES) {
      throw new CandidateUnavailableException();
    }
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) > 0x7f) throw new CandidateUnavailableException();
    }
  }

  private static byte[] canonicalJson(Object value) {
    try {
      byte[] canonical = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (canonical.length == 0 || canonical.length > 8 * 1024) {
        throw new OwnerEvidenceUnavailableException();
      }
      return canonical;
    } catch (IOException | RuntimeException ex) {
      if (ex instanceof OwnerEvidenceUnavailableException unavailable) throw unavailable;
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException ex) {
      throw new StorageUnavailableException();
    }
  }

  private static Map<String, Object> object(Map<String, Object> parent, String field) {
    Object value = parent.get(field);
    if (!(value instanceof Map<?, ?> map)) throw new OwnerEvidenceUnavailableException();
    @SuppressWarnings("unchecked")
    Map<String, Object> typed = (Map<String, Object>) map;
    return typed;
  }

  private static long numberValue(Object value) {
    if (!(value instanceof Number number)) throw new OwnerEvidenceUnavailableException();
    try {
      return Long.parseLong(number.toString());
    } catch (NumberFormatException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static Long numberFromString(Object value) {
    if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) {
      throw new OwnerEvidenceUnavailableException();
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException ex) {
      throw new OwnerEvidenceUnavailableException();
    }
  }

  private static UUID uuid(Record row, String field) {
    UUID value = row.get(field, UUID.class);
    if (value == null) throw new OperationUnavailableException();
    return value;
  }

  private static String text(Record row, String field) {
    String value = row.get(field, String.class);
    if (value == null) throw new OperationUnavailableException();
    return value;
  }

  private static byte[] bytes(Record row, String field) {
    byte[] value = row.get(field, byte[].class);
    if (value == null) throw new OperationUnavailableException();
    return value.clone();
  }

  private static long number(Record row, String field) {
    Object value = row.get(field);
    if (!(value instanceof Number numeric)) throw new OperationUnavailableException();
    return numeric.longValue();
  }

  private static Long longValue(Record row, String field) {
    Object value = row.get(field);
    return value instanceof Number numeric ? numeric.longValue() : null;
  }

  private static long positive(Record row, String field) {
    long value = number(row, field);
    if (value <= 0L) throw new OperationUnavailableException();
    return value;
  }

  private static void requireV4(UUID value, String field) {
    if (value == null || value.version() != 4 || !V4_UUID.matcher(value.toString()).matches()) {
      throw new OperationUnavailableException();
    }
  }

  private static void requireWritableAccountTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      throw new IllegalStateException(
          "Account response-envelope storage requires a writable Account owner transaction");
    }
  }

  private record PendingIdentity(
      UUID operationId,
      UUID requestId,
      UUID accountId,
      String callerWorkload,
      UUID callerContextId,
      AccountGameplayCredentialRequestBinding credentialRequestBinding,
      String requestDigest,
      UUID tokenJti,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond,
      long issuanceFence) {}

  private record CandidateIdentity(
      String tokenHash,
      String kid,
      String signerGeneration,
      GameSessionAccountDelegationRegistryRecord record) {}

  private record CommittedRecoveryIdentity(
      UUID operationId,
      UUID requestId,
      UUID accountId,
      String callerWorkload,
      UUID callerContextId,
      AccountGameplayCredentialRequestBinding credentialRequestBinding,
      String requestDigest,
      UUID tokenJti,
      long issuedAtEpochSecond,
      long notBeforeEpochSecond,
      long expiresAtEpochSecond,
      String tokenHash,
      long issuanceFence) {
    private PendingIdentity pendingIdentity() {
      return new PendingIdentity(
          operationId,
          requestId,
          accountId,
          callerWorkload,
          callerContextId,
          credentialRequestBinding,
          requestDigest,
          tokenJti,
          issuedAtEpochSecond,
          notBeforeEpochSecond,
          expiresAtEpochSecond,
          issuanceFence);
    }
  }

  /** Comparison inputs only; a caller transport must establish its authenticity separately. */
  public record CallerIdentity(String workload, UUID contextId) {
    public CallerIdentity {
      if (workload == null
          || !GAME_SESSION_WORKLOAD.matcher(workload).matches()
          || contextId == null
          || contextId.version() != 4
          || contextId.variant() != 2) {
        throw new IllegalArgumentException("Exact Game Session caller comparison is required");
      }
    }

    @Override
    public String toString() {
      return "CallerIdentity[redacted]";
    }
  }

  /** Non-authorizing readback metadata; no envelope or credential bytes are exposed. */
  public record SealedCandidateObservation(
      UUID operationId,
      UUID requestId,
      String authorityEvidenceBundleSha256,
      long issuanceFence,
      long responseRecoveryExpiryEpochMillis,
      String envelopeSha256,
      int envelopeBytesLength) {
    @Override
    public String toString() {
      return "SealedCandidateObservation[redacted]";
    }
  }

  /** Pre-activation immutable identity read; it does not authenticate or authorize the caller. */
  public record RecoveryPreflight(
      UUID operationId,
      UUID requestId,
      UUID accountId,
      String callerWorkload,
      UUID callerContextId,
      String requestDigest,
      UUID tokenJti,
      long expiresAtEpochSecond,
      long responseRecoveryExpiryEpochMillis) {
    @Override
    public String toString() {
      return "RecoveryPreflight[non-authorizing,redacted]";
    }
  }

  /**
   * Exact compact JWT returned only after committed issuance activation and current owner reads.
   */
  public static final class RecoveredCredential {
    private final byte[] compactJwtBytes;
    private final UUID accountId;
    private final UUID tokenJti;
    private final String tokenSha256;
    private final String profile;
    private final long expiresAtEpochSecond;
    private final AccountAuthEvidenceBundle authEvidenceBundle;

    private RecoveredCredential(
        byte[] compactJwtBytes,
        UUID accountId,
        UUID tokenJti,
        String tokenSha256,
        String profile,
        long expiresAtEpochSecond,
        AccountAuthEvidenceBundle authEvidenceBundle) {
      this.compactJwtBytes = compactJwtBytes.clone();
      this.accountId = Objects.requireNonNull(accountId);
      this.tokenJti = Objects.requireNonNull(tokenJti);
      this.tokenSha256 = Objects.requireNonNull(tokenSha256);
      this.profile = Objects.requireNonNull(profile);
      this.expiresAtEpochSecond = expiresAtEpochSecond;
      this.authEvidenceBundle =
          Objects.requireNonNull(
              authEvidenceBundle, "Verified Account auth-evidence bundle is required");
    }

    public byte[] compactJwtBytes() {
      return compactJwtBytes.clone();
    }

    public UUID accountId() {
      return accountId;
    }

    public UUID tokenJti() {
      return tokenJti;
    }

    public String tokenSha256() {
      return tokenSha256;
    }

    public String profile() {
      return profile;
    }

    public long expiresAtEpochSecond() {
      return expiresAtEpochSecond;
    }

    public AccountAuthEvidenceBundle authEvidenceBundle() {
      return authEvidenceBundle;
    }

    @Override
    public String toString() {
      return "RecoveredCredential[secret,redacted]";
    }
  }

  public static final class OperationUnavailableException extends IllegalStateException {
    public OperationUnavailableException() {
      super("Account gameplay delegation operation is unavailable or ambiguous");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }

  public static final class CandidateUnavailableException extends IllegalStateException {
    public CandidateUnavailableException() {
      super("Account signed delegation candidate is unavailable or malformed");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }

  public static final class OwnerEvidenceUnavailableException extends IllegalStateException {
    public OwnerEvidenceUnavailableException() {
      super("Account owner evidence is unavailable, stale, or ambiguous");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }

  public static final class AuthorityChangedException extends IllegalStateException {
    public AuthorityChangedException() {
      super("Account gameplay delegation authority changed");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }

  public static final class IdempotencyConflictException extends IllegalStateException {
    public IdempotencyConflictException() {
      super("Account gameplay delegation response candidate conflicts with its PENDING operation");
    }

    public String errorCode() {
      return "IDEMPOTENCY_CONFLICT";
    }
  }

  public static final class ResponseRecoveryExpiredException extends IllegalStateException {
    public ResponseRecoveryExpiredException() {
      super("Account response recovery horizon has expired");
    }

    public String errorCode() {
      return "RESPONSE_RECOVERY_EXPIRED";
    }
  }

  public static final class StorageUnavailableException extends IllegalStateException {
    public StorageUnavailableException() {
      super("Account encrypted response-envelope storage is unavailable or ambiguous");
    }

    public String errorCode() {
      return "AUTH_UNAVAILABLE";
    }
  }
}
