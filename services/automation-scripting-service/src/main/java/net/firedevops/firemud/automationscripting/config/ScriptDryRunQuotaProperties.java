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
}
