package net.firedevops.firemud.automationscripting.repository;

import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptDeadLetterReplayRequests.SCRIPT_DEAD_LETTER_REPLAY_REQUESTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptDeadLetterReplayResults.SCRIPT_DEAD_LETTER_REPLAY_RESULTS;
import static net.firedevops.firemud.automationscripting.jooq.tables.ScriptWorkItems.SCRIPT_WORK_ITEMS;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.automationscripting.jooq.tables.records.ScriptDeadLetterReplayRequestsRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

/** Durable request/result ledger for operator dead-letter replay. */
@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class ScriptDeadLetterReplayRepository {
  private static final Field<java.time.OffsetDateTime> REQUEST_HOLD_UNTIL =
      field("retention_hold_until", java.time.OffsetDateTime.class);
  private static final Field<java.time.OffsetDateTime> RESULT_HOLD_UNTIL =
      field("retention_hold_until", java.time.OffsetDateTime.class);
  private final DSLContext dsl;

  public ScriptDeadLetterReplayRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  /** Result rows are disposed first so their tenant-qualified FKs cannot pin work items. */
  public long deleteExpiredResults(Instant safeWatermark, Instant now) {
    java.time.OffsetDateTime cutoff = safeWatermark.atOffset(java.time.ZoneOffset.UTC);
    java.time.OffsetDateTime current = now.atOffset(java.time.ZoneOffset.UTC);
    org.jooq.Condition activeParent =
        SCRIPT_WORK_ITEMS
            .TENANT_ID
            .eq(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.TENANT_ID)
            .and(SCRIPT_WORK_ITEMS.ID.eq(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.WORK_ITEM_ID))
            .and(
                // Keep every nonterminal status conservative.  A newly introduced active status
                // must block evidence disposal until this owner explicitly classifies it as
                // terminal, while the established terminal set remains disposable-safe.
                SCRIPT_WORK_ITEMS.STATUS.notIn(
                    AutomationScriptingJooqRepositorySupport.TERMINAL_WORK_ITEM_STATUSES));
    return dsl.deleteFrom(SCRIPT_DEAD_LETTER_REPLAY_RESULTS)
        .where(
            SCRIPT_DEAD_LETTER_REPLAY_RESULTS
                .TENANT_ID
                .isNotNull()
                .and(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.CREATED_AT.lt(cutoff))
                .and(RESULT_HOLD_UNTIL.isNull().or(RESULT_HOLD_UNTIL.le(current)))
                .and(notExists(selectOne().from(SCRIPT_WORK_ITEMS).where(activeParent))))
        .execute();
  }

  /** Completed request receipts are disposed only after every immutable result is gone. */
  public long deleteExpiredRequests(Instant safeWatermark, Instant now) {
    java.time.OffsetDateTime cutoff = safeWatermark.atOffset(java.time.ZoneOffset.UTC);
    java.time.OffsetDateTime current = now.atOffset(java.time.ZoneOffset.UTC);
    org.jooq.Condition resultForRequest =
        SCRIPT_DEAD_LETTER_REPLAY_RESULTS
            .TENANT_ID
            .eq(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.TENANT_ID)
            .and(
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REPLAY_REQUEST_ID.eq(
                    SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.ID));
    return dsl.deleteFrom(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS)
        .where(
            SCRIPT_DEAD_LETTER_REPLAY_REQUESTS
                .TENANT_ID
                .isNotNull()
                .and(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.STATUS.eq("COMPLETED"))
                .and(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.UPDATED_AT.lt(cutoff))
                .and(REQUEST_HOLD_UNTIL.isNull().or(REQUEST_HOLD_UNTIL.le(current)))
                .and(
                    notExists(
                        selectOne()
                            .from(SCRIPT_DEAD_LETTER_REPLAY_RESULTS)
                            .where(resultForRequest))))
        .execute();
  }

  /** Applies or clears the durable owner hold for one replay request. */
  public boolean setRequestRetentionHold(String tenantId, long requestId, Instant holdUntil) {
    return dsl.update(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS)
            .set(
                REQUEST_HOLD_UNTIL,
                holdUntil == null ? null : holdUntil.atOffset(java.time.ZoneOffset.UTC))
            .where(
                SCRIPT_DEAD_LETTER_REPLAY_REQUESTS
                    .ID
                    .eq(requestId)
                    .and(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.TENANT_ID.eq(tenantId)))
            .execute()
        == 1;
  }

  /** Applies or clears the durable owner hold for one replay result. */
  public boolean setResultRetentionHold(String tenantId, long resultId, Instant holdUntil) {
    return dsl.update(SCRIPT_DEAD_LETTER_REPLAY_RESULTS)
            .set(
                RESULT_HOLD_UNTIL,
                holdUntil == null ? null : holdUntil.atOffset(java.time.ZoneOffset.UTC))
            .where(
                SCRIPT_DEAD_LETTER_REPLAY_RESULTS
                    .ID
                    .eq(resultId)
                    .and(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.TENANT_ID.eq(tenantId)))
            .execute()
        == 1;
  }

  public Optional<ReplayRequest> findRequest(String tenantId, String requestId) {
    return dsl.selectFrom(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS)
        .where(
            SCRIPT_DEAD_LETTER_REPLAY_REQUESTS
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.CONTROL_PLANE_REQUEST_ID.eq(requestId)))
        .fetchOptional(this::toRequest);
  }

  public ReplayRequest insertOrGet(
      String tenantId,
      String requestId,
      String fingerprint,
      String actorPrincipal,
      String reason,
      Instant now) {
    ScriptDeadLetterReplayRequestsRecord row = dsl.newRecord(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS);
    row.setTenantId(tenantId);
    row.setControlPlaneRequestId(requestId);
    row.setRequestFingerprint(fingerprint);
    row.setActorPrincipal(actorPrincipal);
    row.setReason(reason);
    row.setStatus("RUNNING");
    row.setReplayedCount(0L);
    row.setRejectedCount(0L);
    row.setCreatedAt(now.atOffset(java.time.ZoneOffset.UTC));
    row.setUpdatedAt(now.atOffset(java.time.ZoneOffset.UTC));
    row.setRowVersion(0);
    return dsl.insertInto(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS)
        .set(row)
        .onConflict(
            SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.TENANT_ID,
            SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.CONTROL_PLANE_REQUEST_ID)
        .doUpdate()
        .set(
            SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.CONTROL_PLANE_REQUEST_ID,
            SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.CONTROL_PLANE_REQUEST_ID)
        .returningResult(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.fields())
        .fetchOne(this::toRequest);
  }

  public void saveResult(
      long requestId,
      long requestedWorkItemId,
      Long workItemId,
      String outcome,
      String rejectionReason,
      String failureReason,
      long scriptPinEpoch,
      long pluginActivationEpoch,
      long lifecycleRevision,
      long failureGeneration,
      Instant now) {
    String tenantId =
        dsl.select(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.TENANT_ID)
            .from(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS)
            .where(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.ID.eq(requestId))
            .fetchOptional(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.TENANT_ID)
            .orElseThrow(
                () -> new IllegalStateException("Replay result request owner is unavailable"));
    dsl.insertInto(SCRIPT_DEAD_LETTER_REPLAY_RESULTS)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.TENANT_ID, tenantId)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REPLAY_REQUEST_ID, requestId)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REQUESTED_WORK_ITEM_ID, requestedWorkItemId)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.WORK_ITEM_ID, workItemId)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.OUTCOME, outcome)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REJECTION_REASON, rejectionReason)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.FAILURE_REASON, failureReason)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.SCRIPT_PIN_EPOCH, scriptPinEpoch)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.PLUGIN_ACTIVATION_EPOCH, pluginActivationEpoch)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.LIFECYCLE_REVISION, lifecycleRevision)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.FAILURE_GENERATION, failureGeneration)
        .set(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.CREATED_AT, now.atOffset(java.time.ZoneOffset.UTC))
        .onConflict(
            SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REPLAY_REQUEST_ID,
            SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REQUESTED_WORK_ITEM_ID)
        // A competing replay may observe a stale work-item state and produce a rejection after the
        // winner has recorded the successful retry. Result rows are immutable evidence; never let
        // that later observer overwrite the first durable outcome.
        .doNothing()
        .execute();
  }

  public List<ReplayItem> findResults(long requestId) {
    return dsl.selectFrom(SCRIPT_DEAD_LETTER_REPLAY_RESULTS)
        .where(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REPLAY_REQUEST_ID.eq(requestId))
        .orderBy(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.ID.asc())
        .fetch(this::toItem);
  }

  public boolean complete(long requestId, long replayedCount, long rejectedCount, Instant now) {
    return dsl.update(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS)
            .set(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.STATUS, "COMPLETED")
            .set(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.REPLAYED_COUNT, replayedCount)
            .set(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.REJECTED_COUNT, rejectedCount)
            .set(
                SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.UPDATED_AT,
                now.atOffset(java.time.ZoneOffset.UTC))
            // A replay request is completed once. A competing worker must not overwrite the
            // aggregate counts established by the first worker after immutable item results exist.
            .where(
                SCRIPT_DEAD_LETTER_REPLAY_REQUESTS
                    .ID
                    .eq(requestId)
                    .and(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.STATUS.eq("RUNNING")))
            .execute()
        == 1;
  }

  private ReplayRequest toRequest(org.jooq.Record record) {
    return new ReplayRequest(
        record.get(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.ID),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.REQUEST_FINGERPRINT),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.STATUS),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.REPLAYED_COUNT),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_REQUESTS.REJECTED_COUNT));
  }

  private ReplayItem toItem(org.jooq.Record record) {
    return new ReplayItem(
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REQUESTED_WORK_ITEM_ID),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.WORK_ITEM_ID),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.OUTCOME),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.REJECTION_REASON),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.FAILURE_REASON),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.SCRIPT_PIN_EPOCH),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.PLUGIN_ACTIVATION_EPOCH),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.LIFECYCLE_REVISION),
        record.get(SCRIPT_DEAD_LETTER_REPLAY_RESULTS.FAILURE_GENERATION));
  }

  public record ReplayRequest(
      long id, String requestFingerprint, String status, long replayedCount, long rejectedCount) {}

  public record ReplayItem(
      long requestedWorkItemId,
      Long workItemId,
      String outcome,
      String rejectionReason,
      String failureReason,
      long scriptPinEpoch,
      long pluginActivationEpoch,
      long lifecycleRevision,
      long failureGeneration) {
    /**
     * Compatibility constructor for callers that use the work-item ID as both result IDs.
     *
     * @param workItemId requested and persisted work-item identifier
     * @param outcome replay outcome
     * @param rejectionReason bounded rejection reason
     * @param scriptPinEpoch script pin evidence
     * @param pluginActivationEpoch plugin activation evidence
     * @param lifecycleRevision plugin lifecycle evidence
     * @param failureGeneration work-item failure generation
     */
    public ReplayItem(
        long workItemId,
        String outcome,
        String rejectionReason,
        long scriptPinEpoch,
        long pluginActivationEpoch,
        long lifecycleRevision,
        long failureGeneration) {
      this(
          workItemId,
          workItemId,
          outcome,
          rejectionReason,
          "",
          scriptPinEpoch,
          pluginActivationEpoch,
          lifecycleRevision,
          failureGeneration);
    }
  }
}
