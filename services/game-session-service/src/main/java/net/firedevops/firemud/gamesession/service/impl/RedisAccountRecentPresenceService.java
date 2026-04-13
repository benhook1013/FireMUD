package net.firedevops.firemud.gamesession.service.impl;

import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import net.firedevops.firemud.gamesession.config.PresenceProperties;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(
    name = "game-session.dev-isolated",
    havingValue = "false",
    matchIfMissing = true)
public final class RedisAccountRecentPresenceService implements AccountRecentPresenceService {
  private static final String RECENT_PRESENCE_KEY_TEMPLATE = "accountrecentpresence:%d:%d";

  private final RedisTemplate<String, Object> redisTemplate;
  private final SessionContextService sessionContextService;
  private final GameplayPresenceService gameplayPresenceService;
  private final AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver;
  private final Duration ttl;
  private final LongSupplier currentTimeMillisSupplier;

  @Autowired
  public RedisAccountRecentPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      SessionContextService sessionContextService,
      GameplayPresenceService gameplayPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver,
      PresenceProperties presenceProperties) {
    this(
        redisTemplate,
        sessionContextService,
        gameplayPresenceService,
        visibilityPolicyResolver,
        presenceProperties,
        System::currentTimeMillis);
  }

  RedisAccountRecentPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      SessionContextService sessionContextService,
      GameplayPresenceService gameplayPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver,
      PresenceProperties presenceProperties,
      LongSupplier currentTimeMillisSupplier) {
    this.redisTemplate = redisTemplate;
    this.sessionContextService = sessionContextService;
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
    AccountPresenceVisibilityPolicy policy =
        visibilityPolicyResolver.resolve(
            context.tenantId(),
            context.accountId(),
            gameplayPresenceService
                .findConnectedBySessionId(context.sessionId())
                .map(GameplayPresence::role)
                .orElse(null));
    write(context.tenantId(), context.accountId(), policy, currentTimeMillisSupplier.getAsLong());
  }

  @Override
  public void recordActivity(long sessionId) {
    sessionContextService
        .findBySessionId(sessionId)
        .ifPresent(
            context ->
                write(
                    context.tenantId(),
                    context.accountId(),
                    visibilityPolicyResolver.resolve(
                        context.tenantId(),
                        context.accountId(),
                        gameplayPresenceService
                            .findConnectedBySessionId(sessionId)
                            .map(GameplayPresence::role)
                            .orElse(null)),
                    currentTimeMillisSupplier.getAsLong()));
  }

  @Override
  public void recordDisconnect(long sessionId) {
    sessionContextService
        .findBySessionId(sessionId)
        .ifPresent(
            context ->
                write(
                    context.tenantId(),
                    context.accountId(),
                    visibilityPolicyResolver.resolve(
                        context.tenantId(),
                        context.accountId(),
                        gameplayPresenceService
                            .findConnectedBySessionId(sessionId)
                            .map(GameplayPresence::role)
                            .orElse(null)),
                    currentTimeMillisSupplier.getAsLong()));
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
      long tenantId,
      long accountId,
      AccountPresenceVisibilityPolicy visibilityPolicy,
      long timestampMs) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (valueOps == null) {
      return;
    }
    valueOps.set(
        key(tenantId, accountId),
        new AccountRecentPresenceState(tenantId, accountId, timestampMs, visibilityPolicy),
        ttl);
  }

  private String key(long tenantId, long accountId) {
    return String.format(RECENT_PRESENCE_KEY_TEMPLATE, tenantId, accountId);
  }
}
