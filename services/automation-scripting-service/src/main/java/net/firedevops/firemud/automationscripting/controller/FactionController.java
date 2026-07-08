package net.firedevops.firemud.automationscripting.controller;

import net.firedevops.firemud.automationscripting.service.FactionService;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.common.security.RequestIdValidation;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.http.HttpStatus;
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
      @RequestParam PlayableStateScope playableStateScope,
      @RequestParam int delta,
      @RequestParam String tenantId) {
    try {
      int result =
          factionService.adjustReputation(
              RequestIdValidation.requirePositiveLong(tenantId, "tenantId"),
              RequestIdValidation.requirePositiveLong(characterId, "characterId"),
              gameInstanceId,
              playableStateScope,
              RequestIdValidation.requirePositiveLong(id, "factionId"),
              delta);
      return ResponseEntity.ok(ApiResponse.success(result));
    } catch (IllegalArgumentException ex) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .body(ApiResponse.error(new ErrorDetail("INVALID_ARGUMENT", ex.getMessage())));
    }
  }
}
