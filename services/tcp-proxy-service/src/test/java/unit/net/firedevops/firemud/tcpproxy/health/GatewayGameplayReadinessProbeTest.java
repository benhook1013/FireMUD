package net.firedevops.firemud.tcpproxy.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import net.firedevops.firemud.tcpproxy.telnet.GatewayWebSocketClient;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class GatewayGameplayReadinessProbeTest {

  @Test
  void rejectsOverflowingPollIntervalBeforePolling() {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);

    assertThrows(
        ArithmeticException.class,
        () -> new GatewayGameplayReadinessProbe(client, Duration.ofSeconds(Long.MAX_VALUE)));

    verifyNoInteractions(client);
  }

  @Test
  void rejectsZeroPollIntervalBeforePolling() {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);

    assertThrows(
        IllegalArgumentException.class,
        () -> new GatewayGameplayReadinessProbe(client, Duration.ZERO));

    verifyNoInteractions(client);
  }

  @Test
  void rejectsNegativePollIntervalBeforePolling() {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);

    assertThrows(
        IllegalArgumentException.class,
        () -> new GatewayGameplayReadinessProbe(client, Duration.ofMillis(-1)));

    verifyNoInteractions(client);
  }

  @Test
  void startsFailClosedWhilePollingImmediately() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    when(client.isReadyAsync()).thenReturn(pending);
    try (GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofHours(1))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      assertFalse(probe.isReady());
      pending.complete(true);
      awaitReadiness(probe, true);
    }
  }

  @Test
  void cachesReadinessWithoutWaitingForAnInFlightRequestOrStartingOverlappingPolls()
      throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    URI readinessUri = URI.create("https://gateway.internal/actuator/health/readiness");
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    when(client.isReadyAsync()).thenReturn(pending);
    when(client.readinessUri()).thenReturn(readinessUri);
    try (GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(10))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      assertFalse(probe.isReady());
      verify(client, after(50).times(1)).isReadyAsync();

      pending.complete(true);
      awaitReadiness(probe, true);
      assertEquals(readinessUri, probe.readinessUri());
      verify(client).readinessUri();
    }
  }

  @Test
  void completedPollTransitionsTheCachedValueFailClosed() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> healthy = new CompletableFuture<>();
    CompletableFuture<Boolean> unhealthy = new CompletableFuture<>();
    when(client.isReadyAsync()).thenReturn(healthy, unhealthy);
    try (GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(10))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      healthy.complete(true);
      awaitReadiness(probe, true);
      verify(client, org.mockito.Mockito.timeout(1000).times(2)).isReadyAsync();
      unhealthy.complete(false);
      awaitReadiness(probe, false);
    }
  }

  @Test
  void synchronousPollingFailureClearsHealthyStateAndLaterPollingRetries() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> healthy = new CompletableFuture<>();
    CompletableFuture<Boolean> retry = new CompletableFuture<>();
    when(client.isReadyAsync())
        .thenReturn(healthy)
        .thenThrow(new AssertionError("synchronous failure"))
        .thenReturn(retry);
    try (GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(100))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      healthy.complete(true);
      awaitReadiness(probe, true);

      verify(client, org.mockito.Mockito.timeout(1000).times(2)).isReadyAsync();
      awaitReadiness(probe, false);
      verify(client, org.mockito.Mockito.timeout(1000).times(3)).isReadyAsync();
      assertFalse(probe.isReady());
    }
  }

  @Test
  void synchronousPollingFailureWarnsOnlyForTheHealthyToUnreadyTransition() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> healthy = new CompletableFuture<>();
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    when(client.isReadyAsync())
        .thenReturn(healthy)
        .thenThrow(new IllegalStateException("first synchronous failure"))
        .thenThrow(new IllegalStateException("repeated synchronous failure"))
        .thenReturn(pending);
    try (ReadinessLogCapture logs = new ReadinessLogCapture();
        GatewayGameplayReadinessProbe probe =
            new GatewayGameplayReadinessProbe(client, Duration.ofMillis(25))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      healthy.complete(true);
      awaitReadiness(probe, true);

      verify(client, org.mockito.Mockito.timeout(2000).times(4)).isReadyAsync();
      awaitReadiness(probe, false);

      assertEquals(
          1, logs.count(Level.WARN, "Gateway readiness poll failed to start; reporting unready"));
      assertEquals(
          1, logs.count(Level.DEBUG, "Gateway readiness poll failed to start; reporting unready"));
    }
  }

  @Test
  void nullPollingFutureClearsHealthyStateAndLaterPollingRetries() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> healthy = new CompletableFuture<>();
    CompletableFuture<Boolean> retry = new CompletableFuture<>();
    when(client.isReadyAsync()).thenReturn(healthy).thenReturn(null).thenReturn(retry);
    try (GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(100))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      healthy.complete(true);
      awaitReadiness(probe, true);

      verify(client, org.mockito.Mockito.timeout(1000).times(2)).isReadyAsync();
      awaitReadiness(probe, false);
      verify(client, org.mockito.Mockito.timeout(1000).times(3)).isReadyAsync();
      assertFalse(probe.isReady());
    }
  }

  @Test
  void refreshFailureOutsideReadinessRequestStillAllowsLaterPolling() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> retry = new CompletableFuture<>();
    CompletableFuture<Boolean> throwingFuture =
        new CompletableFuture<>() {
          @Override
          public CompletableFuture<Boolean> whenComplete(
              BiConsumer<? super Boolean, ? super Throwable> action) {
            throw new AssertionError("callback registration failure");
          }
        };
    when(client.isReadyAsync()).thenReturn(throwingFuture, retry);
    try (GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(100))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      assertFalse(probe.isReady());

      verify(client, org.mockito.Mockito.timeout(1000).times(2)).isReadyAsync();
      retry.complete(true);
      awaitReadiness(probe, true);
    }
  }

  @Test
  void callbackRegistrationFailureWarnsOnlyForTheHealthyToUnreadyTransition() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> healthy = new CompletableFuture<>();
    CompletableFuture<Boolean> firstFailure = callbackRegistrationFailure();
    CompletableFuture<Boolean> repeatedFailure = callbackRegistrationFailure();
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    when(client.isReadyAsync()).thenReturn(healthy, firstFailure, repeatedFailure, pending);
    try (ReadinessLogCapture logs = new ReadinessLogCapture();
        GatewayGameplayReadinessProbe probe =
            new GatewayGameplayReadinessProbe(client, Duration.ofMillis(25))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      healthy.complete(true);
      awaitReadiness(probe, true);

      verify(client, org.mockito.Mockito.timeout(2000).times(4)).isReadyAsync();
      awaitReadiness(probe, false);

      assertEquals(1, logs.count(Level.WARN, "Gateway readiness poll failed; reporting unready"));
      assertEquals(1, logs.count(Level.DEBUG, "Gateway readiness poll failed; reporting unready"));
    }
  }

  @Test
  void refreshFailureCancelsDetachedRequestAndLateCallbackCannotRestoreReadiness()
      throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    AtomicInteger cancellationAttempts = new AtomicInteger();
    AtomicReference<BiConsumer<? super Boolean, ? super Throwable>> callback =
        new AtomicReference<>();
    CompletableFuture<Boolean> throwingFuture =
        new CompletableFuture<>() {
          @Override
          public CompletableFuture<Boolean> whenComplete(
              BiConsumer<? super Boolean, ? super Throwable> action) {
            callback.set(action);
            throw new IllegalStateException("callback registration failure");
          }

          @Override
          public boolean cancel(boolean mayInterruptIfRunning) {
            cancellationAttempts.incrementAndGet();
            return false;
          }
        };
    when(client.isReadyAsync()).thenReturn(throwingFuture);
    try (GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofHours(1))) {
      verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();
      assertEquals(1, cancellationAttempts.get());
      assertFalse(probe.isReady());

      callback.get().accept(true, null);

      assertFalse(probe.isReady());
    }
  }

  @Test
  void closeCancelsTheInFlightRequestAndLeavesTheCacheFailClosed() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    when(client.isReadyAsync()).thenReturn(pending);
    GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(10));
    verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();

    probe.close();

    assertTrue(pending.isCancelled());
    assertFalse(probe.isReady());
    verify(client, after(50).times(1)).isReadyAsync();
  }

  @Test
  void completionAfterCloseCannotRestoreReadiness() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> pending =
        new CompletableFuture<>() {
          @Override
          public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
          }
        };
    when(client.isReadyAsync()).thenReturn(pending);
    GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(10));
    verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();

    try {
      probe.close();
      assertTrue(pending.complete(true));
      assertFalse(probe.isReady());
    } finally {
      probe.close();
    }
  }

  private static void awaitReadiness(GatewayGameplayReadinessProbe probe, boolean expected)
      throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (probe.isReady() != expected && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    assertEquals(expected, probe.isReady());
  }

  private static CompletableFuture<Boolean> callbackRegistrationFailure() {
    return new CompletableFuture<>() {
      @Override
      public CompletableFuture<Boolean> whenComplete(
          BiConsumer<? super Boolean, ? super Throwable> action) {
        throw new IllegalStateException("callback registration failure");
      }
    };
  }

  private static final class ReadinessLogCapture implements AutoCloseable {
    private final Logger logger =
        (Logger) LoggerFactory.getLogger(GatewayGameplayReadinessProbe.class);
    private final Level originalLevel = logger.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private ReadinessLogCapture() {
      logger.setLevel(Level.DEBUG);
      appender.start();
      logger.addAppender(appender);
    }

    private long count(Level level, String message) {
      List<ILoggingEvent> events;
      synchronized (appender) {
        events = List.copyOf(appender.list);
      }
      return events.stream()
          .filter(event -> event.getLevel().equals(level))
          .filter(event -> event.getFormattedMessage().equals(message))
          .count();
    }

    @Override
    public void close() {
      logger.detachAppender(appender);
      appender.stop();
      logger.setLevel(originalLevel);
    }
  }
}
