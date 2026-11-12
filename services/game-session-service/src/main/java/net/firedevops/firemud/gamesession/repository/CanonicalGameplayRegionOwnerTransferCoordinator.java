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
  private final CanonicalGameplayProtectedIncarnationReceiver originObservations;
  private final CanonicalGameplayCapacityOriginTransferRepository origins;

  CanonicalGameplayRegionOwnerTransferCoordinator(
      DSLContext dsl,
      Clock clock,
      CanonicalGameplayRegionOwnerTransferRepository repository,
      CanonicalGameplayOriginalNewCapacityExecutionOwner capacity,
      CanonicalGameplayProtectedIncarnationReceiver observations) {
    this(dsl, clock, repository, capacity, observations, null);
  }

  /** Both independently configured observation arms are required when origins are present. */
  CanonicalGameplayRegionOwnerTransferCoordinator(
      DSLContext dsl,
      Clock clock,
      CanonicalGameplayRegionOwnerTransferRepository repository,
      CanonicalGameplayOriginalNewCapacityExecutionOwner capacity,
      CanonicalGameplayProtectedIncarnationReceiver observations,
      CanonicalGameplayProtectedIncarnationReceiver originObservations) {
    this.dsl = Objects.requireNonNull(dsl);
    this.clock = Objects.requireNonNull(clock);
    this.repository = Objects.requireNonNull(repository);
    this.capacity = Objects.requireNonNull(capacity);
    this.observations = Objects.requireNonNull(observations);
    this.originObservations = originObservations;
    this.origins = new CanonicalGameplayCapacityOriginTransferRepository(repository);
  }

  /**
   * Read-only lost-completion gate; it never authorizes another generation or recreates a grant.
   */
  boolean alreadyCompleted(
      CanonicalGameplayRegionRuntimeStatus prior, String requestingOwner, long deadline) {
    return repository.completedForCurrent(prior, requestingOwner, deadline);
  }

  CanonicalGameplayRegionOwnerTransferRepository.Item frozenOriginal(
      UUID transitionId, long deadline) {
    return repository.within(deadline, ignored -> repository.frozenOriginal(transitionId));
  }

  void requireFrozenOriginal(
      CanonicalGameplayRegionOwnerTransferRepository.Item original,
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
      CanonicalGameplayRegionOwnerTransferRepository.Item original,
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
      CanonicalGameplayRegionOwnerTransferRepository.Item original) {
    return repository.within(
        deadline,
        budget -> {
          if (original != null) repository.requireFrozenOriginal(original, source, prior);
          UUID transfer =
              original == null ? repository.begin(source, prior) : original.transferId();
          if (transfer == null) return null;
          origins.capture(transfer);
          origins.requireCaptured(transfer);
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
          origins.requireCaptured(transferId);
          var first = repository.page(transferId, -1);
          if (first.isEmpty() && !origins.hasItems(transferId)) {
            // Only a genuinely empty dual-family inventory may use the terminal-only path.
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
          if (!"game-session-service".equals(ownerService)) throw held();
          repository.prepareSuccessor(transferId, ownerInstanceId, leaseCorrelation);
          var owner = capacity.forTransferBudget(budget);
          // One durable physical-capture page per attempt. A lost reply repeats this exact CAS,
          // retaining the same successor identity and fence, without consulting Account.
          for (var item : origins.unfenced(transferId)) {
            var originBefore = observeOrigin(item, budget.absoluteDeadlineMillis());
            var actual = owner.transferOriginCustody(item, budget.absoluteDeadlineMillis());
            var originAfter = observeOrigin(item, budget.absoluteDeadlineMillis());
            origins.retainFence(item, actual, originBefore, originAfter);
          }
          UUID originRound = origins.beginVerification(transferId);
          var firstOrigins = origins.page(transferId, -1);
          var before =
              first.isEmpty()
                  ? observeOrigin(firstOrigins.getFirst(), budget.absoluteDeadlineMillis())
                  : observe(first.getFirst(), budget.absoluteDeadlineMillis());
          // Current verification re-reads every retained physical fence, through bounded pages
          // under this absolute budget. Earlier receipts never repair or excuse missing markers.
          var page = first;
          while (!page.isEmpty()) {
            budget.check();
            for (var item : page) {
              var actual = owner.readTransferFence(item, budget.absoluteDeadlineMillis());
              repository.requireRetainedPhysical(item, actual);
            }
            page = repository.page(transferId, page.getLast().ordinal());
          }
          var originPage = firstOrigins;
          while (!originPage.isEmpty()) {
            budget.check();
            for (var item : originPage) {
              var originBefore = observeOrigin(item, budget.absoluteDeadlineMillis());
              var actual = owner.readOriginTransferCustody(item, budget.absoluteDeadlineMillis());
              var originAfter = observeOrigin(item, budget.absoluteDeadlineMillis());
              origins.retainVerification(item, originRound, actual, originBefore, originAfter);
            }
            originPage = origins.page(transferId, originPage.getLast().ordinal());
          }
          var after =
              first.isEmpty()
                  ? observeOrigin(firstOrigins.getFirst(), budget.absoluteDeadlineMillis())
                  : observe(first.getFirst(), budget.absoluteDeadlineMillis());
          var proof =
              repository.qualify(
                  transferId, before, after, originRound, budget.absoluteDeadlineMillis());
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
          // The protected source now independently checks the actual installed successor.
          var completed =
              first.isEmpty()
                  ? observeOrigin(firstOrigins.getFirst(), budget.absoluteDeadlineMillis())
                  : observe(first.getFirst(), budget.absoluteDeadlineMillis());
          if (!after.incarnationDigest().equals(completed.incarnationDigest())) throw held();
          if (first.isEmpty())
            owner.readOriginTransferCustody(
                firstOrigins.getFirst(), budget.absoluteDeadlineMillis());
          else owner.readTransferFence(first.getFirst(), budget.absoluteDeadlineMillis());
          return installed;
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

  private CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation observeOrigin(
      CanonicalGameplayCapacityOriginTransferRepository.Item item, long deadline) {
    if (originObservations == null) throw held();
    var value =
        originObservations.observeForOriginTransfer(item.transferId(), item.ordinal(), deadline);
    if (!origins.requireCurrent(item).equals(value.originTransferContext())
        || value.ownerKind()
            != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.ORIGIN_OWNER_TRANSFER)
      throw held();
    return value;
  }

  private static IllegalStateException held() {
    return new IllegalStateException(
        "Normal region handoff requires complete current transfer evidence");
  }
}
