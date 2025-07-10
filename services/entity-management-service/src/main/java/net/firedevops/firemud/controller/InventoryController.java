package net.firedevops.firemud.controller;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.dto.AddInventoryItemRequest;
import net.firedevops.firemud.dto.InventoryEntryDto;
import net.firedevops.firemud.service.InventoryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST endpoints for managing character inventories. */
@RestController
@RequestMapping("/characters/{characterId}/inventory")
@RequiredArgsConstructor
public class InventoryController {
  private final InventoryService inventoryService;

  @GetMapping
  public ResponseEntity<ApiResponse<List<InventoryEntryDto>>> list(@PathVariable Long characterId) {
    List<InventoryEntryDto> list = inventoryService.listInventory(characterId);
    return ResponseEntity.ok(ApiResponse.success(list));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<InventoryEntryDto>> addItem(
      @PathVariable Long characterId, @Valid @RequestBody AddInventoryItemRequest request) {
    InventoryEntryDto dto =
        inventoryService.addItem(characterId, request.itemId(), request.quantity());
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @DeleteMapping("/{itemId}")
  public ResponseEntity<ApiResponse<Void>> remove(
      @PathVariable Long characterId, @PathVariable Long itemId) {
    inventoryService.removeItem(characterId, itemId);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
