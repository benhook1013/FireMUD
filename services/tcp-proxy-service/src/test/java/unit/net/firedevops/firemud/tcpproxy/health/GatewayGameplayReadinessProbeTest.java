package net.firedevops.firemud.tcpproxy.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.tcpproxy.telnet.GatewayWebSocketClient;
import org.junit.jupiter.api.Test;

class GatewayGameplayReadinessProbeTest {

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
      Thread.sleep(50);
      verify(client, times(1)).isReadyAsync();

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
    Thread.sleep(50);
    verify(client, times(1)).isReadyAsync();
  }

  @Test
  void completionRacingCloseCannotRestoreReadinessAfterClose() throws Exception {
    GatewayWebSocketClient client = mock(GatewayWebSocketClient.class);
    CompletableFuture<Boolean> pending = new CompletableFuture<>();
    when(client.isReadyAsync()).thenReturn(pending);
    GatewayGameplayReadinessProbe probe =
        new GatewayGameplayReadinessProbe(client, Duration.ofMillis(10));
    verify(client, org.mockito.Mockito.timeout(1000)).isReadyAsync();

    Thread completionThread;
    try {
      synchronized (probe) {
        completionThread = Thread.ofPlatform().start(() -> pending.complete(true));
        awaitBlocked(completionThread);
        probe.close();
        assertFalse(probe.isReady());
      }
      completionThread.join(1000);
      assertFalse(completionThread.isAlive());
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

  private static void awaitBlocked(Thread thread) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (thread.getState() != Thread.State.BLOCKED
        && thread.isAlive()
        && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(Thread.State.BLOCKED, thread.getState());
  }
}
