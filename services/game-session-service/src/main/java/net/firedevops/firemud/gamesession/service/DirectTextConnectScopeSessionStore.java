package net.firedevops.firemud.gamesession.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.shared.v1.PlayerExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores direct-text lobby snapshots and Account-issued scopes by the stable transport session ID.
 * Production state is shared through Redis so it survives Game Session instance replacement.
 */
@Component
public final class DirectTextConnectScopeSessionStore {
  private static final String KEY_TEMPLATE = "gamesession:lobby:%d";
  private static final Duration WORLDS_SNAPSHOT_TTL = Duration.ofMinutes(5);
  private static final Duration MAX_ACCOUNT_SCOPE_TTL = Duration.ofMinutes(15);
  private static final int MAX_CAS_ATTEMPTS = 16;
  private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT = buildCompareAndSetScript();

  private final Backend backend;
  private final ObjectMapper objectMapper;

  @Autowired
  public DirectTextConnectScopeSessionStore(
      StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
    this(
        new RedisBackend(Objects.requireNonNull(redisTemplate, "redisTemplate must not be null")),
        Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
  }

  private DirectTextConnectScopeSessionStore(Backend backend, ObjectMapper objectMapper) {
    this.backend = Objects.requireNonNull(backend, "backend must not be null");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
  }

  /**
   * Creates isolated in-memory storage for focused unit tests; production construction is
   * Redis-only.
   */
  public static DirectTextConnectScopeSessionStore inMemoryForTest() {
    return new DirectTextConnectScopeSessionStore(new InMemoryBackend(), new ObjectMapper());
  }

  public void replaceWorldSnapshot(
      long transportSessionId,
      long authenticatedAccountId,
      String catalogFingerprint,
      List<WorldOrdinalTarget> ordinalTargets,
      Instant now) {
    requireTransportSessionId(transportSessionId);
    if (authenticatedAccountId < 0L) {
      throw new IllegalArgumentException("authenticatedAccountId must not be negative");
    }
    Objects.requireNonNull(now, "now must not be null");
    if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
      throw new IllegalArgumentException("catalogFingerprint must not be blank");
    }
    List<WorldOrdinalTarget> safeTargets =
        List.copyOf(Objects.requireNonNull(ordinalTargets, "ordinalTargets must not be null"));
    validateOrdinalTargets(safeTargets);
    long nowMillis = now.toEpochMilli();
    long expiresAtMillis = now.plus(WORLDS_SNAPSHOT_TTL).toEpochMilli();

    mutate(
        transportSessionId,
        nowMillis,
        current -> {
          LobbyRecord record = current == null ? LobbyRecord.empty(transportSessionId) : current;
          if (record.sessionId() != transportSessionId) {
            throw new ConflictingIdentityException("lobby record transport session did not match");
          }
          if (authenticatedAccountId > 0L
              && record.accountId() > 0L
              && record.accountId() != authenticatedAccountId) {
            record = LobbyRecord.empty(transportSessionId);
          }
          long accountId =
              authenticatedAccountId > 0L ? authenticatedAccountId : record.accountId();
          return record.withWorldSnapshot(
              accountId, expiresAtMillis, catalogFingerprint, safeTargets);
        });
  }

