package net.firedevops.firemud.gamesession.repository;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayBindingInventorySnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationReadback;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Read-only namespace reconciliation for a fully fenced, physically empty disposable cohort.
 * Nonempty canonical rows and any target namespace key are retained and denied; this adapter does
 * not contain an active-session serializer or delete path. Only the exact canonical issuer
 * generation projection key family is outside this namespace result; its owner gate is separate.
 */
final class CanonicalGameplayFreshNamespaceRedisReconciler
    implements CanonicalGameplayLegacyMigrationOwner.NamespaceIndexOwner {
  private static final String TARGET_PREFIX = "session:game:";
  private static final String SCAN_PATTERN = TARGET_PREFIX + "*";
  private static final String ISSUER_GENERATION_PREFIX = "session:game:auth:issuer-generation:v1:";
  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
  private static final UUID NIL_UUID = new UUID(0L, 0L);
  private static final ScanLimits DEFAULT_LIMITS =
      new ScanLimits(128, 5_000, 20_000, 1_024, Duration.ofSeconds(30));

  private final StringRedisTemplate redis;
  private final CanonicalGameplayLegacyRedisSource legacySource;
  private final ScanLimits limits;

  CanonicalGameplayFreshNamespaceRedisReconciler(StringRedisTemplate redis) {
    this(redis, DEFAULT_LIMITS);
  }

  CanonicalGameplayFreshNamespaceRedisReconciler(StringRedisTemplate redis, ScanLimits limits) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.legacySource = new CanonicalGameplayLegacyRedisSource(redis);
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  /**
   * Proves the complete empty-only precondition and repeats physical legacy/target scans. This
   * fresh-cohort operation deliberately performs no Redis mutation.
   */
  @Override
  public void rebuildExact(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      CanonicalGameplayLegacyMigrationSourceSnapshot legacySnapshot,
      CanonicalGameplayBindingInventorySnapshot canonicalSnapshot) {
    Objects.requireNonNull(cohort, "cohort");
    Objects.requireNonNull(legacySnapshot, "legacySnapshot");
    requireEmptyCanonicalSnapshot(canonicalSnapshot);
    requireEmptyLegacySnapshot(legacySnapshot, "durable legacy source snapshot");

    FenceIdentity fence = FenceIdentity.capture(cohort);
    long startedAt = System.nanoTime();
    requireFenceAndTime(cohort, fence, startedAt);
    requireStandaloneRedisTopology();
    requireFenceAndTime(cohort, fence, startedAt);
    CanonicalGameplayLegacyMigrationSourceSnapshot currentLegacy =
        legacySource.captureEveryKnownFamily(cohort);
    requireFenceAndTime(cohort, fence, startedAt);
    requireEmptyLegacySnapshot(currentLegacy, "live legacy source inventory");
    if (!legacySnapshot.equals(currentLegacy)) {
      throw conflict("Legacy source inventory changed before namespace reconciliation");
    }

    Set<String> physicalKeys = scanTargetTwice(cohort, fence, startedAt);
    requireNoNamespaceKeys(physicalKeys);
    requireFenceAndTime(cohort, fence, startedAt);
  }

  /**
   * Returns empty record lists only after independent complete live legacy and target-family
   * inventories prove that the same fenced cohort still has no retained rows.
   */
  @Override
  public CanonicalGameplayLegacyMigrationReadback readBackExact(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      CanonicalGameplayBindingInventorySnapshot canonicalSnapshot) {
    Objects.requireNonNull(cohort, "cohort");
    requireEmptyCanonicalSnapshot(canonicalSnapshot);

    FenceIdentity fence = FenceIdentity.capture(cohort);
    long startedAt = System.nanoTime();
    requireFenceAndTime(cohort, fence, startedAt);
    requireStandaloneRedisTopology();
    requireFenceAndTime(cohort, fence, startedAt);
    CanonicalGameplayLegacyMigrationSourceSnapshot currentLegacy =
        legacySource.captureEveryKnownFamily(cohort);
    requireFenceAndTime(cohort, fence, startedAt);
    requireEmptyLegacySnapshot(currentLegacy, "readback legacy source inventory");

    Set<String> physicalKeys = scanTargetTwice(cohort, fence, startedAt);
    requireNoNamespaceKeys(physicalKeys);
    requireFenceAndTime(cohort, fence, startedAt);
    return new CanonicalGameplayLegacyMigrationReadback(List.of(), List.of(), List.of(), List.of());
  }

  private Set<String> scanTargetTwice(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      FenceIdentity fence,
      long startedAt) {
    Set<String> first = scanOnce(cohort, fence, startedAt);
    requireFenceAndTime(cohort, fence, startedAt);
    Set<String> second = scanOnce(cohort, fence, startedAt);
    requireFenceAndTime(cohort, fence, startedAt);
    if (!first.equals(second)) {
      throw conflict("Target Redis inventory changed between complete fenced scans");
    }
    return first;
  }

  private Set<String> scanOnce(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      FenceIdentity fence,
      long startedAt) {
    Set<String> scanned =
        redis.execute(
            (RedisCallback<Set<String>>)
                connection -> scanConnection(connection, cohort, fence, startedAt));
    if (scanned == null) {
      throw conflict("Target Redis cursor returned no complete namespace inventory");
    }
    return scanned;
  }

  private Set<String> scanConnection(
      RedisConnection connection,
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      FenceIdentity fence,
      long startedAt) {
    requireFenceAndTime(cohort, fence, startedAt);
    if (connection instanceof RedisClusterConnection) {
      throw conflict(
          "Target Redis cluster scan cannot prove complete cluster-wide namespace coverage");
    }
    try {
      CanonicalGameplayMigrationDriverMain.requireStandaloneRedisTopology(
          connection.serverCommands().info("cluster"));
    } catch (RuntimeException unprovenTopology) {
      throw conflict("Target Redis standalone topology could not be proven");
    }

    Set<String> keys = new TreeSet<>();
    ScanOptions options =
        ScanOptions.scanOptions().match(SCAN_PATTERN).count(limits.scanCount()).build();
    try (Cursor<byte[]> cursor = connection.scan(options)) {
      if (cursor == null) {
        throw conflict("Target Redis cursor was unavailable");
      }
      int visited = 0;
      int sinceFenceCheck = 0;
      while (true) {
        requireWithinTime(startedAt);
        boolean hasNext = cursor.hasNext();
        requireWithinTime(startedAt);
        if (!hasNext) {
          break;
        }
        String key = decodeKey(cursor.next());
        if (!key.startsWith(TARGET_PREFIX)) {
          throw conflict("Target Redis scan returned a key outside its pinned prefix");
        }
        if (key.startsWith(ISSUER_GENERATION_PREFIX) && !isCanonicalIssuerProjectionKey(key)) {
          throw conflict("Target Redis inventory contains a malformed issuer projection key");
        }
        visited++;
        if (visited > limits.maxVisitedKeys()) {
          throw conflict("Target Redis cursor exceeded its duplicate-safe visit bound");
        }
        // Redis SCAN may legitimately return the same key more than once. Deduplicate the
        // structural inventory, but count every visit above so repeated output remains bounded.
        keys.add(key);
        if (keys.size() > limits.maxUniqueKeys()) {
          throw conflict("Target Redis inventory exceeded its unique-key bound");
        }
        if (++sinceFenceCheck >= limits.scanCount()) {
          requireFenceAndTime(cohort, fence, startedAt);
          sinceFenceCheck = 0;
        }
      }
      if (cursor.getCursorId() != 0L) {
        throw conflict("Target Redis cursor stopped before the terminal cursor identity");
      }
      requireFenceAndTime(cohort, fence, startedAt);
      return Set.copyOf(keys);
    } catch (CanonicalGameplayBindingInventoryConflictException rejected) {
      throw rejected;
    } catch (RuntimeException incompleteCursor) {
      throw conflict("Target Redis cursor failed before complete exhaustion");
    }
  }

  private void requireStandaloneRedisTopology() {
    try {
      Boolean validated =
          redis.execute(
              (RedisCallback<Boolean>)
                  connection -> {
                    CanonicalGameplayMigrationDriverMain.requireStandaloneRedisTopology(
                        connection.serverCommands().info("cluster"));
                    return Boolean.TRUE;
                  });
      if (!Boolean.TRUE.equals(validated)) {
        throw conflict("Target Redis standalone topology could not be proven");
      }
    } catch (CanonicalGameplayBindingInventoryConflictException rejected) {
      throw rejected;
    } catch (RuntimeException unavailableTopology) {
      throw conflict("Target Redis standalone topology could not be proven");
    }
  }

  private void requireNoNamespaceKeys(Set<String> keys) {
    if (keys.stream().anyMatch(key -> !isCanonicalIssuerProjectionKey(key))) {
      throw conflict("Target Redis session or index family is retained or unknown");
    }
  }

  private void requireFenceAndTime(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      FenceIdentity expected,
      long startedAt) {
    requireWithinTime(startedAt);
    requireSameIdentity(cohort, expected);
    cohort.requireStillFenced(expected.storageIdentity());
    requireSameIdentity(cohort, expected);
    requireWithinTime(startedAt);
  }

  private static void requireSameIdentity(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort, FenceIdentity expected) {
    if (!expected.equals(FenceIdentity.capture(cohort))) {
      throw conflict("Target Redis cohort identity or writer fence changed during reconciliation");
    }
  }

  private void requireWithinTime(long startedAt) {
    if (System.nanoTime() - startedAt >= limits.maxDuration().toNanos()) {
      throw conflict("Target Redis inventory exceeded its time bound");
    }
  }

  private static void requireEmptyCanonicalSnapshot(
      CanonicalGameplayBindingInventorySnapshot snapshot) {
    Objects.requireNonNull(snapshot, "canonicalSnapshot");
    // The real inventory revision is retained by the owner, but its numeric value is never used as
    // a substitute for enumerating every canonical row and repair-obligation family.
    if (!snapshot.bindings().isEmpty()
        || !snapshot.transitions().isEmpty()
        || !snapshot.reservations().isEmpty()
        || !snapshot.accountIndexObligations().isEmpty()
        || !snapshot.issuerIndexObligations().isEmpty()
        || !snapshot.regionBridgeObligations().isEmpty()) {
      throw conflict(
          "Nonempty canonical gameplay inventory is unsupported by the fresh reconciler");
    }
  }

  private static void requireEmptyLegacySnapshot(
      CanonicalGameplayLegacyMigrationSourceSnapshot snapshot, String sourceName) {
    Objects.requireNonNull(snapshot, sourceName);
    if (!snapshot.enumeratedEveryKnownFamily() || !snapshot.entries().isEmpty()) {
      throw conflict("Complete empty legacy source inventory is required before reconciliation");
    }
  }

  private String decodeKey(byte[] serializedKey) {
    if (serializedKey == null
        || serializedKey.length == 0
        || serializedKey.length > limits.maxKeyBytes()) {
      throw conflict("Target Redis key is empty or exceeds its byte bound");
    }
    try {
      String key =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(serializedKey))
              .toString();
      if (!key.equals(key.strip()) || key.chars().anyMatch(Character::isISOControl)) {
        throw conflict("Target Redis key is not a canonical text key");
      }
      return key;
    } catch (CharacterCodingException malformedUtf8) {
      throw conflict("Target Redis key is not valid UTF-8");
    }
  }

  private static boolean isCanonicalIssuerProjectionKey(String key) {
    if (!key.startsWith(ISSUER_GENERATION_PREFIX)) {
      return false;
    }
    String issuerId = key.substring(ISSUER_GENERATION_PREFIX.length());
    if (!CANONICAL_UUID.matcher(issuerId).matches()) {
      return false;
    }
    try {
      UUID parsed = UUID.fromString(issuerId);
      return !NIL_UUID.equals(parsed) && parsed.toString().equals(issuerId);
    } catch (IllegalArgumentException malformedUuid) {
      return false;
    }
  }

  private static CanonicalGameplayBindingInventoryConflictException conflict(String message) {
    return new CanonicalGameplayBindingInventoryConflictException(message);
  }

  record ScanLimits(
      int scanCount, int maxUniqueKeys, int maxVisitedKeys, int maxKeyBytes, Duration maxDuration) {
    ScanLimits {
      Objects.requireNonNull(maxDuration, "maxDuration");
      if (scanCount <= 0
          || maxUniqueKeys <= 0
          || maxVisitedKeys < maxUniqueKeys
          || maxKeyBytes <= 0
          || maxDuration.isZero()
          || maxDuration.isNegative()) {
        throw new IllegalArgumentException(
            "target Redis scan limits must be positive and coherent");
      }
    }
  }

  private record FenceIdentity(
      UUID cohortId,
      UUID legacyWriterFence,
      CanonicalGameplayLegacyMigrationStorageIdentity storageIdentity) {
    private FenceIdentity {
      Objects.requireNonNull(cohortId, "cohortId");
      Objects.requireNonNull(legacyWriterFence, "legacyWriterFence");
      Objects.requireNonNull(storageIdentity, "storageIdentity");
      if (NIL_UUID.equals(cohortId) || NIL_UUID.equals(legacyWriterFence)) {
        throw conflict("Target Redis reconciler requires non-nil cohort and writer-fence ids");
      }
    }

    private static FenceIdentity capture(
        CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort) {
      return new FenceIdentity(
          cohort.cohortId(), cohort.legacyWriterFence(), cohort.storageIdentity());
    }
  }
}
