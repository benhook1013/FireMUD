package net.firedevops.firemud.accountservice.client;

import com.stripe.exception.ApiConnectionException;
import com.stripe.net.HttpHeaders;
import com.stripe.net.StripeRequest;
import com.stripe.net.StripeResponse;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** The held first-invoice composition uses one bounded transport for its small JSON replies. */
public final class StripeFirstInvoiceHttpClient extends com.stripe.net.HttpClient {
  private static final long MAX_RESPONSE_BYTES = 1_048_576;

  private final HttpClient transport;
  private final Duration responseTimeout;

  public StripeFirstInvoiceHttpClient(Duration connectTimeout, Duration responseTimeout) {
    if (responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()) {
      throw new IllegalArgumentException("A positive provider response timeout is required");
    }
    this.responseTimeout = responseTimeout;
    transport = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
  }

  @Override
  public StripeResponse request(StripeRequest request) throws ApiConnectionException {
    HttpRequest.Builder outbound;
    try {
      outbound = HttpRequest.newBuilder(request.url().toURI()).timeout(responseTimeout);
    } catch (URISyntaxException invalid) {
      throw new ApiConnectionException("Invalid first-invoice provider endpoint", invalid);
    }
    request
        .headers()
        .map()
        .forEach((name, values) -> values.forEach(value -> outbound.header(name, value)));
    if (request.content() != null) {
      outbound.setHeader("Content-Type", request.content().contentType());
    }
    var body =
        request.content() == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(request.content().byteArrayContent());
    var exchange =
        transport.sendAsync(
            outbound.method(request.method().name(), body).build(),
            HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), MAX_RESPONSE_BYTES));
    try {
      var received = exchange.get(responseTimeout.toNanos(), TimeUnit.NANOSECONDS);
      return new StripeResponse(
          received.statusCode(), HttpHeaders.of(received.headers().map()), received.body());
    } catch (TimeoutException timeout) {
      exchange.cancel(true);
      throw new ApiConnectionException("First-invoice provider response deadline expired", timeout);
    } catch (InterruptedException interrupted) {
      exchange.cancel(true);
      Thread.currentThread().interrupt();
      throw new ApiConnectionException("First-invoice provider request interrupted", interrupted);
    } catch (ExecutionException failed) {
      throw new ApiConnectionException(
          "First-invoice provider transport failed", failed.getCause());
    }
  }
}
