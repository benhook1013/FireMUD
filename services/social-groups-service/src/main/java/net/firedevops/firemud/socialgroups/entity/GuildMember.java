package net.firedevops.firemud.socialgroups.entity;

import lombok.Data;

@Data
public class GuildMember {
  private Long id;
  private Long tenantId;
  private Long guildId;
  private Long accountId;
  private String role;
}
