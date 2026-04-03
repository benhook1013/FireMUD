package net.firedevops.firemud.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.ScopedSettingsSnapshot;
import net.firedevops.firemud.common.settings.SharedSettingsAuthorityReader;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SuppressWarnings("resource")
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameLogicServiceApplication.class,
    properties = {
      "spring.profiles.active=test",
      "firemud.grpc.plaintext=true",
      "spring.grpc.server.port=0"
    })
@Import(NoGrpcServerTestConfiguration.class)
class GameLogicApplicationIntegrationTest {

  @LocalServerPort private int port;
  @MockitoBean private SharedSettingsAuthorityReader sharedSettingsAuthorityReader;

  @Test
  void pingEndpointReturnsPong() {
    String body = HttpTestSupport.getBodyUnchecked("http://localhost:" + port + "/ping");
    assertThat(body).contains("pong");
  }

  @Test
  void effectiveCommunicationSettingsEndpointExposesCurrentDefaults() {
    when(sharedSettingsAuthorityReader.readOverrides(42L, null))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    null,
                    new ScopedSettingsOverrides.CommunicationOverride(
                        640,
                        new ScopedSettingsOverrides.CommunicationOverride.DefaultsOverride(
                            true, true, true, false)),
                    null,
                    null,
                    null),
                ScopedSettingsOverrides.empty()));

    String body =
        HttpTestSupport.getBodyUnchecked(
            "http://localhost:" + port + "/actuator/settings/effective/communication?tenantId=42");

    assertThat(body).contains("\"maxMessageLength\":640");
    assertThat(body).contains("\"whisperEnabled\":true");
    assertThat(body).contains("\"whisperObserverMetadataEnabled\":false");
    assertThat(body).contains("\"sources\":[\"operatorDefaults\",\"tenantPersistedOverride:42\"]");
  }

  @Test
  void effectiveCommunicationSettingsEndpointHonorsGameInstanceLayer() {
    when(sharedSettingsAuthorityReader.readOverrides(42L, 7L))
        .thenReturn(
            new ScopedSettingsSnapshot(
                new ScopedSettingsOverrides(
                    null,
                    new ScopedSettingsOverrides.CommunicationOverride(
                        640,
                        new ScopedSettingsOverrides.CommunicationOverride.DefaultsOverride(
                            true, true, true, true)),
                    null,
                    null,
                    null),
                new ScopedSettingsOverrides(
                    null,
                    new ScopedSettingsOverrides.CommunicationOverride(
                        null,
                        new ScopedSettingsOverrides.CommunicationOverride.DefaultsOverride(
                            false, null, null, null)),
                    null,
                    null,
                    null)));

    String body =
        HttpTestSupport.getBodyUnchecked(
            "http://localhost:"
                + port
                + "/actuator/settings/effective/communication?tenantId=42&gameInstanceId=7");

    assertThat(body).contains("\"maxMessageLength\":640");
    assertThat(body).contains("\"sayEnabled\":false");
    assertThat(body)
        .contains(
            "\"sources\":[\"operatorDefaults\",\"tenantPersistedOverride:42\",\"gameInstancePersistedOverride:7\"]");
  }
}
