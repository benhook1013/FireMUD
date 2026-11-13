package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stripe.exception.ApiConnectionException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StripeFirstInvoiceHttpClientTest {
  @Test
  void cancelsASlowlyStreamingStripeBodyWithoutAnotherRequest() throws Exception {
    var streaming = new CountDownLatch(1);
    var closed = new CountDownLatch(1);
    var attempts = new java.util.concurrent.atomic.AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/invoices/in_exact",
        exchange -> {
          attempts.incrementAndGet();
          try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
              byte[] chunk = new byte[512];
              java.util.Arrays.fill(chunk, (byte) ' ');
              streaming.countDown();
              for (int index = 0; index < 150; index++) {
                body.write(chunk);
                body.flush();
                Thread.sleep(20);
              }
            } catch (IOException canceled) {
              closed.countDown();
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
            }
          }
        });
    server.start();
    try {
      var stripe =
          com.stripe.StripeClient.builder()
              .setApiKey("sk_test_controlled")
              .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
              .setMaxNetworkRetries(0)
              .setHttpClient(
                  new StripeFirstInvoiceHttpClient(Duration.ofSeconds(1), Duration.ofMillis(500)))
              .build();
      assertThatThrownBy(() -> stripe.invoices().retrieve("in_exact"))
          .isInstanceOf(ApiConnectionException.class);
      assertThat(streaming.getCount()).isZero();
      assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(attempts.get()).isOne();
    } finally {
      server.stop(0);
    }
  }
}
