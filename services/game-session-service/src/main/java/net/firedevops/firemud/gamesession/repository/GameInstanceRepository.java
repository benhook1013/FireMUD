package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameInstances.GAME_INSTANCES;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameInstancesRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.Table;
import org.springframework.stereotype.Repository;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameInstanceRepository {
  private static final Table<Record> SCRIPT_PIN_OPERATION = table(name("script_pin_operation"));
  private static final Field<Long> OP_TENANT_ID = field(name("tenant_id"), Long.class);
  private static final Field<Long> OP_GAME_INSTANCE_ID = field(name("game_instance_id"), Long.class);
  private static final Field<String> OP_REQUEST_ID = field(name("control_plane_request_id"), String.class);
  private static final Field<String> OP_KIND = field(name("operation_kind"), String.class);
  private static final Field<String> OP_TARGET = field(name("target_script_patch_version"), String.class);
  private static final Field<String> OP_EXPECTED_KIND = field(name("expected_pin_kind"), String.class);
  private static final Field<Long> OP_EXPECTED_EPOCH = field(name("expected_script_pin_epoch"), Long.class);
  private static final Field<String> OP_ACTOR = field(name("actor_principal"), String.class);
  private static final Field<String> OP_REASON = field(name("reason"), String.class);
  private static final Field<String> OP_DIGEST = field(name("mutation_digest"), String.class);
  private static final Field<String> OP_OUTCOME = field(name("outcome"), String.class);
  private static final Field<String> OP_ERROR_CODE = field(name("error_code"), String.class);
  private static final Field<String> OP_PREVIOUS_PATCH = field(name("previous_script_patch_version"), String.class);
  private static final Field<Long> OP_PREVIOUS_EPOCH = field(name("previous_script_pin_epoch"), Long.class);
  private static final Field<String> OP_RESULTING_PATCH = field(name("resulting_script_patch_version"), String.class);
  private static final Field<Long> OP_RESULTING_EPOCH = field(name("resulting_script_pin_epoch"), Long.class);
  private static final Field<?>[] SELECT_FIELDS = {
    GAME_INSTANCES.ID,
    GAME_INSTANCES.TENANT_ID,
    GAME_INSTANCES.RUNTIME_VERSION,
    GAME_INSTANCES.SCRIPT_PATCH_VERSION,
    GAME_INSTANCES.SCRIPT_PIN_EPOCH,
    GAME_INSTANCES.GAME_TEMPLATE_ID,
    GAME_INSTANCES.LAUNCH_DESCRIPTOR_ID,
    GAME_INSTANCES.VERSION_ID,
    GAME_INSTANCES.RELEASE_BUNDLE_ID,
    GAME_INSTANCES.VERSION_STATE_EPOCH,
    GAME_INSTANCES.GENERATION_CONFIG_REVISION,
    GAME_INSTANCES.REMAP_SET_ID,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON,
    GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID,
    GAME_INSTANCES.OWNER_ACCOUNT_ID,
    GAME_INSTANCES.STATUS,
    GAME_INSTANCES.ROW_VERSION
  };

  private final DSLContext dsl;

  public GameInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<GameInstance> findById(Long id) {
    return selectGameInstances().where(GAME_INSTANCES.ID.eq(id)).fetchOptional(this::toEntity);
  }

  public long count() {
    return dsl.fetchCount(GAME_INSTANCES);
  }

  public List<GameInstance> findAll() {
    return selectGameInstances().orderBy(GAME_INSTANCES.ID.asc()).fetch(this::toEntity);
  }

  public Optional<GameInstance> findFirstByTenantIdAndOwnerAccountIdAndStatus(
      Long tenantId, Long ownerAccountId, String status) {
    return selectGameInstances()
        .where(
            GAME_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(GAME_INSTANCES.OWNER_ACCOUNT_ID.eq(ownerAccountId))
                .and(GAME_INSTANCES.STATUS.eq(status)))
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  public List<GameInstance> findByStatus(String status) {
    return selectGameInstances()
        .where(GAME_INSTANCES.STATUS.eq(status))
        .orderBy(GAME_INSTANCES.ID.asc())
        .fetch(this::toEntity);
  }

  public List<GameInstance> findByTenantIdAndOwnerAccountIdInAndStatus(
      Long tenantId, Collection<Long> ownerAccountIds, String status) {
    if (ownerAccountIds == null || ownerAccountIds.isEmpty()) {
      return List.of();
    }
    return selectGameInstances()
        .where(
            GAME_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(GAME_INSTANCES.OWNER_ACCOUNT_ID.in(ownerAccountIds))
                .and(GAME_INSTANCES.STATUS.eq(status)))
        .orderBy(GAME_INSTANCES.ID.asc())
        .fetch(this::toEntity);
  }

  public GameInstance save(GameInstance entity) {
    if (entity.getId() == null) {
      GameInstancesRecord record = dsl.newRecord(GAME_INSTANCES);
      populate(record, entity);
      long initialRowVersion = entity.getRowVersion() == null ? 0L : entity.getRowVersion();
      record.setRowVersion(initialRowVersion);
      record.store();
      return findById(record.getId()).orElseThrow();
    }

    long currentRowVersion = entity.getRowVersion() == null ? 0L : entity.getRowVersion();
    long nextRowVersion = currentRowVersion + 1L;
    int updated =
        dsl.update(GAME_INSTANCES)
            .set(GAME_INSTANCES.TENANT_ID, entity.getTenantId())
            .set(GAME_INSTANCES.RUNTIME_VERSION, entity.getRuntimeVersion())
            .set(GAME_INSTANCES.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, entity.getScriptPinEpoch())
            .set(GAME_INSTANCES.GAME_TEMPLATE_ID, entity.getGameTemplateId())
            .set(GAME_INSTANCES.LAUNCH_DESCRIPTOR_ID, entity.getLaunchDescriptorId())
            .set(GAME_INSTANCES.VERSION_ID, entity.getVersionId())
            .set(GAME_INSTANCES.RELEASE_BUNDLE_ID, entity.getReleaseBundleId())
            .set(GAME_INSTANCES.VERSION_STATE_EPOCH, entity.getVersionStateEpoch())
            .set(GAME_INSTANCES.GENERATION_CONFIG_REVISION, entity.getGenerationConfigRevision())
            .set(GAME_INSTANCES.REMAP_SET_ID, entity.getRemapSetId())
            .set(
                GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT,
                toLocalDateTime(entity.getScriptPatchPinnedAt()))
            .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY, entity.getScriptPatchPinnedBy())
            .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON, entity.getScriptPatchPinnedReason())
            .set(
                GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID,
                entity.getScriptPatchPinnedControlPlaneRequestId())
            .set(GAME_INSTANCES.OWNER_ACCOUNT_ID, entity.getOwnerAccountId())
            .set(GAME_INSTANCES.STATUS, entity.getStatus())
            .set(GAME_INSTANCES.ROW_VERSION, nextRowVersion)
            .where(
                GAME_INSTANCES
                    .ID
                    .eq(entity.getId())
                    .and(GAME_INSTANCES.ROW_VERSION.eq(currentRowVersion)))
            .execute();
    if (updated != 1) {
      throw new IllegalStateException("Failed to update game_instance id=" + entity.getId());
    }
    return findById(entity.getId()).orElseThrow();
  }

  public GameInstance saveAndFlush(GameInstance entity) {
    return save(entity);
  }

  /**
   * Applies a script-pin transition under one database transaction and row lock.
   *
   * <p>The request ledger is checked before reading mutable instance state, so an exact retry
   * replays its original result even after a later pin. A request-id reuse with a different
   * normalized request digest fails before any state mutation.
   */
  public ScriptPinMutationResult applyScriptPin(
      Long tenantId,
      Long gameInstanceId,
      String operationKind,
      String targetScriptPatchVersion,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason,
      String expectedPinKind,
      Long expectedScriptPinEpoch) {
    String mutationDigest =
        mutationDigest(
            tenantId,
            gameInstanceId,
            operationKind,
            targetScriptPatchVersion,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = org.jooq.impl.DSL.using(configuration);
          Record existing = findOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (existing != null) {
            if (!mutationDigest.equals(existing.get(OP_DIGEST))) {
              return idempotencyConflict(controlPlaneRequestId);
            }
            return operationResult(existing, controlPlaneRequestId);
          }

          Record current =
              tx.select(SELECT_FIELDS)
                  .from(GAME_INSTANCES)
                  .where(GAME_INSTANCES.ID.eq(gameInstanceId).and(GAME_INSTANCES.TENANT_ID.eq(tenantId)))
                  .forUpdate()
                  .fetchOne();
          if (current == null) {
            throw new IllegalArgumentException("Game instance not found");
          }
          // Different request IDs serialize on the instance row. Recheck the operation after
          // taking that lock so an identical concurrent request replays the winner's result
          // instead of racing its primary-key insert.
          existing = findOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (existing != null) {
            if (!mutationDigest.equals(existing.get(OP_DIGEST))) {
              return idempotencyConflict(controlPlaneRequestId);
            }
            return operationResult(existing, controlPlaneRequestId);
          }
          String previousPatch = normalizePatch(current.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
          Long previousEpoch = current.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH);
          String previousRequestId =
              current.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID);
          requireCoherent(previousPatch, previousEpoch, previousRequestId);

          if (!expectedPinMatches(expectedPinKind, expectedScriptPinEpoch, previousPatch, previousEpoch)) {
            ScriptPinMutationResult result =
                new ScriptPinMutationResult(
                    previousPatch,
                    previousEpoch,
                    previousPatch,
                    previousEpoch,
                    controlPlaneRequestId,
                    "SCRIPT_PIN_EXPECTATION_FAILED");
            insertOperation(
                tx,
                tenantId,
                gameInstanceId,
                operationKind,
                targetScriptPatchVersion,
                expectedPinKind,
                expectedScriptPinEpoch,
                actorPrincipal,
                reason,
                mutationDigest,
                result);
            return result;
          }

          long currentEpoch = previousEpoch == null ? 0L : previousEpoch;
          if (currentEpoch == Long.MAX_VALUE) {
            throw new IllegalStateException("script pin epoch exhausted");
          }
          long resultingEpoch = currentEpoch + 1L;
          long currentRowVersion = current.get(GAME_INSTANCES.ROW_VERSION) == null ? 0L : current.get(GAME_INSTANCES.ROW_VERSION);
          int updated =
              tx.update(GAME_INSTANCES)
                  .set(GAME_INSTANCES.SCRIPT_PATCH_VERSION, targetScriptPatchVersion)
                  .set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, resultingEpoch)
                  .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT, java.time.LocalDateTime.now())
                  .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY, actorPrincipal)
                  .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON, reason)
                  .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID, controlPlaneRequestId)
                  .set(GAME_INSTANCES.ROW_VERSION, currentRowVersion + 1L)
                  .where(
                      GAME_INSTANCES
                          .ID
                          .eq(gameInstanceId)
                          .and(GAME_INSTANCES.TENANT_ID.eq(tenantId))
                          .and(GAME_INSTANCES.ROW_VERSION.eq(currentRowVersion)))
                  .execute();
          if (updated != 1) {
            throw new IllegalStateException("Failed to update game_instance id=" + gameInstanceId);
          }
          ScriptPinMutationResult result =
              new ScriptPinMutationResult(
                  previousPatch,
                  previousEpoch,
                  targetScriptPatchVersion,
                  resultingEpoch,
                  controlPlaneRequestId,
                  null);
          insertOperation(
              tx,
              tenantId,
              gameInstanceId,
              operationKind,
              targetScriptPatchVersion,
              expectedPinKind,
              expectedScriptPinEpoch,
              actorPrincipal,
              reason,
              mutationDigest,
              result);
          return result;
        });
  }

  public void deleteById(Long id) {
    dsl.deleteFrom(GAME_INSTANCES).where(GAME_INSTANCES.ID.eq(id)).execute();
  }

  public void deleteAll() {
    dsl.deleteFrom(GAME_INSTANCES).execute();
  }

  private SelectJoinStep<Record> selectGameInstances() {
    return dsl.select(SELECT_FIELDS).from(GAME_INSTANCES);
  }

  private void insertOperation(
      DSLContext tx,
      Long tenantId,
      Long gameInstanceId,
      String operationKind,
      String targetScriptPatchVersion,
      String expectedPinKind,
      Long expectedScriptPinEpoch,
      String actorPrincipal,
      String reason,
      String mutationDigest,
      ScriptPinMutationResult result) {
    tx.insertInto(SCRIPT_PIN_OPERATION)
        .set(OP_TENANT_ID, tenantId)
        .set(OP_GAME_INSTANCE_ID, gameInstanceId)
        .set(OP_REQUEST_ID, result.controlPlaneRequestId())
        .set(OP_KIND, operationKind)
        .set(OP_TARGET, targetScriptPatchVersion)
        .set(OP_EXPECTED_KIND, expectedPinKind)
        .set(OP_EXPECTED_EPOCH, expectedScriptPinEpoch)
        .set(OP_ACTOR, actorPrincipal)
        .set(OP_REASON, reason)
        .set(OP_DIGEST, mutationDigest)
        .set(OP_OUTCOME, result.succeeded() ? "COMMITTED" : "FAILED")
        .set(OP_ERROR_CODE, result.errorCode())
        .set(OP_PREVIOUS_PATCH, result.previousScriptPatchVersion())
        .set(OP_PREVIOUS_EPOCH, result.previousScriptPinEpoch())
        .set(OP_RESULTING_PATCH, result.resultingScriptPatchVersion())
        .set(OP_RESULTING_EPOCH, result.resultingScriptPinEpoch())
        .execute();
  }

  private Record findOperation(
      DSLContext tx, Long tenantId, Long gameInstanceId, String controlPlaneRequestId) {
    return tx.select(
            OP_TENANT_ID,
            OP_GAME_INSTANCE_ID,
            OP_REQUEST_ID,
            OP_DIGEST,
            OP_ERROR_CODE,
            OP_PREVIOUS_PATCH,
            OP_PREVIOUS_EPOCH,
            OP_RESULTING_PATCH,
            OP_RESULTING_EPOCH)
        .from(SCRIPT_PIN_OPERATION)
        .where(
            OP_TENANT_ID
                .eq(tenantId)
                .and(OP_GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(OP_REQUEST_ID.eq(controlPlaneRequestId)))
        .forUpdate()
        .fetchOne();
  }

  private ScriptPinMutationResult operationResult(Record record, String controlPlaneRequestId) {
    return new ScriptPinMutationResult(
        record.get(OP_PREVIOUS_PATCH),
        record.get(OP_PREVIOUS_EPOCH),
        record.get(OP_RESULTING_PATCH),
        record.get(OP_RESULTING_EPOCH),
        controlPlaneRequestId,
        record.get(OP_ERROR_CODE));
  }

  private ScriptPinMutationResult idempotencyConflict(String controlPlaneRequestId) {
    return new ScriptPinMutationResult(
        null, null, null, null, controlPlaneRequestId, "IDEMPOTENCY_CONFLICT");
  }

  private boolean expectedPinMatches(
      String expectedPinKind, Long expectedScriptPinEpoch, String patchVersion, Long pinEpoch) {
    return switch (expectedPinKind) {
      case "UNCONDITIONAL" -> true;
      case "EXPECT_UNPINNED" -> isAbsent(patchVersion) && pinEpoch == null;
      case "EXPECT_EPOCH" -> pinEpoch != null && pinEpoch.equals(expectedScriptPinEpoch);
      default -> false;
    };
  }

  private void requireCoherent(String patchVersion, Long pinEpoch, String requestId) {
    if (pinEpoch != null && pinEpoch <= 0L) {
      throw new IllegalArgumentException(
          "SCRIPT_PIN_STATE_INVALID: script pin epoch must be positive when present");
    }
    boolean hasPatch = patchVersion != null && !patchVersion.isBlank();
    boolean hasEpoch = pinEpoch != null && pinEpoch > 0L;
    boolean hasRequest = requestId != null && !requestId.isBlank();
    if (!((hasPatch && hasEpoch && hasRequest) || (!hasPatch && !hasEpoch && !hasRequest))) {
      throw new IllegalArgumentException(
          "SCRIPT_PIN_STATE_INVALID: patch, positive epoch, and request id must be present together");
    }
  }

  private String mutationDigest(
      Long tenantId,
      Long gameInstanceId,
      String operationKind,
      String targetScriptPatchVersion,
      String actorPrincipal,
      String reason,
      String expectedPinKind,
      Long expectedScriptPinEpoch) {
    String normalized =
        String.join(
            "|",
            canonical(tenantId),
            canonical(gameInstanceId),
            canonical(operationKind),
            canonical(targetScriptPatchVersion),
            canonical(actorPrincipal),
            canonical(reason),
            canonical(expectedPinKind),
            canonical(expectedScriptPinEpoch));
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private String canonical(Object value) {
    if (value == null) {
      return "-";
    }
    String text = String.valueOf(value);
    return text.length() + ":" + text;
  }

  private boolean isAbsent(String value) {
    return value == null || value.isBlank();
  }

  private String normalizePatch(String value) {
    return isAbsent(value) ? null : value;
  }

  private void populate(GameInstancesRecord record, GameInstance entity) {
    record.setTenantId(entity.getTenantId());
    record.setRuntimeVersion(entity.getRuntimeVersion());
    record.setScriptPatchVersion(entity.getScriptPatchVersion());
    record.setScriptPinEpoch(entity.getScriptPinEpoch());
    record.setGameTemplateId(entity.getGameTemplateId());
    record.setLaunchDescriptorId(entity.getLaunchDescriptorId());
    record.setVersionId(entity.getVersionId());
    record.setReleaseBundleId(entity.getReleaseBundleId());
    record.setVersionStateEpoch(entity.getVersionStateEpoch());
    record.setGenerationConfigRevision(entity.getGenerationConfigRevision());
    record.setRemapSetId(entity.getRemapSetId());
    record.setScriptPatchPinnedAt(toLocalDateTime(entity.getScriptPatchPinnedAt()));
    record.setScriptPatchPinnedBy(entity.getScriptPatchPinnedBy());
    record.setScriptPatchPinnedReason(entity.getScriptPatchPinnedReason());
    record.setScriptPatchPinnedControlPlaneRequestId(
        entity.getScriptPatchPinnedControlPlaneRequestId());
    record.setOwnerAccountId(entity.getOwnerAccountId());
    record.setStatus(entity.getStatus());
  }

  private GameInstance toEntity(Record record) {
    GameInstance entity = new GameInstance();
    entity.setId(record.get(GAME_INSTANCES.ID));
    entity.setTenantId(record.get(GAME_INSTANCES.TENANT_ID));
    entity.setRuntimeVersion(record.get(GAME_INSTANCES.RUNTIME_VERSION));
    entity.setScriptPatchVersion(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
    entity.setScriptPinEpoch(record.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH));
    entity.setGameTemplateId(record.get(GAME_INSTANCES.GAME_TEMPLATE_ID));
    entity.setLaunchDescriptorId(record.get(GAME_INSTANCES.LAUNCH_DESCRIPTOR_ID));
    entity.setVersionId(record.get(GAME_INSTANCES.VERSION_ID));
    entity.setReleaseBundleId(record.get(GAME_INSTANCES.RELEASE_BUNDLE_ID));
    entity.setVersionStateEpoch(record.get(GAME_INSTANCES.VERSION_STATE_EPOCH));
    entity.setGenerationConfigRevision(record.get(GAME_INSTANCES.GENERATION_CONFIG_REVISION));
    entity.setRemapSetId(record.get(GAME_INSTANCES.REMAP_SET_ID));
    entity.setScriptPatchPinnedAt(toInstant(record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT)));
    entity.setScriptPatchPinnedBy(record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY));
    entity.setScriptPatchPinnedReason(record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON));
    entity.setScriptPatchPinnedControlPlaneRequestId(
        record.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID));
    entity.setOwnerAccountId(record.get(GAME_INSTANCES.OWNER_ACCOUNT_ID));
    entity.setStatus(record.get(GAME_INSTANCES.STATUS));
    entity.setRowVersion(record.get(GAME_INSTANCES.ROW_VERSION));
    return entity;
  }
}
