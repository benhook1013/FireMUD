package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.ExpiredWorldSelectionException;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.UnknownWorldSelectorException;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandPayload;
import net.firedevops.firemud.gamesession.command.text.TextCommandType;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.dto.CanonicalGameplayLoginResult;
import net.firedevops.firemud.gamesession.dto.CanonicalPlayableTarget;
import net.firedevops.firemud.gamesession.dto.InitialGameplayLoginAttemptCoordinates;
import net.firedevops.firemud.gamesession.presentation.TextPlayerOutputRenderer;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginOwner;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyOwner;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.socket.WebSocketSession;

/** Wire-selector routing with controlled owners, not source qualification or admission proof. */
class AuthenticatedDirectTextCommandReceiverTest {
  @Test
  void authenticatedLoginWithoutOwnerRefusesBeforeDispatch() {
    var fixture = fixture();
    var command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("player@example.test", "transient-secret"),
            "LOGIN player@example.test transient-secret");
    assertThat(command.credentialsPayload()).isPresent();

    var result = fixture.receiver.receive(fixture.socket, command);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("UNAVAILABLE");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("Authenticated gameplay authority unavailable");
    verifyNoInteractions(fixture.connection, fixture.lobby, fixture.selections);
  }

  @Test
  @SuppressWarnings("unchecked")
  void emailNormalizationRejectionReturnsUsageBeforeProtectedOwnerLookup() {
    var socket = mock(WebSocketSession.class);
    var sessions = mock(AuthenticatedDirectTextSessions.class);
    var connection = mock(AuthenticatedDirectTextSessions.Connection.class);
    when(sessions.require(socket)).thenReturn(connection);
    ObjectProvider<InitialGameplayLoginOwner> logins = mock(ObjectProvider.class);
    ObjectProvider<DirectTextWorldSelectionService> selections = mock(ObjectProvider.class);
    ObjectProvider<DirectTextAuthenticatedLobbyOwner> lobbies = mock(ObjectProvider.class);
    var receiver =
        new AuthenticatedDirectTextCommandReceiver(sessions, logins, selections, lobbies);
    var malformedEmail = " \u0000 ";
    var command =
        new TextCommand(
            TextCommandType.LOGIN,
            List.of(malformedEmail, "secret"),
            "LOGIN malformed secret",
            "LOGIN",
            new TextCommandPayload.Credentials(malformedEmail, "secret"));

    var result = receiver.receive(socket, command);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage()).isEqualTo("Usage: LOGIN <email> <password>");
    verify(logins, never()).getIfAvailable();
    verifyNoInteractions(connection);
  }

  @Test
  void joinUsesOneFriendlyWorldSelectorButDispatchesOnlyTheRetainedScope() {
    var fixture = fixture();
    when(fixture.lobby.join(null, null, fixture.connection))
        .thenReturn(
            new DirectTextAuthenticatedLobbyOwner.Observation(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DirectTextAuthenticatedLobbyOwner.Status.COMPLETED_CURRENT_NON_ADMITTING,
                Optional.of(JoinPublicProductionMembershipResponse.getDefaultInstance())));
    var result =
        fixture.receiver.receive(
            fixture.socket, new TextCommand(TextCommandType.JOIN, List.of("demo"), "JOIN demo"));
    assertThat(result.commandResult().accepted()).isTrue();
    verify(fixture.selections).resolve(fixture.connection.menu, "demo");
    verify(fixture.lobby).join(null, null, fixture.connection);
  }

  @Test
  void unknownRealmSelectorReturnsActionableInvalidArgument() {
    var fixture = fixture();
    when(fixture.lobby.realms(null, null, fixture.connection, fixture.connection.menu, "missing"))
        .thenThrow(new UnknownWorldSelectorException());

    var result =
        fixture.receiver.receive(
            fixture.socket,
            new TextCommand(TextCommandType.REALMS, List.of("missing"), "REALMS missing"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("Unknown or ambiguous world selector. Run WORLDS and choose a listed selector.");
  }

  @Test
  void unknownJoinSelectorReturnsActionableInvalidArgumentBeforeLobbyDispatch() {
    var fixture = fixture();
    when(fixture.selections.resolve(fixture.connection.menu, "missing"))
        .thenThrow(new UnknownWorldSelectorException());

    var result =
        fixture.receiver.receive(
            fixture.socket,
            new TextCommand(TextCommandType.JOIN, List.of("missing"), "JOIN missing"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("Unknown or ambiguous world selector. Run WORLDS and choose a listed selector.");
    verifyNoInteractions(fixture.lobby);
  }

  @Test
  void expiredRealmMenuReturnsActionableInvalidArgumentAfterTheGuardedLobbyCall() {
    var fixture = fixture();
    when(fixture.lobby.realms(null, null, fixture.connection, fixture.connection.menu, "demo"))
        .thenThrow(new ExpiredWorldSelectionException());

    var result =
        fixture.receiver.receive(
            fixture.socket,
            new TextCommand(TextCommandType.REALMS, List.of("demo"), "REALMS demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("World menu expired. Run WORLDS and choose a listed selector again.");
    verify(fixture.lobby).realms(null, null, fixture.connection, fixture.connection.menu, "demo");
  }

  @Test
  void expiredJoinMenuReturnsActionableInvalidArgumentBeforeLobbyDispatch() {
    var fixture = fixture();
    when(fixture.selections.resolve(fixture.connection.menu, "demo"))
        .thenThrow(new ExpiredWorldSelectionException());

    var result =
        fixture.receiver.receive(
            fixture.socket, new TextCommand(TextCommandType.JOIN, List.of("demo"), "JOIN demo"));

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("World menu expired. Run WORLDS and choose a listed selector again.");
    verifyNoInteractions(fixture.lobby);
  }

  @Test
  void refusedJoinReportsTheExactInvalidArgumentOrUnavailableError() {
    var fixture = fixture();
    var empty =
        fixture.receiver.receive(
            fixture.socket, new TextCommand(TextCommandType.JOIN, List.of(), "JOIN"));
    assertThat(empty.commandResult().accepted()).isFalse();
    assertThat(empty.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(empty.commandResult().errorMessage()).isEqualTo("Usage: JOIN <world>");

    var excessive =
        fixture.receiver.receive(
            fixture.socket,
            new TextCommand(TextCommandType.JOIN, List.of("demo", "production"), "JOIN"));
    assertThat(excessive.commandResult().accepted()).isFalse();
    assertThat(excessive.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(excessive.commandResult().errorMessage()).isEqualTo("Usage: JOIN <world>");

    var unresolved = fixture();
    when(unresolved.selections.resolve(unresolved.connection.menu, "demo"))
        .thenReturn(Optional.empty());
    var unresolvedResult =
        unresolved.receiver.receive(
            unresolved.socket, new TextCommand(TextCommandType.JOIN, List.of("demo"), "JOIN demo"));
    assertThat(unresolvedResult.commandResult().accepted()).isFalse();
    assertThat(unresolvedResult.commandResult().errorCode()).isEqualTo("UNAVAILABLE");
    assertThat(unresolvedResult.commandResult().errorMessage())
        .isEqualTo("Authenticated gameplay authority unavailable");
    verifyNoInteractions(unresolved.lobby);
  }

  @Test
  void intakeRefusalPreservesSelectedScopeAndAllowsExistingJoinButNeverPlay() {
    var fixture = fixture();
    var selected = fixture.connection.selectedScope;
    when(fixture.lobby.realms(null, null, fixture.connection, fixture.connection.menu, "demo"))
        .thenThrow(new IllegalStateException("Controlled exhausted intake"));
    var refused = fixture.receiver.receive(fixture.socket,
        new TextCommand(TextCommandType.REALMS, List.of("demo"), "REALMS demo"));
    assertThat(refused.commandResult().accepted()).isFalse();
    assertThat(refused.commandResult().errorCode()).isEqualTo("UNAVAILABLE");
    assertThat(fixture.connection.selectedScope).isSameAs(selected);
    when(fixture.lobby.join(null, null, fixture.connection)).thenReturn(
        new DirectTextAuthenticatedLobbyOwner.Observation(UUID.randomUUID(), UUID.randomUUID(),
            DirectTextAuthenticatedLobbyOwner.Status.COMPLETED_CURRENT_NON_ADMITTING,
            Optional.of(JoinPublicProductionMembershipResponse.getDefaultInstance())));
    assertThat(fixture.receiver.receive(fixture.socket,
        new TextCommand(TextCommandType.JOIN, List.of("demo"), "JOIN demo")).commandResult().accepted()).isTrue();
    var play = fixture.receiver.receive(fixture.socket,
        new TextCommand(TextCommandType.PLAY, List.of("demo"), "PLAY demo"));
    assertThat(play.commandResult().errorCode()).isEqualTo("ADMISSION_UNAVAILABLE");
  }

  @Test
  void loginAndLobbyObservationsNeverSubstituteForCanonicalPlayAdmission() {
    var fixture = fixture();
    var command =
        new TextCommand(
            TextCommandType.PLAY,
            List.of("demo", "production", "hero"),
            "PLAY demo production hero");
    var result = fixture.receiver.receive(fixture.socket, command);
    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("ADMISSION_UNAVAILABLE");
    var wireResponse =
        new TextPlayerOutputRenderer(new PresentationProperties())
            .renderAll(command, result.commandResult(), result.outputs());
    assertThat(wireResponse)
        .isEqualTo("ERROR ADMISSION_UNAVAILABLE Canonical gameplay admission unavailable")
        .doesNotContain("ENTITLEMENT_UNAVAILABLE");
    verifyNoInteractions(fixture.lobby, fixture.selections);
  }

  @ParameterizedTest
  @MethodSource("malformedCommands")
  void malformedCommandsReturnUsageBeforeOwnerOrLoginDispatch(TextCommand command) {
    var fixture = fixture();

    var result = fixture.receiver.receive(fixture.socket, command);

    assertThat(result.commandResult().accepted()).isFalse();
    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage()).startsWith("Usage: " + command.type());
    assertThat(
            new TextPlayerOutputRenderer(new PresentationProperties())
                .renderAll(command, result.commandResult(), result.outputs()))
        .startsWith("ERROR INVALID_ARGUMENT Usage: " + command.type());
    verifyNoInteractions(fixture.connection, fixture.lobby, fixture.selections);
  }

  private static Stream<TextCommand> malformedCommands() {
    return Stream.of(
        new TextCommand(TextCommandType.LOGIN, List.of(), "LOGIN"),
        new TextCommand(TextCommandType.LOGIN, List.of("player@example.test"), "LOGIN"),
        new TextCommand(
            TextCommandType.LOGIN, List.of("player@example.test", "secret", "extra"), "LOGIN"),
        new TextCommand(TextCommandType.LOGIN, List.of(" ", "secret"), "LOGIN"),
        new TextCommand(TextCommandType.LOGIN, List.of("player@example.test", ""), "LOGIN"),
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("player@example.test", "secret"),
            "LOGIN",
            "LOGIN",
            new TextCommandPayload.Credentials(null, "secret")),
        new TextCommand(
            TextCommandType.LOGIN,
            List.of("player@example.test", "secret"),
            "LOGIN",
            "LOGIN",
            new TextCommandPayload.Credentials("player@example.test", null)),
        new TextCommand(TextCommandType.REALMS, List.of(), "REALMS"),
        new TextCommand(TextCommandType.REALMS, List.of("demo", "extra"), "REALMS"),
        new TextCommand(TextCommandType.REALMS, List.of(" "), "REALMS"),
        new TextCommand(TextCommandType.JOIN, List.of(), "JOIN"),
        new TextCommand(TextCommandType.JOIN, List.of("demo", "extra"), "JOIN"),
        new TextCommand(TextCommandType.JOIN, List.of(" "), "JOIN"));
  }

  @Test
  void realmsBeforeWorldsExplainsTheRequiredLocalSequence() {
    var fixture = fixture();
    fixture.connection.menu = null;

    var result =
        fixture.receiver.receive(
            fixture.socket,
            new TextCommand(TextCommandType.REALMS, List.of("demo"), "REALMS demo"));

    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage()).isEqualTo("Run WORLDS before REALMS <world>");
    verifyNoInteractions(fixture.lobby, fixture.selections);
  }

  @Test
  void joinBeforeRealmsExplainsTheRequiredLocalSequence() {
    var fixture = fixture();
    fixture.connection.selectedScope = null;

    var result =
        fixture.receiver.receive(
            fixture.socket, new TextCommand(TextCommandType.JOIN, List.of("demo"), "JOIN demo"));

    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage())
        .isEqualTo("Run WORLDS and REALMS <world> before JOIN <world>");
    verifyNoInteractions(fixture.lobby, fixture.selections);
  }

  @Test
  void differentResolvedWorldRequiresItsOwnRealmsSelection() {
    var fixture = fixture();
    when(fixture.selections.resolve(fixture.connection.menu, "other"))
        .thenReturn(Optional.of(mock(CanonicalPlayableTarget.class)));

    var result =
        fixture.receiver.receive(
            fixture.socket, new TextCommand(TextCommandType.JOIN, List.of("other"), "JOIN other"));

    assertThat(result.commandResult().errorCode()).isEqualTo("INVALID_ARGUMENT");
    assertThat(result.commandResult().errorMessage()).contains("REALMS <world>");
    verifyNoInteractions(fixture.lobby);
  }

  @Test
  void protectedOwnerAndSelectionFailuresRemainUnavailableRatherThanUsageErrors() {
    for (RuntimeException failure :
        List.of(
            new IllegalArgumentException("private-source"),
            new IllegalStateException("private-custody"))) {
      var realms = fixture();
      when(realms.lobby.realms(null, null, realms.connection, realms.connection.menu, "demo"))
          .thenThrow(failure);
      var realmResult =
          realms.receiver.receive(
              realms.socket,
              new TextCommand(TextCommandType.REALMS, List.of("demo"), "REALMS demo"));
      assertThat(realmResult.commandResult().errorCode()).isEqualTo("UNAVAILABLE");
      assertThat(realmResult.commandResult().errorMessage())
          .isEqualTo("Authenticated gameplay authority unavailable");

      var join = fixture();
      when(join.selections.resolve(join.connection.menu, "demo")).thenThrow(failure);
      var joinResult =
          join.receiver.receive(
              join.socket, new TextCommand(TextCommandType.JOIN, List.of("demo"), "JOIN demo"));
      assertThat(joinResult.commandResult().errorCode()).isEqualTo("UNAVAILABLE");
      assertThat(joinResult.commandResult().errorMessage())
          .isEqualTo("Authenticated gameplay authority unavailable");
      verifyNoInteractions(join.lobby);
    }
  }

  private Fixture fixture() {
    var socket = mock(WebSocketSession.class);
    var sessions = mock(AuthenticatedDirectTextSessions.class);
    var connection = mock(AuthenticatedDirectTextSessions.Connection.class);
    when(sessions.require(socket)).thenReturn(connection);
    when(connection.withLogin(any()))
        .thenAnswer(
            invocation -> {
              BiFunction<
                      InitialGameplayLoginAttemptCoordinates, CanonicalGameplayLoginResult, Object>
                  action = invocation.getArgument(0);
              return action.apply(null, null);
            });
    connection.menu = mock(DirectTextWorldSelectionService.SelectionSnapshot.class);
    connection.selectedScope = mock(DirectTextAuthenticatedLobbyScope.class);
    var target = mock(CanonicalPlayableTarget.class);
    when(connection.selectedScope.publicTarget()).thenReturn(target);
    var selections = mock(DirectTextWorldSelectionService.class);
    when(selections.resolve(connection.menu, "demo")).thenReturn(Optional.of(target));
    var lobby = mock(DirectTextAuthenticatedLobbyOwner.class);
    var beans = new StaticListableBeanFactory(Map.of("lobby", lobby, "selections", selections));
    var receiver =
        new AuthenticatedDirectTextCommandReceiver(
            sessions,
            beans.getBeanProvider(InitialGameplayLoginOwner.class),
            beans.getBeanProvider(DirectTextWorldSelectionService.class),
            beans.getBeanProvider(DirectTextAuthenticatedLobbyOwner.class));
    return new Fixture(socket, connection, selections, lobby, receiver);
  }

  private record Fixture(
      WebSocketSession socket,
      AuthenticatedDirectTextSessions.Connection connection,
      DirectTextWorldSelectionService selections,
      DirectTextAuthenticatedLobbyOwner lobby,
      AuthenticatedDirectTextCommandReceiver receiver) {}
}
