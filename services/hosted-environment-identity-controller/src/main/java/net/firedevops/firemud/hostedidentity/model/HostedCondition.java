package net.firedevops.firemud.hostedidentity.model;

public class HostedCondition {
  private String type;
  private String status;
  private String reason;
  private String message;
  private String lastTransitionTime;
  private Long observedGeneration;

  public HostedCondition() {}

  public HostedCondition(String type, String status, String reason, String message) {
    this.type = type;
    this.status = status;
    this.reason = reason;
    this.message = message;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public String getLastTransitionTime() {
    return lastTransitionTime;
  }

  public void setLastTransitionTime(String lastTransitionTime) {
    this.lastTransitionTime = lastTransitionTime;
  }

  public Long getObservedGeneration() {
    return observedGeneration;
  }

  public void setObservedGeneration(Long observedGeneration) {
    this.observedGeneration = observedGeneration;
  }
}
