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
    name = "runtime_region_status",
    indexes = {
      @Index(
          name = "idx_runtime_region_status_tenant_instance",
          columnList = "tenant_id, game_instance_id",
          unique = true)
    })
public class RuntimeRegionStatus {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "region_epoch", nullable = false)
  private long regionEpoch;

  @Column(name = "executor_fence", nullable = false, length = 64)
  private String executorFence;

  @Column(name = "owner_service", nullable = false, length = 80)
  private String ownerService;

  @Column(name = "owner_instance_id", nullable = false, length = 120)
  private String ownerInstanceId;

  @Column(name = "paused", nullable = false)
  private boolean paused;

  @Column(name = "last_committed_tick_batch_id", length = 64)
  private String lastCommittedTickBatchId;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
