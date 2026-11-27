package net.firedevops.firemud.gamesession.repository;

import io.lettuce.core.RedisClient;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.firedevops.firemud.common.redis.contracts.RedisScriptCatalog;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot.Family;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.jooq.DSLContext;

/**
 * Pre-v1 gate waiver (ADR 0185): one-time canonical gameplay store bootstrap at Game Session
 * startup. It drives the actual legacy migration owner to VERIFIED over an empty cohort, then the
 * actual issuer layout bootstrap, coverage and live-control owners to an installed anchor and OPEN.
 * The empty legacy source, namespace index, storage identity, issuer checkpoint and incarnation are
 * stand-ins owed before v1. Every request id is a UUIDv5 of the PostgreSQL system identity, so a
 * restart replays the same retained operations; a changed Redis {@code run_id} fails closed.
 */
public final class PreV1CanonicalGameplayStoreBootstrap {
  public static final BigInteger LAYOUT_VERSION = BigInteger.ONE;
  public static final BigInteger PARTITION_COUNT = BigInteger.ONE;
  public static final BigInteger PARTITION_CAPACITY = BigInteger.valueOf(128);
  private static final UUID NAMESPACE = UUID.fromString("6f0b7c64-3b44-5d4c-9a56-8a1f2f7e1c01");
  private static final String WAIVED = "pre-v1-waiver-no-kubernetes";

  private final DSLContext dsl;
  private final RedisClient redis;
  private final RedisScriptCatalog catalog;
  private final PreV1CoordinationStorageObserver observer;
  private final PreV1CurrentIssuerStateSource issuerSource;

  PreV1CanonicalGameplayStoreBootstrap(
      DSLContext dsl,
      RedisClient redis,
      RedisScriptCatalog catalog,
      PreV1CoordinationStorageObserver observer,
      PreV1CurrentIssuerStateSource issuerSource) {
    this.dsl = Objects.requireNonNull(dsl, "dsl");
    this.redis = Objects.requireNonNull(redis, "redis");
    this.catalog = Objects.requireNonNull(catalog, "catalog");
    this.observer = Objects.requireNonNull(observer, "observer");
    this.issuerSource = Objects.requireNonNull(issuerSource, "issuerSource");
  }

  /** Runs or replays the bootstrap; returns only after VERIFIED and the initial OPEN. */
  public Result run() {
    var postgres = observer.postgresIdentity();
    String pgScope = postgres.systemIdentifier() + "|" + postgres.databaseOid();
    var storage = observedStorage(postgres, observer.redisRunId());
    var migration = establishLegacyDisposition(pgScope, storage);

    String exactDescriptor =
        "issuer-layout-descriptor/v1|"
            + PreV1CurrentIssuerStateSource.ISSUER
            + "|"
            + LAYOUT_VERSION
            + "|"
            + PARTITION_COUNT
            + "|"
            + PARTITION_CAPACITY
            + "|"
            + CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY;
    var layout =
        new CanonicalGameplayIssuerLayoutBootstrapRepository.TrustedLayoutDescriptor(
            PreV1CurrentIssuerStateSource.ISSUER,
            LAYOUT_VERSION,
            PARTITION_COUNT,
            PARTITION_CAPACITY,
            CanonicalGameplayIssuerLayoutBootstrapIntent.MAPPING_IDENTITY,
            exactDescriptor.getBytes(StandardCharsets.UTF_8));
    UUID bootstrapRequestId = uuidV5("issuer-layout-bootstrap|" + pgScope + "|" + exactDescriptor);
    var intent =
        new CanonicalGameplayIssuerLayoutBootstrapRepository(dsl)
            .beginInitialBootstrapIntent(
                bootstrapRequestId, layout, issuerSource.trustedCheckpoint());
    var partitionOwner =
        new CanonicalGameplayIssuerBootstrapPartitionRedisOwner(redis, catalog, issuerSource);
    CanonicalGameplayIssuerInstalledAnchor anchor =
        new CanonicalGameplayIssuerBootstrapCoverageOwner(dsl, partitionOwner, issuerSource)
            .completeInitialBootstrap(intent.operationId(), bootstrapRequestId);
    var control =
        new CanonicalGameplayIssuerAdmissionControlRepository(dsl)
            .installInitialOpen(anchor, issuerSource);
    if (control.state() != CanonicalGameplayIssuerAdmissionControlValue.State.OPEN) {
      throw new IllegalStateException("Issuer live admission control did not reach OPEN");
    }
    return new Result(
        migration.operationId(),
        migration.state().name(),
        anchor.bootstrapOperationId(),
        bootstrapRequestId,
        control.state().name(),
        storage.redisRunId());
  }

