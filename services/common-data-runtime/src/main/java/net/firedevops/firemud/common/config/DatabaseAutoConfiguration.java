package net.firedevops.firemud.common.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.SslVerifyMode;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@AutoConfiguration
@AutoConfigureBefore(
    name = {
      "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
      "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
      "org.springframework.boot.sql.autoconfigure.flyway.FlywayAutoConfiguration"
    })
@EnableConfigurationProperties({PostgresProperties.class, RedisProperties.class})
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Properties are injected and not modified")
public final class DatabaseAutoConfiguration {
  private final PostgresProperties postgres;
  private final RedisProperties redis;

  public DatabaseAutoConfiguration(PostgresProperties postgres, RedisProperties redis) {
    this.postgres = Objects.requireNonNull(postgres);
    this.redis = Objects.requireNonNull(redis);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.database",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnClass(name = "org.postgresql.Driver")
  public DataSource dataSource() {
    String url =
        String.format(
            "jdbc:postgresql://%s:%d/%s?currentSchema=%s",
            postgres.getHost(), postgres.getPort(), postgres.getDatabase(), postgres.getSchema());
    return DataSourceBuilder.create()
        .url(url)
        .username(postgres.getUsername())
        .password(postgres.getPassword())
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.redis",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(ClientResources.class)
  public ClientResources lettuceClientResources(MeterRegistry registry) {
    MicrometerOptions options = MicrometerOptions.create();
    return DefaultClientResources.builder()
        .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(registry, options))
        .build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.redis",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(RedisConnectionFactory.class)
  public RedisConnectionFactory redisConnectionFactory(ClientResources resources) {
    RedisStandaloneConfiguration standalone = redisStandaloneConfiguration();
    LettuceClientConfiguration clientConfiguration = lettuceClientConfiguration(resources);
    return new LettuceConnectionFactory(standalone, clientConfiguration);
  }

  RedisStandaloneConfiguration redisStandaloneConfiguration() {
    RedisStandaloneConfiguration standalone =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
    standalone.setDatabase(Math.max(0, redis.getDatabase()));
    if (redis.getUsername() != null && !redis.getUsername().isBlank()) {
      standalone.setUsername(redis.getUsername());
    }
    if (redis.getPassword() != null && !redis.getPassword().isBlank()) {
      standalone.setPassword(RedisPassword.of(redis.getPassword()));
    }
    return standalone;
  }

  LettuceClientConfiguration lettuceClientConfiguration(ClientResources resources) {
    LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
        LettuceClientConfiguration.builder().clientResources(resources);
    if (redis.isUseSsl() || redis.isStartTls()) {
      LettuceClientConfiguration.LettuceSslClientConfigurationBuilder sslBuilder = builder.useSsl();
      if (redis.isStartTls()) {
        sslBuilder.startTls();
      }
      if (!redis.isVerifyPeer()) {
        sslBuilder.disablePeerVerification();
      } else {
        sslBuilder.verifyPeer(SslVerifyMode.FULL);
      }
      builder = sslBuilder.and();
    }
    return builder.build();
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.redis",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(name = "redisUpGauge")
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
  @ConditionalOnProperty(
      prefix = "firemud.redis",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(RedisTemplate.class)
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    return template;
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "firemud.redis",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  @ConditionalOnMissingBean(StringRedisTemplate.class)
  public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate();
    template.setConnectionFactory(factory);
    return template;
  }
}
