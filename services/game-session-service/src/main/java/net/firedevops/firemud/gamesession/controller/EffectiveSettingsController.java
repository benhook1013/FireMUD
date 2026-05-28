package net.firedevops.firemud.gamesession.controller;

import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.common.settings.ScopedSettingsOverrides;
import net.firedevops.firemud.common.settings.SharedEffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.EffectiveReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.config.WorldTopologyProperties;
import net.firedevops.firemud.gamesession.service.SessionAuthenticationService;
import net.firedevops.firemud.gamesession.service.SessionContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Operator/debug surface for inspecting effective pre-06 settings in Game Session. */
@RestController
@RequestMapping("/actuator/settings")
public class EffectiveSettingsController {
  private final EffectiveSettingsResolver settingsResolver;
  private final EffectiveReconnectionSettingsResolver reconnectionSettingsResolver;
  private final SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver;
  private final SessionAuthenticationService sessionAuthenticationService;

  public EffectiveSettingsController(
      EffectiveSettingsResolver settingsResolver,
      EffectiveReconnectionSettingsResolver reconnectionSettingsResolver,
      SharedEffectiveSettingsResolver sharedEffectiveSettingsResolver,
      SessionAuthenticationService sessionAuthenticationService) {
    this.settingsResolver = settingsResolver;
    this.reconnectionSettingsResolver = reconnectionSettingsResolver;
    this.sharedEffectiveSettingsResolver = sharedEffectiveSettingsResolver;
    this.sessionAuthenticationService = sessionAuthenticationService;
  }

