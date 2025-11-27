package net.firedevops.firemud.command.text;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.client.AccountClient;
import net.firedevops.firemud.config.LogOnlyProperties;
import net.firedevops.firemud.dto.CommandEnqueueResult;
import net.firedevops.firemud.entity.GameInstance;
import net.firedevops.firemud.repository.GameInstanceRepository;
import net.firedevops.firemud.service.CommandService;
import net.firedevops.firemud.service.SessionContext;
import net.firedevops.firemud.service.SessionContextService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
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
  private final LogOnlyProperties logOnlyProperties;
  private final Counter takeoverCounter;
  private final Counter resumeCounter;

  @Autowired
  public LoginCommandHandler(
      CommandService commandService,
      GameInstanceRepository gameInstanceRepository,
      SessionContextService sessionContextService,
      AccountClient accountClient,
      LogOnlyProperties logOnlyProperties,
      MeterRegistry meterRegistry) {
    this.commandService =
        Objects.requireNonNull(commandService, "commandService must not be null");
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.logOnlyProperties =
        Objects.requireNonNull(logOnlyProperties, "logOnlyProperties must not be null");
    Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    this.takeoverCounter = meterRegistry.counter("gamesession.session.takeover");
    this.resumeCounter = meterRegistry.counter("gamesession.session.resume");
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

    Long numericSessionId = parseSessionId(sessionId);
    if (numericSessionId == null) {
      return invalidSessionFailure();
    }

    Optional<GameInstance> maybeInstance = gameInstanceRepository.findById(numericSessionId);
    if (maybeInstance.isEmpty()) {
      CommandEnqueueResult failure =
          CommandEnqueueResult.failure("SESSION_NOT_FOUND", "Session not found");
      return new LoginCommandHandlingResult(failure, null);
    }
    GameInstance instance = maybeInstance.get();

    String otp = args.size() > 2 ? args.get(2) : "";
    AuthenticateResponse authResponse =
        accountClient.authenticate(
            String.valueOf(instance.getTenantId()), args.get(0), args.get(1), otp);
    var error = authResponse.getError();
    if (error != null
        && (!Optional.ofNullable(error.getCode()).orElse("").isBlank()
            || !Optional.ofNullable(error.getMessage()).orElse("").isBlank())) {
      return new LoginCommandHandlingResult(
          CommandEnqueueResult.failure(mapErrorCode(error), error.getMessage()), null);
    }

    Long authenticatedAccountId = parseAccountId(authResponse.getAccountId());
    if (authenticatedAccountId == null || authenticatedAccountId <= 0) {
      if (logOnlyProperties.isLogOnly()) {
        authenticatedAccountId = instance.getOwnerAccountId();
      } else {
        return invalidAccountFailure();
      }
    }
    if (authenticatedAccountId != instance.getOwnerAccountId()) {
      return accountMismatchFailure();
    }

    Optional<LoginResult> loginResult =
        buildLoginResult(instance, authenticatedAccountId, authResponse.getAuthToken());

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    if (enqueueResult.accepted()) {
      loginResult.ifPresent(result -> persistSessionContext(numericSessionId, result));
    }
    return new LoginCommandHandlingResult(enqueueResult, null);
  }

  private void persistSessionContext(long sessionId, LoginResult result) {
    if (sessionContextService == null || result == null) {
      return;
    }

    long tenantId = result.tenantId();
    long accountId = result.accountId();
    long playerId = result.playerId();

    sessionContextService
        .findByAccountAndPlayer(tenantId, accountId, playerId)
        .ifPresent(
            existing ->
                handleExistingSession(sessionId, tenantId, accountId, playerId, existing));

    SessionContext context =
        new SessionContext(
            sessionId,
            tenantId,
            accountId,
            playerId,
            result.gameInstanceId(),
            result.jwt());
    sessionContextService.save(context);
    logger.debug(
        "Updated session context for tenant {} session {} account {} player {}",
        context.tenantId(),
        context.sessionId(),
        context.accountId(),
        context.playerId());
  }

  private void handleExistingSession(
      long incomingSessionId,
      long tenantId,
      long accountId,
      long playerId,
      SessionContext existing) {
    if (existing.sessionId() == incomingSessionId) {
      resumeCounter.increment();
      logger.debug(
          "Session resumed for tenant {} account {} player {} session {}",
          tenantId,
          accountId,
          playerId,
          incomingSessionId);
    } else {
      takeoverCounter.increment();
      logger.info(
          "Taking over session {} for tenant {} account {} player {}; new session {}",
          existing.sessionId(),
          tenantId,
          accountId,
          playerId,
          incomingSessionId);
      sessionContextService.deleteBySessionId(existing.tenantId(), existing.sessionId());
    }
  }

  private Optional<LoginResult> buildLoginResult(GameInstance instance, long accountId, String jwt) {
    if (instance == null) {
      return Optional.empty();
    }
    return Optional.of(
        new LoginResult(
            accountId,
            instance.getTenantId(),
            accountId,
            instance.getId(),
            jwt));
  }

  private static final Map<String, String> CANONICAL_ERROR_MAP =
      Map.of(
          AuthenticationErrorCodes.INVALID_CREDENTIALS,
          "INVALID_CREDENTIALS",
          AuthenticationErrorCodes.OTP_REQUIRED,
          "OTP_REQUIRED",
          AuthenticationErrorCodes.ACCOUNT_LOCKED,
          "ACCOUNT_LOCKED");

  private String mapErrorCode(ErrorDetail error) {
    if (error == null) {
      return "UPSTREAM_FAILURE";
    }
    String rawCode = Optional.ofNullable(error.getCode()).orElse("");
    String upperCode = rawCode.toUpperCase();
    if (CANONICAL_ERROR_MAP.containsKey(upperCode)) {
      return CANONICAL_ERROR_MAP.get(upperCode);
    }
    String message = Optional.ofNullable(error.getMessage()).orElse("").toLowerCase();
    if (message.contains("invalid credentials")) {
      return "INVALID_CREDENTIALS";
    }
    if (message.contains("invalid 2fa") || message.contains("otp")) {
      return "OTP_REQUIRED";
    }
    if (message.contains("locked")) {
      return "ACCOUNT_LOCKED";
    }
    return upperCode.isBlank() ? "UPSTREAM_FAILURE" : upperCode;
  }

  private Long parseSessionId(String sessionIdText) {
    try {
      return Long.parseLong(sessionIdText);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Long parseAccountId(String accountIdText) {
    try {
      return Long.parseLong(accountIdText);
    } catch (NumberFormatException | NullPointerException ex) {
      return null;
    }
  }

  private LoginCommandHandlingResult invalidSessionFailure() {
    return new LoginCommandHandlingResult(
        CommandEnqueueResult.failure("INVALID_ARGUMENT", "sessionId must be numeric"), null);
  }

  private LoginCommandHandlingResult invalidAccountFailure() {
    return new LoginCommandHandlingResult(
        CommandEnqueueResult.failure(
            LoginCommandConstants.INVALID_ACCOUNT_CODE, LoginCommandConstants.INVALID_ACCOUNT_MESSAGE),
        null);
  }

  private LoginCommandHandlingResult accountMismatchFailure() {
    return new LoginCommandHandlingResult(
        CommandEnqueueResult.failure(
            LoginCommandConstants.ACCOUNT_MISMATCH_CODE, LoginCommandConstants.ACCOUNT_MISMATCH_MESSAGE),
        null);
  }
}
