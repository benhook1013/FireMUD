package net.firedevops.firemud.entitymanagement.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class CharacterFriend {
  private CharacterFriendKey id;
  private Character character;
  private Character friend;
  private Long tenantId;
  private Instant createdAt = Instant.now();

  public CharacterFriendKey getId() {
    if (id == null) {
      return null;
    }
    CharacterFriendKey copy = new CharacterFriendKey();
    copy.setCharacterId(id.getCharacterId());
    copy.setFriendId(id.getFriendId());
    return copy;
  }

  public void setId(CharacterFriendKey id) {
    if (id == null) {
      this.id = null;
    } else {
      CharacterFriendKey copy = new CharacterFriendKey();
      copy.setCharacterId(id.getCharacterId());
      copy.setFriendId(id.getFriendId());
      this.id = copy;
    }
  }

  public Character getCharacter() {
    if (character == null) {
      return null;
    }
    Character copy = new Character();
    copy.setId(character.getId());
    return copy;
  }

  public void setCharacter(Character character) {
    if (character == null) {
      this.character = null;
    } else {
      Character copy = new Character();
      copy.setId(character.getId());
      this.character = copy;
    }
  }

  public Character getFriend() {
    if (friend == null) {
      return null;
    }
    Character copy = new Character();
    copy.setId(friend.getId());
    return copy;
  }

  public void setFriend(Character friend) {
    if (friend == null) {
      this.friend = null;
    } else {
      Character copy = new Character();
      copy.setId(friend.getId());
      this.friend = copy;
    }
  }
}
