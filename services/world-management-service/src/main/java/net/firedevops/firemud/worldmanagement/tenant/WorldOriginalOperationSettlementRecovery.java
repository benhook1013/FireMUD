package net.firedevops.firemud.worldmanagement.tenant;

import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementClient;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementEvidence;
import net.firedevops.firemud.common.account.startsession.AccountStartSessionWorldParticipationSettlementInput;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered exact-original recovery composition. Never creates, aborts, renews or activates a
 * World operation. Missing/PENDING local evidence remains unresolved and is not sent for
 * settlement.
 */
public final class WorldOriginalOperationSettlementRecovery {
  private final WorldCanonicalInstancePreparationService preparation;
  private final AccountStartSessionWorldParticipationSettlementClient account;

  public WorldOriginalOperationSettlementRecovery(
      WorldCanonicalInstancePreparationService preparation,
      AccountStartSessionWorldParticipationSettlementClient account) {
    this.preparation = Objects.requireNonNull(preparation, "preparation");
    this.account = Objects.requireNonNull(account, "account");
  }

  /** Caller retains the same correlation on retries; Account independently reads World evidence. */
  public Optional<AccountStartSessionWorldParticipationSettlementEvidence> recover(
      WorldCanonicalInstanceExecutionIdentity original, UUID settlementRequestId) {
    Objects.requireNonNull(original, "original");
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("World settlement recovery requires no ambient transaction");
    }
    var request =
        new AccountStartSessionWorldParticipationSettlementInput(
            original.targetNamespace(),
            settlementRequestId,
            original.accountWorldParticipationId(),
            original.accountWorldParticipationFence());
    var terminal = preparation.recoverExactTerminal(original);
    if (terminal.isEmpty()) return Optional.empty();
    var receipt = account.settle(request);
    if (receipt == null
        || !request.settlementRequestId().equals(receipt.settlementRequestId())
        || !MessageDigest.isEqual(
            terminal.get().canonicalBytes(), receipt.terminal().canonicalBytes())) {
      throw new IllegalStateException("Account settlement differs from exact World terminal");
    }
    return Optional.of(receipt);
  }
}
