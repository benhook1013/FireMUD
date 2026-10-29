package net.firedevops.firemud.accountservice.repository;

import io.grpc.Status;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.AuthenticatedFinalAdmissionReadback;
import net.firedevops.firemud.accountservice.client.AccountOriginalNewFinalAdmissionReadClient.ExpectedOriginalAdmission;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionLeaseOperation.State;
import net.firedevops.firemud.accountservice.dto.AccountGameplayAdmissionOriginalAckReceipt;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.SourceKind;
import net.firedevops.firemud.accountservice.dto.AccountGameplayTokenIdentityFence.TokenIdentity;
import net.firedevops.firemud.accountservice.repository.AccountOriginalNewBoundGameplayIssuanceRepository.Genesis;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayAdmissionCallerVerifier.VerifiedCaller;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.AccountCoordinationRedisException;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationRedisClient.PendingRegistrationReceipt;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationSigner.OriginalNewBoundSignedCandidate;
import net.firedevops.firemud.accountservice.service.session.AccountGameplayDelegationTokenRegistry;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.Binding;
import net.firedevops.firemud.accountservice.service.session.AccountResponseEnvelopeCryptography.EncryptedResponseEnvelope;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredential;
import net.firedevops.firemud.common.grpc.GrpcPeerIdentity;
import net.firedevops.firemud.common.json.Rfc8785CanonicalJson;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.AccountAuthoritySnapshot;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.EvidenceBundleReference;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationRegistryRecord.IssuanceBinding;
import net.firedevops.firemud.common.security.SessionContext;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Manually composed Account owner for the first bound ORIGINAL-NEW gameplay credential.
 *
 * <p>The caller's LOGIN token is only authentication for this operation. The new token and its
 * opaque handle are anchored to one immutable original NEW lease plus its separately durable ACK.
 * This owner does not install a Game Session admission, activate a session, or implement REBIND.
 */
public final class AccountOriginalNewBoundGameplayCredentialOwner {
  private static final String GAME_SESSION_SERVICE = "game-session-service";
  private static final String ENVELOPE_OPERATION = "original-new-bound-gameplay-credential";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DataSource dataSource;
  private final String workloadNamespace;
  private final AccountGameplayAdmissionCallerVerifier callerVerifier;
  private final AccountGameplayAdmissionOriginalAckReceiptCommitExecutor receiptExecutor;
  private final AccountGameplayDelegationSigner signer;
  private final AccountGameplayDelegationTokenRegistry tokenRegistry;
  private final AccountResponseEnvelopeCryptography responseCryptography;
  private final AccountOriginalNewFinalAdmissionReadClient finalAdmissionReadClient;
  private final Clock clock;
  private final SecureRandom secureRandom;

  public AccountOriginalNewBoundGameplayCredentialOwner(
      DataSource dataSource,
      String workloadNamespace,
      AccountGameplayAdmissionCallerVerifier callerVerifier,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry tokenRegistry,
      AccountResponseEnvelopeCryptography responseCryptography,
      Clock clock) {
    this(
        dataSource,
        workloadNamespace,
        callerVerifier,
        signer,
        tokenRegistry,
        responseCryptography,
        clock,
        null,
        new SecureRandom());
  }

  /** Explicitly protected final-admission reader; absent reader leaves confirmation fail-closed. */
  public AccountOriginalNewBoundGameplayCredentialOwner(
      DataSource dataSource,
      String workloadNamespace,
      AccountGameplayAdmissionCallerVerifier callerVerifier,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry tokenRegistry,
      AccountResponseEnvelopeCryptography responseCryptography,
      Clock clock,
      AccountOriginalNewFinalAdmissionReadClient finalAdmissionReadClient) {
    this(
        dataSource,
        workloadNamespace,
        callerVerifier,
        signer,
        tokenRegistry,
        responseCryptography,
        clock,
        Objects.requireNonNull(finalAdmissionReadClient),
        new SecureRandom());
  }

  AccountOriginalNewBoundGameplayCredentialOwner(
      DataSource dataSource,
      String workloadNamespace,
      AccountGameplayAdmissionCallerVerifier callerVerifier,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry tokenRegistry,
      AccountResponseEnvelopeCryptography responseCryptography,
      Clock clock,
      SecureRandom secureRandom) {
    this(
        dataSource,
        workloadNamespace,
        callerVerifier,
        signer,
        tokenRegistry,
        responseCryptography,
        clock,
        null,
        secureRandom);
  }

  private AccountOriginalNewBoundGameplayCredentialOwner(
      DataSource dataSource,
      String workloadNamespace,
      AccountGameplayAdmissionCallerVerifier callerVerifier,
      AccountGameplayDelegationSigner signer,
      AccountGameplayDelegationTokenRegistry tokenRegistry,
      AccountResponseEnvelopeCryptography responseCryptography,
      Clock clock,
      AccountOriginalNewFinalAdmissionReadClient finalAdmissionReadClient,
      SecureRandom secureRandom) {
    this.dataSource = Objects.requireNonNull(dataSource, "Account DataSource is required");
    if (!GrpcPeerIdentity.isValidNamespace(workloadNamespace)) {
      throw new IllegalArgumentException("Canonical Account workload namespace required");
    }
    this.workloadNamespace = workloadNamespace;
    this.callerVerifier = Objects.requireNonNull(callerVerifier);
    this.receiptExecutor = new AccountGameplayAdmissionOriginalAckReceiptCommitExecutor(dataSource);
    this.signer = Objects.requireNonNull(signer);
    this.tokenRegistry = Objects.requireNonNull(tokenRegistry);
    this.responseCryptography = Objects.requireNonNull(responseCryptography);
    this.finalAdmissionReadClient = finalAdmissionReadClient;
    this.clock = Objects.requireNonNull(clock);
    this.secureRandom = Objects.requireNonNull(secureRandom);
  }

  /** Issues once after the durable original COMMITTED lease and ACK are independently read back. */
  public OriginalNewCredential issue(
      String presentedInitialGsACompactJwt, AccountGameplayAdmissionLeaseEvidence requestedLease) {
    return issueChecked(presentedInitialGsACompactJwt, requestedLease).credential();
  }

