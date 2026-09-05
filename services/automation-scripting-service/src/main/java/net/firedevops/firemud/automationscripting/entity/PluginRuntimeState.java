package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class PluginRuntimeState {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String runtimeRegionId;

  private Long runtimeRegionEpoch;
  private String pluginId;
  private String activePluginVersionId;
  private long pluginActivationEpoch;
  private long lifecycleRevision;
  private String pendingPluginVersionId;
  private String pluginState;
  private String statusReason;
  private String controlPlaneRequestId;
  private String actorPrincipal;
  private Instant lastChangedAt = Instant.now();
  private Instant lastPolicyCheckedAt = Instant.EPOCH;
  private int rowVersion;
}
