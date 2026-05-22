package net.firedevops.firemud.loggingadmin.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class PlayerReport {
  private Long id;
  private Long tenantId;
  private Long reporterAccountId;

  private Long targetAccountId;
  private String type;
  private String description;
  private Instant createdAt;
}
