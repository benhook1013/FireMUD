package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.runtime")
public class ScriptRuntimeProperties {
  @Min(1)
  private long pinProjectionStaleThresholdMs = 5000;

  @Min(1)
  private long drainStatusStaleThresholdMs = 5000;

  @Min(1)
  private long scheduleRuntimeProgressStaleThresholdMs = 5000;

  @Min(1)
  private long pluginPolicyReconcileIntervalSeconds = 60;

  @Min(1)
  private int pluginPolicyReconcileBatchSize = 100;

  @Min(1)
  private long pluginPolicyStaleThresholdSeconds = 300;

  public long getPinProjectionStaleThresholdMs() {
    return pinProjectionStaleThresholdMs;
  }

  public void setPinProjectionStaleThresholdMs(long pinProjectionStaleThresholdMs) {
    this.pinProjectionStaleThresholdMs = pinProjectionStaleThresholdMs;
  }

  public long getDrainStatusStaleThresholdMs() {
    return drainStatusStaleThresholdMs;
  }

  public void setDrainStatusStaleThresholdMs(long drainStatusStaleThresholdMs) {
    this.drainStatusStaleThresholdMs = drainStatusStaleThresholdMs;
  }

  public long getScheduleRuntimeProgressStaleThresholdMs() {
    return scheduleRuntimeProgressStaleThresholdMs;
  }

  public void setScheduleRuntimeProgressStaleThresholdMs(
      long scheduleRuntimeProgressStaleThresholdMs) {
    this.scheduleRuntimeProgressStaleThresholdMs = scheduleRuntimeProgressStaleThresholdMs;
  }

  public long getPluginPolicyReconcileIntervalSeconds() {
    return pluginPolicyReconcileIntervalSeconds;
  }

  public void setPluginPolicyReconcileIntervalSeconds(long pluginPolicyReconcileIntervalSeconds) {
    this.pluginPolicyReconcileIntervalSeconds = pluginPolicyReconcileIntervalSeconds;
  }

  public int getPluginPolicyReconcileBatchSize() {
    return pluginPolicyReconcileBatchSize;
  }

  public void setPluginPolicyReconcileBatchSize(int pluginPolicyReconcileBatchSize) {
    this.pluginPolicyReconcileBatchSize = pluginPolicyReconcileBatchSize;
  }

  public long getPluginPolicyStaleThresholdSeconds() {
    return pluginPolicyStaleThresholdSeconds;
  }

  public void setPluginPolicyStaleThresholdSeconds(long pluginPolicyStaleThresholdSeconds) {
    this.pluginPolicyStaleThresholdSeconds = pluginPolicyStaleThresholdSeconds;
  }
}
