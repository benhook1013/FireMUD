package net.firedevops.firemud.gamedesign.publication;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.authoring.DraftCommitBinding.TargetProof;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Unregistered internal API for binding a typed source capture to a sealed Game Design release. */
public final class RealmPolicyPublicationService {
  private final RealmPolicyPublicationRepository repository;
  private final TransactionTemplate read;

  public RealmPolicyPublicationService(
      RealmPolicyPublicationRepository repository, PlatformTransactionManager transactions) {
    this.repository = Objects.requireNonNull(repository);
    read = new TransactionTemplate(Objects.requireNonNull(transactions));
    read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    read.setReadOnly(true);
  }

  /**
   * Call immediately after the real PUBLISHED operation seal, inside the same owner transaction.
   * The repository rereads the operation and its immutable terminal evidence before writing.
   */
  public Optional<RealmPolicyPublishedEvidence.PublishedSet> retainSealedPublished(
      String publishWorkflowId) {
    return repository.retainSealedPublished(publishWorkflowId);
  }

  /** Independent complete-set read; it never establishes runtime catalog or admission authority. */
  public Optional<RealmPolicyPublishedEvidence.PublishedSet> readPublishedSet(
      UUID canonicalTenantId, UUID canonicalVersionId) {
    requireIndependentRead();
    return read.execute(
        status -> repository.readPublishedSet(canonicalTenantId, canonicalVersionId));
  }

  /** Internal complete-target read for owner composition, never caller-provided numeric aliases. */
  public Optional<RealmPolicyPublishedEvidence.PublishedSet> readPublishedSet(TargetProof target) {
    requireIndependentRead();
    return read.execute(status -> repository.readPublishedSet(target));
  }

  /** Exact selector lookup over the independently verified complete set. */
  public Optional<RealmPolicyPublishedEvidence.Policy> readPublishedPolicy(
      TargetProof target, String worldSlug, String realmSlug) {
    if (!net.firedevops.firemud.common.publication.RealmEntryPolicy.isCanonicalSlug(worldSlug)
        || !net.firedevops.firemud.common.publication.RealmEntryPolicy.isCanonicalSlug(realmSlug)) {
      throw new IllegalArgumentException("Canonical realm policy selectors required");
    }
    return readPublishedSet(target)
        .flatMap(
            set ->
                set.policies().stream()
                    .filter(
                        policy ->
                            policy.source().policy().worldSlug().equals(worldSlug)
                                && policy.source().policy().realmSlug().equals(realmSlug))
                    .findFirst());
  }

  private static void requireIndependentRead() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "Published policy readback requires an independent REPEATABLE_READ transaction");
    }
  }
}
