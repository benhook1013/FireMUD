package net.firedevops.firemud.gamesession.controller;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.firedevops.firemud.cache.LookCacheService;
import net.firedevops.firemud.gamesession.GameSessionServiceApplication;
import net.firedevops.firemud.test.FiremudAuthTestProperties;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(
    classes = GameSessionServiceApplication.class,
    properties = {
      "firemud.database.enabled=false",
      "spring.task.scheduling.enabled=false",
      FiremudAuthTestProperties.JWT_SECRET,
      FiremudAuthTestProperties.JWT_EXPIRATION,
      FiremudAuthTestProperties.HTTP_ENABLED,
      FiremudAuthTestProperties.HTTP_ROLE_REQUIREMENT_PRIVILEGED,
      "firemud.auth.http.public-routes[0].method=GET",
      "firemud.auth.http.public-routes[0].path-pattern=/ping",
      "spring.main.allow-bean-definition-overriding=true",
      "firemud.grpc.plaintext=true",
      "spring.application.name=game-session-service",
      "spring.grpc.server.port=0"
    })
@AutoConfigureMockMvc
@Import({NoGrpcServerTestConfiguration.class, LookCacheTestConfiguration.class})
public @interface GameSessionIntegrationTest {}

@TestConfiguration
class LookCacheTestConfiguration {

  @Bean
  LookCacheService lookCacheService() {
    return new InMemoryLookCacheService();
  }

  private static final class InMemoryLookCacheService implements LookCacheService {
    private final Map<String, CachedLook> cache = new ConcurrentHashMap<>();

    @Override
    public void cache(
        long tenantId, long sessionId, String roomId, String renderedText, String protocolText) {
      cache.put(
          key(tenantId, sessionId),
          new CachedLook(roomId, renderedText, protocolText, System.currentTimeMillis()));
    }

    @Override
    public Optional<CachedLook> get(long tenantId, long sessionId) {
      return Optional.ofNullable(cache.get(key(tenantId, sessionId)));
    }

    private String key(long tenantId, long sessionId) {
      return tenantId + ":" + sessionId;
    }
  }
}
