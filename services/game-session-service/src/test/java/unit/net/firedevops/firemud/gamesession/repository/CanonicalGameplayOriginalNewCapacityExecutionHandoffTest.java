package net.firedevops.firemud.gamesession.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class CanonicalGameplayOriginalNewCapacityExecutionHandoffTest {
  @Test
  void ownedReadbackIsNotExposedBeforePhysicalPermitRelease() throws Exception {
    var cleanup = new CompletableFuture<Void>();
    var releaseWaitReached = new CountDownLatch(1);
    var callerThread = new AtomicReference<Thread>();
    var callerClockReads = new AtomicInteger();
    Clock clock =
        new Clock() {
          private final Clock delegate = Clock.systemUTC();

          @Override
          public java.time.ZoneId getZone() {
            return delegate.getZone();
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return delegate.withZone(zone);
          }

          @Override
          public java.time.Instant instant() {
            return delegate.instant();
          }

          @Override
          public long millis() {
            if (Thread.currentThread() == callerThread.get()
                && callerClockReads.incrementAndGet() == 4) {
              // runOwned reads the deadline when constructing the budget, before its result get,
              // after claiming that result, and immediately before waiting for physical release.
              releaseWaitReached.countDown();
            }
            return delegate.millis();
          }
        };
    var result = new Object();
    try (var caller = Executors.newVirtualThreadPerTaskExecutor()) {
      var handoff =
          caller.submit(() -> {
            callerThread.set(Thread.currentThread());
            return CanonicalGameplayOriginalNewCapacityExecutionBudget.runOwned(
                clock,
                Long.MAX_VALUE,
                budget -> {
                  budget.retainUntilAsyncClose(cleanup);
                  return result;
                },
                ignored -> {});
          });
      try {
        assertThat(releaseWaitReached.await(2, TimeUnit.SECONDS))
            .as("caller claimed the result and reached the physical-release wait")
            .isTrue();
        assertThrows(TimeoutException.class, () -> handoff.get(150, TimeUnit.MILLISECONDS));
      } finally {
        cleanup.complete(null);
      }
      assertThat(handoff.get(2, TimeUnit.SECONDS)).isSameAs(result);
    }
  }
}
