package net.firedevops.firemud.gamedesign.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;
import net.firedevops.firemud.gamedesign.model.VersionLifecycleState;

@Data
@Entity
@Table(name = "plugin_version_status_events")
public class PluginVersionStatusEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 80, unique = true)
  private String eventId;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 128)
  private String pluginId;

  @Column(nullable = false, length = 128)
  private String pluginVersionId;

  @Column(nullable = false, length = 64)
  private VersionLifecycleState previousPublicationState;

  @Column(nullable = false, length = 64)
  private VersionLifecycleState newPublicationState;

  @Column(nullable = false, length = 256)
  private String statusReason;

  @Column(nullable = false)
  private Instant observedAt = Instant.EPOCH;

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
