package net.firedevops.firemud.accountservice.service;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository;
import net.firedevops.firemud.accountservice.repository.AccountIssuerProjectionReconciliationRepository.Receipt;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthorityEventReadback;
import net.firedevops.firemud.accountservice.service.AccountIssuerAuthorityEventProducer.IssuerAuthoritySnapshot;
import net.firedevops.firemud.common.account.authority.IssuerGenerationAuthorityEventV1Codec.IssuerGenerationAuthorityEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Unwired owner-local capture of an immutable issuer source snapshot for later projection repair.
 *
 * <p>The supplied workload identity must come from an authenticated exact-method transport
 * certificate check performed by the caller. Comparing it with the configured identity here is only
 * a binding check and does not authenticate an in-process or caller-asserted value.
 */
public final class AccountIssuerProjectionReconciliationService {
  private static final int MAX_ISSUER_ID_LENGTH = 512;
  private static final int MAX_WORKLOAD_IDENTITY_LENGTH = 512;
  private static final String PROJECTION_KEY_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final UUID NIL_UUID = new UUID(0L, 0L);

  private final String exactIssuerId;
  private final String exactGameSessionWorkloadIdentity;
  private final AccountIssuerAuthorityEventProducer source;
  private final AccountIssuerProjectionReconciliationRepository repository;
  private final TransactionTemplate ownerTransaction;

  public AccountIssuerProjectionReconciliationService(
      String exactIssuerId,
      String exactGameSessionWorkloadIdentity,
      AccountIssuerAuthorityEventProducer source,
      AccountIssuerProjectionReconciliationRepository repository,
      PlatformTransactionManager transactionManager) {
    this.exactIssuerId =
        requireConfiguredText(exactIssuerId, "exact Account issuer ID", MAX_ISSUER_ID_LENGTH);
    this.exactGameSessionWorkloadIdentity =
        requireConfiguredText(
            exactGameSessionWorkloadIdentity,
            "exact Game Session workload identity",
            MAX_WORKLOAD_IDENTITY_LENGTH);
    this.source = Objects.requireNonNull(source, "issuer authority source is required");
    this.repository =
        Objects.requireNonNull(repository, "issuer reconciliation repository is required");
    this.ownerTransaction =
        new TransactionTemplate(
            Objects.requireNonNull(transactionManager, "Account transaction manager is required"));
    this.ownerTransaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.ownerTransaction.setReadOnly(false);
  }

  /**
   * Captures the current exact issuer source or recovers the immutable original receipt.
   *
   * <p>The caller must retain and reuse {@code requestId} after an uncertain response. The
   * authenticated workload identity argument must be provided only after transport authentication;
   * this local method's exact string comparison is not proof of that authentication.
   */
  public Receipt capture(
      String requestedIssuerId, String authenticatedWorkloadIdentity, UUID requestId) {
    requireExactIssuer(requestedIssuerId);
    requireAuthenticatedWorkloadIdentity(authenticatedWorkloadIdentity);
    requireRequestId(requestId);
    requireNoAmbientTransaction();

    Receipt transactionResult =
        requireTransactionResult(
            ownerTransaction.execute(
                status -> captureInOwnerTransaction(authenticatedWorkloadIdentity, requestId)));
    Receipt committedResult =
        requireTransactionResult(
            ownerTransaction.execute(
                status ->
                    readCommittedReceiptInOwnerTransaction(
                        authenticatedWorkloadIdentity, requestId)));
    if (!sameReceipt(transactionResult, committedResult)) {
      throw new IllegalStateException(
          "Post-commit issuer reconciliation receipt differs from the owner transaction result");
    }
    return committedResult;
  }

