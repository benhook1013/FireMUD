package net.firedevops.firemud.gamedesign.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class GameSettingsOverride {
  private Long id;
  private String tenantId;

  private Long gameInstanceId;
  private String domain;
  private String payload;
  private Instant updatedAt;
}
