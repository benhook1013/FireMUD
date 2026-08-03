package net.firedevops.firemud.gamesession.command.text;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.firedevops.firemud.account.AuthenticationErrorCodes;
import net.firedevops.firemud.account.v1.AuthenticateResponse;
import net.firedevops.firemud.account.v1.RequestEmailLoginOtpResponse;
import net.firedevops.firemud.common.EmailCanonicalization;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameInstance;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.repository.GameInstanceRepository;
import net.firedevops.firemud.gamesession.service.CommandService;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextResolution;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerAuthorityService;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.PositiveLongParsing;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionIdParsing;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Handles LOGIN/LOGON commands and generates immediate responses when possible. */
@Component
public final class LoginCommandHandler {
  private static final Logger logger = LoggerFactory.getLogger(LoginCommandHandler.class);
  private static final String AUTHENTICATION_UNAVAILABLE_CODE = "UNAVAILABLE";
  private static final String RETRY_LATER_CODE = "RETRY_LATER";
  private static final String ABUSE_CONTROL_UNAVAILABLE_CODE = "ABUSE_CONTROL_UNAVAILABLE";
  private static final String RETRY_LATER_MESSAGE = "Too many failed attempts; try again later.";
  private static final String ABUSE_CONTROL_UNAVAILABLE_MESSAGE =
      "Login protection is temporarily unavailable. Try again later.";

  private final GameInstanceRepository gameInstanceRepository;
  private final SessionContextService sessionContextService;
  private final SessionAuthenticationService sessionAuthenticationService;
  private final AccountClient accountClient;
  private final CommandService commandService;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private final GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService;
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService;

