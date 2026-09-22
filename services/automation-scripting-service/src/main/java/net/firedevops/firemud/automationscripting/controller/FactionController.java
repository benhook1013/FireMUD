package net.firedevops.firemud.automationscripting.controller;

import net.firedevops.firemud.automationscripting.service.FactionService;
import net.firedevops.firemud.common.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/factions")
public class FactionController {
  private final FactionService factionService;

  public FactionController(FactionService factionService) {
    this.factionService = factionService;
  }

  @PatchMapping("/{id}/reputation")
  public ResponseEntity<ApiResponse<Integer>> adjustReputation(
      @PathVariable String id,
      @RequestParam String characterId,
      @RequestParam String gameInstanceId,
      @RequestParam String playableStateScope,
      @RequestParam String delta,
      @RequestParam String tenantId) {
    return AutomationScriptingRequestReaders.withBadRequest(
        () -> {
          int result =
              factionService.adjustReputation(
                  AutomationScriptingRequestReaders.requirePositiveLong(tenantId, "tenantId"),
                  AutomationScriptingRequestReaders.requirePositiveLong(characterId, "characterId"),
                  gameInstanceId,
                  AutomationScriptingRequestReaders.requirePlayableStateScope(playableStateScope),
                  AutomationScriptingRequestReaders.requirePositiveLong(id, "factionId"),
                  AutomationScriptingRequestReaders.requireInteger(delta, "delta"));
          return ResponseEntity.ok(ApiResponse.success(result));
        });
  }
}
