package net.firedevops.firemud.entitymanagement.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.entitymanagement.dto.CraftingRecipeDto;
import net.firedevops.firemud.entitymanagement.service.CraftingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** REST endpoints for crafting recipes. */
@RestController
@RequestMapping("/crafting/recipes")
@RequiredArgsConstructor
public class CraftingController {
  private final CraftingService craftingService;

  @PostMapping
  public ResponseEntity<ApiResponse<CraftingRecipeDto>> create(
      @Valid @RequestBody CraftingRecipeDto dto) {
    CraftingRecipeDto result = craftingService.createRecipe(dto);
    return ResponseEntity.ok(ApiResponse.success(result));
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<CraftingRecipeDto>> get(@PathVariable String id) {
    return EntityManagementRequestReaders.withBadRequest(
        () ->
            ResponseEntity.ok(
                ApiResponse.success(
                    craftingService.getRecipe(
                        EntityManagementRequestReaders.requirePositivePathId(id, "id")))));
  }
}
