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
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
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
        visibilityPolicyResolver.resolve(
            context.tenantId(), context.accountId(), effectivePresenceRole(context, presence));
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
                  visibilityPolicyResolver.resolve(
                      context.tenantId(),
                      context.accountId(),
                      effectivePresenceRole(context, presence));
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
                  visibilityPolicyResolver.resolve(
                      context.tenantId(),
                      context.accountId(),
                      effectivePresenceRole(context, presence));
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
    GameplayPresence effectivePresence = context.hasGameplayRegionBinding() ? presence : null;
    long gameInstanceId =
        effectivePresence != null && effectivePresence.gameInstanceId() > 0
            ? effectivePresence.gameInstanceId()
            : context.gameInstanceId() > 0 ? context.gameInstanceId() : 0L;
    return new RoutingSnapshot(
        context.tenantId(),
        context.accountId(),
        gameInstanceId > 0 ? gameInstanceId : null,
        firstNonBlank(
            effectivePresence == null ? null : effectivePresence.playableStateScope(),
            context.playableStateScope()),
        firstNonBlank(
            effectivePresence == null ? null : effectivePresence.worldSlug(), context.worldSlug()),
        firstNonBlank(
            effectivePresence == null ? null : effectivePresence.realmSlug(), context.realmSlug()),
        pointerVersion(context, effectivePresence));
  }

  private Long pointerVersion(SessionContext context, GameplayPresence presence) {
    long pointerVersion =
        presence != null && presence.pointerVersion() > 0
            ? presence.pointerVersion()
            : context.pointerVersion();
    return pointerVersion > 0 ? pointerVersion : null;
  }

  private GameplayPresenceRole effectivePresenceRole(
      SessionContext context, GameplayPresence presence) {
    return context != null && context.hasGameplayRegionBinding() && presence != null
        ? presence.role()
        : null;
  }

  private String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary;
    }
    if (fallback != null && !fallback.isBlank()) {
      return fallback;
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
