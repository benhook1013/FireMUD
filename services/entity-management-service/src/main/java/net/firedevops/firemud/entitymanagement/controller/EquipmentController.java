package net.firedevops.firemud.entitymanagement.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.security.SessionContext;
import net.firedevops.firemud.entitymanagement.dto.CharacterEquipmentEntryDto;
import net.firedevops.firemud.entitymanagement.dto.WearEquipmentItemRequest;
import net.firedevops.firemud.entitymanagement.service.EquipmentService;
import net.firedevops.firemud.entitymanagement.v1.PlayableStateScope;
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
          Page<CharacterEquipmentEntryDto> list =
              equipmentService.listEquipment(
                  scope.tenantId(),
                  scope.characterId(),
                  gameInstanceId,
                  playableStateScope,
                  pageable);
          return ResponseEntity.ok(ApiResponse.success(list));
        });
  }

  @PostMapping
  public ResponseEntity<ApiResponse<CharacterEquipmentEntryDto>> wear(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope,
      @Valid @RequestBody WearEquipmentItemRequest request) {
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
          SessionContext.requireTenantAccess(scope.tenantId());
          CharacterEquipmentEntryDto dto =
              equipmentService.wearItem(
                  scope.tenantId(),
                  scope.characterId(),
                  gameInstanceId,
                  playableStateScope,
                  request.itemId(),
                  null);
          return ResponseEntity.ok(ApiResponse.success(dto));
        });
  }

  @DeleteMapping("/{slot}")
  public ResponseEntity<ApiResponse<CharacterEquipmentEntryDto>> remove(
      @PathVariable String tenantId,
      @PathVariable String characterId,
      @PathVariable String slot,
      @RequestParam String gameInstanceId,
      @RequestParam PlayableStateScope playableStateScope) {
    return EntityManagementRequestReaders.withBadRequest(
        () -> {
          EntityManagementRequestReaders.CharacterScope scope =
              EntityManagementRequestReaders.requireCharacterScope(tenantId, characterId);
          SessionContext.requireTenantAccess(scope.tenantId());
          CharacterEquipmentEntryDto dto =
              equipmentService.removeWornItem(
                  scope.tenantId(), scope.characterId(), gameInstanceId, playableStateScope, slot);
          return ResponseEntity.ok(ApiResponse.success(dto));
        });
  }
}
