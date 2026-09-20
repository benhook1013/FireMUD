package net.firedevops.firemud.tcpproxy.health;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

  private final GatewayWebSocketClient gatewayWebSocketClient;
  private final PollState pollState;
  private final ScheduledExecutorService pollExecutor;
  private final long pollIntervalNanos;
  private final Object lifecycleMonitor = new Object();
  private ScheduledFuture<?> pollingTask;
  private boolean started;
  private boolean closed;

  @Autowired
  public GatewayGameplayReadinessProbe(GatewayWebSocketClient gatewayWebSocketClient) {
    this(gatewayWebSocketClient, POLL_INTERVAL, REQUEST_TIMEOUT);
  }

  GatewayGameplayReadinessProbe(
      GatewayWebSocketClient gatewayWebSocketClient, Duration pollInterval) {
    this(gatewayWebSocketClient, pollInterval, REQUEST_TIMEOUT);
  }

  GatewayGameplayReadinessProbe(
      GatewayWebSocketClient gatewayWebSocketClient,
      Duration pollInterval,
      Duration requestTimeout) {
    this.gatewayWebSocketClient =
        Objects.requireNonNull(gatewayWebSocketClient, "gatewayWebSocketClient");
    if (pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException("pollInterval must be positive");
    }
    pollIntervalNanos = pollInterval.toNanos();
    if (requestTimeout.isZero() || requestTimeout.isNegative()) {
      throw new IllegalArgumentException("requestTimeout must be positive");
    }
    long requestTimeoutNanos = requestTimeout.toNanos();
    pollState = new PollState(this.gatewayWebSocketClient, requestTimeoutNanos);
    pollExecutor =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("gateway-readiness-poll", 0).factory());
  }

  /** Starts readiness polling after the probe has been fully constructed. */
  @PostConstruct
  void start() {
    synchronized (lifecycleMonitor) {
      if (started || closed) {
        return;
      }
      pollingTask =
          pollExecutor.scheduleWithFixedDelay(
              () -> {
                try {
                  pollState.refresh();
                } catch (Throwable error) {
                  logRefreshFailure(
                      pollState.markUnreadyAfterRefreshFailure(),
                      "Gateway readiness poll failed; reporting unready",
                      error);
                }
              },
              0L,
              pollIntervalNanos,
              TimeUnit.NANOSECONDS);
      started = true;
    }
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
    ScheduledFuture<?> task;
    synchronized (lifecycleMonitor) {
      if (closed) {
        return;
      }
      closed = true;
      task = pollingTask;
      pollingTask = null;
    }
    pollState.close();
    if (task != null) {
      task.cancel(true);
    }
    pollExecutor.shutdownNow();
  }

  private static void logRefreshFailure(
      boolean transitionedToUnready, String message, Throwable error) {
    if (transitionedToUnready) {
      logger.warn(message, error);
    } else {
      logger.debug(message, error);
    }
  }

  private static final class PollState {
    private final GatewayWebSocketClient gatewayWebSocketClient;
    private final long requestTimeoutNanos;
    private final AtomicBoolean ready = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<CompletableFuture<Boolean>> inFlight = new AtomicReference<>();

    private PollState(GatewayWebSocketClient gatewayWebSocketClient, long requestTimeoutNanos) {
      this.gatewayWebSocketClient = gatewayWebSocketClient;
      this.requestTimeoutNanos = requestTimeoutNanos;
    }

    private boolean isReady() {
      return ready.get();
    }

    private synchronized boolean markUnreadyAfterRefreshFailure() {
      boolean transitionedToUnready = ready.getAndSet(false);
      CompletableFuture<Boolean> request = inFlight.getAndSet(null);
      if (request != null) {
        request.cancel(true);
      }
      return transitionedToUnready;
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
      } catch (Throwable e) {
        logRefreshFailure(
            ready.getAndSet(false), "Gateway readiness poll failed to start; reporting unready", e);
        return;
      }
      inFlight.set(request);
      // Bound the probe independently of the client's transport timeout so a misbehaving future
      // cannot suppress every later readiness poll indefinitely.
      request
          // Apply timeout to a dependent future; the timeout callback below cancels the original.
          .whenComplete((ignoredResult, ignoredError) -> {})
          .orTimeout(requestTimeoutNanos, TimeUnit.NANOSECONDS)
          .whenComplete(
              (result, error) -> {
                synchronized (this) {
                  if (error instanceof TimeoutException) {
                    request.cancel(true);
                  }
                  if (inFlight.compareAndSet(request, null) && !closed.get()) {
                    ready.set(error == null && Boolean.TRUE.equals(result));
                  }
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
