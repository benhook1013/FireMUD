package net.firedevops.firemud.gamesession.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.StatusRuntimeException;
import java.util.Map;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayPathReadinessHealthIndicatorTest {

  @Test
  void healthReturnsUpWhenAccountAndGameLogicOperationsAreReachable() {
    AccountClient accountClient = mock(AccountClient.class);
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                        .setMessage("Invalid credentials"))
                .build());
    GameLogicClient gameLogicClient = mock(GameLogicClient.class);
    doThrow(new StatusRuntimeException(io.grpc.Status.NOT_FOUND.withDescription("room missing")))
        .when(gameLogicClient)
        .resolveLook(anyString(), anyString(), anyString(), anyString());
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(accountClient, gameLogicClient);

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.UP, health.getStatus());
    assertEquals("AUTH_INVALID_CREDENTIALS", dependencies.get("accountService").get("outcome"));
    assertEquals("NOT_FOUND", dependencies.get("gameLogicService").get("outcome"));
  }

  @Test
  void healthReturnsOutOfServiceWhenAccountDependencyFails() {
    AccountClient accountClient = mock(AccountClient.class);
    doThrow(new IllegalStateException("account down"))
        .when(accountClient)
        .authenticate(anyString(), anyString(), anyString(), anyString());
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(accountClient, mock(GameLogicClient.class));

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("DOWN", dependencies.get("accountService").get("status"));
  }

  @Test
  void healthReturnsOutOfServiceWhenGameLogicDependencyFails() {
    AccountClient accountClient = mock(AccountClient.class);
    when(accountClient.authenticate(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                        .setMessage("Invalid credentials"))
                .build());
    GameLogicClient gameLogicClient = mock(GameLogicClient.class);
    doThrow(new StatusRuntimeException(io.grpc.Status.UNAVAILABLE.withDescription("logic down")))
        .when(gameLogicClient)
        .resolveLook(anyString(), anyString(), anyString(), anyString());
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(accountClient, gameLogicClient);

    Health health = indicator.health();
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) health.getDetails().get("dependencies");

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("UP", dependencies.get("accountService").get("status"));
    assertEquals("DOWN", dependencies.get("gameLogicService").get("status"));
  }
}
