package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FriendService {
  Page<CharacterFriendDto> listFriends(Long characterId, Pageable pageable);

  CharacterFriendDto addFriend(Long characterId, Long friendId);

  void removeFriend(Long characterId, Long friendId);
}
