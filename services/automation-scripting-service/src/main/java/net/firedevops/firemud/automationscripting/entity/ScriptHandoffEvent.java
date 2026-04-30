package net.firedevops.firemud.automationscripting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(name = "script_handoff_events")
public class ScriptHandoffEvent {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 80, unique = true)
  private String eventId;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 64)
  private String gameInstanceId;

  @Column(nullable = false, length = 128)
  private String scriptPatchVersion;

  @Column(nullable = false, length = 128)
  private String scriptId;

  @Column(length = 128)
  private String pluginId;

  @Column(length = 128)
  private String pluginVersionId;

  @Column(nullable = false)
  private Long workItemId;

  @Column(nullable = false)
  private int commandOrdinal;

  @Column(nullable = false, length = 128)
  private String automationDispatchId;

  @Column(length = 128)
  private String gameSessionCommandId;

  @Column(nullable = false, length = 64)
  private String targetEntityId;

  @Column(nullable = false, length = 32)
  private String playableStateScope = "";

  @Column(nullable = false, length = 64)
  private String worldSlug = "";

  @Column(nullable = false, length = 64)
  private String realmSlug = "";

  @Column(nullable = false, length = 64)
  private String pointerVersion = "";

  @Column(nullable = false, length = 64)
  private String sourceKind = "";

  @Column(nullable = false, length = 64)
  private String sourceState = "";

  @Column private Long sourceOrdinal;

  @Column private Long sourceDueTickId;

  @Column private Long sourceDueAtMs;

  @Column(nullable = false, length = 1024)
  private String emittedCommandText;

  @Column(nullable = false, length = 128)
  private String handoffOutcome;

  @Column(nullable = false, length = 256)
  private String handoffReason;

  @Column(nullable = false)
  private Instant observedAt = Instant.EPOCH;

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
