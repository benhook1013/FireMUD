package net.firedevops.firemud.gamesession.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Redis-backed gameplay presence store for the first WHO implementation. */
@Service
public final class RedisGameplayPresenceService implements GameplayPresenceService {
  private static final Logger logger = LoggingUtil.getLogger(RedisGameplayPresenceService.class);
  private static final String PRESENCE_KEY_TEMPLATE = "gameplaypresence:session:%d";
  private static final String GAME_INSTANCE_SET_TEMPLATE = "gameplaypresence:%d:%d:sessions";
  private static final String ACCOUNT_SET_TEMPLATE = "gameplaypresence:%d:account:%d:sessions";
  private static final Comparator<GameplayPresence> ACCOUNT_PRESENCE_PREFERENCE =
      Comparator.comparing(
              GameplayPresence::lastMeaningfulActivityAtEpochMs,
              Comparator.nullsFirst(Long::compareTo))
          .thenComparing(
              GameplayPresence::lastAcceptedCommandAtEpochMs,
              Comparator.nullsFirst(Long::compareTo))
          .thenComparingLong(GameplayPresence::connectedAtEpochMs)
          .thenComparingLong(GameplayPresence::sessionId);

  private final RedisTemplate<String, Object> redisTemplate;
  private final Duration presenceTtl;
  private final JwtUtil jwtUtil;
  private final LongSupplier currentTimeMillisSupplier;

