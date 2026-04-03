package net.firedevops.firemud.gamesession.controller;

import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.config.FiremudReconnectionProperties;
import net.firedevops.firemud.gamesession.config.EffectiveReconnectionSettingsResolver;
import net.firedevops.firemud.gamesession.config.EffectiveSettingsResolver;
import net.firedevops.firemud.gamesession.config.MovementProperties;
import net.firedevops.firemud.gamesession.config.PresentationProperties;
import net.firedevops.firemud.gamesession.config.WorldTopologyProperties;
import net.firedevops.firemud.gamesession.service.SessionContext;
import net.firedevops.firemud.gamesession.service.SessionContextService;
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
  private final SessionContextService sessionContextService;

  public EffectiveSettingsController(
      EffectiveSettingsResolver settingsResolver,
      EffectiveReconnectionSettingsResolver reconnectionSettingsResolver,
      SessionContextService sessionContextService) {
    this.settingsResolver = settingsResolver;
    this.reconnectionSettingsResolver = reconnectionSettingsResolver;
    this.sessionContextService = sessionContextService;
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
            new DomainSettings<>(reconnection.effective(), reconnection.sources()),
            new DomainSettings<>(movement.effective(), movement.sources()),
            new DomainSettings<>(worldTopology.effective(), worldTopology.sources()));
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  private SessionResolution resolveContext(
      Long sessionId, Long tenantId, Long gameInstanceId, Long bootstrapGameInstanceId) {
    if (sessionId != null) {
      SessionContext context =
          sessionContextService
              .findBySessionId(sessionId)
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
      DomainSettings<FiremudReconnectionProperties> reconnection,
      DomainSettings<MovementProperties> movement,
      DomainSettings<WorldTopologyProperties> worldTopology) {}

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
}
