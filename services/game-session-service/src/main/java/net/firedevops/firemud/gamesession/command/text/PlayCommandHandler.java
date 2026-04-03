package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.config.GameSessionProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the gameplay-binding PLAY command after login. */
@Component
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP2",
    justification = "Injected services/configuration are stored internally")
public class PlayCommandHandler {
  private static final Logger LOG = LoggerFactory.getLogger(PlayCommandHandler.class);
  private static final String INVOCATIONS_METRIC = "gamesession.command.play.invocations";
  private static final String FAILURES_METRIC = "gamesession.command.play.failures";
  private static final String TAKEOVER_METRIC = "gamesession.session.takeover";
  private static final String RESUME_METRIC = "gamesession.session.resume";
  private static final String RESUME_DENIED_METRIC = "gamesession.session.resume_denied";
  private static final String FRESH_ENTRY_FALLBACK_METRIC =
      "gamesession.session.fresh_entry_fallback";

  private final SessionAuthenticationService sessionAuthenticationService;
  private final SessionContextService sessionContextService;
  private final GameplayWorldCatalog gameplayWorldCatalog;
  private final GameLogicProperties gameLogicProperties;
  private final AccountClient accountClient;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final MeterRegistry meterRegistry;
  private final Counter takeoverCounter;
  private final Counter resumeCounter;

