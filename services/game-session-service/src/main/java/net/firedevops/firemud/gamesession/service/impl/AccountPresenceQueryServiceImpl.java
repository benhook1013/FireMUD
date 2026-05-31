package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.gamesession.service.AccountPresenceQueryService;
import net.firedevops.firemud.gamesession.service.AccountPresenceSnapshot;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityState;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import org.springframework.stereotype.Service;

@Service
public class AccountPresenceQueryServiceImpl implements AccountPresenceQueryService {
  private static final Comparator<GameplayPresence> ACCOUNT_PRESENCE_PREFERENCE =
      Comparator.comparing(
              GameplayPresence::lastMeaningfulActivityAtEpochMs,
              Comparator.nullsFirst(Long::compareTo))
          .thenComparing(
              GameplayPresence::lastAcceptedCommandAtEpochMs,
              Comparator.nullsFirst(Long::compareTo))
          .thenComparingLong(GameplayPresence::connectedAtEpochMs)
          .thenComparingLong(GameplayPresence::sessionId);

  private final GameplayPresenceService gameplayPresenceService;
  private final GameplayPresenceActivityResolver gameplayPresenceActivityResolver;
  private final AccountRecentPresenceService accountRecentPresenceService;
  private final AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;

  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Injected collaborators are retained only for internal query composition")
  public AccountPresenceQueryServiceImpl(
      GameplayPresenceService gameplayPresenceService,
      GameplayPresenceActivityResolver gameplayPresenceActivityResolver,
      AccountRecentPresenceService accountRecentPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService) {
    this.gameplayPresenceService = gameplayPresenceService;
    this.gameplayPresenceActivityResolver = gameplayPresenceActivityResolver;
    this.accountRecentPresenceService = accountRecentPresenceService;
    this.visibilityPolicyResolver = visibilityPolicyResolver;
    this.gameplayAdmissionPointerAuthorityService = gameplayAdmissionPointerAuthorityService;
  }

  @Override
  public List<AccountPresenceSnapshot> queryAccountPresence(
      long tenantId, long viewerAccountId, List<Long> accountIds) {
    Objects.requireNonNull(accountIds, "accountIds");
    LinkedHashSet<Long> requestedIds = new LinkedHashSet<>();
    for (Long accountId : accountIds) {
      if (accountId != null && accountId > 0) {
        requestedIds.add(accountId);
      }
    }
    if (requestedIds.isEmpty()) {
      return List.of();
    }

    Map<Long, AccountPresenceSnapshot> results = new LinkedHashMap<>();
    Map<Long, AccountRecentPresenceState> recentStates =
        accountRecentPresenceService.findByAccountIds(tenantId, requestedIds);
    for (Long accountId : requestedIds) {
      results.put(accountId, offline(tenantId, accountId, recentStates.get(accountId)));
    }

    Map<Long, List<GameplayPresence>> activePresences =
        gameplayPresenceService.listConnectedByAccountIds(tenantId, requestedIds);
    for (Map.Entry<Long, List<GameplayPresence>> entry : activePresences.entrySet()) {
      Long accountId = entry.getKey();
      GameplayPresence presence = selectCurrentPresence(tenantId, entry.getValue());
      if (presence == null) {
        continue;
      }
      GameplayPresenceActivityState activityState =
          gameplayPresenceActivityResolver.resolve(presence);
      GameplayAdmissionPointerSnapshot pointer =
          gameplayAdmissionPointerAuthorityService
              .findPointer(presence.worldSlug(), presence.realmSlug())
              .orElse(null);
      results.put(
          accountId,
          new AccountPresenceSnapshot(
              accountId,
              true,
              presence.gameInstanceId(),
              presence.playableStateScope(),
              presence.worldSlug(),
              pointer == null ? null : pointer.worldDisplayName(),
              presence.realmSlug(),
              pointer == null ? null : pointer.realmDisplayName(),
              presence.pointerVersion() > 0 ? presence.pointerVersion() : null,
              presence.characterId(),
              presence.characterName(),
              activityState,
              recentStates.containsKey(accountId)
                  ? Instant.ofEpochMilli(recentStates.get(accountId).lastSeenAtEpochMs())
                  : null,
              recentStates.containsKey(accountId)
                  ? recentStates.get(accountId).disposition()
                  : null,
              recentStates.containsKey(accountId)
                  ? recentStates.get(accountId).visibilityPolicy()
                  : visibilityPolicyResolver.resolve(tenantId, accountId, presence.role())));
    }
    return List.copyOf(new ArrayList<>(results.values()));
  }

  private GameplayPresence selectCurrentPresence(long tenantId, List<GameplayPresence> presences) {
    if (presences == null || presences.isEmpty()) {
      return null;
    }
    return presences.stream()
        .filter(Objects::nonNull)
        .filter(presence -> isCurrentPresence(tenantId, presence))
        .max(ACCOUNT_PRESENCE_PREFERENCE)
        .orElse(null);
  }

  private boolean isCurrentPresence(long tenantId, GameplayPresence presence) {
    if (presence == null
        || presence.tenantId() != tenantId
        || presence.gameInstanceId() <= 0
        || presence.pointerVersion() <= 0
        || presence.worldSlug() == null
        || presence.worldSlug().isBlank()
        || presence.realmSlug() == null
        || presence.realmSlug().isBlank()) {
      return false;
    }
    return gameplayAdmissionPointerAuthorityService
        .findPointer(presence.worldSlug(), presence.realmSlug())
        .filter(pointer -> pointer.tenantId() == tenantId)
        .filter(pointer -> pointer.gameInstanceId() == presence.gameInstanceId())
        .filter(pointer -> pointer.pointerVersion() == presence.pointerVersion())
        .isPresent();
  }

  private AccountPresenceSnapshot offline(
      long tenantId, long accountId, AccountRecentPresenceState recentState) {
    GameplayAdmissionPointerSnapshot pointer =
        recentState == null
            ? null
            : gameplayAdmissionPointerAuthorityService
                .findPointer(recentState.worldSlug(), recentState.realmSlug())
                .orElse(null);
    return new AccountPresenceSnapshot(
        accountId,
        false,
        recentState == null ? null : recentState.gameInstanceId(),
        recentState == null ? null : recentState.playableStateScope(),
        recentState == null ? null : recentState.worldSlug(),
        pointer == null ? null : pointer.worldDisplayName(),
        recentState == null ? null : recentState.realmSlug(),
        pointer == null ? null : pointer.realmDisplayName(),
        recentState == null ? null : recentState.pointerVersion(),
        null,
        null,
        null,
        recentState == null ? null : Instant.ofEpochMilli(recentState.lastSeenAtEpochMs()),
        recentState == null ? null : recentState.disposition(),
        recentState == null
            ? visibilityPolicyResolver.resolve(tenantId, accountId)
            : recentState.visibilityPolicy());
  }
}
