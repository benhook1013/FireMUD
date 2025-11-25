package net.firedevops.firemud.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ClientIpHeaderFilterTest {

  private final ClientIpHeaderFilter filter = new ClientIpHeaderFilter();

  @Test
  void addsClientIpHeader() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/").remoteAddress(new InetSocketAddress("1.2.3.4", 0)).build();
    ServerWebExchange mutatedExchange = filterThroughChain(MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("1.2.3.4");
  }

  @Test
  void noHeaderWhenRemoteAddressMissing() {
    MockServerHttpRequest request = MockServerHttpRequest.get("/").build();
    ServerWebExchange mutatedExchange = filterThroughChain(MockServerWebExchange.from(request));

    assertNull(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"));
  }

  @Test
  void noHeaderWhenAddressUnresolved() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/")
            .remoteAddress(java.net.InetSocketAddress.createUnresolved("example.com", 0))
            .build();
    ServerWebExchange mutatedExchange = filterThroughChain(MockServerWebExchange.from(request));

    assertNull(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"));
  }

  @Test
  void preservesExistingClientIpHeader() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("http://localhost/api/session/test")
            .header("X-Client-IP", "203.0.113.10")
            .build();

    ServerWebExchange mutatedExchange = filterThroughChain(MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("203.0.113.10");
  }

  @Test
  void derivesClientIpFromRemoteAddressWhenHeaderMissing() {
    MockServerHttpRequest request =
        MockServerHttpRequest.get("http://localhost/ws/game/test")
            .remoteAddress(new InetSocketAddress("198.51.100.7", 443))
            .build();
    ServerWebExchange mutatedExchange = filterThroughChain(MockServerWebExchange.from(request));

    assertThat(mutatedExchange.getRequest().getHeaders().getFirst("X-Client-IP"))
        .isEqualTo("198.51.100.7");
  }

  private ServerWebExchange filterThroughChain(ServerWebExchange exchange) {
    AtomicReference<ServerWebExchange> ref = new AtomicReference<>();
    WebFilterChain chain =
        e -> {
          ref.set(e);
          return Mono.empty();
        };
    filter.filter(exchange, chain).block();
    return ref.get();
  }
}
