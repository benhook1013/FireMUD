package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.runtime")
public class ScriptRuntimeProperties {
  @Min(1)
  private long pinProjectionStaleThresholdMs = 5000;

  public long getPinProjectionStaleThresholdMs() {
    return pinProjectionStaleThresholdMs;
  }

  public void setPinProjectionStaleThresholdMs(long pinProjectionStaleThresholdMs) {
    this.pinProjectionStaleThresholdMs = pinProjectionStaleThresholdMs;
  }
}
