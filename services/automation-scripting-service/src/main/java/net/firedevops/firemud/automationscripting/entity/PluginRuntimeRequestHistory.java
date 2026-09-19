package net.firedevops.firemud.automationscripting.entity;

import java.time.Instant;
import lombok.Data;

/** Immutable result of one plugin lifecycle control-plane request. */
@Data
public class PluginRuntimeRequestHistory {
  private Long id;
  private String tenantId;
  private String gameInstanceId;
  private String pluginId;
  private String operation;
  private String controlPlaneRequestId;
  private String requestFingerprint;
  private String previousPluginVersionId;
  private String activePluginVersionId;
  private long pluginActivationEpoch;
  private long lifecycleRevision;
  private String pluginState;
  private Instant createdAt;
}