  public Optional<WorldsSnapshot> worldsSnapshot(SessionContext caller, Instant now) {
    Objects.requireNonNull(caller, "caller must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireCallerIdentity(caller);
    LobbyRecord record = readRecord(caller.sessionId());
    if (record == null
        || record.sessionId() != caller.sessionId()
        || (record.accountId() > 0L && record.accountId() != caller.accountId())) {
      return Optional.empty();
    }
    return asWorldsSnapshot(record, now);
  }

  public Optional<WorldsSnapshot> worldsSnapshot(long transportSessionId, Instant now) {
    requireTransportSessionId(transportSessionId);
    Objects.requireNonNull(now, "now must not be null");
    LobbyRecord record = readRecord(transportSessionId);
    if (record == null || record.sessionId() != transportSessionId) {
      return Optional.empty();
    }
    return asWorldsSnapshot(record, now);
  }

  private Optional<WorldsSnapshot> asWorldsSnapshot(LobbyRecord record, Instant now) {
    if (record.worldsExpiresAtEpochMs() <= now.toEpochMilli()
        || record.catalogFingerprint() == null
        || record.ordinalTargets().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new WorldsSnapshot(
            record.catalogFingerprint(),
            Instant.ofEpochMilli(record.worldsExpiresAtEpochMs()),
            record.ordinalTargets()));
  }

  public void replaceWorldScopes(
      SessionContext caller,
      String requestedWorldSelector,
      String canonicalWorldSlug,
      List<ScopedRealm> scopes) {
    Objects.requireNonNull(caller, "caller must not be null");
    requireCallerIdentity(caller);
    String requestedSelector = normalizeSelector(requestedWorldSelector);
    String worldSlug = normalizeSelector(canonicalWorldSlug);
    if (requestedSelector == null || worldSlug == null) {
      throw new IllegalArgumentException("world selectors must not be blank");
    }
    List<ScopedRealm> safeScopes =
        List.copyOf(Objects.requireNonNull(scopes, "scopes must not be null"));
    safeScopes.forEach(scope -> validateScopeIdentity(caller, scope));
    long nowMillis = Instant.now().toEpochMilli();
    List<StoredScopedRealm> storedScopes =
        safeScopes.stream()
            .filter(scope -> scope.expiresAt().toEpochMilli() > nowMillis)
            .map(scope -> storeScope(scope, nowMillis))
            .toList();

    mutate(
        caller.sessionId(),
        nowMillis,
        current -> {
          LobbyRecord record = current == null ? LobbyRecord.empty(caller.sessionId()) : current;
          if (record.sessionId() != caller.sessionId()) {
            throw new ConflictingIdentityException("lobby record transport session did not match");
          }
          if (record.accountId() > 0L && record.accountId() != caller.accountId()) {
            record = LobbyRecord.empty(caller.sessionId()).withWorldSnapshotFrom(record);
          }
          Map<String, List<StoredScopedRealm>> byWorld = new HashMap<>(record.scopesByWorld());
          Map<String, String> worldBySelector = new HashMap<>(record.worldBySelector());
          byWorld.remove(worldSlug);
          worldBySelector.entrySet().removeIf(entry -> worldSlug.equals(entry.getValue()));
          if (!storedScopes.isEmpty()) {
            byWorld.put(worldSlug, storedScopes);
            worldBySelector.put(worldSlug, worldSlug);
            worldBySelector.put(requestedSelector, worldSlug);
          }
          return new LobbyRecord(
              caller.sessionId(),
              caller.accountId(),
              record.worldsExpiresAtEpochMs(),
              record.catalogFingerprint(),
              record.ordinalTargets(),
              byWorld,
              worldBySelector);
        });
  }

  public void clearWorldScopes(SessionContext caller, String worldSelector) {
    Objects.requireNonNull(caller, "caller must not be null");
    requireCallerIdentity(caller);
    String selector = normalizeSelector(worldSelector);
    if (selector == null) {
      return;
    }
    long nowMillis = Instant.now().toEpochMilli();
    mutate(
        caller.sessionId(),
        nowMillis,
        current -> {
          if (current == null) {
            return null;
          }
          if (current.sessionId() != caller.sessionId()) {
            throw new ConflictingIdentityException("lobby record transport session did not match");
          }
          LobbyRecord record = current;
          if (record.accountId() > 0L && record.accountId() != caller.accountId()) {
            record = LobbyRecord.empty(caller.sessionId()).withWorldSnapshotFrom(record);
          }
          String worldSlug = record.worldBySelector().get(selector);
          if (worldSlug == null) {
            return record.withAccountId(caller.accountId());
          }
          Map<String, List<StoredScopedRealm>> byWorld = new HashMap<>(record.scopesByWorld());
          byWorld.remove(worldSlug);
          Map<String, String> worldBySelector = new HashMap<>(record.worldBySelector());
          worldBySelector.entrySet().removeIf(entry -> worldSlug.equals(entry.getValue()));
          return new LobbyRecord(
              caller.sessionId(),
              caller.accountId(),
              record.worldsExpiresAtEpochMs(),
              record.catalogFingerprint(),
              record.ordinalTargets(),
              byWorld,
              worldBySelector);
        });
  }

  public Optional<ScopedRealm> publicProductionScope(
      SessionContext caller, String worldSelector, Instant now) {
    Objects.requireNonNull(caller, "caller must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireCallerIdentity(caller);
    String selector = normalizeSelector(worldSelector);
    if (selector == null) {
      return Optional.empty();
    }
    LobbyRecord record = readRecord(caller.sessionId());
    if (!matchesCaller(record, caller)) {
      return Optional.empty();
    }
    String worldSlug = record.worldBySelector().get(selector);
    if (worldSlug == null) {
      return Optional.empty();
    }
    List<ScopedRealm> publicScopes =
        record.scopesByWorld().getOrDefault(worldSlug, List.of()).stream()
            .map(DirectTextConnectScopeSessionStore::fromStoredScope)
            .filter(scope -> scope.expiresAt().isAfter(now))
            .filter(ScopedRealm::publicProductionRealm)
            .toList();
    return publicScopes.size() == 1 ? Optional.of(publicScopes.getFirst()) : Optional.empty();
  }

  /** Atomically binds and reuses one JOIN request ID for the exact Account scope. */
  public Optional<JoinScope> publicProductionScopeForJoin(
      SessionContext caller, String worldSelector, Instant now) {
    Objects.requireNonNull(caller, "caller must not be null");
    Objects.requireNonNull(now, "now must not be null");
    requireCallerIdentity(caller);
    String selector = normalizeSelector(worldSelector);
    if (selector == null) {
      return Optional.empty();
    }
    long nowMillis = now.toEpochMilli();
    JoinScope[] selected = new JoinScope[1];
    mutate(
        caller.sessionId(),
        nowMillis,
        current -> {
          selected[0] = null;
          if (!matchesCaller(current, caller)) {
            return current;
          }
          String worldSlug = current.worldBySelector().get(selector);
          if (worldSlug == null) {
            return current;
          }
          List<StoredScopedRealm> scopes =
              current.scopesByWorld().getOrDefault(worldSlug, List.of());
          List<StoredScopedRealm> validPublicScopes =
              scopes.stream()
                  .filter(scope -> scope.expiresAtEpochMs() > nowMillis)
                  .filter(StoredScopedRealm::publicProductionRealm)
                  .toList();
          if (validPublicScopes.size() != 1) {
            return current;
          }
          StoredScopedRealm scope = validPublicScopes.getFirst();
          String requestId = scope.joinRequestId();
          StoredScopedRealm boundScope = scope;
          if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
            boundScope = scope.withJoinRequestId(requestId);
          }
          if (boundScope != scope) {
            StoredScopedRealm scopeToReplace = scope;
            StoredScopedRealm scopeToStore = boundScope;
            List<StoredScopedRealm> updatedScopes =
                scopes.stream()
                    .map(candidate -> candidate == scopeToReplace ? scopeToStore : candidate)
                    .toList();
            Map<String, List<StoredScopedRealm>> byWorld = new HashMap<>(current.scopesByWorld());
            byWorld.put(worldSlug, updatedScopes);
            current = current.withScopes(byWorld, current.worldBySelector());
          }
          selected[0] = new JoinScope(fromStoredScope(boundScope), requestId);
          return current;
        });
    return Optional.ofNullable(selected[0]);
  }

  public void clearSession(long transportSessionId) {
    requireTransportSessionId(transportSessionId);
    backend.delete(key(transportSessionId));
  }

  private void mutate(long transportSessionId, long nowMillis, RecordMutation mutation) {
    requireTransportSessionId(transportSessionId);
    String key = key(transportSessionId);
    for (int attempt = 0; attempt < MAX_CAS_ATTEMPTS; attempt++) {
      String currentRaw = backend.get(key);
      LobbyRecord current = decode(currentRaw, transportSessionId);
      LobbyRecord updated = pruneExpired(mutation.apply(current), nowMillis);
      String updatedRaw = encode(updated);
      long ttlMillis = remainingTtlMillis(updated, nowMillis);
      boolean write = ttlMillis > 0L && updated != null;
      if (backend.compareAndSet(key, currentRaw, updatedRaw, write, Math.max(ttlMillis, 1L))) {
        return;
      }
    }
    throw new StoreUnavailableException("lobby state changed too often to update atomically");
  }

  private LobbyRecord readRecord(long transportSessionId) {
    requireTransportSessionId(transportSessionId);
    String currentRaw = backend.get(key(transportSessionId));
    return decode(currentRaw, transportSessionId);
  }

  private LobbyRecord decode(String json, long expectedSessionId) {
    if (json == null) {
      return null;
    }
    try {
      LobbyRecord record = objectMapper.readValue(json, LobbyRecord.class);
      if (record == null || record.sessionId() != expectedSessionId || record.accountId() < 0L) {
        throw new ConflictingIdentityException("lobby state identity was invalid");
      }
      return record.normalized();
    } catch (JacksonException ex) {
      throw new StoreUnavailableException("lobby state could not be decoded", ex);
    }
  }

  private String encode(LobbyRecord record) {
    if (record == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(record.normalized());
    } catch (JacksonException ex) {
      throw new StoreUnavailableException("lobby state could not be encoded", ex);
    }
  }

  private LobbyRecord pruneExpired(LobbyRecord record, long nowMillis) {
    if (record == null) {
      return null;
    }
    long worldsExpiry =
        record.worldsExpiresAtEpochMs() > nowMillis ? record.worldsExpiresAtEpochMs() : 0L;
    String fingerprint = worldsExpiry > 0L ? record.catalogFingerprint() : null;
    List<WorldOrdinalTarget> ordinalTargets =
        worldsExpiry > 0L ? record.ordinalTargets() : List.of();
    Map<String, List<StoredScopedRealm>> scopesByWorld = new HashMap<>();
    record
        .scopesByWorld()
        .forEach(
            (world, scopes) -> {
              List<StoredScopedRealm> remaining =
                  scopes.stream().filter(scope -> scope.expiresAtEpochMs() > nowMillis).toList();
              if (!remaining.isEmpty()) {
                scopesByWorld.put(world, remaining);
              }
            });
    Map<String, String> worldBySelector = new HashMap<>(record.worldBySelector());
    worldBySelector.entrySet().removeIf(entry -> !scopesByWorld.containsKey(entry.getValue()));
    if (worldsExpiry == 0L && scopesByWorld.isEmpty()) {
      return null;
    }
    return new LobbyRecord(
        record.sessionId(),
        record.accountId(),
        worldsExpiry,
        fingerprint,
        ordinalTargets,
        scopesByWorld,
        worldBySelector);
  }

  private long remainingTtlMillis(LobbyRecord record, long nowMillis) {
    if (record == null) {
      return 0L;
    }
    long maxExpiry = record.worldsExpiresAtEpochMs();
    for (List<StoredScopedRealm> scopes : record.scopesByWorld().values()) {
      for (StoredScopedRealm scope : scopes) {
        maxExpiry = Math.max(maxExpiry, scope.expiresAtEpochMs());
      }
    }
    return maxExpiry - nowMillis;
  }

  private StoredScopedRealm storeScope(ScopedRealm scope, long nowMillis) {
    long requestedExpiry = scope.expiresAt().toEpochMilli();
    long boundedExpiry = Math.min(requestedExpiry, nowMillis + MAX_ACCOUNT_SCOPE_TTL.toMillis());
    if (boundedExpiry <= nowMillis) {
      throw new IllegalArgumentException("Account scope must not be expired");
    }
    String contextBase64 = Base64.getEncoder().encodeToString(scope.playerContext().toByteArray());
    return new StoredScopedRealm(
        scope.realmSlug(),
        scope.publicProductionRealm(),
        scope.connectScopeId(),
        boundedExpiry,
        contextBase64,
        scope.joinRequestId());
  }

  private static ScopedRealm fromStoredScope(StoredScopedRealm stored) {
    try {
      PlayerExecutionContext context =
          PlayerExecutionContext.parseFrom(
              Base64.getDecoder().decode(stored.playerContextBase64()));
      return new ScopedRealm(
          stored.realmSlug(),
          stored.publicProductionRealm(),
          stored.connectScopeId(),
          Instant.ofEpochMilli(stored.expiresAtEpochMs()),
          context,
          stored.joinRequestId());
    } catch (IOException | IllegalArgumentException ex) {
      throw new StoreUnavailableException("stored Account scope was invalid", ex);
    }
  }

  private static void validateScopeIdentity(SessionContext caller, ScopedRealm scope) {
    Objects.requireNonNull(scope, "scope must not be null");
    PlayerExecutionContext context = scope.playerContext();
    if (!Long.toString(caller.accountId()).equals(context.getAccountId())
        || !Long.toString(caller.sessionId()).equals(context.getSessionId())) {
      throw new ConflictingIdentityException("Account scope caller identity did not match session");
    }
  }

  private static boolean matchesCaller(LobbyRecord record, SessionContext caller) {
    return record != null
        && record.sessionId() == caller.sessionId()
        && record.accountId() == caller.accountId();
  }

  private static void validateOrdinalTargets(List<WorldOrdinalTarget> targets) {
    for (int index = 0; index < targets.size(); index++) {
      WorldOrdinalTarget target =
          Objects.requireNonNull(targets.get(index), "target must not be null");
      if (target.ordinal() != index + 1) {
        throw new IllegalArgumentException("world ordinal targets must be contiguous and ordered");
      }
    }
  }

  private static void requireCallerIdentity(SessionContext caller) {
    if (caller.accountId() <= 0L) {
      throw new IllegalArgumentException("authenticated account is required");
    }
    requireTransportSessionId(caller.sessionId());
  }

  public static void requireTransportSessionId(long transportSessionId) {
    if (transportSessionId <= 0L) {
      throw new IllegalArgumentException("positive transport session ID is required");
    }
  }

  private String key(long transportSessionId) {
    return String.format(KEY_TEMPLATE, transportSessionId);
  }

  private static String normalizeSelector(String selector) {
    if (selector == null || selector.isBlank()) {
      return null;
    }
    return selector.trim().toLowerCase(Locale.ROOT);
  }

  private static DefaultRedisScript<Long> buildCompareAndSetScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setResultType(Long.class);
    script.setScriptText(
        """
        local current = redis.call('GET', KEYS[1])
        if ARGV[1] == '1' then
          if current ~= ARGV[2] then return 0 end
        elseif current then
          return 0
        end
        if ARGV[3] == '1' then
          redis.call('SET', KEYS[1], ARGV[4], 'PX', ARGV[5])
        else
          redis.call('DEL', KEYS[1])
        end
        return 1
        """);
    return script;
  }

