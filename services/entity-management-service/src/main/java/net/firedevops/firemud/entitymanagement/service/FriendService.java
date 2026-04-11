package net.firedevops.firemud.entitymanagement.service;

import net.firedevops.firemud.entitymanagement.dto.CharacterFriendDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FriendService {
  Page<CharacterFriendDto> listFriends(Long tenantId, Long characterId, Pageable pageable);

  CharacterFriendDto addFriend(Long tenantId, Long characterId, Long friendId);

  void removeFriend(Long tenantId, Long characterId, Long friendId);
}
