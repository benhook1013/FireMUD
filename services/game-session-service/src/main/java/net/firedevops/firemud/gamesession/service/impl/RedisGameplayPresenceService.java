package net.firedevops.firemud.gamesession.service.impl;

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
import net.firedevops.firemud.gamesession.service.AccountIds;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceRole;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.PositiveLongParsing;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** Redis-backed gameplay presence store for the first WHO implementation. */
@Service
public final class RedisGameplayPresenceService implements GameplayPresenceService {
  private static final String PRESENCE_KEY_TEMPLATE = "gameplaypresence:session:%d";
  private static final String GAME_INSTANCE_SET_TEMPLATE = "gameplaypresence:%d:%d:sessions";
  private static final String ACCOUNT_SET_TEMPLATE = "gameplaypresence:%d:account:%s:sessions";
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
  private final LongSupplier currentTimeMillisSupplier;

  @Autowired
  public RedisGameplayPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      @Value("${FIREMUD_AUTH_SESSION_EXPIRATION_MS:3600000}") long sessionExpirationMs) {
    this(redisTemplate, sessionExpirationMs, System::currentTimeMillis);
  }

  RedisGameplayPresenceService(
      RedisTemplate<String, Object> redisTemplate,
      long sessionExpirationMs,
      LongSupplier currentTimeMillisSupplier) {
    this.redisTemplate = redisTemplate;
    this.presenceTtl = Duration.ofMillis(sessionExpirationMs);
    this.currentTimeMillisSupplier = currentTimeMillisSupplier;
  }

  @Override
  public void registerConnected(SessionContext context) {
    if (context == null
        || !context.hasAccountIdentity()
        || context.tenantId() <= 0
        || context.gameInstanceId() <= 0) {
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
            GameplayPresenceRoleClassifier.classifyRole(),
            currentTimeMillisSupplier.getAsLong(),
            null,
            null,
            null);
    String presenceKey = presenceKey(context.sessionId());
    String gameInstanceKey = gameInstanceKey(context.tenantId(), context.gameInstanceId());
    String accountKey = accountKey(context.tenantId(), context.accountId());
    valueOps.set(presenceKey, presence, presenceTtl);
    setOps.add(gameInstanceKey, Long.toString(context.sessionId()));
    setOps.add(accountKey, Long.toString(context.sessionId()));
    redisTemplate.expire(gameInstanceKey, presenceTtl);
    redisTemplate.expire(accountKey, presenceTtl);
  }

  @Override
  public void removeBySessionId(long sessionId) {
    GameplayPresence existing = readPresenceForMutation(presenceKey(sessionId));
    redisTemplate.delete(presenceKey(sessionId));
    if (existing != null) {
      String gameInstanceKey = gameInstanceKey(existing.tenantId(), existing.gameInstanceId());
      String accountKey = accountKey(existing.tenantId(), existing.accountId());
      SetOperations<String, Object> setOps = redisTemplate.opsForSet();
      setOps.remove(gameInstanceKey, Long.toString(sessionId));
      setOps.remove(accountKey, Long.toString(sessionId));
    }
  }

  @Override
  public void setExplicitAfk(long sessionId, boolean explicitAfk) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    GameplayPresence existing = readPresence(presenceKey(sessionId));
    if (existing == null) {
      return;
    }
    GameplayPresence current = playerPresence(existing);
    GameplayPresence updated =
        new GameplayPresence(
            current.sessionId(),
            current.tenantId(),
            current.gameInstanceId(),
            current.playableStateScope(),
            current.worldSlug(),
            current.realmSlug(),
            current.pointerVersion(),
            current.accountId(),
            current.characterId(),
            current.characterName(),
            GameplayPresenceRole.PLAYER,
            current.connectedAtEpochMs(),
            explicitAfk ? Long.valueOf(currentTimeMillisSupplier.getAsLong()) : null,
            current.lastAcceptedCommandAtEpochMs(),
            current.lastMeaningfulActivityAtEpochMs());
    valueOps.set(presenceKey(sessionId), updated, presenceTtl);
    redisTemplate.expire(
        gameInstanceKey(current.tenantId(), current.gameInstanceId()), presenceTtl);
    redisTemplate.expire(accountKey(current.tenantId(), current.accountId()), presenceTtl);
  }

  @Override
  public void recordCommandActivity(long sessionId, boolean meaningfulGameplayActivity) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    GameplayPresence existing = readPresence(presenceKey(sessionId));
    if (existing == null) {
      return;
    }
    long now = currentTimeMillisSupplier.getAsLong();
    GameplayPresence current = playerPresence(existing);
    GameplayPresence updated =
        new GameplayPresence(
            current.sessionId(),
            current.tenantId(),
            current.gameInstanceId(),
            current.playableStateScope(),
            current.worldSlug(),
            current.realmSlug(),
            current.pointerVersion(),
            current.accountId(),
            current.characterId(),
            current.characterName(),
            GameplayPresenceRole.PLAYER,
            current.connectedAtEpochMs(),
            current.explicitAfkSinceEpochMs(),
            Long.valueOf(now),
            meaningfulGameplayActivity
                ? Long.valueOf(now)
                : current.lastMeaningfulActivityAtEpochMs());
    valueOps.set(presenceKey(sessionId), updated, presenceTtl);
    redisTemplate.expire(
        gameInstanceKey(current.tenantId(), current.gameInstanceId()), presenceTtl);
    redisTemplate.expire(accountKey(current.tenantId(), current.accountId()), presenceTtl);
  }

  @Override
  public List<GameplayPresence> listConnectedByGameInstance(long tenantId, long gameInstanceId) {
    String gameInstanceKey = gameInstanceKey(tenantId, gameInstanceId);
    SetOperations<String, Object> setOps = redisTemplate.opsForSet();
    Set<Object> members = setOps.members(gameInstanceKey);
    if (members == null || members.isEmpty()) {
      return List.of();
    }

    ArrayList<GameplayPresence> matches = new ArrayList<>();
    for (Object member : members) {
      String sessionIdText = String.valueOf(member);
      String presenceKey = presenceKey(sessionIdText);
      if (presenceKey == null) {
        setOps.remove(gameInstanceKey, sessionIdText);
        continue;
      }
      GameplayPresence presence;
      try {
        presence = readPresenceForMutation(presenceKey);
      } catch (SerializationException | ClassCastException | UnreadablePresenceException ex) {
        // Keep the index member until its retained presence value can be read safely.
        continue;
      }
      if (presence == null) {
        setOps.remove(gameInstanceKey, sessionIdText);
        continue;
      }
      if (presence.tenantId() == tenantId && presence.gameInstanceId() == gameInstanceId) {
        matches.add(playerPresence(presence));
      }
    }

    Comparator<GameplayPresence> ordering =
        Comparator.comparing((GameplayPresence presence) -> presence.role().presenceOrdering())
            .thenComparing(
                presence -> presence.characterName().toLowerCase(Locale.ROOT), String::compareTo)
            .thenComparingLong(GameplayPresence::sessionId);
    matches.sort(ordering);
    return List.copyOf(matches);
  }

  @Override
  public Map<String, List<GameplayPresence>> listConnectedByAccountIds(
      long tenantId, Collection<String> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return Map.of();
    }
    SetOperations<String, Object> setOps = redisTemplate.opsForSet();
    LinkedHashMap<String, List<GameplayPresence>> matches = new LinkedHashMap<>();
    for (String accountId : accountIds) {
      if (!AccountIds.isCanonicalNonNilUuid(accountId)) {
        continue;
      }
      String accountKey = accountKey(tenantId, accountId);
      Set<Object> members = setOps.members(accountKey);
      if (members == null || members.isEmpty()) {
        continue;
      }
      ArrayList<GameplayPresence> accountMatches = new ArrayList<>();
      for (Object member : members) {
        String sessionIdText = String.valueOf(member);
        String presenceKey = presenceKey(sessionIdText);
        if (presenceKey == null) {
          setOps.remove(accountKey, sessionIdText);
          continue;
        }
        GameplayPresence presence;
        try {
          presence = readPresenceForMutation(presenceKey);
        } catch (SerializationException | ClassCastException | UnreadablePresenceException ex) {
          // Keep the index member until its retained presence value can be read safely.
          continue;
        }
        if (presence == null) {
          setOps.remove(accountKey, sessionIdText);
          continue;
        }
        if (presence.tenantId() != tenantId || !accountId.equals(presence.accountId())) {
          setOps.remove(accountKey, sessionIdText);
          continue;
        }
        accountMatches.add(playerPresence(presence));
      }
      if (!accountMatches.isEmpty()) {
        accountMatches.sort(ACCOUNT_PRESENCE_PREFERENCE.reversed());
        matches.put(accountId, List.copyOf(accountMatches));
      }
    }
    return Map.copyOf(matches);
  }

  @Override
  public Optional<GameplayPresence> findConnectedBySessionId(long sessionId) {
    return Optional.ofNullable(readPresence(presenceKey(sessionId)))
        .map(RedisGameplayPresenceService::playerPresence);
  }

  private GameplayPresence readPresence(String key) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    try {
      GameplayPresence presence = (GameplayPresence) valueOps.get(key);
      return hasCurrentAccountIdentity(presence) ? presence : null;
    } catch (SerializationException | ClassCastException ex) {
      // An older numeric Account carrier cannot be mapped to a UUID. Fail closed without
      // mutating the retained record; the session must authenticate through its context path.
      return null;
    }
  }

  private GameplayPresence readPresenceForMutation(String key) {
    ValueOperations<String, Object> valueOps = redisTemplate.opsForValue();
    // Unlike read-only lookups, mutation cleanup must distinguish absence from a retained value
    // that cannot be decoded under the current Account carrier. Propagate these decode failures
    // so callers do not delete or overwrite the record or its indexes.
    GameplayPresence presence = (GameplayPresence) valueOps.get(key);
    if (presence != null && !hasCurrentAccountIdentity(presence)) {
      throw new UnreadablePresenceException();
    }
    return presence;
  }

  private static boolean hasCurrentAccountIdentity(GameplayPresence presence) {
    return presence == null || AccountIds.isCanonicalNonNilUuid(presence.accountId());
  }

  private static GameplayPresence playerPresence(GameplayPresence presence) {
    if (presence.role() == GameplayPresenceRole.PLAYER) {
      return presence;
    }
    return new GameplayPresence(
        presence.sessionId(),
        presence.tenantId(),
        presence.gameInstanceId(),
        presence.playableStateScope(),
        presence.worldSlug(),
        presence.realmSlug(),
        presence.pointerVersion(),
        presence.accountId(),
        presence.characterId(),
        presence.characterName(),
        GameplayPresenceRole.PLAYER,
        presence.connectedAtEpochMs(),
        presence.explicitAfkSinceEpochMs(),
        presence.lastAcceptedCommandAtEpochMs(),
        presence.lastMeaningfulActivityAtEpochMs());
  }

  private static final class UnreadablePresenceException extends RuntimeException {}

  private String presenceKey(long sessionId) {
    return String.format(PRESENCE_KEY_TEMPLATE, sessionId);
  }

  private String presenceKey(String sessionId) {
    PositiveLongParsing.ParsedPositiveLong parsed =
        PositiveLongParsing.parseOptionalText(sessionId, "sessionId");
    if (!parsed.valid()) {
      return null;
    }
    return presenceKey(parsed.value());
  }

  private String gameInstanceKey(long tenantId, long gameInstanceId) {
    return String.format(GAME_INSTANCE_SET_TEMPLATE, tenantId, gameInstanceId);
  }

  private String accountKey(long tenantId, String accountId) {
    return String.format(ACCOUNT_SET_TEMPLATE, tenantId, accountId);
  }

  private String fallbackCharacterName(SessionContext context) {
    if (StringUtils.hasText(context.loginName())) {
      return context.loginName().trim();
    }
    return "session-" + context.sessionId();
  }
}
