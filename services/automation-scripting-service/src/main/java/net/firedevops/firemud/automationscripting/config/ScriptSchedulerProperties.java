package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.scheduler")
public class ScriptSchedulerProperties {
  @Min(1)
  private int maxCatchUpFiringsPerObservation = 50;

  public int getMaxCatchUpFiringsPerObservation() {
    return maxCatchUpFiringsPerObservation;
  }

  public void setMaxCatchUpFiringsPerObservation(int maxCatchUpFiringsPerObservation) {
    this.maxCatchUpFiringsPerObservation = maxCatchUpFiringsPerObservation;
  }
}
