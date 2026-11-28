package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

final class CanonicalGameplayOriginalNewCapacityExecutionHandoffTest {
  @Test
  void ownedReadbackIsNotExposedBeforePhysicalPermitRelease() throws Exception {
    var cleanup = new CompletableFuture<Void>();
    var captured = new AtomicReference<CanonicalGameplayOriginalNewCapacityExecutionBudget>();
    var callerThread = new AtomicReference<Thread>();
    var result = new Object();
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var handoff =
          caller.submit(() -> {
            callerThread.set(Thread.currentThread());
            return CanonicalGameplayOriginalNewCapacityExecutionBudget.runOwned(
                Clock.systemUTC(),
                Long.MAX_VALUE,
                budget -> {
                  captured.set(budget);
                  budget.retainUntilAsyncClose(cleanup);
                  return result;
                },
                ignored -> {});
          });
      try {
        Awaitility.await()
            .atMost(2, TimeUnit.SECONDS)
            .untilAsserted(() -> {
              var budget = captured.get();
              assertThat(budget).isNotNull();
              var published =
                  (CompletableFuture<?>)
                      java.util.Objects.requireNonNull(
                          org.springframework.test.util.ReflectionTestUtils.getField(
                              budget, "result"));
              assertThat(published).isCompleted();
              var lifecycle =
                  java.util.Objects.requireNonNull(
                      org.springframework.test.util.ReflectionTestUtils.getField(
                          budget, "lifecycle"));
              synchronized (lifecycle) {
                assertThat(
                        org.springframework.test.util.ReflectionTestUtils.getField(
                            budget, "workerDone"))
                    .isEqualTo(true);
              }
              assertThat(handoff).isNotDone();
              assertThat(cleanup).isNotDone();
              Thread thread = callerThread.get();
              assertThat(thread.getState()).isEqualTo(Thread.State.TIMED_WAITING);
              assertThat(
                      java.util.Arrays.stream(thread.getStackTrace())
                          .anyMatch(frame -> frame.getClassName().contains("CompletableFuture")))
                  .as("caller is blocked in the physical-release completion wait")
                  .isTrue();
            });
      } finally {
        cleanup.complete(null);
      }
      assertThat(handoff.get(2, TimeUnit.SECONDS)).isSameAs(result);
    }
  }
}
