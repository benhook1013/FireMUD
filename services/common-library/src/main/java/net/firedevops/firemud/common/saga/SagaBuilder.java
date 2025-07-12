package net.firedevops.firemud.common.saga;

import java.util.ArrayList;
import java.util.List;

/** Fluent builder for defining saga workflows. */
public class SagaBuilder {
  private final String name;
  private final List<SagaStep> steps = new ArrayList<>();

  public SagaBuilder() {
    this("default");
  }

  public SagaBuilder(String name) {
    this.name = name;
  }

  public SagaBuilder step(String name, SagaAction action) {
    steps.add(new SagaStep(name, action, null));
    return this;
  }

  public SagaBuilder step(String name, SagaAction action, SagaAction compensation) {
    steps.add(new SagaStep(name, action, compensation));
    return this;
  }

  public Saga build() {
    return new Saga(name, steps);
  }

  /** Convenience method to build and immediately run the saga. */
  public void run() throws SagaException {
    build().run();
  }
}
