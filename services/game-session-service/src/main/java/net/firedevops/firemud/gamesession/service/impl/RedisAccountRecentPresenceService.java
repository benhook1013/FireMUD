package net.firedevops.firemud.gamesession.service.impl;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

@Service
public final class RedisAccountRecentPresenceService implements AccountRecentPresenceService {
  private static final String RECENT_PRESENCE_KEY_TEMPLATE = "accountrecentpresence:%d:%d";

  private final RedisTemplate<String, Object> redisTemplate;
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private final GameplayPresenceService gameplayPresenceService;
  private final AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver;
  private final Duration ttl;
  private final LongSupplier currentTimeMillisSupplier;

  @Autowired
  public RedisAccountRecentPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      SessionRoutingNormalizationService sessionRoutingNormalizationService,
      GameplayPresenceService gameplayPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver,
      PresenceProperties presenceProperties) {
    this(
        redisTemplate,
        sessionRoutingNormalizationService,
        gameplayPresenceService,
        visibilityPolicyResolver,
        presenceProperties,
        System::currentTimeMillis);
  }

  RedisAccountRecentPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      SessionRoutingNormalizationService sessionRoutingNormalizationService,
      GameplayPresenceService gameplayPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver,
      PresenceProperties presenceProperties,
      LongSupplier currentTimeMillisSupplier) {
    this.redisTemplate = redisTemplate;
    this.sessionRoutingNormalizationService = sessionRoutingNormalizationService;
    this.gameplayPresenceService = gameplayPresenceService;
    this.visibilityPolicyResolver = visibilityPolicyResolver;
    this.ttl = Duration.ofMillis(presenceProperties.getRecentPresenceTtlMs());
    this.currentTimeMillisSupplier = currentTimeMillisSupplier;
  }

  @Override
  public void recordConnected(SessionContext context) {
    if (context == null || context.tenantId() <= 0 || context.accountId() <= 0) {
      return;
    }
    GameplayPresence presence =
        gameplayPresenceService.findConnectedBySessionId(context.sessionId()).orElse(null);
    AccountPresenceVisibilityPolicy policy =
        visibilityPolicyResolver.resolve(context.tenantId(), context.accountId());
    write(
        routingSnapshot(context, presence),
        AccountRecentPresenceDisposition.TRANSPORT_LOSS,
        policy,
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
              AccountPresenceVisibilityPolicy policy =
                  visibilityPolicyResolver.resolve(context.tenantId(), context.accountId());
              write(
                  snapshot,
                  AccountRecentPresenceDisposition.TRANSPORT_LOSS,
                  policy,
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
              AccountPresenceVisibilityPolicy policy =
                  visibilityPolicyResolver.resolve(context.tenantId(), context.accountId());
              write(snapshot, disposition, policy, currentTimeMillisSupplier.getAsLong());
            });
  }

  @Override
  public Map<Long, AccountRecentPresenceState> findByAccountIds(
      long tenantId, Collection<Long> accountIds) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    LinkedHashMap<Long, AccountRecentPresenceState> results = new LinkedHashMap<>();
    for (Long accountId : accountIds) {
      if (accountId == null || accountId <= 0 || valueOps == null) {
        continue;
      }
      AccountRecentPresenceState state =
          (AccountRecentPresenceState) valueOps.get(key(tenantId, accountId));
      if (state != null) {
        results.put(accountId, state);
      }
    }
    return Map.copyOf(results);
  }

  private void write(
      RoutingSnapshot snapshot,
      AccountRecentPresenceDisposition disposition,
      AccountPresenceVisibilityPolicy visibilityPolicy,
      long timestampMs) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (valueOps == null || snapshot == null) {
      return;
    }
    valueOps.set(
        key(snapshot.tenantId(), snapshot.accountId()),
        new AccountRecentPresenceState(
            snapshot.tenantId(),
            snapshot.accountId(),
            snapshot.gameInstanceId(),
            snapshot.playableStateScope(),
            snapshot.worldSlug(),
            snapshot.realmSlug(),
            snapshot.pointerVersion(),
            timestampMs,
            disposition,
            visibilityPolicy),
        ttl);
  }

  private RoutingSnapshot routingSnapshot(SessionContext context, GameplayPresence presence) {
    if (context == null || context.tenantId() <= 0 || context.accountId() <= 0) {
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

  private String key(long tenantId, long accountId) {
    return String.format(RECENT_PRESENCE_KEY_TEMPLATE, tenantId, accountId);
  }

  private record RoutingSnapshot(
      long tenantId,
      long accountId,
      Long gameInstanceId,
      String playableStateScope,
      String worldSlug,
      String realmSlug,
      Long pointerVersion) {}
}
