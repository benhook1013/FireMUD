package net.firedevops.firemud.gamesession.service.devisolated;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * In-memory {@link SessionContextService} used when the dev-isolated profile is enabled. This keeps
 * the LOGIN path runnable without Redis while still sharing the same API.
 */
@Service
@ConditionalOnProperty(name = "game-session.dev-isolated", havingValue = "true")
public final class DevIsolatedSessionContextService implements SessionContextService {
  private final Map<String, SessionContext> contexts = new ConcurrentHashMap<>();
  private final Map<String, SessionContext> identities = new ConcurrentHashMap<>();
  private final Map<String, SessionContext> names = new ConcurrentHashMap<>();

  @Override
  public void save(SessionContext context) {
    contexts.put(contextKey(context.tenantId(), context.sessionId()), context);
    if (hasGameplayIdentity(context)) {
      identities.put(identityKey(context), context);
      if (StringUtils.hasText(context.characterName())) {
        names.put(
            nameKey(context.tenantId(), context.gameInstanceId(), context.characterName()),
            context);
      }
    }
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
  public Optional<SessionContext> findByGameplayName(
      long tenantId, long gameInstanceId, String characterName) {
    if (!StringUtils.hasText(characterName)) {
      return Optional.empty();
    }
    return Optional.ofNullable(names.get(nameKey(tenantId, gameInstanceId, characterName)));
  }

  @Override
  public void deleteBySessionId(long tenantId, long sessionId) {
    Optional.ofNullable(contexts.remove(contextKey(tenantId, sessionId)))
        .ifPresent(
            context -> {
              if (hasGameplayIdentity(context)) {
                identities.remove(identityKey(context));
                if (StringUtils.hasText(context.characterName())) {
                  names.remove(
                      nameKey(
                          context.tenantId(), context.gameInstanceId(), context.characterName()));
                }
              }
            });
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

  private static String nameKey(long tenantId, long gameInstanceId, String characterName) {
    return tenantId + ":" + gameInstanceId + ":" + characterName.trim().toLowerCase(Locale.ROOT);
  }

  private static boolean hasGameplayIdentity(SessionContext context) {
    return context.gameInstanceId() > 0 && context.characterId() > 0;
  }
}
