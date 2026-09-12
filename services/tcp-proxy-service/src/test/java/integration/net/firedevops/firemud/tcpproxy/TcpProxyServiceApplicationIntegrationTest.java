package net.firedevops.firemud.tcpproxy;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.test.HttpTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = TcpProxyServiceApplication.class,
    properties = {"TCP_PROXY_PORT=0", "GATEWAY_WS_URL=ws://localhost/ws/game"})
class TcpProxyServiceApplicationIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private ApplicationContext applicationContext;

  @Test
  void pingEndpointReturnsPong() {
    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }

  @Test
  void startsWithoutRedisRuntimeInfrastructure() {
    assertThat(applicationContext.getBeansOfType(RedisConnectionFactory.class)).isEmpty();
    assertThat(applicationContext.getBeansOfType(RedisTemplate.class)).isEmpty();
  }
}
