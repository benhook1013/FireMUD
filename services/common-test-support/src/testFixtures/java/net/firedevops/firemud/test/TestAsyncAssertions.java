package net.firedevops.firemud.test;

import java.time.Duration;
import java.util.Collection;
import java.util.function.BooleanSupplier;

/** Shared low-level eventual assertions for integration and cross-service tests. */
public final class TestAsyncAssertions {
  public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

  private TestAsyncAssertions() {}

  public static void assertEventually(
      String description, Duration timeout, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(DEFAULT_POLL_INTERVAL.toMillis());
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  public static <T> void assertQueueContains(
      Collection<T> values, T expected, Duration timeout, String description)
      throws InterruptedException {
    assertEventually(description, timeout, () -> values.contains(expected));
  }
}
