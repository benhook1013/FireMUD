package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class PluginRuntimeEvent {
  private Long id;
  private String eventId;
  private String tenantId;
  private String gameInstanceId;
  private String runtimeRegionId;

  private Long runtimeRegionEpoch;
  private String pluginId;
  private String previousPluginVersionId;
  private String activePluginVersionId;
  private String pluginState;
  private String statusReason;
  private String controlPlaneRequestId;
  private String actorPrincipal;
  private Instant observedAt = Instant.EPOCH;
  private int rowVersion;
}
