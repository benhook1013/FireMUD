package net.firedevops.firemud.springcloudgateway.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator that verifies the gameplay WebSocket route can be upgraded locally. */
@Component("gameplayRouteReadiness")
public class GameplayRouteReadinessHealthIndicator implements HealthIndicator {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);

  private final int serverPort;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  public GameplayRouteReadinessHealthIndicator(
      @Value("${local.server.port:${server.port:8080}}") int serverPort) {
    this.serverPort = serverPort;
  }

  @Override
  public Health health() {
    try {
      URI uri = URI.create("ws://127.0.0.1:" + serverPort + "/ws/game");
      WebSocket socket =
          client
              .newWebSocketBuilder()
              .header("X-Game-Instance-Id", "1")
              .header("X-Tenant-Id", "1")
              .buildAsync(uri, new NoopListener())
              .get(2, TimeUnit.SECONDS);
      socket.abort();
      return Health.up().withDetail("gameplayRoute", uri.toString()).build();
    } catch (Exception ex) {
      return Health.outOfService()
          .withDetail("gameplayRoute", "DOWN")
          .withDetail("reason", message(ex))
          .build();
    }
  }

  private static String message(Exception ex) {
    return ex.getMessage() == null || ex.getMessage().isBlank()
        ? ex.getClass().getSimpleName()
        : ex.getMessage();
  }

  private static final class NoopListener implements WebSocket.Listener {}
}
