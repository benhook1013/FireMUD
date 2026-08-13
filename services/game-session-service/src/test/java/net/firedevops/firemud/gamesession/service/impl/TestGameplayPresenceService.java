package net.firedevops.firemud.gamesession.service.impl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.JwtUtil;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.slf4j.Logger;
import org.springframework.util.StringUtils;

/** Small process-local fake for command tests; production presence is Redis-backed. */
public final class TestGameplayPresenceService implements GameplayPresenceService {
  private static final Logger logger = LoggingUtil.getLogger(TestGameplayPresenceService.class);
  private final ConcurrentMap<Long, GameplayPresence> presences = new ConcurrentHashMap<>();
  private final JwtUtil jwtUtil;

  public TestGameplayPresenceService(JwtUtil jwtUtil) {
    this.jwtUtil = jwtUtil;
  }

  @Override
  public void registerConnected(SessionContext context) {
    if (context == null || context.tenantId() <= 0 || context.gameInstanceId() <= 0) {
      return;
    }
    presences.put(
        context.sessionId(),
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
            GameplayPresenceRoleClassifier.classifyRole(context, jwtUtil, logger),
            System.currentTimeMillis(),
            null,
            null,
            null));
  }

  @Override
  public void setExplicitAfk(long sessionId, boolean explicitAfk) {
    presences.computeIfPresent(
        sessionId,
        (ignored, existing) ->
            copy(
                existing,
                explicitAfk ? System.currentTimeMillis() : null,
                existing.lastAcceptedCommandAtEpochMs(),
                existing.lastMeaningfulActivityAtEpochMs()));
  }

  @Override
  public void recordCommandActivity(long sessionId, boolean meaningfulGameplayActivity) {
    presences.computeIfPresent(
        sessionId,
        (ignored, existing) -> {
          long now = System.currentTimeMillis();
          return copy(
              existing,
              existing.explicitAfkSinceEpochMs(),
              now,
              meaningfulGameplayActivity ? now : existing.lastMeaningfulActivityAtEpochMs());
        });
  }

  @Override
  public void removeBySessionId(long sessionId) {
    presences.remove(sessionId);
  }

  @Override
  public List<GameplayPresence> listConnectedByGameInstance(long tenantId, long gameInstanceId) {
    List<GameplayPresence> matches =
        presences.values().stream()
            .filter(
                presence ->
                    presence.tenantId() == tenantId && presence.gameInstanceId() == gameInstanceId)
            .sorted(ordering())
            .toList();
    return List.copyOf(matches);
  }

  @Override
  public Map<Long, List<GameplayPresence>> listConnectedByAccountIds(
      long tenantId, Collection<Long> accountIds) {
    if (accountIds == null || accountIds.isEmpty()) {
      return Map.of();
    }
    Map<Long, List<GameplayPresence>> results = new LinkedHashMap<>();
    for (Long accountId : accountIds) {
      if (accountId == null || accountId <= 0) {
        continue;
      }
      List<GameplayPresence> matches =
          presences.values().stream()
              .filter(
                  presence ->
                      presence.tenantId() == tenantId && presence.accountId() == accountId)
              .sorted(ordering())
              .toList();
      if (!matches.isEmpty()) {
        results.put(accountId, List.copyOf(matches));
      }
    }
    return Map.copyOf(results);
  }

  @Override
  public Optional<GameplayPresence> findConnectedBySessionId(long sessionId) {
    return Optional.ofNullable(presences.get(sessionId));
  }

  private static Comparator<GameplayPresence> ordering() {
    return Comparator.comparing((GameplayPresence presence) -> presence.role().presenceOrdering())
        .thenComparing(
            presence -> presence.characterName().toLowerCase(Locale.ROOT), String::compareTo)
        .thenComparingLong(GameplayPresence::sessionId);
  }

  private static GameplayPresence copy(
      GameplayPresence existing,
      Long explicitAfkSinceEpochMs,
      Long lastAcceptedCommandAtEpochMs,
      Long lastMeaningfulActivityAtEpochMs) {
    return new GameplayPresence(
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
        explicitAfkSinceEpochMs,
        lastAcceptedCommandAtEpochMs,
        lastMeaningfulActivityAtEpochMs);
  }

  private static String fallbackCharacterName(SessionContext context) {
    if (StringUtils.hasText(context.loginName())) {
      return context.loginName().trim();
    }
    return "session-" + context.sessionId();
  }
}
