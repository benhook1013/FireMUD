package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginCleanupOwner;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginOwner;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/** Process-local custody proof with controlled Account result/cleanup sources, not admission. */
class AuthenticatedDirectTextSessionsTest {
  private final AuthenticatedDirectTextSessions sessions = new AuthenticatedDirectTextSessions();

  @ParameterizedTest
  @CsvSource({
    "AUTH_INVALID_CREDENTIALS, INVALID_CREDENTIALS",
    "AUTH_ACCOUNT_LOCKED, ACCOUNT_LOCKED",
    "AUTH_RETRY_LATER, RETRY_LATER",
    "IDEMPOTENCY_CONFLICT, IDEMPOTENCY_CONFLICT",
    "RESPONSE_RECOVERY_EXPIRED, RESPONSE_RECOVERY_EXPIRED",
    "AUTH_UNAVAILABLE, UNAVAILABLE",
    "CUSTODY_LOST, UNAVAILABLE",
    "LOGIN_UNAVAILABLE, UNAVAILABLE",
    "DEADLINE_EXCEEDED, UNAVAILABLE",
    "untrusted-owner-diagnostic, UNAVAILABLE",
    ", UNAVAILABLE"
  })
  void rejectedLoginPreservesOnlyBoundedDecisionsThroughTheReceiver(
      String ownerCode, String publicCode) throws Exception {
    var socket = socket();
    sessions.open(socket);
    var outcome = mock(InitialGameplayLoginOwner.Outcome.class);
    when(outcome.status()).thenReturn(InitialGameplayLoginOwner.Status.CLEANUP_ACCOUNT_NO_TOKEN);
    when(outcome.failureCode()).thenReturn(Optional.ofNullable(ownerCode));
    var owner = mock(InitialGameplayLoginOwner.class);
    when(owner.receive(any())).thenReturn(outcome);
    var beans = new StaticListableBeanFactory(Map.of("login", owner));
    var receiver =
        new AuthenticatedDirectTextCommandReceiver(
            sessions,
            beans.getBeanProvider(InitialGameplayLoginOwner.class),
            beans.getBeanProvider(DirectTextWorldSelectionService.class),
            beans.getBeanProvider(DirectTextAuthenticatedLobbyOwner.class));

    var result =
        receiver.receive(
            socket,
            new TextCommand(
                TextCommandType.LOGIN,
                List.of("player@example.test", "transient-secret"),
                "LOGIN player@example.test transient-secret"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo(publicCode);
    assertThat(result.commandResult().errorMessage())
        .doesNotContain("transient-secret", "untrusted-owner-diagnostic");
    if ("UNAVAILABLE".equals(publicCode)) {
      assertThat(result.commandResult().errorMessage())
          .isEqualTo("Authenticated gameplay authority unavailable");
    }
    verify(outcome, never()).withOwnedPrivateResult(any());
    var laterBorrow =
        receiver.receive(
            socket, new TextCommand(TextCommandType.REALMS, List.of("demo"), "REALMS demo"));
    assertThat(laterBorrow.commandResult().errorCode()).isEqualTo("LOGIN_REQUIRED");
    verify(outcome).close();
    sessions.close(socket, CloseStatus.NORMAL);
  }

  @ParameterizedTest
  @EnumSource(
      value = TextCommandType.class,
      names = {"REALMS", "JOIN"})
  void missingLoginIsActionableButClosedCustodyRemainsUnavailable(TextCommandType type)
      throws Exception {
    var socket = socket();
    sessions.open(socket);
    var beans = new StaticListableBeanFactory();
    var receiver =
        new AuthenticatedDirectTextCommandReceiver(
            sessions,
            beans.getBeanProvider(InitialGameplayLoginOwner.class),
            beans.getBeanProvider(DirectTextWorldSelectionService.class),
            beans.getBeanProvider(DirectTextAuthenticatedLobbyOwner.class));
    var command = new TextCommand(type, List.of("demo"), type + " demo");

    var missingLogin = receiver.receive(socket, command);

    assertThat(missingLogin.commandResult().errorCode()).isEqualTo("LOGIN_REQUIRED");
    assertThat(missingLogin.commandResult().errorMessage()).contains("LOGIN <email> <password>");
    sessions.close(socket, CloseStatus.NORMAL);

    var closedCustody = receiver.receive(socket, command);

    assertThat(closedCustody.commandResult().errorCode()).isEqualTo("UNAVAILABLE");
    assertThat(closedCustody.commandResult().errorMessage())
        .isEqualTo("Authenticated gameplay authority unavailable");
  }

  @Test
  void rejectedLoginErasesCredentialsAndRetainsCleanupOwnership() throws Exception {
    var socket = socket();
    sessions.open(socket);
    var outcome = mock(InitialGameplayLoginOwner.Outcome.class);
    when(outcome.status()).thenReturn(InitialGameplayLoginOwner.Status.CLEANUP_HELD);
    when(outcome.failureCode()).thenReturn(Optional.of("AUTH_INVALID_CREDENTIALS"));
    var owner = mock(InitialGameplayLoginOwner.class);
    when(owner.receive(any())).thenReturn(outcome);
    char[] secret = "transient-private-secret".toCharArray();

    assertThatThrownBy(() -> sessions.require(socket).login(owner, "player@example.test", secret))
        .isInstanceOf(AuthenticatedDirectTextSessions.LoginRejectedException.class)
        .hasMessage("Authenticated gameplay LOGIN was rejected");

    assertThat(secret).containsOnly('\0');
    verify(outcome).close();
    sessions.close(socket, CloseStatus.NORMAL);
    verify(outcome, org.mockito.Mockito.times(1)).close();
  }

  @Test
  void exactConnectionOwnsIdenticalPrivateResultOnlyInsideBorrow() throws Exception {
    var socket = socket();
    sessions.open(socket);
    var fixture = fixture();
    var connection = sessions.require(socket);
    char[] secret = "transient-private-secret".toCharArray();
    connection.login(fixture.owner(), "player@example.test", secret);
    assertThat(secret).containsOnly('\0');
    assertThat(
            connection.<Boolean>withLogin(
                (original, result) -> {
                  assertThat(result).isSameAs(fixture.result());
                  assertThatThrownBy(() -> sessions.close(socket, CloseStatus.NORMAL))
                      .isInstanceOf(IllegalStateException.class);
                  assertThatThrownBy(sessions::closeAll).isInstanceOf(IllegalStateException.class);
                  assertThat(sessions.require(socket)).isSameAs(connection);
                  return connection.withCurrent(
                      original, result, transport -> transport.transportSessionId().equals("42"));
                }))
        .isTrue();
    assertThatThrownBy(
            () -> connection.withCurrent(fixture.original(), fixture.result(), transport -> true))
        .isInstanceOf(IllegalStateException.class);
    sessions.close(socket, CloseStatus.NORMAL);
    verify(fixture.cleanup()).abandonAndRetire(fixture.original());
    assertThatThrownBy(fixture.result()::compactJwtBytes).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> sessions.require(socket)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void selectionFailuresPropagateThroughCustodyWithoutDiscardingExactLogin() throws Exception {
    var socket = socket();
    sessions.open(socket);
    var fixture = fixture();
    var connection = sessions.require(socket);
    connection.login(fixture.owner(), "player@example.test", "secret".toCharArray());

    var unknown = new DirectTextWorldSelectionService.UnknownWorldSelectorException();
    assertThatThrownBy(
            () ->
                connection.<Boolean>withLogin(
                    (original, result) ->
                        connection.withCurrent(
                            original,
                            result,
                            transport -> {
                              throw unknown;
                            })))
        .isSameAs(unknown);

    var expired = new DirectTextWorldSelectionService.ExpiredWorldSelectionException();
    assertThatThrownBy(
            () ->
                connection.<Boolean>withLogin(
                    (original, result) ->
                        connection.withCurrent(
                            original,
                            result,
                            transport -> {
                              throw expired;
                            })))
        .isSameAs(expired);

    assertThat(
            connection.<Boolean>withLogin(
                (original, result) -> {
                  assertThat(result).isSameAs(fixture.result());
                  return connection.withCurrent(
                      original, result, transport -> transport.transportSessionId().equals("42"));
                }))
        .isTrue();

    sessions.close(socket, CloseStatus.NORMAL);
    verify(fixture.cleanup()).abandonAndRetire(fixture.original());
  }

  @Test
  void disconnectRevokesBeforeDrainingAndCleansOnlyAfterBorrowReturns() throws Exception {
    var socket = socket();
    sessions.open(socket);
    var fixture = fixture();
    var connection = sessions.require(socket);
    connection.login(fixture.owner(), "player@example.test", "secret".toCharArray());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var borrower =
          executor.submit(
              () ->
                  connection.withLogin(
                      (original, result) -> {
                        entered.countDown();
                        await(release);
                        return true;
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var closer = executor.submit(() -> sessions.close(socket, CloseStatus.NORMAL));
      for (int i = 0; i < 1000 && connection.isCurrent(); i++) Thread.sleep(1);
      assertThat(connection.isCurrent()).isFalse();
      assertThat(closer.isDone()).isFalse();
      verify(fixture.cleanup(), never()).abandonAndRetire(any());
      verify(socket, never()).close(any());
      release.countDown();
      assertThatThrownBy(() -> borrower.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      closer.get(5, TimeUnit.SECONDS);
      verify(fixture.cleanup()).abandonAndRetire(fixture.original());
      verify(socket).close(CloseStatus.NORMAL);
    } finally {
      release.countDown();
    }
  }

  @Test
  void replacementCannotActivateBeforeOldGenerationDrainsOrReconstructOldLogin() throws Exception {
    var socket = socket();
    sessions.open(socket);
    var fixture = fixture();
    var connection = sessions.require(socket);
    connection.login(fixture.owner(), "player@example.test", "secret".toCharArray());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var replacement = socket();
    try (var executor = Executors.newFixedThreadPool(2)) {
      var borrower =
          executor.submit(
              () ->
                  connection.withLogin(
                      (original, result) -> {
                        entered.countDown();
                        await(release);
                        return true;
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var replacing = executor.submit(() -> sessions.open(replacement));
      for (int i = 0; i < 1000 && connection.isCurrent(); i++) Thread.sleep(1);
      assertThat(connection.isCurrent()).isFalse();
      assertThatThrownBy(() -> sessions.require(replacement))
          .isInstanceOf(IllegalStateException.class);
      assertThat(replacing.isDone()).isFalse();
      release.countDown();
      assertThatThrownBy(() -> borrower.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      replacing.get(5, TimeUnit.SECONDS);
      var current = sessions.require(replacement);
      assertThatThrownBy(() -> current.withLogin((original, result) -> true))
          .isInstanceOf(IllegalStateException.class);
      verify(socket).close(new CloseStatus(1012, "backend_generation_closed"));
      sessions.close(socket, CloseStatus.NORMAL);
      assertThat(sessions.require(replacement)).isSameAs(current);
      sessions.close(replacement, CloseStatus.NORMAL);
      verify(fixture.cleanup()).abandonAndRetire(fixture.original());
    } finally {
      release.countDown();
    }
  }

  @Test
  void overlappingReplacementsDrainTheWholePredecessorChainBeforeActivation() throws Exception {
    var originalSocket = socket();
    sessions.open(originalSocket);
    var fixture = fixture();
    var originalConnection = sessions.require(originalSocket);
    originalConnection.login(fixture.owner(), "player@example.test", "secret".toCharArray());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var middle = socket();
    var latest = socket();
    try (var executor = Executors.newFixedThreadPool(3)) {
      var borrower =
          executor.submit(
              () ->
                  originalConnection.withLogin(
                      (coordinates, result) -> {
                        entered.countDown();
                        await(release);
                        return true;
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var firstReplacement = executor.submit(() -> sessions.open(middle));
      for (int i = 0; i < 1000 && originalConnection.isCurrent(); i++) Thread.sleep(1);
      assertThat(originalConnection.isCurrent()).isFalse();
      var secondReplacement = executor.submit(() -> sessions.open(latest));
      for (int i = 0;
          i < 1000
              && !latest
                  .getAttributes()
                  .containsKey(AuthenticatedDirectTextSessions.class.getName());
          i++) Thread.sleep(1);
      assertThat(latest.getAttributes())
          .containsKey(AuthenticatedDirectTextSessions.class.getName());
      assertThatThrownBy(() -> sessions.require(latest)).isInstanceOf(IllegalStateException.class);
      assertThat(firstReplacement.isDone()).isFalse();
      assertThat(secondReplacement.isDone()).isFalse();
      verify(fixture.cleanup(), never()).abandonAndRetire(any());
      release.countDown();
      assertThatThrownBy(() -> borrower.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      firstReplacement.get(5, TimeUnit.SECONDS);
      secondReplacement.get(5, TimeUnit.SECONDS);
      var current = sessions.require(latest);
      assertThatThrownBy(() -> sessions.require(middle)).isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> current.withLogin((coordinates, result) -> true))
          .isInstanceOf(IllegalStateException.class);
      sessions.closeAll();
      verify(fixture.cleanup()).abandonAndRetire(fixture.original());
    } finally {
      release.countDown();
    }
  }

  @Test
  void shutdownRevokesEveryGenerationBeforeDrainingAndRefusesNewConnections() throws Exception {
    var first = socket("42");
    var second = socket("43");
    sessions.open(first);
    sessions.open(second);
    var firstFixture = fixture();
    var secondFixture = fixture();
    var firstConnection = sessions.require(first);
    var secondConnection = sessions.require(second);
    firstConnection.login(firstFixture.owner(), "player@example.test", "secret".toCharArray());
    secondConnection.login(secondFixture.owner(), "player@example.test", "secret".toCharArray());
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var borrower =
          executor.submit(
              () ->
                  firstConnection.withLogin(
                      (original, result) -> {
                        entered.countDown();
                        await(release);
                        return true;
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      var shutdown = executor.submit(sessions::closeAll);
      for (int i = 0; i < 1000 && firstConnection.isCurrent(); i++) Thread.sleep(1);
      assertThat(firstConnection.isCurrent()).isFalse();
      assertThat(secondConnection.isCurrent()).isFalse();
      assertThat(shutdown.isDone()).isFalse();
      var newSocket = socket("44");
      assertThatThrownBy(() -> sessions.open(newSocket)).isInstanceOf(IllegalStateException.class);
      assertThat(newSocket.getAttributes())
          .doesNotContainKey(AuthenticatedDirectTextSessions.class.getName());
      release.countDown();
      assertThatThrownBy(() -> borrower.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
      shutdown.get(5, TimeUnit.SECONDS);
      verify(firstFixture.cleanup()).abandonAndRetire(firstFixture.original());
      verify(secondFixture.cleanup()).abandonAndRetire(secondFixture.original());
      verify(first).close(new CloseStatus(1012, "backend_generation_closed"));
      verify(second).close(new CloseStatus(1012, "backend_generation_closed"));
      assertThatThrownBy(() -> sessions.open(newSocket)).isInstanceOf(IllegalStateException.class);
    } finally {
      release.countDown();
    }
  }

  @ParameterizedTest
  @CsvSource({"1000, logout", "1011, internal_error"})
  void callerCloseStatusIsRetainedAfterExactOriginalCleanup(int code, String reason)
      throws Exception {
    var socket = socket();
    sessions.open(socket);
    var fixture = fixture();
    var connection = sessions.require(socket);
    connection.login(fixture.owner(), "player@example.test", "secret".toCharArray());
    var status = new CloseStatus(code, reason);

    sessions.close(socket, status);
    sessions.close(socket, new CloseStatus(1012, "backend_generation_closed"));

    var order = inOrder(fixture.cleanup(), socket);
    order.verify(fixture.cleanup()).abandonAndRetire(fixture.original());
    order.verify(socket).close(status);
    verify(socket, org.mockito.Mockito.times(1)).close(any());
    assertThat(connection.isCurrent()).isFalse();
    assertThatThrownBy(fixture.result()::compactJwtBytes).isInstanceOf(IllegalStateException.class);
  }

  private static WebSocketSession socket() throws Exception {
    return socket("42");
  }

  private static WebSocketSession socket(String sessionId) throws Exception {
    var certificate = mock(X509Certificate.class);
    when(certificate.getSubjectAlternativeNames())
        .thenReturn(List.of(List.of(6, "spiffe://firemud/ns/test/sa/spring-cloud-gateway")));
    when(certificate.getExtendedKeyUsage()).thenReturn(List.of("1.3.6.1.5.5.7.3.2"));
    var request = new MockHttpServletRequest("GET", "/ws/game/tcp");
    request.setSecure(true);
    request.setLocalPort(8444);
    request.setAttribute(
        "jakarta.servlet.request.X509Certificate", new X509Certificate[] {certificate});
    request.addHeader("X-Client-IP", "203.0.113.7");
    request.addHeader("X-Proxy-Connection-Id", "99999999-9999-4999-8999-999999999999");
    request.addHeader("X-Firemud-Connection-Mode", "trusted_tcp_proxy");
    var ingress =
        AuthenticatedDirectTextIngress.authenticate(
            new ServletServerHttpRequest(request), 8444, "test", sessionId);
    var socket = mock(WebSocketSession.class);
    var attributes = new java.util.concurrent.ConcurrentHashMap<String, Object>();
    attributes.put(AuthenticatedDirectTextIngress.ATTRIBUTE, ingress);
    when(socket.getAttributes()).thenReturn(attributes);
    when(socket.isOpen()).thenReturn(true);
    return socket;
  }

  private static Fixture fixture() throws Exception {
    var original =
        new InitialGameplayLoginAttemptCoordinates(UUID.randomUUID(), UUID.randomUUID(), 1);
    var evidence =
        new CanonicalGameplayLoginResult.NonSecretEvidence(
            UUID.randomUUID(),
            original.requestId(),
            original.callerContextId(),
            UUID.randomUUID(),
            "a".repeat(64),
            "game-session-account-delegation/v1",
            1000,
            1000,
            1200,
            "1",
            "3",
            ByteString.EMPTY,
            ByteString.EMPTY,
            ByteString.EMPTY);
    var resultConstructor =
        CanonicalGameplayLoginResult.class.getDeclaredConstructor(
            byte[].class, CanonicalGameplayLoginResult.NonSecretEvidence.class);
    resultConstructor.setAccessible(true);
    var result =
        resultConstructor.newInstance(
            "controlled-private-token".getBytes(StandardCharsets.US_ASCII), evidence);
    var cleanup = mock(InitialGameplayLoginCleanupOwner.class);
    when(cleanup.abandonAndRetire(original))
        .thenReturn(
            new InitialGameplayLoginCleanupOwner.CleanupObservation(
                original,
                InitialGameplayLoginCleanupOwner.Status.HELD,
                Optional.empty(),
                Optional.empty()));
    var installed =
        InitialGameplayLoginOwner.Outcome.class.getDeclaredMethod(
            "installed",
            InitialGameplayLoginAttemptCoordinates.class,
            CanonicalGameplayLoginResult.class,
            InitialGameplayLoginCleanupOwner.class);
    installed.setAccessible(true);
    var outcome =
        (InitialGameplayLoginOwner.Outcome) installed.invoke(null, original, result, cleanup);
    var owner = mock(InitialGameplayLoginOwner.class);
    when(owner.receive(any())).thenReturn(outcome);
    return new Fixture(owner, original, result, cleanup);
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private record Fixture(
      InitialGameplayLoginOwner owner,
      InitialGameplayLoginAttemptCoordinates original,
      CanonicalGameplayLoginResult result,
      InitialGameplayLoginCleanupOwner cleanup) {}
}
