package unit.net.firedevops.firemud.gamesession.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class GameLogicPropertiesTest {
  private GameLogicProperties bind(boolean production, Map<String, Object> overrides)
      throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    var loader = new YamlPropertySourceLoader();
    for (var source :
        loader.load("application", new FileSystemResource("src/main/resources/application.yml"))) {
      environment.getPropertySources().addLast(source);
    }
    if (production) {
      for (var source :
          loader.load(
              "production", new FileSystemResource("src/main/resources/application-prod.yml"))) {
        environment.getPropertySources().addFirst(source);
      }
    }
    environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
    return Binder.get(environment).bind("game.logic", Bindable.of(GameLogicProperties.class)).get();
  }

  @Test
  void localConfigurationBindsInternalReadinessEndpoint() throws IOException {
    assertThat(bind(false, Map.of()).readinessEndpoint())
        .isEqualTo(URI.create("http://game-logic-service:8080/actuator/health/readiness"));
  }

  @Test
  void productionRequiresExplicitEnvironmentBinding() throws IOException {
    assertThat(bind(true, Map.of()).readinessEndpoint()).isNull();
    assertThat(bind(true, Map.of("GAME_LOGIC_READINESS_URL", "")).readinessEndpoint()).isNull();
    assertThat(
            bind(
                    true,
                    Map.of(
                        "GAME_LOGIC_READINESS_URL",
                        "https://game-logic-service.internal:8443/actuator/health/readiness"))
                .readinessEndpoint())
        .isEqualTo(
            URI.create("https://game-logic-service.internal:8443/actuator/health/readiness"));
  }

  @Test
  void localOverrideUsesTheExplicitEnvironmentEndpoint() throws IOException {
    assertThat(
            bind(
                    false,
                    Map.of(
                        "GAME_LOGIC_READINESS_URL",
                        "http://logic.test:18080/actuator/health/readiness"))
                .readinessEndpoint())
        .isEqualTo(URI.create("http://logic.test:18080/actuator/health/readiness"));
  }

  @Test
  void absentAndMalformedBindingsRemainUnusable() throws IOException {
    assertThat(new GameLogicProperties().readinessEndpoint()).isNull();
    assertThat(
            bind(
                    true,
                    Map.of(
                        "GAME_LOGIC_READINESS_URL",
                        "http://logic.test:0/actuator/health/readiness"))
                .readinessEndpoint())
        .isNull();
    assertThat(bind(true, Map.of("GAME_LOGIC_READINESS_URL", "http://[bad")).readinessEndpoint())
        .isNull();
    assertThat(
            bind(
                    true,
                    Map.of(
                        "GAME_LOGIC_READINESS_URL",
                        "http://logic.test:99999/actuator/health/readiness"))
                .readinessEndpoint())
        .isNull();
  }
}
