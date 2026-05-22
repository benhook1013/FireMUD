package net.firedevops.firemud.common.temporal;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import net.firedevops.firemud.common.temporal.config.TemporalProperties;
import org.springframework.util.StringUtils;

@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "TemporalProperties is an injected configuration collaborator.")
public final class TemporalTaskQueueResolver {
  private final String applicationName;
  private final TemporalProperties properties;

  public TemporalTaskQueueResolver(String applicationName, TemporalProperties properties) {
    this.applicationName = normalize(applicationName, "applicationName");
    this.properties = properties;
  }

  public String forWorkflowFamily(String workflowFamily) {
    return normalize(properties.getTaskQueuePrefix(), "taskQueuePrefix")
        + ":"
        + applicationName
        + ":"
        + normalize(workflowFamily, "workflowFamily");
  }

  private static String normalize(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }
}