  private IssuedOriginalNew issueChecked(
      String presentedInitialGsACompactJwt, AccountGameplayAdmissionLeaseEvidence requestedLease) {
    GrpcPeerIdentity peer = requireGameSessionCaller();
    requireNoAmbientSql();
    Objects.requireNonNull(requestedLease, "Original NEW lease is required");
    VerifiedCaller caller = verifyCaller(presentedInitialGsACompactJwt, peer);
    requireCallerMatchesLease(caller, peer, requestedLease);

    SourceRead initial = readCurrentOriginalSource(caller, requestedLease);
    if (initial.operation().state() != State.COMMITTED
        || initial.operation().bindingDecisionId() == null) throw unavailable();
    AccountGameplayAdmissionOriginalAckReceipt receipt =
        receiptExecutor.read(requestedLease, initial.operation().bindingDecisionId());
    requireExactReceipt(receipt, initial.operation(), requestedLease);

    long nowSeconds = clock.instant().getEpochSecond();
    if (nowSeconds <= 0L) throw unavailable();
    UUID newLineageId = UUID.randomUUID();
    UUID newTokenJti = UUID.randomUUID();
    while (newTokenJti.equals(newLineageId) || newTokenJti.equals(caller.identity().tokenJti())) {
      newTokenJti = UUID.randomUUID();
    }
    UUID frozenNewTokenJti = newTokenJti;
    long expirySeconds;
    try {
      expirySeconds =
          Math.addExact(nowSeconds, GameSessionAccountDelegationProfile.MAX_TOKEN_LIFETIME_SECONDS);
    } catch (ArithmeticException malformed) {
      throw unavailable();
    }

    Genesis genesis =
        inWritableSerializableTransaction(
            dsl ->
                new AccountOriginalNewBoundGameplayIssuanceRepository(dsl)
                    .beginGenesis(
                        requestedLease,
                        receipt,
                        newLineageId,
                        frozenNewTokenJti,
                        nowSeconds,
                        nowSeconds,
                        expirySeconds));

    if (genesis.tokenHash() == null) {
      genesis = signAndPersistCandidate(caller, requestedLease, receipt, genesis);
    }
    if (genesis.tokenHash() == null || genesis.encryptedResultEnvelopeBytes() == null) {
      throw unavailable();
    }

    final String receiptDigest;
    if (genesis.status() == AccountOriginalNewBoundGameplayIssuanceRepository.Status.COMMITTED) {
      receiptDigest = genesis.committedRegistryReceiptSha256();
      if (receiptDigest == null) throw unavailable();
      requireCommittedRegistryProjection(genesis);
    } else {
      PendingRegistrationReceipt redisReceipt = tokenRegistry.registerOriginalNewPending(genesis);
      receiptDigest = pendingRegistryReceiptDigest(redisReceipt);
    }

    // Network registration has completed with no SQL lock held. Re-authenticate the original
    // caller, then atomically commit this exact issuance and create its independent token fence.
    VerifiedCaller currentCaller = verifyCaller(presentedInitialGsACompactJwt, peer);
    if (!AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData
        .hasSameOwnerEvidence(caller.currentCandidate(), currentCaller.currentCandidate())) {
      throw unavailable();
    }
    final Genesis exactGenesisForCommit = genesis;
    Genesis committed =
        inWritableSerializableTransaction(
            dsl -> {
              SourceRead current = readCurrentOriginalSource(dsl, currentCaller, requestedLease);
              requireSameOriginalOperation(initial.operation(), current.operation());
              AccountOriginalNewBoundGameplayIssuanceRepository issuance =
                  new AccountOriginalNewBoundGameplayIssuanceRepository(dsl);
              Genesis stored = issuance.readByLease(leaseId(requestedLease));
              requireSameGenesisIdentity(exactGenesisForCommit, stored);
              Genesis committedResult = issuance.commit(stored, receiptDigest);
              var fenceRepository = new AccountGameplayTokenIdentityFenceRepository(dsl);
              var tokenIdentity =
                  new TokenIdentity(
                      committedResult.accountId(),
                      committedResult.lineageId(),
                      committedResult.requestId(),
                      committedResult.tokenHash(),
                      committedResult.tokenJti(),
                      committedResult.notBeforeEpochSecond(),
                      committedResult.tokenGeneration(),
                      committedResult.issuanceFence(),
                      SourceKind.ORIGINAL_NEW);
              fenceRepository.registerCommittedOriginalNewIssuance(tokenIdentity);
              return committedResult;
            });

    // The caller receives the result only after Account's issuance row and active identity fence
    // committed together. Opening requires a fresh source and exact active-fence readback.
    return new IssuedOriginalNew(
        openCommittedResult(currentCaller, requestedLease, receipt, committed),
        receipt,
        initial.operation().bindingDecisionId());
  }

  /** Full protected RPC result, with the original lease and ACK carrier left unchanged. */
  public net.firedevops.firemud.account.v1.AccountGameplayAdmissionLeaseResponse issue(
      AccountGameplayAdmissionLeaseGrpcCodec.OriginalNewCredentialRequest request) {
    Objects.requireNonNull(request, "Parsed ORIGINAL-NEW credential request is required");
    AccountGameplayAdmissionLeaseEvidence lease = request.originalLeaseEvidence();
    IssuedOriginalNew issued = issueChecked(request.presentedInitialGsACompactJwt(), lease);
    AccountGameplayAdmissionOriginalAckReceipt receipt = issued.receipt();
    OriginalNewCredential credential = issued.credential();
    try {
      var ack =
          AccountGameplayAdmissionLeaseGrpcCodec.OriginalAckReceiptEvidence.fromFields(
              receipt.schemaVersion(),
              receipt.requestId(),
              receipt.accountId(),
              receipt.leaseId(),
              receipt.leaseFence(),
              receipt.evidenceSha256(),
              receipt.bindingDecisionId(),
              receipt.expiresAtMs(),
              receipt.finalizationXid(),
              receipt.committedBeforeMs(),
              receipt.receiptXid());
      return AccountGameplayAdmissionLeaseGrpcCodec.toIssueOriginalNewCredentialResponse(
          request,
          lease,
          AccountGameplayAdmissionLeaseGrpcCodec.State.COMMITTED,
          issued.bindingDecisionId(),
          ack,
          credential);
    } finally {
      credential.close();
    }
  }

