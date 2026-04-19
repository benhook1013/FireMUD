package net.firedevops.firemud.gamesession.service.impl;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicy;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public final class InMemoryAccountRecentPresenceService implements AccountRecentPresenceService {
  private final ConcurrentMap<String, AccountRecentPresenceState> states =
      new ConcurrentHashMap<>();
  private final SessionContextService sessionContextService;
  private final GameplayPresenceService gameplayPresenceService;
  private final AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver;
  private final LongSupplier currentTimeMillisSupplier;

  @Autowired
  public InMemoryAccountRecentPresenceService(
      SessionContextService sessionContextService,
      GameplayPresenceService gameplayPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver) {
    this(
        sessionContextService,
        gameplayPresenceService,
        visibilityPolicyResolver,
        System::currentTimeMillis);
  }

  InMemoryAccountRecentPresenceService(
      SessionContextService sessionContextService,
      GameplayPresenceService gameplayPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver,
      LongSupplier currentTimeMillisSupplier) {
    this.sessionContextService = sessionContextService;
    this.gameplayPresenceService = gameplayPresenceService;
    this.visibilityPolicyResolver = visibilityPolicyResolver;
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
    record(
        context.tenantId(),
        context.accountId(),
        AccountRecentPresenceDisposition.TRANSPORT_LOSS,
        policy);
  }

  @Override
  public void recordActivity(long sessionId) {
    sessionContextService
        .findBySessionId(sessionId)
        .ifPresent(
            context ->
                record(
                    context.tenantId(),
                    context.accountId(),
                    AccountRecentPresenceDisposition.TRANSPORT_LOSS,
                    visibilityPolicyResolver.resolve(
                        context.tenantId(),
                        context.accountId(),
                        gameplayPresenceService
                            .findConnectedBySessionId(sessionId)
                            .map(GameplayPresence::role)
                            .orElse(null))));
  }

  @Override
  public void recordDisconnect(long sessionId, AccountRecentPresenceDisposition disposition) {
    sessionContextService
        .findBySessionId(sessionId)
        .ifPresent(
            context ->
                record(
                    context.tenantId(),
                    context.accountId(),
                    disposition,
                    visibilityPolicyResolver.resolve(
                        context.tenantId(),
                        context.accountId(),
                        gameplayPresenceService
                            .findConnectedBySessionId(sessionId)
                            .map(GameplayPresence::role)
                            .orElse(null))));
  }

  @Override
  public Map<Long, AccountRecentPresenceState> findByAccountIds(
      long tenantId, Collection<Long> accountIds) {
    LinkedHashMap<Long, AccountRecentPresenceState> results = new LinkedHashMap<>();
    for (Long accountId : accountIds) {
      if (accountId == null || accountId <= 0) {
        continue;
      }
      Optional.ofNullable(states.get(key(tenantId, accountId)))
          .ifPresent(state -> results.put(accountId, state));
    }
    return Map.copyOf(results);
  }

  private void record(
      long tenantId,
      long accountId,
      AccountRecentPresenceDisposition disposition,
      AccountPresenceVisibilityPolicy visibilityPolicy) {
    states.put(
        key(tenantId, accountId),
        new AccountRecentPresenceState(
            tenantId,
            accountId,
            currentTimeMillisSupplier.getAsLong(),
            disposition,
            visibilityPolicy));
  }

  private String key(long tenantId, long accountId) {
    return tenantId + ":" + accountId;
  }
}