  public record WorldOrdinalTarget(
      int ordinal,
      String worldSlug,
      long tenantId,
      long catalogRevision,
      String targetFingerprint) {
    public WorldOrdinalTarget {
      if (ordinal < 1 || tenantId <= 0L || catalogRevision < 0L) {
        throw new IllegalArgumentException("world ordinal target authority was invalid");
      }
      if (worldSlug == null
          || worldSlug.isBlank()
          || targetFingerprint == null
          || targetFingerprint.isBlank()) {
        throw new IllegalArgumentException("world ordinal target identity must not be blank");
      }
    }
  }

  public record WorldsSnapshot(
      String catalogFingerprint, Instant expiresAt, List<WorldOrdinalTarget> ordinalTargets) {
    public WorldsSnapshot {
      if (catalogFingerprint == null || catalogFingerprint.isBlank()) {
        throw new IllegalArgumentException("catalogFingerprint must not be blank");
      }
      Objects.requireNonNull(expiresAt, "expiresAt must not be null");
      ordinalTargets =
          List.copyOf(Objects.requireNonNull(ordinalTargets, "ordinalTargets must not be null"));
      validateOrdinalTargets(ordinalTargets);
    }
  }

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP",
      justification = "Generated protobuf execution contexts are immutable value messages.")
  public record ScopedRealm(
      String realmSlug,
      boolean publicProductionRealm,
      String connectScopeId,
      Instant expiresAt,
      PlayerExecutionContext playerContext,
      String joinRequestId) {
    public ScopedRealm(
        String realmSlug,
        boolean publicProductionRealm,
        String connectScopeId,
        Instant expiresAt,
        PlayerExecutionContext playerContext) {
      this(realmSlug, publicProductionRealm, connectScopeId, expiresAt, playerContext, "");
    }

    public ScopedRealm {
      if (realmSlug == null || realmSlug.isBlank()) {
        throw new IllegalArgumentException("realmSlug must not be blank");
      }
      if (connectScopeId == null || connectScopeId.isBlank()) {
        throw new IllegalArgumentException("connectScopeId must not be blank");
      }
      Objects.requireNonNull(expiresAt, "expiresAt must not be null");
      Objects.requireNonNull(playerContext, "playerContext must not be null");
      joinRequestId = joinRequestId == null ? "" : joinRequestId;
    }
  }

  public record JoinScope(ScopedRealm scope, String requestId) {
    public JoinScope {
      Objects.requireNonNull(scope, "scope must not be null");
      if (requestId == null || requestId.isBlank()) {
        throw new IllegalArgumentException("requestId must not be blank");
      }
    }
  }

  public static final class StoreUnavailableException extends RuntimeException {
    public StoreUnavailableException(String message) {
      super(message);
    }

    public StoreUnavailableException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  public static final class ConflictingIdentityException extends RuntimeException {
    public ConflictingIdentityException(String message) {
      super(message);
    }
  }

  @FunctionalInterface
  private interface RecordMutation {
    LobbyRecord apply(LobbyRecord current);
  }

  private interface Backend {
    String get(String key);

    boolean compareAndSet(
        String key, String expectedValue, String newValue, boolean write, long ttlMillis);

    void delete(String key);
  }

  private static final class RedisBackend implements Backend {
    private final StringRedisTemplate redisTemplate;

    private RedisBackend(StringRedisTemplate redisTemplate) {
      this.redisTemplate = redisTemplate;
    }

    @Override
    public String get(String key) {
      try {
        return redisTemplate.opsForValue().get(key);
      } catch (RuntimeException ex) {
        throw new StoreUnavailableException("Redis lobby state read failed", ex);
      }
    }

    @Override
    public boolean compareAndSet(
        String key, String expectedValue, String newValue, boolean write, long ttlMillis) {
      try {
        Long updated =
            redisTemplate.execute(
                COMPARE_AND_SET_SCRIPT,
                List.of(key),
                expectedValue == null ? "0" : "1",
                expectedValue == null ? "" : expectedValue,
                write ? "1" : "0",
                newValue == null ? "" : newValue,
                Long.toString(Math.max(ttlMillis, 1L)));
        return Long.valueOf(1L).equals(updated);
      } catch (RuntimeException ex) {
        throw new StoreUnavailableException("Redis lobby state atomic update failed", ex);
      }
    }

    @Override
    public void delete(String key) {
      try {
        redisTemplate.delete(key);
      } catch (RuntimeException ex) {
        throw new StoreUnavailableException("Redis lobby state clear failed", ex);
      }
    }
  }

  private static final class InMemoryBackend implements Backend {
    private final ConcurrentMap<String, MemoryValue> values = new ConcurrentHashMap<>();

    @Override
    public String get(String key) {
      MemoryValue value = values.get(key);
      if (value == null) {
        return null;
      }
      if (value.expiresAtMillis() <= System.currentTimeMillis()) {
        values.remove(key, value);
        return null;
      }
      return value.value();
    }

    @Override
    public synchronized boolean compareAndSet(
        String key, String expectedValue, String newValue, boolean write, long ttlMillis) {
      String current = get(key);
      if (!Objects.equals(current, expectedValue)) {
        return false;
      }
      if (!write) {
        values.remove(key);
      } else {
        values.put(key, new MemoryValue(newValue, System.currentTimeMillis() + ttlMillis));
      }
      return true;
    }

    @Override
    public void delete(String key) {
      values.remove(key);
    }

    private record MemoryValue(String value, long expiresAtMillis) {}
  }

  private record StoredScopedRealm(
      String realmSlug,
      boolean publicProductionRealm,
      String connectScopeId,
      long expiresAtEpochMs,
      String playerContextBase64,
      String joinRequestId) {
    private StoredScopedRealm {
      Objects.requireNonNull(realmSlug, "realmSlug must not be null");
      Objects.requireNonNull(connectScopeId, "connectScopeId must not be null");
      Objects.requireNonNull(playerContextBase64, "playerContextBase64 must not be null");
      joinRequestId = joinRequestId == null ? "" : joinRequestId;
    }

    private StoredScopedRealm withJoinRequestId(String requestId) {
      return new StoredScopedRealm(
          realmSlug,
          publicProductionRealm,
          connectScopeId,
          expiresAtEpochMs,
          playerContextBase64,
          requestId);
    }
  }

  private record LobbyRecord(
      long sessionId,
      long accountId,
      long worldsExpiresAtEpochMs,
      String catalogFingerprint,
      List<WorldOrdinalTarget> ordinalTargets,
      Map<String, List<StoredScopedRealm>> scopesByWorld,
      Map<String, String> worldBySelector) {
    private LobbyRecord {
      ordinalTargets = ordinalTargets == null ? List.of() : List.copyOf(ordinalTargets);
      Map<String, List<StoredScopedRealm>> scopesCopy = new HashMap<>();
      if (scopesByWorld != null) {
        scopesByWorld.forEach((slug, scopes) -> scopesCopy.put(slug, List.copyOf(scopes)));
      }
      scopesByWorld = Map.copyOf(scopesCopy);
      worldBySelector = worldBySelector == null ? Map.of() : Map.copyOf(worldBySelector);
    }

    private static LobbyRecord empty(long sessionId) {
      return new LobbyRecord(sessionId, 0L, 0L, null, List.of(), Map.of(), Map.of());
    }

    private LobbyRecord normalized() {
      if (sessionId <= 0L || accountId < 0L) {
        throw new ConflictingIdentityException("lobby state identity was invalid");
      }
      return new LobbyRecord(
          sessionId,
          accountId,
          worldsExpiresAtEpochMs,
          catalogFingerprint,
          ordinalTargets,
          scopesByWorld,
          worldBySelector);
    }

    private LobbyRecord withWorldSnapshot(
        long accountId,
        long expiresAtEpochMs,
        String fingerprint,
        List<WorldOrdinalTarget> targets) {
      return new LobbyRecord(
          sessionId,
          accountId,
          expiresAtEpochMs,
          fingerprint,
          targets,
          scopesByWorld,
          worldBySelector);
    }

    private LobbyRecord withWorldSnapshotFrom(LobbyRecord other) {
      return new LobbyRecord(
          sessionId,
          accountId,
          other.worldsExpiresAtEpochMs(),
          other.catalogFingerprint(),
          other.ordinalTargets(),
          scopesByWorld,
          worldBySelector);
    }

    private LobbyRecord withAccountId(long updatedAccountId) {
      return new LobbyRecord(
          sessionId,
          updatedAccountId,
          worldsExpiresAtEpochMs,
          catalogFingerprint,
          ordinalTargets,
          scopesByWorld,
          worldBySelector);
    }

    private LobbyRecord withScopes(
        Map<String, List<StoredScopedRealm>> updatedScopes, Map<String, String> updatedSelectors) {
      return new LobbyRecord(
          sessionId,
          accountId,
          worldsExpiresAtEpochMs,
          catalogFingerprint,
          ordinalTargets,
          updatedScopes,
          updatedSelectors);
    }
  }
}
