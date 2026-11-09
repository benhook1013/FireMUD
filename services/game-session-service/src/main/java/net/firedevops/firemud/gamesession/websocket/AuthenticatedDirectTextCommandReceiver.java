package net.firedevops.firemud.gamesession.websocket;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.UnknownWorldSelectorException;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.InitialGameplayLoginOwner;
import net.firedevops.firemud.gamesession.service.directtext.DirectTextAuthenticatedLobbyOwner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/** Public receiving path. An unavailable protected owner never falls back to numeric authority. */
@Component
public final class AuthenticatedDirectTextCommandReceiver {
  private final AuthenticatedDirectTextSessions sessions;
  private final ObjectProvider<InitialGameplayLoginOwner> logins;
  private final ObjectProvider<DirectTextWorldSelectionService> selections;
  private final ObjectProvider<DirectTextAuthenticatedLobbyOwner> lobbies;

  public AuthenticatedDirectTextCommandReceiver(
      AuthenticatedDirectTextSessions sessions,
      ObjectProvider<InitialGameplayLoginOwner> logins,
      ObjectProvider<DirectTextWorldSelectionService> selections,
      ObjectProvider<DirectTextAuthenticatedLobbyOwner> lobbies) {
    this.sessions = sessions;
    this.logins = logins;
    this.selections = selections;
    this.lobbies = lobbies;
  }

  public TextCommandInterpretationResult receive(WebSocketSession socket, TextCommand command) {
    try {
      var connection = sessions.require(socket);
      return switch (command.type()) {
        case LOGIN -> {
          var credentials = command.credentialsPayload().orElse(null);
          if (command.args().size() != 2
              || credentials == null
              || credentials.loginName() == null
              || credentials.loginName().isBlank()
              || credentials.password() == null
              || credentials.password().isEmpty())
            yield failure("INVALID_ARGUMENT", "Usage: LOGIN <email> <password>");
          String email;
          try {
            email = EmailCanonicalization.normalize(credentials.loginName());
          } catch (IllegalArgumentException invalidEmail) {
            yield failure("INVALID_ARGUMENT", "Usage: LOGIN <email> <password>");
          }
          var owner = required(logins);
          connection.login(owner, email, credentials.password().toCharArray());
          yield connection.withLogin((original, result) -> success("Logged in as " + email));
        }
        case WORLDS -> {
          connection.menu = required(selections).browse().orElseThrow();
          yield success(String.join("\n", connection.menu.selectors()));
        }
        case REALMS -> {
          var request = command.realmBrowsePayload().orElse(null);
          if (command.args().size() != 1
              || request == null
              || request.worldSelector() == null
              || request.worldSelector().isBlank())
            yield failure("INVALID_ARGUMENT", "Usage: REALMS <world>");
          yield connection.withLogin(
              (original, result) -> {
                if (connection.menu == null)
                  return failure("INVALID_ARGUMENT", "Run WORLDS before REALMS <world>");
                connection.selectedScope =
                    required(lobbies)
                        .realms(
                            original, result, connection, connection.menu, request.worldSelector());
                return success(connection.selectedScope.publicTarget().realmDisplayName());
              });
        }
        case JOIN -> {
          var request = command.joinRequestPayload().orElse(null);
          if (command.args().size() != 1
              || request == null
              || request.worldSelector() == null
              || request.worldSelector().isBlank())
            yield failure("INVALID_ARGUMENT", "Usage: JOIN <world>");
          yield connection.withLogin(
              (original, result) -> {
                if (connection.menu == null || connection.selectedScope == null)
                  return failure(
                      "INVALID_ARGUMENT", "Run WORLDS and REALMS <world> before JOIN <world>");
                var selected =
                    required(selections)
                        .resolve(connection.menu, request.worldSelector())
                        .orElseThrow();
                if (!selected.equals(connection.selectedScope.publicTarget()))
                  return failure(
                      "INVALID_ARGUMENT", "Use REALMS <world> before JOIN of a different world");
                var observation = required(lobbies).join(original, result, connection);
                if (observation.status()
                        != DirectTextAuthenticatedLobbyOwner.Status.COMPLETED_CURRENT_NON_ADMITTING
                    || observation.currentReply().isEmpty()) throw new IllegalStateException();
                return success("Joined public production realm");
              });
        }
        // LOGIN and JOIN are not admission. Until the canonical binding owner is qualified, the
        // retired numeric entitlement route must never be selected by this protected receiver.
        case PLAY -> failure("ADMISSION_UNAVAILABLE", "Canonical gameplay admission unavailable");
        case LOGOUT -> success("Logged out");
        default -> failure("FEATURE_UNAVAILABLE", "Command unavailable");
      };
    } catch (AuthenticatedDirectTextSessions.LoginRequiredException missingLogin) {
      return failure("LOGIN_REQUIRED", "Use LOGIN <email> <password> before REALMS or JOIN");
    } catch (UnknownWorldSelectorException unknownSelector) {
      return failure(
          "INVALID_ARGUMENT",
          "Unknown or ambiguous world selector. Run WORLDS and choose a listed selector.");
    } catch (AuthenticatedDirectTextSessions.LoginRejectedException rejected) {
      return switch (rejected.code()) {
        case AuthenticationErrorCodes.INVALID_CREDENTIALS ->
            failure("INVALID_CREDENTIALS", "Invalid credentials");
        case AuthenticationErrorCodes.ACCOUNT_LOCKED ->
            failure("ACCOUNT_LOCKED", "Account is locked");
        case AuthenticationErrorCodes.RETRY_LATER ->
            failure("RETRY_LATER", "Too many failed attempts; try again later");
        case "IDEMPOTENCY_CONFLICT", "RESPONSE_RECOVERY_EXPIRED" ->
            failure(rejected.code(), "Login rejected");
        default -> failure("UNAVAILABLE", "Authenticated gameplay authority unavailable");
      };
    } catch (RuntimeException unavailable) {
      return failure("UNAVAILABLE", "Authenticated gameplay authority unavailable");
    }
  }

  private static <T> T required(ObjectProvider<T> provider) {
    T value = provider.getIfAvailable();
    if (value == null) throw new IllegalStateException("Protected owner unavailable");
    return value;
  }

  private static TextCommandInterpretationResult success(String message) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.success(),
        List.of(PlayerOutput.message(message, "message.gameplay.received")));
  }

  private static TextCommandInterpretationResult failure(String code, String message) {
    return new TextCommandInterpretationResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, "error.gameplay.unavailable", Map.of())));
  }
}
