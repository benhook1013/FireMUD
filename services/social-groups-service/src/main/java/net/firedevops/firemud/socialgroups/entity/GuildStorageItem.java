package net.firedevops.firemud.socialgroups.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "guild_storage_items")
public class GuildStorageItem {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private Long tenantId;

  @Column(nullable = false)
  private Long guildId;

  @Column(nullable = false, length = 100)
  private String itemName;

  @Column(nullable = false)
  private int quantity;
}
