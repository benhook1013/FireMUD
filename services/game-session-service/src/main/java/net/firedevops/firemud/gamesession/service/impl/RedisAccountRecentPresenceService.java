package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.service.AccountIds;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;

@Service
public final class RedisAccountRecentPresenceService implements AccountRecentPresenceService {
  private static final String RECENT_PRESENCE_KEY_TEMPLATE = "accountrecentpresence:%d:%s";
  private static final int MAX_WRITE_RETRIES = 8;
  private static final Logger logger =
      LoggerFactory.getLogger(RedisAccountRecentPresenceService.class);

  private final RedisTemplate<String, Object> redisTemplate;
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private final GameplayPresenceService gameplayPresenceService;
  private final Duration ttl;
  private final LongSupplier currentTimeMillisSupplier;

  @Autowired
  public RedisAccountRecentPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      SessionRoutingNormalizationService sessionRoutingNormalizationService,
      GameplayPresenceService gameplayPresenceService,
      PresenceProperties presenceProperties) {
    this(
        redisTemplate,
        sessionRoutingNormalizationService,
        gameplayPresenceService,
        presenceProperties,
        System::currentTimeMillis);
  }

  RedisAccountRecentPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      SessionRoutingNormalizationService sessionRoutingNormalizationService,
      GameplayPresenceService gameplayPresenceService,
      PresenceProperties presenceProperties,
      LongSupplier currentTimeMillisSupplier) {
    this.redisTemplate = redisTemplate;
    this.sessionRoutingNormalizationService = sessionRoutingNormalizationService;
    this.gameplayPresenceService = gameplayPresenceService;
    this.ttl = Duration.ofMillis(presenceProperties.getRecentPresenceTtlMs());
    this.currentTimeMillisSupplier = currentTimeMillisSupplier;
  }

  @Override
  public void recordConnected(SessionContext context) {
    if (context == null
        || context.tenantId() <= 0
        || !AccountIds.isCanonicalNonNilUuid(context.accountId())) {
      return;
    }
    GameplayPresence presence =
        gameplayPresenceService.findConnectedBySessionId(context.sessionId()).orElse(null);
    write(
        routingSnapshot(context, presence),
        AccountRecentPresenceDisposition.TRANSPORT_LOSS,
        currentTimeMillisSupplier.getAsLong());
  }

  @Override
  public void recordActivity(long sessionId) {
    sessionRoutingNormalizationService
        .resolveProjectedSessionContext(Long.toString(sessionId))
        .ifPresent(
            context -> {
              GameplayPresence presence =
                  gameplayPresenceService.findConnectedBySessionId(sessionId).orElse(null);
              RoutingSnapshot snapshot = routingSnapshot(context, presence);
              write(
                  snapshot,
                  AccountRecentPresenceDisposition.TRANSPORT_LOSS,
                  currentTimeMillisSupplier.getAsLong());
            });
  }

  @Override
  public void recordDisconnect(long sessionId, AccountRecentPresenceDisposition disposition) {
    sessionRoutingNormalizationService
        .resolveProjectedSessionContext(Long.toString(sessionId))
        .ifPresent(
            context -> {
              GameplayPresence presence =
                  gameplayPresenceService.findConnectedBySessionId(sessionId).orElse(null);
              RoutingSnapshot snapshot = routingSnapshot(context, presence);
              write(snapshot, disposition, currentTimeMillisSupplier.getAsLong());
            });
  }

  @Override
  public Map<String, AccountRecentPresenceState> findByAccountIds(
      long tenantId, Collection<String> accountIds) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    LinkedHashMap<String, AccountRecentPresenceState> results = new LinkedHashMap<>();
    for (String accountId : accountIds) {
      if (!AccountIds.isCanonicalNonNilUuid(accountId) || valueOps == null) {
        continue;
      }
      try {
        AccountRecentPresenceState state =
            (AccountRecentPresenceState) valueOps.get(key(tenantId, accountId));
        if (state != null
            && state.tenantId() == tenantId
            && accountId.equals(state.accountId())
            && AccountIds.isCanonicalNonNilUuid(state.accountId())) {
          results.put(accountId, state);
        }
      } catch (SerializationException | ClassCastException ex) {
        // Retain unreadable evidence; it must not be treated as an absent value for cleanup.
      }
    }
    return Map.copyOf(results);
  }

  private void write(
      RoutingSnapshot snapshot, AccountRecentPresenceDisposition disposition, long timestampMs) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (valueOps == null
        || snapshot == null
        || snapshot.tenantId() <= 0
        || !AccountIds.isCanonicalNonNilUuid(snapshot.accountId())) {
      return;
    }
    String key = key(snapshot.tenantId(), snapshot.accountId());
    AccountRecentPresenceState state =
        new AccountRecentPresenceState(
            snapshot.tenantId(),
            snapshot.accountId(),
            snapshot.gameInstanceId(),
            snapshot.playableStateScope(),
            snapshot.worldSlug(),
            snapshot.realmSlug(),
            snapshot.pointerVersion(),
            timestampMs,
            disposition);
    for (int attempt = 0; attempt < MAX_WRITE_RETRIES; attempt++) {
      WriteAttemptResult result =
          redisTemplate.execute(
              new SessionCallback<>() {
                @Override
                @SuppressFBWarnings(
                    value = "RCN_REDUNDANT_NULLCHECK_OF_NONNULL_VALUE",
                    justification =
                        "Redis EXEC returns null when WATCH detects a concurrent key change; retrying preserves the newer retained presence.")
                public WriteAttemptResult execute(RedisOperations operations) {
                  operations.watch(key);
                  Object retained;
                  try {
                    retained = operations.opsForValue().get(key);
                  } catch (SerializationException | ClassCastException ex) {
                    operations.unwatch();
                    return WriteAttemptResult.PRESERVED;
                  }
                  if (retained != null) {
                    if (!(retained instanceof AccountRecentPresenceState retainedState)
                        || retainedState.tenantId() != snapshot.tenantId()
                        || !snapshot.accountId().equals(retainedState.accountId())
                        || !AccountIds.isCanonicalNonNilUuid(retainedState.accountId())) {
                      operations.unwatch();
                      return WriteAttemptResult.PRESERVED;
                    }
                  }
                  operations.multi();
                  operations.opsForValue().set(key, state, ttl);
                  return operations.exec() == null
                      ? WriteAttemptResult.RETRY
                      : WriteAttemptResult.COMMITTED;
                }
              });
      if (result == WriteAttemptResult.COMMITTED || result == WriteAttemptResult.PRESERVED) {
        return;
      }
    }
    logger.warn("Recent account presence projection update skipped after concurrent Redis changes");
  }

  private RoutingSnapshot routingSnapshot(SessionContext context, GameplayPresence presence) {
    if (context == null
        || context.tenantId() <= 0
        || !AccountIds.isCanonicalNonNilUuid(context.accountId())) {
      return null;
    }
    GameplayPresence effectivePresence =
        SessionContext.hasGameplayRegionBindingOrFalse(context) ? presence : null;
    GameplayAdmissionPointerSnapshots.RoutingBundle effectivePresenceRoutingBundle =
        effectivePresence == null
            ? null
            : GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
                effectivePresence.worldSlug(),
                effectivePresence.realmSlug(),
                effectivePresence.pointerVersion());
    boolean usePresenceRouting =
        effectivePresence != null && effectivePresenceRoutingBundle != null;
    GameplayAdmissionPointerSnapshots.RoutingBundle routingBundle =
        usePresenceRouting
            ? effectivePresenceRoutingBundle
            : GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
                context.worldSlug(), context.realmSlug(), context.pointerVersion());
    long gameInstanceId =
        usePresenceRouting && effectivePresence.gameInstanceId() > 0
            ? effectivePresence.gameInstanceId()
            : context.gameInstanceId();
    return new RoutingSnapshot(
        context.tenantId(),
        context.accountId(),
        gameInstanceId > 0 ? gameInstanceId : null,
        usePresenceRouting
            ? blankToNull(effectivePresence.playableStateScope())
            : blankToNull(context.playableStateScope()),
        routingBundle == null ? null : routingBundle.worldSlug(),
        routingBundle == null ? null : routingBundle.realmSlug(),
        routingBundle == null ? null : routingBundle.pointerVersion());
  }

  private String blankToNull(String value) {
    if (value != null && !value.isBlank()) {
      return value;
    }
    return null;
  }

  private String key(long tenantId, String accountId) {
    return String.format(RECENT_PRESENCE_KEY_TEMPLATE, tenantId, accountId);
  }

  private record RoutingSnapshot(
      long tenantId,
      String accountId,
      Long gameInstanceId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {}

  private enum WriteAttemptResult {
    COMMITTED,
    RETRY,
    PRESERVED
  }
}
