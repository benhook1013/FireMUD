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
@Table(name = "script_event_ingress_audit")
public class ScriptEventIngressAudit {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 64)
  private String tenantId;

  @Column(length = 64)
  private String gameInstanceId;

  @Column(length = 64)
  private String regionId;

  private Long regionEpoch;

  @Column(length = 64)
  private String entityId;

  @Column(nullable = false, length = 32)
  private String playableStateScope = "";

  @Column(nullable = false, length = 64)
  private String worldSlug = "";

  @Column(nullable = false, length = 64)
  private String realmSlug = "";

  @Column(nullable = false, length = 64)
  private String pointerVersion = "";

  @Column(length = 128)
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

  @Column(nullable = false, length = 128)
  private String sourceService;

  @Column(nullable = false, length = 64)
  private String triggerMode;

  @Column(nullable = false)
  private boolean dryRun;

  @Column(length = 512)
  private String readSnapshotToken;

  @Column(columnDefinition = "TEXT")
  private String payloadJson;

  @Column(nullable = false)
  private boolean admitted;

  @Column(nullable = false, length = 128)
  private String admissionOutcome;

  @Column(nullable = false, length = 256)
  private String admissionReason;

  @Column(nullable = false)
  private int resolvedHandlerCount;

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
