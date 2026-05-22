package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class GuildAlliance {
  private Long id;
  private Long tenantId;
  private Long guildId;
  private Long allyGuildId;
  private Instant createdAt;
}
