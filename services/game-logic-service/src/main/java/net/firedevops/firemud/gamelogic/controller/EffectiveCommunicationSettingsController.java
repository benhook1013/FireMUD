package net.firedevops.firemud.gamelogic.controller;

import java.util.List;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.gamelogic.config.CommunicationProperties;
import net.firedevops.firemud.gamelogic.config.EffectiveCommunicationSettingsResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator/debug surface for inspecting effective communication settings in Game Logic. */
@RestController
@RequestMapping("/actuator/settings/effective")
public class EffectiveCommunicationSettingsController {
  private final EffectiveCommunicationSettingsResolver settingsResolver;

  public EffectiveCommunicationSettingsController(
      EffectiveCommunicationSettingsResolver settingsResolver) {
    this.settingsResolver = settingsResolver;
  }

  @GetMapping("/communication")
  public ResponseEntity<ApiResponse<DomainSettings<CommunicationProperties>>> communication(
      @RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String gameInstanceId) {
    return GameLogicRequestReaders.withBadRequest(
        () -> {
          Long parsedTenantId =
              GameLogicRequestReaders.requireOptionalPositiveLong(tenantId, "tenantId");
          Long parsedGameInstanceId =
              GameLogicRequestReaders.requireOptionalPositiveLong(gameInstanceId, "gameInstanceId");
          EffectiveCommunicationSettingsResolver.ResolvedValue<CommunicationProperties> resolved =
              settingsResolver.resolvedCommunication(parsedTenantId, parsedGameInstanceId);
          return ResponseEntity.ok(
              ApiResponse.success(new DomainSettings<>(resolved.effective(), resolved.sources())));
        });
  }

  public record DomainSettings<T>(T effective, List<String> sources) {
    public DomainSettings {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }
  }
}
