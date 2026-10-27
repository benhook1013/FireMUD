package net.firedevops.firemud.gamesession.repository;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.AclSetuserArgs;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.protocol.CommandKeyword;
import io.lettuce.core.protocol.CommandType;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.account.admission.AccountGameplayAdmissionLeaseEvidence;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.GameSessionAccountDelegationProfile;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionAbortSource;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayAdmissionDecision;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingRegionBridgeObligation;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionRequest;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import net.firedevops.firemud.gamesession.binding.CanonicalIssuerReservationFenceEvidence;
import net.firedevops.firemud.gamesession.client.CanonicalPreseededActorRuntimeReferenceClient;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionBindingBridgeRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalGameplayRegionLeaseRedisScriptContribution;
import net.firedevops.firemud.gamesession.service.CanonicalInitialPlayerLocationRegionResolver;
import net.firedevops.firemud.worldmanagement.v1.CanonicalInitialPlayerLocationRequest;
import org.jooq.DSLContext;
import org.jooq.Record;

/**
 * Test-only wrapper for the package-private Game Session region owners. It starts from an actual
 * current World/Entity source and the actual SQL transition repository; only the upstream Account
 * lease carrier, issuer reservation, and stable Redis incarnation are controlled test evidence.
 */
public final class CanonicalGameplayRegionOwnerCompositionFixture {
  private static final String REDIS_PRINCIPAL = "gamesession_coord_app";
  private static final String REDIS_PASSWORD = UUID.randomUUID().toString();
  private static final String REGION_ATTESTATION = "controlled-region-owner-composition";

  private CanonicalGameplayRegionOwnerCompositionFixture() {}

  /**
   * Attaches actual region ownership to an already installed original candidate. Upstream World and
   * Entity clients remain caller-owned; this path creates no alternate transition or ACK.
   */
  static ExistingBinding installExistingOriginalCandidate(
      DSLContext dsl,
      RedisClient coordinationRedis,
      UUID transitionId,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient) {
    var runtimeIdentity =
        new RuntimeIdentity(
            "game-session-service",
            "final-owner-composition",
            "localhost",
            Instant.now(),
            "test",
            "test",
            "controlled-test-image");
    var owner =
        CanonicalGameplayRegionBindingBridgeOwnerFactory.compose(
            dsl,
            coordinationRedis,
            RedisScriptCatalog.loadInstalled(
                CanonicalGameplayRegionOwnerCompositionFixture.class.getClassLoader()),
            new ControlledStableIncarnationSource(),
            worldRegionResolver,
            entityReferenceClient,
            runtimeIdentity,
            Duration.ofSeconds(30),
            Clock.systemUTC());
    var installation = owner.installForTransition(transitionId, worldRequest);
    var acknowledgement = owner.bridge(installation);
    return new ExistingBinding(owner, installation, acknowledgement);
  }

  record ExistingBinding(
      CanonicalGameplayRegionBindingBridgeOwner owner,
      CanonicalGameplayRegionLeaseOwner.Installation installation,
      CanonicalGameplayBindingInventoryRepository.RegionBridgeAcknowledgement acknowledgement) {}

  /**
   * Creates one real first-NEW provisional candidate from the exact placement selector, installs
   * the first region timeline and SQL fence, then retries the same binding bridge as a lost reply.
   */
  public static Proof installAndBridge(
      DSLContext dsl,
      String redisHost,
      int redisPort,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalPlayableTarget currentTarget,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient)
      throws Exception {
    return installAndBridgeInternal(
            dsl,
            redisHost,
            redisPort,
            worldRequest,
            currentTarget,
            worldRegionResolver,
            entityReferenceClient,
            null)
        .first();
  }

  /** Runs the real first owner chain, then exercises a second candidate under its private grant. */
  public static TwoBindingProof installAndBridgeTwoCandidates(
      DSLContext dsl,
      String redisHost,
      int redisPort,
      CanonicalInitialPlayerLocationRequest firstWorldRequest,
      CanonicalPlayableTarget currentTarget,
      CanonicalInitialPlayerLocationRegionResolver firstWorldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient firstEntityReferenceClient,
      SecondBinding secondBinding)
      throws Exception {
    return installAndBridgeInternal(
        dsl,
        redisHost,
        redisPort,
        firstWorldRequest,
        currentTarget,
        firstWorldRegionResolver,
        firstEntityReferenceClient,
        java.util.Objects.requireNonNull(secondBinding, "secondBinding"));
  }

