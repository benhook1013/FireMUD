package net.firedevops.firemud.gamedesign.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded polling settings for the opt-in authored-world source delivery worker. */
@ConfigurationProperties(prefix = "firemud.authored-world-source.delivery")
public class GameAuthoredWorldSourceDeliveryProperties {
  private int batchSize = 25;
  private long pollIntervalMs = 5_000L;

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public long getPollIntervalMs() {
    return pollIntervalMs;
  }

  public void setPollIntervalMs(long pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
  }

  public void validate() {
    if (batchSize < 1 || batchSize > 100) {
      throw new IllegalArgumentException("Authored-world delivery batch size must be 1..100");
    }
    if (pollIntervalMs < 1_000L) {
      throw new IllegalArgumentException(
          "Authored-world delivery poll interval must be at least 1000 milliseconds");
    }
  }
}