  private record IssuedOriginalNew(
      OriginalNewCredential credential,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      UUID bindingDecisionId) {}

  private void requireCommittedRegistryProjection(Genesis committed) {
    if (committed.confirmedAdmissionAtMillis() != null) {
      tokenRegistry.requireExactOriginalNewActiveRecord(committed);
      return;
    }
    try {
      // Keep the frozen original SQL receipt and absolute deadline, even on a later retry.
      tokenRegistry.registerOriginalNewPending(committed);
    } catch (AccountCoordinationRedisException pendingUnavailableOrConflicting) {
      // Activation may have succeeded before SQL confirmation failed. Accept only its exact
      // original ACTIVE readback; this path cannot reactivate or downgrade a registry record.
      tokenRegistry.requireExactOriginalNewActiveRecord(committed);
    }
  }

  /**
   * Confirms only an actual Game Session final-admission readback fetched by Account's protected
   * client. The caller supplies neither timestamps nor a proof object. No credential is reminted.
   */
  public OriginalAdmissionConfirmation confirmOriginalFinalAdmission(
      String presentedInitialGsACompactJwt, AccountGameplayAdmissionLeaseEvidence requestedLease) {
    if (finalAdmissionReadClient == null) throw unavailable();
    GrpcPeerIdentity peer = requireGameSessionCaller();
    requireNoAmbientSql();
    VerifiedCaller caller = verifyCaller(presentedInitialGsACompactJwt, peer);
    requireCallerMatchesLease(caller, peer, requestedLease);
    SourceRead beforeRead = readCurrentOriginalSource(caller, requestedLease);
    if (beforeRead.operation().state() != State.COMMITTED
        || beforeRead.operation().bindingDecisionId() == null) throw unavailable();
    AccountGameplayAdmissionOriginalAckReceipt receipt =
        receiptExecutor.read(requestedLease, beforeRead.operation().bindingDecisionId());
    requireExactReceipt(receipt, beforeRead.operation(), requestedLease);
    Genesis beforeGenesis =
        inWritableSerializableTransaction(
            dsl -> {
              SourceRead current = readCurrentOriginalSource(dsl, caller, requestedLease);
              requireSameOriginalOperation(beforeRead.operation(), current.operation());
              Genesis stored =
                  new AccountOriginalNewBoundGameplayIssuanceRepository(dsl)
                      .readByLease(leaseId(requestedLease));
              if (stored == null
                  || stored.status()
                      != AccountOriginalNewBoundGameplayIssuanceRepository.Status.COMMITTED) {
                throw unavailable();
              }
              return stored;
            });
    OriginalNewCredential storedCredential =
        beforeGenesis.confirmedAdmissionAtMillis() == null
            ? openFrozenCredentialForFinalAdmissionVerification(
                caller, requestedLease, receipt, beforeGenesis)
            : openCommittedResult(caller, requestedLease, receipt, beforeGenesis);
    String storedCompactJwt = storedCredential.signedBoundGsACompactJwt();
    String boundTenantId = tenantId(requestedLease).toString();
    try {
      Map<String, Object> boundTuple =
          stringObjectMap(beforeGenesis.authorityTupleCanonicalBytes());
      Map<String, String> boundMembership =
          stringStringMap(beforeGenesis.membershipVersionCanonicalBytes());
      signer.verifyOriginalNewBoundCredential(
          storedCompactJwt,
          beforeGenesis,
          beforeRead.accountAuthority(),
          boundTenantId,
          boundTuple,
          boundMembership);
    } finally {
      storedCredential.close();
    }
    ExpectedOriginalAdmission expected = expectedAdmission(requestedLease, beforeGenesis);

    if (beforeGenesis.confirmedAdmissionAtMillis() != null) {
      long nowMillis = clock.millis();
      if (nowMillis <= 0L || nowMillis >= beforeGenesis.confirmedContinuityExpiresAtMillis())
        throw unavailable();
      VerifiedCaller currentCaller = verifyCaller(presentedInitialGsACompactJwt, peer);
      if (!AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData
          .hasSameOwnerEvidence(caller.currentCandidate(), currentCaller.currentCandidate())) {
        throw unavailable();
      }
      Genesis recovered =
          inWritableSerializableTransaction(
              dsl -> {
                SourceRead current = readCurrentOriginalSource(dsl, currentCaller, requestedLease);
                requireSameOriginalOperation(beforeRead.operation(), current.operation());
                AccountGameplayAdmissionOriginalAckReceipt durableReceipt =
                    new AccountGameplayAdmissionOriginalAckReceiptRepository(
                            dsl, new AccountGameplayAdmissionLeaseRepository(dsl))
                        .readExact(requestedLease, current.operation().bindingDecisionId());
                // The receipt owner already proved independent durability outside this
                // transaction. Re-read the immutable row here under current source locks.
                requireExactReceipt(durableReceipt, current.operation(), requestedLease);
                if (!receipt.equals(durableReceipt)) throw unavailable();
                var issuance = new AccountOriginalNewBoundGameplayIssuanceRepository(dsl);
                Genesis exact = issuance.readByLease(leaseId(requestedLease));
                requireSameGenesisIdentity(beforeGenesis, exact);
                if (exact.confirmedAdmissionAtMillis() == null
                    || !Objects.equals(
                        exact.confirmedAdmissionAtMillis(),
                        beforeGenesis.confirmedAdmissionAtMillis())
                    || !Objects.equals(
                        exact.confirmedContinuityExpiresAtMillis(),
                        beforeGenesis.confirmedContinuityExpiresAtMillis())
                    || !exact
                        .confirmationReadbackSha256()
                        .equals(beforeGenesis.confirmationReadbackSha256())) throw unavailable();
                new AccountGameplayTokenIdentityFenceRepository(dsl)
                    .requireActiveReadback(originalNewIdentity(exact));
                return exact;
              });
      tokenRegistry.requireExactOriginalNewActiveRecord(recovered);
      if (clock.millis() >= recovered.confirmedContinuityExpiresAtMillis()) throw unavailable();
      return new OriginalAdmissionConfirmation(
          recovered.lineageId(),
          recovered.leaseId(),
          recovered.confirmedAdmissionAtMillis(),
          recovered.confirmedContinuityExpiresAtMillis(),
          recovered.confirmationReadbackSha256());
    }

    final AuthenticatedFinalAdmissionReadback finalReadback;
    try {
      finalReadback =
          Objects.requireNonNull(
              finalAdmissionReadClient.readActualOriginalFinalAdmission(expected));
    } catch (RuntimeException unavailable) {
      throw unavailable();
    }
    if (!expected.equals(finalReadback.anchors())) throw unavailable();
    long nowMillis = clock.millis();
    if (nowMillis <= 0L
        || finalReadback.admissionAtMillis() > nowMillis
        || nowMillis >= finalReadback.continuityExpiresAtMillis()) throw unavailable();

    // Activation is Account-owned and happens only after the authenticated GS readback exists.
    // If its reply is lost, the Redis CAS accepts only the same immutable active projection.
    AccountGameplayDelegationRedisClient.OriginalNewActivationReadback activation =
        tokenRegistry.activateOriginalNewCommittedPending(beforeGenesis);
    requireActivationMatches(beforeGenesis, activation);

    VerifiedCaller currentCaller = verifyCaller(presentedInitialGsACompactJwt, peer);
    if (!AccountGameplayDelegationIssuanceRepository.CommittedCandidateVerificationData
        .hasSameOwnerEvidence(caller.currentCandidate(), currentCaller.currentCandidate())) {
      throw unavailable();
    }
    signer.verifyOriginalNewBoundCredential(
        storedCompactJwt,
        beforeGenesis,
        beforeRead.accountAuthority(),
        boundTenantId,
        stringObjectMap(beforeGenesis.authorityTupleCanonicalBytes()),
        stringStringMap(beforeGenesis.membershipVersionCanonicalBytes()));
    String confirmationDigest = confirmationDigest(finalReadback, activation);
    Genesis confirmed =
        inWritableSerializableTransaction(
            dsl -> {
              SourceRead current = readCurrentOriginalSource(dsl, currentCaller, requestedLease);
              requireSameOriginalOperation(beforeRead.operation(), current.operation());
              AccountGameplayAdmissionOriginalAckReceipt durableReceipt =
                  new AccountGameplayAdmissionOriginalAckReceiptRepository(
                          dsl, new AccountGameplayAdmissionLeaseRepository(dsl))
                      .readExact(requestedLease, current.operation().bindingDecisionId());
              // Keep the independently proved receipt exact; this transaction supplies
              // current source exclusion, not another historical durability attempt.
              requireExactReceipt(durableReceipt, current.operation(), requestedLease);
              if (!receipt.equals(durableReceipt)) throw unavailable();
              var issuance = new AccountOriginalNewBoundGameplayIssuanceRepository(dsl);
              Genesis exact = issuance.readByLease(leaseId(requestedLease));
              requireSameGenesisIdentity(beforeGenesis, exact);
              TokenIdentity tokenIdentity = originalNewIdentity(exact);
              new AccountGameplayTokenIdentityFenceRepository(dsl)
                  .requireActiveReadback(tokenIdentity);
              return issuance.confirmFinalAdmission(
                  exact,
                  finalReadback.admissionAtMillis(),
                  finalReadback.continuityExpiresAtMillis(),
                  confirmationDigest);
            });
    tokenRegistry.requireExactOriginalNewActiveRecord(confirmed);
    if (!confirmationDigest.equals(confirmed.confirmationReadbackSha256())) throw unavailable();
    return new OriginalAdmissionConfirmation(
        confirmed.lineageId(),
        confirmed.leaseId(),
        confirmed.confirmedAdmissionAtMillis(),
        confirmed.confirmedContinuityExpiresAtMillis(),
        confirmed.confirmationReadbackSha256());
  }

