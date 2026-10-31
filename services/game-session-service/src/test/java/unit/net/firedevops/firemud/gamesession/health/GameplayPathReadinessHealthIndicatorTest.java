package net.firedevops.firemud.gamesession.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Status;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.common.health.HttpEndpointAvailabilityChecker;
import net.firedevops.firemud.common.health.ReadinessTransitionTracker;
import net.firedevops.firemud.gamelogic.v1.PingResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.GameLogicClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.health.GameplayLocalPathReadinessProbe.ProbeResult;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GameplayPathReadinessHealthIndicatorTest {
  private static final String ENDPOINT = "http://game-logic-service:8080/actuator/health/readiness";
  private final AccountClient account = mock(AccountClient.class);
  private final GameLogicClient gameLogic = mock(GameLogicClient.class);
  private final EntityManagementClient entityManagement = mock(EntityManagementClient.class);
  private final GameplayLocalPathReadinessProbe local = mock(GameplayLocalPathReadinessProbe.class);
  private final HttpEndpointAvailabilityChecker http = mock(HttpEndpointAvailabilityChecker.class);
  private final GameLogicProperties properties = new GameLogicProperties();

  private GameplayPathReadinessHealthIndicator indicator() {
    return new GameplayPathReadinessHealthIndicator(
        account,
        gameLogic,
        entityManagement,
        local,
        new ReadinessTransitionTracker(new SimpleMeterRegistry()),
        properties,
        http);
  }

  private void healthyPrerequisites() {
    when(account.authenticateForReadiness(anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(
                    ErrorDetail.newBuilder().setCode(AuthenticationErrorCodes.INVALID_CREDENTIALS))
                .build());
    when(local.probeSessionContextStore()).thenReturn(ProbeResult.up("ROUND_TRIP_OK"));
    when(local.probeCommandQueueStore()).thenReturn(ProbeResult.up("QUEUE_WRITE_OK"));
    properties.setReadinessUrl(ENDPOINT);
    when(gameLogic.pingForReadiness())
        .thenReturn(PingResponse.newBuilder().setMessage("pong").build());
    when(http.isHealthy(URI.create(ENDPOINT))).thenReturn(true);
    when(entityManagement.pingForReadiness())
        .thenReturn(
            net.firedevops.firemud.entitymanagement.v1.PingResponse.newBuilder()
                .setMessage("pong")
                .build());
  }

  @ParameterizedTest
  @CsvSource({"true,true", "true,false", "false,true", "false,false"})
  void requiresBothAuthenticatedPingAndDependencyReadiness(boolean ping, boolean readiness) {
    healthyPrerequisites();
    if (!ping) {
      when(gameLogic.pingForReadiness()).thenThrow(Status.UNAVAILABLE.asRuntimeException());
    }
    when(http.isHealthy(URI.create(ENDPOINT))).thenReturn(readiness);

    var health = indicator().health();

    assertEquals(
        ping && readiness
            ? org.springframework.boot.health.contributor.Status.UP
            : org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
        health.getStatus());
    assertEquals("LOGIN->LOOK", health.getDetails().get("contract"));
    @SuppressWarnings("unchecked")
    var dependencies =
        (java.util.Map<String, java.util.Map<String, Object>>)
            health.getDetails().get("dependencies");
    assertEquals(
        ping && readiness
            ? java.util.List.of(
                "accountService",
                "sessionContextStore",
                "commandQueueStore",
                "gameLogicService",
                "entityManagementService")
            : java.util.List.of(
                "accountService", "sessionContextStore", "commandQueueStore", "gameLogicService"),
        java.util.List.copyOf(dependencies.keySet()));
    assertEquals("pingAndReadiness", dependencies.get("gameLogicService").get("check"));
    if (!ping || !readiness) {
      assertEquals("gameLogicService", health.getDetails().get("failingDependency"));
      verifyNoInteractions(entityManagement);
    }
    if (!ping) {
      verifyNoInteractions(http);
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"INVALID_ARGUMENT", "NOT_FOUND", "PERMISSION_DENIED", "DEADLINE_EXCEEDED"})
  void rejectsEveryPingTransportFailure(String code) {
    healthyPrerequisites();
    when(gameLogic.pingForReadiness())
        .thenThrow(Status.fromCode(Status.Code.valueOf(code)).asRuntimeException());
    assertEquals(
        org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
        indicator().health().getStatus());
    verifyNoInteractions(http);
  }

  @Test
  void rejectsPingApplicationErrorEvenWhenMessageIsPong() {
    healthyPrerequisites();
    when(gameLogic.pingForReadiness())
        .thenReturn(
            PingResponse.newBuilder()
                .setMessage("pong")
                .setError(ErrorDetail.newBuilder().setCode("INTERNAL_ERROR"))
                .build());
    assertEquals(
        org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
        indicator().health().getStatus());
    verifyNoInteractions(http);
  }

  @Test
  void rejectsMissingOrUnexpectedPingResponse() {
    healthyPrerequisites();
    for (PingResponse response : new PingResponse[] {null, PingResponse.getDefaultInstance()}) {
      when(gameLogic.pingForReadiness()).thenReturn(response);
      assertEquals(
          org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
          indicator().health().getStatus());
    }
    verifyNoInteractions(http);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "http://bad host/actuator/health/readiness",
        "http://game-logic-service:8080/ping",
        "ftp://game-logic-service/actuator/health/readiness",
        "http://user@game-logic-service/actuator/health/readiness",
        "http://game-logic-service/actuator/health/readiness?probe=1",
        "http://game-logic-service/actuator/health/readiness#x"
      })
  void rejectsUnconfiguredOrMalformedEndpointWithoutHttpRequest(String endpoint) {
    healthyPrerequisites();
    properties.setReadinessUrl(endpoint);
    assertEquals(
        org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
        indicator().health().getStatus());
    verifyNoInteractions(http);
  }

  @ParameterizedTest
  @ValueSource(ints = {200, 401, 503})
  void actualHttpStatusIsRequiredAlongsidePing(int status) throws java.io.IOException {
    healthyPrerequisites();
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/actuator/health/readiness",
        exchange -> {
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
        });
    server.start();
    try {
      properties.setReadinessUrl(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health/readiness");
      var indicator =
          new GameplayPathReadinessHealthIndicator(
              account,
              gameLogic,
              entityManagement,
              local,
              new ReadinessTransitionTracker(new SimpleMeterRegistry()),
              properties,
              new HttpEndpointAvailabilityChecker());
      assertEquals(
          status == 200
              ? org.springframework.boot.health.contributor.Status.UP
              : org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
          indicator.health().getStatus());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void boundedHttpTimeoutFailsReadiness() throws java.io.IOException {
    healthyPrerequisites();
    var release = new java.util.concurrent.CountDownLatch(1);
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/actuator/health/readiness",
        exchange -> {
          try {
            release.await(4, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      properties.setReadinessUrl(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/actuator/health/readiness");
      var indicator =
          new GameplayPathReadinessHealthIndicator(
              account,
              gameLogic,
              entityManagement,
              local,
              new ReadinessTransitionTracker(new SimpleMeterRegistry()),
              properties,
              new HttpEndpointAvailabilityChecker());
      assertEquals(
          org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
          indicator.health().getStatus());
    } finally {
      release.countDown();
      server.stop(0);
    }
  }

  @Test
  void rejectsHttpCheckerFailure() {
    healthyPrerequisites();
    when(http.isHealthy(any())).thenThrow(new IllegalArgumentException("invalid endpoint"));
    assertEquals(
        org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE,
        indicator().health().getStatus());
  }

  @Test
  void preservesAccountUnavailableAndExceptionDenials() {
    healthyPrerequisites();
    when(account.authenticateForReadiness(anyString(), anyString()))
        .thenReturn(
            AuthenticateResponse.newBuilder()
                .setError(ErrorDetail.newBuilder().setCode(AuthenticationErrorCodes.UNAVAILABLE))
                .build());
    assertEquals("accountService", indicator().health().getDetails().get("failingDependency"));
    when(account.authenticateForReadiness(anyString(), anyString()))
        .thenThrow(new IllegalStateException("account down"));
    assertEquals("accountService", indicator().health().getDetails().get("failingDependency"));
    verifyNoInteractions(gameLogic, http, entityManagement);
  }

  @ParameterizedTest
  @ValueSource(strings = {"sessionContextStore", "commandQueueStore"})
  void preservesLocalStoreDenials(String dependency) {
    healthyPrerequisites();
    if ("sessionContextStore".equals(dependency)) {
      when(local.probeSessionContextStore()).thenReturn(ProbeResult.down("redis down"));
    } else {
      when(local.probeCommandQueueStore()).thenReturn(ProbeResult.down("queue down"));
    }
    assertEquals(dependency, indicator().health().getDetails().get("failingDependency"));
    verifyNoInteractions(gameLogic, http, entityManagement);
  }

  @Test
  void directEntityRefusalBlocksHealthyGameLogicAndRecoversOnSameIndicator() {
    healthyPrerequisites();
    when(entityManagement.pingForReadiness())
        .thenThrow(Status.UNAVAILABLE.asRuntimeException())
        .thenReturn(
            net.firedevops.firemud.entitymanagement.v1.PingResponse.newBuilder()
                .setMessage("pong")
                .build());
    var registry = new SimpleMeterRegistry();
    try {
      var indicator =
          new GameplayPathReadinessHealthIndicator(
              account,
              gameLogic,
              entityManagement,
              local,
              new ReadinessTransitionTracker(registry),
              properties,
              http);

      assertEntityUnavailable(indicator.health());
      assertEquals(
          0.0,
          registry
              .get("firemud.readiness.current")
              .tag("component", "game-session-service")
              .gauge()
              .value());
      var recovered = indicator.health();
      assertEquals(org.springframework.boot.health.contributor.Status.UP, recovered.getStatus());
      assertThat(recovered.getDetails()).doesNotContainKey("failingDependency");
      assertEquals(
          1.0,
          registry
              .get("firemud.readiness.current")
              .tag("component", "game-session-service")
              .gauge()
              .value());
      assertEquals(
          1.0,
          registry
              .get("firemud.readiness.transitions")
              .tags(
                  "component",
                  "game-session-service",
                  "to_status",
                  "OUT_OF_SERVICE",
                  "failing_dependency",
                  "entityManagementService")
              .counter()
              .count());
      assertEquals(
          1.0,
          registry
              .get("firemud.readiness.transitions")
              .tags(
                  "component",
                  "game-session-service",
                  "to_status",
                  "UP",
                  "failing_dependency",
                  "none")
              .counter()
              .count());
    } finally {
      registry.close();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"UNAVAILABLE", "DEADLINE_EXCEEDED", "UNAUTHENTICATED", "PERMISSION_DENIED"})
  void rejectsEntityTransportTimeoutAndAuthenticationFailures(String code) {
    healthyPrerequisites();
    when(entityManagement.pingForReadiness())
        .thenThrow(Status.fromCode(Status.Code.valueOf(code)).asRuntimeException());

    assertEntityUnavailable(indicator().health());
  }

  @Test
  void rejectsMissingMalformedAndApplicationErrorEntityPong() {
    healthyPrerequisites();
    for (var response :
        new net.firedevops.firemud.entitymanagement.v1.PingResponse[] {
          null,
          net.firedevops.firemud.entitymanagement.v1.PingResponse.getDefaultInstance(),
          net.firedevops.firemud.entitymanagement.v1.PingResponse.newBuilder()
              .setMessage("not-pong")
              .build(),
          net.firedevops.firemud.entitymanagement.v1.PingResponse.newBuilder()
              .setMessage("pong")
              .setError(
                  ErrorDetail.newBuilder().setCode("INTERNAL_ERROR").setMessage("private detail"))
              .build()
        }) {
      when(entityManagement.pingForReadiness()).thenReturn(response);
      var health = indicator().health();
      assertEntityUnavailable(health);
      assertThat(health.getDetails().toString()).doesNotContain("private detail");
    }
  }

  private static void assertEntityUnavailable(
      org.springframework.boot.health.contributor.Health health) {
    assertEquals(
        org.springframework.boot.health.contributor.Status.OUT_OF_SERVICE, health.getStatus());
    assertEquals("entityManagementService", health.getDetails().get("failingDependency"));
    assertEquals("LOGIN->LOOK", health.getDetails().get("contract"));
    @SuppressWarnings("unchecked")
    var dependencies =
        (java.util.Map<String, java.util.Map<String, Object>>)
            health.getDetails().get("dependencies");
    assertEquals("UP", dependencies.get("accountService").get("status"));
    assertEquals("UP", dependencies.get("sessionContextStore").get("status"));
    assertEquals("UP", dependencies.get("commandQueueStore").get("status"));
    assertEquals("UP", dependencies.get("gameLogicService").get("status"));
    assertEquals("DOWN", dependencies.get("entityManagementService").get("status"));
    assertEquals("ping", dependencies.get("entityManagementService").get("check"));
    assertEquals(
        "grpc:EntityManagementService#Ping",
        dependencies.get("entityManagementService").get("target"));
  }
}
