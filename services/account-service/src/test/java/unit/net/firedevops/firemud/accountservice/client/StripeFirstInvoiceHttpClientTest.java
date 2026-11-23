package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.stripe.exception.ApiConnectionException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
    try (var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      var client = new StripeFirstInvoiceHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(1));
      var observedTransport = mock(HttpClient.class);
      when(observedTransport.sendAsync(
              any(HttpRequest.class),
              org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
          .thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            HttpResponse.BodyHandler<String> handler = invocation.getArgument(1);
            return transport.sendAsync(
                request, response -> observeFirstBodyChunk(handler.apply(response), streaming));
          });
      ReflectionTestUtils.setField(client, "transport", observedTransport);
      var stripe =
          com.stripe.StripeClient.builder()
              .setApiKey("sk_test_controlled")
              .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
              .setMaxNetworkRetries(0)
              .setHttpClient(client)
              .build();
      assertTimeoutPreemptively(
          Duration.ofMillis(2500),
          () ->
              assertThatThrownBy(() -> stripe.invoices().retrieve("in_exact"))
                  .isInstanceOf(ApiConnectionException.class)
                  .hasMessageContaining("First-invoice provider response deadline expired")
                  .hasCauseInstanceOf(TimeoutException.class));
      assertThat(streaming.getCount()).as("the client received a body chunk").isZero();
      assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(attempts.get()).isOne();
    } finally {
      server.stop(0);
    }
  }

  private static <T> HttpResponse.BodySubscriber<T> observeFirstBodyChunk(
      HttpResponse.BodySubscriber<T> delegate, CountDownLatch observed) {
    return new HttpResponse.BodySubscriber<>() {
      @Override
      public CompletionStage<T> getBody() {
        return delegate.getBody();
      }

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        delegate.onSubscribe(subscription);
      }

      @Override
      public void onNext(List<ByteBuffer> chunks) {
        boolean receivedBytes = chunks.stream().anyMatch(ByteBuffer::hasRemaining);
        delegate.onNext(chunks);
        if (receivedBytes) observed.countDown();
      }

      @Override
      public void onError(Throwable failure) {
        delegate.onError(failure);
      }

      @Override
      public void onComplete() {
        delegate.onComplete();
      }
    };
  }
}
