package net.firedevops.firemud.gamesession.command.text;

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
import net.firedevops.firemud.common.gameplay.GameplayCatalogProperties;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
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
  private final EntityManagementClient entityManagementClient;
  private final FirstPartyConnectContextRegistry firstPartyConnectContextRegistry;
  private final GameplayPresenceLifecycleService gameplayPresenceLifecycleService;
  private final MeterRegistry meterRegistry;
  private final Counter takeoverCounter;
  private final Counter resumeCounter;

  public PlayCommandHandler(
      SessionAuthenticationService sessionAuthenticationService,
      SessionContextService sessionContextService,
      GameplayWorldCatalog gameplayWorldCatalog,
      GameLogicProperties gameLogicProperties,
      AccountClient accountClient,
      EntityManagementClient entityManagementClient,
      FirstPartyConnectContextRegistry firstPartyConnectContextRegistry,
      GameplayPresenceLifecycleService gameplayPresenceLifecycleService,
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
    this.entityManagementClient =
        Objects.requireNonNull(entityManagementClient, "entityManagementClient must not be null");
    this.firstPartyConnectContextRegistry =
        Objects.requireNonNull(
            firstPartyConnectContextRegistry, "firstPartyConnectContextRegistry must not be null");
    this.gameplayPresenceLifecycleService =
        Objects.requireNonNull(
            gameplayPresenceLifecycleService, "gameplayPresenceLifecycleService must not be null");
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
      Optional<GameplayCatalogProperties.World> maybeWorld =
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

      GameplayCatalogProperties.World selectedWorld = maybeWorld.get();
      Optional<GameplayCatalogProperties.Realm> maybeRealm =
          selection.explicitRealmSelector() != null
              ? gameplayWorldCatalog.resolveRealm(selectedWorld, selection.explicitRealmSelector())
              : selectDefaultRealm(context, selectedWorld);
      if (maybeRealm.isEmpty()) {
        return failure(
            GameplayStageCommandConstants.PLAY_SELECTION_REQUIRED_CODE,
            explicitRealmSelectionMessage(selectedWorld),
            "error.play.realm-selection-required",
            Map.of("worldSlug", selectedWorld.getSlug()),
            tenantTag,
            null,
            null,
            null);
      }

      GameplayCatalogProperties.Realm selectedRealm = maybeRealm.orElseThrow();
      String selectedTenantTag = Long.toString(selectedRealm.getTenantId());
      try (GameplayLoggingContext worldContext =
          GameplayLoggingContext.open(
              selectedTenantTag, Long.toString(selectedRealm.getGameInstanceId()), null, null)) {
        Optional<PlayCommandHandlingResult> connectScopeFailure =
            validateFirstPartyConnectScope(
                context, selectedWorld, selectedRealm, selectedTenantTag);
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
        if (selectedRealm.isRequiresCharacterSelection() && !StringUtils.hasText(character)) {
          return failure(
              "PLAY_SELECTION_REQUIRED",
              characterSelectionMessage(selectedWorld, selectedRealm),
              "error.play.character-selection-required",
              Map.of(
                  "worldSlug",
                  selectedWorld.getSlug(),
                  "realmSlug",
                  selectedRealm.getSlug(),
                  "playUsage",
                  playUsage(selectedWorld, selectedRealm),
                  "charsUsage",
                  charsUsage(selectedWorld, selectedRealm)),
              selectedTenantTag,
              Long.toString(selectedRealm.getGameInstanceId()),
              null,
              null);
        }
        long gameInstanceId = selectedRealm.getGameInstanceId();
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
            LOG.debug(
                "PLAY resumed existing gameplay binding for tenant {} gameInstance {} character {} on session {}",
                selectedRealm.getTenantId(),
                gameInstanceId,
                characterId,
                context.sessionId());
            return new PlayCommandHandlingResult(
                CommandEnqueueResult.success(),
                List.of(successNotice(selectedWorld.getSlug(), selectedRealm.getSlug(), character)),
                true);
          }

          boolean freshEntryFallback =
              maybeRecordFreshEntryFallback(
                  context, selectedRealm, character, gameInstanceId, characterId);

          Optional<SessionContext> existingBinding =
              sessionContextService.findByGameplayIdentity(
                  selectedRealm.getTenantId(), gameInstanceId, characterId);
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
                  selectedRealm.getTenantId(),
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
          gameplayPresenceLifecycleService.registerConnected(updated);

          return new PlayCommandHandlingResult(
              CommandEnqueueResult.success(),
              List.of(successNotice(selectedWorld.getSlug(), selectedRealm.getSlug(), character)),
              resumedOrTookOver || freshEntryFallback);
        }
      }
    }
  }

  private Optional<PlayCommandHandlingResult> validateFirstPartyConnectScope(
      SessionContext context,
      GameplayCatalogProperties.World selectedWorld,
      GameplayCatalogProperties.Realm selectedRealm,
      String tenantTag) {
    return firstPartyConnectContextRegistry
        .find(context.sessionId())
        .filter(
            connectContext ->
                connectContext.tenantId() != selectedRealm.getTenantId()
                    || connectContext.gameInstanceId() != selectedRealm.getGameInstanceId()
                    || connectContext.pointerVersion() != selectedRealm.getPointerVersion()
                    || (StringUtils.hasText(connectContext.worldSlug())
                        && !selectedWorld.getSlug().equalsIgnoreCase(connectContext.worldSlug()))
                    || (StringUtils.hasText(connectContext.realmSlug())
                        && !selectedRealm.getSlug().equalsIgnoreCase(connectContext.realmSlug())))
        .map(
            ignored ->
                failure(
                    "CONNECT_SCOPE_MISMATCH",
                    "Connect scope mismatch",
                    "error.play.connect-scope-mismatch",
                    Map.of(),
                    tenantTag,
                    Long.toString(selectedRealm.getGameInstanceId()),
                    null,
                    null));
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
      GameplayCatalogProperties.Realm selectedRealm,
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
                selectedRealm.getTenantId(),
                selectedRealm.getGameInstanceId(),
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

  private Optional<PlayCommandHandlingResult> validateRuntimeAdmission(
      SessionContext context,
      GameplayCatalogProperties.World selectedWorld,
      GameplayCatalogProperties.Realm selectedRealm,
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
            Long.toString(context.accountId()),
            Long.toString(selectedRealm.getTenantId()),
            requestId);
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
            Long.toString(selectedRealm.getTenantId()), requestId);
    return validateEntitlementsResponse(
        entitlementResponse, context, tenantTag, selectedRealm, requestedCharacterId);
  }

  private Optional<PlayCommandHandlingResult> validateMembershipResponse(
      GetTenantMembershipForRuntimeResponse response,
      SessionContext context,
      String tenantTag,
      GameplayCatalogProperties.World selectedWorld,
      GameplayCatalogProperties.Realm selectedRealm,
      long requestedCharacterId,
      String requestId) {
    if (!selectedRealm.isVisible()) {
      GetRealmAccessGrantForRuntimeResponse grantResponse =
          accountClient.getRealmAccessGrantForRuntime(
              Long.toString(context.accountId()),
              Long.toString(selectedRealm.getTenantId()),
              selectedWorld.getSlug(),
              selectedRealm.getSlug(),
              requestId);
      Optional<ErrorDetail> grantError = extractError(grantResponse.getError());
      if (grantError.isPresent() && isAuthorityUnavailable(grantError.get())) {
        recordResumeDeniedIfApplicable(
            context,
            selectedRealm.getGameInstanceId(),
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
                Long.toString(selectedRealm.getGameInstanceId()),
                Long.toString(requestedCharacterId),
                null));
      }
      if (grantError.isPresent() || !grantResponse.getGranted()) {
        recordResumeDeniedIfApplicable(
            context,
            selectedRealm.getGameInstanceId(),
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
                Long.toString(selectedRealm.getGameInstanceId()),
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
            selectedRealm.getGameInstanceId(),
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
                Long.toString(selectedRealm.getGameInstanceId()),
                Long.toString(requestedCharacterId),
                null));
      }
      recordResumeDeniedIfApplicable(
          context,
          selectedRealm.getGameInstanceId(),
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
              Long.toString(selectedRealm.getGameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    if (!response.getGameplayAdmissionAllowed()) {
      if (isPublicProductionRealm(selectedRealm)) {
        EnsurePublicProductionPlayerMembershipResponse ensured =
            accountClient.ensurePublicProductionPlayerMembership(
                Long.toString(context.accountId()),
                Long.toString(selectedRealm.getTenantId()),
                selectedRealm.getSlug(),
                requestId);
        Optional<ErrorDetail> ensureError = extractError(ensured.getError());
        if (ensureError.isEmpty() && ensured.getGameplayAdmissionAllowed()) {
          return Optional.empty();
        }
        if (ensureError.isPresent() && isAuthorityUnavailable(ensureError.get())) {
          recordResumeDeniedIfApplicable(
              context,
              selectedRealm.getGameInstanceId(),
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
                  Long.toString(selectedRealm.getGameInstanceId()),
                  Long.toString(requestedCharacterId),
                  null));
        }
      }
      recordResumeDeniedIfApplicable(
          context,
          selectedRealm.getGameInstanceId(),
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
              Long.toString(selectedRealm.getGameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    return Optional.empty();
  }

  private Optional<PlayCommandHandlingResult> validateEntitlementsResponse(
      GetTenantEntitlementsForRuntimeResponse response,
      SessionContext context,
      String tenantTag,
      GameplayCatalogProperties.Realm selectedRealm,
      long requestedCharacterId) {
    Optional<ErrorDetail> maybeError = extractError(response.getError());
    if (maybeError.isPresent()) {
      recordResumeDeniedIfApplicable(
          context,
          selectedRealm.getGameInstanceId(),
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
              Long.toString(selectedRealm.getGameInstanceId()),
              Long.toString(requestedCharacterId),
              null));
    }
    if (!response.getGameplayAvailable()) {
      recordResumeDeniedIfApplicable(
          context,
          selectedRealm.getGameInstanceId(),
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
              Long.toString(selectedRealm.getGameInstanceId()),
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

  private boolean isPublicProductionRealm(GameplayCatalogProperties.Realm realm) {
    return realm.isVisible() && "production".equalsIgnoreCase(realm.getSlug());
  }

  private PlayableStateScope toPlayableStateScope(GameplayCatalogProperties.Realm realm) {
    return switch (realm.getStateScope()) {
      case SHARED -> PlayableStateScope.PLAYABLE_STATE_SCOPE_SHARED;
      case ISOLATED -> PlayableStateScope.PLAYABLE_STATE_SCOPE_ISOLATED;
    };
  }

  private boolean isAuthorityUnavailable(ErrorDetail error) {
    String code = Optional.ofNullable(error.getCode()).orElse("");
    return GameplayStageCommandConstants.MEMBERSHIP_AUTH_UNAVAILABLE_CODE.equalsIgnoreCase(code);
  }

  private boolean maybeRecordFreshEntryFallback(
      SessionContext context,
      GameplayCatalogProperties.Realm selectedRealm,
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
        selectedRealm.getTenantId(),
        selectedRealm.getGameInstanceId(),
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

    Optional<GameplayCatalogProperties.World> maybeWorld =
        gameplayWorldCatalog.resolveWorld(worldSelector);
    if (maybeWorld.isPresent()
        && StringUtils.hasText(secondSelector)
        && !gameplayWorldCatalog.hasVisibleRealm(maybeWorld.orElseThrow(), secondSelector)) {
      return Optional.of(new ResolvedPlaySelection(worldSelector.trim(), null, secondSelector));
    }
    return Optional.of(new ResolvedPlaySelection(worldSelector.trim(), secondSelector, null));
  }

  private Optional<GameplayCatalogProperties.Realm> selectDefaultRealm(
      SessionContext context, GameplayCatalogProperties.World selectedWorld) {
    Optional<FirstPartyConnectContext> connectContext =
        firstPartyConnectContextRegistry.find(context.sessionId());
    if (connectContext.isPresent()
        && StringUtils.hasText(connectContext.orElseThrow().realmSlug())) {
      Optional<GameplayCatalogProperties.Realm> hintedRealm =
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

  private String explicitRealmSelectionMessage(GameplayCatalogProperties.World selectedWorld) {
    return "Selection required. Use PLAY "
        + selectedWorld.getSlug()
        + " <realm> [character] or browse REALMS first.";
  }

  private String characterSelectionMessage(
      GameplayCatalogProperties.World selectedWorld,
      GameplayCatalogProperties.Realm selectedRealm) {
    return "Selection required. Use "
        + playUsage(selectedWorld, selectedRealm)
        + " or browse "
        + charsUsage(selectedWorld, selectedRealm)
        + " first.";
  }

  private String displaySelection(String world, String realm) {
    if (!StringUtils.hasText(realm) || "production".equalsIgnoreCase(realm)) {
      return world;
    }
    return world + " (" + realm + ")";
  }

  private String playUsage(
      GameplayCatalogProperties.World selectedWorld,
      GameplayCatalogProperties.Realm selectedRealm) {
    return "PLAY "
        + selectedWorld.getSlug()
        + ("production".equalsIgnoreCase(selectedRealm.getSlug())
            ? ""
            : " " + selectedRealm.getSlug())
        + " <character>";
  }

  private String charsUsage(
      GameplayCatalogProperties.World selectedWorld,
      GameplayCatalogProperties.Realm selectedRealm) {
    return "CHARS "
        + selectedWorld.getSlug()
        + ("production".equalsIgnoreCase(selectedRealm.getSlug())
            ? ""
            : " " + selectedRealm.getSlug());
  }

  private record ResolvedPlaySelection(
      String worldSelector, String explicitRealmSelector, String characterSelector) {}

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
    meterRegistry.counter(RESUME_DENIED_METRIC, "reason", reason).increment();
  }
}
