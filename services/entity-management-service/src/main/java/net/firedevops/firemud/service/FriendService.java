package net.firedevops.firemud.service;

import java.util.List;
import net.firedevops.firemud.dto.CharacterFriendDto;

public interface FriendService {
  List<CharacterFriendDto> listFriends(Long characterId);

  CharacterFriendDto addFriend(Long characterId, Long friendId);

  void removeFriend(Long characterId, Long friendId);
}
