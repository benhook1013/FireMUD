package net.firedevops.firemud.common.config;

import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@EnableConfigurationProperties({PostgresProperties.class, RedisProperties.class})
public class DatabaseAutoConfiguration {
  private final PostgresProperties postgres;
  private final RedisProperties redis;

  public DatabaseAutoConfiguration(PostgresProperties postgres, RedisProperties redis) {
    this.postgres = Objects.requireNonNull(postgres);
    this.redis = Objects.requireNonNull(redis);
  }

  @Bean
  public DataSource dataSource() {
    String url =
        String.format(
            "jdbc:postgresql://%s:%d/%s",
            postgres.getHost(), postgres.getPort(), postgres.getDatabase());
    return DataSourceBuilder.create()
        .url(url)
        .username(postgres.getUsername())
        .password(postgres.getPassword())
        .build();
  }

  @Bean
  @ConditionalOnMissingBean(MeterRegistry.class)
  public MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  @Bean
  public ClientResources lettuceClientResources(MeterRegistry registry) {
    MicrometerOptions options = MicrometerOptions.create();
    return DefaultClientResources.builder()
        .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(registry, options))
        .build();
  }

  @Bean
  public RedisConnectionFactory redisConnectionFactory(ClientResources resources) {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(redis.getHost(), redis.getPort());
    factory.setClientResources(resources);
    return factory;
  }

  @Bean
  public Gauge redisUpGauge(RedisConnectionFactory factory, MeterRegistry registry) {
    return Gauge.builder(
            "redis.up",
            () -> {
              try (var conn = factory.getConnection()) {
                return "PONG".equalsIgnoreCase(conn.ping()) ? 1.0 : 0.0;
              } catch (Exception e) {
                return 0.0;
              }
            })
        .register(registry);
  }

  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    return template;
  }
}
