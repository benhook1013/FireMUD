package net.firedevops.firemud.socialgroups.service.impl;

import io.micrometer.core.annotation.Timed;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.LoggingUtil;
import net.firedevops.firemud.gamesession.v1.AccountPresenceActivityState;
import net.firedevops.firemud.gamesession.v1.AccountPresenceEntry;
import net.firedevops.firemud.gamesession.v1.QueryAccountPresenceResponse;
import net.firedevops.firemud.socialgroups.client.GameSessionClient;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceActivityState;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;
import net.firedevops.firemud.socialgroups.dto.FriendRecentPresenceDisposition;
import net.firedevops.firemud.socialgroups.entity.AccountFriendLink;
import net.firedevops.firemud.socialgroups.entity.FriendLink;
import net.firedevops.firemud.socialgroups.mapper.FriendLinkMapper;
import net.firedevops.firemud.socialgroups.repository.AccountFriendLinkRepository;
import net.firedevops.firemud.socialgroups.repository.FriendLinkRepository;
import net.firedevops.firemud.socialgroups.service.FriendService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FriendServiceImpl implements FriendService {
  private static final Logger logger = LoggingUtil.getLogger(FriendServiceImpl.class);

  private final FriendLinkRepository friendLinkRepository;
  private final AccountFriendLinkRepository accountFriendLinkRepository;
  private final FriendLinkMapper friendLinkMapper;
  private final GameSessionClient gameSessionClient;

  @Override
  @Transactional
  @Timed(value = "friend.add")
  public FriendLinkDto addFriend(AddFriendRequest request) {
    logger.info(
        "Adding friend {} -> {} (accountLevel={})",
        request.accountId(),
        request.friendAccountId(),
        request.accountLevel());
    if (request.accountLevel()) {
      AccountFriendLink afl = new AccountFriendLink();
      afl.setTenantId(request.tenantId());
      afl.setAccountId(request.accountId());
      afl.setFriendAccountId(request.friendAccountId());
      afl.setStatus("active");
      afl.setCreatedAt(Instant.now());
      accountFriendLinkRepository.save(afl);
      FriendLink dto = new FriendLink();
      dto.setId(afl.getId());
      dto.setTenantId(afl.getTenantId());
      dto.setAccountId(afl.getAccountId());
      dto.setFriendAccountId(afl.getFriendAccountId());
      dto.setStatus(afl.getStatus());
      dto.setCreatedAt(afl.getCreatedAt());
      return friendLinkMapper.toDto(dto);
    } else {
      FriendLink link = new FriendLink();
      link.setTenantId(request.tenantId());
      link.setAccountId(request.accountId());
      link.setFriendAccountId(request.friendAccountId());
      link.setStatus("active");
      link.setCreatedAt(Instant.now());
      return friendLinkMapper.toDto(friendLinkRepository.save(link));
    }
  }

  @Override
  @Timed(value = "friend.presence.list")
  public List<FriendPresenceDto> listFriendPresence(long tenantId, long accountId) {
    List<AccountFriendLink> links =
        accountFriendLinkRepository.findByTenantIdAndAccountIdAndStatus(
            tenantId, accountId, "active");
    if (links.isEmpty()) {
      return List.of();
    }

    List<Long> friendAccountIds =
        links.stream().map(AccountFriendLink::getFriendAccountId).distinct().toList();
    QueryAccountPresenceResponse response =
        gameSessionClient.queryAccountPresence(tenantId, accountId, friendAccountIds);
    if (response.hasError()) {
      throw new IllegalStateException(response.getError().getMessage());
    }

    Map<Long, FriendPresenceDto> byAccountId = new LinkedHashMap<>();
    for (AccountPresenceEntry entry : response.getPresencesList()) {
      long friendAccountId = Long.parseLong(entry.getAccountId());
      byAccountId.put(
          friendAccountId,
          new FriendPresenceDto(
              friendAccountId,
              visibleOnline(entry),
              visibleGameInstanceId(entry),
              visiblePlayableStateScope(entry),
              visibleWorldSlug(entry),
              visibleWorldDisplayName(entry),
              visibleRealmSlug(entry),
              visibleRealmDisplayName(entry),
              visiblePointerVersion(entry),
              visibleCharacterId(entry),
              visibleCharacterName(entry),
              visibleActivityState(entry),
              entry.getLastSeenAtMs() > 0 ? Instant.ofEpochMilli(entry.getLastSeenAtMs()) : null,
              visibleRecentDisposition(entry)));
    }

    return friendAccountIds.stream()
        .map(
            friendAccountId ->
                byAccountId.getOrDefault(
                    friendAccountId,
                    new FriendPresenceDto(
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
                        null,
                        null,
                        null)))
        .toList();
  }

  private FriendPresenceActivityState mapActivityState(AccountPresenceActivityState activityState) {
    return switch (activityState) {
      case ACCOUNT_PRESENCE_ACTIVITY_STATE_ACTIVE -> FriendPresenceActivityState.ACTIVE;
      case ACCOUNT_PRESENCE_ACTIVITY_STATE_AUTO_AFK -> FriendPresenceActivityState.AUTO_AFK;
      case ACCOUNT_PRESENCE_ACTIVITY_STATE_EXPLICIT_AFK -> FriendPresenceActivityState.EXPLICIT_AFK;
      default -> null;
    };
  }

  private boolean visibleOnline(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF -> false;
      default -> entry.getOnline();
    };
  }

  private Long visibleGameInstanceId(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default ->
          entry.getGameInstanceId().isBlank() ? null : Long.valueOf(entry.getGameInstanceId());
    };
  }

  private Long visibleCharacterId(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> entry.getCharacterId().isBlank() ? null : Long.valueOf(entry.getCharacterId());
    };
  }

  private String visibleWorldSlug(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> entry.getWorldSlug().isBlank() ? null : entry.getWorldSlug();
    };
  }

  private String visiblePlayableStateScope(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default ->
          switch (entry.getPlayableStateScope()) {
            case PLAYABLE_STATE_SCOPE_SHARED -> "SHARED";
            case PLAYABLE_STATE_SCOPE_ISOLATED -> "ISOLATED";
            default -> null;
          };
    };
  }

  private String visibleWorldDisplayName(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> entry.getWorldDisplayName().isBlank() ? null : entry.getWorldDisplayName();
    };
  }

  private String visibleRealmSlug(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> entry.getRealmSlug().isBlank() ? null : entry.getRealmSlug();
    };
  }

  private String visibleRealmDisplayName(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> entry.getRealmDisplayName().isBlank() ? null : entry.getRealmDisplayName();
    };
  }

  private Long visiblePointerVersion(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> entry.getPointerVersion() > 0 ? entry.getPointerVersion() : null;
    };
  }

  private String visibleCharacterName(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> entry.getCharacterName().isBlank() ? null : entry.getCharacterName();
    };
  }

  private FriendPresenceActivityState visibleActivityState(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_PRIVATE,
          ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF ->
          null;
      default -> mapActivityState(entry.getActivityState());
    };
  }

  private FriendRecentPresenceDisposition visibleRecentDisposition(AccountPresenceEntry entry) {
    return switch (entry.getVisibilityPolicy()) {
      case ACCOUNT_PRESENCE_VISIBILITY_POLICY_HIDDEN_STAFF -> null;
      default -> mapRecentDisposition(entry.getRecentDisposition());
    };
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
