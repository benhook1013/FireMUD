package net.firedevops.firemud.socialgroups.service;

import java.util.Optional;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresencePolicyViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceViewDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceVisibilityPolicyValue;
import net.firedevops.firemud.socialgroups.dto.FriendRosterEntryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterFilter;
import net.firedevops.firemud.socialgroups.dto.FriendRosterSummaryDto;
import net.firedevops.firemud.socialgroups.dto.FriendRosterViewDto;

public interface FriendService {
  FriendLinkDto addFriend(AddFriendRequest request);

  void removeFriend(long tenantId, String accountId, String friendAccountId);

  Optional<FriendRosterEntryDto> getFriend(long tenantId, String accountId, String friendAccountId);

  Optional<FriendRosterEntryDto> getFriendByOrdinal(long tenantId, String accountId, int ordinal);

  Optional<FriendRosterEntryDto> removeFriendByOrdinal(
      long tenantId, String accountId, int ordinal);

  FriendRosterViewDto listFriends(long tenantId, String accountId, FriendRosterFilter filter);

  FriendRosterSummaryDto getFriendRosterSummary(long tenantId, String accountId);

  default FriendRosterViewDto listFriends(long tenantId, String accountId) {
    return listFriends(tenantId, accountId, FriendRosterFilter.ALL);
  }

  FriendPresenceViewDto listFriendPresence(
      long tenantId, String accountId, FriendRosterFilter filter);

  default FriendPresenceViewDto listFriendPresence(long tenantId, String accountId) {
    return listFriendPresence(tenantId, accountId, FriendRosterFilter.ALL);
  }

  FriendPresencePolicyViewDto getFriendPresencePolicy(long tenantId, String accountId);

  FriendPresencePolicyViewDto updateFriendPresencePolicy(
      long tenantId, String accountId, FriendPresenceVisibilityPolicyValue visibilityPolicy);
}
