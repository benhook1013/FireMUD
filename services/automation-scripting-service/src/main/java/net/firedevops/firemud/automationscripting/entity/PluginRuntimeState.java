package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "plugin_runtime_states",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_plugin_runtime_state_scope",
          columnNames = {"tenant_id", "game_instance_id", "plugin_id"})
    })
public class PluginRuntimeState {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 64)
  private String gameInstanceId;

  @Column(nullable = false, length = 128)
  private String pluginId;

  @Column(length = 128)
  private String activePluginVersionId;

  @Column(length = 128)
  private String pendingPluginVersionId;

  @Column(nullable = false, length = 64)
  private String pluginState;

  @Column(nullable = false, length = 256)
  private String statusReason;

  @Column(length = 128)
  private String controlPlaneRequestId;

  @Column(length = 256)
  private String actorPrincipal;

  @Column(nullable = false)
  private Instant lastChangedAt = Instant.now();

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
