package net.firedevops.firemud.accountservice.service;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionAcknowledgmentRepository.Acknowledgment;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.common.account.authority.IssuerAuthorityProjectionV1Codec;
import net.firedevops.firemud.common.account.authority.IssuerAuthorityProjectionV1Codec.Projection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired owner-local durable acknowledgment of an installed issuer projection.
 *
 * <p>The supplied workload identity must come from an authenticated exact-method transport
 * certificate check performed by the caller. Comparing it with the configured identity here is only
 * a binding check and does not authenticate an in-process or caller-asserted value.
 */
public final class AccountIssuerProjectionAcknowledgmentService {
  private static final int MAX_ISSUER_ID_LENGTH = 512;
  private static final int MAX_WORKLOAD_IDENTITY_LENGTH = 512;
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String exactIssuerId;
  private final String exactGameSessionWorkloadIdentity;
  private final AccountIssuerProjectionReconciliationService captureService;
  private final AccountIssuerProjectionAcknowledgmentRepository repository;
  private final TransactionTemplate ownerTransaction;

  public AccountIssuerProjectionAcknowledgmentService(
      String exactIssuerId,
      String exactGameSessionWorkloadIdentity,
      AccountIssuerProjectionReconciliationService captureService,
      AccountIssuerProjectionAcknowledgmentRepository repository,
      PlatformTransactionManager transactionManager) {
    this.exactIssuerId =
        requireConfiguredText(exactIssuerId, "exact Account issuer ID", MAX_ISSUER_ID_LENGTH);
    this.exactGameSessionWorkloadIdentity =
        requireConfiguredText(
            exactGameSessionWorkloadIdentity,
            "exact Game Session workload identity",
            MAX_WORKLOAD_IDENTITY_LENGTH);
    this.captureService =
        Objects.requireNonNull(captureService, "issuer reconciliation capture service is required");
    this.repository =
        Objects.requireNonNull(repository, "issuer acknowledgment repository is required");
    this.ownerTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Records or recovers the durable acknowledgment for one exact captured installation.
   *
   * <p>The capture operation/request/digest values and installed projection JSON are stable retry
   * evidence. The caller must retain and reuse all of them after an uncertain response.
   */
  public Acknowledgment acknowledge(
      String requestedIssuerId,
      String authenticatedWorkloadIdentity,
      UUID captureOperationId,
      UUID captureRequestId,
      int captureRequestDigestVersion,
      String captureRequestDigest,
      String installedProjectionJson) {
    requireExactIssuer(requestedIssuerId);
    requireAuthenticatedWorkloadIdentity(authenticatedWorkloadIdentity);
    requireUuid(captureOperationId, "capture operation ID");
    requireUuid(captureRequestId, "capture request ID");
    requireCaptureRequestDigest(captureRequestDigestVersion, captureRequestDigest);
    requireNoAmbientTransaction();

    byte[] projectionBytes = encodeUtf8(installedProjectionJson);
    Projection projection = IssuerAuthorityProjectionV1Codec.verify(installedProjectionJson);
    String requestDigest =
        Acknowledgment.requestDigestFor(
            exactIssuerId,
            exactGameSessionWorkloadIdentity,
            projectionKey(),
            captureOperationId,
            captureRequestId,
            captureRequestDigestVersion,
            captureRequestDigest,
            installedProjectionJson);

    Acknowledgment transactionResult =
        requireTransactionResult(
            ownerTransaction.execute(
                status ->
                    acknowledgeInOwnerTransaction(
                        authenticatedWorkloadIdentity,
                        captureOperationId,
                        captureRequestId,
                        captureRequestDigestVersion,
                        captureRequestDigest,
                        requestDigest,
                        projectionBytes,
                        projection)));
    Acknowledgment committedResult =
        requireTransactionResult(
            ownerTransaction.execute(
                status ->
                    readCommittedAcknowledgmentInOwnerTransaction(
                        authenticatedWorkloadIdentity,
                        captureOperationId,
                        captureRequestId,
                        captureRequestDigestVersion,
                        captureRequestDigest,
                        requestDigest,
                        projectionBytes,
                        projection)));
    if (!sameAcknowledgment(transactionResult, committedResult)) {
      throw new IllegalStateException(
          "Post-commit issuer installation acknowledgment differs from the owner transaction result");
    }
    return committedResult;
  }

  private Acknowledgment acknowledgeInOwnerTransaction(
      String callerIdentity,
      UUID captureOperationId,
      UUID captureRequestId,
      int captureRequestDigestVersion,
      String captureRequestDigest,
      String requestDigest,
      byte[] projectionBytes,
      Projection projection) {
    AccountIssuerProjectionReconciliationService.CaptureReadback captureReadback =
        captureService.readCapturedForAcknowledgmentInOwnerTransaction(
            exactIssuerId, callerIdentity, captureRequestId);
    Receipt capture = captureReadback.receipt();
    requireCaptureBinding(
        capture,
        callerIdentity,
        captureOperationId,
        captureRequestId,
        captureRequestDigestVersion,
        captureRequestDigest);

    Acknowledgment prior = repository.findByCaptureOperationId(captureOperationId).orElse(null);
    if (prior != null) {
      requireExactRetry(prior, capture, callerIdentity, requestDigest, projectionBytes);
      requireProjectionMatchesCapture(projection, capture);
      return prior;
    }

    requireProjectionMatchesCapture(projection, capture);
    if (!AccountIssuerProjectionReconciliationService.sameSnapshot(
        capture.capturedSource(), captureReadback.currentSnapshot())) {
      throw new IllegalStateException(
          "First issuer installation acknowledgment requires the captured source to remain current");
    }

    Acknowledgment candidate =
        new Acknowledgment(
            UUID.randomUUID(),
            captureOperationId,
            captureRequestId,
            exactIssuerId,
            callerIdentity,
            projectionKey(),
            captureRequestDigestVersion,
            captureRequestDigest,
            Acknowledgment.REQUEST_DIGEST_VERSION,
            requestDigest,
            projectionBytes,
            sha256(projectionBytes));
    Acknowledgment inserted = repository.insert(candidate);
    if (!sameAcknowledgment(candidate, inserted)) {
      throw new IllegalStateException(
          "Inserted issuer installation acknowledgment differs from its candidate");
    }
    Acknowledgment stored =
        repository
            .findByCaptureOperationId(captureOperationId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Issuer installation acknowledgment is missing before transaction commit"));
    if (!sameAcknowledgment(candidate, stored)) {
      throw new IllegalStateException(
          "Transaction-local issuer installation acknowledgment readback differs from insert");
    }
    return stored;
  }

  private Acknowledgment readCommittedAcknowledgmentInOwnerTransaction(
      String callerIdentity,
      UUID captureOperationId,
      UUID captureRequestId,
      int captureRequestDigestVersion,
      String captureRequestDigest,
      String requestDigest,
      byte[] projectionBytes,
      Projection projection) {
    // Retained capture validation runs under a fresh issuer fence before durable ack readback.
    AccountIssuerProjectionReconciliationService.CaptureReadback captureReadback =
        captureService.readCapturedForAcknowledgmentInOwnerTransaction(
            exactIssuerId, callerIdentity, captureRequestId);
    Receipt capture = captureReadback.receipt();
    requireCaptureBinding(
        capture,
        callerIdentity,
        captureOperationId,
        captureRequestId,
        captureRequestDigestVersion,
        captureRequestDigest);
    requireProjectionMatchesCapture(projection, capture);

    Acknowledgment committed =
        repository
            .findByCaptureOperationId(captureOperationId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Post-commit issuer installation acknowledgment readback is missing"));
    requireExactRetry(committed, capture, callerIdentity, requestDigest, projectionBytes);
    return committed;
  }

  private void requireCaptureBinding(
      Receipt capture,
      String callerIdentity,
      UUID captureOperationId,
      UUID captureRequestId,
      int captureRequestDigestVersion,
      String captureRequestDigest) {
    if (!captureOperationId.equals(capture.operationId())
        || !captureRequestId.equals(capture.requestId())
        || !exactIssuerId.equals(capture.issuerId())
        || !callerIdentity.equals(capture.callerWorkloadIdentity())
        || !projectionKey().equals(capture.projectionKey())
        || captureRequestDigestVersion != capture.requestDigestVersion()
        || !captureRequestDigest.equals(capture.requestDigest())) {
      throw new AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException(
          "Issuer installation acknowledgment changed its original capture binding");
    }
  }

  private void requireExactRetry(
      Acknowledgment acknowledgment,
      Receipt capture,
      String callerIdentity,
      String requestDigest,
      byte[] projectionBytes) {
    boolean matches =
        acknowledgment.captureOperationId().equals(capture.operationId())
            && acknowledgment.captureRequestId().equals(capture.requestId())
            && acknowledgment.issuerId().equals(exactIssuerId)
            && acknowledgment.callerWorkloadIdentity().equals(callerIdentity)
            && acknowledgment.projectionKey().equals(projectionKey())
            && acknowledgment.captureRequestDigestVersion() == capture.requestDigestVersion()
            && acknowledgment.captureRequestDigest().equals(capture.requestDigest())
            && acknowledgment.requestDigestVersion() == Acknowledgment.REQUEST_DIGEST_VERSION
            && acknowledgment.requestDigest().equals(requestDigest)
            && MessageDigest.isEqual(acknowledgment.installedProjectionUtf8(), projectionBytes);
    if (!matches) {
      throw new AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException(
          "Issuer installation acknowledgment request identity was reused with changed projection bytes or bindings");
    }
  }

  private void requireProjectionMatchesCapture(Projection projection, Receipt capture) {
    var source = capture.capturedSource();
    if (!exactIssuerId.equals(projection.issuerId())
        || !source.outboxStreamKey().equals(projection.streamKey())
        || !BigInteger.valueOf(source.issuerAuthGeneration()).equals(projection.generation())
        || !BigInteger.valueOf(source.sourceVersion()).equals(projection.sourceVersion())
        || !BigInteger.valueOf(source.outboxSequence()).equals(projection.sequence())
        || source.latestEvent().isPresent() != projection.latestEvent().isPresent()
        || (source.latestEvent().isPresent()
            && !AccountIssuerProjectionReconciliationService.sameEvent(
                source.latestEvent().orElseThrow(), projection.latestEvent().orElseThrow()))) {
      throw new IllegalArgumentException(
          "Installed issuer projection does not exactly match the captured checkpoint and event");
    }
  }

  private String projectionKey() {
    return PROJECTION_KEY_PREFIX + exactIssuerId;
  }

  private void requireExactIssuer(String requestedIssuerId) {
    if (!exactIssuerId.equals(requestedIssuerId)) {
      throw new AccountIssuerAuthorityEventProducer.IssuerMismatchException();
    }
  }

  private void requireAuthenticatedWorkloadIdentity(String identity) {
    if (!exactGameSessionWorkloadIdentity.equals(identity)) {
      throw new SecurityException(
          "Issuer installation acknowledgment requires the exact authenticated Game Session workload identity");
    }
  }

  private static void requireCaptureRequestDigest(int version, String digest) {
    if (version != 1 || digest == null || !digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "Issuer installation acknowledgment requires the exact version-1 capture request digest");
    }
  }

  private static void requireUuid(UUID value, String field) {
    if (value == null || NIL_UUID.equals(value)) {
      throw new IllegalArgumentException(field + " must be a canonical non-nil UUID");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Issuer installation acknowledgment must own its Account transactions without an ambient transaction");
    }
  }

  private static String requireConfiguredText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          field + " must contain 1 to " + maximumLength + " characters");
    }
    return value;
  }

  private static byte[] encodeUtf8(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Installed projection JSON is required");
    }
    try {
      ByteBuffer encoded =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .encode(CharBuffer.wrap(value));
      byte[] result = new byte[encoded.remaining()];
      encoded.get(result);
      if (result.length == 0
          || result.length > Acknowledgment.MAX_INSTALLED_PROJECTION_UTF8_BYTES) {
        throw new IllegalArgumentException(
            "Installed projection JSON must contain 1 to "
                + Acknowledgment.MAX_INSTALLED_PROJECTION_UTF8_BYTES
                + " UTF-8 bytes");
      }
      return result;
    } catch (CharacterCodingException malformed) {
      throw new IllegalArgumentException(
          "Installed projection JSON is not valid Unicode", malformed);
    }
  }

  private static byte[] sha256(byte[] value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value);
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException("SHA-256 is unavailable", unavailable);
    }
  }

  private static boolean sameAcknowledgment(Acknowledgment first, Acknowledgment second) {
    return first != null
        && second != null
        && first.acknowledgmentId().equals(second.acknowledgmentId())
        && first.captureOperationId().equals(second.captureOperationId())
        && first.captureRequestId().equals(second.captureRequestId())
        && first.issuerId().equals(second.issuerId())
        && first.callerWorkloadIdentity().equals(second.callerWorkloadIdentity())
        && first.projectionKey().equals(second.projectionKey())
        && first.captureRequestDigestVersion() == second.captureRequestDigestVersion()
        && first.captureRequestDigest().equals(second.captureRequestDigest())
        && first.requestDigestVersion() == second.requestDigestVersion()
        && first.requestDigest().equals(second.requestDigest())
        && MessageDigest.isEqual(first.installedProjectionUtf8(), second.installedProjectionUtf8())
        && MessageDigest.isEqual(
            first.installedProjectionSha256(), second.installedProjectionSha256());
  }

  private static Acknowledgment requireTransactionResult(Acknowledgment acknowledgment) {
    if (acknowledgment == null) {
      throw new IllegalStateException(
          "Issuer installation acknowledgment transaction returned no result");
    }
    return acknowledgment;
  }
}
