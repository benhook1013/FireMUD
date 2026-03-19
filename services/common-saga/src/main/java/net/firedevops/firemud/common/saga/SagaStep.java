package net.firedevops.firemud.common.saga;

/** Single step in a saga workflow. */
public class SagaStep {
  private final String name;
  private final SagaAction action;
  private final SagaAction compensation;

  public SagaStep(String name, SagaAction action, SagaAction compensation) {
    this.name = name;
    this.action = action;
    this.compensation = compensation;
  }

  public String getName() {
    return name;
  }

  public void execute() throws Exception {
    action.run();
  }

  public void compensate() throws Exception {
    if (compensation != null) {
      compensation.run();
    }
  }
}
