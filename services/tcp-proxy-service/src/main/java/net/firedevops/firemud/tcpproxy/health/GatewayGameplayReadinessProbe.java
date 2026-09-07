package net.firedevops.firemud.tcpproxy.health;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.firedevops.firemud.tcpproxy.telnet.GatewayWebSocketClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Checks whether the downstream gateway gameplay admission path is currently ready. */
@Component
public final class GatewayGameplayReadinessProbe implements AutoCloseable {
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final GatewayWebSocketClient gatewayWebSocketClient;
  private final ScheduledExecutorService pollExecutor;
  private final AtomicBoolean ready = new AtomicBoolean();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final AtomicReference<CompletableFuture<Boolean>> inFlight = new AtomicReference<>();
  private final ScheduledFuture<?> pollingTask;

  @Autowired
  public GatewayGameplayReadinessProbe(GatewayWebSocketClient gatewayWebSocketClient) {
    this(gatewayWebSocketClient, POLL_INTERVAL);
  }

  GatewayGameplayReadinessProbe(
      GatewayWebSocketClient gatewayWebSocketClient, Duration pollInterval) {
    this.gatewayWebSocketClient =
        Objects.requireNonNull(gatewayWebSocketClient, "gatewayWebSocketClient");
    if (pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException("pollInterval must be positive");
    }
    pollExecutor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("gateway-readiness-poll", 0).factory());
    long pollIntervalNanos = pollInterval.toNanos();
    pollingTask =
        pollExecutor.scheduleWithFixedDelay(
            this::refresh, pollIntervalNanos, pollIntervalNanos, TimeUnit.NANOSECONDS);
  }

  public boolean isReady() {
    return ready.get();
  }

  public URI readinessUri() {
    return gatewayWebSocketClient.readinessUri();
  }

  private synchronized void refresh() {
    if (closed.get() || inFlight.get() != null) {
      return;
    }
    CompletableFuture<Boolean> request;
    try {
      request =
          Objects.requireNonNull(gatewayWebSocketClient.isReadyAsync(), "Gateway readiness future");
    } catch (RuntimeException e) {
      ready.set(false);
      return;
    }
    inFlight.set(request);
    request.whenComplete(
        (result, error) -> {
          synchronized (this) {
            if (!closed.get()) {
              ready.set(error == null && Boolean.TRUE.equals(result));
            }
            inFlight.compareAndSet(request, null);
          }
        });
  }

  @Override
  @PreDestroy
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    ready.set(false);
    pollingTask.cancel(true);
    CompletableFuture<Boolean> request = inFlight.getAndSet(null);
    if (request != null) {
      request.cancel(true);
    }
    pollExecutor.shutdownNow();
  }
}
