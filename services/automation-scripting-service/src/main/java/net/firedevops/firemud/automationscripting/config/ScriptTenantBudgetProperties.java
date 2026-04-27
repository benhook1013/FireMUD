package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.tenant-budget")
public class ScriptTenantBudgetProperties {
  @Min(1)
  private long highRunsPerMinute = 120;

  @Min(1)
  private long normalRunsPerMinute = 60;

  @Min(1)
  private long backgroundRunsPerMinute = 30;

  public long getHighRunsPerMinute() {
    return highRunsPerMinute;
  }

  public void setHighRunsPerMinute(long highRunsPerMinute) {
    this.highRunsPerMinute = highRunsPerMinute;
  }

  public long getNormalRunsPerMinute() {
    return normalRunsPerMinute;
  }

  public void setNormalRunsPerMinute(long normalRunsPerMinute) {
    this.normalRunsPerMinute = normalRunsPerMinute;
  }

  public long getBackgroundRunsPerMinute() {
    return backgroundRunsPerMinute;
  }

  public void setBackgroundRunsPerMinute(long backgroundRunsPerMinute) {
    this.backgroundRunsPerMinute = backgroundRunsPerMinute;
  }
}
