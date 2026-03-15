package net.firedevops.firemud.socialgroups.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "guild_alliances")
public class GuildAlliance {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long guildId;

  @Column(nullable = false)
  private Long allyGuildId;

  @Column(nullable = false)
  private Instant createdAt;
}
