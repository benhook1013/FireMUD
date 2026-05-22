package net.firedevops.firemud.gamedesign.service;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.gamedesign.GameDesignServiceApplication;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import net.firedevops.firemud.test.PostgresBackedServiceTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(
    classes = GameDesignServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.auth.jwt-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0",
      "asset.store.endpoint=http://localhost:9000",
      "asset.store.bucket=test-bucket",
      "asset.store.region=us-east-1",
      "asset.store.access-key=test-access-key",
      "asset.store.secret-key=test-secret-key"
    })
@Import(NoGrpcServerTestConfiguration.class)
class SettingsAuthorityServiceIntegrationTest {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configure(DynamicPropertyRegistry registry) {
    PostgresBackedServiceTestSupport.registerPostgresService(
        registry, postgres, "game_design_service");
  }

  @Autowired private SettingsAuthorityService settingsAuthorityService;

  @Test
  void persistsTenantAndGameInstanceOverridesSeparately() {
    settingsAuthorityService.putDomainOverride(
        "42",
        null,
        ScopedSettingsOverrides.SettingsDomain.PRESENTATION,
        new ScopedSettingsOverrides(
            null,
            null,
            new ScopedSettingsOverrides.PresentationOverride(
                null, ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC, true, null),
            null,
            null));
    settingsAuthorityService.putDomainOverride(
        "42",
        7L,
        ScopedSettingsOverrides.SettingsDomain.MOVEMENT,
        new ScopedSettingsOverrides(
            null, null, null, new ScopedSettingsOverrides.MovementOverride(false), null));

    ScopedSettingsSnapshot snapshot = settingsAuthorityService.getScopedOverrides("42", 7L);

    assertThat(snapshot.tenantOverrides().presentation()).isNotNull();
    assertThat(snapshot.tenantOverrides().presentation().defaultColorMode())
        .isEqualTo(ScopedSettingsOverrides.PresentationOverride.ColorMode.BASIC);
    assertThat(snapshot.tenantOverrides().presentation().briefEnabledByDefault()).isTrue();
    assertThat(snapshot.gameInstanceOverrides().movement()).isNotNull();
    assertThat(snapshot.gameInstanceOverrides().movement().postMoveLookEnabled()).isFalse();
  }

  @Test
  void deleteRemovesPersistedDomainOverride() {
    settingsAuthorityService.putDomainOverride(
        "84",
        9L,
        ScopedSettingsOverrides.SettingsDomain.RECONNECTION,
        new ScopedSettingsOverrides(
            new ScopedSettingsOverrides.ReconnectionOverride(
                new ScopedSettingsOverrides.ReconnectionOverride.PolicyOverride(300_000L, false),
                null),
            null,
            null,
            null,
            null));

    assertThat(
            settingsAuthorityService
                .getScopedOverrides("84", 9L)
                .gameInstanceOverrides()
                .reconnection())
        .isNotNull();

    settingsAuthorityService.deleteDomainOverride(
        "84", 9L, ScopedSettingsOverrides.SettingsDomain.RECONNECTION);

    assertThat(
            settingsAuthorityService
                .getScopedOverrides("84", 9L)
                .gameInstanceOverrides()
                .reconnection())
        .isNull();
  }
}