  @Autowired
  public RedisGameplayPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      JwtUtil jwtUtil,
      @Value("${FIREMUD_AUTH_SESSION_EXPIRATION_MS:3600000}") long sessionExpirationMs) {
    this(redisTemplate, jwtUtil, sessionExpirationMs, System::currentTimeMillis);
  }

  RedisGameplayPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      JwtUtil jwtUtil,
      long sessionExpirationMs,
      LongSupplier currentTimeMillisSupplier) {
    this.redisTemplate = redisTemplate;
    this.jwtUtil = jwtUtil;
    this.presenceTtl = Duration.ofMillis(sessionExpirationMs);
    this.currentTimeMillisSupplier = currentTimeMillisSupplier;
  }

  @Override
  public void registerConnected(SessionContext context) {
    if (context == null || context.tenantId() <= 0 || context.gameInstanceId() <= 0) {
      return;
    }
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    SetOperations<String, Object> setOps = redisTemplate.opsForSet();
    removeBySessionId(context.sessionId());

    GameplayPresence presence =
        new GameplayPresence(
            context.sessionId(),
            context.tenantId(),
            context.gameInstanceId(),
            context.playableStateScope(),
            context.worldSlug(),
            context.realmSlug(),
            context.pointerVersion(),
            context.accountId(),
            context.characterId(),
            StringUtils.hasText(context.characterName())
                ? context.characterName().trim()
                : fallbackCharacterName(context),
            classifyRole(context),
            currentTimeMillisSupplier.getAsLong(),
            null,
            null,
            null);
    String presenceKey = presenceKey(context.sessionId());
    String gameInstanceKey = gameInstanceKey(context.tenantId(), context.gameInstanceId());
    String accountKey = accountKey(context.tenantId(), context.accountId());
    valueOps.set(presenceKey, presence, presenceTtl);
    if (setOps != null) {
      setOps.add(gameInstanceKey, Long.toString(context.sessionId()));
      setOps.add(accountKey, Long.toString(context.sessionId()));
      redisTemplate.expire(gameInstanceKey, presenceTtl);
      redisTemplate.expire(accountKey, presenceTtl);
    }
  }

  @Override
  public void removeBySessionId(long sessionId) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (valueOps == null) {
      return;
    }
    GameplayPresence existing = (GameplayPresence) valueOps.get(presenceKey(sessionId));
    redisTemplate.delete(presenceKey(sessionId));
    if (existing != null) {
      String gameInstanceKey = gameInstanceKey(existing.tenantId(), existing.gameInstanceId());
      String accountKey = accountKey(existing.tenantId(), existing.accountId());
      SetOperations<String, Object> setOps = redisTemplate.opsForSet();
      if (setOps != null) {
        setOps.remove(gameInstanceKey, Long.toString(sessionId));
        setOps.remove(accountKey, Long.toString(sessionId));
      }
    }
  }

  @Override
  public void setExplicitAfk(long sessionId, boolean explicitAfk) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (valueOps == null) {
      return;
    }
    GameplayPresence existing = (GameplayPresence) valueOps.get(presenceKey(sessionId));
    if (existing == null) {
      return;
    }
    GameplayPresence updated =
        new GameplayPresence(
            existing.sessionId(),
            existing.tenantId(),
            existing.gameInstanceId(),
            existing.playableStateScope(),
            existing.worldSlug(),
            existing.realmSlug(),
            existing.pointerVersion(),
            existing.accountId(),
            existing.characterId(),
            existing.characterName(),
            existing.role(),
            existing.connectedAtEpochMs(),
            explicitAfk ? Long.valueOf(currentTimeMillisSupplier.getAsLong()) : null,
            existing.lastAcceptedCommandAtEpochMs(),
            existing.lastMeaningfulActivityAtEpochMs());
    valueOps.set(presenceKey(sessionId), updated, presenceTtl);
    redisTemplate.expire(
        gameInstanceKey(existing.tenantId(), existing.gameInstanceId()), presenceTtl);
    redisTemplate.expire(accountKey(existing.tenantId(), existing.accountId()), presenceTtl);
  }

  @Override
  public void recordCommandActivity(long sessionId, boolean meaningfulGameplayActivity) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (valueOps == null) {
      return;
    }
    GameplayPresence existing = (GameplayPresence) valueOps.get(presenceKey(sessionId));
    if (existing == null) {
      return;
    }
    long now = currentTimeMillisSupplier.getAsLong();
    GameplayPresence updated =
        new GameplayPresence(
            existing.sessionId(),
            existing.tenantId(),
            existing.gameInstanceId(),
            existing.playableStateScope(),
            existing.worldSlug(),
            existing.realmSlug(),
            existing.pointerVersion(),
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
    valueOps.set(presenceKey(sessionId), updated, presenceTtl);
    redisTemplate.expire(
        gameInstanceKey(existing.tenantId(), existing.gameInstanceId()), presenceTtl);
    redisTemplate.expire(accountKey(existing.tenantId(), existing.accountId()), presenceTtl);
  }

  @Override
  public List<GameplayPresence> listConnectedByGameInstance(long tenantId, long gameInstanceId) {
    String gameInstanceKey = gameInstanceKey(tenantId, gameInstanceId);
    SetOperations<String, Object> setOps = redisTemplate.opsForSet();
    if (setOps == null) {
      return List.of();
    }
    Set<Object> members = setOps.members(gameInstanceKey);
    if (members == null || members.isEmpty()) {
      return List.of();
    }

    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    ArrayList<GameplayPresence> matches = new ArrayList<>();
    for (Object member : members) {
      String sessionIdText = String.valueOf(member);
      GameplayPresence presence = (GameplayPresence) valueOps.get(presenceKey(sessionIdText));
      if (presence == null) {
        setOps.remove(gameInstanceKey, sessionIdText);
        continue;
      }
      if (presence.tenantId() == tenantId && presence.gameInstanceId() == gameInstanceId) {
        matches.add(presence);
      }
    }

    Comparator<GameplayPresence> ordering =
        Comparator.comparing(
                (GameplayPresence presence) -> presence.role() == GameplayPresenceRole.GOD ? 0 : 1)
            .thenComparing(
                presence -> presence.characterName().toLowerCase(Locale.ROOT), String::compareTo)
            .thenComparingLong(GameplayPresence::sessionId);
    matches.sort(ordering);
    return List.copyOf(matches);
  }

  @Override
  public Map<Long, GameplayPresence> findConnectedByAccountIds(
      long tenantId, Collection<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return Map.of();
    }
    SetOperations<String, Object> setOps = redisTemplate.opsForSet();
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (setOps == null || valueOps == null) {
      return Map.of();
    }

    LinkedHashMap<Long, GameplayPresence> matches = new LinkedHashMap<>();
    for (Long accountId : accountIds) {
      if (accountId == null || accountId <= 0) {
        continue;
      }
      String accountKey = accountKey(tenantId, accountId);
      Set<Object> members = setOps.members(accountKey);
      if (members == null || members.isEmpty()) {
        continue;
      }
      GameplayPresence preferred = null;
      for (Object member : members) {
        String sessionIdText = String.valueOf(member);
        GameplayPresence presence = (GameplayPresence) valueOps.get(presenceKey(sessionIdText));
        if (presence == null) {
          setOps.remove(accountKey, sessionIdText);
          continue;
        }
        if (presence.tenantId() != tenantId || presence.accountId() != accountId) {
          setOps.remove(accountKey, sessionIdText);
          continue;
        }
        if (preferred == null || ACCOUNT_PRESENCE_PREFERENCE.compare(preferred, presence) < 0) {
          preferred = presence;
        }
      }
      if (preferred != null) {
        matches.put(accountId, preferred);
      }
    }
    return Map.copyOf(matches);
  }

  @Override
  public Optional<GameplayPresence> findConnectedBySessionId(long sessionId) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    if (valueOps == null) {
      return Optional.empty();
    }
    return Optional.ofNullable((GameplayPresence) valueOps.get(presenceKey(sessionId)));
  }

  private String presenceKey(long sessionId) {
    return String.format(PRESENCE_KEY_TEMPLATE, sessionId);
  }

  private String presenceKey(String sessionId) {
    return String.format(PRESENCE_KEY_TEMPLATE, Long.parseLong(sessionId));
  }

  private String gameInstanceKey(long tenantId, long gameInstanceId) {
    return String.format(GAME_INSTANCE_SET_TEMPLATE, tenantId, gameInstanceId);
  }

  private String accountKey(long tenantId, long accountId) {
    return String.format(ACCOUNT_SET_TEMPLATE, tenantId, accountId);
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
