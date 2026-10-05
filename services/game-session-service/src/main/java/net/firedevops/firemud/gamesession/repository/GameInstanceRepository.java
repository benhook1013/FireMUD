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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.service.AccountIds;
import net.firedevops.firemud.gamesession.service.RuntimeVersionIdResolver;
import net.firedevops.firemud.gamesession.service.ScriptPinTupleCoherence;
import org.jooq.Condition;
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
  private static final Field<UUID> GAME_INSTANCE_UUID =
      DSL.field(DSL.name("game_instance_uuid"), UUID.class);
  private static final Field<?>[] SELECT_FIELDS = {
    GAME_INSTANCES.ID,
    GAME_INSTANCE_UUID,
    GAME_INSTANCES.TENANT_ID,
    GAME_INSTANCES.RUNTIME_VERSION,
    GAME_INSTANCES.SCRIPT_PATCH_VERSION,
    GAME_INSTANCES.SCRIPT_PATCH_BASE_VERSION_ID,
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
    GAME_INSTANCES.OWNER_ACCOUNT_UUID,
    GAME_INSTANCES.STATUS,
    GAME_INSTANCES.ROW_VERSION,
    GAME_INSTANCES.RUN_OWNED_START_REQUEST_ID,
    GAME_INSTANCES.RUN_OWNED_START_REQUEST_DIGEST,
    GAME_INSTANCES.RUN_OWNED_START_PUBLISHED_RELEASE_BUNDLE_REF,
    GAME_INSTANCES.RUN_OWNED_START_PREPARING_EPOCH,
    GAME_INSTANCES.RUN_OWNED_START_ACTIVE_EPOCH
  };

  private final DSLContext dsl;

  public GameInstanceRepository(DSLContext dsl) {
    this.dsl = dsl;
  }

  public Optional<GameInstance> findById(Long id) {
    return selectGameInstances().where(GAME_INSTANCES.ID.eq(id)).fetchOptional(this::toEntity);
  }

  /** Serializes run-owned initial launch identity allocation within the caller's transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockRunOwnedStartIdentity(long tenantId, String requestId) {
    if (dsl.dialect().family() == org.jooq.SQLDialect.POSTGRES) {
      String key = "run-owned-initial-launch:" + tenantId + ":" + requestId;
      dsl.fetch("select pg_advisory_xact_lock(hashtextextended(cast(? as text), 0))", key);
    }
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<GameInstance> findByTenantIdAndRunOwnedStartRequestIdForUpdate(
      long tenantId, String requestId) {
    return selectGameInstances()
        .where(
            GAME_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(GAME_INSTANCES.RUN_OWNED_START_REQUEST_ID.eq(requestId)))
        .forUpdate()
        .fetchOptional(this::toEntity);
  }

  /**
   * Reads the existing Game Session owner's canonical UUID for an internal numeric selector.
   *
   * <p>This is strict owner readback, not an allocator or a numeric-to-UUID derivation. Missing
   * rows, retained rows without a UUID, invalid identity state, or conflicting UUID claims deny
   * resolution.
   */
  public String requireCanonicalGameInstanceUuidByTenantIdAndPrivateId(
      Long tenantId, Long privateGameInstanceId) {
    if (tenantId == null || tenantId <= 0L) {
      throw new IllegalArgumentException("tenantId must be a positive internal selector");
    }
    if (privateGameInstanceId == null || privateGameInstanceId <= 0L) {
      throw new IllegalArgumentException(
          "privateGameInstanceId must be a positive internal selector");
    }

    Record source =
        dsl.select(GAME_INSTANCE_UUID)
            .from(GAME_INSTANCES)
            .where(
                GAME_INSTANCES
                    .ID
                    .eq(privateGameInstanceId)
                    .and(GAME_INSTANCES.TENANT_ID.eq(tenantId)))
            .fetchOne();
    if (source == null) {
      throw new IllegalArgumentException("Game instance not found in tenant scope");
    }

    UUID uuid = source.get(GAME_INSTANCE_UUID);
    if (uuid == null) {
      throw new IllegalStateException("Game instance has no canonical UUID mapping");
    }
    String canonicalUuid = uuid.toString();
    if (!AccountIds.isCanonicalNonNilUuid(canonicalUuid)) {
      throw new IllegalStateException("Game instance has an invalid canonical UUID mapping");
    }

    Long claimCount =
        dsl.selectCount()
            .from(GAME_INSTANCES)
            .where(GAME_INSTANCE_UUID.eq(uuid))
            .fetchOne(0, Long.class);
    if (claimCount == null || claimCount != 1L) {
      throw new IllegalStateException("Game instance UUID mapping is conflicting");
    }
    return canonicalUuid;
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
      Long tenantId, String ownerAccountId, String status) {
    UUID ownerAccountUuid = parseCanonicalOwnerAccountId(ownerAccountId);
    return selectGameInstances()
        .where(
            GAME_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(GAME_INSTANCES.OWNER_ACCOUNT_UUID.eq(ownerAccountUuid))
                .and(GAME_INSTANCES.STATUS.eq(status)))
        .limit(1)
        .fetchOptional(this::toEntity);
  }

  /**
   * Reads and locks unresolved owner rows that are active or not known to be inert.
   *
   * <p>Callers must invoke this method inside the owner transaction that stages a new runtime. Rows
   * without a canonical owner UUID are not matched to the requesting Account; they remain ambiguous
   * evidence until a separately authorized reconciliation resolves them.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public List<GameInstance> findUnresolvedActiveOwnerRowsByTenantIdForUpdate(Long tenantId) {
    return selectGameInstances()
        .where(
            GAME_INSTANCES
                .TENANT_ID
                .eq(tenantId)
                .and(GAME_INSTANCES.OWNER_ACCOUNT_UUID.isNull())
                .and(GAME_INSTANCES.STATUS.isNull().or(GAME_INSTANCES.STATUS.ne("STOPPED"))))
        .orderBy(GAME_INSTANCES.ID.asc())
        .forUpdate()
        .fetch(this::toEntity);
  }

  public List<GameInstance> findByStatus(String status) {
    return selectGameInstances()
        .where(GAME_INSTANCES.STATUS.eq(status))
        .orderBy(GAME_INSTANCES.ID.asc())
        .fetch(this::toEntity);
  }

  public GameInstance save(GameInstance entity) {
    UUID ownerAccountUuid =
        entity.getOwnerAccountId() == null
            ? null
            : parseCanonicalOwnerAccountId(entity.getOwnerAccountId());
    if (entity.getId() == null && ownerAccountUuid == null) {
      throw new IllegalArgumentException(
          "New game instances require a canonical ownerAccountId UUID");
    }
    if (entity.getId() == null && entity.getGameInstanceUuid() != null) {
      throw new IllegalArgumentException("New game instance UUIDs are allocated by Game Session");
    }
    if (entity.getId() == null && entity.getLegacyOwnerAccountId() != null) {
      throw new IllegalArgumentException("New game instances cannot use a legacy numeric owner");
    }
    ScriptPinTupleCoherence.requireCoherent(
        entity.getScriptPatchVersion(),
        entity.getScriptPinEpoch(),
        entity.getScriptPatchPinnedControlPlaneRequestId());
    if (entity.getId() == null) {
      long initialRowVersion = entity.getRowVersion() == null ? 0L : entity.getRowVersion();
      UUID gameInstanceUuid = UUID.randomUUID();
      if (!AccountIds.isCanonicalNonNilUuid(gameInstanceUuid.toString())) {
        throw new IllegalStateException("Game Session generated an invalid game instance UUID");
      }
      Record inserted =
          dsl.insertInto(GAME_INSTANCES)
              .set(GAME_INSTANCES.TENANT_ID, entity.getTenantId())
              .set(GAME_INSTANCES.RUNTIME_VERSION, entity.getRuntimeVersion())
              .set(GAME_INSTANCES.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
              .set(
                  GAME_INSTANCES.SCRIPT_PATCH_BASE_VERSION_ID, entity.getScriptPatchBaseVersionId())
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
              .set(GAME_INSTANCES.OWNER_ACCOUNT_ID, (Long) null)
              .set(GAME_INSTANCES.OWNER_ACCOUNT_UUID, ownerAccountUuid)
              .set(GAME_INSTANCES.STATUS, entity.getStatus())
              .set(GAME_INSTANCES.ROW_VERSION, initialRowVersion)
              .set(GAME_INSTANCES.RUN_OWNED_START_REQUEST_ID, entity.getRunOwnedStartRequestId())
              .set(
                  GAME_INSTANCES.RUN_OWNED_START_REQUEST_DIGEST,
                  entity.getRunOwnedStartRequestDigest())
              .set(
                  GAME_INSTANCES.RUN_OWNED_START_PUBLISHED_RELEASE_BUNDLE_REF,
                  entity.getRunOwnedStartPublishedReleaseBundleRef())
              .set(
                  GAME_INSTANCES.RUN_OWNED_START_PREPARING_EPOCH,
                  entity.getRunOwnedStartPreparingEpoch())
              .set(
                  GAME_INSTANCES.RUN_OWNED_START_ACTIVE_EPOCH, entity.getRunOwnedStartActiveEpoch())
              .set(GAME_INSTANCE_UUID, gameInstanceUuid)
              .returning(GAME_INSTANCES.ID)
              .fetchOne();
      if (inserted == null) {
        throw new IllegalStateException("Failed to insert game instance");
      }
      return findById(inserted.get(GAME_INSTANCES.ID)).orElseThrow();
    }

    UUID expectedGameInstanceUuid = entity.getGameInstanceUuid();
    if (expectedGameInstanceUuid != null
        && !AccountIds.isCanonicalNonNilUuid(expectedGameInstanceUuid.toString())) {
      throw new IllegalArgumentException("gameInstanceUuid must be a canonical non-nil UUID");
    }
    Condition expectedGameInstanceIdentity =
        expectedGameInstanceUuid == null
            ? GAME_INSTANCE_UUID.isNull()
            : GAME_INSTANCE_UUID.eq(expectedGameInstanceUuid);
    long currentRowVersion = entity.getRowVersion() == null ? 0L : entity.getRowVersion();
    long nextRowVersion = currentRowVersion + 1L;
    int updated =
        dsl.update(GAME_INSTANCES)
            .set(GAME_INSTANCES.TENANT_ID, entity.getTenantId())
            .set(GAME_INSTANCES.RUNTIME_VERSION, entity.getRuntimeVersion())
            .set(GAME_INSTANCES.SCRIPT_PATCH_VERSION, entity.getScriptPatchVersion())
            .set(GAME_INSTANCES.SCRIPT_PATCH_BASE_VERSION_ID, entity.getScriptPatchBaseVersionId())
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
            .set(GAME_INSTANCES.OWNER_ACCOUNT_UUID, ownerAccountUuid)
            .set(GAME_INSTANCES.STATUS, entity.getStatus())
            .set(
                GAME_INSTANCES.RUN_OWNED_START_PREPARING_EPOCH,
                entity.getRunOwnedStartPreparingEpoch())
            .set(GAME_INSTANCES.RUN_OWNED_START_ACTIVE_EPOCH, entity.getRunOwnedStartActiveEpoch())
            .set(GAME_INSTANCES.ROW_VERSION, nextRowVersion)
            .where(
                GAME_INSTANCES
                    .ID
                    .eq(entity.getId())
                    .and(GAME_INSTANCES.ROW_VERSION.eq(currentRowVersion))
                    .and(expectedGameInstanceIdentity))
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
      Long expectedScriptPinEpoch,
      Long validatedBaseVersionId) {
    if (validatedBaseVersionId == null || validatedBaseVersionId <= 0L) {
      throw new IllegalArgumentException("validated base_version_id must be positive");
    }
    ScriptPinLedgerContext ledger =
        prepareScriptPinLedger(
            tenantId,
            gameInstanceId,
            operationKind,
            targetScriptPatchVersion,
            controlPlaneRequestId,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch,
            validatedBaseVersionId,
            null,
            false);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Optional<ScriptPinMutationResult> replay =
              ledger.replayExistingOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (replay.isPresent()) {
            return replay.get();
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
          replay =
              ledger.replayExistingOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (replay.isPresent()) {
            return replay.get();
          }
          String previousPatch = normalizePatch(current.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
          Long previousBaseVersionId = current.get(GAME_INSTANCES.SCRIPT_PATCH_BASE_VERSION_ID);
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

          Long lockedRuntimeVersionId =
              RuntimeVersionIdResolver.resolve(
                  current.get(GAME_INSTANCES.VERSION_ID),
                  current.get(GAME_INSTANCES.RUNTIME_VERSION));
          if (lockedRuntimeVersionId == null
              || !validatedBaseVersionId.equals(lockedRuntimeVersionId)) {
            String errorCode =
                lockedRuntimeVersionId == null
                    ? "SCRIPT_PATCH_AUTHORITY_UNAVAILABLE"
                    : "SCRIPT_PATCH_BASE_VERSION_MISMATCH";
            ScriptPinMutationResult result =
                new ScriptPinMutationResult(
                    previousPatch,
                    previousEpoch,
                    previousPatch,
                    previousEpoch,
                    controlPlaneRequestId,
                    errorCode,
                    previousBaseVersionId,
                    previousBaseVersionId);
            insertOperation(
                tx,
                tenantId,
                gameInstanceId,
                effectiveOperationKind,
                targetScriptPatchVersion,
                validatedBaseVersionId,
                expectedPinKind,
                expectedScriptPinEpoch,
                actorPrincipal,
                reason,
                effectiveMutationDigest,
                result);
            return result;
          }

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
                    "SCRIPT_PIN_EXPECTATION_FAILED",
                    previousBaseVersionId,
                    previousBaseVersionId);
            insertOperation(
                tx,
                tenantId,
                gameInstanceId,
                effectiveOperationKind,
                targetScriptPatchVersion,
                validatedBaseVersionId,
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
                    "SCRIPT_PIN_EPOCH_EXHAUSTED",
                    previousBaseVersionId,
                    previousBaseVersionId);
            insertOperation(
                tx,
                tenantId,
                gameInstanceId,
                effectiveOperationKind,
                targetScriptPatchVersion,
                validatedBaseVersionId,
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
                  .set(GAME_INSTANCES.SCRIPT_PATCH_BASE_VERSION_ID, validatedBaseVersionId)
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
                  null,
                  previousBaseVersionId,
                  validatedBaseVersionId);
          insertOperation(
              tx,
              tenantId,
              gameInstanceId,
              effectiveOperationKind,
              targetScriptPatchVersion,
              validatedBaseVersionId,
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
    return recordScriptPinFailure(
        tenantId,
        gameInstanceId,
        operationKind,
        targetScriptPatchVersion,
        controlPlaneRequestId,
        actorPrincipal,
        reason,
        expectedPinKind,
        expectedScriptPinEpoch,
        null,
        errorCode);
  }

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
      Long validatedBaseVersionId,
      String errorCode) {
    ScriptPinLedgerContext ledger =
        prepareScriptPinLedger(
            tenantId,
            gameInstanceId,
            operationKind,
            targetScriptPatchVersion,
            controlPlaneRequestId,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch,
            validatedBaseVersionId,
            errorCode,
            true);
    return dsl.transactionResult(
        configuration -> {
          DSLContext tx = DSL.using(configuration);
          Optional<ScriptPinMutationResult> replay =
              ledger.replayExistingOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (replay.isPresent()) {
            return replay.get();
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
          replay =
              ledger.replayExistingOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
          if (replay.isPresent()) {
            return replay.get();
          }
          String previousPatch = normalizePatch(current.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
          Long previousBaseVersionId = current.get(GAME_INSTANCES.SCRIPT_PATCH_BASE_VERSION_ID);
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
                  errorCode,
                  previousBaseVersionId,
                  previousBaseVersionId);
          insertOperation(
              tx,
              tenantId,
              gameInstanceId,
              effectiveOperationKind,
              targetScriptPatchVersion,
              validatedBaseVersionId,
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

  private ScriptPinLedgerContext prepareScriptPinLedger(
      Long tenantId,
      Long gameInstanceId,
      String operationKind,
      String targetScriptPatchVersion,
      String controlPlaneRequestId,
      String actorPrincipal,
      String reason,
      String expectedPinKind,
      Long expectedScriptPinEpoch,
      Long validatedBaseVersionId,
      String errorCode,
      boolean requireErrorCode) {
    validateOperationKind(operationKind);
    if (controlPlaneRequestId == null || controlPlaneRequestId.isBlank()) {
      throw new IllegalArgumentException("control_plane_request_id is required");
    }
    if (targetScriptPatchVersion == null || targetScriptPatchVersion.isBlank()) {
      throw new IllegalArgumentException("target_script_patch_version is required");
    }
    if (expectedPinKind == null || expectedPinKind.isBlank()) {
      throw new IllegalArgumentException("expected_pin_kind is required");
    }
    if (requireErrorCode && (errorCode == null || errorCode.isBlank())) {
      throw new IllegalArgumentException("errorCode is required");
    }
    validateExpectedPin(expectedPinKind, expectedScriptPinEpoch);
    if (validatedBaseVersionId != null && validatedBaseVersionId <= 0L) {
      throw new IllegalArgumentException("validated base_version_id must be positive");
    }
    return new ScriptPinLedgerContext(
        mutationDigest(
            tenantId,
            gameInstanceId,
            operationKind,
            targetScriptPatchVersion,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch),
        mutationDigest(
            tenantId,
            gameInstanceId,
            "REPIN",
            targetScriptPatchVersion,
            actorPrincipal,
            reason,
            expectedPinKind,
            expectedScriptPinEpoch),
        canDeriveRepin(operationKind));
  }

  private final class ScriptPinLedgerContext {
    private final String requestedMutationDigest;
    private final String repinMutationDigest;
    private final boolean repinEligible;

    private ScriptPinLedgerContext(
        String requestedMutationDigest, String repinMutationDigest, boolean repinEligible) {
      this.requestedMutationDigest = requestedMutationDigest;
      this.repinMutationDigest = repinMutationDigest;
      this.repinEligible = repinEligible;
    }

    private Optional<ScriptPinMutationResult> replayExistingOperation(
        DSLContext tx, Long tenantId, Long gameInstanceId, String controlPlaneRequestId) {
      Record existing = findOperation(tx, tenantId, gameInstanceId, controlPlaneRequestId);
      if (existing == null) {
        return Optional.empty();
      }
      if (!matchesMutationDigest(
          existing, requestedMutationDigest, repinEligible ? repinMutationDigest : null)) {
        return Optional.of(idempotencyConflict(controlPlaneRequestId));
      }
      return Optional.of(operationResult(existing, controlPlaneRequestId));
    }
  }

  private void insertOperation(
      DSLContext tx,
      Long tenantId,
      Long gameInstanceId,
      String operationKind,
      String targetScriptPatchVersion,
      Long validatedBaseVersionId,
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
        .set(SCRIPT_PIN_OPERATION.VALIDATED_BASE_VERSION_ID, validatedBaseVersionId)
        .set(SCRIPT_PIN_OPERATION.EXPECTED_PIN_KIND, expectedPinKind)
        .set(SCRIPT_PIN_OPERATION.EXPECTED_SCRIPT_PIN_EPOCH, expectedScriptPinEpoch)
        .set(SCRIPT_PIN_OPERATION.ACTOR_PRINCIPAL, actorPrincipal)
        .set(SCRIPT_PIN_OPERATION.REASON, reason)
        .set(SCRIPT_PIN_OPERATION.MUTATION_DIGEST, mutationDigest)
        .set(SCRIPT_PIN_OPERATION.OUTCOME, result.succeeded() ? "COMMITTED" : "FAILED")
        .set(SCRIPT_PIN_OPERATION.ERROR_CODE, result.errorCode())
        .set(
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_VERSION, result.previousScriptPatchVersion())
        .set(
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_BASE_VERSION_ID,
            result.previousScriptPatchBaseVersionId())
        .set(SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PIN_EPOCH, result.previousScriptPinEpoch())
        .set(
            SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_VERSION,
            result.resultingScriptPatchVersion())
        .set(
            SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_BASE_VERSION_ID,
            result.resultingScriptPatchBaseVersionId())
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
            SCRIPT_PIN_OPERATION.VALIDATED_BASE_VERSION_ID,
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_VERSION,
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_BASE_VERSION_ID,
            SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PIN_EPOCH,
            SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_VERSION,
            SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_BASE_VERSION_ID,
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
        record.get(SCRIPT_PIN_OPERATION.ERROR_CODE),
        record.get(SCRIPT_PIN_OPERATION.PREVIOUS_SCRIPT_PATCH_BASE_VERSION_ID),
        record.get(SCRIPT_PIN_OPERATION.RESULTING_SCRIPT_PATCH_BASE_VERSION_ID));
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
    boolean samePatch = targetScriptPatchVersion.equals(previousPatch);
    if ("REPIN".equals(requestedOperationKind) && !samePatch) {
      throw new IllegalArgumentException(
          "REPIN requires target_script_patch_version to equal the current script patch");
    }
    return canDeriveRepin(requestedOperationKind) && samePatch ? "REPIN" : requestedOperationKind;
  }

  private boolean canDeriveRepin(String operationKind) {
    return "SET".equals(operationKind);
  }

  private void validateOperationKind(String operationKind) {
    if (operationKind == null || operationKind.isBlank()) {
      throw new IllegalArgumentException("operation_kind is required");
    }
    if (!"SET".equals(operationKind)
        && !"ROLLBACK".equals(operationKind)
        && !"REPIN".equals(operationKind)) {
      throw new IllegalArgumentException("operation_kind is not supported");
    }
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
    // Digest only normalized caller inputs; the resolved publication base is mutable authority
    // evidence and remains persisted separately on the operation row and committed result.
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

  private GameInstance toEntity(Record record) {
    GameInstance entity = new GameInstance();
    entity.setId(record.get(GAME_INSTANCES.ID));
    UUID gameInstanceUuid = record.get(GAME_INSTANCE_UUID);
    if (gameInstanceUuid != null
        && !AccountIds.isCanonicalNonNilUuid(gameInstanceUuid.toString())) {
      throw new IllegalStateException("Stored game instance UUID is not canonical and non-nil");
    }
    entity.setGameInstanceUuid(gameInstanceUuid);
    entity.setTenantId(record.get(GAME_INSTANCES.TENANT_ID));
    entity.setRuntimeVersion(record.get(GAME_INSTANCES.RUNTIME_VERSION));
    entity.setScriptPatchVersion(record.get(GAME_INSTANCES.SCRIPT_PATCH_VERSION));
    entity.setScriptPatchBaseVersionId(record.get(GAME_INSTANCES.SCRIPT_PATCH_BASE_VERSION_ID));
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
    UUID ownerAccountUuid = record.get(GAME_INSTANCES.OWNER_ACCOUNT_UUID);
    entity.setOwnerAccountId(ownerAccountUuid == null ? null : ownerAccountUuid.toString());
    entity.setLegacyOwnerAccountId(record.get(GAME_INSTANCES.OWNER_ACCOUNT_ID));
    entity.setStatus(record.get(GAME_INSTANCES.STATUS));
    entity.setRowVersion(record.get(GAME_INSTANCES.ROW_VERSION));
    entity.setRunOwnedStartRequestId(record.get(GAME_INSTANCES.RUN_OWNED_START_REQUEST_ID));
    entity.setRunOwnedStartRequestDigest(record.get(GAME_INSTANCES.RUN_OWNED_START_REQUEST_DIGEST));
    entity.setRunOwnedStartPublishedReleaseBundleRef(
        record.get(GAME_INSTANCES.RUN_OWNED_START_PUBLISHED_RELEASE_BUNDLE_REF));
    entity.setRunOwnedStartPreparingEpoch(
        record.get(GAME_INSTANCES.RUN_OWNED_START_PREPARING_EPOCH));
    entity.setRunOwnedStartActiveEpoch(record.get(GAME_INSTANCES.RUN_OWNED_START_ACTIVE_EPOCH));
    return entity;
  }

  private static UUID parseCanonicalOwnerAccountId(String ownerAccountId) {
    if (!AccountIds.isCanonicalNonNilUuid(ownerAccountId)) {
      throw new IllegalArgumentException("ownerAccountId must be a canonical non-nil UUID");
    }
    return UUID.fromString(ownerAccountId);
  }
}
