package net.firedevops.firemud.socialgroups.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class Guild {
  private Long id;
  private Long tenantId;
  private String name;
  private String ownerAccountId;
  private Instant createdAt;
}
