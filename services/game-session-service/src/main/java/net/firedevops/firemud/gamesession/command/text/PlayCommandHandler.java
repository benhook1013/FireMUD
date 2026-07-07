package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.EnsurePublicProductionPlayerMembershipResponse;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextResolution;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.ScriptEventPublisher;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
import net.firedevops.firemud.gamesession.service.SessionRoutingNormalizationService;
import net.firedevops.firemud.shared.v1.ErrorDetail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Handles the gameplay-binding PLAY command after login. */
@SuppressFBWarnings(
    value = "CT_CONSTRUCTOR_THROW",
    justification =
        "Constructor validation only guards injected collaborators before the handler is used.")
@Component
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
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private final GameplayWorldCatalog gameplayWorldCatalog;
  private final GameLogicProperties gameLogicProperties;
  private final AccountClient accountClient;
  private final EntityManagementClient entityManagementClient;
  private final ModerationPolicyClient moderationPolicyClient;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService;
  private final ScriptEventPublisher scriptEventPublisher;
  private final MeterRegistry meterRegistry;
  private final Counter takeoverCounter;
  private final Counter resumeCounter;

  public PlayCommandHandler(
      SessionAuthenticationService sessionAuthenticationService,
      SessionContextService sessionContextService,
      SessionRoutingNormalizationService sessionRoutingNormalizationService,
      GameplayWorldCatalog gameplayWorldCatalog,
      GameLogicProperties gameLogicProperties,
      AccountClient accountClient,
      EntityManagementClient entityManagementClient,
      ModerationPolicyClient moderationPolicyClient,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      GameplayPresenceLifecycleService gameplayPresenceLifecycleService,
      ScriptEventPublisher scriptEventPublisher,
      MeterRegistry meterRegistry) {
    this.sessionAuthenticationService =
        Objects.requireNonNull(
            sessionAuthenticationService, "sessionAuthenticationService must not be null");
    this.sessionContextService =
        Objects.requireNonNull(sessionContextService, "sessionContextService must not be null");
    this.sessionRoutingNormalizationService =
        Objects.requireNonNull(
            sessionRoutingNormalizationService,
            "sessionRoutingNormalizationService must not be null");
    this.gameplayWorldCatalog =
        Objects.requireNonNull(gameplayWorldCatalog, "gameplayWorldCatalog must not be null");
    this.gameLogicProperties =
        Objects.requireNonNull(gameLogicProperties, "gameLogicProperties must not be null");
    this.accountClient = Objects.requireNonNull(accountClient, "accountClient must not be null");
    this.entityManagementClient =
        Objects.requireNonNull(entityManagementClient, "entityManagementClient must not be null");
    this.moderationPolicyClient =
        Objects.requireNonNull(moderationPolicyClient, "moderationPolicyClient must not be null");
    this.firstPartyConnectContextRegistry =
        Objects.requireNonNull(
            firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    this.gameplayPresenceLifecycleService =
        Objects.requireNonNull(
            gameplayPresenceLifecycleService, "gameplayPresenceLifecycleService must not be null");
    this.scriptEventPublisher =
        Objects.requireNonNull(scriptEventPublisher, "scriptEventPublisher must not be null");
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
    meterRegistry.counter(INVOCATIONS_METRIC).increment();

    if (maybeContext.isEmpty()) {
      return failure(
          GameplayStageCommandConstants.LOGIN_REQUIRED_CODE,
          GameplayStageCommandConstants.LOGIN_REQUIRED_MESSAGE,
          "error.login-required",
          Map.of(),
          tenantTag,
          null,
          null,
          null);
    }

    Optional<TextCommandPayload.PlayRequest> maybePlayRequest = command.playRequestPayload();
    if (maybePlayRequest.isEmpty()) {
      return failure(
          GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_CODE,
          GameplayStageCommandConstants.PLAY_INVALID_ARGUMENT_MESSAGE,
          "error.play.invalid-argument",
          Map.of(),
          tenantTag,
          null,
          null,
          null);
    }
    TextCommandPayload.PlayRequest playRequest = maybePlayRequest.orElseThrow();

    SessionContext context = maybeContext.get();
    try (GameplayLoggingContext baseContext =
        GameplayLoggingContext.open(Long.toString(context.tenantId()), null, null, null)) {
      Optional<ResolvedPlaySelection> maybeSelection = resolveSelection(playRequest);
      if (maybeSelection.isEmpty()) {
        return failure(
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE,
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_MESSAGE,
            "error.play.selection-required",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }

      ResolvedPlaySelection selection = maybeSelection.orElseThrow();
      Optional<GameplayWorldCatalog.WorldView> maybeWorld =
          gameplayWorldCatalog.resolveWorld(selection.worldSelector());
      if (maybeWorld.isEmpty()) {
        return failure(
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE,
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_MESSAGE,
            "error.play.selection-required",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }

      GameplayWorldCatalog.WorldView selectedWorld = maybeWorld.get();
      FirstPartyConnectContextResolution connectContextResolution =
          FirstPartyConnectContextResolution.resolve(
              context.sessionId(), context, firstPartyConnectContextRegistry);
      if (connectContextResolution.invalid()) {
        return connectContextInvalidFailure(
            tenantTag,
            context.bootstrapGameInstanceId() > 0
                ? Long.toString(context.bootstrapGameInstanceId())
                : null);
      }
      Optional<GameplayWorldCatalog.RealmView> maybeRealm =
          selection.explicitRealmSelector() != null
              ? gameplayWorldCatalog.resolveRealm(selectedWorld, selection.explicitRealmSelector())
              : selectDefaultRealm(selectedWorld, connectContextResolution.connectContext());
      if (maybeRealm.isEmpty()) {
        return failure(
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE,
            explicitRealmSelectionMessage(selectedWorld),
            "error.play.realm-selection-required",
            Map.of("worldSlug", selectedWorld.slug()),
            tenantTag,
            null,
            null,
            null);
      }

      GameplayWorldCatalog.RealmView selectedRealm = maybeRealm.orElseThrow();
      String selectedTenantTag = Long.toString(selectedRealm.tenantId());
      try (GameplayLoggingContext worldContext =
          GameplayLoggingContext.open(
              selectedTenantTag, Long.toString(selectedRealm.gameInstanceId()), null, null)) {
        Optional<PlayCommandHandlingResult> connectScopeFailure =
            validateFirstPartyConnectScope(
                connectContextResolution, selectedWorld, selectedRealm, selectedTenantTag);
        if (connectScopeFailure.isPresent()) {
          return connectScopeFailure.get();
        }
        Optional<PlayCommandHandlingResult> authorityFailure =
            validateRuntimeAdmission(
                context,
                selectedWorld,
                selectedRealm,
                selectedTenantTag,
                selection.characterSelector());
        if (authorityFailure.isPresent()) {
          return authorityFailure.get();
        }

        String character = selection.characterSelector();
        String characterName = resolveCharacterName(context, character);
        if (selectedRealm.requiresCharacterSelection() && !StringUtils.hasText(character)) {
          return failure(
              "PLAY_SELECTION_REQUIRED",
              characterSelectionMessage(selectedWorld, selectedRealm),
              "error.play.character-selection-required",
              Map.of(
                  "worldSlug",
                  selectedWorld.slug(),
                  "realmSlug",
                  selectedRealm.slug(),
                  "playUsage",
                  playUsage(selectedWorld, selectedRealm),
                  "charsUsage",
                  charsUsage(selectedWorld, selectedRealm)),
              selectedTenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
              null,
              null);
        }
        long gameInstanceId = selectedRealm.gameInstanceId();
        Optional<PlayCommandHandlingResult> moderationFailure =
            validateModerationPolicy(context, selectedRealm, selectedTenantTag);
        if (moderationFailure.isPresent()) {
          return moderationFailure.get();
        }
        long characterId = resolveCharacterId(context, selectedRealm, character, characterName);
        try (GameplayLoggingContext gameplayContext =
            GameplayLoggingContext.open(
                selectedTenantTag,
                Long.toString(gameInstanceId),
                Long.toString(characterId),
                null)) {
          if (StringUtils.hasText(context.roomInstanceId())
              && context.gameInstanceId() == gameInstanceId
              && context.characterId() == characterId
              && Objects.equals(
                  normalizeName(context.characterName()), normalizeName(characterName))) {
            resumeCounter.increment();
            publishCommandEvent(context);
            LOG.debug(
                "PLAY resumed existing gameplay binding for tenant {} gameInstance {} character {} on session {}",
                selectedRealm.tenantId(),
                gameInstanceId,
                characterId,
                context.sessionId());
            return new PlayCommandHandlingResult(
                CommandEnqueueResult.success(),
                List.of(successNotice(selectedWorld.slug(), selectedRealm.slug(), character)),
                true);
          }

          boolean freshEntryFallback =
              maybeRecordFreshEntryFallback(
                  context, selectedRealm, character, gameInstanceId, characterId);

          Optional<SessionContext> existingBinding =
              sessionAuthenticationService
                  .resolveByGameplayIdentity(selectedRealm.tenantId(), gameInstanceId, characterId)
                  .filter(SessionContext::hasGameplayRegionBinding);
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
                  selectedRealm.tenantId(),
                  context.accountId(),
                  context.loginName(),
                  characterId,
                  characterName,
                  gameInstanceId,
                  roomInstanceId,
                  context.jwt(),
                  context.localeTag(),
                  context.bootstrapGameInstanceId(),
                  selectedWorld.slug(),
                  selectedRealm.slug(),
                  selectedRealm.pointerVersion(),
                  selectedRealm.stateScope(),
                  context.connectScopeId(),
                  context.connectRequestId());
          sessionContextService.save(updated);
          gameplayPresenceLifecycleService.registerConnected(updated);
          publishCommandEvent(updated);
          if (!resumedOrTookOver) {
            publishSpawnEvent(updated);
          }

          return new PlayCommandHandlingResult(
              CommandEnqueueResult.success(),
              List.of(successNotice(selectedWorld.slug(), selectedRealm.slug(), character)),
              resumedOrTookOver || freshEntryFallback);
        }
      }
    }
  }

  private Optional<PlayCommandHandlingResult> validateModerationPolicy(
      SessionContext context, GameplayWorldCatalog.RealmView selectedRealm, String tenantTag) {
    var decision =
        moderationPolicyClient.evaluateGameplayAdmission(
            selectedRealm.tenantId(), context.accountId());
    if (decision.hasError()
        && decision.getError().getCode() != null
        && !decision.getError().getCode().isBlank()) {
      return Optional.of(
          failure(
              "MODERATION_POLICY_UNAVAILABLE",
              "Gameplay admission policy unavailable",
              "error.play.moderation-policy-unavailable",
              Map.of("errorCode", decision.getError().getCode()),
              tenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
              null,
              null));
    }
    if (!decision.getAllowed()) {
      return Optional.of(
          failure(
              "MODERATION_POLICY_DENIED",
              "Gameplay admission denied by moderation policy",
              "error.play.moderation-policy-denied",
              Map.of("action", decision.getAction()),
              tenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
              null,
              null));
    }
    return Optional.empty();
  }

  private Optional<PlayCommandHandlingResult> validateFirstPartyConnectScope(
      FirstPartyConnectContextResolution connectContextResolution,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      String tenantTag) {
    return connectContextResolution
        .connectContext()
        .flatMap(
            connectContext -> {
              if (!connectContext.hasCompleteRoutingScope()) {
                return Optional.of(
                    connectContextInvalidFailure(
                        tenantTag, Long.toString(selectedRealm.gameInstanceId())));
              }
              if (!GameplayAdmissionPointerSnapshots.sameBootstrapRoute(
                  connectContext,
                  selectedRealm.tenantId(),
                  selectedRealm.gameInstanceId(),
                  selectedWorld.slug(),
                  selectedRealm.slug(),
                  selectedRealm.pointerVersion())) {
                return Optional.of(
                    failure(
                        "CONNECT_SCOPE_MISMATCH",
                        "Connect scope mismatch",
                        "error.play.connect-scope-mismatch",
                        Map.of(),
                        tenantTag,
                        Long.toString(selectedRealm.gameInstanceId()),
                        null,
                        null));
              }
              return Optional.empty();
            });
  }

  private PlayCommandHandlingResult connectContextInvalidFailure(
      String tenantTag, String gameInstanceTag) {
    return failure(
        "CONNECT_CONTEXT_INVALID",
        "Connect context invalid",
        "error.play.connect-context-invalid",
        Map.of(),
        tenantTag,
        gameInstanceTag,
        null,
        null);
  }

  private PlayCommandHandlingResult failure(
      String errorCode,
      String message,
      String messageKey,
      Map<String, String> arguments,
      String tenantTag,
      String gameInstanceTag,
      String characterTag,
      RuntimeException ex) {
    meterRegistry.counter(FAILURES_METRIC, "error", errorCode).increment();
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
        CommandEnqueueResult.failure(errorCode, message),
        List.of(PlayerOutput.error(errorCode, message, messageKey, arguments)));
  }

  private long resolveCharacterId(
      SessionContext context,
      GameplayWorldCatalog.RealmView selectedRealm,
      String requestedCharacter,
      String characterName) {
    if (context.characterId() > 0) {
      return context.characterId();
    }
    if (StringUtils.hasText(characterName)) {
      Optional<net.firedevops.firemud.entitymanagement.v1.Character> character =
          entityManagementClient.findCharacterByName(
              context, toPlayableStateScope(selectedRealm), characterName.trim());
      if (character.isPresent() && StringUtils.hasText(character.get().getId())) {
        return Long.parseLong(character.get().getId());
      }
    }
    if (!StringUtils.hasText(requestedCharacter)) {
      return context.accountId();
    }
    return Math.floorMod(
            Objects.hash(
                selectedRealm.tenantId(),
                selectedRealm.gameInstanceId(),
                characterName.trim().toLowerCase()),
            Integer.MAX_VALUE - 1)
        + 1L;
  }

  private String resolveCharacterName(SessionContext context, String character) {
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
    return "character-" + context.accountId();
  }

  private boolean handleExistingBinding(
      SessionContext incoming, SessionContext existing, long gameInstanceId, long characterId) {
    if (existing.sessionId() == incoming.sessionId()) {
      resumeCounter.increment();
      LOG.debug(
          "PLAY resumed gameplay binding for tenant {} gameInstance {} character {} on session {}",
          existing.tenantId(),
          gameInstanceId,
          characterId,
          incoming.sessionId());
      return true;
    }

    takeoverCounter.increment();
    LOG.info(
        "PLAY taking over gameplay binding tenant {} gameInstance {} character {} from session {} to {}",
        existing.tenantId(),
        gameInstanceId,
        characterId,
        existing.sessionId(),
        incoming.sessionId());
    gameplayPresenceLifecycleService.recordDisconnected(
        existing.sessionId(), AccountRecentPresenceDisposition.TAKEOVER);
    sessionContextService.deleteBySessionId(existing.tenantId(), existing.sessionId());
    return true;
  }

  private String formatSuccessResponse(String world, String realm, String character) {
    String suffix = StringUtils.hasText(character) ? " as " + character : "";
    return "Entered world: " + displaySelection(world, realm) + suffix;
  }

  private PlayerOutput successNotice(String world, String realm, String character) {
    String characterSuffix = StringUtils.hasText(character) ? " as " + character : "";
    return PlayerOutput.notice(
        formatSuccessResponse(world, realm, character),
        "notice.world.entered",
        Map.of(
            "worldName", world,
            "realmName", realm == null ? "" : realm,
            "characterSuffix", characterSuffix));
  }

  private String normalizeName(String value) {
    return value == null ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
  }

  private void publishSpawnEvent(SessionContext context) {
    try {
      scriptEventPublisher.publishSpawnEvent(
          context,
          "play_entry",
          "play-spawn:"
              + context.sessionId()
              + ":"
              + context.gameInstanceId()
              + ":"
              + context.characterId()
              + ":"
              + context.pointerVersion());
    } catch (RuntimeException ex) {
      LOG.warn(
          "PLAY spawn event publish failed tenantId={} gameInstanceId={} characterId={} sessionId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          context.sessionId(),
          ex);
    }
  }

  private void publishCommandEvent(SessionContext context) {
    try {
      scriptEventPublisher.publishCommandEvent(context, scriptEventCommand(context));
    } catch (RuntimeException ex) {
      LOG.warn(
          "PLAY command event publish failed tenantId={} gameInstanceId={} characterId={} sessionId={}",
          context.tenantId(),
          context.gameInstanceId(),
          context.characterId(),
          context.sessionId(),
          ex);
    }
  }

  private static GameplayCommand scriptEventCommand(SessionContext context) {
    GameplayCommand gameplayCommand = new GameplayCommand();
    gameplayCommand.setCommandId(
        "play-command:"
            + context.sessionId()
            + ":"
            + context.gameInstanceId()
            + ":"
            + context.characterId()
            + ":"
            + context.pointerVersion());
    gameplayCommand.setCommandName(TextCommandType.PLAY.name());
    return gameplayCommand;
  }

  private Optional<PlayCommandHandlingResult> validateRuntimeAdmission(
      SessionContext context,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      String tenantTag,
      String requestedCharacter) {
    String requestId = context.sessionId() + ":" + UUID.randomUUID();
    long requestedCharacterId =
        resolveCharacterId(
            context,
            selectedRealm,
            requestedCharacter,
            resolveCharacterName(context, requestedCharacter));
    GetTenantMembershipForRuntimeResponse membershipResponse =
        accountClient.getTenantMembershipForRuntime(
            Long.toString(context.accountId()), Long.toString(selectedRealm.tenantId()), requestId);
    Optional<PlayCommandHandlingResult> membershipFailure =
        validateMembershipResponse(
            membershipResponse,
            context,
            tenantTag,
            selectedWorld,
            selectedRealm,
            requestedCharacterId,
            requestId);
    if (membershipFailure.isPresent()) {
      return membershipFailure;
    }

    GetTenantEntitlementsForRuntimeResponse entitlementResponse =
        accountClient.getTenantEntitlementsForRuntime(
            Long.toString(selectedRealm.tenantId()), requestId);
    return validateEntitlementsResponse(
        entitlementResponse,
        context,
        tenantTag,
        selectedWorld,
        selectedRealm,
        requestedCharacterId);
  }

  private Optional<PlayCommandHandlingResult> validateMembershipResponse(
      GetTenantMembershipForRuntimeResponse response,
      SessionContext context,
      String tenantTag,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      long requestedCharacterId,
      String requestId) {
    if (!selectedRealm.visible()) {
      GetRealmAccessGrantForRuntimeResponse grantResponse =
          accountClient.getRealmAccessGrantForRuntime(
              Long.toString(context.accountId()),
              Long.toString(selectedRealm.tenantId()),
              selectedWorld.slug(),
              selectedRealm.slug(),
              requestId);
      Optional<ErrorDetail> grantError = extractError(grantResponse.getError());
      if (grantError.isPresent() && isAuthorityUnavailable(grantError.get())) {
        recordResumeDeniedIfApplicable(
            context,
            selectedWorld.slug(),
            selectedRealm.slug(),
            selectedRealm.pointerVersion(),
            selectedRealm.gameInstanceId(),
            requestedCharacterId,
            tenantTag,
            "authority_unavailable");
        return Optional.of(
            failure(
                GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_CODE,
                GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_MESSAGE,
                "error.play.membership-unavailable",
                Map.of(),
                tenantTag,
                Long.toString(selectedRealm.gameInstanceId()),
                Long.toString(requestedCharacterId),
                null));
      }
      if (grantError.isPresent() || !grantResponse.getGranted()) {
        recordResumeDeniedIfApplicable(
            context,
            selectedWorld.slug(),
            selectedRealm.slug(),
            selectedRealm.pointerVersion(),
            selectedRealm.gameInstanceId(),
            requestedCharacterId,
            tenantTag,
            "access_denied");
        return Optional.of(
            failure(
                GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE,
                GameplayStageCommandConstants.WORLD_ACCESS_DENIED_MESSAGE,
                "error.play.world-access-denied",
                Map.of(),
                tenantTag,
                Long.toString(selectedRealm.gameInstanceId()),
                Long.toString(requestedCharacterId),
                null));
      }
      return Optional.empty();
    }
    Optional<ErrorDetail> maybeError = extractError(response.getError());
    if (maybeError.isPresent()) {
      ErrorDetail error = maybeError.get();
      if ("NOT_FOUND".equalsIgnoreCase(error.getCode())) {
        recordResumeDeniedIfApplicable(
            context,
            selectedWorld.slug(),
            selectedRealm.slug(),
            selectedRealm.pointerVersion(),
            selectedRealm.gameInstanceId(),
            requestedCharacterId,
            tenantTag,
            "access_denied");
        return Optional.of(
            failure(
                GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE,
                GameplayStageCommandConstants.WORLD_ACCESS_DENIED_MESSAGE,
                "error.play.world-access-denied",
                Map.of(),
                tenantTag,
                Long.toString(selectedRealm.gameInstanceId()),
                Long.toString(requestedCharacterId),
                null));
      }
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.slug(),
          selectedRealm.slug(),
          selectedRealm.pointerVersion(),
          selectedRealm.gameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "authority_unavailable");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_CODE,
              GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_MESSAGE,
              "error.play.membership-unavailable",
              Map.of(),
              tenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    if (!response.getGameplayAdmissionAllowed()) {
      if (isPublicProductionRealm(selectedRealm)) {
        EnsurePublicProductionPlayerMembershipResponse ensured =
            accountClient.ensurePublicProductionPlayerMembership(
                Long.toString(context.accountId()),
                Long.toString(selectedRealm.tenantId()),
                selectedWorld.slug(),
                selectedRealm.slug(),
                requestId);
        Optional<ErrorDetail> ensureError = extractError(ensured.getError());
        if (ensureError.isEmpty() && ensured.getGameplayAdmissionAllowed()) {
          return Optional.empty();
        }
        if (ensureError.isPresent() && isAuthorityUnavailable(ensureError.get())) {
          recordResumeDeniedIfApplicable(
              context,
              selectedWorld.slug(),
              selectedRealm.slug(),
              selectedRealm.pointerVersion(),
              selectedRealm.gameInstanceId(),
              requestedCharacterId,
              tenantTag,
              "authority_unavailable");
          return Optional.of(
              failure(
                  GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_CODE,
                  GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_MESSAGE,
                  "error.play.membership-unavailable",
                  Map.of(),
                  tenantTag,
                  Long.toString(selectedRealm.gameInstanceId()),
                  Long.toString(requestedCharacterId),
                  null));
        }
      }
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.slug(),
          selectedRealm.slug(),
          selectedRealm.pointerVersion(),
          selectedRealm.gameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "access_denied");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE,
              GameplayStageCommandConstants.WORLD_ACCESS_DENIED_MESSAGE,
              "error.play.world-access-denied",
              Map.of(),
              tenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    return Optional.empty();
  }

  private Optional<PlayCommandHandlingResult> validateEntitlementsResponse(
      GetTenantEntitlementsForRuntimeResponse response,
      SessionContext context,
      String tenantTag,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      long requestedCharacterId) {
    Optional<ErrorDetail> maybeError = extractError(response.getError());
    if (maybeError.isPresent()) {
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.slug(),
          selectedRealm.slug(),
          selectedRealm.pointerVersion(),
          selectedRealm.gameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "authority_unavailable");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE,
              GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE,
              "error.play.entitlement-unavailable",
              Map.of(),
              tenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    if (!response.getGameplayAvailable()) {
      recordResumeDeniedIfApplicable(
          context,
          selectedWorld.slug(),
          selectedRealm.slug(),
          selectedRealm.pointerVersion(),
          selectedRealm.gameInstanceId(),
          requestedCharacterId,
          tenantTag,
          "tenant_unavailable");
      return Optional.of(
          failure(
              GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_CODE,
              GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_MESSAGE,
              "error.play.billing-blocked",
              Map.of(),
              tenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
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

  private boolean isPublicProductionRealm(GameplayWorldCatalog.RealmView realm) {
    return realm.visible() && realm.publicProductionRealm();
  }

  private PlayableStateScope toPlayableStateScope(GameplayWorldCatalog.RealmView realm) {
    return switch (realm.stateScope()) {
      case "SHARED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case "ISOLATED" -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
      default -> PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED;
    };
  }

  private boolean isAuthorityUnavailable(ErrorDetail error) {
    String code = Optional.ofNullable(error.getCode()).orElse("");
    return GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_CODE.equalsIgnoreCase(code);
  }

  private boolean maybeRecordFreshEntryFallback(
      SessionContext context,
      GameplayWorldCatalog.RealmView selectedRealm,
      String requestedCharacter,
      long requestedGameInstanceId,
      long requestedCharacterId) {
    if (context.gameInstanceId() != requestedGameInstanceId
        || context.characterId() != requestedCharacterId) {
      return false;
    }
    if (StringUtils.hasText(context.roomInstanceId())
        && Objects.equals(
            normalizeName(context.characterName()),
            normalizeName(resolveCharacterName(context, requestedCharacter)))) {
      return false;
    }
    meterRegistry
        .counter(FRESH_ENTRY_FALLBACK_METRIC, "reason", "stale_or_missing_context")
        .increment();
    LOG.info(
        "PLAY falling back to fresh entry for tenant {} gameInstance {} character {} on session {} because resumable context was stale or incomplete",
        selectedRealm.tenantId(),
        selectedRealm.gameInstanceId(),
        requestedCharacterId,
        context.sessionId());
    return true;
  }

  private Optional<ResolvedPlaySelection> resolveSelection(
      TextCommandPayload.PlayRequest playRequest) {
    String worldSelector = playRequest.worldSelector();
    if (!StringUtils.hasText(worldSelector)) {
      return Optional.empty();
    }
    String secondSelector =
        StringUtils.hasText(playRequest.realmSelector())
            ? playRequest.realmSelector().trim()
            : null;
    String characterSelector =
        StringUtils.hasText(playRequest.characterSelector())
            ? playRequest.characterSelector().trim()
            : null;
    if (characterSelector != null) {
      return Optional.of(
          new ResolvedPlaySelection(worldSelector.trim(), secondSelector, characterSelector));
    }

    Optional<GameplayWorldCatalog.WorldView> maybeWorld =
        gameplayWorldCatalog.resolveWorld(worldSelector);
    if (maybeWorld.isPresent()
        && StringUtils.hasText(secondSelector)
        && !gameplayWorldCatalog.hasVisibleRealm(maybeWorld.orElseThrow(), secondSelector)) {
      return Optional.of(new ResolvedPlaySelection(worldSelector.trim(), null, secondSelector));
    }
    return Optional.of(new ResolvedPlaySelection(worldSelector.trim(), secondSelector, null));
  }

  private Optional<GameplayWorldCatalog.RealmView> selectDefaultRealm(
      GameplayWorldCatalog.WorldView selectedWorld,
      Optional<FirstPartyConnectContext> connectContext) {
    if (connectContext.isPresent()
        && StringUtils.hasText(connectContext.orElseThrow().realmSlug())) {
      Optional<GameplayWorldCatalog.RealmView> hintedRealm =
          gameplayWorldCatalog.resolveRealm(
              selectedWorld, connectContext.orElseThrow().realmSlug());
      if (hintedRealm.isPresent()) {
        return hintedRealm;
      }
    }
    if (gameplayWorldCatalog.requiresExplicitRealmSelection(selectedWorld)) {
      return Optional.empty();
    }
    return gameplayWorldCatalog.resolveDefaultRealm(selectedWorld);
  }

  private String explicitRealmSelectionMessage(GameplayWorldCatalog.WorldView selectedWorld) {
    return "Selection required. Use PLAY "
        + selectedWorld.slug()
        + " <realm> [character] or browse REALMS first.";
  }

  private String characterSelectionMessage(
      GameplayWorldCatalog.WorldView selectedWorld, GameplayWorldCatalog.RealmView selectedRealm) {
    return "Selection required. Use "
        + playUsage(selectedWorld, selectedRealm)
        + " or browse "
        + charsUsage(selectedWorld, selectedRealm)
        + " first.";
  }

  private String displaySelection(String world, String realm) {
    if (!StringUtils.hasText(realm)
        || gameplayWorldCatalog
            .resolveWorld(world)
            .flatMap(selectedWorld -> gameplayWorldCatalog.resolveRealm(selectedWorld, realm))
            .map(GameplayWorldCatalog.RealmView::publicProductionRealm)
            .orElse(false)) {
      return world;
    }
    return world + " (" + realm + ")";
  }

  private String playUsage(
      GameplayWorldCatalog.WorldView selectedWorld, GameplayWorldCatalog.RealmView selectedRealm) {
    return "PLAY "
        + selectedWorld.slug()
        + (selectedRealm.publicProductionRealm() ? "" : " " + selectedRealm.slug())
        + " <character>";
  }

  private String charsUsage(
      GameplayWorldCatalog.WorldView selectedWorld, GameplayWorldCatalog.RealmView selectedRealm) {
    return "CHARS "
        + selectedWorld.slug()
        + (selectedRealm.publicProductionRealm() ? "" : " " + selectedRealm.slug());
  }

  private record ResolvedPlaySelection(
      String worldSelector, String explicitRealmSelector, String characterSelector) {}

  private void recordResumeDeniedIfApplicable(
      SessionContext context,
      String requestedWorldSlug,
      String requestedRealmSlug,
      long requestedPointerVersion,
      long requestedGameInstanceId,
      long requestedCharacterId,
      String tenantTag,
      String reason) {
    boolean sameGameplayIdentity =
        context.gameInstanceId() == requestedGameInstanceId
            && context.characterId() == requestedCharacterId;
    boolean sameVisibleRealm =
        sameSlug(context.worldSlug(), requestedWorldSlug)
            && sameSlug(context.realmSlug(), requestedRealmSlug);
    if (!sameGameplayIdentity && !sameVisibleRealm) {
      return;
    }
    meterRegistry.counter(RESUME_DENIED_METRIC, "reason", reason).increment();
    gameplayPresenceLifecycleService.clearGameplayBinding(context, reason);
    sessionContextService.save(
        new SessionContext(
            context.sessionId(),
            context.tenantId(),
            0L,
            null,
            0L,
            null,
            0L,
            null,
            null,
            context.localeTag(),
            context.bootstrapGameInstanceId(),
            requestedWorldSlug,
            requestedRealmSlug,
            requestedPointerVersion,
            null,
            context.connectScopeId(),
            context.connectRequestId()));
    LOG.debug(
        "Cleared stale gameplay binding after denied reconnect-style PLAY for tenant {} session {} world {} realm {} reason {}",
        tenantTag,
        context.sessionId(),
        requestedWorldSlug,
        requestedRealmSlug,
        reason);
  }

  private boolean sameSlug(String left, String right) {
    return StringUtils.hasText(left) && StringUtils.hasText(right) && left.equalsIgnoreCase(right);
  }
}
