package net.firedevops.firemud.entitymanagement.service;

import java.util.Objects;
import net.firedevops.firemud.common.world.CanonicalGameplayRosterRecoveryEvidence;
import net.firedevops.firemud.entitymanagement.client.GameSessionCanonicalGameplayRosterOwnerReadClient;
import net.firedevops.firemud.entitymanagement.repository.CanonicalGameplayRosterRetentionRepository;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryRequest;
import net.firedevops.firemud.entitymanagement.v1.CanonicalGameplayRosterRecoveryResponse;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Unregistered exact original-selection closure; the owner read always precedes local SQL. */
public final class CanonicalGameplayRosterRecoveryService {
  private final CanonicalGameplayRosterRetentionRepository repository;
  private final GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient;
  private final TransactionOperations transactions;
  private final CanonicalGameplayRosterRetentionPolicy policy;

  public CanonicalGameplayRosterRecoveryService(
      CanonicalGameplayRosterRetentionRepository repository,
      GameSessionCanonicalGameplayRosterOwnerReadClient ownerClient,
      TransactionOperations transactions,
      CanonicalGameplayRosterRetentionPolicy policy) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.ownerClient = Objects.requireNonNull(ownerClient, "ownerClient");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  public CanonicalGameplayRosterRecoveryResponse recover(
      CanonicalGameplayRosterRecoveryRequest request) {
    CanonicalGameplayRosterRecoveryEvidence.requireRequest(request);
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw new IllegalStateException("CANONICAL_ROSTER_RECOVERY_REQUIRES_NO_AMBIENT_TRANSACTION");
    }
    var owner = ownerClient.getCanonicalGameplayRosterReference(request.getOriginalSelection());
    CanonicalGameplayRosterRecoveryEvidence.requireOwnerRecovery(request, owner);
    return Objects.requireNonNull(
        transactions.execute(ignored -> repository.closeAdmission(request, owner, policy)));
  }
}
