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

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.firedevops.firemud.tcpproxy.telnet.GatewayWebSocketClient;
import org.junit.jupiter.api.Test;

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
        .thenThrow(new IllegalStateException("synchronous failure"))
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
            throw new IllegalStateException("callback registration failure");
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
}
