package unit.net.firedevops.firemud.gamedesign.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.firedevops.firemud.gamedesign.config.AssetStoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

class AssetStorePropertiesBindingTest {

  @Test
  void canonicalEnvironmentVariablesBindToSeparatePrivateAndPublicEndpoints() throws IOException {
    AssetStoreProperties properties =
        bind(
            Map.of(
                "ASSET_STORE_ENDPOINT", "https://s3.private.example",
                "ASSET_STORE_PUBLIC_BASE_URL", "https://cdn.example/assets",
                "ASSET_STORE_BUCKET", "published-assets",
                "ASSET_STORE_REGION", "ap-southeast-2",
                "ASSET_STORE_ACCESS_KEY", "test-access-key",
                "ASSET_STORE_SECRET_KEY", "test-secret-key"));

    assertThat(properties.getEndpoint()).isEqualTo("https://s3.private.example");
    assertThat(properties.getPublicBaseUrl()).isEqualTo("https://cdn.example/assets");
    assertThat(properties.getBucket()).isEqualTo("published-assets");
    assertThat(properties.getRegion()).isEqualTo("ap-southeast-2");
    assertThat(properties.getAccessKey()).isEqualTo("test-access-key");
    assertThat(properties.getSecretKey()).isEqualTo("test-secret-key");
  }

  @Test
  void publicBaseAndPrivateConnectionHaveNoEndpointFallbackWhenEnvironmentIsAbsent()
      throws IOException {
    AssetStoreProperties properties = bind(Map.of());

    assertThat(properties.getEndpoint()).isEmpty();
    assertThat(properties.getPublicBaseUrl()).isEmpty();
    assertThat(properties.getBucket()).isEmpty();
    assertThat(properties.getAccessKey()).isEmpty();
    assertThat(properties.getSecretKey()).isEmpty();
    assertThat(properties.getRegion()).isEqualTo("ap-southeast-2");
  }

  private AssetStoreProperties bind(Map<String, Object> environmentValues) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test-environment", environmentValues));

    YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
    ClassPathResource productionResource = new ClassPathResource("application-prod.yml");
    FileSystemResource baseResource =
        new FileSystemResource(
            productionResource.getFile().toPath().resolveSibling("application.yml"));
    List<PropertySource<?>> sources = loader.load("application", baseResource);
    sources.forEach(environment.getPropertySources()::addLast);

    return Binder.get(environment)
        .bind("asset.store", Bindable.of(AssetStoreProperties.class))
        .orElseThrow(IllegalStateException::new);
  }
}
