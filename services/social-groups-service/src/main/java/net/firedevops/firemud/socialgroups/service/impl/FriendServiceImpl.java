package net.firedevops.firemud.socialgroups.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.gamesession.v1.AccountPresenceActivityState;
import net.firedevops.firemud.gamesession.v1.AccountPresenceEntry;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import net.firedevops.firemud.socialgroups.client.AccountClient;
import net.firedevops.firemud.socialgroups.client.GameSessionClient;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresencePolicyViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import net.firedevops.firemud.socialgroups.dto.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.dto.FriendRosterEntryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.dto.FriendRosterSummaryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterViewDto;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import net.firedevops.firemud.socialgroups.repository.AccountFriendLinkRepository;
import net.firedevops.firemud.socialgroups.service.FriendService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {
  private static final Logger logger = LoggingUtil.getLogger(FriendServiceImpl.class);

  private final AccountFriendLinkRepository accountFriendLinkRepository;
  private final GameSessionClient gameSessionClient;
  private final AccountClient accountClient;

  @Override
  @Transactional
  @Timed(value = "friend.add")
  public FriendLinkDto addFriend(AddFriendRequest request) {
    validateNotSelfLink(request.accountId(), request.friendAccountId());
    logger.info(
        "Adding account-scoped friend {} -> {}", request.accountId(), request.friendAccountId());
    return accountFriendLinkRepository
        .findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            request.tenantId(), request.accountId(), request.friendAccountId(), "active")
        .map(this::toDto)
        .orElseGet(
            () -> {
              AccountFriendLink link = new AccountFriendLink();
              link.setTenantId(request.tenantId());
              link.setAccountId(request.accountId());
              link.setFriendAccountId(request.friendAccountId());
              link.setStatus("active");
              link.setCreatedAt(Instant.now());
              return toDto(accountFriendLinkRepository.save(link));
            });
  }

  @Override
  @Transactional
  @Timed(value = "friend.remove")
  public void removeFriend(long tenantId, long accountId, long friendAccountId) {
    validateNotSelfLink(accountId, friendAccountId);
    accountFriendLinkRepository
        .findFirstByTenantIdAndAccountIdAndFriendAccountIdAndStatus(
            tenantId, accountId, friendAccountId, "active")
        .ifPresent(accountFriendLinkRepository::delete);
  }

  @Override
  @Timed(value = "friend.get")
  public Optional<FriendRosterEntryDto> getFriend(
      long tenantId, long accountId, long friendAccountId) {
    return listFriends(tenantId, accountId, FriendRosterFilter.ALL).friends().stream()
        .filter(
            entry ->
                entry.friendAccountId() != null
                    && entry.friendAccountId().longValue() == friendAccountId)
        .findFirst();
  }

  @Override
  @Timed(value = "friend.get.ordinal")
  public Optional<FriendRosterEntryDto> getFriendByOrdinal(
      long tenantId, long accountId, int ordinal) {
    validateOrdinal(ordinal);
    return listFriends(tenantId, accountId, FriendRosterFilter.ALL).friends().stream()
        .filter(entry -> entry.ordinal() == ordinal)
        .findFirst();
  }

  @Override
  @Transactional
  @Timed(value = "friend.remove.ordinal")
  public Optional<FriendRosterEntryDto> removeFriendByOrdinal(
      long tenantId, long accountId, int ordinal) {
    Optional<FriendRosterEntryDto> friend = getFriendByOrdinal(tenantId, accountId, ordinal);
    friend.ifPresent(entry -> removeFriend(tenantId, accountId, entry.friendAccountId()));
    return friend;
  }

  private void validateNotSelfLink(long accountId, long friendAccountId) {
    if (accountId == friendAccountId) {
      throw new IllegalArgumentException("Cannot add or remove your own account as a friend");
    }
  }

  private void validateOrdinal(int ordinal) {
    if (ordinal <= 0) {
      throw new IllegalArgumentException("Friend roster ordinal must be greater than zero");
    }
  }

  private void validateVisibilityPolicy(FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    if (visibilityPolicy == null) {
      throw new IllegalArgumentException("Friend presence visibility policy is required");
    }
    if (!visibilityPolicy.selectableByAccountHolder()) {
      throw new IllegalArgumentException(
          "Friend presence visibility policy HIDDEN_STAFF is reserved");
    }
  }

  private FriendLinkDto toDto(AccountFriendLink link) {
    return new FriendLinkDto(
        link.getId(),
        link.getTenantId(),
        link.getAccountId(),
        link.getFriendAccountId(),
        link.getStatus(),
        link.getCreatedAt());
  }

  @Override
  @Timed(value = "friend.list")
  public FriendRosterViewDto listFriends(long tenantId, long accountId, FriendRosterFilter filter) {
    List<AccountFriendLink> links =
        accountFriendLinkRepository.findByTenantIdAndAccountIdAndStatus(
            tenantId, accountId, "active");
    if (links.isEmpty()) {
      return new FriendRosterViewDto(filter, 0, 0, List.of());
    }

    List<Long> friendAccountIds =
        links.stream().map(AccountFriendLink::getFriendAccountId).distinct().toList();
    Map<Long, FriendPresenceVisibilityPolicyValue> visibilityPolicies =
        accountClient.getPresenceVisibilityPolicies(tenantId, friendAccountIds);
    Map<Long, FriendPresenceDto> byAccountId =
        loadFriendPresenceByAccountId(tenantId, accountId, friendAccountIds, visibilityPolicies);
    List<FriendRosterEntryDto> roster =
        java.util.stream.IntStream.range(0, links.size())
            .mapToObj(
                index ->
                    toRosterEntry(index + 1, links.get(index), byAccountId, visibilityPolicies))
            .toList();
    List<FriendRosterEntryDto> filtered =
        roster.stream().filter(entry -> matchesFilter(filter, entry)).toList();

    return new FriendRosterViewDto(filter, roster.size(), filtered.size(), filtered);
  }

  @Override
  @Timed(value = "friend.summary")
  public FriendRosterSummaryDto getFriendRosterSummary(long tenantId, long accountId) {
    FriendRosterViewDto roster = listFriends(tenantId, accountId, FriendRosterFilter.ALL);
    int onlineCount = 0;
    int recentCount = 0;
    int publicCount = 0;
    int friendsOnlyCount = 0;
    int privateCount = 0;
    int hiddenStaffCount = 0;
    int unspecifiedVisibilityCount = 0;
    int sharedCount = 0;
    int isolatedCount = 0;
    int unspecifiedScopeCount = 0;
    for (FriendRosterEntryDto entry : roster.friends()) {
      if (entry.presence().online()) {
        onlineCount++;
      } else if (entry.presence().lastSeenAt() != null) {
        recentCount++;
      }
      String visibilityPolicy = entry.presence().visibilityPolicy();
      switch (visibilityPolicy == null ? "" : visibilityPolicy) {
        case "PUBLIC" -> publicCount++;
        case "FRIENDS_ONLY" -> friendsOnlyCount++;
        case "PRIVATE" -> privateCount++;
        case "HIDDEN_STAFF" -> hiddenStaffCount++;
        default -> unspecifiedVisibilityCount++;
      }
      String playableStateScope = entry.presence().playableStateScope();
      switch (playableStateScope == null ? "" : playableStateScope) {
        case "SHARED" -> sharedCount++;
        case "ISOLATED" -> isolatedCount++;
        default -> unspecifiedScopeCount++;
      }
    }
    return new FriendRosterSummaryDto(
        roster.totalCount(),
        onlineCount,
        Math.max(0, roster.totalCount() - onlineCount),
        recentCount,
        publicCount,
        friendsOnlyCount,
        privateCount,
        hiddenStaffCount,
        unspecifiedVisibilityCount,
        sharedCount,
        isolatedCount,
        unspecifiedScopeCount);
  }

  @Override
  @Timed(value = "friend.presence.list")
  public FriendPresenceViewDto listFriendPresence(
      long tenantId, long accountId, FriendRosterFilter filter) {
    FriendRosterViewDto roster = listFriends(tenantId, accountId, filter);
    return new FriendPresenceViewDto(
        roster.filter(),
        roster.totalCount(),
        roster.matchCount(),
        roster.friends().stream().map(FriendRosterEntryDto::presence).toList());
  }

  @Override
  @Timed(value = "friend.visibility.get")
  public FriendPresencePolicyViewDto getFriendPresencePolicy(long tenantId, long accountId) {
    FriendPresenceVisibilityPolicyValue visibilityPolicy =
        accountClient
            .getPresenceVisibilityPolicy(tenantId, accountId)
            .orElseThrow(
                () -> new IllegalStateException("Friend presence visibility policy unavailable"));
    return new FriendPresencePolicyViewDto(visibilityPolicy);
  }

  @Override
  @Timed(value = "friend.visibility.update")
  public FriendPresencePolicyViewDto updateFriendPresencePolicy(
      long tenantId, long accountId, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    validateVisibilityPolicy(visibilityPolicy);
    if (!accountClient.updatePresenceVisibilityPolicy(tenantId, accountId, visibilityPolicy)) {
      throw new IllegalStateException("Friend presence visibility update unavailable");
    }
    return new FriendPresencePolicyViewDto(visibilityPolicy);
  }

  private FriendRosterEntryDto toRosterEntry(
      int ordinal,
      AccountFriendLink link,
      Map<Long, FriendPresenceDto> byAccountId,
      Map<Long, FriendPresenceVisibilityPolicyValue> visibilityPolicies) {
    return new FriendRosterEntryDto(
        ordinal,
        link.getId(),
        link.getTenantId(),
        link.getAccountId(),
        link.getFriendAccountId(),
        link.getStatus(),
        link.getCreatedAt(),
        byAccountId.getOrDefault(
            link.getFriendAccountId(),
            defaultPresence(
                link.getFriendAccountId(),
                visibilityPolicyFor(link.getFriendAccountId(), visibilityPolicies))));
  }

  private boolean matchesFilter(FriendRosterFilter filter, FriendRosterEntryDto entry) {
    FriendPresenceDto presence = entry.presence();
    return switch (filter) {
      case ALL -> true;
      case ONLINE -> presence.online();
      case OFFLINE -> !presence.online();
      case RECENT -> !presence.online() && presence.lastSeenAt() != null;
      case PUBLIC -> "PUBLIC".equals(presence.visibilityPolicy());
      case FRIENDS_ONLY -> "FRIENDS_ONLY".equals(presence.visibilityPolicy());
      case PRIVATE -> "PRIVATE".equals(presence.visibilityPolicy());
      case HIDDEN_STAFF -> "HIDDEN_STAFF".equals(presence.visibilityPolicy());
      case UNSPECIFIED_VISIBILITY -> !StringUtils.hasText(presence.visibilityPolicy());
      case SHARED -> "SHARED".equals(presence.playableStateScope());
      case ISOLATED -> "ISOLATED".equals(presence.playableStateScope());
      case UNSPECIFIED_SCOPE -> !StringUtils.hasText(presence.playableStateScope());
    };
  }

  private Map<Long, FriendPresenceDto> loadFriendPresenceByAccountId(
      long tenantId,
      long accountId,
      List<Long> friendAccountIds,
      Map<Long, FriendPresenceVisibilityPolicyValue> visibilityPolicies) {
    QueryAccountPresenceResponse response =
        gameSessionClient.queryAccountPresence(tenantId, accountId, friendAccountIds);
    if (response == null) {
      return Map.of();
    }
    if (response.hasError()) {
      throw new IllegalStateException(response.getError().getMessage());
    }

    Map<Long, FriendPresenceDto> byAccountId = new LinkedHashMap<>();
    for (AccountPresenceEntry entry : response.getPresencesList()) {
      long friendAccountId = requirePositivePresenceId(entry.getAccountId(), "accountId");
      byAccountId.put(
          friendAccountId,
          mapPresence(
              friendAccountId, entry, visibilityPolicyFor(friendAccountId, visibilityPolicies)));
    }
    return byAccountId;
  }

  private FriendPresenceDto mapPresence(
      long friendAccountId,
      AccountPresenceEntry entry,
      FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return new FriendPresenceDto(
        friendAccountId,
        visibleOnline(entry, visibilityPolicy),
        visibleGameInstanceId(entry, visibilityPolicy),
        visiblePlayableStateScope(entry, visibilityPolicy),
        visibleWorldSlug(entry, visibilityPolicy),
        visibleWorldDisplayName(entry, visibilityPolicy),
        visibleRealmSlug(entry, visibilityPolicy),
        visibleRealmDisplayName(entry, visibilityPolicy),
        visiblePointerVersion(entry, visibilityPolicy),
        visibleCharacterId(entry, visibilityPolicy),
        visibleCharacterName(entry, visibilityPolicy),
        visibilityPolicy.name(),
        visibleActivityState(entry, visibilityPolicy),
        entry.getLastSeenAtMs() > 0 ? Instant.ofEpochMilli(entry.getLastSeenAtMs()) : null,
        visibleRecentDisposition(entry, visibilityPolicy));
  }

  private FriendPresenceDto defaultPresence(
      long friendAccountId, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return new FriendPresenceDto(
        friendAccountId,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        visibilityPolicy.name(),
        null,
        null,
        null);
  }

  private FriendPresenceActivityState mapActivityState(AccountPresenceActivityState activityState) {
    return switch (activityState) {
      case ACCOUNT_PRESENCE_ACTIVITY_STATE_ACTIVE -> FriendPresenceActivityState.ACTIVE;
      case ACCOUNT_PRESENCE_ACTIVITY_STATE_AUTO_AFK -> FriendPresenceActivityState.AUTO_AFK;
      case ACCOUNT_PRESENCE_ACTIVITY_STATE_EXPLICIT_AFK -> FriendPresenceActivityState.EXPLICIT_AFK;
      default -> null;
    };
  }

  private boolean visibleOnline(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case HIDDEN_STAFF -> false;
      default -> entry.getOnline();
    };
  }

  private Long visibleGameInstanceId(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> parseOptionalPositivePresenceId(entry.getGameInstanceId(), "gameInstanceId");
    };
  }

  private Long visibleCharacterId(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> parseOptionalPositivePresenceId(entry.getCharacterId(), "characterId");
    };
  }

  private String visibleWorldSlug(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> entry.getWorldSlug().isBlank() ? null : entry.getWorldSlug();
    };
  }

  private String visiblePlayableStateScope(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default ->
          switch (entry.getPlayableStateScope()) {
            case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
            case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
            default -> null;
          };
    };
  }

  private String visibleWorldDisplayName(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> entry.getWorldDisplayName().isBlank() ? null : entry.getWorldDisplayName();
    };
  }

  private String visibleRealmSlug(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> entry.getRealmSlug().isBlank() ? null : entry.getRealmSlug();
    };
  }

  private String visibleRealmDisplayName(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> entry.getRealmDisplayName().isBlank() ? null : entry.getRealmDisplayName();
    };
  }

  private Long visiblePointerVersion(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> entry.getPointerVersion() > 0 ? entry.getPointerVersion() : null;
    };
  }

  private String visibleCharacterName(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> entry.getCharacterName().isBlank() ? null : entry.getCharacterName();
    };
  }

  private FriendPresenceActivityState visibleActivityState(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case PRIVATE, HIDDEN_STAFF -> null;
      default -> mapActivityState(entry.getActivityState());
    };
  }

  private FriendRecentPresenceDisposition visibleRecentDisposition(
      AccountPresenceEntry entry, FriendPresenceVisibilityPolicyValue visibilityPolicy) {
    return switch (visibilityPolicy) {
      case HIDDEN_STAFF -> null;
      default -> mapRecentDisposition(entry.getRecentDisposition());
    };
  }

  private FriendPresenceVisibilityPolicyValue visibilityPolicyFor(
      long accountId, Map<Long, FriendPresenceVisibilityPolicyValue> visibilityPolicies) {
    return visibilityPolicies.getOrDefault(accountId, FriendPresenceVisibilityPolicyValue.PRIVATE);
  }

  private Long parseOptionalPositivePresenceId(String value, String fieldName) {
    try {
      return RequestIdValidation.parseOptionalPositiveLong(value, fieldName);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private long requirePositivePresenceId(String value, String fieldName) {
    try {
      return RequestIdValidation.requirePositiveLong(value, fieldName);
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Malformed account presence " + fieldName + ": " + ex.getMessage(), ex);
    }
  }

  private FriendRecentPresenceDisposition mapRecentDisposition(
      net.firedevops.firemud.gamesession.v1.AccountRecentPresenceDisposition disposition) {
    return switch (disposition) {
      case ACCOUNT_RECENT_PRESENCE_DISPOSITION_TRANSPORT_LOSS ->
          FriendRecentPresenceDisposition.TRANSPORT_LOSS;
      case ACCOUNT_RECENT_PRESENCE_DISPOSITION_LOGOUT -> FriendRecentPresenceDisposition.LOGOUT;
      case ACCOUNT_RECENT_PRESENCE_DISPOSITION_TAKEOVER -> FriendRecentPresenceDisposition.TAKEOVER;
      default -> null;
    };
  }
}
