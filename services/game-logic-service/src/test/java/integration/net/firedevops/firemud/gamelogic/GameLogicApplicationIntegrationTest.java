package net.firedevops.firemud.gamelogic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
  private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();

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
                    new ScopedSettingsOverrides.CommunicationOverride(640, false),
                    null,
                    null,
                    null),
                ScopedSettingsOverrides.empty()));

    String body =
        HttpTestSupport.getBodyUnchecked(
            "http://localhost:" + port + "/actuator/settings/effective/communication?tenantId=42");

    assertThat(body).contains("\"maxMessageLength\":640");
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
                    new ScopedSettingsOverrides.CommunicationOverride(640, true),
                    null,
                    null,
                    null),
                new ScopedSettingsOverrides(
                    null,
                    new ScopedSettingsOverrides.CommunicationOverride(null, false),
                    null,
                    null,
                    null)));

    String body =
        HttpTestSupport.getBodyUnchecked(
            "http://localhost:"
                + port
                + "/actuator/settings/effective/communication?tenantId=42&gameInstanceId=7");

    assertThat(body).contains("\"maxMessageLength\":640");
    assertThat(body).contains("\"whisperObserverMetadataEnabled\":false");
    assertThat(body)
        .contains(
            "\"sources\":[\"operatorDefaults\",\"tenantPersistedOverride:42\",\"gameInstancePersistedOverride:7\"]");
  }

  @Test
  void effectiveCommunicationSettingsRejectsMalformedTenantIdWithInvalidArgumentEnvelope()
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/actuator/settings/effective/communication?tenantId=bad-tenant"))
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"tenantId must be numeric\"");
  }

  @Test
  void effectiveCommunicationSettingsRejectsZeroGameInstanceIdWithInvalidArgumentEnvelope()
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(
                URI.create(
                    "http://localhost:"
                        + port
                        + "/actuator/settings/effective/communication?tenantId=42&gameInstanceId=0"))
            .GET()
            .build();

    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.body()).contains("\"code\":\"INVALID_ARGUMENT\"");
    assertThat(response.body()).contains("\"message\":\"gameInstanceId must be positive\"");
  }
}
