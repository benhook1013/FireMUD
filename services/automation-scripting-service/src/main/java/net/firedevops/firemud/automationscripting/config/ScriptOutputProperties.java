package net.firedevops.firemud.automationscripting.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "script.output")
public class ScriptOutputProperties {
  @Min(1)
  private int maxCommandsPerRun = 64;

  @Min(1)
  private int maxCommandsPerEntityPerTrigger = 8;

  @Min(1)
  private int maxSerializedWorkItemBytes = 32768;

  public int getMaxCommandsPerRun() {
    return maxCommandsPerRun;
  }

  public void setMaxCommandsPerRun(int maxCommandsPerRun) {
    this.maxCommandsPerRun = maxCommandsPerRun;
  }

  public int getMaxCommandsPerEntityPerTrigger() {
    return maxCommandsPerEntityPerTrigger;
  }

  public void setMaxCommandsPerEntityPerTrigger(int maxCommandsPerEntityPerTrigger) {
    this.maxCommandsPerEntityPerTrigger = maxCommandsPerEntityPerTrigger;
  }

  public int getMaxSerializedWorkItemBytes() {
    return maxSerializedWorkItemBytes;
  }

  public void setMaxSerializedWorkItemBytes(int maxSerializedWorkItemBytes) {
    this.maxSerializedWorkItemBytes = maxSerializedWorkItemBytes;
  }
}
