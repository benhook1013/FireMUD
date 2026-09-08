package net.firedevops.firemud.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class HttpTestSupportTest {
  @Test
  void rootReadinessStatusSatisfiesReadiness() throws Exception {
    try (TestHttpServer server = TestHttpServer.responding("{\"status\":\"UP\"}")) {
      HttpTestSupport.awaitReadiness(server.url(), Duration.ofSeconds(1));
    }
  }

  @Test
  void nestedComponentStatusDoesNotSatisfyReadiness() throws Exception {
    try (TestHttpServer server =
        TestHttpServer.responding(
            "{\"status\":\"DOWN\",\"components\":{\"readinessState\":{\"status\":\"UP\"}}}")) {
      Throwable failure =
          catchThrowable(
              () -> HttpTestSupport.awaitReadiness(server.url(), Duration.ofMillis(250)));

      assertThat(failure)
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Timed out waiting for HTTP readiness");
    }
  }

  @Test
  void malformedAndNonObjectBodiesDoNotSatisfyReadiness() throws Exception {
    for (String body : List.of("not-json", "[]", "\"UP\"")) {
      try (TestHttpServer server = TestHttpServer.responding(body)) {
        Throwable failure =
            catchThrowable(
                () -> HttpTestSupport.awaitReadiness(server.url(), Duration.ofMillis(250)));

        assertThat(failure)
            .as("readiness body: %s", body)
            .isInstanceOf(AssertionError.class)
            .hasMessageContaining("Timed out waiting for HTTP readiness");
      }
    }
  }

  @Test
  @Timeout(5)
  void readinessRequestIsBoundByRemainingDeadline() throws Exception {
    CountDownLatch requestStarted = new CountDownLatch(1);
    CountDownLatch releaseRequest = new CountDownLatch(1);
    CountDownLatch requestFinished = new CountDownLatch(1);
    try (TestHttpServer server =
        TestHttpServer.hanging(requestStarted, releaseRequest, requestFinished)) {
      Duration timeout = Duration.ofMillis(300);
      long startedAt = System.nanoTime();
      Throwable failure;
      try {
        failure =
            catchThrowable(
                () -> HttpTestSupport.awaitReadiness(server.url(), timeout));
      } finally {
        releaseRequest.countDown();
      }
      long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

      assertThat(failure)
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("Timed out waiting for HTTP readiness");
      assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(requestFinished.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(elapsedMillis).isLessThan(timeout.toMillis() + 1_000);
    }
  }

  private static final class TestHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor;

    private TestHttpServer(HttpHandler handler) throws IOException {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/actuator/health/readiness", handler);
      executor =
          Executors.newSingleThreadExecutor(
              runnable -> {
                Thread thread = new Thread(runnable, "http-test-support-test-server");
                thread.setDaemon(true);
                return thread;
              });
      server.setExecutor(executor);
      server.start();
    }

    private static TestHttpServer responding(String body) throws IOException {
      return new TestHttpServer(
          exchange -> {
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
              output.write(response);
            }
          });
    }

    private static TestHttpServer hanging(
        CountDownLatch requestStarted,
        CountDownLatch releaseRequest,
        CountDownLatch requestFinished)
        throws IOException {
      return new TestHttpServer(
          exchange -> {
            requestStarted.countDown();
            try {
              releaseRequest.await();
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            } finally {
              exchange.close();
              requestFinished.countDown();
            }
          });
    }

    private String url() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health/readiness";
    }

    @Override
    public void close() {
      server.stop(0);
      executor.shutdownNow();
    }
  }
}
