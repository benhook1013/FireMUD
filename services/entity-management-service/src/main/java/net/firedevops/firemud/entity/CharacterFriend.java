package net.firedevops.firemud.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "character_friend")
public class CharacterFriend {
  @EmbeddedId private CharacterFriendKey id;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("characterId")
  private Character character;

  @ManyToOne(fetch = FetchType.LAZY)
  @MapsId("friendId")
  private Character friend;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();
}
