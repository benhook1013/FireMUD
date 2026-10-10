package net.firedevops.firemud.accountservice.service.session;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository;
import net.firedevops.firemud.accountservice.repository.AccountGameplayDelegationIssuanceRepository.CommittedIssuanceReadback;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationAuthorityProjection.ProjectionObservation;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.PendingRegistrationReceipt;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.CandidateOutcome;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.PendingCandidateVerificationProof;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Explicitly unwired, non-authorizing Account PENDING-to-COMMITTED orchestration boundary.
 *
 * <p>All protected signer, Coordination Redis, and source projection work finishes before the final
 * Account SQL transaction begins. The final transaction rechecks the signer and owner rows,
 * persists only bounded canonical non-secret evidence, and is followed by an independent exact
 * COMMITTED readback. No compact JWT is returned, decrypted, or persisted here.
 */
public final class AccountGameplayDelegationIssuanceCommitService {
  public static final String COMMIT_PROOF_SCHEMA =
      "account-game-session-delegation-commit-proof/v1";
  public static final int MAX_COMMIT_PROOF_BYTES = 32 * 1024;

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final AccountGameplayDelegationSigner signer;
  private final AccountGameplayDelegationTokenRegistry tokenRegistry;
  private final AccountGameplayDelegationAuthorityProjection authorityProjection;
  private final AccountGameplayDelegationIssuanceRepository issuanceRepository;
  private final TransactionTemplate accountTransaction;
  private final Clock clock;

