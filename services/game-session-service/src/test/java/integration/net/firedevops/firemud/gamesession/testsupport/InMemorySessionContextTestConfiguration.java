package net.firedevops.firemud.gamesession.testsupport;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.gamesession.service.GameplayRuntimeRoomIds;
import net.firedevops.firemud.gamesession.service.MovementEffectIdempotencyService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class InMemorySessionContextTestConfiguration {

  @Bean
  @Primary
  SessionContextService sessionContextService() {
    return new InMemorySessionContextService();
  }

  @Bean
  @Primary
  MovementEffectIdempotencyService movementEffectIdempotencyService(
      SessionContextService sessionContextService) {
    return new InMemoryMovementEffectIdempotencyService(sessionContextService);
  }

  private static final class InMemorySessionContextService implements SessionContextService {
    private final Map<Long, SessionContext> sessionMap = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> identityMap = new ConcurrentHashMap<>();
    private final Map<String, SessionContext> nameMap = new ConcurrentHashMap<>();

    @Override
    public void save(SessionContext context) {
      sessionMap.put(context.sessionId(), context);
      if (hasGameplayIdentity(context)) {
        identityMap.put(identityKey(context), context);
        if (context.characterName() != null && !context.characterName().isBlank()) {
          nameMap.put(
              nameKey(context.tenantId(), context.gameInstanceId(), context.characterName()),
              context);
        }
      }
    }

    @Override
    public Optional<SessionContext> findBySessionId(long sessionId) {
      return Optional.ofNullable(sessionMap.get(sessionId));
    }

    @Override
    public Optional<SessionContext> findByTenantAndSessionId(long tenantId, long sessionId) {
      SessionContext context = sessionMap.get(sessionId);
      if (context == null || context.tenantId() != tenantId) {
        return Optional.empty();
      }
      return Optional.of(context);
    }

    @Override
    public Optional<SessionContext> findByGameplayIdentity(
        long tenantId, long gameInstanceId, long characterId) {
      return Optional.ofNullable(
          identityMap.get(identityKey(tenantId, gameInstanceId, characterId)));
    }

    @Override
    public Optional<SessionContext> findByGameplayName(
        long tenantId, long gameInstanceId, String characterName) {
      return Optional.ofNullable(nameMap.get(nameKey(tenantId, gameInstanceId, characterName)));
    }

    @Override
    public void deleteBySessionId(long tenantId, long sessionId) {
      SessionContext removed = sessionMap.remove(sessionId);
      if (removed != null && hasGameplayIdentity(removed)) {
        identityMap.remove(identityKey(removed));
        if (removed.characterName() != null && !removed.characterName().isBlank()) {
          nameMap.remove(
              nameKey(removed.tenantId(), removed.gameInstanceId(), removed.characterName()));
        }
      }
    }

    private String identityKey(SessionContext context) {
      return identityKey(context.tenantId(), context.gameInstanceId(), context.characterId());
    }

    private String identityKey(long tenantId, long gameInstanceId, long characterId) {
      return tenantId + ":" + gameInstanceId + ":" + characterId;
    }

    private String nameKey(long tenantId, long gameInstanceId, String characterName) {
      return tenantId + ":" + gameInstanceId + ":" + characterName.trim().toLowerCase();
    }

    private boolean hasGameplayIdentity(SessionContext context) {
      return context.gameInstanceId() > 0 && context.characterId() > 0;
    }
  }

  private static final class InMemoryMovementEffectIdempotencyService
      implements MovementEffectIdempotencyService {
    private final SessionContextService sessionContextService;
    private final Map<String, SessionContext> appliedEffects = new ConcurrentHashMap<>();

    private InMemoryMovementEffectIdempotencyService(SessionContextService sessionContextService) {
      this.sessionContextService = sessionContextService;
    }

    @Override
    public synchronized MoveEffectApplyResult apply(
        String effectId, SessionContext expectedContext, String destinationRoomInstanceId) {
      if (!GameplayRuntimeRoomIds.isCanonical(expectedContext.roomInstanceId())
          || !GameplayRuntimeRoomIds.isCanonical(destinationRoomInstanceId)) {
        return new MoveEffectApplyResult(MoveEffectApplyStatus.CONFLICT, null);
      }
      SessionContext current =
          sessionContextService.findBySessionId(expectedContext.sessionId()).orElse(null);
      if (current == null) {
        return new MoveEffectApplyResult(MoveEffectApplyStatus.NOT_FOUND, null);
      }
      if (!GameplayRuntimeRoomIds.isCanonical(current.roomInstanceId())) {
        return new MoveEffectApplyResult(MoveEffectApplyStatus.CONFLICT, current);
      }
      SessionContext replayed = appliedEffects.get(effectKey(expectedContext, effectId));
      if (replayed != null) {
        if (!GameplayRuntimeRoomIds.isCanonical(replayed.roomInstanceId())) {
          return new MoveEffectApplyResult(MoveEffectApplyStatus.CONFLICT, replayed);
        }
        return new MoveEffectApplyResult(MoveEffectApplyStatus.REPLAYED, replayed);
      }
      if (current.gameInstanceId() != expectedContext.gameInstanceId()
          || current.characterId() != expectedContext.characterId()
          || !java.util.Objects.equals(
              current.roomInstanceId(), expectedContext.roomInstanceId())) {
        return new MoveEffectApplyResult(MoveEffectApplyStatus.CONFLICT, current);
      }
      SessionContext updated =
          new SessionContext(
              current.sessionId(),
              current.tenantId(),
              current.accountId(),
              current.loginName(),
              current.characterId(),
              current.characterName(),
              current.gameInstanceId(),
              destinationRoomInstanceId,
              current.jwt(),
              current.localeTag(),
              current.bootstrapGameInstanceId());
      sessionContextService.save(updated);
      appliedEffects.put(effectKey(expectedContext, effectId), updated);
      return new MoveEffectApplyResult(MoveEffectApplyStatus.APPLIED, updated);
    }

    private String effectKey(SessionContext context, String effectId) {
      return context.tenantId() + ":" + context.sessionId() + ":" + effectId;
    }
  }
}
