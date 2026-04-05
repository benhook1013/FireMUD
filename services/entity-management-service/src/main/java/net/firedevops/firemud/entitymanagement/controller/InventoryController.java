package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.entitymanagement.dto.AddInventoryItemRequest;
import net.firedevops.firemud.entitymanagement.dto.InventoryEntryDto;
import net.firedevops.firemud.entitymanagement.service.InventoryService;
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
      @PathVariable Long tenantId, @PathVariable Long characterId, Pageable pageable) {
    Page<InventoryEntryDto> list = inventoryService.listInventory(tenantId, characterId, pageable);
    return ResponseEntity.ok(ApiResponse.success(list));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<InventoryEntryDto>> addItem(
      @PathVariable Long tenantId,
      @PathVariable Long characterId,
      @Valid @RequestBody AddInventoryItemRequest request) {
    InventoryEntryDto dto =
        inventoryService.addItem(tenantId, characterId, request.itemId(), request.quantity());
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @DeleteMapping("/{itemId}")
  public ResponseEntity<ApiResponse<Void>> remove(
      @PathVariable Long tenantId, @PathVariable Long characterId, @PathVariable Long itemId) {
    inventoryService.removeItem(tenantId, characterId, itemId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
