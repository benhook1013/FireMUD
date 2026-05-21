package net.firedevops.firemud.socialgroups.entity;

import lombok.Data;

@Data
public class GuildStorageItem {
  private Long id;
  private Long tenantId;
  private Long guildId;
  private String itemName;
  private int quantity;
}
