package net.firedevops.firemud.gamesession.service.devisolated;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * In-memory {@link SessionContextService} used when the dev-isolated profile is enabled. This keeps
 * the LOGIN path runnable without Redis while still sharing the same API.
 */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public final class DevIsolatedSessionContextService implements SessionContextService {
  private final Map<String, SessionContext> contexts = new ConcurrentHashMap<>();
  private final Map<String, SessionContext> identities = new ConcurrentHashMap<>();

  @Override
  public void save(SessionContext context) {
    contexts.put(contextKey(context.tenantId(), context.sessionId()), context);
    identities.put(identityKey(context), context);
  }

  @Override
  public Optional<SessionContext> findByTenantAndSessionId(long tenantId, long sessionId) {
    return Optional.ofNullable(contexts.get(contextKey(tenantId, sessionId)));
  }

  @Override
  public Optional<SessionContext> findByGameplayIdentity(
      long tenantId, long gameInstanceId, long characterId) {
    return Optional.ofNullable(identities.get(identityKey(tenantId, gameInstanceId, characterId)));
  }

  @Override
  public void deleteBySessionId(long tenantId, long sessionId) {
    Optional.ofNullable(contexts.remove(contextKey(tenantId, sessionId)))
        .ifPresent(context -> identities.remove(identityKey(context)));
  }

  private static String contextKey(long tenantId, long sessionId) {
    return tenantId + ":" + sessionId;
  }

  private static String identityKey(SessionContext context) {
    return identityKey(context.tenantId(), context.gameInstanceId(), context.characterId());
  }

  private static String identityKey(long tenantId, long gameInstanceId, long characterId) {
    return tenantId + ":" + gameInstanceId + ":" + characterId;
  }
}
