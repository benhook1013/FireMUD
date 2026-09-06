package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toInstant;
import static net.firedevops.firemud.common.persistence.jooq.JooqPersistenceSupport.toLocalDateTime;
import static net.firedevops.firemud.gamesession.jooq.tables.GameInstances.GAME_INSTANCES;
import static net.firedevops.firemud.gamesession.jooq.tables.ScriptPinOperation.SCRIPT_PIN_OPERATION;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.jooq.tables.records.GameInstancesRecord;
import net.firedevops.firemud.gamesession.service.ScriptPinTupleCoherence;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.SelectJoinStep;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected DSLContext is an internal Spring collaborator.")
public class GameInstanceRepository {
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

  /**
   * Reads the authoritative game instance while holding its row lock.
   *
   * <p>Callers must invoke this method inside their admission/staging transaction. The tenant is
   * part of the predicate so an instance from another tenant cannot be used as lock evidence.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<GameInstance> findByTenantIdAndGameInstanceIdForUpdate(
      Long tenantId, Long gameInstanceId) {
    return selectGameInstances()
        .where(GAME_INSTANCES.ID.eq(gameInstanceId).and(GAME_INSTANCES.TENANT_ID.eq(tenantId)))
        .forUpdate()
        .fetchOptional(this::toEntity);
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
    ScriptPinTupleCoherence.requireCoherent(
        entity.getScriptPatchVersion(),
        entity.getScriptPinEpoch(),
        entity.getScriptPatchPinnedControlPlaneRequestId());
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
   * replays its original result even after a later pin. A new request derives {@code REPIN} under
   * the instance row lock when its target equals the current patch; the derived kind and digest are
   * persisted together. A request-id reuse with a different normalized request digest fails before
   * any state mutation.
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
    if (controlPlaneRequestId == null || controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
    if (targetScriptPatchVersion == null || targetScriptPatchVersion.isBlank()) {
      throw new IllegalArgumentException("target_script_patch_version is required");
    }
    if (expectedPinKind == null || expectedPinKind.isBlank()) {
      throw new IllegalArgumentException("expected_pin_kind is required");
    }
    validateExpectedPin(expectedPinKind, expectedScriptPinEpoch);
    String requestedMutationDigest =
        mutationDigest(
            tenantId,
            gameInstanceId,
            operationKind,
            targetScriptPatchVersion,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch);
    String repinMutationDigest =
        mutationDigest(
            tenantId,
            gameInstanceId,
            "REPIN",
            targetScriptPatchVersion,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch);
    boolean repinEligible = canDeriveRepin(operationKind);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Record existing = findOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (existing != null) {
            if (!matchesMutationDigest(
                existing, requestedMutationDigest, repinEligible ? repinMutationDigest : null)) {
              return idempotencyConflict(controlPlaneRequestId);
            }
            return operationResult(existing, controlPlaneRequestId);
          }

          Record current =
              tx.select(SELECT_FIELDS)
                  .from(GAME_INSTANCES)
                  .where(
                      GAME_INSTANCES
                          .ID
                          .eq(gameInstanceId)
                          .and(GAME_INSTANCES.TENANT_ID.eq(tenantId)))
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
            if (!matchesMutationDigest(
                existing, requestedMutationDigest, repinEligible ? repinMutationDigest : null)) {
              return idempotencyConflict(controlPlaneRequestId);
            }
            return operationResult(existing, controlPlaneRequestId);
          }
          String previousPatch = normalizePatch(current.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
          Long previousEpoch = current.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH);
          String previousRequestId =
              current.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID);
          ScriptPinTupleCoherence.requireCoherent(previousPatch, previousEpoch, previousRequestId);
          String effectiveOperationKind =
              effectiveOperationKind(operationKind, targetScriptPatchVersion, previousPatch);
          String effectiveMutationDigest =
              mutationDigest(
                  tenantId,
                  gameInstanceId,
                  effectiveOperationKind,
                  targetScriptPatchVersion,
                  actorPrincipal,
                  reason,
                  expectedPinKind,
                  expectedScriptPinEpoch);

          long currentEpoch = previousEpoch == null ? 0L : previousEpoch;
          if (!expectedPinMatches(
              expectedPinKind, expectedScriptPinEpoch, previousPatch, previousEpoch)) {
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
                effectiveOperationKind,
                targetScriptPatchVersion,
                expectedPinKind,
                expectedScriptPinEpoch,
                actorPrincipal,
                reason,
                effectiveMutationDigest,
                result);
            return result;
          }

          if (currentEpoch == Long.MAX_VALUE) {
            ScriptPinMutationResult result =
                new ScriptPinMutationResult(
                    previousPatch,
                    previousEpoch,
                    previousPatch,
                    previousEpoch,
                    controlPlaneRequestId,
                    "SCRIPT_PIN_EPOCH_EXHAUSTED");
            insertOperation(
                tx,
                tenantId,
                gameInstanceId,
                effectiveOperationKind,
                targetScriptPatchVersion,
                expectedPinKind,
                expectedScriptPinEpoch,
                actorPrincipal,
                reason,
                effectiveMutationDigest,
                result);
            return result;
          }

          long resultingEpoch = currentEpoch + 1L;
          long currentRowVersion =
              current.get(GAME_INSTANCES.ROW_VERSION) == null
                  ? 0L
                  : current.get(GAME_INSTANCES.ROW_VERSION);
          int updated =
              tx.update(GAME_INSTANCES)
                  .set(GAME_INSTANCES.SCRIPT_PATCH_VERSION, targetScriptPatchVersion)
                  .set(GAME_INSTANCES.SCRIPT_PIN_EPOCH, resultingEpoch)
                  .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_AT, toLocalDateTime(Instant.now()))
                  .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_BY, actorPrincipal)
                  .set(GAME_INSTANCES.SCRIPT_PATCH_PINNED_REASON, reason)
                  .set(
                      GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID,
                      controlPlaneRequestId)
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
              effectiveOperationKind,
              targetScriptPatchVersion,
              expectedPinKind,
              expectedScriptPinEpoch,
              actorPrincipal,
              reason,
              effectiveMutationDigest,
              result);
          return result;
        });
  }

  /**
   * Records a deterministic pre-commit pin validation failure without changing the instance tuple.
   *
   * <p>The operation ledger is checked before the current row is read, so an exact retry replays
   * the original result even if the external authority has since changed or recovered. New failures
   * derive {@code REPIN} under the instance row lock when the target equals the current patch,
   * using the same digest and operation-kind rules as successful mutations.
   */
  public ScriptPinMutationResult recordScriptPinFailure(
      Long tenantId,
      Long gameInstanceId,
      String operationKind,
      String targetScriptPatchVersion,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason,
      String expectedPinKind,
      Long expectedScriptPinEpoch,
      String errorCode) {
    if (controlPlaneRequestId == null || controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
    if (targetScriptPatchVersion == null || targetScriptPatchVersion.isBlank()) {
      throw new IllegalArgumentException("target_script_patch_version is required");
    }
    if (expectedPinKind == null || expectedPinKind.isBlank()) {
      throw new IllegalArgumentException("expected_pin_kind is required");
    }
    if (errorCode == null || errorCode.isBlank()) {
      throw new IllegalArgumentException("errorCode is required");
    }
    validateExpectedPin(expectedPinKind, expectedScriptPinEpoch);
    String requestedMutationDigest =
        mutationDigest(
            tenantId,
            gameInstanceId,
            operationKind,
            targetScriptPatchVersion,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch);
    String repinMutationDigest =
        mutationDigest(
            tenantId,
            gameInstanceId,
            "REPIN",
            targetScriptPatchVersion,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch);
    boolean repinEligible = canDeriveRepin(operationKind);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Record existing = findOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (existing != null) {
            if (!matchesMutationDigest(
                existing, requestedMutationDigest, repinEligible ? repinMutationDigest : null)) {
              return idempotencyConflict(controlPlaneRequestId);
            }
            return operationResult(existing, controlPlaneRequestId);
          }
          Record current =
              tx.select(SELECT_FIELDS)
                  .from(GAME_INSTANCES)
                  .where(
                      GAME_INSTANCES
                          .ID
                          .eq(gameInstanceId)
                          .and(GAME_INSTANCES.TENANT_ID.eq(tenantId)))
                  .forUpdate()
                  .fetchOne();
          if (current == null) {
            throw new IllegalArgumentException("Game instance not found");
          }
          existing = findOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (existing != null) {
            if (!matchesMutationDigest(
                existing, requestedMutationDigest, repinEligible ? repinMutationDigest : null)) {
              return idempotencyConflict(controlPlaneRequestId);
            }
            return operationResult(existing, controlPlaneRequestId);
          }
          String previousPatch = normalizePatch(current.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
          Long previousEpoch = current.get(GAME_INSTANCES.SCRIPT_PIN_EPOCH);
          String previousRequestId =
              current.get(GAME_INSTANCES.SCRIPT_PATCH_PINNED_CONTROL_PLANE_REQUEST_ID);
          ScriptPinTupleCoherence.requireCoherent(previousPatch, previousEpoch, previousRequestId);
          String effectiveOperationKind =
              effectiveOperationKind(operationKind, targetScriptPatchVersion, previousPatch);
          String effectiveMutationDigest =
              mutationDigest(
                  tenantId,
                  gameInstanceId,
                  effectiveOperationKind,
                  targetScriptPatchVersion,
                  actorPrincipal,
                  reason,
                  expectedPinKind,
                  expectedScriptPinEpoch);
          ScriptPinMutationResult result =
              new ScriptPinMutationResult(
                  previousPatch,
                  previousEpoch,
                  previousPatch,
                  previousEpoch,
                  controlPlaneRequestId,
                  errorCode);
          insertOperation(
              tx,
              tenantId,
              gameInstanceId,
              effectiveOperationKind,
              targetScriptPatchVersion,
              expectedPinKind,
              expectedScriptPinEpoch,
              actorPrincipal,
              reason,
              effectiveMutationDigest,
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
        .set(SCRIPT_PIN_OPERATION.TENANT_ID, tenantId)
        .set(SCRIPT_PIN_OPERATION.GAME_INSTANCE_ID, gameInstanceId)
        .set(SCRIPT_PIN_OPERATION.CONTROL_PLANE_REQUEST_ID, result.controlPlaneRequestId())
        .set(SCRIPT_PIN_OPERATION.OPERATION_KIND, operationKind)
        .set(SCRIPT_PIN_OPERATION.TARGET_SCRIPT_PATCH_VERSION, targetScriptPatchVersion)
        .set(SCRIPT_PIN_OPERATION.EXPECTED_PIN_KIND, expectedPinKind)
        .set(SCRIPT_PIN_OPERATION.EXPECTED_SCRIPT_PIN_EPOCH, expectedScriptPinEpoch)
        .set(SCRIPT_PIN_OPERATION.ACTOR_PRINCIPAL, actorPrincipal)
        .set(SCRIPT_PIN_OPERATION.REASON, reason)
        .set(SCRIPT_PIN_OPERATION.MUTATION_DIGEST, mutationDigest)
        .set(SCRIPT_PIN_OPERATION.OUTCOME, result.succeeded() ? "COMMITTED" : "FAILED")
        .set(SCRIPT_PIN_OPERATION.ERROR_CODE, result.errorCode())
        .set(
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_VERSION, result.previousScriptPatchVersion())
        .set(SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PIN_EPOCH, result.previousScriptPinEpoch())
        .set(
            SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_VERSION,
            result.resultingScriptPatchVersion())
        .set(SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PIN_EPOCH, result.resultingScriptPinEpoch())
        .execute();
  }

  private Record findOperation(
      DSLContext tx, Long tenantId, Long gameInstanceId, String controlPlaneRequestId) {
    return tx.select(
            SCRIPT_PIN_OPERATION.TENANT_ID,
            SCRIPT_PIN_OPERATION.GAME_INSTANCE_ID,
            SCRIPT_PIN_OPERATION.CONTROL_PLANE_REQUEST_ID,
            SCRIPT_PIN_OPERATION.MUTATION_DIGEST,
            SCRIPT_PIN_OPERATION.ERROR_CODE,
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_VERSION,
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PIN_EPOCH,
            SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_VERSION,
            SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PIN_EPOCH)
        .from(SCRIPT_PIN_OPERATION)
        .where(
            SCRIPT_PIN_OPERATION
                .TENANT_ID
                .eq(tenantId)
                .and(SCRIPT_PIN_OPERATION.GAME_INSTANCE_ID.eq(gameInstanceId))
                .and(SCRIPT_PIN_OPERATION.CONTROL_PLANE_REQUEST_ID.eq(controlPlaneRequestId)))
        .forUpdate()
        .fetchOne();
  }

  private ScriptPinMutationResult operationResult(Record record, String controlPlaneRequestId) {
    return new ScriptPinMutationResult(
        record.get(SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_VERSION),
        record.get(SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PIN_EPOCH),
        record.get(SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_VERSION),
        record.get(SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PIN_EPOCH),
        controlPlaneRequestId,
        record.get(SCRIPT_PIN_OPERATION.ERROR_CODE));
  }

  private ScriptPinMutationResult idempotencyConflict(String controlPlaneRequestId) {
    return new ScriptPinMutationResult(
        null, null, null, null, controlPlaneRequestId, "IDEMPOTENCY_CONFLICT");
  }

  private boolean matchesMutationDigest(
      Record existing, String requestedMutationDigest, String repinMutationDigest) {
    String existingDigest = existing.get(SCRIPT_PIN_OPERATION.MUTATION_DIGEST);
    return requestedMutationDigest.equals(existingDigest)
        || (repinMutationDigest != null && repinMutationDigest.equals(existingDigest));
  }

  private String effectiveOperationKind(
      String requestedOperationKind, String targetScriptPatchVersion, String previousPatch) {
    return canDeriveRepin(requestedOperationKind) && targetScriptPatchVersion.equals(previousPatch)
        ? "REPIN"
        : requestedOperationKind;
  }

  private boolean canDeriveRepin(String operationKind) {
    return "SET".equals(operationKind);
  }

  private boolean expectedPinMatches(
      String expectedPinKind, Long expectedScriptPinEpoch, String patchVersion, Long pinEpoch) {
    if (expectedPinKind == null) {
      return false;
    }
    return switch (expectedPinKind) {
      case "UNCONDITIONAL" -> true;
      case "EXPECT_UNPINNED" -> isAbsent(patchVersion) && pinEpoch == null;
      case "EXPECT_EPOCH" -> pinEpoch != null && pinEpoch.equals(expectedScriptPinEpoch);
      default -> false;
    };
  }

  private void validateExpectedPin(String expectedPinKind, Long expectedScriptPinEpoch) {
    switch (expectedPinKind) {
      case "UNCONDITIONAL" ->
          require(
              expectedScriptPinEpoch == null,
              "expected_script_pin_epoch must be null for UNCONDITIONAL");
      case "EXPECT_UNPINNED" ->
          require(
              expectedScriptPinEpoch == null,
              "expected_script_pin_epoch must be null for EXPECT_UNPINNED");
      case "EXPECT_EPOCH" ->
          require(
              expectedScriptPinEpoch != null && expectedScriptPinEpoch > 0L,
              "expected_script_pin_epoch must be positive for EXPECT_EPOCH");
      default -> throw new IllegalArgumentException("expected_pin_kind is not supported");
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalArgumentException(message);
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
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(String.format("%02x", value));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException ex) {
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
