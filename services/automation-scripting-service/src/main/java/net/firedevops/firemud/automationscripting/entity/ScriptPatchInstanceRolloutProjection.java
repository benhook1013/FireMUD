package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptPatchInstanceRolloutProjection {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String scriptPatchVersion;
  private long scriptPinEpoch;
  private String rolloutStatus;
  private String statusReason;
  private Instant lastChangedAt = Instant.EPOCH;
  private Instant projectionRefreshedAt = Instant.EPOCH;
  private int rowVersion;
}
