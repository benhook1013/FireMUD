package net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.resource.ClientResources;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.health.DataRedisReactiveHealthContributorAutoConfiguration;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(RedisHealthMockitoBeanConfigurationTest.Config.class)
@TestPropertySource(properties = "firemud.database.enabled=false")
class RedisHealthMockitoBeanConfigurationTest {
  @MockitoBean private ClientResources clientResources;

  // An imperative-only replacement loses the reactive interface after conditions are evaluated.
  @MockitoBean private LettuceConnectionFactory redisConnectionFactory;

  @Autowired private ApplicationContext context;

  @Test
  void mockedFactoryRetainsBothInterfacesAndReactiveHealthContributor() {
    assertThat(context.getBeansOfType(RedisConnectionFactory.class))
        .containsOnlyKeys("redisConnectionFactory")
        .containsValue(redisConnectionFactory);
    assertThat(context.getBeansOfType(ReactiveRedisConnectionFactory.class))
        .containsOnlyKeys("redisConnectionFactory");
    assertThat(context.getBean(ReactiveRedisConnectionFactory.class))
        .isSameAs(redisConnectionFactory);
    assertThat(context.getBean("redisHealthContributor"))
        .isInstanceOf(ReactiveHealthContributor.class);
  }

  @Configuration(proxyBeanMethods = false)
  @ImportAutoConfiguration({
    DatabaseAutoConfiguration.class,
    DataRedisReactiveHealthContributorAutoConfiguration.class
  })
  static class Config {
    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }
}
