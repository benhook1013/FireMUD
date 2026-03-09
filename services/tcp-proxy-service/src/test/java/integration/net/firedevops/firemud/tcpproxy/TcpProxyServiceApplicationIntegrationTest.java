package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Disabled("integration environment not configured")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplicationIntegrationTest.TestApp.class,
    properties = {"TCP_PROXY_PORT=0", "GATEWAY_WS_URL=ws://localhost/ws"})
class TcpProxyServiceApplicationIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void pingEndpointReturnsPong() {
    String body = restTemplate.getForObject("http://localhost:" + port + "/ping", String.class);
    assertThat(body).contains("pong");
  }

  @Configuration
  @EnableAutoConfiguration(
      exclude = {DataSourceAutoConfiguration.class, RedisAutoConfiguration.class})
  @Import(CommonAutoConfiguration.class)
  static class TestApp {}
}
