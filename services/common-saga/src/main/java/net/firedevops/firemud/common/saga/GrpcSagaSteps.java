package net.firedevops.firemud.common.saga;

import java.time.Duration;
import java.util.function.Supplier;

/** Helper methods for creating gRPC-based synchronous saga steps. */
public final class GrpcSagaSteps {
  private GrpcSagaSteps() {}

  /**
   * Returns a {@link SagaAction} that invokes the given call with simple retry logic.
   *
   * @param call supplier invoking the gRPC method
   * @param retries number of retries before giving up
   */
  public static SagaAction callWithRetry(Supplier<?> call, int retries) {
    return () -> {
      int attempts = 0;
      while (true) {
        try {
          call.get();
          return;
        } catch (Exception e) {
          if (attempts++ >= retries) {
            throw e;
          }
          Thread.sleep(Duration.ofMillis(200).toMillis());
        }
      }
    };
  }
}
