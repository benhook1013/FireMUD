package net.firedevops.firemud.gamesession.repository;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.common.world.WorldCanonicalInstanceLifecycleEvidence;
import net.firedevops.firemud.gamesession.entity.CanonicalGameplayRegionRuntimeStatus;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Durable region timeline and owner-fence persistence, separate from the instance status row. */
final class CanonicalGameplayRegionRuntimeStatusRepository {
  private static final String TABLE = "game_session_region_runtime_status";
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
}
