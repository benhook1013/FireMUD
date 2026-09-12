package net.firedevops.firemud.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Shared HTTP helpers for integration tests that should not depend on TestRestTemplate beans. */
public final class HttpTestSupport {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
  private static final ObjectMapper JSON_MAPPER =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  private HttpTestSupport() {}

  public static String getBody(String url) throws IOException, InterruptedException {
    return getBody(url, StandardCharsets.UTF_8);
  }

  public static String getBodyUnchecked(String url) {
    return getBodyUnchecked(url, Map.of());
  }

  public static String getBodyUnchecked(String url, Map<String, String> headers) {
    try {
      return getBody(url, headers);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("HTTP test probe failed for " + url, e);
    }
  }

  public static String getBody(String url, Charset charset)
      throws IOException, InterruptedException {
    return getBody(url, charset, Map.of());
  }

  public static String getBody(String url, Map<String, String> headers)
      throws IOException, InterruptedException {
    return getBody(url, StandardCharsets.UTF_8, headers);
  }

  /** Waits until a Spring Boot readiness endpoint reports UP or the timeout expires. */
  public static void awaitReadiness(String url, Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      long remainingNanos = deadline - System.nanoTime();
      if (remainingNanos <= 0) {
        break;
      }
      try {
        if (isReady(getBody(url, Duration.ofNanos(Math.max(1, remainingNanos))))) {
          return;
        }
      } catch (IOException ignored) {
        // The server may not be listening yet.
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw ex;
      }
      long remainingAfterProbeNanos = deadline - System.nanoTime();
      if (remainingAfterProbeNanos <= 0) {
        break;
      }
      try {
        Thread.sleep(
            Duration.ofNanos(
                Math.min(
                    remainingAfterProbeNanos,
                    TestAsyncAssertions.DEFAULT_POLL_INTERVAL.toNanos())));
      } catch (InterruptedException ex) {
        Thread.currentThread().interrupt();
        throw ex;
      }
    }
    throw new AssertionError("Timed out waiting for HTTP readiness at " + url);
  }

  private static boolean isReady(String body) {
    try {
      JsonNode root = JSON_MAPPER.readTree(body);
      if (root == null || !root.isObject()) {
        return false;
      }
      JsonNode status = root.get("status");
      return status != null && status.isTextual() && "UP".equals(status.textValue());
    } catch (JacksonException ignored) {
      return false;
    }
  }

  public static String getBody(String url, Charset charset, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url));
    headers.forEach(requestBuilder::header);
    HttpRequest request = requestBuilder.GET().build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(charset)).body();
  }

  private static String getBody(String url, Duration requestTimeout)
      throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(url)).timeout(requestTimeout).GET().build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        .body();
  }

  public static String postJsonBody(String url, String requestBody)
      throws IOException, InterruptedException {
    return postJsonBody(url, requestBody, Map.of());
  }

  public static String postJsonBody(String url, String requestBody, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
    headers.forEach(requestBuilder::header);
    HttpRequest request =
        requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString()).body();
  }

  public static String postJsonBodyUnchecked(String url, String requestBody) {
    return postJsonBodyUnchecked(url, requestBody, Map.of());
  }

  public static String postJsonBodyUnchecked(
      String url, String requestBody, Map<String, String> headers) {
    try {
      return postJsonBody(url, requestBody, headers);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("HTTP JSON test probe failed for " + url, e);
    }
  }
}
