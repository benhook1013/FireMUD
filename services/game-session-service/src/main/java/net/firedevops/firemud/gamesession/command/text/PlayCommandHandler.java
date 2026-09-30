package net.firedevops.firemud.gamesession.command.text;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.firedevops.firemud.account.v1.GetRealmAccessGrantForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantEntitlementsForRuntimeResponse;
import net.firedevops.firemud.account.v1.GetTenantMembershipForRuntimeResponse;
import net.firedevops.firemud.entitymanagement.v1.Character;
import net.firedevops.firemud.entitymanagement.v1.ListCharactersByAccountResponse;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import net.firedevops.firemud.gamesession.client.AccountClient;
import net.firedevops.firemud.gamesession.client.EntityManagementClient;
import net.firedevops.firemud.gamesession.client.ModerationPolicyClient;
import net.firedevops.firemud.gamesession.config.GameLogicProperties;
import net.firedevops.firemud.gamesession.dto.CommandEnqueueResult;
import net.firedevops.firemud.gamesession.entity.GameplayCommand;
import net.firedevops.firemud.gamesession.presentation.PlayerOutput;
import net.firedevops.firemud.gamesession.service.AccountRecentPresenceDisposition;
import net.firedevops.firemud.gamesession.service.DirectTextConnectScopeSessionStore;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContext;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextRegistry;
import net.firedevops.firemud.gamesession.service.FirstPartyConnectContextResolution;
import net.firedevops.firemud.gamesession.service.GameplayAdmissionPointerSnapshots;
import net.firedevops.firemud.gamesession.service.GameplayPresenceLifecycleService;
import net.firedevops.firemud.gamesession.service.PositiveLongParsing;
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
  private static final String PUBLIC_PRODUCTION_ADMISSION_DENIED_CODE =
      "PUBLIC_PRODUCTION_ADMISSION_DENIED";
  private static final String PUBLIC_PRODUCTION_ADMISSION_DENIED_MESSAGE =
      "Public joining is not available for this world.";

  private final SessionAuthenticationService sessionAuthenticationService;
  private final SessionContextService sessionContextService;
  private final SessionRoutingNormalizationService sessionRoutingNormalizationService;
  private final GameplayWorldCatalog gameplayWorldCatalog;
  private final DirectTextConnectScopeSessionStore connectScopeSessionStore;
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

  @org.springframework.beans.factory.annotation.Autowired
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
      MeterRegistry meterRegistry,
      DirectTextConnectScopeSessionStore connectScopeSessionStore) {
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
    this.connectScopeSessionStore =
        Objects.requireNonNull(
            connectScopeSessionStore, "connectScopeSessionStore must not be null");
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

      ResolvedPlaySelection requestedSelection = maybeSelection.orElseThrow();
      GameplayWorldCatalog.DiscoverySnapshot currentCatalog;
      try {
        currentCatalog = gameplayWorldCatalog.readDiscoverySnapshot();
      } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
        return failure(
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE,
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE,
            "error.play.authority-unavailable",
            Map.of(),
            tenantTag,
            null,
            null,
            ex);
      }
      WorldSelectorResolution worldSelection =
          resolvePlayWorld(context, requestedSelection.worldSelector(), currentCatalog);
      if (worldSelection instanceof WorldSelectorResolution.Unavailable) {
        return failure(
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE,
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE,
            "error.play.authority-unavailable",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }
      if (worldSelection instanceof WorldSelectorResolution.Stale) {
        return failure(
            "CONNECT_SCOPE_MISMATCH",
            "World selection is stale; run WORLDS again.",
            "error.play.connect-scope-mismatch",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }
      if (worldSelection instanceof WorldSelectorResolution.Invalid) {
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
      GameplayWorldCatalog.WorldView selectedWorld =
          ((WorldSelectorResolution.Selected) worldSelection).world();
      ResolvedPlaySelection selection = disambiguateSelection(requestedSelection, selectedWorld);
      if (!gameplayWorldCatalog.hasValidPublicProductionRealm(currentCatalog, selectedWorld)) {
        return failure(
            "ADMISSION_POINTER_UNAVAILABLE",
            "Gameplay admission pointer is unavailable",
            "error.play.authority-unavailable",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }

      boolean numericRealmSelector =
          GameplayWorldCatalog.isOrdinalSelector(selection.explicitRealmSelector());
      RealmSelectorResolution realmSelection =
          numericRealmSelector
              ? resolvePlayRealm(context, selectedWorld, selection.explicitRealmSelector())
              : new RealmSelectorResolution.NoSelection();
      if (realmSelection instanceof RealmSelectorResolution.Unavailable) {
        return failure(
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE,
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE,
            "error.play.authority-unavailable",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }
      if (realmSelection instanceof RealmSelectorResolution.Stale) {
        return failure(
            "CONNECT_SCOPE_MISMATCH",
            "Realm selection is stale; run REALMS again.",
            "error.play.connect-scope-mismatch",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }
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
      if (!numericRealmSelector) {
        realmSelection =
            StringUtils.hasText(selection.explicitRealmSelector())
                ? resolvePlayRealm(context, selectedWorld, selection.explicitRealmSelector())
                : new RealmSelectorResolution.NoSelection();
      }
      if (realmSelection instanceof RealmSelectorResolution.Unavailable) {
        return failure(
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE,
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE,
            "error.play.authority-unavailable",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }
      if (realmSelection instanceof RealmSelectorResolution.Stale) {
        return failure(
            "CONNECT_SCOPE_MISMATCH",
            "Realm selection is stale; run REALMS again.",
            "error.play.connect-scope-mismatch",
            Map.of(),
            tenantTag,
            null,
            null,
            null);
      }
      Optional<GameplayWorldCatalog.RealmView> maybeRealm =
          realmSelection instanceof RealmSelectorResolution.Selected selected
              ? Optional.of(selected.realm())
              : selection.explicitRealmSelector() != null
                  ? Optional.empty()
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
      boolean currentPointerMatches;
      try {
        currentPointerMatches =
            gameplayWorldCatalog.matchesCurrentAdmissionPointer(selectedWorld, selectedRealm);
      } catch (GameplayWorldCatalog.AuthorityPointerReadUnavailableException ex) {
        return failure(
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE,
            GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE,
            "error.play.authority-unavailable",
            Map.of(),
            selectedTenantTag,
            Long.toString(selectedRealm.gameInstanceId()),
            null,
            ex);
      }
      if (!currentPointerMatches) {
        return admissionPointerUnavailableFailure(
            selectedTenantTag, Long.toString(selectedRealm.gameInstanceId()));
      }
      try (GameplayLoggingContext worldContext =
          GameplayLoggingContext.open(
              selectedTenantTag, Long.toString(selectedRealm.gameInstanceId()), null, null)) {
        Optional<PlayCommandHandlingResult> connectScopeFailure =
            validateFirstPartyConnectScope(
                connectContextResolution, selectedWorld, selectedRealm, selectedTenantTag);
        if (connectScopeFailure.isPresent()) {
          return connectScopeFailure.get();
        }
        String character = selection.characterSelector();
        long gameInstanceId = selectedRealm.gameInstanceId();
        Optional<PlayCommandHandlingResult> authorityFailure =
            validateRuntimeAdmission(context, selectedWorld, selectedRealm, selectedTenantTag, 0L);
        if (authorityFailure.isPresent()) {
          PlayCommandHandlingResult failure = authorityFailure.orElseThrow();
          if (!gameplayWorldCatalog.isPubliclyDiscoverable(currentCatalog, selectedWorld)
              && isDefinitivePrivateWorldDenial(failure)) {
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
          return failure;
        }
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
        ResolvedCharacter resolvedCharacter;
        try {
          resolvedCharacter = resolveCharacter(context, selectedWorld, selectedRealm, character);
        } catch (IllegalStateException ex) {
          return characterIdentityUnavailableFailure(
              selectedTenantTag, Long.toString(selectedRealm.gameInstanceId()), character, ex);
        }
        boolean retainedTargetMatchesSelection =
            context.hasGameplayRegionBinding()
                && context.tenantId() == selectedRealm.tenantId()
                && context.gameInstanceId() == selectedRealm.gameInstanceId()
                && (!StringUtils.hasText(context.worldSlug())
                    || sameSlug(context.worldSlug(), selectedWorld.slug()))
                && (!StringUtils.hasText(context.realmSlug())
                    || sameSlug(context.realmSlug(), selectedRealm.slug()));
        if (!StringUtils.hasText(character)
            && retainedTargetMatchesSelection
            && context.characterId() != resolvedCharacter.id()) {
          return characterIdentityUnavailableFailure(
              selectedTenantTag,
              Long.toString(selectedRealm.gameInstanceId()),
              Long.toString(context.characterId()),
              new IllegalStateException(
                  "Current persisted roster no longer contains the retained actor"));
        }
        String characterName = resolvedCharacter.name();
        Optional<PlayCommandHandlingResult> moderationFailure =
            validateModerationPolicy(context, selectedRealm, selectedTenantTag);
        if (moderationFailure.isPresent()) {
          return moderationFailure.get();
        }
        long characterId = resolvedCharacter.id();
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
            publishCommandEvent(context, command);
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

          maybeRecordFreshEntryFallback(
              context, selectedRealm, characterName, gameInstanceId, characterId);

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
          publishCommandEvent(updated, command);
          if (!resumedOrTookOver) {
            publishSpawnEvent(updated);
          }

          return new PlayCommandHandlingResult(
              CommandEnqueueResult.success(),
              List.of(successNotice(selectedWorld.slug(), selectedRealm.slug(), character)),
              resumedOrTookOver);
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

  private ResolvedCharacter resolveCharacter(
      SessionContext context,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      String requestedCharacter) {
    PlayableStateScope playableStateScope = toPlayableStateScope(selectedRealm);
    if (playableStateScope == PlayableStateScope.PLAYABLE_STATE_SCOPE_UNSPECIFIED) {
      throw new IllegalStateException("Selected realm has no playable-state scope");
    }
    ListCharactersByAccountResponse response =
        entityManagementClient.listCharactersByAccount(
            Long.toString(selectedRealm.tenantId()),
            Long.toString(context.accountId()),
            Long.toString(selectedRealm.gameInstanceId()),
            playableStateScope);
    if (response == null || response.hasError()) {
      throw new IllegalStateException("Character roster unavailable for selected gameplay target");
    }

    List<Character> roster = response.getCharactersList();
    java.util.Set<Long> characterIds = new java.util.HashSet<>();
    for (Character character : roster) {
      long characterId = requireResolvedCharacterId(character.getId());
      if (!Long.toString(selectedRealm.tenantId()).equals(character.getTenantId())
          || !Long.toString(context.accountId()).equals(character.getAccountId())
          || character.getPlayableStateScope() != playableStateScope
          || !StringUtils.hasText(character.getName())
          || !characterIds.add(characterId)) {
        throw new IllegalStateException(
            "Malformed or unauthorized character roster for selected gameplay target");
      }
    }
    Character selected;
    if (StringUtils.hasText(requestedCharacter)) {
      List<Character> matches =
          roster.stream()
              .filter(
                  character ->
                      Objects.equals(
                          normalizeName(character.getName()), normalizeName(requestedCharacter)))
              .toList();
      if (matches.size() != 1) {
        throw new IllegalStateException(
            matches.isEmpty()
                ? "Selected character is not present in the authenticated account roster"
                : "Selected character is ambiguous in the authenticated account roster");
      }
      selected = matches.getFirst();
    } else {
      if (roster.size() != 1) {
        throw new IllegalStateException(
            "PLAY without a character requires exactly one current account character");
      }
      selected = roster.getFirst();
    }
    return new ResolvedCharacter(
        requireResolvedCharacterId(selected.getId()), selected.getName().trim());
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

  private void publishCommandEvent(SessionContext context, TextCommand command) {
    try {
      scriptEventPublisher.publishCommandEvent(context, scriptEventCommand(context, command));
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

  private static GameplayCommand scriptEventCommand(SessionContext context, TextCommand command) {
    return ScriptEventGameplayCommands.syntheticWithId(
        "play-command:"
            + context.sessionId()
            + ":"
            + context.gameInstanceId()
            + ":"
            + context.characterId()
            + ":"
            + context.pointerVersion(),
        command,
        TextCommandType.PLAY.name(),
        null);
  }

  private Optional<PlayCommandHandlingResult> validateRuntimeAdmission(
      SessionContext context,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      String tenantTag,
      long requestedCharacterId) {
    String requestId = context.sessionId() + ":" + UUID.randomUUID();
    // This is only a denial-cleanup hint for an already bound runtime, never actor admission.
    long denialCleanupCharacterId =
        context.accountId() > 0L
                && context.tenantId() == selectedRealm.tenantId()
                && context.gameInstanceId() == selectedRealm.gameInstanceId()
                && context.characterId() > 0L
            ? context.characterId()
            : requestedCharacterId > 0L ? requestedCharacterId : 0L;
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
            denialCleanupCharacterId,
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
        denialCleanupCharacterId);
  }

  private long requireResolvedCharacterId(String characterId) {
    if (!StringUtils.hasText(characterId)) {
      throw new IllegalStateException(
          "Malformed resolved characterId from entity lookup: characterId is required");
    }
    try {
      return PositiveLongParsing.requireOptionalText(characterId, "characterId")
          .orElseThrow(() -> new IllegalArgumentException("characterId is required"));
    } catch (IllegalArgumentException ex) {
      throw new IllegalStateException(
          "Malformed resolved characterId from entity lookup: " + ex.getMessage(), ex);
    }
  }

  private PlayCommandHandlingResult characterIdentityUnavailableFailure(
      String tenantTag, String gameInstanceTag, String characterTag, RuntimeException ex) {
    return failure(
        GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_CODE,
        GameplayStageCommandConstants.PLAY_IDENTITY_UNAVAILABLE_MESSAGE,
        "error.play.identity-unavailable",
        Map.of(),
        tenantTag,
        gameInstanceTag,
        characterTag,
        ex);
  }

  private Optional<PlayCommandHandlingResult> validateMembershipResponse(
      GetTenantMembershipForRuntimeResponse response,
      SessionContext context,
      String tenantTag,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      long requestedCharacterId,
      String requestId) {
    Optional<ErrorDetail> maybeError = extractError(response.getError());
    if (maybeError.isPresent()) {
      if (!isAuthorityUnavailable(maybeError.orElseThrow())) {
        return Optional.of(
            worldAccessDeniedFailure(
                context, tenantTag, selectedWorld, selectedRealm, requestedCharacterId));
      }
      return Optional.of(
          authorityUnavailableFailure(
              tenantTag, Long.toString(selectedRealm.gameInstanceId()), requestedCharacterId));
    }
    if (!isSafeMembershipAuthorityResponse(response, context, selectedRealm)) {
      return Optional.of(
          authorityUnavailableFailure(
              tenantTag, Long.toString(selectedRealm.gameInstanceId()), requestedCharacterId));
    }
    if (!response.getMembershipExists() || !response.getGameplayAdmissionAllowed()) {
      boolean membershipRequiresExplicitJoin =
          !response.getMembershipExists()
              || "INACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState());
      if (isPublicProductionRealm(selectedRealm) && membershipRequiresExplicitJoin) {
        GetTenantEntitlementsForRuntimeResponse entitlementResponse =
            accountClient.getTenantEntitlementsForRuntime(
                Long.toString(selectedRealm.tenantId()), requestId);
        Optional<PlayCommandHandlingResult> entitlementFailure =
            validateEntitlementsResponse(
                entitlementResponse,
                context,
                tenantTag,
                selectedWorld,
                selectedRealm,
                requestedCharacterId);
        if (entitlementFailure.isPresent()) {
          return entitlementFailure;
        }
        if (!entitlementResponse.getAllowPublicJoin()) {
          return Optional.of(
              publicProductionAdmissionDeniedFailure(
                  context, tenantTag, selectedWorld, selectedRealm, requestedCharacterId));
        }
        recordResumeDeniedIfApplicable(
            context,
            selectedRealm.tenantId(),
            selectedWorld.slug(),
            selectedRealm.slug(),
            selectedRealm.pointerVersion(),
            selectedRealm.gameInstanceId(),
            requestedCharacterId,
            tenantTag,
            "join_required");
        return Optional.of(
            failure(
                GameplayStageCommandConstants.JOIN_REQUIRED_CODE,
                GameplayStageCommandConstants.JOIN_REQUIRED_MESSAGE,
                "error.play.join-required",
                Map.of(),
                tenantTag,
                Long.toString(selectedRealm.gameInstanceId()),
                Long.toString(requestedCharacterId),
                null));
      }
      return Optional.of(
          worldAccessDeniedFailure(
              context, tenantTag, selectedWorld, selectedRealm, requestedCharacterId));
    }
    if (!isPublicProductionRealm(selectedRealm)) {
      GetRealmAccessGrantForRuntimeResponse grantResponse =
          accountClient.getRealmAccessGrantForRuntime(
              Long.toString(context.accountId()),
              Long.toString(selectedRealm.tenantId()),
              selectedWorld.slug(),
              selectedRealm.slug(),
              requestId);
      Optional<ErrorDetail> grantError = extractError(grantResponse.getError());
      if (grantError.isPresent() && isAuthorityUnavailable(grantError.get())) {
        return Optional.of(
            authorityUnavailableFailure(
                tenantTag, Long.toString(selectedRealm.gameInstanceId()), requestedCharacterId));
      }
      if (grantError.isPresent() || !grantResponse.getGranted()) {
        return Optional.of(
            worldAccessDeniedFailure(
                context, tenantTag, selectedWorld, selectedRealm, requestedCharacterId));
      }
      if (!isValidGrant(grantResponse, context, selectedWorld, selectedRealm)) {
        return Optional.of(
            authorityUnavailableFailure(
                tenantTag, Long.toString(selectedRealm.gameInstanceId()), requestedCharacterId));
      }
      return Optional.empty();
    }
    return Optional.empty();
  }

  private PlayCommandHandlingResult worldAccessDeniedFailure(
      SessionContext context,
      String tenantTag,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      long requestedCharacterId) {
    recordResumeDeniedIfApplicable(
        context,
        selectedRealm.tenantId(),
        selectedWorld.slug(),
        selectedRealm.slug(),
        selectedRealm.pointerVersion(),
        selectedRealm.gameInstanceId(),
        requestedCharacterId,
        tenantTag,
        "access_denied");
    return failure(
        GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE,
        GameplayStageCommandConstants.WORLD_ACCESS_DENIED_MESSAGE,
        "error.play.world-access-denied",
        Map.of(),
        tenantTag,
        Long.toString(selectedRealm.gameInstanceId()),
        Long.toString(requestedCharacterId),
        null);
  }

  private boolean isDefinitivePrivateWorldDenial(PlayCommandHandlingResult result) {
    return GameplayStageCommandConstants.WORLD_ACCESS_DENIED_CODE.equals(
        result.commandResult().errorCode());
  }

  private PlayCommandHandlingResult publicProductionAdmissionDeniedFailure(
      SessionContext context,
      String tenantTag,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      long requestedCharacterId) {
    recordResumeDeniedIfApplicable(
        context,
        selectedRealm.tenantId(),
        selectedWorld.slug(),
        selectedRealm.slug(),
        selectedRealm.pointerVersion(),
        selectedRealm.gameInstanceId(),
        requestedCharacterId,
        tenantTag,
        "public_admission_denied");
    return failure(
        PUBLIC_PRODUCTION_ADMISSION_DENIED_CODE,
        PUBLIC_PRODUCTION_ADMISSION_DENIED_MESSAGE,
        "error.play.public-production-admission-denied",
        Map.of(),
        tenantTag,
        Long.toString(selectedRealm.gameInstanceId()),
        Long.toString(requestedCharacterId),
        null);
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
      if (isEntitlementUnavailable(maybeError.get())) {
        return Optional.of(
            entitlementUnavailableFailure(
                tenantTag, Long.toString(selectedRealm.gameInstanceId()), requestedCharacterId));
      }
      if ("FAILED_PRECONDITION".equalsIgnoreCase(maybeError.get().getCode())) {
        return Optional.of(
            worldAccessDeniedFailure(
                context, tenantTag, selectedWorld, selectedRealm, requestedCharacterId));
      }
      if (isAuthorityUnavailable(maybeError.get())) {
        return Optional.of(
            authorityUnavailableFailure(
                tenantTag, Long.toString(selectedRealm.gameInstanceId()), requestedCharacterId));
      }
      return Optional.of(
          tenantBillingBlockedFailure(
              context, tenantTag, selectedWorld, selectedRealm, requestedCharacterId));
    }
    if (!isValidEntitlement(response, selectedRealm)) {
      return Optional.of(
          entitlementUnavailableFailure(
              tenantTag, Long.toString(selectedRealm.gameInstanceId()), requestedCharacterId));
    }
    if (!response.getGameplayAvailable()) {
      return Optional.of(
          tenantBillingBlockedFailure(
              context, tenantTag, selectedWorld, selectedRealm, requestedCharacterId));
    }
    return Optional.empty();
  }

  private PlayCommandHandlingResult tenantBillingBlockedFailure(
      SessionContext context,
      String tenantTag,
      GameplayWorldCatalog.WorldView selectedWorld,
      GameplayWorldCatalog.RealmView selectedRealm,
      long requestedCharacterId) {
    recordResumeDeniedIfApplicable(
        context,
        selectedRealm.tenantId(),
        selectedWorld.slug(),
        selectedRealm.slug(),
        selectedRealm.pointerVersion(),
        selectedRealm.gameInstanceId(),
        requestedCharacterId,
        tenantTag,
        "tenant_unavailable");
    return failure(
        GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_CODE,
        GameplayStageCommandConstants.TENANT_BILLING_BLOCKED_MESSAGE,
        "error.play.billing-blocked",
        Map.of(),
        tenantTag,
        Long.toString(selectedRealm.gameInstanceId()),
        Long.toString(requestedCharacterId),
        null);
  }

  private PlayCommandHandlingResult authorityUnavailableFailure(
      String tenantTag, String gameInstanceTag, long requestedCharacterId) {
    return failure(
        GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE,
        GameplayStageCommandConstants.AUTH_UNAVAILABLE_MESSAGE,
        "error.play.authority-unavailable",
        Map.of(),
        tenantTag,
        gameInstanceTag,
        Long.toString(requestedCharacterId),
        null);
  }

  private PlayCommandHandlingResult admissionPointerUnavailableFailure(
      String tenantTag, String gameInstanceTag) {
    return failure(
        "ADMISSION_POINTER_UNAVAILABLE",
        "Gameplay admission pointer is unavailable",
        "error.play.authority-unavailable",
        Map.of(),
        tenantTag,
        gameInstanceTag,
        null,
        null);
  }

  private PlayCommandHandlingResult entitlementUnavailableFailure(
      String tenantTag, String gameInstanceTag, long requestedCharacterId) {
    return failure(
        GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE,
        GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_MESSAGE,
        "error.play.entitlement-unavailable",
        Map.of(),
        tenantTag,
        gameInstanceTag,
        Long.toString(requestedCharacterId),
        null);
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
    return GameplayStageCommandConstants.AUTH_UNAVAILABLE_CODE.equalsIgnoreCase(code);
  }

  private boolean isEntitlementUnavailable(ErrorDetail error) {
    String code = Optional.ofNullable(error.getCode()).orElse("");
    return GameplayStageCommandConstants.ENTITLEMENT_UNAVAILABLE_CODE.equalsIgnoreCase(code);
  }

  private boolean isSafeMembershipAuthorityResponse(
      GetTenantMembershipForRuntimeResponse response,
      SessionContext context,
      GameplayWorldCatalog.RealmView selectedRealm) {
    if (!hasMatchingAuthorityIdentity(
            response.getAccountId(),
            response.getTenantId(),
            context.accountId(),
            selectedRealm.tenantId())
        || !isFreshAuthorityEvaluation(response.getEvaluatedAt())) {
      return false;
    }
    if (!response.getMembershipExists()) {
      return "MISSING".equalsIgnoreCase(response.getMembershipLifecycleState())
          && !response.getGameplayAdmissionAllowed()
          && response.getMembershipVersion() == 0L
          && response.getMembershipAuthorityGeneration() == 0L;
    }
    if (response.getMembershipVersion() <= 0L
        || response.getMembershipAuthorityGeneration() <= 0L) {
      return false;
    }
    if (response.getGameplayAdmissionAllowed()) {
      return "ACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState());
    }
    return "ACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState())
        || "INACTIVE".equalsIgnoreCase(response.getMembershipLifecycleState());
  }

  private boolean isValidGrant(
      GetRealmAccessGrantForRuntimeResponse response,
      SessionContext context,
      GameplayWorldCatalog.WorldView world,
      GameplayWorldCatalog.RealmView realm) {
    return response.getGrantVersion() > 0L
        && hasMatchingAuthorityIdentity(
            response.getAccountId(), response.getTenantId(), context.accountId(), realm.tenantId())
        && world.slug().equals(response.getWorldSlug())
        && realm.slug().equals(response.getRealmSlug())
        && isFreshAuthorityEvaluation(response.getEvaluatedAt());
  }

  private boolean isValidEntitlement(
      GetTenantEntitlementsForRuntimeResponse response, GameplayWorldCatalog.RealmView realm) {
    return hasMatchingTenantId(response.getTenantId(), realm.tenantId())
        && response.getEntitlementVersion() > 0L
        && response.getTenantBillingSequence() > 0L
        && isFreshAuthorityEvaluation(response.getEvaluatedAt());
  }

  private boolean isFreshAuthorityEvaluation(String evaluatedAt) {
    if (!StringUtils.hasText(evaluatedAt)) {
      return false;
    }
    try {
      Instant evaluated = Instant.parse(evaluatedAt);
      Instant now = Instant.now();
      return !evaluated.isAfter(now) && !evaluated.isBefore(now.minusSeconds(15));
    } catch (DateTimeParseException ex) {
      return false;
    }
  }

  private boolean hasMatchingAuthorityIdentity(
      String accountId, String tenantId, long expectedAccountId, long expectedTenantId) {
    try {
      return Long.parseLong(accountId) == expectedAccountId
          && Long.parseLong(tenantId) == expectedTenantId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private boolean hasMatchingTenantId(String tenantId, long expectedTenantId) {
    try {
      return Long.parseLong(tenantId) == expectedTenantId;
    } catch (NumberFormatException ex) {
      return false;
    }
  }

  private boolean maybeRecordFreshEntryFallback(
      SessionContext context,
      GameplayWorldCatalog.RealmView selectedRealm,
      String selectedCharacterName,
      long requestedGameInstanceId,
      long requestedCharacterId) {
    if (context.gameInstanceId() != requestedGameInstanceId
        || context.characterId() != requestedCharacterId) {
      return false;
    }
    if (StringUtils.hasText(context.roomInstanceId())
        && Objects.equals(
            normalizeName(context.characterName()), normalizeName(selectedCharacterName))) {
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

  private WorldSelectorResolution resolvePlayWorld(
      SessionContext context,
      String selector,
      GameplayWorldCatalog.DiscoverySnapshot currentCatalog) {
    if (!StringUtils.hasText(selector)) {
      return new WorldSelectorResolution.Invalid();
    }
    if (!GameplayWorldCatalog.isOrdinalSelector(selector)) {
      return gameplayWorldCatalog
          .resolveStableWorld(currentCatalog, selector)
          .<WorldSelectorResolution>map(WorldSelectorResolution.Selected::new)
          .orElseGet(WorldSelectorResolution.Invalid::new);
    }
    Optional<DirectTextConnectScopeSessionStore.WorldsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot = connectScopeSessionStore.worldsSnapshot(context, Instant.now());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return new WorldSelectorResolution.Unavailable();
    }
    if (maybeSnapshot.isEmpty()) {
      return new WorldSelectorResolution.Stale();
    }
    DirectTextConnectScopeSessionStore.WorldsSnapshot snapshot = maybeSnapshot.orElseThrow();
    if (!snapshot.catalogFingerprint().equals(currentCatalog.catalogFingerprint())) {
      return new WorldSelectorResolution.Stale();
    }
    int ordinal;
    try {
      ordinal = Integer.parseInt(selector.trim());
    } catch (NumberFormatException ex) {
      return new WorldSelectorResolution.Stale();
    }
    Optional<DirectTextConnectScopeSessionStore.WorldOrdinalTarget> maybeTarget =
        snapshot.ordinalTargets().stream()
            .filter(target -> target.ordinal() == ordinal)
            .findFirst();
    if (maybeTarget.isEmpty()) {
      return new WorldSelectorResolution.Stale();
    }
    return gameplayWorldCatalog
        .resolveSnapshotOrdinal(currentCatalog, maybeTarget.orElseThrow())
        .<WorldSelectorResolution>map(WorldSelectorResolution.Selected::new)
        .orElseGet(WorldSelectorResolution.Stale::new);
  }

  private RealmSelectorResolution resolvePlayRealm(
      SessionContext context, GameplayWorldCatalog.WorldView world, String selector) {
    if (!StringUtils.hasText(selector)) {
      return new RealmSelectorResolution.NoSelection();
    }
    if (!GameplayWorldCatalog.isOrdinalSelector(selector)) {
      return gameplayWorldCatalog
          .resolveRealmForAdmission(world, selector)
          .<RealmSelectorResolution>map(RealmSelectorResolution.Selected::new)
          .orElseGet(RealmSelectorResolution.Invalid::new);
    }
    long tenantId = worldTenantId(world);
    if (tenantId <= 0L) {
      return new RealmSelectorResolution.Invalid();
    }
    Optional<DirectTextConnectScopeSessionStore.RealmsSnapshot> maybeSnapshot;
    try {
      maybeSnapshot =
          connectScopeSessionStore.realmsSnapshot(context, tenantId, world.slug(), Instant.now());
    } catch (DirectTextConnectScopeSessionStore.StoreUnavailableException
        | DirectTextConnectScopeSessionStore.ConflictingIdentityException ex) {
      return new RealmSelectorResolution.Unavailable();
    }
    if (maybeSnapshot.isEmpty()) {
      return new RealmSelectorResolution.Stale();
    }
    DirectTextConnectScopeSessionStore.RealmsSnapshot snapshot = maybeSnapshot.orElseThrow();
    GameplayWorldCatalog.RealmDiscoverySnapshot currentRealmCatalog =
        gameplayWorldCatalog.readRealmDiscoverySnapshot(world);
    if (!snapshot.catalogFingerprint().equals(currentRealmCatalog.catalogFingerprint())) {
      return new RealmSelectorResolution.Stale();
    }
    int ordinal;
    try {
      ordinal = Integer.parseInt(selector.trim());
    } catch (NumberFormatException ex) {
      return new RealmSelectorResolution.Stale();
    }
    Optional<DirectTextConnectScopeSessionStore.RealmOrdinalTarget> maybeTarget =
        snapshot.ordinalTargets().stream()
            .filter(target -> target.ordinal() == ordinal)
            .findFirst();
    if (maybeTarget.isEmpty()) {
      return new RealmSelectorResolution.Stale();
    }
    return gameplayWorldCatalog
        .resolveRealmSnapshotOrdinal(world, currentRealmCatalog, maybeTarget.orElseThrow())
        .<RealmSelectorResolution>map(RealmSelectorResolution.Selected::new)
        .orElseGet(RealmSelectorResolution.Stale::new);
  }

  private static long worldTenantId(GameplayWorldCatalog.WorldView world) {
    List<Long> tenantIds =
        world.realms().stream().map(GameplayWorldCatalog.RealmView::tenantId).distinct().toList();
    return tenantIds.size() == 1 ? tenantIds.getFirst() : -1L;
  }

  private sealed interface WorldSelectorResolution
      permits WorldSelectorResolution.Selected,
          WorldSelectorResolution.Invalid,
          WorldSelectorResolution.Stale,
          WorldSelectorResolution.Unavailable {
    record Selected(GameplayWorldCatalog.WorldView world) implements WorldSelectorResolution {}

    record Invalid() implements WorldSelectorResolution {}

    record Stale() implements WorldSelectorResolution {}

    record Unavailable() implements WorldSelectorResolution {}
  }

  private sealed interface RealmSelectorResolution
      permits RealmSelectorResolution.Selected,
          RealmSelectorResolution.NoSelection,
          RealmSelectorResolution.Invalid,
          RealmSelectorResolution.Stale,
          RealmSelectorResolution.Unavailable {
    record Selected(GameplayWorldCatalog.RealmView realm) implements RealmSelectorResolution {}

    record NoSelection() implements RealmSelectorResolution {}

    record Invalid() implements RealmSelectorResolution {}

    record Stale() implements RealmSelectorResolution {}

    record Unavailable() implements RealmSelectorResolution {}
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
    return Optional.of(new ResolvedPlaySelection(worldSelector.trim(), secondSelector, null));
  }

  private ResolvedPlaySelection disambiguateSelection(
      ResolvedPlaySelection requestedSelection, GameplayWorldCatalog.WorldView selectedWorld) {
    String secondSelector = requestedSelection.explicitRealmSelector();
    if (!StringUtils.hasText(secondSelector)
        || GameplayWorldCatalog.isOrdinalSelector(secondSelector)
        || gameplayWorldCatalog.hasRealmForAdmission(selectedWorld, secondSelector)) {
      return requestedSelection;
    }
    return new ResolvedPlaySelection(requestedSelection.worldSelector(), null, secondSelector);
  }

  private Optional<GameplayWorldCatalog.RealmView> selectDefaultRealm(
      GameplayWorldCatalog.WorldView selectedWorld,
      Optional<FirstPartyConnectContext> connectContext) {
    if (connectContext.isPresent()
        && StringUtils.hasText(connectContext.orElseThrow().realmSlug())) {
      Optional<GameplayWorldCatalog.RealmView> hintedRealm =
          gameplayWorldCatalog.resolveRealmForAdmission(
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

  private record ResolvedCharacter(long id, String name) {}

  private void recordResumeDeniedIfApplicable(
      SessionContext context,
      long requestedTenantId,
      String requestedWorldSlug,
      String requestedRealmSlug,
      long requestedPointerVersion,
      long requestedGameInstanceId,
      long requestedCharacterId,
      String tenantTag,
      String reason) {
    boolean selectedTenantMatchesContext =
        requestedTenantId > 0L
            && context.tenantId() > 0L
            && hasMatchingTenantId(tenantTag, context.tenantId());
    boolean sameRuntimeTarget =
        selectedTenantMatchesContext
            && requestedGameInstanceId > 0L
            && context.gameInstanceId() > 0L
            && context.gameInstanceId() == requestedGameInstanceId;
    boolean sameGameplayIdentity =
        sameRuntimeTarget
            && context.accountId() > 0L
            && context.characterId() > 0L
            && requestedCharacterId > 0
            && context.characterId() == requestedCharacterId;
    boolean sameVisibleRealm =
        sameRuntimeTarget
            && sameSlug(context.worldSlug(), requestedWorldSlug)
            && sameSlug(context.realmSlug(), requestedRealmSlug);
    if (!sameGameplayIdentity && !sameVisibleRealm && !sameRuntimeTarget) {
      return;
    }
    meterRegistry.counter(RESUME_DENIED_METRIC, "reason", reason).increment();
    gameplayPresenceLifecycleService.clearGameplayBinding(context, reason);
    sessionContextService.save(
        new SessionContext(
            context.sessionId(),
            context.tenantId(),
            context.accountId(),
            context.loginName(),
            0L,
            null,
            0L,
            null,
            context.jwt(),
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
