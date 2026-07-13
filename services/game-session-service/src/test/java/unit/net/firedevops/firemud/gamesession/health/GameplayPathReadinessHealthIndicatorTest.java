package net.firedevops.firemud.gamesession.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.grpc.StatusRuntimeException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.health.GameplayLocalPathReadinessProbe.ProbeResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

class GameplayPathReadinessHealthIndicatorTest {

  @Test
  void healthReturnsUpWhenAccountAndGameLogicOperationsAreReachable() {
    AccountClient accountClient = mock(AccountClient.class);
    when(accountClient.authenticateForReadiness(anyString(), anyString(), anyString()))
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
        .resolveLookForReadiness(anyString(), anyString(), anyString(), anyString(), anyString());
    GameplayLocalPathReadinessProbe localPathProbe = mock(GameplayLocalPathReadinessProbe.class);
    when(localPathProbe.probeSessionContextStore()).thenReturn(ProbeResult.up("ROUND_TRIP_OK"));
    when(localPathProbe.probeCommandQueueStore()).thenReturn(ProbeResult.up("QUEUE_WRITE_OK"));
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(
            accountClient, gameLogicClient, localPathProbe, tracker());

    Health health = indicator.health();
    Object rawDependencies = Objects.requireNonNull(health.getDetails().get("dependencies"));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) rawDependencies;

    assertEquals(Status.UP, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies"), health.getDetails().keySet());
    assertEquals(
        "AUTH_INVALID_CREDENTIALS",
        Objects.requireNonNull(dependencies.get("accountService")).get("outcome"));
    assertEquals(
        "NOT_FOUND", Objects.requireNonNull(dependencies.get("gameLogicService")).get("outcome"));
  }

  @Test
  void healthReturnsOutOfServiceWhenAccountDependencyFails() {
    AccountClient accountClient = mock(AccountClient.class);
    doThrow(new IllegalStateException("account down"))
        .when(accountClient)
        .authenticateForReadiness(anyString(), anyString(), anyString());
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(
            accountClient,
            mock(GameLogicClient.class),
            mock(GameplayLocalPathReadinessProbe.class),
            tracker());

    Health health = indicator.health();
    Object rawDependencies = Objects.requireNonNull(health.getDetails().get("dependencies"));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) rawDependencies;

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    assertEquals("DOWN", Objects.requireNonNull(dependencies.get("accountService")).get("status"));
  }

  @Test
  void healthReturnsOutOfServiceWhenGameLogicDependencyFails() {
    AccountClient accountClient = mock(AccountClient.class);
    when(accountClient.authenticateForReadiness(anyString(), anyString(), anyString()))
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
        .resolveLookForReadiness(anyString(), anyString(), anyString(), anyString(), anyString());
    GameplayLocalPathReadinessProbe localPathProbe = mock(GameplayLocalPathReadinessProbe.class);
    when(localPathProbe.probeSessionContextStore()).thenReturn(ProbeResult.up("ROUND_TRIP_OK"));
    when(localPathProbe.probeCommandQueueStore()).thenReturn(ProbeResult.up("QUEUE_WRITE_OK"));
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(
            accountClient, gameLogicClient, localPathProbe, tracker());

    Health health = indicator.health();
    Object rawDependencies = Objects.requireNonNull(health.getDetails().get("dependencies"));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) rawDependencies;

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    assertEquals("UP", Objects.requireNonNull(dependencies.get("accountService")).get("status"));
    assertEquals(
        "DOWN", Objects.requireNonNull(dependencies.get("gameLogicService")).get("status"));
  }

  @Test
  void healthReturnsOutOfServiceWhenLocalSessionContextProbeFails() {
    AccountClient accountClient = mock(AccountClient.class);
    when(accountClient.authenticateForReadiness(anyString(), anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(
                    net.firedevops.firemud.shared.v1.ErrorDetail.newBuilder()
                        .setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS)
                        .setMessage("Invalid credentials"))
                .build());
    GameplayLocalPathReadinessProbe localPathProbe = mock(GameplayLocalPathReadinessProbe.class);
    when(localPathProbe.probeSessionContextStore())
        .thenReturn(ProbeResult.down("redis write failed"));
    GameplayPathReadinessHealthIndicator indicator =
        new GameplayPathReadinessHealthIndicator(
            accountClient, mock(GameLogicClient.class), localPathProbe, tracker());

    Health health = indicator.health();
    Object rawDependencies = Objects.requireNonNull(health.getDetails().get("dependencies"));
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> dependencies =
        (Map<String, Map<String, Object>>) rawDependencies;

    assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
    assertIterableEquals(
        List.of("contract", "admissionMeaning", "dependencies", "failingDependency"),
        health.getDetails().keySet());
    assertEquals(
        "DOWN", Objects.requireNonNull(dependencies.get("sessionContextStore")).get("status"));
  }

  private static ReadinessTransitionTracker tracker() {
    return new ReadinessTransitionTracker(new SimpleMeterRegistry());
  }
}
