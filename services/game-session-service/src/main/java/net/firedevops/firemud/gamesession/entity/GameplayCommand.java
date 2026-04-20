package net.firedevops.firemud.gamesession.entity;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Data;

@Data
@Entity
@Table(
    name = "gameplay_command",
    indexes = {
      @Index(name = "idx_gameplay_command_command_id", columnList = "command_id", unique = true),
      @Index(
          name = "idx_gameplay_command_tenant_instance_status",
          columnList = "tenant_id, game_instance_id, execution_outcome")
    })
public class GameplayCommand {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "command_id", nullable = false, length = 64, unique = true)
  private String commandId;

  @Column(name = "tenant_id", nullable = false)
  private Long tenantId;

  @Column(name = "game_instance_id", nullable = false)
  private Long gameInstanceId;

  @Column(name = "session_id", nullable = false)
  private Long sessionId;

  @Column(name = "account_id")
  private Long accountId;

  @Column(name = "character_id")
  private Long characterId;

  @Column(name = "command_name", nullable = false, length = 80)
  private String commandName;

  @Column(name = "command_text", nullable = false, length = 1000)
  private String commandText;

  @Column(name = "sanitized_command_text", nullable = false, length = 1000)
  private String sanitizedCommandText;

  @Column(name = "requires_solo_tick", nullable = false)
  private boolean requiresSoloTick;

  @Column(name = "execution_outcome", nullable = false, length = 40)
  private String executionOutcome;

  @Column(name = "gameplay_result", nullable = false, length = 40)
  private String gameplayResult;

  @Column(name = "accepted_at", nullable = false)
  private Instant acceptedAt;

  @Column(name = "staged_at")
  private Instant stagedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "last_attempt_at")
  private Instant lastAttemptAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "failure_code", length = 80)
  private String failureCode;

  @Column(name = "failure_message", length = 500)
  private String failureMessage;
}
