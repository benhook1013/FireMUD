package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "tick_batch",
    indexes = {
      @Index(name = "idx_tick_batch_tick_batch_id", columnList = "tick_batch_id", unique = true),
      @Index(
          name = "idx_tick_batch_tenant_instance_status",
          columnList = "tenant_id, game_instance_id, status")
    })
public class TickBatch {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tick_batch_id", nullable = false, length = 64, unique = true)
  private String tickBatchId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "region_epoch", nullable = false)
  private long regionEpoch;

  @Column(name = "executor_fence", nullable = false, length = 64)
  private String executorFence;

  @Column(name = "batch_source", nullable = false, length = 40)
  private String batchSource;

  @Column(name = "status", nullable = false, length = 40)
  private String status;

  @Column(name = "requires_solo_tick", nullable = false)
  private boolean requiresSoloTick;

  @Column(name = "command_count", nullable = false)
  private int commandCount;

  @Column(name = "expected_effect_count", nullable = false)
  private int expectedEffectCount;

  @Column(name = "selected_work_manifest_digest", length = 64)
  private String selectedWorkManifestDigest;

  @Column(name = "selected_work_manifest_json", columnDefinition = "TEXT")
  private String selectedWorkManifestJson;

  @Column(name = "staged_at", nullable = false)
  private Instant stagedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  @Column(name = "failure_message", length = 500)
  private String failureMessage;
}
