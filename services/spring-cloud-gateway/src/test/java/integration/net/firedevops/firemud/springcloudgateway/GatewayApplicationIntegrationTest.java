package net.firedevops.firemud.springcloudgateway;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@Disabled("integration environment not configured")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TestApp.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration,org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
      "spring.main.web-application-type=reactive"
    })
@ImportAutoConfiguration
class GatewayApplicationIntegrationTest {

  @LocalServerPort private int port;

  @Test
  void pingEndpointReturnsPong() {
    assertThat(HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping"))
        .contains("pong");
  }
}

@SpringBootConfiguration
@EnableAutoConfiguration(
    excludeName = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration"
    })
@Import({CommonAutoConfiguration.class})
class TestApp {}
