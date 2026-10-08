package net.firedevops.firemud.gamedesign.publication;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Independent immutable readback boundary. Writes deliberately remain caller-transaction owned so
 * the authenticated coordinator can compose application, visibility and publication atomically. No
 * transport registration, retained-baseline initializer, terminal association or activation exists
 * here.
 */
public final class RealmPolicySourceService {
  private final RealmPolicySourceRepository repository;
  private final TransactionTemplate read;

  public RealmPolicySourceService(
      RealmPolicySourceRepository repository, PlatformTransactionManager transactions) {
    this.repository = Objects.requireNonNull(repository);
    read = new TransactionTemplate(transactions);
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  public Optional<RealmPolicySnapshot> readSnapshot(
      DraftCommitBinding.TargetProof target, UUID commitId) {
    requireIndependent();
    return read.execute(status -> repository.readSnapshot(target, commitId));
  }

  public Optional<RealmPolicySnapshot.Capture> readCapture(
      GameDesignPublicationOperation operation) {
    requireIndependent();
    return read.execute(status -> repository.readCapture(operation));
  }

  public Optional<RealmPolicyGenesis> readGenesis(DraftCommitBinding.TargetProof target) {
    requireIndependent();
    return read.execute(status -> repository.readGenesis(target));
  }

  private static void requireIndependent() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("Independent immutable policy readback required");
    }
  }
}
