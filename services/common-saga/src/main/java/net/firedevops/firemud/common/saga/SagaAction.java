package net.firedevops.firemud.common.saga;

/** Represents an action within a saga step. */
@FunctionalInterface
public interface SagaAction {
  void run() throws Exception;
}
