package net.firedevops.firemud.gamesession;

import static org.assertj.core.api.Assertions.assertThat;

import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ResultStatus;
import net.firedevops.firemud.gamesession.dto.GameInstanceDto;
import net.firedevops.firemud.gamesession.dto.StartSessionRequest;
import net.firedevops.firemud.test.HttpTestSupport;
import net.firedevops.firemud.test.NoGrpcServerTestConfiguration;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.type.TypeReference;

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
@Import(NoGrpcServerTestConfiguration.class)
class DevIsolatedGameSessionSmokeTest {

  @LocalServerPort private int port;

  @MockitoBean private RedisTemplate<String, Object> redisTemplate;

  @Test
  void startSessionIsAcceptedAndLogged(CapturedOutput output) {
    StartSessionRequest request = new StartSessionRequest(42L, "1.0.0", "patch-1", 100L);

    ApiResponse<GameInstanceDto> body =
        HttpTestSupport.postJsonUnchecked(
            "http://localhost:" + port + "/sessions",
            request,
            new TypeReference<ApiResponse<GameInstanceDto>>() {});
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
}
