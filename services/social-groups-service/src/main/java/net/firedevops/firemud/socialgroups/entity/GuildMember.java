package net.firedevops.firemud.socialgroups.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "guild_members")
public class GuildMember {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long guildId;

  @Column(nullable = false)
  private Long accountId;

  @Column(nullable = false, length = 50)
  private String role;
}
