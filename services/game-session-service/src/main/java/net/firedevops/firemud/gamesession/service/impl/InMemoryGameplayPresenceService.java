package net.firedevops.firemud.gamesession.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

/** Local in-memory gameplay presence store for the first WHO implementation. */
public final class InMemoryGameplayPresenceService implements GameplayPresenceService {
  private static final Logger logger = LoggingUtil.getLogger(InMemoryGameplayPresenceService.class);

  private final ConcurrentMap<Long, GameplayPresence> presences = new ConcurrentHashMap<>();
  private final JwtUtil jwtUtil;
  private final LongSupplier currentTimeMillisSupplier;

  public InMemoryGameplayPresenceService(JwtUtil jwtUtil) {
    this(jwtUtil, System::currentTimeMillis);
  }

  InMemoryGameplayPresenceService(JwtUtil jwtUtil, LongSupplier currentTimeMillisSupplier) {
    this.jwtUtil = jwtUtil;
    this.currentTimeMillisSupplier = currentTimeMillisSupplier;
  }

  @Override
  public void registerConnected(SessionContext context) {
    if (context == null || context.tenantId() <= 0 || context.gameInstanceId() <= 0) {
      return;
    }
    long now = currentTimeMillisSupplier.getAsLong();
    presences.put(
        context.sessionId(),
        new GameplayPresence(
            context.sessionId(),
            context.tenantId(),
            context.gameInstanceId(),
            context.worldSlug(),
            context.realmSlug(),
            context.accountId(),
            context.characterId(),
            StringUtils.hasText(context.characterName())
                ? context.characterName().trim()
                : fallbackCharacterName(context),
            classifyRole(context),
            now,
            null,
            null,
            null));
  }

  @Override
  public void setExplicitAfk(long sessionId, boolean explicitAfk) {
    presences.computeIfPresent(
        sessionId,
        (ignored, existing) ->
            new GameplayPresence(
                existing.sessionId(),
                existing.tenantId(),
                existing.gameInstanceId(),
                existing.worldSlug(),
                existing.realmSlug(),
                existing.accountId(),
                existing.characterId(),
                existing.characterName(),
                existing.role(),
                existing.connectedAtEpochMs(),
                explicitAfk ? Long.valueOf(currentTimeMillisSupplier.getAsLong()) : null,
                existing.lastAcceptedCommandAtEpochMs(),
                existing.lastMeaningfulActivityAtEpochMs()));
  }

  @Override
  public void recordCommandActivity(long sessionId, boolean meaningfulGameplayActivity) {
    presences.computeIfPresent(
        sessionId,
        (ignored, existing) -> {
          long now = currentTimeMillisSupplier.getAsLong();
          return new GameplayPresence(
              existing.sessionId(),
              existing.tenantId(),
              existing.gameInstanceId(),
              existing.worldSlug(),
              existing.realmSlug(),
              existing.accountId(),
              existing.characterId(),
              existing.characterName(),
              existing.role(),
              existing.connectedAtEpochMs(),
              existing.explicitAfkSinceEpochMs(),
              Long.valueOf(now),
              meaningfulGameplayActivity
                  ? Long.valueOf(now)
                  : existing.lastMeaningfulActivityAtEpochMs());
        });
  }

  @Override
  public void removeBySessionId(long sessionId) {
    presences.remove(sessionId);
  }

  @Override
  public List<GameplayPresence> listConnectedByGameInstance(long tenantId, long gameInstanceId) {
    Comparator<GameplayPresence> ordering =
        Comparator.comparing(
                (GameplayPresence presence) -> presence.role() == GameplayPresenceRole.GOD ? 0 : 1)
            .thenComparing(
                presence -> presence.characterName().toLowerCase(Locale.ROOT), String::compareTo)
            .thenComparingLong(GameplayPresence::sessionId);
    ArrayList<GameplayPresence> matches = new ArrayList<>();
    for (GameplayPresence presence : presences.values()) {
      if (presence.tenantId() == tenantId && presence.gameInstanceId() == gameInstanceId) {
        matches.add(presence);
      }
    }
    matches.sort(ordering);
    return List.copyOf(matches);
  }

  @Override
  public Optional<GameplayPresence> findConnectedBySessionId(long sessionId) {
    return Optional.ofNullable(presences.get(sessionId));
  }

  private GameplayPresenceRole classifyRole(SessionContext context) {
    if (!StringUtils.hasText(context.jwt())) {
      return GameplayPresenceRole.PLAYER;
    }
    try {
      Claims claims = jwtUtil.parseToken(context.jwt()).getPayload();
      if (hasElevatedRole(claims.get("globalRoles", List.class))) {
        return GameplayPresenceRole.GOD;
      }
      Object scopedRoles = claims.get("scopedRoles");
      if (scopedRoles instanceof Map<?, ?> scopedMap) {
        Object tenantRoles = scopedMap.get(Long.toString(context.tenantId()));
        if (hasElevatedRole(tenantRoles)) {
          return GameplayPresenceRole.GOD;
        }
      }
    } catch (JwtException ex) {
      logger.debug(
          "Failed to classify WHO role from JWT for session {} tenant {}",
          context.sessionId(),
          context.tenantId(),
          ex);
    }
    return GameplayPresenceRole.PLAYER;
  }

  private boolean hasElevatedRole(Object rolesRaw) {
    if (!(rolesRaw instanceof List<?> roles)) {
      return false;
    }
    for (Object role : roles) {
      String normalized = String.valueOf(role).trim().toLowerCase(Locale.ROOT);
      if (normalized.equals("platformadmin")
          || normalized.equals("tenantadmin")
          || normalized.equals("god")) {
        return true;
      }
    }
    return false;
  }

  private String fallbackCharacterName(SessionContext context) {
    if (StringUtils.hasText(context.loginName())) {
      return context.loginName().trim();
    }
    return "session-" + context.sessionId();
  }
}
