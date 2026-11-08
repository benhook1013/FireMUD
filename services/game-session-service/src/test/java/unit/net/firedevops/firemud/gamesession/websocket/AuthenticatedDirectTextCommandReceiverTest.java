package net.firedevops.firemud.gamesession.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import net.firedevops.firemud.account.v1.JoinPublicProductionMembershipResponse;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
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
  void emptyOrDifferentJoinSelectionNeverCallsAccountLobbyOwner() {
    var fixture = fixture();
    for (var args : List.of(List.<String>of(), List.of("other"), List.of("demo", "production"))) {
      var result =
          fixture.receiver.receive(
              fixture.socket, new TextCommand(TextCommandType.JOIN, args, "JOIN"));
      assertThat(result.commandResult().accepted()).isFalse();
    }
    verifyNoInteractions(fixture.lobby);
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
