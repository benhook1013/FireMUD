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
@Table(name = "script_event_audit")
public class ScriptEventAudit {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(nullable = false, length = 64)
  private String gameInstanceId;

  @Column(nullable = false, length = 64)
  private String regionId;

  @Column(nullable = false)
  private Long regionEpoch;

  @Column(nullable = false, length = 64)
  private String entityId;

  @Column(nullable = false, length = 32)
  private String playableStateScope = "";

  @Column(nullable = false, length = 64)
  private String worldSlug = "";

  @Column(nullable = false, length = 64)
  private String realmSlug = "";

  @Column(nullable = false, length = 64)
  private String pointerVersion = "";

  @Column(nullable = false, length = 128)
  private String scriptId;

  @Column(length = 128)
  private String pluginId;

  @Column(length = 128)
  private String pluginVersionId;

  @Column(nullable = false, length = 128)
  private String eventType;

  @Column(nullable = false, length = 32)
  private String eventSchemaVersion;

  @Column(nullable = false, length = 128)
  private String scriptPatchVersion;

  @Column(nullable = false, length = 128)
  private String scriptEventId;

  @Column(nullable = false)
  private boolean dryRun;

  @Column(nullable = false, length = 128)
  private String sourceService;

  @Column(nullable = false, length = 64)
  private String triggerMode;

  @Column(nullable = false, length = 64)
  private String sourceKind = "";

  @Column(nullable = false, length = 64)
  private String sourceState = "";

  @Column private Long sourceOrdinal;

  @Column private Long sourceDueTickId;

  @Column private Long sourceDueAtMs;

  @Column(name = "work_item_id")
  private Long workItemId;

  @Column(nullable = false, length = 64)
  private String finalStage;

  @Column(nullable = false, length = 128)
  private String finalOutcome;

  @Column(nullable = false, length = 256)
  private String finalReason;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Column(nullable = false)
  private Instant updatedAt = Instant.now();

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