  private static TwoBindingProof installAndBridgeInternal(
      DSLContext dsl,
      String redisHost,
      int redisPort,
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalPlayableTarget currentTarget,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient,
      SecondBinding secondBinding)
      throws Exception {
    UUID transitionId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    var resolved = worldRegionResolver.resolve(worldRequest);
    requireExactCurrentTarget(worldRequest, currentTarget);
    var identity =
        new CanonicalGameplayBindingIdentity(
            UUID.fromString(worldRequest.getCanonicalAccountUuid()),
            currentTarget.canonicalTenantId(),
            currentTarget.playableStateNamespaceId(),
            UUID.fromString(worldRequest.getCharacterUuid()),
            currentTarget.playableStateScope(),
            currentTarget.canonicalGameInstanceId(),
            currentTarget.gameInstanceId(),
            sessionId.toString(),
            resolved.operationalRegionId(),
            BigInteger.ONE,
            GameSessionAccountDelegationProfile.ISSUER,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.ONE,
            BigInteger.valueOf(64));

    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    establishActualLegacyDisposition(dsl);
    var transitionRequest =
        new CanonicalGameplayBindingTransitionRequest(
            transitionId,
            UUID.randomUUID(),
            identity,
            new CanonicalIssuerReservationFenceEvidence(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                BigInteger.ONE,
                BigInteger.ONE,
                BigInteger.ONE),
            null,
            null);
    var prepared = inventory.prepare(transitionRequest);
    AccountGameplayAdmissionLeaseEvidence lease =
        controlledNewBindingLease(identity, currentTarget, prepared.bindingGeneration());
    var decision =
        new CanonicalGameplayAdmissionDecision(
            transitionId, lease, identity, prepared.bindingGeneration(), null, currentTarget);
    inventory.markProvisional(inventory.readProvisionalCasEvidence(transitionId), decision);

    try (var redis = RegionRedis.open(redisHost, redisPort)) {
      var scriptCatalog =
          RedisScriptCatalog.fromContributions(
              List.of(
                  new CanonicalGameplayRegionLeaseRedisScriptContribution(),
                  new CanonicalGameplayRegionBindingBridgeRedisScriptContribution()));
      var stableSource = new ControlledStableIncarnationSource();
      var runtimeIdentity =
          new RuntimeIdentity(
              "game-session-service",
              "region-owner-composition",
              "localhost",
              Instant.now(),
              "test",
              "test",
              "controlled-test-image");
      CanonicalPreseededActorRuntimeReferenceClient bridgeEntityReferenceClient =
          secondBinding == null ? entityReferenceClient : secondBinding.entityReferenceClient();
      var bridgeOwner =
          CanonicalGameplayRegionBindingBridgeOwnerFactory.compose(
              dsl,
              redis.ownerClient(),
              scriptCatalog,
              stableSource,
              worldRegionResolver,
              bridgeEntityReferenceClient,
              runtimeIdentity,
              Duration.ofSeconds(30),
              Clock.systemUTC());
      var installation = bridgeOwner.installForTransition(transitionId, worldRequest);
      var firstAcknowledgement = bridgeOwner.bridge(installation);
      var lostReplyRetry = bridgeOwner.bridge(installation);
      var acknowledged = firstAcknowledgement.obligation();
      var replayed = lostReplyRetry.obligation();
      if (!acknowledged.equals(replayed)
          || acknowledged.status()
              != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED) {
        throw new IllegalStateException("Exact first-NEW region bridge retry changed its SQL ACK");
      }
      requireMaterializedBridgeCaptured(dsl, transitionId);
      var status =
          new CanonicalGameplayRegionRuntimeStatusRepository(dsl)
              .find(installation.source)
              .orElseThrow();
      if (status.paused()
          || status.regionEpoch() != 1L
          || status.lastCommittedTickId() != -1L
          || !"game-session-service".equals(status.ownerService())) {
        throw new IllegalStateException("Initial region SQL fence is not the expected live owner");
      }
      String initialMetaKey =
          CanonicalGameplayRegionRedisKeys.meta(
              identity.tenantId(), identity.gameInstanceId(), status.regionId());
      byte[] redisKey =
          ascii(
              CanonicalGameplayRegionRedisKeys.lease(
                  identity.tenantId(), identity.gameInstanceId(), status.regionId()));
      byte[] currentTickState =
          redis.admin().hget(ascii(initialMetaKey), ascii("current_tick_state"));
      if (!Arrays.equals(currentTickState, ascii("APPLIED"))) {
        throw new IllegalStateException("Canonical Redis initial timeline metadata is absent");
      }
      Proof firstProof =
          new Proof(
              transitionId,
              sessionId,
              identity.regionId(),
              resolved.canonicalRegionInstanceId(),
              status.lifecycleEpoch(),
              status.lifecycleRowVersion(),
              status.regionEpoch(),
              status.executorFence(),
              acknowledged.bridgeOperationId(),
              acknowledged.runtimeEntityId(),
              acknowledged.entityProjectionGeneration(),
              acknowledged.entityProjectionFence(),
              acknowledged.entityReferenceDigest(),
              acknowledged.worldSourceDigest(),
              acknowledged.redisReadbackDigest(),
              acknowledged.inventoryRevision(),
              acknowledged.acknowledgedAt(),
              acknowledged.equals(replayed));
      if (secondBinding == null) {
        return new TwoBindingProof(firstProof, null, null, true, true, true, true, true);
      }

      AccountAbortProof firstAbort = abortAfterBridge(dsl, firstProof);
      Record firstTimelineBefore =
          dsl.fetchOne(
              "SELECT initialization_operation_id, transition_id, lifecycle_request_bytes,"
                  + " preparation_digest, world_source_digest"
                  + " FROM game_session_region_timeline_preparation"
                  + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
              identity.tenantId(),
              identity.gameInstanceId(),
              identity.regionId());
      Objects.requireNonNull(firstTimelineBefore, "first timeline source row");
      String firstTimelineOperationId =
          Objects.requireNonNull(
                  firstTimelineBefore.get("initialization_operation_id", UUID.class),
                  "first initialization operation")
              .toString();
      String firstTimelineDigest = firstTimelineBefore.get("preparation_digest", String.class);
      String firstWorldSourceDigest = firstTimelineBefore.get("world_source_digest", String.class);
      byte[] firstLifecycleRequest =
          firstTimelineBefore.get("lifecycle_request_bytes", byte[].class);
      byte[] firstMetaOperation =
          redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_operation_id"));
      byte[] firstMetaDigest =
          redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_source_digest"));

      var secondResolved =
          secondBinding.worldRegionResolver().resolve(secondBinding.worldRequest());
      requireExactCurrentTarget(secondBinding.worldRequest(), secondBinding.currentTarget());
      UUID secondTransitionId = UUID.randomUUID();
      UUID secondSessionId = UUID.randomUUID();
      var secondIdentity =
          new CanonicalGameplayBindingIdentity(
              UUID.fromString(secondBinding.worldRequest().getCanonicalAccountUuid()),
              secondBinding.currentTarget().canonicalTenantId(),
              secondBinding.currentTarget().playableStateNamespaceId(),
              UUID.fromString(secondBinding.worldRequest().getCharacterUuid()),
              secondBinding.currentTarget().playableStateScope(),
              secondBinding.currentTarget().canonicalGameInstanceId(),
              secondBinding.currentTarget().gameInstanceId(),
              secondSessionId.toString(),
              secondResolved.operationalRegionId(),
              BigInteger.ONE,
              GameSessionAccountDelegationProfile.ISSUER,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.ONE,
              BigInteger.valueOf(64));
      var secondPrepared =
          inventory.prepare(
              new CanonicalGameplayBindingTransitionRequest(
                  secondTransitionId,
                  UUID.randomUUID(),
                  secondIdentity,
                  new CanonicalIssuerReservationFenceEvidence(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      BigInteger.ONE,
                      BigInteger.ONE,
                      BigInteger.ONE),
                  null,
                  null));
      AccountGameplayAdmissionLeaseEvidence secondLease =
          controlledNewBindingLease(
              secondIdentity, secondBinding.currentTarget(), secondPrepared.bindingGeneration());
      inventory.markProvisional(
          inventory.readProvisionalCasEvidence(secondTransitionId),
          new CanonicalGameplayAdmissionDecision(
              secondTransitionId,
              secondLease,
              secondIdentity,
              secondPrepared.bindingGeneration(),
              null,
              secondBinding.currentTarget()));
      var secondInstallation =
          bridgeOwner.installForTransition(
              installation, secondTransitionId, secondBinding.worldRequest());
      var secondAcknowledgement = bridgeOwner.bridge(secondInstallation).obligation();
      var secondLostReplyRetry = bridgeOwner.bridge(secondInstallation).obligation();
      requireMaterializedBridgeCaptured(dsl, secondTransitionId);
      if (!secondAcknowledgement.equals(secondLostReplyRetry)
          || secondAcknowledgement.status()
              != CanonicalGameplayBindingRegionBridgeObligation.Status.ACKNOWLEDGED
          || secondAcknowledgement.transitionId().equals(acknowledged.transitionId())
          || secondAcknowledgement.bridgeOperationId().equals(acknowledged.bridgeOperationId())
          || !secondAcknowledgement
              .inventoryRevision()
              .equals(secondLostReplyRetry.inventoryRevision())) {
        throw new IllegalStateException("Second binding did not retain its independent exact ACK");
      }
      var secondStatus =
          new CanonicalGameplayRegionRuntimeStatusRepository(dsl)
              .find(secondInstallation.source)
              .orElseThrow();
      Record firstTimelineAfter =
          dsl.fetchOne(
              "SELECT initialization_operation_id, transition_id, lifecycle_request_bytes,"
                  + " preparation_digest, world_source_digest"
                  + " FROM game_session_region_timeline_preparation"
                  + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?",
              identity.tenantId(),
              identity.gameInstanceId(),
              identity.regionId());
      byte[] secondMetaOperation =
          redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_operation_id"));
      byte[] secondMetaDigest =
          redis.admin().hget(ascii(initialMetaKey), ascii("first_timeline_source_digest"));
      byte[] secondMetaRegionEpoch =
          redis.admin().hget(ascii(initialMetaKey), ascii("region_epoch"));
      byte[] secondMetaTickId = redis.admin().hget(ascii(initialMetaKey), ascii("current_tick_id"));
      byte[] secondMetaTickState =
          redis.admin().hget(ascii(initialMetaKey), ascii("current_tick_state"));
      Objects.requireNonNull(firstTimelineAfter, "retained timeline source row");
      boolean unchangedTimeline =
          firstTimelineOperationId.equals(
                  Objects.requireNonNull(
                          firstTimelineAfter.get("initialization_operation_id", UUID.class),
                          "retained initialization operation")
                      .toString())
              && transitionId.equals(firstTimelineAfter.get("transition_id", UUID.class))
              && Arrays.equals(
                  firstLifecycleRequest,
                  firstTimelineAfter.get("lifecycle_request_bytes", byte[].class))
              && firstTimelineDigest.equals(
                  firstTimelineAfter.get("preparation_digest", String.class))
              && firstWorldSourceDigest.equals(
                  firstTimelineAfter.get("world_source_digest", String.class))
              && Arrays.equals(firstMetaOperation, secondMetaOperation)
              && Arrays.equals(firstMetaDigest, secondMetaDigest)
              && Arrays.equals(secondMetaRegionEpoch, ascii("1"))
              && Arrays.equals(secondMetaTickId, ascii("-1"))
              && Arrays.equals(secondMetaTickState, ascii("APPLIED"))
              && secondStatus.regionEpoch() == 1L
              && secondStatus.lastCommittedTickId() == -1L;
      if (!unchangedTimeline
          || !status.leaseTokenCorrelation().equals(secondStatus.leaseTokenCorrelation())
          || redis.admin().pttl(redisKey) <= 0L
          || redis.admin().pttl(ascii(initialMetaKey)) != -1L) {
        throw new IllegalStateException(
            "Second binding changed first timeline provenance or lost the live region grant");
      }
      boolean regionLeaseStillLive = redis.admin().pttl(redisKey) > 0L;
      List<List<String>> sqlBeforeForeignOwner =
          snapshotRegionOwnerSqlState(
              dsl, identity.tenantId(), identity.gameInstanceId(), identity.regionId());
      RegionRedisState redisBeforeForeignOwner =
          snapshotRegionRedisState(redis, redisKey, initialMetaKey);
      var foreignBridgeOwner =
          CanonicalGameplayRegionBindingBridgeOwnerFactory.compose(
              dsl,
              redis.ownerClient(),
              scriptCatalog,
              stableSource,
              secondBinding.worldRegionResolver(),
              secondBinding.entityReferenceClient(),
              runtimeIdentity,
              Duration.ofSeconds(30),
              Clock.systemUTC());
      boolean foreignOwnerInstallationRejected;
      try {
        foreignBridgeOwner.installForTransition(
            installation, secondTransitionId, secondBinding.worldRequest());
        foreignOwnerInstallationRejected = false;
      } catch (IllegalStateException expected) {
        foreignOwnerInstallationRejected =
            "Canonical Game Session region owner requires current exact assignment and lease evidence"
                .equals(expected.getMessage());
      }
      boolean foreignOwnerCapabilityRejectedWithoutStateChange =
          foreignOwnerInstallationRejected
              && sqlBeforeForeignOwner.equals(
                  snapshotRegionOwnerSqlState(
                      dsl, identity.tenantId(), identity.gameInstanceId(), identity.regionId()))
              && redisBeforeForeignOwner.equals(
                  snapshotRegionRedisState(redis, redisKey, initialMetaKey))
              && redis.admin().pttl(redisKey) > 0L
              && redis.admin().pttl(ascii(initialMetaKey)) == -1L;
      if (!foreignOwnerCapabilityRejectedWithoutStateChange) {
        throw new IllegalStateException(
            "A foreign region owner accepted or changed state for another owner's installation");
      }
      redis.admin().pexpire(redisKey, 0L);
      List<List<String>> sqlBeforeExpiredGrant =
          snapshotRegionOwnerSqlState(
              dsl, identity.tenantId(), identity.gameInstanceId(), identity.regionId());
      RegionRedisState redisBeforeExpiredGrant =
          snapshotRegionRedisState(redis, redisKey, initialMetaKey);
      boolean expiredGrantInstallationRejected;
      try {
        bridgeOwner.installForTransition(
            secondInstallation, secondTransitionId, secondBinding.worldRequest());
        expiredGrantInstallationRejected = false;
      } catch (IllegalStateException expected) {
        expiredGrantInstallationRejected = true;
      }
      boolean expiredGrantRejectedWithoutStateChange =
          expiredGrantInstallationRejected
              && sqlBeforeExpiredGrant.equals(
                  snapshotRegionOwnerSqlState(
                      dsl, identity.tenantId(), identity.gameInstanceId(), identity.regionId()))
              && redisBeforeExpiredGrant.equals(
                  snapshotRegionRedisState(redis, redisKey, initialMetaKey))
              && secondStatus.equals(
                  new CanonicalGameplayRegionRuntimeStatusRepository(dsl)
                      .find(secondInstallation.source)
                      .orElseThrow());
      if (!expiredGrantRejectedWithoutStateChange) {
        throw new IllegalStateException(
            "An expired private region grant was accepted or changed SQL/Redis state");
      }
      Proof secondProof =
          new Proof(
              secondTransitionId,
              secondSessionId,
              secondIdentity.regionId(),
              secondResolved.canonicalRegionInstanceId(),
              secondStatus.lifecycleEpoch(),
              secondStatus.lifecycleRowVersion(),
              secondStatus.regionEpoch(),
              secondStatus.executorFence(),
              secondAcknowledgement.bridgeOperationId(),
              secondAcknowledgement.runtimeEntityId(),
              secondAcknowledgement.entityProjectionGeneration(),
              secondAcknowledgement.entityProjectionFence(),
              secondAcknowledgement.entityReferenceDigest(),
              secondAcknowledgement.worldSourceDigest(),
              secondAcknowledgement.redisReadbackDigest(),
              secondAcknowledgement.inventoryRevision(),
              secondAcknowledgement.acknowledgedAt(),
              secondAcknowledgement.equals(secondLostReplyRetry));
      return new TwoBindingProof(
          firstProof,
          secondProof,
          firstAbort,
          unchangedTimeline,
          Arrays.equals(firstMetaOperation, secondMetaOperation)
              && Arrays.equals(firstMetaDigest, secondMetaDigest),
          regionLeaseStillLive,
          foreignOwnerCapabilityRejectedWithoutStateChange,
          expiredGrantRejectedWithoutStateChange);
    }
  }

