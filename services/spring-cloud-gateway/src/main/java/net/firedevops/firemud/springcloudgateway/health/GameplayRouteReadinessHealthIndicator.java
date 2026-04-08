package net.firedevops.firemud.springcloudgateway.health;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.common.health.DependencyReadinessSupport;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.common.security.JwtUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/** Readiness indicator that verifies the gameplay WebSocket route can be upgraded locally. */
@Component("gameplayRouteReadiness")
public class GameplayRouteReadinessHealthIndicator implements HealthIndicator {
  private static final String COMPONENT = "spring-cloud-gateway";
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final String CONTRACT = "Gateway /ws/game upgrade";
  private static final String CONNECT_TOKEN_HEADER = "X-Firemud-Connect-Token";
  private static final String PROXY_CONNECTION_ID_HEADER = "X-Proxy-Connection-Id";
  private static final String GAME_INSTANCE_ID_HEADER = "X-Game-Instance-Id";
  private static final String TENANT_ID_HEADER = "X-Tenant-Id";
  private static final String SYNTHETIC_PROXY_CONNECTION_ID = "gateway-readiness-probe";
  // Reserved numeric ids so the readiness JWT matches the game-session parser contract.
  private static final String PROBE_ACCOUNT_ID = "0";
  private static final String PROBE_TENANT_ID = "0";
  private static final String PROBE_GAME_INSTANCE_ID = "0";

  private final int serverPort;
  private final ReadinessTransitionTracker readinessTransitionTracker;
  private final JwtUtil jwtUtil;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  public GameplayRouteReadinessHealthIndicator(
      @Value("${local.server.port:${server.port:8080}}") int serverPort,
      ReadinessTransitionTracker readinessTransitionTracker,
      ObjectProvider<JwtUtil> jwtUtilProvider) {
    this.serverPort = serverPort;
    this.readinessTransitionTracker = readinessTransitionTracker;
    this.jwtUtil = jwtUtilProvider.getIfAvailable();
  }

  @Override
  public org.springframework.boot.health.contributor.Health health() {
    URI uri = URI.create("ws://127.0.0.1:" + serverPort + "/ws/game");
    try {
      WebSocket socket = buildClient().buildAsync(uri, new NoopListener()).get(2, TimeUnit.SECONDS);
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

  private WebSocket.Builder buildClient() {
    WebSocket.Builder builder = client.newWebSocketBuilder();
    if (jwtUtil != null) {
      return builder.header(
          CONNECT_TOKEN_HEADER,
          jwtUtil.generateToken(
              PROBE_ACCOUNT_ID,
              Map.of(
                  "accountId", PROBE_ACCOUNT_ID,
                  "tenantId", PROBE_TENANT_ID,
                  "gameInstanceId", PROBE_GAME_INSTANCE_ID,
                  "jti", "gateway-readiness-" + UUID.randomUUID())));
    }
    return builder
        .header(GAME_INSTANCE_ID_HEADER, "1")
        .header(TENANT_ID_HEADER, "1")
        .header(PROXY_CONNECTION_ID_HEADER, SYNTHETIC_PROXY_CONNECTION_ID);
  }

  private static final class NoopListener implements WebSocket.Listener {}
}