  @GetMapping("/effective")
  public ResponseEntity<ApiResponse<EffectiveSettingsResponse>> effectiveSettings(
      @RequestParam(required = false) Long sessionId,
      @RequestParam(required = false) Long tenantId,
      @RequestParam(required = false) Long gameInstanceId,
      @RequestParam(required = false) Long bootstrapGameInstanceId) {
    SessionResolution resolution =
        resolveContext(sessionId, tenantId, gameInstanceId, bootstrapGameInstanceId);
    EffectiveSettingsResolver.ResolvedValue<PresentationProperties> presentation =
        settingsResolver.resolvedPresentation(resolution.context());
    EffectiveSettingsResolver.ResolvedValue<MovementProperties> movement =
        settingsResolver.resolvedMovement(resolution.context());
    EffectiveSettingsResolver.ResolvedValue<WorldTopologyProperties> worldTopology =
        settingsResolver.resolvedWorldTopology(resolution.context());
    EffectiveReconnectionSettingsResolver.ResolvedValue<FiremudReconnectionProperties>
        reconnection = reconnectionSettingsResolver.resolvedReconnection(resolution.context());
    Long effectiveGameInstanceId = effectiveGameInstanceId(resolution.context());
    SharedEffectiveSettingsResolver.ResolvedScopedSettings sharedOverrides =
        resolution.context().tenantId() > 0L
            ? sharedEffectiveSettingsResolver.resolve(
                resolution.context().tenantId(), effectiveGameInstanceId)
            : new SharedEffectiveSettingsResolver.ResolvedScopedSettings(
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty(),
                ScopedSettingsOverrides.empty());
    EffectiveSettingsResponse response =
        new EffectiveSettingsResponse(
            new Scope(
                resolution.persistedSession(),
                resolution.sessionId(),
                resolution.context().tenantId(),
                resolution.context().gameInstanceId(),
                resolution.context().bootstrapGameInstanceId(),
                resolution.context().localeTag()),
            new DomainSettings<>(presentation.effective(), presentation.sources()),
            new DomainSettings<>(
                PromptSettings.from(presentation.effective().prompt()), presentation.sources()),
            new DomainSettings<>(
                TranscriptRenderingSettings.from(presentation.effective()), presentation.sources()),
            new DomainSettings<>(reconnection.effective(), reconnection.sources()),
            new DomainSettings<>(reconnection.effective().policy(), reconnection.sources()),
            new DomainSettings<>(reconnection.effective().buffer(), reconnection.sources()),
            new DomainSettings<>(movement.effective(), movement.sources()),
            new DomainSettings<>(
                MovementPostMoveViewSettings.from(movement.effective()), movement.sources()),
            new DomainSettings<>(worldTopology.effective(), worldTopology.sources()),
            new DomainSettings<>(
                WorldTopologyScopeModelSettings.from(worldTopology.effective()),
                worldTopology.sources()),
            new DomainSettings<>(
                WorldTopologyRegionBehaviorSettings.from(worldTopology.effective()),
                worldTopology.sources()),
            new ScopedOverrideSettings<>(
                sharedOverrides.effectiveOverrides().communication(),
                sharedOverrides.sourcesFor(
                    ScopedSettingsOverrides.SettingsDomain.COMMUNICATION,
                    resolution.context().tenantId(),
                    effectiveGameInstanceId)));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  private SessionResolution resolveContext(
      Long sessionId, Long tenantId, Long gameInstanceId, Long bootstrapGameInstanceId) {
    if (sessionId != null) {
      SessionContext context =
          sessionAuthenticationService
              .resolveUnverifiedSessionContext(Long.toString(sessionId))
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.NOT_FOUND, "Session context not found for " + sessionId));
      return new SessionResolution(sessionId, context, true);
    }
    long resolvedTenantId = tenantId == null ? 0L : tenantId;
    long resolvedGameInstanceId = gameInstanceId == null ? 0L : gameInstanceId;
    long resolvedBootstrapGameInstanceId =
        bootstrapGameInstanceId == null ? resolvedGameInstanceId : bootstrapGameInstanceId;
    SessionContext synthetic =
        new SessionContext(
            0L,
            resolvedTenantId,
            0L,
            null,
            0L,
            null,
            resolvedGameInstanceId,
            null,
            null,
            null,
            resolvedBootstrapGameInstanceId);
    return new SessionResolution(null, synthetic, false);
  }

  private record SessionResolution(
      Long sessionId, SessionContext context, boolean persistedSession) {}

  public record EffectiveSettingsResponse(
      Scope scope,
      DomainSettings<PresentationProperties> presentation,
      DomainSettings<PromptSettings> prompt,
      DomainSettings<TranscriptRenderingSettings> transcriptRendering,
      DomainSettings<FiremudReconnectionProperties> reconnection,
      DomainSettings<FiremudReconnectionProperties.Policy> reconnectionPolicy,
      DomainSettings<FiremudReconnectionProperties.Buffer> reconnectBuffer,
      DomainSettings<MovementProperties> movement,
      DomainSettings<MovementPostMoveViewSettings> movementPostMoveView,
      DomainSettings<WorldTopologyProperties> worldTopology,
      DomainSettings<WorldTopologyScopeModelSettings> worldTopologyScopeModel,
      DomainSettings<WorldTopologyRegionBehaviorSettings> worldTopologyRegionBehavior,
      ScopedOverrideSettings<ScopedSettingsOverrides.CommunicationOverride>
          communicationOverrides) {}

  public record Scope(
      boolean persistedSession,
      Long sessionId,
      long tenantId,
      long gameInstanceId,
      long bootstrapGameInstanceId,
      String localeTag) {}

  public record DomainSettings<T>(T effective, List<String> sources) {
    public DomainSettings {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }

  public record ScopedOverrideSettings<T>(T effectiveOverride, List<String> sources) {
    public ScopedOverrideSettings {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }

  public record PromptSettings(
      boolean enabled, boolean emitAfterReconnectRestore, long coalesceWindowMs) {
    static PromptSettings from(PresentationProperties.Prompt prompt) {
      return new PromptSettings(
          prompt.enabled(), prompt.emitAfterReconnectRestore(), prompt.coalesceWindowMs());
    }
  }

  public record TranscriptRenderingSettings(
      String defaultLocaleTag,
      PresentationProperties.ColorMode defaultColorMode,
      boolean briefEnabledByDefault) {
    static TranscriptRenderingSettings from(PresentationProperties presentation) {
      return new TranscriptRenderingSettings(
          presentation.defaultLocaleTag(),
          presentation.defaultColorMode(),
          presentation.briefEnabledByDefault());
    }
  }

  public record MovementPostMoveViewSettings(boolean postMoveLookEnabled) {
    static MovementPostMoveViewSettings from(MovementProperties movement) {
      return new MovementPostMoveViewSettings(movement.postMoveLookEnabled());
    }
  }

  public record WorldTopologyScopeModelSettings(
      WorldTopologyProperties.ScopeModel scopeModel, boolean mapEnabled, boolean areasEnabled) {
    static WorldTopologyScopeModelSettings from(WorldTopologyProperties worldTopology) {
      return new WorldTopologyScopeModelSettings(
          worldTopology.scopeModel(), worldTopology.mapEnabled(), worldTopology.areasEnabled());
    }
  }

  public record WorldTopologyRegionBehaviorSettings(boolean regionsEnabled) {
    static WorldTopologyRegionBehaviorSettings from(WorldTopologyProperties worldTopology) {
      return new WorldTopologyRegionBehaviorSettings(worldTopology.regionsEnabled());
    }
  }

  private Long effectiveGameInstanceId(SessionContext context) {
    if (context == null) {
      return null;
    }
    if (context.gameInstanceId() > 0L) {
      return context.gameInstanceId();
    }
    return context.bootstrapGameInstanceId() > 0L ? context.bootstrapGameInstanceId() : null;
  }
}
