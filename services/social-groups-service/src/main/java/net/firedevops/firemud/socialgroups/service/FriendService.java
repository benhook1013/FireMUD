package net.firedevops.firemud.socialgroups.service;

import net.firedevops.firemud.socialgroups.dto.AddFriendRequest;
import net.firedevops.firemud.socialgroups.dto.FriendLinkDto;

public interface FriendService {
  FriendLinkDto addFriend(AddFriendRequest request);
}
