package net.firedevops.firemud.loggingadmin.entity;

import java.time.Instant;
import java.util.UUID;
import lombok.Data;

@Data
public class PlayerReport {
  private Long id;
  private Long tenantId;
  private UUID reporterAccountId;

  private UUID targetAccountId;
  private String type;
  private String description;
  private Instant createdAt;
}
