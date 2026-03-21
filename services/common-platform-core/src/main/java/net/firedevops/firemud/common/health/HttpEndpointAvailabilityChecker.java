package net.firedevops.firemud.common.health;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Bounded HTTP checker used by readiness indicators for dependent actuator endpoints. */
public class HttpEndpointAvailabilityChecker {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

  private final HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

  public boolean isHealthy(URI uri) {
    if (uri == null) {
      return false;
    }
    HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT).build();
    try {
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      return response.statusCode() >= 200 && response.statusCode() < 300;
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return false;
    }
  }

  public URI readinessEndpointForWebSocketUrl(String websocketUrl) {
    if (websocketUrl == null || websocketUrl.isBlank()) {
      return null;
    }
    try {
      URI websocketUri = URI.create(websocketUrl);
      String scheme =
          switch (websocketUri.getScheme()) {
            case "wss" -> "https";
            case "ws" -> "http";
            default -> null;
          };
      if (scheme == null) {
        return null;
      }
      return new URI(
          scheme,
          websocketUri.getUserInfo(),
          websocketUri.getHost(),
          websocketUri.getPort(),
          "/actuator/health/readiness",
          null,
          null);
    } catch (IllegalArgumentException | URISyntaxException ex) {
      return null;
    }
  }
}
