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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Checks whether the downstream gateway gameplay admission path is currently ready. */
@Component
public final class GatewayGameplayReadinessProbe implements AutoCloseable {
  private static final Logger logger = LoggerFactory.getLogger(GatewayGameplayReadinessProbe.class);
  private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);

  private final GatewayWebSocketClient gatewayWebSocketClient;
  private final PollState pollState;
  private final ScheduledExecutorService pollExecutor;
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
    long pollIntervalNanos = pollInterval.toNanos();
    pollState = new PollState(this.gatewayWebSocketClient);
    pollExecutor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("gateway-readiness-poll", 0).factory());
    pollingTask =
        pollExecutor.scheduleWithFixedDelay(
            () -> {
              try {
                pollState.refresh();
              } catch (RuntimeException error) {
                pollState.markUnreadyAfterRefreshFailure();
                logger.debug("Gateway readiness poll failed; reporting unready", error);
              }
            },
            0L,
            pollIntervalNanos,
            TimeUnit.NANOSECONDS);
  }

  public boolean isReady() {
    return pollState.isReady();
  }

  public URI readinessUri() {
    return gatewayWebSocketClient.readinessUri();
  }

  @Override
  @PreDestroy
  public void close() {
    pollState.close();
    pollingTask.cancel(true);
    pollExecutor.shutdownNow();
  }

  private static final class PollState {
    private final GatewayWebSocketClient gatewayWebSocketClient;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Boolean>> inFlight = new AtomicReference<>();

    private PollState(GatewayWebSocketClient gatewayWebSocketClient) {
      this.gatewayWebSocketClient = gatewayWebSocketClient;
    }

    private boolean isReady() {
      return ready.get();
    }

    private synchronized void markUnreadyAfterRefreshFailure() {
      ready.set(false);
      inFlight.set(null);
    }

    private synchronized void refresh() {
      if (closed.get() || inFlight.get() != null) {
        return;
      }
      CompletableFuture<Boolean> request;
      try {
        request =
            Objects.requireNonNull(
                gatewayWebSocketClient.isReadyAsync(), "Gateway readiness future");
      } catch (RuntimeException e) {
        ready.set(false);
        logger.debug("Gateway readiness poll failed to start; reporting unready", e);
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

    private synchronized void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      ready.set(false);
      CompletableFuture<Boolean> request = inFlight.getAndSet(null);
      if (request != null) {
        request.cancel(true);
      }
    }
  }
}
