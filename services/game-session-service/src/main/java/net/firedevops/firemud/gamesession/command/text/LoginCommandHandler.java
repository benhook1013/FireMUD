package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles LOGIN/LOGON commands and generates immediate responses when possible. */
@Component
public final class LoginCommandHandler {
  private static final Logger logger = LoggerFactory.getLogger(LoginCommandHandler.class);

  private final GameInstanceRepository gameInstanceRepository;
  private final SessionContextService sessionContextService;
  private final AccountClient accountClient;
  private final CommandService commandService;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;

  @Autowired
  public LoginCommandHandler(
      GameInstanceRepository gameInstanceRepository,
      SessionContextService sessionContextService,
      AccountClient accountClient,
      CommandService commandService,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      MeterRegistry meterRegistry) {
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    this.firstPartyConnectContextRegistry =
        Objects.requireNonNull(
            firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    this.gameplayAdmissionPointerAuthorityService =
        Objects.requireNonNull(
            gameplayAdmissionPointerAuthorityService,
            "gameplayAdmissionPointerAuthorityService must not be null");
    Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
  }

  public LoginCommandHandlingResult handle(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    Optional<TextCommandPayload.Credentials> maybeCredentials = command.credentialsPayload();
    if (maybeCredentials.isEmpty()) {
      return handleVerifiedFirstPartyLogin(sessionId, command, requiresSoloTick);
    }
    TextCommandPayload.Credentials credentials = maybeCredentials.orElseThrow();

    Long numericSessionId = parseSessionId(sessionId);
    if (numericSessionId == null) {
      return invalidSessionFailure();
    }

    long bootstrapGameInstanceId = resolveBootstrapGameInstanceId(numericSessionId);
    Optional<GameInstance> maybeInstance = gameInstanceRepository.findById(bootstrapGameInstanceId);
    if (maybeInstance.isEmpty()) {
      clearFailedLoginSessionState(numericSessionId, 0L, bootstrapGameInstanceId, null, null, 0L);
      return failure("SESSION_NOT_FOUND", "Session not found");
    }
    GameInstance instance = maybeInstance.get();

    String otp = StringUtils.hasText(credentials.otp()) ? credentials.otp() : "";
    AuthenticateResponse authResponse =
        accountClient.authenticate(
            String.valueOf(instance.getTenantId()),
            credentials.loginName(),
            credentials.password(),
            otp);
    var error = authResponse.getError();
    if (error != null
        && (!Optional.ofNullable(error.getCode()).orElse("").isBlank()
            || !Optional.ofNullable(error.getMessage()).orElse("").isBlank())) {
      clearFailedLoginSessionState(
          numericSessionId, instance.getTenantId(), bootstrapGameInstanceId, null, null, 0L);
      return failure(mapErrorCode(error), error.getMessage());
    }

    Long authenticatedAccountId = parseAccountId(authResponse.getAccountId());
    if (authenticatedAccountId == null || authenticatedAccountId <= 0) {
      clearFailedLoginSessionState(
          numericSessionId, instance.getTenantId(), bootstrapGameInstanceId, null, null, 0L);
      return invalidAccountFailure();
    }
    if (!Objects.equals(authenticatedAccountId, instance.getOwnerAccountId())) {
      clearFailedLoginSessionState(
          numericSessionId, instance.getTenantId(), bootstrapGameInstanceId, null, null, 0L);
      return accountMismatchFailure();
    }

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    if (!enqueueResult.accepted()) {
      return fromCommandResult(enqueueResult);
    }
    persistSessionContext(
        numericSessionId,
        instance.getTenantId(),
        authenticatedAccountId,
        credentials.loginName(),
        authResponse.getAuthToken(),
        bootstrapGameInstanceId);
    return new LoginCommandHandlingResult(
        enqueueResult,
        List.of(
            PlayerOutput.message(
                "Logged in as " + credentials.loginName(),
                "message.login.success",
                Map.of("loginName", credentials.loginName()))));
  }

  private LoginCommandHandlingResult handleVerifiedFirstPartyLogin(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    Long numericSessionId = parseSessionId(sessionId);
    if (numericSessionId == null) {
      return invalidSessionFailure();
    }
    Optional<net.firedevops.firemud.gamesession.service.FirstPartyConnectContext> maybeContext =
        firstPartyConnectContextRegistry.find(numericSessionId);
    if (maybeContext.isEmpty()) {
      clearFailedLoginSessionState(numericSessionId, 0L, 0L, null, null, 0L);
      return failure(
          LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE,
          LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_MESSAGE);
    }

    var verifiedContext = maybeContext.get();
    Optional<GameInstance> maybeInstance =
        gameInstanceRepository.findById(verifiedContext.gameInstanceId());
    if (maybeInstance.isEmpty()) {
      clearFailedLoginSessionState(
          numericSessionId,
          verifiedContext.tenantId(),
          verifiedContext.gameInstanceId(),
          verifiedContext.worldSlug(),
          verifiedContext.realmSlug(),
          verifiedContext.pointerVersion());
      return failure("SESSION_NOT_FOUND", "Session not found");
    }
    GameInstance instance = maybeInstance.get();
    if (instance.getTenantId() != verifiedContext.tenantId()) {
      clearFailedLoginSessionState(
          numericSessionId,
          verifiedContext.tenantId(),
          verifiedContext.gameInstanceId(),
          verifiedContext.worldSlug(),
          verifiedContext.realmSlug(),
          verifiedContext.pointerVersion());
      return failure("CONNECT_SCOPE_INVALID", "Connect scope invalid");
    }
    if (!Objects.equals(instance.getOwnerAccountId(), verifiedContext.accountId())) {
      clearFailedLoginSessionState(
          numericSessionId,
          verifiedContext.tenantId(),
          verifiedContext.gameInstanceId(),
          verifiedContext.worldSlug(),
          verifiedContext.realmSlug(),
          verifiedContext.pointerVersion());
      return accountMismatchFailure();
    }
    if (!currentAdmissionPointerMatches(verifiedContext)) {
      clearFailedLoginSessionState(
          numericSessionId,
          verifiedContext.tenantId(),
          verifiedContext.gameInstanceId(),
          verifiedContext.worldSlug(),
          verifiedContext.realmSlug(),
          verifiedContext.pointerVersion());
      return failure("CONNECT_SCOPE_MISMATCH", "Connect scope invalid");
    }

    CommandEnqueueResult enqueueResult =
        commandService.enqueue(sessionId, command.rawLine(), requiresSoloTick);
    if (!enqueueResult.accepted()) {
      return fromCommandResult(enqueueResult);
    }
    persistSessionContext(
        numericSessionId,
        verifiedContext.tenantId(),
        verifiedContext.accountId(),
        "first-party:" + verifiedContext.accountId(),
        null,
        verifiedContext.gameInstanceId());
    return new LoginCommandHandlingResult(
        enqueueResult,
        List.of(
            PlayerOutput.message(
                "Logged in as first-party account " + verifiedContext.accountId(),
                "message.login.first-party-success",
                Map.of("accountId", Long.toString(verifiedContext.accountId())))));
  }

  private void persistSessionContext(
      long sessionId,
      long tenantId,
      long accountId,
      String loginName,
      String jwt,
      long bootstrapGameInstanceId) {
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
            ? new SessionContext(
                sessionId,
                tenantId,
                accountId,
                loginName,
                0L,
                null,
                0L,
                null,
                jwt,
                null,
                bootstrapGameInstanceId)
            : new SessionContext(
                sessionId,
                tenantId,
                accountId,
                loginName,
                existing.characterId(),
                existing.characterName(),
                existing.gameInstanceId(),
                existing.roomInstanceId(),
                jwt,
                existing.localeTag(),
                existing.bootstrapGameInstanceId() > 0
                    ? existing.bootstrapGameInstanceId()
                    : bootstrapGameInstanceId,
                existing.worldSlug(),
                existing.realmSlug(),
                existing.pointerVersion(),
                existing.playableStateScope());
    sessionContextService.save(context);
    logger.debug(
        "Updated login context for tenant {} session {} account {}",
        context.tenantId(),
        context.sessionId(),
        context.accountId());
  }

  private long resolveBootstrapGameInstanceId(long sessionId) {
    return sessionContextService
        .findBySessionId(sessionId)
        .map(
            context ->
                context.bootstrapGameInstanceId() > 0
                    ? context.bootstrapGameInstanceId()
                    : context.gameInstanceId())
        .filter(candidate -> candidate > 0)
        .orElse(sessionId);
  }

  private boolean currentAdmissionPointerMatches(
      net.firedevops.firemud.gamesession.service.FirstPartyConnectContext verifiedContext) {
    if (!StringUtils.hasText(verifiedContext.worldSlug())
        || !StringUtils.hasText(verifiedContext.realmSlug())) {
      return false;
    }
    return gameplayAdmissionPointerAuthorityService
        .findPointer(verifiedContext.worldSlug(), verifiedContext.realmSlug())
        .filter(pointer -> pointer.tenantId() == verifiedContext.tenantId())
        .filter(pointer -> pointer.gameInstanceId() == verifiedContext.gameInstanceId())
        .filter(pointer -> pointer.pointerVersion() == verifiedContext.pointerVersion())
        .isPresent();
  }

  private void clearFailedLoginSessionState(
      long sessionId,
      long fallbackTenantId,
      long fallbackBootstrapGameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {
    SessionContext existing = sessionContextService.findBySessionId(sessionId).orElse(null);
    long tenantId =
        existing != null ? existing.tenantId() : (fallbackTenantId > 0 ? fallbackTenantId : 0L);
    if (tenantId <= 0) {
      return;
    }
    String preservedWorldSlug =
        StringUtils.hasText(worldSlug) ? worldSlug : existing != null ? existing.worldSlug() : null;
    String preservedRealmSlug =
        StringUtils.hasText(realmSlug) ? realmSlug : existing != null ? existing.realmSlug() : null;
    long preservedPointerVersion =
        pointerVersion > 0 ? pointerVersion : existing != null ? existing.pointerVersion() : 0L;
    long bootstrapGameInstanceId =
        existing != null && existing.bootstrapGameInstanceId() > 0
            ? existing.bootstrapGameInstanceId()
            : fallbackBootstrapGameInstanceId;
    sessionContextService.save(
        new SessionContext(
            sessionId,
            tenantId,
            0L,
            null,
            0L,
            null,
            0L,
            null,
            null,
            existing != null ? existing.localeTag() : null,
            bootstrapGameInstanceId,
            preservedWorldSlug,
            preservedRealmSlug,
            preservedPointerVersion,
            null));
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
    return failure("INVALID_ARGUMENT", "sessionId must be numeric");
  }

  private LoginCommandHandlingResult invalidAccountFailure() {
    return failure(
        LoginCommandConstants.INVALID_ACCOUNT_CODE, LoginCommandConstants.INVALID_ACCOUNT_MESSAGE);
  }

  private LoginCommandHandlingResult accountMismatchFailure() {
    return failure(
        LoginCommandConstants.ACCOUNT_MISMATCH_CODE,
        LoginCommandConstants.ACCOUNT_MISMATCH_MESSAGE);
  }

  private LoginCommandHandlingResult failure(String code, String message) {
    return failure(code, message, loginErrorMessageKey(code), Map.of());
  }

  private LoginCommandHandlingResult failure(
      String code, String message, String messageKey, Map<String, String> arguments) {
    return new LoginCommandHandlingResult(
        CommandEnqueueResult.failure(code, message),
        List.of(PlayerOutput.error(code, message, messageKey, arguments)));
  }

  private LoginCommandHandlingResult fromCommandResult(CommandEnqueueResult result) {
    if (result.accepted()) {
      return new LoginCommandHandlingResult(result, List.of());
    }
    return failure(result.errorCode(), result.errorMessage());
  }

  private String loginErrorMessageKey(String code) {
    if (code == null) {
      return null;
    }
    return switch (code) {
      case "SESSION_NOT_FOUND" -> "error.login.session-not-found";
      case "INVALID_ARGUMENT" -> "error.login.invalid-session-id";
      case LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE -> "error.login.prompt-unsupported";
      case LoginCommandConstants.ACCOUNT_MISMATCH_CODE -> "error.login.account-mismatch";
      case LoginCommandConstants.INVALID_ACCOUNT_CODE -> "error.login.invalid-account";
      case "INVALID_CREDENTIALS" -> "error.login.invalid-credentials";
      case "OTP_REQUIRED" -> "error.login.otp-required";
      case "ACCOUNT_LOCKED" -> "error.login.account-locked";
      case "UPSTREAM_FAILURE" -> "error.login.upstream-failure";
      default -> null;
    };
  }
}
