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
@Table(name = "script_work_items")
public class ScriptWorkItem {
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

  @Column(nullable = false, length = 128)
  private String scriptId;

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

  @Column(length = 512)
  private String readSnapshotToken;

  @Column(columnDefinition = "TEXT")
  private String payloadJson;

  @Column(nullable = false, length = 64)
  private String status = "PENDING_EVALUATION";

  @Column(nullable = false)
  private Instant createdAt = Instant.now();

  @Version
  @Column(name = "row_version")
  private int rowVersion;
}
