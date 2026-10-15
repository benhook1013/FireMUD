package net.firedevops.firemud.accountservice.service;

import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.accountservice.repository.AccountCanonicalRuntimeMembershipSnapshotRepository;
import net.firedevops.firemud.common.account.authority.RuntimeMembershipAuthorityEvidenceValidator.Snapshot;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit transaction shell for the local, unregistered membership evidence reader. */
public final class AccountCanonicalRuntimeMembershipSnapshotService {
  private final AccountCanonicalRuntimeMembershipSnapshotRepository repository;
  private final TransactionTemplate ownerTransaction;

  public AccountCanonicalRuntimeMembershipSnapshotService(
      AccountCanonicalRuntimeMembershipSnapshotRepository repository,
      PlatformTransactionManager transactionManager) {
    this.repository = Objects.requireNonNull(repository);
    this.ownerTransaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
    this.ownerTransaction.setReadOnly(false);
  }

  /** Reads through one fresh writable transaction and returns only validator-supported content. */
  public Snapshot readCurrent(UUID accountUuid, UUID tenantUuid) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Account membership evidence must own its transaction without an ambient transaction");
    }
    return Objects.requireNonNull(
        ownerTransaction.execute(status -> repository.readCurrent(accountUuid, tenantUuid)),
        "Account membership evidence transaction returned no snapshot");
  }
}
