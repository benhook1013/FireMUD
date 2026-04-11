package net.firedevops.firemud.socialgroups.service;

import java.util.List;
import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;
import net.firedevops.firemud.socialgroups.dto.FriendPresenceDto;

public interface FriendService {
  FriendLinkDto addFriend(AddFriendRequest request);

  List<FriendPresenceDto> listFriendPresence(long tenantId, long accountId);
}
