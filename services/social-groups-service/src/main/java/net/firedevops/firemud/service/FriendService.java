package net.firedevops.firemud.service;

import net.firedevops.firemud.dto.AddFriendRequest;
import net.firedevops.firemud.dto.FriendLinkDto;

public interface FriendService {
  FriendLinkDto addFriend(AddFriendRequest request);
}
