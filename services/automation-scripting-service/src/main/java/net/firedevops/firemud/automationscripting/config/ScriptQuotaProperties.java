package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.quota")
public class ScriptQuotaProperties {
  @Min(1)
  private long limit = 50;

  @Min(1)
  private long windowSeconds = 60;

  public long getLimit() {
    return limit;
  }

  public void setLimit(long limit) {
    this.limit = limit;
  }

  public long getWindowSeconds() {
    return windowSeconds;
  }

  public void setWindowSeconds(long windowSeconds) {
    this.windowSeconds = windowSeconds;
  }
}
