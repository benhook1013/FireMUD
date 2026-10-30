package net.firedevops.firemud.socialgroups.entity;

import lombok.Data;

@Data
public class GuildMember {
  private Long id;
  private Long tenantId;
  private Long guildId;
  private String accountId;
  private String role;
}
