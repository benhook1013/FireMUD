package net.firedevops.firemud.common.saga;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Executes a list of {@link SagaStep} instances sequentially for short synchronous orchestration.
 * On failure the already executed steps are compensated in reverse order.
 */
public class Saga {
  private final String name;
  private final List<SagaStep> steps;

  Saga(String name, List<SagaStep> steps) {
    this.name = name;
    this.steps = new ArrayList<>(steps);
  }

  public String getName() {
    return name;
  }

  public List<SagaStep> getSteps() {
    return Collections.unmodifiableList(steps);
  }

  /** Executes the synchronous saga inline. */
  public void run() throws SagaException {
    List<SagaStep> executed = new ArrayList<>();
    try {
      for (SagaStep step : steps) {
        step.execute();
        executed.add(step);
      }
    } catch (Exception e) {
      Collections.reverse(executed);
      // compensate failing step first if it wasn't successful
      for (SagaStep step : steps) {
        if (!executed.contains(step)) {
          try {
            step.compensate();
          } catch (Exception ex) {
            // ignore
          }
          break;
        }
      }
      for (SagaStep step : executed) {
        try {
          step.compensate();
        } catch (Exception ex) {
          // Log and continue, compensation must not stop rollback
        }
      }
      throw new SagaException("Saga failed at step: " + executed.size(), e);
    }
  }
}
