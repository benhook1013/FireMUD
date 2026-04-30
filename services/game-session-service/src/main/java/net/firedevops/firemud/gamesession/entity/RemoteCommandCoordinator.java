package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "remote_command_coordinator",
    indexes = {
      @Index(
          name = "idx_remote_command_coordinator_coordinator_id",
          columnList = "coordinator_id",
          unique = true),
      @Index(
          name = "idx_remote_command_coordinator_origin_region_state",
          columnList = "tenant_id, origin_region_id, state"),
      @Index(
          name = "idx_remote_command_coordinator_command_id",
          columnList = "tenant_id, command_id",
          unique = true)
    })
public class RemoteCommandCoordinator {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "coordinator_id", nullable = false, length = 64, unique = true)
  private String coordinatorId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "command_id", nullable = false, length = 64)
  private String commandId;

  @Column(name = "followup_id", nullable = false, length = 64)
  private String followupId;

  @Column(name = "origin_game_instance_id", nullable = false)
  private Long originGameInstanceId;

  @Column(name = "origin_region_id", nullable = false, length = 64)
  private String originRegionId;

  @Column(name = "origin_region_epoch", nullable = false)
  private long originRegionEpoch;

  @Column(name = "target_game_instance_id", nullable = false)
  private Long targetGameInstanceId;

  @Column(name = "target_region_id", nullable = false, length = 64)
  private String targetRegionId;

  @Column(name = "target_region_epoch", nullable = false)
  private long targetRegionEpoch;

  @Column(name = "target_due_tick_id", nullable = false)
  private long targetDueTickId;

  @Column(name = "origin_deadline_region_epoch", nullable = false)
  private long originDeadlineRegionEpoch;

  @Column(name = "origin_deadline_tick_id", nullable = false)
  private long originDeadlineTickId;

  @Column(name = "state", nullable = false, length = 40)
  private String state;

  @Column(name = "late_result_policy", nullable = false, length = 64)
  private String lateResultPolicy;

  @Column(name = "execution_outcome", length = 40)
  private String executionOutcome;

  @Column(name = "gameplay_result", length = 40)
  private String gameplayResult;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
