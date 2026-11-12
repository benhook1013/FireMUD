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
          boolean hasOriginItems = origins.hasItems(transferId);
          if (first.isEmpty() && !hasOriginItems) {
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
          var owner = capacity.forTransferBudget(budget);
          UUID originRound;
          List<CanonicalGameplayCapacityOriginTransferRepository.Item> firstOrigins;
          if (hasOriginItems) {
            // The physical origin custody CAS names this reserved successor.
            repository.prepareSuccessor(transferId, ownerInstanceId, leaseCorrelation);
            // One durable physical-capture page per attempt. A lost reply repeats this exact CAS,
            // retaining the same successor identity and fence, without consulting Account.
            for (var item : origins.unfenced(transferId)) {
              var originBefore = observeOrigin(item, budget.absoluteDeadlineMillis());
              var actual = owner.transferOriginCustody(item, budget.absoluteDeadlineMillis());
              var originAfter = observeOrigin(item, budget.absoluteDeadlineMillis());
              origins.retainFence(
                  item, actual, originBefore, originAfter, budget.absoluteDeadlineMillis());
            }
            originRound = origins.beginVerification(transferId);
            firstOrigins = origins.page(transferId, -1);
          } else {
            originRound = null;
            firstOrigins = List.of();
          }
          return owner.withTransferReadback(
              reader -> {
                long absoluteDeadline = budget.absoluteDeadlineMillis();
                var before =
                    first.isEmpty()
                        ? observeOrigin(firstOrigins.getFirst(), absoluteDeadline)
                        : observe(first.getFirst(), absoluteDeadline);
                // Both inventories use one authenticated non-reconnecting read connection.
                var page = first;
                while (!page.isEmpty()) {
                  budget.check();
                  for (var item : page) {
                    repository.requireRetainedPhysical(
                        item, reader.readTransferFence(item, absoluteDeadline));
                  }
                  page = repository.page(transferId, page.getLast().ordinal());
                }
                var originPage = firstOrigins;
                while (!originPage.isEmpty()) {
                  budget.check();
                  for (var item : originPage) {
                    var originBefore = observeOrigin(item, absoluteDeadline);
                    var actual = reader.readOriginTransferCustody(item, absoluteDeadline);
                    var originAfter = observeOrigin(item, absoluteDeadline);
                    origins.retainVerification(
                        item, originRound, actual, originBefore, originAfter, absoluteDeadline);
                  }
                  originPage = origins.page(transferId, originPage.getLast().ordinal());
                }
                var after =
                    first.isEmpty()
                        ? observeOrigin(firstOrigins.getFirst(), absoluteDeadline)
                        : observe(first.getFirst(), absoluteDeadline);
                var proof =
                    repository.qualify(
                        transferId,
                        before,
                        after,
                        originRound,
                        ownerInstanceId,
                        leaseCorrelation,
                        absoluteDeadline);
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
                var completed =
                    first.isEmpty()
                        ? observeOrigin(firstOrigins.getFirst(), absoluteDeadline)
                        : observe(first.getFirst(), absoluteDeadline);
                if (!after.incarnationDigest().equals(completed.incarnationDigest())) throw held();
                if (first.isEmpty()) {
                  reader.readOriginTransferCustody(firstOrigins.getFirst(), absoluteDeadline);
                } else {
                  repository.requireRetainedPhysical(
                      first.getFirst(),
                      reader.readTransferFence(first.getFirst(), absoluteDeadline));
                }
                return installed;
              });
        });
  }

  /** Rechecks the exact completed successor. This path cannot enroll, fence or install. */
  CanonicalGameplayRegionRuntimeStatus recoverCompleted(
      UUID transferId, String ownerInstance, String correlation, long deadline) {
    return repository.within(
        deadline,
        budget -> {
          long absoluteDeadline = budget.absoluteDeadlineMillis();
          var completed = repository.completedSuccessor(transferId, ownerInstance, correlation);
          var firstPage = repository.completedPage(transferId, -1);
          var firstOrigins = origins.page(transferId, -1);
          if (firstPage.isEmpty() && firstOrigins.isEmpty()) {
            if (origins.hasItems(transferId)) throw held();
            repository.requireTerminalOnlyCompletion(transferId, completed);
            return completed.status();
          }
          var first = firstPage.isEmpty() ? null : firstPage.getFirst();
          var firstOrigin = firstOrigins.isEmpty() ? null : firstOrigins.getFirst();
          var owner = capacity.forTransferBudget(budget);
          return owner.withTransferReadback(
              reader -> {
                var before =
                    first == null
                        ? observeOrigin(firstOrigin, absoluteDeadline)
                        : observe(first, absoluteDeadline);
                if (!before.incarnationDigest().equals(completed.incarnationDigest())) throw held();
                var page = firstPage;
                while (!page.isEmpty()) {
                  for (var item : page) {
                    budget.check();
                    repository.requireRetainedPhysical(
                        item, reader.readTransferFence(item, absoluteDeadline));
                  }
                  page = repository.completedPage(transferId, page.getLast().ordinal());
                }
                var originPage = firstOrigins;
                while (!originPage.isEmpty()) {
                  for (var item : originPage) {
                    budget.check();
                    origins.requireRetainedPhysical(
                        item,
                        reader.readOriginTransferCustody(item, absoluteDeadline),
                        completed.incarnationDigest());
                  }
                  originPage = origins.page(transferId, originPage.getLast().ordinal());
                }
                var after =
                    first == null
                        ? observeOrigin(firstOrigin, absoluteDeadline)
                        : observe(first, absoluteDeadline);
                if (!after.incarnationDigest().equals(completed.incarnationDigest())
                    || !completed.equals(
                        repository.completedSuccessor(transferId, ownerInstance, correlation))) {
                  throw held();
                }
                return completed.status();
              });
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
    if (!origins.requireCurrent(item, deadline).equals(value.originTransferContext())
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
