package net.firedevops.firemud.common.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.resource.ClientResources;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class DatabaseAutoConfigurationTest {
  @Test
  void mapsRedisStandaloneCredentialsAndDatabaseIndex() {
    PostgresProperties postgres = new PostgresProperties();
    RedisProperties redis = new RedisProperties();
    redis.setHost("redis.internal");
    redis.setPort(6380);
    redis.setDatabase(3);
    redis.setUsername("redis-user");
    redis.setPassword("redis-pass");
    redis.setUseSsl(true);
    redis.setStartTls(true);
    redis.setVerifyPeer(true);

    DatabaseAutoConfiguration config = new DatabaseAutoConfiguration(postgres, redis);

    RedisStandaloneConfiguration standalone = config.redisStandaloneConfiguration();
    assertEquals("redis.internal", standalone.getHostName());
    assertEquals(6380, standalone.getPort());
    assertEquals(3, standalone.getDatabase());
    assertEquals("redis-user", standalone.getUsername());
    assertArrayEquals(
        "redis-pass".toCharArray(), standalone.getPassword().toOptional().orElseThrow());
  }

  @Test
  void mapsLettuceTlsSettingsAndClientResources() {
    PostgresProperties postgres = new PostgresProperties();
    RedisProperties redis = new RedisProperties();
    redis.setUseSsl(true);
    redis.setStartTls(true);
    redis.setVerifyPeer(true);

    DatabaseAutoConfiguration config = new DatabaseAutoConfiguration(postgres, redis);
    ClientResources resources = mock(ClientResources.class);

    LettuceClientConfiguration clientConfiguration = config.lettuceClientConfiguration(resources);
    assertTrue(clientConfiguration.isUseSsl());
    assertTrue(clientConfiguration.isStartTls());
    assertTrue(clientConfiguration.isVerifyPeer());
    assertEquals(SslVerifyMode.FULL, clientConfiguration.getVerifyMode());
    assertTrue(clientConfiguration.getClientResources().isPresent());

    LettuceConnectionFactory factory =
        (LettuceConnectionFactory) config.redisConnectionFactory(resources);
    assertTrue(factory.getClientConfiguration().isUseSsl());
    assertTrue(factory.getClientConfiguration().isStartTls());
    assertTrue(factory.getClientConfiguration().isVerifyPeer());
    assertEquals(SslVerifyMode.FULL, factory.getClientConfiguration().getVerifyMode());
  }
}
