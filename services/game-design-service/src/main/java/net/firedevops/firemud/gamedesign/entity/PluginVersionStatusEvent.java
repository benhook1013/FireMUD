package net.firedevops.firemud.gamedesign.entity;

import java.time.Instant;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

@Data
public class PluginVersionStatusEvent {
  private Long id;
  private String eventId;
  private String tenantId;
  private String pluginId;
  private String pluginVersionId;
  private VersionLifecycleState previousPublicationState;
  private VersionLifecycleState newPublicationState;
  private String statusReason;
  private Instant observedAt = Instant.EPOCH;
  private int rowVersion;
}
