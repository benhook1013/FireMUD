package net.firedevops.firemud.gamesession.repository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import org.jooq.DSLContext;

/** Explicit normal-handoff composition; not a receiver, scheduler, reset owner or Account owner. */
final class CanonicalGameplayRegionOwnerTransferCoordinator {
  private final DSLContext dsl;
  private final Clock clock;
  private final CanonicalGameplayRegionOwnerTransferRepository repository;
  private final CanonicalGameplayOriginalNewCapacityExecutionOwner capacity;
  private final CanonicalGameplayProtectedIncarnationReceiver observations;

  CanonicalGameplayRegionOwnerTransferCoordinator(
      DSLContext dsl,
      Clock clock,
      CanonicalGameplayRegionOwnerTransferRepository repository,
      CanonicalGameplayOriginalNewCapacityExecutionOwner capacity,
      CanonicalGameplayProtectedIncarnationReceiver observations) {
    this.dsl = Objects.requireNonNull(dsl);
    this.clock = Objects.requireNonNull(clock);
    this.repository = Objects.requireNonNull(repository);
    this.capacity = Objects.requireNonNull(capacity);
    this.observations = Objects.requireNonNull(observations);
  }

  CanonicalGameplayRegionOwnerTransferRepository.FrozenOriginal frozenOriginal(
      UUID transitionId, long deadline) {
    return repository.within(deadline, ignored -> repository.frozenOriginal(transitionId));
  }

  /** Runs one lease-owner status operation through the same frozen handoff deadline. */
  <T> T withStatusRepository(
      long deadline, Function<CanonicalGameplayRegionRuntimeStatusRepository, T> operation) {
    Objects.requireNonNull(operation, "operation");
    return repository.within(
        deadline,
        budget ->
            operation.apply(new CanonicalGameplayRegionRuntimeStatusRepository(budget.sql(dsl))));
  }

  void requireFrozenOriginal(
      CanonicalGameplayRegionOwnerTransferRepository.FrozenOriginal original,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior,
      long deadline) {
    repository.within(
        deadline,
        ignored -> {
          repository.requireFrozenOriginal(original, source, prior);
          return null;
        });
  }

  UUID resume(
      CanonicalGameplayRegionOwnerTransferRepository.FrozenOriginal original,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior,
      long deadline) {
    return advance(source, prior, deadline, original);
  }

  /** A bounded step; incomplete progress remains durable and the caller must resume this intent. */
  UUID advance(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior,
      long deadline) {
    return advance(source, prior, deadline, null);
  }

  private UUID advance(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior,
      long deadline,
      CanonicalGameplayRegionOwnerTransferRepository.FrozenOriginal original) {
    return repository.within(
        deadline,
        budget -> {
          if (original != null) repository.requireFrozenOriginal(original, source, prior);
          UUID transfer =
              original == null ? repository.begin(source, prior) : original.transferId();
          if (transfer == null) return null;
          var owner = capacity.forTransferBudget(budget);
          for (var item : repository.capture(transfer)) {
            var before = observe(item, budget.absoluteDeadlineMillis());
            var physical = owner.fenceForTransfer(item, budget.absoluteDeadlineMillis());
            var after = observe(item, budget.absoluteDeadlineMillis());
            repository.retainFence(item, physical, before, after);
          }
          // A verification round re-reads every retained marker, including older generations,
          // through bounded restartable pages. It never reinstalls a missing retained fence.
          long cursor = repository.verificationCursor(transfer);
          List<CanonicalGameplayRegionOwnerTransferRepository.Item> page =
              repository.page(transfer, cursor);
          for (var item : page) {
            var before = observe(item, budget.absoluteDeadlineMillis());
            var actual = owner.readTransferFence(item, budget.absoluteDeadlineMillis());
            var after = observe(item, budget.absoluteDeadlineMillis());
            repository.retainVerification(item, actual, before, after);
          }
          if (!repository.finishVerification(transfer)) throw held();
          return transfer;
        });
  }

