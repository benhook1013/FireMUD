package net.firedevops.firemud.gamesession.testsupport;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.firedevops.firemud.cache.ScreenBufferService;
import net.firedevops.firemud.gamesession.service.SessionContext;

/** Shared eventual assertions for gameplay cross-service proof and transport tests. */
public final class GameplayAsyncAssertions {
  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(50);

  private GameplayAsyncAssertions() {}

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

  public static void assertMetricEventually(
      MeterRegistry registry,
      Duration timeout,
      String meterName,
      double expectedValue,
      String... tags)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Counter counter = registry.find(meterName).tags(tags).counter();
      if (counter != null && counter.count() >= expectedValue) {
        return;
      }
      Thread.sleep(DEFAULT_POLL_INTERVAL.toMillis());
    }
    Counter counter = registry.find(meterName).tags(tags).counter();
    double actual = counter == null ? 0.0 : counter.count();
    throw new AssertionError(
        "Metric " + meterName + " did not reach " + expectedValue + "; actual=" + actual);
  }

  public static void assertBufferedScreenEventuallyContains(
      ScreenBufferService screenBufferService,
      SessionContext context,
      Duration timeout,
      String expectedSubstring)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<ScreenBufferService.BufferedScreen> maybeBuffer =
          screenBufferService.get(
              context.tenantId(), context.gameInstanceId(), context.characterId());
      if (maybeBuffer.isPresent()
          && maybeBuffer.orElseThrow().protocolText().contains(expectedSubstring)) {
        return;
      }
      Thread.sleep(DEFAULT_POLL_INTERVAL.toMillis());
    }
    Optional<ScreenBufferService.BufferedScreen> maybeBuffer =
        screenBufferService.get(
            context.tenantId(), context.gameInstanceId(), context.characterId());
    String actual = maybeBuffer.map(ScreenBufferService.BufferedScreen::protocolText).orElse("");
    throw new AssertionError(
        "Expected buffered screen to contain '" + expectedSubstring + "', got '" + actual + "'");
  }
}
