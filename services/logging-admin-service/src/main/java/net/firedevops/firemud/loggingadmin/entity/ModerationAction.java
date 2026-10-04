package net.firedevops.firemud.loggingadmin.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ModerationAction {
  private Long id;
  private Long tenantId;
  private Long accountId;
  private String action;
  private String reason;
  private Instant createdAt;

  private Instant expiresAt;
}
