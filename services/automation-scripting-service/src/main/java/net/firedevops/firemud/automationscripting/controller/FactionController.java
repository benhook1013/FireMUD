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
      @PathVariable Long id,
      @RequestParam Long characterId,
      @RequestParam int delta,
      @RequestParam Long tenantId) {
    int result = factionService.adjustReputation(tenantId, characterId, id, delta);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
