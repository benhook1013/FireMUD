package net.firedevops.firemud.common.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.lettuce.core.metrics.MicrometerCommandLatencyRecorder;
import io.lettuce.core.metrics.MicrometerOptions;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import javax.sql.DataSource;
import net.firedevops.firemud.common.saga.persistence.SagaInstance;
import net.firedevops.firemud.common.saga.persistence.SagaInstanceRepository;
import net.firedevops.firemud.common.saga.persistence.SagaStep;
import net.firedevops.firemud.common.saga.persistence.SagaStepRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
@EntityScan(basePackageClasses = {SagaInstance.class, SagaStep.class})
@ConditionalOnProperty(
    prefix = "firemud.database",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties({PostgresProperties.class, RedisProperties.class})
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Properties are injected and not modified")
public class DatabaseAutoConfiguration {
  private final PostgresProperties postgres;
  private final RedisProperties redis;

  public DatabaseAutoConfiguration(PostgresProperties postgres, RedisProperties redis) {
    this.postgres = Objects.requireNonNull(postgres);
    this.redis = Objects.requireNonNull(redis);
  }

  @Bean
  @ConditionalOnClass(name = "org.postgresql.Driver")
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
  public ClientResources lettuceClientResources(MeterRegistry registry) {
    MicrometerOptions options = MicrometerOptions.create();
    return DefaultClientResources.builder()
        .commandLatencyRecorder(new MicrometerCommandLatencyRecorder(registry, options))
        .build();
  }

  @Bean
  public RedisConnectionFactory redisConnectionFactory(ClientResources resources) {
    RedisStandaloneConfiguration standalone =
        new RedisStandaloneConfiguration(redis.getHost(), redis.getPort());
    LettuceClientConfiguration clientConfiguration =
        LettuceClientConfiguration.builder().clientResources(resources).build();
    return new LettuceConnectionFactory(standalone, clientConfiguration);
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

  @Bean
  @ConditionalOnMissingBean(StringRedisTemplate.class)
  public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
    StringRedisTemplate template = new StringRedisTemplate();
    template.setConnectionFactory(factory);
    return template;
  }

  @Bean
  @ConditionalOnBean(EntityManagerFactory.class)
  @ConditionalOnMissingBean(SagaInstanceRepository.class)
  public SagaInstanceRepository sagaInstanceRepository(EntityManagerFactory entityManagerFactory) {
    EntityManager entityManager =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    return new JpaRepositoryFactory(entityManager).getRepository(SagaInstanceRepository.class);
  }

  @Bean
  @ConditionalOnBean(EntityManagerFactory.class)
  @ConditionalOnMissingBean(SagaStepRepository.class)
  public SagaStepRepository sagaStepRepository(EntityManagerFactory entityManagerFactory) {
    EntityManager entityManager =
        SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
    return new JpaRepositoryFactory(entityManager).getRepository(SagaStepRepository.class);
  }
}
