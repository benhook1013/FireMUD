package net.firedevops.firemud.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ClientIpHeaderFilterTest {
  @Test
  void addsClientIpHeader() {
    ClientIpHeaderFilter filter = new ClientIpHeaderFilter();
    MockServerHttpRequest request =
        MockServerHttpRequest.get("/").remoteAddress(new InetSocketAddress("1.2.3.4", 0)).build();
    MockServerWebExchange exchange = MockServerWebExchange.from(request);
    java.util.concurrent.atomic.AtomicReference<ServerWebExchange> ref =
        new java.util.concurrent.atomic.AtomicReference<>();
    WebFilterChain chain =
        e -> {
          ref.set(e);
          return Mono.empty();
        };
    filter.filter(exchange, chain).block();
    assertEquals("1.2.3.4", ref.get().getRequest().getHeaders().getFirst("X-Client-IP"));
  }
}
