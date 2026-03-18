package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ResultStatus;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.grpc.server.lifecycle.GrpcServerLifecycle;
import org.springframework.grpc.server.service.GrpcServiceDiscoverer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SuppressWarnings({})
@Disabled(
    "TODO: re-enable once Account/Redis/GameInstance persistence is wired; "
        + "tests currently depend on the dev-isolated stubbed services "
        + "(see design/project-management/vertical-slices/02-task-list-login-and-session-vertical-slice.md#7-dev-mode-stubs-and-real-service-rollout)")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    classes = GameSessionServiceApplication.class,
    properties = {
      "spring.application.name=game-session-service",
      "spring.profiles.active=dev",
      "game-session.dev-isolated=true",
      "game-session.require-authenticated-commands=false",
      "spring.autoconfigure.exclude=org.springframework.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration,org.springframework.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration,org.springframework.boot.grpc.server.autoconfigure.health.GrpcServerHealthAutoConfiguration"
    })
@Import(DevIsolatedGameSessionSmokeTest.DisabledGrpcTestConfig.class)
class DevIsolatedGameSessionSmokeTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @MockitoBean private RedisTemplate<String, Object> redisTemplate;

  @Test
  void startSessionIsAcceptedAndLogged(CapturedOutput output) {
    StartSessionRequest request = new StartSessionRequest(42L, "1.0.0", "patch-1", 100L);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    HttpEntity<StartSessionRequest> entity = new HttpEntity<>(request, headers);
    ParameterizedTypeReference<ApiResponse<GameInstanceDto>> responseType =
        new ParameterizedTypeReference<>() {};

    ResponseEntity<ApiResponse<GameInstanceDto>> response =
        restTemplate.exchange(
            "http://localhost:" + port + "/sessions", HttpMethod.POST, entity, responseType);

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
            "Dev-isolated mode enabled; acknowledging start for tenant 42 version 1.0.0 patch patch-1");
  }

  @Configuration
  static class DisabledGrpcTestConfig {
    @Bean
    GrpcServerLifecycle grpcServerLifecycle() {
      return Mockito.mock(GrpcServerLifecycle.class);
    }

    @Bean
    GrpcServiceDiscoverer grpcServiceDiscoverer() {
      return Mockito.mock(GrpcServiceDiscoverer.class);
    }
  }
}
