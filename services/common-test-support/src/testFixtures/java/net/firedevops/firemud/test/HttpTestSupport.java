package net.firedevops.firemud.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Shared HTTP helpers for integration tests that should not depend on TestRestTemplate beans. */
public final class HttpTestSupport {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

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

  public static String getBody(String url, Charset charset, Map<String, String> headers)
      throws IOException, InterruptedException {
    HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(url));
    headers.forEach(requestBuilder::header);
    HttpRequest request = requestBuilder.GET().build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(charset)).body();
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
