package net.firedevops.firemud.gamesession.repository;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
      DSLContext dsl, Clock clock, CanonicalGameplayRegionOwnerTransferRepository repository,
      CanonicalGameplayOriginalNewCapacityExecutionOwner capacity,
      CanonicalGameplayProtectedIncarnationReceiver observations) {
    this.dsl=Objects.requireNonNull(dsl);
    this.clock=Objects.requireNonNull(clock);
    this.repository=Objects.requireNonNull(repository);
    this.capacity=Objects.requireNonNull(capacity);
    this.observations=Objects.requireNonNull(observations);
  }

  /** Read-only lost-completion gate; it never authorizes another generation or recreates a grant. */
  boolean alreadyCompleted(CanonicalGameplayRegionRuntimeStatus prior,String requestingOwner,long deadline) {
    return repository.completedForCurrent(prior,requestingOwner,deadline);
  }

  /** A bounded step; incomplete progress remains durable and the caller must resume this intent. */
  UUID advance(CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior, long deadline) {
    return repository.within(deadline, budget -> {
      UUID transfer=repository.begin(source,prior);
      if(transfer==null) return null;
      var owner=capacity.forTransferBudget(budget);
      for (var item:repository.capture(transfer)) {
        var before=observe(item,budget.absoluteDeadlineMillis());
        var physical=owner.fenceForTransfer(item,budget.absoluteDeadlineMillis());
        var after=observe(item,budget.absoluteDeadlineMillis());
        repository.retainFence(item,physical,before,after);
      }
      // A verification round re-reads every retained marker, including older generations,
      // through bounded restartable pages. It never reinstalls a missing retained fence.
      long cursor=repository.verificationCursor(transfer);
      List<CanonicalGameplayRegionOwnerTransferRepository.Item> page=repository.page(transfer,cursor);
      for (var item:page) {
        var before=observe(item,budget.absoluteDeadlineMillis());
        var actual=owner.readTransferFence(item,budget.absoluteDeadlineMillis());
        var after=observe(item,budget.absoluteDeadlineMillis());
        repository.retainVerification(item,actual,before,after);
      }
      if (!repository.finishVerification(transfer)) throw held();
      return transfer;
    });
  }

  CanonicalGameplayRegionRuntimeStatus install(
      UUID transferId, CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior, String leaseCorrelation,
      String ownerService,String ownerInstanceId,long deadline) {
    return repository.within(deadline,budget -> {
      var first=repository.page(transferId,-1);
      if (first.isEmpty()) {
        return new CanonicalGameplayRegionRuntimeStatusRepository(budget.sql(dsl))
            .installOwner(source,prior.regionEpoch(),prior,leaseCorrelation,ownerService,
                ownerInstanceId,clock.instant());
      }
      var before=observe(first.getFirst(),budget.absoluteDeadlineMillis());
      var owner=capacity.forTransferBudget(budget);
      // Stream the entire frozen inventory through bounded pages under this one absolute
      // operation budget. Earlier page receipts cannot hide a subsequently lost marker.
      // An inventory that cannot be currently proved inside the deadline stays frozen.
      var page=first;
      while(!page.isEmpty()) {
        budget.check();
        for(var item:page) {
          var actual=owner.readTransferFence(item,budget.absoluteDeadlineMillis());
          repository.requireRetainedPhysical(item,actual);
        }
        page=repository.page(transferId,page.getLast().ordinal());
      }
      var after=observe(first.getFirst(),budget.absoluteDeadlineMillis());
      var proof=repository.qualify(transferId,before,after,budget.absoluteDeadlineMillis());
      var installed=new CanonicalGameplayRegionRuntimeStatusRepository(budget.sql(dsl))
          .installOwner(source,prior.regionEpoch(),prior,leaseCorrelation,ownerService,
              ownerInstanceId,clock.instant(),proof);
      // Completion grants no historical-fence exemption. The source now independently checks
      // the immutable completed successor against the actual current region owner.
      var completed=observations.observeForRegionOwnerTransfer(
          transferId,first.getFirst().ordinal(),budget.absoluteDeadlineMillis());
      if(!after.incarnationDigest().equals(completed.incarnationDigest())) throw held();
      owner.readTransferFence(first.getFirst(),budget.absoluteDeadlineMillis());
      return installed;
    });
  }

  private CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation observe(
      CanonicalGameplayRegionOwnerTransferRepository.Item item,long deadline) {
    var value=observations.observeForRegionOwnerTransfer(item.transferId(),item.ordinal(),deadline);
    if(!repository.readCurrent(item.transferId(),item.ordinal()).equals(value.regionOwnerTransferContext())
        || value.ownerKind()!=CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.REGION_OWNER_TRANSFER) throw held();
    return value;
  }

  private static IllegalStateException held() {
    return new IllegalStateException("Normal region handoff requires complete current transfer evidence");
  }
}
