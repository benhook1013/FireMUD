package net.firedevops.firemud;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ResultStatus;
import net.firedevops.firemud.dto.GameInstanceDto;
import net.firedevops.firemud.dto.StartSessionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameSessionServiceApplication.class,
    properties = {
      "spring.profiles.active=dev",
      "game-session.log-only=true"
    })
class LogOnlyGameSessionSmokeTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Test
  void startSessionIsAcceptedAndLogged(CapturedOutput output) {
    StartSessionRequest request =
        new StartSessionRequest(42L, "1.0.0", "patch-1", 100L);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<StartSessionRequest> entity = new HttpEntity<>(request, headers);
    ParameterizedTypeReference<ApiResponse<GameInstanceDto>> responseType =
        new ParameterizedTypeReference<>() {};

    ResponseEntity<ApiResponse<GameInstanceDto>> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/sessions",
            HttpMethod.POST,
            entity,
            responseType);

    assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();

    ApiResponse<GameInstanceDto> body = response.getBody();
    assertThat(body).isNotNull();
    assertThat(body.status()).isEqualTo(ResultStatus.SUCCESS);
    GameInstanceDto dto = body.data();
    assertThat(dto).isNotNull();
    assertThat(dto.tenantId()).isEqualTo(42L);
    assertThat(dto.runtimeVersion()).isEqualTo("1.0.0");
    assertThat(dto.status()).isEqualTo("RUNNING");

    assertThat(output.getOut())
        .contains(
            "Log-only mode enabled; acknowledging start for tenant 42 version 1.0.0 patch patch-1");
  }
}