  private static ExpectedOriginalAdmission expectedAdmission(
      AccountGameplayAdmissionLeaseEvidence lease, Genesis genesis) {
    Map<?, ?> scope = object(lease.carrier().get("bindingScope"));
    UUID sessionId = uuid(scope.get("sessionId"));
    UUID tenantId = uuid(scope.get("tenantId"));
    return new ExpectedOriginalAdmission(
        genesis.requestId(),
        genesis.accountId(),
        tenantId,
        sessionId,
        genesis.bindingDecisionId(),
        genesis.leaseId(),
        genesis.leaseFence(),
        genesis.originalLeaseEvidenceSha256(),
        genesis.lineageId(),
        genesis.tokenJti(),
        genesis.tokenHash(),
        genesis.tokenGeneration(),
        genesis.issuanceFence(),
        genesis.bindingScopeSha256(),
        sha256(genesis.membershipBaselineCanonicalBytes()),
        sha256(genesis.outboxCheckpointsCanonicalBytes()),
        genesis.rebindHandleSha256());
  }

  private static void requireActivationMatches(
      Genesis genesis,
      AccountGameplayDelegationRedisClient.OriginalNewActivationReadback activation) {
    if (activation == null
        || !genesis.tokenHash().equals(activation.tokenHash())
        || !genesis.accountId().toString().equals(activation.accountId())
        || !genesis.lineageId().toString().equals(activation.lineageId())
        || !genesis.tokenJti().toString().equals(activation.tokenJti())
        || activation.localAofCount() < 1L
        || activation.replicaAofCount() < 1L) throw unavailable();
  }

