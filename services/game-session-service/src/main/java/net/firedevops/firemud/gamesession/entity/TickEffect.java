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
    name = "tick_effect",
    indexes = {
      @Index(name = "idx_tick_effect_effect_id", columnList = "effect_id", unique = true),
      @Index(name = "idx_tick_effect_tick_batch_id", columnList = "tick_batch_id"),
      @Index(name = "idx_tick_effect_command_id", columnList = "command_id"),
      @Index(name = "idx_tick_effect_effect_key", columnList = "effect_key"),
      @Index(
          name = "idx_tick_effect_batch_effect_key",
          columnList = "tick_batch_id, effect_key",
          unique = true)
    })
public class TickEffect {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "effect_id", nullable = false, length = 64, unique = true)
  private String effectId;

  @Column(name = "tick_batch_id", nullable = false, length = 64)
  private String tickBatchId;

  @Column(name = "command_id", length = 64)
  private String commandId;

  @Column(name = "effect_key", nullable = false, length = 160)
  private String effectKey;

  @Column(name = "effect_type", nullable = false, length = 80)
  private String effectType;

  @Column(name = "target_aggregate", nullable = false, length = 120)
  private String targetAggregate;

  @Column(name = "status", nullable = false, length = 40)
  private String status;

  @Column(name = "staged_at", nullable = false)
  private Instant stagedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  @Column(name = "failure_message", length = 500)
  private String failureMessage;
}
