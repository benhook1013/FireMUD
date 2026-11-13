package net.firedevops.firemud.gamesession.repository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retained normal-handoff custody. It supplies neither an execution outcome nor Account authority.
 */
final class CanonicalGameplayRegionOwnerTransferRepository
    implements CanonicalGameplayRegionOwnerTransferObservationSource {
  static final int PAGE_SIZE = 32;
  private static final String TRANSFER = "game_session_region_owner_transfer";
  private static final String ITEM = "game_session_region_owner_transfer_item";
  // Join predicates prove the four shared identity fields equal; project them only once.
  private static final String CURRENT_PROJECTION =
      "SELECT t.*,r.canonical_region_id,r.tenant_region_tag,r.lifecycle_epoch,"
          + "r.lifecycle_row_version,r.lifecycle_request_bytes,r.capture_id,r.graph_sha256,"
          + "r.preparation_input_digest,r.executor_fence,r.lease_token_correlation,"
          + "r.owner_service,r.owner_instance_id,r.paused,r.last_committed_tick_batch_id,"
          + "r.last_committed_tick_id,r.updated_at FROM ";
  private static final String PENDING =
      " FROM game_session_original_new_capacity_execution e"
          + " JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=e.transition_id"
          + " WHERE b.tenant_id=? AND b.game_instance_id=? AND b.region_id=?"
          + " AND e.status IN ('INSTALL_DISPATCHED','AUTHORIZED')";
  private static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();
  private final DSLContext dsl;
  private final Clock clock;
  private final CanonicalGameplayTenantGameplayTagRepository tags;
  private final ThreadLocal<CanonicalGameplayOriginalNewCapacityExecutionBudget> admitted =
      new ThreadLocal<>();

  CanonicalGameplayRegionOwnerTransferRepository(
      DSLContext dsl, Clock clock, CanonicalGameplayTenantGameplayTagRepository tags) {
    this.dsl = Objects.requireNonNull(dsl);
    this.clock = Objects.requireNonNull(clock);
    this.tags = Objects.requireNonNull(tags);
  }

  <T> T within(
      long deadline, Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> work) {
    noSql();
    if (admitted.get() != null) return work.apply(admitted.get());
    return CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
        clock,
        deadline,
        budget -> {
          admitted.set(budget);
          try {
            return work.apply(budget);
          } finally {
            admitted.remove();
          }
        });
  }

  @Override
  public <T> T withinObservation(
      Clock observationClock,
      long deadline,
      Function<CanonicalGameplayOriginalNewCapacityExecutionBudget, T> observation) {
    return within(deadline, observation);
  }

  DSLContext sql() {
    var budget = admitted.get();
    if (budget == null) throw unavailable();
    return budget.sql(dsl);
  }

  /**
   * Pins the captured original or the exact original whose transition became terminal before
   * capture. Neither path creates a transfer, an inventory item or provisional admission authority.
   */
  FrozenOriginal frozenOriginal(UUID transitionId) {
    noSql();
    return sql()
        .transactionResult(
            configuration -> {
              DSLContext transaction = DSL.using(configuration);
              transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
              return readFrozenOriginal(transaction, transitionId);
            });
  }

  private FrozenOriginal readFrozenOriginal(DSLContext tx, UUID transitionId) {
    var rows =
        tx.fetch(
            "SELECT i.transfer_id,i.target_ordinal,i.execution_digest FROM "
                + ITEM
                + " i JOIN "
                + TRANSFER
                + " t ON t.transfer_id=i.transfer_id"
                + " JOIN game_session_original_new_capacity_execution e ON e.execution_digest=i.execution_digest"
                + " WHERE e.transition_id=? AND t.state='FROZEN' LIMIT 2",
            transitionId);
    if (rows.size() > 1) throw unavailable();
    if (rows.size() == 1) {
      var row = rows.getFirst();
      UUID id = row.get("transfer_id", UUID.class);
      current(tx, id, false);
      var item =
          loadItem(
              tx,
              id,
              row.get("target_ordinal", Long.class),
              row.get("execution_digest", String.class));
      return new FrozenOriginal(id, item.execution().digest(), item.candidate());
    }

    // An Account abort changes the transition, not its capacity outcome. An unresolved execution
    // still belongs to the normal capture and fencing pass when this transfer resumes.
    var terminalRows =
        tx.fetch(
            "SELECT t.transfer_id,e.execution_digest,e.canonical_execution,"
                + "c.original_new_candidate_bytes,c.original_new_candidate_digest"
                + " FROM game_session_canonical_binding_transition c"
                + " JOIN game_session_original_new_capacity_execution e ON e.transition_id=c.transition_id"
                + " AND e.tenant_id=c.tenant_id AND e.request_id=c.admission_request_id"
                + " AND e.admission_id=c.transition_id"
                + " JOIN game_session_canonical_binding_region_bridge_obligation b ON b.transition_id=c.transition_id"
                + " JOIN "
                + TRANSFER
                + " t ON t.tenant_id=b.tenant_id AND t.game_instance_id=b.game_instance_id"
                + " AND t.region_id=b.region_id AND t.region_epoch=b.region_epoch"
                + " AND t.old_owner_id=e.selected_owner_id AND t.old_owner_fence=e.selected_owner_fence"
                + " WHERE c.transition_id=? AND t.state='FROZEN'"
                + " AND c.original_new_candidate_state='READBACK_CONFIRMED'"
                + " AND ((c.status='COMMITTED' AND e.status='COMMITTED'"
                + " AND e.execution_digest=c.original_new_capacity_execution_digest"
                + " AND c.original_new_final_session_sha256 IS NOT NULL)"
                + " OR (c.status='ABORTED' AND c.admission_orphan_cleanup_id IS NOT NULL"
                + " AND c.original_new_final_session_sha256 IS NULL"
                + " AND e.status IN ('INSTALL_DISPATCHED','AUTHORIZED','CLOSED'))) LIMIT 2",
            transitionId);
    if (terminalRows.isEmpty()) return null;
    if (terminalRows.size() != 1) throw unavailable();
    var row = terminalRows.getFirst();
    UUID id = row.get("transfer_id", UUID.class);
    var transfer = current(tx, id, false);
    byte[] candidateBytes = row.get("original_new_candidate_bytes", byte[].class);
    var candidate = CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes);
    var execution = decode(row.get("canonical_execution", byte[].class));
    String candidateDigest = digest(candidateBytes);
    var binding = candidate.bindingIdentity();
    if (!candidate.bindingDecisionId().equals(transitionId)
        || !execution.transitionId().equals(transitionId)
        || !execution.digest().equals(row.get("execution_digest", String.class))
        || !execution.candidateDigest().equals(candidateDigest)
        || !candidateDigest.equals(row.get("original_new_candidate_digest", String.class))
        || !execution
            .request()
            .identity()
            .requestId()
            .toString()
            .equals(candidate.leaseEvidence().carrier().get("requestId"))
        || !execution.selectedOwnerId().equals(transfer.get("old_owner_id", String.class))
        || execution.selectedOwnerFence() != transfer.get("old_owner_fence", Long.class)
        || !binding.tenantId().equals(transfer.get("tenant_id", UUID.class))
        || !binding.gameInstanceId().equals(transfer.get("game_instance_id", UUID.class))
        || !binding.regionId().equals(transfer.get("region_id", UUID.class))
        || binding.regionEpoch().longValueExact() != transfer.get("region_epoch", Long.class))
      throw unavailable();
    requireExecutionPlayerBinding(execution, candidate);
    return new FrozenOriginal(id, execution.digest(), candidate);
  }

  /** Revalidate a pinned continuation without creating a replacement transfer intent. */
  void requireFrozenOriginal(
      FrozenOriginal original,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior) {
    noSql();
    sql()
        .transaction(
            configuration -> {
              var tx = DSL.using(configuration);
              // Bridge retries take inventory before region. Retained tag validation below must
              // join this transaction, so no separate connection waits behind our own clock lock.
              CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(tx);
              requireOldOwner(tx, source, prior);
              var row = current(tx, original.transferId(), true);
              if (!sourceDigest(source, prior).equals(row.get("source_digest", String.class))
                  || !original.equals(
                      readFrozenOriginal(tx, original.candidate().bindingDecisionId())))
                throw unavailable();
            });
  }

  UUID begin(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior) {
    noSql();
    String sourceDigest = sourceDigest(source, prior);
    return sql()
        .transactionResult(
            configuration -> {
              var tx = DSL.using(configuration);
              requireOldOwner(tx, source, prior);
              Record old =
                  tx.fetchOne(
                      "SELECT * FROM " + TRANSFER + " WHERE old_owner_fence=?",
                      prior.executorFence().longValueExact());
              if (old != null) {
                if (!sourceDigest.equals(old.get("source_digest", String.class))
                    || !"FROZEN".equals(old.get("state", String.class))) throw unavailable();
                return old.get("transfer_id", UUID.class);
              }
              if (!exists(
                  tx,
                  "SELECT 1" + PENDING,
                  source.tenantId(),
                  source.gameInstanceId(),
                  source.regionId())) return null;
              UUID id = UUID.randomUUID();
              String intentDigest =
                  digest(bytes("region-owner-transfer/v1/" + id + "/" + sourceDigest));
              if (tx.execute(
                      "INSERT INTO "
                          + TRANSFER
                          + " (transfer_id,tenant_id,game_instance_id,region_id,region_epoch,old_owner_id,old_owner_fence,old_lease_correlation,source_digest,intent_digest,state) VALUES (?,?,?,?,?,?,?,?,?,?,'FROZEN')",
                      id,
                      source.tenantId(),
                      source.gameInstanceId(),
                      source.regionId(),
                      prior.regionEpoch(),
                      prior.ownerInstanceId(),
                      prior.executorFence().longValueExact(),
                      prior.leaseTokenCorrelation(),
                      sourceDigest,
                      intentDigest)
                  != 1) throw unavailable();
              return id;
            });
  }

  /**
   * One bounded enrollment step. The final anti-join, not a cursor or page size, proves
   * completeness.
   */
  List<Item> capture(UUID transferId) {
    noSql();
    sql()
        .transaction(
            configuration -> {
              var tx = DSL.using(configuration);
              Record transfer = current(tx, transferId, true);
              var rows =
                  tx.fetch(
                      "SELECT DISTINCT e.execution_digest"
                          + PENDING
                          + " AND NOT EXISTS (SELECT 1 FROM "
                          + ITEM
                          + " i WHERE i.transfer_id=? AND i.execution_digest=e.execution_digest)"
                          + " ORDER BY e.execution_digest LIMIT ?",
                      transfer.get("tenant_id", UUID.class),
                      transfer.get("game_instance_id", UUID.class),
                      transfer.get("region_id", UUID.class),
                      transferId,
                      PAGE_SIZE);
              long ordinal =
                  tx.fetchSingle(
                          "SELECT coalesce(max(target_ordinal),-1) FROM "
                              + ITEM
                              + " WHERE transfer_id=?",
                          transferId)
                      .get(0, Long.class);
              for (Record row : rows) {
                String executionDigest = row.get("execution_digest", String.class);
                // The actual retained execution and original candidate, never a caller selector.
                Record retained =
                    tx.fetchSingle(
                        "SELECT canonical_execution,tenant_gameplay_tag FROM game_session_original_new_capacity_execution WHERE execution_digest=?",
                        executionDigest);
                var execution = decode(retained.get("canonical_execution", byte[].class));
                ordinal = Math.incrementExact(ordinal);
                String tag = retained.get("tenant_gameplay_tag", String.class);
                String namespace = execution.request().targetNamespace();
                int allocation =
                    tags.requireRetainedAllocation(
                        tx, execution.request().identity().tenantId(), tag, namespace);
                tx.execute(
                    "INSERT INTO "
                        + ITEM
                        + " (transfer_id,target_ordinal,execution_digest,canonical_execution,candidate_digest,tenant_gameplay_tag,workload_namespace,allocation_version) VALUES (?,?,?,?,?,?,?,?)",
                    transferId,
                    ordinal,
                    executionDigest,
                    execution.canonicalBytes(),
                    execution.candidateDigest(),
                    tag,
                    namespace,
                    allocation);
              }
            });
    List<Item> result = new ArrayList<>();
    for (Record row :
        sql()
            .fetch(
                "SELECT target_ordinal,execution_digest FROM "
                    + ITEM
                    + " WHERE transfer_id=? AND fence_readback IS NULL ORDER BY target_ordinal LIMIT ?",
                transferId,
                PAGE_SIZE)) {
      result.add(
          readItem(
              transferId,
              row.get("target_ordinal", Long.class),
              row.get("execution_digest", String.class)));
    }
    return List.copyOf(result);
  }

  List<Item> page(UUID transferId, long afterOrdinal) {
    current(sql(), transferId, false);
    return readPage(transferId, afterOrdinal);
  }

  List<Item> completedPage(UUID transferId, long afterOrdinal) {
    requireCompleted(transferId);
    return readPage(transferId, afterOrdinal);
  }

  CompletedTransfer completedSuccessor(UUID transferId, String ownerInstance, String correlation) {
    Record completed = requireCompleted(transferId);
    var status = CanonicalGameplayRegionRuntimeStatusRepository.toStatus(completed);
    if (status.paused()
        || !ownerInstance.equals(status.ownerInstanceId())
        || !correlation.equals(status.leaseTokenCorrelation())) throw unavailable();
    return new CompletedTransfer(status, completed.get("incarnation_digest", String.class));
  }

  /** Requalifies the existing no-item installation path without synthesizing an observation. */
  void requireTerminalOnlyCompletion(UUID transferId, CompletedTransfer expected) {
    var status = expected.status();
    if (expected.incarnationDigest() != null
        || exists(sql(), "SELECT 1 FROM " + ITEM + " WHERE transfer_id=?", transferId))
      throw unavailable();
    Record retained = requireCompleted(transferId);
    if (retained.get("incarnation_digest") != null
        || !status.equals(CanonicalGameplayRegionRuntimeStatusRepository.toStatus(retained))
        || exists(
            sql(),
            "SELECT 1" + PENDING + " AND e.selected_owner_fence<=?",
            status.tenantId(),
            status.gameInstanceId(),
            status.regionId(),
            retained.get("old_owner_fence", Long.class))) throw unavailable();
  }

  private Record requireCompleted(UUID transferId) {
    Record completed = observationCurrent(sql(), transferId);
    if (!"COMPLETED".equals(completed.get("state", String.class))) throw unavailable();
    return completed;
  }

  record CompletedTransfer(CanonicalGameplayRegionRuntimeStatus status, String incarnationDigest) {}

  private List<Item> readPage(UUID transferId, long afterOrdinal) {
    List<Item> result = new ArrayList<>();
    for (Record row :
        sql()
            .fetch(
                "SELECT target_ordinal,execution_digest FROM "
                    + ITEM
                    + " WHERE transfer_id=? AND target_ordinal>? ORDER BY target_ordinal LIMIT ?",
                transferId,
                afterOrdinal,
                PAGE_SIZE)) {
      result.add(
          readItem(
              transferId,
              row.get("target_ordinal", Long.class),
              row.get("execution_digest", String.class)));
    }
    return List.copyOf(result);
  }

  void retainFence(
      Item item,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.TransferFence actual,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after) {
    var context = readCurrent(item.transferId(), item.ordinal());
    requireObservation(context, before);
    requireObservation(context, after);
    if (!before.incarnationDigest().equals(after.incarnationDigest())
        || !item.execution().digest().equals(actual.executionDigest())) throw unavailable();
    sql()
        .transaction(
            configuration -> {
              var tx = DSL.using(configuration);
              Record transfer = current(tx, item.transferId(), true);
              String retained = transfer.get("incarnation_digest", String.class);
              if (retained != null && !retained.equals(after.incarnationDigest()))
                throw unavailable();
              tx.execute(
                  "UPDATE "
                      + TRANSFER
                      + " SET incarnation_digest=? WHERE transfer_id=? AND incarnation_digest IS NULL",
                  after.incarnationDigest(),
                  item.transferId());
              Record row =
                  tx.fetchSingle(
                      "SELECT * FROM "
                          + ITEM
                          + " WHERE transfer_id=? AND target_ordinal=? FOR UPDATE",
                      item.transferId(),
                      item.ordinal());
              byte[] prior = row.get("fence_readback", byte[].class);
              if (prior != null && !Arrays.equals(prior, actual.canonicalReadback()))
                throw unavailable();
              tx.execute(
                  "UPDATE "
                      + ITEM
                      + " SET fence_readback=?,incarnation_digest=?,before_payload_digest=?,after_payload_digest=? WHERE transfer_id=? AND target_ordinal=? AND fence_readback IS NULL",
                  actual.canonicalReadback(),
                  after.incarnationDigest(),
                  before.payloadDigest(),
                  after.payloadDigest(),
                  item.transferId(),
                  item.ordinal());
            });
  }

  /** Private capability is minted only after a complete current physical verification pass. */
  Completion qualify(
      UUID transferId,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after,
      long deadline) {
    if (clock.millis() >= deadline
        || !before.incarnationDigest().equals(after.incarnationDigest())
        || !"FROZEN".equals(before.regionOwnerTransferContext().controlState())
        || !"FROZEN".equals(after.regionOwnerTransferContext().controlState())) throw unavailable();
    requireObservation(readCurrent(transferId, before.targetOrdinal()), before);
    requireObservation(readCurrent(transferId, after.targetOrdinal()), after);
    Record transfer = current(sql(), transferId, false);
    if (!before.incarnationDigest().equals(transfer.get("incarnation_digest", String.class)))
      throw unavailable();
    UUID round = transfer.get("verification_round", UUID.class);
    if (round == null
        || !Boolean.TRUE.equals(transfer.get("verification_complete", Boolean.class))
        || exists(
            sql(),
            "SELECT 1 FROM " + ITEM + " WHERE transfer_id=? AND verified_round IS DISTINCT FROM ?",
            transferId,
            round)) throw unavailable();
    requireComplete(sql(), transferId);
    long evidenceDeadline =
        Math.min(deadline, Math.min(before.expiresAtEpochMillis(), after.expiresAtEpochMillis()));
    if (clock.millis() >= evidenceDeadline) throw unavailable();
    return new Completion(
        transferId,
        transfer.get("source_digest", String.class),
        after.incarnationDigest(),
        round,
        evidenceDeadline,
        clock);
  }

  long verificationCursor(UUID transferId) {
    Record transfer = current(sql(), transferId, false);
    requireComplete(sql(), transferId);
    if (transfer.get("verification_round") == null) {
      UUID round = UUID.randomUUID();
      sql()
          .execute(
              "UPDATE "
                  + TRANSFER
                  + " SET verification_round=?,verification_cursor=-1,verification_complete=false WHERE transfer_id=? AND verification_round IS NULL",
              round,
              transferId);
      return -1;
    }
    return transfer.get("verification_cursor", Long.class);
  }

  void retainVerification(
      Item item,
      CanonicalGameplayOriginalNewCapacityExecutionOwner.TransferFence actual,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation before,
      CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation after) {
    requireObservation(readCurrent(item.transferId(), item.ordinal()), before);
    requireObservation(readCurrent(item.transferId(), item.ordinal()), after);
    if (!before.incarnationDigest().equals(after.incarnationDigest())) throw unavailable();
    sql()
        .transaction(
            configuration -> {
              var tx = DSL.using(configuration);
              Record transfer = current(tx, item.transferId(), true);
              if (!after
                  .incarnationDigest()
                  .equals(transfer.get("incarnation_digest", String.class))) throw unavailable();
              Record row =
                  tx.fetchSingle(
                      "SELECT fence_readback FROM "
                          + ITEM
                          + " WHERE transfer_id=? AND target_ordinal=?",
                      item.transferId(),
                      item.ordinal());
              if (!Arrays.equals(
                  actual.canonicalReadback(), row.get("fence_readback", byte[].class)))
                throw unavailable();
              tx.execute(
                  "UPDATE "
                      + ITEM
                      + " SET verified_round=? WHERE transfer_id=? AND target_ordinal=?",
                  transfer.get("verification_round", UUID.class),
                  item.transferId(),
                  item.ordinal());
              tx.execute(
                  "UPDATE "
                      + TRANSFER
                      + " SET verification_cursor=? WHERE transfer_id=? AND verification_cursor<?",
                  item.ordinal(),
                  item.transferId(),
                  item.ordinal());
            });
  }

  boolean finishVerification(UUID transferId) {
    Record transfer = current(sql(), transferId, false);
    UUID round = transfer.get("verification_round", UUID.class);
    if (round == null
        || exists(
            sql(),
            "SELECT 1 FROM " + ITEM + " WHERE transfer_id=? AND verified_round IS DISTINCT FROM ?",
            transferId,
            round)) return false;
    sql()
        .execute(
            "UPDATE "
                + TRANSFER
                + " SET verification_complete=true WHERE transfer_id=? AND verification_complete=false",
            transferId);
    return true;
  }

  void requireRetainedPhysical(
      Item item, CanonicalGameplayOriginalNewCapacityExecutionOwner.TransferFence actual) {
    Record row =
        sql()
            .fetchSingle(
                "SELECT fence_readback FROM " + ITEM + " WHERE transfer_id=? AND target_ordinal=?",
                item.transferId(),
                item.ordinal());
    if (!item.execution().digest().equals(actual.executionDigest())
        || !Arrays.equals(actual.canonicalReadback(), row.get("fence_readback", byte[].class)))
      throw unavailable();
  }

  static void requireAndComplete(
      DSLContext tx,
      Completion proof,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior,
      String newOwner,
      long newFence,
      String newLease) {
    if (proof == null || !sourceDigest(source, prior).equals(proof.sourceDigest))
      throw unavailable();
    Record transfer = current(tx, proof.transferId, true);
    if (!proof.incarnationDigest.equals(transfer.get("incarnation_digest", String.class))
        || !proof.round.equals(transfer.get("verification_round", UUID.class))
        || !Boolean.TRUE.equals(transfer.get("verification_complete", Boolean.class)))
      throw unavailable();
    requireComplete(tx, proof.transferId);
    proof.requireFresh();
    if (tx.execute(
            "UPDATE "
                + TRANSFER
                + " SET state='COMPLETED',new_owner_id=?,new_owner_fence=?,new_lease_correlation=? WHERE transfer_id=? AND state='FROZEN'",
            newOwner,
            newFence,
            newLease,
            proof.transferId)
        != 1) throw unavailable();
  }

  static boolean frozen(DSLContext tx, UUID tenant, UUID game, UUID region) {
    return tx.fetchExists(
        DSL.selectOne()
            .from(DSL.table(TRANSFER))
            .where(DSL.field("tenant_id").eq(tenant))
            .and(DSL.field("game_instance_id").eq(game))
            .and(DSL.field("region_id").eq(region))
            .and(DSL.field("state").eq("FROZEN")));
  }

  /** Terminal originals need no new denial; close only the now-empty control intent atomically. */
  static void completeTerminalOnly(
      DSLContext tx,
      CanonicalGameplayRegionRuntimeStatus prior,
      String newOwner,
      long newFence,
      String newLease) {
    if (exists(
        tx, "SELECT 1" + PENDING, prior.tenantId(), prior.gameInstanceId(), prior.regionId()))
      throw unavailable();
    if (tx.execute(
            "UPDATE "
                + TRANSFER
                + " t SET state='COMPLETED',new_owner_id=?,new_owner_fence=?,new_lease_correlation=?"
                + " WHERE t.tenant_id=? AND t.game_instance_id=? AND t.region_id=?"
                + " AND t.old_owner_fence=? AND t.state='FROZEN' AND NOT EXISTS (SELECT 1 FROM "
                + ITEM
                + " i WHERE i.transfer_id=t.transfer_id)",
            newOwner,
            newFence,
            newLease,
            prior.tenantId(),
            prior.gameInstanceId(),
            prior.regionId(),
            prior.executorFence().longValueExact())
        != 1) throw unavailable();
  }

  @Override
  public Context readCurrent(UUID transferId, long targetOrdinal) {
    return readCurrent(transferId, targetOrdinal, Long.MAX_VALUE);
  }

  @Override
  public Context readCurrent(UUID transferId, long targetOrdinal, long deadline) {
    if (admitted.get() == null)
      return within(deadline, ignored -> readCurrent(transferId, targetOrdinal, deadline));
    if (deadline < admitted.get().absoluteDeadlineMillis()) throw unavailable();
    return sql()
        .transactionResult(
            configuration -> {
              DSLContext transaction = DSL.using(configuration);
              transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
              return readCurrent(transaction, transferId, targetOrdinal);
            });
  }

  private Context readCurrent(DSLContext transaction, UUID transferId, long targetOrdinal) {
    Record transfer = observationCurrent(transaction, transferId);
    Record row =
        transaction.fetchOne(
            "SELECT execution_digest FROM " + ITEM + " WHERE transfer_id=? AND target_ordinal=?",
            transferId,
            targetOrdinal);
    if (row == null) throw unavailable();
    Item item =
        loadItem(transaction, transferId, targetOrdinal, row.get("execution_digest", String.class));
    var binding = item.candidate().bindingIdentity();
    return new Context(
        transferId,
        targetOrdinal,
        transfer.get("intent_digest", String.class),
        transfer.get("source_digest", String.class),
        transfer.get("old_owner_id", String.class),
        transfer.get("old_owner_fence", Long.class),
        binding.tenantId(),
        binding.gameInstanceId(),
        binding.regionId(),
        transfer.get("region_epoch", Long.class),
        item.execution().transitionId(),
        item.execution().request().identity().requestId(),
        binding.accountId(),
        binding.playableStateNamespaceId(),
        item.execution().digest(),
        item.execution().candidateDigest(),
        item.assignment().tenantGameplayTag(),
        item.assignment().workloadNamespace(),
        item.assignment().allocationVersion(),
        transfer.get("state", String.class),
        completionDigest(transfer));
  }

  @Override
  public CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
      UUID transitionId, CanonicalGameplayTenantGameplayTagRepository sourceTags) {
    return readAssignment(transitionId, sourceTags, Long.MAX_VALUE);
  }

  @Override
  public CanonicalGameplayTenantGameplayTagRepository.Assignment readAssignment(
      UUID transitionId, CanonicalGameplayTenantGameplayTagRepository sourceTags, long deadline) {
    if (admitted.get() == null)
      return within(deadline, ignored -> readAssignment(transitionId, sourceTags, deadline));
    if (deadline < admitted.get().absoluteDeadlineMillis()) throw unavailable();
    return sourceTags
        .withCapacityExecutionDsl(sql())
        .readAssignmentForRetainedCapacityExecution(transitionId);
  }

  private static Record observationCurrent(DSLContext tx, UUID id) {
    Record row =
        tx.fetchOne(
            CURRENT_PROJECTION
                + TRANSFER
                + " t JOIN game_session_region_runtime_status r ON r.tenant_id=t.tenant_id AND r.game_instance_id=t.game_instance_id AND r.region_id=t.region_id WHERE t.transfer_id=? AND r.region_epoch=t.region_epoch AND ((t.state='FROZEN' AND r.owner_instance_id=t.old_owner_id AND r.executor_fence=t.old_owner_fence) OR (t.state='COMPLETED' AND r.owner_instance_id=t.new_owner_id AND r.executor_fence=t.new_owner_fence AND r.lease_token_correlation=t.new_lease_correlation))",
            id);
    if (row == null) throw unavailable();
    var actual = CanonicalGameplayRegionRuntimeStatusRepository.toStatus(row);
    if (!"game-session-service".equals(actual.ownerService())) throw unavailable();
    String observed =
        sourceDigest(
            actual,
            row.get("old_owner_id", String.class),
            row.get("old_owner_fence", Long.class),
            row.get("old_lease_correlation", String.class));
    if (!observed.equals(row.get("source_digest", String.class))) throw unavailable();
    return row;
  }

  private static String completionDigest(Record transfer) {
    if (!"COMPLETED".equals(transfer.get("state", String.class))) return null;
    return digest(
        bytes(
            transfer.get("transfer_id", UUID.class)
                + "/"
                + transfer.get("new_owner_id", String.class)
                + "/"
                + transfer.get("new_owner_fence", Long.class)
                + "/"
                + transfer.get("new_lease_correlation", String.class)));
  }

  private Item readItem(UUID transferId, long ordinal, String executionDigest) {
    return sql()
        .transactionResult(
            configuration -> {
              DSLContext transaction = DSL.using(configuration);
              transaction.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY");
              return loadItem(transaction, transferId, ordinal, executionDigest);
            });
  }

  private Item loadItem(DSLContext tx, UUID transferId, long ordinal, String executionDigest) {
    Record row =
        tx.fetchOne(
            "SELECT e.*,t.original_new_candidate_bytes FROM game_session_original_new_capacity_execution e JOIN game_session_canonical_binding_transition t ON t.transition_id=e.transition_id WHERE e.execution_digest=?",
            executionDigest);
    if (row == null) throw unavailable();
    var candidate =
        CanonicalGameplayOriginalNewCandidate.parseCanonical(
            row.get("original_new_candidate_bytes", byte[].class));
    var execution = decode(row.get("canonical_execution", byte[].class));
    if (!execution.digest().equals(executionDigest)
        || !execution.transitionId().equals(candidate.bindingDecisionId())
        || !execution.candidateDigest().equals(digest(candidate.canonicalBytes()))
        || !execution.selectedOwnerId().equals(row.get("selected_owner_id", String.class))
        || execution.selectedOwnerFence() != row.get("selected_owner_fence", Long.class)
        || !execution.request().identity().requestId().equals(row.get("request_id", UUID.class))
        || !execution
            .request()
            .admissionAttemptId()
            .equals(row.get("admission_attempt_id", UUID.class))) throw unavailable();
    var assignment = tags.readAssignmentForRetainedCapacityExecution(tx, execution.transitionId());
    if (!assignment.tenantGameplayTag().equals(row.get("tenant_gameplay_tag", String.class)))
      throw unavailable();
    var binding = candidate.bindingIdentity();
    Record transfer = observationCurrent(tx, transferId);
    if (!binding.tenantId().equals(transfer.get("tenant_id", UUID.class))
        || !binding.gameInstanceId().equals(transfer.get("game_instance_id", UUID.class))
        || !binding.regionId().equals(transfer.get("region_id", UUID.class))
        || binding.regionEpoch().longValueExact() != transfer.get("region_epoch", Long.class)
        || execution.selectedOwnerFence() > transfer.get("old_owner_fence", Long.class))
      throw unavailable();
    Record retained =
        tx.fetchOne(
            "SELECT * FROM " + ITEM + " WHERE transfer_id=? AND target_ordinal=?",
            transferId,
            ordinal);
    if (retained == null
        || !executionDigest.equals(retained.get("execution_digest", String.class))
        || !Arrays.equals(
            execution.canonicalBytes(), retained.get("canonical_execution", byte[].class))
        || !execution.candidateDigest().equals(retained.get("candidate_digest", String.class))
        || !assignment.tenantGameplayTag().equals(retained.get("tenant_gameplay_tag", String.class))
        || !assignment.workloadNamespace().equals(retained.get("workload_namespace", String.class))
        || assignment.allocationVersion() != retained.get("allocation_version", Integer.class))
      throw unavailable();
    requireExecutionPlayerBinding(execution, candidate);
    return new Item(transferId, ordinal, execution, candidate, assignment);
  }

  private static void requireExecutionPlayerBinding(
      OriginalNewCapacityExecution execution, CanonicalGameplayOriginalNewCandidate candidate) {
    var player = (TenantCapacityAdmission.PlayerContext) execution.request().context();
    var binding = candidate.bindingIdentity();
    if (!binding.accountId().equals(player.accountId())
        || !binding.tenantId().equals(player.tenantId())
        || !binding.gameInstanceId().equals(player.gameInstanceId())
        || !binding.playableStateNamespaceId().equals(player.playableStateNamespaceId())
        || !binding.characterId().equals(player.characterId())
        || !binding.sessionId().equals(player.sessionId().toString())) throw unavailable();
  }

  private static Record current(DSLContext tx, UUID id, boolean lock) {
    Record row =
        tx.fetchOne(
            CURRENT_PROJECTION
                + TRANSFER
                + " t JOIN game_session_region_runtime_status r ON r.tenant_id=t.tenant_id AND r.game_instance_id=t.game_instance_id AND r.region_id=t.region_id WHERE t.transfer_id=? AND t.state='FROZEN' AND r.owner_instance_id=t.old_owner_id AND r.executor_fence=t.old_owner_fence AND r.region_epoch=t.region_epoch"
                + (lock ? " FOR UPDATE OF r,t" : ""),
            id);
    if (row == null) throw unavailable();
    if (!sourceDigest(CanonicalGameplayRegionRuntimeStatusRepository.toStatus(row))
        .equals(row.get("source_digest", String.class))) throw unavailable();
    return row;
  }

  private static void requireComplete(DSLContext tx, UUID id) {
    Record transfer = current(tx, id, false);
    boolean missing =
        exists(
            tx,
            "SELECT 1"
                + PENDING
                + " AND NOT EXISTS (SELECT 1 FROM "
                + ITEM
                + " i WHERE i.transfer_id=? AND i.execution_digest=e.execution_digest AND i.canonical_execution=e.canonical_execution AND i.fence_readback IS NOT NULL AND i.incarnation_digest=?)",
            transfer.get("tenant_id", UUID.class),
            transfer.get("game_instance_id", UUID.class),
            transfer.get("region_id", UUID.class),
            id,
            transfer.get("incarnation_digest", String.class));
    if (missing) throw unavailable();
  }

  private static void requireOldOwner(
      DSLContext tx,
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior) {
    Record row =
        tx.fetchOne(
            "SELECT * FROM game_session_region_runtime_status WHERE tenant_id=? AND game_instance_id=? AND region_id=? FOR UPDATE",
            source.tenantId(),
            source.gameInstanceId(),
            source.regionId());
    if (row == null
        || !prior.ownerInstanceId().equals(row.get("owner_instance_id", String.class))
        || prior.executorFence().longValueExact() != row.get("executor_fence", Long.class)
        || prior.regionEpoch() != row.get("region_epoch", Long.class)
        || !Arrays.equals(
            prior.lifecycleRequest().canonicalBytes(),
            row.get("lifecycle_request_bytes", byte[].class))
        || !sourceDigest(source, prior)
            .equals(sourceDigest(CanonicalGameplayRegionRuntimeStatusRepository.toStatus(row))))
      throw unavailable();
  }

  static String sourceDigest(
      CanonicalGameplayRegionRuntimeStatusRepository.Source source,
      CanonicalGameplayRegionRuntimeStatus prior) {
    if (!source.tenantId().equals(prior.tenantId())
        || !source.gameInstanceId().equals(prior.gameInstanceId())
        || !source.regionId().equals(prior.regionId())
        || !source.canonicalRegionId().equals(prior.canonicalRegionId())
        || source.lifecycleEpoch() != prior.lifecycleEpoch()
        || source.lifecycleRowVersion() != prior.lifecycleRowVersion()
        || !source.captureId().equals(prior.captureId())
        || !source.graphSha256().equals(prior.graphSha256())
        || !source.preparationInputDigest().equals(prior.preparationInputDigest())
        || !CanonicalGameplayRegionRuntimeStatusRepository.sameLifecycleRequestExceptReadRequestId(
            source.lifecycleRequest(), prior.lifecycleRequest())) throw unavailable();
    return sourceDigest(prior);
  }

  private static String sourceDigest(CanonicalGameplayRegionRuntimeStatus prior) {
    return sourceDigest(
        prior,
        prior.ownerInstanceId(),
        prior.executorFence().longValueExact(),
        prior.leaseTokenCorrelation());
  }

  private static String sourceDigest(
      CanonicalGameplayRegionRuntimeStatus prior, String owner, long fence, String lease) {
    return digest(
        bytes(
            prior.tenantId()
                + "/"
                + prior.gameInstanceId()
                + "/"
                + prior.regionId()
                + "/"
                + prior.canonicalRegionId()
                + "/"
                + prior.lifecycleEpoch()
                + "/"
                + prior.lifecycleRowVersion()
                + "/"
                + Base64.getEncoder().encodeToString(prior.lifecycleRequest().canonicalBytes())
                + "/"
                + prior.captureId()
                + "/"
                + prior.graphSha256()
                + "/"
                + prior.preparationInputDigest()
                + "/"
                + prior.regionEpoch()
                + "/"
                + prior.ownerService()
                + "/"
                + owner
                + "/"
                + fence
                + "/"
                + lease));
  }

  private void requireObservation(
      Context context, CanonicalGameplayProtectedIncarnationReceiver.VerifiedIncarnation value) {
    if (value.ownerKind()
            != CanonicalGameplayProtectedIncarnationReceiver.OwnerKind.REGION_OWNER_TRANSFER
        || !context.equals(value.regionOwnerTransferContext())
        || clock.millis() >= value.expiresAtEpochMillis()) throw unavailable();
  }

  /**
   * Strictly reconstruct the existing retained envelope; canonical re-encoding excludes alternate
   * grammars.
   */
  static OriginalNewCapacityExecution decode(byte[] canonical) {
    try {
      if (canonical == null || canonical.length == 0 || canonical.length > 1048576)
        throw unavailable();
      JsonNode node = JSON.readTree(canonical);
      byte[] payload = Base64.getDecoder().decode(node.get("canonicalAttemptPayload").textValue());
      var request =
          CapacityAdmissionDigest.decodeCommittable(
              payload, node.get("mutationDigest").textValue());
      var identity = request.identity();
      var reservation =
          new TenantCapacityAdmission.Result(
              identity,
              request.admissionAttemptId(),
              request.capacityRearmFence(),
              request.mutationDigest(),
              UUID.fromString(node.get("reservationId").textValue()),
              Long.parseLong(node.get("reservationFence").textValue()),
              Instant.parse(node.get("expiresAt").textValue()),
              TenantCapacityAdmission.State.RESERVED,
              node.get("snapshotIdentity").textValue(),
              node.get("evaluationIdentity").textValue());
      var result =
          new OriginalNewCapacityExecution(
              request,
              reservation,
              UUID.fromString(node.get("transitionId").textValue()),
              node.get("candidateDigest").textValue(),
              node.get("selectedOwnerId").textValue(),
              Long.parseLong(node.get("selectedOwnerFence").textValue()));
      if (!Arrays.equals(canonical, result.canonicalBytes())) throw unavailable();
      return result;
    } catch (RuntimeException invalid) {
      throw unavailable();
    }
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String digest(byte[] value) {
    return "sha256:" + OriginalNewCapacityExecution.sha256(value);
  }

  private static boolean exists(DSLContext tx, String query, Object... bindings) {
    return Boolean.TRUE.equals(
        tx.fetchSingle("SELECT EXISTS (" + query + ")", bindings).get(0, Boolean.class));
  }

  private static void noSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Normal region owner transfer is held");
  }

  record FrozenOriginal(
      UUID transferId, String executionDigest, CanonicalGameplayOriginalNewCandidate candidate) {}

  record Item(
      UUID transferId,
      long ordinal,
      OriginalNewCapacityExecution execution,
      CanonicalGameplayOriginalNewCandidate candidate,
      CanonicalGameplayTenantGameplayTagRepository.Assignment assignment) {}

  static final class Completion {
    private final UUID transferId;
    private final String sourceDigest;
    private final String incarnationDigest;
    private final UUID round;
    private final long deadline;
    private final Clock clock;

    private Completion(
        UUID transferId,
        String sourceDigest,
        String incarnationDigest,
        UUID round,
        long deadline,
        Clock clock) {
      this.transferId = transferId;
      this.sourceDigest = sourceDigest;
      this.incarnationDigest = incarnationDigest;
      this.round = round;
      this.deadline = deadline;
      this.clock = clock;
    }

    void requireFresh() {
      if (clock.millis() >= deadline) throw unavailable();
    }
  }
}