  private Receipt captureInOwnerTransaction(String callerIdentity, UUID requestId) {
    // This call takes and retains the issuer row fence before receipt lookup or insertion.
    IssuerAuthoritySnapshot current = source.readCurrentForProjectionReconciliation(exactIssuerId);
    Optional<Receipt> prior = repository.findByIssuerAndRequestId(exactIssuerId, requestId);
    if (prior.isPresent()) {
      Receipt recovered = prior.orElseThrow();
      requireOriginalBindings(recovered, callerIdentity, requestId);
      requireRetainedEvidence(recovered, current);
      return recovered;
    }

    String projectionKey = PROJECTION_KEY_PREFIX + exactIssuerId;
    Receipt candidate =
        new Receipt(
            UUID.randomUUID(),
            requestId,
            exactIssuerId,
            callerIdentity,
            projectionKey,
            1,
            Receipt.requestDigestFor(exactIssuerId, callerIdentity, projectionKey, requestId),
            current);
    Receipt inserted = repository.insert(candidate);
    if (!sameReceipt(candidate, inserted)) {
      throw new IllegalStateException(
          "Inserted issuer reconciliation receipt differs from its candidate");
    }
    Receipt stored =
        repository
            .findByIssuerAndRequestId(exactIssuerId, requestId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Issuer reconciliation receipt is missing before transaction commit"));
    if (!sameReceipt(candidate, stored)) {
      throw new IllegalStateException(
          "Transaction-local issuer reconciliation receipt readback differs from insert");
    }
    return stored;
  }

  private Receipt readCommittedReceiptInOwnerTransaction(String callerIdentity, UUID requestId) {
    // Reacquire the issuer fence in a new READ_COMMITTED transaction before reading the receipt.
    IssuerAuthoritySnapshot current = source.readCurrentForProjectionReconciliation(exactIssuerId);
    Receipt committed =
        repository
            .findByIssuerAndRequestId(exactIssuerId, requestId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Post-commit issuer reconciliation receipt readback is missing"));
    requireOriginalBindings(committed, callerIdentity, requestId);
    requireRetainedEvidence(committed, current);
    return committed;
  }

  private void requireOriginalBindings(Receipt receipt, String callerIdentity, UUID requestId) {
    String projectionKey = PROJECTION_KEY_PREFIX + exactIssuerId;
    String expectedDigest =
        Receipt.requestDigestFor(exactIssuerId, callerIdentity, projectionKey, requestId);
    if (!requestId.equals(receipt.requestId())
        || !exactIssuerId.equals(receipt.issuerId())
        || !callerIdentity.equals(receipt.callerWorkloadIdentity())
        || !projectionKey.equals(receipt.projectionKey())
        || receipt.requestDigestVersion() != 1
        || !expectedDigest.equals(receipt.requestDigest())) {
      throw new AccountIssuerProjectionReconciliationRepository.IdempotencyConflictException(
          "Issuer reconciliation request identity was reused with changed caller or projection bindings");
    }
  }

  private void requireRetainedEvidence(Receipt receipt, IssuerAuthoritySnapshot current) {
    IssuerAuthoritySnapshot captured = receipt.capturedSource();
    if (!exactIssuerId.equals(current.issuerId())
        || !exactIssuerId.equals(captured.issuerId())
        || !captured.outboxStreamKey().equals(current.outboxStreamKey())
        || current.issuerAuthGeneration() < captured.issuerAuthGeneration()
        || current.sourceVersion() < captured.sourceVersion()
        || current.outboxSequence() < captured.outboxSequence()) {
      throw new IllegalStateException(
          "Issuer reconciliation receipt source evidence is ahead of or detached from current source");
    }
    if (current.outboxSequence() == captured.outboxSequence()) {
      if (!sameSnapshot(captured, current)) {
        throw new IllegalStateException(
            "Issuer reconciliation receipt contradicts the current source checkpoint");
      }
      return;
    }
    if (captured.outboxSequence() == 0L) {
      // The durable receipt records the proved pristine baseline; later fenced progress is
      // expected.
      return;
    }

    IssuerAuthorityEventReadback historical =
        source.readCommittedEventForProjectionReconciliation(
            exactIssuerId, captured.outboxSequence());
    if (!sameSnapshot(current, historical.currentSnapshot())
        || captured.latestEvent().isEmpty()
        || !sameEvent(captured.latestEvent().orElseThrow(), historical.requestedEvent())) {
      throw new IllegalStateException(
          "Issuer reconciliation receipt's captured event is missing or contradicts retained source evidence");
    }
  }

  private void requireExactIssuer(String requestedIssuerId) {
    if (!exactIssuerId.equals(requestedIssuerId)) {
      throw new AccountIssuerAuthorityEventProducer.IssuerMismatchException();
    }
  }

  private void requireAuthenticatedWorkloadIdentity(String identity) {
    if (!exactGameSessionWorkloadIdentity.equals(identity)) {
      throw new SecurityException(
          "Issuer reconciliation capture requires the exact authenticated Game Session workload identity");
    }
  }

  private static void requireRequestId(UUID requestId) {
    if (requestId == null || NIL_UUID.equals(requestId)) {
      throw new IllegalArgumentException(
          "Issuer reconciliation request ID must be a canonical non-nil UUID");
    }
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Issuer reconciliation capture must own its Account transaction without an ambient transaction");
    }
  }

  private static String requireConfiguredText(String value, String field, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          field + " must contain 1 to " + maximumLength + " characters");
    }
    return value;
  }

  private static boolean sameSnapshot(
      IssuerAuthoritySnapshot first, IssuerAuthoritySnapshot second) {
    if (first == null || second == null) {
      return false;
    }
    if (!first.issuerId().equals(second.issuerId())
        || first.issuerAuthGeneration() != second.issuerAuthGeneration()
        || first.sourceVersion() != second.sourceVersion()
        || !first.outboxStreamKey().equals(second.outboxStreamKey())
        || first.outboxSequence() != second.outboxSequence()
        || first.latestEvent().isPresent() != second.latestEvent().isPresent()) {
      return false;
    }
    return first.latestEvent().isEmpty()
        || sameEvent(first.latestEvent().orElseThrow(), second.latestEvent().orElseThrow());
  }

  private static boolean sameEvent(
      IssuerGenerationAuthorityEvent first, IssuerGenerationAuthorityEvent second) {
    return first != null
        && second != null
        && first.schemaVersion().equals(second.schemaVersion())
        && first.eventType().equals(second.eventType())
        && first.eventId().equals(second.eventId())
        && first.requestId().equals(second.requestId())
        && first.issuerId().equals(second.issuerId())
        && first.sourceScope().equals(second.sourceScope())
        && first.outboxStreamKey().equals(second.outboxStreamKey())
        && first.outboxSequence().equals(second.outboxSequence())
        && first.issuerAuthGeneration().equals(second.issuerAuthGeneration())
        && first.sourceVersion().equals(second.sourceVersion())
        && first.eventDigest().equals(second.eventDigest())
        && MessageDigest.isEqual(first.canonicalJsonUtf8(), second.canonicalJsonUtf8());
  }

  private static boolean sameReceipt(Receipt first, Receipt second) {
    return first != null
        && second != null
        && first.operationId().equals(second.operationId())
        && first.requestId().equals(second.requestId())
        && first.issuerId().equals(second.issuerId())
        && first.callerWorkloadIdentity().equals(second.callerWorkloadIdentity())
        && first.projectionKey().equals(second.projectionKey())
        && first.requestDigestVersion() == second.requestDigestVersion()
        && MessageDigest.isEqual(
            first.requestDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            second.requestDigest().getBytes(java.nio.charset.StandardCharsets.US_ASCII))
        && sameSnapshot(first.capturedSource(), second.capturedSource());
  }

  private static Receipt requireTransactionResult(Receipt receipt) {
    if (receipt == null) {
      throw new IllegalStateException("Issuer reconciliation transaction returned no receipt");
    }
    return receipt;
  }
}
