package net.firedevops.firemud.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Shared HTTP helpers for integration tests that should not depend on TestRestTemplate beans. */
public final class HttpTestSupport {
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

  private HttpTestSupport() {}

  public static String getBody(String url) throws IOException, InterruptedException {
    return getBody(url, StandardCharsets.UTF_8);
  }

  public static String getBody(String url, Charset charset)
      throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
    return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(charset)).body();
  }
}
