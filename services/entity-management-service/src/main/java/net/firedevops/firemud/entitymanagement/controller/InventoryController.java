package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.AddInventoryItemRequest;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
import net.firedevops.firemud.entitymanagement.service.ScopedCharacterResolver;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST endpoints for managing character inventories. */
@RestController
@RequestMapping("/tenants/{tenantId}/characters/{characterId}/inventory")
@RequiredArgsConstructor
public class InventoryController {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects InventoryService; storing reference is safe")
  private final InventoryService inventoryService;

  private final ScopedCharacterResolver scopedCharacterResolver;

  @GetMapping
  public ResponseEntity<ApiResponse<Page<InventoryEntryDto>>> list(
      @PathVariable Long tenantId,
      @PathVariable Long characterId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      Pageable pageable) {
    SessionContext.requireTenantAccess(tenantId);
    scopedCharacterResolver.requireScopedCharacter(
        tenantId, characterId, gameInstanceId, playableStateScope);
    Page<InventoryEntryDto> list = inventoryService.listInventory(tenantId, characterId, pageable);
    return ResponseEntity.ok(ApiResponse.success(list));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<InventoryEntryDto>> addItem(
      @PathVariable Long tenantId,
      @PathVariable Long characterId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      @Valid @RequestBody AddInventoryItemRequest request) {
    SessionContext.requireTenantAccess(tenantId);
    scopedCharacterResolver.requireScopedCharacter(
        tenantId, characterId, gameInstanceId, playableStateScope);
    InventoryEntryDto dto =
        inventoryService.addItem(tenantId, characterId, request.itemId(), request.quantity());
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @DeleteMapping("/{itemId}")
  public ResponseEntity<ApiResponse<Void>> remove(
      @PathVariable Long tenantId,
      @PathVariable Long characterId,
      @PathVariable Long itemId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope) {
    SessionContext.requireTenantAccess(tenantId);
    scopedCharacterResolver.requireScopedCharacter(
        tenantId, characterId, gameInstanceId, playableStateScope);
    inventoryService.removeItem(tenantId, characterId, itemId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
