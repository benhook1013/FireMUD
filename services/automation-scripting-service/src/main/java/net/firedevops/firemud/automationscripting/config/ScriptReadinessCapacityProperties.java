package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.readiness")
public class ScriptReadinessCapacityProperties {
  @Min(1)
  private long maxConcurrency = 4;

  @Min(1)
  private long maxClusterConcurrency = 20;

  public long getMaxConcurrency() {
    return maxConcurrency;
  }

  public void setMaxConcurrency(long maxConcurrency) {
    this.maxConcurrency = maxConcurrency;
  }

  public long getMaxClusterConcurrency() {
    return maxClusterConcurrency;
  }

  public void setMaxClusterConcurrency(long maxClusterConcurrency) {
    this.maxClusterConcurrency = maxClusterConcurrency;
  }
}
