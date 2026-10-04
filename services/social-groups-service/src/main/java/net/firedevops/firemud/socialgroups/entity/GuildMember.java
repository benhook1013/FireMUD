package net.firedevops.firemud.socialgroups.entity;

import java.util.UUID;
import lombok.Data;

@Data
public class GuildMember {
  private Long id;
  private Long tenantId;
  private Long guildId;
  private UUID accountId;
  private String role;
}