  public PlayCommandHandler(
      SessionAuthenticationService sessionAuthenticationService,
      SessionContextService sessionContextService,
      GameplayWorldCatalog gameplayWorldCatalog,
      GameLogicProperties gameLogicProperties,
      AccountClient accountClient,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      MeterRegistry meterRegistry) {
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.gameplayWorldCatalog =
        Objects.requireNonNull(gameplayWorldCatalog, "gameplayWorldCatalog must not be null");
    this.gameLogicProperties =
        Objects.requireNonNull(gameLogicProperties, "gameLogicProperties must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.firstPartyConnectContextRegistry =
        Objects.requireNonNull(
            firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    this.takeoverCounter = this.meterRegistry.counter(TAKEOVER_METRIC);
    this.resumeCounter = this.meterRegistry.counter(RESUME_METRIC);
  }

  public PlayCommandHandlingResult handle(String sessionId, TextCommand command) {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(command, "command must not be null");

    Optional<SessionContext> maybeContext =
        sessionAuthenticationService.resolveSessionContext(sessionId);
    String tenantTag =
        maybeContext.map(context -> Long.toString(context.tenantId())).orElse("unknown");
    meterRegistry.counter(INVOCATIONS_METRIC, "tenantId", tenantTag).increment();

    if (maybeContext.isEmpty()) {
      return failure(
          GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
          GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE,
          tenantTag,
          null,
          null,
          null);
    }

    Optional<TextCommandPayload.Selection> maybeSelection = command.selectionPayload();
    if (maybeSelection.isEmpty()) {
      return failure(
          GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_CODE,
          GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_MESSAGE,
          tenantTag,
          null,
          null,
          null);
    }
    TextCommandPayload.Selection selection = maybeSelection.orElseThrow();

    SessionContext context = maybeContext.get();
    try (GameplayLoggingContext baseContext =
        GameplayLoggingContext.open(Long.toString(context.tenantId()), null, null, null)) {
      String worldSelector = selection.primary();
      Optional<GameSessionProperties.WorldOption> maybeWorld =
          gameplayWorldCatalog.resolve(worldSelector);
      if (maybeWorld.isEmpty()) {
        return failure(
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE,
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_MESSAGE,
            tenantTag,
            null,
            null,
            null);
      }

      GameSessionProperties.WorldOption selectedWorld = maybeWorld.get();
      try (GameplayLoggingContext worldContext =
          GameplayLoggingContext.open(
              Long.toString(context.tenantId()),
              Long.toString(selectedWorld.getGameInstanceId()),
              null,
              null)) {
        Optional<PlayCommandHandlingResult> connectScopeFailure =
            validateFirstPartyConnectScope(context, selectedWorld, tenantTag);
        if (connectScopeFailure.isPresent()) {
          return connectScopeFailure.get();
        }
        Optional<PlayCommandHandlingResult> authorityFailure =
            validateRuntimeAdmission(context, selectedWorld, tenantTag, selection.secondary());
        if (authorityFailure.isPresent()) {
          return authorityFailure.get();
        }

        String character = selection.secondary();
        if (selectedWorld.isRequiresCharacterSelection() && !StringUtils.hasText(character)) {
          return failure(
              "PLAY_SELECTION_REQUIRED",
              "Selection required. Use PLAY "
                  + selectedWorld.getSlug()
                  + " <character> or browse CHARS first.",
              tenantTag,
              Long.toString(selectedWorld.getGameInstanceId()),
              null,
              null);
        }

        long gameInstanceId = selectedWorld.getGameInstanceId();
        long characterId = resolveCharacterId(context, selectedWorld, character);
        String characterName = resolveCharacterName(context, characterId, character);
        try (GameplayLoggingContext gameplayContext =
            GameplayLoggingContext.open(
                Long.toString(context.tenantId()),
                Long.toString(gameInstanceId),
                Long.toString(characterId),
                null)) {
          if (StringUtils.hasText(context.roomInstanceId())
              && context.gameInstanceId() == gameInstanceId
              && context.characterId() == characterId
              && Objects.equals(
                  normalizeName(context.characterName()), normalizeName(characterName))) {
            resumeCounter.increment();
            meterRegistry.counter(RESUME_METRIC, "tenantId", tenantTag).increment();
            LOG.debug(
                "PLAY resumed existing gameplay binding for tenant {} gameInstance {} character {} on session {}",
                context.tenantId(),
                gameInstanceId,
                characterId,
                context.sessionId());
            return new PlayCommandHandlingResult(
                CommandEnqueueResult.success(),
                List.of(successNotice(selectedWorld.getSlug(), character)),
                true);
          }

          boolean freshEntryFallback =
              maybeRecordFreshEntryFallback(
                  context, selectedWorld, character, gameInstanceId, characterId, tenantTag);

          Optional<SessionContext> existingBinding =
              sessionContextService.findByGameplayIdentity(
                  context.tenantId(), gameInstanceId, characterId);
          boolean resumedOrTookOver =
              existingBinding
                  .map(
                      existing ->
                          handleExistingBinding(context, existing, gameInstanceId, characterId))
                  .orElse(false);

          String roomInstanceId =
              existingBinding
                  .map(SessionContext::roomInstanceId)
                  .filter(StringUtils::hasText)
                  .orElse(gameLogicProperties.getDefaultRoomId());

          SessionContext updated =
              new SessionContext(
                  context.sessionId(),
                  context.tenantId(),
                  context.accountId(),
                  context.loginName(),
                  characterId,
                  characterName,
                  gameInstanceId,
                  roomInstanceId,
                  context.jwt(),
                  context.localeTag(),
                  context.bootstrapGameInstanceId());
          sessionContextService.save(updated);

          return new PlayCommandHandlingResult(
              CommandEnqueueResult.success(),
              List.of(successNotice(selectedWorld.getSlug(), character)),
              resumedOrTookOver || freshEntryFallback);
        }
      }
    }
  }

  private Optional<PlayCommandHandlingResult> validateFirstPartyConnectScope(
      SessionContext context, GameSessionProperties.WorldOption selectedWorld, String tenantTag) {
    return firstPartyConnectContextRegistry
        .find(context.sessionId())
        .filter(
            connectContext ->
                connectContext.tenantId() != context.tenantId()
                    || connectContext.gameInstanceId() != selectedWorld.getGameInstanceId())
        .map(
            ignored ->
                failure(
                    "CONNECT_SCOPE_MISMATCH",
                    "Connect scope mismatch",
                    tenantTag,
                    Long.toString(selectedWorld.getGameInstanceId()),
                    null,
                    null));
  }

  private PlayCommandHandlingResult failure(
      String errorCode,
      String message,
      String tenantTag,
      String gameInstanceTag,
      String characterTag,
      RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "tenantId", tenantTag, "error", errorCode).increment();
    if (ex == null) {
      LOG.warn(
          "PLAY failed tenantId={} gameInstanceId={} characterId={} error={} reason={}",
          tenantTag,
          gameInstanceTag,
          characterTag,
          errorCode,
          message);
    } else {
      LOG.warn(
          "PLAY failed tenantId={} gameInstanceId={} characterId={} error={} reason={}",
          tenantTag,
          gameInstanceTag,
          characterTag,
          errorCode,
          message,
          ex);
    }
    return new PlayCommandHandlingResult(
        CommandEnqueueResult.failure(errorCode, message), List.of());
  }

  private long resolveCharacterId(
      SessionContext context, GameSessionProperties.WorldOption selectedWorld, String character) {
    if (context.characterId() > 0) {
      return context.characterId();
    }
    if (!StringUtils.hasText(character)) {
      return context.accountId();
    }
    return Math.floorMod(
            Objects.hash(
                context.tenantId(),
                selectedWorld.getGameInstanceId(),
                character.trim().toLowerCase()),
            Integer.MAX_VALUE - 1)
        + 1L;
  }

  private String resolveCharacterName(SessionContext context, long characterId, String character) {
    if (StringUtils.hasText(character)) {
      return character.trim();
    }
    if (StringUtils.hasText(context.characterName())) {
      return context.characterName().trim();
    }
    if (StringUtils.hasText(context.loginName())) {
      String login = context.loginName().trim();
      int at = login.indexOf('@');
      return at > 0 ? login.substring(0, at) : login;
    }
    return "character-" + characterId;
  }

  private boolean handleExistingBinding(
      SessionContext incoming, SessionContext existing, long gameInstanceId, long characterId) {
    if (existing.sessionId() == incoming.sessionId()) {
      resumeCounter.increment();
      meterRegistry
          .counter(RESUME_METRIC, "tenantId", Long.toString(incoming.tenantId()))
          .increment();
      LOG.debug(
          "PLAY resumed gameplay binding for tenant {} gameInstance {} character {} on session {}",
          incoming.tenantId(),
          gameInstanceId,
          characterId,
          incoming.sessionId());
      return true;
    }

    takeoverCounter.increment();
    meterRegistry
        .counter(TAKEOVER_METRIC, "tenantId", Long.toString(incoming.tenantId()))
        .increment();
    LOG.info(
        "PLAY taking over gameplay binding tenant {} gameInstance {} character {} from session {} to {}",
        incoming.tenantId(),
        gameInstanceId,
        characterId,
        existing.sessionId(),
        incoming.sessionId());
    sessionContextService.deleteBySessionId(existing.tenantId(), existing.sessionId());
    return true;
  }

  private String formatSuccessResponse(String world, String character) {
    String suffix = StringUtils.hasText(character) ? " as " + character : "";
    return "Entered world: " + world + suffix;
  }

  private PlayerOutput successNotice(String world, String character) {
    String characterSuffix = StringUtils.hasText(character) ? " as " + character : "";
    return PlayerOutput.notice(
        formatSuccessResponse(world, character),
        "notice.world.entered",
        Map.of("worldName", world, "characterSuffix", characterSuffix));
  }

  private String normalizeName(String value) {
    return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private Optional<PlayCommandHandlingResult> validateRuntimeAdmission(
      SessionContext context,
      GameSessionProperties.WorldOption selectedWorld,
      String tenantTag,
      String requestedCharacter) {
    String requestId = context.sessionId() + ":" + UUID.randomUUID();
    long requestedCharacterId = resolveCharacterId(context, selectedWorld, requestedCharacter);
    GetTenantMembershipForRuntimeResponse membershipResponse =
        accountClient.getTenantMembershipForRuntime(
            Long.toString(context.accountId()), Long.toString(context.tenantId()), requestId);
    Optional<PlayCommandHandlingResult> membershipFailure =
        validateMembershipResponse(
            membershipResponse, context, tenantTag, selectedWorld, requestedCharacterId);
    if (membershipFailure.isPresent()) {
      return membershipFailure;
    }

    GetTenantEntitlementsForRuntimeResponse entitlementResponse =
        accountClient.getTenantEntitlementsForRuntime(Long.toString(context.tenantId()), requestId);
    return validateEntitlementsResponse(
        entitlementResponse, context, tenantTag, selectedWorld, requestedCharacterId);
  }

  private Optional<PlayCommandHandlingResult> validateMembershipResponse(
      GetTenantMembershipForRuntimeResponse response,
      SessionContext context,
      String tenantTag,
      GameSessionProperties.WorldOption selectedWorld,
      long requestedCharacterId) {
    Optional<ErrorDetail> maybeError = extractError(response.getError());
    if (maybeError.isPresent()) {
      ErrorDetail error = maybeError.get();
      if ("NOT_FOUND".equalsIgnoreCase(error.getCode())) {
        recordResumeDeniedIfApplicable(
            context,
            selectedWorld.getGameInstanceId(),
            requestedCharacterId,
            tenantTag,
            "access_denied");
        return Optional.of(
            failure(
                GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE,
                GameplayStageCommandConstants.WORLD_ACCESS_DENIED_MESSAGE,
                tenantTag,
                Long.toString(selectedWorld.getGameInstanceId()),
                Long.toString(requestedCharacterId),
                null));
      }
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.getGameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "authority_unavailable");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_CODE,
              GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_MESSAGE,
              tenantTag,
              Long.toString(selectedWorld.getGameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    if (!response.getGameplayAdmissionAllowed()) {
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.getGameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "access_denied");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE,
              GameplayStageCommandConstants.WORLD_ACCESS_DENIED_MESSAGE,
              tenantTag,
              Long.toString(selectedWorld.getGameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    return Optional.empty();
  }

  private Optional<PlayCommandHandlingResult> validateEntitlementsResponse(
      GetTenantEntitlementsForRuntimeResponse response,
      SessionContext context,
      String tenantTag,
      GameSessionProperties.WorldOption selectedWorld,
      long requestedCharacterId) {
    Optional<ErrorDetail> maybeError = extractError(response.getError());
    if (maybeError.isPresent()) {
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.getGameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "authority_unavailable");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE,
              GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE,
              tenantTag,
              Long.toString(selectedWorld.getGameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    if (!response.getGameplayAvailable()) {
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.getGameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "tenant_unavailable");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_CODE,
              GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_MESSAGE,
              tenantTag,
              Long.toString(selectedWorld.getGameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    return Optional.empty();
  }

  private Optional<ErrorDetail> extractError(ErrorDetail error) {
    if (error == null) {
      return Optional.empty();
    }
    if (Optional.ofNullable(error.getCode()).orElse("").isBlank()
        && Optional.ofNullable(error.getMessage()).orElse("").isBlank()) {
      return Optional.empty();
    }
    return Optional.of(error);
  }

  private boolean maybeRecordFreshEntryFallback(
      SessionContext context,
      GameSessionProperties.WorldOption selectedWorld,
      String requestedCharacter,
      long requestedGameInstanceId,
      long requestedCharacterId,
      String tenantTag) {
    if (context.gameInstanceId() != requestedGameInstanceId
        || context.characterId() != requestedCharacterId) {
      return false;
    }
    if (StringUtils.hasText(context.roomInstanceId())
        && Objects.equals(
            normalizeName(context.characterName()),
            normalizeName(
                resolveCharacterName(context, requestedCharacterId, requestedCharacter)))) {
      return false;
    }
    meterRegistry
        .counter(
            FRESH_ENTRY_FALLBACK_METRIC,
            "tenantId",
            tenantTag,
            "reason",
            "stale_or_missing_context")
        .increment();
    LOG.info(
        "PLAY falling back to fresh entry for tenant {} gameInstance {} character {} on session {} because resumable context was stale or incomplete",
        context.tenantId(),
        selectedWorld.getGameInstanceId(),
        requestedCharacterId,
        context.sessionId());
    return true;
  }

  private void recordResumeDeniedIfApplicable(
      SessionContext context,
      long requestedGameInstanceId,
      long requestedCharacterId,
      String tenantTag,
      String reason) {
    if (context.gameInstanceId() != requestedGameInstanceId
        || context.characterId() != requestedCharacterId) {
      return;
    }
    meterRegistry
        .counter(RESUME_DENIED_METRIC, "tenantId", tenantTag, "reason", reason)
        .increment();
  }
}
