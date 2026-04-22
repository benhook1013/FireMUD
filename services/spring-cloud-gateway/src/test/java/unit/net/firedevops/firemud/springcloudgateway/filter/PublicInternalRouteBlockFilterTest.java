package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class PublicInternalRouteBlockFilterTest {
  private final PublicInternalRouteBlockFilter filter = new PublicInternalRouteBlockFilter();

  @Test
  void blocksInternalSubtreeUnderPublicApiFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account/internal/runtime/tenants/7/entitlements")
                .build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksActuatorSubtreeUnderPublicApiFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/session/actuator/settings/effective").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void allowsNormalPublicApiRoute() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/account/auth/login").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void allowsGameplayWebSocketPath() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/ws/game/connect").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  private WebFilterChain chain(AtomicBoolean chainCalled) {
    return exchange -> {
      chainCalled.set(true);
      return Mono.empty();
    };
  }
}
