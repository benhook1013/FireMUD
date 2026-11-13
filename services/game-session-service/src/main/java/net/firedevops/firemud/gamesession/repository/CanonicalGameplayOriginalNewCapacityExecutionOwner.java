package net.firedevops.firemud.gamesession.repository;

import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.CAPACITY_EXECUTION_AUTHORIZED;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.CAPACITY_EXECUTION_AUTHORIZED_OBSERVED;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.CAPACITY_EXECUTION_CLOSED;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.CAPACITY_EXECUTION_CLOSED_OBSERVED;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.CAPACITY_EXECUTION_COMMITTED_OBSERVED;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.CAPACITY_EXECUTION_FENCED_INDETERMINATE;
import static net.firedevops.firemud.gamesession.repository.CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.CAPACITY_EXECUTION_FENCED_INDETERMINATE_OBSERVED;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution;
import net.firedevops.firemud.common.account.capacity.CapacityAdmissionDigest;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecution;
import net.firedevops.firemud.common.account.capacity.OriginalNewCapacityExecutionReadGrpcCodec;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.PlayerContext;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Request;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.Result;
import net.firedevops.firemud.common.account.capacity.TenantCapacityAdmission.State;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unregistered original-NEW execution evidence owner. Account alone owns capacity and all its
 * transitions. SQL commits the exact original identity and a single install dispatch before Redis;
 * the tenant-slot script serializes final admission against permanent same-attempt closure.
 * Missing/interrupted installation never permits replacement installation or no-commit evidence.
 */
