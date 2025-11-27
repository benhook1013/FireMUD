package net.firedevops.firemud.command.text;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.client.AccountClient;
import net.firedevops.firemud.config.GameSessionProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.SessionContext;
import net.firedevops.firemud.service.SessionContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Handles LOGIN/LOGON commands and generates immediate responses when possible. */
@Component
public final class LoginCommandHandler {
  private static final Logger logger = LoggerFactory.getLogger(LoginCommandHandler.class);

  private final CommandService commandService;
  private final GameInstanceRepository gameInstanceRepository;
  private final SessionContextService sessionContextService;
  private final AccountClient accountClient;
  private final GameSessionProperties properties;

  @Autowired
  public LoginCommandHandler(
      CommandService commandService,
      GameInstanceRepository gameInstanceRepository,
      SessionContextService sessionContextService,
      AccountClient accountClient,
      GameSessionProperties properties) {
    this.commandService =
        Objects.requireNonNull(commandService, "commandService must not be null");
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
  }

  public LoginCommandHandlingResult handle(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    List<String> args = command.args();
    if (args.size() < 2) {
      CommandEnqueueResult failure =
          CommandEnqueueResult.failure(
              LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE,
              LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_MESSAGE);
      return new LoginCommandHandlingResult(failure, null);
    }

    String otp = args.size() > 2 ? args.get(2) : "";
    AuthenticateResponse authResponse =
        accountClient.authenticate(
            properties.getDefaultTenantId(), args.get(0), args.get(1), otp);
    var error = authResponse.getError();
    if (!error.getCode().isBlank()) {
      return new LoginCommandHandlingResult(
          CommandEnqueueResult.failure(error.getCode(), error.getMessage()), null);
    }

    Optional<LoginResult> loginResult = buildLoginResult(sessionId, authResponse.getAuthToken());

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    if (enqueueResult.accepted()) {
      loginResult.ifPresent(result -> storeSessionContext(sessionId, result));
    }
    return new LoginCommandHandlingResult(enqueueResult, null);
  }

  private void storeSessionContext(String sessionIdText, LoginResult result) {
    if (sessionContextService == null || result == null) {
      return;
    }

    Long sessionId = parseSessionId(sessionIdText);
    if (sessionId == null) {
      return;
    }

    SessionContext context =
        new SessionContext(
            sessionId,
            result.tenantId(),
            result.accountId(),
            result.playerId(),
            result.gameInstanceId(),
            result.jwt());
    sessionContextService.save(context);
    logger.debug("Updated session context for {}:{}", context.tenantId(), context.sessionId());
  }

  private Optional<LoginResult> buildLoginResult(String sessionIdText, String jwt) {
    Long sessionId = parseSessionId(sessionIdText);
    if (sessionId == null) {
      return Optional.empty();
    }
    return gameInstanceRepository
        .findById(sessionId)
        .map(
            instance ->
                new LoginResult(
                    instance.getOwnerAccountId(),
                    instance.getTenantId(),
                    instance.getOwnerAccountId(),
                    instance.getId(),
                    jwt));
  }

  private Long parseSessionId(String sessionIdText) {
    try {
      return Long.parseLong(sessionIdText);
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
