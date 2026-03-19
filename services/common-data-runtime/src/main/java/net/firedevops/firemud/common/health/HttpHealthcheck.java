package net.firedevops.firemud.common.health;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Minimal container-safe HTTP health probe for bootBuildImage images that do not include curl. */
public final class HttpHealthcheck {
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

  private HttpHealthcheck() {}

  public static void main(String[] args) {
    if (args.length != 1) {
      System.err.println("Usage: HttpHealthcheck <url>");
      System.exit(2);
    }

    HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(args[0])).GET().timeout(REQUEST_TIMEOUT).build();

    try {
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        System.exit(0);
      }
      System.err.println("Healthcheck HTTP status " + response.statusCode() + " for " + args[0]);
    } catch (IOException | InterruptedException ex) {
      if (ex instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      System.err.println("Healthcheck request failed for " + args[0] + ": " + ex.getMessage());
    }

    System.exit(1);
  }
}
