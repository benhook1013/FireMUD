package net.firedevops.firemud.loggingadmin.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class LogEvent {
  private Long id;
  private Long tenantId;
  private String type;
  private String message;
  private Instant timestamp;

  private Long accountId;
}
