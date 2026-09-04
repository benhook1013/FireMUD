package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class ScriptPatchInstanceRolloutEvent {
  private Long id;
  private String eventId;
  private String tenantId;
  private String gameInstanceId;
  private String scriptPatchVersion;
  private long scriptPinEpoch;
  private String lastObservedControlPlaneRequestId = "";
  private String rolloutStatus;
  private String statusReason;
  private Instant observedAt = Instant.EPOCH;
  private Instant projectionRefreshedAt = Instant.EPOCH;
  private int rowVersion;
}
