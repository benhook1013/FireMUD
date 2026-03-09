package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameSessionApplicationIntegrationTest.TestApp.class)
@Disabled("integration environment not configured")
class GameSessionApplicationIntegrationTest {

  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    registry.add("firemud.postgres.host", postgres::getHost);
    registry.add("firemud.postgres.port", () -> postgres.getMappedPort(5432));
    registry.add("firemud.postgres.database", () -> postgres.getDatabaseName());
    registry.add("firemud.postgres.username", postgres::getUsername);
    registry.add("firemud.postgres.password", postgres::getPassword);
    registry.add("firemud.redis.host", redis::getHost);
    registry.add("firemud.redis.port", () -> redis.getMappedPort(6379));
  }

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
  @Import({CommonAutoConfiguration.class, DatabaseAutoConfiguration.class})
  static class TestApp {}
}
