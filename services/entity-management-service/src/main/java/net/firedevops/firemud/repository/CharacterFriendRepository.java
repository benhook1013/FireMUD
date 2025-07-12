package net.firedevops.firemud.repository;

import java.util.List;
import net.firedevops.firemud.entity.CharacterFriend;
import net.firedevops.firemud.entity.CharacterFriendKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CharacterFriendRepository
    extends JpaRepository<CharacterFriend, CharacterFriendKey> {
  List<CharacterFriend> findByIdCharacterId(Long characterId);
}