  private CanonicalGameplayLegacyMigrationOperation establishLegacyDisposition(
      String pgScope, CanonicalGameplayLegacyMigrationStorageIdentity storage) {
    var inventory = new CanonicalGameplayBindingInventoryRepository(dsl);
    var migration = new CanonicalGameplayLegacyMigrationRepository(dsl, inventory);
    UUID cohortId = uuidV5("legacy-cohort|" + pgScope);
    UUID writerFence = uuidV5("legacy-writer-fence|" + pgScope);
    var cohort =
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
            return storage;
          }

          @Override
          public void requireStillFenced(CanonicalGameplayLegacyMigrationStorageIdentity expected) {
            if (!storage.equals(expected)
                || !storage.equals(
                    observedStorage(observer.postgresIdentity(), observer.redisRunId()))) {
              throw new IllegalStateException("Pre-v1 storage identity changed");
            }
          }
        };
    var operation =
        new CanonicalGameplayLegacyMigrationOwner(
                migration,
                inventory,
                () -> cohort,
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
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                      CanonicalGameplayLegacyMigrationSourceSnapshot source,
                      CanonicalGameplayBindingInventorySnapshot canonical) {
                    requireEmptyCanonical(canonical);
                  }

                  @Override
                  public CanonicalGameplayLegacyMigrationReadback readBackExact(
                      CanonicalGameplayLegacyMigrationOwner.FencedCohort ignored,
                      CanonicalGameplayBindingInventorySnapshot canonical) {
                    requireEmptyCanonical(canonical);
                    return new CanonicalGameplayLegacyMigrationReadback(
                        List.of(), List.of(), List.of(), List.of());
                  }
                })
            .migrate();
    if (operation.state() != CanonicalGameplayLegacyMigrationOperation.State.VERIFIED) {
      throw new IllegalStateException("Legacy migration owner did not publish VERIFIED");
    }
    return operation;
  }

  private static CanonicalGameplayLegacyMigrationStorageIdentity observedStorage(
      PreV1CoordinationStorageObserver.PostgresIdentity postgres, String redisRunId) {
    return new CanonicalGameplayLegacyMigrationStorageIdentity(
        WAIVED,
        WAIVED,
        "pre-v1-game-session",
        "pre-v1-game-session-process",
        "pre-v1-compose-host",
        postgres.systemIdentifier(),
        postgres.databaseOid(),
        "pre-v1-postgres-" + postgres.systemIdentifier(),
        redisRunId,
        "pre-v1-redis-" + redisRunId);
  }

  private static void requireEmptyCanonical(CanonicalGameplayBindingInventorySnapshot canonical) {
    if (!canonical.bindings().isEmpty()
        || !canonical.reservations().isEmpty()
        || !canonical.transitions().isEmpty()
        || !canonical.accountIndexObligations().isEmpty()
        || !canonical.issuerIndexObligations().isEmpty()
        || !canonical.regionBridgeObligations().isEmpty()) {
      throw new IllegalStateException("Pre-v1 empty-cohort migration found canonical state");
    }
  }

  static UUID uuidV5(String name) {
    try {
      MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
      sha1.update(
          ByteBuffer.allocate(16)
              .putLong(NAMESPACE.getMostSignificantBits())
              .putLong(NAMESPACE.getLeastSignificantBits())
              .array());
      byte[] value = sha1.digest(name.getBytes(StandardCharsets.UTF_8));
      value[6] = (byte) ((value[6] & 0x0f) | 0x50);
      value[8] = (byte) ((value[8] & 0x3f) | 0x80);
      ByteBuffer bytes = ByteBuffer.wrap(value);
      return new UUID(bytes.getLong(), bytes.getLong());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-1", impossible);
    }
  }

  /** Durable bootstrap outcome; states are the owners' own names (VERIFIED, OPEN). */
  public record Result(
      UUID legacyMigrationOperationId,
      String legacyMigrationState,
      UUID issuerBootstrapOperationId,
      UUID issuerBootstrapRequestId,
      String issuerAdmissionControlState,
      String redisRunId) {}
}