  private static boolean sameAbortSource(
      CanonicalGameplayAdmissionAbortSource expected,
      CanonicalGameplayAdmissionAbortSource actual) {
    var left = expected.originalDecision();
    var right = actual.originalDecision();
    // Independently loaded decisions are verified carriers, not value records.
    return expected.transition().equals(actual.transition())
        && expected.candidate().equals(actual.candidate())
        && expected.reservation().equals(actual.reservation())
        && Objects.equals(expected.orphanCleanupId(), actual.orphanCleanupId())
        && left.bindingDecisionId().equals(right.bindingDecisionId())
        && left.leaseEvidenceJson().equals(right.leaseEvidenceJson())
        && left.leaseSha256().equals(right.leaseSha256())
        && left.requestId().equals(right.requestId())
        && left.leaseId().equals(right.leaseId())
        && left.leaseFence().equals(right.leaseFence())
        && left.candidate().equals(right.candidate())
        && left.bindingGeneration().equals(right.bindingGeneration())
        && Objects.equals(left.expectedOldBindingGeneration(), right.expectedOldBindingGeneration())
        && Objects.equals(left.resumeEpisodeId(), right.resumeEpisodeId())
        && left.targetEvidence().equals(right.targetEvidence());
  }

  /**
   * Carries a controlled definitive Account abort through the actual repository after a real
   * PostgreSQL/Redis bridge ACK, then proves exact cleanup retry/read preserves that ACK without
   * promoting the candidate or claiming cleanup completion.
   */
  private static void requireMaterializedBridgeCaptured(DSLContext dsl, UUID transitionId) {
    String exactRow =
        dsl.fetchSingle(
                "SELECT to_jsonb(g)::text FROM game_session_canonical_binding_region_bridge_obligation g"
                    + " WHERE transition_id = ? AND bridge_operation_id IS NOT NULL",
                transitionId)
            .get(0, String.class);
    java.math.BigDecimal revision =
        dsl.fetchSingle(
                "SELECT inventory_revision FROM game_session_canonical_binding_inventory_clock"
                    + " WHERE singleton_id = 1")
            .get(0, java.math.BigDecimal.class);
    var captured =
        dsl
            .fetch(
                CanonicalGameplayIssuerCohortSql.cohortSql(),
                CanonicalGameplayIssuerCohortSql.cohortBindings(
                    GameSessionAccountDelegationProfile.ISSUER, revision))
            .stream()
            .filter(
                row ->
                    "REGION_OBLIGATION".equals(row.get("source_kind", String.class))
                        && transitionId.toString().equals(row.get("source_key", String.class)))
            .toList();
    if (captured.size() != 1
        || !Objects.equals(exactRow, captured.getFirst().get("source_row_json", String.class))) {
      throw new IllegalStateException("Issuer cohort omitted materialized region bridge evidence");
    }
  }

