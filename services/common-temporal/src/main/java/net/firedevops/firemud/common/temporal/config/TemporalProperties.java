package net.firedevops.firemud.common.temporal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "firemud.temporal")
public class TemporalProperties {
  private boolean enabled;
  private String namespace = "firemud";
  private String target = "127.0.0.1:7233";
  private boolean workersEnabled = true;
  private String taskQueuePrefix = "firemud";

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public String getTarget() {
    return target;
  }

  public void setTarget(String target) {
    this.target = target;
  }

  public boolean isWorkersEnabled() {
    return workersEnabled;
  }

  public void setWorkersEnabled(boolean workersEnabled) {
    this.workersEnabled = workersEnabled;
  }

  public String getTaskQueuePrefix() {
    return taskQueuePrefix;
  }

  public void setTaskQueuePrefix(String taskQueuePrefix) {
    this.taskQueuePrefix = taskQueuePrefix;
  }
}
