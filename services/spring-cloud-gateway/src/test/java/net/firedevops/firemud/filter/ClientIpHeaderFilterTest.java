package net.firedevops.firemud.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ClientIpHeaderFilterTest {

  private final ClientIpHeaderFilter filter = new ClientIpHeaderFilter();

  @Test
  void preservesExistingClientIpHeader() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            requestBuilder ->
                requestBuilder
                    .uri("http://localhost/api/session/test")
                    .header("X-Client-IP", "203.0.113.10"));

    filter.filter(exchange, noOpChain()).block();

    assertThat(exchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("203.0.113.10");
  }

  @Test
  void derivesClientIpFromRemoteAddressWhenHeaderMissing() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(requestBuilder -> requestBuilder.uri("http://localhost/ws/game/test"));
    ServerHttpRequest mutatedRequest =
        exchange
            .getRequest()
            .mutate()
            .remoteAddress(new InetSocketAddress("198.51.100.7", 443))
            .build();
    MockServerWebExchange mutatedExchange = MockServerWebExchange.from(mutatedRequest);

    filter.filter(mutatedExchange, noOpChain()).block();

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("198.51.100.7");
  }

  private WebFilterChain noOpChain() {
    return exchange -> Mono.empty();
  }
}