  public static AccountAbortProof abortAfterBridge(DSLContext dsl, Proof bridgeProof) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var before = inventory.readFirstNewAdmissionAbortSource(bridgeProof.transitionId());
    Record acknowledgedBefore =
        dsl.fetchOne(
            "SELECT status, inventory_revision, redis_readback_digest, acknowledged_at"
                + " FROM game_session_canonical_binding_region_bridge_obligation"
                + " WHERE transition_id = ?",
            bridgeProof.transitionId());
    if (acknowledgedBefore == null
        || !"ACKNOWLEDGED".equals(acknowledgedBefore.get("status", String.class))
        || !bridgeProof
            .acknowledgedInventoryRevision()
            .equals(
                new BigInteger(
                    Objects.requireNonNull(
                            acknowledgedBefore.get("inventory_revision"), "original ACK revision")
                        .toString()))
        || !bridgeProof
            .redisReadbackDigest()
            .equals(acknowledgedBefore.get("redis_readback_digest", String.class))) {
      throw new IllegalStateException("The exact region ACK is not the retained abort source");
    }
    String acknowledgementImageBefore =
        dsl.fetchSingle(
                "SELECT (to_jsonb(obligation) - 'inventory_revision')::text AS image"
                    + " FROM game_session_canonical_binding_region_bridge_obligation obligation"
                    + " WHERE transition_id = ?",
                bridgeProof.transitionId())
            .get("image", String.class);
    UUID cleanupId =
        UUID.nameUUIDFromBytes(
            ("controlled-region-bridge-account-abort/" + bridgeProof.transitionId())
                .getBytes(StandardCharsets.UTF_8));
    var aborted = inventory.recordFirstNewAccountAbort(before, cleanupId);
    var retrySource = inventory.readFirstNewAdmissionAbortSource(bridgeProof.transitionId());
    var retry = inventory.recordFirstNewAccountAbort(retrySource, cleanupId);
    var retained =
        inventory.readRetainedDefinitiveFirstNewAccountAbortSource(bridgeProof.transitionId());
    Record acknowledgedAfter =
        dsl.fetchOne(
            "SELECT status, inventory_revision, redis_readback_digest, acknowledged_at"
                + " FROM game_session_canonical_binding_region_bridge_obligation"
                + " WHERE transition_id = ?",
            bridgeProof.transitionId());
    Objects.requireNonNull(acknowledgedAfter, "retained region ACK row");
    String acknowledgementImageAfter =
        dsl.fetchSingle(
                "SELECT (to_jsonb(obligation) - 'inventory_revision')::text AS image"
                    + " FROM game_session_canonical_binding_region_bridge_obligation obligation"
                    + " WHERE transition_id = ?",
                bridgeProof.transitionId())
            .get("image", String.class);
    List<String> abortFailures = new ArrayList<>();
    if (!sameAbortSource(aborted, retry)) {
      abortFailures.add("abort retry changed the retained terminal result");
    }
    if (!sameAbortSource(aborted, retained)) {
      abortFailures.add("retained abort read changed the terminal result");
    }
    if (!aborted.isAccountAborted()) {
      abortFailures.add("the original Account abort is not retained");
    }
    if (!cleanupId.equals(aborted.orphanCleanupId())) {
      abortFailures.add("the abort changed its exact orphan cleanup identity");
    }
    if (aborted.candidate().lifecycle()
        != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventoryEntry
            .Lifecycle.TERMINAL_UNRESOLVED) {
      abortFailures.add("the aborted candidate is not TERMINAL_UNRESOLVED");
    }
    if (aborted.reservation().lifecycle()
        != net.firedevops.firemud.gamesession.binding.CanonicalIssuerPartitionReservation.Lifecycle
            .RELEASE_PENDING) {
      abortFailures.add("the aborted reservation is not RELEASE_PENDING");
    }
    if (!"ACKNOWLEDGED".equals(acknowledgedAfter.get("status", String.class))) {
      abortFailures.add("the retained region ACK status changed");
    }
    if (!aborted
        .transition()
        .inventoryRevision()
        .equals(acknowledgedAfter.get("inventory_revision", BigInteger.class))) {
      abortFailures.add("the retained region ACK revision differs from the aborted transition");
    }
    if (!bridgeProof
        .redisReadbackDigest()
        .equals(acknowledgedAfter.get("redis_readback_digest", String.class))) {
      abortFailures.add("the retained region ACK Redis readback digest changed");
    }
    Instant persistedAcknowledgedAt = bridgeProof.acknowledgedAt().truncatedTo(ChronoUnit.MICROS);
    if (!persistedAcknowledgedAt.equals(
        acknowledgedAfter.get("acknowledged_at", java.time.OffsetDateTime.class).toInstant())) {
      abortFailures.add(
          "the retained region ACK timestamp changed after SQL precision normalization");
    }
    if (!acknowledgedBefore
        .get("acknowledged_at", java.time.OffsetDateTime.class)
        .equals(acknowledgedAfter.get("acknowledged_at", java.time.OffsetDateTime.class))) {
      abortFailures.add("the region ACK timestamp changed across the Account abort");
    }
    if (!acknowledgementImageBefore.equals(acknowledgementImageAfter)) {
      abortFailures.add("the retained region ACK row image changed");
    }
    if (!aborted
        .transition()
        .inventoryRevision()
        .equals(
            new BigInteger(
                Objects.requireNonNull(
                        acknowledgedAfter.get("inventory_revision"), "retained ACK revision")
                    .toString()))) {
      abortFailures.add(
          "the retained region ACK revision changed in its SQL numeric representation");
    }
    if (aborted.transition().status()
        != net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingTransitionSnapshot
            .Status.ABORTED) {
      abortFailures.add("the aborted transition does not remain ABORTED");
    }
    if (!abortFailures.isEmpty()) {
      throw new IllegalStateException(
          "Definitive Account abort composition failed: " + String.join("; ", abortFailures));
    }
    return new AccountAbortProof(
        bridgeProof.transitionId(),
        cleanupId,
        before.transition().inventoryRevision(),
        aborted.transition().inventoryRevision(),
        acknowledgementImageBefore.equals(acknowledgementImageAfter),
        aborted.candidate().lifecycle().name(),
        aborted.reservation().lifecycle().name());
  }

  private static void requireExactCurrentTarget(
      CanonicalInitialPlayerLocationRequest worldRequest, CanonicalPlayableTarget target) {
    var expected = worldRequest.getExpectedTarget();
    if (!target.targetNamespace().equals("firemud")
        || !target.canonicalTenantId().toString().equals(expected.getCanonicalTenantUuid())
        || !target.realmId().toString().equals(expected.getRealmUuid())
        || !target.worldSlug().equals(expected.getWorldSlug())
        || !target.realmSlug().equals(expected.getRealmSlug())
        || !target.canonicalGameInstanceId().toString().equals(expected.getGameInstanceId())
        || target.catalogRevision() != expected.getCatalogRevision()
        || !target.canonicalVersionId().toString().equals(expected.getCanonicalVersionUuid())
        || !target
            .playableStateNamespaceId()
            .toString()
            .equals(expected.getPlayableStateNamespaceUuid())
        || !target
            .playableStateScope()
            .equals(
                expected.getPlayableStateScope()
                        == net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                            .PLAYABLE_STATE_SCOPE_SHARED
                    ? "SHARED"
                    : expected.getPlayableStateScope()
                            == net.firedevops.firemud.entitymanagement.v1.PlayableStateScope
                                .PLAYABLE_STATE_SCOPE_ISOLATED
                        ? "ISOLATED"
                        : "UNSPECIFIED")) {
      throw new IllegalArgumentException("World placement is not the exact current GS target");
    }
  }

  private static AccountGameplayAdmissionLeaseEvidence controlledNewBindingLease(
      CanonicalGameplayBindingIdentity identity,
      CanonicalPlayableTarget target,
      BigInteger bindingGeneration) {
    Instant now = Instant.now();
    long nowMillis = now.toEpochMilli();
    long nowSeconds = now.getEpochSecond();
    UUID requestId = UUID.randomUUID();
    UUID leaseId = UUID.randomUUID();
    String account = identity.accountId().toString();
    String tenant = identity.tenantId().toString();

    Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("schema", AccountGameplayAdmissionLeaseEvidence.SCHEMA);
    carrier.put("schemaVersion", "1");
    carrier.put("mode", "PUBLIC_PRODUCTION");
    carrier.put("targetNamespace", target.targetNamespace());
    carrier.put(
        "callerWorkload",
        "spiffe://firemud/ns/" + target.targetNamespace() + "/sa/game-session-service");
    carrier.put("requestId", requestId.toString());
    carrier.put("leaseId", leaseId.toString());
    carrier.put("leaseFence", "1");
    carrier.put("leaseKind", "NEW_BINDING");

    Map<String, Object> scope = new LinkedHashMap<>();
    scope.put("accountId", account);
    scope.put("tenantId", tenant);
    scope.put("realmId", target.realmId().toString());
    scope.put("worldSlug", target.worldSlug());
    scope.put("realmSlug", target.realmSlug());
    scope.put("playableStateNamespaceId", target.playableStateNamespaceId().toString());
    scope.put("playableStateScope", target.playableStateScope());
    scope.put("gameInstanceId", target.canonicalGameInstanceId().toString());
    scope.put("characterId", identity.characterId().toString());
    scope.put("sessionId", identity.sessionId());
    scope.put("bindingGeneration", bindingGeneration.toString());
    scope.put("catalogRevision", Long.toString(target.catalogRevision()));
    scope.put("pointerVersion", Long.toString(target.pointerVersion()));
    scope.put("regionId", identity.regionId().toString());
    scope.put("regionEpoch", identity.regionEpoch().toString());
    carrier.put("bindingScope", scope);

    carrier.put(
        "authorityTuple",
        Map.of(
            "issuerAuthGeneration", "1",
            "accountAuthorityGeneration", "1",
            "tenantAuthorityGeneration", Map.of(tenant, "1"),
            "membershipAuthorityGeneration", Map.of(tenant, "1"),
            "privateRealmGrantVersions", List.of()));
    carrier.put("issuanceFence", "1");
    carrier.put(
        "membershipBaseline",
        Map.of(
            "membershipLifecycleState", "ACTIVE",
            "membershipVersion", Map.of(tenant, "1"),
            "membershipAuthorityGeneration", "1"));

    String prefix = "account:auth-authority:v1:";
    List<Map<String, String>> checkpoints = new ArrayList<>();
    for (var coordinate :
        List.of(
            Map.entry(prefix + "account/" + account, "0"),
            Map.entry(prefix + "issuer/" + GameSessionAccountDelegationProfile.ISSUER, "0"),
            Map.entry(prefix + "membership/" + account + "/" + tenant, "1"),
            Map.entry(prefix + "tenant/" + tenant, "1"))) {
      checkpoints.add(
          Map.of("outboxStreamKey", coordinate.getKey(), "outboxSequence", coordinate.getValue()));
    }
    checkpoints.sort(Comparator.comparing(value -> value.get("outboxStreamKey")));
    carrier.put("outboxCheckpoints", checkpoints);

    carrier.put(
        "tokenIdentityEvidence",
        Map.ofEntries(
            Map.entry("accountId", account),
            Map.entry("operationId", UUID.randomUUID().toString()),
            Map.entry("issuanceRequestId", UUID.randomUUID().toString()),
            Map.entry("tokenJti", UUID.randomUUID().toString()),
            Map.entry("tokenSHA256", "a".repeat(64)),
            Map.entry("tokenGeneration", "1"),
            Map.entry("issuanceFence", "1"),
            Map.entry("tokenIdentityFence", "1"),
            Map.entry("tokenProfile", GameSessionAccountDelegationProfile.PROFILE),
            Map.entry("issuedAt", Long.toString(nowSeconds)),
            Map.entry("notBefore", Long.toString(nowSeconds)),
            Map.entry("expiresAt", Long.toString(nowSeconds + 30))));
    carrier.put("evaluatedAt", Long.toString(nowMillis));
    carrier.put("expiresAt", Long.toString(nowMillis + 10_000L));
    return AccountGameplayAdmissionLeaseEvidence.fromCarrier(carrier);
  }

  /** Actual migration state machine over the selected controlled empty legacy cohort. */
  private static void establishActualLegacyDisposition(DSLContext dsl) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = UUID.randomUUID();
    UUID writerFence = UUID.randomUUID();
    var storageIdentity =
        new CanonicalGameplayLegacyMigrationStorageIdentity(
            "controlled-cluster-uid",
            "controlled-namespace-uid",
            "controlled-producer-pod-uid",
            "controlled-container-id",
            "controlled-node-uid",
            "123456789",
            42L,
            "controlled-postgres-volume-uid",
            "controlled-redis-run-id",
            "controlled-redis-volume-uid");
    var fencedCohort =
        new CanonicalGameplayLegacyMigrationOwner.FencedCohort() {
          @Override
          public UUID cohortId() {
            return cohortId;
          }

          @Override
          public UUID legacyWriterFence() {
            return writerFence;
          }

          @Override
          public CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity() {
            return storageIdentity;
          }

          @Override
          public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
            if (!storageIdentity.equals(expected)) {
              throw new IllegalStateException("Controlled source storage identity changed");
            }
          }
        };
    var migrated =
        new CanonicalGameplayLegacyMigrationOwner(
                migration,
                inventory,
                () -> fencedCohort,
                ignored ->
                    new CanonicalGameplayLegacyMigrationSourceSnapshot(
                        EnumSet.of(
                            Family.TENANT_SESSION_CONTEXT,
                            Family.SESSION_ALIAS_CONTEXT,
                            Family.GAMEPLAY_IDENTITY_CONTEXT,
                            Family.GAMEPLAY_NAME_CONTEXT,
                            Family.MOVEMENT_EFFECT,
                            Family.DURABLE_EFFECT),
                        List.of()),
                new CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner() {
                  @Override
                  public void rebuildExact(
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                      CanonicalGameplayLegacyMigrationSourceSnapshot source,
                      net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventorySnapshot
                          canonical) {
                    requireEmptyCanonical(canonical);
                  }

                  @Override
                  public CanonicalGameplayLegacyMigrationReadback readBackExact(
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignoredCohort,
                      net.firedevops.firemud.gamesession.binding
                              .CanonicalGameplayBindingInventorySnapshot
                          canonical) {
                    requireEmptyCanonical(canonical);
                    return new CanonicalGameplayLegacyMigrationReadback(
                        List.of(), List.of(), List.of(), List.of());
                  }
                })
            .migrate();
    if (migrated.state() != CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
      throw new IllegalStateException("Actual legacy migration owner did not publish VERIFIED");
    }
  }

  private static void requireEmptyCanonical(
      net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot
          canonical) {
    if (!canonical.bindings().isEmpty()
        || !canonical.reservations().isEmpty()
        || !canonical.transitions().isEmpty()
        || !canonical.accountIndexObligations().isEmpty()
        || !canonical.issuerIndexObligations().isEmpty()
        || !canonical.regionBridgeObligations().isEmpty()) {
      throw new IllegalStateException("Controlled empty-cohort collaborator found GS state");
    }
  }

  private static CanonicalGameplayRegionIncarnationSource.StableEvidence
      controlledStableIncarnation(CanonicalGameplayRegionIncarnationSource.RegionScope scope) {
    var deployment =
        new CanonicalGameplayProtectedIncarnationReceiver.ExpectedTargetDeploymentEvidence(
            "controlled-region-deployment",
            "deployment-generation-1",
            List.of("node-a"),
            "redis-build-1",
            "script-registry-v1",
            "sha256:" + "a".repeat(64),
            "READY",
            "boot-1",
            "startup-1",
            "storage-generation-1",
            "aof-1");
    return CanonicalGameplayRegionIncarnationSource.StableEvidence.fromCurrentSource(
        scope,
        REGION_ATTESTATION,
        "b".repeat(64),
        deployment,
        "c".repeat(64),
        "d".repeat(64),
        true,
        "controlled-non-reset-profile-v1",
        "e".repeat(64),
        true,
        "f".repeat(64),
        true,
        "1".repeat(64));
  }

  private static List<List<String>> snapshotRegionOwnerSqlState(
      DSLContext dsl, UUID tenantId, UUID gameInstanceId, UUID regionId) {
    return List.of(
        dsl.fetch(
                "SELECT to_jsonb(runtime_status)::text AS image"
                    + " FROM game_session_region_runtime_status runtime_status"
                    + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?"
                    + " ORDER BY tenant_id, game_instance_id, region_id",
                tenantId,
                gameInstanceId,
                regionId)
            .getValues("image", String.class),
        dsl.fetch(
                "SELECT to_jsonb(preparation)::text AS image"
                    + " FROM game_session_region_timeline_preparation preparation"
                    + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?"
                    + " ORDER BY initialization_operation_id",
                tenantId,
                gameInstanceId,
                regionId)
            .getValues("image", String.class),
        dsl.fetch(
                "SELECT to_jsonb(obligation)::text AS image"
                    + " FROM game_session_canonical_binding_region_bridge_obligation obligation"
                    + " WHERE tenant_id = ? AND game_instance_id = ? AND region_id = ?"
                    + " ORDER BY transition_id",
                tenantId,
                gameInstanceId,
                regionId)
            .getValues("image", String.class));
  }

  private static RegionRedisState snapshotRegionRedisState(
      RegionRedis redis, byte[] leaseKey, String metaKey) {
    Map<String, String> metadata = new LinkedHashMap<>();
    redis
        .admin()
        .hgetall(ascii(metaKey))
        .forEach((field, value) -> metadata.put(asciiString(field), asciiString(value)));
    return new RegionRedisState(
        redis.admin().type(leaseKey),
        asciiString(redis.admin().get(leaseKey)),
        Map.copyOf(metadata));
  }

  private static String asciiString(byte[] value) {
    return value == null ? null : new String(value, StandardCharsets.US_ASCII);
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  public record Proof(
      UUID transitionId,
      UUID sessionId,
      UUID operationalRegionId,
      UUID canonicalRegionId,
      long lifecycleEpoch,
      long lifecycleRowVersion,
      long regionEpoch,
      BigInteger executorFence,
      UUID bridgeOperationId,
      Long runtimeEntityId,
      Long entityProjectionGeneration,
      Long entityProjectionFence,
      String entityReferenceDigest,
      String worldSourceDigest,
      String redisReadbackDigest,
      BigInteger acknowledgedInventoryRevision,
      Instant acknowledgedAt,
      boolean exactLostReplyRetry) {}

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "The generated Protocol Buffers request is immutable")
  public record SecondBinding(
      CanonicalInitialPlayerLocationRequest worldRequest,
      CanonicalPlayableTarget currentTarget,
      CanonicalInitialPlayerLocationRegionResolver worldRegionResolver,
      CanonicalPreseededActorRuntimeReferenceClient entityReferenceClient) {}

  public record TwoBindingProof(
      Proof first,
      Proof second,
      AccountAbortProof firstAbort,
      boolean firstTimelineUnchanged,
      boolean initialMetaUnchanged,
      boolean regionLeaseStillLive,
      boolean foreignOwnerCapabilityRejectedWithoutStateChange,
      boolean expiredGrantRejectedWithoutStateChange) {}

  private record RegionRedisState(
      String leaseType, String leaseValue, Map<String, String> metadata) {}

  public record AccountAbortProof(
      UUID transitionId,
      UUID cleanupId,
      BigInteger acknowledgedRevision,
      BigInteger abortRevision,
      boolean exactAcknowledgementRetained,
      String candidateLifecycle,
      String reservationLifecycle) {}

  private static final class ControlledStableIncarnationSource
      implements CanonicalGameplayRegionIncarnationSource {
    @Override
    public StableEvidence readCurrent(RegionScope scope) {
      return controlledStableIncarnation(scope);
    }
  }

  private static final class RegionRedis implements AutoCloseable {
    private final RedisClient administrator;
    private final RedisClient ownerClient;
    private final StatefulRedisConnection<byte[], byte[]> adminConnection;
    private final RedisCommands<byte[], byte[]> admin;

    private RegionRedis(
        RedisClient administrator,
        RedisClient ownerClient,
        StatefulRedisConnection<byte[], byte[]> adminConnection,
        RedisCommands<byte[], byte[]> admin) {
      this.administrator = administrator;
      this.ownerClient = ownerClient;
      this.adminConnection = adminConnection;
      this.admin = admin;
    }

    static RegionRedis open(String host, int port) {
      var baseUri = RedisURI.Builder.redis(host, port).withTimeout(Duration.ofSeconds(5)).build();
      RedisClient administrator = RedisClient.create(baseUri);
      StatefulRedisConnection<byte[], byte[]> adminConnection =
          administrator.connect(ByteArrayCodec.INSTANCE);
      RedisCommands<byte[], byte[]> admin = adminConnection.sync();
      admin.aclSetuser(
          REDIS_PRINCIPAL,
          new AclSetuserArgs()
              .reset()
              .on()
              .addPassword(REDIS_PASSWORD)
              .keyPattern("tick-executor-lease:*")
              .keyPattern("tick:*")
              .addCommand(CommandType.HELLO)
              .addCommand(CommandType.PING)
              .addCommand(CommandType.CLIENT, CommandKeyword.SETINFO)
              .addCommand(CommandType.ACL, CommandKeyword.WHOAMI)
              .addCommand(CommandType.SCRIPT, CommandKeyword.LOAD)
              .addCommand(CommandType.EVALSHA)
              .addCommand(CommandType.TYPE)
              .addCommand(CommandType.HGET)
              .addCommand(CommandType.HSET)
              .addCommand(CommandType.HLEN)
              .addCommand(CommandType.PTTL)
              .addCommand(CommandType.GET)
              .addCommand(CommandType.SET)
              .addCommand(CommandType.PEXPIRE));
      RedisURI ownerUri =
          RedisURI.Builder.redis(host, port)
              .withTimeout(Duration.ofSeconds(5))
              .withAuthentication(REDIS_PRINCIPAL, REDIS_PASSWORD)
              .build();
      return new RegionRedis(administrator, RedisClient.create(ownerUri), adminConnection, admin);
    }

    RedisClient ownerClient() {
      return ownerClient;
    }

    RedisCommands<byte[], byte[]> admin() {
      return admin;
    }

    @Override
    public void close() {
      adminConnection.close();
      ownerClient.shutdown(Duration.ZERO, Duration.ofSeconds(2));
      administrator.shutdown(Duration.ZERO, Duration.ofSeconds(2));
    }
  }
}
