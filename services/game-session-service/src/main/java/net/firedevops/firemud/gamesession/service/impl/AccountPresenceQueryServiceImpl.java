package net.firedevops.firemud.gamesession.service.impl;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.gamesession.service.AccountPresenceQueryService;
import net.firedevops.firemud.gamesession.service.AccountPresenceSnapshot;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshot;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
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
    Map<Long, GameplayAdmissionPointerSnapshot> currentRuntimePointers = new HashMap<>();
    Map<Long, AccountRecentPresenceState> recentStates =
        accountRecentPresenceService.findByAccountIds(tenantId, requestedIds);
    for (Long accountId : requestedIds) {
      results.put(
          accountId,
          offline(tenantId, accountId, recentStates.get(accountId), currentRuntimePointers));
    }

    Map<Long, List<GameplayPresence>> activePresences =
        gameplayPresenceService.listConnectedByAccountIds(tenantId, requestedIds);
    for (Map.Entry<Long, List<GameplayPresence>> entry : activePresences.entrySet()) {
      Long accountId = entry.getKey();
      GameplayPresence presence =
          selectCurrentPresence(tenantId, entry.getValue(), currentRuntimePointers);
      if (presence == null) {
        continue;
      }
      GameplayPresenceActivityState activityState =
          gameplayPresenceActivityResolver.resolve(presence);
      GameplayAdmissionPointerSnapshot pointer =
          currentRuntimePointer(tenantId, presence.gameInstanceId(), currentRuntimePointers)
              .orElse(null);
      AccountRecentPresenceState recentState = recentStates.get(accountId);
      Long currentGameInstanceId =
          pointer == null ? presence.gameInstanceId() : pointer.gameInstanceId();
      String currentPlayableStateScope =
          pointer == null ? presence.playableStateScope() : pointer.stateScope();
      String currentWorldSlug = pointer == null ? presence.worldSlug() : pointer.worldSlug();
      String currentWorldDisplayName = pointer == null ? null : pointer.worldDisplayName();
      String currentRealmSlug = pointer == null ? presence.realmSlug() : pointer.realmSlug();
      String currentRealmDisplayName = pointer == null ? null : pointer.realmDisplayName();
      Long currentPointerVersion =
          pointer == null
              ? (presence.pointerVersion() > 0 ? Long.valueOf(presence.pointerVersion()) : null)
              : Long.valueOf(pointer.pointerVersion());
      results.put(
          accountId,
          new AccountPresenceSnapshot(
              accountId,
              true,
              currentGameInstanceId,
              currentPlayableStateScope,
              currentWorldSlug,
              currentWorldDisplayName,
              currentRealmSlug,
              currentRealmDisplayName,
              currentPointerVersion,
              presence.characterId(),
              presence.characterName(),
              activityState,
              recentState == null ? null : Instant.ofEpochMilli(recentState.lastSeenAtEpochMs()),
              recentState == null ? null : recentState.disposition(),
              recentState == null
                  ? visibilityPolicyResolver.resolve(tenantId, accountId, presence.role())
                  : recentState.visibilityPolicy()));
    }
    return List.copyOf(new ArrayList<>(results.values()));
  }

  private GameplayPresence selectCurrentPresence(
      long tenantId,
      List<GameplayPresence> presences,
      Map<Long, GameplayAdmissionPointerSnapshot> currentRuntimePointers) {
    if (presences == null || presences.isEmpty()) {
      return null;
    }
    return presences.stream()
        .filter(Objects::nonNull)
        .filter(presence -> isCurrentPresence(tenantId, presence, currentRuntimePointers))
        .max(ACCOUNT_PRESENCE_PREFERENCE)
        .orElse(null);
  }

  private boolean isCurrentPresence(
      long tenantId,
      GameplayPresence presence,
      Map<Long, GameplayAdmissionPointerSnapshot> currentRuntimePointers) {
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
    return currentRuntimePointer(tenantId, presence.gameInstanceId(), currentRuntimePointers)
        .map(
            pointer ->
                GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                    List.of(pointer),
                    tenantId,
                    presence.gameInstanceId(),
                    presence.worldSlug(),
                    presence.realmSlug(),
                    presence.pointerVersion(),
                    presence.playableStateScope()))
        .orElse(false);
  }

  private AccountPresenceSnapshot offline(
      long tenantId,
      long accountId,
      AccountRecentPresenceState recentState,
      Map<Long, GameplayAdmissionPointerSnapshot> currentRuntimePointers) {
    GameplayAdmissionPointerSnapshot pointer =
        matchingCurrentRuntimePointer(recentState, currentRuntimePointers);
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

  private Optional<GameplayAdmissionPointerSnapshot> currentRuntimePointer(
      long tenantId,
      long gameInstanceId,
      Map<Long, GameplayAdmissionPointerSnapshot> currentRuntimePointers) {
    if (tenantId <= 0 || gameInstanceId <= 0) {
      return Optional.empty();
    }
    if (currentRuntimePointers.containsKey(gameInstanceId)) {
      return Optional.ofNullable(currentRuntimePointers.get(gameInstanceId));
    }
    GameplayAdmissionPointerSnapshot singularPointer =
        GameplayAdmissionPointerSnapshots.singularCompletePointer(
                gameplayAdmissionPointerAuthorityService
                    .listByRuntimeTarget(tenantId, gameInstanceId)
                    .stream()
                    .filter(pointer -> pointer.tenantId() == tenantId)
                    .toList())
            .orElse(null);
    currentRuntimePointers.put(gameInstanceId, singularPointer);
    return Optional.ofNullable(singularPointer);
  }

  private GameplayAdmissionPointerSnapshot matchingCurrentRuntimePointer(
      AccountRecentPresenceState recentState,
      Map<Long, GameplayAdmissionPointerSnapshot> currentRuntimePointers) {
    if (recentState == null || recentState.gameInstanceId() <= 0) {
      return null;
    }
    return currentRuntimePointer(
            recentState.tenantId(), recentState.gameInstanceId(), currentRuntimePointers)
        .filter(
            pointer ->
                GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
                    List.of(pointer),
                    recentState.tenantId(),
                    recentState.gameInstanceId(),
                    recentState.worldSlug(),
                    recentState.realmSlug(),
                    recentState.pointerVersion(),
                    recentState.playableStateScope()))
        .orElse(null);
  }
}
