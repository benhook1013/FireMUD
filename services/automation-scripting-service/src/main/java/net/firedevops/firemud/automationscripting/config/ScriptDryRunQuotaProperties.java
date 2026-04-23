package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.test")
public class ScriptDryRunQuotaProperties {
  @Min(1)
  private long maxRunsPerMinute = 60;

  @Min(1)
  private long maxRunsPerMinutePerPrincipal = 30;

  @Min(1)
  private long maxConcurrency = 10;

  @Min(1)
  private long maxClusterConcurrency = 50;

  public long getMaxRunsPerMinute() {
    return maxRunsPerMinute;
  }

  public void setMaxRunsPerMinute(long maxRunsPerMinute) {
    this.maxRunsPerMinute = maxRunsPerMinute;
  }

  public long getMaxRunsPerMinutePerPrincipal() {
    return maxRunsPerMinutePerPrincipal;
  }

  public void setMaxRunsPerMinutePerPrincipal(long maxRunsPerMinutePerPrincipal) {
    this.maxRunsPerMinutePerPrincipal = maxRunsPerMinutePerPrincipal;
  }

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
