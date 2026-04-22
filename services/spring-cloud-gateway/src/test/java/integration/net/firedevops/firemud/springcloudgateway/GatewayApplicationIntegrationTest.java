package net.firedevops.firemud.springcloudgateway;

import net.firedevops.firemud.test.FiremudAuthTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = SpringCloudGatewayApplication.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
      "spring.main.web-application-type=reactive",
      "management.endpoint.health.group.readiness.include=readinessState",
      FiremudAuthTestProperties.JWT_SECRET
    })
@ImportAutoConfiguration
class GatewayApplicationIntegrationTest {

  @LocalServerPort private int port;

  @Test
  void wsGameWithoutConnectTokenIsRejected() {
    WebTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .get()
        .uri("/ws/game/test")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectHeader()
        .valueEquals("X-Firemud-Handshake-Error-Class", "CONNECT_TOKEN_MISSING");
  }
}
