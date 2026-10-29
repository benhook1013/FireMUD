package net.firedevops.firemud.gamesession.repository;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseGrpcCodec;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortOutcome;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortOutcome.State;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.client.AccountGameplayAdmissionLeaseClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered first-NEW Account abort composer over the retained Game Session transition owner.
 *
 * <p>The original transition identifier selects the durable source. Its complete persisted lease is
 * sent to Account for the exact purpose-specific abort. The remote call is always outside SQL; only
 * a typed definitive Account ABORTED result can enter the guarded Game Session CAS.
 */
public final class CanonicalGameplayAdmissionAbortOwner {
  private final CanonicalGameplayBindingInventoryRepository repository;
  private final AccountGameplayAdmissionLeaseClient accountClient;

  CanonicalGameplayAdmissionAbortOwner(
      CanonicalGameplayBindingInventoryRepository repository,
      AccountGameplayAdmissionLeaseClient accountClient) {
    this.repository = Objects.requireNonNull(repository, "Game Session abort source required");
    this.accountClient = Objects.requireNonNull(accountClient, "Account lease client required");
  }

  /**
   * Resolves only one existing first-NEW transition. A pending Account response leaves its original
   * non-admitting obligation intact; a committed response preserves the receipt and does not
   * relabel Game Session state.
   */
  public CanonicalGameplayAdmissionAbortOutcome abortFirstNew(
      UUID transitionId, String presentedInitialGsACompactJwt) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("First-NEW Account abort requires no ambient SQL scope");
    }
    Objects.requireNonNull(transitionId, "transitionId");
    if (presentedInitialGsACompactJwt == null || presentedInitialGsACompactJwt.isBlank()) {
      throw new IllegalArgumentException("Original initial GSA credential is required");
    }

    CanonicalGameplayAdmissionAbortSource source =
        repository.readFirstNewAdmissionAbortRetrySource(transitionId);
    AccountGameplayAdmissionLeaseEvidence originalLease = source.originalDecision().leaseEvidence();
    byte[] originalLeaseBytes =
        source.originalDecision().leaseEvidenceJson().getBytes(StandardCharsets.UTF_8);
    if (!Arrays.equals(
        originalLeaseBytes, originalLease.canonicalJson().getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalStateException("Retained first-NEW lease bytes are not canonical");
    }

    // Account owns definitive abort and performs its own exact Game Session decision read. This
    // RPC must finish before the repository opens its guarded transition transaction.
    AccountGameplayAdmissionLeaseGrpcCodec.OperationEvidence accountOperation =
        accountClient.abort(presentedInitialGsACompactJwt, originalLeaseBytes, transitionId);
    requireExactAccountAbort(source, accountOperation, transitionId);

    if (source.isAccountAborted()) {
      if (accountOperation.state() != AccountGameplayAdmissionLeaseGrpcCodec.State.ABORTED
          || !source.orphanCleanupId().equals(accountOperation.orphanCleanupId())) {
        throw new IllegalStateException("Account replay contradicts the retained definitive abort");
      }
      return new CanonicalGameplayAdmissionAbortOutcome(
          transitionId, State.ABORTED, source.orphanCleanupId(), accountOperation);
    }

    if (accountOperation.state() == AccountGameplayAdmissionLeaseGrpcCodec.State.COMMITTED) {
      return new CanonicalGameplayAdmissionAbortOutcome(
          transitionId, State.ACCOUNT_COMMITTED, null, accountOperation);
    }
    if (accountOperation.state() == AccountGameplayAdmissionLeaseGrpcCodec.State.PENDING) {
      return new CanonicalGameplayAdmissionAbortOutcome(
          transitionId, State.PENDING, null, accountOperation);
    }

    UUID cleanupId = accountOperation.orphanCleanupId();
    CanonicalGameplayAdmissionAbortSource terminalSource =
        repository.recordFirstNewAccountAbort(source, cleanupId);
    if (!terminalSource.isAccountAborted() || !cleanupId.equals(terminalSource.orphanCleanupId())) {
      throw new IllegalStateException("Game Session did not retain Account's exact abort result");
    }
    return new CanonicalGameplayAdmissionAbortOutcome(
        transitionId, State.ABORTED, cleanupId, accountOperation);
  }

  private static void requireExactAccountAbort(
      CanonicalGameplayAdmissionAbortSource source,
      AccountGameplayAdmissionLeaseGrpcCodec.OperationEvidence operation,
      UUID transitionId) {
    AccountGameplayAdmissionLeaseEvidence originalLease = source.originalDecision().leaseEvidence();
    if (!originalLease.hasSameIdentity(operation.originalLeaseEvidence())
        || !source
            .originalDecision()
            .leaseEvidenceJson()
            .equals(operation.originalLeaseEvidence().canonicalJson())
        || operation.originalLeaseEvidence().leaseKind()
            != AccountGameplayAdmissionLeaseEvidence.LeaseKind.NEW_BINDING) {
      throw new IllegalStateException("Account response changed the complete original NEW lease");
    }
    if (operation.bindingDecisionId() != null
        && !transitionId.equals(operation.bindingDecisionId())) {
      throw new IllegalStateException("Account response changed the original transition identity");
    }
    if (operation.state() == AccountGameplayAdmissionLeaseGrpcCodec.State.ABORTED
        && (!operation.matchesAbortTransition(transitionId)
            || operation.orphanCleanupId() == null
            || operation.originalAckReceipt() != null)) {
      throw new IllegalStateException("Account ABORTED result lacks exact cleanup evidence");
    }
    if (operation.state() == AccountGameplayAdmissionLeaseGrpcCodec.State.COMMITTED
        && (operation.bindingDecisionId() == null
            || operation.orphanCleanupId() != null
            || operation.originalAckReceipt() == null)) {
      throw new IllegalStateException("Account COMMITTED result lacks its original ACK receipt");
    }
    if (operation.state() == AccountGameplayAdmissionLeaseGrpcCodec.State.PENDING
        && (operation.bindingDecisionId() != null
            || operation.orphanCleanupId() != null
            || operation.originalAckReceipt() != null)) {
      throw new IllegalStateException("Account PENDING result carries contradictory terminal data");
    }
  }
}
