package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.config.DevIsolatedProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.devisolated.DevIsolatedGameInstanceRegistry;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Handles LOGIN/LOGON commands and generates immediate responses when possible. */
@Component
public final class LoginCommandHandler {
  private static final Logger logger = LoggerFactory.getLogger(LoginCommandHandler.class);

  private final GameInstanceRepository gameInstanceRepository;
  private final SessionContextService sessionContextService;
  private final AccountClient accountClient;
  private final CommandService commandService;
  private final DevIsolatedProperties devIsolatedProperties;
  private final DevIsolatedGameInstanceRegistry devIsolatedGameInstanceRegistry;

  @Autowired
  public LoginCommandHandler(
      GameInstanceRepository gameInstanceRepository,
      SessionContextService sessionContextService,
      AccountClient accountClient,
      CommandService commandService,
      DevIsolatedProperties devIsolatedProperties,
      ObjectProvider<DevIsolatedGameInstanceRegistry> devIsolatedGameInstanceRegistryProvider,
      MeterRegistry meterRegistry) {
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    this.devIsolatedProperties =
        Objects.requireNonNull(devIsolatedProperties, "devIsolatedProperties must not be null");
    this.devIsolatedGameInstanceRegistry = devIsolatedGameInstanceRegistryProvider.getIfAvailable();
    Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
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
    if (maybeInstance.isEmpty()
        && devIsolatedProperties.isDevIsolated()
        && devIsolatedGameInstanceRegistry != null) {
      maybeInstance = devIsolatedGameInstanceRegistry.findById(numericSessionId);
    }
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
      if (devIsolatedProperties.isDevIsolated()) {
        authenticatedAccountId = instance.getOwnerAccountId();
      } else {
        return invalidAccountFailure();
      }
    }
    if (!Objects.equals(authenticatedAccountId, instance.getOwnerAccountId())) {
      return accountMismatchFailure();
    }

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    if (!enqueueResult.accepted()) {
      return new LoginCommandHandlingResult(enqueueResult, null);
    }
    persistSessionContext(
        numericSessionId,
        instance.getTenantId(),
        authenticatedAccountId,
        authResponse.getAuthToken());
    String responseText = "Logged in as " + args.get(0);
    return new LoginCommandHandlingResult(enqueueResult, responseText);
  }

  private void persistSessionContext(long sessionId, long tenantId, long accountId, String jwt) {
    if (sessionContextService == null) {
      return;
    }
    SessionContext existing =
        sessionContextService.findByTenantAndSessionId(tenantId, sessionId).orElse(null);
    // LOGIN authenticates account identity. If this session already has gameplay scope, preserve it
    // so reconnect on the same transport session can continue through PLAY without losing room
    // state.
    SessionContext context =
        existing == null
            ? new SessionContext(sessionId, tenantId, accountId, 0L, 0L, null, jwt)
            : new SessionContext(
                sessionId,
                tenantId,
                accountId,
                existing.characterId(),
                existing.gameInstanceId(),
                existing.roomInstanceId(),
                jwt);
    sessionContextService.save(context);
    logger.debug(
        "Updated login context for tenant {} session {} account {}",
        context.tenantId(),
        context.sessionId(),
        context.accountId());
  }

  private static final Map<String, String> CANONICAL_ERROR_MAP =
      Map.of(
          AuthenticationErrorCodes.INVALID_CREDENTIALS,
          "INVALID_CREDENTIALS",
          AuthenticationErrorCodes.OTP_REQUIRED,
          "OTP_REQUIRED",
          AuthenticationErrorCodes.ACCOUNT_LOCKED,
          "ACCOUNT_LOCKED",
          AuthenticationErrorCodes.UPSTREAM_FAILURE,
          "UPSTREAM_FAILURE");

  private String mapErrorCode(ErrorDetail error) {
    if (error == null) {
      return "UPSTREAM_FAILURE";
    }
    String rawCode = Optional.ofNullable(error.getCode()).orElse("").toUpperCase();
    if (CANONICAL_ERROR_MAP.containsKey(rawCode)) {
      return CANONICAL_ERROR_MAP.get(rawCode);
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
    return "UPSTREAM_FAILURE";
  }

  private Long parseSessionId(String sessionIdText) {
    try {
      return Long.parseLong(sessionIdText);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Long parseAccountId(String accountIdText) {
    if (accountIdText == null) {
      return null;
    }
    try {
      return Long.parseLong(accountIdText);
    } catch (NumberFormatException ex) {
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
            LoginCommandConstants.INVALID_ACCOUNT_CODE,
            LoginCommandConstants.INVALID_ACCOUNT_MESSAGE),
        null);
  }

  private LoginCommandHandlingResult accountMismatchFailure() {
    return new LoginCommandHandlingResult(
        CommandEnqueueResult.failure(
            LoginCommandConstants.ACCOUNT_MISMATCH_CODE,
            LoginCommandConstants.ACCOUNT_MISMATCH_MESSAGE),
        null);
  }
}