  public AccountGameplayDelegationIssuanceCommitService(
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry tokenRegistry,
      AccountGameplayDelegationAuthorityProjection authorityProjection,
      AccountGameplayDelegationIssuanceRepository issuanceRepository,
      PlatformTransactionManager accountTransactionManager,
      Clock clock) {
    this.signer = Objects.requireNonNull(signer, "Account gameplay signer is required");
    this.tokenRegistry =
        Objects.requireNonNull(tokenRegistry, "Account pending registry is required");
    this.authorityProjection =
        Objects.requireNonNull(authorityProjection, "Account authority projection is required");
    this.issuanceRepository =
        Objects.requireNonNull(issuanceRepository, "Account issuance repository is required");
    this.accountTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(
                accountTransactionManager, "Account transaction manager is required"));
    this.clock = Objects.requireNonNull(clock, "Clock is required");
  }

  /**
   * Verifies and commits one already-persisted initial LOGIN issuance operation. This method is
   * intentionally not connected to Authenticate, Spring runtime wiring, or a response endpoint.
   */
  public CommitResult commitPendingCandidate(UUID requestId) {
    requireRequestId(requestId);
    // This method performs signer, Redis and source reads before its final SQL transaction. An
    // ambient caller transaction could retain Account locks across those network operations, so
    // refuse it instead of silently joining or suspending unknown work.
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    try {
      Optional<CommittedIssuanceReadback> prior = issuanceRepository.readCommittedProof(requestId);
      if (prior.isPresent()) return CommitResult.exactRetry(prior.orElseThrow());

      CandidateOutcome candidate = signer.signPendingCandidate(requestId);
      if (candidate == null || candidate.verificationProof() == null) throw unavailable();
      PendingCandidateVerificationProof proof = candidate.verificationProof();
      long now = Math.floorDiv(clock.millis(), 1_000L);
      if (!requestId.equals(proof.requestId())
          || now <= 0L
          || proof.notBeforeEpochSecond() > now
          || proof.expiresAtEpochSecond() <= now) {
        throw unavailable();
      }

      PendingRegistrationReceipt registration = tokenRegistry.registerPending(requestId);
      ProjectionObservation projection = authorityProjection.observeCurrent(proof.accountId());
      CommitProof commitProof = CommitProof.create(proof, registration, projection);

      CommittedIssuanceReadback transactionReadback =
          accountTransaction.execute(
              status -> {
                signer.requireCurrentCommittedSigner(proof);
                return issuanceRepository.commitPendingCandidate(
                    requestId, proof, registration, projection, commitProof);
              });
      if (transactionReadback == null
          || !transactionReadback.proofSha256().equals(commitProof.sha256())) {
        throw unavailable();
      }

      Optional<CommittedIssuanceReadback> durable =
          issuanceRepository.readCommittedProof(requestId);
      if (durable.isEmpty() || !durable.orElseThrow().equals(transactionReadback)) {
        throw unavailable();
      }
      return CommitResult.created(durable.orElseThrow());
    } catch (RuntimeException failure) {
      if (failure instanceof IssuanceCommitUnavailableException unavailable) throw unavailable;
      throw unavailable();
    }
  }

  private static void requireRequestId(UUID requestId) {
    Objects.requireNonNull(requestId, "Account issuance request ID is required");
    if (requestId.version() != 4 || requestId.variant() != 2) throw unavailable();
  }

  private static IssuanceCommitUnavailableException unavailable() {
    return new IssuanceCommitUnavailableException();
  }

  /** Private-constructor immutable proof assembled only from owner-produced typed evidence. */
  public static final class CommitProof {
    private final UUID operationId;
    private final UUID requestId;
    private final UUID accountId;
    private final String requestDigest;
    private final String tokenSha256;
    private final String sha256;
    private final byte[] canonicalBytes;

    private CommitProof(
        UUID operationId,
        UUID requestId,
        UUID accountId,
        String requestDigest,
        String tokenSha256,
        byte[] canonicalBytes) {
      this.operationId = Objects.requireNonNull(operationId);
      this.requestId = Objects.requireNonNull(requestId);
      this.accountId = Objects.requireNonNull(accountId);
      this.requestDigest = Objects.requireNonNull(requestDigest);
      this.tokenSha256 = Objects.requireNonNull(tokenSha256);
      this.canonicalBytes = canonicalBytes.clone();
      if (this.canonicalBytes.length == 0 || this.canonicalBytes.length > MAX_COMMIT_PROOF_BYTES) {
        throw unavailable();
      }
      this.sha256 = AccountGameplayDelegationIssuanceCommitService.sha256(this.canonicalBytes);
    }

    private static CommitProof create(
        PendingCandidateVerificationProof proof,
        PendingRegistrationReceipt registration,
        ProjectionObservation projection) {
      Objects.requireNonNull(proof, "Signer-owned pending verification proof is required");
      Objects.requireNonNull(registration, "Pinned Redis registration receipt is required");
      Objects.requireNonNull(projection, "Current Account authority projection is required");
      var identity = proof.identity();
      AccountAuthoritySnapshot snapshot = proof.authoritySnapshot();
      EvidenceBundleReference bundleReference = proof.evidenceBundleReference();
      if (!identity.operationId().toString().equals(registration.operationId())
          || !identity.requestId().toString().equals(registration.requestId())
          || !identity.accountId().toString().equals(registration.accountId())
          || !identity.requestDigest().equals(registration.requestDigest())
          || !identity.tokenJti().toString().equals(registration.tokenJti())
          || !proof.tokenSha256().equals(registration.tokenHash())
          || !proof.signerKid().equals(registration.kid())
          || !proof.signerGeneration().equals(registration.signerGeneration())
          || proof.issuanceFence() != registration.issuanceFence()
          || !AccountGameplayDelegationRedisClient.REQUIRED_ACL_IDENTITY.equals(
              registration.aclIdentity())
          || !bundleReference.equals(registration.evidenceBundleReference())
          || !proof.canonicalRegistryRecordSha256().equals(registration.canonicalRecordSha256())
          || projection.issuerGeneration() != snapshot.issuerGeneration()
          || projection.accountGeneration() != snapshot.accountGeneration()
          || registration.registryVersion() != 1L
          || registration.localAofCount() < 1L
          || registration.replicaAofCount() < 1L) {
        throw unavailable();
      }

      Map<String, Object> root = new LinkedHashMap<>();
      root.put("schema", COMMIT_PROOF_SCHEMA);
      root.put(
          "identity",
          Map.ofEntries(
              Map.entry("operationId", identity.operationId().toString()),
              Map.entry("requestId", identity.requestId().toString()),
              Map.entry("accountId", identity.accountId().toString()),
              Map.entry("requestDigest", identity.requestDigest()),
              Map.entry("callerWorkload", identity.callerWorkload()),
              Map.entry("callerContextId", identity.callerContextId().toString()),
              Map.entry("tokenJti", identity.tokenJti().toString()),
              Map.entry("tokenGeneration", "1"),
              Map.entry("issuedAtEpochSecond", Long.toString(identity.issuedAtEpochSecond())),
              Map.entry("notBeforeEpochSecond", Long.toString(identity.notBeforeEpochSecond())),
              Map.entry("expiresAtEpochSecond", Long.toString(identity.expiresAtEpochSecond())),
              Map.entry("tokenSha256", proof.tokenSha256())));
      root.put("tokenSha256", proof.tokenSha256());
      root.put("registryRecordSha256", proof.canonicalRegistryRecordSha256());
      root.put(
          "registry",
          Map.ofEntries(
              Map.entry("state", "pending"),
              Map.entry("registryVersion", Long.toString(registration.registryVersion())),
              Map.entry("tokenKey", registration.tokenKey()),
              Map.entry("tokenHash", registration.tokenHash()),
              Map.entry("kid", registration.kid()),
              Map.entry("signerGeneration", registration.signerGeneration()),
              Map.entry("operationId", registration.operationId()),
              Map.entry("requestId", registration.requestId()),
              Map.entry("requestDigest", registration.requestDigest()),
              Map.entry("absoluteExpiryMillis", Long.toString(registration.absoluteExpiryMillis())),
              Map.entry("localAofCount", Long.toString(registration.localAofCount())),
              Map.entry("replicaAofCount", Long.toString(registration.replicaAofCount())),
              Map.entry("outcome", registration.outcome().name()),
              Map.entry("canonicalRecordSha256", registration.canonicalRecordSha256()),
              Map.entry(
                  "evidenceBundleSha256", registration.evidenceBundleReference().canonicalSha256()),
              Map.entry("aclIdentity", registration.aclIdentity())));
      root.put("bundle", bundleReference.toMap());

      root.put(
          "authority",
          Map.ofEntries(
              Map.entry("issuerGeneration", Long.toString(snapshot.issuerGeneration())),
              Map.entry("issuerSourceVersion", Long.toString(snapshot.issuerSourceVersion())),
              Map.entry("accountGeneration", Long.toString(snapshot.accountGeneration())),
              Map.entry("accountSourceVersion", Long.toString(snapshot.accountSourceVersion())),
              Map.entry("issuanceFence", Long.toString(snapshot.issuanceFence())),
              Map.entry(
                  "issuanceFenceSourceVersion",
                  Long.toString(snapshot.issuanceFenceSourceVersion())),
              Map.entry(
                  "authorityTuple",
                  GameSessionAccountDelegationProfile.authorityTuple(
                      snapshot.issuerGeneration(),
                      snapshot.accountGeneration(),
                      snapshot.accountSecurityCutoff())),
              Map.entry("authoritySourceVersions", snapshot.sourceVersions()),
              Map.entry("issuerProjectionGeneration", Long.toString(projection.issuerGeneration())),
              Map.entry(
                  "accountProjectionGeneration", Long.toString(projection.accountGeneration())),
              Map.entry("issuerProjectionSha256", projection.issuerDigest()),
              Map.entry("accountProjectionSha256", projection.accountDigest())));

      AccountGameplayDelegationSigner.AuthenticatedSignerCorrespondence correspondence =
          proof.signerCorrespondence();
      Object canonicalCorrespondence = canonicalValue(correspondence, 0);
      byte[] correspondenceBytes = canonicalJson(canonicalCorrespondence);
      root.put(
          "signer",
          Map.of(
              "promotionStatus",
              correspondence.promotionStatus(),
              "promotionOperationId",
              correspondence.promotionOperationId().toString(),
              "generationOperationId",
              correspondence.generationOperationId().toString(),
              "targetKid",
              proof.signerKid(),
              "targetGeneration",
              proof.signerGeneration(),
              "correspondenceSha256",
              AccountGameplayDelegationIssuanceCommitService.sha256(correspondenceBytes),
              "correspondence",
              canonicalCorrespondence));

      var sealed = proof.sealedCandidate();
      root.put(
          "envelope",
          Map.of(
              "operationId",
              sealed.operationId().toString(),
              "requestId",
              sealed.requestId().toString(),
              "tokenHash",
              proof.tokenSha256(),
              "kid",
              proof.signerKid(),
              "signerGeneration",
              proof.signerGeneration(),
              "authorityEvidenceBundleSha256",
              sealed.authorityEvidenceBundleSha256(),
              "issuanceFence",
              Long.toString(sealed.issuanceFence()),
              "responseRecoveryExpiryEpochMillis",
              Long.toString(sealed.responseRecoveryExpiryEpochMillis()),
              "envelopeSha256",
              sealed.envelopeSha256(),
              "envelopeBytesLength",
              Integer.toString(sealed.envelopeBytesLength())));

      byte[] canonicalBytes = canonicalJson(root);
      return new CommitProof(
          identity.operationId(),
          identity.requestId(),
          identity.accountId(),
          identity.requestDigest(),
          proof.tokenSha256(),
          canonicalBytes);
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

    public String requestDigest() {
      return requestDigest;
    }

    public String tokenSha256() {
      return tokenSha256;
    }

    public String sha256() {
      return sha256;
    }

    public byte[] canonicalBytes() {
      return canonicalBytes.clone();
    }

    @Override
    public String toString() {
      return "AccountGameplayDelegationCommitProof[redacted]";
    }
  }

  /** The result exposes only immutable operation identity and proof digest; never a JWT. */
  public record CommitResult(
      UUID operationId, UUID requestId, UUID accountId, String proofSha256, Outcome outcome) {
    public CommitResult {
      Objects.requireNonNull(operationId);
      Objects.requireNonNull(requestId);
      Objects.requireNonNull(accountId);
      Objects.requireNonNull(proofSha256);
      Objects.requireNonNull(outcome);
    }

    private static CommitResult created(CommittedIssuanceReadback readback) {
      return result(readback, Outcome.COMMITTED);
    }

    private static CommitResult exactRetry(CommittedIssuanceReadback readback) {
      return result(readback, Outcome.EXACT_RETRY);
    }

    private static CommitResult result(CommittedIssuanceReadback readback, Outcome outcome) {
      return new CommitResult(
          readback.operationId(),
          readback.requestId(),
          readback.accountId(),
          readback.proofSha256(),
          outcome);
    }

    @Override
    public String toString() {
      return "AccountGameplayDelegationCommitResult[non-authorizing]";
    }
  }

  public enum Outcome {
    COMMITTED,
    EXACT_RETRY
  }

  public static final class IssuanceCommitUnavailableException extends IllegalStateException {
    public IssuanceCommitUnavailableException() {
      super("Account gameplay delegation commit is unavailable or ambiguous");
    }
  }

  private static Object canonicalValue(Object value, int depth) {
    if (depth > 8) throw unavailable();
    if (value == null) return null;
    if (value instanceof Optional<?> optional)
      return canonicalValue(optional.orElse(null), depth + 1);
    if (value instanceof UUID uuid) return uuid.toString();
    if (value instanceof Enum<?> enumValue) return enumValue.name();
    if (value instanceof String || value instanceof Boolean || value instanceof Number)
      return value;
    if (!value.getClass().isRecord()) throw unavailable();
    RecordComponent[] components = value.getClass().getRecordComponents();
    if (components.length == 0 || components.length > 64) throw unavailable();
    Map<String, Object> fields = new LinkedHashMap<>();
    try {
      for (RecordComponent component : components) {
        fields.put(
            component.getName(), canonicalValue(component.getAccessor().invoke(value), depth + 1));
      }
    } catch (ReflectiveOperationException ex) {
      throw unavailable();
    }
    return fields;
  }

  private static byte[] canonicalJson(Object value) {
    try {
      byte[] bytes = Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
      if (bytes.length == 0 || bytes.length > MAX_COMMIT_PROOF_BYTES) throw unavailable();
      return bytes;
    } catch (IOException ex) {
      throw unavailable();
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException ex) {
      throw unavailable();
    }
  }
}
