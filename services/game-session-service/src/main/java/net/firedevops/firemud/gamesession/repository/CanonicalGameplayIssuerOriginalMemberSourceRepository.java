package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reloads real original member provenance; never manufactures a registration or ADD. */
final class CanonicalGameplayIssuerOriginalMemberSourceRepository {
  private static final CanonicalGameplayIssuerPartitionRedisSupport REDIS =
      new CanonicalGameplayIssuerPartitionRedisSupport((message, failure) -> unavailable());
  private final DSLContext dsl;
  private final CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource
      currentSource;

  CanonicalGameplayIssuerOriginalMemberSourceRepository(
      DSLContext dsl,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerStateSource currentSource) {
    this.dsl = Objects.requireNonNull(dsl);
    this.currentSource = Objects.requireNonNull(currentSource);
  }

  CanonicalGameplayIssuerOriginalMemberSource read(CanonicalGameplayIssuerCleanupSource source) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) throw unavailable();
    var before = Objects.requireNonNull(currentSource.readFreshCurrentState());
    var original =
        dsl.transactionResult(
            configuration -> {
              var transaction = org.jooq.impl.DSL.using(configuration);
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(transaction);
              var anchor =
                  CanonicalGameplayIssuerBootstrapCoverageRepository
                      .readInstalledAnchorForBindingPreparationInTransaction(transaction, before);
              var local =
                  transaction.fetchOne(
                      "SELECT operation_id FROM game_session_canonical_issuer_binding_local_operation WHERE reservation_id = ?",
                      source.reservation().reservationId());
              var bootstrap =
                  transaction.fetchOne(
                      "SELECT * FROM game_session_canonical_issuer_bootstrap_member_operation WHERE bootstrap_operation_id = ? AND reservation_id = ?",
                      anchor.bootstrapOperationId(),
                      source.reservation().reservationId());
              if (local != null && bootstrap != null) throw unavailable();
              if (local != null) return null;
              if (bootstrap == null || !source.neverAdmitted()) throw unavailable();
              var result = readInstalledInTransaction(transaction, anchor, before, bootstrap);
              if (!result.reservation().owner().equals(source.reservation().owner())
                  || !result
                      .reservation()
                      .bindingGeneration()
                      .equals(source.reservation().bindingGeneration())
                  || !result.transitionId().equals(source.transition().transitionId())
                  || !sameFrozenReservation(result.reservation(), source.reservation()))
                throw unavailable();
              return result;
            });
    var after = Objects.requireNonNull(currentSource.readFreshCurrentState());
    if (original == null) {
      return CanonicalGameplayIssuerOriginalMemberSource.ordinary(
          new CanonicalGameplayIssuerBindingLocalOperationRepository(dsl, currentSource)
              .readOriginalForIssuerRelease(source));
    }
    if (!REDIS.matchesCurrentState(before, original.anchor())
        || !REDIS.matchesCurrentState(after, original.anchor())) throw unavailable();
    return original;
  }

  static CanonicalGameplayIssuerOriginalMemberSource.InstalledBootstrap readInstalledInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current,
      Record row) {
    var builder = new CanonicalGameplayIssuerPartitionBuildRepository(transaction);
    var plan =
        builder.readInstalledPlanForAnchorInTransaction(
            transaction, anchor.bootstrapOperationId(), anchor.bootstrapRequestId());
    return readInstalledInTransaction(transaction, anchor, current, row, plan);
  }

  static CanonicalGameplayIssuerOriginalMemberSource.InstalledBootstrap readInstalledInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current,
      Record row,
      CanonicalGameplayIssuerPartitionBuildPlan plan) {
    return readInstalledInTransaction(
        transaction, anchor, current, row, plan, new InstalledPartitionProofs(transaction, anchor));
  }

  static CanonicalGameplayIssuerOriginalMemberSource.InstalledBootstrap readInstalledInTransaction(
      DSLContext transaction,
      CanonicalGameplayIssuerInstalledAnchor anchor,
      CanonicalGameplayIssuerBootstrapPartitionRedisOwner.CurrentIssuerState current,
      Record row,
      CanonicalGameplayIssuerPartitionBuildPlan plan,
      InstalledPartitionProofs partitionProofs) {
    var builder = new CanonicalGameplayIssuerPartitionBuildRepository(transaction);
    var ordinal = row.get("work_ordinal", Long.class);
    if (ordinal == null) throw unavailable();
    var prepared = builder.readPreparedMemberWorkFromVerifiedPlan(plan, ordinal);
    var operation =
        CanonicalGameplayIssuerBootstrapMemberOperationRepository.loadAndRequireExactOperation(
            row, prepared, current.incarnation());
    if (operation.bindingLifecycle()
            != CanonicalGameplayBindingInventoryEntry.Lifecycle.CANDIDATE_PREPARED
        || operation.expectedBindingPresent()
        || !operation.countsTowardCapacity()
        || operation.memberValue().reservation().lifecycle()
            != CanonicalIssuerPartitionReservation.Lifecycle.RESERVED
        || !plan.buildOperationId().equals(anchor.buildOperationId())
        || !plan.planDigest().equals(anchor.buildPlanDigest())) throw unavailable();
    var fields =
        partitionProofs.fieldsFor(
            transaction, anchor, operation.memberValue().reservation().partitionId());
    var capacity =
        CanonicalGameplayIssuerBootstrapPartitionPhysicalCodec.reservationField(
            operation.memberValue().reservation().reservationId());
    var binding =
        new String(operation.memberValue().owner().bindingRef().bytes(), StandardCharsets.UTF_8);
    if (!Arrays.equals(fields.get(capacity), operation.exactMemberValueBytes())
        || fields.containsKey(binding)) throw unavailable();
    return new CanonicalGameplayIssuerOriginalMemberSource.InstalledBootstrap(operation, anchor);
  }

  /** One locked projection read only; no proof or decoded fields survive that read. */
  static final class InstalledPartitionProofs {
    private final DSLContext transaction;
    private final CanonicalGameplayIssuerInstalledAnchor anchor;
    private final Map<BigInteger, Map<String, byte[]>> fieldsByPartition = new HashMap<>();

    InstalledPartitionProofs(
        DSLContext transaction, CanonicalGameplayIssuerInstalledAnchor anchor) {
      this.transaction = Objects.requireNonNull(transaction);
      this.anchor = Objects.requireNonNull(anchor);
    }

    private Map<String, byte[]> fieldsFor(
        DSLContext expectedTransaction,
        CanonicalGameplayIssuerInstalledAnchor expectedAnchor,
        BigInteger partitionId) {
      if (transaction != expectedTransaction || anchor != expectedAnchor) throw unavailable();
      return fieldsByPartition.computeIfAbsent(
          partitionId,
          id -> {
            var proof =
                CanonicalGameplayIssuerBootstrapCoverageRepository
                    .readInstalledAnchorPartitionProof(transaction, anchor, id);
            return CanonicalGameplayIssuerBootstrapCoverageRepository.decodeFieldSet(
                proof.exactPhysicalFieldSetBytes());
          });
    }
  }

  private static boolean sameFrozenReservation(
      CanonicalIssuerPartitionReservation original, CanonicalIssuerPartitionReservation current) {
    return original.reservationId().equals(current.reservationId())
        && original.transitionId().equals(current.transitionId())
        && original.owner().equals(current.owner())
        && original.bindingGeneration().equals(current.bindingGeneration())
        && original.partitionId().equals(current.partitionId())
        && original.reservationFence().equals(current.reservationFence())
        && original.issuerCoverageOperationId().equals(current.issuerCoverageOperationId())
        && original.issuerCoverageOperationFence().equals(current.issuerCoverageOperationFence())
        && original.coverageFence().equals(current.coverageFence())
        && original.inventorySnapshotRevision().equals(current.inventorySnapshotRevision())
        && current.inventoryRevision().compareTo(original.inventoryRevision()) >= 0;
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original installed issuer member provenance is unavailable");
  }
}
