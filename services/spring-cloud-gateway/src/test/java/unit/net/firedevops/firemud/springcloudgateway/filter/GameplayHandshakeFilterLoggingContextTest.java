package net.firedevops.firemud.springcloudgateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import net.firedevops.firemud.common.runtime.RuntimeIdentity;
import net.firedevops.firemud.common.security.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

class GameplayHandshakeFilterLoggingContextTest {
  private static final String SECRET = "testsecretkeytestsecretkeytest1234";

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void openLoggingContextUsesTransportSessionHeaderAsCorrelationId() {
    GameplayHandshakeFilter filter =
        new GameplayHandshakeFilter(
            new JwtUtil(SECRET, 30_000L),
            new RuntimeIdentity(
                "spring-cloud-gateway", "gateway-test", null, Instant.EPOCH, null, null, null),
            null);
    MockServerWebExchange exchange =
        MockServerWebExchange.from(
            MockServerHttpRequest.get("/ws/game/test")
                .header(GameplayHandshakeFilter.TRANSPORT_SESSION_HEADER, "12345")
                .build());

    try (var ignored = filter.openLoggingContext(exchange)) {
      assertThat(MDC.get("service")).isEqualTo("spring-cloud-gateway");
      assertThat(MDC.get("serviceInstanceId")).isEqualTo("gateway-test");
      assertThat(MDC.get("correlationId")).isEqualTo("12345");
    }

    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
