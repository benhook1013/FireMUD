package net.firedevops.firemud.common.saga;

/** Represents one action within a short synchronous saga step. */
@FunctionalInterface
public interface SagaAction {
  void run() throws Exception;
}