  CanonicalGameplayRegionRuntimeStatus install(
      UUID transferId,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior,
      String leaseCorrelation,
      String ownerService,
      String ownerInstanceId,
      long deadline) {
    return repository.within(
        deadline,
        budget -> {
          var first = repository.page(transferId, -1);
          if (first.isEmpty()) {
            return new CanonicalGameplayRegionRuntimeStatusRepository(budget.sql(dsl))
                .installOwner(
                    source,
                    prior.regionEpoch(),
                    prior,
                    leaseCorrelation,
                    ownerService,
                    ownerInstanceId,
                    clock.instant());
          }
          var before = observe(first.getFirst(), budget.absoluteDeadlineMillis());
          var owner = capacity.forTransferBudget(budget);
          // Stream the entire frozen inventory through bounded pages under this one absolute
          // operation budget. Earlier page receipts cannot hide a subsequently lost marker.
          // An inventory that cannot be currently proved inside the deadline stays frozen.
          owner.withTransferReadback(
              reader -> {
                var page = first;
                while (!page.isEmpty()) {
                  budget.check();
                  for (var item : page) {
                    var actual = reader.readTransferFence(item, budget.absoluteDeadlineMillis());
                    repository.requireRetainedPhysical(item, actual);
                  }
                  page = repository.page(transferId, page.getLast().ordinal());
                }
              });
          var after = observe(first.getFirst(), budget.absoluteDeadlineMillis());
          var proof =
              repository.qualify(transferId, before, after, budget.absoluteDeadlineMillis());
          var installed =
              new CanonicalGameplayRegionRuntimeStatusRepository(budget.sql(dsl))
                  .installOwner(
                      source,
                      prior.regionEpoch(),
                      prior,
                      leaseCorrelation,
                      ownerService,
                      ownerInstanceId,
                      clock.instant(),
                      proof);
          // Completion grants no historical-fence exemption. The source now independently checks
          // the immutable completed successor against the actual current region owner.
          var completed =
              observations.observeForRegionOwnerTransfer(
                  transferId, first.getFirst().ordinal(), budget.absoluteDeadlineMillis());
          if (!after.incarnationDigest().equals(completed.incarnationDigest())) throw held();
          repository.requireRetainedPhysical(
              first.getFirst(),
              owner.readTransferFence(first.getFirst(), budget.absoluteDeadlineMillis()));
          return installed;
        });
  }

  /** Rechecks the exact completed successor. This path cannot enroll, fence or install. */
  CanonicalGameplayRegionRuntimeStatus recoverCompleted(
      UUID transferId, String ownerInstance, String correlation, long deadline) {
    return repository.within(
        deadline,
        budget -> {
          var completed = repository.completedSuccessor(transferId, ownerInstance, correlation);
          var firstPage = repository.completedPage(transferId, -1);
          if (firstPage.isEmpty()) {
            repository.requireTerminalOnlyCompletion(transferId, completed);
            return completed.status();
          }
          var first = firstPage.getFirst();
          var before = observe(first, budget.absoluteDeadlineMillis());
          if (!before.incarnationDigest().equals(completed.incarnationDigest())) throw held();
          var owner = capacity.forTransferBudget(budget);
          owner.withTransferReadback(
              reader -> {
                var page = firstPage;
                while (!page.isEmpty()) {
                  for (var item : page) {
                    budget.check();
                    repository.requireRetainedPhysical(
                        item, reader.readTransferFence(item, budget.absoluteDeadlineMillis()));
                  }
                  page = repository.completedPage(transferId, page.getLast().ordinal());
                }
              });
          var after = observe(first, budget.absoluteDeadlineMillis());
          if (!after.incarnationDigest().equals(completed.incarnationDigest())
              || !completed.equals(
                  repository.completedSuccessor(transferId, ownerInstance, correlation))) {
            throw held();
          }
          return completed.status();
        });
  }

  private CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation observe(
      CanonicalGameplayRegionOwnerTransferRepository.Item item, long deadline) {
    var value =
        observations.observeForRegionOwnerTransfer(item.transferId(), item.ordinal(), deadline);
    if (!repository
            .readCurrent(item.transferId(), item.ordinal())
            .equals(value.regionOwnerTransferContext())
        || value.ownerKind()
            != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.REGION_OWNER_TRANSFER)
      throw held();
    return value;
  }

  private static IllegalStateException held() {
    return new IllegalStateException(
        "Normal region handoff requires complete current transfer evidence");
  }
}
