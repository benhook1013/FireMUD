package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.outbox")
public class ScriptOutboxProperties {
  @Min(1)
  private int handedOffRetentionDays = 7;

  @Min(1)
  private int canceledRetentionDays = 7;

  @Min(1)
  private long deadLetterMaxAgeSeconds = 604800;

  @Min(1)
  private int deadLetterMaxRows = 100000;

  @Min(1)
  private long terminalCleanupIntervalSeconds = 300;

  @Min(1)
  private long queueRebuildIntervalSeconds = 60;

  @Min(1)
  private int queueRebuildBatchSize = 200;

  @Min(1)
  private long executionIntervalSeconds = 5;

  @Min(1)
  private int executionBatchSize = 50;

  public int getHandedOffRetentionDays() {
    return handedOffRetentionDays;
  }

  public void setHandedOffRetentionDays(int handedOffRetentionDays) {
    this.handedOffRetentionDays = handedOffRetentionDays;
  }

  public int getCanceledRetentionDays() {
    return canceledRetentionDays;
  }

  public void setCanceledRetentionDays(int canceledRetentionDays) {
    this.canceledRetentionDays = canceledRetentionDays;
  }

  public long getDeadLetterMaxAgeSeconds() {
    return deadLetterMaxAgeSeconds;
  }

  public void setDeadLetterMaxAgeSeconds(long deadLetterMaxAgeSeconds) {
    this.deadLetterMaxAgeSeconds = deadLetterMaxAgeSeconds;
  }

  public int getDeadLetterMaxRows() {
    return deadLetterMaxRows;
  }

  public void setDeadLetterMaxRows(int deadLetterMaxRows) {
    this.deadLetterMaxRows = deadLetterMaxRows;
  }

  public long getTerminalCleanupIntervalSeconds() {
    return terminalCleanupIntervalSeconds;
  }

  public void setTerminalCleanupIntervalSeconds(long terminalCleanupIntervalSeconds) {
    this.terminalCleanupIntervalSeconds = terminalCleanupIntervalSeconds;
  }

  public long getQueueRebuildIntervalSeconds() {
    return queueRebuildIntervalSeconds;
  }

  public void setQueueRebuildIntervalSeconds(long queueRebuildIntervalSeconds) {
    this.queueRebuildIntervalSeconds = queueRebuildIntervalSeconds;
  }

  public int getQueueRebuildBatchSize() {
    return queueRebuildBatchSize;
  }

  public void setQueueRebuildBatchSize(int queueRebuildBatchSize) {
    this.queueRebuildBatchSize = queueRebuildBatchSize;
  }

  public long getExecutionIntervalSeconds() {
    return executionIntervalSeconds;
  }

  public void setExecutionIntervalSeconds(long executionIntervalSeconds) {
    this.executionIntervalSeconds = executionIntervalSeconds;
  }

  public int getExecutionBatchSize() {
    return executionBatchSize;
  }

  public void setExecutionBatchSize(int executionBatchSize) {
    this.executionBatchSize = executionBatchSize;
  }
}
