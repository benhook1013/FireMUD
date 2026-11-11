package net.firedevops.firemud.common.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

class DatabaseAutoConfigurationTest {
  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  DatabaseAutoConfiguration.class,
                  DataRedisAutoConfiguration.class,
                  DataRedisReactiveAutoConfiguration.class))
          .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
          .withPropertyValues("firemud.database.enabled=false");

  @Test
  void bootRedisAutoConfigurationPreservesTypedObjectAndStringTemplates() {
    contextRunner
        .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
        .run(
            context -> {
              Object typed =
                  context
                      .getBeanProvider(
                          ResolvableType.forClassWithGenerics(
                              RedisTemplate.class, String.class, Object.class))
                      .getIfUnique();
              assertNotNull(typed, "Session consumers require RedisTemplate<String, Object>");
              assertSame(context.getBean("redisTemplate"), typed);
              assertSame(
                  context.getBean(RedisConnectionFactory.class),
                  context.getBean(StringRedisTemplate.class).getConnectionFactory());
            });
  }

  @Test
  void bootRedisAutoConfigurationCannotReplaceTheFiremudEndpoint() {
    contextRunner
        .withPropertyValues(
            "firemud.redis.host=firemud-redis.internal",
            "firemud.redis.port=6380",
            "firemud.redis.database=3",
            "spring.data.redis.host=unrelated-boot-default.internal")
        .run(
            context -> {
              var factory = context.getBean(LettuceConnectionFactory.class);
              assertSame(factory, context.getBean(ReactiveRedisConnectionFactory.class));
              assertSame(
                  factory,
                  context
                      .getBean("reactiveRedisTemplate", ReactiveRedisTemplate.class)
                      .getConnectionFactory());
              assertSame(
                  factory,
                  context
                      .getBean("reactiveStringRedisTemplate", ReactiveStringRedisTemplate.class)
                      .getConnectionFactory());
              assertEquals(
                  "firemud-redis.internal", factory.getStandaloneConfiguration().getHostName());
              assertEquals(6380, factory.getStandaloneConfiguration().getPort());
              assertEquals(3, factory.getStandaloneConfiguration().getDatabase());
            });
  }

  @Test
  void explicitTypedTemplateOverrideStillWinsAlongsideBootAutoConfiguration() {
    contextRunner
        .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class))
        .withUserConfiguration(ExplicitRedisTemplate.class)
        .run(
            context -> {
              var template = context.getBean("redisTemplate", RedisTemplate.class);
              assertSame(
                  template,
                  context
                      .getBeanProvider(
                          ResolvableType.forClassWithGenerics(
                              RedisTemplate.class, String.class, Object.class))
                      .getObject());
              assertEquals(StringRedisSerializer.class, template.getKeySerializer().getClass());
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class ExplicitRedisTemplate {
    @Bean
    RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
      RedisTemplate<String, Object> template = new RedisTemplate<>();
      template.setConnectionFactory(factory);
      template.setKeySerializer(new StringRedisSerializer());
      return template;
    }
  }

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
