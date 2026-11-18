package net.firedevops.firemud.springcloudgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

class GatewayDefaultRetryConfigurationTest {
  @Test
  void defaultRetriesCannotReplayOneUseAccountMutations() throws IOException {
    var environment = new StandardEnvironment();
    for (var source :
        new YamlPropertySourceLoader()
            .load(
                "gateway-defaults", new FileSystemResource("src/main/resources/application.yml"))) {
      environment.getPropertySources().addFirst(source);
    }
    var properties =
        new CanonicalGatewayRoutesConfiguration().canonicalGatewayProperties(environment);
    var retryFilters =
        properties.getDefaultFilters().stream()
            .filter(filter -> "Retry".equals(filter.getName()))
            .toList();
    assertThat(retryFilters).hasSize(1);
    assertThat(retryFilters.getFirst().getArgs()).containsEntry("methods", "GET");
  }
}
