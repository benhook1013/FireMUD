package net.firedevops.firemud.gamesession.repository;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationSourceSnapshot;
import net.firedevops.firemud.gamesession.binding.CanonicalGameplayLegacyMigrationStorageIdentity;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Bounded read-only inventory of the legacy Game Session Redis keyspace. Values are never read or
 * deserialized; retained rows remain non-authorizing and block migration until a mapping owner is
 * implemented.
 */
final class CanonicalGameplayLegacyRedisSource
    implements CanonicalGameplayLegacyMigrationOwner.CompleteLegacySource {
  private static final String OWNED_PREFIX = "sessionctx:";
  private static final String SCAN_PATTERN = OWNED_PREFIX + "*";
  private static final Pattern TENANT_SESSION_CONTEXT =
      Pattern.compile("sessionctx:[0-9]+:[0-9]+:context");
  private static final Pattern SESSION_ALIAS_CONTEXT =
      Pattern.compile("sessionctx:session:[0-9]+:context");
  private static final Pattern GAMEPLAY_IDENTITY_CONTEXT =
      Pattern.compile("sessionctx:[0-9]+:identity:[0-9]+:[0-9]+:context");
  private static final Pattern GAMEPLAY_NAME_CONTEXT =
      Pattern.compile("sessionctx:[0-9]+:identity:[0-9]+:name:.+:context");
  private static final Pattern MOVEMENT_EFFECT =
      Pattern.compile("sessionctx:[0-9]+:[0-9]+:movement-effect:.+");
  private static final Pattern DURABLE_EFFECT =
      Pattern.compile("sessionctx:[0-9]+:[0-9]+:durable-effect:.+");
  private static final EnumSet<CanonicalGameplayLegacyMigrationSourceSnapshot.Family>
      KNOWN_FAMILIES =
          EnumSet.of(
              CanonicalGameplayLegacyMigrationSourceSnapshot.Family.TENANT_SESSION_CONTEXT,
              CanonicalGameplayLegacyMigrationSourceSnapshot.Family.SESSION_ALIAS_CONTEXT,
              CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_IDENTITY_CONTEXT,
              CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_NAME_CONTEXT,
              CanonicalGameplayLegacyMigrationSourceSnapshot.Family.MOVEMENT_EFFECT,
              CanonicalGameplayLegacyMigrationSourceSnapshot.Family.DURABLE_EFFECT);
  private static final ScanLimits DEFAULT_LIMITS =
      new ScanLimits(128, 5_000, 20_000, 1_024, Duration.ofSeconds(30));

  private final StringRedisTemplate redis;
  private final ScanLimits limits;

  CanonicalGameplayLegacyRedisSource(StringRedisTemplate redis) {
    this(redis, DEFAULT_LIMITS);
  }

  CanonicalGameplayLegacyRedisSource(StringRedisTemplate redis, ScanLimits limits) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.limits = Objects.requireNonNull(limits, "limits");
  }

  @Override
  public CanonicalGameplayLegacyMigrationSourceSnapshot captureEveryKnownFamily(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort) {
    Objects.requireNonNull(cohort, "cohort");
    FenceIdentity fence = FenceIdentity.capture(cohort);
    long startedAt = System.nanoTime();
    requireFenceAndTime(cohort, fence, startedAt);
    Map<String, CanonicalGameplayLegacyMigrationSourceSnapshot.Family> first =
        scanOnce(cohort, fence, startedAt);
    requireFenceAndTime(cohort, fence, startedAt);
    Map<String, CanonicalGameplayLegacyMigrationSourceSnapshot.Family> second =
        scanOnce(cohort, fence, startedAt);
    requireFenceAndTime(cohort, fence, startedAt);
    if (!first.equals(second)) {
      throw conflict("Legacy Redis key inventory changed between complete fenced scans");
    }

    List<CanonicalGameplayLegacyMigrationSourceSnapshot.Entry> entries = new ArrayList<>();
    first.forEach(
        (key, family) ->
            entries.add(
                new CanonicalGameplayLegacyMigrationSourceSnapshot.Entry(
                    family,
                    digestKey(key),
                    family == CanonicalGameplayLegacyMigrationSourceSnapshot.Family.UNCLASSIFIED
                        ? CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition.UNKNOWN
                        : CanonicalGameplayLegacyMigrationSourceSnapshot.Disposition.UNMAPPABLE)));
    return new CanonicalGameplayLegacyMigrationSourceSnapshot(KNOWN_FAMILIES, entries);
  }

  private Map<String, CanonicalGameplayLegacyMigrationSourceSnapshot.Family> scanOnce(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      FenceIdentity fence,
      long startedAt) {
    Map<String, CanonicalGameplayLegacyMigrationSourceSnapshot.Family> scanned =
        redis.execute(
            (RedisCallback<Map<String, CanonicalGameplayLegacyMigrationSourceSnapshot.Family>>)
                connection -> scanConnection(connection, cohort, fence, startedAt));
    if (scanned == null) {
      throw conflict("Legacy Redis key cursor returned no complete inventory");
    }
    return scanned;
  }

  private Map<String, CanonicalGameplayLegacyMigrationSourceSnapshot.Family> scanConnection(
      RedisConnection connection,
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      FenceIdentity fence,
      long startedAt) {
    requireFenceAndTime(cohort, fence, startedAt);
    Map<String, CanonicalGameplayLegacyMigrationSourceSnapshot.Family> keys = new TreeMap<>();
    ScanOptions options =
        ScanOptions.scanOptions().match(SCAN_PATTERN).count(limits.scanCount()).build();
    try (Cursor<byte[]> cursor = connection.scan(options)) {
      if (cursor == null) {
        throw conflict("Legacy Redis key cursor was unavailable");
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
        byte[] serializedKey = cursor.next();
        String key = decodeKey(serializedKey);
        if (!key.startsWith(OWNED_PREFIX)) {
          throw conflict("Legacy Redis scan returned a key outside its pinned prefix");
        }
        visited++;
        if (visited > limits.maxVisitedKeys()) {
          throw conflict("Legacy Redis key cursor exceeded its duplicate-safe visit bound");
        }
        keys.putIfAbsent(key, classify(key));
        if (keys.size() > limits.maxUniqueKeys()) {
          throw conflict("Legacy Redis key inventory exceeded its unique-key bound");
        }
        if (++sinceFenceCheck >= limits.scanCount()) {
          requireFenceAndTime(cohort, fence, startedAt);
          sinceFenceCheck = 0;
        }
      }
      if (cursor.getCursorId() != 0L) {
        throw conflict("Legacy Redis key cursor stopped before the terminal cursor identity");
      }
      requireFenceAndTime(cohort, fence, startedAt);
      return keys;
    } catch (CanonicalGameplayBindingInventoryConflictException rejected) {
      throw rejected;
    } catch (RuntimeException incompleteCursor) {
      throw conflict("Legacy Redis key cursor failed before complete exhaustion");
    }
  }

  private void requireFenceAndTime(
      CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort,
      FenceIdentity expected,
      long startedAt) {
    requireWithinTime(startedAt);
    if (!expected.equals(FenceIdentity.capture(cohort))) {
      throw conflict("Legacy Redis cohort identity or writer fence changed during inventory");
    }
    cohort.requireStillFenced(expected.storageIdentity());
    if (!expected.equals(FenceIdentity.capture(cohort))) {
      throw conflict("Legacy Redis cohort identity or writer fence changed during inventory");
    }
  }

  private void requireWithinTime(long startedAt) {
    if (System.nanoTime() - startedAt >= limits.maxDuration().toNanos()) {
      throw conflict("Legacy Redis key inventory exceeded its time bound");
    }
  }

  private String decodeKey(byte[] serializedKey) {
    if (serializedKey == null
        || serializedKey.length == 0
        || serializedKey.length > limits.maxKeyBytes()) {
      throw conflict("Legacy Redis key is empty or exceeds its byte bound");
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
        throw conflict("Legacy Redis key is not a canonical text key");
      }
      return key;
    } catch (CharacterCodingException malformedUtf8) {
      throw conflict("Legacy Redis key is not valid UTF-8");
    }
  }

  private static CanonicalGameplayLegacyMigrationSourceSnapshot.Family classify(String key) {
    if (TENANT_SESSION_CONTEXT.matcher(key).matches()) {
      return CanonicalGameplayLegacyMigrationSourceSnapshot.Family.TENANT_SESSION_CONTEXT;
    }
    if (SESSION_ALIAS_CONTEXT.matcher(key).matches()) {
      return CanonicalGameplayLegacyMigrationSourceSnapshot.Family.SESSION_ALIAS_CONTEXT;
    }
    if (GAMEPLAY_IDENTITY_CONTEXT.matcher(key).matches()) {
      return CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_IDENTITY_CONTEXT;
    }
    if (GAMEPLAY_NAME_CONTEXT.matcher(key).matches()) {
      return CanonicalGameplayLegacyMigrationSourceSnapshot.Family.GAMEPLAY_NAME_CONTEXT;
    }
    if (MOVEMENT_EFFECT.matcher(key).matches()) {
      return CanonicalGameplayLegacyMigrationSourceSnapshot.Family.MOVEMENT_EFFECT;
    }
    if (DURABLE_EFFECT.matcher(key).matches()) {
      return CanonicalGameplayLegacyMigrationSourceSnapshot.Family.DURABLE_EFFECT;
    }
    return CanonicalGameplayLegacyMigrationSourceSnapshot.Family.UNCLASSIFIED;
  }

  private static String digestKey(String key) {
    try {
      return "sha256:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(key.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(
          "SHA-256 is unavailable for legacy Redis source inventory", impossible);
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
            "legacy Redis scan limits must be positive and coherent");
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
      if (cohortId.equals(new UUID(0L, 0L)) || legacyWriterFence.equals(new UUID(0L, 0L))) {
        throw conflict("Legacy Redis source requires non-nil cohort and writer-fence identities");
      }
    }

    private static FenceIdentity capture(
        CanonicalGameplayLegacyMigrationOwner.FencedCohort cohort) {
      return new FenceIdentity(
          cohort.cohortId(), cohort.legacyWriterFence(), cohort.storageIdentity());
    }
  }
}
