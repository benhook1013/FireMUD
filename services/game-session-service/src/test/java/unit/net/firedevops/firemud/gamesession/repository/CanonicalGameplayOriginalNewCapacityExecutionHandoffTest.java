package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

final class CanonicalGameplayOriginalNewCapacityExecutionHandoffTest {
  @Test
  void ownedReadbackIsNotExposedBeforePhysicalPermitRelease() throws Exception {
    var cleanup = new CompletableFuture<Void>();
    var readbackProduced = new CountDownLatch(1);
    var result = new Object();
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var handoff =
          caller.submit(() ->
              CanonicalGameplayOriginalNewCapacityExecutionBudget.runOwned(
                  Clock.systemUTC(),
                  Long.MAX_VALUE,
                  budget -> {
                    budget.retainUntilAsyncClose(cleanup);
                    readbackProduced.countDown();
                    return result;
                  },
                  ignored -> {}));
      try {
        assertThat(readbackProduced.await(2, TimeUnit.SECONDS)).isTrue();
        assertThrows(TimeoutException.class, () -> handoff.get(150, TimeUnit.MILLISECONDS));
      } finally {
        cleanup.complete(null);
      }
      assertThat(handoff.get(2, TimeUnit.SECONDS)).isSameAs(result);
    }
  }
}
