package net.firedevops.firemud.springcloudgateway.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator that verifies the gameplay WebSocket route can be upgraded locally. */
@Component("gameplayRouteReadiness")
public class GameplayRouteReadinessHealthIndicator implements HealthIndicator {
  private static final String COMPONENT = "spring-cloud-gateway";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final String CONTRACT = "Gateway /ws/game upgrade";

  private final int serverPort;
  private final ReadinessTransitionTracker readinessTransitionTracker;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  public GameplayRouteReadinessHealthIndicator(
      @Value("${local.server.port:${server.port:8080}}") int serverPort,
      ReadinessTransitionTracker readinessTransitionTracker) {
    this.serverPort = serverPort;
    this.readinessTransitionTracker = readinessTransitionTracker;
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    URI uri = URI.create("ws://127.0.0.1:" + serverPort + "/ws/game");
    try {
      WebSocket socket =
          client
              .newWebSocketBuilder()
              .header("X-Game-Instance-Id", "1")
              .header("X-Tenant-Id", "1")
              .buildAsync(uri, new NoopListener())
              .get(2, TimeUnit.SECONDS);
      socket.abort();
      return DependencyReadinessSupport.recordUp(
          readinessTransitionTracker,
          COMPONENT,
          CONTRACT,
          Map.of(
              "gameplayRoute",
              DependencyReadinessSupport.upDependency(
                  "websocketUpgrade", uri.toString(), "UPGRADED")));
    } catch (Exception ex) {
      return DependencyReadinessSupport.recordOutOfService(
          readinessTransitionTracker,
          COMPONENT,
          CONTRACT,
          "gameplayRoute",
          Map.of(
              "gameplayRoute",
              DependencyReadinessSupport.downDependency(
                  "websocketUpgrade", uri.toString(), DependencyReadinessSupport.message(ex))));
    }
  }

  private static final class NoopListener implements WebSocket.Listener {}
}
