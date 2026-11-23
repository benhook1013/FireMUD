package net.firedevops.firemud.accountservice.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.stripe.exception.ApiConnectionException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class StripeFirstInvoiceHttpClientTest {
  @Test
  void cancelsASlowlyStreamingStripeBodyWithoutAnotherRequest() throws Exception {
    var streaming = new CountDownLatch(1);
    try (var server = new ServerSocket();
        var serverExecutor = Executors.newSingleThreadExecutor();
        var transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()) {
      server.bind(new InetSocketAddress("127.0.0.1", 0));
      server.setSoTimeout(5_000);
      var peerClosed =
          serverExecutor.submit(() -> {
            try (var peer = server.accept()) {
              peer.setSoTimeout(5_000);
              var inbound =
                  new BufferedReader(
                      new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
              assertThat(inbound.readLine()).startsWith("GET /v1/invoices/in_exact HTTP/");
              String header;
              do {
                header = inbound.readLine();
                assertThat(header)
                    .as("request headers terminate before the body fixture")
                    .isNotNull();
              } while (!header.isEmpty());
              var outbound = peer.getOutputStream();
              outbound.write(
                  ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                          + "Transfer-Encoding: chunked\r\n\r\n"
                          + "8\r\n{\"held\":\r\n")
                      .getBytes(StandardCharsets.US_ASCII));
              outbound.flush();
              // No terminal chunk or server shutdown: only the real client can end the exchange.
              // A socket read timeout is a test failure, not evidence of cancellation.
              try {
                return inbound.read() == -1;
              } catch (SocketException resetAfterFirstChunk) {
                return true;
              }
            }
          });
      var client = new StripeFirstInvoiceHttpClient(Duration.ofSeconds(1), Duration.ofSeconds(2));
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
              .setApiBase("http://127.0.0.1:" + server.getLocalPort())
              .setMaxNetworkRetries(0)
              .setHttpClient(client)
              .build();
      assertTimeoutPreemptively(
          Duration.ofSeconds(5),
          () ->
              assertThatThrownBy(() -> stripe.invoices().retrieve("in_exact"))
                  .isInstanceOf(ApiConnectionException.class)
                  .hasMessageContaining("First-invoice provider response deadline expired")
                  .hasCauseInstanceOf(TimeoutException.class));
      assertThat(streaming.getCount()).as("the client received a body chunk").isZero();
      assertThat(peerClosed.get(5, TimeUnit.SECONDS))
          .as("the real client closed the peer connection after receiving body bytes")
          .isTrue();
      verify(observedTransport)
          .sendAsync(
              any(HttpRequest.class),
              org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
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
