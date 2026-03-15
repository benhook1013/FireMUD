package net.firedevops.firemud.springcloudgateway;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;

@Disabled("integration environment not configured")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TestApp.class,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,org.springframework.cloud.gateway.config.GatewayClassPathWarningAutoConfiguration",
      "spring.main.web-application-type=reactive"
    })
@ImportAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
class GatewayApplicationIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void pingEndpointReturnsPong() {
    ResponseEntity<String> response =
        restTemplate.getForEntity("http://localhost:" + port + "/ping", String.class);
    assertThat(response.getBody()).contains("pong");
  }
}

@SpringBootConfiguration
@EnableAutoConfiguration(
    exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
@Import({CommonAutoConfiguration.class})
class TestApp {}
