package net.firedevops.firemud.gamesession.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.AccountPresenceQueryService;
import net.firedevops.firemud.gamesession.service.AccountPresenceSnapshot;
import net.firedevops.firemud.gamesession.service.AccountPresenceVisibilityPolicyResolver;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceService;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceState;
import net.firedevops.firemud.gamesession.service.GameplayPresence;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityResolver;
import net.firedevops.firemud.gamesession.service.GameplayPresenceActivityState;
import net.firedevops.firemud.gamesession.service.GameplayPresenceService;
import org.springframework.stereotype.Service;

@Service
public class AccountPresenceQueryServiceImpl implements AccountPresenceQueryService {
  private final GameInstanceRepository gameInstanceRepository;
  private final GameplayPresenceService gameplayPresenceService;
  private final GameplayPresenceActivityResolver gameplayPresenceActivityResolver;
  private final AccountRecentPresenceService accountRecentPresenceService;
  private final AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver;
  private final GameplayWorldCatalog gameplayWorldCatalog;

  public AccountPresenceQueryServiceImpl(
      GameInstanceRepository gameInstanceRepository,
      GameplayPresenceService gameplayPresenceService,
      GameplayPresenceActivityResolver gameplayPresenceActivityResolver,
      AccountRecentPresenceService accountRecentPresenceService,
      AccountPresenceVisibilityPolicyResolver visibilityPolicyResolver,
      GameplayWorldCatalog gameplayWorldCatalog) {
    this.gameInstanceRepository = gameInstanceRepository;
    this.gameplayPresenceService = gameplayPresenceService;
    this.gameplayPresenceActivityResolver = gameplayPresenceActivityResolver;
    this.accountRecentPresenceService = accountRecentPresenceService;
    this.visibilityPolicyResolver = visibilityPolicyResolver;
    this.gameplayWorldCatalog = gameplayWorldCatalog;
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

    List<GameInstance> runningInstances =
        gameInstanceRepository.findByTenantIdAndOwnerAccountIdInAndStatus(
            tenantId, requestedIds, "RUNNING");
    for (GameInstance instance : runningInstances) {
      GameplayPresence presence =
          gameplayPresenceService.findConnectedBySessionId(instance.getId()).orElse(null);
      if (presence == null) {
        continue;
      }
      GameplayPresenceActivityState activityState =
          gameplayPresenceActivityResolver.resolve(presence);
      GameplayWorldCatalog.RuntimeRealmTarget runtimeTarget =
          gameplayWorldCatalog
              .resolveRuntimeTarget(tenantId, presence.gameInstanceId())
              .orElse(null);
      results.put(
          instance.getOwnerAccountId(),
          new AccountPresenceSnapshot(
              instance.getOwnerAccountId(),
              true,
              presence.gameInstanceId(),
              runtimeTarget == null ? null : runtimeTarget.worldSlug(),
              runtimeTarget == null ? null : runtimeTarget.worldDisplayName(),
              runtimeTarget == null ? null : runtimeTarget.realmSlug(),
              runtimeTarget == null ? null : runtimeTarget.realmDisplayName(),
              presence.characterId(),
              presence.characterName(),
              activityState,
              recentStates.containsKey(instance.getOwnerAccountId())
                  ? Instant.ofEpochMilli(
                      recentStates.get(instance.getOwnerAccountId()).lastSeenAtEpochMs())
                  : null,
              recentStates.containsKey(instance.getOwnerAccountId())
                  ? recentStates.get(instance.getOwnerAccountId()).visibilityPolicy()
                  : visibilityPolicyResolver.resolve(
                      tenantId, instance.getOwnerAccountId(), presence.role())));
    }
    return List.copyOf(new ArrayList<>(results.values()));
  }

  private AccountPresenceSnapshot offline(
      long tenantId, long accountId, AccountRecentPresenceState recentState) {
    return new AccountPresenceSnapshot(
        accountId,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        recentState == null ? null : Instant.ofEpochMilli(recentState.lastSeenAtEpochMs()),
        recentState == null
            ? visibilityPolicyResolver.resolve(tenantId, accountId)
            : recentState.visibilityPolicy());
  }
}
