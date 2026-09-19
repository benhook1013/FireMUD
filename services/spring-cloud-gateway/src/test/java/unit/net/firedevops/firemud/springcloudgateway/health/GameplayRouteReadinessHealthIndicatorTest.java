package net.firedevops.firemud.springcloudgateway.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayRouteReadinessHealthIndicatorTest {

  @Test
  void healthReturnsOutOfServiceWhenLocalGameplayRouteCannotUpgrade() {
    GameplayRouteReadinessHealthIndicator indicator =
        new GameplayRouteReadinessHealthIndicator(
            1, tracker(), provider(new JwtUtil(SECRET, 30_000L)));

    Health health = indicator.health();
    Object rawDependencies = Objects.requireNonNull(health.getDetails().get("dependencies"));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) rawDependencies;

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    Map<String, Object> gameplayRoute = Objects.requireNonNull(dependencies.get("gameplayRoute"));
    assertEquals("DOWN", gameplayRoute.get("status"));
  }

  @Test
  void readinessProbeUsesProtectedConnectTokenCookie() {
    HttpClient client = mock(HttpClient.class);
    WebSocket.Builder builder = mock(WebSocket.Builder.class);
    when(client.newWebSocketBuilder()).thenReturn(builder);
    when(builder.header(anyString(), anyString())).thenReturn(builder);
    when(builder.buildAsync(any(URI.class), any(WebSocket.Listener.class)))
        .thenReturn(CompletableFuture.failedFuture(new IOException("probe unavailable")));

    GameplayRouteReadinessHealthIndicator indicator =
        new GameplayRouteReadinessHealthIndicator(
            1, tracker(), provider(new JwtUtil(SECRET, 30_000L)), client);

    indicator.health();

    verify(builder)
        .header(eq("Cookie"), org.mockito.ArgumentMatchers.startsWith("Firemud-Connect-Token="));
    verify(builder, never()).header(eq("X-Firemud-Connect-Token"), anyString());
  }

  private static ReadinessTransitionTracker tracker() {
    return new ReadinessTransitionTracker(new SimpleMeterRegistry());
  }

  private static final String SECRET = "testsecretkeytestsecretkeytest1234";

  private static ObjectProvider<JwtUtil> provider(JwtUtil jwtUtil) {
    return new ObjectProvider<>() {
      @Override
      public JwtUtil getObject(Object... args) {
        return jwtUtil;
      }

      @Override
      public JwtUtil getIfAvailable() {
        return jwtUtil;
      }

      @Override
      public JwtUtil getIfUnique() {
        return jwtUtil;
      }

      @Override
      public JwtUtil getObject() {
        return jwtUtil;
      }
    };
  }
}
