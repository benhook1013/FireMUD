package net.firedevops.firemud.common.temporal;

import org.springframework.util.StringUtils;

public final class FiremudWorkflowIds {
  private FiremudWorkflowIds() {}

  public static String workflowId(
      String workflowFamily, String tenantId, String scopeKey, String businessKey) {
    return segment(workflowFamily, "workflowFamily")
        + ":"
        + segment(tenantId, "tenantId")
        + ":"
        + segment(scopeKey, "scopeKey")
        + ":"
        + segment(businessKey, "businessKey");
  }

  public static String businessStepKey(String workflowId, String stepName, String businessKey) {
    return segment(workflowId, "workflowId")
        + "#"
        + segment(stepName, "stepName")
        + "#"
        + segment(businessKey, "businessKey");
  }

  private static String segment(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value.trim();
  }
}
