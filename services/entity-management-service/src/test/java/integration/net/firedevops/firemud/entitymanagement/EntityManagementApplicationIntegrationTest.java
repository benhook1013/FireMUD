package net.firedevops.firemud.entitymanagement;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.config.CommonAutoConfiguration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = EntityManagementApplicationIntegrationTest.TestApp.class)
@Disabled("integration environment not configured")
class EntityManagementApplicationIntegrationTest {
  @Container
  static GenericContainer<?> redis =
      new GenericContainer<>("redis:7.2-alpine").withExposedPorts(6379);

  @Test
  void contextLoads() {
    assertThat(redis.isRunning()).isTrue();
  }

  @Configuration
  @EnableAutoConfiguration(exclude = {RedisAutoConfiguration.class})
  @Import(CommonAutoConfiguration.class)
  static class TestApp {}
}
