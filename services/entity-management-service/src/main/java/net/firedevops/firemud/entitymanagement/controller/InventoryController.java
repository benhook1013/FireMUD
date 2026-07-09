package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.AddInventoryItemRequest;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
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

  @GetMapping
  public ResponseEntity<ApiResponse<Page<InventoryEntryDto>>> list(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      Pageable pageable) {
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
          SessionContext.requireTenantAccess(scope.tenantId());
          Page<InventoryEntryDto> list =
              inventoryService.listInventory(
                  scope.tenantId(),
                  scope.characterId(),
                  gameInstanceId,
                  playableStateScope,
                  pageable);
          return ResponseEntity.ok(ApiResponse.success(list));
        });
  }

  @PostMapping
  public ResponseEntity<ApiResponse<InventoryEntryDto>> addItem(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      @Valid @RequestBody AddInventoryItemRequest request) {
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
          SessionContext.requireTenantAccess(scope.tenantId());
          InventoryEntryDto dto =
              inventoryService.addItem(
                  scope.tenantId(),
                  scope.characterId(),
                  gameInstanceId,
                  playableStateScope,
                  request.itemId(),
                  request.quantity());
          return ResponseEntity.ok(ApiResponse.success(dto));
        });
  }

  @DeleteMapping("/{itemId}")
  public ResponseEntity<ApiResponse<Void>> remove(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @PathVariable String itemId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope) {
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
          long parsedItemId =
              EntityManagementRequestReaders.requirePositivePathId(itemId, "itemId");
          SessionContext.requireTenantAccess(scope.tenantId());
          inventoryService.removeItem(
              scope.tenantId(),
              scope.characterId(),
              gameInstanceId,
              playableStateScope,
              parsedItemId);
          return ResponseEntity.ok(ApiResponse.success(null));
        });
  }
}
