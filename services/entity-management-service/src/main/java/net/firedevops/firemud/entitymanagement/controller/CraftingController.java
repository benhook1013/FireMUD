package net.firedevops.firemud.entitymanagement.controller;

import jakarta.validation.Valid;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.common.ErrorDetail;
import net.firedevops.firemud.entitymanagement.dto.CraftingRecipeDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Fail-closed legacy crafting routes until tenant-bound owner authorization is implemented. */
@RestController
@RequestMapping("/crafting/recipes")
public class CraftingController {
  @PostMapping
  public ResponseEntity<ApiResponse<CraftingRecipeDto>> create(
      @Valid @RequestBody CraftingRecipeDto dto) {
    return unavailable();
  }

  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<CraftingRecipeDto>> get(@PathVariable String id) {
    return unavailable();
  }

  private ResponseEntity<ApiResponse<CraftingRecipeDto>> unavailable() {
    return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
        .body(
            ApiResponse.error(
                new ErrorDetail(
                    "CRAFTING_UNAVAILABLE", "Crafting requires tenant-bound owner authorization")));
  }
}