public final class CanonicalGameplayOriginalNewCapacityExecutionOwner {
  private static final String TABLE = "game_session_original_new_capacity_execution";
  static final int MAX_IN_FLIGHT_CONNECTS = 4;
  // Shared across recreated manual owners. A non-interruptible connect occupies one of four
  // workers until it completes; saturation rejects immediately rather than retaining a queue.
  private static final java.util.concurrent.ThreadPoolExecutor CONNECTS =
      new java.util.concurrent.ThreadPoolExecutor(
          0,
          MAX_IN_FLIGHT_CONNECTS,
          30,
          TimeUnit.SECONDS,
          new java.util.concurrent.SynchronousQueue<>(),
          Thread.ofPlatform().daemon(true).name("original-new-capacity-connect-", 0).factory(),
          new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
  private final DSLContext dsl;
  private final RedisClient redis;
  private final Clock clock;
  private final CanonicalGameplayTenantGameplayTagRepository tags;
  private final RedisScriptCatalog scripts;
  private final CanonicalGameplayOriginalNewCapacityExecutionBudget budget;
  private final StatefulRedisConnection<byte[], byte[]> transferReadConnection;

  public CanonicalGameplayOriginalNewCapacityExecutionOwner(
      DSLContext dsl,
      RedisClient redis,
      RedisScriptCatalog scripts,
      Clock clock,
      CanonicalGameplayTenantGameplayTagRepository tags) {
    this(dsl, redis, scripts, clock, tags, null, null);
  }

  private CanonicalGameplayOriginalNewCapacityExecutionOwner(
      DSLContext dsl,
      RedisClient redis,
      RedisScriptCatalog scripts,
      Clock clock,
      CanonicalGameplayTenantGameplayTagRepository tags,
      CanonicalGameplayOriginalNewCapacityExecutionBudget budget,
      StatefulRedisConnection<byte[], byte[]> transferReadConnection) {
    this.dsl = Objects.requireNonNull(dsl);
    this.redis = Objects.requireNonNull(redis);
    this.clock = Objects.requireNonNull(clock);
    this.tags = Objects.requireNonNull(tags);
    this.scripts = Objects.requireNonNull(scripts);
    this.budget = budget;
    this.transferReadConnection = transferReadConnection;
  }

  void requireOrdinaryAdmission(Request request, long deadline) {
    within(
        deadline,
        owner -> {
          CanonicalGameplayCapacityOriginRepository.requireNoRetainedOrigin(
              owner.sql(), request.admissionAttemptId());
          return null;
        });
  }

  /** Manual receiving path: persist exact custody and its physical fence before Account RPC. */
  CanonicalGameplayCapacityOrigin prepareOrigin(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      Request request,
      long deadline) {
    noSql();
    if (budget == null)
      return within(deadline, owner -> owner.prepareOrigin(source, request, deadline));
    CurrentOwner selected = requireRequestBinding(source, request, deadline);
    var assignment = scopedTags().assignmentForTransition(source.candidate().bindingDecisionId());
    var origin =
        new CanonicalGameplayCapacityOrigin(
            request,
            source.candidate(),
            assignment.tenantGameplayTag(),
            selected.instanceId(),
            selected.fence());
    var repository = new CanonicalGameplayCapacityOriginRepository(sql());
    var preparation = repository.prepare(origin);
    var retained = preparation.retained();
    if (!retained.ownerId().equals(selected.instanceId())
        || retained.ownerFence() != selected.fence()) throw unavailable();
    if (retained.state() == CanonicalGameplayCapacityOrigin.State.OPEN) {
      if (preparation.insertedByThisCall()) {
        originRedis()
            .apply(
                origin,
                CanonicalGameplayCapacityOriginRedisOwner.Action.INIT,
                selected.instanceId(),
                selected.fence(),
                selected.instanceId(),
                selected.fence(),
                retained.state(),
                Math.min(deadline, budget.absoluteDeadlineMillis()));
      } else {
        originRedis()
            .readExact(origin, retained.ownerId(), retained.ownerFence(), retained.state());
      }
    } else if (retained.state() == CanonicalGameplayCapacityOrigin.State.DISPATCH_CLAIMED) {
      requireResumableOriginReplay(source, origin, retained);
    } else if (retained.state() != CanonicalGameplayCapacityOrigin.State.DENIED) {
      throw unavailable();
    }
    var after = repository.read(origin);
    if (!after.ownerId().equals(selected.instanceId())
        || after.ownerFence() != selected.fence()
        || after.state() != retained.state()) throw unavailable();
    return origin;
  }

  private void requireResumableOriginReplay(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayCapacityOrigin origin,
      CanonicalGameplayCapacityOriginRepository.Retained retained) {
    if (retained.state() != CanonicalGameplayCapacityOrigin.State.DISPATCH_CLAIMED
        || !origin.selectedOwnerId().equals(retained.ownerId())
        || origin.selectedOwnerFence() != retained.ownerFence()) throw unavailable();
    var execution =
        CanonicalGameplayRegionOwnerTransferRepository.decode(retained.accountResolution());
    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.requireExecutionBinding(source, execution);
    if (!Arrays.equals(
            CapacityAdmissionDigest.preimage(origin.request()),
            CapacityAdmissionDigest.preimage(execution.request()))
        || !origin.selectedOwnerId().equals(execution.selectedOwnerId())
        || origin.selectedOwnerFence() != execution.selectedOwnerFence()) throw unavailable();
    Record row = find(sql(), execution);
    if (row == null) throw unavailable();
    exact(row, execution);
    String status = row.get("status", String.class);
    if (!"AUTHORIZED".equals(status) && !"COMMITTED".equals(status)) throw unavailable();
    originRedis().readExact(origin, retained.ownerId(), retained.ownerFence(), retained.state());
    var physical = readExecution(execution, origin.tenantGameplayTag());
    String state = text(physical.get("state"));
    boolean canResumeFinal =
        "AUTHORIZED".equals(state)
            && "AUTHORIZED".equals(status)
            && source.transitionStatus()
                == CanonicalGameplayBindingTransitionSnapshot.Status.PROVISIONAL
            && execution.reservation().state() == State.RESERVED
            && clock.instant().isBefore(execution.reservation().expiresAt());
    if (!"COMMITTED".equals(state) && !canResumeFinal) throw unavailable();
    exactPhysicalBinding(physical, execution, state);
  }

  /**
   * Durably consumes one observation before Account lookup; lost/unknown replies cannot refund it.
   */
  public net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
      beginOriginObservation(Request request, long deadline) {
    noSql();
    if (budget == null)
      return withinBackground(deadline, owner -> owner.beginOriginObservation(request, deadline));
    var repository = new CanonicalGameplayCapacityOriginRepository(sql());
    return repository.admitObservation(
        repository.readOrigin(request),
        clock.instant(),
        java.time.Instant.ofEpochMilli(budget.absoluteDeadlineMillis()));
  }

  /** Called only by the separate authenticated receiving composition, never by readExact. */
  public void receiveOriginResolution(
      net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution resolution,
      net.firedevops.firemud.common.account.capacity.AccountCapacityOriginContinuation.Permit
          permit,
      long deadline) {
    noSql();
    if (budget == null) {
      withinBackground(
          deadline,
          owner -> {
            owner.receiveOriginResolution(resolution, permit, deadline);
            return null;
          });
      return;
    }
    budget.check();
    var repository = new CanonicalGameplayCapacityOriginRepository(sql());
    var origin = repository.readOrigin(resolution.request());
    var before = repository.read(origin);
    boolean denied =
        resolution.status()
            == net.firedevops.firemud.common.account.capacity.AccountCapacityOriginResolution.Status
                .NO_ACCEPT_RETAINED;
    var target =
        denied
            ? CanonicalGameplayCapacityOrigin.State.DENIED
            : CanonicalGameplayCapacityOrigin.State.CLOSURE_CLAIMED;
    var retained =
        sql()
            .transactionResult(
                configuration -> {
                  var tx = DSL.using(configuration);
                  if (!CanonicalGameplayCapacityOriginRepository.requireObservationInTransaction(
                      tx, origin, permit, clock.instant(), resolution)) return null;
                  if (resolution.status()
                      == net.firedevops.firemud.common.account.capacity
                          .AccountCapacityOriginResolution.Status.UNKNOWN) return null;
                  var claimed =
                      CanonicalGameplayCapacityOriginRepository.claimInTransaction(
                          tx, origin, before.ownerId(), before.ownerFence(), target, resolution);
                  if (!denied) {
                    var execution =
                        new OriginalNewCapacityExecution(
                            resolution.request(),
                            resolution.reservation(),
                            origin.transitionId(),
                            origin.candidateDigest(),
                            origin.selectedOwnerId(),
                            origin.selectedOwnerFence());
                    CanonicalGameplayCapacityEvidenceBudget.requireCharge(
                        tx, execution, origin.tenantGameplayTag());
                    Record existing = find(tx, execution);
                    if (existing != null) {
                      exact(existing, execution);
                      if (!"CLOSURE_CLAIMED".equals(existing.get("status", String.class))
                          && !"CLOSED".equals(existing.get("status", String.class)))
                        throw unavailable();
                    } else if (tx.execute(
                            "INSERT INTO "
                                + TABLE
                                + " (tenant_id,request_id,admission_id,admission_attempt_id,transition_id,capacity_rearm_fence,reservation_id,reservation_fence,mutation_digest,execution_digest,canonical_execution,selected_owner_id,selected_owner_fence,tenant_gameplay_tag,status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'CLOSURE_CLAIMED')",
                            resolution.request().identity().tenantId(),
                            resolution.request().identity().requestId(),
                            resolution.request().identity().admissionId(),
                            resolution.request().admissionAttemptId(),
                            origin.transitionId(),
                            resolution.request().capacityRearmFence(),
                            resolution.reservation().reservationId(),
                            resolution.reservation().reservationFence(),
                            resolution.request().mutationDigest(),
                            execution.digest(),
                            execution.canonicalBytes(),
                            origin.selectedOwnerId(),
                            origin.selectedOwnerFence(),
                            origin.tenantGameplayTag())
                        != 1) throw unavailable();
                  }
                  return claimed;
                });
    if (retained == null) throw unavailable();
    long physicalDeadline =
        Math.min(
            budget.absoluteDeadlineMillis(),
            Math.min(
                permit.deadline().toEpochMilli(),
                resolution.continuationPolicy().deadline().toEpochMilli()));
    if (clock.millis() >= physicalDeadline) throw unavailable();
    originRedis()
        .apply(
            origin,
            denied
                ? CanonicalGameplayCapacityOriginRedisOwner.Action.DENY
                : CanonicalGameplayCapacityOriginRedisOwner.Action.CLOSE_CUSTODY,
            retained.ownerId(),
            retained.ownerFence(),
            retained.ownerId(),
            retained.ownerFence(),
            retained.state(),
            physicalDeadline);
    if (denied) {
      // Retire the origin marker before returning the active execution reserve. A lost reply can
      // replay the retained denial through this same owner without freeing uncertain attempts.
      sql()
          .transaction(
              configuration ->
                  CanonicalGameplayCapacityEvidenceBudget.settleDenied(
                      DSL.using(configuration),
                      origin.request(),
                      origin.candidateDigest(),
                      new CurrentOwner(origin.selectedOwnerId(), origin.selectedOwnerFence()),
                      origin.tenantGameplayTag(),
                      resolution.denialId()));
      return;
    }
    var execution =
        new OriginalNewCapacityExecution(
            resolution.request(),
            resolution.reservation(),
            origin.transitionId(),
            origin.candidateDigest(),
            origin.selectedOwnerId(),
            origin.selectedOwnerFence());
    Record transition = transition(execution);
    var assignment =
        "ABORTED".equals(transition.get("status", String.class))
            ? scopedTags().assignmentForRetainedCapacityExecution(origin.transitionId())
            : scopedTags().assignmentForTransition(origin.transitionId());
    long result =
        scopedFinalOwner()
            .forOrigin(origin, retained)
            .closeCapacityExecutionOnce(
                execution, origin.candidate(), assignment, physicalDeadline);
    if (result != CAPACITY_EXECUTION_CLOSED && result != CAPACITY_EXECUTION_CLOSED_OBSERVED)
      throw unavailable();
    // Existing closure path independently checks physical CLOSED, candidate/index evidence and
    // immutable SQL proof. The repeat physical CLOSE is a read-only exact CLOSED replay.
    closeOriginalAttempt(resolution.request(), resolution.reservation());
  }

  CanonicalGameplayCapacityOriginRedisOwner.Readback transferOriginCustody(
      CanonicalGameplayCapacityOriginTransferRepository.Item item, long deadline) {
    if (budget == null)
      return within(deadline, owner -> owner.transferOriginCustody(item, deadline));
    budget.check();
    return originRedis()
        .apply(
            item.origin(),
            CanonicalGameplayCapacityOriginRedisOwner.Action.TRANSFER,
            item.oldOwner(),
            item.oldFence(),
            item.newOwner(),
            item.newFence(),
            item.state(),
            Math.min(deadline, budget.absoluteDeadlineMillis()));
  }

  CanonicalGameplayCapacityOriginRedisOwner.Readback readOriginTransferCustody(
      CanonicalGameplayCapacityOriginTransferRepository.Item item, long deadline) {
    if (budget == null)
      return within(deadline, owner -> owner.readOriginTransferCustody(item, deadline));
    budget.check();
    if (transferReadConnection != null) {
      return originRedis()
          .readExact(
              transferReadConnection.async(),
              item.origin(),
              item.newOwner(),
              item.newFence(),
              item.state());
    }
    return originRedis().readExact(item.origin(), item.newOwner(), item.newFence(), item.state());
  }

  CanonicalGameplayOriginalNewFinalAdmissionRedisOwner bindFinalOwnerToOrigin(
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner finalOwner,
      CanonicalGameplayCapacityOrigin origin,
      long deadline) {
    if (budget == null)
      return within(deadline, owner -> owner.bindFinalOwnerToOrigin(finalOwner, origin, deadline));
    budget.check();
    return finalOwner.forOrigin(
        origin, new CanonicalGameplayCapacityOriginRepository(sql()).read(origin));
  }

  private CanonicalGameplayCapacityOriginRedisOwner originRedis() {
    var descriptor = scripts.require("game-session.original-new-capacity-origin-custody.v2");
    var expectedArguments =
        java.util.List.of(
                "action",
                "originDigest",
                "oldOwner",
                "oldFence",
                "newOwner",
                "newFence",
                "capturedState",
                "absoluteDeadlineMillis")
            .stream()
            .map(
                net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.ArgumentSpec
                    ::new)
            .toList();
    if (!"game-session.original-new-capacity-origin-custody.v2".equals(descriptor.scriptId())
        || !"redis/lua/game-session-original-new-capacity-origin-custody.lua"
            .equals(descriptor.resourcePath())
        || !"game-session-service".equals(descriptor.owner())
        || !"gamesession_coord_app".equals(descriptor.principal())
        || descriptor.role()
            != net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.RedisRole
                .COORDINATION
        || !descriptor.arguments().equals(expectedArguments)
        || !descriptor
            .keys()
            .equals(
                java.util.List.of(
                    new net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor.KeySpec(
                        "capacityOrigin",
                        "session:game:capacity-origin:",
                        net.firedevops.firemud.common.redis.contracts.RedisScriptDescriptor
                            .HashTagDeclaration.REQUIRED)))) throw unavailable();
    try (var input =
        CanonicalGameplayOriginalNewCapacityExecutionOwner.class
            .getClassLoader()
            .getResourceAsStream(descriptor.resourcePath())) {
      if (input == null) throw unavailable();
      byte[] script = input.readAllBytes();
      return new CanonicalGameplayCapacityOriginRedisOwner(
          this::connectForRead, budget::remainingMillis, script, descriptor.sha256());
    } catch (java.io.IOException invalid) {
      throw unavailable();
    }
  }

  OriginalNewCapacityExecution bind(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      Request request,
      Result result) {
    return bind(source, request, result, Long.MAX_VALUE);
  }

  OriginalNewCapacityExecution bind(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      Request request,
      Result result,
      long deadline) {
    return bind(source, request, result, deadline, null);
  }

  OriginalNewCapacityExecution bind(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      Request request,
      Result result,
      long deadline,
      CurrentOwner expectedOwner) {
    if (budget == null)
      return within(
          deadline, owner -> owner.bind(source, request, result, deadline, expectedOwner));
    budget.check();
    request.requireCommittable();
    result.requireBinding(request);
    CurrentOwner selected = requireRequestBinding(source, request, deadline);
    if (expectedOwner != null && !expectedOwner.equals(selected)) throw unavailable();
    var candidate = source.candidate();
    return new OriginalNewCapacityExecution(
        request,
        result,
        candidate.bindingDecisionId(),
        source.candidateDigest(),
        selected.instanceId(),
        selected.fence());
  }

  /** Qualifies deterministic candidate binding before Account commits a reservation. */
  CurrentOwner requireRequestBinding(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      Request request,
      long deadline) {
    noSql();
    if (budget == null)
      return within(deadline, owner -> owner.requireRequestBinding(source, request, deadline));
    budget.check();
    // Account owns fresh policy denial; coherent non-creating evidence must reach that owner.
    request.requireStructurallyValid();
    var candidate = source.candidate();
    var binding = candidate.bindingIdentity();
    var target = candidate.targetEvidence();
    if (!(request.context() instanceof PlayerContext player)
        || !"SHARED".equals(target.playableStateScope())
        || !"PRESEEDED_ONLY".equals(target.characterCreationPolicy())
        || !target.targetNamespace().equals(request.targetNamespace())
        || !binding.accountId().equals(player.accountId())
        || !binding.tenantId().equals(player.tenantId())
        || !binding.playableStateNamespaceId().equals(player.playableStateNamespaceId())
        || !binding.gameInstanceId().equals(player.gameInstanceId())
        || !binding.characterId().equals(player.characterId())
        || !binding.sessionId().equals(player.sessionId().toString())
        || !target.realmId().equals(player.realmId())
        || !(request.evidence().fields().get("target") instanceof Map<?, ?> evidenceTarget)
        || !Long.toString(target.catalogRevision()).equals(evidenceTarget.get("catalogRevision"))
        || !Long.toString(target.pointerVersion()).equals(evidenceTarget.get("pointerVersion"))
        || !Long.toString(target.activeWorldEpoch()).equals(evidenceTarget.get("activeWorldEpoch"))
        || !target.canonicalVersionId().toString().equals(evidenceTarget.get("canonicalVersionId"))
        || !player
            .requestId()
            .toString()
            .equals(candidate.leaseEvidence().carrier().get("requestId"))
        || !request.identity().admissionId().equals(candidate.bindingDecisionId())
        || !Objects.equals(
            request.evidence().fields().get("authorityTuple"),
            candidate.leaseEvidence().carrier().get("authorityTuple"))) throw unavailable();
    CurrentOwner selected = currentOwner(candidate);
    if (selected.fence() != source.regionAcknowledgement().executorFence().longValueExact())
      throw unavailable();
    return selected;
  }

  /** Returns a retained terminal denial on exact replay, otherwise reserves execution evidence. */
  UUID precharge(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      Request request,
      CurrentOwner selected,
      long deadline) {
    if (budget == null)
      return within(deadline, owner -> owner.precharge(source, request, selected, deadline));
    budget.check();
    request.requireCommittable();
    if (!source.candidate().bindingDecisionId().equals(request.identity().admissionId())
        || !source.candidate().bindingIdentity().tenantId().equals(request.identity().tenantId())
        || selected.fence() != source.regionAcknowledgement().executorFence().longValueExact()) {
      throw unavailable();
    }
    var candidate = source.candidate();
    var assignment = scopedTags().assignmentForTransition(candidate.bindingDecisionId());
    return sql()
        .transactionResult(
            configuration -> {
              var tx = DSL.using(configuration);
              requireSelectedOwner(tx, candidate, selected);
              return CanonicalGameplayCapacityEvidenceBudget.precharge(
                  tx, request, source.candidateDigest(), selected, assignment.tenantGameplayTag());
            });
  }

  void retireQuotaDeniedOrigin(
      CanonicalGameplayCapacityOrigin origin, UUID accountDenialId, long deadline) {
    within(
        deadline,
        owner -> {
          var resolution =
              new AccountCapacityOriginResolution(
                  origin.request(),
                  AccountCapacityOriginResolution.Status.NO_ACCEPT_RETAINED,
                  null,
                  accountDenialId);
          var repository = new CanonicalGameplayCapacityOriginRepository(owner.sql());
          var before = repository.read(origin);
          if (!origin.selectedOwnerId().equals(before.ownerId())
              || origin.selectedOwnerFence() != before.ownerFence()) throw unavailable();
          var retained =
              owner
                  .sql()
                  .transactionResult(
                      configuration ->
                          CanonicalGameplayCapacityOriginRepository.claimInTransaction(
                              DSL.using(configuration),
                              origin,
                              before.ownerId(),
                              before.ownerFence(),
                              CanonicalGameplayCapacityOrigin.State.DENIED,
                              resolution));
          owner
              .originRedis()
              .apply(
                  origin,
                  CanonicalGameplayCapacityOriginRedisOwner.Action.DENY,
                  retained.ownerId(),
                  retained.ownerFence(),
                  retained.ownerId(),
                  retained.ownerFence(),
                  retained.state(),
                  owner.budget.absoluteDeadlineMillis());
          owner
              .sql()
              .transaction(
                  configuration ->
                      CanonicalGameplayCapacityEvidenceBudget.settleDenied(
                          DSL.using(configuration),
                          origin.request(),
                          origin.candidateDigest(),
                          new CurrentOwner(origin.selectedOwnerId(), origin.selectedOwnerFence()),
                          origin.tenantGameplayTag(),
                          accountDenialId));
          return null;
        });
  }

  void settleDeniedCharge(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      Request request,
      CurrentOwner selected,
      UUID accountDenialId,
      long deadline) {
    within(
        deadline,
        owner -> {
          var assignment =
              owner.scopedTags().assignmentForTransition(request.identity().admissionId());
          owner
              .sql()
              .transaction(
                  configuration ->
                      CanonicalGameplayCapacityEvidenceBudget.settleDenied(
                          DSL.using(configuration),
                          request,
                          source.candidateDigest(),
                          selected,
                          assignment.tenantGameplayTag(),
                          accountDenialId));
          return null;
        });
  }

  /** Returns only after independently reading the already-installed exact execution record. */
  void authorize(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewAuthenticatedSession.Template template,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen,
      OriginalNewCapacityExecution execution,
      long deadline) {
    authorizeWithOrigin(source, template, accountOpen, issuerOpen, execution, deadline, null);
  }

  void authorizeWithOrigin(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayOriginalNewAuthenticatedSession.Template template,
      CanonicalGameplayAccountInitialOpenProof.CurrentOpen accountOpen,
      CanonicalGameplayIssuerAdmissionBarrierRedisOwner.Readback issuerOpen,
      OriginalNewCapacityExecution execution,
      long deadline,
      CanonicalGameplayCapacityOrigin origin) {
    noSql();
    if (budget == null) {
      within(
          deadline,
          owner -> {
            owner.authorizeWithOrigin(
                source, template, accountOpen, issuerOpen, execution, deadline, origin);
            return null;
          });
      return;
    }
    budget.check();
    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.requireExecutionBinding(source, execution);
    var assignment = scopedTags().assignmentForTransition(execution.transitionId());
    Record retained = find(sql(), execution);
    if (retained != null) {
      // Preserve exact retry/recovery behavior: an existing dispatch never needs fresh inputs.
      exact(retained, execution);
    } else {
      try {
        scopedFinalOwner()
            .validateInstallInputs(source, template, accountOpen, issuerOpen, execution, deadline);
      } catch (RuntimeException invalidInputs) {
        // A concurrent exact dispatcher may have committed while local validation ran. Only
        // that retained row can turn this into the existing read-only recovery path.
        Record concurrentlyRetained = find(sql(), execution);
        if (concurrentlyRetained == null) throw invalidInputs;
        exact(concurrentlyRetained, execution);
      }
    }
    boolean dispatch =
        sql()
            .transactionResult(
                configuration -> {
                  var tx = DSL.using(configuration);
                  // Serialize original effect custody with every durable region-owner replacement.
                  // This lock is released before any Redis command is submitted.
                  if (origin != null)
                    CanonicalGameplayBindingInventoryRepository.lockInventoryRevision(tx);
                  requireSelectedOwner(
                      tx,
                      source.candidate(),
                      new CurrentOwner(
                          execution.selectedOwnerId(), execution.selectedOwnerFence()));
                  if (origin == null) {
                    // Origin preparation locks the same region before inserting custody. Recheck
                    // under that lock before an ordinary dispatch or an existing-row replay.
                    CanonicalGameplayCapacityOriginRepository.requireNoRetainedOrigin(
                        tx, execution.request().admissionAttemptId());
                  } else {
                    CanonicalGameplayCapacityOriginRepository.claimDispatchInTransaction(
                        tx, origin, execution);
                  }
                  Record row = find(tx, execution);
                  if (row != null) {
                    exact(row, execution);
                    if (!"COMMITTED".equals(row.get("status", String.class))) {
                      CanonicalGameplayCapacityEvidenceBudget.requireCharge(
                          tx, execution, assignment.tenantGameplayTag());
                    }
                    return false;
                  }
                  if (execution.reservation().state() != State.RESERVED
                      || !clock.instant().isBefore(execution.reservation().expiresAt()))
                    throw unavailable();
                  // Lock the original transition to exclude preparing an unrelated successor
                  // binding.
                  Record transition =
                      tx.fetchOne(
                          "SELECT * FROM game_session_canonical_binding_transition WHERE transition_id = ? FOR UPDATE",
                          execution.transitionId());
                  if (transition == null
                      || !"PROVISIONAL".equals(transition.get("status", String.class))
                      || !Arrays.equals(
                          source.candidate().canonicalBytes(),
                          transition.get("original_new_candidate_bytes", byte[].class)))
                    throw unavailable();
                  int inserted =
                      tx.execute(
                          "INSERT INTO "
                              + TABLE
                              + " (tenant_id, request_id, admission_id, admission_attempt_id, transition_id, capacity_rearm_fence, reservation_id, reservation_fence, mutation_digest, execution_digest, canonical_execution, selected_owner_id, selected_owner_fence, tenant_gameplay_tag, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'INSTALL_DISPATCHED')",
                          execution.request().identity().tenantId(),
                          execution.request().identity().requestId(),
                          execution.request().identity().admissionId(),
                          execution.request().admissionAttemptId(),
                          execution.transitionId(),
                          execution.request().capacityRearmFence(),
                          execution.reservation().reservationId(),
                          execution.reservation().reservationFence(),
                          execution.request().mutationDigest(),
                          execution.digest(),
                          execution.canonicalBytes(),
                          execution.selectedOwnerId(),
                          execution.selectedOwnerFence(),
                          assignment.tenantGameplayTag());
                  if (inserted != 1) throw unavailable();
                  return true;
                });
    // A lost SQL commit reply intentionally loses dispatch permission; retries only inspect.
    if (!dispatch && origin != null) {
      requireResumableOriginReplay(
          source, origin, new CanonicalGameplayCapacityOriginRepository(sql()).read(origin));
      return;
    }
    if (dispatch) {
      if (origin != null)
        originRedis()
            .apply(
                origin,
                CanonicalGameplayCapacityOriginRedisOwner.Action.DISPATCH,
                execution.selectedOwnerId(),
                execution.selectedOwnerFence(),
                execution.selectedOwnerId(),
                execution.selectedOwnerFence(),
                CanonicalGameplayCapacityOrigin.State.DISPATCH_CLAIMED,
                Math.min(deadline, budget.absoluteDeadlineMillis()));
      var finalOwner =
          origin == null
              ? scopedFinalOwner()
              : scopedFinalOwner()
                  .forOrigin(
                      origin, new CanonicalGameplayCapacityOriginRepository(sql()).read(origin));
      long result =
          finalOwner.dispatchCapacityAction(
              source,
              template,
              accountOpen,
              issuerOpen,
              execution,
              "INSTALL",
              budget.absoluteDeadlineMillis());
      if (result != CAPACITY_EXECUTION_AUTHORIZED
          && result != CAPACITY_EXECUTION_AUTHORIZED_OBSERVED
          && result != CAPACITY_EXECUTION_COMMITTED_OBSERVED) throw unavailable();
    }
    Map<String, byte[]> physical = readExecution(execution, assignment.tenantGameplayTag());
    String state = text(physical.get("state"));
    if (!"AUTHORIZED".equals(state) && !"COMMITTED".equals(state)) throw unavailable();
    exactPhysicalBinding(physical, execution, state);
    sql()
        .execute(
            "UPDATE "
                + TABLE
                + " SET status = 'AUTHORIZED' WHERE execution_digest = ? AND status = 'INSTALL_DISPATCHED'",
            execution.digest());
  }

  /**
   * Owner-local irreversible closure. No credential, Account transition or expiry inference.
   * Recreated owners use only the original retained identity; this is deliberately not the read
   * RPC.
   */
  public void closeOriginalAttempt(Request request, Result reservation) {
    noSql();
    if (budget == null) {
      within(
          Long.MAX_VALUE,
          owner -> {
            owner.closeOriginalAttempt(request, reservation);
            return null;
          });
      return;
    }
    budget.check();
    Record row =
        sql()
            .fetchOne(
                "SELECT * FROM "
                    + TABLE
                    + " WHERE tenant_id = ? AND request_id = ? AND admission_id = ? AND admission_attempt_id = ?",
                request.identity().tenantId(),
                request.identity().requestId(),
                request.identity().admissionId(),
                request.admissionAttemptId());
    if (row == null) throw unavailable();
    var execution =
        new OriginalNewCapacityExecution(
            request,
            reservation,
            row.get("transition_id", UUID.class),
            candidateDigest(row),
            row.get("selected_owner_id", String.class),
            row.get("selected_owner_fence", Long.class));
    exact(row, execution);
    Record transition = transition(execution);
    if ("COMMITTED".equals(transition.get("status", String.class))) {
      retainCommittedBeforeCleanup(request, reservation, budget.absoluteDeadlineMillis());
      // Successful positive reconciliation never authorizes the requested negative closure.
      throw unavailable();
    }
    if ("CLOSED".equals(row.get("status", String.class))) {
      Snapshot proof = readExact(request, reservation, false);
      if (!"NOT_EXECUTED_AFTER_AUTHORIZATION".equals(proof.disposition())) throw unavailable();
      return;
    }
    requireClosedTransition(transition);
    if ("ABORTED".equals(transition.get("status", String.class))) {
      var abort =
          CanonicalGameplayBindingInventoryRepository
              .readRetainedDefinitiveFirstNewAccountAbortSourceInTransaction(
                  sql(), execution.transitionId(), false);
      if (!abort.isAccountAborted()) throw unavailable();
    }
    var candidate =
        CanonicalGameplayOriginalNewCandidate.parseCanonical(
            transition.get("original_new_candidate_bytes", byte[].class));
    var assignment =
        "ABORTED".equals(transition.get("status", String.class))
            ? scopedTags().assignmentForRetainedCapacityExecution(execution.transitionId())
            : scopedTags().assignmentForTransition(execution.transitionId());
    if (!assignment.tenantGameplayTag().equals(row.get("tenant_gameplay_tag", String.class)))
      throw unavailable();
    CanonicalGameplayCapacityEvidenceBudget.requireCharge(
        sql(), execution, assignment.tenantGameplayTag());
    long result =
        scopedFinalOwner()
            .closeCapacityExecutionOnce(
                execution, candidate, assignment, budget.absoluteDeadlineMillis());
    if (result == CAPACITY_EXECUTION_COMMITTED_OBSERVED) {
      retainCommittedBeforeCleanup(request, reservation, budget.absoluteDeadlineMillis());
      throw unavailable();
    }
    if (result != CAPACITY_EXECUTION_CLOSED && result != CAPACITY_EXECUTION_CLOSED_OBSERVED)
      throw unavailable();
    Map<String, byte[]> physical = readExecution(execution, assignment.tenantGameplayTag());
    exactPhysicalBinding(physical, execution, "CLOSED");
    requireCandidateAndIndexes(row, candidate, null, execution);
    byte[] proof =
        execution.terminalEvidence(
            "NOT_EXECUTED_AFTER_AUTHORIZATION",
            execution.candidateDigest(),
            closedReceipt(execution));
    retainTerminal(execution, "CLOSED", proof);
  }

  /**
   * Owner-local continuation after a lost final reply or process stop. It never dispatches FINAL,
   * issues a credential, or interprets absence as a negative outcome. Serving cleanup must wait for
   * this retained positive evidence (or the independently verified original CLOSE path).
   */
  public Snapshot retainCommittedBeforeCleanup(Request request, Result reservation) {
    return retainCommittedBeforeCleanup(request, reservation, Long.MAX_VALUE);
  }

  public Snapshot retainCommittedBeforeCleanup(Request request, Result reservation, long deadline) {
    noSql();
    if (budget == null)
      return within(
          deadline, owner -> owner.retainCommittedBeforeCleanup(request, reservation, deadline));
    budget.check();
    var execution = retainedExecution(request, reservation);
    Record transition = transition(execution);
    if ("PROVISIONAL".equals(transition.get("status", String.class))) {
      // Redis may already contain the irreversible positive effect even when its SQL receipt
      // was not reached. Recover that exact result without the original LOGIN or private grant.
      try (var actual = readProvisionalFinal(execution, transition)) {
        new CanonicalGameplayBindingInventoryRepository(sql())
            .commitOriginalNewFinalAdmission(actual);
      }
    }
    Snapshot snapshot = readExact(request, reservation, true);
    if (!"COMMITTED".equals(snapshot.disposition())) throw unavailable();
    retainTerminal(execution, "COMMITTED", snapshot.canonicalReadback());
    return readExact(request, reservation, false);
  }

  /** The fence owner can observe a physical FINAL before opening its SQL intent transaction. */
  HistoricalFinalReadback readFinalBeforeAccountIntent(
      Request request, Result reservation, long deadline) {
    noSql();
    if (budget == null)
      return within(
          deadline, owner -> owner.readFinalBeforeAccountIntent(request, reservation, deadline));
    budget.check();
    var execution = retainedExecution(request, reservation);
    Record transition = transition(execution);
    if (!"PROVISIONAL".equals(transition.get("status", String.class))) throw unavailable();
    try (var actual = readProvisionalFinal(execution, transition)) {
      var binding = actual.source().candidate().bindingIdentity();
      return new HistoricalFinalReadback(
          binding.accountId(),
          binding.tenantId(),
          binding.playableStateNamespaceId(),
          execution,
          actual.finalSessionSha256(),
          actual.admissionAtMillis(),
          actual.continuityExpiresAtMillis());
    }
  }

  record HistoricalFinalReadback(
      UUID accountId,
      UUID tenantId,
      UUID playableStateNamespaceId,
      OriginalNewCapacityExecution execution,
      String finalSessionSha256,
      long admissionAtMillis,
      long continuityExpiresAtMillis) {}

  private OriginalNewCapacityExecution retainedExecution(Request request, Result reservation) {
    Record row =
        sql()
            .fetchOne(
                "SELECT * FROM "
                    + TABLE
                    + " WHERE tenant_id = ? AND request_id = ? AND admission_id = ? AND admission_attempt_id = ?",
                request.identity().tenantId(),
                request.identity().requestId(),
                request.identity().admissionId(),
                request.admissionAttemptId());
    if (row == null) throw unavailable();
    var execution =
        new OriginalNewCapacityExecution(
            request,
            reservation,
            row.get("transition_id", UUID.class),
            candidateDigest(row),
            row.get("selected_owner_id", String.class),
            row.get("selected_owner_fence", Long.class));
    exact(row, execution);
    return execution;
  }

  private CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback readProvisionalFinal(
      OriginalNewCapacityExecution execution, Record transition) {
    var candidate =
        CanonicalGameplayOriginalNewCandidate.parseCanonical(
            transition.get("original_new_candidate_bytes", byte[].class));
    var inventory = new CanonicalGameplayBindingInventoryRepository(sql());
    var decision =
        inventory.readAdmissionDecision(execution.transitionId(), candidate.leaseEvidence());
    var source = inventory.readOriginalNewFinalAdmissionSource(decision);
    return scopedFinalOwner().readCommittedForRetention(source, execution);
  }

  static void retainCommittedInTransaction(
      DSLContext transaction,
      OriginalNewCapacityExecution execution,
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionReceipt finalReceipt) {
    String receipt =
        receiptDigest(
            execution,
            finalReceipt.finalSessionSha256(),
            finalReceipt.admissionAtMillis(),
            finalReceipt.continuityExpiresAtMillis(),
            finalReceipt.sessionDurationMillis());
    byte[] proof =
        execution.terminalEvidence(
            "COMMITTED", "sha256:" + finalReceipt.finalSessionSha256(), receipt);
    retainTerminal(transaction, execution, "COMMITTED", proof);
  }

  void retainCommitted(
      CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.Readback actual, long deadline) {
    noSql();
    if (budget == null) {
      within(
          deadline,
          owner -> {
            owner.retainCommitted(actual, deadline);
            return null;
          });
      return;
    }
    budget.check();
    var execution = actual.execution();
    Record row = find(sql(), execution);
    if (row == null) throw unavailable();
    exact(row, execution);
    // A pre-existing receipt may predate atomic retention. Qualify that historical gap through
    // the exact physical indexes and atomic terminal record before retaining its outcome.
    Snapshot snapshot = readExact(execution.request(), execution.reservation(), true);
    if (!"COMMITTED".equals(snapshot.disposition())) throw unavailable();
    retainTerminal(execution, "COMMITTED", snapshot.canonicalReadback());
  }

  private void retainTerminal(OriginalNewCapacityExecution execution, String status, byte[] proof) {
    sql()
        .transaction(
            configuration -> retainTerminal(DSL.using(configuration), execution, status, proof));
  }

  private static void retainTerminal(
      DSLContext tx, OriginalNewCapacityExecution execution, String status, byte[] proof) {
    Record row = find(tx, execution);
    if (row == null) throw unavailable();
    exact(row, execution);
    String current = row.get("status", String.class);
    if (current.equals(status)) {
      if (!Arrays.equals(proof, row.get("terminal_readback", byte[].class))) throw unavailable();
      return;
    }
    if (!("AUTHORIZED".equals(current)
        || ("CLOSED".equals(status)
            && ("INSTALL_DISPATCHED".equals(current) || "CLOSURE_CLAIMED".equals(current)))))
      throw unavailable();
    if (tx.execute(
            "UPDATE "
                + TABLE
                + " SET status = ?, terminal_readback = ? WHERE execution_digest = ? AND status = ?",
            status,
            proof,
            execution.digest(),
            current)
        != 1) throw unavailable();
  }

  /** Reads the historical acquisition witness using the bounded recovery resource pool. */
  public OriginalNewCapacityExecutionReadGrpcCodec.HistoricalFinalReadback readHistoricalFinal(
      Request request, Result reservation) {
    return withinBackground(
        Long.MAX_VALUE,
        owner -> {
          var receipt =
              new CanonicalGameplayAccountAdmissionBarrierRepository(owner.sql(), clock)
                  .readHistoricalFinalReceipt(request, reservation)
                  .orElseThrow(CanonicalGameplayOriginalNewCapacityExecutionOwner::unavailable);
          var observation = receipt.observation();
          return new OriginalNewCapacityExecutionReadGrpcCodec.HistoricalFinalReadback(
              receipt.execution(),
              observation.operationId(),
              observation.finalSessionSha256(),
              observation.admissionAtMillis(),
              observation.continuityExpiresAtMillis(),
              observation.inventorySnapshotRevision().toString(),
              observation.cohortDigest(),
              observation.barrierTargetDigest());
        });
  }

  void requireRetainedCommittedOriginal(
      CanonicalGameplayBindingInventoryRepository.OriginalNewFinalAdmissionSource source,
      CanonicalGameplayRegionRuntimeStatus prior) {
    Record transition =
        sql()
            .fetchOne(
                "SELECT original_new_capacity_execution_digest"
                    + " FROM game_session_canonical_binding_transition WHERE transition_id=?",
                source.candidate().bindingDecisionId());
    if (transition == null) throw unavailable();
    String digest = transition.get(0, String.class);
    Record row =
        sql()
            .fetchOne(
                "SELECT canonical_execution FROM "
                    + TABLE
                    + " WHERE execution_digest=? AND status='COMMITTED'",
                digest);
    if (row == null) throw unavailable();
    var execution = CanonicalGameplayRegionOwnerTransferRepository.decode(row.get(0, byte[].class));
    CanonicalGameplayOriginalNewFinalAdmissionRedisOwner.requireExecutionBinding(source, execution);
    var proof = readExact(execution.request(), execution.reservation(), false);
    var receipt =
        source
            .finalReceipt()
            .orElseThrow(CanonicalGameplayOriginalNewCapacityExecutionOwner::unavailable);
    if (!"COMMITTED".equals(proof.disposition())
        || !proof.physicalDigest().equals("sha256:" + receipt.finalSessionSha256())
        || !prior.ownerInstanceId().equals(proof.currentOwnerId())
        || !prior.executorFence().equals(java.math.BigInteger.valueOf(proof.currentOwnerFence()))) {
      throw unavailable();
    }
  }

  /** Authenticated transport calls this read-only method. Absence and contradictions throw. */
  public Snapshot readExact(Request request, Result reservation) {
    return withinBackground(Long.MAX_VALUE, owner -> owner.readExact(request, reservation, false));
  }

  private Snapshot readExact(Request request, Result reservation, boolean verifyForRetention) {
    noSql();
    budget.check();
    Record row =
        sql()
            .fetchOne(
                "SELECT * FROM "
                    + TABLE
                    + " WHERE tenant_id = ? AND request_id = ? AND admission_id = ? AND admission_attempt_id = ?",
                request.identity().tenantId(),
                request.identity().requestId(),
                request.identity().admissionId(),
                request.admissionAttemptId());
    if (row == null) throw unavailable();
    var execution =
        new OriginalNewCapacityExecution(
            request,
            reservation,
            row.get("transition_id", UUID.class),
            candidateDigest(row),
            row.get("selected_owner_id", String.class),
            row.get("selected_owner_fence", Long.class));
    exact(row, execution);
    Record transition = transition(execution);
    byte[] candidateBytes = transition.get("original_new_candidate_bytes", byte[].class);
    var candidate = CanonicalGameplayOriginalNewCandidate.parseCanonical(candidateBytes);
    if (!execution
        .candidateDigest()
        .equals("sha256:" + OriginalNewCapacityExecution.sha256(candidateBytes)))
      throw unavailable();
    CurrentOwner observedOwner = currentOwner(candidate);
    if (observedOwner.fence() < execution.selectedOwnerFence()
        || (observedOwner.fence() == execution.selectedOwnerFence()
            && !observedOwner.instanceId().equals(execution.selectedOwnerId())))
      throw unavailable();
    if ("COMMITTED".equals(row.get("status", String.class))) {
      String finalDigest = transition.get("original_new_final_session_sha256", String.class);
      if (!"COMMITTED".equals(transition.get("status", String.class))
          || finalDigest == null
          || !execution
              .digest()
              .equals(transition.get("original_new_capacity_execution_digest", String.class)))
        throw unavailable();
      String receipt = receiptDigest(transition, execution);
      byte[] retained = execution.terminalEvidence("COMMITTED", "sha256:" + finalDigest, receipt);
      if (!Arrays.equals(retained, row.get("terminal_readback", byte[].class))
          || !observedOwner.equals(currentOwner(candidate))) throw unavailable();
      return new Snapshot(
          execution,
          "COMMITTED",
          "sha256:" + finalDigest,
          receipt,
          retained,
          observedOwner.instanceId(),
          observedOwner.fence());
    }
    if ("CLOSED".equals(row.get("status", String.class))) {
      requireClosedTransition(transition);
      Map<String, byte[]> tombstone =
          readExecution(execution, row.get("tenant_gameplay_tag", String.class));
      exactPhysicalBinding(tombstone, execution, "CLOSED");
      byte[] retained =
          execution.terminalEvidence(
              "NOT_EXECUTED_AFTER_AUTHORIZATION",
              execution.candidateDigest(),
              closedReceipt(execution));
      if (!Arrays.equals(retained, row.get("terminal_readback", byte[].class))) throw unavailable();
      // Already independently verified closure is the evidence, never absence of serving keys.
      // Keep the exact irreversible tombstone and current-owner check after serving retirement.
      requireClosedTransition(transition(execution));
      if (!observedOwner.equals(currentOwner(candidate))) throw unavailable();
      return new Snapshot(
          execution,
          "NOT_EXECUTED_AFTER_AUTHORIZATION",
          execution.candidateDigest(),
          closedReceipt(execution),
          retained,
          observedOwner.instanceId(),
          observedOwner.fence());
    }
    Map<String, byte[]> physical =
        readExecution(execution, row.get("tenant_gameplay_tag", String.class));
    String state = text(physical.get("state"));
    exactPhysicalBinding(physical, execution, state);
    final String disposition;
    final String physicalDigest;
    final String receiptDigest;
    if ("COMMITTED".equals(state)) {
      if (!verifyForRetention) throw unavailable();
      String finalDigest = transition.get("original_new_final_session_sha256", String.class);
      if (!"COMMITTED".equals(transition.get("status", String.class))
          || !execution
              .digest()
              .equals(transition.get("original_new_capacity_execution_digest", String.class))
          || finalDigest == null) throw unavailable();
      requireCandidateAndIndexes(row, candidate, finalDigest, execution);
      disposition = "COMMITTED";
      physicalDigest = "sha256:" + finalDigest;
      receiptDigest = receiptDigest(transition, execution);
    } else throw unavailable();
    byte[] proof = execution.terminalEvidence(disposition, physicalDigest, receiptDigest);
    Record after = find(sql(), execution);
    exact(Objects.requireNonNull(after), execution);
    if (!row.get("status", String.class).equals(after.get("status", String.class)))
      throw unavailable();
    if (!observedOwner.equals(currentOwner(candidate))) throw unavailable();
    return new Snapshot(
        execution,
        disposition,
        physicalDigest,
        receiptDigest,
        proof,
        observedOwner.instanceId(),
        observedOwner.fence());
  }

  private static void requireClosedTransition(Record transition) {
    String status = transition.get("status", String.class);
    if (!("PROVISIONAL".equals(status) || "ABORTED".equals(status))
        || transition.get("original_new_final_session_sha256") != null
        || transition.get("original_new_capacity_execution_digest") != null
        || transition.get("original_new_admission_at_ms") != null
        || transition.get("original_new_continuity_expires_at_ms") != null
        || transition.get("original_new_session_duration_ms") != null) throw unavailable();
  }

  private static void requireSelectedOwner(
      DSLContext tx, CanonicalGameplayOriginalNewCandidate candidate, CurrentOwner selected) {
    var binding = candidate.bindingIdentity();
    Record current =
        tx.fetchOne(
            "SELECT owner_service, owner_instance_id, executor_fence, region_epoch, paused FROM game_session_region_runtime_status WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ? FOR UPDATE",
            binding.tenantId(),
            binding.gameInstanceId(),
            binding.regionId());
    if (current == null
        || !"game-session-service".equals(current.get("owner_service", String.class))
        || !selected.instanceId().equals(current.get("owner_instance_id", String.class))
        || !Long.valueOf(selected.fence()).equals(current.get("executor_fence", Long.class))
        || !binding
            .regionEpoch()
            .equals(java.math.BigInteger.valueOf(current.get("region_epoch", Long.class)))
        || !Boolean.FALSE.equals(current.get("paused", Boolean.class))
        || CanonicalGameplayRegionOwnerTransferRepository.frozen(
            tx, binding.tenantId(), binding.gameInstanceId(), binding.regionId()))
      throw unavailable();
  }

  CanonicalGameplayOriginalNewCapacityExecutionOwner forTransferBudget(
      CanonicalGameplayOriginalNewCapacityExecutionBudget operationBudget) {
    return new CanonicalGameplayOriginalNewCapacityExecutionOwner(
        dsl, redis, scripts, clock, tags, operationBudget, null);
  }

  /** Keeps one authenticated non-reconnecting connection for the current transfer readback. */
  <T> T withTransferReadback(Function<CanonicalGameplayOriginalNewCapacityExecutionOwner, T> read) {
    noSql();
    if (budget == null || transferReadConnection != null) throw unavailable();
    budget.check();
    var connection = connectForRead();
    try {
      principal(connection.async());
      return read.apply(
          new CanonicalGameplayOriginalNewCapacityExecutionOwner(
              dsl, redis, scripts, clock, tags, budget, connection));
    } finally {
      connection.closeAsync();
    }
  }

  /** Fences future work without resolving the retained original or its Account capacity hold. */
  TransferFence fenceForTransfer(
      CanonicalGameplayRegionOwnerTransferRepository.Item item, long deadline) {
    if (budget == null) return within(deadline, owner -> owner.fenceForTransfer(item, deadline));
    budget.check();
    var execution = item.execution();
    Record row = find(sql(), execution);
    if (row == null) throw unavailable();
    exact(row, execution);
    CanonicalGameplayCapacityEvidenceBudget.requireCharge(
        sql(), execution, item.assignment().tenantGameplayTag());
    long result =
        scopedFinalOwner()
            .fenceCapacityExecutionOnce(
                execution, item.candidate(), item.assignment(), budget.absoluteDeadlineMillis());
    if (result == CAPACITY_EXECUTION_COMMITTED_OBSERVED) {
      // Merely seeing a COMMITTED hash must not release old-generation custody.
      retainCommittedBeforeCleanup(execution.request(), execution.reservation(), deadline);
    } else if (result == CAPACITY_EXECUTION_CLOSED_OBSERVED) {
      if ("CLOSED".equals(row.get("status", String.class))) {
        readExact(execution.request(), execution.reservation(), false);
      } else {
        closeOriginalAttempt(execution.request(), execution.reservation());
      }
    } else if (result != CAPACITY_EXECUTION_FENCED_INDETERMINATE
        && result != CAPACITY_EXECUTION_FENCED_INDETERMINATE_OBSERVED) throw unavailable();
    return readTransferFence(item, deadline);
  }

  /** Read only. In particular, a lost/reset denying hash is never recreated by verification. */
  TransferFence readTransferFence(
      CanonicalGameplayRegionOwnerTransferRepository.Item item, long deadline) {
    if (budget == null) return within(deadline, owner -> owner.readTransferFence(item, deadline));
    budget.check();
    var execution = item.execution();
    Map<String, byte[]> physical = readExecution(execution, item.assignment().tenantGameplayTag());
    String state = text(physical.get("state"));
    if ("FENCED_INDETERMINATE".equals(state)) {
      if (physical.size() != 3
          || !Arrays.equals(execution.canonicalBytes(), physical.get("binding"))
          || !execution.digest().equals(text(physical.get("digest")))) throw unavailable();
    } else {
      if (!"COMMITTED".equals(state) && !"CLOSED".equals(state)) throw unavailable();
      exactPhysicalBinding(physical, execution, state);
      Record retained = find(sql(), execution);
      if (retained == null || !state.equals(retained.get("status", String.class)))
        throw unavailable();
      // The immutable terminal reader independently proves the full retained positive/negative
      // result.
      readExact(execution.request(), execution.reservation(), false);
    }
    var output = new java.io.ByteArrayOutputStream();
    physical.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              for (byte[] value : new byte[][] {bytes(entry.getKey()), entry.getValue()}) {
                output.writeBytes(bytes(Integer.toString(value.length)));
                output.write(':');
                output.writeBytes(value);
              }
            });
    return new TransferFence(execution.digest(), output.toByteArray());
  }

  /** Only physical inspection above constructs this future-exclusion capability. */
  static final class TransferFence {
    private final String executionDigest;
    private final byte[] canonicalReadback;

    private TransferFence(String executionDigest, byte[] canonicalReadback) {
      this.executionDigest = executionDigest;
      this.canonicalReadback = canonicalReadback.clone();
    }

    String executionDigest() {
      return executionDigest;
    }

    byte[] canonicalReadback() {
      return canonicalReadback.clone();
    }
  }

  private CurrentOwner currentOwner(CanonicalGameplayOriginalNewCandidate candidate) {
    var binding = candidate.bindingIdentity();
    Record current =
        sql()
            .fetchOne(
                "SELECT owner_service, owner_instance_id, executor_fence FROM game_session_region_runtime_status WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
                binding.tenantId(),
                binding.gameInstanceId(),
                binding.regionId());
    if (current == null
        || !"game-session-service".equals(current.get("owner_service", String.class)))
      throw unavailable();
    String instance = current.get("owner_instance_id", String.class);
    Long fence = current.get("executor_fence", Long.class);
    if (instance == null
        || !instance.matches("[A-Za-z0-9._:-]{1,120}")
        || fence == null
        || fence <= 0) throw unavailable();
    return new CurrentOwner(instance, fence);
  }

  record CurrentOwner(String instanceId, long fence) {}

  private String candidateDigest(Record row) {
    Record transition =
        sql()
            .fetchOne(
                "SELECT original_new_candidate_digest FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                row.get("transition_id", UUID.class));
    if (transition == null) throw unavailable();
    return transition.get("original_new_candidate_digest", String.class);
  }

  private Record transition(OriginalNewCapacityExecution execution) {
    Record row =
        sql()
            .fetchOne(
                "SELECT * FROM game_session_canonical_binding_transition WHERE transition_id = ?",
                execution.transitionId());
    if (row == null) throw unavailable();
    return row;
  }

  private void requireCandidateAndIndexes(
      Record row,
      CanonicalGameplayOriginalNewCandidate candidate,
      String expectedFinalDigest,
      OriginalNewCapacityExecution execution) {
    var assignment =
        "ABORTED".equals(transition(execution).get("status", String.class))
            ? scopedTags().assignmentForRetainedCapacityExecution(execution.transitionId())
            : scopedTags().assignmentForTransition(execution.transitionId());
    if (!assignment.tenantGameplayTag().equals(row.get("tenant_gameplay_tag", String.class)))
      throw unavailable();
    var binding = candidate.bindingIdentity();
    String expectedCharacter =
        "{\"sessionId\":\""
            + binding.sessionId()
            + "\",\"gameInstanceId\":\""
            + binding.gameInstanceId()
            + "\",\"bindingGeneration\":\""
            + candidate.transition().bindingGeneration()
            + "\"}";
    StatefulRedisConnection<byte[], byte[]> connection = connectForRead();
    try {
      var commands = connection.async();
      principal(commands);
      byte[] session =
          bytes(CanonicalGameplaySessionRedisKeys.session(assignment, binding.bindingRef()));
      byte[] character =
          bytes(CanonicalGameplaySessionRedisKeys.characterIndex(assignment, binding.bindingRef()));
      byte[] account =
          bytes(
              CanonicalGameplaySessionRedisKeys.accountTenantIndex(
                  assignment, binding.bindingRef()));
      byte[] tenant =
          bytes(CanonicalGameplaySessionRedisKeys.tenantIndex(assignment, binding.bindingRef()));
      if (!persistent(commands, session, "string")
          || !persistent(commands, character, "string")
          || !Arrays.equals(bytes(expectedCharacter), await(commands.get(character)))
          || !persistent(commands, account, "set")
          || !Boolean.TRUE.equals(await(commands.sismember(account, bytes(binding.sessionId()))))
          || !persistent(commands, tenant, "set")
          || !Boolean.TRUE.equals(await(commands.sismember(tenant, bytes(binding.sessionId())))))
        throw unavailable();
      byte[] actual = await(commands.get(session));
      try {
        if (expectedFinalDigest == null) {
          if (!Arrays.equals(candidate.canonicalBytes(), actual)) throw unavailable();
        } else {
          if (!expectedFinalDigest.equals(OriginalNewCapacityExecution.sha256(actual)))
            throw unavailable();
          try (var finalSession =
              CanonicalGameplayOriginalNewAuthenticatedSession.parseFinalized(actual, clock)) {
            if (!candidate.equals(finalSession.candidate())) throw unavailable();
          }
          Map<String, byte[]> executionPhysical =
              readExecution(execution, assignment.tenantGameplayTag());
          exactPhysicalBinding(executionPhysical, execution, "COMMITTED");
          if (!text(executionPhysical.get("sessionSha1")).equals(sha1(actual))) throw unavailable();
          Record receipt = transition(execution);
          if (!text(executionPhysical.get("admissionAt"))
                  .equals(Long.toString(receipt.get("original_new_admission_at_ms", Long.class)))
              || !text(executionPhysical.get("continuityExpiresAt"))
                  .equals(
                      Long.toString(
                          receipt.get("original_new_continuity_expires_at_ms", Long.class))))
            throw unavailable();
        }
      } finally {
        if (actual != null) Arrays.fill(actual, (byte) 0);
      }
    } finally {
      connection.closeAsync();
    }
  }

  private Map<String, byte[]> readExecution(OriginalNewCapacityExecution execution, String tag) {
    if (transferReadConnection != null)
      return readExecution(transferReadConnection.async(), execution, tag);
    StatefulRedisConnection<byte[], byte[]> connection = connectForRead();
    try {
      var commands = connection.async();
      principal(commands);
      return readExecution(commands, execution, tag);
    } finally {
      connection.closeAsync();
    }
  }

  private Map<String, byte[]> readExecution(
      RedisAsyncCommands<byte[], byte[]> commands,
      OriginalNewCapacityExecution execution,
      String tag) {
    byte[] key =
        bytes(
            "session:game:capacity-execution:{"
                + tag
                + "}:"
                + execution.request().admissionAttemptId());
    if (!persistent(commands, key, "hash")) throw unavailable();
    Map<byte[], byte[]> raw = await(commands.hgetall(key));
    Map<String, byte[]> result = new LinkedHashMap<>();
    raw.forEach(
        (name, value) -> {
          if (result.put(text(name), value) != null) throw unavailable();
        });
    return result;
  }

  /**
   * Bound connection establishment itself, including DNS/TLS/auth, to the incoming deadline. A late
   * connection cannot escape cancellation into a read; its resource worker retains admission until
   * physical connect and one asynchronous close attempt finish. No shared Redis client's options or
   * connection identity are changed.
   */
  StatefulRedisConnection<byte[], byte[]> connectForRead() {
    if (budget == null)
      return within(
          Long.MAX_VALUE, CanonicalGameplayOriginalNewCapacityExecutionOwner::connectForRead);
    var opened =
        new java.util.concurrent.CompletableFuture<StatefulRedisConnection<byte[], byte[]>>();
    long initialRemainingMillis = remainingMillis();
    var attempt = new ConnectAttempt(redis, opened);
    try {
      CONNECTS.execute(attempt);
    } catch (java.util.concurrent.RejectedExecutionException saturated) {
      attempt.abandonAndClose();
      throw unavailable();
    } catch (RuntimeException | Error submissionFailure) {
      attempt.abandonAndClose();
      throw submissionFailure;
    }
    try {
      var connection =
          opened.get(Math.min(initialRemainingMillis, remainingMillis()), TimeUnit.MILLISECONDS);
      if (!connection.isOpen()
          || connection.getOptions() == null
          || connection.getOptions().isAutoReconnect()) {
        throw unavailable();
      }
      // Failure before ownership is returned is disposed by the attempt's connection handoff.
      remainingMillis();
      return connection;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      attempt.abandonAndClose();
      throw unavailable();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException
        | RuntimeException failure) {
      attempt.abandonAndClose();
      throw unavailable();
    } catch (Error failure) {
      attempt.abandonAndClose();
      throw failure;
    }
  }

  private static final class ConnectAttempt implements Runnable {
    private final java.util.concurrent.CompletableFuture<StatefulRedisConnection<byte[], byte[]>>
        opened;
    private final FutureTask<Void> task;
    private final AtomicBoolean runStarted = new AtomicBoolean();
    private final java.util.concurrent.CompletableFuture<Void> physicalFinished =
        new java.util.concurrent.CompletableFuture<>();

    ConnectAttempt(
        RedisClient redis,
        java.util.concurrent.CompletableFuture<StatefulRedisConnection<byte[], byte[]>> opened) {
      this.opened = opened;
      task =
          new FutureTask<>(
              () -> {
                try {
                  opened.complete(redis.connect(ByteArrayCodec.INSTANCE));
                } catch (RuntimeException failure) {
                  opened.completeExceptionally(failure);
                } catch (Error failure) {
                  opened.completeExceptionally(failure);
                  throw failure;
                }
                return null;
              });
    }

    @Override
    public void run() {
      if (!runStarted.compareAndSet(false, true)) return;
      try {
        task.run();
      } finally {
        physicalFinished.complete(null);
      }
    }

    void abandonAndClose() {
      boolean cancelledBeforeCompletion = false;
      try {
        cancelledBeforeCompletion = task.cancel(true);
      } catch (RuntimeException | Error ignored) {
        // Keep waiting for the independent execution signal if interruption itself fails.
      }
      if (cancelledBeforeCompletion && !runStarted.get()) {
        physicalFinished.complete(null);
      }
      physicalFinished.join();
      // FutureTask cancellation can finish before its connector Runnable starts. Resolving the
      // uncancelled handoff then gives the resource worker sole ownership of any late connection.
      opened.complete(null);
      StatefulRedisConnection<byte[], byte[]> lateConnection =
          opened.handle((connection, failure) -> connection).join();
      if (lateConnection == null) return;
      try {
        lateConnection.closeAsync().handle((ignored, failure) -> null).join();
      } catch (RuntimeException | Error ignored) {
        // A failed close has uncertain disposal. Do not retry it or leak operation permits after
        // the close attempt has returned.
      }
    }
  }

  private long remainingMillis() {
    return budget.remainingMillis();
  }

  private DSLContext sql() {
    return budget.sql(dsl);
  }

  private CanonicalGameplayTenantGameplayTagRepository scopedTags() {
    return tags.withCapacityExecutionDsl(sql());
  }

  private CanonicalGameplayOriginalNewFinalAdmissionRedisOwner scopedFinalOwner() {
    return new CanonicalGameplayOriginalNewFinalAdmissionRedisOwner(
        redis, scripts, clock, scopedTags(), this::connectForRead, budget::remainingMillis);
  }

  private <T> T within(
      long deadline,
      java.util.function.Function<CanonicalGameplayOriginalNewCapacityExecutionOwner, T>
          operation) {
    noSql();
    if (budget != null) {
      budget.check();
      return operation.apply(this);
    }
    return CanonicalGameplayOriginalNewCapacityExecutionBudget.run(
        clock,
        deadline,
        scoped ->
            operation.apply(
                new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                    dsl, redis, scripts, clock, tags, scoped, null)));
  }

  private <T> T withinBackground(
      long deadline, Function<CanonicalGameplayOriginalNewCapacityExecutionOwner, T> operation) {
    noSql();
    if (budget != null) {
      budget.check();
      return operation.apply(this);
    }
    return CanonicalGameplayOriginalNewCapacityExecutionBudget.runBackground(
        clock,
        deadline,
        scoped ->
            operation.apply(
                new CanonicalGameplayOriginalNewCapacityExecutionOwner(
                    dsl, redis, scripts, clock, tags, scoped, null)));
  }

  private static void exactPhysicalBinding(
      Map<String, byte[]> fields, OriginalNewCapacityExecution execution, String state) {
    int size = "COMMITTED".equals(state) ? 6 : 3;
    if (!("AUTHORIZED".equals(state) || "COMMITTED".equals(state) || "CLOSED".equals(state))
        || fields.size() != size
        || !state.equals(text(fields.get("state")))
        || !Arrays.equals(execution.canonicalBytes(), fields.get("binding"))
        || !execution.digest().equals(text(fields.get("digest")))) throw unavailable();
  }

  private static Record find(DSLContext tx, OriginalNewCapacityExecution execution) {
    return tx.fetchOne(
        "SELECT * FROM " + TABLE + " WHERE execution_digest = ?", execution.digest());
  }

  private static void exact(Record row, OriginalNewCapacityExecution execution) {
    if (!Arrays.equals(execution.canonicalBytes(), row.get("canonical_execution", byte[].class))
        || !execution.transitionId().equals(row.get("transition_id", UUID.class))
        || !execution
            .request()
            .admissionAttemptId()
            .equals(row.get("admission_attempt_id", UUID.class))) throw unavailable();
  }

  private static String receiptDigest(Record row, OriginalNewCapacityExecution execution) {
    return receiptDigest(
        execution,
        row.get("original_new_final_session_sha256", String.class),
        row.get("original_new_admission_at_ms", Long.class),
        row.get("original_new_continuity_expires_at_ms", Long.class),
        row.get("original_new_session_duration_ms", Long.class));
  }

  private static String receiptDigest(
      OriginalNewCapacityExecution execution,
      String finalSessionSha256,
      long admissionAtMillis,
      long continuityExpiresAtMillis,
      long durationMillis) {
    String receipt =
        execution.digest()
            + "/"
            + finalSessionSha256
            + "/"
            + admissionAtMillis
            + "/"
            + continuityExpiresAtMillis
            + "/"
            + durationMillis;
    return "sha256:" + OriginalNewCapacityExecution.sha256(bytes(receipt));
  }

  private static String closedReceipt(OriginalNewCapacityExecution execution) {
    return "sha256:"
        + OriginalNewCapacityExecution.sha256(
            bytes("CLOSED/" + execution.digest() + "/" + execution.candidateDigest()));
  }

  private boolean persistent(RedisAsyncCommands<byte[], byte[]> commands, byte[] key, String type) {
    return type.equals(await(commands.type(key)))
        && Long.valueOf(-1).equals(await(commands.pttl(key)));
  }

  private void principal(RedisAsyncCommands<byte[], byte[]> commands) {
    if (!"gamesession_coord_app".equals(await(commands.aclWhoami()))) throw unavailable();
  }

  private <T> T await(RedisFuture<T> future) {
    try {
      return future.get(remainingMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw unavailable();
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException
        | RuntimeException failure) {
      future.cancel(true);
      throw unavailable();
    }
  }

  private static String sha1(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException unavailable) {
      throw new IllegalStateException(unavailable);
    }
  }

  private static String text(byte[] bytes) {
    if (bytes == null) throw unavailable();
    for (byte value : bytes) if ((value & 0x80) != 0) throw unavailable();
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static void noSql() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive()) throw unavailable();
  }

  private static IllegalStateException unavailable() {
    return new IllegalStateException("Original NEW capacity execution is indeterminate");
  }

  public record Snapshot(
      OriginalNewCapacityExecution execution,
      String disposition,
      String physicalDigest,
      String receiptDigest,
      byte[] canonicalReadback,
      String currentOwnerId,
      long currentOwnerFence) {
    public Snapshot {
      canonicalReadback = canonicalReadback.clone();
    }

    @Override
    public byte[] canonicalReadback() {
      return canonicalReadback.clone();
    }

    @Override
    public String toString() {
      return "OriginalNewCapacitySnapshot[redacted]";
    }
  }
}
