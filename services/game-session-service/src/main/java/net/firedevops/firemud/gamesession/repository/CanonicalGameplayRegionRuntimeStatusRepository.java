package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingProvisionalCasEvidence;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable region timeline and owner-fence persistence, separate from the instance status row. */
final class CanonicalGameplayRegionRuntimeStatusRepository {
  private static final String TABLE = "game_session_region_runtime_status";
  private static final String PREPARATION_TABLE = "game_session_region_timeline_preparation";
  private static final String FENCE_SEQUENCE = "game_session_region_executor_fence_seq";
  private final DSLContext dsl;

  CanonicalGameplayRegionRuntimeStatusRepository(DSLContext dsl) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
  }

  Optional<CanonicalGameplayRegionRuntimeStatus> find(Source source) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(source, "source");
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + TABLE
                + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
            source.tenantId(),
            source.gameInstanceId(),
            source.regionId());
    if (row == null) return Optional.empty();
    CanonicalGameplayRegionRuntimeStatus status = toStatus(row);
    if (!matchesSource(status, source)
        || !CanonicalGameplayRegionRedisKeys.tenantRegionTag(
                status.tenantId(), status.gameInstanceId(), status.regionId())
            .equals(status.tenantRegionTag())) {
      throw unavailable();
    }
    return Optional.of(status);
  }

  /** Persists the original nonadmitting first-timeline operation before any Redis mutation. */
  TimelinePreparation prepareInitialTimeline(
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion,
      Source source,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation,
      CanonicalGameplayRegionLeaseRedisOwner.TimelineAbsenceProof absenceProof,
      Instant now) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(now, "now");
    if (absenceProof == null || !absenceProof.matches(source, incarnation)) throw unavailable();
    InitialTimelineInputs inputs =
        requireInitialTimelineInputs(
            evidence,
            worldRequest,
            resolvedRegion,
            source,
            incarnation,
            absenceProof.digest());
    try {
      TimelinePreparation prepared =
          dsl.transactionResult(
              configuration -> {
                DSLContext transaction = DSL.using(configuration);
                lockRegionScope(transaction, inputs.tenantRegionTag());
                Record existingStatus =
                    transaction.fetchOne(
                        "SELECT * FROM "
                            + TABLE
                            + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?"
                            + " FOR UPDATE",
                        source.tenantId(),
                        source.gameInstanceId(),
                        source.regionId());
                if (existingStatus != null) throw unavailable();
                Record existingTransition =
                    transaction.fetchOne(
                        "SELECT * FROM " + PREPARATION_TABLE + " WHERE transition_id = ? FOR UPDATE",
                        evidence.transition().transitionId());
                if (existingTransition != null) {
                  TimelinePreparation current = toTimelinePreparation(existingTransition);
                  if (!matchesPreparation(current, evidence, inputs, source)
                      || current.phase == TimelinePreparationPhase.INSTALLED) {
                    throw unavailable();
                  }
                  return current;
                }
                Record existingScope =
                    transaction.fetchOne(
                        "SELECT initialization_operation_id FROM "
                            + PREPARATION_TABLE
                            + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?"
                            + " FOR UPDATE",
                        source.tenantId(),
                        source.gameInstanceId(),
                        source.regionId());
                if (existingScope != null) throw unavailable();
                UUID operationId = UUID.randomUUID();
                transaction.execute(
                    "INSERT INTO "
                        + PREPARATION_TABLE
                        + " (initialization_operation_id, transition_id, account_id, tenant_id,"
                        + " playable_state_namespace_id, character_id, playable_state_scope,"
                        + " game_instance_id, runtime_game_instance_id, binding_ref, session_id,"
                        + " binding_generation, canonical_region_id, region_id, tenant_region_tag,"
                        + " lifecycle_epoch, lifecycle_row_version, capture_id,"
                        + " lifecycle_request_bytes, graph_sha256, preparation_input_digest,"
                        + " assignment_uuid, assignment_intent_digest, world_source_digest,"
                        + " preparation_digest, stable_incarnation_scope_bytes,"
                        + " stable_incarnation_source_bytes, stable_incarnation_qualification_bytes,"
                        + " stable_incarnation_digest,"
                        + " timeline_preimage_state, timeline_absence_proof_digest, region_epoch,"
                        + " last_committed_tick_id, phase, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ABSENT', ?, 1, -1, 'PREPARED', ?, ?)",
                    operationId,
                    evidence.transition().transitionId(),
                    evidence.candidate().identity().accountId(),
                    evidence.candidate().identity().tenantId(),
                    evidence.candidate().identity().playableStateNamespaceId(),
                    evidence.candidate().identity().characterId(),
                    evidence.candidate().identity().playableStateScope(),
                    evidence.candidate().identity().gameInstanceId(),
                    evidence.candidate().identity().runtimeGameInstanceId(),
                    evidence.candidate().bindingRef().bytes(),
                    evidence.candidate().identity().sessionId(),
                    evidence.candidate().bindingGeneration().toString(),
                    source.canonicalRegionId(),
                    source.regionId(),
                    inputs.tenantRegionTag(),
                    source.lifecycleEpoch(),
                    source.lifecycleRowVersion(),
                    source.captureId(),
                    source.lifecycleRequest().canonicalBytes(),
                    source.graphSha256(),
                    source.preparationInputDigest(),
                    UUID.fromString(worldRequest.getAssignmentUuid()),
                    worldRequest.getAssignmentIntentDigest(),
                    inputs.worldSourceDigest(),
                    inputs.preparationDigest(),
                    inputs.incarnationScopeBytes(),
                    inputs.incarnationSourceBytes(),
                    inputs.incarnationQualificationBytes(),
                    inputs.incarnationDigest(),
                    inputs.absenceProofDigest(),
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                Record inserted =
                    transaction.fetchOne(
                        "SELECT * FROM " + PREPARATION_TABLE + " WHERE transition_id = ?",
                        evidence.transition().transitionId());
                TimelinePreparation result =
                    inserted == null ? null : toTimelinePreparation(inserted);
                if (result == null || !matchesPreparation(result, evidence, inputs, source)) {
                  throw unavailable();
                }
                return result;
              });
      if (find(source).isPresent()) throw unavailable();
      return prepared;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  Optional<TimelinePreparation> findTimelinePreparation(Source source) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(source, "source");
    String tag = CanonicalGameplayRegionRedisKeys.tenantRegionTag(
        source.tenantId(), source.gameInstanceId(), source.regionId());
    Record row =
        dsl.fetchOne(
            "SELECT * FROM "
                + PREPARATION_TABLE
                + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
            source.tenantId(),
            source.gameInstanceId(),
            source.regionId());
    if (row == null) return Optional.empty();
    TimelinePreparation preparation = toTimelinePreparation(row);
    if (!preparation.source.equals(source) || !preparation.tenantRegionTag.equals(tag)) {
      throw unavailable();
    }
    return Optional.of(preparation);
  }

  boolean matchesTimelinePreparation(
      TimelinePreparation preparation,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion,
      Source source,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation) {
    Objects.requireNonNull(preparation, "preparation");
    InitialTimelineInputs inputs =
        requireInitialTimelineInputs(
            evidence,
            worldRequest,
            resolvedRegion,
            source,
            incarnation,
            preparation.absenceProofDigest);
    return matchesPreparation(preparation, evidence, inputs, source);
  }

  /** The durable PREPARED-to-INITIALIZING transition grants the sole permission to create meta. */
  Optional<InitialInitializationPermit> beginInitialTimeline(
      TimelinePreparation preparation, Instant now) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(preparation, "preparation");
    Objects.requireNonNull(now, "now");
    return dsl.transactionResult(
        configuration -> {
          DSLContext transaction = DSL.using(configuration);
          lockRegionScope(transaction, preparation.tenantRegionTag);
          if (transaction.fetchOne(
                  "SELECT 1 FROM "
                      + TABLE
                      + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ? FOR UPDATE",
                  preparation.source.tenantId(),
                  preparation.source.gameInstanceId(),
                  preparation.source.regionId())
              != null) {
            throw unavailable();
          }
          Record row =
              transaction.fetchOne(
                  "SELECT * FROM "
                      + PREPARATION_TABLE
                      + " WHERE initialization_operation_id = ? FOR UPDATE",
                  preparation.operationId);
          if (row == null) throw unavailable();
          TimelinePreparation current = toTimelinePreparation(row);
          if (!samePreparation(preparation, current)) throw unavailable();
          if (current.phase == TimelinePreparationPhase.INSTALLED) {
            return Optional.empty();
          }
          if (current.phase != TimelinePreparationPhase.PREPARED) return Optional.empty();
          int updated =
              transaction.execute(
                  "UPDATE "
                      + PREPARATION_TABLE
                      + " SET phase = 'INITIALIZING', updated_at = ?"
                      + " WHERE initialization_operation_id = ? AND phase = 'PREPARED'",
                  OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                  current.operationId);
          if (updated != 1) throw unavailable();
          return Optional.of(new InitialInitializationPermit(current.operationId, current.sourceDigest));
        });
  }

  TimelinePreparation markRedisInitialized(
      TimelinePreparation preparation,
      String leaseTokenCorrelation,
      String redisMetaDigest,
      Instant now) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(preparation, "preparation");
    requireDigest(leaseTokenCorrelation);
    requireDigest(redisMetaDigest);
    Objects.requireNonNull(now, "now");
    String expectedMetaDigest =
        CanonicalGameplayRegionBindingBridgeProofs.initialMetaDigest(
            preparation.tenantRegionTag, preparation.operationId, preparation.sourceDigest());
    if (!expectedMetaDigest.equals(redisMetaDigest)) throw unavailable();
    return updatePreparationAfterRedis(
        preparation, leaseTokenCorrelation, redisMetaDigest, null, null, now);
  }

  TimelinePreparation markTimelineInstalled(
      TimelinePreparation preparation,
      CanonicalGameplayRegionRuntimeStatus installed,
      Instant now) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(preparation, "preparation");
    Objects.requireNonNull(installed, "installed");
    Objects.requireNonNull(now, "now");
    if (!matchesSource(installed, preparation.source)
        || !installed.tenantRegionTag().equals(preparation.tenantRegionTag)
        || installed.regionEpoch() != 1L) {
      throw unavailable();
    }
    return updatePreparationAfterRedis(
        preparation,
        installed.leaseTokenCorrelation(),
        preparation.redisMetaDigest,
        installed.executorFence().longValueExact(),
        installed,
        now);
  }

  private TimelinePreparation updatePreparationAfterRedis(
      TimelinePreparation preparation,
      String leaseTokenCorrelation,
      String redisMetaDigest,
      Long executorFence,
      CanonicalGameplayRegionRuntimeStatus expectedInstalled,
      Instant now) {
    try {
      return dsl.transactionResult(
          configuration -> {
            DSLContext transaction = DSL.using(configuration);
            lockRegionScope(transaction, preparation.tenantRegionTag);
            Record currentStatus =
                transaction.fetchOne(
                    "SELECT * FROM "
                        + TABLE
                        + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ? FOR UPDATE",
                    preparation.source.tenantId(),
                    preparation.source.gameInstanceId(),
                    preparation.source.regionId());
            Record row =
                transaction.fetchOne(
                    "SELECT * FROM "
                        + PREPARATION_TABLE
                        + " WHERE initialization_operation_id = ? FOR UPDATE",
                    preparation.operationId);
            if (row == null) throw unavailable();
            TimelinePreparation current = toTimelinePreparation(row);
            if (!samePreparation(preparation, current)) throw unavailable();
            String expectedMetaDigest =
                CanonicalGameplayRegionBindingBridgeProofs.initialMetaDigest(
                    current.tenantRegionTag, current.operationId, current.sourceDigest());
            if (!expectedMetaDigest.equals(redisMetaDigest)
                || current.redisMetaDigest != null
                    && !current.redisMetaDigest.equals(redisMetaDigest)) {
              throw unavailable();
            }
            String nextPhase;
            if (executorFence == null) {
              if (currentStatus != null
                  || (current.phase != TimelinePreparationPhase.INITIALIZING
                      && current.phase != TimelinePreparationPhase.REDIS_INITIALIZED)) {
                throw unavailable();
              }
              nextPhase = "REDIS_INITIALIZED";
            } else {
              if (currentStatus == null
                  || (current.phase != TimelinePreparationPhase.REDIS_INITIALIZED
                      && current.phase != TimelinePreparationPhase.INSTALLED)) {
                throw unavailable();
              }
              CanonicalGameplayRegionRuntimeStatus status = toStatus(currentStatus);
              if (!status.equals(expectedInstalled)
                  || !matchesSource(status, preparation.source)
                  || status.executorFence().longValueExact() != executorFence
                  || !status.leaseTokenCorrelation().equals(leaseTokenCorrelation)) {
                throw unavailable();
              }
              nextPhase = "INSTALLED";
            }
            int updated =
                transaction.execute(
                    "UPDATE "
                        + PREPARATION_TABLE
                        + " SET phase = ?, lease_token_correlation = ?, executor_fence = ?,"
                        + " redis_meta_digest = ?, updated_at = ?"
                        + " WHERE initialization_operation_id = ?",
                    nextPhase,
                    leaseTokenCorrelation,
                    executorFence,
                    redisMetaDigest,
                    OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                    current.operationId);
            if (updated != 1) throw unavailable();
            return toTimelinePreparation(
                Objects.requireNonNull(
                    transaction.fetchOne(
                        "SELECT * FROM "
                            + PREPARATION_TABLE
                            + " WHERE initialization_operation_id = ?",
                        current.operationId),
                    "region timeline preparation must survive phase readback"));
          });
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static InitialTimelineInputs requireInitialTimelineInputs(
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalInitialPlayerLocationRegionResolver.ResolvedRegion resolvedRegion,
      Source source,
      CanonicalGameplayRegionIncarnationSource.StableEvidence incarnation,
      String absenceProofDigest) {
    Objects.requireNonNull(evidence, "evidence");
    Objects.requireNonNull(worldRequest, "worldRequest");
    Objects.requireNonNull(resolvedRegion, "resolvedRegion");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(incarnation, "incarnation");
    if (!CanonicalGameplayRegionIncarnationSource.RegionScope.from(source)
            .equals(incarnation.scope())
        || absenceProofDigest == null
        || !absenceProofDigest.matches("[0-9a-f]{64}")) {
      throw unavailable();
    }
    var identity = evidence.candidate().identity();
    var assignment = resolvedRegion.lifecycleAssignment();
    var target = worldRequest.getExpectedTarget();
    UUID assignmentId = canonicalNonNilUuid(worldRequest.getAssignmentUuid());
    if (evidence.transition().status()
            != CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
        || evidence.candidate().lifecycle()
            != CanonicalGameplayBindingInventoryEntry.Lifecycle.PROVISIONAL
        || evidence.expectedPrior() != null
        || evidence.transition().expectedPriorBindingRef() != null
        || evidence.transition().expectedPriorBindingGeneration() != null
        || !evidence.transition().transitionId().equals(evidence.candidate().transitionId())
        || worldRequest.getSchemaVersion() != 1
        || !worldRequest.hasExpectedTarget()
        || !worldRequest.getUnknownFields().asMap().isEmpty()
        || !target.getUnknownFields().asMap().isEmpty()
        || !identity.accountId().toString().equals(worldRequest.getCanonicalAccountUuid())
        || !identity.characterId().toString().equals(worldRequest.getCharacterUuid())
        || !identity.tenantId().toString().equals(target.getCanonicalTenantUuid())
        || !identity.gameInstanceId().toString().equals(target.getGameInstanceId())
        || !identity.playableStateNamespaceId()
            .toString()
            .equals(target.getPlayableStateNamespaceUuid())
        || !"SHARED".equals(identity.playableStateScope())
        || !identity.tenantId().equals(source.tenantId())
        || !identity.gameInstanceId().equals(source.gameInstanceId())
        || !identity.regionId().equals(source.regionId())
        || !identity.regionEpoch().equals(BigInteger.ONE)
        || !worldRequest.getAssignmentIntentDigest().matches("[0-9a-f]{64}")
        || !source.canonicalRegionId().equals(resolvedRegion.canonicalRegionInstanceId())
        || !source.regionId().equals(resolvedRegion.operationalRegionId())
        || !source.regionId().equals(assignment.operationalRegionId())
        || !source.lifecycleRequest().equals(assignment.request())
        || source.lifecycleEpoch() != assignment.lifecycleEpoch()
        || source.lifecycleRowVersion() != assignment.lifecycleRowVersion()
        || !source.captureId().equals(assignment.captureId())
        || !source.graphSha256().equals(assignment.graphSha256())
        || !source.preparationInputDigest().equals(assignment.preparationInputDigest())
        || !identity.tenantId().equals(assignment.request().canonicalTenantId())
        || !identity.gameInstanceId().equals(assignment.request().canonicalGameInstanceId())
        || !identity.regionId().equals(assignment.operationalRegionId())) {
      throw unavailable();
    }
    String tag =
        CanonicalGameplayRegionRedisKeys.tenantRegionTag(
            source.tenantId(), source.gameInstanceId(), source.regionId());
    String worldDigest =
        CanonicalGameplayRegionBindingBridgeProofs.worldSourceDigest(worldRequest, resolvedRegion);
    String preparationDigest =
        CanonicalGameplayRegionBindingBridgeProofs.timelinePreparationDigest(
            evidence,
            worldRequest,
            resolvedRegion,
            source,
            worldDigest,
            incarnation,
            absenceProofDigest);
    return new InitialTimelineInputs(
        tag,
        assignmentId,
        worldRequest.getAssignmentIntentDigest(),
        worldDigest,
        preparationDigest,
        incarnation.scopeBytes(),
        incarnation.stableCanonicalBytes(),
        incarnation.timelineInitializationQualification().canonicalBytes(),
        incarnation.digest(),
        absenceProofDigest);
  }

  private static boolean matchesPreparation(
      TimelinePreparation preparation,
      CanonicalGameplayBindingProvisionalCasEvidence evidence,
      InitialTimelineInputs inputs,
      Source source) {
    var identity = evidence.candidate().identity();
    return preparation.transitionId.equals(evidence.transition().transitionId())
        && preparation.source.equals(source)
        && preparation.tenantRegionTag.equals(inputs.tenantRegionTag())
        && preparation.accountId.equals(identity.accountId())
        && preparation.tenantId.equals(identity.tenantId())
        && preparation.playableStateNamespaceId.equals(identity.playableStateNamespaceId())
        && preparation.characterId.equals(identity.characterId())
        && preparation.playableStateScope.equals(identity.playableStateScope())
        && preparation.gameInstanceId.equals(identity.gameInstanceId())
        && preparation.runtimeGameInstanceId == identity.runtimeGameInstanceId()
        && Arrays.equals(preparation.bindingRef, evidence.candidate().bindingRef().bytes())
        && preparation.sessionId.equals(identity.sessionId())
        && preparation.bindingGeneration.equals(evidence.candidate().bindingGeneration())
        && preparation.assignmentId.equals(inputs.assignmentId())
        && preparation.assignmentIntentDigest.equals(inputs.assignmentIntentDigest())
        && preparation.worldSourceDigest.equals(inputs.worldSourceDigest())
        && preparation.preparationDigest.equals(inputs.preparationDigest())
        && Arrays.equals(preparation.incarnationScopeBytes, inputs.incarnationScopeBytes())
        && Arrays.equals(preparation.incarnationSourceBytes, inputs.incarnationSourceBytes())
        && Arrays.equals(
            preparation.incarnationQualificationBytes,
            inputs.incarnationQualificationBytes())
        && preparation.incarnationDigest.equals(inputs.incarnationDigest())
        && preparation.timelinePreimageState.equals("ABSENT")
        && preparation.absenceProofDigest.equals(inputs.absenceProofDigest());
  }

  private static boolean samePreparation(
      TimelinePreparation expected, TimelinePreparation actual) {
    return expected.operationId.equals(actual.operationId)
        && expected.transitionId.equals(actual.transitionId)
        && expected.source.equals(actual.source)
        && expected.preparationDigest.equals(actual.preparationDigest)
        && expected.worldSourceDigest.equals(actual.worldSourceDigest)
        && expected.tenantRegionTag.equals(actual.tenantRegionTag)
        && Arrays.equals(expected.incarnationScopeBytes, actual.incarnationScopeBytes)
        && Arrays.equals(expected.incarnationSourceBytes, actual.incarnationSourceBytes)
        && Arrays.equals(
            expected.incarnationQualificationBytes, actual.incarnationQualificationBytes)
        && expected.incarnationDigest.equals(actual.incarnationDigest)
        && expected.timelinePreimageState.equals(actual.timelinePreimageState)
        && expected.absenceProofDigest.equals(actual.absenceProofDigest);
  }

  private static void lockRegionScope(DSLContext transaction, String tenantRegionTag) {
    String key = "game-session-region-owner-install:v1:" + tenantRegionTag;
    transaction.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", key);
  }

  private static TimelinePreparation toTimelinePreparation(Record row) {
    Source source =
        new Source(
            row.get("tenant_id", UUID.class),
            row.get("game_instance_id", UUID.class),
            row.get("canonical_region_id", UUID.class),
            row.get("region_id", UUID.class),
            row.get("lifecycle_epoch", Long.class),
            row.get("lifecycle_row_version", Long.class),
            WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(
                row.get("lifecycle_request_bytes", byte[].class)),
            row.get("capture_id", UUID.class),
            row.get("graph_sha256", String.class),
            row.get("preparation_input_digest", String.class));
    Long fence = row.get("executor_fence", Long.class);
    return new TimelinePreparation(
        row.get("initialization_operation_id", UUID.class),
        row.get("transition_id", UUID.class),
        row.get("account_id", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("playable_state_namespace_id", UUID.class),
        row.get("character_id", UUID.class),
        row.get("playable_state_scope", String.class),
        row.get("game_instance_id", UUID.class),
        row.get("runtime_game_instance_id", Long.class),
        row.get("binding_ref", byte[].class),
        row.get("session_id", String.class),
        new BigInteger(row.get("binding_generation", java.math.BigDecimal.class).toPlainString()),
        row.get("assignment_uuid", UUID.class),
        row.get("assignment_intent_digest", String.class),
        row.get("canonical_region_id", UUID.class),
        row.get("region_id", UUID.class),
        row.get("tenant_region_tag", String.class),
        source,
        row.get("world_source_digest", String.class),
        row.get("preparation_digest", String.class),
        row.get("stable_incarnation_scope_bytes", byte[].class),
        row.get("stable_incarnation_source_bytes", byte[].class),
        row.get("stable_incarnation_qualification_bytes", byte[].class),
        row.get("stable_incarnation_digest", String.class),
        row.get("timeline_preimage_state", String.class),
        row.get("timeline_absence_proof_digest", String.class),
        row.get("region_epoch", Long.class),
        row.get("last_committed_tick_id", Long.class),
        TimelinePreparationPhase.valueOf(row.get("phase", String.class)),
        row.get("lease_token_correlation", String.class),
        fence == null ? null : BigInteger.valueOf(fence),
        row.get("redis_meta_digest", String.class),
        row.get("updated_at", OffsetDateTime.class).toInstant());
  }

  private record InitialTimelineInputs(
      String tenantRegionTag,
      UUID assignmentId,
      String assignmentIntentDigest,
      String worldSourceDigest,
      String preparationDigest,
      byte[] incarnationScopeBytes,
      byte[] incarnationSourceBytes,
      byte[] incarnationQualificationBytes,
      String incarnationDigest,
      String absenceProofDigest) {
    private InitialTimelineInputs {
      incarnationScopeBytes = incarnationScopeBytes.clone();
      incarnationSourceBytes = incarnationSourceBytes.clone();
      incarnationQualificationBytes = incarnationQualificationBytes.clone();
    }

    @Override
    public byte[] incarnationScopeBytes() {
      return incarnationScopeBytes.clone();
    }

    @Override
    public byte[] incarnationSourceBytes() {
      return incarnationSourceBytes.clone();
    }

    @Override
    public byte[] incarnationQualificationBytes() {
      return incarnationQualificationBytes.clone();
    }
  }

  /** Installs a new durable fence for an already acquired and source-bound Redis grant. */
  CanonicalGameplayRegionRuntimeStatus installOwner(
      Source source,
      long expectedRegionEpoch,
      CanonicalGameplayRegionRuntimeStatus prior,
      String leaseTokenCorrelation,
      String ownerService,
      String ownerInstanceId,
      Instant now) {
    requireNoAmbientTransaction();
    Objects.requireNonNull(source, "source");
    requireDigest(leaseTokenCorrelation);
    requireText(ownerService);
    requireText(ownerInstanceId);
    Objects.requireNonNull(now, "now");
    if (expectedRegionEpoch <= 0L) throw unavailable();
    if ((prior == null && expectedRegionEpoch != 1L)
        || (prior != null
            && (prior.regionEpoch() != expectedRegionEpoch || !matchesSource(prior, source)))) {
      throw unavailable();
    }
    String expectedTag =
        CanonicalGameplayRegionRedisKeys.tenantRegionTag(
            source.tenantId(), source.gameInstanceId(), source.regionId());
    if (prior != null && !expectedTag.equals(prior.tenantRegionTag())) throw unavailable();

    try {
      return dsl.transactionResult(
          configuration -> {
            DSLContext transaction = DSL.using(configuration);
            String scopeLockKey = "game-session-region-owner-install:v1:" + expectedTag;
            transaction.fetch("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", scopeLockKey);
            Record currentRow =
                transaction.fetchOne(
                    "SELECT * FROM "
                        + TABLE
                        + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ? FOR UPDATE",
                    source.tenantId(),
                    source.gameInstanceId(),
                    source.regionId());
            CanonicalGameplayRegionRuntimeStatus current =
                currentRow == null ? null : toStatus(currentRow);
            if (current != null) {
              if (!matchesSource(current, source)
                  || !expectedTag.equals(current.tenantRegionTag())) {
                throw unavailable();
              }
              if (current.leaseTokenCorrelation().equals(leaseTokenCorrelation)) {
                if (matchesLeaseGeneration(
                    current,
                    expectedRegionEpoch,
                    leaseTokenCorrelation,
                    ownerService,
                    ownerInstanceId)) {
                  return current;
                }
                throw unavailable();
              }
              if (prior == null || !current.equals(prior)) throw unavailable();
            } else if (prior != null || expectedRegionEpoch != 1L) {
              throw unavailable();
            }

            Record fenceRow =
                transaction.fetchOne("SELECT nextval('" + FENCE_SEQUENCE + "') AS fence");
            Long fence = fenceRow == null ? null : fenceRow.get("fence", Long.class);
            if (fence == null || fence <= 0L) throw unavailable();
            Record installed;
            if (current == null) {
              if (expectedRegionEpoch != 1L) throw unavailable();
              installed =
                  transaction.fetchOne(
                      "INSERT INTO "
                          + TABLE
                          + " (tenant_id, game_instance_id, canonical_region_id, region_id,"
                          + " tenant_region_tag, lifecycle_epoch, lifecycle_row_version, capture_id,"
                          + " lifecycle_request_bytes, graph_sha256, preparation_input_digest,"
                          + " region_epoch, executor_fence,"
                          + " lease_token_correlation, owner_service, owner_instance_id, paused,"
                          + " last_committed_tick_id, updated_at)"
                          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, false, -1, ?)"
                          + " ON CONFLICT (tenant_id, game_instance_id, region_id) DO NOTHING"
                          + " RETURNING *",
                      source.tenantId(),
                      source.gameInstanceId(),
                      source.canonicalRegionId(),
                      source.regionId(),
                      expectedTag,
                      source.lifecycleEpoch(),
                      source.lifecycleRowVersion(),
                      source.captureId(),
                      source.lifecycleRequest().canonicalBytes(),
                      source.graphSha256(),
                      source.preparationInputDigest(),
                      fence,
                      leaseTokenCorrelation,
                      ownerService,
                      ownerInstanceId,
                      localDateTime(now));
            } else {
              installed =
                  transaction.fetchOne(
                      "UPDATE "
                          + TABLE
                          + " SET executor_fence = ?, lease_token_correlation = ?,"
                          + " owner_service = ?, owner_instance_id = ?, updated_at = ?"
                          + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?"
                          + " AND tenant_region_tag = ? AND canonical_region_id = ?"
                          + " AND lifecycle_epoch = ? AND lifecycle_row_version = ?"
                          + " AND capture_id = ? AND lifecycle_request_bytes = ?"
                          + " AND graph_sha256 = ?"
                          + " AND preparation_input_digest = ? AND region_epoch = ?"
                          + " AND executor_fence = ? AND lease_token_correlation = ?"
                          + " AND owner_service = ? AND owner_instance_id = ? AND paused = ?"
                          + " AND last_committed_tick_id = ?"
                          + " AND last_committed_tick_batch_id IS NOT DISTINCT FROM ?"
                          + " RETURNING *",
                      fence,
                      leaseTokenCorrelation,
                      ownerService,
                      ownerInstanceId,
                      localDateTime(now),
                      current.tenantId(),
                      current.gameInstanceId(),
                      current.regionId(),
                      current.tenantRegionTag(),
                      current.canonicalRegionId(),
                      current.lifecycleEpoch(),
                      current.lifecycleRowVersion(),
                      current.captureId(),
                      current.lifecycleRequest().canonicalBytes(),
                      current.graphSha256(),
                      current.preparationInputDigest(),
                      current.regionEpoch(),
                      current.executorFence().longValueExact(),
                      current.leaseTokenCorrelation(),
                      current.ownerService(),
                      current.ownerInstanceId(),
                      current.paused(),
                      current.lastCommittedTickId(),
                      current.lastCommittedTickBatchId());
            }
            if (installed == null) throw unavailable();
            CanonicalGameplayRegionRuntimeStatus result = toStatus(installed);
            if (!matchesSource(result, source)
                || result.regionEpoch() != expectedRegionEpoch
                || !result.executorFence().equals(BigInteger.valueOf(fence))
                || !result.leaseTokenCorrelation().equals(leaseTokenCorrelation)
                || !result.tenantRegionTag().equals(expectedTag)) {
              throw unavailable();
            }
            return result;
          });
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static CanonicalGameplayRegionRuntimeStatus toStatus(Record row) {
    return new CanonicalGameplayRegionRuntimeStatus(
        row.get("tenant_id", UUID.class),
        row.get("game_instance_id", UUID.class),
        row.get("canonical_region_id", UUID.class),
        row.get("region_id", UUID.class),
        row.get("tenant_region_tag", String.class),
        row.get("lifecycle_epoch", Long.class),
        row.get("lifecycle_row_version", Long.class),
        WorldCanonicalInstanceLifecycleEvidence.Request.fromStored(
            row.get("lifecycle_request_bytes", byte[].class)),
        row.get("capture_id", UUID.class),
        row.get("graph_sha256", String.class),
        row.get("preparation_input_digest", String.class),
        row.get("region_epoch", Long.class),
        BigInteger.valueOf(row.get("executor_fence", Long.class)),
        row.get("lease_token_correlation", String.class),
        row.get("owner_service", String.class),
        row.get("owner_instance_id", String.class),
        Boolean.TRUE.equals(row.get("paused", Boolean.class)),
        row.get("last_committed_tick_batch_id", String.class),
        row.get("last_committed_tick_id", Long.class),
        row.get("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
  }

  private static boolean matchesSource(CanonicalGameplayRegionRuntimeStatus status, Source source) {
    return status.tenantId().equals(source.tenantId())
        && status.gameInstanceId().equals(source.gameInstanceId())
        && status.canonicalRegionId().equals(source.canonicalRegionId())
        && status.regionId().equals(source.regionId())
        && status.lifecycleEpoch() == source.lifecycleEpoch()
        && status.lifecycleRowVersion() == source.lifecycleRowVersion()
        && status.lifecycleRequest().equals(source.lifecycleRequest())
        && status.captureId().equals(source.captureId())
        && status.graphSha256().equals(source.graphSha256())
        && status.preparationInputDigest().equals(source.preparationInputDigest());
  }

  private static boolean matchesLeaseGeneration(
      CanonicalGameplayRegionRuntimeStatus status,
      long regionEpoch,
      String leaseTokenCorrelation,
      String ownerService,
      String ownerInstanceId) {
    return status.regionEpoch() == regionEpoch
        && status.leaseTokenCorrelation().equals(leaseTokenCorrelation)
        && status.ownerService().equals(ownerService)
        && status.ownerInstanceId().equals(ownerInstanceId);
  }

  private static LocalDateTime localDateTime(Instant instant) {
    return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private static void requireDigest(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) throw unavailable();
  }

  private static void requirePrefixedDigest(String value) {
    if (value == null || !value.matches("sha256:[0-9a-f]{64}")) throw unavailable();
  }

  private static void requireText(String value) {
    if (value == null || value.isBlank() || !value.equals(value.trim())) throw unavailable();
  }

  private static void requireNoAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) {
      throw unavailable();
    }
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Canonical Game Session region ownership is unavailable");
  }

  private static IllegalStateException unavailable(Throwable cause) {
    return new IllegalStateException(
        "Canonical Game Session region ownership is unavailable", cause);
  }

  /** Fresh authenticated World assignment supplied by the closed resolver, never caller fields. */
  record Source(
      UUID tenantId,
      UUID gameInstanceId,
      UUID canonicalRegionId,
      UUID regionId,
      long lifecycleEpoch,
      long lifecycleRowVersion,
      WorldCanonicalInstanceLifecycleEvidence.Request lifecycleRequest,
      UUID captureId,
      String graphSha256,
      String preparationInputDigest) {
    Source {
      Objects.requireNonNull(tenantId, "tenantId");
      Objects.requireNonNull(gameInstanceId, "gameInstanceId");
      Objects.requireNonNull(canonicalRegionId, "canonicalRegionId");
      Objects.requireNonNull(regionId, "regionId");
      Objects.requireNonNull(captureId, "captureId");
      Objects.requireNonNull(lifecycleRequest, "lifecycleRequest");
      requireNonNil(canonicalRegionId);
      requireNonNil(captureId);
      CanonicalGameplayRegionRedisKeys.tenantRegionTag(tenantId, gameInstanceId, regionId);
      if (!tenantId.equals(lifecycleRequest.canonicalTenantId())
          || !gameInstanceId.equals(lifecycleRequest.canonicalGameInstanceId())) {
        throw unavailable();
      }
      requireDigest(graphSha256);
      requirePrefixedDigest(preparationInputDigest);
      if (lifecycleEpoch <= 0L || lifecycleRowVersion < 0L) throw unavailable();
    }
  }

  private static void requireNonNil(UUID value) {
    if (value.equals(new UUID(0L, 0L))) throw unavailable();
  }

  private static UUID canonicalNonNilUuid(String value) {
    try {
      UUID parsed = UUID.fromString(value);
      if (!parsed.toString().equals(value) || parsed.equals(new UUID(0L, 0L))) {
        throw unavailable();
      }
      return parsed;
    } catch (RuntimeException malformed) {
      throw unavailable(malformed);
    }
  }

  static final class TimelinePreparation {
    private final UUID operationId;
    private final UUID transitionId;
    private final UUID accountId;
    private final UUID tenantId;
    private final UUID playableStateNamespaceId;
    private final UUID characterId;
    private final String playableStateScope;
    private final UUID gameInstanceId;
    private final long runtimeGameInstanceId;
    private final byte[] bindingRef;
    private final String sessionId;
    private final BigInteger bindingGeneration;
    private final UUID assignmentId;
    private final String assignmentIntentDigest;
    private final UUID canonicalRegionId;
    private final UUID regionId;
    private final String tenantRegionTag;
    private final Source source;
    private final String worldSourceDigest;
    private final String preparationDigest;
    private final byte[] incarnationScopeBytes;
    private final byte[] incarnationSourceBytes;
    private final byte[] incarnationQualificationBytes;
    private final String incarnationDigest;
    private final String timelinePreimageState;
    private final String absenceProofDigest;
    private final long regionEpoch;
    private final long lastCommittedTickId;
    private final TimelinePreparationPhase phase;
    private final String leaseTokenCorrelation;
    private final BigInteger executorFence;
    private final String redisMetaDigest;
    private final Instant updatedAt;

    private TimelinePreparation(
        UUID operationId,
        UUID transitionId,
        UUID accountId,
        UUID tenantId,
        UUID playableStateNamespaceId,
        UUID characterId,
        String playableStateScope,
        UUID gameInstanceId,
        long runtimeGameInstanceId,
        byte[] bindingRef,
        String sessionId,
        BigInteger bindingGeneration,
        UUID assignmentId,
        String assignmentIntentDigest,
        UUID canonicalRegionId,
        UUID regionId,
        String tenantRegionTag,
        Source source,
        String worldSourceDigest,
        String preparationDigest,
        byte[] incarnationScopeBytes,
        byte[] incarnationSourceBytes,
        byte[] incarnationQualificationBytes,
        String incarnationDigest,
        String timelinePreimageState,
        String absenceProofDigest,
        long regionEpoch,
        long lastCommittedTickId,
        TimelinePreparationPhase phase,
        String leaseTokenCorrelation,
        BigInteger executorFence,
        String redisMetaDigest,
        Instant updatedAt) {
      this.operationId = Objects.requireNonNull(operationId, "operationId");
      this.transitionId = Objects.requireNonNull(transitionId, "transitionId");
      this.accountId = Objects.requireNonNull(accountId, "accountId");
      this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
      this.playableStateNamespaceId =
          Objects.requireNonNull(playableStateNamespaceId, "playableStateNamespaceId");
      this.characterId = Objects.requireNonNull(characterId, "characterId");
      this.playableStateScope = Objects.requireNonNull(playableStateScope, "playableStateScope");
      this.gameInstanceId = Objects.requireNonNull(gameInstanceId, "gameInstanceId");
      this.runtimeGameInstanceId = runtimeGameInstanceId;
      this.bindingRef = Objects.requireNonNull(bindingRef, "bindingRef").clone();
      this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
      this.bindingGeneration = Objects.requireNonNull(bindingGeneration, "bindingGeneration");
      this.assignmentId = Objects.requireNonNull(assignmentId, "assignmentId");
      this.assignmentIntentDigest = Objects.requireNonNull(assignmentIntentDigest, "assignmentIntentDigest");
      this.canonicalRegionId = Objects.requireNonNull(canonicalRegionId, "canonicalRegionId");
      this.regionId = Objects.requireNonNull(regionId, "regionId");
      this.tenantRegionTag = Objects.requireNonNull(tenantRegionTag, "tenantRegionTag");
      this.source = Objects.requireNonNull(source, "source");
      this.worldSourceDigest = Objects.requireNonNull(worldSourceDigest, "worldSourceDigest");
      this.preparationDigest = Objects.requireNonNull(preparationDigest, "preparationDigest");
      this.incarnationScopeBytes =
          Objects.requireNonNull(incarnationScopeBytes, "incarnationScopeBytes").clone();
      this.incarnationSourceBytes =
          Objects.requireNonNull(incarnationSourceBytes, "incarnationSourceBytes").clone();
      this.incarnationQualificationBytes =
          Objects.requireNonNull(incarnationQualificationBytes, "incarnationQualificationBytes")
              .clone();
      this.incarnationDigest = Objects.requireNonNull(incarnationDigest, "incarnationDigest");
      this.timelinePreimageState = Objects.requireNonNull(timelinePreimageState, "timelinePreimageState");
      this.absenceProofDigest = Objects.requireNonNull(absenceProofDigest, "absenceProofDigest");
      if (!incarnationDigest.equals(
              CanonicalGameplayRegionIncarnationSource.digestBytes(this.incarnationSourceBytes))
          || this.incarnationQualificationBytes.length == 0
          || this.incarnationQualificationBytes.length > 8192
          || !timelinePreimageState.equals("ABSENT")
          || !absenceProofDigest.matches("[0-9a-f]{64}")) {
        throw unavailable();
      }
      this.regionEpoch = regionEpoch;
      this.lastCommittedTickId = lastCommittedTickId;
      this.phase = Objects.requireNonNull(phase, "phase");
      this.leaseTokenCorrelation = leaseTokenCorrelation;
      this.executorFence = executorFence;
      this.redisMetaDigest = redisMetaDigest;
      this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    UUID operationId() {
      return operationId;
    }

    UUID transitionId() {
      return transitionId;
    }

    Source source() {
      return source;
    }

    String tenantRegionTag() {
      return tenantRegionTag;
    }

    String sourceDigest() {
      return preparationDigest;
    }

    long regionEpoch() {
      return regionEpoch;
    }

    String redisMetaDigest() {
      return redisMetaDigest;
    }

    long lastCommittedTickId() {
      return lastCommittedTickId;
    }

    TimelinePreparationPhase phase() {
      return phase;
    }

    String leaseTokenCorrelation() {
      return leaseTokenCorrelation;
    }

    BigInteger executorFence() {
      return executorFence;
    }

    boolean matchesIncarnation(
        CanonicalGameplayRegionIncarnationSource.StableEvidence current) {
      return current != null
          && timelinePreimageState.equals("ABSENT")
          && Arrays.equals(incarnationScopeBytes, current.scopeBytes())
          && Arrays.equals(incarnationSourceBytes, current.stableCanonicalBytes())
          && Arrays.equals(
              incarnationQualificationBytes,
              current.timelineInitializationQualification().canonicalBytes())
          && incarnationDigest.equals(current.digest());
    }

    String absenceProofDigest() {
      return absenceProofDigest;
    }

  }

  static final class InitialInitializationPermit {
    private final UUID operationId;
    private final String sourceDigest;

    private InitialInitializationPermit(UUID operationId, String sourceDigest) {
      this.operationId = Objects.requireNonNull(operationId, "operationId");
      this.sourceDigest = Objects.requireNonNull(sourceDigest, "sourceDigest");
    }

    UUID operationId() {
      return operationId;
    }

    String sourceDigest() {
      return sourceDigest;
    }
  }

  enum TimelinePreparationPhase {
    PREPARED,
    INITIALIZING,
    REDIS_INITIALIZED,
    INSTALLED
  }
}
