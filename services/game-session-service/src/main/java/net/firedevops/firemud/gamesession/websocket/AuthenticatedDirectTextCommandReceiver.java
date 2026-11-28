package net.firedevops.firemud.gamesession.websocket;

import java.util.List;
import java.util.Map;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.ExpiredWorldSelectionException;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.StaleWorldSelectionException;
import net.firedevops.firemud.gamesession.command.text.DirectTextWorldSelectionService.UnknownWorldSelectorException;
import net.firedevops.firemud.gamesession.command.text.GameplayWorldCatalog.AuthorityPointerUnavailableException;
import net.firedevops.firemud.gamesession.command.text.TextCommand;
import net.firedevops.firemud.gamesession.command.text.TextCommandInterpretationResult;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.BriefRenderPolicy;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.presentation.PlayerOutputKind;
import net.firedevops.firemud.gamesession.presentation.ReplayPolicy;
import net.firedevops.firemud.gamesession.presentation.TextMessageOutput;
import net.firedevops.firemud.gamesession.repository.PreV1CanonicalPlayAdmissionOwner;
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
  private final ObjectProvider<PreV1CanonicalPlayAdmissionOwner> admissions;

  public AuthenticatedDirectTextCommandReceiver(
      AuthenticatedDirectTextSessions sessions,
      ObjectProvider<InitialGameplayLoginOwner> logins,
      ObjectProvider<DirectTextWorldSelectionService> selections,
      ObjectProvider<DirectTextAuthenticatedLobbyOwner> lobbies,
      ObjectProvider<PreV1CanonicalPlayAdmissionOwner> admissions) {
    this.sessions = sessions;
    this.logins = logins;
    this.selections = selections;
    this.lobbies = lobbies;
    this.admissions = admissions;
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
                var realm = connection.selectedScope.publicTarget();
                return success(realm.realmDisplayName() + " (" + realm.realmSlug() + ")");
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
                return new TextCommandInterpretationResult(
                    CommandEnqueueResult.success(),
                    List.of(
                        new PlayerOutput(
                            PlayerOutputKind.MESSAGE,
                            new TextMessageOutput(
                                "Joined public production realm",
                                "message.gameplay.received",
                                Map.of()),
                            ReplayPolicy.NO_REPLAY,
                            BriefRenderPolicy.ALWAYS_SHOW)));
              });
        }
        case HELP -> joinHelp(command);
        case PLAY -> play(connection, command);
        case LOOK -> look(connection, command);
        case LOGOUT -> success("Logged out");
        default -> failure("FEATURE_UNAVAILABLE", "Command unavailable");
      };
    } catch (AuthenticatedDirectTextSessions.LoginRequiredException missingLogin) {
      return failure("LOGIN_REQUIRED", "Use LOGIN <email> <password> to authenticate");
    } catch (ExpiredWorldSelectionException expiredSelection) {
      return failure(
          "INVALID_ARGUMENT", "World menu expired. Run WORLDS and choose a listed selector again.");
    } catch (StaleWorldSelectionException staleSelection) {
      return failure(
          "SELECTION_STALE", "World selection changed. Run WORLDS, then REALMS <world> again.");
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
    } catch (AuthorityPointerUnavailableException unavailable) {
      return unavailable.authorityUnavailable()
          ? failure("AUTH_UNAVAILABLE", "Realm routing authority is temporarily unavailable.")
          : failure("ADMISSION_POINTER_UNAVAILABLE", "Realm routing evidence is unavailable.");
    } catch (RuntimeException unavailable) {
      return failure("UNAVAILABLE", "Authenticated gameplay authority unavailable");
    }
  }

  private TextCommandInterpretationResult play(
      AuthenticatedDirectTextSessions.Connection connection, TextCommand command) {
    var owner = admissions.getIfAvailable();
    if (owner == null || !owner.ready()) return admissionUnavailable();
    var request = command.playRequestPayload().orElse(null);
    if (request == null
        || request.worldSelector() == null
        || request.worldSelector().isBlank()
        || request.characterSelector() != null) {
      return failure("INVALID_ARGUMENT", "Usage: PLAY <world> [realm]");
    }
    return connection.withLogin(
        (original, login) -> {
          var scope = connection.selectedScope;
          if (connection.menu == null || scope == null) {
            return failure("INVALID_ARGUMENT", "Run WORLDS and REALMS <world> before PLAY <world>");
          }
          var selected =
              required(selections).resolve(connection.menu, request.worldSelector()).orElseThrow();
          if (!selected.equals(scope.publicTarget())
              || request.realmSelector() != null
                  && !request.realmSelector().equalsIgnoreCase(selected.realmSlug())) {
            return failure("INVALID_ARGUMENT", "Select the current world and realm before PLAY");
          }
          return required(lobbies)
              .withCurrentScope(
                  original,
                  login,
                  connection,
                  scope,
                  transport -> {
                    if (!connection.beginPlay()) return admissionUnavailable();
                    try {
                      connection.admitted =
                          owner.play(
                              scope,
                              original,
                              login,
                              transport,
                              () -> connection.isCurrent() && connection.selectedScope == scope);
                      return success("Entered world: " + selected.worldSlug());
                    } catch (IllegalStateException unavailable) {
                      return admissionUnavailable();
                    }
                  });
        });
  }

  private TextCommandInterpretationResult look(
      AuthenticatedDirectTextSessions.Connection connection, TextCommand command) {
    if (!command.args().isEmpty()) return failure("INVALID_ARGUMENT", "Usage: LOOK");
    return connection.withLogin(
        (original, login) -> {
          var admitted = connection.admitted;
          if (admitted == null) return failure("PLAY_REQUIRED", "Use PLAY to enter a world");
          if (!connection.isCurrent()) return admissionUnavailable();
          var owner = admissions.getIfAvailable();
          if (owner == null) return admissionUnavailable();
          try {
            var result = owner.look(admitted);
            if (!connection.isCurrent() || connection.admitted != admitted) {
              return admissionUnavailable();
            }
            return new TextCommandInterpretationResult(
                CommandEnqueueResult.success(), List.of(result));
          } catch (PreV1CanonicalPlayAdmissionOwner.LookAdmissionUnavailableException unavailable) {
            return admissionUnavailable();
          } catch (RuntimeException unavailable) {
            return failure("ROOM_UNAVAILABLE", "The current room is unavailable");
          }
        });
  }

  private static TextCommandInterpretationResult joinHelp(TextCommand command) {
    if (command.args().size() != 1 || !"JOIN".equalsIgnoreCase(command.args().getFirst())) {
      return failure("FEATURE_UNAVAILABLE", "Command unavailable");
    }
    return success(
        "JOIN <world>\n"
            + "After LOGIN, run WORLDS and REALMS <world>.\n"
            + "Use JOIN <world> when the selected public-production realm requires membership"
            + " before PLAY.");
  }

  private static TextCommandInterpretationResult admissionUnavailable() {
    return failure("ADMISSION_UNAVAILABLE", "Canonical gameplay admission unavailable");
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
