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
  void blocksInternalSubtreeUnderPublicDesignFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/design/internal/runtime/engine").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksInternalSubtreeUnderPublicAdminFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/admin/internal/tenants/7/clients").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksInternalSubtreeUnderPublicSocialFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/social/internal/friends/123").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksInternalSubtreeUnderPublicSessionFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/session/internal/control").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksActuatorSubtreeUnderPublicAccountFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account/actuator/health").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksActuatorSubtreeUnderPublicDesignFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/design/actuator/health").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksActuatorSubtreeUnderPublicAdminFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(MockServerHttpRequest.get("/api/admin/actuator/health").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksActuatorSubtreeUnderPublicSocialFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/social/actuator/health").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksActuatorSubtreeUnderPublicSessionFamily() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/session/actuator/settings/effective").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksUnavailableExternalAccountLinkRoute() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/account/accounts/42/external").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksExternalAccountRouteWithMatrixParameterOnAccountId() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/account/accounts/42;provider=steam/external").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksExternalAccountRouteWithMatrixParameterOnExternalSegment() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/account/accounts/42/external;provider=steam").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksExternalAccountRouteWithMatrixParametersOnEverySegment() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post(
                    "/api;probe/account;probe/accounts;probe/42;probe/external;probe")
                .build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksExternalAccountRouteWithDuplicateSeparatorBeforeAccountId() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/account/accounts//42/external").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksExternalAccountRouteWithDuplicateSeparatorBeforeExternal() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/account/accounts/42//external").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void blocksExternalAccountRouteWithTrailingSeparator() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/account/accounts/42/external/").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isFalse();
    assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  @Test
  void allowsExternalAccountRouteWithAdditionalSegment() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.post("/api/account/accounts/42/external/extra").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void allowsSupportedAccountSiblingRoute() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/api/account/accounts/42/export").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
  }

  @Test
  void allowsInternalNamedSubtreeOutsidePublicApiPrefix() {
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/private/account/internal/status").build());
    AtomicBoolean chainCalled = new AtomicBoolean(false);

    filter.filter(exchange, chain(chainCalled)).block();

    assertThat(chainCalled).isTrue();
    assertThat(exchange.getResponse().getStatusCode()).isNull();
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