  private static String confirmationDigest(
      AuthenticatedFinalAdmissionReadback finalReadback,
      AccountGameplayDelegationRedisClient.OriginalNewActivationReadback activation) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("domain", "account-original-new-final-admission-confirmation/v1");
    fields.put("finalReadbackSha256", finalReadback.canonicalReadbackSha256());
    fields.put("admissionAtMs", Long.toString(finalReadback.admissionAtMillis()));
    fields.put("continuityExpiresAtMs", Long.toString(finalReadback.continuityExpiresAtMillis()));
    fields.put("sessionDurationMs", Long.toString(finalReadback.canonicalSessionDurationMillis()));
    fields.put("activeRecordSha256", activation.activeRecordSha256());
    fields.put("redisAbsoluteExpiryMs", Long.toString(activation.absoluteExpiryMillis()));
    return sha256(canonicalJson(fields));
  }

  private Genesis signAndPersistCandidate(
      VerifiedCaller caller,
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      Genesis genesis) {
    Map<String, Object> tuple = stringObjectMap(genesis.authorityTupleCanonicalBytes());
    Map<String, String> membershipVersion =
        stringStringMap(genesis.membershipVersionCanonicalBytes());
    Object tenantValue = objectMap(genesis.bindingScopeCanonicalBytes()).get("tenantId");
    if (!(tenantValue instanceof String tenantId)) throw unavailable();
    AccountAuthoritySnapshot accountAuthority = caller.currentCandidate().authoritySnapshot();
    EvidenceBundleReference evidenceReference = caller.currentCandidate().evidenceBundleReference();

    byte[] handle = new byte[32];
    byte[] compactBytes = null;
    byte[] envelopeBytes = null;
    byte[] recordBytes = null;
    byte[] payload = null;
    try {
      secureRandom.nextBytes(handle);
      try (OriginalNewBoundSignedCandidate signed =
          signer.signOriginalNewBoundCandidate(
              genesis, accountAuthority, tenantId, tuple, membershipVersion)) {
        compactBytes = signed.compactJwtBytes();
        String compactJwt = new String(compactBytes, StandardCharsets.US_ASCII);
        GameSessionAccountDelegationRegistryRecord record =
            GameSessionAccountDelegationRegistryRecord.fromAccountSignedBoundCompactJwt(
                compactJwt,
                signed.signerGeneration(),
                new IssuanceBinding(
                    genesis.lineageId().toString(),
                    genesis.requestId().toString(),
                    genesis.requestDigest(),
                    genesis.accountId().toString()),
                accountAuthority,
                evidenceReference,
                tenantId,
                tuple,
                membershipVersion,
                genesis.issuedAtEpochSecond(),
                GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
        recordBytes =
            record.toCanonicalJsonBytes(
                GameSessionAccountDelegationProfile.MAX_REGISTRY_RECORD_BYTES);
        if (!signed.tokenHash().equals(record.tokenHash())) throw unavailable();
        payload = encodeProtectedResult(compactBytes, handle);
        EncryptedResponseEnvelope envelope =
            responseCryptography.encrypt(
                responseBinding(genesis, lease, receipt),
                payload,
                Instant.ofEpochSecond(genesis.expiresAtEpochSecond()));
        envelopeBytes = envelope.bytes();
        String handleHash = sha256(handle);
        String tokenHash = signed.tokenHash();
        String signerKid = signed.signerKid();
        String signerGeneration = signed.signerGeneration();
        byte[] exactRecord = recordBytes.clone();
        byte[] exactEnvelope = envelopeBytes.clone();
        try {
          return inWritableSerializableTransaction(
              dsl -> {
                SourceRead current = readCurrentOriginalSource(dsl, caller, lease);
                if (current.operation().state() != State.COMMITTED
                    || !current
                        .operation()
                        .bindingDecisionId()
                        .equals(receipt.bindingDecisionId())) {
                  throw unavailable();
                }
                requireExactReceipt(receipt, current.operation(), lease);
                Genesis currentGenesis =
                    new AccountOriginalNewBoundGameplayIssuanceRepository(dsl)
                        .readByLease(leaseId(lease));
                requireSameGenesisIdentity(genesis, currentGenesis);
                return new AccountOriginalNewBoundGameplayIssuanceRepository(dsl)
                    .bindSignedResult(
                        currentGenesis,
                        tokenHash,
                        signerKid,
                        signerGeneration,
                        exactRecord,
                        handleHash,
                        exactEnvelope);
              });
        } finally {
          wipe(exactRecord);
          wipe(exactEnvelope);
        }
      }
    } finally {
      wipe(handle);
      wipe(compactBytes);
      wipe(envelopeBytes);
      wipe(recordBytes);
      wipe(payload);
    }
  }

  private OriginalNewCredential openCommittedResult(
      VerifiedCaller caller,
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      Genesis expectedGenesis) {
    SourceRead source = readCurrentOriginalSource(caller, lease);
    requireExactReceipt(receipt, source.operation(), lease);
    Genesis committed =
        inWritableSerializableTransaction(
            dsl -> {
              SourceRead current = readCurrentOriginalSource(dsl, caller, lease);
              requireSameOriginalOperation(source.operation(), current.operation());
              var issuance = new AccountOriginalNewBoundGameplayIssuanceRepository(dsl);
              Genesis exact = issuance.readByLease(leaseId(lease));
              requireSameGenesisIdentity(expectedGenesis, exact);
              if (exact.status()
                  != AccountOriginalNewBoundGameplayIssuanceRepository.Status.COMMITTED) {
                throw unavailable();
              }
              var tokenIdentity =
                  new TokenIdentity(
                      exact.accountId(),
                      exact.lineageId(),
                      exact.requestId(),
                      exact.tokenHash(),
                      exact.tokenJti(),
                      exact.notBeforeEpochSecond(),
                      exact.tokenGeneration(),
                      exact.issuanceFence(),
                      SourceKind.ORIGINAL_NEW);
              new AccountGameplayTokenIdentityFenceRepository(dsl)
                  .requireActiveReadback(tokenIdentity);
              return exact;
            });

    requireCommittedRegistryProjection(committed);

    return openFrozenCredentialResult(lease, receipt, source, committed);
  }

  /**
   * Opens only the already-frozen signed credential needed to verify Account's actual Game Session
   * final-admission read. This path deliberately does not require or mutate a Redis registry state;
   * the later positive result still requires the authenticated Game Session read and the exact
   * Account-owned pending-to-active CAS.
   */
  private OriginalNewCredential openFrozenCredentialForFinalAdmissionVerification(
      VerifiedCaller caller,
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      Genesis expectedGenesis) {
    SourceRead source = readCurrentOriginalSource(caller, lease);
    requireExactReceipt(receipt, source.operation(), lease);
    Genesis committed =
        inWritableSerializableTransaction(
            dsl -> {
              SourceRead current = readCurrentOriginalSource(dsl, caller, lease);
              requireSameOriginalOperation(source.operation(), current.operation());
              var issuance = new AccountOriginalNewBoundGameplayIssuanceRepository(dsl);
              Genesis exact = issuance.readByLease(leaseId(lease));
              requireSameGenesisIdentity(expectedGenesis, exact);
              if (exact.status()
                  != AccountOriginalNewBoundGameplayIssuanceRepository.Status.COMMITTED) {
                throw unavailable();
              }
              new AccountGameplayTokenIdentityFenceRepository(dsl)
                  .requireActiveReadback(originalNewIdentity(exact));
              return exact;
            });

    return openFrozenCredentialResult(lease, receipt, source, committed);
  }

  private OriginalNewCredential openFrozenCredentialResult(
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      SourceRead source,
      Genesis committed) {

    byte[] plaintext = null;
    try {
      plaintext =
          responseCryptography.decrypt(
              new EncryptedResponseEnvelope(committed.encryptedResultEnvelopeBytes()),
              responseBinding(committed, lease, receipt),
              Instant.ofEpochSecond(committed.expiresAtEpochSecond()));
      OpenedProtectedResult stored = decodeProtectedResult(plaintext);
      byte[] jwtBytes = stored.compactJwtBytes();
      byte[] handle = stored.handleBytes();
      try {
        if (handle.length != 32
            || !sha256(handle).equals(committed.rebindHandleSha256())
            || !sha256(jwtBytes).equals(committed.tokenHash())) {
          throw unavailable();
        }
        String compactJwt = new String(jwtBytes, StandardCharsets.US_ASCII);
        signer.verifyOriginalNewBoundCredential(
            compactJwt,
            committed,
            source.accountAuthority(),
            tenantId(lease).toString(),
            stringObjectMap(committed.authorityTupleCanonicalBytes()),
            stringStringMap(committed.membershipVersionCanonicalBytes()));
        return AccountGameplayAdmissionLeaseGrpcCodec.originalNewCredential(
            compactJwt,
            handle,
            committed.lineageId(),
            committed.tokenJti(),
            committed.tokenHash(),
            committed.issuanceFence(),
            committed.leaseId(),
            committed.leaseFence(),
            receipt.receiptXid(),
            sha256(committed.outboxCheckpointsCanonicalBytes()));
      } finally {
        wipe(handle);
        wipe(jwtBytes);
      }
    } catch (RuntimeException failure) {
      throw unavailable();
    } finally {
      wipe(plaintext);
    }
  }

  private SourceRead readCurrentOriginalSource(
      VerifiedCaller caller, AccountGameplayAdmissionLeaseEvidence lease) {
    return inWritableSerializableTransaction(dsl -> readCurrentOriginalSource(dsl, caller, lease));
  }

  private SourceRead readCurrentOriginalSource(
      DSLContext dsl, VerifiedCaller caller, AccountGameplayAdmissionLeaseEvidence lease) {
    UUID tenantId = tenantId(lease);
    AccountGameplayAdmissionCurrentSources sourceReader =
        AccountGameplayAdmissionCurrentSources.bind(dsl, workloadNamespace);
    var current =
        sourceReader.readCurrentPublicProductionNew(
            tenantId, caller.identity().tokenJti(), caller.currentCandidate());
    current.requireMatchesOriginalLease(lease);
    AccountGameplayAdmissionLeaseOperation operation =
        new AccountGameplayAdmissionLeaseRepository(dsl)
            .readExact(lease)
            .orElseThrow(AccountOriginalNewBoundGameplayCredentialOwner::unavailable);
    return new SourceRead(operation, current.currentCommittedCandidate().authoritySnapshot());
  }

  private Binding responseBinding(
      Genesis genesis,
      AccountGameplayAdmissionLeaseEvidence lease,
      AccountGameplayAdmissionOriginalAckReceipt receipt) {
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("bindingScopeSha256", genesis.bindingScopeSha256());
    evidence.put("leaseEvidenceSha256", genesis.originalLeaseEvidenceSha256());
    evidence.put("ackEvidenceSha256", genesis.originalAckEvidenceSha256());
    evidence.put("ackReceiptXid", genesis.originalAckReceiptXid());
    evidence.put("outboxCheckpoints", checkpointArray(genesis.outboxCheckpointsCanonicalBytes()));
    evidence.put("receiptCommittedBeforeMs", Long.toString(receipt.committedBeforeMs()));
    byte[] evidenceBytes = canonicalJson(evidence);
    try {
      return new Binding(
          ENVELOPE_OPERATION,
          genesis.requestId().toString(),
          HexFormat.of().parseHex(genesis.requestDigest()),
          genesis.callerWorkload(),
          genesis.leaseId().toString(),
          Optional.of(genesis.lineageId().toString()),
          Optional.empty(),
          Optional.of(genesis.leaseId().toString()),
          genesis.issuanceFence(),
          genesis.authorityTupleCanonicalBytes(),
          genesis.membershipVersionCanonicalBytes(),
          evidenceBytes);
    } finally {
      wipe(evidenceBytes);
    }
  }

  private static byte[] encodeProtectedResult(byte[] compactJwt, byte[] handle) {
    if (compactJwt == null
        || compactJwt.length == 0
        || compactJwt.length > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
        || handle == null
        || handle.length != 32) throw unavailable();
    ByteBuffer framed = ByteBuffer.allocate(8 + compactJwt.length + handle.length);
    framed.put(new byte[] {'F', 'O', 'N', '1'});
    framed.putInt(compactJwt.length);
    framed.put(compactJwt);
    framed.put(handle);
    return framed.array();
  }

  private static OpenedProtectedResult decodeProtectedResult(byte[] exactBytes) {
    if (exactBytes == null
        || exactBytes.length < 8 + 1 + 32
        || exactBytes.length > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES + 40) {
      throw unavailable();
    }
    ByteBuffer input = ByteBuffer.wrap(exactBytes);
    if (input.get() != 'F' || input.get() != 'O' || input.get() != 'N' || input.get() != '1') {
      throw unavailable();
    }
    int jwtLength = input.getInt();
    if (jwtLength <= 0
        || jwtLength > GameSessionAccountDelegationProfile.MAX_COMPACT_JWT_BYTES
        || input.remaining() != jwtLength + 32) throw unavailable();
    byte[] jwt = new byte[jwtLength];
    byte[] handle = new byte[32];
    input.get(jwt);
    input.get(handle);
    for (byte value : jwt) {
      if (Byte.toUnsignedInt(value) > 0x7f) {
        wipe(jwt);
        wipe(handle);
        throw unavailable();
      }
    }
    return new OpenedProtectedResult(jwt, handle);
  }

  private static byte[] canonicalJson(Object value) {
    try {
      return Rfc8785CanonicalJson.canonicalizeUtf8(JSON.writeValueAsString(value));
    } catch (Exception malformed) {
      throw unavailable();
    }
  }

  private static Map<String, Object> stringObjectMap(byte[] bytes) {
    return parseJsonMap(bytes);
  }

  private static Map<String, String> stringStringMap(byte[] bytes) {
    Map<?, ?> value = parseJsonMap(bytes);
    Map<String, String> copy = new LinkedHashMap<>();
    value.forEach(
        (key, item) -> {
          if (!(key instanceof String textKey) || !(item instanceof String textValue)) {
            throw unavailable();
          }
          copy.put(textKey, textValue);
        });
    return Map.copyOf(copy);
  }

  private static Map<String, Object> objectMap(byte[] bytes) {
    return parseJsonMap(bytes);
  }

  private static Map<String, Object> parseJsonMap(byte[] bytes) {
    try {
      return JSON.readValue(bytes, Map.class);
    } catch (Exception malformed) {
      throw unavailable();
    }
  }

  private static List<?> checkpointArray(byte[] bytes) {
    try {
      return JSON.readValue(bytes, List.class);
    } catch (Exception malformed) {
      throw unavailable();
    }
  }

  private VerifiedCaller verifyCaller(String compactJwt, GrpcPeerIdentity peer) {
    VerifiedCaller caller;
    try {
      caller = callerVerifier.verifyPresentedCompactToken(compactJwt);
    } catch (RuntimeException failure) {
      throw unavailable();
    }
    if (!peer.uri().equals(caller.identity().callerWorkload())) throw unavailable();
    return caller;
  }

  private void requireCallerMatchesLease(
      VerifiedCaller caller, GrpcPeerIdentity peer, AccountGameplayAdmissionLeaseEvidence lease) {
    Map<String, Object> carrier = lease.carrier();
    Map<?, ?> scope = object(carrier.get("bindingScope"));
    Map<?, ?> token = object(carrier.get("tokenIdentityEvidence"));
    if (lease.leaseKind() != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING
        || !workloadNamespace.equals(carrier.get("targetNamespace"))
        || !peer.uri().equals(carrier.get("callerWorkload"))
        || !peer.uri().equals(caller.identity().callerWorkload())
        || !caller.identity().accountId().toString().equals(scope.get("accountId"))
        || !caller.identity().tokenJti().toString().equals(token.get("tokenJti"))
        || !caller.currentCandidate().tokenSha256().equals(token.get("tokenSHA256"))) {
      throw unavailable();
    }
  }

  private GrpcPeerIdentity requireGameSessionCaller() {
    GrpcPeerIdentity peer = GrpcPeerIdentity.current();
    if (peer == null
        || SessionContext.hasAuthenticatedCallerContext()
        || SessionContext.isInternalService()
        || SessionContext.getServiceName() != null
        || SessionContext.getServiceInstanceId() != null
        || !GAME_SESSION_SERVICE.equals(peer.service())
        || !workloadNamespace.equals(peer.namespace())
        || !("spiffe://firemud/ns/" + workloadNamespace + "/sa/game-session-service")
            .equals(peer.uri())) {
      throw Status.PERMISSION_DENIED
          .withDescription("Exact same-namespace Game Session workload required")
          .asRuntimeException();
    }
    return peer;
  }

  private void requireNoAmbientSql() {
    Object managed =
        Objects.requireNonNull(new DataSourceTransactionManager(dataSource).getDataSource());
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()
        || TransactionSynchronizationManager.hasResource(dataSource)
        || TransactionSynchronizationManager.hasResource(managed)) {
      throw Status.FAILED_PRECONDITION
          .withDescription("Original-new credential owner requires no ambient Account transaction")
          .asRuntimeException();
    }
  }

  private <T> T inWritableSerializableTransaction(
      java.util.function.Function<DSLContext, T> operation) {
    DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
    TransactionTemplate transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    transaction.setReadOnly(false);
    T result =
        transaction.execute(
            status -> {
              if (!status.isNewTransaction()) throw unavailable();
              Object resource =
                  TransactionSynchronizationManager.getResource(
                      Objects.requireNonNull(manager.getDataSource()));
              if (!(resource instanceof ConnectionHolder holder)) throw unavailable();
              var connection = holder.getConnection();
              try {
                if (connection.getAutoCommit()
                    || connection.isReadOnly()
                    || connection.getTransactionIsolation()
                        != java.sql.Connection.TRANSACTION_SERIALIZABLE) throw unavailable();
              } catch (java.sql.SQLException failure) {
                throw unavailable();
              }
              return operation.apply(DSL.using(connection, SQLDialect.POSTGRES));
            });
    return Objects.requireNonNull(result);
  }

  private static byte[] pendingRegistryReceiptBytes(PendingRegistrationReceipt receipt) {
    Map<String, Object> fields = new LinkedHashMap<>();
    fields.put("domain", "account-original-new-pending-registry-receipt/v1");
    fields.put("aclIdentity", receipt.aclIdentity());
    fields.put("operationId", receipt.operationId());
    fields.put("requestId", receipt.requestId());
    fields.put("requestDigest", receipt.requestDigest());
    fields.put("accountId", receipt.accountId());
    fields.put("tokenJti", receipt.tokenJti());
    fields.put("tokenHash", receipt.tokenHash());
    fields.put("recordSha256", receipt.canonicalRecordSha256());
    fields.put("absoluteExpiryMillis", Long.toString(receipt.absoluteExpiryMillis()));
    fields.put("localAofCount", Long.toString(receipt.localAofCount()));
    fields.put("replicaAofCount", Long.toString(receipt.replicaAofCount()));
    fields.put("registryVersion", Long.toString(receipt.registryVersion()));
    return canonicalJson(fields);
  }

  private static String pendingRegistryReceiptDigest(PendingRegistrationReceipt receipt) {
    byte[] proof = pendingRegistryReceiptBytes(Objects.requireNonNull(receipt));
    try {
      return sha256(proof);
    } finally {
      wipe(proof);
    }
  }

  private static void requireExactReceipt(
      AccountGameplayAdmissionOriginalAckReceipt receipt,
      AccountGameplayAdmissionLeaseOperation operation,
      AccountGameplayAdmissionLeaseEvidence lease) {
    if (operation == null
        || receipt == null
        || operation.state() != State.COMMITTED
        || !operation.evidence().hasSameIdentity(lease)
        || !receipt.operation().evidence().hasSameIdentity(lease)
        || !receipt.bindingDecisionId().equals(operation.bindingDecisionId())
        || !receipt.evidenceSha256().equals(lease.sha256())) throw unavailable();
  }

  private static void requireSameOriginalOperation(
      AccountGameplayAdmissionLeaseOperation expected,
      AccountGameplayAdmissionLeaseOperation actual) {
    if (expected == null
        || actual == null
        || expected.state() != State.COMMITTED
        || actual.state() != State.COMMITTED
        || !expected.evidence().hasSameIdentity(actual.evidence())
        || !Objects.equals(expected.bindingDecisionId(), actual.bindingDecisionId()))
      throw unavailable();
  }

  private static void requireSameGenesisIdentity(Genesis expected, Genesis actual) {
    if (expected == null
        || actual == null
        || !expected.lineageId().equals(actual.lineageId())
        || !expected.tokenJti().equals(actual.tokenJti())
        || !expected.requestDigest().equals(actual.requestDigest())
        || !expected.leaseId().equals(actual.leaseId())
        || expected.issuanceFence() != actual.issuanceFence()) throw unavailable();
  }

  private static UUID tenantId(AccountGameplayAdmissionLeaseEvidence lease) {
    Object value = object(lease.carrier().get("bindingScope")).get("tenantId");
    if (!(value instanceof String text)) throw unavailable();
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.version() != 4 || parsed.variant() != 2)
        throw unavailable();
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
  }

  private static UUID leaseId(AccountGameplayAdmissionLeaseEvidence lease) {
    Object value = lease.carrier().get("leaseId");
    if (!(value instanceof String text)) throw unavailable();
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.version() != 4 || parsed.variant() != 2)
        throw unavailable();
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
  }

  private static Map<?, ?> object(Object value) {
    if (!(value instanceof Map<?, ?> map)) throw unavailable();
    return map;
  }

  private static UUID uuid(Object value) {
    if (!(value instanceof String text)) throw unavailable();
    try {
      UUID parsed = UUID.fromString(text);
      if (!parsed.toString().equals(text) || parsed.version() != 4 || parsed.variant() != 2) {
        throw unavailable();
      }
      return parsed;
    } catch (IllegalArgumentException malformed) {
      throw unavailable();
    }
  }

  private static TokenIdentity originalNewIdentity(Genesis genesis) {
    return new TokenIdentity(
        genesis.accountId(),
        genesis.lineageId(),
        genesis.requestId(),
        genesis.tokenHash(),
        genesis.tokenJti(),
        genesis.notBeforeEpochSecond(),
        genesis.tokenGeneration(),
        genesis.issuanceFence(),
        SourceKind.ORIGINAL_NEW);
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException impossible) {
      throw unavailable();
    }
  }

  private static void wipe(byte[] bytes) {
    if (bytes != null) Arrays.fill(bytes, (byte) 0);
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original-new bound gameplay credential is unavailable");
  }

  private record SourceRead(
      AccountGameplayAdmissionLeaseOperation operation, AccountAuthoritySnapshot accountAuthority) {
    SourceRead {
      Objects.requireNonNull(operation);
      Objects.requireNonNull(accountAuthority);
    }
  }

  private record OpenedProtectedResult(byte[] compactJwtBytes, byte[] handleBytes) {}

  /** Actual original-admission anchors returned only by the Account-side confirmation hook. */
  public static final class OriginalAdmissionConfirmation {
    private final UUID lineageId;
    private final UUID originalLeaseId;
    private final long admissionAtMillis;
    private final long continuityExpiresAtMillis;
    private final String authenticatedConfirmationSha256;

    private OriginalAdmissionConfirmation(
        UUID lineageId,
        UUID originalLeaseId,
        long admissionAtMillis,
        long continuityExpiresAtMillis,
        String authenticatedConfirmationSha256) {
      this.lineageId = Objects.requireNonNull(lineageId);
      this.originalLeaseId = Objects.requireNonNull(originalLeaseId);
      if (admissionAtMillis <= 0L || continuityExpiresAtMillis <= admissionAtMillis) {
        throw unavailable();
      }
      this.admissionAtMillis = admissionAtMillis;
      this.continuityExpiresAtMillis = continuityExpiresAtMillis;
      this.authenticatedConfirmationSha256 =
          Objects.requireNonNull(authenticatedConfirmationSha256);
      if (!authenticatedConfirmationSha256.matches("[0-9a-f]{64}")) throw unavailable();
    }

    public UUID lineageId() {
      return lineageId;
    }

    public UUID originalLeaseId() {
      return originalLeaseId;
    }

    public long admissionAtMillis() {
      return admissionAtMillis;
    }

    public long continuityExpiresAtMillis() {
      return continuityExpiresAtMillis;
    }

    public String authenticatedConfirmationSha256() {
      return authenticatedConfirmationSha256;
    }

    @Override
    public String toString() {
      return "OriginalAdmissionConfirmation[redacted]";
    }
  }
}
