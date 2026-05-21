package net.firedevops.firemud.gamesession.entity;

import java.time.Instant;
import lombok.Data;

@Data
public class TickEffect {
  private Long id;
  private String effectId;
  private String tickBatchId;
  private String commandId;
  private String effectKey;
  private String effectType;
  private String targetAggregate;
  private String status;
  private Instant stagedAt;
  private Instant completedAt;
  private String failureCode;
  private String failureMessage;
}
