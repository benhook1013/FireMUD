package net.firedevops.firemud.gamesession.service.impl;

import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import net.firedevops.firemud.common.config.DatabaseAutoConfiguration;
import net.firedevops.firemud.common.config.PostgresProperties;
import net.firedevops.firemud.common.config.RedisProperties;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.RedisSerializer;

/** Uses the value serializer installed by the production Redis template configuration. */
final class SessionContextSerializationTestSupport {
  private SessionContextSerializationTestSupport() {}

  static String serialize(SessionContext context) {
    return new String(
        Objects.requireNonNull(serializer().serialize(context)), StandardCharsets.ISO_8859_1);
  }

  static SessionContext roundTrip(SessionContext context) {
    RedisSerializer<Object> serializer = serializer();
    return (SessionContext)
        Objects.requireNonNull(
            serializer.deserialize(Objects.requireNonNull(serializer.serialize(context))));
  }

  @SuppressWarnings("unchecked")
  private static RedisSerializer<Object> serializer() {
    var template =
        new DatabaseAutoConfiguration(new PostgresProperties(), new RedisProperties())
            .redisTemplate(mock(RedisConnectionFactory.class));
    template.afterPropertiesSet();
    return (RedisSerializer<Object>) Objects.requireNonNull(template.getValueSerializer());
  }
}
