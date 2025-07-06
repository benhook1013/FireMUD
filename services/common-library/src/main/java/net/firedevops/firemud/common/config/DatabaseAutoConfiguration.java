package net.firedevops.firemud.common.config;

import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

@Configuration
@EnableConfigurationProperties({PostgresProperties.class, RedisProperties.class})
@RequiredArgsConstructor
public class DatabaseAutoConfiguration {
  private final PostgresProperties postgres;
  private final RedisProperties redis;

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
  public RedisConnectionFactory redisConnectionFactory() {
    return new LettuceConnectionFactory(redis.getHost(), redis.getPort());
  }

  @Bean
  public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(factory);
    return template;
  }
}
