package net.firedevops.firemud.entitymanagement.controller;

import lombok.RequiredArgsConstructor;
import net.firedevops.firemud.common.ApiResponse;
import net.firedevops.firemud.entitymanagement.dto.CharacterDto;
import net.firedevops.firemud.entitymanagement.service.CharacterService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST endpoints for listing characters by account. */
@RestController
@RequestMapping("/accounts/{accountId}/characters")
@RequiredArgsConstructor
public class CharacterController {
  private final CharacterService characterService;

  @GetMapping
  public ResponseEntity<ApiResponse<Page<CharacterDto>>> list(
      @PathVariable Long accountId, Pageable pageable) {
    Page<CharacterDto> list = characterService.listForAccount(accountId, pageable);
    return ResponseEntity.ok(ApiResponse.success(list));
  }
}
