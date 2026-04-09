package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST endpoints for managing equipped character items. */
@RestController
@RequestMapping("/tenants/{tenantId}/characters/{characterId}/equipment")
@RequiredArgsConstructor
public class EquipmentController {
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "Spring injects EquipmentService; storing reference is safe")
  private final EquipmentService equipmentService;

  @GetMapping
  public ResponseEntity<ApiResponse<Page<CharacterEquipmentEntryDto>>> list(
      @PathVariable Long tenantId, @PathVariable Long characterId, Pageable pageable) {
    SessionContext.requireTenantAccess(tenantId);
    Page<CharacterEquipmentEntryDto> list =
        equipmentService.listEquipment(tenantId, characterId, pageable);
    return ResponseEntity.ok(ApiResponse.success(list));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CharacterEquipmentEntryDto>> wear(
      @PathVariable Long tenantId,
      @PathVariable Long characterId,
      @Valid @RequestBody WearEquipmentItemRequest request) {
    SessionContext.requireTenantAccess(tenantId);
    CharacterEquipmentEntryDto dto =
        equipmentService.wearItem(tenantId, characterId, request.itemId(), null);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }

  @DeleteMapping("/{slot}")
  public ResponseEntity<ApiResponse<CharacterEquipmentEntryDto>> remove(
      @PathVariable Long tenantId, @PathVariable Long characterId, @PathVariable String slot) {
    SessionContext.requireTenantAccess(tenantId);
    CharacterEquipmentEntryDto dto = equipmentService.removeWornItem(tenantId, characterId, slot);
    return ResponseEntity.ok(ApiResponse.success(dto));
  }
}