  @Autowired
  public LoginCommandHandler(
      GameInstanceRepository gameInstanceRepository,
      SessionContextService sessionContextService,
      SessionAuthenticationService sessionAuthenticationService,
      AccountClient accountClient,
      CommandService commandService,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      SessionRoutingNormalizationService sessionRoutingNormalizationService,
      GameplayAdmissionPointerAuthorityService gameplayAdmissionPointerAuthorityService,
      GameplayPresenceLifecycleService gameplayPresenceLifecycleService,
      MeterRegistry meterRegistry) {
    this.gameInstanceRepository =
        Objects.requireNonNull(gameInstanceRepository, "gameInstanceRepository must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.commandService = Objects.requireNonNull(commandService, "commandService must not be null");
    this.firstPartyConnectContextRegistry =
        Objects.requireNonNull(
            firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    this.sessionRoutingNormalizationService =
        Objects.requireNonNull(
            sessionRoutingNormalizationService,
            "sessionRoutingNormalizationService must not be null");
    this.gameplayAdmissionPointerAuthorityService =
        Objects.requireNonNull(
            gameplayAdmissionPointerAuthorityService,
            "gameplayAdmissionPointerAuthorityService must not be null");
    this.gameplayPresenceLifecycleService =
        Objects.requireNonNull(
            gameplayPresenceLifecycleService, "gameplayPresenceLifecycleService must not be null");
    Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
  }

  public LoginCommandHandlingResult handle(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    Optional<TextCommandPayload.EmailLoginChallengeRequest> maybeChallengeRequest =
        command.emailLoginChallengePayload();
    if (maybeChallengeRequest.isPresent()) {
      return handleEmailLoginChallenge(sessionId, maybeChallengeRequest.orElseThrow());
    }
    Optional<TextCommandPayload.Credentials> maybeCredentials = command.credentialsPayload();
    if (maybeCredentials.isEmpty()) {
      if (!command.args().isEmpty()) {
        return failure(
            LoginCommandConstants.INVALID_ARGUMENTS_CODE,
            LoginCommandConstants.INVALID_ARGUMENTS_MESSAGE);
      }
      return handleVerifiedFirstPartyLogin(sessionId, command, requiresSoloTick);
    }
    TextCommandPayload.Credentials credentials = maybeCredentials.orElseThrow();
    String canonicalLoginName = EmailCanonicalization.normalize(credentials.loginName());

    SessionIdParsing.ParsedSessionId parsedSessionId = parseSessionId(sessionId);
    if (!parsedSessionId.valid()) {
      return invalidSessionFailure(parsedSessionId.errorMessage());
    }
    long numericSessionId = parsedSessionId.value();

    long bootstrapGameInstanceId = resolveBootstrapGameInstanceId(numericSessionId);
    if (bootstrapGameInstanceId <= 0) {
      clearFailedLoginSessionState(numericSessionId, 0L, 0L, null, null, 0L);
      return failure("SESSION_NOT_FOUND", "Session not found");
    }
    Optional<GameInstance> maybeInstance = gameInstanceRepository.findById(bootstrapGameInstanceId);
    if (maybeInstance.isEmpty()) {
      clearFailedLoginSessionState(numericSessionId, 0L, bootstrapGameInstanceId, null, null, 0L);
      return failure("SESSION_NOT_FOUND", "Session not found");
    }
    GameInstance instance = maybeInstance.get();

    AuthenticateResponse authResponse =
        accountClient.authenticate(
            String.valueOf(instance.getTenantId()), canonicalLoginName, credentials.password());
    var error = authResponse.getError();
    if (error != null
        && (!Optional.ofNullable(error.getCode()).orElse("").isBlank()
            || !Optional.ofNullable(error.getMessage()).orElse("").isBlank())) {
      clearFailedLoginSessionState(
          numericSessionId, instance.getTenantId(), bootstrapGameInstanceId, null, null, 0L);
      String errorCode = mapErrorCode(error);
      return failure(errorCode, publicErrorMessage(error, errorCode));
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
        canonicalLoginName,
        authResponse.getAuthToken(),
        bootstrapGameInstanceId);
    return new LoginCommandHandlingResult(
        enqueueResult,
        List.of(
            PlayerOutput.message(
                "Logged in as " + canonicalLoginName,
                "message.login.success",
                Map.of("loginName", canonicalLoginName))));
  }

  private LoginCommandHandlingResult handleEmailLoginChallenge(
      String sessionId, TextCommandPayload.EmailLoginChallengeRequest challengeRequest) {
    SessionIdParsing.ParsedSessionId parsedSessionId = parseSessionId(sessionId);
    if (!parsedSessionId.valid()) {
      return invalidSessionFailure(parsedSessionId.errorMessage());
    }
    long numericSessionId = parsedSessionId.value();

    long bootstrapGameInstanceId = resolveBootstrapGameInstanceId(numericSessionId);
    if (bootstrapGameInstanceId <= 0) {
      return failure("SESSION_NOT_FOUND", "Session not found");
    }
    Optional<GameInstance> maybeInstance = gameInstanceRepository.findById(bootstrapGameInstanceId);
    if (maybeInstance.isEmpty()) {
      return failure("SESSION_NOT_FOUND", "Session not found");
    }
    GameInstance instance = maybeInstance.orElseThrow();

    RequestEmailLoginOtpResponse response =
        accountClient.requestEmailLoginOtp(
            String.valueOf(instance.getTenantId()),
            EmailCanonicalization.normalize(challengeRequest.email()));
    if (hasError(response.getError()) || !response.getAccepted()) {
      return failure(AUTHENTICATION_UNAVAILABLE_CODE, "Authentication service unavailable");
    }
    return new LoginCommandHandlingResult(
        CommandEnqueueResult.success(),
        List.of(
            PlayerOutput.message(
                LoginCommandConstants.EMAIL_LOGIN_CODE_MESSAGE, "message.login.code-sent")));
  }

  private LoginCommandHandlingResult handleVerifiedFirstPartyLogin(
      String sessionId, TextCommand command, boolean requiresSoloTick) {
    SessionIdParsing.ParsedSessionId parsedSessionId = parseSessionId(sessionId);
    if (!parsedSessionId.valid()) {
      return invalidSessionFailure(parsedSessionId.errorMessage());
    }
    long numericSessionId = parsedSessionId.value();
    SessionContext existingSession =
        sessionRoutingNormalizationService.resolveProjectedSessionContext(sessionId).orElse(null);
    FirstPartyConnectContextResolution connectContextResolution =
        FirstPartyConnectContextResolution.resolve(
            numericSessionId, existingSession, firstPartyConnectContextRegistry);
    Optional<net.firedevops.firemud.gamesession.service.FirstPartyConnectContext> maybeContext =
        connectContextResolution.connectContext();
    if (connectContextResolution.invalid()) {
      clearFailedLoginSessionState(
          numericSessionId,
          existingSession != null ? existingSession.tenantId() : 0L,
          existingSession != null ? existingSession.bootstrapGameInstanceId() : 0L,
          existingSession != null ? existingSession.worldSlug() : null,
          existingSession != null ? existingSession.realmSlug() : null,
          existingSession != null ? existingSession.pointerVersion() : 0L);
      return failure("CONNECT_CONTEXT_INVALID", "Connect context invalid");
    }
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
        sessionRoutingNormalizationService
            .resolveProjectedSessionContext(Long.toString(sessionId))
            .filter(context -> context.tenantId() == tenantId)
            .orElse(null);
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
                existing.playableStateScope(),
                existing.connectScopeId(),
                existing.connectRequestId());
    sessionContextService.save(context);
    logger.debug(
        "Updated login context for tenant {} session {} account {}",
        context.tenantId(),
        context.sessionId(),
        context.accountId());
  }

  private long resolveBootstrapGameInstanceId(long sessionId) {
    return sessionRoutingNormalizationService
        .resolveProjectedSessionContext(Long.toString(sessionId))
        .map(
            context ->
                context.bootstrapGameInstanceId() > 0
                    ? context.bootstrapGameInstanceId()
                    : context.gameInstanceId())
        .filter(candidate -> candidate > 0)
        .orElse(0L);
  }

  private boolean currentAdmissionPointerMatches(
      net.firedevops.firemud.gamesession.service.FirstPartyConnectContext verifiedContext) {
    return GameplayAdmissionPointerSnapshots.matchesCurrentRuntimeTarget(
        gameplayAdmissionPointerAuthorityService.listByRuntimeTarget(
            verifiedContext.tenantId(), verifiedContext.gameInstanceId()),
        verifiedContext.tenantId(),
        verifiedContext.gameInstanceId(),
        verifiedContext.worldSlug(),
        verifiedContext.realmSlug(),
        verifiedContext.pointerVersion());
  }

  private void clearFailedLoginSessionState(
      long sessionId,
      long fallbackTenantId,
      long fallbackBootstrapGameInstanceId,
      String worldSlug,
      String realmSlug,
      long pointerVersion) {
    SessionContext projectedExisting =
        sessionAuthenticationService
            .resolveUnverifiedSessionContext(Long.toString(sessionId))
            .orElse(null);
    long tenantId =
        projectedExisting != null
            ? projectedExisting.tenantId()
            : (fallbackTenantId > 0 ? fallbackTenantId : 0L);
    if (tenantId <= 0) {
      return;
    }
    GameplayAdmissionPointerSnapshots.RoutingBundle fallbackRoutingBundle =
        GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
            worldSlug, realmSlug, pointerVersion);
    GameplayAdmissionPointerSnapshots.RoutingBundle projectedRoutingBundle =
        projectedExisting == null
            ? null
            : GameplayAdmissionPointerSnapshots.normalizeRoutingBundle(
                projectedExisting.worldSlug(),
                projectedExisting.realmSlug(),
                projectedExisting.pointerVersion());
    GameplayAdmissionPointerSnapshots.RoutingBundle preservedRoutingBundle =
        fallbackRoutingBundle != null ? fallbackRoutingBundle : projectedRoutingBundle;
    String preservedWorldSlug =
        preservedRoutingBundle == null ? null : preservedRoutingBundle.worldSlug();
    String preservedRealmSlug =
        preservedRoutingBundle == null ? null : preservedRoutingBundle.realmSlug();
    long preservedPointerVersion =
        preservedRoutingBundle == null ? 0L : preservedRoutingBundle.pointerVersion();
    long bootstrapGameInstanceId =
        projectedExisting != null && projectedExisting.bootstrapGameInstanceId() > 0
            ? projectedExisting.bootstrapGameInstanceId()
            : fallbackBootstrapGameInstanceId;
    if (projectedExisting != null && projectedExisting.hasGameplayBinding()) {
      gameplayPresenceLifecycleService.clearGameplayBinding(projectedExisting, "LOGIN_FAILED");
    }
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
            projectedExisting != null ? projectedExisting.localeTag() : null,
            bootstrapGameInstanceId,
            preservedWorldSlug,
            preservedRealmSlug,
            preservedPointerVersion,
            null,
            projectedExisting != null ? projectedExisting.connectScopeId() : null,
            projectedExisting != null ? projectedExisting.connectRequestId() : null));
  }

  private static final Map<String, String> CANONICAL_ERROR_MAP =
      Map.of(
          AuthenticationErrorCodes.INVALID_CREDENTIALS,
          "INVALID_CREDENTIALS",
          AuthenticationErrorCodes.ACCOUNT_LOCKED,
          "ACCOUNT_LOCKED",
          AuthenticationErrorCodes.UNAVAILABLE,
          AUTHENTICATION_UNAVAILABLE_CODE,
          AuthenticationErrorCodes.RETRY_LATER,
          RETRY_LATER_CODE,
          AuthenticationErrorCodes.ABUSE_CONTROL_UNAVAILABLE,
          ABUSE_CONTROL_UNAVAILABLE_CODE);

  private String mapErrorCode(ErrorDetail error) {
    if (error == null) {
      return AUTHENTICATION_UNAVAILABLE_CODE;
    }
    String rawCode = Optional.ofNullable(error.getCode()).orElse("").toUpperCase(Locale.ROOT);
    if (CANONICAL_ERROR_MAP.containsKey(rawCode)) {
      return CANONICAL_ERROR_MAP.get(rawCode);
    }
    return AUTHENTICATION_UNAVAILABLE_CODE;
  }

  private String publicErrorMessage(ErrorDetail error, String mappedCode) {
    return switch (mappedCode) {
      case AUTHENTICATION_UNAVAILABLE_CODE -> "Authentication service unavailable";
      case RETRY_LATER_CODE -> RETRY_LATER_MESSAGE;
      case ABUSE_CONTROL_UNAVAILABLE_CODE -> ABUSE_CONTROL_UNAVAILABLE_MESSAGE;
      default -> Optional.ofNullable(error.getMessage()).orElse("");
    };
  }

  private boolean hasError(ErrorDetail error) {
    return error != null
        && (!Optional.ofNullable(error.getCode()).orElse("").isBlank()
            || !Optional.ofNullable(error.getMessage()).orElse("").isBlank());
  }

  private SessionIdParsing.ParsedSessionId parseSessionId(String sessionIdText) {
    return SessionIdParsing.parse(sessionIdText);
  }

  private Long parseAccountId(String accountIdText) {
    PositiveLongParsing.ParsedPositiveLong parsed =
        PositiveLongParsing.parseOptionalText(accountIdText, "accountId");
    if (!parsed.valid()) {
      return null;
    }
    return parsed.value();
  }

  private LoginCommandHandlingResult invalidSessionFailure(String message) {
    return failure("INVALID_ARGUMENT", message);
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
      case "CONNECT_CONTEXT_INVALID" -> "error.login.connect-context-invalid";
      case LoginCommandConstants.PROMPT_MODE_UNSUPPORTED_CODE -> "error.login.prompt-unsupported";
      case LoginCommandConstants.INVALID_ARGUMENTS_CODE -> "error.login.invalid-arguments";
      case LoginCommandConstants.ACCOUNT_MISMATCH_CODE -> "error.login.account-mismatch";
      case LoginCommandConstants.INVALID_ACCOUNT_CODE -> "error.login.invalid-account";
      case "INVALID_CREDENTIALS" -> "error.login.invalid-credentials";
      case "ACCOUNT_LOCKED" -> "error.login.account-locked";
      case RETRY_LATER_CODE -> "error.login.retry-later";
      case ABUSE_CONTROL_UNAVAILABLE_CODE -> "error.login.abuse-control-unavailable";
      case AUTHENTICATION_UNAVAILABLE_CODE -> "error.login.unavailable";
      default -> null;
    };
  }
}
