package net.firedevops.firemud.entitymanagement.repository;

import net.firedevops.firemud.entitymanagement.entity.CharacterFriend;
import net.firedevops.firemud.entitymanagement.entity.CharacterFriendKey;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterFriendRepository
    extends JpaRepository<CharacterFriend, CharacterFriendKey> {
  Page<CharacterFriend> findByIdCharacterId(Long characterId, Pageable pageable);
}
