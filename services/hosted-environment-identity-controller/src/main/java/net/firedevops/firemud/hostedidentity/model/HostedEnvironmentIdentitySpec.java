package net.firedevops.firemud.hostedidentity.model;

public class HostedEnvironmentIdentitySpec {
  private DesiredState desiredState;

  public DesiredState getDesiredState() {
    return desiredState;
  }

  public void setDesiredState(DesiredState desiredState) {
    this.desiredState = desiredState;
  }

  public enum DesiredState {
    Active,
    Retired
  }
}
